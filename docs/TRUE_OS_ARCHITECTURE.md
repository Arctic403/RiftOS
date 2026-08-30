# RiftOS True OS Architecture

## Goal

RiftOS has one userspace operating environment above the host browser or native WKWebView. Host technology is treated as a hardware/platform layer, not as the owner of RiftOS application policy.

```text
RiftOS Desktop / RiftDev / RiftApps / RiftShell
                    |
                RiftKernel
       ______________|_______________
      |          |          |         |
   RiftFS    Processes  Capabilities  BrowserService
      |                                  |
 OPFS/IDB                         Renderer contract
                                      /   |   \
                              WebKit  Engine  Web fallback
      |
  RiftNative <---------------- Swift / iOS APIs
```

## 1. One kernel

`src/riftcore.js` is the runtime authority. It owns process IDs/lifecycle, app registration, grants, mounts, boot/system information, RiftNative and RiftFS.

`src/riftbrowser-kernel.js` attaches the browser subsystem as `RiftKernel.browser`. It is a kernel service, not a second kernel and not an engine-specific app runtime.

No new subsystem should create a competing filesystem, process table, permission system or browser-policy store.

## 2. One filesystem

RiftFS uses OPFS when available. The original IndexedDB `riftos/files` store remains only as a compatibility mirror so existing user data survives migrations.

RiftGit and RiftApps use `window.RiftOSCore.fs`.

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

RiftOS Native can expose user-approved Files directories under `/mounts/<name>`. Security-scoped URLs/bookmarks are resolved by the Swift host; unmounting never deletes the underlying files.

## 3. Process model

Visible built-ins, RiftDev sessions and installed RiftApps receive RiftKernel process records. Protected kernel/desktop/native processes cannot be killed by normal user controls. Tasks and `ps` read the same table.

The Web/PWA RiftBrowser window receives a normal browser process record. A native renderer can present outside the HTML desktop while still being launched through the browser service.

## 4. Capability model

Kernel capabilities include:

- `fs.read`, `fs.write`
- `network`
- `clipboard.read`, `clipboard.write`
- `share`, `notifications`
- `process.read`, `process.manage`
- `system.settings`
- `native.read`, `native.files`, `native.background`

Trusted built-ins receive declared capabilities. Installed `.rift` apps request only their declared package permissions through the broker.

A renderer is **not** automatically granted filesystem capabilities merely because it renders browser content.

## 5. Rift Apps

`.rift` packages and app data live in RiftFS. The sandbox injects scoped storage, permission requests, clipboard/share/notification bridges and CSP. Apps without `network` permission receive `connect-src 'none'`.

## 6. RiftGit

Workspaces live at `/home/repos/<owner>/<repo>`. Push uses the Git Data API to make one tree/commit for the working set, with a remote-head check to prevent silent overwrite.

## 7. RiftDev

`Arctic403/Editor` is read-only source material. Pages clones a pinned commit, removes Git metadata, copies it into the staging site and injects only the RiftOS overlay. `apps/riftdev` therefore remains generated mirror content.

## 8. Native bridge

Only the trusted top-level RiftOS shell WKWebView receives the `riftNative` message handler. Sandboxed apps and browser pages do not.

Native methods cover device/filesystem mounts, workspace transactions, clipboard/share, notifications and browser-surface control. Native access remains inside iOS sandbox/user-permission boundaries.

## 9. RiftKernel BrowserService

`RiftKernel.browser` is the stable browser control plane. It is exposed as `window.RiftBrowser` for trusted RiftOS UI/shell code.

The service owns:

- logical tab records and active-tab selection;
- navigation intent and URL/search normalization;
- logical history/bookmark metadata;
- renderer registry, selection and capability discovery;
- renderer-independent shell/UI API;
- persistence of browser metadata in the RiftOS origin.

Primary API:

```js
RiftOSCore.kernel.browser.open(url)
RiftOSCore.kernel.browser.newTab(url)
RiftOSCore.kernel.browser.navigate(url)
RiftOSCore.kernel.browser.back()
RiftOSCore.kernel.browser.forward()
RiftOSCore.kernel.browser.reload()
RiftOSCore.kernel.browser.listTabs()
RiftOSCore.kernel.browser.bookmarks()
RiftOSCore.kernel.browser.rendererStatus()
RiftOSCore.kernel.browser.setRenderer("auto")
```

`src/riftbrowser-ui.js` is only an adapter between this service and the current desktop/RiftShell UI. Engine choice does not live in `src/riftos.js`.

## 10. Renderer contract

A renderer provides at minimum:

```js
{
  id,
  name,
  priority,
  capabilities,
  available: () => boolean,
  open: async ({ url, tab, newTab, service }) => result
}
```

Optional renderer-private operations may be exposed while the kernel keeps cross-renderer browser policy above them. Render results identify a mode (`native`, `document`, `external`, `riftengine`, or a future custom mode) and may update URL/title metadata.

Built-ins are:

### `native-webkit`

Available only when the Swift host is connected. It delegates page rendering to the native RiftBrowser `WKWebView` surface. This is the current full-web renderer on iOS Native.

### `riftengine`

An integration slot for the custom local WebCore/JSC WASM stack. It becomes available only when `window.RiftEngineBrowserBackend` exists and passes its own readiness gate. RiftEngine no longer owns browser chrome, top-level tab policy or a second browser API.

### `web-transport`

Always available in PWA mode. It can directly fetch/sanitize CORS-readable text/HTML and otherwise returns an external-open result. It cannot bypass normal browser CORS/CSP/frame restrictions and is deliberately labeled a fallback, not a full browser engine.

Auto-selection currently prefers native WebKit, then a ready RiftEngine backend, then web transport.

## 11. Browser security boundary

Rendered websites do not receive `RiftNative` or `RiftWorkspace` simply because RiftBrowser opened them.

- Native browser tabs are created without the `riftNative` script-message handler.
- Web fallback documents render in sandboxed iframes after active script/event-handler stripping.
- RiftEngine implementations must expose only browser-rendering primitives unless a separate kernel capability grant is explicitly designed.

This keeps “browser can view a site” separate from “site can control RiftOS.”

## 12. RiftEngine build isolation

The kernel browser integration is active; the heavyweight WebCore port remains isolated. Normal Pages changes do not install Emscripten or rebuild JSC/WebCore. Dedicated engine workflows remain the only place for those expensive builds.

When RiftEngine becomes usable, integration is a renderer registration, not a browser rewrite.

## 13. Native RiftWorkspace

The iOS host creates:

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

It is exposed through iOS Files and mounted as `/mounts/RiftWorkspace`. Trusted code can use `window.RiftWorkspace` for constrained file operations and transactional `riftcity-ai-patch` preview/apply/history/rollback. `.rift` metadata is protected.

## 14. Compatibility rule

Use `window.RiftOSCore` for system services and `RiftOSCore.kernel.browser` for browser control. Compatibility layers may exist for migration/read-through only and must not become new sources of truth.
