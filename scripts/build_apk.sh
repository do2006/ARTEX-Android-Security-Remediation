#!/bin/sh
# 组装 / 打包 / 签名 ARTEX Android APK
#
# 前置：
#   assets/backend/artex  由 prepare_backend.sh 产出
#   assets/pg/            由 prepare_postgres.sh 产出
#   keys/artex.keystore   自己的签名密钥
#
# 思路：aapt 只处理 manifest + res（秒级），assets 与 dex 用 Python 直写 zip。
# 这样避开在 arm64 上用 qemu 跑 aapt 处理数百 MB assets 的漫长耗时。
#
# 环境变量：
#   ANDROID_SDK / WORK / ARTEX_KEYSTORE / ARTEX_KS_PASS / ARTEX_KEY_PASS
set -e

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK:-/opt/android-sdk}"
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
WORK="${WORK:-/tmp/artex-build}"
OUT="${1:-$REPO/dist/ARTEX-Mobile.apk}"
ASSETS="$REPO/assets"
KEYSTORE="${ARTEX_KEYSTORE:-$REPO/keys/artex.keystore}"
KS_PASS="${ARTEX_KS_PASS:?Set ARTEX_KS_PASS to the release keystore password}"
KEY_PASS="${ARTEX_KEY_PASS:?Set ARTEX_KEY_PASS to the release key password}"

[ -d "$SDK" ] || { echo "错误：找不到 Android SDK：$SDK" >&2; exit 1; }
[ -f "$ASSETS/backend/artex" ] || { echo "错误：缺少 $ASSETS/backend/artex（先跑 prepare_backend.sh）" >&2; exit 1; }
[ -d "$ASSETS/pg" ] || { echo "错误：缺少 $ASSETS/pg（先跑 prepare_postgres.sh）" >&2; exit 1; }
[ -f "$KEYSTORE" ] || {
  echo "错误：找不到签名密钥 $KEYSTORE" >&2
  echo "可用以下命令生成：" >&2
  echo "  Generate a release keystore offline with a strong unique password; supply it through ARTEX_KS_PASS and ARTEX_KEY_PASS." >&2
  exit 1
}

mkdir -p "$WORK" "$(dirname "$OUT")"

# 在 arm64 主机上用 qemu 跑 x86 版 aapt；x86 主机直接跑
ARCH="$(uname -m)"
if [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then
  AAPT="${QEMU_BIN:-/usr/bin/qemu-x86_64} $BT/aapt"
  export QEMU_LD_PREFIX="${QEMU_LD_PREFIX:-/tmp/debian-root}"
else
  AAPT="$BT/aapt"
fi

MANIFEST="$REPO/app/AndroidManifest.xml"
RES="$REPO/app/res"
SRC="$REPO/app/src"

echo "[1/5] aapt：manifest + res"
rm -f "$WORK/base-slim.apk"
$AAPT package -f -M "$MANIFEST" -S "$RES" -I "$PLATFORM" -F "$WORK/base-slim.apk"

echo "[2/5] 生成 R.java 并编译 dex"
rm -rf "$WORK/rgen" "$WORK/classes" "$WORK/dex"
mkdir -p "$WORK/rgen" "$WORK/classes" "$WORK/dex"
$AAPT package -f -m -J "$WORK/rgen" -M "$MANIFEST" -S "$RES" -I "$PLATFORM"
# zxing-core.jar 供 ScanActivity 解二维码（桌面长按 → 扫描）
ZXING="$REPO/libs/zxing-core.jar"
[ -f "$ZXING" ] || { echo "错误：缺少 $ZXING（扫码功能依赖）" >&2; exit 1; }
javac -source 11 -target 11 -classpath "$PLATFORM:$ZXING" \
  -d "$WORK/classes" "$WORK/rgen/com/artex/mobile/R.java" "$SRC"/*.java
"$BT/d8" --release --lib "$PLATFORM" --min-api 24 \
  --output "$WORK/dex" "$WORK/classes/com/artex/mobile"/*.class "$ZXING"

echo "[3/5] 组装 APK（aapt 产物 + assets + dex）"
WORK="$WORK" ASSETS="$ASSETS" python3 - << 'PY'
import os, zipfile
work = os.environ['WORK']
assets = os.environ['ASSETS']
out = zipfile.ZipFile(os.path.join(work, 'full.apk'), 'w', zipfile.ZIP_DEFLATED)
src = zipfile.ZipFile(os.path.join(work, 'base-slim.apk'))
for item in src.infolist():
    out.writestr(item, src.read(item.filename))
src.close()
for dirpath, _, files in os.walk(assets):
    for fn in files:
        full = os.path.join(dirpath, fn)
        rel = os.path.relpath(full, assets)
        out.writestr('assets/' + rel, open(full, 'rb').read())
out.writestr('classes.dex', open(os.path.join(work, 'dex', 'classes.dex'), 'rb').read())
out.close()
print('  组装完成 %.1f MB' % (os.path.getsize(os.path.join(work, 'full.apk')) / 1024 / 1024))
PY

echo "[3.5/5] 修复图标 PNG 模式（aapt 会把 RGBA 转成 P 调色板，Android 通知图标会拒绝）"
WORK="$WORK" RES="$RES" python3 - << 'PY'
import os, zipfile
work, res = os.environ['WORK'], os.environ['RES']
targets = {}
for dpi in ['mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi']:
    targets['res/drawable-%s-v4/ic_notify.png' % dpi] = os.path.join(res, 'drawable-%s' % dpi, 'ic_notify.png')
targets['res/drawable-nodpi-v4/ic_notify.png'] = os.path.join(res, 'drawable-nodpi', 'ic_notify.png')

full = os.path.join(work, 'full.apk')
fixed = os.path.join(work, 'full-fixed.apk')
src = zipfile.ZipFile(full, 'r')
out = zipfile.ZipFile(fixed, 'w', zipfile.ZIP_DEFLATED)
n = 0
for item in src.infolist():
    p = targets.get(item.filename)
    if p and os.path.exists(p):
        out.writestr(item, open(p, 'rb').read())
        n += 1
    else:
        out.writestr(item, src.read(item.filename))
src.close()
out.close()
os.replace(fixed, full)
print('  修复 %d 个图标为 RGBA' % n)
PY

echo "[4/6] zipalign"
"$BT/zipalign" -p -f 4 "$WORK/full.apk" "$WORK/full-aligned.apk"

echo "[5/6] 签名"
export ARTEX_KS_PASS ARTEX_KEY_PASS
java -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KEYSTORE" --ks-pass "env:ARTEX_KS_PASS" --key-pass "env:ARTEX_KEY_PASS" \
  --min-sdk-version 24 --out "$OUT" "$WORK/full-aligned.apk"

echo "[6/6] 验证"
"$BT/zipalign" -c -v 4 "$OUT"
java -jar "$BT/lib/apksigner.jar" verify --min-sdk-version 24 "$OUT" | head -2
ls -la "$OUT"
echo "完成：$OUT"
