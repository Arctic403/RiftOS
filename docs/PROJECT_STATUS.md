# RiftOS Project Status

## Active target

RiftOS currently ships as an Android APK targeting Android 8.0 / API 26+ with Samsung/DeX-friendly resizing. `main` is the authoritative project branch after validated changes are promoted from `android-apk`.

## Implemented

### Kernel and filesystem

- RiftKernel JavaScript runtime hosted by `MainActivity`.
- Exact-origin `RiftAndroid` WebMessage bridge.
- App-private RiftFS at `filesDir/riftfs`.
- SAF external-folder mounts with persisted permissions.
- RiftWorkspace Android adapter and JSON patch/history surface.
- Android Keystore secret storage.

### Desktop and applications

- RiftDesktop window manager with focus, move, resize, minimize, maximize and taskbar state.
- Explorer-style Files app inside a normal RiftOS window.
- Settings app with privacy-limited System Dump export and Android Save As picker.
- RiftDev Android editor backed by RiftWorkspace through `RiftDevAndroidDB` compatibility plumbing.
- RiftRT v1 worker/iframe/WASM application runtime.

### RiftBrowser

- Native Android System WebView hosted inside the RiftOS browser window.
- RiftOS-owned browser chrome and desktop window state.
- ChatGPT/OpenAI authentication handling, cookies and file chooser support.
- Exact-origin ChatGPT sandbox filesystem (`browser-sandbox`).
- Rift Agent v2 browser adapter with fixed filesystem tools, DOM-aware tool-block parsing, automatic tool-result continuation and hidden internal bridge turns.

### Diagnostics/build

- Privacy-limited JSON system dump.
- User-selected dump destination.
- GitHub Actions build/sign/verify/publish pipeline.
- No emulator smoke test or compatibility matrix in normal CI.
- CI guard against `libllamaserver.so` reappearing.

## Removed / inactive

The following are not active RiftOS Android architecture:

- local gpt-oss/llama.cpp runtime,
- `libllamaserver.so`,
- full-screen `RiftBrowserActivity`,
- WebKit-WASM/Wisp RiftBrowser engine,
- Gecko WASM browser experiments,
- Android emulator CI matrix/smoke tests,
- PWA/service-worker/iOS-only assets in the Android APK.

Some historical documents or source history may mention those experiments. They are not current runtime claims.

## Known limitations

- Rift Agent depends on ChatGPT Web DOM structure and may require selector maintenance.
- Rift Agent is a browser-side adapter, not native ChatGPT MCP/tool registration.
- Embedded identity providers may independently reject Android WebView login.
- Native Kotlin/DEX cannot be arbitrarily hot-swapped; APK rebuild is required.
- RiftBrowser sandbox access is intentionally restricted to its app-private sandbox and exact ChatGPT origin.

## Planned

See `ROADMAP.md`, especially RiftScript Studio, developer overlays, live inspection/testing and Development Snapshot export.
