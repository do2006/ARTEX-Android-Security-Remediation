# Android adaptation notes

ARTEX was designed for Linux servers. Compiling its backend for Android is not enough: Android has a different process lifecycle, filesystem layout, DNS configuration, execution policy, and screen size. The Android wrapper adapts these boundaries in `app/src/`.

## Process lifecycle

`ArtExService` extracts runtime assets, initializes PostgreSQL on first launch, starts the database, starts the backend, and then opens the WebView on `http://127.0.0.1:8787`. It checks for service readiness instead of assuming a process is ready as soon as it starts. Child-process output is drained to avoid pipe-buffer deadlocks; stop timeouts retain process references and report failed shutdowns.

Android does not provide the server's `systemd`/shell-supervisor setup. The wrapper starts processes directly from a foreground service. The upstream self-update flow is therefore unsupported: its last step expects a supervisor to relaunch the backend.

## Process environment

The wrapper supplies app-private writable paths for `HOME` and `TMPDIR`, the extracted runtime and user-installed tools in `PATH`, and the bundled Python prefix in `PYTHONHOME`. After explicit installation, the ARTEX backend receives the staged Python tool site in `PYTHONPATH`. `SSL_CERT_FILE` points to the bundled CA file.

Android DNS servers are managed by the system rather than `/etc/resolv.conf`. The wrapper reads the active network's DNS configuration and passes it to the backend through `ARTEX_DNS`, filtering loopback addresses. Memory limits are set for the Go backend. The wrapper does not set a TOR or HTTP proxy; outbound networking uses the device's configured network.

ARM64 native libraries are supplied through `LD_LIBRARY_PATH` on native ARM64. On x86_64 Android translation, that variable is removed before launching ARM64 payloads because the translation runner itself is an x86_64 process and cannot load ARM64 libraries. This workaround allows the translated Python interpreter to start, but it does not make all native Python extensions compatible; see [PYTHON_TOOL_WHEELHOUSE.md](PYTHON_TOOL_WHEELHOUSE.md).

## Private directory layout

The installed application ID is `io.nightfall.artex`. PostgreSQL assets contain fixed-length package paths, so changing the application ID requires reviewing the runtime path assets and regenerating them with the documented preparation process. Do not change the manifest ID alone.

The app's private root is `/data/user/0/io.nightfall.artex`. PostgreSQL and Python runtime files are extracted under that root, while app data and the ARTEX backend live under `files/`:

```text
/data/user/0/io.nightfall.artex/
├── postgres, initdb, psql, bash, python3
├── lib/ and share/          PostgreSQL runtime libraries and data
├── py/                      Python prefix and Python/Bash shared libraries
└── files/
    ├── artex                ARTEX backend
    ├── tmp/                 temporary files and sockets
    └── artex/
        └── data/tools/      catalog tools and installed Python packages
```

`ArtExService.ASSETS_VERSION` must be incremented whenever extracted asset contents or runtime paths change so existing installations replace stale copies.

## Tool execution

The backend invokes tools through `bash -c`, so the wrapper adds the app-private tool directories to `PATH` and ensures imported catalog binaries are executable. Import checks inspect the ELF format and target architecture, reject the glibc dynamic loader, and run a bounded harmless version/help probe. These checks do not establish tool provenance beyond the checked download hash, and a successful probe does not validate every tool feature.

Android app sandboxing remains in effect. A tool cannot gain access to another app's private files, kernel facilities, or elevated privileges merely because ARTEX can launch it.

## WebView adaptation

The upstream frontend is desktop-oriented. `MainActivity` injects limited CSS and JavaScript adjustments for narrow screens, overflow, and WebView behavior. WebView file access is disabled, and navigation is restricted to the local ARTEX service. Cleartext networking is allowed only for the required loopback backend.

## Skills and backups

`SkillCatalog` uses the backend's HTTP API to upload, list, and delete skill archives rather than writing files behind the backend's back. Requests use the local WebView session token.

Android builds do not include a general-purpose `pg_dump` workflow, so backups copy the app's PostgreSQL data directory together with ARTEX data. Restore validates its version-2 manifest, archive paths, expansion limits, and per-file hashes before switching data. Legacy unverified backups are rejected. The manifest detects corruption but is not signed, and backup archives are not encrypted; do not share them publicly.

## Android execution compatibility

The current package is intended for 64-bit ARM Android. Android's x86_64 native-translation layer can run some ARM64 code, but the available emulator has known Python C-extension and CLI failures. It is suitable for limited smoke checks, not as evidence that all tools work. Complete native ARM64 device tests before publishing a release.
