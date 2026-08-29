# RiftOS

RiftOS is an experimental browser-native, touch-first operating environment designed to run well on mobile Safari and static hosting such as GitHub Pages.

## v0.1

- Mobile-first desktop shell and dock
- Rift Runtime task registry
- RiftFS persistent virtual filesystem using IndexedDB
- RiftShell with filesystem/process/app commands
- Files app
- Pocket code editor
- Experimental web-view app
- Settings and task viewer
- PWA manifest
- Offline application-shell cache through a Service Worker
- No Linux or Windows image required

## Run

Serve the repository over HTTP(S), or enable GitHub Pages for the repository. Service Workers and persistent browser features work best over HTTPS.

## Architecture

The browser is the hardware abstraction layer. RiftOS builds its own lightweight runtime and APIs on browser primitives rather than emulating a desktop OS.

The Browser app in v0.1 is intentionally only a prototype. Many websites block iframe embedding. A later networking/browser layer should address browsing without pretending that an iframe is a full browser engine.
