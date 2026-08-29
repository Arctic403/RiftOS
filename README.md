# RiftOS

RiftOS is an experimental browser-native, touch-first operating environment designed to run well on mobile Safari and static hosting such as GitHub Pages.

## Current shell

- Mobile-first desktop shell and dock
- Rift Runtime task registry
- RiftFS persistent virtual filesystem using IndexedDB
- RiftShell with filesystem/process/app commands
- Files app
- Pocket code editor
- RiftBrowser v0.2
- Settings and task viewer
- PWA manifest and offline shell cache
- No Linux or Windows image required

## RiftEngine

RiftEngine is now a **clean RiftOS-owned WebKit-to-WASM port project**. The previous WebkitWasm build experiment, Codespace scripts, compatibility patcher, temporary logs and Codespace configuration have been removed.

The production target is upstream WebKit/WebCore/JavaScriptCore compiled with Emscripten against a small RiftPlatform layer owned by RiftOS. Page parsing, JavaScript, layout and painting are intended to execute locally on the device. A future Wisp service may relay network bytes, but it will not render pages remotely.

### Prototype milestone

The current Phase 0 port harness is intentionally smaller than WebKit. It proves our build and runtime boundary first:

- GitHub Actions builds C++ to WebAssembly with Emscripten
- GitHub Pages receives the generated runtime
- the RiftEngine app boots the WASM module locally
- WASM owns a pixel buffer which is presented to the RiftOS canvas
- pointer input is forwarded into WASM
- the viewport resizes without restarting the OS shell
- memory is growable but capped for the mobile prototype

Once that works reliably on iPhone, Milestone 2 is JavaScriptCore bring-up, followed by WebCore local-document rendering and then networking.

See `riftengine/README.md` for the architecture and feature-cut plan.

## Run

The main branch is built and deployed through the RiftEngine GitHub Pages workflow. Service Workers and persistent browser features work best over HTTPS.

## Architecture

The browser is the hardware abstraction layer. RiftOS builds its own runtime and APIs on browser primitives instead of emulating a desktop OS.
