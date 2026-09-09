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
              ChatGPT sandbox + Rift Agent
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

When **Rift Agent** is enabled, RiftBrowser automatically wraps user tasks with the sandbox tool contract, detects rendered ChatGPT tool blocks in the DOM, executes approved sandbox operations locally and feeds results back into the same conversation. Internal protocol/tool-result turns are visually hidden; the bridge remains browser-side and is **not native ChatGPT MCP/tool registration**.

## Files and Settings

The Files app uses the RiftOS window manager and an Explorer-style UI with navigation, address path, list/details view, mount controls and RiftFS/SAF operations.

Settings includes **System diagnostics → Save system dump…**. The dump is privacy-limited and Android opens the system **Save As** picker so the user chooses the destination. Dumps exclude secrets, file contents/names, account data, Android IDs and installed-app lists.

## Android build

The APK workflow is `.github/workflows/riftos-android-apk.yml` and runs on `android-apk` and `main`.

It:

1. builds the release APK with Java 17 / Gradle,
2. aligns and signs it,
3. verifies API 26+, package/signature and Android-only assets,
4. rejects removed local-AI binaries and obsolete browser Activity leakage,
5. uploads the APK artifact and updates the `android-latest` release.

The previous emulator matrix/smoke-test pipeline has been removed. Device diagnostics are handled by the in-app system dump instead.

## Branches

- **`main`** — authoritative project source after validated Android changes are promoted.
- **`android-apk`** — Android staging/validation branch used to prove APK changes before promotion to `main`.

## Documentation

- [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md) — what is implemented, removed and planned.
- [`docs/TRUE_OS_ARCHITECTURE.md`](docs/TRUE_OS_ARCHITECTURE.md) — current RiftKernel/Android boundary.
- [`docs/ANDROID_NATIVE_ARCHITECTURE.md`](docs/ANDROID_NATIVE_ARCHITECTURE.md) — Android host and native services.
- [`docs/RIFTBROWSER_ARCHITECTURE.md`](docs/RIFTBROWSER_ARCHITECTURE.md) — current windowed System WebView browser.
- [`docs/RIFTBROWSER_CHATGPT_SANDBOX.md`](docs/RIFTBROWSER_CHATGPT_SANDBOX.md) — ChatGPT sandbox and Rift Agent.
- [`docs/RIFTWORKSPACE_WEB_ARCHITECTURE.md`](docs/RIFTWORKSPACE_WEB_ARCHITECTURE.md) — current workspace boundary (historical filename retained).
- [`docs/RIFTRT-v1.md`](docs/RIFTRT-v1.md) — application runtime ABI.
- [`ROADMAP.md`](ROADMAP.md) — planned RiftScript/developer-platform work.

Earlier iPhone/WebKit-WASM work is retained only as historical context where explicitly labeled. It is not the active Android runtime.
