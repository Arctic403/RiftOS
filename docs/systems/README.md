# RiftOS Systems

## 2026-10-10 — Current component migration roadmap

See [external Core, last-known-good recovery, graphical Shell and minimal APK gates](android-host/EXTERNAL_CORE_SHELL_ROADMAP.md). Existing source/verified labels do not establish any future external module as installed or proven. Stable host H3 must pass manual signed/device tests before normal compatible Core/Shell revisions can avoid APK rebuilding; the final embedded-code removal still requires a user-manual signed APK. External Core cannot depend on embedded Core execution; both Core and Shell must have prior stable external revision recovery and available crash diagnostics.

## Trust rule

Every directory under `docs/systems/` is a proposed ownership boundary, but its README is **UNVERIFIED by default**.

A system README is trusted only when:
1. its current implementation/build sources were audited directly;
2. stale and missing behavior was reconciled;
3. the README contains a `## Verification status` section explicitly marked `VERIFIED`;
4. a second audit found no material source/doc mismatch.

Source always outranks documentation.

The master audit/status map is [`../README.md`](../README.md). The file-to-document ownership ledger is [`../SOURCE_OWNERSHIP.md`](../SOURCE_OWNERSHIP.md); that ledger records responsibility, not runtime activation or truth.

When code moves across boundaries, update the ownership ledger immediately, then mark affected documentation unverified until re-audited.
