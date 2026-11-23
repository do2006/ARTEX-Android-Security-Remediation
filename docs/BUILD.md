# 自己构建

本仓库**已包含全部 assets**（后端二进制、PostgreSQL、Python、bash），
clone 下来直接构建即可，不需要跑 prepare 脚本。

```sh
git clone https://github.com/do2006/ARTEX-Android-Security-Remediation.git
cd ARTEX-Android-Security-Remediation

# 生成签名密钥（只需一次，务必离线备份）
mkdir -p keys
keytool -genkeypair -keystore keys/artex.keystore -alias artex \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=ARTEX"

# 构建；在受控 shell 中提供强且唯一的密码，绝不写进命令历史或仓库
export ARTEX_KS_PASS='set-a-strong-unique-keystore-password'
export ARTEX_KEY_PASS='set-a-strong-unique-key-password'
ANDROID_SDK=/opt/android-sdk sh scripts/build_apk.sh dist/ARTEX.apk
```

产物约 60MB，`dist/ARTEX.apk`。

## 发布前检查

构建脚本只验证 APK 签名；发布者还应在一台受控 Android 8.0+ 设备上完成以下检查后再创建 GitHub Release：

1. 安装自己刚构建的 APK：`adb install -r dist/ARTEX.apk`。
2. 首次启动并等待数据库初始化完成，确认 WebView 能加载本机服务。
3. 创建一个小型 v2 备份，再恢复它，确认恢复后的数据可用。
4. 从面板下载一个所需工具，确认 SHA-256 校验成功；不要执行来源不明的手动导入二进制。
5. 记录 APK 的 SHA-256、签名证书指纹和已测试的设备/Android 版本到 Release 说明。

后端已有一次来自固定上游提交的 Android arm64 重建记录，见 [REPRODUCIBLE_INPUTS.md](REPRODUCIBLE_INPUTS.md)。PostgreSQL 资产仍没有完整、可复现的依赖来源证明。每个发布者都必须自行评估这些资产，并且不得把源码编译或签名成功表述为完整运行时安全认证。

---

## 依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| Android SDK | `build-tools/36.0.0` + `platforms/android-34` | 打包签名，默认 `/opt/android-sdk` |
| JDK | 11+ | 编译 Java 壳 |
| Python 3 | 任意 | 组装 zip |
| aapt / zipalign / apksigner | 随 build-tools | 由 SDK 提供 |

**不需要** Go、Node.js —— 后端二进制和前端已经编译好放在 `assets/` 里了。

只有想更新后端时才需要它们，见最后的「重新编译后端」。

### arm64 主机上的 aapt

如果在 ARM 机器上构建，而 SDK 里的 `aapt` 是 x86 版，需要 `qemu-x86_64`：

```sh
sudo apt install qemu-user-static
```

`build_apk.sh` 会自动检测并套上。

---

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `ANDROID_SDK` | `/opt/android-sdk` | SDK 路径 |
| `WORK` | `/tmp/artex-build` | 中间产物目录 |
| `ARTEX_KEYSTORE` | `keys/artex.keystore` | 签名密钥 |
| `ARTEX_KS_PASS` | 必填 | keystore 密码；通过 `apksigner env:` 引用，不出现在进程参数中 |
| `ARTEX_KEY_PASS` | 必填 | key 密码；通过 `apksigner env:` 引用，不出现在进程参数中 |

例：

```sh
export ARTEX_KS_PASS='strong-unique-keystore-password'
export ARTEX_KEY_PASS='strong-unique-key-password'
ANDROID_SDK=/opt/sdk ARTEX_KEYSTORE=~/my.jks sh scripts/build_apk.sh out.apk
```

---

## 构建做了什么

`build_apk.sh` 是五步：

```
[1/5] 编译 Java 壳 → classes.dex
[2/5] aapt 处理 manifest + res → base.apk（秒级）
[3/5] Python 直写 zip，塞入 assets 和 dex（分钟级）
[4/5] 签名
[5/5] 验证
```

