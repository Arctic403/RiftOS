# RiftOS Native Dev Lab

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

Dev Lab is RiftOS's native staged-edit control plane for the local `workspace/RiftOS-main` source project.

It separates staging/snapshot review from publication so ordinary editing does not immediately overwrite the canonical project.

## Source ownership

Primary authority:
- `RiftNativeDevLab.kt`

Live callers:
- `RiftNativeWorkspaceApps.kt` — native Dev Lab window.
- `RiftNativeShellServices.kt` — finite `devlab` command family.
- `RiftNativeShell.kt` — routes shell command to services.
- `RiftVortexLocalAgent.kt` — bounded local-agent Dev Lab operation forwarding.

Retained/non-authoritative:
- `src/riftdevlab.js`
- historical web/shell Dev Lab execution flows.

The zero-caller `RiftHeadlessJsRuntime.executeDevLab()` execution island and its Dev Lab QuickJS wrapper were removed during this audit.

## Storage

App-private RiftFS root:
`filesDir/riftfs`

Dev Lab root:
`riftfs/system/devlab`

Subtrees:
- `stage/`
- `snapshots/`
- `publications/`
- `transactions/`
- `state.json`

Canonical publication target:
`riftfs/workspace/RiftOS-main`

displayed internally as `workspace/RiftOS-main`.

## Limits

- staged source text: <=2 MiB per file;
- maximum staged paths: 256;
- snapshot list query: 1..200;
- stage reason: <=500 characters;
- snapshot note: <=1000 characters.

Only explicit text/source extensions and a small set of extensionless source filenames are stageable.

Project-relative paths reject blank, `.`, `..`, NUL and traversal.

## State

Native state format/version:
- `riftos-devlab-state-native`
- version 2.

State tracks:
- project;
- baseline Git head;
- staged path metadata;
- latest snapshot id;
- last publication receipt;
- updated timestamp.

State is written through native atomic replacement.

## Staging

First staged path captures the current project Git head as `baselineHeadSha`.

For each staged path Dev Lab records the original:
- existence;
- SHA-256, when a file existed.

Write staging additionally stores the staged SHA-256.

Deletion can only be staged for an existing baseline file.

A baseline file larger than the 2 MiB source limit cannot be staged through this text/source workflow.

Staging mutates only `system/devlab/stage`, not the canonical project.

## stage-file

`stage-file` may read a bounded text file from RiftFS using the current shell cwd/path mapping, then stage its text under an independently validated project-relative destination.

The source file may come from elsewhere in RiftFS; that does not change the publication target or target-path confinement.

## Snapshot

`snapshot` requires at least one staged path.

The snapshot JSON contains:
- unique snapshot id;
- baseline and captured Git heads;
- sorted staged entries;
- staged content for write entries;
- base hashes/existence and action metadata.

Snapshots are not modified through the Dev Lab API after creation.

They are still stored under trusted app-private RiftFS and are therefore not a cryptographic/tamper-proof boundary against stronger native RiftShell authority.

## Preview

Preview revalidates every snapshot entry against the current canonical project.

A publish conflict now includes:
- file existence changed;
- file SHA changed;
- a non-file object appeared at a staged file path;
- known baseline Git head differs from the current known Git head.

`safeToPublish=true` only when there are no path conflicts and no Git-head conflict.

The Git-head check was added during this audit because the previous source recorded baseline head metadata but did not enforce it.

## Publish transaction

Publish first runs preview and refuses unsafe snapshots.

For each entry:
1. current file is copied into a per-publication transaction directory when it exists;
2. write action publishes through atomic file replacement;
3. delete removes the target;
4. path is added to the applied list.

On failure:
- applied paths are restored in reverse order;
- newly created targets are removed when no backup existed;
- each rollback failure is collected.

If rollback fully succeeds, the transaction directory is deleted and the call reports that publish failed but rolled back.

If any rollback step fails:
- source does **not** claim a successful rollback;
- transaction data is retained;
- error reports that rollback was incomplete and includes the recovery path/details.

On successful publication:
- a version-2 publication receipt is written;
- transaction directory is deleted;
- published paths are removed from staging;
- baseline head is cleared when staging becomes empty;
- lastPublished is persisted.

