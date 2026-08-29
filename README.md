# RiftOS

RiftOS is an experimental browser-native, touch-first operating environment designed to run well on mobile Safari and static hosting such as GitHub Pages.

## v0.1

- Mobile-first desktop shell and dock
- Rift Runtime task registry
- RiftFS persistent virtual filesystem using IndexedDB
- RiftShell with filesystem/process/app commands
- Files app
- Pocket code editor
- RiftBrowser v0.2 with URL/search parsing, direct CORS-safe rendering, blocked-site detection, compatibility iframe mode, and Safari fallback
- Settings and task viewer
- PWA manifest
- Offline application-shell cache through a Service Worker
- No Linux or Windows image required

## Run

Serve the repository over HTTP(S), or enable GitHub Pages for the repository. Service Workers and persistent browser features work best over HTTPS.

## Architecture

The browser is the hardware abstraction layer. RiftOS builds its own lightweight runtime and APIs on browser primitives rather than emulating a desktop OS.

RiftBrowser v0.2 no longer pretends an iframe is a full browser engine. It first attempts a restricted direct fetch/render path for sites that permit CORS, then exposes compatibility and external-navigation fallbacks. Google and many major sites still require the planned remote transport because a static GitHub Pages app cannot override their CORS or frame-embedding policy.


## RiftEngine experimental track

RiftEngine is a separate local-browser-engine experiment and does not replace RiftBrowser v0.2. The target is a non-pthread WebKit/WebAssembly build that executes and paints locally on the client device. The repository includes a GitHub Actions build experiment that clones the upstream WebkitWasm research project, checks out its `non-pthread` branch, bootstraps/builds WebCore, and publishes generated engine assets into the Pages artifact.

This first integration deliberately separates two checkpoints: (1) prove the generated WebKit/WASM module can boot and paint locally on mobile Safari; (2) add Wisp networking afterward. Wisp is a transport relay only; it is not remote browser rendering.

The upstream WebkitWasm project is a research prototype. Its non-pthread build avoids the SharedArrayBuffer/COOP/COEP requirement of its threaded build, but long guest-JavaScript tasks can block the host tab.
