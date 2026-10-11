# Independent component build evidence — 2026-10-10

This document tracks actual registered Android compiler and D8 outputs from live RiftOS **#701**, source `6a31b999d352c8758fb24cdcd87a3ed06a9e8fc7`. Source lives in this directory **outside** the RiftOS APK's exact 110-Kotlin-file Gradle list. The native generic build command was `riftbuild compiler-run` against the registered `kotlin-android` provider; it uses the same `build.local` compiler capabilities as the installed independent RAPP.

| Component | Kotlin compiler | D8 | Raw DEX bytes | DEX SHA-256 |
|---|---|---|---:|---|
| `IndependentCoreV1` | `success` | `dexed` | 2,574,604 | `44d1b9745f63fca19488c64531b9933faa1698aef1edb8741bd924b7f4d3abe2` |
| `IndependentGraphicalShellV1` | `success` | `dexed` | 2,594,884 | `f29747a5828b66717d900519101cc08fc538fa47851fe8e657c69ac6ba1b4276` |

Exact output files exist and native RiftShell `stat` confirmed the sizes:
- `/D:/Builds/Components/core/core.dex` and `/D:/Builds/Components/core/component.json`
- `/D:/Builds/Components/shell/shell.dex` and `/D:/Builds/Components/shell/component.json`

The four `HostAbiCompileOnly.kt` generated interface `.class` files were removed from BOTH compiler class directories BEFORE D8. Registered D8 adds the standard Kotlin runtime as program classes. Do not confuse Kotlin compilation/D8 with a protected exact-source production activation receipt; neither external component was staged/activated, and no native administrator ticket was used.

A separate `rift-critical-component-builder` generic `.rapp` was built and installed (latest artifact `/D:/Builds/rift-critical-component-builder-06240ef38b7549d6.rapp`); it has `build.local`, `fs.read`, `fs.write`, and `window.title` only—NO protected Core/Shell activation capabilities. Core confirmed that the first installed version ran as an attached RAPP with a 12-node surface. A replacement version with the now-tracked source path was installed; do not claim that its compilation buttons have been physically exercised yet.

**Important limitation:** the resulting DEX includes bundled Kotlin standard-library class definitions because of the generic host `RiftJvmDexService`; #701 protected DEX verifier rejects all class definitions outside the module namespace. A source patch to `RiftProtectedDexVerifier.kt` permits only `Lkotlin/` support definitions (besides exact external component namespace) and rejects duplicates or host-owned classes. This source change **requires another user-manual signed APK build** before a protected candidate could be staged. Full DEX class inventory and host qualified-link proof still need real-device verification; no claim that the new verifier already passed a DEX.

**Remaining engineering:** Core external QuickJS/VM execution, app sessions/queues, capability grants and atomic install/remove are NOT implemented; Core v0.1 explicitly fails closed rather than falsely starting existing JS RAPPs. Graphical Shell v0.1 owns generic RAPP windows/launcher/input but built-in system application parity and physical graphical behavior are unverified. Do not activate either version as a production replacement. After the independent executor and graphical parity gates are complete, manually build/test host verifier compatibility, then protected stage → distinct approved next-boot activation → actual device proof → distinct SHA/PID acceptance and external N-1 rollback.

Full protected contract: `docs/systems/android-host/PROTECTED_COMPONENT_FORMAT.md`.
