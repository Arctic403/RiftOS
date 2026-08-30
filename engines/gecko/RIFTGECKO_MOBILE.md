# RiftGecko Mobile

RiftGecko Mobile is RiftOS's iPhone-oriented build profile for the `HeyPuter/firefox-wasm` Gecko engine. It is an optional RiftBrowser engine artifact; RiftKernel and RiftWorkspace do not depend on it.

## Pinned source

- Upstream: `HeyPuter/firefox-wasm`
- Upstream release: `v0.0.1`
- Upstream commit: `eadcb5fa828d7e8b54ae2df13b6b3208f6553944`
- Upstream Gecko fork/commit remains whatever that pinned upstream build harness selects.
- License: MPL-2.0. The exact RiftOS modification script is kept in `engines/gecko/riftgecko-mobile-profile.py` so the modified source form is reproducible.

## Why this profile exists

The upstream engine is already substantially trimmed for web embedding: JIT code generation, tests/debug, crash reporting, updater/background tasks, WebRTC, ctypes, PDF/front-end GRE trees, telemetry resources, translations, WebExtensions/Sync resources and other non-rendering assets are already disabled or excluded.

The remaining iPhone problem is primarily runtime pressure. The upstream release link uses a 512 MiB initial shared WebAssembly memory, a 64 MiB stack and a 20-worker pthread pool. That is a poor fit for a nested engine running inside iOS WebKit.

RiftGecko Mobile v1 changes only a small set of high-impact knobs:

| Setting | Upstream v0.0.1 | RiftGecko Mobile v1 |
| --- | ---: | ---: |
| Initial shared WASM memory | 512 MiB | 256 MiB |
| Maximum WASM memory | 4 GiB | 1 GiB |
| Stack reservation | 64 MiB | 16 MiB |
| Preloaded pthread pool | 20 | 4 |
| Pthread strict pool | off | off |
| Manual GPU JSPI wrappers | enabled | disabled |
| Final wasm-opt target | speed-oriented `-O4 -O3` | size-oriented `-Oz` |
| profiling function names | enabled | disabled |

The pool remains non-strict so Gecko can request additional workers if a real workload requires them. This avoids preloading twenty workers without pretending Gecko can run single-threaded.

## What v1 deliberately does not remove

RiftGecko Mobile v1 keeps the normal HTML/DOM/CSS/layout stack, SpiderMonkey, networking/Wisp plumbing, fonts/text, images, Canvas/WebGL plumbing, storage/security/origin machinery, audio/media bridges and the rest of the modern-web surface that is already present in the upstream embed build. Removing those subsystems would save more code but would also make ordinary websites fail in hard-to-predict ways.

The first goal is therefore **boot reliably on iPhone with much lower baseline pressure**. Further feature removal should be driven by measured size/RAM wins and compatibility tests, not guesses.

## Build and release flow

`.github/workflows/riftgecko-mobile.yml` is manual-only because a full Gecko build is expensive. It:

1. checks out the exact upstream commit,
2. applies `riftgecko-mobile-profile.py`,
3. builds one optimized release artifact,
4. packages `dist/gecko.js` + `dist/gecko.wasm.zst` with profile/source metadata,
5. publishes/replaces the `riftgecko-mobile-v1` release asset, and
6. dispatches the normal RiftOS Pages workflow.

Normal RiftOS commits do **not** rebuild Gecko. The Pages workflow prefers the RiftGecko Mobile release when it exists and falls back to upstream `v0.0.1` until the first custom build is available.
