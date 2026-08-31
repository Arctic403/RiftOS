# RiftBrowser Architecture

## Decision

RiftBrowser is a **WebKit-only browser service owned by RiftKernel**. The Gecko WASM experiment and the signed native WebKit browser backend are retired from the active architecture.

The stable OS boundary remains RiftKernel + RiftFS/OPFS + RiftWorkspace. The browser engine is lazy-loaded and may fail without preventing the rest of RiftOS from booting.

## Current unsigned path

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
RiftWebKit Mobile host
     |
WebCore + JavaScriptCore + Skia -> WebAssembly
     |
canvas surface + Wisp networking
```

The engine port is pinned to `theogbob/WebkitWasm` commit `cbdc40b3ca68f45fff8de61fd843f81a55026178`, WebKit commit `aec9d2ad958e716ab4bca4bf03007e6edac7323f`, and Emscripten 6.0.0.

## Kernel separation

RiftOS core MUST NOT require WebAssembly to boot.

`src/riftbrowser-engines.js` probes and loads RiftWebKit only when RiftBrowser is used. The service worker does not pre-cache the engine as part of the mandatory shell; engine files are cached lazily after first use.

If the engine crashes, runs out of memory, or is unavailable, RiftKernel, RiftFS, OPFS, RiftWorkspace and the rest of RiftOS remain usable.

## One engine, no fallback backends

The only selectable RiftBrowser engine ID is:

- `riftwebkit` — WebCore/JSC/Skia compiled to WebAssembly.

`unavailable` is a runtime state, not a second backend.

There is no Gecko backend, no signed native browser backend, and no CORS-fetch/HTML-sanitizer/`srcdoc` fallback. A fake document fetch must never masquerade as a browser engine.

## Engine milestones

The proven `riftwebkit-poc-v1` artifact validates single-threaded WebCore layout/paint, JSC initialization, Skia raster output, linear memory and iPhone touch reaching WebCore.

The full `riftwebkit-mobile-v1` artifact uses the pinned helper embedder in `BIB_PTHREAD=0` mode and adds the pieces needed for real browsing:

- real `http(s)` navigation through `bib_load_url`
- guest JavaScript through JavaScriptCore CLoop
- CSS/images/resource loading
- cookies and browser storage adapters
- Wisp-backed TCP transport for external HTTPS
- engine persistence
- host canvas rendering
- mobile touch/tap/drag-to-scroll bridging in RiftOS

Pages always serves one logical engine root at `/engines/webkit/`. Before the full mobile artifact exists, the proven proof artifact may occupy that slot; after the mobile release is published it replaces the proof automatically.

## Mobile host behavior

RiftOS owns the browser chrome and address bar. The embedded engine host is stripped to the canvas surface at runtime. On iPhone, RiftBrowser converts touch gestures into the WebCore input APIs exposed by the embedder:

- tap -> WebCore mouse press/release compatibility path
- finger drag -> WebCore wheel scrolling path
- address entry -> native iOS software keyboard in the RiftOS chrome

The initial mobile engine viewport is 390x844. Dynamic viewport resizing and guest text-field IME bridging can be added without reintroducing a second browser backend.

## Networking

The nested WebCore engine cannot open raw iOS sockets. Its curl/SOCKFS network path is carried through a Wisp WebSocket transport. The user-configured endpoint is stored by RiftBrowser and passed only to the WebKit engine host.

Wisp is transport only. It is not a kernel, filesystem or workspace capability.

## Storage and security boundary

RiftOS workspace data remains under RiftFS/OPFS. RiftWebKit keeps guest browser state separately.

```text
RiftKernel
  |-- RiftWorkspace -> RiftFS -> OPFS
  |
  `-- RiftBrowser
       `-- RiftWebKit WASM
            `-- guest browser state
```

Guest web content never receives raw RiftKernel, RiftWorkspace or OPFS handles.

## State ownership

RiftKernel owns browser product state:

- tab identities
- active tab
- logical navigation history
- bookmarks
- configured Wisp endpoint

RiftWebKit owns page execution/rendering and guest browser state.

## Rules

1. RiftKernel and RiftWorkspace boot without the RiftWebKit engine.
2. RiftBrowser has one engine path: `riftwebkit`.
3. Do not restore Gecko or a signed native browser fallback.
4. Do not reintroduce CORS document fetching, HTML sanitization or `srcdoc` as a browser backend.
5. Untrusted guest pages never gain direct RiftFS/OPFS workspace capability access.
6. Heavy engine artifacts load lazily and are not part of mandatory OS boot.
