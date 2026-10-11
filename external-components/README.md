# RiftOS independent Core and Shell components

## 2026-10-11 — External Core v0.5 native-buffer integration (NOT PROMOTED)

The existing `IndependentCoreV1` is the only external-Core candidate. It owns RAPP packages, sessions, adapter selection, frames, state, capability policy and effect-result continuations. No duplicate of APK-owned `RiftCoreRuntime` is allowed. `EXTERNAL_CORE_PROMOTION_PROVEN = false` remains locked until real device proof and a qualified prior-working recovery revision.

**Source implementation:**

- `IndependentRappPackagesV1.kt` provides the embedded-tested `.rapp` ZIP/package format, transactional pack/install/replace/uninstall, state migration and grant revocation through Core-owned callbacks.
- `ExternalRiftAppAbi.kt`, `ExternalRiftRapp*Adapter.kt` modules implement both JSON/QuickJS and Rift++ RPE3/RPE4/RWS2/RWS4 adapters. `IndependentRappSessionsV1.kt` chooses the installed adapter and runtime, enforces focused user input, separates Core lifecycle/effect events, and preserves Core-owned frame/state/queue.
- **New generic host runtime bridge:** the signed APK source `RiftHostNativeBufferV1` forwards only bounded runtime/input/output bytes through existing isolated `RiftNativeBufferCompilerService`, with output/status validation and timeout inside that worker. It has no RAPP session, package, permission or window authority. This complements the original APK-hosted `RiftHostQuickJsV1`; no second JavaScript VM is bundled. External Core selects based on `adapter.executorKind`, verifies both host API versions, and applies a stricter 256KiB native-buffer payload limit.
- `IndependentRappEffectsV1.kt` uses installed package identity, declared capabilities and **existing explicit grants** to enforce bounded filesystem read/write and no-prompt window title. Newly requested consent, privileged build/signing, network, clipboard/share and unregistered providers remain **denied**. Do not import the APK's `RiftRappCapabilityBroker` to circumvent this.

**Not production-qualified:** The newly added native-buffer host service must first exist in a **user-manually signed and device-proven APK**; previously installed signed #710 only has the QuickJS bridge. The standalone Core may compile against the new ABI but must not be activated on #710. The independent consent/platform bridge is not yet complete. Real installed RAPP execution across both adapter kinds, package lifecycle, capability outcomes, graphical Shell interoperability and an accepted-update/backup/restore cycle still require physical proof. Preserve embedded Core and old recovery until the user authorizes and completes those gates.

**Build:** `compile-core.json` and the `rift-critical-component-builder` RAPP's `runtime.js` use the same 12 Kotlin sources and the registered `kotlin-android` compiler + D8. The producer emits a **v0.5.0** experimental Core DEX and manifest but has **no activation authority**. It strips SIX compile-only APK host ABI class stubs before D8, including the new `RiftHostNativeBufferV1.class`. Source code lives only under `com.riftos.external.core`; the real host classes come from the signed APK parent loader, never from the independently compiled component.

See `BUILD_EVIDENCE.md`, `docs/systems/android-host/EXTERNAL_CORE_SHELL_ROADMAP.md`, and `docs/systems/android-host/SIMPLE_BOOTSTRAP_RECOVERY_LOCK.md`. Independent graphical Shell is a separate still-unqualified component; Patch C removal remains blocked.
