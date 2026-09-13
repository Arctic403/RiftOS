# RiftWorkspace

## Purpose

RiftWorkspace is the controlled project API shared by project-oriented RiftOS UI/runtime features. It gives callers a JSON-safe workspace abstraction while Android stores data in the canonical `filesDir/riftfs/workspace` tree.

## Source ownership

- `src/riftworkspace-web.js` — high-level workspace contract and project/history/patch abstractions.
- `src/riftworkspace-android-adapter.js` — Android-native workspace/mount transfer adapter.
- `RiftWorkspaceWatcher.kt` — native live observation of the canonical workspace.
- `src/riftworkspace-live-host.js` / `workspace-live/` — narrow HTML live UI transport.

MCP's `RiftToolSandbox` reaches the same physical workspace independently; it does not call through the Workspace Records iframe. The separate `rift_workspace_diff` tool reads bounded records/checkpoint data from `RiftWorkspaceRecords`.

## Runtime selection

`riftworkspace-web.js` is now strictly the common/local workspace layer; it no longer contains a second legacy `workspace.*` native RPC path. It exposes the high-level API plus `RiftWorkspaceJSON.invoke`, a JSON-safe compatibility surface for trusted same-origin callers. On Android, `riftworkspace-android-adapter.js` replaces `window.RiftWorkspace` after the common layer loads; the JSON invoker resolves `window.RiftWorkspace` at call time so it always uses the active Android adapter. The Android adapter is the single Android workspace implementation and maps logical paths and mount transfer helpers onto RiftFS/native operations.

## Why this boundary exists

Project features need more structure than raw file IO: snapshots, history, patch preview/apply and consistent JSON-safe results. Keeping this boundary separate from browser guest content means local project UI can be powerful without granting normal webpages filesystem access.

## Workspace authority

The canonical workspace is the user-owned project tree. It starts empty on a fresh install. Files, MCP project tooling and Workspace Records observation all converge on this tree. Workspace APIs normalize paths and must not escape it when operating in workspace-scoped mode.

## Failure signatures

- Workspace Records and MCP disagree on changed files -> verify canonical workspace scope plus recorder observed/checkpoint state.
- Web workspace works but Android build does not -> native adapter/bridge path.
- Mount import/export fails -> Android adapter + RiftFS/dispatcher transfer path.
- Snapshot/history/patch behavior wrong -> high-level `RiftWorkspaceWeb` methods.

## Fix map

High-level project API -> `riftworkspace-web.js`.
Android path/mount handoff -> Android adapter.
Filesystem mechanics -> RiftFS/native dispatcher.
Persistent change records/diff UI -> Workspace Records subsystem.
MCP-specific transaction/search tools -> MCP sandbox.

## Validation

Verify list/stat/read/write/mkdir/remove/move/copy, project snapshot/history/patch surfaces, native Android mode, mount copy/move helpers and consistency with the Files app/MCP view of the same workspace.
