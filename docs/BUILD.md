# Build and sign the APK

This repository includes the Android wrapper sources and bundled runtime assets. A successful build or signature check is not evidence that the backend, database, Python tools, or device behavior has been runtime-tested. Current runtime evidence and gaps are tracked in [PYTHON_TOOL_WHEELHOUSE.md](PYTHON_TOOL_WHEELHOUSE.md) and [SECURITY_REMEDIATION.md](SECURITY_REMEDIATION.md).

## Requirements

- JDK 11 or newer.
- Android SDK platform `android-34` and build-tools `36.0.0`.
- Python 3 for APK assembly.
- Git Bash on Windows, or a POSIX shell on Linux/macOS.

The backend and frontend are already embedded. Updating the upstream backend requires Go and Node.js; do not run the preparation scripts unless you have reviewed their source and inputs.

## Clone and create a signing key

```sh
git clone https://github.com/do2006/ARTEX-Android-Security-Remediation.git
cd ARTEX-Android-Security-Remediation

mkdir -p keys
keytool -genkeypair -keystore keys/artex.keystore -alias artex \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=NightFall Artex"
```

Keep the keystore and its recovery copy private and backed up. Do not commit it. If the release key is lost, Android will not accept a differently signed APK as an update to existing users' installation.

## Build

Set the passwords in the build environment without putting them in command-line arguments, shell history, source files, or logs. The build uses apksigner's `env:` password references.

```sh
export ANDROID_SDK="$HOME/Android/Sdk"
read -rsp "Keystore password: " ARTEX_KS_PASS; printf '\n'
export ARTEX_KS_PASS
read -rsp "Signing key password: " ARTEX_KEY_PASS; printf '\n'
export ARTEX_KEY_PASS
sh scripts/build_apk.sh dist/NightFall-Artex.apk
```

On Windows, use Git Bash, set `ANDROID_SDK` to the SDK path in Git Bash form (for example `/e/Android/Sdk`), and set `PYTHON` to the Git Bash-compatible path of `python.exe` if it is not found automatically. The script compiles the Java wrapper, packages resources and assets, signs the APK, and verifies the signature. Review `scripts/build_apk.sh` before running it in your environment.

The test APK produced in this workspace used a temporary local certificate. It is not an official release artifact. No release APK has been built from the current source revision as of this documentation update. A Firebase Test Lab Robo run of this test APK on an ARM virtual device (Android 14/API 34) passed, but its crawl timed out after 4m 59s; this does not verify Python tool installation or tool commands.

## Test safely before release

Do not install over an existing app unless the signing key matches and you have a verified backup. Prefer a clean test device or emulator with test data. Test ARM64-native devices for the intended runtime; the x86_64 emulator currently has known failures in translated native Python extensions. See the runtime test record for exact outcomes.

Before publishing a release, verify at least:

1. Confirm runtime extraction, PostgreSQL initialization, backend startup, and WebView loading. A Firebase Robo test passed on a Medium Phone ARM virtual device with Android 14/API 34, but timed out during its crawl and does not establish these service checks. An x86_64 emulator with ARM translation is not an equivalent test.
2. Exercise the backup and restore round trip, including malformed archives and failed rollback behavior.
3. Download a catalog tool and verify redirect restrictions and SHA-256 handling.
4. Install the four Python tools from the bundled wheelhouse, run each command's harmless version/help path, and exercise representative features on the device.
5. Confirm the loopback-only service remains inaccessible from another LAN device.
6. Record the APK SHA-256, signing certificate fingerprint, tested device models, Android versions, ABI, and results in the release notes.

Do not describe a source compile, unit test, or APK signature check as device validation or security certification. Binary provenance gaps are recorded in [REPRODUCIBLE_INPUTS.md](REPRODUCIBLE_INPUTS.md).

## Build inputs and limitations

- Android SDK platform `android-34`; build-tools `36.0.0`.
- JDK 11 or newer.
- Python 3 for APK assembly.
- The packaged PostgreSQL and other native assets still need a complete reproducible dependency and provenance record.
- The Android ARM64 Python wheelhouse contains 96 hash-pinned wheels. Cross-environment installation passed. A Robo test passed on an ARM virtual device, but native ARM64 Python tool installation and feature validation remain outstanding.
- x86_64 ARM translation is not a release-supported substitute for an ARM64 test: known `nxc`, `pypykatz`, lxml, and Pillow native load failures remain. Firebase Test Lab documents no-cost virtual test quotas; check the [current quotas and pricing](https://firebase.google.com/docs/test-lab/usage-quotas-pricing) before running cloud tests.

## Useful source locations

| Area | Source |
|---|---|
| Startup and local services | `app/src/ArtExService.java` |
| Main screen and WebView | `app/src/MainActivity.java` |
| Side panel | `app/src/PanelActivity.java` |
| Backup and restore | `app/src/BackupManager.java`, `app/src/RestoreManager.java` |
| Catalog and tool downloads | `app/src/ToolCatalog.java`, `app/src/SkillCatalog.java` |
| Offline Python installation | `app/src/PythonToolsInstaller.java` |
| Security remediation notes | `docs/SECURITY_REMEDIATION.md` |

When changing extracted runtime assets, increment `ArtExService.ASSETS_VERSION` so existing installations re-extract the updated files.
