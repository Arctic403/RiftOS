# RiftEngine audit — iPhone-first WebKit/WASM plan

Status: audit/refactor pass before the next full compile.

## Executive decision

Do **not** fork the upstream research prototype blindly and do **not** throw away
what it already proved. Keep the WebCore/JSC/Wisp/Skia core, but treat it as an
engine substrate behind RiftOS rather than as RiftOS's browser UI.

The first RiftEngine shipping profile is deliberately:

- `BIB_PTHREAD=0`
- wasm32 + SIMD
- JavaScriptCore CLoop, JIT disabled
- single-process WebCore embedder
- WebGL2/Skia when available, raster fallback
- OPFS/local persistence where supported
- Wisp only as a byte transport; TLS/layout/JS/rendering remain on-device
- touch-first host integration

The upstream revision is pinned in `scripts/riftengine/version.env`. Build scripts
must fail instead of silently building a newer branch head.

## What upstream has already proved

The project has working gates for JSC-in-WASM, WebCore paint, canvas presentation,
mouse/keyboard/wheel interaction, JavaScript execution, HTTPS navigation through
curl/OpenSSL over Wisp, persistence work, and modern-site smoke testing. Its
non-pthread branch also explicitly supports the host class RiftOS currently has:
no SharedArrayBuffer / no COOP+COEP requirement.

That makes this an integration/optimization project, not a ground-up browser-engine
port.

## KEEP

### WebCore + JSC single-process embedder

The single-process `WebCore::Page` approach is exactly what RiftEngine needs. A
WebKit2-style process model would be much heavier and is a poor fit inside mobile
Safari's WASM sandbox.

### JSC CLoop / JIT off

iPhone Safari will not give guest WASM code the executable-memory/JIT environment a
normal native browser engine expects. The upstream CLoop route is therefore the
correct baseline. Performance work should focus on host scheduling, rendering,
feature trimming and avoiding pathological JS workloads rather than betting the
project on a guest JIT.

### Skia/WebGL2 path with raster fallback

WebGL2 is present on the target device class and upstream already contains a GPU
presentation path. Keep the fallback because iOS can lose contexts or impose memory
pressure at runtime.

### curl + OpenSSL + Wisp transport

Keep this separation. Wisp is not a remote browser; it supplies socket-like byte
transport the host browser API does not expose. TLS still terminates inside the
engine.

### Event-driven engine pumping

Upstream moved important work away from a pure `requestAnimationFrame` pump. Keep
that approach; async network/timer work should not be quantized to display frames.

## MODIFY

### 1. Make the build deterministic

Done in this audit pass:

- pin the exact non-pthread upstream commit;
- centralize compatibility changes in
  `scripts/riftengine/apply-upstream-patches.py`;
- use the same patch layer in Codespaces and GitHub Actions;
- fail on upstream drift instead of silently replacing unknown text;
- preserve successful dependency/sysroot work between Codespace builds.

### 2. Make iPhone single-thread the named product profile

Upstream treats pthread and non-pthread as two modes of a general experiment.
RiftEngine treats non-pthread as the **primary iPhone profile**, not as a fallback.
SharedArrayBuffer support can become an optional desktop/host capability later.

### 3. Separate engine runtime from browser chrome

Do not merge upstream's large `web/browser.html` into RiftOS UI. Initially use it
only as a reference/test harness. RiftOS should own tabs, navigation, permissions,
lifecycle and task state. The engine should expose a small adapter surface:

- `boot(config)`
- `navigate(url)`
- `resize(width,height,scale)`
- `pointer(...)`
- `key(...)`
- `text(...)`
- `wheel(...)`
- `suspend()` / `resume()` where feasible
- events for title, URL, loading, history, crash and frame readiness

The current RiftOS experiment is not wired to this contract yet.

### 4. Correct the generated-engine contract

The upstream build produces:

- `embedder.js`
- `embedder.wasm`
- `bib-build-config.js`

The current RiftOS experiment still checks/imports `webcore.js`. That is a known
integration mismatch and should be fixed only after the first successful pinned
compile, when we can inspect the exact generated JS shape instead of guessing an
ES-module factory that upstream does not promise.

