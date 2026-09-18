# Workspace Records

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

Workspace Records is RiftOS's persistent, observational change recorder for the canonical workspace.

It records local workspace changes, maintains an explicit checkpoint baseline, exposes bounded diffs to the native UI and MCP, and never acts as an approval/deny/rollback gate.

## Source ownership

Live:
- `RiftWorkspaceRecords.kt` — process-wide record/checkpoint state.
- `RiftWorkspaceWatcher.kt` — recursive `FileObserver` tree.
- `RiftNativeWorkspaceApps.kt` — native Workspace Records window.
- `RiftToolSandbox.kt` — read-only `workspace.diff` dispatch.
- `RiftNativeGit.kt` — checkpoint advancement after applicable Git pull/push operations.
- `MainActivity.kt` — watcher lifecycle.

Retained only:
- `workspace-live/*`
- `src/riftworkspace-live-host.js`

## Roots

Observed workspace:
`<filesDir>/riftfs/workspace`

Private records store:
`<filesDir>/rift-workspace-records`

Private subtrees:
- events/
- observed/
- checkpoint/
- state.json

Records state is deliberately outside the workspace so normal project/MCP tools cannot rewrite their own history.

## Process model

`RiftWorkspaceRecords.get(context)` is a process singleton.

It owns one single-thread scheduled executor.

MainActivity creates a `RiftWorkspaceWatcher`, starts it during boot, and shuts the watcher down during Activity destruction.

The records singleton itself remains process-owned; Android process death reconstructs it from private state.

## Initial state

On first initialization:
- if no observed/checkpoint state exists, current workspace files seed both observed and checkpoint snapshots;
- otherwise state is loaded and the current tree is reconciled against observed state.

Initial seed therefore does not report every pre-existing file as a change.

## Observation

Watcher recursively installs Android FileObservers under the canonical workspace only.

Events include create/delete/modify/move/write/attrib/self-delete/self-move.

Ordinary file events settle for 220 ms before capture.

Directory and self/move events schedule a full-tree reconciliation after 420 ms.

New directories receive recursive observers.

Watcher paths are canonicalized and rejected when outside the workspace.

## Reconciliation

A query triggers a full reconciliation when the last reconciliation is older than 30 seconds.

Unchanged files whose size and mtime still match observed metadata avoid a full hash on every reconciliation.

Changed candidates are snapshotted and SHA-256 compared before a record is created.

## Snapshots

Text snapshot limit: 1 MiB.

Files above the limit or recognized as binary are tracked by metadata/hash only.

Observed text snapshots support per-event diffs.

Checkpoint text snapshots support changed-since-baseline diffs.

Snapshot paths are canonicalized beneath the private observed/checkpoint roots.

Snapshot writes use temporary-file + rename publication.

## Event records

Each event record contains:
- format;
- sequence/id;
- timestamp;
- workspace-relative path;
- action;
- source;
- before/after metadata;
- whether text diff is available;
- bounded diff.

Actions:
- created;
- deleted;
- type-changed;
- modified.

Event JSON is written atomically.

Maximum stored event records: 2000; older files are pruned.

## Diff bounds

Text diff:
- maximum 64000 characters;
- maximum changed lines represented: 420, split between removals/additions plus small context.

Binary/oversized changes produce metadata/hash summaries rather than file contents.

## Query

`query(args)` is read-only.

Path filter accepts:
- blank / full workspace;
- project-relative path such as `RiftOS-main/src`;
- normal MCP-prefixed form `workspace/RiftOS-main/src`.

A leading `workspace/` is normalized away. `..` is rejected.

Query bounds:
- requested recent-record limit clamped to 1..250;
- changed-file rows capped at 500;
- combined raw query payload budget: 96000 characters;
- diffs are omitted first when needed;
- omitted file/record counts and `responseTruncated` are reported.

## MCP

`RiftToolSandbox` maps the ToolHost read-only `rift_workspace_diff` tool to:

`workspaceRecords.query(args)`

It does not expose checkpoint mutation.

ToolHost classifies `rift_workspace_diff` as read-only.

## Native UI

The Android-native Workspace Records window:
- refreshes on explicit user action/open;
- queries at most 80 recent records;
- requests `includeDiff=false` for compact display;
- displays changed files and recent events.

During this audit the UI was fixed to read the actual `action` field, so records show created/modified/deleted/type-changed instead of defaulting to a generic “change”.

There is no approve/deny/revert/checkpoint button in this window.

## Checkpoints

`checkpoint(args)`:
1. reconciles current state;
2. replaces the checkpoint map with current observed state;
3. rebuilds checkpoint text snapshots;
4. stores checkpoint timestamp/reason;
5. optionally stores Git root/head metadata;
6. persists state atomically.

Checkpoint changes the **comparison baseline**, not workspace content.

## Git integration

Native Git advances the records baseline for applicable workspace operations:
- after successful Git push: reason `git:push`;
- after successful Git pull/project replacement: reason `git:pull`.

Only display roots equal to `/workspace` or beneath `/workspace/` are accepted for this checkpoint hook.

Git root and head SHA are stored as checkpoint metadata.

## Persistence

state.json contains:
- sequence;
- checkpoint metadata;
- observed entry map;
- checkpoint entry map.

State writes use temp-file + rename.

Event files and text snapshots are private app files.

## Non-ownership boundaries

Workspace Records does not own:
- workspace mutation;
- patch application;
- approval/denial;
- rollback;
- Git remote operations;
- MCP permission policy.

It observes and reports.

## Source fixes in this audit

- native Workspace Records UI now reads the real `action` field;
- query path filters now accept the standard `workspace/...` MCP prefix while remaining workspace-relative internally;
- historical HTML/shadow-root UI claims were purged from the retained workspace-live README during the parent Workspace audit.

## Critical invariants

- records state stays outside workspace;
- recorder never mutates workspace content;
- no approval/deny/rollback gate;
- watcher cannot escape workspace;
- text snapshot <=1 MiB;
- event store <=2000 files;
- query recent-record limit <=250;
- raw query payload <=96000 chars;
- diff <=64000 chars / 420 changed lines;
- checkpoint only advances baseline;
- MCP access is query/read-only;
- Git checkpoint occurs only after successful workspace Git operations.

## Failure signatures

- recent events all show “change” -> native UI action-field regression;
- `workspace/RiftOS-main` filter returns no data while relative form works -> prefix normalization regression;
- records files appear under workspace -> integrity regression;
- MCP can call checkpoint -> authority regression;
- query returns unbounded records/diffs -> bound regression;
- changed workspace content disappears because recorder altered files -> ownership violation;
- watcher accepts path outside workspace -> containment failure.

## Fix map

Persistence/diff/checkpoint/query -> `RiftWorkspaceRecords.kt`.

Filesystem events -> `RiftWorkspaceWatcher.kt`.

Native display -> `RiftNativeWorkspaceApps.kt`.

MCP mapping -> `RiftToolSandbox.kt` / `RiftToolHost.kt`.

Git baseline hooks -> `RiftNativeGit.kt`.

## Validation

Second source audit must verify roots, observer containment/lifecycle, event settlement/reconciliation, text/diff/event/query bounds, atomic private writes, query prefix normalization, read-only MCP mapping, native UI action field, Git push/pull checkpoint call sites, and absence of approval/rollback APIs.

Device testing should mutate files through Editor, MCP, Shell, Git and Dev Lab and confirm one consistent record stream and expected checkpoint changes.
