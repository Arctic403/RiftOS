# RiftOS Project Status

## Verification status

**CURRENT ENGINE STATUS VERIFIED AGAINST SOURCE — 2026-09-30.**

This file reports both current source state and explicitly identified installed-device proof. Source-only claims are labeled separately from installed-device evidence; a successful install or launch request is not treated as proof that the launched target survived its own loader/runtime initialization.

## Android target

From `android/app/build.gradle.kts`:
- application id `com.riftos.app`;
- min SDK 26;
- target/compile SDK 36;
- Java 17;
- version `0.11.11-relay-client`;
- release minification disabled.

## Engine state

### Source-verified live architecture

- `MainActivity` is an Android-native desktop host with no WebKit imports.
- `RiftNativeDesktop` owns launcher, taskbar, native window records, z-order and window geometry/state.
- Terminal and Task Manager are native through `RiftNativeSystemApps`.
- Files, Editor, Dev Lab, Workspace Records and Settings are native through `RiftNativeWorkspaceApps`.
- `RiftMcpRuntime` owns process-wide native shell, MCP host/server/relay, native Git, Vortex bridge and the Codynex LR0 Binder bridge.
- `RiftMcpRuntime` also owns one process-wide passive `RiftDebugHub` and one persistent `RiftMcpOperationJournal`; MCP Server and Tool Host publish correlated spans while journaled calls get restart-safe operation identity, no-replay state, and read-only reconciliation.
- Current source adds the fixed `codynex` shell family over an explicit Binder binding to `com.codynex.lr0lab/.CodynexBridgeService`, with bounded JSON/source sizes, bounded bind/RPC timeouts and one reconnect after Binder death. `RiftCodynexBridgeClient.kt` is now part of the exact 49-file Gradle Kotlin snapshot; installed-device bridge proof still waits for the next RiftOS build/install.
- `RiftNativeShell` is the live shell executor and has no renderer fallback.
- **RiftBuild bundled native-toolchain provisioning is source-implemented and Builder payload generation is proven.** The second `fb83e531…` Builder attempt successfully packaged Termux Clang/LLD 21.1.8-3 for both `armeabi-v7a` and `arm64-v8a` (8 private runtime libraries per ABI) and emitted the 177,781,923-byte `assets/riftbuild/android-clang-v1.zip` with SHA-256 `c9022831…`. That run then reached Kotlin compilation and exposed one source typo in `RiftBuildNativeToolchain.installBundled`: ZIP path normalization encoded two backslashes too many in a Kotlin `Char` literal. Current source uses the valid escaped-backslash char, and `test-riftbuild-native.mjs` now gates the exact good/bad forms so the same syntax regression fails before the expensive Builder stages. Installed-device C++ proof remains pending.
- **RiftBuild generic native-app preparation is source-implemented.** `riftbuild prepare-native-app <project>` now consumes bounded `rift-app.json`, emits a dynamic NativeActivity binary manifest, and materializes project `assets/` into the prepared APK tree while preserving ABI libraries. This completes the source-level C++ + custom-script/assets → prepared APK path; Builder and installed-device proof remain pending.
- **RiftBuild Native Compile V1 is source-implemented.** `riftbuild toolchain-status` and `riftbuild compile-native <project> [arm32|arm64|universal]` now route through `RiftBuildNativeToolchain`, which permits explicitly configured local/downloaded Android-host compiler toolchains and launches the compiler with a structured argv vector rather than shell command text. Per-project `rift-native.json` selects bounded source/include sets, link libraries, C++17/20/23, API level and optimization; toolchain manifests may supply bounded driver argv for resource/libc++ setup; outputs are confined to `build/riftbuild/prepared/lib/{arm64-v8a,armeabi-v7a}`, then independently ELF-checked and hashed. Builder compilation, compatible toolchain provisioning and installed-device ARM32/ARM64 compile proof remain pending.
- **Rift++ machine-code compiler host C1 + generated ARM32 payload proof are installed and live-proven on source `44b7f8b2456c9403b7bc7504e92182106798a96c`.** Live `riftpp-host status` reports `hostAbi=armeabi-v7a`, private process `:riftppCompiler`, and `riftOsCompilerSemantics=false`. The exact 276-byte ARM32 compiler identity `1725b5341e87a09943737130a945d8ee500492370da8ce648f696b331e118653` compiled `ret 0/1/9/42/255` to the exact documented 32-byte dual-ABI bundles. `riftpp-host prove` then executed only the generated ARM32 payload from each same compile result and returned `0/1/9/42/255` respectively with proof status `0`. Invalid `ret 01` returned compiler rejection and no generated-payload proof fields. Previous malformed-input, output-bound and compiler-identity rejection proofs remain valid. Crash/timeout fault injection and ARM64-host/cross-host proof remain pending. The older QuickJS `riftpp` route remains legacy compatibility/reference, not authority for the new compiler.
- **Rift++ Stage1 is now real-device self-host proven and frozen on installed source `7b57df89a22ccedd603a012e467359c8e4a718e2` / Builder run 433.** `riftpp-host stage1-selfhost rift++` succeeded twice on `armeabi-v7a`. Frozen Seed0 reconstructed the exact 340-byte ARM32 Stage1 image (`7b11fae1…`) and exact 336-byte ARM64 Stage1 image (`1d5a87ef…`) solely through Seed0 compile + generated-payload return values. The reconstructed ARM32 Stage1 then executed natively, compiled its complete ARM32 source back to the identical 340-byte image, and compiled the complete ARM64 Stage1 source to the identical 336-byte ARM64 image. Both runs reported `selfHostedCurrentAbi=true`, `crossTargetReproduced=true`, `hostParsesStage1Numbers=false`, and `hostEmitsStage1Instructions=false`. Stage1 is bootstrap infrastructure only and is frozen; the next active phase is the tiny real Rift++ compiler/runtime. Current source adds `riftpp-host stage1-selfhost <riftpp-root>` over the existing private `:riftppCompiler` process. The transaction admits only the exact frozen Seed0 host compiler plus Stage1 source SHA-256 values `ed2fb30f…` (ARM32) and `e3415740…` (ARM64). Native bootstrap code frames newline records but never decodes `N`; each record must be compiled by Seed0 and its generated current-ABI payload must execute to produce the appended byte. The reconstructed Stage1 host compiler then compiles both complete Stage1 sources. Success requires exact 340-byte ARM32 identity `7b11fae1…`, exact 336-byte ARM64 identity `1d5a87ef…`, and byte-identical bootstrap/self outputs. This is a fixed proof transaction, not a general arbitrary-image compiler/executor.
- **Rift++ S2 self-host is PROMOTED / FROZEN on installed source `5206c80c5e7b1279bda63e566af19569d462d43b` / Builder run 445.** `riftpp-host s2-selfhost rift++` succeeded twice deterministically on the real `armeabi-v7a` device. Generation A compiled the canonical ARM32 + ARM64 sources into 44288-byte B images; current-host B executed and emitted 44288-byte C images for both targets; current-host C executed and reproduced itself byte-for-byte as D for both targets (`generationCArm32EqualsD=true`, `generationCArm64EqualsD=true`); C cross-compiled both targets; and C compiled the fixed ret42 sources to exact 96-byte outputs. The generated ARM32 proof executed with status `0` and returned `42`. Promoted Generation-C identities are ARM32 `13c691dcb1214d7a66ac8d931907a25d96ac12a42b9f52ba2a9b8dfa0d344aa2` and ARM64 `cf9de173f31cb745a2d7afd32959d798b2ee76e78b0f0cd2775fa0bc6a137600`; proof identities are ARM32 `1f9ffbb7a94afcc37821d0686d6cc1c23c76258ae54eec0cbd97f85ccd09631f` and ARM64 `6b99c0501765629c7752f361617bfe56350fb974e7fa790b7d7c5992e139b5c4`. Both repeated runs reported `generationACompiledCanonicalCompiler=true`, `generationBCurrentAbiExecuted=true`, `generationBCompiledBothTargets=true`, `generationCCurrentAbiExecuted=true`, `generationCCompiledBothTargets=true`, `hostParsesS2Opcodes=false`, and `hostEmitsS2Instructions=false`. Native ARM64 execution remains deferred to compatible hardware/userspace; cross-target ARM64 C=D reproduction is part of the promoted proof. S3 permanent-base minimization is now active; the final per-app frozen base is not yet frozen.
- **Rift++ seed0 ARM64-only proof APK lane is source-implemented and awaiting Builder/install proof.** Current source adds an AArch64-only NativeActivity harness compiled only for `arm64-v8a`, a fixed `proofs/riftpp-seed0-arm64` packaging project, and `riftbuild prepare-riftpp-seed0-arm64 <riftpp-root>`. The materializer accepts only the canonical 276-byte ARM64 compiler identity, extracts the ARM64 proof host from RiftOS's own universal APK, packages no ARM32 fallback, and leaves all Rift++ parsing/emission authority in the compiler bytes. The proof harness checks the five exact cross-host bundles, generated ARM64 returns, determinism and rejection corpus; install failure due unsupported ABI is treated as device/userspace evidence, not compiler failure.

