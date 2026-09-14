# RiftOS Android Native Architecture

The active RiftOS distribution is a native Android APK targeting Android 8.0 / API 26+.

## Runtime stack

```text
Android MainActivity
    -> RiftNativeDesktop (desktop / launcher / taskbar / native window authority)
    -> trusted compatibility WebView (existing app bodies + RiftKernel/RiftFS/RiftGit runtime)
         -> RiftNativeTransport
         -> exact-origin RiftAndroid WebMessage
    -> RiftNativeDispatcher / Android APIs
    -> RiftBrowserWindow focused native renderer surface
```

`MainActivity` hosts `RiftNativeDesktop` as the visible shell and is declared Android `singleTask`: one APK process must expose one authoritative RiftOS desktop/kernel/WebView runtime, not multiple independent ProcessTables. The trusted RiftOS WebView is still served through `WebViewAssetLoader`, but it lives inside the native desktop's content layer and is used as a compatibility app-content canvas rather than as the desktop/window manager. Native Android owns launcher/taskbar/window chrome, bounds, focus, move/resize, minimize/maximize/restore/close and Accessibility controls.

## Storage

RiftFS lives in `filesDir/riftfs`. Android initializes `home`, `apps`, `system`, `workspace`, `downloads` and `documents`.

External user folders mount through Storage Access Framework with persisted URI permissions. RiftWorkspace maps its common JSON API onto the native `/workspace` tree.

The canonical project workspace lives at `filesDir/riftfs/workspace` and is shared by Files and workspace tooling. It is also the **only** filesystem root exposed through MCP. Legacy `tool-sandbox/workspace` and `browser-sandbox/workspace` trees are migration input only; unique entries are merged into the canonical workspace without overwriting newer user files. No transfer, system, downloads, documents or SAF-mount root is MCP-addressable.

## Native services

- RiftFS and SAF mounts,
- Android Keystore encrypted secrets,
- clipboard/share/vibration/notifications,
- notification permission request,
- Android file chooser,
- DownloadManager,
- preview Activity for workspace files,
- privacy-limited System Dump + Save As picker,
- `RiftNativeDesktop` desktop/window/taskbar/launcher authority,
- trusted compatibility WebView app-content plane,
- `RiftBrowserWindow` native WebView content plane,
- local `RiftMcpServer` + `RiftToolHost`,
- optional outbound `RiftMcpRelayClient`,
- `RiftMcpActivity` for local grants, audit and relay configuration.

The APK never opens an MCP listening socket. When explicitly enabled, its WSS client authenticates to the configured public relay and forwards MCP JSON-RPC into the same local server.

## Native desktop and browser host

`RiftNativeDesktop` is created inside `MainActivity` before the trusted runtime page loads. It owns the Android-visible desktop, launcher, Start menu, taskbar, native window chrome, geometry/focus state and Back behavior. Existing RiftOS app bodies initially remain inside the trusted compatibility WebView; the JS compatibility layer mirrors Android-published content rectangles but does not own frame geometry or chrome. Dynamic RiftRT/installed/system launcher entries are mirrored into Android controls.

`RiftBrowserWindow` is mounted inside the native desktop content layer. The compatibility app body owns the tab/address/navigation controls while Android positions one dedicated renderer container over that browser body rectangle. The container may hold up to eight per-tab engines, but exactly one selected renderer is visible/clickable at a time; inactive tabs are paused and `View.GONE`. Every renderer is hidden immediately when browser visibility/focus changes.

The obsolete standalone `RiftBrowserActivity` is not part of the current source/build.

On exact ChatGPT Web origins, `RiftBrowserMcpAppBridge` exposes only MCP JSON-RPC messaging to the in-process local server. It does not expose a filesystem JavaScript API or the wider native dispatcher.

## Rift MCP

```text
ChatGPT Web compatibility asset
        |
 exact-origin WebMessage
        |
RiftBrowserMcpAppBridge
        |
   RiftMcpServer
        |
   RiftToolHost
        |
 RiftToolSandbox
```

`RiftToolHost` owns tool schemas, local read/write grants and the bounded audit log. `RiftToolSandbox` enforces canonical-path containment and payload/listing limits.

`RiftMcpRelayClient` is transport-only. It stores its bearer token using Android Keystore, requires TLS (`wss://`) and reconnects with bounded backoff. The existing exact-origin WebMessage route remains available as a fallback during migration.

Read access defaults on. Write access defaults off. The `Rift MCP` system app is the only current settings surface for those grants.

## RiftBrowser engine ownership

`RiftBrowserWindow` owns a dedicated native renderer container plus the bounded browser-tab registry. Each tab is created behind the same `RiftBrowserEngine` contract; `AndroidWebViewBrowserEngine` is the current compatibility backend. Switching tabs pauses/hides the previous engine and exposes only the selected renderer. Browser minimize/unfocus makes every tab renderer and the container Android `GONE` while tab engine/session/history state remains allocated.

The WebView backend owns per-tab rendering, navigation history, downloads, popup/auth handling, callbacks and exact-origin MCP injection; cookies remain the normal shared WebView cookie jar. This keeps `RiftBrowserWindow` responsible for RiftOS browser-window geometry/visibility/tab lifecycle and makes a future renderer swap possible without changing the desktop/tab contract.

## System Dump

Settings invokes `system.dump.save`. Android generates a JSON diagnostic snapshot and opens `ACTION_CREATE_DOCUMENT`, allowing the user to choose the provider, folder and filename.

The dump includes app/build, Android/WebView, memory/heap/storage and aggregate RiftFS metrics. It excludes file names/content, secrets, account data, Android IDs and installed-app lists.

## Editor removal

RiftDev and its iframe, CDN assets, and credential cache have been removed. An in-house IDE is planned.

## Build/signing

The separate `Arctic403/Riftos-builder` repository builds only through manual dispatch. Signing uses the configured release secrets when present, otherwise the bundled legacy debug identity. The fallback key is public and is unsuitable for trusted production updates.

**Signing policy:** Moving to a private signing key is planned for a future release. Do not rotate or replace the signing key, remove the existing fallback, or require signing secrets until the project owner explicitly requests the change. Keep manual builds and the current signing behavior in place until then.

CI builds the native relay transport and continues to reject the removed DOM Agent, expensive whole-chat MCP scanner and removed local-AI binaries.
