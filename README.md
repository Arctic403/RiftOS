# RiftOS

RiftOS is an **Android-hosted user-space operating environment** built around RiftKernel, RiftFS, RiftWorkspace, RiftRT and a desktop window manager. The active product line is the native Android APK targeting **Android 8.0 / API 26+**.

RiftOS does not replace the Android/Linux kernel. Android owns process isolation, permissions, storage providers and hardware access; RiftKernel owns the RiftOS app/process model, desktop windows, brokered capabilities and RiftOS filesystem namespace.

## Current architecture

```text
Android 8+ / Samsung / DeX
        |
    MainActivity
        |
  RiftOS shell WebView
        |
     RiftKernel
   /      |       \
RiftFS  RiftRT  RiftDesktop
  |                 |
Android filesDir     +-- Files / Settings / RiftDev / Rift MCP / apps
+ SAF mounts         |
                    RiftBrowser window chrome
                         |
                  native RiftBrowserWindow
                         |
                  Android System WebView
                         |
                ChatGPT Web compatibility
                         |
                 in-process MCP JSON-RPC
                         |
                    RiftMcpServer
                         |
                    RiftToolHost
                         |
                  riftfs/tool-sandbox
```

There is **no remote Rift MCP relay** in the active architecture. No WSS device client, pairing key, public MCP endpoint or process-start relay provider is required for the RiftBrowser local MCP path.

### Core runtime

- **RiftKernel**: app/process/service authority and native bridge client.
- **RiftFS**: app-private filesystem rooted at `filesDir/riftfs`.
- **RiftWorkspace**: JSON-safe workspace API backed by native RiftFS on Android.
- **SAF mounts**: user-selected external folders through Android Storage Access Framework.
- **RiftDesktop**: draggable/resizable/minimizable/maximizable desktop windows and taskbar.
- **RiftRT v1**: worker/iframe/WASM application runtime integrated with RiftDesktop.
- **RiftDev**: Android editor using a RiftWorkspace-backed IndexedDB compatibility facade.
- **Rift MCP**: local system app for MCP tool permissions and recent tool activity.

## RiftBrowser

RiftBrowser is a normal RiftOS desktop window. RiftOS owns its title bar, address bar, taskbar entry, focus, move/resize/minimize/maximize state; Android currently owns the System WebView content surface positioned inside that window.

On ChatGPT Web, RiftBrowser installs the exact-origin `rift-mcp-app-v1` compatibility adapter. It obtains the live tool manifest from the in-process MCP server and brokers strict structured calls/results. ChatGPT never receives a general filesystem JavaScript object or `RiftNativeDispatcher` access.

The compatibility asset is performance-gated: it must not rescan the complete conversation on every streaming DOM mutation, and it injects a compact manifest once per conversation route rather than on every message.

RiftBrowser is planned to migrate from Android System WebView to RiftEngine/Servo after the compatibility gate in `docs/RIFTBROWSER_ENGINE_MIGRATION.md` passes on real hardware.

## Local Rift MCP

The active tool path is entirely local inside the RiftOS process:

```text
ChatGPT Web
    |
riftbrowser-mcp-app.js
    |
exact-origin WebMessage
    |
RiftBrowserMcpAppBridge
    |
RiftMcpServer
    |
RiftToolHost
    |
riftfs/tool-sandbox
```

Current tool scope:

```text
riftfs/tool-sandbox/
  workspace/
  uploads/
  downloads/
```

Read tools are enabled by default. Write tools remain disabled by default until enabled in the **Rift MCP** system app. The local activity log records tool/path/outcome without storing file contents.

Existing alpha data under the historical `riftfs/browser-sandbox` directory is migrated to `riftfs/tool-sandbox` on first use.

## Files and Settings

The Files app uses the RiftOS window manager and an Explorer-style UI with navigation, address path, list/details view, mount controls and RiftFS/SAF operations.

Settings includes **System diagnostics → Save system dump…**. The dump is privacy-limited and Android opens the system **Save As** picker so the user chooses the destination. Dumps exclude secrets, file contents/names, account data, Android IDs and installed-app lists.

## Android build

The APK workflow is `.github/workflows/riftos-android-apk.yml` and runs on `android-apk` and `main`.

It builds, aligns, signs and verifies the APK; checks API 26+, package/signature and Android-only assets; verifies the local Rift MCP module; rejects the removed DOM Agent, whole-chat streaming scanner, remote MCP relay/client/provider/Activity and removed local-AI binaries; uploads the artifact; and updates the `android-latest` release.

## Branches

- **`main`** — authoritative project source.
- **`android-apk`** — Android staging/validation branch when a staged promotion is useful.

## Documentation

- [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md) — what is implemented, removed and planned.
- [`docs/TRUE_OS_ARCHITECTURE.md`](docs/TRUE_OS_ARCHITECTURE.md) — current RiftKernel/Android boundary.
- [`docs/ANDROID_NATIVE_ARCHITECTURE.md`](docs/ANDROID_NATIVE_ARCHITECTURE.md) — Android host and native services.
- [`docs/RIFTBROWSER_ARCHITECTURE.md`](docs/RIFTBROWSER_ARCHITECTURE.md) — current browser host and MCP compatibility boundary.
- [`docs/RIFTBROWSER_ENGINE_MIGRATION.md`](docs/RIFTBROWSER_ENGINE_MIGRATION.md) — RiftEngine/Servo migration gate.
- [`docs/RIFT_MCP_APP_ARCHITECTURE.md`](docs/RIFT_MCP_APP_ARCHITECTURE.md) — canonical local MCP architecture.
- [`docs/RIFT_BROWSER_MCP_APP.md`](docs/RIFT_BROWSER_MCP_APP.md) — ChatGPT Web compatibility protocol.
- [`docs/RIFTWORKSPACE_WEB_ARCHITECTURE.md`](docs/RIFTWORKSPACE_WEB_ARCHITECTURE.md) — workspace boundary.
- [`docs/RIFTRT-v1.md`](docs/RIFTRT-v1.md) — application runtime ABI.
- [`ROADMAP.md`](ROADMAP.md) — planned RiftScript/developer-platform work.

Earlier DOM Agent, remote MCP relay and WebKit/WASM experiments are retained only in Git history and removed/inactive documentation notes. They are not active runtime paths.
