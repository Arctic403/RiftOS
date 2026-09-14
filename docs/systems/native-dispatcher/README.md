# RiftNativeDispatcher

## Purpose

`RiftNativeDispatcher` is the main Android implementation behind RiftOS native capability calls. It translates validated method names/JSON arguments into Android file, mount, device and OS operations.

## Source ownership

Primary source: `android/app/src/main/java/com/riftos/app/RiftNativeDispatcher.kt`.

Call entry: `handleAsync(raw)` -> `dispatch(method,args)`.

## Responsibilities

The dispatcher implements internal RiftFS and SAF-mounted file operations, stat/list/read/write/base64 IO, bounded-buffer streaming SHA-256, mkdir/remove, recursive copy/move, ZIP/unzip, transfer manifest/progress/verification, mount enumeration/unmounting, settings storage, device/storage info, clipboard, share, vibration, allowed Android intents, preview launch and notifications. It also exposes the Android-native `vortex.bridge` route plus bounded `vortex.session` foreground-session route to `RiftVortexBridgeClient`, the separate fixed-scope `vortex.agent` and `riftos.agent` routes to the local UI-agent subsystem, and the local `chat.handoff` route to `RiftChatHandoff`. Vortex protocol/session semantics remain owned by the dedicated bridge subsystem, Android UI automation policy remains owned by the fixed-scope local-agent subsystem, and `.riftchat` schema/path/hash policy remains owned by the chat-handoff subsystem.

Local-agent methods run on a dedicated single-thread `agentExecutor`, separate from ordinary native RPCs. This is required because `riftos.agent` Dev Lab control can synchronously re-enter the trusted shell while the Dev Lab operation itself performs nested RiftFS/workspace RPCs; putting both on the normal single native worker would deadlock. Directory/notification picker completion enters from `MainActivity` through dedicated completion methods rather than pretending those asynchronous Android UI operations are synchronous dispatcher calls.

## Why this boundary exists

Android-specific APIs should not leak into `riftcore.js` or application code. One dispatcher makes the native authority auditable and gives the bridge a finite method surface.

## Transfer path

For copy/move, the dispatcher first resolves source/destination mount types. It can attempt provider-native copy/move for SAF where possible, otherwise recursively streams nodes. `buildTransferManifest` records expected file/directory/byte totals; progress is emitted during work; `verifyTransferComplete` checks completion before success.

## Security and path rules

- Internal paths are resolved inside the RiftFS root.
- `fs.sha256` accepts only a resolved RiftFS/SAF file and streams it through `MessageDigest`; it must never expose raw Android paths or return file bytes.
- `build.execute` is intentionally a finite fail-closed placeholder until a trusted native build executor exists; it throws rather than pretending compilation succeeded, while `RiftNativeBridge.capabilities()` advertises `localBuildExecutor:false`.
- SAF paths are resolved relative to an explicitly persisted mount.
- External intents are limited to an allowed scheme set.
- This dispatcher is available to the trusted RiftOS shell, not directly to guest web pages or MCP.
- `vortex.bridge` and `vortex.session` use explicit local Binder IPC through `RiftVortexBridgeClient`; do not replace them with a localhost/network listener or direct Vortex private-file access.
- `vortex.agent` delegates only to hard-coded `com.vortex3d.app`, while `riftos.agent` delegates only to hard-coded `com.riftos.app`; never add an arbitrary package argument here. The RiftOS self-agent's Dev Lab branch is a finite structured action whitelist routed through the authoritative shell/Dev Lab API, not a generic command field.
- `chat.handoff` delegates only to app-private RiftFS bundle logic; it must not read ChatGPT/Android app-private storage or grow a network path.
- MCP has its own narrower `RiftToolSandbox`; do not expose this dispatcher as an MCP shortcut.

## Failure signatures

- File operation fails only on Android, while web abstraction looks correct -> dispatcher/provider implementation.
- Mounted-folder operation reports missing file -> `externalNode`/mount record/provider behavior.
- Big copy stalls or progress is wrong -> manifest/progress/stream path.
- ZIP extraction behaves unexpectedly -> `unzip` validation/commit behavior.
- Clipboard/share/intent/notification fails -> respective Android API method here plus platform permissions.

## Fix map

Keep semantic policy in the caller when it is not Android-specific. Patch this class for Android IO/API behavior, path resolution, provider compatibility, transfer mechanics, or dispatcher method mapping. Do not add MCP-specific permission policy here.

## Validation

After native dispatcher changes, build the APK and test both app-private storage and at least one SAF provider. Verify `fs.sha256` against known digests, zero-byte files and a file larger than the 48 MiB binary bridge limit. Exercise failure/cancel paths as well as success. Transfer changes should test files, nested directories, zero-byte files and cross-provider moves.

## Safe extension points

Add a new dispatcher method only when an operation truly needs Android authority. Prefer explicit method names and small JSON-safe results. Long-running work must not block the UI thread and should use existing transfer/progress patterns where applicable.
