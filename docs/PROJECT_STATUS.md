# RiftOS Project Status

## Active target

RiftOS currently ships as an Android APK targeting Android 8.0 / API 26+ with Samsung/DeX-friendly resizing. `main` is the authoritative project branch.

## Implemented

### Kernel and filesystem

- RiftKernel JavaScript runtime hosted by `MainActivity`.
- Exact-origin `RiftAndroid` WebMessage bridge for the RiftOS shell.
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
- **Rift Bridge** registered as a RiftOS system app for AI-tool pairing, permissions and recent tool activity.

### RiftBrowser

- Native Android System WebView hosted inside the RiftOS browser window.
- RiftOS-owned browser chrome and desktop window state.
- ChatGPT/OpenAI authentication handling, cookies and file chooser support.
- RiftBrowser no longer injects a filesystem bridge, prompt wrapper, DOM parser or Agent runtime into `chatgpt.com`.
- ChatGPT web content is treated as ordinary web content; it has no direct native Rift filesystem capability.

### Rift Bridge

- Outbound-only WSS device connection to a remote adapter.
- App-private tool scope remains `filesDir/riftfs/browser-sandbox`.
- Device-side read/write permission gates are authoritative.
- Read tools: `info`, `stat`, `list`, `readText`.
- Write tools: `writeText`, `mkdir`, `remove`, `move`.
- Pairing key encrypted with Android Keystore.
- Reconnect support for network/process interruptions.
- Recent tool activity log records tool, target/path, outcome and time without storing file contents.
- Remote MCP relay exposes first-class ChatGPT tools while RiftOS keeps MCP outside the internal capability API.

### Diagnostics/build

- Privacy-limited JSON system dump.
- User-selected dump destination.
- GitHub Actions build/sign/verify/publish pipeline.
- APK verification fails if the removed ChatGPT DOM Agent asset reappears and requires the Rift Bridge system module instead.
- Separate CI syntax/package check for the remote Rift Bridge MCP relay.
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
- PWA/service-worker/iOS-only assets in the Android APK,
- Rift Agent V1/V2/V3 prompt wrappers, model-visible markers and DOM tool-block parsing,
- direct `RiftSandbox` WebMessage access from `chatgpt.com`.

Some historical source history may mention those experiments. They are not current runtime claims.

## Known limitations

- ChatGPT requires a reachable remote MCP/custom-app endpoint; the phone itself does not accept inbound internet connections.
- The alpha relay uses a pairing-key URL and is single-device; OAuth/multi-user routing is future work.
- Write tools are locally disabled by default until the user explicitly enables them in Rift Bridge.
- Embedded identity providers may independently reject Android WebView login.
- Native Kotlin/DEX cannot be arbitrarily hot-swapped; APK rebuild is required.

## Planned

See `ROADMAP.md`, especially Rift Bridge deployment/hardening, RiftScript Studio, developer overlays, live inspection/testing, Development Snapshot export and capability-gated expansion of the tool registry.
