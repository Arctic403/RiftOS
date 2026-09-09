# RiftOS

RiftOS is an **Android-hosted user-space operating environment** built around RiftKernel, RiftFS, RiftWorkspace, RiftRT and a desktop window manager. The active product line is the native Android APK targeting **Android 8.0 / API 26+**.

RiftOS does not replace the Android/Linux kernel. Android owns process isolation, permissions, WebView, storage providers and hardware access; RiftKernel owns the RiftOS app/process model, desktop windows, brokered capabilities and RiftOS filesystem namespace.

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
Android filesDir     +-- Files / Settings / RiftDev / Rift Bridge / apps
+ SAF mounts         |
                    RiftBrowser window chrome
                         |
                  native RiftBrowserWindow
                         |
                  Android System WebView

ChatGPT custom app
        |
        | MCP / HTTPS
        v
Remote Rift Bridge adapter
        |
        | paired WSS
        v
Rift Bridge system app
        |
        v
riftfs/browser-sandbox
```

### Core runtime

- **RiftKernel**: app/process/service authority and native bridge client.
- **RiftFS**: app-private filesystem rooted at `filesDir/riftfs`.
- **RiftWorkspace**: JSON-safe workspace API backed by native RiftFS on Android.
- **SAF mounts**: user-selected external folders through Android Storage Access Framework.
- **RiftDesktop**: draggable/resizable/minimizable/maximizable desktop windows and taskbar.
- **RiftRT v1**: worker/iframe/WASM application runtime integrated with RiftDesktop.
- **RiftDev**: Android editor using a RiftWorkspace-backed IndexedDB compatibility facade.
- **Rift Bridge**: system app for AI-tool pairing, capability grants and recent tool activity.

## RiftBrowser

RiftBrowser is a normal RiftOS desktop window. RiftOS owns its title bar, address bar, taskbar entry, focus, move/resize/minimize/maximize state; Android owns the native WebView content surface positioned inside that window.

There is **no active full-screen RiftBrowser Activity** and no active custom WebKit-WASM browser engine in the Android APK.

RiftBrowser treats `chatgpt.com` like ordinary web content. It does **not** inject a tool prompt, filesystem bridge, response parser or Agent runtime into the page.

## Rift Bridge

Rift Bridge is the supported first-class AI-tool path. The phone opens an outbound WSS connection to a remote adapter, and the device remains the capability authority.

Current scope:

```text
riftfs/browser-sandbox/
  workspace/
  uploads/
  downloads/
```

Read tools are enabled by default; write tools require explicit local enablement. Pairing keys are stored with Android Keystore, and a bounded local activity log records tool/path/outcome without storing file contents.

The first remote adapter is `services/rift-mcp-relay`, which exposes Rift Bridge tools as first-class MCP tools to ChatGPT custom apps. MCP is an adapter protocol, not the internal RiftOS capability API.

## Files and Settings

The Files app uses the RiftOS window manager and an Explorer-style UI with navigation, address path, list/details view, mount controls and RiftFS/SAF operations.

Settings includes **System diagnostics → Save system dump…**. The dump is privacy-limited and Android opens the system **Save As** picker so the user chooses the destination. Dumps exclude secrets, file contents/names, account data, Android IDs and installed-app lists.

## Android build

The APK workflow is `.github/workflows/riftos-android-apk.yml` and runs on `android-apk` and `main`.

It builds, aligns, signs and verifies the APK; checks API 26+, package/signature and Android-only assets; rejects removed local-AI binaries, obsolete browser Activity leakage and the removed ChatGPT DOM Agent asset; verifies the Rift Bridge system module; uploads the artifact; and updates the `android-latest` release.

The previous emulator matrix/smoke-test pipeline has been removed. Device diagnostics are handled by the in-app system dump instead.

## Branches

- **`main`** — authoritative project source.
- **`android-apk`** — Android staging/validation branch when a staged promotion is useful.

## Documentation

- [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md) — what is implemented, removed and planned.
- [`docs/TRUE_OS_ARCHITECTURE.md`](docs/TRUE_OS_ARCHITECTURE.md) — current RiftKernel/Android boundary.
- [`docs/ANDROID_NATIVE_ARCHITECTURE.md`](docs/ANDROID_NATIVE_ARCHITECTURE.md) — Android host and native services.
- [`docs/RIFTBROWSER_ARCHITECTURE.md`](docs/RIFTBROWSER_ARCHITECTURE.md) — current windowed System WebView browser.
- [`docs/RIFT_BRIDGE_ARCHITECTURE.md`](docs/RIFT_BRIDGE_ARCHITECTURE.md) — AI-tool bridge, permissions, audit and external adapters.
- [`docs/RIFTWORKSPACE_WEB_ARCHITECTURE.md`](docs/RIFTWORKSPACE_WEB_ARCHITECTURE.md) — current workspace boundary (historical filename retained).
- [`docs/RIFTRT-v1.md`](docs/RIFTRT-v1.md) — application runtime ABI.
- [`ROADMAP.md`](ROADMAP.md) — planned RiftScript/developer-platform work.

Earlier browser-agent and iPhone/WebKit-WASM work is retained only in source history. It is not the active Android runtime.