- **Rift++ Android Native R1 Bionic-valid load gate is source-staged.** Checkpoint `ca71050ac0043719ac16eb2c15f9c4cd377ab600` introduced the 3-second 228-byte ARM32 entry probe plus a compact 3,424-byte Rift++ ELF emitter that emits a 972-byte ET_DYN object with a real 7-entry section table (`.dynstr`, `.dynsym`, `.hash`, `.dynamic`, `.text`, `.shstrtab`). The first live repro on run 482 exposed a host admission typo: the emitter transport contains 213 fixed records total (1 S3 header + 212 body), so decoded source size is 1,704 bytes, not the 1,696 body-only byte count. Current source fixes that gate to 1,704 and regression-pins it. A rebuilt RiftOS from current source must rerun the R1 device repro.
- **RiftBrowser bounded editor bridge is source-complete and awaiting Builder/install proof.** The active HTTPS-page inspector now supports explicit `edit` plus Base64-safe `edit-b64` for non-sensitive text inputs, textareas and contenteditable editor surfaces, with a 256 KiB UTF-8 ceiling, reset support and password/secret/token/API-key/authorization guards. It still exposes no arbitrary JavaScript execution, form submission/deploy authority, cookies, storage, headers or control-value readback.
- `RiftToolSandbox` is hard-scoped to `filesDir/riftfs/workspace`.
- the current source model-visible MCP catalog is exactly 21 tools; `rift_debug` is passive/read-only and `rift_mcp_reconcile` is persistent/read-only.
- `RiftWorkspaceRecords` is native/shared; `RiftWorkspaceWatcher` is Activity-owned and is recreated with `MainActivity`.
- `RiftDiffEngineV2` provides bounded adaptive exact-LCS/patience multi-hunk text diffs to Workspace Records; it is evidence formatting only and does not approve patches.
- `RiftFileIdentityV2` adds exact SHA rename/copy content identity plus bounded non-exact rename/rewrite similarity evidence; it does not infer user intent or authorize mutations.
- `RiftPatchSessions` binds explicit MCP/Shell/Editor/Dev Lab/Git writer provenance to asynchronous Workspace Records observations; exact file claims are state-bound, directory claims are lower-confidence, and unknown writers remain `unattributed-local`. It is evidence-only and does not enforce acceptance.
- `RiftPatchManifestV1` deterministically binds operational base/result tree bytes, change-set/structural identity, retained provenance and record-chain state; private freezes are SHA-addressed and trusted-checkpoint fields are inert with no promotion API.
- `RiftSourceIntelligenceV2` is the shared lexical analyzer for normal Project Intelligence v2 indexing and candidate before/after semantic deltas. The internal candidate-impact path derives affected symbols/dependencies/dependents/references/tests/docs from Patch Manifest V1 and remains OBSERVE evidence only.
- Native RiftCLI Gates N0 and N1 retain historical on-device proof. N1.6 Batch V2 also retains historical proof from Builder run #259 / source `eaa2a390438784be435929e49283f9e6281b8ed0`, but **CLI-owned batching is intentionally retired in the current rollback branch**. Current RiftCLI reports `batchV2=false`, `batchV2MaxSteps=0`, and `batchOwner=riftos-local-agent`; direct `rift_local_agent_batch` is installed-device partially proven on source `e4d32aa87d82840ea0d64e5622b5f647e27b3b55`, Builder run `36271037740` / run #406. Live proof covers tool publication, queued→completed execution, status/result/list, exact-plan requestId deduplication, same-requestId/different-plan rejection, the 16-step ceiling, result paging, continue-on-error, stop-on-error and terminal cancel behavior. Restart/no-replay and true in-flight cancellation remain pending installed-device proof. **N1.5 persistent push is historically live-proven on the installed Android build from source `0814fb8cf8ca31186d6e639fc7ad3b965d822897` (2026-09-21).** The live Cloudflare Durable Object reported the current N1.5 health surface (`deviceConnected`, `driverSockets`, `sseClients`, `cliSequence`). With an external Chrome SSE subscriber attached, a read-only RiftCLI `version` job advanced the relay sequence and DebugHub independently showed matching `event.created`, `cli.event.send`=`queued`, and `cli.ack`=`received` records for all lifecycle events; Chrome received the same sequences without polling. Replay was then proven by forcing `sseClients: 0`, creating a three-event job while disconnected, and reconnecting from the prior cursor; exactly the missed sequences replayed in order with no duplicate or older event. After Builder run `35542173887` exposed a syntax-corrupted wiring validator, the source validator was repaired and the external Builder was hardened with independent source-gate syntax preflight, required N1.5/DebugHub npm-entrypoint checks and final signed-DEX N1.5 marker verification. The canonical CLI core remains C++ under `android/app/src/main/cpp/riftcli/`; `RiftCliHost.kt` remains a thin JNI transport host only.
- **Local Agent engineering batch expansion is source-implemented and awaiting Builder/install proof.** The same persistent 16-step `rift_local_agent_batch` now accepts bounded Code Mode engineering steps (`read`, `read_range`, `write`, guarded replace/patch operations, list/stat/hash, search/grep, symbols/references, project/snapshot, audit/scan, project export, workspace diff and passive debug) in addition to the existing device/UI operations. Engineering work is delegated through `RiftToolHost -> RiftToolSandbox`, preserving normal workspace confinement, provenance, atomic/guarded mutation and search/index bounds rather than creating a second filesystem authority. Read-only plans require read permission; any workspace/UI mutation requires read+write. Raw RiftShell batching remains disabled.
- **RiftCLI N1.7 zero-poll steady-state hardening is source-complete but not yet installed-proven.** The native C++ status/architecture contract now reports `driverObservationMode=persistent-push-steady-state`, `automaticPolling=false` and `pollFallbackOnly=true`. `rift_cli_job_list` / `rift_cli_job_poll` remain explicit recovery/debug fallbacks and `rift_cli_job_cancel` remains explicit control; there is no internal automatic poll loop. Builder regression coverage now locks both the contract fields and the explicit-only poll call structure. Remaining N1.7 restart/backpressure/large-result/concurrency/cancellation stress still requires a green Builder artifact and installed-device proof.
- RiftCLI N2 is now frozen as the **federated Rift Memory Kernel roadmap** in `docs/systems/riftmemory/N2_FEDERATED_MEMORY_ROADMAP.md`. It is roadmap-only: no N2 runtime is claimed active. The design requires one canonical evidence/event/transaction/reconciliation authority with rebuildable specialist engines, SQLite as the initial reference `MemoryStore`, a benchmarked RiftStore replacement experiment, Observer/Validator reconciliation, integrity/poisoning/crash/rebuild gates, public/private benchmark suites, incremental hybrids and ablations. **N3 is hard-blocked until N2.12 promotion evidence passes the mandatory weakest-link categories and Android resource gates.**
- RiftCLI targets `arm64-v8a` as the primary ABI and `armeabi-v7a` as required 32-bit compatibility. Gradle declares both and pins the native CMake source snapshot.
- Gate N0 proved the minimal native bootstrap on-device. Gate N1 adds `rift-cli driver request`: while explicitly enabled, the C++ core may authorize one RiftOS shell action or one direct `rift_*` ToolHost action per request. Every authority-bearing request has a replay-protected request-id; authority work is job-based instead of blocking; jobs can be recovered/listed, polled or cancelled; direct model/API clients remain absent and driver continuation stays external and bounded. N1.5 makes persistent relay push the normal job-observation path with a bounded device-owned replay ring and WebSocket/SSE fan-out, while polling stays recovery/debug fallback. Historical N1.6 added `rift_cli_batch`, but that dispatcher is now retired and fails closed; current batching is owned directly by `rift_local_agent_batch` outside RiftCLI.
- The former Experimental RiftCLI Kotlin lifecycle/research/docs-parity/verification/swarm/IR/tokenizer implementation and its focused tests are retired. Shared Project Intelligence, Workspace Records, Diff/File Identity, Patch Manifest/Sessions, Git, MCP, Dev Lab and Local Agent remain independent reusable RiftOS authorities.
- The permanent reasoning dependency is external driver → MCP/RiftShell → RiftCLI → existing RiftOS authorities. RiftCLI does not call a model or inference API. When explicitly enabled, RiftCLI may authorize full RiftOS authority with exactly one outstanding authority job globally across the active shell + ToolHost lanes. CLI Batch V2 no longer owns that slot because the batch dispatcher is retired; direct Local Agent batches use their separate Local Agent execution lease. Accepted request IDs are retained without eviction for the RiftOS process lifetime and replay-protected, duplicate transport retries cannot silently re-run an action, cancellation remains observable, and any multi-turn information loop is externally advanced by the driver and capped at 8 steps. Retired RiftShell `batch` and multi-op `rift_workspace_exec` remain disabled.
- `RiftSecretStore` is the Android Keystore-backed secret owner.
- Installed RiftBuild on source `1c1ae33b81cfe643eb804cac0841ced636e982e3` / Builder run 214 has device-proven Rift++ V0 ELF materialization, the fixed 1,440-byte binary Android manifest, project validation/planning and deterministic universal unsigned APK packaging under `D:/Builds`.
- `RiftApkV2Signer` is the live bounded Android-Keystore RSA-2048 APK Signature Scheme v2 signer/verifier. The sign/verify path is installed-device proven and remains independent from target launch/runtime survival.
- `RiftBuildInstaller` is the live user-confirmed PackageInstaller/launch owner for its fixed allowlist, including `com.riftpp.nativeproof`, `com.riftpp.hello`, `com.riftpp.editor` and `com.riftpp.editor.nativev1`. Installed-device evidence proves APK install success and exact launch requests; target survival is a separate gate and is not inferred from `launchProven` alone.
- Native Files owns persisted Android SAF document-tree mounts.
- `RiftBrowser*` classes are the only allowed WebKit/Chromium owners.
- installed HTML/JS programs already present under `C:/Programs` can be launched by `RiftBrowserAppHost`.
- production `riftpp` routes through `RiftHeadlessJsRuntime` / QuickJS.
- `semx` routes through the same bounded headless QuickJS owner using the separately packaged Semnexis bootstrap asset. Semnexis 0.6 is installed-device proven on source `1d743b6fda2f5e7f1085186bc4bf6e9bbbd3ef36`, including checked add/sub/mul/div, `r4-r7` allocation, CFG/phi conditionals and explicit-state loop backedges. The 0.6.1 hardening layer remains source verified: IR effects/capabilities are re-derived, runtime ELF verification is bound to canonical IR lowering, cyclic phi copies use parallel-copy resolution, compiler/AST/CFG/artifact/output budgets are enforced, and Builder runs independent ARM32 execution plus the exact embedded `semx self-test`. Current `0.7.0-quickjs-bootstrap` source now reaches additive `SNIRV7`: `u8`, borrowed `Slice<u8>`, flat records, record projection/phis/loop state, verified `u8`→`i32` widening, `Arena` state descriptors, typed `arena_store` / `arena_load<Record>`, flattened record stack arguments, bounded native recursion and recursive-descent parsing. Source/machine regression builds a five-node Arena AST for `1+(2+3)` and recursively evaluates it to `6`; frame 256 succeeds and frame 257 traps. The current source-embedded promotion gate is `semnexis-bootstrap-self-test/17`; the installed APK still exposes older `semx` wiring until the next RiftOS build/install.
- Gradle packages only `src/riftpp-core.js`, `src/riftvm.js` and `src/semnexis-bootstrap.js` from `src/`.

