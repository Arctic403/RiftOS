# RiftOS True OS Architecture

## Goal

RiftOS has one userspace kernel and one native iOS host. The installed app is the primary runtime; the PWA/Pages build is a development preview.

```text
iPhone
  |
RiftOSNative.app
  |
local bundled RiftOS shell
  |
RiftKernel
  |-----------------------------|
RiftFS / apps / processes   BrowserService
  |                             |
RiftNative                  browser command bridge
  |                             |
RiftWorkspace              RiftBrowser.swift
                                |
                             WKWebView
                                |
                           Apple WebKit
```

## 1. Native-first boot

The production shell is bundled into the application at build time and served by `RiftBundleSchemeHandler` from `riftos:///index.html`.

RiftOS Native has no remote privileged-shell fallback. HTTP/HTTPS main-frame navigation from the shell is rerouted into RiftBrowser.

GitHub Pages therefore cannot become the privileged native document.

## 2. One kernel

`src/riftcore.js` owns process lifecycle, app registration, permissions, mounts, system information, native bridge state and RiftFS.

System services attach to this kernel; they do not create parallel runtime authorities.

## 3. Browser service

`src/riftbrowser-kernel.js` installs `RiftKernel.browser`.

The service owns OS-facing browser state and commands. In native mode it talks to Swift through the dedicated `riftBrowser` message handler and receives real tab state from `RiftBrowserKernelSync`.

```text
RiftKernel.browser
      |
      +-- native-webkit -> RiftBrowserCommandBridge -> RiftBrowserStore -> WKWebView
      |
      `-- web-transport -> development-preview fallback only
```

## 4. Native browser

`RiftBrowser.swift` owns the browser product around Apple WebKit.

Current support:

- multi-tab browsing
- Desktop mode by default
- per-tab Mobile/Desktop mode
- restored sessions
- persistent cookies/site data
- popup/new-window tabs
- downloads into RiftWorkspace
- Find on Page
- Share sheet
- error recovery
- back/forward/reload/stop

## 5. Capability boundary

The privileged handlers exist only on the local shell WKWebView.

Browser tabs are normal website contexts and do not receive:

- RiftNative
- RiftBrowser command channel
- RiftWorkspace
- external mount access
- JSON patch APIs

Native integrations must be brokered by trusted RiftOS code.

## 6. RiftWorkspace

The Swift host owns:

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

The workspace provides sandboxed path normalization, native file operations, SHA-256 support and transactional patch history/rollback. Browser downloads now use the same workspace.

## 7. RiftFS

RiftFS remains the kernel filesystem abstraction for shell/apps. Web storage backends are compatibility/runtime storage for the shell; native filesystem authority is exposed through RiftNative/RiftWorkspace rather than giving website tabs direct filesystem access.

## 8. Processes and apps

Built-ins, RiftDev sessions and installed RiftApps receive RiftKernel process records. RiftApps remain sandboxed and use brokered permissions.

## 9. RiftDev

RiftDev remains a pinned mirror of `Arctic403/Editor`. Pages stages that mirror for the development preview, and the current mirror is also bundled into native builds so RiftDev remains available when RiftOS boots offline.

## 10. Build boundaries

Active CI has only two product paths:

- `riftos-pages.yml` — web development preview validation/deploy
- `riftos-native-ios.yml` — production native compile/bundle validation

The native job validates that the built `.app` contains `Web/index.html` and the RiftKernel/browser modules before it packages artifacts.

## 11. Compatibility rule

GitHub Pages, service workers, OPFS and IndexedDB are not the definition of RiftOS. They are host capabilities used by the web preview or compatibility layers.

The production architecture is the bundled RiftKernel shell plus Swift native services.
