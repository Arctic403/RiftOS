# RiftRT worker-js Engine

## Purpose

The `worker-js` engine executes app JavaScript in a Web Worker and renders through a host-owned canvas. The worker receives a constrained Rift API rather than direct DOM or Android authority.

## Source ownership

Implementation: `launchWorker`, worker RPC/message plumbing, `drawCommands`, `fitCanvas` and normalized input handling in `src/riftrt.js`.

## Runtime flow

```text
worker entry asset
  -> Worker
  -> constrained Rift RPC/messages
  -> host capability broker
  -> host-owned canvas frame commands
```

Resize plus pointer/key/wheel input are normalized by the host and forwarded to the worker.

## Critical invariants

- Worker code does not own the desktop DOM.
- Capability calls remain brokered and permission-checked.
- Canvas commands are interpreted by the host, not evaluated as code.
- Worker termination/disposal must clear handlers and pending state.

## Failure signatures

- Worker starts but canvas stays blank -> message/frame command path or resize dimensions.
- RPC promises never resolve -> message ID/correlation path.
- Input is offset -> normalized input/canvas scaling.
- Closed app keeps running -> Worker disposal lifecycle.

## Fix map

Worker creation/message plumbing -> `launchWorker`. Drawing -> `drawCommands`. Resize/input -> `fitCanvas`/normalized input. Shared capability denial -> parent RiftRT broker.

## Validation

Launch/terminate repeatedly, draw each supported frame command, resize, pointer/key/wheel input, allowed/denied RPC, worker exception handling and pending-call cleanup.