### Logical RiftKernel

`RiftKernel` remains the protected logical engine identity reported by the native shell. It is implemented across native owners; `src/riftcore.js` is not the live installed kernel.

The native logical task model protects:
- `kernel`;
- `desktop`;
- `shell`.

Visible desktop windows are user tasks, not Android/Linux PIDs.

## Browser state

`RiftBrowserEngine` is the renderer interface. `RiftBrowserAndroidWebViewEngine` is the current backend.

The live native composition reaches browser open, Android Back, state query and the bounded active-page inspector. `RiftBrowserWindow` also implements forward/reload/direct navigation/desktop-mode/new/select/close-tab/visibility/bounds APIs, but the source audit found no current Kotlin callers for those methods, and MainActivity passes a no-op browser state sink. They are therefore implemented-but-unwired, not active UI features.

File chooser ownership, renderer crash containment and the exact-origin MCP compatibility bridge remain RiftBrowser-owned. A hard Gradle preBuild validator rejects WebKit imports/renderer code outside explicit `RiftBrowser*` sources.

## Rift++ / executable state

The older installed/reference `riftpp` language path is native shell → headless QuickJS → Rift++ Core/RiftVM assets. It is retained as legacy compatibility/reference; the new compiler-development authority is the separately owned Rift++ machine-code compiler executed through `riftpp-host`.

