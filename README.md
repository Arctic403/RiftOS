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
Android filesDir     +-- Files / Settings / RiftDev / apps
+ SAF mounts         |
                    RiftBrowser window chrome
                         |
                  native RiftBrowserWindow
                         |
                  Android System WebView
                         |
              ChatGPT sandbox + Rift Agent v3
```

### Core runtime

- **RiftKernel**: app/process/service authority and native bridge client.
- **RiftFS**: app-private filesystem rooted at `filesDir/riftfs`.
- **RiftWorkspace**: JSON-safe workspace API backed by native RiftFS on Android.
- **SAF mounts**: user-selected external folders through Android Storage Access Framework.
- **RiftDesktop**: draggable/resizable/minimizable/maximizable desktop windows and taskbar.
- **RiftRT v1**: worker/iframe/WASM application runtime integrated with RiftDesktop.
- **RiftDev**: Android editor using a RiftWorkspace-backed IndexedDB compatibility facade.

## RiftBrowser

RiftBrowser is a normal RiftOS desktop window. RiftOS owns its title bar, address bar, taskbar entry, focus, move/resize/minimize/maximize state; Android owns the native WebView content surface positioned inside that window.

There is **no active full-screen RiftBrowser Activity** and no active custom WebKit-WASM browser engine in the Android APK.

For `https://chatgpt.com`, RiftBrowser exposes an exact-origin, app-private filesystem sandbox rooted at:

```text
riftfs/browser-sandbox/
  workspace/
  uploads/
  downloads/
```

When **Rift Agent v3** is enabled, RiftBrowser behaves like a persistent custom tool runtime without becoming MCP. The first task in each ChatGPT conversation carries a compact one-time filesystem-tool bootstrap; later tasks carry only a tiny `RIFT_AGENT_V3 fs1` marker. ChatGPT tool blocks are detected from rendered DOM code blocks, approved sandbox operations execute locally, and hidden result turns continue the same conversation automatically.

V3 removes model-visible UUID security tokens. Replay limits, tool allowlisting, call validation, round limits, exact-origin checks and sandbox path enforcement are handled by RiftBrowser/native code instead. Rift Agent makes **no separate OpenAI API calls**; it rides the existing ChatGPT Web session.

## Files and Settings

The Files app uses the RiftOS window manager and an Explorer-style UI with navigation, address path, list/details view, mount controls and RiftFS/SAF operations.

Settings includes **System diagnostics → Save system dump…**. The dump is privacy-limited and Android opens the system **Save As** picker so the user chooses the destination. Dumps exclude secrets, file contents/names, account data, Android IDs and installed-app lists.

## Android build

The APK workflow is `.github/workflows/riftos-android-apk.yml` and runs on `android-apk` and `main`.

It builds, aligns, signs and verifies the APK; checks API 26+, package/signature and Android-only assets; rejects removed local-AI binaries and obsolete browser Activity leakage; verifies Rift Agent v3 is packaged; uploads the artifact; and updates the `android-latest` release.

The previous emulator matrix/smoke-test pipeline has been removed. Device diagnostics are handled by the in-app system dump instead.

## Branches

- **`main`** — authoritative project source.
- **`android-apk`** — Android staging/validation branch when a staged promotion is useful.

## Documentation

- [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md) — what is implemented, removed and planned.
- [`docs/TRUE_OS_ARCHITECTURE.md`](docs/TRUE_OS_ARCHITECTURE.md) — current RiftKernel/Android boundary.
- [`docs/ANDROID_NATIVE_ARCHITECTURE.md`](docs/ANDROID_NATIVE_ARCHITECTURE.md) — Android host and native services.
- [`docs/RIFTBROWSER_ARCHITECTURE.md`](docs/RIFTBROWSER_ARCHITECTURE.md) — current windowed System WebView browser.
- [`docs/RIFTBROWSER_CHATGPT_SANDBOX.md`](docs/RIFTBROWSER_CHATGPT_SANDBOX.md) — ChatGPT sandbox and Rift Agent v3.
- [`docs/RIFTWORKSPACE_WEB_ARCHITECTURE.md`](docs/RIFTWORKSPACE_WEB_ARCHITECTURE.md) — current workspace boundary (historical filename retained).
- [`docs/RIFTRT-v1.md`](docs/RIFTRT-v1.md) — application runtime ABI.
- [`ROADMAP.md`](ROADMAP.md) — planned RiftScript/developer-platform work.

Earlier iPhone/WebKit-WASM work is retained only as historical context where explicitly labeled. It is not the active Android runtime.
