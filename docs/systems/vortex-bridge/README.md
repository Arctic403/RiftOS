# RiftOS ↔ Vortex3D Local Development Bridge

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

## Purpose

RiftOS can inspect and drive the Vortex3D debug runtime through one explicit local Android Binder protocol.

The bridge is separate from the Accessibility local agent.

Binder bridge:
- engine/runtime/status/catalog/API/snapshot/validation/script/job/artifact operations.

Accessibility agent:
- fixed-package UI inspection/input/foreground assistance.

Neither one widens the other's authority.

## Source ownership

RiftOS:
- RiftVortexBridgeClient.kt — Binder connection, JSON envelope limits, foreground sessions, artifact/image transfer.
- RiftMcpRuntime.kt — process-owned singleton bridge client.
- RiftNativeShellServices.kt — strict finite vortex shell command grammar.
- RiftMcpServer.kt — bounded MCP image-content attachment/shell-result sanitization.
- AndroidManifest.xml — package visibility for com.vortex3d.app.
- RiftVortexLocalAgent.kt — separate fixed-package foreground assistance used by wait sessions.

Vortex3D counterpart, separately owned:
- workspace/Vortex3d/android/app/src/debug/java/com/vortex3d/app/VortexDevBridgeService.java
- workspace/Vortex3d/android/app/src/debug/AndroidManifest.xml
- workspace/Vortex3d/docs/DEV_BRIDGE.md

The Vortex3D source was cross-checked during this audit to confirm descriptor/request/chunk protocol compatibility, but it remains a different repository and release pipeline.

## Current call path

Remote tool path:

ChatGPT / MCP
-> rift_shell_exec
-> process-owned RiftNativeShell
-> RiftNativeShellServices.vortex(...)
-> RiftMcpRuntime.vortexBridge(...)
-> RiftVortexBridgeClient
-> explicit Binder component
-> VortexDevBridgeService in the Vortex3D debug APK

No RiftNativeDispatcher or shell WebView participates.

## Fixed Binder identity

RiftOS hard-codes:
- package: com.vortex3d.app
- service: com.vortex3d.app.VortexDevBridgeService
- Binder descriptor: com.vortex3d.app.devbridge.v1
- transaction: IBinder.FIRST_CALL_TRANSACTION

Binding uses an explicit ComponentName with:
- BIND_AUTO_CREATE
- BIND_IMPORTANT

There is no caller-selected package, service, descriptor or transaction code.

## Vortex-side caller check

The current Vortex3D debug service independently checks Binder.getCallingUid() and requires that UID to resolve to package com.riftos.app.

The Vortex bridge is declared only in Vortex3D's debug manifest in the currently inspected counterpart source.

RiftOS cannot directly read Vortex3D private app storage; evidence must be returned through the protocol/artifact reader.

## No network transport

RiftVortexBridgeClient contains no TCP, HTTP, WebSocket or raw socket transport.

All bridge requests use local Binder IPC.

This is distinct from RiftOS MCP relay networking.

## Process-owned lifetime

RiftMcpRuntime owns one volatile RiftVortexBridgeClient singleton per RiftOS process.

MainActivity recreation does not recreate the bridge client.

Android process death resets the singleton.

This lets a debug Binder session survive RiftOS Activity recreation without tying it to a browser renderer/window.

## Bind behavior

ensureRemote():
1. reuses a live Binder when available;
2. unbinds stale bound state before rebinding;
3. binds only the fixed Vortex component;
4. waits at most 8000 ms;
5. tears down failed/timed-out binding state;
6. requires a live Binder before returning.

Service disconnect/binding-death/null-binding clear the cached remote reference.

## Reconnect behavior

transactWithReconnect():
- validates request size before attempting Binder work;
- submits synchronous Binder `transact()` through a capped two-worker, no-queue RPC executor;
- waits at most 12000 ms for each Binder transaction;
- if both RPC workers are already occupied by stalled Binder calls, fails fast rather than leaking more threads;
- on a transaction failure, clears/unbinds stale connection state;
- obtains a fresh fixed Binder;
- retries once.

Moving request-size validation ahead of reconnect during this audit prevents an oversized local request from causing a pointless unbind/rebind cycle.

## Binder request bound

RiftOS request JSON is capped at:

256 KiB UTF-8

The request is serialized and checked before reconnect/transaction.

transact() repeats the bound before Parcel.writeString.

The currently inspected Vortex3D counterpart independently caps its request string at 256 * 1024 characters.

RiftOS uses the stricter byte-oriented bound.

## Binder response bound

RiftOS rejects a returned response string above:

512 KiB UTF-8

before JSONObject parsing.

This is a client-side parse/propagation bound.

Android Binder itself may reject an oversized remote transaction before RiftOS receives the string; the counterpart is therefore still expected to maintain its own bounded debug protocol.

Current Vortex3D source uses bounded inline JSON/report behavior and chunked binary evidence.

## Supported shell surface