`.rxe` compile/inspect/run/exec behavior is implemented inside the bounded headless runtime. There is no live Kotlin `RiftRT` class.

Historical `src/riftrt.js` and its Worker/WASM/application-runtime architecture are retained source/reference unless a current native owner explicitly uses them.

## Installed-program state

The current source can discover and host packages already present in `C:/Programs/<id>/package.json`.

The current working tree now contains a native `RiftBuildInstaller` bootstrap installer, but it is source-only until the next Builder/install pass. It is deliberately restricted to the signed `com.riftpp.nativeproof` artifact and does not replace the older general JavaScript app-package design (`src/riftapps.js`), which remains unpackaged.

## Retired/non-live engine paths

The following must not be described as current APK engine authority:
- shell WebView / trusted compatibility renderer;
- `RiftShellBridge`;
- `RiftNativeDispatcher`;
- `RiftSystemDump`;
- JavaScript `RiftOSCore` as installed kernel authority;
- `RiftAndroid` web-shell boot bridge;
- `index.html` as the OS bootstrap;
- `riftandroid-entry.js` as the APK boot chain;
- `src/riftgit.js` as live Git owner;
- `src/riftdevlab.js` as live Dev Lab owner;
- `workspace-live` HTML dashboard as the live Workspace Records UI;
- `src/riftruntime.js` as live capability truth;
- generic RiftShell `mount`/`umount` compatibility route;
- generic `rift` local-platform wrapper.

