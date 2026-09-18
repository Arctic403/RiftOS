# RiftWorkspace Web Architecture — Retained Reference

## Status

**HISTORICAL / UNVERIFIED REFERENCE — NOT THE CURRENT BUILT-IN WORKSPACE RECORDS ARCHITECTURE.**

The old web workspace modules and `workspace-live/` assets remain in the repository for tests/reference. Gradle does not package them as the RiftOS shell.

## Current source-proven boundary

The current native engine uses:
- `RiftToolSandbox.kt` for MCP workspace operations and Project Intelligence;
- `RiftWorkspaceRecords.kt` for persistent private records/checkpoints;
- `RiftWorkspaceWatcher.kt` for recursive workspace observation;
- `RiftNativeWorkspaceApps.kt` for the visible native Workspace Records built-in.

The canonical workspace backing tree is `filesDir/riftfs/workspace`. The records store is outside that tree.

`src/riftworkspace-web.js`, `src/riftworkspace-android-adapter.js`, `src/riftworkspace-live-host.js` and `workspace-live/*` are retained reference/test sources unless a later source audit explicitly promotes them.

## Trust rule

Do not use this historical file as evidence for current workspace behavior. Use the native workspace/records owners and their verified subsystem documentation after that subsystem audit is completed.
