# Reproducible input inventory

This document records the source inputs required to replace the prebuilt assets in this repository. It is an acquisition and verification plan, not evidence that any listed component has already been built, tested, or approved for Android.

## Snapshot

The references below were checked on 2026-11-23. Before a release, resolve each reference again, record its full Git object ID, verify its upstream signature or checksum where provided, and commit the resulting lock file and build attestation.

| Component | Authoritative source | Candidate pinned reference | Status |
|---|---|---|---|
| ARTEX backend and Web UI | <https://github.com/RuoJi6/ARTEX> | `v0.3.31` / `253a8eeac3b278cd4ff383674a31a0edcc34e143` | Rebuilt for Android arm64 on 2026-11-23; runtime/device validation remains required |
| PostgreSQL for Android | <https://github.com/termux/termux-packages/tree/d85d18f456d732d26d8b87d826155c2ced0342e8/packages/postgresql> | Termux packages commit `d85d18f456d732d26d8b87d826155c2ced0342e8`; package recipe declares PostgreSQL `18.7` and its source hash | Source recipe found; complete dependency closure still required |
| Impacket | <https://github.com/fortra/impacket> | commit `59f6cb3632264ad889c1715740555dd9eb9658e5` | Android/Python 3.14 wheel is hash-locked; device/runtime verification pending |
| NetExec (`nxc`) | <https://github.com/Pennyw0rth/NetExec> | commit `89d25017ad40afa70551b6e8d006c2956ef4b282` | Android/Python 3.14 wheel is hash-locked; device/runtime verification pending |
| pypykatz | <https://github.com/skelsec/pypykatz> | release `0.6.13` | Android-target lock install passed in cross environment; device/runtime verification pending |
| LaZagne | <https://github.com/AlessandroZ/LaZagne> | commit `a678a971c06710b0041ee5ff8488ccef9c7ce3ed` | Android/Python 3.14 wheel is hash-locked; device/runtime verification pending |

## Current state

### NightFall Artex application identity

The Android launcher label is `NightFall Artex` and the installed application ID is `io.nightfall.artex`. The app-private runtime root is exactly 31 bytes (`/data/user/0/io.nightfall.artex`) to retain PostgreSQL's fixed-length path layout. `scripts/rebrand_embedded_paths.py` migrates the bundled PostgreSQL, Python, and bash assets from the former `com.artex.mobile` layout; `scripts/patch_pg_paths.py` now emits PostgreSQL paths for the new root. Java source classes keep the internal `com.artex.mobile` namespace, and manifest components name those classes explicitly.

### English-first UI update (2026-11-24)

The Android build now defaults to English for static HTML and the first client render. Simplified Chinese remains available from the language selector. The exact source changes against the pinned ARTEX commit are recorded in [patches/upstream-english-default.patch](../patches/upstream-english-default.patch); apply it to the pinned source before rebuilding the embedded backend. This changes the default only; saved language preferences and user-provided content are preserved.

The English static frontend built with Next.js 16.2.9, passed TypeScript, and exported all 34 routes. The Android arm64 backend was rebuilt from the pinned upstream commit with Go 1.26.10 and `embedui`; SHA-256: `3722e0db44297ba06702a132927698e9b2bd123a0e22bfc37a880b84c7b46977`. This local build is not a release attestation. The source i18n suite passed all 38 tests after correcting a Windows path normalization bug in the populated-intelligence test fixture.

### Backend build evidence

`assets/backend/artex` was rebuilt from the pinned ARTEX source above in an isolated Docker build on 2026-11-23. The frontend was built with the committed `web/package-lock.json`, statically exported, and embedded into the backend using the `embedui` build tag. The output was not executed.

| Item | Recorded value |
|---|---|
| Node builder | `node:22-bookworm@sha256:e13726ca604184d7a91fcacab1fc09eefd642dc28e79ac34e85540dd93b78cfb` |
| Go builder | `golang:1.26.10-bookworm@sha256:5de7c99eb78ca5e515a4c340235d0955ec5964a5071c492c15503d7c267da473` |
| Frontend command | `npm ci --ignore-scripts --no-audit --fund=false && npm run build:static` |
| Backend command | `GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -buildvcs=false -tags embedui -buildmode=pie -o artex ./cmd/artex` |
| Output | ELF64 little-endian, type `ET_DYN`, machine `AArch64`; 56,687,680 bytes |
| SHA-256 | `8a5bd5d36500f0a1e3caf3f0e01eb917176fa854d8455120f1b4742e68efdb33` |

