# RiftWebCore

RiftWebCore is the browser-engine layer above RiftEngine's JavaScriptCore host.

The goal is **not** to grow RiftDOM into a home-grown browser. RiftDOM remains a small diagnostics/test bridge. Real HTML parsing, CSS, layout, DOM semantics, navigation, and page lifecycle should come from a stripped WebCore port compiled to WebAssembly.

## Build boundaries

RiftEngine is intentionally split into four layers so normal development does not rebuild JavaScriptCore:

1. **JSC core** — `libJavaScriptCore.a`, `libWTF.a`, ICU. Expensive, versioned, cached by WebKit SHA + toolchain + port-patch hash.
2. **RiftJSC host** — tiny RiftEngine-owned embedder. Relink independently against the cached core.
3. **RiftWebCore** — WebCore/browser integration with its own incremental build directory and cache boundary.
4. **RiftOS browser shell** — normal web UI, tabs, chrome, controls, and app integration.

`riftengine/tools/rift-build-layout.sh` creates separate core/host/WebCore build roots and a deterministic JSC-core cache key. Build scripts should source the generated `.riftengine-work/layout.env` rather than sharing one throwaway build tree.

## Phase gates

### Gate 0 — persistent JSC

The custom host must prove that two evaluations share one `JSGlobalContextRef` (`41 -> 42`) and that destroy/recreate clears it.

### Gate 1 — immutable core boundary

Package/cache the successful JSC/WTF/ICU static core. Host-only edits must no longer invoke the JSC compilation step.

### Gate 2 — WebCore hello document

Compile the smallest viable WebCore surface and render a static document such as `<h1>Hello RiftBrowser</h1>` into a worker-owned rendering target. No networking is required for this gate.

### Gate 3 — event loop and input

Connect timers, task scheduling, pointer/keyboard input, focus, and DOM event dispatch without routing page JavaScript through RiftDOM.

### Gate 4 — networking

Add the controlled browser networking layer. Keep transport behind a RiftEngine interface so Wisp, a same-origin proxy, or another backend can be swapped without changing WebCore-facing code.

### Gate 5 — persistent profile

Add cookies, local/session storage, cache metadata, and profile persistence using browser-safe storage such as OPFS/IndexedDB where appropriate.

### Gate 6 — iPhone stability

Measure startup time, memory pressure, page lifecycle, worker recovery, background/foreground transitions, and hard reset behavior on Safari/iOS before enabling RiftWebCore by default.

## Rules

- Keep upstream WebKit pinned; make narrow, marker-checked Emscripten compatibility patches.
- Do not copy or fork large WebKit source trees into RiftOS.
- Do not rebuild JSC for RiftWebCore-only or RiftOS-only changes.
- Preserve the current known-good JSC runtime as fallback until RiftWebCore passes iPhone testing.
- Use separate build directories for materially different compiler/thread profiles; do not invalidate one tree by flipping global flags.
- Measure `.wasm` size and startup/memory cost at every promotion gate.
- Prefer dead stripping and feature disablement over invasive source deletion.

## Initial profile

The first RiftWebCore profile should be deliberately narrow:

- single-threaded first for compatibility and simpler bring-up;
- no media pipeline;
- no printing;
- no inspector/remote debugging backend in production output;
- no WebRTC;
- no unnecessary platform integrations;
- rendering isolated from the RiftOS main UI thread;
- JavaScript provided by the pinned RiftEngine JSC core.

Threaded/SharedArrayBuffer builds can live in a separate profile later instead of replacing the single-thread baseline.
