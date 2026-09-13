# Transfer Subsystem

## Purpose

The transfer subsystem keeps large copy/move/ZIP/unzip operations off the normal native RPC worker, reports bounded progress to the RiftOS shell, and verifies recursive transfers before success. The active design is intentionally small: one JavaScript queue, one dedicated native executor, one manifest counter, and one progress path.

## Source ownership

- `RiftTransferQueue` in `src/riftcore.js` — serializes high-level RiftFS transfer requests and ensures one rejected task cannot poison later work.
- `RiftTransferManifest.kt` — expected file/directory/byte totals used for recursive verification.
- `RiftNativeDispatcher.kt` — owns the dedicated transfer executor, provider-native copy/move fast paths, streaming fallback, ZIP/unzip, progress accounting and rollback/error behavior.
- `MainActivity.sendNativeProgress()` -> `window.RiftTransferUI.__progress(...)` — native-to-shell progress bridge.
- `src/riftos.js` `RiftTransferUI` / `renderTransferState()` — visible progress state.

The removed `RiftTransferJob` / manager / service / registry experiment is not part of the active runtime. Cancellation/pause/resume are not currently implemented.

## Runtime flow

```text
Files/RiftFS request
  -> RiftTransferQueue
  -> RiftAndroid fs.copy/fs.move/fs.zip/fs.unzip
  -> dedicated transferExecutor
  -> provider-native fast path or streaming recursion
  -> TransferProgress + RiftTransferManifest verification
  -> MainActivity progress callback
  -> RiftTransferUI
  -> success/error
```

## Why this boundary exists

Large trees can contain thousands of files. Transfer work must not monopolize the normal native RPC executor or the browser/UI thread. Provider-native operations are used when possible; otherwise the dispatcher streams data in bounded buffers and yields during long work.

## Critical invariants

- A rejected `RiftTransferQueue` task must not block later queued transfers.
- `fs.copy`, `fs.move`, `fs.zip` and `fs.unzip` must run on `transferExecutor`, not the normal executor.
- Transfer IDs must remain stable from JS request through native progress events.
- Move must establish destination success before deleting the source; failed source deletion rolls the destination back.
- Cross-mount recursive copy/move must verify file, directory and byte totals before reporting success.
- ZIP extraction must retain entry-count, expansion-size and traversal limits.
- UI progress must be throttled/bounded enough that many tiny files do not recreate the original lock-up problem.

## Failure signatures

- UI/native RPCs stall during a large transfer -> transfer escaped onto the normal executor or progress/UI work is too frequent.
- Progress panel never appears/updates -> transfer ID, `progressSink`, `MainActivity.sendNativeProgress`, or `RiftTransferUI`.
- Queue never continues after one failure -> `RiftTransferQueue.tail` continuation handling.
- Destination incomplete but operation reports success -> manifest/progress verification.
- Move loses source or destination on failure -> `moveNode` ordering/rollback.
- User expects cancel/pause but nothing happens -> those controls are not active features; do not look for the removed job registry.

## Fix map

- JS sequencing -> `RiftTransferQueue` in `src/riftcore.js`.
- Worker selection -> `RiftNativeDispatcher.handleAsync` transfer-method set.
- Throughput/provider behavior -> `copyFileBytes`, `copyNode`, `moveNode`, `tryProviderCopy`, `tryProviderMove`.
- Completion verification -> `RiftTransferManifest` + `verifyTransferComplete`.
- ZIP/unzip safety -> dispatcher `zip`/`unzip` and archive limits.
- Visual progress -> dispatcher `emitTransfer`, `MainActivity.sendNativeProgress`, and `RiftTransferUI`.

## Validation

Test large recursive trees, many tiny files, one large file, internal-to-internal, internal-to-SAF, SAF-to-internal, same-provider fast paths, forced streaming fallback, ZIP/unzip, error mid-transfer and move source-removal failure. Confirm normal native RPCs and the desktop remain responsive throughout.

## Planned extension points

If cancellation, pause/resume or persistent transfer jobs are added later, build them on top of the current transfer executor/progress/manifest path and add explicit runtime references plus tests. Do not reintroduce an unused parallel job registry.
