# RiftKernel

## Purpose

RiftKernel is the JavaScript-side operating authority that connects RiftOS process/app concepts to RiftFS, permissions and the native Android bridge.

## Source ownership

Primary source: `src/riftcore.js`.

Important types in that file:

- `RiftNativeBridge` — request/response transport to the native `RiftAndroid` host.
- `RiftTransferQueue` — serializes filesystem transfer work at the JS layer.
- `RiftFS` — logical filesystem API and mount routing.
- `ProcessTable` — RiftOS process records.
- `PermissionBroker` — app/capability grants.
- `AndroidKernel` — aggregates the runtime into the exported RiftOS core.

The initialized core is exposed through `globalThis.RiftOSCore` and consumed by shell, apps, desktop, workspace, RiftRT, RiftGit and MCP launcher integration.

## Why this boundary exists

Android provides the real process sandbox and hardware APIs, but RiftOS needs a stable user-space model independent of any one Android API. The kernel gives web-side systems one coherent interface instead of teaching each app how to call Android directly.

## Runtime flow

```text
RiftOS module/app
  -> RiftOSCore kernel/fs/processes/permissions/native
  -> RiftNativeBridge where native authority is needed
  -> MainActivity / native service
```

Local bookkeeping such as process records or permission decisions stays in the kernel layer; Android-specific IO remains behind native calls.

## Critical invariants

- `RiftOSCore` is created once and must be available before consumer modules execute.
- Native requests need unique IDs and bounded pending-request lifecycle; unmatched results must not resolve unrelated requests.
- Filesystem paths must be normalized before routing.
- Permission decisions must be checked before capability use, not after the native side-effect.
- A missing class/reference at module evaluation time stops the entire Android import chain.

## Failure signatures

**Boot loop:** syntax/reference failure while `riftcore.js` evaluates.

**All native calls time out/fail:** `RiftNativeBridge` connection/result handling or Android host bridge.

**Apps launch but capability operations fail:** `PermissionBroker`, app permission declaration, or downstream subsystem.

**Task list wrong/stale:** `ProcessTable` lifecycle rather than desktop DOM state.

## Fix map

- Transport/request correlation -> `RiftNativeBridge`.
- Logical path/mount/filesystem behavior -> `RiftFS`.
- Transfer sequencing -> `RiftTransferQueue` or native transfer system depending on where the stall occurs.
- App/process lifecycle bookkeeping -> `ProcessTable` / `AndroidKernel`.
- Capability grants -> `PermissionBroker`.

Do not add Android framework logic directly to consumer apps to bypass a kernel problem.

## Validation

`package.json` runs `node --check src/riftcore.js` as part of `npm run check`. Because most kernel regressions are integration regressions, also test cold boot, native request/response, Files, Settings, app launch and terminal after a kernel change.

## Safe extension points

Add stable capabilities to the kernel only when multiple systems need them. Keep Android-specific implementation behind native bridge methods and keep subsystem-specific policy inside the subsystem rather than turning the kernel into a catch-all.
