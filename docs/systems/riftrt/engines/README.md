# RiftRT Engines

RiftRT engine implementations are execution backends beneath the same installed-program, permission, process and RiftDesktop contracts.

- [`native-webview/README.md`](native-webview/README.md) — V1 default for installed HTML/JS-compatible programs; dedicated Android WebView View in the native window, never an iframe.
- [`worker-js/README.md`](worker-js/README.md) — constrained Worker + host-canvas compatibility engine.
- [`wasm-base64/README.md`](wasm-base64/README.md) — sandboxed WebAssembly compatibility engine.
- [`native-arm64/README.md`](native-arm64/README.md) — reserved packaged native-plugin direction; arbitrary downloaded ELF execution remains disabled.

The retired iframe engine is intentionally absent. A legacy `engine: "iframe"` package declaration is translated by RiftRT to `native-webview` so old packages can migrate without reintroducing iframe execution.

## Source ownership

Engine selection, compatibility translation, process/session lifecycle and shared broker logic live in `src/riftrt.js`. Engine-specific maintenance notes live in the child README for that engine. Android-owned `native-webview` rendering additionally depends on `RiftNativeAppHost.kt` and `RiftNativeDesktop.kt`.

## Failure signatures

- an engine name is accepted but launches through the wrong backend -> RiftRT engine parsing/dispatch drift.
- legacy `iframe` creates an iframe instead of translating to `native-webview` -> retired execution path returned.
- one engine bypasses shared permissions/process/window ownership -> engine implementation escaped the common RiftRT contracts.
- an engine-specific failure is documented only here -> maintenance ownership is too broad; move the repair detail into that engine's README.

## Fix map

Shared engine parsing/dispatch/session contracts -> `src/riftrt.js`.
Renderer/ABI-specific behavior -> the owning child engine README and implementation it names.
Native window attachment -> `RiftNativeDesktop.kt`.
Installed package/registry problems -> RiftApps, not the engine layer.

## Validation

Verify every declared engine resolves to exactly one backend, legacy `iframe` maps only to `native-webview`, unsupported engines fail closed, all active engines preserve shared permission/process/window lifecycle, and each child engine README remains accurate for its implementation.
