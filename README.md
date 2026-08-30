# RiftOS

RiftOS is a touch-first operating environment with one userspace runtime and two host modes:

- **Web/PWA mode** on Safari and other modern browsers.
- **RiftOS Native** inside a thin Swift/WKWebView iOS host.

The True OS refactor removes duplicated runtime layers. RiftOS now has one kernel API, one filesystem API, one process table, one shell path, one capability model, and one browser-service contract.

## True OS Core

### RiftKernel

`src/riftcore.js` is the system authority. It owns boot state, process/PID lifecycle, application registration, capability grants, RiftNative bridge state, mounts, and RiftFS.

Browser policy is attached as the first-class `RiftKernel.browser` service by `src/riftbrowser-kernel.js`. Browser UI code does not choose engines directly.

### RiftFS

RiftFS prefers **OPFS** and keeps the original `riftos` IndexedDB store as a compatibility mirror.

```text
/
├── home/
├── apps/
├── system/
└── mounts/
```

When RiftOS runs inside the native iOS host, user-approved Files folders can be mounted below `/mounts` through the same RiftFS API used by web mode.

### RiftShell

RiftShell talks to RiftKernel/RiftFS. In addition to filesystem, process, storage, capability and Git commands, the shell can drive the browser service:

```text
browser [url]
browserctl status
browserctl tabs
browserctl renderers
browserctl renderer <auto|renderer-id>
browserctl new [url]
browserctl back
browserctl forward
browserctl reload
browserctl bookmark [url]
browserctl bookmarks
```

### Rift Apps

`.rift` packages live below RiftFS `/apps`. Installed apps launch in sandboxed iframes and request declared capabilities through the kernel permission broker.

### RiftGit

RiftGit uses RiftFS directly. GitHub workspaces live under:

```text
/home/repos/<owner>/<repo>
```

Push creates one Git commit containing the complete local change set instead of one commit per file.

### RiftDev

RiftDev remains a pinned, read-only clone of `Arctic403/Editor`. RiftOS modifies only the staged Pages mirror by adding its integration overlay. Files under `apps/riftdev` are generated mirror content, not a second RiftOS architecture source.

## RiftBrowser: one service, replaceable renderers

RiftBrowser is no longer defined by whichever engine happens to draw a page. The stable API is `RiftKernel.browser` / `window.RiftBrowser`.

```text
RiftBrowser UI / RiftShell / RiftApps
                 |
          RiftKernel.browser
      ___________|____________
     |            |            |
Native WebKit  RiftEngine   Web transport
   backend      backend       fallback
```

The service owns logical tabs, active-tab selection, navigation intent, history/bookmark metadata, renderer selection, and renderer capability discovery. Renderer implementations register behind one contract and can be replaced without rewriting browser chrome or shell commands.

Current renderer priority in `auto` mode:

1. **`native-webkit`** — full native `WKWebView` surface when RiftOS runs in the Swift host.
2. **`riftengine`** — future local WebCore/JSC WASM renderer when `window.RiftEngineBrowserBackend` passes its availability gate.
3. **`web-transport`** — PWA fallback for CORS-readable text/HTML plus external-open fallback for sites a normal web page cannot embed.

The PWA fallback is intentionally not presented as a full browser engine. Browser restrictions such as CSP, frame policy and CORS still apply until a full renderer backend is available.

### Renderer extension point

A future renderer registers through:

```js
window.RiftBrowserRendererContract.register({
  id: "my-renderer",
  name: "My Renderer",
  priority: 150,
  available: () => true,
  capabilities: { fullWeb: true },
  open: async ({ url, tab }) => ({ mode: "custom", url })
});
```

See `docs/RIFTBROWSER_ARCHITECTURE.md` for the contract and ownership rules.

## Native RiftOS

`native/ios` contains the Swift host. It provides persistent Files mounts, document picking, clipboard/share, device information, notifications, RiftWorkspace, JSON patch transactions, and the native browser renderer.

The native RiftBrowser uses multi-tab WebKit with persistent website data. The privileged `RiftNative` message handler is **not** installed in normal browser tabs, so sites such as ChatGPT do not receive RiftOS filesystem or patch privileges.

A dedicated `Documents/RiftWorkspace` is exposed through iOS Files and mounted in RiftFS at `/mounts/RiftWorkspace`. Trusted RiftOS code receives `window.RiftWorkspace` for constrained read/write/list/move operations plus `riftcity-ai-patch` preview/apply/history/rollback.

## RiftEngine status

RiftEngine is still the long-term custom local renderer path, but it is no longer a separate browser architecture. Its job is to implement the `RiftKernel.browser` renderer contract.

The heavyweight JSC/WebCore port remains isolated behind dedicated Actions workflows. Normal RiftOS UI/kernel deployments do **not** rebuild Emscripten/WebKit. This lets browser-service, UI and OS work move quickly while the expensive engine port progresses independently.

See `riftengine/README.md` and `riftengine/webcore/README.md`.

## Deploy and validation

The normal Pages workflow validates all RiftOS JavaScript browser/kernel modules, assembles the shell plus pinned RiftDev mirror, and deploys GitHub Pages.

The native iOS workflow runs for `native/ios/**`, uses the current Xcode 26 toolchain, generates the Xcode project with XcodeGen, and compiles both Simulator and unsigned physical-iPhone targets.

`.github/workflows/riftos-testflight.yml` is an optional manual App Store Connect/TestFlight delivery path for accounts with Apple distribution signing material. It is not required for Web/PWA development or for unsigned native compile validation.
