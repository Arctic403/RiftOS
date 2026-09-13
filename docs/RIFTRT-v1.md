# RiftRT v1

RiftRT is the active RiftOS desktop application runtime. Android remains the kernel/driver host; RiftOS owns app/process records, windows, brokered capabilities and RiftFS namespace.

## Package model

RiftRT extends the existing `rift-app-v1` `.rift` JSON package. Existing iframe packages remain compatible. A package opts into RiftRT by including `riftrt.json` in `files`.

```json
{
  "engine": "worker-js",
  "entry": "main.js",
  "abi": "riftrt-1",
  "capabilities": ["fs.read", "fs.write"],
  "window": { "width": 720, "height": 480 }
}
```

Supported v1 engines:

- `iframe` — legacy `.rift` HTML application hosted in a RiftDesktop window.
- `worker-js` — isolated Worker application using a host-owned canvas surface and RiftRT APIs.
- `wasm-base64` — WebAssembly module stored as base64 text and loaded through the Rift ABI.
- `native-arm64` — reserved direction for modules compiled/packaged with RiftOS; arbitrary downloaded native ELF execution is not enabled.

## Worker ABI

Worker apps receive a frozen `Rift` object with logging, window title, host-owned surface, resize/input callbacks, local app storage and capability-gated filesystem/clipboard/share operations. Share is exposed as `Rift.share.text(text)` and requires the app's `share` capability.

Canvas frame commands currently include `clear`, `rect`, `line` and `text`.

## WASM ABI

A `wasm-base64` app may export `rift_init`, `rift_tick`, `rift_resize`, pointer/key/wheel handlers, memory and frame JSON accessors. Host imports expose width, height and simple logging.

The WASM path uses the same host-owned surface command format as Worker apps.

## Desktop integration

RiftRT uses the existing RiftOS window manager. Runtime apps participate in taskbar state, focus, move/resize, minimize/maximize, Alt-Tab/show-desktop behavior and RiftKernel process management.

## Security direction

Modern Android does not provide a safe general-purpose route for running arbitrary downloaded ELF binaries from writable app storage. Native acceleration therefore targets code compiled and packaged with RiftOS or a future explicitly trusted plugin mechanism.
