# Gate 2 — Generic Core-owned module importer (design + implementation gates)

**Baseline:** source `0f9ae746a331e5676571ffdb08a5266ac3cc3df3`, signed live RiftOS #685. User is the sole physical-device tester. No assistant-triggered builds, installations or device UI interactions.

## Gate 2-A/B source checkpoint (UNPUSHED; generic staging + activation, DEVICE UNPROVEN)

Code now in the RiftOS working tree (on Gate 1 pushed baseline) introduces:
- **RiftCoreModuleManifest.kt** — strict `riftos.module/1` identity/entrypoint/ABI/metadata/SDK/DEX SHA, unknown field rejection, empty capabilities/dependencies, deterministic content identity digest.
- **RiftCoreModuleStore.kt** — Core-only private content-addressed staging, immutable regular DEX, bounded verified SHA, atomically persisted canonical metadata and read-only staged inventory. Neither execution nor provider registration.
- Trusted Shell/Admin Approvals generic picker scans only `/D:/Builds/Modules/<id>/module.json` and a same-folder bounded `.dex`; no application identity is hardcoded. Core verifies selected manifest identity digest and DEX bytes again after a signer/PID/foreground-bound one-use 45-second ticket. Shared `RiftCoreSurfaceIpcProvider`/client carries only manifest text and read-only FD, never caller-supplied private module storage paths.
- **RiftCoreModuleActivation.kt** — generic **single active module slot** (multiple independently staged module IDs/revisions), atomic Core-private active/previous pointers, fsync'd pending startup marker, rollback of unsuccessful/interrupted start and bounded nonce-bound completion receipt. Pending startup is recovered on Core cold boot.
- **RiftGenericModuleService.kt** — one nonexported Android service running in `:riftModuleHost` (separate PROCESS, SAME Android app UID). Reads only Core's sealed active pointer, rejects APK class shadowing, validates DEX+manifest again, loads class implementing shared RiftBootstrapEntry ABI and records a successful return. Failure restores the prior activated revision pointer. No dynamic Android manifest components.
- Trusted UI adds a distinct stage scope and SHA/version/ID-bound **generic activation** scope with separate fresh one-use 45-second approval. `core status.moduleHost` reports activation, pending startup, proof nonce and PID, while explicitly reporting `separateAndroidUid=false`. The module Service can stop after a successful proof; a receipt is **not proof of continuing process liveness**.
- An independent proof fixture now lives in **external** `workspace/RiftModuleBuilder/src/com/riftos/genericproof/AlternateProof.kt` with entrypoint `com.riftos.genericproof.AlternateProof`. Its generic build option emits `/D:/Builds/Modules/example.alternate-proof/module.dex` and `module.json` with the actual D8-produced SHA-256. This fixture is NOT compiled into RiftOS APK or hardcoded in Core.
- Existing fixed ProbeV1 picker, importer and activation remain untouched until the new generic path is device-proven. No provider enrollment, general `software.install`, dynamic capabilities or app-specific hacks in generic Core.
- Gradle exact Kotlin source allowlist and JS source wiring guards updated. **NO full Android compile, signed APK build, push, installed RAPP rebuild or new device proof yet.** The user alone runs the eventual Builder, RAPP pack/install and physical-device tests.

Example producer-owned module directory (DEX bytes and SHA must really match):
```text
D:/Builds/Modules/example.module-proof/
  module.json
  module.dex
```

Example v1 manifest:
```json
{
  "schema": "riftos.module/1",
  "id": "example.module-proof",
  "name": "Example Module Proof",
  "version": "1.0.0",
  "kind": "dex-service-v1",
  "abi": "riftos.bootstrap-entry/1",
  "entrypoint": "com.example.module.ProofEntry",
  "payload": "module.dex",
  "sha256": "<real lowercase SHA-256 of module.dex>",
  "minSdk": 26,
  "maxSdk": 1000,
  "requestedCapabilities": [],
  "dependencies": []
}
```

The placeholder digest above is deliberately invalid; modules must be built independently and verified, not invented. A general-purpose module system will require separate adapters for Python/Node or signer-pinned Android providers. Do not pretend a DEX service is a full isolated process security domain.

## Gate 1 device evidence (2026-10-10)

The independent Module Builder RAPP compiled `ProbeV1.kt` using registered managed Kotlin compiler and D8 to `/D:/Builds/Modules/probe-v1.dex`, 2,563,188 bytes, D8 receipt SHA-256 `2d2373dfd13f0f7b9ab15a4146cfef8760a3c7d5aa0f6fa858b7d7feb461877d`. On #685 the user selected this file through trusted native RiftFS picker, Core explicitly refused staging without a ticket, accepted one-use stage and reported matching SHA prefix, accepted an independently approved digest-bound activate and requested the nonexported probe service. User-supplied `core status` showed Core PID 28315, Shell PID 28288, external probe PID 30999 in `com.riftos.app:riftBootstrapProbe`; `probeActivationPresent:true`, `probeProofPresent:true`, fresh proof schema `riftos.bootstrap-probe-proof/1` revision `probe-v1`. Core and Shell remained connected; 3 RAPPs installed, 0 runtime providers, 0 persistent system grants, 0 pending registry/rollback journals. Denial after user-chosen Deny returned `No stage approval ticket`. After waiting at least a minute following Allow once, a stage attempt returned Core denial `Admin ticket absent or used` (this **rejects stale bearer**; does not alone identify exact expiry cause). Probe process is distinct from Core/Shell but shares the host Android APK UID. Gate 1 positive + authorization tests passed; malformed-byte, failure recovery and system isolation edge tests still required.

