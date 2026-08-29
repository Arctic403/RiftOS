# RiftEngine JavaScriptCore host

RiftEngine does not want to become a fork of the WebKit `jsc` command-line shell.
The long-term engine boundary is a small RiftEngine-owned embedder linked against
the pinned JSCOnly build.

## Target ABI

The wasm host intentionally starts with four operations:

- `rift_jsc_create()` — create one persistent JavaScriptCore global context.
- `rift_jsc_eval(source)` — evaluate source in that same context.
- `rift_jsc_destroy()` — release the context.
- `rift_jsc_alive()` — diagnostic health check.

Keeping the context alive is what allows RiftDOM callbacks, timers, event
listeners, promises and page state to retain real JavaScript functions/closures
instead of serializing source back through the stock CLI.

## Build strategy

WebKit remains an upstream, commit-pinned dependency. RiftEngine's Emscripten
compatibility edits stay narrow and marker checked. We build/cache the expensive
JSC/WTF core, then link this tiny host as a separate final stage. This avoids
copying JavaScriptCore source into RiftOS and makes future host/API changes cheap.

The existing `jsc.js` + `jsc.wasm` build remains the known-good bootstrap and
fallback until the custom host passes persistence and RiftDOM smoke tests.

## Promotion gates

A custom host must not replace the preserved v1 engine until it proves:

1. cold boot succeeds;
2. `rift_jsc_create()` creates a context;
3. two `rift_jsc_eval()` calls share global state (`41` then `42`);
4. destroy/recreate resets state;
5. RiftDOM can run on the persistent context;
6. browser worker loading succeeds on Safari/iOS.
