# Independent RiftOS Core and graphical Shell

## Current architecture decision — 2026-10-10

**QuickJS stays inside the signed RiftOS Android host as the single embedded JavaScript engine exception.** It is a generic, zero-authority execution service exposed as `com.riftos.app.RiftHostQuickJsV1`, with `abiVersion()` and `execute(runtime, input, outputLimit)`. The host QuickJS executor enforces source/input/output sizes and its execution deadline. The service **cannot own RAPP sessions, grants, state, execution scheduling, filesystem effects, app installation or Core lifecycle**.

The independently compiled `IndependentCoreV1` owns application identity, the checked installed-package catalogue, session generations, bounded event queue, serialized execution, state persistence, graphical frame/focus validation and effect-result continuation. It calls **only** the host QuickJS V1 execution primitive, not the embedded `RiftCoreRuntime`, `RiftCoreAppExecutor`, `RiftRappCapabilityBroker` or another APK-owned Core owner. RAPP mutable program/state is capped at **1 MiB** and JS executable source at **8 MiB**. BOOT (0) and HOST_EFFECT_RESULT (13) are Core-internal and rejected through the public Shell input path.

**External Core activation remains blocked** by `EXTERNAL_CORE_PROMOTION_PROVEN = false`. A real independent permissions/grant/capability broker, package install/uninstall parity, signed-host QuickJS ABI proof, and physical prior-external N-1 recovery must pass before promotion. The current Core denies effects and rejects installation/uninstallation; this is not complete application parity. External graphical Shell also needs built-in system-app and crash/recovery parity.

### Build and release rules

The source for the independent Core and Shell lives in `external-components/`, outside the APK Kotlin Gradle source set. Compile with the **registered** `kotlin-android` toolchain and convert the classes with the generic RiftBuild D8 path. `HostAbiCompileOnly.kt` is only an SDK mirror of the actual APK host interfaces; strip **all five** host-owned generated class files (four interface classes and `RiftHostQuickJsV1.class`) before D8. No compile-only host class may be defined by the exported Core DEX. The protected verifier may reference only the approved host QuickJS service and explicit interface types, not APK-owned execution managers. See `BUILD_EVIDENCE.md` for precise versions and generated DEX hashes.

`runtime.js` and `riftapp.json` are the existing generic independent Core/Shell producer RAPP. They have only ordinary `build.local`, `fs.read`, `fs.write` and `window.title` capabilities and **no protected-component staging or activation privilege**. Generated Core/Shell artifacts are under `/D:/Builds/Components`. Do not activate a generated candidate without completing the gate.

The required host QuickJS ABI source change is **not in installed signed #705** until the user manually runs the normal signed Builder and installs the new APK. The existing signed #705 already device-proved an ordinary, permission-free **1,250,390-byte RAPP JavaScript source**, packed, installed and Core-executed, then cleanly uninstalled. That proof demonstrates the >1 MiB host runtime limit, **not external Core JavaScript execution**.

### External components and future expansion

The target remains: small stable Android bootstrap with MCP/relay, protected loader, native platform services and the QuickJS execution primitive; fully replaceable real Core and real graphical Shell; freely installed RAPPs. Future language engines can use other registered generic host execution interfaces. Keeping QuickJS in the host does **not** hardcode all future RAPPs to JavaScript. QuickJS implementation updates require a manually signed host APK; compatible Core/Shell changes should eventually not.

The older separately SHA-pinned JavaScript VM DEX / empty approved SHA pipeline is **retired in source**. Do not restore it unless the user changes this explicit architectural decision.

Read also `docs/systems/android-host/EXTERNAL_CORE_SHELL_ROADMAP.md` and `docs/systems/android-host/PROTECTED_COMPONENT_FORMAT.md`.
