# RiftEngine

RiftEngine is RiftOS's custom local browser-renderer project.

The production goal remains **upstream WebKit/WebCore/JSC compiled to WebAssembly with a RiftOS-owned platform layer**, but RiftEngine is no longer a separate browser product. Its integration target is the renderer contract owned by `RiftKernel.browser`.

## System position

```text
RiftBrowser UI / RiftShell
          |
   RiftKernel.browser
          |
   RiftEngine backend
          |
   RiftEmbedder C API
          |
 WebCore + JavaScriptCore
          |
      RiftPlatform
   |-- RiftRunLoop
   |-- RiftGPU
   |-- RiftFonts
   |-- RiftInput
   `-- RiftNet
          |
 Emscripten -> WebAssembly
```

Tabs, bookmarks, browser chrome and OS-level policy stay above RiftEngine. RiftEngine owns page execution/rendering and the platform primitives needed to make that page work.

## Current port harness

The existing harness proves the RiftOS-owned pieces before attaching the full engine:

- Emscripten -> WebAssembly builds in GitHub Actions;
- stable C ABI experiments;
- local device execution rather than remote rendering;
- WASM pixel memory -> canvas presentation;
- pointer/resizable viewport plumbing;
- bounded growable memory;
- pinned JSC artifacts and dedicated engine workflows.

The heavyweight port is intentionally isolated from normal RiftOS Pages builds.

## BrowserService integration target

When ready, RiftEngine publishes a trusted adapter such as:

```js
window.RiftEngineBrowserBackend = {
  available: () => true,
  open: async ({ url, tab }) => {
    // Bind the selected kernel tab to the local WebCore page/view.
    return { mode: "riftengine", url };
  }
};
```

`src/riftbrowser-kernel.js` already contains the `riftengine` renderer slot. No RiftBrowser UI rewrite should be required when the engine reaches the gate.

Do not give page content unrestricted `RiftFS`/`RiftWorkspace` access. Any future file/download capability crosses an explicit RiftKernel boundary.

## Lightweight iPhone profile

Keep initially:

- WebCore, JavaScriptCore, WTF/PAL;
- HTML/CSS/DOM/forms;
- JavaScript with JSC CLoop/no JIT;
- Canvas/core image decoding;
- fonts/text shaping;
- fetch/HTTP/TLS and controlled transport;
- storage required for normal sites;
- one page/view process model.

Disable initially:

- WebRTC/media capture;
- video/audio media pipeline;
- WebDriver;
- production devtools backend;
- gamepad, speech, screen capture, WebGPU;
- testing/benchmark infrastructure not needed at runtime.

## Milestones

1. **Port harness** — local WASM execution/painting/input scaffolding.
2. **JSC bring-up** — upstream JavaScriptCore builds and runs under the Emscripten platform configuration.
3. **WebCore local document** — actual WebKit parses/layouts/paints a local HTML document.
4. **Input + navigation** — links/forms/touch/keyboard/page lifecycle.
5. **RiftNet** — real HTTPS networking behind a controlled engine transport interface.
6. **Browser renderer contract** — implement `window.RiftEngineBrowserBackend` and satisfy `RiftKernel.browser` integration.
7. **Measure and shrink** — iPhone runtime size, startup, memory and page-load profiling before promotion.

Do not add remote rendering or Linux emulation to RiftEngine.
