# RiftBrowser Architecture

## Decision

RiftBrowser is an **experimental engine browser service owned by RiftKernel**. RiftOS does not currently claim a stable browser backend.

The stable boundary is the unsigned RiftKernel + RiftFS/OPFS + RiftWorkspace runtime. Browser engines are optional, lazy-loaded services that may be replaced without changing the OS boot path.

## Current unsigned experiment

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

## Kernel separation

RiftOS core MUST NOT require WebAssembly to boot.

`src/riftbrowser-engines.js` probes Gecko only when RiftBrowser needs it. The service worker deliberately does not pre-cache the large Gecko binary as part of the mandatory core shell.

If Gecko fails, runs out of memory, or is unavailable, RiftKernel, RiftFS, OPFS, RiftWorkspace and the rest of the desktop remain usable.

## Browser backends

Current backend IDs are:

- `wasm-gecko` — primary unsigned browser-engine experiment.
- `native-webkit` — optional signed native capability adapter.
- `unavailable` — runtime state when no rendering engine can run; this is not a backend the user selects.

There is **no CORS-fetch/HTML-sanitizer/`srcdoc` browser backend**. The old `web-transport` implementation has been removed because it was not a real browser engine and created misleading behavior.

When no engine is available, RiftBrowser reports the engine failure and may offer to open the current URL in the host browser.

## Gecko bootstrap

The pinned `gecko.js` v0.0.1 package requires an explicit WASM descriptor:

```js
new Gecko({
  canvas,
  wasm: {
    url: "./vendor/gecko.wasm.zst",
    compressed: true
  },
  wispUrl
});
```

The glue, pthread worker and minimal GRE data are bundled/inlined by the upstream package. RiftOS serves the compressed Gecko WASM artifact next to the host page.

## Cross-origin isolation

Gecko WASM uses pthreads and therefore requires `SharedArrayBuffer` and a cross-origin-isolated page.

GitHub Pages cannot set arbitrary response headers directly, so the RiftOS service worker adds the required same-origin response headers:

```text
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Embedder-Policy: require-corp
Cross-Origin-Resource-Policy: same-origin
```

The engine registry reports the actual isolation state instead of pretending Gecko is ready.

## Networking

A browser engine nested inside a browser tab cannot open arbitrary raw TCP sockets through normal Web APIs. Gecko therefore uses a Wisp WebSocket transport for arbitrary HTTP/HTTPS networking.

RiftBrowser stores the Wisp endpoint as browser configuration and passes it into the Gecko host. Wisp is transport only; RiftKernel remains the owner of tabs, logical history, bookmarks and browser process state.

Without Wisp, Gecko may still initialize and render internal/data content, but arbitrary external browsing is not expected to work.

## Storage

RiftOS workspace data remains under RiftFS/OPFS. Gecko may maintain its own browser profile in OPFS, separated from the RiftWorkspace capability boundary.

```text
RiftKernel
  |-- RiftWorkspace -> RiftFS -> OPFS
  |
  `-- RiftBrowser
       `-- Gecko WASM
            `-- isolated browser profile storage
```

Guest web content never receives raw RiftKernel, RiftWorkspace or OPFS handles.

## State ownership

RiftKernel owns browser product state:

- tab identities
- active tab
- logical navigation history
- bookmarks
- selected engine preference
- Wisp configuration

The engine adapter owns rendering/runtime integration. This keeps Gecko replaceable.

## Rules

1. RiftKernel and RiftWorkspace boot without Gecko, WebAssembly or native iOS code.
2. Gecko binaries load only when RiftBrowser is opened.
3. The browser UI talks to a replaceable engine adapter.
4. Do not reintroduce `web-transport`, CORS document fetching, HTML sanitization or `srcdoc` as a browser backend.
5. Untrusted guest pages never gain direct RiftFS/OPFS workspace capability access.
6. A future engine can replace Gecko without rewriting RiftKernel browser state.
7. Native WebKit remains optional and signed; it is not required for RiftOS itself.
