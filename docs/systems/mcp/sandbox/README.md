# RiftToolSandbox and Rift Code Mode

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

`RiftToolSandbox` is the workspace-only execution engine behind normal Rift MCP filesystem tools, project inspection and Rift Code Mode / Project Intelligence v2.

It owns canonical workspace containment, single-operation filesystem methods, bounded search/read results, transactional multi-operation batches, snapshots, project intelligence and archive safety.

## Source ownership

Primary:
- `RiftToolSandbox.kt`

Related narrow owners:
- `RiftToolHost.kt` — schemas/grants/aliases;
- `RiftProjectExporter.kt` — paged whole-project export;
- `RiftWorkspaceRecords.kt` — persistent change records/checkpoint state.

## Canonical root

Physical authority is exactly:

`filesDir/riftfs/workspace`

Tool paths are represented as `workspace/...`.

Legacy `tool-sandbox/workspace` and `browser-sandbox/workspace` are one-time migration inputs only. They are never addressable roots after migration.

Old workspace scaffold metadata under `.rift` migrates into app-private system metadata; empty legacy scaffold directories can be removed.

## Core bounds

- ordinary tool file/payload bound: 8 MiB;
- ordinary list: 5000 entries;
- Code Mode operations per batch: 192;
- Code Mode result byte budget: 700 KiB;
- bounded workspace read: 240000 chars;
- search matches: 300;
- patch edits: 96;
- Code Mode list entries: 1200;
- searchable/indexed file max: 2 MiB;
- rollback backup set: 64 MiB;
- search preview: 320 chars;
- symbol results: 240;
- reference results: 400;
- index files scanned: 25000;
- graph edges: 600;
- persisted index rows: 4000;
- persisted index file: 8 MiB;
- hunks: 128;
- snapshot files: 50000;
- archive entries: 50000;
- archive source: 256 MiB;
- archive expansion: 512 MiB.

## Single-operation methods

The sandbox dispatcher implements:
- info;
- stat/hash/list/read/write;
- mkdir/remove/move/copy;
- archive/extract;
- workspace audit/scan;
- project export;
- workspace diff;
- workspace exec.

All paths are canonicalized beneath the workspace root.

## Code Mode operation family

`workspace.exec` supports:
- project;
- snapshot;
- stat;
- hash;
- list;
- search;
- symbols;
- references;
- read;
- read_range;
- read_symbol;
- write;
- replace;
- patch;
- patch_range;
- apply_hunks;
- mkdir;
- remove;
- move;
- rename;
- copy;
- archive;
- extract.

Both canonical flat operations and the same unambiguous shorthand normalization recognized by ToolHost are accepted.

## Snapshot guards

Before batch execution:
- `expectedSnapshot` can require an exact scoped project snapshot;
- `expectedExportSnapshot` can require the current RiftProjectExporter snapshot id.

A mismatch rejects the whole batch before mutation.

`returnSnapshot` optionally returns a fresh scoped snapshot after the batch.

## Transactions

Every batch creates a private cache transaction directory.

Before each mutating operation:
- write/replace/patch/patch_range/apply_hunks/mkdir/remove capture `path`;
- move/rename capture both `from` and `to`;
- copy/archive/extract capture `to`.

A path is captured only once, and a child capture is skipped when an ancestor is already snapshotted.

Existing files/directories are copied into the rollback store before mutation. Total rollback material is capped at 64 MiB.

If an operation throws:
1. every captured target is cleared;
2. original file/directory/missing state is restored;
3. index invalidation follows restoration;
4. rollback errors are appended to the surfaced batch error;
5. transaction staging is removed.

The code never silently claims atomicity when rollback itself failed.

## Dry-run

Dry-run allows reads and content-edit operations whose effects can be restored through the transaction.

Structural mkdir/remove/move/rename/copy/archive/extract are rejected in dry-run mode.

After successful dry-run execution the transaction is rolled back; rollback failure is surfaced as an error.

