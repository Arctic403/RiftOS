# Independent component build evidence — 2026-10-10

## Latest #702 external Core v0.2.1 — larger RAPP executable, registered Kotlin+D8 proof

After lifting the *native APK-host* RAPP executable `runtime.bin` cap from 1 MiB to **8 MiB** (and compressed/extracted `.rapp` limit from 4 MiB to **16 MiB**), aligned **incomplete independent Core** `IndependentRappSessionsV1.MAX_RUNTIME` and its per-file SHA catalogue reader with the 8 MiB source limit. Persistent RAPP `program.bin/state.bin` stays **1 MiB** and bounded event/result I/O remains unchanged. This permits *larger RAPP JS sources*, not inherently faster Kotlin/D8 compilation.

User-signed, installed #702 executed the registered `kotlin-android` compiler successfully on the revised source. All four compile-only host interface classes were removed before D8. D8 produced a single Core **v0.2.1** DEX of **2,604,100 bytes**, SHA-256 **`61b3e47bc9ebd7b3af654f10335f576251dea53579f8e79bf5370da4f2ee15ae`**; independent MCP hash confirms it, and native RiftShell stat confirms the saved `/D:/Builds/Components/core/core.dex` size. The exact `component.json` fields, `sha256` and `buildSha256`, both match the final DEX hash. Prior v0.2.0 DEX/manifest were copied into `/D:/Builds/Components/core/previous/` before replacement. This v0.2.1 Core remains intentionally **NON-SELECTABLE**: no independently proven JS VM or capability broker, no stage/activate/accepted N-1 proof. Installed #702 *host* still enforces 1 MiB until a later user-manual signed APK install.

## Later signed #702 Core v0.2 independent compiler evidence (2026-10-10)

On the user-installed signed **#702** host (run `38098117132`, source `eb6682f3eed93f5ea30478140d53df72c3273e9e`), the generic `External Core & Shell Builder` RAPP obtained ordinary **build.local** user permission and showed `Registered compiler + D8 ready`, confirming live `kotlin-android` registration.

Implemented `IndependentRappSessionsV1.kt` **outside the APK**, a real Core-owned bounded session/queue/frame/state/event engine and separate `IndependentJavascriptVmV1` interface. It persists state through Android `AtomicFile`, maintains generation/revision and focus, validates bounded JSON RAPP frame/event payloads and returns rejected capability effects as `HOST_EFFECT_RESULT` until the independent broker is added. It loads no APK `RiftCoreRuntime`/executor/QuickJS singleton. A separate SHA-checked Javascript VM loader exists, but Core v0.2 deliberately pins **no approved VM SHA**; a manifest's self-asserted approval cannot enable arbitrary runtime code. Therefore **RAPP JS still does not execute, and Core v0.2 MUST NOT be activated.**

Actual registered `kotlin-android` compiler on `HostAbiCompileOnly.kt`, `IndependentCoreV1.kt` and `IndependentRappSessionsV1.kt` succeeded **twice**, including after the VM SHA pin. Removed four compile-only host interface `.class` definitions before each D8. Latest D8 `dexed` a single **2,603,876-byte** raw DEX, SHA-256 **`c1b44f56481ee4ffc90b7e74812e07b64f5cc73e9c07e3e6b113f79a13399113`**; separate MCP `rift_hash` confirms exactly this SHA and bytes. Source DEX and target `/D:/Builds/Components/core/core.dex` both showed **2,603,876 bytes**; strict `core/component.json` has version **0.2.0** and the matching SHA, copied to the target path. Original Core v0.1 `core.dex` and its manifest were preserved at `/D:/Builds/Components/core/previous/`. Shell v0.1 `shell.dex` (2,594,884 bytes, SHA `f29747a5…`) was not modified.

The builder RAPP was repacked from the tracked source as `/D:/Builds/rift-critical-component-builder-66f8efda0d0c2692.rapp` (4,868 bytes) and installed successfully as `rift-critical-component-builder` on #702. After the update, read-only Core status still reports embedded Core, no external Core/Shell pending revisions, and the same Core/Shell/supervisor PIDs; the builder's previous live app session is no longer running and can be launched again. No protected stage or activation occurred.

**Unproven:** independently runnable JS/QuickJS VM DEX and its fully checked qualification, full RAPP effects/grants/install/uninstall, real external Core startup, guarded lifecycle device execution and accepted N-1 rollback. No Core/Shell release pointer, admin approval, or installed Android host code was changed. Next work: author+compile external JavaScript VM (actual RAPP execution, not APK forwarding), add independent authorization/grant layer, inspect real frames and state in a nonproduction test harness before even considering guarded staging.

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
