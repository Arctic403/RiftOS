# RiftWebCore

RiftWebCore is the page engine above RiftEngine's JavaScriptCore host and below the `RiftKernel.browser` renderer contract.

The goal is **not** to grow RiftDOM into a home-grown browser. RiftDOM remains a diagnostics bridge. Real HTML parsing, CSS, layout, DOM semantics, navigation and page lifecycle should come from a stripped upstream WebCore port compiled to WebAssembly.

## Build and ownership boundaries

1. **JSC core** — `libJavaScriptCore.a`, `libWTF.a`, ICU; expensive and cacheable.
2. **RiftJSC host** — small persistent JavaScriptCore embedder.
3. **RiftWebCore** — WebCore/page integration with a separate incremental build boundary.
4. **RiftEngine browser backend** — adapts WebCore page/view operations to `window.RiftEngineBrowserBackend`.
5. **RiftKernel.browser** — tabs, bookmarks, renderer selection, shell/UI contract and OS policy.

`riftengine/tools/rift-build-layout.sh` keeps the core/host/WebCore build roots separate. RiftOS/browser-service edits must not rebuild JSC.

## Phase gates

### Gate 0 — persistent JSC

Two evaluations share one persistent context and destroy/recreate clears it.

### Gate 1 — immutable core boundary

Package/cache the successful JSC/WTF/ICU core so host/WebCore changes do not recompile it unnecessarily.

### Gate 2 — WebCore hello document

Compile the smallest viable WebCore surface and render a static document into a worker-owned target. No network required.

### Gate 3 — event loop and input

Connect timers, task scheduling, pointer/keyboard input, focus and DOM events without routing real page JavaScript through RiftDOM.

### Gate 4 — networking

Add controlled browser networking behind a RiftEngine transport interface so transport can change without rewriting WebCore or BrowserService.

### Gate 5 — persistent profile

Add cookies, local/session storage, cache metadata and profile persistence using browser-safe storage where appropriate.

### Gate 6 — BrowserService backend

Publish `window.RiftEngineBrowserBackend`, satisfy the kernel renderer contract, and demonstrate that the existing RiftBrowser UI/shell can use it without engine-specific branches.

### Gate 7 — iPhone stability

Measure startup, memory pressure, page lifecycle, worker recovery, background/foreground transitions and hard reset behavior before `riftengine` becomes a default backend.

## Rules

- Keep upstream WebKit pinned and use narrow marker-checked compatibility patches.
- Do not copy/fork large WebKit source trees into RiftOS.
- Do not rebuild JSC for WebCore-only or RiftOS-only changes.
- Preserve known-good JSC artifacts until later gates pass.
- Keep materially different compiler/thread profiles in separate build directories.
- Measure WASM size/startup/memory at each promotion gate.
- Prefer feature disabling/dead stripping over invasive source deletion.
- Do not create a second tabs/history/bookmarks/permissions implementation inside WebCore.
- Page content gets no implicit RiftWorkspace privilege.

## Initial profile

Start single-threaded for compatibility and bring-up simplicity, without media pipeline, printing, production inspector backend, WebRTC or unnecessary platform integrations. Rendering should remain isolated from the main RiftOS UI thread, with JavaScript provided by the pinned JSC core.
