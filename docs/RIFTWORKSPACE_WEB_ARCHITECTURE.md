# RiftWorkspace Architecture

RiftWorkspace is the controlled project/workspace boundary between RiftOS apps, MCP tools, local filesystem writers, and RiftFS.

## Android runtime path

```text
RiftOS app / Workspace Records
      |
RiftWorkspace API / narrow records RPC
      |
riftworkspace-android-adapter.js
      |
RiftAndroid fs.* native calls
      |
filesDir/riftfs/workspace
```

`src/riftworkspace-web.js` remains the common high-level workspace contract; `src/riftworkspace-android-adapter.js` redirects storage to native RiftFS on Android.

## Workspace Records surface

The historical `workspace-live/` asset folder now ships the **Workspace Records** dashboard. It is rendered inside RiftOS as a sandboxed iframe and observes the same canonical `filesDir/riftfs/workspace` tree used by Files, RiftWorkspace and the MCP tool sandbox.

```text
                    filesDir/riftfs/workspace
                       ^            ^
                       |            |
                RiftToolSandbox  RiftWorkspace / Files / Git / shell
                       |            |
                       +-----+------+
                             |
                    RiftWorkspaceWatcher
                             |
                    RiftWorkspaceRecords
                     /                 \
         rift_workspace_diff       trusted shell host
                                         |
                                 narrow postMessage RPC
                                         |
                              sandboxed records dashboard
```

The dashboard does **not** receive `RiftAndroid`, `RiftWorkspace`, MCP, RiftShell, or a generic filesystem object. Its parent host exposes records/info/read and Git-diff queries plus a records-only checkpoint action. It deliberately has no direct workspace write/remove/move/copy/mkdir RPC.

The iframe uses `sandbox="allow-scripts"` and intentionally omits `allow-same-origin`, giving it an opaque origin even though its assets are packaged locally.

## Persistent filesystem observation

`RiftWorkspaceWatcher.kt` recursively observes only `filesDir/riftfs/workspace` and starts with the RiftOS shell session. It catches changes regardless of which local component made them, including:

- MCP through `RiftToolSandbox`;
- RiftFS / Files / RiftWorkspace writes;
- RiftGit operations;
- RiftShell/process-backed local work;
- other local writers inside the canonical workspace.

`RiftWorkspaceRecords.kt` persists records under app-private `filesDir/rift-workspace-records`, outside the project tree. It keeps rolling observed state for event-to-event history and a separate checkpoint state for the complete current local working diff. Text files within the recorder limit receive bounded git-style diffs; binary/oversized files are represented by hashes and metadata transitions.

Directory move/delete events trigger full reconciliation so descendant changes are not lost when Android can no longer stat the removed path as a directory.

## Local diff and Git diff are separate

The local records/checkpoint diff is private and network-independent. It is exposed to MCP through read-only `rift_workspace_diff` and to the dashboard through the trusted records host.

The dashboard's Git tab uses `RiftGit.workspaceDiff()` to compare `/workspace/RiftOS-main` against the current remote `Arctic403/RiftOS#main` tree. Successful workspace Git push/pull creates a new records checkpoint tagged with the resulting Git head SHA.

A manual **New checkpoint** only updates record-baseline metadata. It does not approve, reject, accept, deny, rollback, or alter workspace files.

## Public workspace operations

RiftWorkspace itself still supports controlled list/stat/read/write/mkdir/remove/move/copy plus snapshot and project patch/history surfaces used by other RiftOS tooling. Those mutation APIs are **not** exposed to the Workspace Records iframe.

Path normalization prevents escaping the workspace/RiftFS boundary.

## MCP separation

RiftWorkspace and RiftBrowser remain separate capabilities:

```text
RiftKernel
  |-- RiftWorkspace -> RiftFS/native storage
  |
  `-- RiftBrowser -> renderer -> guest web content
```

Normal guest webpages never receive RiftWorkspace or unrestricted RiftFS authority. MCP receives only its fixed capability registry and workspace-scoped paths. The records store itself is outside the ordinary workspace namespace and is readable only through the dedicated bounded `rift_workspace_diff` query.

## Why no localhost server

The records UI is packaged with RiftOS and communicates through the existing trusted-shell/iframe boundary, so it needs no TCP listener, LAN port, remote service, API key, or cloud file service. Persistent records remain app-private on the device.
