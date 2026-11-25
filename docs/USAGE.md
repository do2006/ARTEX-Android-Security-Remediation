# Installation and usage

## Install the app

There is no verified public release APK yet. Build and sign it using [BUILD.md](BUILD.md), or use an APK provided by a publisher you trust. A new APK must be signed with the same key as an existing installation to update it without uninstalling. Uninstalling may erase app data, so do not do that as a routine troubleshooting step.

## First launch

The app extracts its PostgreSQL, Python, and shell runtime, initializes the database on first launch, starts the local backend, and opens the ARTEX web interface. Initial setup can take a few minutes. Keep the app in the foreground and the device awake during first initialization. If startup appears stalled, wait several minutes and review the startup log before force-stopping the app.

The service is reachable only from the app's WebView at `http://127.0.0.1:8787`. LAN and remote access are disabled.

## Navigation

- Swipe left with two fingers to open the right-side panel for backups, restore, tools, and skills.
- Swipe right with two fingers to open the ARTEX web sidebar.
- The Android Back button navigates the web page first. Press it again at the page's start to leave the app.
- Ordinary one-finger scrolling, tapping, and table navigation are handled by the web interface.

## Install tools

Standalone catalog tools are user-selected and downloaded from the pinned GitHub release route. Each download is restricted to approved HTTPS hosts and checked against its catalog SHA-256 before import.

The Python wheelhouse is included in the APK. To install Impacket, NetExec (`nxc`), pypykatz, and LaZagne, open the right-side panel, select **Install Python tools**, and confirm. This operation is offline, checks the lockfile and pinned wheel hashes, and stages the package tree before switching it into place. Allow several minutes and at least 200 MB of free app storage. The ARTEX backend restarts afterward to reload the Python path.

The packages install in the x86_64 emulator, but two command paths are currently known to fail under its ARM64 translation layer: `nxc` encounters a native `netifaces` import error and `pypykatz` cannot load OpenSSL's legacy provider. Do not rely on those tools in that environment. Native ARM64 device validation is still outstanding. See [PYTHON_TOOL_WHEELHOUSE.md](PYTHON_TOOL_WHEELHOUSE.md).

Standalone tools and Python package installation do not guarantee every protocol or feature is compatible with Android. Do not execute manually imported binaries unless their source and provenance have been reviewed.

## Back up and restore

Backups are stored in app-private storage and are not encrypted or signed. Do not upload or share them publicly. The app supports full and reduced backups; the full archive may exceed 200 MB.

Restore accepts version-2 backups created by this hardened app. It parses the manifest, validates archive paths and expansion limits, and checks each declared file's SHA-256 before applying the restore. Legacy backups without the verified manifest are rejected; create a fresh v2 backup on the source device before migrating. Restore stages the existing data and attempts rollback if replacement fails; a rollback failure is reported in the app log.

The v2 manifest detects accidental corruption but is not an authenticity signature. Backups remain unencrypted. Use a trusted private transfer method and keep a separate copy until the destination restore is confirmed.

## Known limitations

- ARTEX runs inside Android's application sandbox. It cannot access other apps' private data or obtain desktop/system privileges.
- LaZagne cannot read another app's private credential store; Android does not provide desktop Secret Service integration.
- pypykatz can parse a dump supplied by the user, but it cannot access live Windows LSASS from Android.
- NetExec and Impacket protocols may require privileges, kernel interfaces, or system services unavailable to an Android app.
- The backend update control is not supported by this wrapper because the upstream update flow expects a process supervisor that Android does not provide.
- There is no supported PC or LAN client mode.

## Troubleshooting

### Startup does not finish

Wait through first database initialization. If it continues to fail, save the in-app startup log and include the Android version and device ABI when reporting the issue. Do not delete app data before preserving backups.

### A downloaded standalone tool is unavailable

Open the Tools panel and run its availability check. A failure can indicate an unsupported CPU format, a glibc dependency not present on Android, or a missing executable permission. Only install catalog files that pass the SHA-256 check.

### Python tools install but a command fails

Check the in-app log and the platform limitations above. On x86_64 translation, `nxc` and `pypykatz` have known native-runtime failures. A successful wheel install is not proof that a command's features work. Report the exact command and traceback; do not use it against a target while it is in a failed or partially supported state.

### Moving to another phone

Create a v2 backup on the source device, transfer it privately, install the app on the destination, let first startup finish, then select the archive from the Restore panel. The archive is not encrypted; keep it private and retain the original until restore is confirmed.

### Offline use

The core local service can start offline after initial setup. Catalog tools and skills require a network connection to download. Python wheels are included in the APK and install offline after the user confirms.

### Logs

Startup logs can be viewed in the failure screen. The app also writes a diagnostic log under its external app-specific files directory (`Android/data/io.nightfall.artex/files/`). Avoid posting logs publicly before checking for sensitive paths or data.
