#!/usr/bin/env python3
"""把 PostgreSQL 二进制中硬编码的 Termux 路径改写为应用私有目录路径。

约束：Android 应用的私有目录是 /data/user/0/<包名>，而 /data/data/<包名>
在部分 ROM 上因 SELinux 不可达，所以必须使用真实路径。

为了让"等长替换"成立，PG 安装在 filesDir 的父目录，也就是包的数据根目录：

    原：/data/data/com.termux/files/usr     （31 字符）
    新：/data/user/0/io.nightfall.artex     （31 字符）

两者恰好等长，替换后二进制长度不变，短出的部分用 \\0 补齐。

替换必须"完整路径优先、长度降序"，否则 /usr 会先把
/usr/share/postgresql 截断成 /share（丢失 postgresql 层）。
"""
import sys
import glob
import os

OLD = b"/data/data/com.termux/files"
NEW = b"/data/user/0/io.nightfall.artex"
LEGACY_ROOT = b"/data/user/0/com.artex.mobile/p"

PAIRS = [
    (OLD + b"/usr/share/postgresql",          NEW + b"/share/postgresql"),
    (OLD + b"/usr/share/doc//postgresql",     NEW + b"/share/doc//postgresql"),
    (OLD + b"/usr/share/locale",              NEW + b"/share/locale"),
    (OLD + b"/usr/share/man",                 NEW + b"/share/man"),
    (OLD + b"/usr/include/postgresql/server", NEW + b"/include/pgserver"),
    (OLD + b"/usr/include/postgresql",        NEW + b"/include/pg"),
    (OLD + b"/usr/lib/postgresql",            NEW + b"/lib/postgresql"),
    (OLD + b"/usr/etc/postgresql",            NEW + b"/etc/postgresql"),
    (OLD + b"/usr/bin/bash",                  NEW + b"/bin/bash"),
    (OLD + b"/usr/bin/login",                 NEW + b"/bin/login"),
    (OLD + b"/usr/lib",                       NEW + b"/lib"),
    (OLD + b"/usr/tmp",                       NEW + b"/tmp"),
    (OLD + b"/usr/bin",                       NEW + b"/bin"),
    (OLD + b"/usr/etc",                       NEW + b"/etc"),
    (OLD + b"/usr/include",                   NEW + b"/include"),
    (OLD + b"/usr",                           NEW),
    (OLD + b"/home",                          NEW),
]

# 新路径不能比原路径长，否则等长替换不成立
for old, new in PAIRS:
    if len(new) > len(old):
        raise SystemExit(
            "替换对超长：%s (%d) -> %s (%d)" % (old.decode(), len(old), new.decode(), len(new))
        )


def patch(path: str) -> int:
    with open(path, "rb") as f:
        data = f.read()
    count = data.count(LEGACY_ROOT)
    data = data.replace(LEGACY_ROOT, NEW)
    legacy_home = b"/data/user/0/com.artex.mobile/h"
    count += data.count(legacy_home)
    data = data.replace(legacy_home, NEW)
    for old, new in PAIRS:
        n = data.count(old)
        if n:
            data = data.replace(old, new + b"\x00" * (len(old) - len(new)))
            count += n
    if count:
        with open(path, "wb") as f:
            f.write(data)
    return count


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("用法：patch_pg_paths.py <pg 目录>")
    root = sys.argv[1]

    files = ["postgres", "initdb", "psql"]
    files = [os.path.join(root, f) for f in files if os.path.exists(os.path.join(root, f))]
    files += glob.glob(os.path.join(root, "lib", "*.so*"))
    files += glob.glob(os.path.join(root, "lib", "postgresql", "*.so"))

    total = 0
    for f in files:
        total += patch(f)

    # 残留检查
    leftover = []
    for f in files:
        with open(f, "rb") as fh:
            if b"com.termux" in fh.read():
                leftover.append(f)

    print("替换 %d 处 / %d 个文件" % (total, len(files)))
    if leftover:
        print("警告：以下文件仍含 com.termux：")
        for f in leftover:
            print("  " + f)
        raise SystemExit(1)
    print("无残留")


if __name__ == "__main__":
    main()
