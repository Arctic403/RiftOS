# RiftEngine JavaScriptCore host

The JavaScriptCore host is an internal RiftEngine layer, not a browser API. RiftBrowser talks to `RiftKernel.browser`; the future RiftEngine renderer talks to this host while executing page JavaScript.

RiftEngine does not want to become a fork of WebKit's `jsc` command-line shell. The long-term boundary is a small RiftEngine-owned embedder linked against the pinned JSCOnly build.

## Target ABI

The WASM host starts with four operations:

- `rift_jsc_create()` — create one persistent JavaScriptCore global context.
- `rift_jsc_eval(source)` — evaluate source in that context.
- `rift_jsc_destroy()` — release it.
- `rift_jsc_alive()` — diagnostic health check.

Keeping context state alive is required for callbacks, timers, listeners, promises and page closures. Source must not be repeatedly serialized through a stock CLI process.

## Build strategy

WebKit remains upstream and commit pinned. RiftEngine's Emscripten compatibility edits stay narrow and marker checked. The expensive JSC/WTF core is built/cached separately, then the small host can relink cheaply.

The known-good `jsc.js` + `jsc.wasm` artifact stays a bootstrap/fallback until the custom host passes promotion tests.

## Promotion gates

1. cold boot succeeds;
2. `rift_jsc_create()` creates a context;
3. sequential evaluations share global state (`41` then `42`);
4. destroy/recreate resets state;
5. RiftDOM diagnostics run on the persistent context;
6. browser worker loading succeeds on Safari/iOS;
7. the host can be consumed by RiftWebCore without leaking a second browser-control API above `RiftKernel.browser`.

The host must not own tabs, bookmarks, browser permissions or RiftOS filesystem policy.
