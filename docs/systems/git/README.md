# RiftGit

## Purpose

RiftGit provides GitHub-backed source synchronization for complete RiftFS project directories without embedding a normal `.git` object database. It tracks remote metadata in `.riftgit.json` and performs GitHub tree/blob/commit operations through the GitHub API.

## Source ownership

`src/riftgit.js`.

Primary commands/functions include clone, attach/init, status, pull, atomic push, sync, branch list/switch, workspace status/push and token/auth session handling.

## Metadata model

`.riftgit.json` (`riftgit-v3`) records owner/repo/branch/root/head SHA and tracked file blob SHA/size/mode. The file is local synchronization metadata and is excluded from Git tree content where appropriate. A separate current-root pointer supports shell workflows.

## Pull model

`treeFor()` fetches the remote branch/tree recursively, blobs are downloaded (bounded/concurrent through `mapLimit`), and `replaceBranch()` stages a replacement before swapping it into place. Pull must not destroy the existing project before the replacement is known complete.

## Push model

`atomicPush()` compares local files with tracked metadata, uploads needed blobs, creates a tree/commit and updates the remote branch only after consistency checks. Workspace push checks that the remote head and local workspace did not change unexpectedly during upload.

## Binary behavior

Files are read/written as base64 for Git blob fidelity. Text assumptions must not silently corrupt binary project assets. Oversized/truncated transfers should fail rather than pretend synchronization succeeded.

## Authentication

The GitHub token lives in `sessionStorage` for the shell session (`riftgit-token`). It is not persisted into repository files. Keep token handling out of package/project exports and docs/log output.

## Critical invariants

- Verify remote head before final branch update.
- Verify local workspace stability during long push.
- Pull stages before replacement; failure must leave original project recoverable.
- Blob SHA semantics are Git blob hashes, not raw file SHA-256; do not compare them as if identical algorithms.
- `.git` and RiftGit metadata are not ordinary synchronized content.

## Failure signatures

- Push says remote advanced -> expected concurrency protection; pull/sync then retry.
- Pull leaves missing project -> replacement staging/rollback regression.
- Binary changed after round-trip -> base64/blob conversion.
- Status shows everything changed after no edits -> metadata/path/blob-SHA calculation.
- Auth fails only after restart -> token is intentionally session-only.

## Fix map

GitHub HTTP/API/tree/blob/commit logic -> `riftgit.js`.
Shell command parsing -> shell dispatcher.
RiftFS read/write/copy semantics -> filesystem subsystem.

## Validation

Run `scripts/test-rift-shell-git.mjs`. Test attach existing folder, clone explicit destination, clean status, text/binary push, remote-advanced conflict, local-changed-during-push conflict, failed pull staging, branch switch and sync.
