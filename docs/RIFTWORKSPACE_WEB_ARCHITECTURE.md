# RiftWorkspace Web Architecture

RiftWorkspace is the unsigned RiftKernel workspace boundary. It runs inside the same Apple WebKit host as RiftOS and stores its local sandbox through RiftFS, which uses OPFS when available and IndexedDB as the compatibility fallback.

The workspace does **not** depend on `WKWebView`, Safari controller APIs, a signed iOS executable, WebAssembly, Gecko, or direct access to the iPhone filesystem.

## Runtime path

```text
RiftOS Home Screen web app
        |
     RiftKernel
        |
  RiftWorkspace JSON API
        |
      RiftFS
        |
       OPFS
```

The local sandbox is physically rooted inside RiftFS at `/workspace`, but callers see a workspace-relative root `/`.

Default directories:

```text
/
├── projects/
├── downloads/
├── documents/
├── patches/
└── .rift/
    ├── history/
    └── rolled-back/
```

`.rift` is reserved for kernel-managed patch history and rollback data.

## Public workspace API

`window.RiftWorkspace` is available in the unsigned WebKit runtime and keeps the same high-level contract as the optional native workspace:

- `info()`
- `list(path, options)`
- `stat(path)`
- `readText(path)` / `readJSON(path)`
- `writeText(path, text)` / `writeJSON(path, value)`
- `mkdir(path)`
- `remove(path)`
- `move(path, newPath)`
- `previewPatch(patch)`
- `applyPatch(patch)`
- `history()`
- `rollback(historyId)`
- `snapshot(path, options)`

When the optional native host is present, the compatibility facade may route native workspace operations through `RiftNative`. In the unsigned runtime the same API is backed entirely by RiftFS/OPFS.

## JSON bridge

`window.RiftWorkspaceJSON.invoke(request)` provides a JSON-safe RPC surface for RiftOS apps and AI handoff code.

Example request:

```json
{
  "id": "req-1",
  "method": "workspace.readText",
  "args": {
    "path": "projects/RiftOS/src/riftcore.js"
  }
}
```

Example response:

```json
{
  "id": "req-1",
  "ok": true,
  "result": "...file contents..."
}
```

The bridge also accepts same-origin `postMessage` requests using `type: "riftworkspace:request"`. Cross-origin pages do not receive workspace access merely because they are displayed by a browser engine.

## Patch contract

The web workspace implements the existing Rift AI patch contract rather than inventing a second format.

Supported versions are 1 and 2. Supported actions are `write`, `delete`, and `move`; `rename` is normalized to `move`. Version 2 is required for move/rename.

The patch engine validates paths, overlapping operations, destination conflicts, optional `base_sha256` guards, and the 500-change limit before applying changes. Applied patches save rollback state under `.rift/history`.

## Browser boundary

RiftBrowser and RiftWorkspace are intentionally separate capabilities.

The current unsigned RiftBrowser experiment runs Gecko compiled to WebAssembly inside the WebKit-hosted RiftOS environment. That changes how guest webpages are rendered, but it does **not** grant those webpages direct workspace authority.

```text
RiftKernel
  |-- RiftWorkspace -> RiftFS -> OPFS
  |
  `-- RiftBrowser -> Gecko WASM -> guest web content
```

Guest pages may only interact with workspace data through an explicit, brokered JSON interface that RiftKernel chooses to expose. They never receive raw OPFS handles or unrestricted `window.RiftWorkspace` access.

This separation is the key design for future ChatGPT/workspace integration: the browser can host the AI surface while RiftKernel remains the trusted filesystem and patch authority.

## Architecture rule

> RiftOS core and RiftWorkspace MUST NOT require WebAssembly, Gecko, or native iOS code to boot.

WebAssembly and the optional native host may add capabilities, but neither is a dependency of the local workspace.
