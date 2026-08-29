# RiftEngine

RiftEngine is RiftOS's clean browser-engine port project.

The production goal is **upstream WebKit/WebCore/JSC compiled to WebAssembly with a RiftOS-owned platform layer**. We are not shipping the old WebkitWasm project's custom host/embedder code.

## Phase 0: port harness

The current prototype intentionally does **not** contain WebKit yet. It proves the pieces we own before attaching a huge engine:

- Emscripten -> WebAssembly build on GitHub Actions
- a tiny stable C ABI for RiftOS
- local iPhone execution (no remote renderer)
- WASM pixel memory -> HTML canvas presentation
- pointer input -> WASM
- resizable viewport
- bounded growable memory
- GitHub Pages deployment for immediate device testing

The existing RiftOS RiftEngine app loads `riftengine/engine/webcore.js`. That compatibility loader now boots this clean prototype and paints the canvas from WASM memory.

## Production architecture

```text
RiftBrowser UI
     |
RiftEngine JS adapter
     |
RiftEmbedder C API
     |
WebCore + JavaScriptCore
     |
RiftPlatform
  |-- RiftRunLoop
  |-- RiftGPU
  |-- RiftFonts
  |-- RiftFS / OPFS
  |-- RiftInput
  `-- RiftNet / Wisp byte transport
     |
Emscripten -> WebAssembly
```

The network relay, when added, transports bytes only. Page parsing, JavaScript, layout and painting remain on the device.

## Lightweight iPhone profile

Initial WebKit bring-up keeps the web fundamentals and removes expensive browser features that RiftOS does not need for the first browser milestone.

Keep initially:

- WebCore, JavaScriptCore, WTF/PAL
- HTML/CSS/DOM/forms
- JavaScript with JSC CLoop/no JIT
- Canvas and core image decoding
- fonts/text shaping
- fetch/HTTP/TLS and Wisp transport
- storage required for normal sites
- one page/view process model

Disable initially:

- WebRTC and media capture
- video/audio media pipeline
- WebDriver
- remote inspector/devtools backend
- gamepad
- notifications/push
- speech APIs
- screen capture
- WebGPU
- accessibility extras that require desktop platform services during first bring-up
- testing/benchmark infrastructure

Features come back only when a real RiftOS use case requires them.

## Milestones

1. **Port harness** — current. WASM paints locally and accepts input.
2. **JSC bring-up** — upstream JavaScriptCore builds/runs under our Emscripten platform configuration.
3. **WebCore local document** — actual WebKit parses and paints a local HTML document.
4. **Input + navigation** — links/forms/touch/keyboard.
5. **RiftNet** — Wisp-backed sockets, TLS inside the engine, real HTTPS pages.
6. **RiftBrowser integration** — tabs/history/downloads/RiftFS bridge.
7. **Measure and shrink** — runtime size, peak memory, startup and page-load profiling on iPhone.

Do not add remote rendering or Linux emulation to RiftEngine.