### 为什么用 Python 写 zip

`aapt` 处理 154MB assets 极慢（尤其 ARM 上套 qemu 时）。所以拆开：

- **aapt 只碰 manifest 和 res**（小，秒级）
- **assets 和 dex 用 Python 的 `zipfile` 直写**

### 为什么签名后要覆盖图标

`aapt` 会把 PNG 从 **RGBA 转成 P 调色板**（省体积）。但 Android 的通知
小图标必须是 RGBA，否则系统**拒绝加载并回退成默认图标**。

`build_apk.sh` 在签名后会把原始 RGBA 的 `ic_notify.png` 覆盖回去。

---

## 重新编译后端

只在改上游代码时才需要。

```sh
git clone https://github.com/RuoJi6/ARTEX.git
ARTEX_SRC=./ARTEX ./scripts/prepare_backend.sh
```

需要 Go 1.26+ 和 Node.js（构建前端）。产物写到 `assets/backend/artex`。

**关键点**：

```sh
GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
  go build -buildmode=pie -tags embedui ...
```

- `GOOS=android` 生成 PIE —— **非 PIE 会被 Android 静默拒绝执行**
- `embedui` 标签把前端静态资源嵌进同一个二进制

### 重新准备 PostgreSQL

一般不需要。只有升级 PG 版本时：

```sh
# 准备一份 Termux 的 usr 目录
TERMUX_USR=/tmp/termux-usr ./scripts/prepare_postgres.sh
```

脚本会做三件事：提取 aarch64 二进制、按长度降序做路径等长替换、
补齐 Android 专用的 `.so`。

**路径补丁的原理**见 [ADAPTATION.md 第 4 节](ADAPTATION.md#4-目录布局)。

---

## 开发时改什么

| 想改 | 文件 |
|---|---|
| 启动流程、服务看护 | `app/src/ArtExService.java` |
| 主界面、手势、页面注入 | `app/src/MainActivity.java` |
| 右侧面板 | `app/src/PanelActivity.java` |
| 启动控制台 | `app/src/BootConsole.java` |
| 通知 | `app/src/Notifier.java` |
| 备份 / 恢复 | `BackupManager.java` / `RestoreManager.java` |
| 工具导入与探测 | `ToolImporter.java` |
| 工具下载 | `ToolCatalog.java` |
| Skill 管理 | `SkillCatalog.java` |
| 扫码 | `ScanActivity.java` |

### 改完怎么验证

```sh
# 编译（不打包，快）
javac -source 8 -target 8 -bootclasspath $ANDROID_SDK/platforms/android-34/android.jar \
      -d /tmp/cls app/src/*.java

# 完整构建并装机
sh scripts/build_apk.sh dist/test.apk
adb install -r dist/test.apk
```

### 版本号

`app/AndroidManifest.xml`：

```xml
android:versionCode="335"          <!-- 每次必须递增，否则装不上 -->
android:versionName="1.9.3-green"
```

### 改了 assets 里的东西

`ArtExService.ASSETS_VERSION` 要递增，否则老机器上不会重新释放：

```java
private static final int ASSETS_VERSION = 8;
```

---

## 常见构建错误

**`找不到 Android SDK`**

设 `ANDROID_SDK=`，或确认 `/opt/android-sdk/build-tools/36.0.0` 存在。

**`找不到签名密钥`**

按上面的 keytool 命令生成，并设置两个密码环境变量。不要在命令行参数、仓库、截图或发布日志中写入密码。

**`aapt: not found` / `Exec format error`**

ARM 主机上需要 `qemu-user-static`。

**`javac: invalid target release`**

需要 JDK 11+。

**装到手机上提示「应用未安装」**

- 版本号没递增（装了同版本或更低版本）
- 签名不一致（换过 keystore，必须先卸载旧版）

**装上了但启动卡住**

看 `adb logcat | grep -i artex`，或应用内的失败日志页。
