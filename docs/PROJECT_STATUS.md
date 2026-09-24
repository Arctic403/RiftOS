# RiftOS Project Status

## Verification status

**CURRENT ENGINE STATUS VERIFIED AGAINST SOURCE — 2026-09-23.**

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
- **RiftBrowser bounded editor bridge is source-complete and awaiting Builder/install proof.** The active HTTPS-page inspector now supports explicit `edit` plus Base64-safe `edit-b64` for non-sensitive text inputs, textareas and contenteditable editor surfaces, with a 256 KiB UTF-8 ceiling, reset support and password/secret/token/API-key/authorization guards. It still exposes no arbitrary JavaScript execution, form submission/deploy authority, cookies, storage, headers or control-value readback.
- `RiftToolSandbox` is hard-scoped to `filesDir/riftfs/workspace`.
- the current model-visible MCP catalog is exactly 19 tools; `rift_debug` is passive/read-only.
- `RiftWorkspaceRecords` is native/shared; `RiftWorkspaceWatcher` is Activity-owned and is recreated with `MainActivity`.
- `RiftDiffEngineV2` provides bounded adaptive exact-LCS/patience multi-hunk text diffs to Workspace Records; it is evidence formatting only and does not approve patches.
- `RiftFileIdentityV2` adds exact SHA rename/copy content identity plus bounded non-exact rename/rewrite similarity evidence; it does not infer user intent or authorize mutations.
- `RiftPatchSessions` binds explicit MCP/Shell/Editor/Dev Lab/Git writer provenance to asynchronous Workspace Records observations; exact file claims are state-bound, directory claims are lower-confidence, and unknown writers remain `unattributed-local`. It is evidence-only and does not enforce acceptance.
- `RiftPatchManifestV1` deterministically binds operational base/result tree bytes, change-set/structural identity, retained provenance and record-chain state; private freezes are SHA-addressed and trusted-checkpoint fields are inert with no promotion API.
- `RiftSourceIntelligenceV2` is the shared lexical analyzer for normal Project Intelligence v2 indexing and candidate before/after semantic deltas. The internal candidate-impact path derives affected symbols/dependencies/dependents/references/tests/docs from Patch Manifest V1 and remains OBSERVE evidence only.
- **RiftLLM RiftTrainData V2 is source-implemented as a production-data candidate and remains deliberately unqualified for production pretraining.** RiftOS now owns one frozen B2 runtime authority plus fixed-path V2 binary packing, immutable train/validation/challenge generations, global exact/group-safe dedup, bounded bottom-k near-dedup comparison, labeled threshold qualification and a real-writer/real-parser adversarial lab. Threshold/adversarial evidence is bound to current input/tokenizer/current installed APK SHA-256, deep generation publication is atomic+fsynced+revalidated, and `productionPretrainingEligible=false` remains hard until threshold/policy/device/parser gates are frozen. Permanent source regression: `scripts/test-riftllm-training-v2.mjs`; Builder/install/Hardware Target A qualification is still pending.
- Native RiftCLI Gates N0 and N1 are proven on-device. N1.6 Batch V2 is live-proven on Builder run #259 / source `eaa2a390438784be435929e49283f9e6281b8ed0`. **N1.5 persistent push is now live-proven on the installed Android build from source `0814fb8cf8ca31186d6e639fc7ad3b965d822897` (2026-09-21).** The live Cloudflare Durable Object reported the current N1.5 health surface (`deviceConnected`, `driverSockets`, `sseClients`, `cliSequence`). With an external Chrome SSE subscriber attached, a read-only RiftCLI `version` job advanced the relay sequence and DebugHub independently showed matching `event.created`, `cli.event.send`=`queued`, and `cli.ack`=`received` records for all lifecycle events; Chrome received the same sequences without polling. Replay was then proven by forcing `sseClients: 0`, creating a three-event job while disconnected, and reconnecting from the prior cursor; exactly the missed sequences replayed in order with no duplicate or older event. After Builder run `35542173887` exposed a syntax-corrupted wiring validator, the source validator was repaired and the external Builder was hardened with independent source-gate syntax preflight, required N1.5/DebugHub npm-entrypoint checks and final signed-DEX N1.5 marker verification. The canonical compiled RiftCLI trust kernel remains C++ under `android/app/src/main/cpp/riftcli/`; `RiftCliHost.kt` remains a thin JNI transport host, while replaceable intelligence belongs under `/workspace/.riftcli/` behind Local Agent.
- **RiftCLI N1.7 is partially installed-proven and remains the active stress gate.** Zero-poll steady state is live in the installed source `679dba5a78fc1bd8a66f846933dcb05c139285f1`: `driverObservationMode=persistent-push-steady-state`, `automaticPolling=false`, `pollFallbackOnly=true`. Live 2026-09-21 stress additionally proved eight SSE clients plus ninth-client rejection, stable session replacement, bounded backpressure, lease-based stale-client cleanup to zero without event pressure, and true Android force-stop/reopen recovery with exact nonzero resume cursor and zero replay. Remaining N1.7 work is the rest of the abuse matrix: cancellation between batch steps, global no-interleave behavior, concurrent-driver pressure, both failure policies, bounds and cleanup.
- **RiftCLI N1.7H Local Agent hosting boundary is source-implemented and is now the required architecture before any further CLI intelligence.** MCP/RiftShell ingress reaches the existing RiftOS Local Agent; the Local Agent hosts RiftCLI as an internal gated intelligence/tool layer; the compatibility `rift-cli` shell entry routes through `RiftOsLocalAgent` before the existing native CLI supervisor. The native process-local `CONFIRM-EXPERIMENTAL` gate remains the only enable switch, defaults OFF after every process start and is non-persistent. The N1.8.0 promotion condition is now satisfied; further CLI intelligence may resume only through explicit post-promotion implementation/proof and no lane is activated automatically.
- **RiftCLI N1.7L replaceable local intelligence boundary is architecture-locked and installed-proven.** The APK retains only the trust kernel/protocol and Local Agent authority supervisor. Future planner, memory content, skills, command policy, Observer interpretation, retry/procedure knowledge and debugger/history interpretation are loaded from `/workspace/.riftcli/` and executed in bounded headless QuickJS. Local package code has read-only RiftFS visibility and no direct shell, ToolHost, Git, Android, process or network authority; it can only return a bounded request envelope that the compiled supervisor validates. Builder run `35778126690` at source `8a4da45f7c9530ab14e08793736aec3f7cc3a8cf` stopped before Kotlin/NDK compilation because `validate-rift-wiring.mjs` still asserted the retired Patch 10.37 direct Local Agent route; Patch 10.40 updates that validator to enforce the new trust-kernel/local-package split instead. This loader boundary does not activate the blocked N2/N4 intelligence work and does not waive the N1.8.0 promotion gate.
- **N1.8.0 Repository Consistency Observer foundation is PROMOTED on installed source `9d196567e38e781d97a24bb2c808b47cbc2303eb`.** The strengthened Patch 10.42 Builder gate passed, then the exact installed build stabilized at `complete=true`, 1034 facts / 1088 edges / 260 repository files, graph SHA-256 `25869f703a8a4a7fe36b28ae6f9c3ab34daece15925c21b98166100eaab57d87`, verified cache, and `changed=false`. After a real Android force-stop/reopen, the **first** consistency read reproduced that exact SHA, graph ID, counts, completeness and cache state; RiftCLI also returned to `enabled=false`, proving the process-local non-persistent gate reset. Earlier installed torture evidence covers v5 cache integrity/recovery, long stable-key identity, >4 MiB snapshot fail-soft behavior, 4096/4097 symbol and dependency bounds, resolver bounds, mutation sensitivity, mutation-during-scan coherence, path/identity stress, eviction/rebuild, concurrency/reentrancy, exact/+1 semantic and repository byte bounds, and safe result omission. Patch 10.42 permanently locks cache write/post-write verification fail-soft control flow in the Builder chain. The N1.8.0 freeze is therefore cleared, but no Planner/Memory/Skills lane is activated automatically; N1.8.1 is now promoted on installed source `198a3f31e22a5d385378fee087aa5f115aed6d5a`; N1.8.2 is promoted on installed source `9cc74b25c94fd3e23e93f64d3d132e65e63fe3a6`; N1.8.3 is source-implemented/promotion-pending; N1.8.4-N1.8.7 remain pending, and full N1.8 promotion is still required before N2. Canonical spec: `docs/systems/workspace/REPOSITORY_CONSISTENCY_OBSERVER.md`.
- **N1.8.1 syntax/import integrity is PROMOTED on installed source `198a3f31e22a5d385378fee087aa5f115aed6d5a`.** The corrected analyzer v6 / PI cache v9 / `bounded-structural-v4-conservative` build restored N1.8.0 to `complete=true`, zero findings and stable post-restart graph SHA-256 `c7a4c3ad246d0e3fa8fee7e5b04a3370437550031d5a3dd17b53cb8a15b0ad7a` with `changed=false`. The full RiftOS integrity oracle is `complete=true`, `clean=true`, 261/261 files selected, 144 syntax-checked files, 0 invalid files, 769 dependencies, 56 local-resolved, 0 local-missing, 0 ambiguous-local, 713 external/unclassified, 0 findings and deterministic integrity SHA-256 `d73568a332d466d2c137a5899288b8fe06c5b4e8640463d859479c49c376b0ab`; the first post-force-stop read reproduced that exact hash from cache v9 with `cacheLoadStatus=loaded`, 261 reused and 0 bytes reanalyzed. A disposable torture repo proved clean nested JS templates/regex, fake dynamic-import text suppression, Kotlin interpolation, real JS/Kotlin local resolution, external Node/Android imports, malformed-syntax detection with `complete=true/clean=false`, relative-target rename/delete/move drift, recovery after importer updates, package-local missing and duplicate-symbol ambiguity, direct outgoing/reverse focused frontiers, and process-restart parity. A 241-target focused fan-out proved fail-closed frontier truncation: `frontierCount=241`, preview capped at 240, `frontierTruncated=true`, `clean=true`, `complete=false`, reason `integrity-frontier-output-bound`; the full oracle over the same 246-file fixture then returned `complete=true`, `clean=true`, 243 local-resolved and 0 findings. `incremental.filesRemoved` is defined as rows pruned during refresh; mutation APIs may report 0 after an eager invalidation has already removed the row before refresh. N1.8.2 is promoted on installed source `9cc74b25c94fd3e23e93f64d3d132e65e63fe3a6`; N1.8.3 is source-implemented/promotion-pending; N1.8.4-N1.8.7 remain pending.
- **N1.8.2 semantic dependency propagation is PROMOTED on installed source `9cc74b25c94fd3e23e93f64d3d132e65e63fe3a6`, Builder run `35929751856` / run number `317`.** A new read-only `project kind=propagation` lane reuses PI-v2 symbols/dependencies and the promoted local dependency resolver. Symbol identity is deterministic from `path|kind|name|ordinal` and deliberately independent of declaration line; a separate `signatureId` is bound to the normalized declaration signature. Reference discovery is now false-match hardened before resolution: `RiftSourceIntelligenceV2.referenceCodeMask()` masks supported-language comments/string-like regions, same-file symbol candidates take precedence over dependency-target candidates, and only candidate pools that can refer to the selected propagation seeds survive; unrelated same-name text increments `ignoredNameMatches` instead of consuming the reference budget. Unique seed-relevant pools resolve, multi-candidate pools remain ambiguous, callers are attributed to the smallest containing indexed symbol range, and type/interface relations cover `extends`, `implements`, Kotlin inheritance/interface lists and C++ inheritance with resolved/unresolved/ambiguous status. Reverse impact is a bounded BFS over dependency, resolved-reference and type-relation edges. Hard limits are 64 seeds, 8192 symbols, 1024 references, 512 callers, 512 type relations, 1024 closure paths, 4096 reverse edges, depth 16 and 240 preview rows. Results are evidence-only, expose explicit incomplete reasons, and bind exact evidence to deterministic `propagationSha256`. Permanent Builder regression: `scripts/test-rift-propagation-v1.mjs`. Installed promotion evidence now covers identity/signature/caller/type fixtures, multi-hop/cycle closure, false-reference stress, exact/+1 bounds, warm/restart determinism, and post-restart proof that N1.8.0/N1.8.1 stay green; N1.8.3 is the next active N1.8 phase.
- **N1.8.3 cross-boundary contracts is SOURCE-IMPLEMENTED and promotion-pending.** The new evidence-only `project kind=contracts` oracle lives in `RiftCrossBoundaryContractsV1.kt` and keeps `RiftToolSandbox` to a thin dispatch. It checks Android config/build mirrors, manifest-to-managed components, CMake/loadLibrary producers, Kotlin `external fun` / Java `native` declarations to JNI exports plus reverse export-to-managed pairing, MCP tool registry coverage, exact relay/event protocol mirrors, and the strict 45s < 60s < 65s < 70s < 75s timeout chain. Scans fail closed at 4096 files, 2 MiB/file, 64 MiB aggregate and 1024 findings, expose deterministic `contractsSha256`, and have zero mutation authority. Permanent regression: `scripts/test-rift-cross-boundary-contracts-v1.mjs`, wired into `npm check`. The first installed build (`29222aca534a0fdb9b711d3264676e5e4d1f60b9`, Builder run `35935936280` / run `318`) correctly exposed the lane but found three detector false positives: the oracle matched its own `System.loadLibrary('$library')` diagnostic and JNI ownership attached `nativeExecute` to nested `CommandResult`, which also made the real `RiftCliHost_nativeExecute` export look orphaned. Patch 10.56 fixes both by executable-code masking and lexical brace-depth owner attribution; a rebuild/install is required before clean/mutation/bound/restart torture continues. Patch 10.57 additionally caps returned findings/evidence previews at 240 rows while preserving full counts and `contractsSha256`, after the 1024-finding torture case exposed project-tool response overflow when every row was serialized. Installed run 321 then passed exact/+1 finding, per-file-size and file-count bounds plus the exact 64 MiB aggregate boundary. The 64 MiB +1 case correctly raised only `contracts-byte-bound` in `incompleteReasons` but exposed misleading secondary missing-member findings from the necessarily partial source set; Patch 10.58 now suppresses contract findings whenever source scanning is already incomplete and exposes `partialFindingsSuppressed=true`. Rebuild/install, +1 aggregate retest, warm/restart determinism and final N1.8.0-N1.8.2 continuity remain before promotion. N1.8.4-N1.8.7 remain pending.
- RiftCLI N2 is now frozen as the **federated Rift Memory Kernel roadmap** in `docs/systems/riftmemory/N2_FEDERATED_MEMORY_ROADMAP.md`. It is roadmap-only and blocked until N1.8 promotion: no N2 runtime is claimed active. The design requires one canonical evidence/event/transaction/reconciliation authority with rebuildable specialist engines, SQLite as the initial reference `MemoryStore`, a benchmarked RiftStore replacement experiment, Observer/Validator reconciliation, integrity/poisoning/crash/rebuild gates, public/private benchmark suites, incremental hybrids and ablations. **N3 is hard-blocked until N2.12 promotion evidence passes the mandatory weakest-link categories and Android resource gates.**
- RiftCLI targets `arm64-v8a` as the primary ABI and `armeabi-v7a` as required 32-bit compatibility. Gradle declares both and pins the native CMake source snapshot.
- Gate N0 proved the minimal native bootstrap on-device. Gate N1 adds `rift-cli driver request`: while explicitly enabled, the C++ core may authorize one RiftOS shell action or one direct `rift_*` ToolHost action per request. Every authority-bearing request has a replay-protected request-id; authority work is job-based instead of blocking; jobs can be recovered/listed, polled or cancelled; direct model/API clients remain absent and driver continuation stays external and bounded. N1.5 makes persistent relay push the normal job-observation path with a bounded device-owned replay ring and WebSocket/SSE fan-out, while polling stays recovery/debug fallback. N1.6 adds `rift_cli_batch`: at most 16 fully prevalidated sequential steps under one global authority reservation with per-step events, bounded results, stop/continue policy, cancellation checks and `rift-cli-batch` provenance.
- The former Experimental RiftCLI Kotlin lifecycle/research/docs-parity/verification/swarm/IR/tokenizer implementation and its focused tests are retired. Shared Project Intelligence, Workspace Records, Diff/File Identity, Patch Manifest/Sessions, Git, MCP, Dev Lab and Local Agent remain independent reusable RiftOS authorities.
- The permanent reasoning dependency is external reasoning source → MCP/RiftShell → RiftOS Local Agent → RiftCLI → existing bounded RiftOS authorities. RiftCLI is an internal Local Agent tool/intelligence layer and does not call a model or inference API. When explicitly enabled, RiftCLI may authorize full RiftOS authority with exactly one outstanding authority job globally; Batch V2 owns that one slot for its full plan. Accepted request IDs are retained without eviction for the RiftOS process lifetime and replay-protected, duplicate transport retries cannot silently re-run an action, cancellation remains observable, and the current compatibility continuation loop is advanced through the Local Agent host and capped at 8 steps. Retired RiftShell `batch` and multi-op `rift_workspace_exec` remain disabled.
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
