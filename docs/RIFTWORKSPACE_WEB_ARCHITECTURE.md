# RiftWorkspace Architecture

RiftWorkspace is the controlled project/workspace boundary between RiftOS apps, ChatGPT Web MCP tools, and RiftFS.

## Android runtime path

```text
RiftOS app / Workspace Live
      |
RiftWorkspace API / scoped HTML RPC
      |
riftworkspace-android-adapter.js
      |
RiftAndroid fs.* native calls
      |
filesDir/riftfs/workspace
```

`src/riftworkspace-web.js` remains the common high-level workspace contract; `src/riftworkspace-android-adapter.js` redirects storage to native RiftFS on Android.

## Workspace Live HTML surface

RiftOS now ships a local HTML workspace at `workspace-live/index.html`. It is rendered inside the RiftOS desktop as a sandboxed iframe and watches the same `filesDir/riftfs/workspace` tree used by the MCP tool sandbox.

```text
                 filesDir/riftfs/workspace
                    ^                 ^
                    |                 |
             RiftToolSandbox      RiftWorkspace
                    |                 |
              ChatGPT Web       trusted shell host
                                      |
                              narrow postMessage RPC
                                      |
                           sandboxed local HTML page
```

The local page does **not** receive `RiftAndroid`, `RiftWorkspace`, the MCP bridge, or a generic filesystem object. The parent host accepts a small workspace-only RPC surface and validates/normalizes every path through the existing workspace boundary.

The iframe is created with `sandbox="allow-scripts"` and intentionally omits `allow-same-origin`. That gives the HTML app an opaque origin even though its files are packaged locally.

## Live filesystem observation

`RiftWorkspaceWatcher.kt` recursively observes only `filesDir/riftfs/workspace`. This catches changes regardless of which local component made them, including:

- ChatGPT Web through `RiftToolSandbox`
- RiftFS / Files / Editor writes
- local git or process activity that changes files
- manual edits made in Workspace Live

Native events are delivered to the trusted RiftOS shell and then forwarded to the sandboxed HTML page. The page refreshes the affected file/folder and shows a live activity log plus a compact text diff.

Workspace Live uses SHA-256 revision checks when manually saving an open file. If the file changed since it was loaded, the save is rejected instead of clobbering the newer version.

## Public operations

The workspace supports controlled list/stat/read/write/mkdir/remove/move/copy operations plus snapshot, patch preview/apply, history and rollback surfaces used by project tooling.

Path normalization prevents escaping the workspace/RiftFS boundary.

## MCP separation

RiftWorkspace and RiftBrowser remain separate capabilities:

```text
RiftKernel
  |-- RiftWorkspace -> RiftFS/native storage
  |
  `-- RiftBrowser -> Android System WebView -> guest web content
```

Normal guest webpages never receive `RiftWorkspace` or unrestricted RiftFS authority.

ChatGPT receives capability access only through the exact-origin MCP bridge and only to the canonical `workspace/` tree. It has no MCP path to RiftOS system roots, downloads, documents, mounts, or workspace-history metadata.

Workspace Live is a separate local HTML capability. It does not broaden the ChatGPT origin's permissions.

## Why no localhost server

The HTML UI is packaged with RiftOS and communicates through the existing WebView/native boundary, so no TCP listener, LAN port, remote service, API key, or cloud file service is required. The result behaves like a local Files.com-style control surface while keeping the workspace private to RiftOS.
