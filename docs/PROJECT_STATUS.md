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
- **Rift Bridge** registered as a RiftOS system app for local MCP tools, permissions, optional remote pairing and recent tool activity.

### RiftBrowser

- Native Android System WebView hosted inside the RiftOS browser window.
- RiftOS-owned browser chrome and desktop window state.
- ChatGPT/OpenAI authentication handling, cookies and file chooser support.
- Exact-origin `rift-mcp-app-v1` compatibility adapter on ChatGPT Web.
- Adapter performs in-process MCP `initialize`, `tools/list` and `tools/call` through `RiftMcpServer`.
- Live MCP tool manifest is supplied to ChatGPT conversation context; strict `<rift_call>...</rift_call>` envelopes are validated and routed to the local MCP server.
- ChatGPT never receives direct `RiftSandbox`, `RiftSandboxFS` or general `RiftNativeDispatcher` access.
- The removed Agent V1/V2/V3 protocol remains inactive and is not used as fallback.

### Rift Bridge / Tool Host

- `RiftToolHost` is the canonical device-side capability registry for every AI adapter.
- `RiftMcpServer` is an in-process MCP JSON-RPC server with no listening socket.
- App-private tool scope remains `filesDir/riftfs/browser-sandbox`.
- Device-side read/write permission gates are authoritative.
- Canonical read tools: `rift_info`, `rift_stat`, `rift_list`, `rift_read_text`.
- Canonical write tools: `rift_write_text`, `rift_mkdir`, `rift_remove`, `rift_move`.
- Write tools are disabled by default.
- Recent activity log records canonical tool, target/path, outcome and time without storing file contents.
- Existing remote MCP relay remains optional and now delegates execution to the same Tool Host instead of owning a parallel permission implementation.
- Remote pairing key remains encrypted with Android Keystore and the device connection remains outbound-only WSS.

### Diagnostics/build

- Privacy-limited JSON system dump.
- User-selected dump destination.
- GitHub Actions build/sign/verify/publish pipeline.
- APK verification requires the Rift MCP App asset and rejects the removed ChatGPT DOM Agent asset or V3 protocol marker.
- Separate CI syntax/package check for the optional remote Rift Bridge MCP relay.
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
- Rift Agent V1/V2/V3 fenced `rift-tool` protocol and response-node tracking,
- direct `RiftSandbox` / `RiftSandboxFS` WebMessage access from `chatgpt.com`.

Some historical source history may mention those experiments. They are not current runtime claims.

## Known limitations

- On ChatGPT plans without supported custom MCP registration, RiftBrowser compatibility mode necessarily depends on the ChatGPT composer and semantic rendered-message attributes. ChatGPT UI changes can break that browser-facing adapter without weakening the local capability boundary.
- The optional alpha remote relay still uses a pairing-key URL and is single-device; OAuth/multi-user routing is future work.
- Write tools are locally disabled by default until the user explicitly enables them in Rift Bridge.
- Embedded identity providers may independently reject Android WebView login.
- Native Kotlin/DEX cannot be arbitrarily hot-swapped; APK rebuild is required.

## Planned

See `ROADMAP.md`, especially Rift Bridge hardening, browser compatibility resilience, secure-tunnel adapters, RiftScript Studio, developer overlays, live inspection/testing, Development Snapshot export and capability-gated expansion of the tool registry.
