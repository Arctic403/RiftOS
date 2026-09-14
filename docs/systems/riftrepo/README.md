# RiftRepo

## Purpose

RiftRepo is RiftOS-owned local source control for `/workspace`. It creates offline checkpoints, manifests, branch heads, tags, history, diffs, safety rollback and local releases without requiring GitHub. Checkpoint file bytes are stored through RiftVault's local content-addressed object store while repository metadata stays under `/system/riftrepo/v1`.

## Source ownership

- `src/riftrepo.js` owns repository registration, scan/checkpoint/status/history/diff/rollback/branch/tag/release semantics.
- `src/riftvault.js` owns immutable checkpoint object bytes.
- `src/riftlocal-platform.js` exposes the trusted shell family.

`.riftgit.json`, Gradle build outputs and dependency/build caches are excluded by default because they are transport/reproducible state rather than authoritative source.

## Drive-path compatibility

RiftRepo accepts either `D:/Workspace/...` or `/workspace/...`, then canonicalizes through `RiftOSCore.path.canonical()` before registry lookup and containment checks. Registry roots therefore use one stable `/workspace/...` identity even when the shell cwd is the D: display alias. Paths outside the Workspace backing tree remain rejected.

## Transaction model

A checkpoint hashes the current repository, stores missing immutable objects, writes a manifest, writes a checkpoint record, then advances the active branch head. Rollback first creates an automatic safety checkpoint, restores the requested manifest, and attempts safety restoration if the target restore fails. Branch switching refuses a dirty RiftRepo working tree.

## Failure signatures

- no selected repository -> run `rift repo init <workspace-path>`.
- dirty branch switch -> checkpoint or rollback first.
- invalid/missing hash -> inspect the RiftVault -> RiftFS native streaming SHA-256 path; checkpoints must never substitute weak or path-derived identities.

## Fix map

Repository semantics -> `src/riftrepo.js`.
Blob durability -> `src/riftvault.js`.
Workspace mutation observation remains owned by Workspace Records; RiftRepo does not replace it.

## Validation

Test init, initial checkpoint, clean status, changed-file status, history, diff, branch creation/switch, immutable tags, release metadata, rollback exact bytes and safety-checkpoint creation while offline.