## Known source/documentation gaps found by this audit

- Previous docs incorrectly described the old web runtime as the active RiftKernel.
- Previous boot docs described HTML/JS module boot even though Gradle no longer packages it.
- Previous status docs claimed the old exact-origin `RiftAndroid` shell bridge was active.
- Previous status/root docs claimed System Dump remained in Settings; its source was removed.
- Previous docs claimed web Workspace Records was the live UI; the built-in is now native.
- Previous docs described the JavaScript RiftRT/app installer as active despite Gradle not packaging those modules.
- Previous `src/README.md` incorrectly called the whole folder packaged runtime source.
- Previous runtime-capability docs treated `window.RiftRuntime` as live even though it is not packaged.

These claims are being purged during the current code-first documentation audit.

## Build/device proof status

The current migration is **source-audited, not yet promoted**.

Still required before calling the runtime proven:
- execute updated repository source checks;
- push only with explicit authorization;
- external Android Builder compile/package/sign verification;
- install the exact artifact;
- cold start;
- native window/task abuse;
- background/foreground and Activity lifecycle abuse;
- Files SAF mount/edit abuse;
- browser/app/preview renderer loss tests;
- MCP/native shell survival;
- final Builder/regression pass.

## Next documentation sequence

The engine/core pass is followed by a second engine re-audit. After the engine is clean, each subsystem is audited independently from source and only then marked verified/trusted.


