# RiftBrowser Architecture

## Decision

RiftBrowser is an **experimental multi-engine browser service owned by RiftKernel**. RiftOS does not currently claim a stable browser backend.

The stable boundary is the unsigned RiftKernel + RiftFS/OPFS runtime. Browser engines are optional, lazy-loaded services that may be replaced without changing the OS boot path.

## Primary experiment

```text
iPhone / iPad
     |
Apple WebKit host
     |
RiftOS Home Screen runtime
     |
RiftKernel
     |
RiftKernel.browser
     |
RiftBrowser Engine Adapter
     |
Gecko compiled to WebAssembly
     |
canvas + Wisp networking
```

The first engine package is pinned to the MPL-2.0 `HeyPuter/firefox-wasm` `gecko.js` v0.0.1 release. RiftOS does not rebuild Gecko during normal Pages deployment; CI downloads the published release package and stages the engine assets next to the RiftBrowser host.

## Why this is separate from the kernel

RiftOS core MUST NOT require WebAssembly to boot.

`src/riftbrowser-engines.js` probes the optional engine only when RiftBrowser needs it. The service worker deliberately does not pre-cache the large Gecko binaries as part of the core shell.

If the engine fails, runs out of memory, or is unavailable, RiftKernel, RiftFS, OPFS, RiftWorkspace and the rest of the desktop remain usable.

## Cross-origin isolation

Gecko WASM uses pthreads and therefore requires `SharedArrayBuffer` and a cross-origin-isolated page.

GitHub Pages cannot set arbitrary response headers directly, so the existing RiftOS service worker adds these headers to same-origin controlled responses:

```text
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Embedder-Policy: require-corp
Cross-Origin-Resource-Policy: same-origin
```

The first visit after the service-worker update may require one reload before `crossOriginIsolated === true`. The engine registry reports this state instead of pretending the engine is ready.

## Networking

A browser engine nested inside a browser tab cannot open arbitrary raw TCP sockets through normal Web APIs. Gecko therefore uses a Wisp WebSocket transport for arbitrary HTTP/HTTPS networking.

RiftBrowser stores the Wisp endpoint as browser configuration and passes it into the engine host. Wisp is transport only; RiftKernel remains the owner of tabs, logical history, bookmarks and browser process state.

## Storage

RiftOS workspace data remains under RiftFS/OPFS. The browser engine may maintain its own browser profile data, but third-party guest pages do not receive direct RiftKernel or RiftWorkspace capabilities.

```text
RiftKernel
  |-- RiftWorkspace -> RiftFS -> OPFS
  |
  `-- RiftBrowser
       `-- Gecko WASM guest web content
```

That separation is important for the future ChatGPT/workspace bridge: guest web content should exchange controlled JSON messages with RiftKernel rather than receiving raw filesystem objects.

## Backends

Current backend IDs:

- `wasm-gecko` — experimental full browser-engine path; preferred in unsigned auto mode when ready.
- `native-webkit` — optional signed native host retained as a capability adapter.
- `web-transport` — legacy CORS-readable document transport and emergency fallback only. It is not described as a stable browser.

## Rules

1. RiftKernel and RiftWorkspace boot without Gecko, WebAssembly or native iOS code.
2. Browser-engine binaries load only when RiftBrowser is opened.
3. The browser UI talks to a replaceable engine adapter, not directly to one engine implementation.
4. Untrusted guest pages never gain direct RiftFS/OPFS capability access.
5. A future engine can replace Gecko without rewriting RiftKernel browser state.
