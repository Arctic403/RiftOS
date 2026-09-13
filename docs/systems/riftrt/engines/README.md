# RiftRT Engine Map

RiftRT supports multiple execution engines behind one application/runtime boundary. The parent maintenance guide is [`../README.md`](../README.md).

## Source ownership

All current engine implementations live in `src/riftrt.js`; package/ABI intent is summarized in `docs/RIFTRT-v1.md`.

Engine guides:

- [`iframe/README.md`](iframe/README.md) — legacy HTML/iframe packages.
- [`worker-js/README.md`](worker-js/README.md) — isolated JavaScript Worker + host-owned canvas.
- [`wasm-base64/README.md`](wasm-base64/README.md) — WASM module + Rift frame ABI.
- [`native-arm64/README.md`](native-arm64/README.md) — reserved trusted-native direction; downloaded ELF execution is not active.

## Boundary

Engine selection changes how app code executes, not who owns permissions, storage, desktop windows or package validation. Those stay in RiftRT, RiftKernel/RiftFS and the app registry.

## Failure signatures

If only one runtime type fails, start in that engine guide. If every engine fails, start in the parent RiftRT README and shared `parseRuntime`, capability, window/process and package paths.

## Fix map

Engine-specific launch/ABI/input/frame failures -> engine guide. Shared capability/storage/window/process failures -> parent RiftRT subsystem.

## Validation

Every supported active engine must have at least one launch/close test path plus error-path coverage. Reserved engines must remain explicitly unavailable rather than silently falling back to unsafe execution.