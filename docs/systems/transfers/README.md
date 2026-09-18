# Transfer Ownership

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

RiftOS no longer has one generic transfer engine.

Copy, move, archive, extraction, Git project replacement and SAF editing are owned by different narrow subsystems with different guarantees.

## Source ownership

Current owners:
- `RiftNativeShell.kt` — app-private RiftFS cp/mv/zip/unzip.
- `RiftToolSandbox.kt` — workspace-only MCP copy/move/archive/extract and transaction support.
- `RiftNativeGit.kt` — repository/project staging and replacement.
- `RiftNativeWorkspaceApps.kt` — SAF mount/browse/text edit only; **not** a copy/move/archive engine.

Retired:
- `RiftTransferManifest.kt` — absent.
- general transfer code formerly inside `RiftNativeDispatcher.kt` — absent.

## Native Shell transfers

Shell `cp` / `mv`:
- stay inside canonical app-private RiftFS;
- reject RiftFS/virtual volume roots;
- refuse overwrite unless `--force` / `-f`;
- bound recursive copy to 10000 entries and 256 MiB;
- try `renameTo` first for move;
- fall back to bounded copy + source deletion;
- if move cleanup fails after copy, both copies are retained and an error is returned rather than deleting the destination.

Shell `zip`:
- requires a non-root source;
- requires a new `.zip` destination;
- stages output in a temporary file;
- rejects unsafe entry names;
- bounds source to 10000 entries and 256 MiB;
- publishes by rename only after archive completion.

Shell `unzip`:
- requires a non-existing destination;
- extracts to a staging directory;
- rejects absolute, drive-letter, `.`, `..` and duplicate entries;
- bounds to 10000 entries and 256 MiB expanded bytes;
- canonicalizes each staged target;
- publishes the staged directory by rename;
- removes staging on failure.

## MCP / Code Mode transfers

All MCP transfer paths remain inside `riftfs/workspace`.

Archive creation:
- source/destination must be distinct;
- destination must end in `.zip`;
- archive cannot be created inside its source directory;
- bounds: 50000 entries, 256 MiB source;
- writes a temporary archive;
- uses `commitStaged()` for destination replacement/rollback behavior.

Extraction:
- source must be a `.zip` file;
- destination cannot be workspace root;
- bounds: 50000 entries, 512 MiB expanded bytes;
- rejects absolute, drive-letter, empty, duplicate, `.` and `..` entries;
- canonicalizes every staged output;
- extracts to a temporary directory;
- commits through `commitStaged()`.

Workspace batch transactions separately capture mutation destinations for copy/archive/extract and provide copy-on-write rollback for the batch.

## Git project replacement

`RiftNativeGit` owns repository synchronization rather than using Shell/MCP transfer routines.

For project replacement it:
1. builds a staged project;
2. writes metadata into the stage;
3. renames the current project into a recovery backup;
4. renames the stage into the project root;
5. restores the backup if publish fails and the destination is absent;
6. removes the backup only after successful publish.

A failed replacement reports the recovery staging path rather than silently deleting both copies.

## SAF / native Files

Native Files currently does **not** implement general copy/move/zip/unzip.

Its SAF responsibilities are:
- mount;
- browse;
- bounded text open/edit/save;
- unmount.

External-provider write behavior is provider-specific and belongs to Files/Editor validation.

Do not describe SAF support as a native transfer engine.

## Non-ownership boundaries

Transfer ownership does not imply:
- MCP may leave workspace;
- Shell may access SAF;
- Files may perform arbitrary transfer operations;
- Git project swaps use generic cp/mv;
- installed apps inherit Shell transfer authority.

## Critical invariants

- `RiftTransferManifest.kt` remains absent;
- no broad transfer dispatcher returns;
- Shell transfers remain RiftFS-confined;
- MCP transfers remain workspace-confined;
- archive traversal/duplicate/size limits remain enforced;
- move failure never silently destroys both source and destination;
- staged archive/extract paths are cleaned or retained safely on failure;
- Git replacement preserves a recovery path;
- Files/SAF is not falsely documented as a copy/move/archive engine.

## Failure signatures

- generic transfer queue/manifest returns -> retired architecture regression;
- Shell transfer escapes app-private RiftFS -> containment failure;
- MCP transfer accepts non-workspace path -> sandbox failure;
- ZIP traversal/absolute/duplicate entry is accepted -> archive security failure;
- shell move deletes source despite failed copy/publish -> data-loss regression;
- Git replacement deletes current project before a recoverable stage/backup exists -> data-loss regression;
- docs claim Files supports copy/move/zip when source has no such implementation -> documentation regression.

## Fix map

Shell cp/mv/zip/unzip -> `RiftNativeShell.kt`.

Workspace copy/move/archive/extract and transactional staging -> `RiftToolSandbox.kt`.

Git pull/project replacement -> `RiftNativeGit.kt`.

SAF mount/edit semantics -> `RiftNativeWorkspaceApps.kt`.

## Validation

Source verification must recheck:
- retired transfer files absent;
- Shell copy/move bounds/failure semantics;
- Shell ZIP stage/traversal/size rules;
- MCP archive/extract limits and staging;
- workspace confinement;
- batch rollback capture;
- Git recovery backup flow;
- absence of Files copy/move/archive implementation.

Installed-device/provider validation remains necessary for SAF and storage-provider failure semantics.
