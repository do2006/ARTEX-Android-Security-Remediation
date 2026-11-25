#!/usr/bin/env python3
"""Relocate bundled runtimes from the old ARTEX package root to NightFall Artex.

Both prefixes are exactly 31 bytes. This permits safe in-place replacement in
ELF and text assets without shifting binary contents.
"""
from pathlib import Path
import sys

OLD = b"/data/user/0/com.artex.mobile/p"
NEW = b"/data/user/0/io.nightfall.artex"
OLD_HOME = b"/data/user/0/com.artex.mobile/h"

if len(OLD) != len(NEW) or len(OLD_HOME) != len(NEW):
    raise SystemExit("old and new runtime paths must have equal byte lengths")


def main() -> None:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
    changed_files = 0
    replacements = 0
    remaining = []
    for asset_dir in (root / "assets" / "pg", root / "assets" / "python", root / "assets" / "bash"):
        if not asset_dir.is_dir():
            raise SystemExit(f"missing runtime asset directory: {asset_dir}")
        for path in asset_dir.rglob("*"):
            if not path.is_file():
                continue
            data = path.read_bytes()
            updated = data.replace(OLD, NEW).replace(OLD_HOME, NEW)
            count = data.count(OLD) + data.count(OLD_HOME)
            if count:
                path.write_bytes(updated)
                changed_files += 1
                replacements += count
            if OLD in updated or OLD_HOME in updated:
                remaining.append(str(path))
    print(f"Updated {replacements} embedded paths in {changed_files} files")
    if remaining:
        raise SystemExit("old runtime paths remain:\n" + "\n".join(remaining))
    print("No old runtime paths remain in PostgreSQL, Python, or bash assets")


if __name__ == "__main__":
    main()
