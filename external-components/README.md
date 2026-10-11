# Independent RiftOS Core and Shell components

This source/build producer is versioned in RiftOS main under external-components/, strictly outside android/app and the signed APK Gradle source list. It uses the registered external Kotlin compiler and D8. Its production DEX outputs live independently under /D:/Builds/Components/ and do not require repacking RiftOS for source-only changes.

## Implemented

- `IndependentCoreV1.kt`: implements both installed Core V1 interfaces. Reads genuine installed RAPP metadata, checks program/runtime SHA-256 and reports independent process state. Does NOT call APK-owned Core. RAPP JavaScript execution, input, installation and capability grants deliberately fail closed because that independent runtime has not yet been built.
- `IndependentGraphicalShellV1.kt`: implements the graphical V1 contract with its own launcher, Core IPC RAPP windows, surfaces, input, focus, dragging, minimize, close and Shell recovery hooks. System-app parity and device behavior still need testing.
- `HostAbiCompileOnly.kt`: ABI stub for Kotlin compilation only. The RAPP build recipe removes its four generated interface classes before D8. Parent APK loader provides real ABI at runtime.
- `runtime.js`, `riftapp.json`, `riftbuild-hot.json`: independent generic RAPP producer, using registered `kotlin-android` via `build.local/compilerRun` and `build.local/jvmDex`. NO admin or Core activation rights.

Outputs: `/D:/Builds/Components/core/core.dex`, `/D:/Builds/Components/core/component.json`, and matching `shell` files. The manifests use `riftos.protected-component/1`, ABI 1, exact D8 SHA-256, and version 0.1.0. The `buildSha256` is currently the DEX content digest, not independent source provenance.

## Host compatibility blocker

Current host-source correction (NOT installed in #701) allows only `Lkotlin/` bundled runtime definitions in protected DEX while rejecting duplicate/APK-owned classes. Do not stage until the user runs a new signed host build with this verifier patch; complete Core execution remains the main blocker.

The installed #701 host's D8 service packages `kotlin-stdlib.jar` as program input, but the protected DEX verifier rejects Kotlin support class definitions outside `com.riftos.external.core` or `com.riftos.external.shell`. That source mismatch must be repaired in the Android host, then manually signed and installed. Do not bypass it using retired compiler paths.

Do NOT activate the incomplete Core or treat a DEX build as production proof. Required next: full external JavaScript RAPP runtime, execution sessions and capability broker; complete graphical desktop/system-app parity; then real device staging, controlled activation, recovery and SHA/PID acceptance.
