# Security remediation status

This branch changes the Android wrapper only. The reviewed backend remains a prebuilt binary, so its TLS, authentication, authorization, archive handling, and command-execution behavior cannot be verified or corrected here.

## Safe defaults now enforced

- Tool and skill catalogs are pinned to reviewed immutable commit `c1fa82a2507be24c2f3f219137f74b7832812e7d`. Entries require a strict basename, valid SHA-256, the exact `Srysfu/artex-mobile-tools` GitHub release route, HTTPS port 443, and at most three redirects to approved GitHub release delivery hosts before download or installation.
- Catalog-installed binaries are statically checked but are not executed as an install probe.
- The backend binds only to `127.0.0.1`; LAN sharing is disabled by default.
- Unlocked network `pip install` is disabled. A future offline wheelhouse must include a committed, reviewed, fully hash-locked requirements file before it is enabled.
- Android Auto Backup is disabled. Cleartext is denied by default and allowed only for `http://127.0.0.1:8787`; WebView main-frame navigation is limited to that local service and file access is disabled.
- New backups use a version-2 `manifest.json` written before archive contents. Restore parses it, rejects malformed/empty or legacy manifests, enforces ZIP path and expansion limits, and verifies SHA-256 for every declared file before accepting the restore.
- Restore reports a failed rollback instead of silently discarding it. PostgreSQL shutdown retains the process reference until it has actually exited.
- Release signing passes password source names (`env:ARTEX_KS_PASS` and `env:ARTEX_KEY_PASS`) to `apksigner`; passwords are no longer placed in its command-line arguments.

## Follow-up required

- Provide backend source plus reproducible, signed provenance for `assets/backend/artex` and PostgreSQL assets before enabling LAN access, TLS, or server-side authentication changes.
- Backups remain in internal app-private storage and are not encrypted or signed. The v2 manifest detects accidental tampering/corruption but is not a trust signature. Implement authenticated encryption with a user-selected passphrase and recovery UX before adding any export flow.
- Legacy backups with the prior unverified manifest format are intentionally rejected; users must retain the source device and create a fresh v2 backup before migration.
- Pin and verify all build inputs, including Termux packages and the Android signing environment. `env:` keeps signing passwords out of process arguments, but environment-based secrets still require a controlled build host and must never be logged.
