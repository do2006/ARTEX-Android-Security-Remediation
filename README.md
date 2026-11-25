<div align="center">

# NightFall Artex for Android

**A local Android wrapper for ARTEX. The backend and database run on-device and listen only on loopback.**

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#requirements)

<img src="docs/images/banner-light.webp" alt="NightFall Artex for Android" width="100%">

</div>

## What it is

ARTEX is an AI-assisted security testing platform. This Android wrapper packages its backend, PostgreSQL, CPython, and shell runtime into an app. It does not require Docker or a separate server for its local service.

The service binds to `127.0.0.1:8787`; the app's WebView is the client. Network access is needed to download catalog tools and skills. Python tools are packaged as a hash-locked offline wheelhouse and are installed only after the user confirms in the app.

## Project status

This repository is a security-hardening work branch, not a certified release. Source-level Java regression checks pass. An x86_64 emulator can run the translated ARM64 CPython runtime and install the hash-locked Python wheels, but feature validation found failures in native Python extensions: NetExec (`nxc`) fails while importing `netifaces`, and `pypykatz` cannot load the OpenSSL legacy provider. Therefore the four Python tools are **not certified functional on x86_64 translation**. Their behavior on a native ARM64 Android phone still needs verification.

The current changes have not been built into a release APK. Do not treat a test-signed APK as a NightFall release. A Firebase Test Lab Robo run passed on an ARM virtual device running Android 14/API 34, with 82 actions across 4 activities and 19 screens; its crawl timed out at 4m 59s. This confirms app installation and automated exploration, but not Python tool installation or functionality. The local ARM64 AVD cannot boot on this Intel host. The bundled backend/PostgreSQL assets also have provenance limitations. See [security and platform limitations](docs/SECURITY_REMEDIATION.md) and the [runtime validation record](docs/PYTHON_TOOL_WHEELHOUSE.md).

## Requirements

- Android 8.0 or newer.
- ARM64 (`arm64-v8a`) is the intended runtime. x86_64 emulators may run some ARM64 components through Android's translation layer, but the Python tool features are not verified there and currently have known failures.
- Allow at least 250 MB for initial extraction and database setup. Python tool installation needs additional temporary storage; see the [wheelhouse guide](docs/PYTHON_TOOL_WHEELHOUSE.md).
- Internet access is needed for optional catalog downloads. The bundled Python wheelhouse installs offline.

## Setup

There is no verified public release APK yet. To build from source, follow [docs/BUILD.md](docs/BUILD.md). Read [docs/USAGE.md](docs/USAGE.md) for first launch, installation, backups, and troubleshooting. Only install APKs signed by a publisher you trust; Android will not allow an update signed by a different key over an existing installation.

## Tools

Standalone tools are downloaded on demand from a pinned catalog. Downloads are restricted to the reviewed GitHub release route and HTTPS port 443, redirects are checked, and each payload must match its catalog SHA-256. The separate catalog currently lists 32 tools:

```text
Asset discovery: subfinder, shuffledns, puredns, alterx, asnmap, cloudlist,
                 cdncheck, uncover, gau
Probing:         httpx, dnsx, tlsx, naabu
Content finding: katana, ffuf, gobuster
Vulnerability:   nuclei, afrog, dalfox, gitleaks, interactsh-client
Tunneling:       gost, npc, chisel, suo5, frp-frpc, frp-frps,
                 ligolo-agent, ligolo-proxy
Utilities:       fscan, gogo, notify
```

Impacket, NetExec (`nxc`), pypykatz, and LaZagne are provided in the bundled offline Python wheelhouse. The user starts installation from the Tools panel; the app verifies the lock and installs into a staging directory. Package installation alone does not prove all commands, protocols, or features work on a device. Platform constraints are described in the [wheelhouse guide](docs/PYTHON_TOOL_WHEELHOUSE.md).

## Using the app

- Swipe left with two fingers to open backup, restore, tool, and skill controls.
- Swipe right with two fingers to open the ARTEX web sidebar.
- The Android Back button navigates the WebView first, then exits the app.
- The service is intentionally inaccessible from other devices on the LAN.

## Documentation

- [Installation and usage](docs/USAGE.md)
- [Build and signing setup](docs/BUILD.md)
- [Security remediation and limitations](docs/SECURITY_REMEDIATION.md)
- [Python tool wheelhouse](docs/PYTHON_TOOL_WHEELHOUSE.md)
- [Reproducible inputs and provenance](docs/REPRODUCIBLE_INPUTS.md)
- [Android adaptation notes](docs/ADAPTATION.md)

## Responsible use

NightFall Artex is intended for authorized security assessment and lab use. Use it only against systems and data you are authorized to test, and follow applicable laws and engagement rules. The app does not grant privileges beyond Android's app sandbox.

## Upstream

The Android wrapper is based on [RuoJi6/ARTEX](https://github.com/RuoJi6/ARTEX). ARTEX backend and frontend copyrights remain with their respective upstream authors.