Current RiftShell vortex commands:
- help
- status
- catalog
- api
- snapshot
- ui-tree [limit]
- screenshot [name]
- click <target>
- touch <action> <x> <y>
- test/validate [target]
- test-wait/validate-wait <target>
- script <RiftFS-path> [--unsafe] [--live]
- script-wait <RiftFS-path> [--unsafe] [--live]
- job <id> [--image]
- pull <artifact-id> [filename]
- cleanup

No new MCP tool is created; these remain operations behind rift_shell_exec.

## Strict shell grammar

This audit removed silent extra-argument/default behavior.

Current parsing requires:
- help: no trailing args;
- status/catalog/api/snapshot/cleanup: no args;
- ui-tree: zero or one integer, 1..1024;
- screenshot: zero or one name, <=120 chars;
- click: non-empty target, <=256 chars;
- touch: exactly action + x + y;
- touch coordinates: numeric and finite;
- validation target: at most one, <=160 chars;
- test-wait: exactly one target, <=160 chars;
- script/script-wait: exactly one path after optional --unsafe/--live removal;
- job: id plus optional exact --image, id <=160 chars;
- pull: artifact id plus optional filename, id <=512 chars, filename <=120 chars.

Malformed or unexpected extra input fails instead of being ignored.

## Script source bound

The Vortex shell reads script source only from RiftFS-confined paths.

The old helper allowed 2 MiB, which contradicted one-request Binder semantics.

Current maximum script source:
240 KiB UTF-8

Both backing file length and decoded UTF-8 byte length are checked.

The margin leaves room for JSON fields/flags/name beneath the 256 KiB bridge request ceiling.

## Foreground wait sessions

executeSession() supports exactly:
- validation
- script

Before queueing:
- RiftVortexLocalAgent.ensureActiveForSession() brings/stabilizes the fixed Vortex package;
- status is polled until activity_alive and renderer_ready.

Bounds:
- renderer-ready wait: up to 12 seconds;
- full foreground session: up to 85 seconds;
- job polling: every 150 ms;
- Vortex foreground reassertion: every 1000 ms.

The session queues a normal Vortex job, then polls by returned job id.

Terminal complete state may fetch a final job response with includeImage=true.

Session timeout does not pretend to cancel the remote job; it returns a timeout result that states the job remains.

## Image attachment

When includeImage is requested and a successful response advertises preview artifact metadata:
- advertised size must be >0 and <=512 KiB;
- artifact id must be nonblank;
- artifact bytes are fetched through the chunk protocol;
- resulting MIME must be one of:
  - image/jpeg
  - image/png
  - image/webp
- image name is sanitized through the same safeName function used for local pulls;
- bytes are Base64-encoded into _riftImage.

RiftNativeShellServices replaces image payload data in displayed shell JSON with an attached marker.

RiftMcpServer extracts the image for MCP image content and sanitizes structured shell output so the large Base64 body is not duplicated there.

## Artifact chunk protocol

RiftOS requests remote artifact chunks with:
- op=artifact_read
- id
- current offset
- maxBytes=192 KiB

The currently inspected Vortex3D service uses the same 192 KiB maximum chunk size.

## Chunk integrity

This audit tightened every artifact chunk.

For image reads and pulled files, RiftOS now requires:
- remote size is nonnegative and within local maximum;
- declared total size remains identical across every chunk;
- returned offset equals requested offset;
- returned bytes equals decoded Base64 byte count;
- next_offset equals offset + decoded length;
- next_offset never exceeds declared total/local maximum;
- non-EOF chunks contain progress;
- EOF occurs exactly at declared total size;
- final accumulated byte count equals declared total.

A changing size, replayed/decreasing offset, false byte count, premature EOF or no-progress chunk fails immediately.

## Image artifact bound

Inline MCP preview download maximum:
512 KiB

readArtifactBytes() keeps the whole preview in memory only within this bound.

Larger evidence must be pulled as a file instead.

## Pulled artifact bound

Maximum Vortex artifact pull:
128 MiB

Pull destination root:

workspace/.vortex-bridge/

Destination names:
- are sanitized to [A-Za-z0-9._-];
- leading/trailing unsafe dots are removed by sanitization;
- max length 120;
- fall back to vortex-artifact.bin;
- are canonicalized;
- must have the bridge root as direct parent.

Existing filenames receive a duplicate-safe numeric suffix from 1..9999.

## Pull publication

Artifact pulls write to a same-root randomized temporary file.

Only after all chunks pass integrity checks:
- temporary length must equal declared remote total;
- temp is renamed to the chosen destination;
- committed destination length must still equal declared total.

Temporary files are deleted in finally on failure.

The returned result includes:
- workspace-relative path;
- final size;
- locally computed SHA-256.

The local digest proves the bytes RiftOS committed, not an independent remote-origin checksum.

## Current Vortex counterpart

The inspected Vortex debug service currently exposes:
- status
- catalog
- api
- snapshot
- ui_tree
- screenshot
- click
- touch
- validate
- script
- job
- artifact_read
- cleanup