### 5. Add iOS memory gates before feature pruning

Current upstream link flags start at 256 MB, allow growth and permit a 4 GB wasm32
maximum. Do not change those numbers blindly before a baseline boots. First record
on-device:

- cold boot peak;
- blank-page steady memory;
- simple page steady memory;
- a medium JS-heavy page;
- reload/recovery behavior after Safari memory pressure.

Then test smaller initial memory and practical maximums. Any size reduction must be
proven against page stability.

### 6. Touch/input adaptation

Pointer events should be translated to the embedder deliberately rather than making
RiftOS emulate a desktop mouse everywhere. Required follow-up work includes touch
scroll semantics, long-press/context actions, keyboard viewport changes, selection,
IME/composition and device-pixel-ratio-aware resize.

### 7. Persistence ownership

Upstream has browser-profile persistence work. RiftOS should eventually own the
profile namespace and lifecycle so cookies/site storage can be scoped per RiftOS
user/profile rather than living as an opaque browser-demo state blob.

## DEFER / REMOVE FROM THE FIRST PRODUCT CUT

Do not spend initial stability budget on multiple browser tabs inside the engine,
remote desktop features, a guest OS, a guest JIT, WebKit2 processes, or pthread-only
presentation. RiftOS itself owns app/window/tab concepts.

Media support, Web Workers, advanced WebSockets, service workers, WebRTC and other
large surfaces should be audited feature-by-feature **after** a stable iPhone boot.
Do not rip them out before the baseline build because that would destroy our ability
to compare behavior and payload size against upstream.

## Build issues found and fixed in our environment

The GitHub/Codespace environment exposed two reproducibility bugs in the dependency
path:

1. FreeType needs Brotli/WOFF2 before upstream's later curl tier builds Brotli.
2. `fontconfig-2.15.0.tar.xz` can return HTTP 418 from freedesktop.org on
   GitHub-hosted infrastructure, and fontconfig's static utility link needs
   FreeType's private `brotlicommon` closure explicitly.

The compatibility layer now builds/reuses Brotli early, swaps only the transport URL
for the exact same fontconfig release via Debian's mirror, and supplies:

`-lfreetype -lpng16 -lz -lbrotlidec -lbrotlicommon`

These are build-environment fixes, not a redesign of WebKit.

## Licensing gate before public RiftOS distribution

Upstream's own `LICENSING.md` says its original `src/embedder/`, `web/`, `tools/` and
`docs/` code does not currently have a finalized license / top-level LICENSE, while
the WebKit patch inherits upstream WebKit licensing. Because of that, RiftOS should
**not publicly redistribute the upstream original host/embedder code or a packaged
runtime derived from it until permission/licensing is clarified**.

For now the GitHub workflow is build/verify only. Codespace compilation for research
continues, but public Pages deployment of the engine is intentionally disabled.

This is also why RiftEngine should gradually move toward a RiftOS-owned adapter and
host layer rather than copying the upstream browser chrome.

## Tomorrow's compile gate

From the existing RiftOS Codespace:

```bash
git pull
bash scripts/riftengine-codespace-setup.sh
bash scripts/riftengine-codespace-build.sh
```

The setup script resets the **tracked upstream source** to the pinned commit and
reapplies our idempotent compatibility layer. The large untracked build/sysroot
artifacts remain in the persistent Codespace, so successful dependency work is not
intentionally discarded.

A successful run must produce non-empty `embedder.js`, `embedder.wasm` and
`bib-build-config.js`, and the build script rejects a stale pthread-stamped output.

## Next gates after compile

1. Inspect generated `embedder.js` API and create the RiftEngine host adapter against
   the real output contract.
2. Boot a local/static demo on iPhone with networking disabled.
3. Measure memory + first-paint time.
4. Add a configurable WSS Wisp transport and load a simple HTTPS page.
5. Test touch/keyboard/rotation/background-resume.
6. Only then begin payload/feature trimming and deeper WebKit-port refactors.

The rule is **baseline first, measured refactor second**. We keep the research
project's proven engine pieces and replace the parts that belong to RiftOS itself.
