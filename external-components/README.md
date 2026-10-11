# RiftOS independent Core and Shell components

## 2026-10-11 — External Core v0.4 development checkpoint (NOT QUALIFIED)

User-approved direction: **keep improving the preexisting external Core; do not duplicate the entire embedded Core or change the running signed APK**. The source for the independently compiled Core is entirely in this directory and implements the APK-published `RiftCoreComponentV1` and `RiftCoreExecutionViewV1` interfaces. QuickJS remains the *single* generic JavaScript engine in the signed Android APK, accessed only by `RiftHostQuickJsV1`. External Core must never call `RiftCoreRuntime`, `RiftCoreAppExecutor`, `RiftRappCapabilityBroker` or any APK-owned Core class.

**Now extracted from working embedded Core into standalone Core source:**

- `IndependentRappPackagesV1.kt`: production-derived `.rapp` ZIP/package format, manifest/runtime bounds, transactional pack/install/replace/uninstall and state persistence. APK-owned lifecycle dependencies are replaced by Core-owned start/stop/invalidate callbacks. Paths match the real `/C:/Programs` and `/D:/Builds` virtual roots. Package updates/uninstalls revoke stored RAPP grants using an independent package-grant module.
- `ExternalRiftAppAbi.kt` plus three `ExternalRiftRapp*Adapter.kt` files: the complete existing JSON and Rift++ RPE3/RPE4/RWS2/RWS4 transport contracts, copied to the **external package namespace**. Independent RAPP sessions select and validate the installed adapter/ABI/presentation and produce frame geometry through its own typed adapter, rather than assuming every installed RAPP is JSON.
- `IndependentRappEffectsV1.kt` and `ExternalRiftVolumePaths.kt`: Core-owned declared-capability and already-user-approved grant checks, scoped/size-limited filesystem read/write and window-title results, Core-owned effect/result continuation and existing grant revocation. Newly requesting consent is **not automatically approved**. Unqualified build/signing, network, clipboard, sharing and provider capabilities remain explicitly denied.
- `IndependentRappSessionsV1.kt`: independently owned FIFO dispatch, generations, state, surfaces, input focus and supported QuickJS typed adapter execution, with rejection of reserved BOOT/effect result from Shell events. Mutable program state is 1 MiB, JS runtime source max 8 MiB, and output is 512 KiB. The package manager invalidates old sessions when replacing/uninstalling an app.

**Production gaps — why external Core activation is still blocked:**
1. The current APK-host V1 exposes **QuickJS only**, not the production native-buffer runtime provider. Rift++ RPE3/RPE4 adapter **protocols are ported**, but their actual native-buffer execution is deliberately rejected, never masqueraded as QuickJS. Supporting this needs a narrow, versioned generic host execution service and a **future user-authorized signed host update**. Do NOT modify the host during an in-flight Patch B build.
2. Fresh user-consent prompts and privileged Android effects need Core-owned policy + approved stable host primitives, not calls back into embedded Core. Unqualified effects must remain denied. Existing permissions grants may be honored if declared and still bound to an installed package.
3. Physical parity for all installed RAPPs, real package transactions and effect/denial continuations, plus independent Core process startup and user-controlled backup/restore through the APK's Patch B Recovery UI, are **not yet proven**.
4. Keep `EXTERNAL_CORE_PROMOTION_PROVEN = false`. A green Kotlin/D8 result proves packaging, **not** execution or release qualification.

### Build

`compile-core.json` is the canonical registered `kotlin-android` recipe (source list includes all extracted external Core classes); `runtime.js` in the existing `rift-critical-component-builder` RAPP contains the **same source list**. Both use the generic registered compiler and D8; the producer strips five compile-only APK host ABI class stubs before D8. No separate JavaScript VM DEX, no new compiler, no legacy signing flow. Producer emits a candidate `core.dex` and versioned `component.json` to `/D:/Builds/Components/core`, but has **no component activation privilege**.

External Shell is separately compiled from `IndependentGraphicalShellV1.kt` and still awaits system-app/desktop parity and physical proof. Do not remove embedded Core/Shell or automatic fallback before user-accepted manual Recovery Patch B and later cleanup Patch C.

Read [build receipts](BUILD_EVIDENCE.md), [Core extraction plan](../../AI-Handoffs/riftos-external-core-qualification-plan-20261011.md), and [locked APK recovery design](../docs/systems/android-host/SIMPLE_BOOTSTRAP_RECOVERY_LOCK.md).