It independently uses:
- 256K-character request ceiling;
- 128 KiB inline JSON threshold;
- 192 KiB artifact chunks;
- <=512 KiB image preview;
- max 32 retained live jobs.

These are counterpart facts observed during this audit, not code owned/enforced by the RiftOS repository.

## Debug-only rule

The inspected Vortex3D repository declares VortexDevBridgeService only in src/debug/AndroidManifest.xml and its repository policy explicitly rejects the service in the production manifest.

RiftOS should therefore treat the bridge as a development/debug integration.

A release Vortex3D APK without that service is expected to be unavailable to this bridge.

## Separation from Accessibility agent

The Binder bridge does not gain arbitrary Accessibility authority.

The local Accessibility agent does not gain Binder engine operations automatically.

executeSession uses only one narrow connection between them:
- fixed Vortex foreground stabilization through RiftVortexLocalAgent;
- engine job execution through the fixed Binder client.

Each subsystem retains its own audit boundary.

## Source fixes in this audit

- request JSON bounded to 256 KiB UTF-8;
- response JSON bounded to 512 KiB UTF-8 before parsing;
- request bound moved before reconnect so local oversize does not rebind;
- Vortex script source reduced from 2 MiB to 240 KiB UTF-8;
- shell vortex grammar changed to fail on malformed/extra arguments;
- touch coordinates must be finite;
- preview MIME restricted to JPEG/PNG/WebP;
- preview names sanitized;
- artifact chunk total/offset/byte-count/next-offset/EOF consistency is verified;
- pulled temp/final length must equal remote declared size;
- transport validator now locks the Binder/script/artifact/shell limits.

## Current limitations

- Binder response cap is checked after Android has delivered the response Parcel; the remote debug service must maintain its own Binder-safe response sizes.
- RiftOS computes a local SHA-256 after pull but does not compare against a remote cryptographic digest.
- foreground session timeout does not cancel the Vortex job.
- Binder availability depends on the Vortex3D debug APK/service and Android package visibility.
- device/runtime behavior is not proven by this source audit.

## Critical invariants

- fixed package/service/descriptor/transaction only;
- Binder only, no network fallback;
- process-owned client, not renderer-owned;
- request/response JSON bounds remain explicit;
- script source stays below request envelope;
- shell grammar remains finite/fail-closed;
- binary evidence stays chunked;
- every chunk must progress consistently;
- image attachment stays <=512 KiB and image-MIME-only;
- file pull stays <=128 MiB under workspace/.vortex-bridge;
- temp file is not published before complete verified transfer;
- Vortex runtime/test behavior remains Vortex-owned;
- Accessibility agent and Binder bridge remain separate authorities.

## Failure signatures

- caller can select package/service/descriptor -> Binder scope regression;
- Socket/HTTP/WebSocket fallback appears -> transport expansion;
- request/response JSON loses explicit bounds -> Binder resource regression;
- shell accepts >240 KiB Vortex script -> envelope mismatch;
- malformed/extra shell args are silently ignored -> command-contract regression;
- artifact next_offset is trusted without consistency check -> loop/replay regression;
- changing remote size is accepted -> transfer-integrity regression;
- EOF before declared total is accepted -> truncation regression;
- temp artifact is renamed before full length verification -> publication regression;
- arbitrary MIME becomes MCP image content -> content-type regression;
- MainActivity/browser renderer owns bridge lifetime -> lifecycle regression;
- release Vortex behavior is assumed to include the debug bridge -> packaging/trust regression.

## Fix map

Binder connection/envelopes/session/artifacts -> RiftVortexBridgeClient.kt.

Process lifetime -> RiftMcpRuntime.kt.

Shell routing/grammar/script-source bound -> RiftNativeShellServices.kt.

MCP image extraction/sanitization -> RiftMcpServer.kt.

Foreground assistance -> RiftVortexLocalAgent.kt.

RiftOS package visibility -> AndroidManifest.xml.

Vortex protocol/service behavior -> separate Vortex3D debug-source owners.

## Validation

Second RiftOS source audit must verify:
- fixed package/service/descriptor/transaction;
- BIND_AUTO_CREATE/BIND_IMPORTANT only to fixed component;
- 8-second bind timeout, 12-second per-transaction timeout and reconnect behavior;
- process-owned singleton;
- no network transport;
- 256 KiB request / 512 KiB response limits;
- 240 KiB script limit;
- strict shell grammar and finite touch coordinates;
- session kind/timing bounds;
- 192 KiB chunk size;
- 512 KiB image / 128 MiB pull limits;
- MIME allowlist;
- offset/bytes/next/size/EOF consistency checks;
- temp/final length verification;
- canonical bridge output root/name handling;
- MCP image sanitization path.

Cross-check the current Vortex3D debug service descriptor/chunk/request contract when that repository changes.

Builder/device validation remains separate.
