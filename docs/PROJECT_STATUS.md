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
- `RiftMcpRuntime` owns process-wide native shell, MCP host/server/relay, native Git and Vortex bridge; the retired Codynex LR0 singleton/bridge is gone.
- `RiftMcpRuntime` also owns one process-wide passive `RiftDebugHub` and one persistent `RiftMcpOperationJournal`; MCP Server and Tool Host publish correlated spans while journaled calls get restart-safe operation identity, no-replay state, and read-only reconciliation.
- C0.1 project separation: the `codynex-editor` and `riftpp-editor` shell commands and both OS-side Binder bridge clients are retired. Mirrored editor payloads remain pending the separate C0.2 removal gate; generic RiftShell and RAPP hosting remain OS-owned.
- `RiftNativeShell` is the live shell executor and has no renderer fallback.
- **External RiftBuild provider is promoted and the embedded fallback is purged.** The complete external path was device-proven through Compile → Preflight → Pack → Sign → Verify, Android install, editor launch, typing, clear, preview, and downstream editor native compile/preflight/pack/sign output. Provider semantics now live outside RiftOS.
- **RiftOS owns only the generic provider boundary.** `RiftLocalBuildCapability` exposes registered compiler execution and project confinement; `RiftJvmDexService` owns bounded D8 conversion; `RiftBuildManagedToolchains`, `RiftManagedJvmToolService`, and `RiftNativeBufferCompilerService` own reusable compiler execution primitives; `RiftBuildPlatformTools` exposes only surviving generic shell/RAPP/verify/install operations.
- **APK signing semantics are external.** `RiftRappCapabilityBroker` exposes permission-gated `signing.identity` metadata plus generic SHA256withRSA sign/verify while the private key remains in Android Keystore. `RiftApkV2Verifier` is verification-only and `RiftBuildInstaller` independently verifies/installs the finished APK through normal Android user confirmation.
- **Retired embedded build implementations stay absent.** `RiftBuildLocalExecutor.kt`, `RiftBuildKotlinCompiler.kt`, `RiftBuildNativeToolchain.kt`, `RiftBuildNativeApp.kt`, `RiftApkV2Signer.kt`, the Android-host clang ZIP/JNI payload, and their old shell commands/tests are no longer part of the live architecture. The boundary is frozen unless device proof demonstrates a missing generic reusable primitive.

