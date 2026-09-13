# Workspace Records

## Purpose

Workspace Records is RiftOS's private, persistent workspace change-history surface. It is observational: it records what changed, when, where, and how text changed, regardless of whether the writer was Files, RiftWorkspace, RiftGit, MCP, shell tooling, or another local component. It does **not** approve, deny, accept, reject, or apply workspace mutations.

The historical source folder remains `workspace-live/` and the trusted host remains `riftworkspace-live-host.js` for compatibility, but the shipped product surface is now **Workspace Records**.

## Source ownership

- `workspace-live/index.html`, `app.js`, `style.css` — trusted-shell records template, controller and shadow-root styles.
- `src/riftworkspace-live-host.js` — trusted component host, local records calls and native watcher subscription.
- `RiftWorkspaceWatcher.kt` — always-on recursive native workspace watcher for the RiftOS shell session.
- `RiftWorkspaceRecords.kt` — persistent observed state, checkpoint state, event records, and bounded text diffs stored outside `riftfs/workspace`.
- `src/riftgit.js` — real remote Git comparison plus automatic records checkpoints after successful workspace Git pull/push.
- `src/riftos.js` `openWorkspaceLive()` — desktop window integration; the internal ID stays `workspace-live` for compatibility.

## Security model

Records now runs inside the main trusted RiftOS shell. A shadow root prevents CSS and element-ID collisions, but is **not** a security boundary: this component has access to shell globals, unlike installed guest apps and browser pages. Its UI currently invokes only local records/info and optional Git-diff queries. No iframe, `postMessage` RPC, server connection, or separate credential is required.

Persistent record data lives under app-private `filesDir/rift-workspace-records`, outside `filesDir/riftfs/workspace`. Workspace tools therefore cannot rewrite their own record history through ordinary workspace paths.

## Runtime flow

```text
any local workspace writer
  -> filesDir/riftfs/workspace
  -> RiftWorkspaceWatcher (always on while RiftOS shell lives)
  -> RiftWorkspaceRecords
       -> observed snapshot
       -> persistent event record
       -> checkpoint comparison
  -> Workspace Records dashboard

Rift MCP rift_workspace_diff
  -> RiftToolHost
  -> RiftToolSandbox
  -> RiftWorkspaceRecords.query()
```

Directory events trigger a bounded full reconciliation. File events are debounced and compared against the last observed state. Text files up to the recorder limit receive bounded git-style diffs; binary/oversized files remain represented by metadata/hash transitions.

## Local checkpoint diff

The checkpoint is separate from the rolling observed state. Every event advances **observed** state, preserving event-to-event history, while the checkpoint stays fixed so the dashboard and `rift_workspace_diff` can show the complete local working change set.

A successful RiftGit workspace pull/push creates a new checkpoint tagged with the Git head SHA. This resets the changed-since-sync comparison, not the persistent record timeline. The dashboard no longer exposes a manual checkpoint button.

## Git comparison

The Git tab uses `RiftGit.workspaceDiff()` to compare `/workspace/RiftOS-main` to the current remote `Arctic403/RiftOS#main` tree. It reports modified/deleted/untracked files and bounded text diffs; binary files are reported as binary changes. This network-backed Git comparison is distinct from the private local checkpoint diff exposed by MCP.

## MCP tool

`rift_workspace_diff` is read-only. Optional arguments:

- `path` — limit results to a workspace-relative subtree;
- `limit` — recent persistent records returned, clamped to 1..250;
- `includeDiff` — include or omit bounded text diffs.

The result includes checkpoint metadata, all currently affected files since checkpoint, bounded git-style local diffs, and recent writer-agnostic records.

## Failure signatures

- Recording indicator offline -> `MainActivity` watcher startup / watcher state.
- File changed but no record appears -> `RiftWorkspaceWatcher` event delivery or `RiftWorkspaceRecords` reconciliation/debounce.
- Local changed-file count is wrong -> observed/checkpoint state in `RiftWorkspaceRecords`.
- Git tab fails but local records work -> RiftGit/GitHub network/auth/rate-limit path, not the local recorder.
- MCP cannot see local diff -> `rift_workspace_diff` registry/mapping or client tool-schema refresh.
- Records stays on loading placeholders -> `riftworkspace-live-host.js` template/module load, `workspace-live/app.js` initialization, or native query failure (render an error instead of hanging).

## Fix map

Native event capture -> `RiftWorkspaceWatcher.kt`.
Persistent records/checkpoints/local diff -> `RiftWorkspaceRecords.kt`.
Trusted component mount and direct local calls -> `src/riftworkspace-live-host.js`.
Dashboard rendering -> `workspace-live/app.js` + `style.css`.
Remote Git comparison/checkpoint-on-sync -> `src/riftgit.js`.
MCP schema/permission mapping -> `RiftToolHost.kt`; query dispatch -> `RiftToolSandbox.kt`.
Window creation/name -> `src/riftos.js` / RiftDesktop launcher.

## Validation

Start RiftOS, change/create/delete files through at least two different local writers, and confirm one persistent record stream plus correct affected-file state. Restart/reopen the Records window and confirm history survives because it is native, not component memory. Push/pull with RiftGit and confirm the checkpoint receives the Git head while the activity count survives. Verify the optional Git tab and local tab remain distinct, and `rift_workspace_diff` returns the same local changed-file set. Confirm the component mounts without an iframe or messages, handles a failed query visibly, and stops watcher subscriptions when closed.