Response reports `committed=false`.

## Change summary

Before/after snapshots generate bounded changed-file metadata.

For small text files the transaction computes added/removed line deltas. Changed files may include post-change SHA-256 when within ordinary tool bounds.

This is a batch summary, not a permanent AI-session journal.

## Result bounding

Each operation result is counted against the 700 KiB batch result budget.

When the budget is exceeded, execution continues but the affected operation receives a compact `resultOmitted=true` summary rather than returning unbounded content.

## Project Intelligence v2

Persistent cache:
`filesDir/rift-project-intelligence-v2.json`

The cache:
- version-checks at load;
- max file size 8 MiB;
- keeps at most 4000 persisted indexed files;
- is advisory;
- is validated against current file mtime/size;
- can be deleted/rebuilt safely;
- is persisted through a temporary file + rename;
- is outside the user workspace.

Live indexing scans at most 25000 source files, skips ignored/generated/binary directories/files, and indexes source files no larger than 2 MiB.

Ignored directories include common build/cache/vendor trees plus `.vortex-bridge`.

## Project views

The existing `project` operation provides bounded:
- graph;
- impact;
- validation views.

Graph resolves project dependency evidence without guessing unresolved system includes into fake local edges.

Impact combines definitions/references/dependencies/dependents/docs/tests.

Validation discovers repository check/test/build guidance.

These are Project Intelligence v2 behavior behind the existing tool schema, not separate MCP tools.

## Search / symbols / references

Text search is bounded by file size, match count and preview size.

Symbol/reference results are bounded independently.

Index invalidation is batched around mutations and persisted after refresh/invalidation.

Workspace files remain authoritative; stale cache rows cannot override source.

## Archive security

Archive/extract use staged destinations.

Extraction rejects:
- absolute paths;
- drive-letter paths;
- `.` and `..`;
- duplicate entries;
- targets escaping the staging root.

Entry count and expanded-byte limits are enforced before publish.

## Shutdown

Shutdown attempts to persist dirty PI-v2 state, then stops the sandbox single-thread executor.

## Non-ownership boundaries

Sandbox does not own:
- model-visible tool names/grants -> Tool Host;
- shell execution -> RiftNativeShell;
- external SAF folders -> Files;
- whole-project export paging format -> Project Exporter;
- permanent workspace record UI/checkpoint -> Workspace Records.

## Critical invariants

- every tool path remains beneath physical workspace;
- mutation capture happens before mutation;
- rollback max remains 64 MiB;
- failed rollback is surfaced;
- dry-run never permits structural operations;
- expected snapshots reject stale edits before mutation;
- result payload remains bounded;
- PI cache is advisory/app-private/versioned;
- mutation invalidation keeps index coherent;
- archive traversal/duplicate/expansion attacks are rejected.

## Failure signatures

- workspace tool accesses sibling RiftFS root -> containment regression;
- partial batch remains after ordinary failure -> rollback regression;
- rollback failure reported as success -> atomicity regression;
- dry-run structural op succeeds -> dry-run contract regression;
- stale PI cache overrides changed file -> cache-validation regression;
- generated `.vortex-bridge` evidence appears as source -> indexing regression;
- batch result grows beyond budget -> result-bound regression;
- ZIP traversal or duplicate path accepted -> archive regression.

## Fix map

Path/mutation/search/index/transaction behavior -> `RiftToolSandbox.kt`.

Tool schemas/grants -> `RiftToolHost.kt`.

Export paging/snapshot id -> `RiftProjectExporter.kt`.

Workspace persistent records -> `RiftWorkspaceRecords.kt`.

## Validation

Second source audit must recheck canonical root/path guards, operation set, 192-op bound, 700 KiB result bound, transaction capture-before-execute, 64 MiB rollback cap, dry-run restriction/rollback, snapshot guards, index persistence/version/bounds/ignored paths, search/symbol/reference limits, archive staging/traversal and shutdown persistence.
