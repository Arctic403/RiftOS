# RiftRT wasm-base64 Engine

## Purpose

The `wasm-base64` engine loads a base64-encoded WebAssembly module from a Rift package and connects it to the RiftRT surface/input ABI without giving the module general Android or DOM access.

## Source ownership

Implementation: `decodeBase64`, `launchWasm`, host imports, exported `rift_*` callback handling and frame-buffer decoding in `src/riftrt.js`.

## Runtime flow

```text
base64 WASM package asset
  -> decode + WebAssembly.instantiate
  -> small Rift host import surface
  -> rift_init/tick/resize/input exports
  -> bounded JSON frame-command buffer
  -> host-owned canvas
```

## Critical invariants

- Frame pointer/length must stay inside WASM memory.
- Frame length remains bounded before decode/render.
- WASM receives only the defined import surface.
- Missing optional exports must not crash unrelated runtime state.

## Failure signatures

- Instantiation fails -> invalid base64/WASM/import contract.
- Module runs but no frame -> missing/wrong `rift_frame`/`rift_frame_len` or memory bounds.
- Resize/input ignored -> missing exports or host forwarding.
- Corrupt frame data -> ABI/encoding mismatch.

## Fix map

Decode/instantiate/imports -> `launchWasm`. Memory/frame bounds -> frame loop. Drawing semantics -> shared `drawCommands`. Package data errors -> app/package system.

## Validation

Test valid and invalid modules, missing optional exports, memory bounds, oversized frame length, resize/input callbacks, repeated launch/close and malformed frame JSON without destabilizing the shell.