# RiftOS

RiftOS is a native-first iOS operating environment built around one RiftKernel runtime and a Swift host.

The production runtime is **RiftOS Native**. GitHub Pages remains available only as a development/demo surface for the same web shell.

## Production architecture

```text
RiftOSNative.app
      |
      +-- bundled RiftOS shell (riftos://)
      |         |
      |     RiftKernel
      |    /    |     \
      | RiftFS apps  RiftKernel.browser
      |                 |
      +----------- native browser bridge
                        |
                 RiftBrowserStore
                        |
                WKWebView per tab
                        |
                   Apple WebKit
```

The installed app no longer boots its privileged shell from GitHub Pages. `index.html`, `src/**`, RiftDev and the rest of the shell are copied into the `.app` during the Xcode build, then served locally through `RiftBundleSchemeHandler` at `riftos:///index.html`.

## RiftKernel

`src/riftcore.js` remains the runtime authority for process lifecycle, apps, capabilities, mounts, RiftFS, system information and native bridge state.

`src/riftbrowser-kernel.js` installs `RiftKernel.browser`. In the native app it is synchronized with the real Swift browser through a narrow `riftBrowser` command channel plus native-state events. The kernel can open/navigate/select/close tabs, go back/forward, reload/stop, switch Desktop/Mobile mode, open Share and Find on Page, and inspect the current native tab state.

## RiftBrowser

The production browser is `native/ios/RiftOSNative/RiftBrowser.swift`.

RiftOS owns the browser product while Apple WebKit owns standards rendering. Current native support includes:

- real multi-tab `WKWebView` browsing
- Desktop Website mode by default
- per-tab Desktop / Mobile switching
- persistent WebKit cookies/site data
- restored browser sessions across launches
- `target=_blank` / `window.open()` into RiftBrowser tabs
- back, forward, reload and stop
- Find on Page
- iOS Share sheet
- navigation/process error UI
- downloads saved directly into `RiftWorkspace/downloads`
- normal non-web URL handoff to iOS when appropriate

Browser tabs never receive `riftNative`, `riftBrowser` or RiftWorkspace filesystem capabilities.

## Native shell boundary

The privileged shell WKWebView loads only the bundled `riftos://` origin. Main-frame HTTP/HTTPS navigation is intercepted and sent to RiftBrowser instead of replacing the shell.

This prevents a normal website from becoming the privileged RiftOS document.

## RiftWorkspace

The native host owns `Documents/RiftWorkspace`:

```text
RiftWorkspace/
├── projects/
├── downloads/
├── documents/
├── patches/
└── .rift/
```

Trusted RiftOS code can use native list/read/write/move/remove methods plus JSON patch preview/apply/history/rollback. Browser downloads land in `downloads/`.

## Web development preview

GitHub Pages still deploys the RiftOS shell for UI/kernel testing. It is **not** the production OS runtime and cannot reproduce unrestricted native browsing because the host browser still enforces normal CORS/CSP/frame rules.

The web-only `web-transport` backend exists for development diagnostics only.

## GitHub Actions

- `.github/workflows/riftos-pages.yml` validates and deploys the web development preview.
- `.github/workflows/riftos-native-ios.yml` validates the native-first contract, builds Simulator + physical-iPhone targets, verifies the bundled shell exists inside the built `.app`, packages the unsigned IPA, and uploads artifacts.

The native workflow now runs when either Swift/native files **or bundled shell files** change, because those shell files are part of the installed application.

## Browser development rule

Do not add another WebCore/JSC/WASM browser engine. Browser features belong in one of these layers:

```text
RiftKernel.browser            OS/browser API and state
RiftBrowserCommandBridge      trusted kernel -> Swift commands
RiftBrowserKernelSync         Swift -> kernel state sync
RiftBrowser.swift             browser behavior/chrome
WKWebView / Apple WebKit      web platform implementation
```

See `docs/RIFTBROWSER_ARCHITECTURE.md` and `docs/TRUE_OS_ARCHITECTURE.md`.
