# Core boot success = Core known-good (2026-10-11 source change)

## Ownership and scope

The user-set policy is **Core boots successfully → Core promoted for Recovery**. A successful Core boot, not full RiftOS feature qualification, ends its protected *startup* trial. This change applies to **Core only**; the external graphical Shell still has its own process/attach and acceptance path until separately redesigned. Installing, activating or selecting an external Core remains protected and SHA-bound; no RAPP, shell command, external Core payload, or accessibility action can directly write its release ledger.

## Exact APK-owned sequence

1. `RiftCoreCandidateSwitch.selectAtBoot` validates the active exact SHA, protected DEX, namespace, ABI and structural receipt. A durable `core.booting` marker is written **before invoking the selected implementation**. If a previous marker remains, rollback to N-1 or embedded is attempted before loading the candidate.
2. `RiftHostCoreComponents.initializeAtBoot` calls the independent selected Core's real `initialize` and records the selected SHA/process. If initialization throws, the host records the error, rolls back and uses embedded Core for the current process.
3. `RiftBootstrapHost.startCore` initializes the APK Core/Shell recovery coordinator, then invokes `RiftHostCoreComponents.acceptSuccessfulBoot`. The selected Core must respond to the mandatory execution-view `coreStatus` ABI (nonempty schema); **no RAPP launch, compiler, filesystem consent, feature/device matrix, external Shell connection, or MCP relay is required**.
4. For a newly booted candidate, the host-only `RiftProtectedComponentInstaller.acceptBootedCore` checks current selected SHA, exact active pointer, staged manifest/receipt, and still-present marker. `RiftComponentReleaseLedger.acceptProven` promotes that pending exact SHA to `lastKnownGood` and preserves the older revision, then `clearProtectedStartupMarker` removes `core.booting`. The in-memory Core selector switches to `external-known-good`. A previously accepted Core follows the **same new-process boot checkpoint** before clearing its fresh marker, without re-promoting.
5. A failure to persist acceptance or clear the marker is logged. That revision is **not claimed healthy**; the marker remains and next startup can recover. Optional MCP relay startup errors are separately logged and must not block Core acceptance.

## Recovery policy

- **Before successful Core boot:** invalid/missing protected bytes, constructor/init failure, interrupted startup marker, or failed boot checkpoint may lead to protected rollback.
- **After successful Core boot:** RAPP failures, compiler errors, capability/UI issues, normal foreground/background transitions and later process exits **do not revoke known-good Core status**. Android historical process crash/ANR evidence is preserved for diagnosis, not used to demote the accepted SHA.
- Actual Recovery remains in APK-owned `:riftRecovery`, with the previous accepted external Core or embedded migration fallback. No in-process Core hot swap or automatic restart is introduced.

## Deployment and validation

This is **source-only until a newly signed host RiftOS APK is manually built and installed by the user**. It does **not** retroactively remove a startup marker from already-installed APK #722 or promote the live v0.6.1 candidate.

Required next device proof, after the user runs their usual Builder flow: install a new independent Core revision through the unchanged protected stage/activate path; allow its real Core process to start; verify protected ledger `lastKnownGoodSha256` matches selected SHA and `core.booting` is absent **without a third acceptance ticket**; close normally and relaunch to confirm same SHA survives; then deliberately exercise a **safe, isolated startup-failure test** (not damage to user's working Core) and verify previous/embedded fallback. Shell automatic promotion is a separate next patch. Source assertions live in `scripts/validate-rift-wiring.mjs`. Never claim device PASS before proof.
