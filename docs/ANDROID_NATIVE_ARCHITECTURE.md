# RiftOS Android Native Architecture

The active RiftOS distribution is a native Android APK targeting Android 8.0 / API 26+.

## Runtime stack

```text
RiftOS HTML/JS shell
    -> RiftKernel / RiftDesktop
    -> RiftNativeTransport
    -> exact-origin RiftAndroid WebMessage
    -> MainActivity / RiftNativeDispatcher
    -> Android APIs
```

`MainActivity` hosts the RiftOS shell WebView through `WebViewAssetLoader` and owns the native browser content surface and system-document pickers.

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
- `RiftBrowserWindow` native WebView content plane,
- local `RiftMcpServer` + `RiftToolHost`,
- optional outbound `RiftMcpRelayClient`,
- `RiftMcpActivity` for local grants, audit and relay configuration.

The APK never opens an MCP listening socket. When explicitly enabled, its WSS client authenticates to the configured public relay and forwards MCP JSON-RPC into the same local server.

## Desktop browser host

`RiftBrowserWindow` is created inside `MainActivity`. RiftDesktop sends window bounds/visibility/navigation commands through the native bridge. Android positions the WebView over the browser content rectangle while RiftOS retains title bar, address bar, move/resize, minimize/maximize, focus and taskbar behavior.

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

`RiftBrowserWindow` owns a dedicated native renderer container. The renderer is created behind the `RiftBrowserEngine` contract; `AndroidWebViewBrowserEngine` is the current compatibility backend. Browser minimize/unfocus never keeps a full-host native WebView visible behind the shell: the renderer container becomes Android `GONE` while the engine/session remains allocated.

The WebView backend owns WebView-specific cookies, downloads, popup/auth handling, navigation callbacks and exact-origin MCP injection. This keeps `RiftBrowserWindow` responsible only for RiftOS window geometry/visibility/lifecycle and makes a future renderer swap possible without changing the desktop window contract.

## System Dump

Settings invokes `system.dump.save`. Android generates a JSON diagnostic snapshot and opens `ACTION_CREATE_DOCUMENT`, allowing the user to choose the provider, folder and filename.

The dump includes app/build, Android/WebView, memory/heap/storage and aggregate RiftFS metrics. It excludes file names/content, secrets, account data, Android IDs and installed-app lists.

## Editor removal

RiftDev and its iframe, CDN assets, and credential cache have been removed. An in-house IDE is planned.

## Build/signing

The separate `Arctic403/Riftos-builder` repository builds only through manual dispatch. Signing uses the configured release secrets when present, otherwise the bundled legacy debug identity. The fallback key is public and is unsuitable for trusted production updates.

CI builds the native relay transport and continues to reject the removed DOM Agent, expensive whole-chat MCP scanner and removed local-AI binaries.
