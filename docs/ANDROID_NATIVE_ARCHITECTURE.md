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

The canonical project workspace lives at `filesDir/riftfs/workspace` and is shared by Files, RiftDev and Rift AI Code Mode. It is also the **only** filesystem root exposed through MCP. Legacy `tool-sandbox/workspace` and `browser-sandbox/workspace` trees are migration input only; unique entries are merged into the canonical workspace without overwriting newer user files. No transfer, system, downloads, documents or SAF-mount root is MCP-addressable.

## Native services

- RiftFS and SAF mounts,
- Android Keystore encrypted secrets,
- clipboard/share/vibration/notifications,
- notification permission request,
- Android file chooser,
- DownloadManager,
- preview Activity for RiftDev workspace files,
- privacy-limited System Dump + Save As picker,
- `RiftBrowserWindow` native WebView content plane,
- local-only `RiftMcpServer` + `RiftToolHost`,
- `RiftMcpActivity` for local read/write grants and audit.

There is no remote MCP relay runtime, WSS device client, pairing provider or public endpoint in the active APK architecture.

## Desktop browser host

`RiftBrowserWindow` is created inside `MainActivity`. RiftDesktop sends window bounds/visibility/navigation commands through the native bridge. Android positions the WebView over the browser content rectangle while RiftOS retains title bar, address bar, move/resize, minimize/maximize, focus and taskbar behavior.

The obsolete standalone `RiftBrowserActivity` is not part of the current source/build.

On exact ChatGPT Web origins, `RiftBrowserMcpAppBridge` exposes only MCP JSON-RPC messaging to the in-process local server. It does not expose a filesystem JavaScript API or the wider native dispatcher.

## Local Rift MCP

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

Read access defaults on. Write access defaults off. The `Rift MCP` system app is the only current settings surface for those grants.

## Rift AI native support

`MainActivity` brokers local `ai.*` shell commands for session state, event polling, project-tree metadata, diffs, accept/revert and ChatGPT-Web visibility. `RiftAiJournal` stores persistent rollback/event state in `filesDir/rift-ai`. That directory is deliberately outside the MCP sandbox.

`RiftBrowserWindow` has a transport-only mode used by Rift AI. While `transportActive` is true, the existing ChatGPT WebView remains fully laid out and Android-`INVISIBLE`; no extra AI WebView, model HTTP client, API key or public listener is introduced. Terminal complete/stopped/error state releases the invisible renderer unless the user explicitly revealed ChatGPT. On a new process, `RiftAiJournal` recovers any persisted active-transport marker as `interrupted` because the old WebView execution context no longer exists.

RiftBrowser injects a native-created AI session ID into local MCP request `_meta`; `RiftMcpServer`/`RiftToolHost` use it only to scope journal/audit events to the active Rift AI task. It is not a model-supplied capability and does not expand MCP access.

## System Dump

Settings invokes `system.dump.save`. Android generates a JSON diagnostic snapshot and opens `ACTION_CREATE_DOCUMENT`, allowing the user to choose the provider, folder and filename.

The dump includes app/build, Android/WebView, memory/heap/storage and aggregate RiftFS metrics. It excludes file names/content, secrets, account data, Android IDs and installed-app lists.

## RiftDev

The active Android editor is `apps/riftdev/riftdev-android.js`. During APK asset generation, legacy IndexedDB calls are rewritten to `RiftDevAndroidDB`, which stores through RiftWorkspace.

Local Test uses the native preview path rather than a service worker.

## Build/signing

`.github/workflows/riftos-android-apk.yml` builds on `android-apk` and `main`, signs/verifies the APK and publishes `android-latest`.

CI also rejects removed remote MCP bridge source/runtime, the removed DOM Agent, the expensive whole-chat MCP scanner and removed local-AI binaries.
