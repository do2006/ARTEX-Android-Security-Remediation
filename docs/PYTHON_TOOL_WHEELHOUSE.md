# Python tool wheelhouse

## What the app installs

The APK includes 96 locked wheels for the bundled CPython 3.14 runtime and Android arm64. The intended device is 64-bit ARM Android (`arm64-v8a`). The current x86_64 emulator exposes ARM64 translation and can run CPython and pip, but several native extensions and tool commands fail under translation; x86_64 is not a supported or release-certified platform for these Python features. Other x86 devices are rejected before installation. The package set includes Impacket, NetExec (`nxc`), pypykatz, LaZagne, and their resolved Python dependencies. The lock is `assets/python-tools/requirements.lock` (SHA-256 `1e801e996a59d5debd919b3c46300f6b79b154635ee60e8a4c5b77a03e199fa0`). Every wheel is also individually SHA-256 pinned in that lock.

The Tools panel offers an explicit **Install Python tools** action. It does not install on startup and does not contact PyPI. After confirmation the app verifies the lock, installs with `--no-index --require-hashes` into an app-private staging directory, checks the expected modules/entry points, then switches the installation and restarts the ARTEX backend. A failed pip run leaves the prior installed tree in place.

The APK wheelhouse occupies 33,149,310 bytes before APK compression. In the cross-build environment, the extracted installed package tree used 128 MiB; allow at least 200 MB of free app storage for the copied wheelhouse, staging tree, and final installation. Device requirements may vary. The first install may take several minutes on a phone.

## Build inputs

The dependency set was resolved and cross-built in the isolated Termux package-builder environment using Python 3.14 and the Android NDK r30 arm64 toolchain (Android API 24 baseline). The source references used for the four top-level projects are:

| Project | Source reference |
|---|---|
| NetExec | `Pennyw0rth/NetExec` commit `89d25017ad40afa70551b6e8d006c2956ef4b282` |
| Impacket | `fortra/impacket` commit `59f6cb3632264ad889c1715740555dd9eb9658e5` |
| pypykatz | PyPI release `0.6.13`, locked wheel hash in `requirements.lock` |
| LaZagne | `AlessandroZ/LaZagne` commit `a678a971c06710b0041ee5ff8488ccef9c7ce3ed` (v2.4.7); packaged with an Android command wrapper because upstream has no Python package metadata |

The NetExec wheel was built from the pinned commit with its direct Git dependencies replaced by exact wheel versions in the lock so the app can install without Git or network access. The lock is the installation integrity boundary. The wheelhouse is not a full reproducible build attestation: all native build recipes, source hashes, and compiler configuration have not yet been committed as a reproducible build manifest.

## Validation evidence and remaining work

- The isolated cross-pip environment completed a `--no-index --require-hashes` install of the complete wheelhouse into an Android-target staging directory. This did not execute the installed tools or validate them on Android.
- The direct dependencies for PyNaCl, lxml, and Pillow's core imaging/WebP paths are bundled under `assets/python/lib`. The original 16 Termux packages plus signed ARM64 `libxml2` and `libiconv` packages are listed in `assets/python/native-libraries.lock`; x86_64 PostgreSQL builds cannot supply the ARM64 copies needed by translated lxml. The signed `InRelease` and `Packages.gz` digests and both `.deb` hashes were verified before extracting only the shared libraries. No package maintainer scripts were run.
- Static ELF closure review now leaves `libavif.so`, `libfreetype.so`, and `libraqm.so` unresolved for Pillow's AVIF and font-rendering extensions. Pillow loads these optional features on demand; their behavior is not device-tested. This closes the identified direct dependencies for PyNaCl, lxml, Pillow core image codecs, and WebP, but does not replace Android runtime tests.
- On the available x86_64 emulator, translated CPython 3.14.10 starts with `LD_LIBRARY_PATH` unset and imports `ssl`, `bz2`, `lzma`, `sqlite3`, and `zlib`. A scratch offline pip install of all 96 wheels passed with `--require-hashes` and ARM64 target tags. `impacket`, `pypykatz`, and `lazagne` Python imports passed; `laZagne --help` exited successfully. However, `nxc --help` fails while loading `netifaces` with unresolved Python C-API symbol `PyExc_OSError`; `pypykatz --help` fails because its cryptography dependency cannot load the OpenSSL legacy provider. lxml and Pillow native extension imports also fail with unresolved Python symbols. Setting `LD_LIBRARY_PATH` to the ARM64 asset directory prevents the x86 translation runner itself from starting because it attempts to load ARM64 libraries into the x86_64 host process. These are observed emulator failures, not proof of behavior on a native ARM64 phone.
- On 2026-11-24, Firebase Test Lab ran the current test-signed APK on a Medium Phone ARM virtual device with Android 14 (API 34). The Robo matrix passed (1/1 device); the crawl ran 5m 9s and ended at its 4m 59s crawl timeout after 82 actions, 4 activities, and 19 screens. This confirms the APK can be installed and Robo can explore the app on an ARM virtual device. It did not exercise the explicit Python-tools installation flow or verify any of the four tool commands. The test artifact is test-signed, not a release APK.
- The earlier scratch test ran under `/data/local/tmp`, not through the installed app's asset extraction and panel flow. The local ARM64 AVD cannot boot on this Intel x86_64 host. The ARM virtual-device Robo result does not replace manual checks of PostgreSQL initialization, backend/WebView behavior, Python-tools installation, or representative tool features on ARM64. Those remain unverified; use a native ARM64 phone or genuine ARM virtual device for the remaining checks.

## Platform limitations

- Android app sandboxing prevents LaZagne from reading another app's private credential stores. Its Linux SecretStorage integration is omitted because Android does not provide the desktop D-Bus Secret Service.
- pypykatz can parse a dump the user provides, but an Android app cannot access a Windows host's live LSASS process.
- NetExec and Impacket install as Python packages, but individual protocols may rely on Android-unavailable kernel interfaces, privileges, or system services. Package installation does not certify every protocol.
- These tools do not bypass Android's app sandbox or platform permissions. Users must supply authorized targets and data.
