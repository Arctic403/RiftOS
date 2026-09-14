# RiftRT Engines

RiftRT engine implementations are execution backends beneath the same installed-program, permission, process and RiftDesktop contracts.

- [`native-webview/README.md`](native-webview/README.md) — V1 default for installed HTML/JS-compatible programs; dedicated Android WebView View in the native window, never an iframe.
- [`worker-js/README.md`](worker-js/README.md) — constrained Worker + host-canvas compatibility engine.
- [`wasm-base64/README.md`](wasm-base64/README.md) — sandboxed WebAssembly compatibility engine.
- [`native-arm64/README.md`](native-arm64/README.md) — reserved packaged native-plugin direction; arbitrary downloaded ELF execution remains disabled.

The retired iframe engine is intentionally absent. A legacy `engine: "iframe"` package declaration is translated by RiftRT to `native-webview` so old packages can migrate without reintroducing iframe execution.
