# RiftRT v1

RiftRT is the RiftOS desktop application runtime. Android remains the kernel/driver host; RiftOS owns windows, processes, input, permissions and RiftFS.

## Package model

RiftRT extends the existing `rift-app-v1` `.rift` JSON package. Existing packages remain compatible. A package opts into RiftRT by including `riftrt.json` in `files`.

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

- `iframe` — legacy `.rift` HTML application, now hosted in a RiftDesktop window.
- `worker-js` — isolated Worker application using a host-owned canvas surface and RiftRT APIs.
- `wasm-base64` — WebAssembly module stored as base64 text and loaded through the Rift ABI.
- `native-arm64` — reserved packaged-plugin route. Arbitrary downloaded ELF execution is intentionally not enabled.

## Worker ABI

Worker apps receive a frozen global `Rift` object:

- `Rift.log(...values)`
- `Rift.window.setTitle(title)`
- `Rift.surface.frame(commands)`
- `Rift.surface.onResize(handler)`
- `Rift.input.on(handler)`
- `Rift.storage.get/set/remove`
- `Rift.fs.readText/writeText/list` (capability gated)
- `Rift.clipboard.readText/writeText` (capability gated)

Canvas frame commands currently support `clear`, `rect`, `line`, and `text`. The host owns the canvas so the app does not need its own browser/window stack.

## WASM ABI

A `wasm-base64` app may export:

- `rift_init(width, height)`
- `rift_tick(time_ms)`
- `rift_resize(width, height)`
- `rift_pointer(kind, x, y, button, buttons)`
- `rift_key(key_code, down)`
- `rift_wheel(dx, dy)`
- `memory`
- `rift_frame()` -> pointer to UTF-8 JSON command array
- `rift_frame_len()` -> byte length

The host imports:

- `env.rift_width()`
- `env.rift_height()`
- `env.rift_log_i32(value)`

`rift_frame` JSON uses the same canvas command format as Worker apps.

## Desktop integration

RiftRT extends the existing RiftOS window manager instead of creating a second desktop. Runtime applications therefore participate in RiftOS taskbar state, minimize/maximize, focus, Alt-Tab, Show Desktop and RiftKernel process management.

## Native ARM64 direction

Modern Android does not provide a safe general-purpose route for executing arbitrary downloaded native ELF binaries from writable app storage. RiftRT's native route therefore targets native modules compiled and packaged with RiftOS (or a future approved plugin packaging mechanism). This keeps the fast path compatible with Android security rules while preserving a stable Rift window/input/RiftFS ABI.