- **Rift++ Android Native R1 load/entry, R2 window-callback ownership, and R3 first-frame rendering are installed-device proven.** Run 483 / source `cc990d77fa20c42c8c2a0b2218209f5798e81cfd` proved Bionic load, symbol resolution and exact Rift++ entry execution. Run 484 / source `7e6f83738d4d8b343cb6378edbb5c7d32a0cf6ef` proved Android-to-Rift++ `onNativeWindowCreated` ownership with two valid stage-6 callback packets carrying non-zero activity/window pointers. The first version-5 R3 attempt installed but crashed because the earlier 1,196-byte ELF SHA-256 `d825e50988a3265e05b8b8c56b46fa915a260ecb212488085f72f954f3f5c9e0` retained `0xA5` guard poison in mandatory zero-valued dynsym, SysV hash and `DT_NULL` fields. The corrected 260-body-record linker transport SHA-256 `18782a0cb8719b04fcac338667ca99f22e58d0e3c52b5a09d18ea4773c0173b6` explicitly zeros those fields. Build 488 / source `dbff24f773efdf62494e5a44027650d19c2c7e56` then exposed a separate host-containment `-96` rejection because generic source/output buffers still used one guarded page; current source moved them to multi-page `GuardedSpan` allocations while retaining guard pages and prefix-canary checks. Builder run 490 / source `18e1b5bfd6c91efee110d6152fbdafda8843e866` cleared that host gate, compiled the corrected source under frozen S3 to a 4,192-byte linker SHA-256 `98087752725238853d58d124930dcb25a0d96120899d5b2e07f69527137b9a8d`, and generated the promoted 1,196-byte R3 ELF SHA-256 `44f8b266aef910acaaedfe3a5f6cf7b9f1e029acd6e1c2e310410a22568be28e`. Numeric inspection verified all fourteen formerly poisoned fields were zero, `DT_NULL` was present, the three imports/relocations were exact, and RX/RW mappings remained separate with no W+E load. That ELF was packaged into version `0.5.0-riftpp-r3-frame`; signed APK SHA-256 `cbd5bb384f97aee456bef6fdae286c9c2c939266d74943405d4106fbddd1d6be` passed independent APK-v2 verification, Android session `1663217197` reported `INSTALL_SUCCEEDED` and `launch-proven`, and direct user screenshot evidence showed the full-screen orange frame. R3 is promoted. R4 first editor-surface rendering is now installed-device proven on Builder run 492 / source `2b94042e6fd229ee7aba4813ab99857542dee262`. The exact 65-body-record / 528-byte decoded Rift++ patcher (transport SHA-256 `09afaeda7cbf30281718ec6e354838e75be3d6228297f0b4b70f815253610706`, decoded SHA-256 `9cf4f6c7670d50f2b6caaeca2f24d20949811291fbe0dfdcbad3a104c78332af`) compiled under frozen S3 to a 1,072-byte patcher SHA-256 `82e6aa4e82426c7d1cc2392c3a52d5f46f5604873f2fd774a0ef6377b6d5d57d`, which patched only the exact promoted R3 ELF and produced a 1,196-byte R4 ELF SHA-256 `90dc170d574f9d3b0947cccb013b82f5a2bf0b3c3b65013e0fc5050f4da76f9a`. Exact byte comparison found 68 differing bytes confined to the renderer tail and two size fields; program headers, dynamic table, SysV hash and relocation regions remained byte-for-byte unchanged, while `.text` and exported symbol sizes grew 152→204 bytes. VersionCode 6 / `0.6.0-riftpp-r4-topbar` was packaged, APK-v2 signed and independently verified as SHA-256 `b11fc25040c64fbca054e050cffedffd1612d06935780c9a4b7da109592538c5`; Android session `418510229` reported `INSTALL_SUCCEEDED`, and direct user screenshot evidence showed the dark charcoal editor bar over the preserved orange frame. All host semantic-ownership flags remained false. R4 is promoted. R4.1 input-queue/ANR hardening is now interaction-stability proven on Builder run 493 / source `92ca3fe0f7bb4ebb948047c7cc6e8736a8411834`. Frozen S3 compiled the exact 628-body-record / 5,032-byte decoded patcher (transport SHA-256 `66c8949f80ac23b729bf92e5188e1f3b4e5058dce81cca94f979602e25e18551`, decoded SHA-256 `59a3ae7494697f83acf34d6310686b7e1b8d6ec54c8d25643f4bc917e58ee6a5`) to the exact 10,080-byte patcher SHA-256 `9d443ba3d9e188885d8b029678b2305e36651670280f34447407e92af859f914`, then transformed only the exact promoted 1,196-byte R4 base SHA-256 `90dc170d574f9d3b0947cccb013b82f5a2bf0b3c3b65013e0fc5050f4da76f9a` into the 1,880-byte R4.1 ELF SHA-256 `abf2b0789f72fbc885a5c73eeb10cf6199fb9c5d6b29e4f8024b50b3a9fec610`. ELF inspection confirmed separate RX/RW mappings with no W+E, valid null dynsym / SysV hash terminator / final `DT_NULL`, all nine expected GLOB_DAT imports, and the exact NativeActivity input-queue callbacks plus looper drain/pre-dispatch/finish path while preserving the proven R4 renderer. VersionCode 7 / `0.7.0-riftpp-r41-input` was APK-v2 signed and independently verified as SHA-256 `467ac3749f8ab844eda2e18f796641a52da16e6efe268901ad09a6b712eb9d93`; Android session `1067604178` reported `INSTALL_SUCCEEDED`, and direct user stress testing repeatedly exercised touch/back behavior—including rapid repeated Back presses—without reproducing the prior not-responding closure. `ApplicationExitInfo` access was explicitly unavailable because Android denied cross-package history without `android.permission.DUMP`, so the historical root cause is strongly supported by the successful fix but not formally proven from prior exit records. R4.1 is promoted. R5 fixed-glyph rendering is now installed-device proven on Builder run 494 / source `c808372725db39b7200b2cbac6a2930047e830e6`. Frozen S3 compiled the exact 275-body-record / 2,208-byte decoded Rift++ patcher (4,692-byte transport SHA-256 `ba8f4978c05c0421591fde9ec406cfff1c03373b67341c35838a32a06af29818`, decoded SHA-256 `3510e1dccfe1025f2cfbab7c9723b90b5cf8274c12d0bd6975928d8bd85c5f34`) to the exact 4,432-byte native patcher SHA-256 `159d089a562d2784047ed0de9e12146d694a72b0a16a81b3365787f73885b1e6`, then transformed only the exact promoted 1,880-byte R4.1 base SHA-256 `abf2b0789f72fbc885a5c73eeb10cf6199fb9c5d6b29e4f8024b50b3a9fec610` into the exact 2,368-byte R5 ELF SHA-256 `1702e86b8672697f1139eb105b6c69e9ce455222f90a31d77123bac860b7c2bc`. Exact inspection found only 12 changed bytes inside the inherited R4.1 image (RX file/memory sizes plus renderer call tail) and a 488-byte appended glyph routine; the full R4.1 input/lifecycle code, dynamic table, section headers, dynstr/dynsym/hash/relocations and existing imports remained byte-for-byte unchanged, with separate RX/RW mappings and no W+E load. VersionCode 8 / `0.8.0-riftpp-r5-glyph` was APK-v2 signed and independently verified as SHA-256 `411d644b207a53a666ec1a626696fd897f0cc873a4d11baf1a1a57678ca1d4b1`; Android session `2023843614` reported `INSTALL_SUCCEEDED`, and direct user screenshot evidence showed readable white `RIFT++` glyphs inside the dark editor bar above the preserved orange body. All host semantic-ownership flags remained false. R5 is promoted. R6 semantic input/focus is now installed-device proven on Builder run 495 / source `a83ed529971311289187b49a3c86d5516c910fff`. Frozen S3 compiled the exact 239-body-record / 1,920-byte decoded Rift++ source (4,080-byte transport SHA-256 `340d192199408411775baeb3be8a2d20b18c42bdfcb19253a2941da1ddd3f40f`, decoded SHA-256 `10ffd04fb115c6229c1114ccef4f1d71ab5b36a58f11a993159a0e9fe2c200b4`) to the exact 3,856-byte native patcher SHA-256 `d09e12c6ba9e462411fc2a326b58866336dc83404a5c729b8ac7ab4a77a59fe1`, then transformed only the exact promoted 2,368-byte R5 base SHA-256 `1702e86b8672697f1139eb105b6c69e9ce455222f90a31d77123bac860b7c2bc` into the exact 2,768-byte R6 ELF SHA-256 `32f7824d6dd4b2f31c4ec30d93cb46995c242fe62263bcf009eb384a7cd8f5e9`. Exact inspection confirmed RX grows only to `0xad0`, RW file size remains `0x224` while BSS grows to `0x22c`, no W+E mapping exists, dynamic/GOT/section/dynstr/dynsym/hash/relocation data remain byte-for-byte unchanged, and the entire promoted R5 glyph renderer remains exact. R6 adds no imports and only two BSS state words for current-window/focus, adds window-destroy cleanup, and preserves the original `AInputQueue_finishEvent` path. VersionCode 9 / `0.9.0-riftpp-r6-focus` was APK-v2 signed and independently verified as SHA-256 `4c293a6303bd2229d07f2639539f7229fa93db31806663227c1551ec1d1b1540`; Android session `822132264` reported `INSTALL_SUCCEEDED`, and direct user screenshot evidence showed the expected white underline appear beneath `RIFT++` after a tap, proving the input -> Rift++ state -> native redraw chain. All host semantic-ownership flags remained false. R6 is promoted. R7 mutable text-buffer/caret state is now installed-device proven on Builder run 496 / source `fb407a5e5827c72cbea27013a866f418e567e45b`. Frozen S3 compiled the exact 271-body-record / 2,176-byte decoded Rift++ source (4,624-byte transport SHA-256 `b4cd99191c66136d03a2232d941db327e733ebc24cb253ee85efa23dfbb108ac`, decoded SHA-256 `8b8f5df2cef4364ebfd0ea51450e89728a29f3b8a56a191dc24a7200fa23a6d7`) to the exact 4,368-byte native patcher SHA-256 `8a7ced9da44cfd1d37dff355f1e295bf0093077307a30d68b6e90419a6d11ae7`, then transformed only the exact promoted 2,768-byte R6 base SHA-256 `32f7824d6dd4b2f31c4ec30d93cb46995c242fe62263bcf009eb384a7cd8f5e9` into the exact 3,248-byte R7 ELF SHA-256 `c489adfd62155b3f916819726cde543eee91e54faefdd371c48c7413c5d6b49a`. Exact inspection confirmed RX grows only to `0xcb0`, RW file size remains `0x224` while BSS grows to `0x23c`, no W+E mapping exists, only nine inherited bytes change at expected size/hook fields, the entire R5 glyph engine remains exact, and dynamic/GOT/dynstr/dynsym/hash/relocation metadata remain byte-for-byte unchanged. R7 adds length, caret and an 8-byte mutable buffer above the proven R6 window/focus words; handled input appends actual bounded `EDIT` bytes, advances length/caret, rereads the stored bytes and renders them through the proven glyph path. VersionCode 10 / `0.10.0-riftpp-r7-buffer` was APK-v2 signed and independently verified as SHA-256 `0cca2e041ac3268ed2e5286c9d38393c23de86b4b257c806b854eafff78a7d54`; Android session `1132312570` reported `INSTALL_SUCCEEDED`, and direct user screenshot evidence showed visible stored `EDIT` plus caret beneath `RIFT++`. R7 is promoted. R8 native key semantics remains unpromoted after the first device attempt was rejected. Builder run 497 / source `1ef78d11788b981dd3476f6986d62cfa0ace7b91` generated the earlier 4,968-byte (`0x1368`) R8 ELF SHA-256 `02bee1074d7b023d7bcae8d8c000db7989a9243baec7ee941824c206db6208a7`; signed v11 APK SHA-256 `dff0ade39f2e6d4c81f67abcb6c3469fc644376953c17a23ad98af600f4678e4` installed successfully in session `2055875628` but closed immediately on launch. Numeric postmortem found the RX PT_LOAD had grown past virtual `0x1000` and overlapped the existing RW PT_LOAD beginning at `0x1000`, so that artifact is rejected. The draft activity-pointer write was also unreachable because it hooked inherited offset `0x288` after R6 already branches away at `0x280`. The repaired authoritative source has 1,040 body records / 8,320 decoded bytes, 17,680-byte transport SHA-256 `c5dc2e8959a9aa2e4b380e292db9c744be07e2b91a10d038acb25e9ea0de1ef4` and decoded SHA-256 `2617091d17d2425dac4dc47ae4d928792da79fda239ea75e30c8ad0a1ff31431`; frozen S3 is expected to compile it to 13,904 bytes and produce a 4,388-byte (`0xfdc`) ELF, leaving 36 bytes before the RW segment boundary. Rebuilt dynamic metadata is packed into reclaimed loader-only metadata space `0x368..0x683`, stale section headers are stripped, and the reached `0x280` tail now stores `ANativeActivity*`. The first repaired proof maps real key-down A-Z, SPACE and DEL into the proven R7 eight-byte buffer/caret path and requests the soft keyboard on motion; full IME `commitText` remains explicitly unproven. That historical R8 transaction path is retired from current source. Further Rift++ compiler/runtime work uses project-owned managed hot compiler payloads; no fixed S3 transaction route remains in RiftOS. Frozen S3/VM1 semantics remain unchanged.
- **RiftBrowser bounded editor bridge is source-complete and awaiting Builder/install proof.** The active HTTPS-page inspector now supports explicit `edit` plus Base64-safe `edit-b64` for non-sensitive text inputs, textareas and contenteditable editor surfaces, with a 256 KiB UTF-8 ceiling, reset support and password/secret/token/API-key/authorization guards. It still exposes no arbitrary JavaScript execution, form submission/deploy authority, cookies, storage, headers or control-value readback.
- `RiftToolSandbox` is hard-scoped to `filesDir/riftfs/workspace`.
- the current source model-visible MCP catalog is exactly 21 tools; `rift_debug` is passive/read-only and `rift_mcp_reconcile` is persistent/read-only.
- `RiftWorkspaceRecords` is native/shared; `RiftWorkspaceWatcher` is Activity-owned and is recreated with `MainActivity`.
- `RiftDiffEngineV2` provides bounded adaptive exact-LCS/patience multi-hunk text diffs to Workspace Records; it is evidence formatting only and does not approve patches.
- `RiftFileIdentityV2` adds exact SHA rename/copy content identity plus bounded non-exact rename/rewrite similarity evidence; it does not infer user intent or authorize mutations.
- `RiftPatchSessions` binds explicit MCP/Shell/Editor/Dev Lab/Git writer provenance to asynchronous Workspace Records observations; exact file claims are state-bound, directory claims are lower-confidence, and unknown writers remain `unattributed-local`. It is evidence-only and does not enforce acceptance.
- `RiftPatchManifestV1` deterministically binds operational base/result tree bytes, change-set/structural identity, retained provenance and record-chain state; private freezes are SHA-addressed and trusted-checkpoint fields are inert with no promotion API.
- `RiftSourceIntelligenceV2` is the shared lexical analyzer for normal Project Intelligence v2 indexing and candidate before/after semantic deltas. The internal candidate-impact path derives affected symbols/dependencies/dependents/references/tests/docs from Patch Manifest V1 and remains OBSERVE evidence only.
- **RiftCLI retirement is staged in source; Builder/device proof pending** (2026-10-07). Legacy JNI/native core, shell/ToolHost dispatch, driver jobs and CLI-owned batch lanes have been removed from live source. Historical N0/N1/N1.5/N1.6 proof below in `PATCH_HISTORY.md` is not current execution authority. Live AI workspace execution stays with bounded MCP and Local Agent; relay `cli.*` event names are retained as compatibility protocol keys until separately migrated.
- **Local Agent engineering batch expansion is source-implemented and awaiting Builder/install proof.** The same persistent 16-step `rift_local_agent_batch` now accepts bounded Code Mode engineering steps (`read`, `read_range`, `write`, guarded replace/patch operations, list/stat/hash, search/grep, symbols/references, project/snapshot, audit/scan, project export, workspace diff and passive debug) in addition to the existing device/UI operations. Engineering work is delegated through `RiftToolHost -> RiftToolSandbox`, preserving normal workspace confinement, provenance, atomic/guarded mutation and search/index bounds rather than creating a second filesystem authority. Read-only plans require read permission; any workspace/UI mutation requires read+write. Raw RiftShell batching remains disabled.
- `RiftSecretStore` is the Android Keystore-backed secret owner.
- The promoted external provider, not RiftOS, owns deterministic APK packing and APK-v2 signing semantics. The successful full-device proof is the promotion evidence; earlier embedded pack/sign proofs are historical only.
- `RiftApkV2Verifier` is the live keyless APK-v2 verification owner. It independently verifies the provider-produced signed APK before Android installation.
- `RiftBuildInstaller` is the live user-confirmed PackageInstaller/launch owner. Package identity is derived from the verified APK and bound to persisted install status; Android user confirmation remains mandatory and project-specific package allowlists do not live in the installer.
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

The older installed/reference `riftpp` language path is native shell → headless QuickJS → Rift++ Core/RiftVM assets and remains compatibility/reference only. Compiler-development authority is project-owned managed payloads executed through RiftBuild's hot compiler engines; `riftpp-host` is retired.

`.rxe` compile/inspect/run/exec behavior is implemented inside the bounded headless runtime. There is no live Kotlin `RiftRT` class.

Historical `src/riftrt.js` and its Worker/WASM/application-runtime architecture are retained source/reference unless a current native owner explicitly uses them.

## Installed-program state

The current source can discover and host packages already present in `C:/Programs/<id>/package.json`.

The current working tree contains the native generic `RiftBuildInstaller` path. It accepts only an APK that has passed RiftBuild v2 verification, derives and validates that artifact's package identity, binds installation callbacks and launch to the recorded package, and still requires Android's normal unknown-source trust/user confirmation. It does not replace the older general JavaScript app-package design (`src/riftapps.js`), which remains unpackaged.

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
