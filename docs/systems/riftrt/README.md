# RiftRT Application Runtime

## Purpose

RiftRT is the execution/runtime layer for modern RiftOS applications. It runs app code inside RiftDesktop windows while brokering host capabilities and app storage through RiftOS rather than giving apps unrestricted page/native authority.

## Source ownership

- `src/riftrt.js` — runtime parsing, windows, host calls, worker/WASM/iframe execution, canvas command rendering and manager UI.
- `src/riftruntime.js` — platform/runtime capability report.
- `src/riftapps.js` — package registry consumed by RiftRT.
- `docs/RIFTRT-v1.md` — ABI overview; this README is the maintenance map.

## Supported engines

`parseRuntime(app)` recognizes `iframe`, `worker-js`, `wasm-base64`, and the reserved `native-arm64` direction. Each engine has its own maintenance README under [`engines/README.md`](engines/README.md). Arbitrary downloaded native ELF execution is not enabled.

### iframe

Legacy package HTML runs in a sandboxed app frame with the app host bridge.

### worker-js

A Worker receives a constrained runtime API, can make RPC host calls and drives a host-owned canvas through command messages. Resize and normalized pointer/key/wheel input are forwarded by the host.

### wasm-base64

A base64-encoded WASM module is instantiated with a small import surface. Optional `rift_*` exports drive initialization/tick/resize/input and a JSON frame-command buffer. Frame command length is bounded before decode/render.

## Host capabilities

`hostCall(app,method,args)` is the central runtime broker. Capabilities are checked against the app/package before filesystem, clipboard and share operations. Both iframe and Worker engines expose share as `Rift.share.text(text)`, which requires the declared/granted `share` capability and routes to the Android share sheet. Per-app persistent runtime storage is JSON under `/system/appdata/<id>/riftrt-storage.json` and is capped at roughly 1 MB serialized data.

## Desktop/process integration

RiftRT creates normal RiftDesktop windows and kernel process records. Runtime windows participate in focus, taskbar, minimize/maximize, show desktop and process close behavior. Do not create a parallel window manager for runtime apps.

## Failure signatures

- Package installs but runtime manager says invalid spec -> `parseRuntime`/`riftrt.json`.
- Worker runs but canvas is blank -> worker message/frame command path or resize.
- WASM instantiates but no output -> exported ABI functions/memory/frame accessor contract.
- Capability call denied -> app declaration/permission broker, not worker messaging.
- Closing app leaves process/window -> dispose/process/window integration.

## Fix map

Engine parsing/execution/ABI -> `riftrt.js`.
Package metadata -> app system.
Window behavior -> RiftDesktop.
Filesystem/native semantics -> kernel/RiftFS.
Platform availability reporting -> runtime-capabilities subsystem.

## Validation

Test every engine path represented in v1, app storage set/get/remove, denied and allowed capabilities, resize/input, repeated launch/close and error during engine initialization. Keep Worker/WASM payloads bounded and verify one broken app does not destabilize the shell.
