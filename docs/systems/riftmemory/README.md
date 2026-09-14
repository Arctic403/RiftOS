# RiftMemory

## Purpose

RiftMemory is the build/workset cache control plane. The current MVP manages a verified local-flash warm cache under `/system/riftmemory/v1/cache`, supports prefetch, pin/unpin, status, flush and pressure pruning, and uses RiftVault as the durable cold-object source.

It deliberately reports `nativeAccelerator:false`: the future C++/JNI hot-tier engine is not faked before a compiled native implementation exists.

## Source ownership

- `src/riftmemory-control.js` owns cache metadata, prefetch/pin/prune behavior and shell commands.
- `src/riftvault.js` owns durable content-addressed objects used to hydrate cache entries.
- `src/riftbuild.js` consumes RiftMemory status/cache controls during planning.

## Drive-path compatibility

RiftMemory accepts bare C:/D: shell paths through `RiftOSCore.path.isAbsolute()` and canonicalizes source identity before indexing. `D:/Workspace/foo` and `/workspace/foo` therefore refer to one cache source rather than two aliases of the same bytes, including pin/unpin lookup.

## Failure signatures

- prefetch hash limit -> source exceeds current vault bridge hashing capability.
- pinned cache does not prune -> expected; pinned entries are protected.
- `nativeAccelerator:false` -> current APK has only the safe local-flash control plane.

## Fix map

Residency metadata/pin/prune -> `src/riftmemory-control.js`.
Cold durable objects -> `src/riftvault.js`.
Future C++/JNI data plane must be added as a separate native subsystem and capability before changing this flag.

## Validation

Verify prefetch creates warm cache bytes, pin survives prune, unpin permits eviction, status byte counts match the index and no cache eviction removes RiftVault's durable object copy.