**Frozen proof baseline:** keep existing fixed ProbeV1 stage/activate UI and all stable Core paths functioning until a truly independent generic module is proven and a separate explicit migration approved. Do not retire proof UI preemptively.

## Fundamental non-goals / boundaries

- Do not hardcode the Module Builder RAPP, ProbeV1 class name, Python, Node or other application-specific behavior into Core.
- Do not confuse a module installed into an Android app's own UID/process with an independently sandboxed Android application. DEX run in a RiftOS secondary process still has host app UID privileges. It is for **explicitly trusted, privileged** code only, not untrusted third-party runtime plugins.
- For untrusted/3rd-party runtimes use the **already generic external runtime provider** model: separately signed Android APKs, signer-pinned service metadata and Binder transport. That is a distinct enrollment gate (C2-B), not equivalent to injecting DEX.
- Never silently promote `runtime.register`, `software.install`, `system.fs.write` or other restricted Core policies.
- Existing RAPP generic execution, compile, signing, RiftBuild hot path and manual Builder remain independent and untouched.
- Core and Shell are still in the signed Android APK and must remain embedded; no arbitrary executable replacement of critical processes.

## Proposed generic trusted module ABI

- Manifest `riftos.module/1` (bounded strict JSON): module id, display label, module kind (`dex-service-v1`), ABI (`riftos.bootstrap-entry/1`), entrypoint class, DEX file name, exact SHA-256, requested permissions (empty list for initial proof), min/max supported API, version. Default-deny unsupported types/capabilities and unknown fields, forbid path traversal, links and shadowing APK classes.
- RAPP/compiler produces `/D:/Builds/Modules/<module-id>/module.json` and `module.dex` independently of Core. **Generic native selector** reads only public build folder, no knowledge of module names. Immutable manifests + read-only FD passed to protected Core binder; Core parses, bounds-checks, hashes against manifest and stages into Core-private content-addressed store.
- Fresh user-approved stage ticket pinned to a digest of the whole manifest/DEX pair (and module id); Core re-verifies on import. Stage does **not** execute, register a runtime provider or elevate anything.
- Fresh and separate user-approved activate ticket pinned to the staged exact module id/version/SHA-256. Core atomically updates a single active slot and its rollback journal (multiple IDs/revisions can be staged; parallel activations are a future gate), then launches only a predeclared nonexported Android module-host Service. **No dynamic Android manifest components.**
- Generic module host loads only an immutable verified DEX with `RiftBootstrapEntry.start(Application)`, runs in a secondary Android process with **same UID**, not Core/Shell. Host writes a bounded nonce-bound success receipt identifying module id/version/SHA/process PID, and rolls back the activation pointer on unsuccessful start; failures are logged and must not be misreported as execution proof. No automatic restart loops, no activation of Core/Shell.
- Read-only Core inventory/status of staged vs active versions and recovery; no module may choose Core-private paths. Limits: max 32 MiB DEX, max 4 KiB canonical manifest, bounded entry count, no symlinks/escapes, and journaled/atomic writes with no accidental overwrite of production data.
- Generic code should be reusable across module id and entrypoint; none of these values hardcoded in platform source. ProbeV1 remains as a legacy test fixture **only until** a second unrelated module id+entrypoint succeeds on device.

## Implementation progress (source built; device proof pending)

1. Lock positive Gate 1 proof, backup clean source; audit existing registry, Core descriptor IPC, startup/recovery and RAPP interfaces. (Complete.)
2. Implement strict generic manifest validator and read-only discovery; unit/source checks for traversal, SHA mismatch, size/capability/schema denials. (Next safe checkpoint.)
3. Implement generic stage and per-module version registry as Core-private data, bind one-use approval to immutable package manifest + content hash.
4. Implement separate one-use activation, generic nonexported host, boot marker/recovery and accurate proof receipt.
5. Add native UI that lists manifests from RiftOS Files with no fixed module id/class; exact consent details displayed before staging/activation. Preserve old ProbeV1 path.
6. Generate a **second independent generic test module**, not simply rename ProbeV1; independently compile/pack it outside RiftOS. Add source validation and docs, commit locally only.
7. At a reviewable source checkpoint, ask user for RiftGit push permission. User dispatches signed Builder, installs RiftOS, manually tests generic module positive path AND bad digest, missing entrypoint, denied/expired/replayed ticket, version rollback, process death/recovery, Core/Shell/RAPP survival. Promote only after evidence.

Device test manual policy: user alone taps/installs/tests; assistant may review screenshots and terminal output supplied by user, and may run static source checks. No GitHub API; RiftOS MCP + internal RiftGit only. No push without separate permission.
