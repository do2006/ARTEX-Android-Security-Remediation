#!/usr/bin/env python3
"""Relocate ARM64 shared-library RUNPATHs and refresh wheel integrity data.

Python and Bash libraries are extracted at the app data root's ``py`` path.
The same relocation is applied to the ELF payloads inside the locked wheels.
Run this only on reviewed assets before building an APK.
"""
from __future__ import annotations

import base64
import csv
import hashlib
import io
import re
import sys
import zipfile
from pathlib import Path


ASSET_OLD = b"/data/user/0/io.nightfall.artex/lib"
APP_PY = b"/data/user/0/io.nightfall.artex/py"
WHEEL_OLD = b"/data/data/com.termux/files/usr/lib"


def replace_path(data: bytes, old: bytes, new: bytes) -> tuple[bytes, int]:
    if not data.startswith(b"\x7fELF") or old not in data:
        return data, 0
    if len(new) > len(old):
        raise ValueError("replacement path does not fit in the existing ELF string")
    count = data.count(old)
    return data.replace(old, new + b"\0" * (len(old) - len(new))), count


def patch_asset_elfs(root: Path) -> int:
    changed = 0
    for subtree in (root / "assets" / "python", root / "assets" / "bash"):
        for path in subtree.rglob("*"):
            if not path.is_file():
                continue
            data, count = replace_path(path.read_bytes(), ASSET_OLD, APP_PY)
            data, extra = replace_path(data, WHEEL_OLD, APP_PY)
            count += extra
            if count:
                path.write_bytes(data)
                changed += 1
    return changed


def normalized_name(value: str) -> str:
    return re.sub(r"[-_.]+", "-", value).lower()


def patch_wheel(path: Path) -> bool:
    with zipfile.ZipFile(path, "r") as source:
        infos = source.infolist()
        payloads = {info.filename: source.read(info.filename) for info in infos}
        comment = source.comment
    changed = set()
    for name, payload in payloads.items():
        if ".so" not in name:
            continue
        patched, count = replace_path(payload, WHEEL_OLD, APP_PY)
        if count:
            payloads[name] = patched
            changed.add(name)
    if not changed:
        return False

    records = [name for name in payloads if name.endswith(".dist-info/RECORD")]
    signatures = [name for name in payloads if name.endswith(("RECORD.jws", "RECORD.p7s"))]
    if len(records) != 1 or signatures:
        raise ValueError(f"unexpected wheel RECORD/signature structure: {path.name}")
    record = records[0]
    rows = list(csv.reader(io.StringIO(payloads[record].decode("utf-8"))))
    row_names = set()
    for row in rows:
        if not row:
            continue
        row_names.add(row[0])
        if row[0] in changed:
            digest = base64.urlsafe_b64encode(hashlib.sha256(payloads[row[0]]).digest()).decode("ascii").rstrip("=")
            row[1] = "sha256=" + digest
            row[2] = str(len(payloads[row[0]]))
    if changed - row_names:
        raise ValueError(f"wheel RECORD omits patched files: {path.name}")
    for row in rows:
        if row and row[0] == record:
            row[1:] = ["", ""]
    stream = io.StringIO(newline="")
    csv.writer(stream, lineterminator="\n").writerows(rows)
    payloads[record] = stream.getvalue().encode("utf-8")

    temporary = path.with_suffix(path.suffix + ".tmp")
    with zipfile.ZipFile(temporary, "w") as target:
        target.comment = comment
        for info in infos:
            target.writestr(info, payloads[info.filename])
    temporary.replace(path)
    return True


def refresh_lock(root: Path) -> str:
    wheelhouse = root / "assets" / "python-tools" / "wheelhouse"
    lock = root / "assets" / "python-tools" / "requirements.lock"
    by_requirement = {}
    for wheel in wheelhouse.glob("*.whl"):
        with zipfile.ZipFile(wheel) as archive:
            metadata_name = next(name for name in archive.namelist()
                                 if name.endswith(".dist-info/METADATA"))
            metadata = archive.read(metadata_name).decode("utf-8", "replace")
        name = re.search(r"(?m)^Name: (.+)$", metadata)
        version = re.search(r"(?m)^Version: (.+)$", metadata)
        if not name or not version:
            raise ValueError(f"wheel has incomplete metadata: {wheel.name}")
        key = (normalized_name(name.group(1).strip()), version.group(1).strip().lower())
        if key in by_requirement:
            raise ValueError(f"duplicate locked wheel: {key}")
        by_requirement[key] = hashlib.sha256(wheel.read_bytes()).hexdigest()

    lines = lock.read_text(encoding="utf-8").splitlines()
    seen = set()
    output = []
    for line in lines:
        match = re.match(r"^([A-Za-z0-9_.-]+)==([^ ]+)(.*)$", line)
        if not match:
            output.append(line)
            continue
        key = (normalized_name(match.group(1)), match.group(2).lower())
        if key not in by_requirement:
            raise ValueError(f"requirements lock has no corresponding wheel: {key}")
        seen.add(key)
        suffix = re.sub(r" --hash=sha256:[0-9a-fA-F]{64}", "", match.group(3))
        output.append(f"{match.group(1)}=={match.group(2)} --hash=sha256:{by_requirement[key]}{suffix}")
    if seen != set(by_requirement):
        raise ValueError(f"wheel/lock mismatch: {len(seen)} locked, {len(by_requirement)} wheels")
    lock.write_bytes(("\n".join(output) + "\n").encode("utf-8"))
    return hashlib.sha256(lock.read_bytes()).hexdigest()


def main() -> None:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
    assets_changed = patch_asset_elfs(root)
    wheels_changed = sum(patch_wheel(path)
                         for path in (root / "assets" / "python-tools" / "wheelhouse").glob("*.whl"))
    lock_hash = refresh_lock(root)
    print(f"Patched {assets_changed} runtime ELF files and {wheels_changed} wheels")
    print(f"requirements.lock SHA-256: {lock_hash}")


if __name__ == "__main__":
    main()
