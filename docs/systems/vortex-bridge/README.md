# RiftOS ↔ Vortex3D Local Development Bridge

## Purpose

This subsystem lets the trusted RiftOS development shell drive and inspect the **real running Vortex3D debug APK** through local Android Binder IPC. ChatGPT reaches it only through the existing MCP `rift_shell_exec` tool and RiftShell `vortex` command family; no new MCP tool or tool schema is added.

```text
ChatGPT
  -> existing rift_shell_exec
  -> RiftShell runShell("vortex ...")
  -> core.native.call("vortex.bridge", args)
  -> RiftNativeDispatcher
  -> RiftVortexBridgeClient
  -> explicit Binder component com.vortex3d.app.VortexDevBridgeService
  -> Vortex3D debug validation / VTXScript / capture runtime
```

## Source ownership

- `android/app/src/main/java/com/riftos/app/RiftVortexBridgeClient.kt` — explicit Binder binding, protocol transaction, reconnect, screenshot attachment, bounded artifact chunking and artifact pull into RiftFS workspace.
- `RiftNativeDispatcher.kt` — finite Android-native method mapping `vortex.bridge`.
- `src/riftos.js` — user/model-facing `vortex` RiftShell command family.
- `RiftToolHost.kt` — preserves the shell's structured return value so MCP can see bridge results.
- `RiftMcpServer.kt` — attaches optional bounded Vortex preview data as MCP image content while keeping Base64 out of text/structured output.
- `android/app/src/main/AndroidManifest.xml` — package-visibility query for `com.vortex3d.app`; it does not declare or own the Vortex service.

Vortex-side protocol/service ownership lives in Vortex3D `docs/DEV_BRIDGE.md`.

## Shell commands

`vortex help`
`vortex status`
`vortex catalog`
`vortex api`
`vortex snapshot`
`vortex ui-tree [limit]`
`vortex screenshot [name]`
`vortex click <exact-tag-or-content-description>`
`vortex touch <down|move|up|cancel|0..3> <x> <y>`
`vortex test [all|system|exact-case-id]`
`vortex script <RiftFS-path> [--unsafe] [--live]`
`vortex job <vtx-id> [--image]`
`vortex pull <artifact-id> [filename]`
`vortex cleanup`

Validation/script commands return jobs immediately. Use `vortex job <id>` to poll. VTXScript saves/restores the current Vortex project by default; add `--live` only when the script should intentionally keep project mutations. `--unsafe` is a separate lifecycle-JNI gate. `--image` asks RiftOS to fetch the bounded JPEG preview and attach it to the existing MCP tool result. `vortex pull` streams an arbitrary evidence artifact into `workspace/.vortex-bridge/` in bounded chunks with an atomic final rename, SHA-256 and duplicate-safe local filename.

The `vortex` command is deliberately **not supported inside `batch`**. Live app/test operations cannot honestly participate in RiftShell filesystem rollback semantics, so the atomic batch preflight continues to reject them.

## Android IPC contract

The client binds with an explicit `ComponentName("com.vortex3d.app", "com.vortex3d.app.VortexDevBridgeService")` and Binder descriptor `com.vortex3d.app.devbridge.v1`. There is no implicit service discovery and no network transport. The Vortex service verifies the Binder caller UID maps to `com.riftos.app`.

`RiftVortexBridgeClient` automatically reconnects once after Binder death. Binding is bounded to eight seconds. Vortex runtime operations can still fail cleanly if Vortex3D's `MainActivity` or renderer is not alive; opening the editor is intentionally a user-visible prerequisite rather than a hidden Activity launch.

## Evidence / image flow

Vortex artifacts are read with `artifact_read` chunks capped at 192 KiB. MCP image attachment accepts only a Vortex preview artifact <=512 KiB. The raw Base64 is placed only in a private `_riftImage` field returned by the shell command. `RiftMcpServer` extracts that field into MCP `content[type=image]`, removes the Base64 from structured/text content, and leaves compact image metadata in the visible result. This prevents duplicate payload expansion and does not change the model-visible tool schema.

Arbitrary evidence ZIPs/reports remain out-of-band until explicitly pulled. `vortex pull` caps one artifact at 128 MiB and writes only under `workspace/.vortex-bridge/`; an existing local filename is never deleted and receives a numeric suffix instead. `.vortex-bridge` is excluded from Project Intelligence indexing so generated evidence cannot pollute source symbols/dependency analysis.

## Security / authority rules

- No new MCP tool is registered; the existing stronger `rift_shell_exec` permission remains the authority boundary.
- RiftOS uses explicit Binder IPC only; no TCP/HTTP/WebSocket/localhost listener.
- RiftOS never reaches Vortex3D private files directly; it can only request bridge-declared artifacts.
- Artifact names/paths are bounded and canonicalized on both apps.
- Vortex lifecycle-sensitive VTXScript calls remain blocked unless `--unsafe` is explicitly supplied.
- RiftOS does not invent engine/test behavior: Vortex3D's existing validation suites and VTXScript runtime remain authoritative.

## Failure / repair map

`vortex status` cannot bind -> debug Vortex3D APK missing, wrong package/component, package visibility or Binder service packaging.
status binds but activity/renderer false -> open Vortex3D or repair Vortex activity/renderer lifecycle.
all `vortex` commands fail at native method -> RiftShell/native dispatcher/client wiring.
validation fails -> inspect owning Vortex subsystem suite; bridge is only the transport/runner entry.
semantic click misses -> inspect Vortex UI tag/content-description ownership.
screenshot works in Vortex but no ChatGPT image -> artifact chunk/client `_riftImage` or MCP result framing.
`vortex pull` fails -> remote artifact id/size, chunk protocol or RiftFS `.vortex-bridge` output commit.
MCP shows `value:null` for shell -> `RiftToolHost` shell-result wrapper regression.

## Validation

`validate-rift-transport.mjs` locks the explicit Binder component/descriptor, no-network rule, dispatcher route, shell command family, result wrapper, image framing, package visibility, source inclusion and unchanged MCP tool family. Any Kotlin change still requires an Android build. After installing both updated APKs, device-smoke `status`, `catalog`, a targeted diagnostics case, job polling, screenshot image attachment, UI tree/click, VTXScript, artifact pull, Vortex close/reopen and Binder reconnect.
