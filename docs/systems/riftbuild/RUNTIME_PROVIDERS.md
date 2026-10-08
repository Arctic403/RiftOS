# C0.2.5 — External runtime/tool-provider boundary (migration gate A)

This contract is owned by RiftOS **only as a generic platform interface**. QuickJS, Rift++, JVM compilers, D8, and all project tools must eventually be separately installed providers. No language/compiler implementation belongs inside the permanent base APK.

## Current status: source implementation / pending real-device proof

* RiftOS implements `RiftExternalRuntimeProviders`, an execution-kind registry and Binder transport to **other installed Android packages**. Providers execute outside the RiftOS process. The platform owns registry parsing, signer-certificate pinning, Android component discovery, request bounds, call timeouts and process disconnection handling.
* `RiftRappHost` routes both `native-buffer-v1` and `quickjs-v1` execution through this generic provider boundary.
* **Compatibility rule for migration gate A:** when a kind is unregistered, the current internal execution remains available. Once a kind is registered, failed verification, missing provider, crash or timeout MUST fail closed; silently falling back to a bundled engine is forbidden.
* `riftbuild runtime-status` reports installed-provider readiness and `legacyEmbeddedFallback=true`. This flag must become false only after every existing QuickJS consumer has been migrated/proven.
* **Not complete yet:** the standalone QuickJS provider APK, privileged runtime install/registration flow, externalized shell `qjs`/`riftpp`/`semx`, and removal of QuickJS/embedded language scripts from the base APK. Do not claim these are complete or remove the current dependency before proving the provider with RiftBuild Hosted on a real device.
* User dispatches every RiftOS Builder workflow manually; never auto-trigger Builder.

## Registry contract

Only the platform's runtime installation service may create/update `<RiftOS app files>/riftfs/system/runtime-providers/registry.json` in a completed system. Gate A reads but does **not** provide runtime registration mutation. Filesystem write authority to this path must be restricted to trusted platform actions, not ordinary RAPP programs. Missing file means no registered external providers, not an error.

```json
{
  "schema": "riftos-runtime-providers/1",
  "providers": [
    {
      "id": "example-js-engine",
      "executorKind": "quickjs-v1",
      "package": "example.runtime.package",
      "service": "example.runtime.package.RuntimeService",
      "signerSha256": "<64 lowercase hex chars of current APK signing certificate DER SHA-256>"
    }
  ]
}
```

Each provider ID and execution kind must be unique. Up to 12 providers are supported in a 16KiB bounded registry. The provider package signer must be **one exact current APK signer** with DER certificate SHA-256 matching the registry pin. An installed service must match the explicit registered component and declare `com.riftos.runtime.EXECUTE_V1` as an exported service action. Registration must never imply ambient filesystem, signing or network authority.

## IPC wire contract

* Android service action: `com.riftos.runtime.EXECUTE_V1`
* Binder interface descriptor: `riftos.runtime.provider/1`
* Transaction code: `IBinder.FIRST_CALL_TRANSACTION`
* Parcel request order: `writeInterfaceToken(descriptor)`, UTF-8 schema string `riftos-runtime-exec/1`, `executorKind` string, executable/runtime byte array, input-envelope byte array, maximum output byte count.
* Parcel response: `writeNoException()` followed by one nonempty output byte array.
* Bounds: runtime at most 384KiB, input at most 192KiB; returned output at most 192KiB, with requested maximum clamped. Binder join timeout 1,200ms and call timeout 4,300ms (subject to RAPP host event watchdog). Crash, timeout or malformed reply is a hard provider failure.
* Provider must treat runtime source and input as untrusted; its own execution sandbox, language semantics, quotas and version belong to the independently installed provider. No RAPP capability token or OS secrets are sent to a provider. Effects return through the existing `RiftRappCapabilityBroker` after host validation.

## Promotion gates

1. Build/verify source contracts, compile RiftOS APK and test `riftbuild runtime-status` with absent registry. Existing RiftBuild Hosted and native-buffer RAPP should remain functional.
2. Build/sign/install standalone QuickJS provider APK as an independent software package. Install via generic package flow, not a baked QuickJS-specific bootstrap.
3. User-authorized registration with pinned signer identity, then verify provider readiness and RTP Binder request/response on-device. Force-kill provider and tamper signer/registry to prove fail-closed behavior.
4. Device-proof RiftBuild Hosted Compile → Preflight → Pack → Sign → Verify using external provider, plus native-buffer and shell regressions.
5. Migrate remaining shell/compatibility users to external providers or retire project-owned command surfaces. Remove `quickjs-kt`, `RiftRappQuickJsExecutor`, `RiftHeadlessJsRuntime` and embedded project JS assets only once **all** live dependencies are accounted for and device gates pass.
6. Verify no QuickJS code or shared engine library ships inside signed RiftOS APK; leave only generic registry, process supervision and capability broker.

Kotlin compiler-seed, D8, and JVM host externalization is a later gate using equivalent independent providers; do not conflate generic `build.local` orchestration with embedded language implementations.
