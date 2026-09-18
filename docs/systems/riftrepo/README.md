# RiftRepo — Retained Local Checkpoint Design

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Status

RiftRepo is **retained/inactive reference source**.

The implementation remains in:

- src/riftrepo.js

but current Android source contains no RiftRepo class, command, service or caller, and current Gradle does not package riftrepo.js into the APK runtime.

The native RiftShell explicitly retires the old generic `rift` / RiftLocalPlatform wrapper.

## Retained design

The retained JavaScript describes a local workspace checkpoint/source-control design using:
- /system/riftrepo/v1 state;
- workspace-confined working trees;
- RiftVault content-addressed objects;
- manifests and checkpoints;
- local branches/tags/releases;
- checkpoint-based rollback;
- local backup references.

That design is useful as migration/reference material only.

Its presence does not make any of those commands available in the current APK.

## Source ownership

Retained:
- src/riftrepo.js
- src/riftlocal-platform.js where the historical generic command family referenced RiftRepo
- src/riftbuild.js as another retained consumer

Current replacement/related live owners:
- RiftNativeGit.kt — live Git/GitHub synchronization
- RiftWorkspaceRecords.kt — live local workspace observation/diffs/checkpoints
- RiftNativeDevLab.kt — live staged source publication

These native systems do not instantiate or call globalThis.RiftRepo.

## Activation proof

Current source audit finds:
- zero Kotlin `RiftRepo` references;
- zero native `rift repo` command;
- zero Gradle include for src/riftrepo.js;
- no wildcard src/** packaging;
- native shell explicitly rejects the legacy RiftLocalPlatform wrapper.

References to RiftRepo are confined to retained JavaScript and reference/source-validation scripts.

## Public surface status

RiftRepo is not a live public/cross-layer APK surface.

docs/PUBLIC_SURFACES.md classifies the local-first JS family as retained reference code rather than native authority.

## Security/trust boundary

Because RiftRepo is inactive:
- its JavaScript rollback semantics are not used as current data-safety guarantees;
- its historical RiftVault dependency does not create current storage authority;
- its branch/checkpoint commands are not exposed to native Shell/MCP;
- no current credentials or secrets should be added to this retained source.

Any future reactivation requires a new source-first audit of the implementation itself before packaging or wiring it.

## Critical invariants

- riftrepo.js remains un-packaged unless deliberately reactivated;
- native Shell does not expose `rift repo`;
- live Git/Workspace Records/Dev Lab behavior is not attributed to RiftRepo;
- retained tests must identify RiftRepo as reference code, not public APK authority;
- future activation requires an explicit owner, packaging path and bounded capability contract.

## Failure signatures

- native Shell starts routing `rift repo` without a dedicated audit -> activation regression;
- Gradle packages riftrepo.js silently -> packaging regression;
- docs advertise local checkpoint/rollback commands as available today -> capability overclaim;
- CI labels RiftRepo a public live surface merely because src/riftrepo.js exists -> test/runtime drift.

## Fix map

Retained design semantics -> src/riftrepo.js.

Historical aggregate wrapper -> src/riftlocal-platform.js.

Current Git synchronization -> RiftNativeGit.kt.

Current workspace history -> RiftWorkspaceRecords.kt.

Current staged publication -> RiftNativeDevLab.kt.

## Validation

Second source audit must prove:
- retained implementation still exists;
- all current references are retained/reference-only;
- zero Kotlin/native command activation;
- zero Gradle packaging;
- legacy generic `rift` command remains retired;
- public-surface documentation does not present RiftRepo as live.

VERIFIED status for this README means the **inactive/retained classification** is verified, not that the retained JavaScript checkpoint implementation is device-tested or approved for reactivation.