## Rift++ active installed/reference state

The installed/reference Rift++ compiler is `0.10.0-bootstrap` / `riftpp/1` targeting `rift-exec-v1 / riftvm-1`; the currently installed RiftOS proof host is source `1c1ae33b81cfe643eb804cac0841ced636e982e3` / Builder run 214.

Current proven state:
- promoted checked `u8` plus real `Buffer<u8,N>` / `Slice<u8>`;
- Rift Text reference V4 including TextString backing/views/cache and exact-size planning;
- portable system-runtime core V1 PASS / frozen reference;
- RiftLLM+ linked compile PASS at 2 modules / 28 functions / 150,796 bytes;
- Rift IR v1 feature level 0 PASS / frozen prototype;
- ARMv7 + AArch64 leaf backend assembly/object/link proof PASS.

The direct Rift++ V0 ELF materialization, fixed binary Android manifest and unsigned APK package path are now installed-device proven. The remaining bootstrap gap is to Builder-compile/install the current v2 signer/PackageInstaller source and prove sign → independent verify → Android install → first launch; after that work returns to RiftLLM+ as the first real repository compile target.

## Rift++ historical candidate notes

Current workspace Rift++ source is the local `0.10.0-bootstrap` candidate / `riftpp/1` targeting `rift-exec-v1 / riftvm-1`; the installed APK still requires Builder/install proof.

- Gate 1A Buffer/Slice is frozen.
- Gate 1B SourceText/TextCursor/StringBuilder + numeric text is source-verified but not device-frozen.
- Gate 1B hot working text uses UTF-16 code units; UTF-8 remains explicit boundary accounting/interchange.
- `rift-tool text-model-benchmark` provides a fixed installed-device representation benchmark before Gate 1B freeze.


### Rift++ 0.10 native-byte substrate candidate

Local source adds checked `u8`, `Buffer<u8,N>` / `Slice<u8>`, explicit `u8_to_u32`, and checked `u8_from_u32`.

The compiler/VM test and wiring gates are updated, but the candidate is **not runtime-proven yet** because native RiftShell correctly denies arbitrary Node/process execution and the installed APK still carries the previous compiler. Builder/install/device proof is required.

No raw-pointer, filesystem, network, process, or host-import authority was added.
