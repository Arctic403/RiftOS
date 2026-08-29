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