This is build provenance, not a claim that the backend is security-reviewed or runtime-certified. The APK has not yet been installed or exercised on a device/emulator.

### APK assembly evidence

On 2026-11-23, the Android wrapper was compiled and assembled with Android SDK platform `android-34` and build-tools `36.0.0`. Build-tools `34.0.0` was rejected for release use because its D8 compiler crashed internally on the project's anonymous callback classes; `36.0.0` produced `classes.dex` successfully. The resulting test-only APK was aligned and passed `apksigner` v2/v3 verification. It uses a disposable local certificate and **must not be published or presented as a NightFall release**. A permanent release keystore and an arm64 device test are still required.

`assets/python-tools/requirements.lock` and 96 Android arm64/Python 3.14 wheels now provide an offline, SHA-256 locked user install. Its cross-environment `--no-index --require-hashes` install completed. The app installs only after an explicit panel confirmation, stages the package tree, and switches it after checking expected modules and command entry points. The app integration has not yet been device-tested.

The APK includes Termux-native libraries for the direct PyNaCl, lxml, Pillow core image codec, and WebP dependencies. Their signed repository metadata and package hashes are recorded in `assets/python/native-libraries.lock`. The ARM64 `libxml2` library is bundled separately so translated Python on x86_64 does not accidentally load the x86_64 copy used by PostgreSQL. Static ELF review still finds Pillow's optional AVIF and font-rendering dependencies (`libavif`, `libfreetype`, `libraqm`) absent. Source build recipes and a full native dependency SBOM/attestation are also outstanding; see [Python tool wheelhouse](PYTHON_TOOL_WHEELHOUSE.md).

The current PostgreSQL preparation script copies a pre-existing Termux installation and patches paths. It does not establish the provenance of the Termux package, its dependency packages, or their transitive native libraries.

## Required release inputs

### ARTEX backend

1. Clone the recorded ARTEX tag/commit into an isolated build environment.
2. Pin the Go version, Node version, package-manager lockfile, and every downloaded module/package digest.
3. Build the Web UI and Android `arm64` PIE backend from that exact source tree.
4. Record the source revision, builder image digest, build command, output SHA-256, and software bill of materials (SBOM).
5. Sign the produced artifact and retain the signing certificate fingerprint in the GitHub Release notes.

### PostgreSQL

1. Build the Termux `postgresql` recipe from the recorded `termux-packages` commit rather than copying an unverified `$PREFIX` directory.
2. Resolve and build or retrieve, with checksums, every package declared by the recipe and every shared library copied into `assets/pg`.
3. Record package versions, source URLs, source hashes, native-library list, path-patch source hash, and final asset hashes.
4. Test `initdb`, startup, clean shutdown, backup, and restore on each supported Android version before release.

### Python wheelhouse

1. Preserve the current lock and wheel hashes; record and commit reproducible source recipes and source hashes for locally built Android wheels.
2. Decide whether to include Pillow's optional AVIF/font-rendering libraries or explicitly exclude those features; keep the installed core imaging paths covered by the native dependency audit.
3. Verify imports and representative features on an arm64 Android device; test rollback on a failed install and confirm installed package versions in app diagnostics.
4. Record the final APK, wheelhouse, native library list, and build provenance in a release attestation.

Automatic Python tool installation remains disabled. The user-confirmed installer is present, but the tools must not be advertised as runtime-verified until the native-library closure and device tests are complete.

## Acceptance evidence

A public release should include all of the following:

- Git commit IDs for ARTEX and `termux-packages`
- Locked dependency manifests and SHA-256 values
- Builder/container image digests and an SBOM
- SHA-256 hashes for the APK and every separately distributed asset
- APK signing certificate fingerprint
- Device/Android-version test matrix covering startup, local WebView access, backup/restore, and catalog downloads
- A statement of remaining unsupported Python tools or Android versions
