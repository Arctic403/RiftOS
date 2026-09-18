# RiftOS Systems

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
