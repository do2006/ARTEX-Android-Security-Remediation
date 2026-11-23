# Reproducible input inventory

This document records the source inputs required to replace the prebuilt assets in this repository. It is an acquisition and verification plan, not evidence that any listed component has already been built, tested, or approved for Android.

## Snapshot

The references below were checked on 2026-11-23. Before a release, resolve each reference again, record its full Git object ID, verify its upstream signature or checksum where provided, and commit the resulting lock file and build attestation.

| Component | Authoritative source | Candidate pinned reference | Status |
|---|---|---|---|
| ARTEX backend and Web UI | <https://github.com/RuoJi6/ARTEX> | `v0.3.31` / `253a8eeac3b278cd4ff383674a31a0edcc34e143` | Source found; Android build not yet reproduced |
| PostgreSQL for Android | <https://github.com/termux/termux-packages/tree/d85d18f456d732d26d8b87d826155c2ced0342e8/packages/postgresql> | Termux packages commit `d85d18f456d732d26d8b87d826155c2ced0342e8`; package recipe declares PostgreSQL `18.6` and its source hash | Source recipe found; complete dependency closure still required |
| Impacket | <https://github.com/fortra/impacket> | release `impacket_0_13_1` | Source found; Android Python compatibility not yet verified |
| NetExec (`nxc`) | <https://github.com/Pennyw0rth/NetExec> | release `v1.5.1` | Source found; dependency closure and Android compatibility not yet verified |
| pypykatz | <https://github.com/skelsec/pypykatz> | release `0.6.13` | Source found; dependency closure and Android compatibility not yet verified |
| LaZagne | <https://github.com/AlessandroZ/LaZagne> | release `v2.4.7` / `a678a971c06710b0041ee5ff8488ccef9c7ce3ed` | Source found; Android Python compatibility not yet verified |

## Current state

`assets/python/stdlib/python3.14/site-packages` contains `pip` only. The app intentionally disables its former automatic network `pip install` path because unpinned packages are not a trustworthy execution input.

The current PostgreSQL preparation script copies a pre-existing Termux installation and patches paths. It does not establish the provenance of the Termux package, its dependency packages, or their transitive native libraries. The current backend preparation script can compile ARTEX from a specified checkout, but it does not lock that checkout, Node dependencies, Go toolchain, or generated Web UI inputs.

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

1. Resolve each tool and all transitive dependencies for the bundled Python 3.14 and Android `arm64` target.
2. Reject dependencies that require unavailable native extensions or unsupported OS features; do not silently fall back to network installation.
3. Build compatible wheels in an isolated environment and generate a fully pinned `requirements.lock` with SHA-256 hashes for every wheel.
4. Ship the reviewed wheelhouse with the APK or as a separately signed, immutable release asset.
5. Verify every wheel hash before installation and retain the installed package/version manifest for support diagnostics.

Until these steps are complete, the application must keep automatic Python tool installation disabled.

## Acceptance evidence

A public release should include all of the following:

- Git commit IDs for ARTEX and `termux-packages`
- Locked dependency manifests and SHA-256 values
- Builder/container image digests and an SBOM
- SHA-256 hashes for the APK and every separately distributed asset
- APK signing certificate fingerprint
- Device/Android-version test matrix covering startup, local WebView access, backup/restore, and catalog downloads
- A statement of remaining unsupported Python tools or Android versions
