# RiftToolSandbox and Rift Code Mode

## Purpose

`RiftToolSandbox` is the workspace-only implementation behind normal Rift MCP filesystem/project tools. It enforces canonical path containment, payload/result limits, safe mutation behavior, project intelligence and transactional Code Mode batches.

## Source ownership

`android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt`.

Related: `RiftProjectExporter.kt` provides paged full-source export. Mutation rollback is owned by the sandbox's copy-on-write transaction for each `rift_workspace_exec` batch; there is no separate persistent AI-session journal.

## Filesystem boundary

The sandbox prepares and owns one canonical root: `filesDir/riftfs/workspace`, represented to tools as `workspace/...`. `sandboxFile`, `workspacePath`, `workspaceMutationPath`, segment normalization and containment checks prevent path traversal or access to sibling RiftFS roots.

Legacy tool/browser sandbox workspace directories are migration inputs only; missing content can be merged into the canonical workspace without making old roots addressable.

## Single-operation capabilities

The sandbox implements info, stat/hash/list/read/write/mkdir/remove/move/copy/archive/extract, audit/scan and project export. Writes use staged/atomic replacement patterns where appropriate. ZIP extraction rejects traversal and applies size/entry safety limits.

## Rift Code Mode

`workspaceExec()` accepts up to the bounded operation count and executes a declarative sequence such as project/snapshot/stat/hash/list/search/symbols/references/read/read_range/read_symbol/write/replace/patch/patch_range/apply_hunks/mkdir/remove/move/rename/copy/archive/extract.

Mutating batches create a lazy `BatchTransaction`. Each affected path is captured before first mutation. If any operation throws, all captured mutations are rolled back. On success, all remain. Dry-run permits reads/content edits but rolls them back; structural operations such as mkdir/remove/move/copy/archive/extract are rejected in dry-run mode.

## Project intelligence

The sandbox provides bounded text search plus an incremental in-memory symbol index. Index invalidations are batched around mutations. Symbol extraction supports the source languages recognized by `languageFor`; references and symbol reads are bounded.

Snapshots/hash guards allow callers to reject edits when the workspace changed after inspection.

## Critical invariants

- Every path is contained before IO.
- Result and file/payload limits are enforced before large data escapes.
- Mutating batch capture occurs before mutation.
- Rollback failure is surfaced; never silently claim atomicity after a failed restore.
- Symbol index invalidation follows every relevant mutation.
- Archive extraction must never permit `../`, absolute-path or oversized expansion attacks.

## Failure signatures

- MCP cannot see path that Files can -> expected if outside workspace; otherwise path mapping/migration.
- Batch partially changed files after error -> transaction capture/rollback defect.
- Symbol/reference results stale -> invalidation/index refresh.
- Large project call truncates -> bounded result behavior; use targeted reads/export instead of removing limits casually.
- ZIP rejected -> inspect traversal/duplicate/size policy before loosening it.

## Fix map

Workspace operation semantics, atomicity, project search/indexing and path security belong here. Tool names/grants belong in `RiftToolHost`; whole-project export paging belongs in `RiftProjectExporter`.

## Validation

Test path traversal rejection, atomic write replacement, failed-batch rollback, dry-run restoration, expected snapshot/hash rejection, archive traversal rejection, symbol invalidation after edits and bounded search/list behavior.
