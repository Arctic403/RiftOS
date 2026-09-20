# RiftOS Project Status

## Verification status

**CURRENT ENGINE STATUS VERIFIED AGAINST SOURCE — 2026-09-19.**

This file reports both current local source state and explicitly identified installed-device proof. New signer/installer source in the current working tree is not called installed until a subsequent Builder/install pass proves it.

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
- `RiftMcpRuntime` also owns one process-wide passive `RiftDebugHub`; MCP Server and Tool Host publish correlated spans, and the reusable `RiftDebugAdapter` plug is ready for additional subsystems.
- Current source adds the fixed `codynex` shell family over an explicit Binder binding to `com.codynex.lr0lab/.CodynexBridgeService`, with bounded JSON/source sizes, bounded bind/RPC timeouts and one reconnect after Binder death. `RiftCodynexBridgeClient.kt` is now part of the exact 46-file Gradle Kotlin snapshot; installed-device bridge proof still waits for the next RiftOS build/install.
- `RiftNativeShell` is the live shell executor and has no renderer fallback.
- `RiftToolSandbox` is hard-scoped to `filesDir/riftfs/workspace`.
- the current model-visible MCP catalog is exactly 19 tools; `rift_debug` is passive/read-only.
- `RiftWorkspaceRecords` is native/shared; `RiftWorkspaceWatcher` is Activity-owned and is recreated with `MainActivity`.
- `RiftDiffEngineV2` provides bounded adaptive exact-LCS/patience multi-hunk text diffs to Workspace Records; it is evidence formatting only and does not approve patches.
- `RiftFileIdentityV2` adds exact SHA rename/copy content identity plus bounded non-exact rename/rewrite similarity evidence; it does not infer user intent or authorize mutations.
- `RiftPatchSessions` binds explicit MCP/Shell/Editor/Dev Lab/Git writer provenance to asynchronous Workspace Records observations; exact file claims are state-bound, directory claims are lower-confidence, and unknown writers remain `unattributed-local`. It is evidence-only and does not enforce acceptance.
- `RiftPatchManifestV1` deterministically binds operational base/result tree bytes, change-set/structural identity, retained provenance and record-chain state; private freezes are SHA-addressed and trusted-checkpoint fields are inert with no promotion API.
- `RiftSourceIntelligenceV2` is the shared lexical analyzer for normal Project Intelligence v2 indexing and candidate before/after semantic deltas. The internal candidate-impact path derives affected symbols/dependencies/dependents/references/tests/docs from Patch Manifest V1 and remains OBSERVE evidence only.
- Native RiftCLI Gates N0 and N1 are proven on-device. N1.6 Batch V2 is live-proven on Builder run #259 / source `eaa2a390438784be435929e49283f9e6281b8ed0`. N1.5 persistent push is installed/source-complete but still awaits the final external driver push-receipt proof. Current source adds passive RiftDebugHub metadata for CLI event creation, relay queueing/replay/socket lifecycle and Cloudflare ACK receipt; that instrumentation requires the next APK build/install before live use. After Builder run `35542173887` exposed a syntax-corrupted wiring validator, the source validator was repaired and the external Builder was hardened with independent source-gate syntax preflight, required N1.5/DebugHub npm-entrypoint checks and final signed-DEX N1.5 marker verification. The canonical CLI core remains C++ under `android/app/src/main/cpp/riftcli/`; `RiftCliHost.kt` remains a thin JNI transport host only.
- RiftCLI N2 is now frozen as the **federated Rift Memory Kernel roadmap** in `docs/systems/riftmemory/N2_FEDERATED_MEMORY_ROADMAP.md`. It is roadmap-only: no N2 runtime is claimed active. The design requires one canonical evidence/event/transaction/reconciliation authority with rebuildable specialist engines, SQLite as the initial reference `MemoryStore`, a benchmarked RiftStore replacement experiment, Observer/Validator reconciliation, integrity/poisoning/crash/rebuild gates, public/private benchmark suites, incremental hybrids and ablations. **N3 is hard-blocked until N2.12 promotion evidence passes the mandatory weakest-link categories and Android resource gates.**
- RiftCLI targets `arm64-v8a` as the primary ABI and `armeabi-v7a` as required 32-bit compatibility. Gradle declares both and pins the native CMake source snapshot.
- Gate N0 proved the minimal native bootstrap on-device. Gate N1 adds `rift-cli driver request`: while explicitly enabled, the C++ core may authorize one RiftOS shell action or one direct `rift_*` ToolHost action per request. Every authority-bearing request has a replay-protected request-id; authority work is job-based instead of blocking; jobs can be recovered/listed, polled or cancelled; direct model/API clients remain absent and driver continuation stays external and bounded. N1.5 makes persistent relay push the normal job-observation path with a bounded device-owned replay ring and WebSocket/SSE fan-out, while polling stays recovery/debug fallback. N1.6 adds `rift_cli_batch`: at most 16 fully prevalidated sequential steps under one global authority reservation with per-step events, bounded results, stop/continue policy, cancellation checks and `rift-cli-batch` provenance.
- The former Experimental RiftCLI Kotlin lifecycle/research/docs-parity/verification/swarm/IR/tokenizer implementation and its focused tests are retired. Shared Project Intelligence, Workspace Records, Diff/File Identity, Patch Manifest/Sessions, Git, MCP, Dev Lab and Local Agent remain independent reusable RiftOS authorities.
- The permanent reasoning dependency is external driver → MCP/RiftShell → RiftCLI → existing RiftOS authorities. RiftCLI does not call a model or inference API. When explicitly enabled, RiftCLI may authorize full RiftOS authority with exactly one outstanding authority job globally; Batch V2 owns that one slot for its full plan. Accepted request IDs are retained without eviction for the RiftOS process lifetime and replay-protected, duplicate transport retries cannot silently re-run an action, cancellation remains observable, and any multi-turn information loop is externally advanced by the driver and capped at 8 steps. Retired RiftShell `batch` and multi-op `rift_workspace_exec` remain disabled.
- `RiftSecretStore` is the Android Keystore-backed secret owner.
- Installed RiftBuild on source `1c1ae33b81cfe643eb804cac0841ced636e982e3` / Builder run 214 has device-proven Rift++ V0 ELF materialization, the fixed 1,440-byte binary Android manifest, project validation/planning and deterministic universal unsigned APK packaging under `D:/Builds`.
- Current unpushed source adds `RiftApkV2Signer`: one Android-Keystore RSA-2048 key, APK Signature Scheme v2 / RSA-PKCS1-SHA256 signing, and independent signature/certificate/content-digest verification with no raw process authority.
- Current unpushed source adds `RiftBuildInstaller`: PackageInstaller handoff restricted to `com.riftpp.nativeproof`, Android-managed unknown-source/user confirmation, persisted install status, exact NativeActivity launch request and protected first-launch proof recording. Builder compile + installed-device sign/verify/install/launch proof is still pending.
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

The active production language path is native shell → headless QuickJS → Rift++ Core/RiftVM assets.

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
