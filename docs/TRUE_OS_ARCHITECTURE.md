# RiftOS True OS Architecture

## Goal

RiftOS is no longer structured as a desktop simulation layered on top of a second compatibility runtime. The True OS 1.0 refactor makes the browser or WKWebView a hardware abstraction layer and puts one RiftOS-owned userspace runtime above it.

```text
RiftOS Desktop / RiftDev / RiftApps
              |
          RiftKernel
     _________|_________
    |         |         |
 RiftFS   Processes  Capabilities
    |                   |
 OPFS/IDB          RiftNative
    |                   |
 browser         Swift / iOS APIs
```

## 1. One kernel

`src/riftcore.js` is the only kernel/runtime authority.

It owns:

- process IDs and lifecycle
- system and installed-app registration
- permission grants
- mount table
- boot/system information
- native bridge
- RiftFS

The previous `RiftFS` and `RiftKernel` classes inside `src/riftos.js` are removed.

## 2. One filesystem

RiftFS uses OPFS when available.

The original IndexedDB `riftos/files` store remains only as a compatibility mirror so existing user files survive the refactor. New built-in subsystems no longer open that database directly.

RiftGit and RiftApps both use `window.RiftOSCore.fs`.

### Namespace

```text
/
├── home/
│   └── repos/<owner>/<repo>
├── apps/
│   ├── packages/
│   └── data/
├── system/
└── mounts/
```

### Native mounts

RiftOS Native can expose user-approved iOS Files directories below `/mounts/<name>`.

The same methods are used regardless of backend:

- `get` / `readText`
- `write` / `writeText`
- `mkdir`
- `remove`
- `stat`
- `list`

Native mounts are backed by security-scoped document-picker URLs and bookmark records in the Swift host. Removing a mount does not delete its files.

## 3. Process model

Each visible built-in app, RiftDev session, and installed RiftApp receives a RiftKernel process record.

Protected kernel/desktop/native bridge processes cannot be killed by normal user process controls.

The Tasks app and `ps` read the same process table.

## 4. Capability model

Kernel capabilities:

- `fs.read`
- `fs.write`
- `network`
- `clipboard.read`
- `clipboard.write`
- `share`
- `notifications`
- `process.read`
- `process.manage`
- `system.settings`
- `native.read`
- `native.files`
- `native.background`

Trusted built-ins receive declared capabilities.

Installed `.rift` apps declare a limited package permission set. Sensitive capabilities are granted on first use through the kernel broker.

## 5. Rift Apps

`.rift` v1 remains a JSON/text package format, but installed packages and app data now live in RiftFS instead of a separate `riftapps` IndexedDB runtime.

The refactor migrates existing installed packages/data once.

The sandbox runtime injects:

- scoped storage
- permission requests
- clipboard bridge
- share bridge
- notification bridge

A CSP is injected into each app document. Apps without `network` permission receive `connect-src 'none'`.

## 6. RiftGit

RiftGit no longer opens IndexedDB itself.

Workspaces live at:

```text
/home/repos/<owner>/<repo>
```

The terminal backend supports:

```text
git auth
git clone owner/repo [branch]
git use owner/repo
git repo
git status
git pull
git push <message>
git branches
git switch <branch>
```

Push uses the Git Data API to create one tree and one commit for the entire working set. A remote-head check prevents silently overwriting newer remote work.

## 7. RiftDev

`Arctic403/Editor` remains read-only source material.

The Pages workflow:

1. clones the pinned Editor commit;
2. removes its `.git` metadata;
3. copies it into the staged RiftOS site;
4. injects `riftos-overlay.js` only into that deployed copy.

The source Editor repository is not patched by RiftOS.

## 8. Native bridge

The main RiftOS document is the only WKWebView frame allowed to call the native script-message handler directly. Sandboxed RiftApps must route requests through RiftKernel.

Native bridge methods include:

- `native.capabilities`
- `device.info`
- `files.pickDirectory`
- `files.pickDocument`
- `files.mounts`
- `files.unmount`
- `fs.list`
- `fs.stat`
- `fs.readText`
- `fs.writeText`
- `fs.mkdir`
- `fs.remove`
- `clipboard.readText`
- `clipboard.writeText`
- `share.text`
- `notifications.request`
- `notifications.schedule`

This does not bypass iOS sandboxing.

## 9. Browser-engine separation

RiftEngine/WebCore is paused.

Normal Pages deployments do not build Emscripten, the prototype engine, JSC or WebCore. The engine source and dedicated workflows remain preserved for later work.

This separation means a UI/RiftDev/True OS change should deploy in minutes or seconds instead of waiting on browser-engine compilation.

## 10. Compatibility rule

No new subsystem should create its own filesystem, kernel, process table or permission implementation.

Use:

```js
window.RiftOSCore
```

for system services.

Compatibility stores may exist only as migration/read-through layers and should not become new sources of truth.

## 11. Native RiftBrowser + RiftWorkspace

The iOS host now owns a real browser surface instead of forwarding normal sites to Safari.

`RiftBrowserStore` manages persistent WebKit tab sessions with shared website data, navigation, history gestures, address/search input, popup handling and normal HTTP/HTTPS navigation. Launching the Browser app while native-hosted opens this surface directly; ChatGPT is the default launch destination.

The privileged `riftNative` message handler is **not installed in browser tabs**. It exists only in the RiftOS shell WKWebView. This keeps ordinary sites from seeing filesystem, patch, clipboard or other OS capabilities.

The native host also creates a sandboxed `Documents/RiftWorkspace` tree:

```text
RiftWorkspace/
├── projects/
├── downloads/
├── documents/
├── patches/
└── .rift/
    ├── workspace.json
    └── history/
```

The app exposes Documents through iOS file sharing/open-in-place, so the workspace can also be inspected from Files. RiftWorkspace is automatically surfaced to RiftFS as the protected `/mounts/RiftWorkspace` system mount.

Trusted RiftOS code can call `window.RiftWorkspace` for list/stat/read/write/move/remove operations and for JSON patch transactions. The native patch engine accepts the existing `riftcity-ai-patch` v1/v2 contract, validates per-file `base_sha256` values when supplied, rejects path overlap/traversal, protects `.rift` metadata, snapshots every affected path before applying, and can roll back a completed patch from native history.

A future Rift AI/OpenAI API client can therefore use the narrow tool surface (`list`, `readText`, `previewPatch`, `applyPatch`, `rollback`) without exposing unrestricted iOS filesystem access to websites.