## Atomic writes

Dev Lab state, staged files, snapshots, receipts and canonical write publication use a temp + backup + rename replacement helper.

If replacement fails, the previous target is restored when possible.

## Native UI

The native Dev Lab window exposes:
- Load
- Stage
- Snapshot
- Publish
- Reset

Publish first obtains a preview and only proceeds through the native publish action when the snapshot reports safe-to-publish.

The UI is Android-native; it does not require WebView.

## Shell

Native shell routes:
- status
- load
- staged
- stage
- stage-file
- delete
- unstage
- reset
- snapshot
- snapshots
- load-snapshot
- preview
- publish

Historical web execution actions:
- run
- run-file
- css
- css-off
- open

fail closed with a message that execution moved out of the Dev Lab control plane.

`runs` returns an empty run list.

## Generic batch status

The current native RiftShell has **no generic shell `batch` command**.

The old `src/riftshell-batch.js` is retained/unpackaged.

Therefore current Dev Lab does not need a special live “cannot run inside batch” check; there is no live generic shell batch authority to enter.

Rift MCP Code Mode transactions are a separate workspace subsystem and do not call Dev Lab.

## Local agent

The Vortex/RiftOS local-agent command router can forward a bounded `devlab` operation to the same `RiftNativeDevLab.execute()` authority.

It does not implement an alternate staging/publish engine.

## Browser/headless execution

Dev Lab itself does not execute arbitrary staged JavaScript/HTML.

The dead headless Dev Lab runner was removed during this audit.

Any future preview/execution runtime must be introduced under an explicitly audited owner rather than silently restoring the old web Dev Lab path.

## Workspace Records

Dev Lab publish changes canonical workspace files, so the shared workspace watcher can observe them like changes from other local writers.

Dev Lab does not directly mutate Workspace Records state.

## Non-ownership boundaries

Dev Lab does not own:
- Git remote push/pull;
- Builder/install;
- generic workspace MCP transactions;
- browser renderer execution;
- Workspace Records;
- Android shell/process execution.

## Source fixes in this audit

- enforced staged baseline Git-head conflict during preview/publish;
- reject non-file path-type changes before publish;
- rollback now reports incomplete recovery honestly and retains transaction evidence;
- successfully rolled-back failed transactions are cleaned;
- dead zero-caller headless Dev Lab JavaScript runner removed;
- stale generic shell-batch claim corrected to current native architecture.

## Critical invariants

- staging never mutates canonical project;
- stage paths stay project-relative and source-type allowlisted;
- <=2 MiB per staged source;
- <=256 staged paths;
- preview verifies file baselines and known Git-head baseline;
- publish cannot replace a newly appeared directory/non-file;
- publication rollback failure is never hidden;
- no arbitrary Dev Lab process/shell/browser execution;
- UI, shell and local agent share one native authority;
- snapshots are immutable through Dev Lab API but not claimed as cryptographically tamper-proof.

## Failure signatures

- staging changes workspace before publish -> isolation regression;
- Git head changes but preview stays safe -> baseline-head regression;
- directory at staged file path is replaced -> path-type regression;
- failed restore still says “rolled back” -> recovery-reporting regression;
- old headless `executeDevLab` returns -> dead execution-surface regression;
- web run/css/open action executes natively -> execution-boundary regression;
- docs claim live generic shell batch exclusion -> stale architecture.

## Fix map

Staging/snapshot/preview/publish/recovery -> `RiftNativeDevLab.kt`.

Native UI -> `RiftNativeWorkspaceApps.kt`.

Shell parsing -> `RiftNativeShellServices.kt`.

Local-agent forwarding -> `RiftVortexLocalAgent.kt`.

Builder/device activation -> Build/validation subsystem.

## Validation

Second source audit must recheck storage roots, allowed source paths, 2 MiB/256 bounds, baseline capture, snapshot contents, file/head/type conflict detection, publish backup/rollback behavior, transaction cleanup/retention, UI/shell/local-agent callers, dead headless runner absence and absence of live shell batch.

Device proof should stage/edit/snapshot, introduce file and Git-head drift, test successful publication, force a publication failure, verify Workspace Records observation, then Builder/install before treating Kotlin changes as active.
