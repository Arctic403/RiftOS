# RiftOS Patch History

## 2026-10-10 — Gate 2-C Core bearer replay + controlled recovery gate (SOURCE ONLY, UNPUSHED)

On signed #689, actual device testing confirmed generic DEX execution, native denied/stale approval checks, Core SHA rejection, invalid manifest discovery rejection, missing-entrypoint active-pointer rollback and healthy reactivation of the known-good module. Two gaps remained: the trusted UI previously stopped a second stage click before testing Core's actual one-use bearer, and an entrypoint failure was not an interrupted-start journal recovery test. Wrong-SHA stages could also leave a new empty private module-ID directory, counting against the max-32 admission limit.

New source-only Gate 2-C patch:
- `RiftCoreModuleStore.kt`: track new module ID directories and committed-but-unrecorded DEX; on failure clean only newly created empty directories and newly committed orphan payloads; safely prune older **empty** module ID directories before quota admission, never dereference symlinks or remove contentful staged/current module data.
- `RiftNativeAdminApprovals.kt`: opt-in **Prove Core consumed stage ticket replay** button uses normal explicitly authorized module.stage and sends the same bearer, target, manifest and read-only DEX twice via existing trusted Binder IPC; first exact-stage receipt must succeed and second must be rejected **inside Core** with `Admin ticket absent or used`; never executes DEX.
- `RiftCoreModuleActivation.kt`: journal validated prior execution receipt with prior active revision, restore only a matching nonce-bound proof on rollback (fixes lost proof observed in failed-entry device test). Adds `proveInterruptedStartRecovery`: requires same already active verified module and proof, uses the **actual** stage/rollback journal and marker, invokes the actual `recover`, checks original active ID/revision/nonce and original proof returned. No Android process kill and no DEX load; actual cold-boot recovery **still unproven**.
- `RiftCoreAdminConsent.kt` and protected Core/Shell IPC: separate one-use signer/PID/foreground-bound operation `module.recovery.proof` for exact ID/digest with new response schema. Native Admin Approvals displays correct risk/one-use scope. `RiftCoreSystemCapabilities.kt` audits only this isolated operation; ordinary admin elevation, external runtime providers and the fixed ProbeV1 fallback remain unchanged.
- `scripts/validate-rift-wiring.mjs` adds source contracts for cleanup, direct Core replay, bounded independent recovery proof and receipt restoration. Source audit currently 15/15 focused checks and JS source parsing passed.

**Not yet Android/Kotlin compiled; not pushed or installed; no new live-device proof.** Manual signed Builder remains user-dispatched. Riftos-builder unaffected. Subsequent test plan is in `docs/GENERIC_MODULE_IMPORTER_DEVICE_TEST.md`.

## 2026-10-10 — Gate 2 source-documentation ownership preflight fix (UNPUSHED)

User's manual Builder run `38026894107` at RiftOS source `09f46329d8687bb9c39496c3bbca560a4b308c15` stopped in `npm run check:transport`: native wiring reported 99 reachable Kotlin classes and parsed 67 JS/MJS files, but `scripts/validate-rift-docs.mjs` rejected four unowned new generic module sources: `RiftCoreModuleActivation.kt`, `RiftCoreModuleManifest.kt`, `RiftCoreModuleStore.kt` and `RiftGenericModuleService.kt`. Kotlin/Gradle compilation was **not reached**. Fixed `docs/SOURCE_OWNERSHIP.md` with exactly one ledger entry for each new source, each assigned to Core/Shell, Android Host and Build Validation. Updated those three actual owning READMEs with module source/host responsibilities and accurate unproven gate status. Verification against the current RiftOS MCP Kotlin source listing found **99/99 Android Kotlin sources represented in the ownership ledger**, no missing ownership. Documentation validator itself and Riftos-builder remain unchanged. **No new build, push, or device test performed**; next Builder run remains user-triggered after explicit push permission.

## 2026-10-10 — Gate 2 Builder preflight repair (SOURCE ONLY; UNPUSHED)

User-provided logs `logs_103032417908.zip` from manual Builder source `e696852915b4ff9417b1ff126ce87d4e0a58cb26` show a **preflight failure before Android/Gradle compilation**: `Builder C1.4-B Kotlin nullable admin request parsing missing: require(operation.length <= 64 && target.length <= 128)`. Gate 2 had expanded the trusted Core admin target limit to 192, while unchanged Builder requires the established 128-character bound. Fixed RiftOS Core provider back to 128. Generic activation target `module://activate/<module-id>/<64-character manifest digest>` uses 18+id-length+1+64 bytes, so maximum ID length is now 45; aligned manifest, Core staging-store and Core approval-scope regex guards. Added source validation to enforce all four coupled constraints. **Builder repository not modified**; no rebuild or device tests run. Source-only; obtain explicit push permission before new user-owned manual build.

## 2026-10-10 — Gate 2 generic module stage + activation/recovery SOURCE checkpoint (UNPUSHED)

Following the user-proven signed #685 fixed ProbeV1 end-to-end DEX activation, built an app-identity-independent `riftos.module/1` manifest validator, SHA/content-addressed Core staging store, one-use `module.stage` authorization bound to canonical manifest digest and separate one-use `module.activate` authorization bound to exact module ID/revision. Core stores an atomic active/previous pointer and durable startup marker; a single nonexported `:riftModuleHost` Android process (same APK UID) verifies immutable bytes/entrypoint, loads the module via DexClassLoader and writes an activation-nonce/PID proof after module start returns. On failure/interrupted Core boot it rolls back previous activation pointer, not Core/Shell or RAPPs. **One active module slot** in v1; multiple IDs/revisions can be staged, not simultaneous long-lived processes. No provider registration, unrestricted software install, system elevation, app-specific entrypoint or new trusted compiler in Core. Original fixed ProbeV1 remains unchanged until new path is device-proven.

Changed Core/Shell admin approval bridge, native generic manifest discovery/consent UI, Core status moduleHost receipt, AndroidManifest service declaration and Gradle exact Kotlin source snapshot allowlist (99/99 source files verified). Independent `workspace/RiftModuleBuilder` RAPP adds a separate `com.riftos.genericproof.AlternateProof` entrypoint and a managed Kotlin/D8 build button emitting `/D:/Builds/Modules/example.alternate-proof/module.dex` + D8-SHA-bound `module.json`. RAPP build-protocol simulation 10/10; Core source-contract markers 14/14; security scan had only pre-existing filename heuristic. Source-only checks **are not Android/Kotlin compilation or device proof**. Documentation: `docs/GENERIC_MODULE_IMPORTER_GATE2.md` and `docs/GENERIC_MODULE_IMPORTER_DEVICE_TEST.md`.

**NOT PUSHED, NOT COMPILED, NO NEW DEVICE TEST.** User retains sole authority for RiftGit push approval, signed Builder run, installed RAPP packaging and all device interactions. Riftos-builder repo unchanged.

## 2026-10-10 — Gate 1 PROVEN on signed #685; Gate 2-A generic module staging SOURCE ONLY

User manual device test of the fixed ProbeV1 RiftFS importer succeeded end-to-end: external Module Builder RAPP had compiled a Kotlin DEX (2,563,188 bytes; D8 reported SHA-256 `2d2373dfd13f0f7b9ab15a4146cfef8760a3c7d5aa0f6fa858b7d7feb461877d`), which native Admin Approvals selected from `/D:/Builds/Modules/probe-v1.dex`. Core refused staging without approval, accepted a user-approved one-use stage and reported matching hash prefix, then accepted distinct SHA-bound activation and launched nonexported `:riftBootstrapProbe` process. User-supplied `core status` showed external PID 30999, Core PID 28315, Shell PID 28288, `probeActivationPresent=true`, `probeProofPresent=true`, 3 installed RAPPs, 0 runtime providers, 0 system grants and no pending rollback/registry journals. Denied stage reported `No stage approval ticket`; waiting over a minute after approval produced Core `Admin ticket absent or used` (rejects stale bearer, exact expiry vs reuse unspecified). **Gate1 positive path proven; failure/recovery and malformed input cases remain future regression.**

Started separate **Gate 2-A GENERIC stage-only SOURCE checkpoint**, backed up clean commit `0f9ae746a331`. New `RiftCoreModuleManifest.kt` and `RiftCoreModuleStore.kt` parse `riftos.module/1`, verify metadata/digest and import immutable content-addressed DEX to Core private storage. Trusted native selector reads manifest+payload pairs in `D:/Builds/Modules/<id>/`, forwards bounded manifest and a read-only FD through protected Binder only after a fresh user-approved `module.stage` target bound to the canonical manifest digest. Core independently validates content SHA and manifest identity; no generic activation, runtime provider registration, app-specific entrypoint, unrestricted execution, RAPP admin grant or service launch is enabled. Fixed ProbeV1 proof importer remains untouched. See `docs/GENERIC_MODULE_IMPORTER_GATE2.md`.

**Gate2-A is UNBUILT, UNPUSHED and NOT DEVICE-PROVEN.** User owns any eventual Builder run and all device testing. Generalized activation and recovery still need separate implementation and user proof before old ProbeV1-specific production code is considered for retirement.

## 2026-10-09 — Trusted native RiftFS ProbeV1 selector (SOURCE ONLY; DEVICE TEST USER-OWNED)

Live RiftOS #684 successfully ran independent `rift-module-builder` RAPP `build.local` Kotlin → D8 and exported `/D:/Builds/Modules/probe-v1.dex` (2,563,188 bytes, D8 reported SHA-256 `2d2373dfd13f0f7b9ab15a4146cfef8760a3c7d5aa0f6fa858b7d7feb461877d`). The Android file-picker detour was redundant; user authorized direct in-RiftOS importing, but **manual device tests must be performed by user**.

Minimal fix: `RiftNativeAdminApprovals` now offers `Select ProbeV1 DEX from RiftOS Files`, presenting only bounded regular `.dex` files in the fixed `/D:/Builds/Modules` RiftFS folder. Canonical path and every directory component checked against symlinks; file size 1..32 MiB and filename allowlist checked both at selection and right before opening a read-only `ParcelFileDescriptor`. The trusted Shell passes *only the descriptor* over the already-signer/PID/foreground-gated Core IPC. Existing separate Core 45-second one-use scope for stage, separate SHA-256 activation approval, immutable SHA-addressed Core storage, no provider registration, embedded Core/Shell fallbacks and Android document picker all remain unchanged. No new Core admin endpoint, RAPP elevated capability, compiler or APK packaging route. Added source wiring guards. **New source unbuilt/unpushed, no device proof claimed.**

User's manual regression gates: new UI select and stage, activation SHA/process proof; denial/expiry/replay; path/symlink/out-of-folder rejection, missing/oversized/malformed DEX; Android picker fallback; existing two RAPPs/Core/Shell survive. See handoff for steps.

## 2026-10-10 — External ProbeV1 DEX workflow setup-only failure repaired (SOURCE ONLY)

The user-supplied `logs_103013000955.zip` contains only `external-probe` job steps. It failed at `Set up Android tools` from `android-actions/setup-android@v3` because `sdkmanager` could not find the obsolete `tools` package (exit 1). Checkout and Java 17 setup passed; `javac`, D8 compile and artifact upload were **not reached**. Replaced that Android setup action with a guarded use of the GitHub Ubuntu runner's existing SDK and conditional install of just API 35 + Build Tools/D8 35.0.0 when absent. Standalone DEX script, user-manual signed RiftOS Builder, and installed RiftOS APK untouched. **Rerun required; no DEX/device proof yet.**

## 2026-10-09 — Native Core-authorized Bootstrap Probe importer SOURCE checkpoint

After signed #682 confirmed live and two retained RAPPs present, added Android document picker and two explicit native one-use approvals for **fixed ProbeV1 only**, using existing installed APK signer + exact Shell Binder PID/foreground lease/45s and Core audit. Shell forwards only user-picked SAF FD through existing protected ContentProvider; Core immutable-stages under SHA-256, separately approves exact hash activation and starts the nonexported isolated probe Service. Failed service launch attempts previous-revision rollback; Core/Shell and C2-A proof are not replaced. `RiftCoreSystemCapabilities` accepts probe-only audit names without enabling `software.install` or `runtime.register` generic effects. Added read-only bounded probe proof process receipt. Standalone javac+D8 script and separate manual workflow produce external DEX, never part of RiftOS APK. Synchronized Builder preflight/selftest and source validator. **Source only: no Kotlin compilation, APK build, external DEX compilation/run or physical device proof performed for this new gate. User manually builds APK and separately triggers external DEX artifact job.**

## 2026-10-09 — Bootstrap ComponentStore proof-only staged revisions (SOURCE, NOT DEVICE)

After user-manual signed Builder #680 (`494be44e6348`) booted, user screenshot and Core audit recorded **C2-A positive physical PASS**: one-use scoped `runtime.register` requested→approved→consumed→rolled-back, empty registry created/removed, no journal/registered provider/grant. Negative deny/replay/expiry tests and C2-B2 real third-party enrollment remain separate.

New `RiftBootstrapComponentStore` stages bounded DEX into read-only content-addressed revision filenames, maintains Android AtomicFile `probe.json`/previous fallback activation pointers and interrupted-startup rollback. `RiftBootstrapHost` now allows separate DEX only in the non-exported `:riftBootstrapProbe` Android process, never in Core or Shell; external Core/Shell are specifically disabled. Added parent-classloader collision rejection, exact Gradle source and ownership, source-validator/Builder guards, passive probe status and this documentation. **No native user import/consent, actual loaded external probe, complete Node/Gradle compilation, build, device test or push** yet. Pre-edit clean #680 source archived to `workspace/backups/riftos-494be44-pre-component-store-20261009.zip`. No RAPP installation/privilege changes.

## 2026-10-09 — Bootstrap Host compatibility entrypoint and optional DEX selector (SOURCE-ONLY)

User authorized the direction toward a tiny Android bootstrap with RiftOS Core/Shell as separately updatable components. Before editing, archived the clean RiftOS source `3c8a7bd5f6f5` to `workspace/backups/riftos-pre-bootstrap-host-20261009.zip` (SHA-256 `5ee3cbda40da45097a262c4338a0c7dbe1d328b0fe6f0bb9bdba9ca73af768aa`). `RiftCoreApplication` now delegates actual default-process Core startup and remote Shell WebView setup to `RiftBootstrapHost` while the embedded fallback preserves existing C1.4 rollback/journal recovery, Core runtime, Shell recovery and MCP relay sequencing. Added an optional hash-verified immutable app-private DEX `RiftBootstrapEntry` selector with synced interrupted-boot fallback, plus passive Core status introspection. This is **process-start-only**, not hot-swapping and not an externalized Core/Shell.

Synchronized Gradle exact Kotlin source declarations, RiftOS source-validator ownership checks, source-owner ledger, Android host documentation, and Builder preflight for startup-owner changes. The current C2-A positive device proof remains pending and C2-B2 remains on hold. No external DEX, APK build, signed install, live device test or RAPP migration occurred. See `docs/systems/android-host/BOOTSTRAP_HOST_MIGRATION.md` for required completion gates. This is not a claim that full bootstrap migration is finished.

## 2026-10-09 — C2-A signed #678 Android hardlink EACCES root cause and guarded O_EXCL candidate fix

User screenshot showed `Core C2-A registry transaction FAILED at atomic-create-only-publish (ErrnoException, errno=13)`. MCP on installed source `272f39b2ec11`, run 38007385307/#678 confirmed failure in Core audit, zero registry/scratch/recovery journal afterward, zero admin grants, Core+Shell alive, retained `riftbuild-hosted` and `riftpp-compiler-lab`. The extra Android `Os.link` fallback proved equally denied; no more blind hardlink retries. Replaced ONLY disposable C2-A EMPTY test publication with kernel non-overwriting exclusive `Os.open(O_CREAT|O_EXCL|O_NOFOLLOW,0600)` and immediate write/fsync/exact signer-stamped verification/rollback. This method is NOT pathname-atomic during write; synchronized Core runtime provider reads and pending-journal fail-close prevent partial interpretation. Journal recovery requires verified scratch before accepting a partial target and only deletes own expected-prefix bytes; unknown files remain untouched. All privileged ticket/signer/role restrictions remain unchanged. Source validation + Builder contracts updated. **Kotlin compiled APK and positive device proof pending**, C2-B2 on hold.



## 2026-10-09 — Manual Builder 38006658730 preflight regression fixed; C2-A signed retest still pending

User-manual Builder run **38006658730**, using RiftOS source `b87820dba3e7`, reached `Build RiftOS APK` after Builder selftests, Java/Node/Android SDK/Gradle setup and alpha-signing restore. The build then **stopped before source check/Kotlin compilation** in Builder's C2-A preflight: `native Core registry consent/rollback UI missing: Core C2-A registry transaction FAILED at $stage ($kind)`. This was a **false-positive exact-string contract**: the native UI intentionally formats a safe optional Android errno using `$type` rather than the former `$kind`. Its actual failure warning, no-success wording and one-use security behavior remain present.

Corrected the RiftOS wiring validator and Builder preflight to check stable user-visible failure wording, numeric errno lookup and explicit no-success warning independently, instead of pinning a Kotlin local variable name. Builder selftest now protects these markers. Verified **7/7 focused source/contract checks** against the live source and both project audits passed (RiftOS has a pre-existing filename heuristic). No Core runtime implementation, APK signing pipeline or Android permissions were changed by this correction. This checkpoint remains SOURCE-only; **new Kotlin/Gradle compilation and signed C2-A positive/negative device proof are pending USER's manual rerun**. C2-B2 remains on hold.


## 2026-10-09 — C1.4-C2-A signed #676 transaction blocker diagnostic + same-security Android hardlink retry

After user-manual signed APK #676 on source `30bebf009e88`, the real `:riftShell` and Core were alive, C2-B1 empty read-only discovery succeeded, two intentionally uninstalled RAPPs were absent as expected, but C2-A consumed its one-use `runtime.register` ticket twice and recorded `failed/registry-proof-rejected`. C2-A target and journal were absent, but Core Binder hid the true failure as `Core IPC response JSON missing`.

New source patch preserves signer-stamped EMPTY registry, exact operation+target, installed signer PID/45-second native approval, persistent-before-write journal, no overwrite and exact-byte crash recovery. Publication first attempts non-replacing Java NIO `Files.createLink` and may retry Android's **equally non-replacing** kernel `Os.link` only when Java API unavailable or `FileSystemException` arises. The original exception stage is tracked through mandatory `finally` cleanup; Core status exposes sanitized stage, Java exception class and numeric errno with conservatively unknown paths marked PRESENT. Only a verified clean failure (no target/scratch/journal) returns `riftos.core.admin-registry-proof/1` `transactionCommitted:false` diagnostic reply; native Admin Approvals explicitly reports failure without treating the ticket as reusable. Dirty/unsafe failed rollback still throws. RiftOS and Builder source contracts updated. **Source-only patch, no Gradle/Kotlin/device test; actual Android error root not known until signed physical retry.** Hold C2-B2 per user until C2-A signed-positive proof.



## 2026-10-09 — Major-milestone Android device testing policy and C1.4-C2-B1 source checkpoint

User-approved test cadence now requires source/security/regression/ownership checks for each small patch but reserves manual signed physical-device acceptance for complete C1.4-C2, C1.4-C3 and C1.5 milestones, with earlier Android testing when high-risk Binder/process/package/rollback interfaces require proof. Only user dispatches Builder. Current C1.4-C1 is fully proven on signed #669; C2-A empty registry transaction is source-only and was blocked in last manual source-check by an ownership map omission fixed in `ff84000f`. This change introduces read-only external runtime service candidate discovery in Core (Android PackageManager id/kind/service/export/signer checks), authenticated Shell `discover-providers` action and dedicated `riftos.core.runtime-candidates/1` reply, native Admin Approvals inspection button, RiftOS+Builder source contracts and signed schema verifier. **No runtime registry writer/admission or new provider activation was introduced in C2-B1.** Validation at this checkpoint: 20/20 focused static textual interface/negative-effect checks and local RiftOS/Builder project audits; no npm full suite, Kotlin/Gradle build, signed APK, device proof or actual provider service bind. C2-B2 remains next, then combined C2 major acceptance.

## 2026-10-09 — C1.4 Core consent schema validator fix after manual Builder 38002644671

The user-manual retry of RiftOS `db83724fa1ab` successfully completed Android SDK, Gradle setup and alpha-development signing preparation, then failed `npm check` at `scripts/validate-rift-wiring.mjs` before Kotlin compilation. Failure: `C1.4-C1 Shell must validate rollback schema only for execute-rollback-proof and retain B consent schema otherwise`. The actual production Shell client correctly contained separate rollback, registry-proof and newly inserted read-only `discover-providers` response branches; the validator's monolithic regex incorrectly assumed registry proof was immediately followed by the fallback consent branch. Replaced only that validator regex with independent exact action-to-response-schema checks for rollback, registry proof and discovery, plus preserved default-consent fallback. Focused evaluation against actual client passed, rejected three altered schema mappings, and accepted an additional unrelated branch. No Core/Shell Kotlin runtime behavior, Builder pipeline or signing configuration changed; **no complete npm suite/Gradle compile/new signed APK or device test** on the repaired source. User-approved policy remains physical testing at major C2/C3/C1.5 milestones unless Android-risk exception.

## Historical provenance — not current execution authority

**RiftCLI retired permanently during the October 7, 2026 cleanup.** Earlier RiftCLI JNI/C++, jobs, Batch V2, native build, N0/N1/N1.5/N1.6 gates and tests described below are historical evidence only. They are not installed/source requirements or valid instructions. Current authority belongs to the live source, bounded MCP/Local Agent, generic host boundaries, and the external RiftBuild Hosted provider. Relay `cli.*` event/ack/replay keys remain intentionally compatible wire protocol names, not the CLI runtime.

**HISTORICAL SOURCE LOG — earlier entries describe past states, not current source.**

This file records source-first implementation patches. It is not current architecture authority: source code, Gradle packaging, manifest state, focused tests, direct audits, and `docs/systems/riftbuild/README.md` outrank this history. Older entries intentionally preserve retired RiftBuild compiler/toolchain/prepared-app/pack/sign experiments as provenance; those names and commands must not be treated as live implementation guidance. Each entry describes what changed, where, why, validation performed, limits/risks and rollback scope.

## Patch 10.68 — RiftBuild Hosted source split

The full external Rift++ editor chain passed on-device: Hosted Compile produced a fresh run-specific prepared tree, Hosted Preflight/Pack/Sign/Verify passed, Android PackageInstaller installed the signed editor APK, and the newly installed editor's background Binder workshop passed `compile`, `preflight`, and `build-debug` with a signed-verified debug APK.

With that promotion gate satisfied, the device-proven `riftbuild-hosted` provider source moved out of the RiftOS repository into the sibling `RiftBuild-Hosted` workspace. The copied provider was hash-verified before removal, and its QuickJS smoke passed from the new external location. Provider-specific manifest/runtime contract assertions moved with it into `contract.mjs`.

RiftOS no longer contains or validates an embedded `apps/riftbuild-hosted` provider. RiftOS tests and Riftos-builder now validate only the reusable platform boundary: generic RAPP hosting, bounded filesystem effects, bounded `build.local` compiler/DEX execution, generic signing identity, and installation/platform infrastructure. Historical provider mentions in patch history remain historical only.

This split does not yet remove the old embedded compatibility compiler/package/sign commands. Those are now explicit purge debt for the next cleanup commit so repository ownership changes and legacy semantic removal remain separately reversible.

## Patch 10.67 — Hosted Compile + fresh prepared-tree ownership

This migration stage moves compile orchestration and prepared-tree production into `riftbuild-hosted` instead of asking the RAPP to invoke the old `riftbuild kotlin-compile` command or consume `build/riftbuild/prepared`. The generic RAPP boundary now implements permissioned `build.local` operations for managed-toolchain status, registered compiler execution, and JVM class-to-DEX conversion. There is no shell escape and the provider still cannot call embedded pack/sign/install implementation classes. Managed compiler sources/outputs remain project-confined, requested classpaths are restricted to the selected project or RiftOS managed-toolchain root, and `build.local` alone receives a bounded 180-second watchdog because real on-device compilation exceeds the ordinary 60-second capability window.

The Hosted provider now exposes an explicit **Compile** action before Preflight. It reads project-owned `rift-hosted.json` plus the selected compile manifest, runs the registered project compiler through `build.local`, converts fresh classes to indexed DEX, and materializes a unique `build/riftbuild/hosted-*-prepared` tree via generic `fs.read`/`fs.write` effects. Project-owned bootstrap inputs are SHA-256 pinned while copied; project assets are traversed recursively under the same bounded filesystem contract. Preflight refuses to run without a successful Hosted Compile, and source-contract tests forbid both the historical `build/riftbuild/prepared` dependency and any `kotlin-compile` shell fallback.

The first external proof target remains ARM32 because the current editor preparation recipe has a proven `armeabi-v7a` bridge input only. ARM64/universal are not promoted by this patch. Embedded compiler/preparation/package/signing semantics remain present until the complete Hosted **Compile → Preflight → Pack → Sign → Verify → Install/device-behavior** sequence is Builder-green and device-proven. Only then may obsolete implementations, tests, gates and docs be purged and the generic boundary frozen.

## Patch 10.66 — explicit hosted project commit / focused-input stability

Live hosted-builder device testing showed that project-path typing still behaved like an active build-target mutation and that delayed async frame responses could rewrite the focused Android `EditText`, causing the keyboard/caret to fight the user during the first keystrokes. The provider now keeps `projectDraft` separate from the confirmed `project`; text events update only the draft, and a new `SET PROJECT` action is the sole promotion point that invalidates stale preflight/pack/sign/verify state. The active project is shown separately in the frame, and existing persisted state migrates in-place by initializing a missing draft from the confirmed project.

The generic `RiftRappHost.kt` flow renderer now refuses to call `setText(canonical)` on an actively focused text input. Serialized provider events still persist draft state, but delayed frame responses cannot overwrite text underneath the soft keyboard. Once focus leaves, canonical state may synchronize normally. Hosted smoke now proves typing alone does not change the active project, SET PROJECT promotes it, and Preflight uses only the confirmed path. Source-contract tests guard both the focused-input rule and explicit project confirmation.

## Patch 10.65 — generic FLOW_COLUMN overflow boundary

Live `riftbuild-hosted` device testing exposed a generic RAPP layout boundary: a `FLOW_COLUMN` whose content exceeded its desktop window could measure a `TEXT_INPUT` down to zero height while later controls/log rows remained present. `RiftRappHost.kt` now scroll-contains every generic `FLOW_COLUMN` frame with a fill-viewport `ScrollView`; the inner vertical layout and all existing generic event/state semantics remain unchanged. This is a reusable host boundary for all RAPPs, not a RiftBuild-specific renderer case.

The hosted RiftBuild provider also no longer appends `Project changed` on every path keystroke. Project edits still invalidate stale preflight/pack/sign/verify state immediately, but typing no longer grows the frame structure/log on every character. `test-riftbuild-native.mjs` now guards both the generic scroll boundary and the absence of per-keystroke project log spam. Device re-proof requires editing a long project path without input collapse, then hosted Preflight -> Pack -> Sign -> Verify against the current Rift++ editor hot-path prepared tree.

## Patch 10.64 — generic RAPP runtime completion / stable Rift++ app boundary

This patch completes the RiftOS-native RAPP boundary as a generic platform contract rather than adding editor-specific host cases. The architectural goal is that future Rift++ editor/application evolution changes the runtime/package, not RiftOS, unless a genuinely new platform capability must be implemented.

`RiftAppAbi.kt` keeps `riftos-app-abi/1` as the language-neutral host boundary and now carries the complete current event surface, including pointer, key, text-input, display-resize, lifecycle and generic `HOST_EFFECT_RESULT` delivery. Events carry target id, four integer arguments, bounded UTF-8 text and an opaque bounded byte payload. Generic nodes remain `ROOT`, `SURFACE`, `TEXT`, `TEXT_INPUT`, `ACTION`, and `IMAGE`. Runtime payloads now carry optional manifest-declared capability names, and runtime responses may request at most one ordered generic host effect; effects are chained through result events rather than hard-coding operations such as Open/Save into the host.

Two older Rift++ adapters remain compatibility lanes: `riftpp-rpa2-v1` for RPE2/RUI2 and `riftpp-rws2-rui3-v1` for the pointer/stateful WS15 RPE3/RWS2/RUI3 lane already used by live RAPP proofs. New Rift++ runtime work targets `riftpp-generic-v1` in `RiftRappRiftppGenericAdapter.kt`. Its RPE4 envelope carries the ABI event losslessly (kind, target id, args, text, bytes and program state); its RWS4 response carries next program state, a RUI3 frame and an optional generic host-effect request. The host does not parse Rift++ application semantics.

`RiftRappHost.kt` now serializes each app's event/effect chain with a bounded per-session queue, updates opaque next-state through `decodeOutput`, and resolves generic effects before allowing later input to overtake the transaction. `RiftRappAbsoluteView.kt` is upgraded from pointer-only painting to a generic absolute ViewGroup that still paints root/surface/text/image nodes while hosting live text-input and action child controls; it emits text, key, pointer and resize events only when the selected adapter advertises support. Flow-column text inputs/actions receive the same support gating so existing RPA2 packages remain compatible. MainActivity forwards resume/pause only through the generic RAPP lifecycle surface.

`RiftRappManager.kt` now preserves optional `permissions` declarations through project -> package -> install -> launch, rejects unknown capabilities, and validates adapter/presentation identity at packaging/install time. `RiftRappCapabilityBroker.kt` uses the same persisted permission-grant store and capability names already used by other RiftOS apps. Current implemented operations are confined text filesystem read/list/write, clipboard read/write, text sharing and window-title updates. Network/build capability names are reserved in the stable broker vocabulary but remain unsupported until a real platform implementation is intentionally added. Filesystem policy mirrors the existing browser-app confinement: app-owned installed files are read-only; writes remain inside approved D: user/project/AppData roots.

Focused `test-riftbuild-native.mjs`, Gradle's exact Kotlin source snapshot, subsystem docs, component index and SOURCE_OWNERSHIP are updated to include the generic adapter and capability broker. The historical RAPP/WS15 runtime remains untouched so already-installed apps retain their existing protocol.

Lifecycle is **GENERIC RAPP SOURCE STAGED / BUILDER-KOTLIN GATE PENDING / DEVICE RPE4-RWS4 PROOF PENDING**. Promotion requires source validation, Android Kotlin compilation/package verification, one RiftOS update install, then a live `riftpp-generic-v1` RAPP proof covering real text input, independent multi-document state, resize/lifecycle delivery and at least one generic capability result.

## Patch 10.63 — Rift++ Android R8 native key-semantics lane

R8 forks only from the exact preserved 3,248-byte R7 mutable-buffer proof ELF SHA-256 `c489adfd62155b3f916819726cde543eee91e54faefdd371c48c7413c5d6b49a`.

The first R8 device attempt is retained as rejected failure evidence. Builder run 497 / source `1ef78d11788b981dd3476f6986d62cfa0ace7b91` compiled the earlier 7,120-byte decoded source to a 14,256-byte patcher SHA-256 `48812dfbb751a262b7efefa1477f4a1d12f13d6bb7b3a8bb89e1975cb9d63d11` and emitted a 4,968-byte (`0x1368`) ELF SHA-256 `02bee1074d7b023d7bcae8d8c000db7989a9243baec7ee941824c206db6208a7`. VersionCode 11 / `0.11.0-riftpp-r8-keysemantics`, signed APK SHA-256 `dff0ade39f2e6d4c81f67abcb6c3469fc644376953c17a23ad98af600f4678e4`, installed successfully in Android session `2055875628` but closed immediately on launch. Numeric ELF postmortem found the first RX PT_LOAD crossed the existing RW PT_LOAD base at virtual `0x1000`; that overlapping load layout is rejected. The draft activity-pointer write at inherited offset `0x288` was also unreachable because R6 already branches away at `0x280`.

The repaired authoritative source is `standalone/android-native-r1/elf32-r8-keysemantics-patcher.arm32.r3.hex`: 1,040 body records / 8,320 decoded bytes, 17,680-byte fixed-record transport SHA-256 `c5dc2e8959a9aa2e4b380e292db9c744be07e2b91a10d038acb25e9ea0de1ef4`, decoded-source SHA-256 `2617091d17d2425dac4dc47ae4d928792da79fda239ea75e30c8ad0a1ff31431`. Frozen S3 is expected to compile it to a 16,656-byte native patcher and produce a 4,388-byte (`0xfdc`) R8 ELF, leaving 36 bytes before the RW segment at `0x1000`.

The repair leaves the proven R7 window/focus/length/caret/buffer addresses unchanged. It still adds exactly four libandroid imports — `AInputEvent_getType`, `AKeyEvent_getAction`, `AKeyEvent_getKeyCode`, and `ANativeActivity_showSoftInput` — with GLOB_DAT slots at `0x123c..0x1248`, activity state at `0x124c`, and RW memsz `0x250`. Rebuilt dynstr/dynsym/SysV-hash/REL data are packed into reclaimed pre-code loader metadata space `0x368..0x683`; stale section headers are stripped. The actually reached `0x280` callback-install tail now records the activity pointer.

The repaired first proof intentionally narrows key decoding to real key-down A-Z, SPACE and DEL. Motion input sets focus, requests the soft keyboard, and redraws without editing. A compact 208-byte A-Z bitmap table renders stored supported bytes through the exact inherited R5 `drawGlyph` routine. All edits still use the proven eight-byte R7 buffer/caret state.

This remains a native key-event semantics gate, not a full soft-keyboard IME `commitText` claim. Modern IMEs may commit text without letter key events; a narrow commit-text bridge remains a separate follow-up if required.

Independent fixed-record validation confirmed all 868 records are exact 8-byte records, all five S3 branch targets are in range, and the highest fixed write is `0xfd8` inside the planned `0xfdc` output.

RiftOS keeps separate `TRANSACTION_S3_KEY_SEMANTICS` / `riftpp-host s3-android-r8 <riftpp-root>` worker, client, shell and regression coverage. The repaired lane hard-pins the frozen S3 compiler, corrected R8 identities, exact preserved R7 proof base, 16,656-byte compiled patcher and 4,388-byte target ELF. Earlier R3-R7 lanes remain unchanged and all host semantic-ownership flags remain false.

Lifecycle is **R8 REPAIR SOURCE/HOST STAGED / NEW BUILDER GENERATION PENDING / DEVICE KEY-SEMANTICS PROOF PENDING**. R8 is not promoted until frozen S3 generates the repaired target, ELF/import/load invariants are inspected, a distinct proof APK is installed, and direct device evidence shows supported real key events mutating the proven buffer.

## Patch 10.62 — Rift++ Android R7 mutable text-buffer/caret lane

R7 forks only from the exact preserved 2,768-byte R6 semantic-input proof ELF SHA-256 `32f7824d6dd4b2f31c4ec30d93cb46995c242fe62263bcf009eb384a7cd8f5e9`. The authoritative proven Rift++ source is `standalone/android-native-r1/elf32-r7-textbuffer-patcher.arm32.r3.hex`: 271 body records / 2,176 decoded bytes, 4,624-byte fixed-record transport SHA-256 `b4cd99191c66136d03a2232d941db327e733ebc24cb253ee85efa23dfbb108ac`, decoded-source SHA-256 `8b8f5df2cef4364ebfd0ea51450e89728a29f3b8a56a191dc24a7200fa23a6d7`. Builder run 496 / source `fb407a5e5827c72cbea27013a866f418e567e45b` compiled it under frozen S3 to the exact 4,368-byte native patcher SHA-256 `8a7ced9da44cfd1d37dff355f1e295bf0093077307a30d68b6e90419a6d11ae7` and produced the exact 3,248-byte R7 ELF SHA-256 `c489adfd62155b3f916819726cde543eee91e54faefdd371c48c7413c5d6b49a`.

The target adds no Android imports, dynsym entries or relocations. It extends inherited RW/BSS state from `0x22c` to `0x23c`: length at `0x122c`, caret at `0x1230`, and an 8-byte mutable text buffer at `0x1234..0x123b`, above the proven R6 window/focus words. The exact R5 glyph engine at `0x758..0x93f` is not rewritten, and the R6 full-frame renderer remains byte-for-byte unchanged except for one pre-title overlay call.

For this bounded state-model proof, each handled non-pre-dispatched input event appends the next actual byte of deterministic `EDIT` content while length is below four, advances length and caret, and requests a fresh redraw through the proven R6 path. The overlay reads those stored bytes back, maps them to 5x7 rows, calls the exact generic `drawGlyph` routine at `0x874`, and draws a vertical caret at the stored end position before the inherited R5 title renderer unlocks/posts. This gate proves mutable text-buffer/caret state; it does not claim soft-keyboard/IME character decoding.

An earlier caret draft advanced each row by `stride-24` from an unadvanced base and was rejected before host wiring. The corrected source advances by the full stride and is the only pinned R7 identity. Independent fixed-record validation confirmed all 272 records are exact 8-byte records, all five S3 branch targets are in range, and the highest fixed write is `0xcac` inside planned `0xcb0` output.

RiftOS current source adds separate `TRANSACTION_S3_TEXT_BUFFER` / `riftpp-host s3-android-r7 <riftpp-root>` worker, client, shell and regression coverage. The lane hard-pins the frozen S3 compiler, corrected R7 source identities, exact preserved R6 proof base, 4,368-byte compiled patcher and 3,248-byte target ELF. Existing R3/R4/R4.1/R5/R6 lanes remain unchanged and all host semantic-ownership flags remain false.

Exact ELF inspection confirmed the RX load grows only to `0xcb0`, the RW load keeps file size `0x224` while BSS grows only to `0x23c`, no W+E mapping exists, only nine inherited bytes change at expected size/hook fields, and the exact R5 glyph engine plus dynamic/GOT/dynstr/dynsym/hash/relocation regions remain byte-for-byte unchanged. VersionCode 10 / `0.10.0-riftpp-r7-buffer` was APK-v2 signed and independently verified as SHA-256 `0cca2e041ac3268ed2e5286c9d38393c23de86b4b257c806b854eafff78a7d54`; Android install session `1132312570` reported `INSTALL_SUCCEEDED`, and direct user screenshot evidence showed stored `EDIT` plus caret rendered beneath `RIFT++`. Exact proof artifacts are preserved as `elf32-r7-textbuffer-patcher.r7-buffer-proven.arm32.r3.hex` and `libriftpp_editor_native_r7.buffer-proven.so`. Lifecycle is **R7 MUTABLE TEXT-BUFFER/CARET PROMOTED / REAL CHARACTER-SEMANTICS GATE OPEN**.

## Patch 10.61 — Rift++ Android R6 semantic input/focus lane

R6 forks only from the exact promoted 2,368-byte R5 glyph-proven ELF SHA-256 `1702e86b8672697f1139eb105b6c69e9ce455222f90a31d77123bac860b7c2bc`. The authoritative proven Rift++ source is `standalone/android-native-r1/elf32-r6-focus-patcher.arm32.r3.hex`: 239 body records / 1,920 decoded bytes, 4,080-byte fixed-record transport SHA-256 `340d192199408411775baeb3be8a2d20b18c42bdfcb19253a2941da1ddd3f40f`, decoded-source SHA-256 `10ffd04fb115c6229c1114ccef4f1d71ab5b36a58f11a993159a0e9fe2c200b4`. Builder run 495 / source `a83ed529971311289187b49a3c86d5516c910fff` compiled it under frozen S3 to the exact 3,856-byte native patcher SHA-256 `d09e12c6ba9e462411fc2a326b58866336dc83404a5c729b8ac7ab4a77a59fe1` and produced the exact 2,768-byte R6 ELF SHA-256 `32f7824d6dd4b2f31c4ec30d93cb46995c242fe62263bcf009eb384a7cd8f5e9`.

The target adds no Android imports, dynsym entries or relocations. It extends the inherited RW mapping by exactly eight zero-initialized BSS bytes: a current-window pointer at `0x1224` and focus-state word at `0x1228`. The existing R4.1 input queue remains the event source. Window creation records the current `ANativeWindow*`; a new window-destroyed callback clears both state words. The first non-pre-dispatched input event sets focus and, when a current window is valid, locks a fresh buffer and performs an owned redraw: proven orange body, proven dark top bar, a white 210x6 underline at x=24 / y=100, then the exact inherited R5 glyph renderer for readable `RIFT++`. The R5 glyph code region `0x758..0x93f` is intentionally not rewritten by the R6 source.

Independent fixed-record/control-flow validation confirmed all 240 records are exact 8-byte records, all five S3 branch targets are in range, and the highest fixed write is `0xacc` inside the planned `0xad0`-byte output. A separate ARM review caught a +4 PC-relative address-materialization bug in an earlier R6 draft before any Builder/device execution; that draft was overwritten. Only corrected transport SHA-256 `340d192199408411775baeb3be8a2d20b18c42bdfcb19253a2941da1ddd3f40f` / decoded SHA-256 `10ffd04fb115c6229c1114ccef4f1d71ab5b36a58f11a993159a0e9fe2c200b4` are pinned.

RiftOS current source adds a separate `TRANSACTION_S3_FOCUS_SEMANTIC` / `riftpp-host s3-android-r6 <riftpp-root>` path. Worker, client, shell and Builder regression coverage hard-pin the frozen S3 compiler, corrected R6 source identities, exact promoted R5 base, 3,856-byte compiled patcher and 2,768-byte target ELF. Existing R3/R4/R4.1/R5 lanes remain unchanged and all host semantic-ownership flags remain false.

Exact ELF inspection confirmed the RX load grows only to `0xad0`, the RW load keeps file size `0x224` while BSS grows to `0x22c`, no W+E mapping exists, the dynamic/GOT/section/dynstr/dynsym/hash/relocation regions remain byte-for-byte unchanged, and the exact R5 glyph code remains intact. VersionCode 9 / `0.9.0-riftpp-r6-focus` was APK-v2 signed and independently verified as SHA-256 `4c293a6303bd2229d07f2639539f7229fa93db31806663227c1551ec1d1b1540`; Android session `822132264` reported `INSTALL_SUCCEEDED`, and direct user screenshot evidence showed the white focus underline appear beneath `RIFT++` after a tap. Exact proof artifacts are preserved as `elf32-r6-focus-patcher.r6-semantic-input-proven.arm32.r3.hex` and `libriftpp_editor_native_r6.semantic-input-proven.so`. Lifecycle is **R6 SEMANTIC INPUT/FOCUS PROMOTED / R7 EDITABLE TEXT-BUFFER GATE OPEN**.

## Patch 10.60 — Rift++ Android R5 fixed-glyph rendering lane

R5 forks from the exact promoted R4.1 interaction-stability ELF rather than modifying the hardened base. The authoritative Rift++ source is `standalone/android-native-r1/elf32-r5-glyph-patcher.arm32.r3.hex`: 275 body records / 2,208 decoded bytes, 4,692-byte fixed-record transport SHA-256 `ba8f4978c05c0421591fde9ec406cfff1c03373b67341c35838a32a06af29818`, decoded-source SHA-256 `3510e1dccfe1025f2cfbab7c9723b90b5cf8274c12d0bd6975928d8bd85c5f34`. An earlier draft transport accidentally omitted the C byte from ordinary S3 records and was rejected by the independent 8-byte-record audit before any RiftOS host wiring; it was overwritten and is not evidence. The corrected source is the only pinned R5 identity.

Builder run 494 / source `c808372725db39b7200b2cbac6a2930047e830e6` compiled the corrected source under frozen S3 to the exact 4,432-byte native patcher SHA-256 `159d089a562d2784047ed0de9e12146d694a72b0a16a81b3365787f73885b1e6`. The patcher accepted only the exact 1,880-byte R4.1 stability-proven ELF SHA-256 `abf2b0789f72fbc885a5c73eeb10cf6199fb9c5d6b29e4f8024b50b3a9fec610` and produced the exact 2,368-byte R5 ELF SHA-256 `1702e86b8672697f1139eb105b6c69e9ce455222f90a31d77123bac860b7c2bc`. Exact inspection found only 12 changed bytes inside the inherited image (RX file/memory sizes plus renderer call tail) and a 488-byte appended glyph routine; the renderer prefix through `0x257`, entire hardened NativeActivity input/lifecycle code at `0x26c..0x367`, dynamic table, section headers, dynstr/dynsym/hash/relocations and all existing imports remained byte-for-byte unchanged. It requires no new Android imports, dynsym entries or relocations, and retains separate RX/RW mappings with no W+E load.

The first proof target is fixed white `RIFT++` text rendered as six 5x7 bitmap glyphs scaled to 6x6 pixel cells inside the already-proven dark editor bar. Width/height guards run before drawing, the orange body and R4.1 input hardening remain intact, and control returns through the existing proven unlock/post path.

RiftOS current source adds a separate `TRANSACTION_S3_GLYPH_RENDER` / `riftpp-host s3-android-r5 <riftpp-root>` lane. Worker and shell hard-pin the frozen S3 compiler, corrected R5 transport/decoded identities, exact R4.1 stability base, 4,432-byte compiled patcher and 2,368-byte target ELF. Existing R3/R4/R4.1 lanes remain unchanged and all host semantic-ownership flags remain false.

VersionCode 8 / `0.8.0-riftpp-r5-glyph` was packaged, APK-v2 signed and independently verified as SHA-256 `411d644b207a53a666ec1a626696fd897f0cc873a4d11baf1a1a57678ca1d4b1`; Android install session `2023843614` reported `INSTALL_SUCCEEDED`, and direct user screenshot evidence showed readable white `RIFT++` inside the dark editor bar above the preserved orange body. Exact proof artifacts are preserved as `elf32-r5-glyph-patcher.r5-glyph-proven.arm32.r3.hex` and `libriftpp_editor_native_r5.glyph-proven.so`. Lifecycle is **R5 FIXED-GLYPH RENDERING PROMOTED / R6 SEMANTIC INPUT-FOCUS GATE OPEN**.

## Patch 10.59 — Rift++ Android R4.1 input-queue / ANR hardening lane

An intermittent Android "not responding" closure was observed once during the R3/R4 period and again after R4 first-surface proof. Because the symptom predates the R4 top-bar patch and R4 preserved the inherited NativeActivity lifecycle path, the likely cause was treated as inherited input-queue/lifecycle handling rather than an R4-only renderer regression. The successful R4.1 stress session strongly supports that diagnosis, but the historical exits were never captured directly, so the old root cause remains strongly supported rather than formally proven.

R4.1 forks from the exact promoted 1,196-byte R4 ELF SHA-256 `90dc170d574f9d3b0947cccb013b82f5a2bf0b3c3b65013e0fc5050f4da76f9a`. The exact Rift++ source `standalone/android-native-r1/elf32-r41-inputqueue-patcher.arm32.r3.hex` has 628 body records / 5,032 decoded bytes, 10,693-byte transport SHA-256 `66c8949f80ac23b729bf92e5188e1f3b4e5058dce81cca94f979602e25e18551`, and decoded-source SHA-256 `59a3ae7494697f83acf34d6310686b7e1b8d6ec54c8d25643f4bc917e58ee6a5`. Builder run 493 / source `92ca3fe0f7bb4ebb948047c7cc6e8736a8411834` compiled it under frozen S3 to the exact 10,080-byte patcher SHA-256 `9d443ba3d9e188885d8b029678b2305e36651670280f34447407e92af859f914`, which produced the exact 1,880-byte R4.1 ELF SHA-256 `abf2b0789f72fbc885a5c73eeb10cf6199fb9c5d6b29e4f8024b50b3a9fec610`. The entire proven R4 renderer region through `0x26b` is preserved. ELF inspection confirmed separate RX/RW mappings with no W+E, valid null dynsym / SysV hash terminator / final `DT_NULL`, all nine expected GLOB_DAT imports, and the NativeActivity input-queue create/destroy callbacks plus looper attach/drain/pre-dispatch/finish handling.

The proof wrapper was versionCode 7 / `0.7.0-riftpp-r41-input`; APK-v2 signed artifact SHA-256 `467ac3749f8ab844eda2e18f796641a52da16e6efe268901ad09a6b712eb9d93` passed independent verification, Android session `1067604178` reported `INSTALL_SUCCEEDED`, and direct user stress testing repeatedly exercised touch/back behavior—including rapid repeated Back presses—without reproducing the prior not-responding closure. Existing R3/R4 lanes remained unchanged and all host semantic-ownership flags remained false. Best-effort `ApplicationExitInfo` correctly reported cross-package history unavailable with `SecurityException` because `android.permission.DUMP` is not granted; that limitation is explicit and is not treated as evidence of no crash.

Lifecycle is **R4.1 INTERACTION-STABILITY PROMOTED / R5 TEXT-GLYPH GATE OPEN**. Exact proof artifacts are preserved as `elf32-r41-inputqueue-patcher.r41-stability-proven.arm32.r3.hex` and `libriftpp_editor_native_r41.stability-proven.so`.

## Patch 10.58 — Rift++ Android R4 first editor-surface patch lane

R4 begins above the promoted R3 first-frame proof rather than modifying the frozen R3 linker. The new Rift++ source `standalone/android-native-r1/elf32-r4-topbar-patcher.arm32.r3.hex` has 65 body records / 528 decoded bytes, 1,122-byte transport SHA-256 `09afaeda7cbf30281718ec6e354838e75be3d6228297f0b4b70f815253610706`, and decoded-source SHA-256 `9cf4f6c7670d50f2b6caaeca2f24d20949811291fbe0dfdcbad3a104c78332af`. Frozen S3 is expected to compile it to a 1,072-byte native patcher. The patcher accepts only the exact promoted 1,196-byte R3 ELF SHA-256 `44f8b266aef910acaaedfe3a5f6cf7b9f1e029acd6e1c2e310410a22568be28e`, copies it byte-for-byte, then patches eighteen renderer-tail words plus the exported-symbol and `.text` sizes. The existing orange fill remains the background; the new tail paints up to the first 160 rows dark charcoal before calling the already-proven unlock/post path. Dynamic metadata, imports, relocations, GOT, program headers and total ELF size remain unchanged.

RiftOS current source adds a separate `TRANSACTION_S3_UI_PATCH` and `riftpp-host s3-android-r4 <riftpp-root>` path. The worker process hard-checks the frozen S3 compiler identity, exact decoded patcher size/hash and exact promoted R3 base identity; the shell separately pins the 1,122-byte transport identity and publishes only a hash-verified 1,196-byte result. Result envelopes expose patcher source/output identities while all semantic-ownership flags remain false. R3's `TRANSACTION_S3_FRAME_LINK` path is left intact.

Builder run 492 / source `2b94042e6fd229ee7aba4813ab99857542dee262` compiled the exact 528-byte decoded R4 patcher under frozen S3 to a 1,072-byte native image SHA-256 `82e6aa4e82426c7d1cc2392c3a52d5f46f5604873f2fd774a0ef6377b6d5d57d`, then transformed only the exact promoted R3 ELF into the 1,196-byte R4 ELF SHA-256 `90dc170d574f9d3b0947cccb013b82f5a2bf0b3c3b65013e0fc5050f4da76f9a`. Exact R3↔R4 comparison found 68 changed bytes confined to the renderer tail and the two size fields; program headers, dynamic table, SysV hash and relocation regions were byte-for-byte unchanged, with `.text` and exported symbol sizes growing 152→204 bytes. VersionCode 6 / `0.6.0-riftpp-r4-topbar` was signed and independently APK-v2 verified as SHA-256 `b11fc25040c64fbca054e050cffedffd1612d06935780c9a4b7da109592538c5`; Android session `418510229` reported `INSTALL_SUCCEEDED`, and direct user screenshot evidence showed the dark charcoal editor bar over the preserved orange frame. Lifecycle is **R4 FIRST EDITOR SURFACE PROMOTED / TEXT-GLYPH R5 GATE OPEN**.

## Patch 10.56 — Rift++ generic machine-code host bounds raised to 64 KiB

The generic Rift++ machine-code host previously mixed a 64 KiB compiler-image ceiling with 4 KiB source/output ceilings in the JNI/native host, Kotlin service and `riftpp-host compile/prove` shell boundary. That limit was an execution-host containment policy, not a frozen S3/VM1 architectural requirement, and it became artificially tight as the Android-native Rift++ emitter approached the 4 KiB generated-image boundary.

Current source raises the generic source and generated-output ceilings to 64 KiB end-to-end. `RiftppCompilerService` now admits at most 64 KiB source and output buffers; generic `riftpp-host compile` defaults to a 64 KiB output capacity while accepting caller-selected capacities only in 1..65536; and `riftpp-host prove` intentionally keeps its 32-byte default proof output. Build 488 exposed that the JNI/native `nativeCompile` lane still backed source/output with one guarded page despite those logical ceilings: the corrected 4,192-byte R3 linker compile was rejected with native status `-96` before execution. Current source migrates generic compiler, source and output buffers to the existing multi-page `GuardedSpan` allocator while retaining guard pages and prefix-canary checks, making the 64 KiB source/output policy physically real rather than page-size limited. Compiler images remain bounded to 64 KiB. Regression coverage now pins the multi-page source/output containment contract as well as the service, native host and shell limits.

This does not modify frozen S3, VM1, any opcode, compiler ABI, target code semantics or proof-specific exact-size gates. Stage1/S2 bootstrap transactions keep their narrower canonical source checks where those are part of the proof contract. The change only removes the obsolete generic 4 KiB host ceiling so later Rift++ emitters/rendering stages can grow under the same bounded private-process execution model.

Lifecycle is **BUILDER VERIFIED / LIVE ON RUN 490**. The multi-page guarded-span host path was exercised by the corrected 4,192-byte R3 linker compile and cleared the prior native `-96` page-size rejection.

## Patch 10.57 — Rift++ Android R2 promotion and R3 first-frame linker

RiftOS run 484 / source `7e6f83738d4d8b343cb6378edbb5c7d32a0cf6ef` installed version `0.4.0-riftpp-r2-window` and promoted the NativeActivity window-callback gate. Android reported `INSTALL_SUCCEEDED`, launched `com.riftpp.editor.nativev1`, and the cooperative diagnostic bridge received two valid stage-6 `window-callback` packets from target PID 27663. Both packets carried non-zero `ANativeActivity*` and `ANativeWindow*` values, proving that the Rift++-written callback-table entry was invoked by Android and that a real native window reached Rift++ code. The exact R2 source is retained as `entry.r2-window-callback-proven.arm32.r3.hex`.

R3 does not enlarge or rewrite the promoted R1/R2 transaction. Instead it adds a second Rift++-owned stage over the exact 972-byte R2 ELF SHA-256 `d9669d97c6f0f0225b8624818dc9f2f0dad4611ded757ac48dfea1b9cba06d46`. The first installed version-5 attempt used the earlier 245-body-record linker, produced a 1,196-byte ELF SHA-256 `d825e50988a3265e05b8b8c56b46fa915a260ecb212488085f72f954f3f5c9e0`, installed successfully, then crashed on launch with no RDBG event. Exact ELF inspection found the guarded host's `0xA5` poison still present in mandatory zero-valued loader metadata: dynsym entry 0, undefined-import `st_value` / `st_size`, SysV hash-chain terminators and the final `DT_NULL`. That failed source is retained as `elf32-r3-frame-linker.loader-failed.arm32.r3.hex`, SHA-256 `fea11e16ab4a3bbcb5c4bf611c627973d843451c435e7b84ccac33f532cc96ef`. Active `elf32-r3-frame-linker.arm32.r3.hex` now adds one zero load plus fourteen explicit zero stores, has 260 body records / 2,088 decoded source bytes, 4,437-byte transport SHA-256 `18782a0cb8719b04fcac338667ca99f22e58d0e3c52b5a09d18ea4773c0173b6`, and is expected to compile to 4,192 bytes under frozen S3. Its semantic target remains a 1,196-byte ELF.

The R3 ELF keeps executable code in RX memory and adds a separate RW mapping rather than a W+E segment. It extends dynamic metadata with `DT_NEEDED libandroid.so`, imports exactly `ANativeWindow_setBuffersGeometry`, `ANativeWindow_lock`, and `ANativeWindow_unlockAndPost`, and binds them through three ARM `R_ARM_GLOB_DAT` relocations into a writable GOT. The callback renderer is 152 ARM32 bytes: it requests RGBA8888, locks the real `ANativeWindow`, fills the complete stride × height buffer with a fixed orange pixel value, then unlocks/posts the frame. No Java/Kotlin/C/C++ target code, Clang target build step, or RiftOS UI semantics are introduced.

RiftOS adds a separate `TRANSACTION_S3_FRAME_LINK` and `riftpp-host s3-android-r3 <riftpp-root>` path. The host only verifies fixed identities/sizes, executes the frozen S3 compiler and generated linker, transports bytes, and publishes `libriftpp_editor_native_r3.so`; all S3/ARM/ELF/UI semantics remain Rift++ owned. The version-5 wrapper is `0.5.0-riftpp-r3-frame`. Builder run 490 / source `18e1b5bfd6c91efee110d6152fbdafda8843e866` compiled the corrected source under frozen S3 to 4,192-byte linker SHA-256 `98087752725238853d58d124930dcb25a0d96120899d5b2e07f69527137b9a8d` and generated 1,196-byte ELF SHA-256 `44f8b266aef910acaaedfe3a5f6cf7b9f1e029acd6e1c2e310410a22568be28e`. All fourteen formerly poisoned mandatory loader fields inspected as zero; imports, relocations and RX/RW mappings remained exact. Signed APK SHA-256 `cbd5bb384f97aee456bef6fdae286c9c2c939266d74943405d4106fbddd1d6be` passed independent APK-v2 verification; Android session `1663217197` reported `INSTALL_SUCCEEDED` and `launch-proven`; direct user screenshot evidence showed the full orange frame. Lifecycle is **R1 LOAD PROVEN / R2 WINDOW CALLBACK PROMOTED / R3 FIRST FRAME PROMOTED / R4 EDITOR UI GATE OPEN**.

## Patch 10.55 — Rift++ Android R1 device promotion and R2 NativeActivity window-callback gate

RiftOS run 483 / source `cc990d77fa20c42c8c2a0b2218209f5798e81cfd` cleared the corrected 1,704-byte decoded emitter-source admission gate and completed the full frozen-S3 Android R1 transaction on the real `armeabi-v7a` device. The exact chain produced a 1,968-byte entry emitter, a 228-byte ARM32 entry body, a 3,424-byte ELF emitter and a 972-byte Bionic-valid ELF32 ARM ET_DYN object with SHA-256 `72d26ffad51b5f48643460aab0447139b458a599daad291aef1965a5dcc5178f`. All host-ownership booleans remained false: RiftOS did not parse S3 opcodes, emit S3/ARM instructions, parse ELF or emit ELF.

The exact 972-byte shared object was copied into the prepared ARM32 NativeActivity package, packed into a 1,500-byte unsigned APK, signed with APK Signature Scheme v2 and independently verified. The signed artifact SHA-256 was `a1bf1ad8df8696e1dd46149cf168a86eb6f9592462d65f815f5890116aa616d4`. Android reported `INSTALL_SUCCEEDED` for `com.riftpp.editor.nativev1`, and the exact launch request was recorded. The cooperative diagnostic receiver then received one valid stage-1 `entry-begin` packet from target PID 18208 on localhost. This promotes the Bionic load/symbol-resolution/Rift++ entry-execution gate: Android loaded the Rift++-emitted ELF and executed the exact S3-emitted entry body on-device.

The loader-proven R1 entry is retained as `entry.r1-bionic-load-proven.arm32.r3.hex` with transport SHA-256 `85d92e2f49aa0f058b183d954144ab4f6d2659272bd93f32e3327eaf2607ef51`. The active R2 source remains the same 121-body-record / 976-byte decoded S3 program and still emits exactly 228 ARM32 bytes, but now installs a Rift++ callback pointer into `ANativeActivityCallbacks.onNativeWindowCreated`. On ARM32, `ANativeActivity.callbacks` is the first pointer field and `onNativeWindowCreated` is callback slot 7, so the table write is at byte offset 28. The callback body begins at byte offset 0x10 of the same `.text` region and emits fixed `RDBG` stage 6 `window-callback` carrying the activity and window pointers. Active R2 entry transport SHA-256 is `5ce665811c7753f1b55d8d0cfe0cac3a1cafcd8d9f8b43e35acbb0b03d6643c6`; the proven 3,424-byte ELF emitter and 972-byte ELF layout remain unchanged.

The proof wrapper advances to versionCode 4 / versionName `0.4.0-riftpp-r2-window`. Lifecycle is **R1 LOAD/ENTRY PROMOTED / R2 SOURCE STAGED / INSTALLED WINDOW-CALLBACK PROOF PENDING**. Rendering is explicitly deferred until stage 6 is observed.

## Patch 10.54 — Rift++ Android R1 decoded emitter-source admission correction

The first installed repro on RiftOS run 482 / source `ecb9f512c5e5c5bf2329773f4eac42bc2f24ed87` reached the fixed `riftpp-host s3-android-r1` route but failed before native execution with `status=host-reject` / `reason=s3-emit-emitter-source-size`. The active ELF-emitter transport is 3,621 bytes of canonical fixed-record text containing 213 records total: one required S3 header plus 212 body records. Decoding therefore yields 1,704 source bytes. The earlier 1,696 figure is correct only for the 212 body records and was incorrectly reused as the decoded-source admission size.

Current source changes only that host admission constant from 1,696 to 1,704 bytes and adds a focused regression requirement for the exact `emitterBytes.size != 1704` gate. The active emitter transport identity `43641344176878c30c116d0e1c4c67f9631a8773a35171beaa57857a8306267a`, 212-body-record semantics, predicted 3,424-byte compiled emitter output, 972-byte Bionic-valid ELF output and frozen S3 semantics are unchanged. The Rift++ R1 README now distinguishes body-only raw bytes (1,696) from decoded source bytes including the S3 header (1,704).

Lifecycle is **SOURCE FIXED / BUILDER + INSTALLED DEVICE REPRO PENDING**. The next device run should regenerate the exact 972-byte object and proceed to package/sign/verify/install/launch diagnostics; no ELF or entry semantics changed in this correction.

## Patch 10.53 — Rift++ Android R1 diagnostic bridge and Bionic-valid ELF gate

Installed RiftOS source `e391f12c55525297e652f4f6a60755c865aee797` / Builder run 480 brought the cooperative Rift++ app diagnostic lane live. `RiftAppDiagnosticBridge.kt` binds a bounded UDP receiver to `127.0.0.1:39771`, accepts fixed 32-byte `RDBG` packets only for the fixed Rift++ proof/editor package allowlist, retains at most 64 events and persists bounded advisory evidence under `D:/Diagnostics/riftpp/<package>/latest.json`. `riftcrash help|status|start|capture|latest|reset [package]` exposes the receiver through RiftShell. `RiftBuildInstaller` starts a diagnostic session only for supported packages before exact launch; generic NativeActivity manifests may request an optional bounded permission list whose current allowlist is exactly `android.permission.INTERNET`.

The first installed RDBG proof package `com.riftpp.editor.nativev1` version 3 installed successfully and received an exact launch request while the listener was active, but the dump remained `eventCount=0`. That evidence does not prove the entry symbol was never reached because the first packet itself depended on raw socket setup. A 3-second raw `nanosleep` entry probe was therefore staged before any network syscall so the next device run can independently distinguish entry execution from UDP failure.

A deeper Android/Bionic loader audit then found a concrete pre-entry defect in the earlier 644-byte Rift++ ELF: it had `e_shnum=0`, `e_shstrndx=0` and no section-header table. Current source checkpoint `ca71050ac0043719ac16eb2c15f9c4cd377ab600` pins entry transport SHA-256 `85d92e2f49aa0f058b183d954144ab4f6d2659272bd93f32e3327eaf2607ef51` plus compact ELF-emitter transport SHA-256 `43641344176878c30c116d0e1c4c67f9631a8773a35171beaa57857a8306267a`. Frozen S3 compiles the active ELF source to a 3,424-byte emitter, which emits a 972-byte ELF32 ARM ET_DYN object. The original loaded region/code offset remains intact while the file adds a 48-byte `.shstrtab` and seven 40-byte section headers for null, `.dynstr`, `.dynsym`, `.hash`, `.dynamic`, `.text` and `.shstrtab`; `.dynamic` exactly mirrors `PT_DYNAMIC`, and the exported `ANativeActivity_onCreate` symbol points at `.text`.

Frozen S3/VM semantics remain unchanged. RiftOS still does not parse S3 opcodes, emit ARM instructions, parse ELF or own target runtime semantics; it only executes exact pinned Rift++ emitters and transports/persists bounded evidence. Lifecycle is **SOURCE STAGED / BUILDER + INSTALLED DEVICE REPRO PENDING** for `ca71050…`. The next gate is to install a RiftOS build from that checkpoint, regenerate the 972-byte object, package/sign/verify/install `com.riftpp.editor.nativev1`, launch it with the diagnostic receiver active, observe whether the visible NativeActivity survives the 3-second probe, and inspect `riftcrash latest`.

## Patch 10.52 — Rift++ S2 self-host promotion and freeze

Installed RiftOS source `5206c80c5e7b1279bda63e566af19569d462d43b` / Builder run 445 completed the canonical S2 A→B→C→D convergence transaction twice on the real `armeabi-v7a` device. Both runs returned `status=success` with 44288-byte B and C images for ARM32 and ARM64, current-host B and C native execution enabled, cross-target compilation for both targets, and exact fixed-point flags `generationCArm32EqualsD=true` plus `generationCArm64EqualsD=true`.

The promoted Generation-C identities are ARM32 `13c691dcb1214d7a66ac8d931907a25d96ac12a42b9f52ba2a9b8dfa0d344aa2` and ARM64 `cf9de173f31cb745a2d7afd32959d798b2ee76e78b0f0cd2775fa0bc6a137600`. After fixed-point convergence, current-host C compiled the fixed ret42 sources to exact 96-byte outputs with ARM32 SHA-256 `1f9ffbb7a94afcc37821d0686d6cc1c23c76258ae54eec0cbd97f85ccd09631f` and ARM64 SHA-256 `6b99c0501765629c7752f361617bfe56350fb974e7fa790b7d7c5992e139b5c4`; only the generated ARM32 proof executed, with proof status `0` and return value `42`. The second run reproduced the same compiler/proof identities and booleans exactly.

This closes S2 self-host promotion. The S2 contract, canonical compiler sources and promoted Generation-C identities are frozen as the proven self-host foundation. Frozen Seed0, Stage1 and Generation A remain bootstrap history and are not expanded. Native ARM64 execution remains deferred to compatible AArch64 hardware/userspace, while ARM64 cross-target C=D byte identity is mandatory and proven in the promoted transaction. RiftOS remains a bounded executor/evidence host only: it does not parse S2 opcodes or emit S2 instructions. The next active phase is S3 minimization and freeze of the permanent per-application Rift++ base; that final base is not yet frozen.

## Patch 10.51 — Rift++ S2 C→D convergence fixed-point gate

Installed RiftOS source `4cf42b409116f0dad920da2ff9281169755eb97e` / Builder run 441 advanced the canonical S2 self-host chain through A→B→C on the real `armeabi-v7a` device. The fixed transaction produced 44288-byte B and C images for both targets and proved that current-host B executes and cross-compiles both canonical sources. ARM64 was already byte-stable at B=C. ARM32 was not: the native host returned `-294` with `generationBArm32EqualsC=false` and `generationBArm64EqualsC=true`.

That ARM32 mismatch is a bootstrap convergence transition, not a compiler failure. Frozen Generation A emits non-aliasing EQ/LTU slots with the historical `MOV dst,#0; CMP lhs,rhs; MOVcc dst,#1` word order. The corrected compiler carried by B emits the permanent alias-safe `CMP; MOV dst,#0; MOVcc dst,#1` order. The bootstrap-safe canonical source contains no EQ/LTU alias hazards, so B executes correctly, but B's ARM32 bytes cannot equal C because A and B intentionally emit different native word order. The already-pinned `13c691dc…` ARM32 and `cf9de173…` ARM64 identities are therefore Generation-C candidate identities.

The fixed self-host transaction now converges one generation further without changing compiler semantics. After B emits C, the current-host C image is copied into a separate guarded multi-page W→X region, executes natively, compiles the same ARM32 and ARM64 canonical sources into Generation D, and success requires C32=D32 plus C64=D64 byte-for-byte. Only after C=D does C compile the two fixed ret42 sources; only the current-host generated proof image executes, and it must return 42. The JNI evidence envelope remains 12 longs; the existing fixed-point booleans now report C=D, while the returned 44288-byte identity buffers contain Generation C so Kotlin can enforce the exact promoted hashes. The bounded self-host timeout rises from 20 to 30 seconds to cover the extra full generation. Generic `riftpp-host compile/prove` remains capped at 4096 bytes and RiftOS still contains no S2 opcode parser or instruction emitter.

The run-436 `diagnosticReturnValue=44288` interpretation is also corrected in current docs: it was a successful full compiler-image length, not evidence of v5/r5 corruption. The root cause of the earlier ARM32 execution failure was frozen A's alias-unsafe EQ/LTU ordering already addressed by Patch 10.49. Lifecycle remains **SOURCE IMPLEMENTED / INSTALLED C=D PROOF PENDING**.

## Patch 10.50 — RiftBuild bundled-toolchain Kotlin path-normalization compile fix

The second Builder attempt for RiftOS source `fb83e5319a9e1f87034e578cd7d1e718d651c454`, after Builder commit `e71f6f2075a7188aa80a4aad5d8922dc9ad7d500`, cleared the previous Termux-prefix failure completely. The private worker log shows both `armeabi-v7a` and `arm64-v8a` packaging Clang/LLD 21.1.8-3 plus eight private runtime libraries per ABI, followed by successful creation of the 177,781,923-byte `android-clang-v1.zip` (`c902283171949b2cb31cbe60e77bb535dd02472e8933e476efe32643673d4874`). Source checks and dedicated Gradle validation also passed.

The Android build then failed at Kotlin compilation with one exact error: `RiftBuildNativeToolchain.kt:135:51 Unsupported escape sequence`. The checked-in `installBundled()` ZIP-path normalization `Char` literal contained four backslash characters where Kotlin source requires the standard two-character escaped backslash. Current source corrects only that literal while preserving the existing bounded archive/path-confinement logic. `scripts/test-riftbuild-native.mjs` now requires the valid form and explicitly rejects the malformed form so this syntax error is caught by `npm run check` before toolchain generation/Gradle.

This patch does not alter Rift++ S2 compiler semantics, canonical source identities, Builder toolchain package versions, archive bounds, native compiler authority, or installed-device promotion claims. The next gate is a Builder compile of this corrected source, then installed-device `s2-selfhost` proof.

## Patch 10.49 — Rift++ S2 bootstrap-safe self-host correction

Installed RiftOS source `cbe5924cfac296aed57a0606c7c581f6e54e8270` / Builder run 437 preserved the Patch 10.45 reject-offset lane and reproduced the self-host failure on `armeabi-v7a`. Independent interpretation of the exact 1384-record canonical S2 compiler returned 44288 without entering reject and emitted the exact previously pinned Generation-B bytes, proving the S2 algorithm/source logic itself was sound. The fixed emitter corpus then exposed the native ARM32 defect: Generation A encodes `EQ`/`LTU` as `MOV dst,#0; CMP lhs,rhs; conditional MOV dst,#1`, which is wrong whenever `dst` aliases `lhs` or `rhs`. Canonical source audit found 65 aliased `EQ` records and 48 aliased `LTU` records.

The canonical 1383-record body is now rewritten in place without changing record count or numeric branch layout. Every aliased `EQ` becomes `SUB dst,lhs,rhs` and its immediately following `BRZ`/`BRNZ` is inverted, preserving equality semantics through zero/nonzero without requiring a temporary. Every aliased `LTU` redirects its result and immediate branch condition to a liveness-proven dead non-operand virtual register; none of those original destinations are live after the branch. A full CFG/liveness pass confirms zero aliased `EQ`/`LTU` records remain. Frozen Generation A is unchanged.

The compiler's own ARM32 EQ/LTU emitter is also permanently corrected: it now writes the `CMP` word before `MOV dst,#0`, then the conditional `MOV dst,#1`, so self-hosted Generation B correctly supports aliasing for future S2 programs instead of merely avoiding the bootstrap defect. ARM64 target emission remains unchanged. Rewritten canonical raw identities are `476266f8…` (ARM32) and `d2644b4c…` (ARM64); transport identities are `dcef727d…` and `f7bb06ff…`. Independent S2 interpretation returns exactly 44288 for both rewritten sources and produces candidate Generation-B identities `13c691dc…` (ARM32) and `cf9de173…` (ARM64) without reaching reject. Fixed diagnostic artifacts are regenerated from the rewritten sources and repinned in shell/service/test admission.

RiftOS host authority is unchanged: it still does not parse S2 opcodes or emit/repair native instructions, the generic 4096-byte compile/prove ceiling is unchanged, and installed A→B→C B=C proof remains mandatory before S2 promotion.

## Patch 10.48 — RiftBuild bundled Android-host toolchain provisioning

Added the on-device provisioning lane required to turn Native Compile V1 from a source contract into a self-contained RiftOS build capability. `riftbuild toolchain-install-bundled` now installs a bounded `assets/riftbuild/android-clang-v1.zip` into `C:/Toolchains/android-clang-v1` via staged/atomic replacement and immediately returns toolchain readiness. The host Clang/LLD executables are expected as Builder-generated JNI payloads so Android extracts them into executable native-library storage rather than writable app data. Gradle now consumes `build/generated/riftosJniLibs`, uses legacy/extracted JNI packaging, and the manifest requests `extractNativeLibs=true`.

Native Compile now exports `LD_LIBRARY_PATH` to the compiler executable directory and supports `%COMPILER_DIR%` toolchain argv expansion in addition to `%TOOLCHAIN%`/`%SYSROOT%`. This allows a bundled Clang driver to use an ABI-matched bundled LLD while the target NDK sysroot/resource payload remains ordinary extracted data. Lifecycle: **SOURCE IMPLEMENTED**; Builder payload generation and installed-device compile proof are the next gates.

## Patch 10.47 — RiftBuild generic NativeActivity app preparation

Added `RiftBuildNativeApp.kt` and `riftbuild prepare-native-app <project>` so ordinary native applications no longer need a proof-specific frozen Android manifest. A bounded `rift-app.json` now defines package/library/version/SDK metadata and an optional project asset directory. The preparer emits Android binary XML for a `NativeActivity`, cross-checks the native library identity against `rift-native.json` when present, clears/re-materializes only the prepared asset subtree, and preserves ABI `.so` outputs already emitted by Native Compile V1. Asset count/bytes and all paths are bounded and project-confined.

This closes the source-side C++ + custom-script packaging path: `compile-native -> prepare-native-app -> pack -> sign -> verify`. Historical proof manifest encoders and their byte identities remain unchanged. Lifecycle: **SOURCE IMPLEMENTED**; Builder compilation, generic-manifest artifact validation, compatible Android-host compiler provisioning and installed-device proof are still pending.

## Patch 10.46 — RiftBuild Native Compile V1

Added a general C/C++ native compilation lane to RiftBuild. `RiftBuildNativeToolchain.kt` owns explicit Android-host toolchain configuration from `C:/Toolchains/android-clang-v1/toolchain.json`, reads bounded per-project `rift-native.json`, and emits verified `lib/<abi>/lib<name>.so` artifacts directly into the existing prepared APK tree for ARM32, ARM64 or universal builds. The compiler is launched directly with a structured argv vector; project/source text is not interpreted as a shell command. Local/downloaded toolchains are permitted when explicitly configured and executable on the Android host.

`riftbuild toolchain-status` reports provisioning readiness and `riftbuild compile-native <project> [arm32|arm64|universal]` performs compilation. The runner confines project sources/includes/outputs, bounds source counts/sizes/compiler output/time, pins supported C++ standards and optimization values, supports bounded toolchain-owned argv for resource/libc++ setup plus bounded project `libraries` lowered to `-l<name>`, and independently verifies ELF class/type/machine plus SHA-256 before returning success. `RiftBuildLocalExecutor.doctor` now reports real `compileReady` state from this toolchain owner instead of the previous hardcoded native-compile blocker. Existing pack/sign/verify/install ownership is unchanged.

Lifecycle: **SOURCE IMPLEMENTED**. Focused source/validator and Builder compilation are required next; installed-device compiler execution is not claimed until a compatible Android-host toolchain is provisioned and both ABI outputs are proven on-device.

## Patch 10.45 — Rift++ S2 fixed reject-offset diagnostic correction

Installed RiftOS source `ff852299c4dabbfaf82f5b32c62a7b20cd67bf53` / Builder run 436 executed Patch 10.44's fixed diagnostic on `armeabi-v7a`. Generation A again produced the exact 44288-byte ARM32 and ARM64 Generation-B identities, while canonical Generation B still failed its first self-recompile with host status `-273`. The diagnostic image returned `diagnosticReturnValue=44288` instead of a record index. A source audit then proved `v5` is written only for compiler loop-index initialization/increment, so the live value 44288 demonstrates that generated Generation-B native execution is corrupting mapped v5/r5 state; the original assumption that `v5` remained a trustworthy reject-site index was invalid.

This patch replaces the diagnostic artifact only. The two new fixed sources remain identical to canonical compiler sources except the first reject-tail instruction is `SUB v6, v1, v0`; the following `RET v6` is unchanged. In both validation and emission loops `v0` remains the canonical source base and `v1` points to the current source record, so the returned value is the exact source byte offset active at rejection and does not depend on corrupted `v5`. ARM32 transport/raw identities are `ec32b059…` / `e592d359…`; ARM64 transport/raw identities are `9ee9abcd…` / `165c4919…`.

`RiftNativeShell`, `RiftppCompilerService`, and the Rift++ host regression are repinned to the new fixed paths and identities. The native diagnostic execution lane, 44288-byte proof-specific guarded W→X mapping, exact canonical source admission, 12-field evidence envelope, and generic 4096-byte compile/prove ceiling are unchanged. This diagnostic remains non-authoritative debugging evidence and cannot satisfy self-host promotion. Builder compilation and installed reject-offset evidence are the next gate.

## Patch 10.44 — Rift++ S2 fixed reject-index diagnostic

Installed RiftOS source `9a90ea6b188100864bac51236b51798bdf049ebc` / Builder run 435 reached current-host Generation B but failed when B recompiled the canonical compiler source with native host status `-273`. The bounded native helper defines `-273` only when the compiler returns `0xffffffff` or returns a length above the supplied capacity. The self-host call already supplies the exact 11072-byte canonical source and exact 44288-byte output capacity, so this patch adds diagnostic evidence instead of widening bounds or moving compiler semantics into RiftOS.

Two fixed diagnostic S2 sources now live under `s2/diagnostics/`. Each is an exact copy of its canonical target source except the final reject tail returns `v5`, the current validation record index, rather than `0xffffffff`. ARM32 transport/raw identities are `0981e85a…` / `cac67ffa…`; ARM64 transport/raw identities are `6b457d9c…` / `ba4ea986…`. `RiftNativeShell` chooses only the current-host ABI file, enforces the exact 23528-byte transport size and transport SHA before canonical fixed-record decoding, and `RiftppCompilerService` independently pins the decoded 11072-byte raw identity.

Inside the existing private self-host transaction, the already-proven Generation-A compiler compiles the exact diagnostic source to a temporary 44288-byte host-native image. Native code places only that generated image in a separate guarded W→X mapping and invokes it only against the exact current-host canonical compiler source. Its returned value is carried back as `diagnosticReturnValue`, including through the existing B→C failure paths. The diagnostic image cannot replace the canonical compiler, repair output, accept arbitrary source/compiler paths, or satisfy the self-host promotion gate; RiftOS still contains no S2 opcode parser or native instruction selector.

Regression coverage now locks both transport and raw diagnostic identities, fixed paths, the 12-field result envelope, the generated-only diagnostic executable path and the unchanged generic 4096-byte compile-source ceiling. RiftBuild structural validation remains green with `sourceReady=true`. Builder compilation and installed reject-index evidence remain pending, and S2 remains unpromoted until the canonical compiler completes deterministic B=C for ARM32 and ARM64.

## Patch 10.43 — Rift++ S2 canonical self-host fixed point

Generation A is already installed-device proven on source `01243f65b81866fd48552be6c33ab2d73752c35a` / Builder run 434. Current source now adds the next fixed authority transition: `riftpp-host s2-selfhost <riftpp-root>`.

The canonical S2 compiler is authored entirely as S2 v0 records. Each target source contains 1384 records / 11072 raw bytes; only the target header differs. Pinned raw source identities are `95964849…` for ARM32 and `d03e4e23…` for ARM64. Generation A must compile them to exact 44288-byte Generation-B images `0d493aab…` and `82bf9588…`. The current-host Generation-B image is then copied into a separate multi-page guarded mapping, changed W→X, executed natively, and required to compile the same two sources into Generation C with byte-identical B=C for both targets. Generation B must also reproduce the two exact 96-byte `ret42` target images, and the generated current-host proof must execute with return value 42.

The self-host route accepts no caller-selected compiler or source paths. Kotlin pins Generation-A, canonical compiler-source, Generation-B and proof identities; native code performs bounded execution and byte comparison but contains no S2 opcode parser or native instruction selector. The existing generic `compile/prove` 4096-byte source/output surface remains unchanged. The 44288-byte compiler image uses a proof-specific guarded multi-page allocation rather than widening the generic one-page compiler surface.

Validation at source checkpoint: RiftBuild Android structural validation reports `sourceReady=true`; Rift++ audit/scan have zero findings; RiftOS audit/scan report only the pre-existing `RiftSecretStore.kt` filename heuristic. The normal Builder-owned `test-riftpp-machine-code-host.mjs` regression now pins this transaction and all fixed identities. Builder compilation and installed A→B→C fixed-point proof remain pending.

## Patch 10.42 — Rift++ S2 fixed emitter-corpus diagnostic

After Generation A passed installed ARM32 proof on source `01243f65b81866fd48552be6c33ab2d73752c35a` / Builder run 434, current source adds one bounded derivation-only route: `riftpp-host s2-vectors <riftpp-root>`. It accepts only the exact host-ABI Generation-A image identity and two fixed 70-record S2 corpus sources (560 raw bytes each), then invokes Generation A to produce exactly 2240 bytes for ARM32 and 2240 bytes for ARM64.

The transaction exists only to recover Generation-A's exact deterministic 32-byte slot encodings before authoring the canonical compiler in S2. Kotlin pins the two raw-source hashes and Generation-A identity before JNI execution. Native code maps only the approved Generation-A compiler W→X, compiles both fixed sources through the real compiler, copies the resulting bytes back for hashing/analysis, and explicitly does not execute either corpus output. RiftOS does not parse S2 opcodes or emit S2 instructions, and the generic 4096-byte compile/prove surface remains unchanged.

The corpus covers all 21 S2 v0 opcodes with bounded register/immediate/branch variants. Its raw identities are `cf33c59d…` for ARM32-target source and `3075cb2d…` for ARM64-target source. Structural RiftBuild validation remains green (`sourceReady=true`); audit/scan remain clean except the pre-existing `RiftSecretStore.kt` filename heuristic. Builder compilation and installed execution of this new diagnostic remain pending.

## Patch 10.41 — Rift++ S2 Generation-A fixed bootstrap host

Current source adds the fixed `riftpp-host s2-bootstrap <riftpp-root>` transaction over the existing private `:riftppCompiler` process. The route admits only exact frozen Seed0 and Stage1 inputs plus exact Generation-A Stage1 sources and exact 24-byte S2 proof sources. It does not change the generic 4096-byte compiler-source ceiling.

The native transaction reconstructs Stage1 through Seed0, executes the current-host Stage1 compiler, manufactures exact candidate Generation-A images for ARM32 (3128 bytes, `d8a72510…`) and ARM64 (2884 bytes, `f0e3c871…`), then executes only the current-host Generation-A compiler. Generation A compiles both fixed S2 `ret42` sources to 96-byte native images, and the generated current-host image is the only proof payload executed; success requires return value 42.

Kotlin pins exact source/image hashes before native execution. The native worker does not parse S2 opcodes, and the shell's fixed-record decoder only materializes canonical lowercase 8-byte record hex. The existing regression now locks the new transaction, identities, bounds, generated-only execution path, and unchanged generic 4096-byte source limit.

Builder run 434 installed source `01243f65b81866fd48552be6c33ab2d73752c35a` and closed the Generation-A device gate. The fixed bootstrap transaction succeeded twice on `armeabi-v7a`, reproduced the exact Generation-A ARM32/ARM64 identities, compiled both 24-byte proof sources to 96-byte target images, and executed the generated ARM32 image with status `0` / return value `42`. ARM64 execution remains deferred to compatible hardware/userspace.

## Patch 10.40 — Stage1 real-device self-host proof and freeze

Installed RiftOS source `7b57df89a22ccedd603a012e467359c8e4a718e2` (Builder run 433) exposes the fixed `riftpp-host stage1-selfhost <riftpp-root>` transaction under the live `armeabi-v7a` process.

The proof succeeded twice against the frozen `rift++` sources. The exact ARM32 Seed0 compiler identity `1725b5341e87a09943737130a945d8ee500492370da8ce648f696b331e118653` reconstructed the 340-byte ARM32 Stage1 image with SHA-256 `7b11fae1b5ad0314a6fcf1a310c57e2c10b90cb8b5b14b40c010e89aa3c3c431` and the 336-byte ARM64 Stage1 image with SHA-256 `1d5a87efb088e68ef1cec2b80c49c2a484d5e83d2c327d8131ef81e18ca9556b`.

The reconstructed ARM32 Stage1 compiler then executed natively and compiled its complete ARM32 source back to the exact same 340-byte image. The same self-hosted ARM32 Stage1 compiler compiled the complete ARM64 Stage1 source to the exact frozen 336-byte ARM64 image. Bootstrap and self outputs were byte-identical for both targets in both proof runs.

The live envelope reported `selfHostedCurrentAbi=true`, `crossTargetReproduced=true`, `hostParsesStage1Numbers=false`, and `hostEmitsStage1Instructions=false`. This establishes the first genuine self-host rung on real ARM32 hardware while preserving the rule that RiftOS is execution/proof infrastructure rather than compiler authority.

Stage1 is now frozen. ARM64 execution remains deferred to compatible hardware, but ARM64 Stage1 construction from the self-hosted ARM32 compiler is proven. All subsequent compiler/runtime development moves to the tiny real Rift++ stage; Seed0 and Stage1 no longer receive feature growth.

## Patch 10.39 — Stage1 fixed self-host bootstrap transaction

Current source adds the first post-Seed0 self-host rung without expanding the frozen 276-byte machine-code compiler.

The fixed `riftpp-host stage1-selfhost <riftpp-root>` route reads only `stage1/stage1.arm32.rpp` and `stage1/stage1.arm64.rpp`. `RiftppCompilerService` admits the exact current-ABI Seed0 compiler and exact Stage1 source identities only. The native host frames newline-terminated records but does not decode the decimal value or synthesize Stage1 instructions. Each record is compiled by Seed0, the generated current-ABI Seed0 payload is executed, and only that return value may become the next Stage1 image byte.

Candidate Stage1 identities are locked at 340 ARM32 bytes / SHA-256 `7b11fae1b5ad0314a6fcf1a310c57e2c10b90cb8b5b14b40c010e89aa3c3c431` and 336 ARM64 bytes / SHA-256 `1d5a87efb088e68ef1cec2b80c49c2a484d5e83d2c327d8131ef81e18ca9556b`. After reconstruction, the current-ABI Stage1 image executes in the same private worker and compiles both complete Stage1 sources. Promotion requires both outputs to match the exact frozen image identities and the bootstrap outputs byte-for-byte.

A dedicated 15-second Stage1 proof timeout is separate from the existing 3-second single Seed0 compile timeout. Binder death/timeout still kills or classifies only the private worker. Focused regression coverage locks the exact source/image hashes, transaction, fixed shell source paths, newline-only framing and absence of `strtol`/`strtoul` or general payload APIs.

Builder/NDK compilation and installed ARM32 self-host evidence remain pending.

## Patch 10.38 — ARM64-only seed0 proof APK lane

Current source adds a bounded standalone AArch64 proof for the new 276-byte Rift++ machine-code compiler without reviving the legacy direct-ELF compiler path.

The new `riftpp_seed0_arm64_proof` NativeActivity harness is compiled only when `ANDROID_ABI == arm64-v8a`. RiftBuild exposes `prepare-riftpp-seed0-arm64 <riftpp-root>`, validates the canonical ARM64 compiler text/raw identities, extracts the ARM64 proof host from the installed universal RiftOS APK, and materializes an arm64-only `com.riftpp.nativeproof` package containing only that host plus `assets/compiler.bin`. No ARM32 fallback is packaged.

The harness is an execution/oracle surface, not compiler authority: it contains fixed expected seed bundles but no Rift++ parser or ARM emitter. On launch it executes the exact compiler against `ret 0/1/9/42/255`, requires byte-identical 32-byte output, executes the generated ARM64 payload for each value, repeats `ret 42` for determinism, and verifies the documented malformed-input suite plus output-capacity rejection. PASS is exposed through the NativeActivity title for bounded UI-agent observation.

The proof project already passes current RiftBuild structural validation with `sourceReady=true`. Builder compilation, installation of the arm64-only APK, and actual AArch64 execution remain pending. An Android ABI-incompatibility install failure will be recorded as a current-device userspace limitation rather than a compiler failure.

## Patch 10.37 — Generated ARM32 payload live proof

Installed RiftOS source `44b7f8b2456c9403b7bc7504e92182106798a96c` exposes `riftpp-host prove` under `armeabi-v7a`. Using the exact approved 276-byte ARM32 compiler identity, the live proof path compiled the five canonical seed vectors and then executed only the 8-byte ARM32 payload generated by each same compile result inside `:riftppCompiler`.

Observed generated-payload returns were exact: `ret 0 -> 0`, `ret 1 -> 1`, `ret 9 -> 9`, `ret 42 -> 42`, and `ret 255 -> 255`, all with `generatedPayloadProofStatus=0`. The emitted 32-byte bundles retained their previously proven SHA-256 identities. An invalid `ret 01` source returned `compiler-reject` / `0xffffffff` and did not expose generated-payload proof fields, proving rejected source does not enter target execution. Temporary staging files were deleted and the workspace returned to zero diff afterward.

This closes the ARM32 generated-code execution gate for seed v0. Remaining seed-v0 native proof is the AArch64 host/compiler path, generated ARM64 payload execution, and ARM64/ARM32 cross-host byte equality. Crash/timeout fault injection also remains separate host-containment evidence.

## Patch 10.36 — Generated native payload proof route

Current source extends the existing private Rift++ compiler worker with a proof-only `riftpp-host prove` transaction. The caller still supplies only an approved compiler root and source file; it cannot supply executable payload bytes. After the exact approved compiler succeeds, the native host requires the seed-v0 32-byte result, selects the current ABI's fixed 8-byte payload from that same output (`16..23` on AArch64 or `24..31` on ARM32), copies those generated bytes into a fresh guarded mapping, transitions it RW→RX, clears the instruction cache, calls it as `u32 fn()`, records the return value, and releases the mapping. Native faults remain inside the existing `:riftppCompiler` process.

The normal `riftpp-host compile` path remains non-executing. Focused regression coverage now locks the proof transaction, both fixed ABI offsets, the generated-only dataflow, and absence of any generic caller-supplied payload execution API. Builder compilation and installed-device `prove` evidence remain pending.

## Patch 10.35 — Rift++ C1 live ARM32 compiler proof

Installed RiftOS reports exact source SHA `22827f11b472f825bbd0f59a06d5ff36f39e84ad`. The live `riftpp-host` process reports `armeabi-v7a` and executes the approved 276-byte ARM32 compiler identity `1725b5341e87a09943737130a945d8ee500492370da8ce648f696b331e118653` inside `:riftppCompiler`.

Observed device evidence:
- `ret 0`, `ret 1`, `ret 9`, `ret 42`, and `ret 255` each returned 32 bytes matching the exact documented dual-ABI bundle;
- two additional `ret 42` executions returned identical output SHA-256 `d2b545b317af8740acfc4babd03328e9ac3465488c689a352db0af3da356a76b`;
- every documented malformed source representative returned `compiler-reject` / `0xffffffff`;
- output capacity 31 returned `compiler-reject` / `0xffffffff`;
- a one-byte-tampered compiler decoded to a different SHA-256 and was rejected with `reason=compiler-identity` before native execution;
- temporary staged proof files were deleted afterward and the RiftOS workspace returned to zero source changes before this documentation update.

This proves the ARM32 compiler host artifact itself executes deterministically on real hardware and that C1 normal/rejection/identity paths are live. It does **not** yet prove generated ARM32 payload execution, crash/timeout fault containment under induced native failure, ARM64-host execution, or ARM64/ARM32 cross-host byte equality.

## Patch 10.34 — C1 source-ownership gate repair

Builder run `36433953342` on source `9bf0ca9ea22db58eaf280dccae32d9bee1154fd1` passed native wiring and transport checks, including the new Rift++ machine-code host regression, then stopped in documentation validation before Kotlin/NDK compilation. The only failures were missing ownership-ledger entries for `RiftppCompilerService.kt` and `scripts/test-riftpp-machine-code-host.mjs`.

Repair:
- add exact `SOURCE_OWNERSHIP.md` rows for both maintained files;
- assign the compiler service to Android-host/build-validation documentation;
- assign the focused regression to build-validation/Android-host documentation;
- update the corresponding subsystem ownership sections and scripts index;
- preserve the C1 implementation unchanged.

This patch changes documentation ownership only. It does not alter compiler bytes, compiler admission identities, Binder/JNI behavior, executable-memory policy, shell authority, or C1 proof claims. The next Builder run must pass the source gate before Kotlin/NDK compilation can be evaluated.

## Patch 10.33 — Rift++ machine-code compiler execution host C1

### Contract

RiftOS now has a source-implemented execution/proof host for the separately owned Rift++ machine-code compiler. RiftOS is not compiler authority and contains no Rift++ source parser or ARM emitter for this path.

### Source implementation

- `RiftppCompilerService.kt` runs in private process `:riftppCompiler`;
- Binder separates the main RiftOS process from native compiler faults;
- only an exact 276-byte compiler matching the ABI-specific approved SHA-256 may execute;
- source and output are bounded to 4096 bytes;
- a 2-second bind timeout and 3-second execution timeout are enforced by the main process;
- timeout kills the private worker; Binder death is reported as compiler-process crash;
- `riftpp_compiler_host.cpp` uses guard pages, writes compiler bytes into non-executable memory, transitions the compiler page to read+execute, clears the instruction cache, invokes the documented four-argument compiler ABI, and releases the mappings;
- `riftpp-host status|compile` is the bounded shell surface and does not accept an arbitrary executable-file path;
- `test-riftpp-machine-code-host.mjs` is wired into `check:transport` and statically rejects parser/emitter semantics appearing in the host.

### Validation boundary

Local RiftBuild structural validation reports `RiftOS-main/android` source-ready with settings, root Gradle, app Gradle, manifest and activity checks green. Audit/scan retain only the pre-existing `RiftSecretStore.kt` filename heuristic. The local RiftBuild doctor explicitly reports that general repository native compilation is not wired, so this patch is **SOURCE IMPLEMENTED / STRUCTURALLY VALIDATED ONLY**. Builder compilation, APK installation, actual `riftpp-host compile`, machine-code execution and C2 device proof remain pending.

The older QuickJS `riftpp` route and earlier VM1/App0/direct-ELF work remain historical/compatibility paths and do not define the new machine-code compiler authority.

## Patch 10.32 — Rift++ universal U0 runtime baseline

### Contract

Rift++ App0 is no longer an ARM32-only application package. U0 is locked as one universal ARM APK containing both `arm64-v8a` and `armeabi-v7a` implementations of the same VM1 runtime contract. ARM64 is canonical/default; ARM32 is compatibility. Both hosts come from one source and execute one compiled program identity.

Bootstrap0's 812-byte ARM32 machine-code VM remains historical trust/bootstrap evidence. It is not U0 application runtime data.

### Packaging

The U0 prepared package contains one binary manifest, both ABI copies of `libriftpp_app0_host.so`, and one shared `assets/program.bin`. The old `assets/vm1_seed.bin` application dependency is retired from U0. RiftBuild validates the exact ELF class/machine for each extracted host and `pack universal` requires both ABI libraries.

### Proof boundary

The existing ARM32 Hello device proof remains historical evidence. U0 promotion requires a rebuilt RiftOS APK, local universal prepare/package/sign/verify/install/launch, both ABI payloads in the signed artifact, exact Hello program identity, and observed `Hello from Rift++` through the ARM64-selected backend. ARM32 build conformance is mandatory; ARM32 hardware proof is added when a target is available.

### Source validation

The U0 semantic oracle passes with the unchanged 216-byte / 54-instruction Hello program and exact `Hello from Rift++` output while selecting `core.vm1.arm64`, `core.vm1.arm32`, and only the justified shared slices. Rift++ audit and architecture scan are clean. RiftOS audit/architecture retain only the pre-existing `RiftSecretStore.kt` filename heuristic. Native RiftBuild structural validation reports both RiftOS Android source and the Hello NativeActivity source ready. The stale ARM32-only prepared App0 tree was purged. Full NDK/Gradle compilation, signed universal-APK inspection, install and ARM64-selected device output remain the promotion boundary.

## Patch 10.31 — Hybrid PackageInstaller confirmation handoff

Observed during the first live Rift++ App0 `Hello from Rift++` install proof: self-hosted TIG0 compilation, four-slice runtime materialization, APK packaging, v2 signing and independent verification all passed, but the PackageInstaller session remained at `committed-awaiting-result` and no installer UI appeared. `launch-proof` correctly confirmed `com.riftpp.hello` was not installed.

The live Local Agent also proved that an `open` request can be accepted while Android keeps ChatGPT foregrounded, so a background RiftOS process cannot be assumed to own a visible Activity.

The 2026-09-20 receiver-only installer path had reliable `STATUS_PENDING_USER_ACTION` delivery but could not foreground the nested confirmation intent under Android background-activity restrictions. Its Activity-only successor solved that historical case, but on this device the PackageInstaller status callback was not reaching `RiftBuildInstallActivity`, leaving the session stuck before `pending-user-action` was recorded.

The installer now uses a hybrid boundary:

- PackageInstaller status callbacks target private `RiftBuildInstallReceiver` through `PendingIntent.getBroadcast(...)`;
- `STATUS_PENDING_USER_ACTION` retains a defensive copy of Android's system confirmation `Intent` in process memory;
- if a focused registered `MainActivity` exists, confirmation launches immediately from that Activity;
- otherwise `MainActivity.onResume()` and regained window focus consume the retained confirmation from a foreground Activity;
- terminal install results clear retained confirmation state;
- process death does not persist or replay the system-owned nested confirmation intent; the install must be retried;
- `USER_ACTION_REQUIRED`, verified-v2 input, package allowlisting and explicit Android confirmation remain unchanged.

`test-riftbuild-native.mjs` now rejects regression to Activity-only callback delivery and requires the MainActivity resume bridge. Full proof requires the next rebuilt RiftOS APK followed by the same already-proven App0 artifact chain.

## Patch 10.30 — Engineering-capable Local Agent batch + App0 ARM64 build fix

### Batch architecture

`rift_local_agent_batch` remains the only active batch authority, but its step surface is expanded from UI-only work into a bounded engineering control plane.

Engineering steps reuse `RiftToolHost -> RiftToolSandbox` rather than duplicating filesystem logic. Direct batch operations now include Code Mode project/snapshot/stat/hash/list/search/grep/symbol/reference/read/write/replace/patch/move/copy/archive/extract operations plus audit, scan, project export, workspace diff and passive debug queries. `grep` is an alias of bounded Code Mode text search and does not invoke a shell process.

Each step may carry an `args` object; normalization flattens those arguments before plan hashing, validation and execution while rejecting argument collisions. The existing 16-step plan limit, per-step/plan byte limits, exact requestId-to-plan binding, persistence, paging, cancellation and restart no-replay behavior remain in force.

Read-only engineering plans require read permission only. Workspace-mutating steps and UI/device-mutating steps require both read and write; cancellation also requires both. Mutation classification occurs before submit acceptance.

Raw RiftShell batching remains disabled. No process or network authority is added to Local Agent batch.

### Builder failure repair

Builder run `36376407884` on source `d42f329c1c476237d5caaad6c6432153097a3eaa` failed while compiling `riftpp_app0_host.cpp` for ARM64 because the App0 VM helpers are ARM32-only and `-Werror` treated the resulting ARM64 unused constants/functions as fatal. The repair keeps `kOutputBytes`/reporting ABI-neutral and compiles the VM asset constants, VM context, executable mapping and VM helper functions only under `#if defined(__arm__)`. Warnings remain strict; the fix does not weaken CMake flags.

### Validation/status

`test-rift-local-agent-batch.mjs` now locks the engineering operation family, ToolHost/Sandbox reuse, nested-args normalization, permission classification and continued raw-shell separation. `test-riftbuild-native.mjs` historically locked the then-ARM32-only App0 helper boundary; Patch 10.32 supersedes that application-runtime restriction with universal U0. Local Rift audit and architecture scan report no new findings beyond the pre-existing `RiftSecretStore.kt` filename heuristic. Full Node/Gradle/NDK Builder proof remains pending the next Builder run.

## Patch 10.29 — Persistent MCP lost-turn reconciliation and no-replay identity

### Problem

A ChatGPT/mobile UI turn can freeze or disconnect after RiftOS has already accepted an MCP operation. Device-side work may still finish, while the next model turn no longer has trustworthy knowledge of the completed downstream state. The old relay pending map and MCP completed-response cache were process/transport memory only, and workspace provenance used a generated ToolHost request id rather than the originating MCP operation identity.

### Architecture

Current source adds one process-owned, device-authoritative `RiftMcpOperationJournal`. It persists a bounded AtomicFile journal of up to 256 operations with a monotonic sequence, stable operation/request identity, canonical request hash, tool/mutation classification, execution status, bounded terminal summary and response-delivery state. Raw MCP arguments and payload bodies are not stored.

`RiftMcpServer -> RiftToolHost -> RiftToolSandbox -> RiftPatchSessions -> RiftWorkspaceRecords` now propagates the same `mcp-...` operation id for journaled workspace mutations. `rift_mcp_reconcile` reads recent or since-sequence journal state and attaches matching retained Workspace Records evidence by operation id.

### Lost response and restart semantics

Execution completion is authoritative independently of transport delivery. The relay client records only `queued_to_relay`, `response_not_delivered`, or `unknown`; it never claims the ChatGPT UI displayed or acknowledged a result.

A retained terminal request identity is never replayed. After Android process restart, any journal entry that had still been `running` becomes `interrupted_on_restart` with explicit may-have-applied semantics. A repeated retained terminal request returns a recovered no-replay summary and directs callers to reconciliation evidence instead of executing the mutation again.

### Validation/status

The exact Android Kotlin source snapshot now includes `RiftMcpOperationJournal.kt`, and the normal `npm run check` source gate includes `test-rift-mcp-operation-journal.mjs`. Riftos-builder source-gate syntax/contract checks were synchronized to require that regression before packaging. The focused regression locks persistence/bounds, stable identity propagation across Code Mode/direct-file/native-shell mutation provenance, restart no-replay, reconciliation evidence and truthful transport-delivery semantics.

Native `riftbuild validate android` reports `sourceReady=true` with all structural Android checks passing. Post-change RiftOS audit plus architecture/runtime scans report no new findings; only the pre-existing medium filename heuristic on `RiftSecretStore.kt` remains, and the Builder audit is clean. Native RiftShell intentionally does not expose raw `node`, so the focused Node regression could not be executed directly in this local session; full Android Gradle compile/install proof remains pending the normal Builder run.

## Patch 10.28 — Direct Local Agent batching restored; RiftCLI batching retired

### Architecture

Batch ownership is now direct and local:

`ChatGPT / MCP -> RiftOS Local Agent -> bounded Local Agent batch executor -> normal fixed-scope Local Agent operations`.

`rift_local_agent_batch` is the only active batch authority. It prevalidates 1..16 fixed-scope steps before execution, binds each retained `requestId` to the exact normalized plan, persists bounded job state/results with `AtomicFile` + fsync, supports status/result/cancel/list, pages retained results, and never replays unfinished jobs after process restart.

A process-local `RiftLocalAgentExecutionGate` now reserves the RiftOS Local Agent authority for the whole batch. Standalone `riftos-agent` work and the shell Dev Lab shortcut fail closed while that lease is held; batch steps must present the active job owner ID.

### Truthful failure and restart semantics

Cancellation after a completed step is reported as `cancelled_may_have_applied`. Stop-on-error and unexpected post-execution failures use `failed_may_have_applied` rather than implying rollback. Persistent-store loading is fail-closed: malformed state does not partially become authoritative, nonterminal jobs recover as `interrupted_on_restart`, and no UI action is replayed automatically.

### RiftCLI retirement boundary

Historical N1.6 Batch V2 proof is preserved as history only. Current RiftCLI reports `batchV2=false`, `batchV2MaxSteps=0`, and `batchOwner=riftos-local-agent`; its `rift_cli_batch` dispatcher fails closed. The retired RiftShell `batch` path and multi-operation workspace batching remain disabled. Existing private Batch V2 helper code is unreachable from the current dispatcher and is guarded by retirement regressions.

### Validation and status

Local source-contract assertions passed 35/35, covering the Local Agent lease, exact-plan idempotency, persistence/no-replay behavior, failure states, MCP publication, CLI retirement, documentation alignment and Gradle inclusion. `riftbuild validate android` reports `sourceReady=true` with all structural checks passing. Rift audit/runtime scan found no batch-related findings; the only reported item is the pre-existing medium filename heuristic for `RiftSecretStore.kt`.

Installed-device proof is now **partial and current** on source `e4d32aa87d82840ea0d64e5622b5f647e27b3b55`, Builder run `36271037740` / run #406. The live MCP manifest reports 20 tools and includes `rift_local_agent_batch`; current RiftCLI reports `batchV2=false`, `batchV2MaxSteps=0`, and `batchOwner=riftos-local-agent`, while `rift-cli batch` fails closed. Live batch tests proved queued→completed execution, status/result/list, exact-plan requestId deduplication, same-requestId/different-plan rejection, the 16-step ceiling, 4-result paging, `continue` after a failed step, stop-on-error with `failed_may_have_applied`, and terminal cancel idempotence. A force-stop/reopen attempt did not catch a job while still nonterminal because the test batch completed before process death, so **restart/no-replay remains source-locked but not yet installed-device proven**. True in-flight cancellation likewise remains pending live proof.

## Patch 10.27 — N1.7 deterministic SSE lifecycle

### Live stress finding

External browser stress on 2026-09-21 proved the relay's separate eight-client SSE ceiling and fail-closed backpressure path, but also proved backpressure cannot be the primary stale-client detector. Eight browser streams were accepted, the ninth was correctly rejected, and closing all visible tabs left all eight Durable Object entries registered. Event pressure evicted six through `backpressureDropped`, while two idle streams remained because the outer Worker/Chrome path continued to appear writable.

The failure had three concrete causes. Direct browser SSE requests carried no stable `Mcp-Session-Id`, so same-session replacement and explicit close could not identify them. The Worker wrapped the Durable Object response in a second `ReadableStream`, introducing another buffering boundary between the room's `desiredSize` and the real browser connection. The Cloudflare Worker configuration also did not explicitly enable incoming `Request.signal` cancellation, and there was no absolute lease to guarantee eventual cleanup if abort and backpressure both failed.

### Source repair

Production SSE now requires a stable `Mcp-Session-Id`. Manual browser diagnostics may instead supply a validated `?subscriber=<id>` value limited to 128 characters from `[A-Za-z0-9._:-]`; the Worker prefixes it internally as `diag:<id>` so diagnostic identities cannot silently become anonymous streams. Anonymous SSE opens fail closed and increment `anonymousRejected`.

The outer Worker now returns the Durable Object SSE response directly instead of copying it through `proxySseResponse`. `relay/wrangler.jsonc` explicitly enables `enable_request_signal` and `request_signal_passthrough`, allowing client cancellation to propagate to the request signal used by the room.

Every SSE client also receives an absolute 180-second lease plus up to 30 seconds of jitter. Lease expiry removes the client, clears heartbeat/lease timers and abort listeners, closes the stream, increments `leaseExpired`, and relies on `Last-Event-ID` for cursor-safe reconnect. The existing byte backpressure and two-heartbeat no-drain checks remain secondary memory/liveness protection.

No Durable Object storage, event payload persistence or offline queue was added.

### Validation and status

`test-rift-cli-push-channel.mjs` and `validate-rift-transport.mjs` now require the stable identity, lease cleanup, direct-streaming contract and Cloudflare request-signal flags. This patch is source-complete pending Builder validation and live Worker deployment. N1.7 remains unpromoted until external browser re-test proves closed streams return `sseClients` to zero without event pressure and the subsequent Android force-stop/reopen test proves restart recovery from the persisted ACK cursor.

## Patch 10.26 — N1.7 force-stop cursor durability and stale-SSE cleanup

### Live stress finding

Installed-device stress on 2026-09-21 against source `a2b29555445fdcc7ea2ad228f98fd8a1af5a6833` proved the first relay cursor-recovery repair live. Manual relay replacement resumed from the exact current ACK high-water instead of zero, replay count stayed zero, five consecutive replacements recovered on the next MCP call, five 16-wide MCP waves completed 80/80, and device replacement did not strand seven simultaneous scans. External SSE cap testing opened eight browser streams and correctly rejected the ninth with `Too many MCP SSE subscribers`.

Closing all eight browser tabs exposed a separate lifecycle leak: after multiple heartbeat windows, the Durable Object still reported eight SSE clients because Chrome/Cloudflare had not propagated abort/cancel. Deliberate ~37 KiB CLI event pressure then evicted six stale streams through `backpressureDropped`, proving the 512 KiB fail-closed queue path works live, while two idle streams remained registered because they continued to appear drainable from the Worker side.

A pre-force-stop audit also found that Android `lastCliAckSequence` was RAM-only. Ordinary relay reconnect was fixed, but a true Android process death could still reset `device.hello.cliAckSequence` to zero if the Durable Object also lost its in-memory cursor while the phone was offline.

### Source repair

`RiftRelaySettings` now stores the highest relay-ACKed CLI sequence as a monotonic app-private `Long`. `RiftMcpRelayClient` restores that value at construction and synchronously persists each newly advanced ACK before a later force-stop can erase the process copy. The persisted value contains no token, payload or event body.

The Worker now records SSE queue `desiredSize` progress across heartbeats. A client with queued bytes and no forward drain progress across two consecutive heartbeat observations is evicted through the existing backpressure cleanup path. Existing byte-cap protection remains unchanged, so either a full queue or a no-drain stream fails closed instead of living indefinitely.

No Durable Object storage or event-payload persistence was added. Focused and broad transport validators now require the persisted Android ACK cursor plus the no-drain heartbeat contract.

### Validation and status

These changes are source-complete pending Builder validation, APK installation and Worker deployment. N1.7 remains unpromoted until a new live pass proves: force-stop/reopen restores the persisted ACK cursor without a zero replay, closed browser SSE tabs age out automatically without event pressure, and subscriber count returns to zero. Existing live proof already covers the eight-client SSE ceiling, ninth-client rejection, real backpressure eviction, repeated relay replacement, concurrency and transport size bounds.

## Patch 10.25 — N1.7 relay cursor recovery hardening

### Live stress finding

Installed-device relay torture testing on 2026-09-21 against source `6d21cd5fd2d9ef2331c8ec42a8654d7de07dd31f` exercised concurrent MCP bursts, repeated relay replacement, CLI disable/re-enable, health-counter cleanup and device-to-relay CLI event ACKs. Normal reconnects recovered on the next MCP call and preserved the current resume cursor. One `EOFException` recovery path exposed a real transport defect: after the device WebSocket attachment and room in-memory cursor were no longer available, the next `relay.ready` returned `cliResumeAfter=0`. The still-running Android process retained its device-owned event ring and replayed 29 already-ACKed events. Cloudflare ACKed those duplicates as non-advancing, so CLI authority did not re-execute, but the reconnect produced unnecessary replay traffic and proved that the relay high-water mark was not fully recoverable from a dead device socket alone.

### Source repair

`RiftMcpRelayClient` now includes its process-local `lastCliAckSequence` in authenticated `device.hello` as `cliAckSequence`. `RiftRelayRoom` validates that value as a non-negative safe integer, monotonically merges it into `lastCliSequence`, refreshes the current device WebSocket attachment, and only then calculates `relay.ready.cliResumeAfter`. Active subscriber cursors can still lower the requested replay point when older events are genuinely required.

The fix deliberately adds no Durable Object storage and persists no event payloads. `test-rift-cli-push-channel.mjs` and `validate-rift-transport.mjs` now require the device-owned ACK handshake so a future relay rewrite cannot silently regress to attachment-only cursor recovery.

### Validation and status

This patch is source-complete only until Builder validation, APK installation and Worker deployment. N1.7 remains unpromoted. The live re-test must reproduce EOF/socket replacement recovery and confirm that the reconnect resumes from the highest ACKed device cursor instead of zero. Slow-SSE/backpressure, SSE subscriber-cap saturation and a true Android process force-stop/restart remain separate installed-device proofs.

## Patch 10.24 — RiftBrowser bounded editor bridge

### Source change

Extended the existing active-page RiftBrowser inspector so RiftOS can edit browser-hosted code/text editors without adding arbitrary JavaScript execution or a second WebView owner. The new `edit` action accepts only non-sensitive text-like inputs, textareas and contenteditable surfaces, uses native value setters plus input/change events for framework-backed controls, records the original value/text for inspector reset, and rejects password plus password/secret/token/API-key/authorization-like controls.

RiftShell exposes `riftos-agent browser-inspect edit <selector> <text>` and `edit-b64 <selector> <base64-utf8>`. `edit-b64` preserves complete source text across shell parsing; the Android bridge decodes canonical UTF-8 and enforces a 256 KiB payload ceiling. No submit/deploy click authority, cookies, storage, headers, innerHTML, control-value readback or arbitrary page script execution was added.

### Validation and status

`validate-rift-wiring.mjs` now requires the bounded editor bridge, Base64 transport, secret-field guard and shell commands so this capability cannot silently disappear. Browser and Local Agent subsystem docs describe the new contract. This patch is source-complete only until Builder validation and installation of the resulting APK; after install, the intended acceptance test is to inspect an active HTTPS code editor, edit a disposable/non-secret field or source buffer, reset it, then use the same bridge against the Cloudflare Worker editor before any explicit deploy click.

## Patch 10.23 — N1.7 zero-poll steady-state contract lock

### Source hardening

Promoted push-first observation from a documented preference into an explicit native RiftCLI contract without removing recovery controls. The C++ status/architecture surfaces now advertise `driverObservationMode=persistent-push-steady-state`, `automaticPolling=false` and `pollFallbackOnly=true` alongside the existing persistent relay push/replay fields.

No automatic polling loop existed in the runtime before this patch: `rift_cli_job_poll` was already invoked only through the explicit external job-control route. This patch locks that structure with regression coverage so a future internal poll loop cannot be introduced silently. `rift_cli_job_list` and `rift_cli_job_poll` remain explicit recovery/debug fallbacks, while `rift_cli_job_cancel` remains an explicit control surface.

### Validation and status

`test-rift-cli-driver-protocol.mjs` now requires the zero-poll contract fields and verifies the bounded poll call-site structure: one shell poll helper definition plus its explicit control call, one ToolHost poll helper definition, and one explicit ToolHost poll call. `validate-rift-wiring.mjs` also requires the new native contract markers.

RiftCLI remained OFF while this source hardening was made. The change is source-complete only until the external Builder runs the Node/Android validation chain and an updated APK is installed. N1.7 is not promoted by this patch; forced restart, repeated reconnect, backpressure/subscriber caps, large-result fallback, cancellation/no-interleave, concurrent-driver pressure and broader bounds remain pending.

## Patch 10.22 — N1.5 persistent SSE push/replay live promotion

### Installed-device proof

Promoted RiftCLI N1.5 on the installed Android build from source `0814fb8cf8ca31186d6e639fc7ad3b965d822897` after completing the missing external-subscriber proof against the live `rift-mcp-relay` Cloudflare Worker/Durable Object.

The live `/health` surface reported `deviceConnected`, `driverSockets`, `sseClients` and `cliSequence`. With an external Chrome SSE subscriber attached (`sseClients: 1`), a read-only RiftCLI `version` job emitted `job.submitted`, `job.started` and `job.completed`. RiftDebugHub independently recorded matching `riftcli.event-bus/event.created`, `mcp.relay/cli.event.send`=`queued`, and `mcp.relay/cli.ack`=`received` records for the same event sequences. The Chrome subscriber received those exact sequences and the inline terminal result without job polling.

### Reconnect/replay proof

The SSE client was fully disconnected until `/health` reported `sseClients: 0`. A second read-only `version` job then created a three-event gap while no SSE subscriber existed. Reconnecting with the previous `after` cursor produced the `notifications/riftcli/ready` event followed by exactly the three missed lifecycle sequences, in order, with no replay of the cursor event and no older duplicate.

### Promotion result

N1.5 persistent push/events is therefore live-proven for basic external SSE delivery, device-to-relay ACK correlation, cursor reconnect and duplicate-free missed-event replay. Poll/list/cancel remain recovery/debug fallbacks. Large-result fallback, forced relay/device restart abuse, repeated reconnect pressure, slow-subscriber/backpressure behavior, subscriber caps, cancellation/no-interleave and broader bounds remain N1.7 stress work. RiftCLI was returned to its default OFF state after proof.

This promotion updates documentation/status only; it does not change runtime authority, relay secrets, Durable Object bindings or CLI enable defaults.

## Patch 10.21 — N1.5 Builder validation closure

### Failure reproduced

Public Builder run `35542173887` for RiftOS source `c9e7852661840aaaee90a760d2737269455347eb` failed before product validation because `scripts/validate-rift-wiring.mjs` contained literal `\\n` characters inside the N1.5 runtime-wiring condition. Node rejected the validator itself with a syntax error.

### Source repair

Repaired the malformed condition and extended `validate-rift-wiring.mjs` so N1.5 wiring now also requires the passive DebugHub component/hooks:

- `riftcli.event-bus` + `event.created`;
- `mcp.relay`;
- `cli.event.send`;
- `relay.ready`;
- `cli.replay.request`;
- `cli.replay.send`;
- `cli.ack`.

The focused `test-rift-cli-push-channel.mjs` and `test-rift-debug-hub.mjs` remain part of `npm run check`.

### External Builder hardening

The public Builder now independently `node --check`s the critical source-gate entrypoints before running the source-owned validator. It also fails if `package.json` no longer routes wiring, transport, docs, RiftCLI push, Batch V2 or DebugHub checks through `npm run check`.

The final signed-APK verifier now requires DEX to contain the N1.5 diagnostic component/operation markers in addition to the mandatory Kotlin class descriptors. This proves the specific event/relay instrumentation survived compilation rather than only proving its owner classes exist.

No installed-device behavior is claimed by these source/Builder checks. N1.5 still requires the green artifact, install, DebugHub device-to-relay ACK proof and final external subscriber push proof.

## Patch 10.20 — RiftCLI N2 federated memory roadmap freeze

### Roadmap/documentation changes only

Froze the full pre-N3 RiftCLI N2 memory program in `docs/systems/riftmemory/N2_FEDERATED_MEMORY_ROADMAP.md` without claiming any new runtime capability.

The frozen architecture requires one canonical Rift Memory Kernel and one canonical evidence/event/transaction/reconciliation authority. Specialized temporal/graph, episodic, consolidation, semantic, belief/reflection, skill/procedural, failure, causal, commitment and predictive engines operate as rebuildable cognitive views over canonical IDs rather than independent sources of truth.

N2 starts with a replaceable SQLite reference `MemoryStore`. RiftStore is an experimental backend that may replace SQLite responsibilities only when identical benchmark workloads show enough correctness/resource/performance benefit to justify the added complexity. Logical JSON-shaped schemas remain independent from physical storage encoding.

The roadmap freezes evidence-vs-belief separation, bi-temporal history, protected policy/authority, governed memory transactions, Observer/Validator reconciliation, Difference/Surprise handling, NO/FAST/DEEP/FORENSIC retrieval modes, multi-index fusion, Context Compiler, speculative branch isolation, fsck/snapshot/replay/rollback, poisoning defenses, crash consistency, scale gates, public/private benchmark suites, specialist metrics, incremental hybrid benchmarks and ablations.

Implementation is split into N2.0 through N2.12. N3 is explicitly blocked until N2.12 promotion proves the mandatory weakest-link categories and bounded Android resource behavior. Happy-path demos or strong average benchmark scores cannot waive a failing critical category.

Updated `ROADMAP.md`, RiftCLI docs, RiftMemory classification, project status and docs index to point at the frozen N2 program while preserving `src/riftmemory-control.js` as inactive retained reference source. Extended `scripts/validate-rift-docs.mjs` so Builder/documentation validation fails if the N2 roadmap, SQLite/RiftStore storage split or hard N3 barrier disappears.

## Patch 10.19 — N1.5 passive relay/event observability

### Current source changes

Connected the existing process-wide RiftDebugHub to the RiftCLI persistent-push path without placing the debugger in the execution or authority path.

`RiftCliEventBus` now emits bounded `event.created` metadata through component `riftcli.event-bus`. `RiftMcpRelayClient` now emits bounded metadata through component `mcp.relay` for socket connect/open/close/failure/reconnect, `relay.ready`, CLI event queue attempts, replay request/send and `cli.ack` receipt.

The trace deliberately separates three evidence boundaries: local event creation, local OkHttp WebSocket queue acceptance, and Cloudflare relay acknowledgement. A matching `cli.ack` proves the relay received that event sequence; it does not by itself prove an external SSE/WebSocket subscriber consumed the event.

Diagnostics are metadata-only. They do not include MCP payload bodies, CLI result bodies, relay endpoint URLs, Authorization headers or pairing tokens. DebugHub remains passive/read-only and owns no network, execution, mutation or cancellation authority. Event-bus and relay diagnostic emission is wrapped in fail-isolation so an unexpected debugger exception cannot block CLI event delivery, socket handling or replay.

Updated the N1.5 push/debug regression locks, debugger/relay/RiftCLI documentation, ownership ledger, roadmap and project status. N1.6 Batch V2 status is corrected to live-proven on Builder run #259 / source `eaa2a390438784be435929e49283f9e6281b8ed0`; N1.5 still requires the final external push-receipt proof after this instrumentation is built and installed.

## Patch 10.18 — Retired RiftShell batch regression narrowed for Batch V2

### Current source changes

Builder run `35537848556` on source `508f6fbe175d3af34282ceb047af00e23b0d4e62` passed RiftCLI N1.5 persistent push, RiftCLI N1.6 Batch V2, transport, wiring and documentation validation, then failed in the legacy retired-batch regression.

The old test rejected any occurrence of the literal `"batch"` inside `RiftNativeShell.kt`. That became stale once the new, separately-authorized `rift_cli_batch` Batch V2 path was added. The regression now rejects the actual retired native RiftShell command dispatch pattern (`"batch" ->`) instead.

The old RiftShell batch command remains disabled. This patch changes the test only; Batch V2 runtime behavior and authority are unchanged.

## Patch 10.17 — RiftCLI N1.5/N1.6 source ownership ledger repair

### Current source changes

Builder run `35537600791` on source `f31f9505e3e3edc8302caa516626484c2678bc27` passed native wiring, the 19-tool MCP surface, relay/transport validation, RiftCLI persistent-push validation and RiftCLI Batch V2 validation. It then stopped in documentation validation because three newly maintained N1.5/N1.6 sources had no documentation ownership rows.

Added exact ownership entries for:
- `android/app/src/main/java/com/riftos/app/RiftCliEventBus.kt`;
- `scripts/test-rift-cli-push-channel.mjs`;
- `scripts/test-rift-cli-batch-v2.mjs`.

The entries point to the existing RiftCLI, relay and build-validation documentation that already describes those sources. Runtime behavior, MCP configuration, relay identity and authority boundaries are unchanged.

## Patch 10.16 — RiftCLI pre-N2 Builder validator diagnostics hardening

### Current source changes

Builder run `35537136265` on source `5f6e951760194cff41a93836b72841ba34a4542d` stopped in `validate-rift-wiring.mjs` at the combined RiftCLI N1/N1.5/N1.6 core-contract assertion. Direct inspection of the pushed source proved every individual fragment in that combined assertion was present.

The validator now checks the same contract as individually named requirements instead of collapsing roughly twenty independent conditions into one generic “boundary drifted” failure. Replay protection still separately fails if `g_recentRequestIds.clear()` returns.

This changes validation diagnostics only; RiftCLI authority, relay configuration, MCP endpoint/tool surface, Batch V2 behavior and Android runtime code are unchanged by this patch.

## Patch 10.15 — RiftDebugHub passive global debugger foundation

### Current source changes

Added the first process-wide global debugger layer without placing it in the execution path:

- `RiftDebugHub.kt` owns bounded in-memory spans/events, monotonic durations, trace correlation, active-span visibility, capacity counters and secret-key redaction;
- `RiftDebugAdapter` + `RiftDebugSink` provide the reusable subsystem plug;
- `RiftMcpRuntime` owns one hub per Android process;
- `RiftMcpServer` creates the parent tool-call span, forwards its context and returns `riftos/traceId`;
- `RiftToolHost` creates the child span and exposes one read-only `rift_debug` tool with status/events/active/components actions;
- the MCP catalog is now 19 tools;
- the exact Gradle Kotlin source snapshot is now 46 files;
- focused regression, transport validator, ownership ledger and subsystem documentation were updated together.

Authority remains unchanged: the hub cannot execute, mutate, cancel, read files, access the network, host a model, grant permission or enable RiftCLI. RiftShell batch remains disabled. Current proof is source/static-validation level; APK compilation and installed-device behavior remain the next build gate.

## Patch 10.14 — Semnexis SNIRV7 Arena AST + bounded recursion pressure loop

### Current source changes

Continued the Semnexis 0.7 self-hosting pressure loop from parser-state records into native AST storage and recursive parsing.

Added and verified:
- a first-class borrowed `Arena` state descriptor with fixed 16-byte flat-record cells;
- typed `arena_store(arena,index,record)` and `arena_load<Record>(arena,index)` with descriptor/index/capacity/data-pointer validation;
- derived `state` effects for Arena reads/writes and additive `SNIRV7`, while SNIRV0–SNIRV6 remain frozen compatibility surfaces and reject newer semantics;
- ARM32 Arena load/store lowering plus canonical machine-image verification and independent machine execution over seeded memory;
- flattened record parameter ABI beyond r0-r3 using aligned caller stack words, including parser-state records;
- bounded direct and mutual native recursion. Only functions participating in recursive call cycles receive the backend-private `r11` depth guard; frame 256 succeeds and frame 257 traps through the canonical runtime trap;
- canonical verifier checks for the actual recursion-guard instruction sequence and trap branch target, not metadata alone;
- recursive-descent parsing of `1+(2+3)` into a five-node Arena-backed AST followed by recursive AST evaluation to `6`;
- permanent Semnexis self-host probe `parser_recursive_arena_probe.snx` plus its QuickJS runner;
- embedded source `semx self-test` promoted to `semnexis-bootstrap-self-test/17`, reporting SNIRV7 Arena-read, parser-state stack ABI, record-loop-yield and bounded-recursion proof metrics;
- RiftOS/Builder documentation parity updated for the third packaged headless asset `src/semnexis-bootstrap.js`.

The installed APK still exposes older `semx` wiring until the next RiftOS build/install. Current 0.7 SNIRV7/Arena/recursion work is source + independent-machine-regression verified; APK/device promotion remains a separate gate.

## Patch 10.13 — Semnexis 0.7 record/parser pressure loop

### Current source changes

Continued the self-hosting pressure loop by compiling increasingly real lexer/parser kernels and adding only the general capabilities those kernels exposed.

Added and verified:
- flat immutable records (up to four scalar fields), `SNIRV3`, deterministic aggregate stack slots and r0-r3 record returns/calls;
- record field projection through `record.get` and frozen additive `SNIRV4`;
- record-valued conditionals and explicit-state record loop values through `phi.record`, additive `SNIRV5`, and the existing cycle-safe parallel phi edge-copy resolver;
- generic CFG return handling for `ret.i32`, `ret.u8` and `ret.record`;
- post-definition phi type verification, including backedge-safe record phi validation;
- variable-width numeric token spans over borrowed `Slice<u8>` source;
- a native streaming parser-state kernel that accepts valid `digit + digit` forms and rejects incomplete/extra/wrong-operator forms;
- verified zero-extension from semantic `u8` to `i32` through `zext.u8.i32` and additive `SNIRV6`;
- native decimal accumulation (`1234` -> integer `1234`) using checked arithmetic;
- independent ARM32 execution regressions for record returns/calls/projection, record branch/loop state, variable-width token spans, parser state and numeric widening;
- embedded `semx self-test/13` proof for the V6 binary/runtime path;
- Builder wiring guards for SNIRV6, zext and `/13` host proof.

Compatibility remains additive and fail-closed: V0-V5 remain explicit encoders/decoders, and older formats reject newer semantics rather than silently reinterpreting them.

0.6 remains installed-device verified. The 0.7 lexer/parser/V6 work is source + independent-machine-regression verified and awaits the `/13` APK/device promotion gate.

## Patch 10.12 — Semnexis 0.7 self-hosting byte/slice pressure loop

### Current source changes

Semnexis advanced to `0.7.0-quickjs-bootstrap` by feeding real lexer requirements back into the language instead of predesigning unrelated features.

Added:
- real `u8` parameters/returns/literals with zero-extended 32-bit register representation;
- frozen `SNIRV0` preservation plus additive `SNIRV1` for `u8` IR;
- bounded generic type-reference parsing (`Name<T,...>`, maximum nesting 16);
- read-only borrowed `Slice<u8>` values with a fixed descriptor-pointer ABI;
- pure `slice_len` and bounds-checked `slice_get` intrinsics;
- additive `SNIRV2` for slice values/ops while V0/V1 reject newer value kinds;
- ARM32 word/byte loads and fail-closed descriptor/index/length/data-pointer checks;
- independent ARM32 execution over seeded external descriptor/data memory;
- a real native scanner kernel that walks `a1b23!` through `Slice<u8>` and returns digit count `3`;
- exact embedded `semx self-test/8` proof for SNIRV2 + canonical slice ARM32 lowering;
- Builder wiring guards for SNIRV2, slice IR/backend markers and `/8` host proof.

Compatibility remains explicit: i32-only IR auto-encodes as `SNIRV0`, `u8` IR as `SNIRV1`, and borrowed-slice IR as `SNIRV2`. Existing 0.6 smoke/conditional/loop binary sizes remain frozen.

0.6 remains device-verified on `1d743b6fda2f5e7f1085186bc4bf6e9bbbd3ef36`. The 0.7 self-hosting slice is source + independent-machine-regression verified and requires the next APK/device `/8` gate.

## Patch 10.11 — Semnexis 0.6.1 hardening gate

### Current source changes

Semnexis advanced to `0.6.1-quickjs-bootstrap` without adding language feature surface.

Hardening includes:
- effect/capability re-derivation from IR instructions and call graph;
- canonical IR-bound ARM32 machine verification;
- independent ARM32 machine-execution regression in `npm run check`;
- parallel-copy resolution for cyclic phi edges;
- bounded source/token/AST/graph/CFG/artifact/output resources;
- iterative call-cycle analysis;
- lazy bounded graph/plan/IR dumps;
- frozen `SNIRV0` V0 version/flags/opcode compatibility tests;
- advisory-only graph-node correlation semantics made explicit;
- exact embedded `semx self-test/7` execution in Builder;
- Semnexis-specific host source limit and pre-allocation binary payload checks.

0.6 remains device-verified on `1d743b6fda2f5e7f1085186bc4bf6e9bbbd3ef36`. 0.6.1 requires a new APK/device gate.

## Patch 10.10 — Explicit CFG, phi merges and explicit-state loops

### Current source changes

Semnexis advanced to `0.6.0-quickjs-bootstrap`.

Added:
- signed comparison syntax `== != < <= > >=`;
- expression-oriented `if ... { ... } else { ... }`;
- internal control-flow `bool` facts;
- explicit Native IR basic blocks and branch terminators;
- `phi.i32` merge semantics;
- CFG reachability/predecessor/dominator verification;
- `SNIRV0` serialization for blocks, branches and phi incoming edges;
- ARM signed conditional branches and named block relocation;
- conservative `cfg-spill-v0` for control-flow functions while straight-line functions retain the proven `linear-scan-r4-r7-v0` allocator.

Added explicit-state loops:

`loop (state = initial, ...) while condition { next (nextState, ...); } yield value`

Loop state is represented semantically, not as hidden mutable locals. Header phis receive preheader and backedge inputs; all next-state values are simultaneous.

### Source proof

- all 0.5 straight-line graph/plan/IR/arithmetic sizes remain unchanged;
- six signed comparison predicates verified against named ARM targets;
- conditional: 674-byte `SNIRV0`, 340-byte ELF, 4 blocks;
- nested conditional: 7 blocks / 444-byte ELF;
- loop: 756-byte `SNIRV0`, 368-byte ELF, 4 blocks;
- loop preheader and backedge each perform two phi edge copies;
- loop emits a real backward ARM branch to its header;
- invalid conditions, chained comparisons, malformed next-state arity, state shadowing and duplicate loop-state names are rejected.

Device proof requires the next APK.

## Patch 10.9 — Full checked i32 ARM32 arithmetic + liveness allocation

### Current source changes

Semnexis advanced to `0.5.0-quickjs-bootstrap`.

The ARM32 runtime backend now includes:
- linear-scan live-range allocation into callee-saved `r4-r7`;
- deterministic stack spills only when live-range pressure exceeds four value registers;
- checked runtime multiply using `SMULL` plus high/sign-extension verification;
- checked runtime divide using a shared software divider instead of optional ARM `SDIV`;
- divide-by-zero and `INT32_MIN / -1` overflow trapping;
- complete checked runtime `i32` add/sub/mul/div coverage.

The register-allocated add/call fixture shrank from 268 bytes to 192 bytes with zero spill frame. A forced-pressure fixture proves deterministic spilling. The full arithmetic fixture emits a 1016-byte ELF with a 716-byte shared divider and one spill slot.

The software divide algorithm matched signed-`i32` reference semantics across 417 deterministic boundary/stress cases. The full ELF was independently disassembled as ARMv7 and confirmed the intended arithmetic, call and branch instructions.

Device proof requires the next RiftOS APK.

## Patch 10.8 — Runtime-valued ARM32 lowering

### Current source changes

Semnexis bootstrap compiler advanced to `0.4.0-quickjs-bootstrap`.

Added `SEMNEXIS_ARM32_RUNTIME_ELF_V0`, a direct runtime-valued ARM32 backend that consumes verified Native IR without whole-program constant evaluation.

Current lowering:
- deterministic stack slot per SSA value;
- 8-byte-aligned frames;
- `r0-r3` parameter and call-argument ABI;
- `BL` function calls;
- `r0` returns;
- `MOVW/MOVT` constants;
- runtime copies;
- checked add/sub using `ADDS/SUBS` plus `BVS` to a shared overflow trap;
- nested calls;
- fail-closed rejection for multiply/divide/effects/recursion until those lowerings exist.

The runtime fixture emits a 268-byte ELF32/EM_ARM image with `constantEvaluated=false` and `runtimeLowered=true`.

Fixed runtime artifact output:
- `/documents/builds/Semnexis/semx-arm32-runtime.elf`

Generated writable artifacts remain non-executable by RiftOS policy. Device proof requires the next APK.

## Patch 10.7 — Semnexis Native IR V0 + direct ARM32 backend seed

### Current source changes

Extended the QuickJS-hosted Semnexis compiler to `0.3.0-quickjs-bootstrap`.

Added:
- typed SSA-like `SEMNEXIS_NATIVE_IR_V0`;
- deterministic IR verifier and textual dump;
- checked signed-i32 add/sub/mul/div semantics;
- canonical binary IR format `SNIRV0`;
- binary decode + source-independent re-verification;
- `semx dump-ir`;
- direct pure-program ARM32 backend proof;
- deterministic ELF32/EM_ARM image verifier;
- fixed `semx emit-arm32-proof` output at `/documents/builds/Semnexis/semx-arm32-proof.elf`.

The ARM32 proof backend is intentionally narrow: pure/capability-free, zero-input V0 only. It evaluates current V0 IR at build time and emits a real ARM EABI5 executable whose result is returned through the Linux/Android exit syscall. Runtime effects, inputs, recursion and unsupported operations fail closed.

Generated RiftFS ELF artifacts remain non-executable by policy.

### Current proof

Source-host execution proves:
- smoke IR: 1 function / 6 instructions;
- canonical `SNIRV0`: 157 bytes;
- multi-function IR binary: 411 bytes;
- effectful IR binary: 233 bytes;
- corrupt binary magic rejected;
- ARM32 ELF: 100 bytes, ELF32, EM_ARM, entry `0x10054`, result 42;
- effectful code rejected by the current backend.

Device proof for 0.3 requires the next RiftOS APK.

## Patch 10.6 — QuickJS Semnexis bootstrap replaces native Clang experiment

### Current source changes

The Semnexis bootstrap now runs through the already-packaged headless QuickJS runtime.

Active path:
- packaged \`src/semnexis-bootstrap.js\`;
- fixed native \`semx help|version|self-test|check|dump-graph|dump-plan\` command family;
- confined RiftFS source reads only;
- deterministic Program Graph + verifier + effect/capability solver + Execution Plan;
- V0 ambient capability grants restricted to the application boundary \`main\`;
- direct regression tests for graph/plan goldens and invalid programs.

Removed:
- \`RiftNativeToolchain.kt\`;
- the active \`riftclang\` shell route;
- Clang/LLD APK payload expectations;
- Builder Rift Clang workflow and payload scripts;
- native-toolchain subsystem documentation.

QuickJS is a bootstrap host only. It does not define the future Semnexis program runtime or native backend. The intended next compiler transition is Semnexis source compiling the Semnexis compiler itself.

### Validation gate

Source promotion requires the Semnexis bootstrap and shell-boundary regression tests, exact Android source inventory, documentation parity, full RiftOS audit/scans, then installed-device \`semx self-test\` and smoke-source proof.

## Patch 10.5 — Retired native Clang bootstrap experiment

Patch 10.5 briefly introduced a bounded Android-hosted Clang/LLD bootstrap host. It was retired before payload integration after the bootstrap strategy changed to the already-packaged bounded QuickJS runtime. No Clang/LLD payload is part of the active RiftOS Semnexis path.

## Patch 10.4 — Installed unsigned proof + bounded APK v2 sign/verify/install bootstrap

### Proven before this source patch

Installed RiftOS source `1c1ae33b81cfe643eb804cac0841ced636e982e3` / Builder run 214 executed the RiftBuild bootstrap path on-device:
- `prepare-riftpp-v0` materialized the AArch64 ELF at 1,064 bytes / SHA-256 `9cfc79cd6452d1c920c87b30a40a4c64561d216288b8bf5edc1fc480d07525ab`;
- it materialized the ARMv7 ELF at 732 bytes / SHA-256 `b4e91421b5078ad1b67f9a1f9f4e22a1bda08b8127255b7837e72e0f14007f49`;
- the fixed Android binary manifest matched 1,440 bytes / SHA-256 `ac035bb5bf89f55a3f34bae8eea980108324d2f36333f1e708f8a0b82af8e7c2`;
- project validate/plan reported the universal package ready;
- `riftbuild pack` produced a 1,843-byte unsigned APK with three entries and SHA-256 `a01c190f3b5475df1be59303ffc0a92623cff0dcde38a4743184dcd75e08f329`.

That is installed-device proof for the direct-ELF bridge, binary manifest and local unsigned APK packaging stages. It is not signing/install proof.

### Current source changes

Added `RiftApkV2Signer.kt`:
- one persistent AndroidKeyStore RSA-2048 signing key;
- APK Signature Scheme v2 algorithm `0x0103` (RSA PKCS#1 v1.5 + SHA-256);
- AOSP-style 1 MiB content-digest chunks and APK Signing Block insertion before the ZIP central directory;
- strict non-ZIP64/single-signer bootstrap parsing;
- independent certificate/public-key/signature/content-digest verification;
- sign operations self-verify before publishing their receipt.

Added `RiftBuildInstaller.kt`:
- `REQUEST_INSTALL_PACKAGES` / per-source Android trust handling;
- PackageInstaller session ownership with user action required;
- install restricted to exact package `com.riftpp.nativeproof`;
- persisted install result state;
- exact `android.app.NativeActivity` launch request;
- protected `PACKAGE_FIRST_LAUNCH` receipt recorded as `launch-proven`.

The existing `riftbuild` family now exposes only bounded `sign`, `verify`, `install-proof`, `install-status` and `launch-proof`; it adds no MCP tool, raw process/package-manager shell, automatic Git push or experimental CLI authority.

### Proof boundary

This signer/installer patch is **SOURCE IMPLEMENTED ONLY** until Android Builder compiles it and that APK is installed. The next proof sequence is exactly: sign the already-produced proof APK → independently verify v2 → Android PackageInstaller confirmation/install → launch → confirm first-launch status. After that bootstrap milestone, active development returns to RiftLLM+.

## Patch 10.3 — RiftBuild Kotlin regex escape repair

Builder run `35420418538` for source `bc8c0cb3a8012f2eef685354ab1253fea6baf1dd` passed source checks and Gradle validation, then reached real Kotlin compilation.

Kotlin compilation failed only in `RiftBuildLocalExecutor.kt:187`: the NativeActivity metadata regex used `\.` inside a normal Kotlin string. Kotlin interprets `\.` as an unsupported string escape before the regex engine sees it.

Repair:
- preserve the exact regex semantics;
- move the pattern to a Kotlin raw triple-quoted string so regex escapes remain regex syntax and require no Kotlin escaping.

No authority, behavior, MCP surface, CLI state or packaging contract changes in this repair.

## Patch 10.2 — Gradle Kotlin snapshot escape repair

Builder run `35420241649` for source `7d9de917ba5347c11f18bdde2ab4773aaf53d77b` passed the full RiftOS source/documentation gate and reached Gradle configuration.

Gradle then failed before Kotlin compilation because the exact mandatory Kotlin source list contained one literal `\\n` escape between `RiftBrowserWindow.kt` and `RiftBuildLocalExecutor.kt` instead of a physical newline. That single malformed token caused the subsequent parser-error cascade through the remainder of the list.

Repair:
- replaced the literal `\\n` with a real newline in `android/app/build.gradle.kts`;
- extended `validate-rift-wiring.mjs` to fail source validation if the mandatory Kotlin list ever contains this escaped-line-separator pattern again.

No Kotlin runtime source, MCP surface, CLI state or build authority changed in this repair.

## Patch 10.1 — Builder documentation-gate repair

Builder run `35419961262` for source `9052a0ffa913986142e32f79d3e12a8c32d61b32` stopped in `validate-rift-docs.mjs` before Android compilation.

The failure was documentation-only:
- RiftBuild README lacked the required exact `## Source ownership` maintenance section;
- its verification text did not match the validator's required source-verification marker;
- two SOURCE_OWNERSHIP table insertions contained literal `\\n` text, causing the RiftBuild executor and local-platform test ownership rows to be invisible to the row parser.

Repair:
- added the canonical `**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**` marker while preserving the explicit Android compile/device-pending boundary;
- added RiftBuild's exact source-ownership section;
- replaced both literal `\\n` table separators with real newlines.

No Kotlin, Gradle, MCP, CLI, build authority or runtime behavior changed in this repair.

## Patch 10 — Native RiftBuild bounded Android build core

### What changed

Promoted RiftBuild from an inactive JavaScript design plus non-executing app façade to a native, workspace-bounded Android build controller.

`RiftBuildLocalExecutor` now owns:
- source/project validation for workspace Android projects;
- deterministic project identity and bounded run records;
- fixed ARM32 / ARM64 / universal planning;
- the existing capability-gated `build.local` app methods;
- a native `riftbuild` shell command family;
- a real prepared-artifact APK ZIP stage under `D:/Builds`.

The prepared package stage only accepts compiled Android binary manifest input plus selected ABI `.so` payloads and optional bounded assets/resources. It writes an **unsigned** APK plus SHA-256 receipt and explicitly records `signed=false` and `installableClaimed=false`.

### Security boundary

This patch does not add `ProcessBuilder`, raw `exec`, downloaded toolchain execution, automatic Git push, experimental CLI enablement or any new MCP tool. Projects remain confined to `D:/Workspace`; outputs remain confined to `D:/Builds`.

Missing direct ELF emission, signing or PackageInstaller ownership produces a blocker rather than fake build success.

### Validation

`scripts/test-riftbuild-native.mjs` locks:
- workspace/output confinement;
- binary-manifest requirement;
- dual-ABI package expectations;
- unsigned/non-installable honesty;
- no process/CLI/MCP authority expansion;
- retained `src/riftbuild.js` remaining unpackaged reference source;
- native shell and existing `build.local` wiring.

The Android source snapshot is now exact 50/50 with `RiftBuildLocalExecutor.kt` included.

Actual Android compilation of this new Kotlin source and on-device RiftBuild execution remain Builder/install proof steps; source validation is not called APK/device proof.

### Next dependency

Implement direct Rift++ ARMv7/AArch64 ELF/shared-object emission into the prepared-artifact contract, then add bounded APK signing/verification and explicit PackageInstaller integration.

## Patch 9 — Impact-derived verification planner

### What changed

Added `RiftVerificationPlannerV1` and bound it into the manual OBSERVE-only CLI lifecycle.

The planner derives one exact `rift.verification-plan/1` from the final Patch Manifest / PI-v2 candidate and current repository state.

It owns:
- impacted test targets and bounded repository-check fallback;
- security targets from changed source/build config/direct dependents plus API/dependency-surface reasons;
- required repository `rift-audit` / `rift-scan` checks for source/build candidates;
- exact added/removed dependency delta reviews;
- changed build-config reviews;
- current dependency/build manifest reviews;
- deterministic check ids for every required verification action.

Security, dependencies and tests evidence now require:

`verificationPlan.schema = rift.verification-evidence/1`

with the exact `planSha256` and evidence kind from:

`rift-cli lifecycle verification-plan <sessionId>`

The generic evidence `checks` list must contain every planned check id with PASS status, and `targets` must contain every required plan target. Extra checks may be reported, but the required set cannot be replaced by a caller-selected subset.

Final evaluation recomputes the plan and binds `verificationPlanSha256` into the evaluator subject. Missing/incomplete/stale security/dependencies/tests plan evidence therefore denies the candidate.

### Fail-closed behavior

- source/build/test change with no impact test and no derivable repository validation fallback -> `NO_TEST_OR_VALIDATION_TARGET`;
- missing planned test -> hard plan issue;
- missing audit/scan/target/dependency/build-config check -> incomplete verification evidence;
- candidate mutation after verification -> `VERIFICATION_PLAN_STALE`.

Patch 9 remains a planner/evidence gate, not an autonomous test runner. It adds no MCP tool, process runner, dependency installer, trust promotion or publication authority.

### Regression

`scripts/test-rift-verification-planner-v1.mjs` locks schemas, bounds, test/security/dependency derivation, exact-check identity, lifecycle binding, Gradle/source ownership and zero MCP/trust expansion.

## Patch 8 — Documentation / project-state parity gate

### What changed

Added `RiftDocumentationParityV1` and bound it into the manual OBSERVE-only CLI lifecycle.

The new deterministic plan is derived from the exact Patch Manifest / PI-v2 candidate and checks:
- substantive owner documentation separately from bookkeeping (`PATCH_HISTORY` / `SOURCE_OWNERSHIP` cannot alone satisfy an owner-doc update);
- maintained source/build/test ownership;
- stale ownership after deletion;
- owner-document existence;
- mandatory owner-document updates for added/type-changed/API/dependency/build-config changes;
- ownership-ledger updates for added/deleted maintained files;
- PATCH_HISTORY updates for maintained source/build/test candidates;
- explicit review coverage for README, ROADMAP, PROJECT_STATUS, SOURCE_OWNERSHIP and PATCH_HISTORY when present.

Documentation evidence now requires a `documentationParity` object generated against:

`rift-cli lifecycle documentation-plan <sessionId>`

The normalized evidence must review every maintained changed path and every governance surface using the exact deterministic owner set. Final evaluation recomputes the plan and binds `documentationParityPlanSha256` into the evaluator subject; stale parity evidence therefore denies the candidate.

### What Patch 8 does not claim

The gate does not claim arbitrary English prose can be proven true by local Kotlin. `UNCHANGED_VALID` remains a bounded structured claim and the independent evaluator must re-check prose against exact source/impact evidence.

Patch 8 remains OBSERVE-only:
- no MCP tool;
- no trusted promotion;
- no publication;
- no autonomous documentation rewrite.

### Regression

`scripts/test-rift-documentation-parity-v1.mjs` locks the plan/evidence schemas, ownership rules, governance review rules, lifecycle binding, Gradle/source ownership and absence of MCP/trust expansion.

## RiftGit read-only history update

Added bounded native `git log` support to RiftGit before Patch 8. The command reads commit history through GitHub's commits API for the already attached repository and validated current branch; it does not invoke a local Git process or widen mutation authority.

Supported forms:
- `git log`
- `git log -n N`
- `git log -nN`
- `git log --max-count=N`
- optional `--oneline`

The default is 20 commits and the hard per-request cap is 100. RiftGit also now exposes `git head` and `git rev-parse HEAD`, while `git status` prints recorded HEAD explicitly. `git log` reports recorded local HEAD versus remote branch HEAD and whether they match. Results include structured SHA/message/author/committer/parent data. History reads do not write `.riftgit.json`, create Workspace Records checkpoints, change branch state or expose arbitrary remote/ref queries.

## Builder hotfix — lifecycle regression expectation after stress-foundation repair

Builder run `35405254326` for source `fdbb30c2e62f4d2c4b4c82540c64f8c107a6f1c3` passed source integrity and reached the focused lifecycle test, then stopped because `test-rift-cli-patch-lifecycle-v1.mjs` still asserted the pre-repair scope expression `understanding -> governance + buildManifests`.

The implementation was correct: the stress-foundation repair intentionally changed pre-patch UNDERSTAND/DESIGN to the acquired base inventory while post-patch evidence unions base + current inventory. The regression test now locks both sides of that contract instead of the obsolete expression. No runtime authority, lifecycle policy, MCP surface or trust behavior changed.

## Pre-Patch-8 Stress-foundation repair

### Why

Live abuse of RiftCLI Patch Lifecycle V1 exposed two real blockers before the documentation parity gate could be trusted:

1. a candidate that added new governance files could mark DOCUMENT_AUDIT complete while omitting newly introduced `TODO.md`, `docs/PATCH_HISTORY.md` and `docs/SOURCE_OWNERSHIP.md`;
2. process recreation could reset Experimental authority correctly but lifecycle-session durability was inconsistent, and an unexplained external workspace change/delete across restart could invalidate the candidate underneath evaluation.

### Repair

`RiftCliPatchLifecycleV1` now:

- derives post-patch governance scope from **base inventory + current inventory + Project Intelligence changed-documentation evidence**;
- derives dependency/security/build scope from **base build manifests + current build manifests + changed-build-config evidence**;
- keeps UNDERSTAND/DESIGN bound to the original base inventory;
- stores lifecycle sessions under `<filesDir>/riftfs/system/rift-cli-patch-lifecycle-v1`;
- migrates legacy sessions from the previous app-private root on access;
- records a process epoch plus last observed source snapshot/candidate manifest;
- if process epoch changes and source/candidate identity drifted, permanently records `restartDriftDetected`;
- blocks new evidence imports and evaluation for a restart-drifted session;
- surfaces the drift receipt in lifecycle status.

This does **not** claim the external deleter/root cause was identified. Source audit found no normal MainActivity/MCP-runtime/RiftGit-constructor path that intentionally deletes arbitrary untracked repository files on startup. The lifecycle therefore treats unexplained restart drift as unsafe instead of guessing intent.

### Regression

Added `scripts/test-rift-cli-stress-foundation.mjs` to the root check chain. It locks:

- current governance/build-manifest discovery;
- changed-documentation/build-config inclusion;
- base-only pre-patch scope;
- RiftFS system session storage + legacy migration;
- process-epoch/restart-drift detection;
- zero MCP/trust expansion.

### Authority

Still OBSERVE-only. No new MCP tool, trust promotion or publication authority.

## CLI Patch Lifecycle V1 — Patches 6/7 core + 8–10/12 foundation

### What changed

Added the manual OBSERVE-only CLI→AI→CLI patch lifecycle requested for real repository work:

```text
acquire full clean repo
→ understand complete layout/ownership
→ research external assumptions
→ document intent
→ patch through existing tools
→ audit documents
→ audit code
→ security/dependency/test/build verification
→ end-to-end/rollback verification
→ freeze exact candidate
→ send bounded evidence bundle to independent AI
→ locally verify returned hashes/verdict
```

### Research performed first

The design was checked against:
- SLSA v1.2 Source/Build guidance for immutable source revision, provenance and verification-summary concepts;
- in-toto attestation concepts for binding claims to exact subjects;
- NIST SSDF lifecycle secure-development practices.

RiftOS does not claim certification against those standards. The implementation adopts the useful patterns locally.

### Primary source

- `RiftCliPatchLifecycleV1.kt`
- `RiftResearchLedgerV1.kt`
- `RiftExperimentalCli.kt`

Existing evidence owners reused rather than duplicated:
- RiftGit;
- Project Export;
- Workspace Records/Patch Manifest;
- Project Intelligence V2;
- ToolHost internal candidate-impact seam.

### What was added beyond the original proposed workflow

- immutable Git HEAD + Project Export snapshot at acquisition;
- clean-tree requirement and optional native Git pull;
- bounded full-repository inventory;
- README/docs/ROADMAP/TODO/TASK/patch-history/status/source-ownership discovery;
- generated/vendor boundary discovery;
- dependency/build manifest discovery;
- source/version/claim research ledger;
- authoritative-source requirement for critical claims;
- pre-patch research→design ordering;
- actual candidate-derived audit targets;
- documentation→code→security/dependency→test/build→E2E/rollback ordering;
- lockfile/SBOM/license/provenance disposition;
- build environment + artifact SHA-256 evidence;
- explicit rollback evidence;
- stale-evidence invalidation against candidate manifest;
- final candidate/semantic/evidence/policy hashes;
- independent evaluator/patch-actor identity separation;
- bounded structured defects;
- evaluation packet size limit with fail-closed behavior;
- no trust promotion/publication.

### Authority

Lifecycle commands are behind the existing manually enabled Experimental RiftCLI.

No new MCP tool, relay method, raw Android shell, model backend, persistent enable flag, trusted-checkpoint promotion or publishing authority was added.

`help` and `contract` are descriptive. Session/evidence/evaluation commands require process-local experimental enablement.

### Evidence ordering

The lifecycle requires:
1. base-bound repository understanding;
2. research;
3. design/document intent;
4. patch;
5. documentation audit;
6. code audit;
7. security;
8. dependency/supply-chain audit;
9. tests when source/build config changed;
10. optional build evidence when available (becomes required/current if supplied; Patch 13 will own mandatory artifact handshake);
11. E2E;
12. rollback;
13. freeze/evaluation.

Non-research evidence cannot be complete with zero checks. WARN/FAIL/NOT_RUN makes the record incomplete. Missing impact-derived targets also makes it incomplete.

### State-of-the-art evidence additions

Complete dependency evidence must disposition:
- lockfiles;
- SBOM;
- licenses;
- provenance.

Complete build evidence must name:
- builder;
- toolchain;
- source revision;
- artifact SHA-256s.

Research collection itself is not called trusted. The final evaluator must independently re-check critical claims. V1 evaluator/patch-actor IDs are declarative separation metadata, not cryptographically authenticated identities.

### Current roadmap effect

- Patch 6 state-machine/policy core: OBSERVE core implemented.
- Patch 7 research ledger: collection/claim schema implemented; final independent re-check remains evaluator responsibility.
- Patch 8 parity gate: target-coverage/order foundation implemented; semantic truth remains validators/evaluator work.
- Patch 9 impact-derived verification planner: target-selection foundation implemented; no autonomous test/build runner.
- Patch 10 stale-result invalidation: candidate/source/evidence binding implemented; hermetic execution is not.
- Patch 11 enforcement/bypass closure: not implemented.
- Patch 12 verification-bundle foundation: implemented; immutable/hash-chained decision trail not yet implemented.
- Patch 13 Builder provenance handshake: not implemented.
- Patch 14 adversarial graduation: pending.

### Limits

- lifecycle session store: 64 sessions;
- imported evidence: 512 KiB/file, 96 records/session;
- checks: 256/record;
- targets: 2000/record;
- full inventory: 50000 files / 512 MiB;
- evaluation packet: 700 KiB and fails rather than truncates;
- evaluator defects: 256.

### Validation

Focused source contract:
- `scripts/test-rift-cli-patch-lifecycle-v1.mjs`

The source test locks manual OBSERVE authority, lifecycle stages, clean acquisition, research rules, evidence completeness/coverage/order, supply-chain/build evidence, stale-result binding, evaluator separation, source ownership and no MCP expansion.

Native shell on-device does not provide Node, so the new JS regression test cannot be honestly claimed executed locally in this source session. It is wired into root `npm run check` and must run in Builder/source-validation environment. Android/Gradle compile and installed-device abuse remain separate gates.

### Rollback

Remove:
- `RiftCliPatchLifecycleV1.kt`;
- `RiftResearchLedgerV1.kt`;
- Experimental CLI lifecycle branch/status/help additions;
- focused test and docs/source declarations.

Existing Patches 1–5 evidence services remain independent and continue to work.

## Build-validation hotfix — verification marker date contract

Builder run `35371827181` for source `84c0a39c7f7e8e2edf27529b566460dd7ef8f087` passed source integrity and all code/wiring checks, then failed only because `scripts/validate-rift-docs.mjs` still hard-coded `2026-09-17` while the subsystems changed by Patches 1–5 had been correctly re-verified on `2026-09-18`.

The validator now checks verification-marker class plus a valid non-future ISO audit date instead of one global hard-coded date. This preserves per-subsystem verification history and removes the false requirement that untouched source must claim a newer audit date. No runtime, MCP, filesystem, Git, Local Agent or acceptance authority changed.

## Patch 5 — Semantic diff and Project Intelligence V2 impact mapping

### What changed

Added a candidate-bound semantic impact layer that reuses Project Intelligence V2 rather than trusting the patch author to declare affected APIs, callers, tests or documentation.

### Where

Primary source:
- `RiftSourceIntelligenceV2.kt`
- `RiftWorkspaceRecords.kt`
- `RiftToolSandbox.kt`
- `RiftToolHost.kt`

Validation:
- `scripts/test-rift-semantic-impact-v1.mjs`
- exact Gradle Kotlin source snapshot
- Workspace/MCP/Engine/build-validation documentation

### Why

Patch 4 proves exactly which bytes changed, but a physical diff cannot by itself determine which symbols, imports, callers, tests, documentation owners or build surfaces can be affected. The semantic layer must derive that scope independently from the frozen candidate rather than accepting a model-provided list.

### How

`RiftSourceIntelligenceV2` is the single lexical parser used by both normal PI-v2 indexing and before/after semantic comparison. The previous private symbol/dependency parser was removed from `RiftToolSandbox` so the two evidence paths cannot drift.

Workspace Records derives an internal semantic seed from the exact Patch Manifest V1 candidate. The seed includes exact changed-path identities plus bounded before/after source text. It is not an MCP method and cannot accept a caller-selected path scope.

PI-v2 then derives project roots from the changed paths, refreshes the current index, computes added/removed symbols, signature changes, added/removed dependencies, conservative API-surface changes, current dependencies, current dependents, changed-symbol references, relevant tests, documentation ownership from `docs/SOURCE_OWNERSHIP.md`, nearest README fallbacks, and global project docs. Build/config and documentation changes are classified separately.

The deterministic evidence payload is hashed as `semanticImpactSha256` using Patch Manifest canonical hashing. Cache-refresh diagnostics are attached after that hash and are not part of semantic identity.

The process-owned ToolHost exposes only an internal `candidateImpactAsync` seam for the future Local Agent. It is deliberately absent from `tools()`, aliases and MCP backend mappings.

### Bounds and completeness

Semantic seed:
- maximum 4096 changed paths;
- maximum 1024 changed source files;
- maximum 8 MiB combined before/after source text.

Impact:
- maximum 32 derived project roots;
- maximum 1000 changed symbol names in the output;
- maximum 80 changed symbols used for one-pass textual reference discovery;
- maximum 800 references;
- maximum 800 dependency rows;
- maximum 800 dependent rows;
- maximum 300 tests;
- maximum 300 documentation targets;
- maximum 128 changed source targets for test-affinity expansion.

Crossing a bound, missing before/after source text, a truncated PI index, or a truncated semantic delta adds an explicit incomplete reason. The system never silently calls partial impact evidence complete.

### Effects

Patch 5 can discover affected code/tests/docs from the candidate itself and bind that analysis to the candidate hash. It still cannot accept, deny, publish, advance trusted state or block existing workflows. Development mode remains OBSERVE.

The parser is intentionally a bounded lexical Project Intelligence layer, not a compiler AST or proof of correctness. Later validation patches must still require language/compiler/build/tests and may deny incomplete semantic evidence.

### Validation

Source audit verifies one shared parser owner, no returned private parser duplicate, exact candidate-derived seed, bounded incomplete semantics, deterministic impact hashing, ownership-ledger lookup, internal ToolHost routing, unchanged 18-tool MCP catalog, exact source declaration and focused-test wiring.

### Rollback

Remove `RiftSourceIntelligenceV2.kt`, restore the previous private PI-v2 parser in `RiftToolSandbox`, remove candidate-impact/semantic-seed integration, focused test/docs/source declaration, and leave Patch 4 physical manifest evidence intact.

## Patch 4 — Immutable candidate manifest and tamper-evident record chain

### What changed

Added deterministic candidate identity and tamper-evident evidence primitives without enabling patch blocking or trusted-state promotion.

### Where

Primary source:
- `RiftPatchManifestV1.kt`
- `RiftWorkspaceRecords.kt`

Validation:
- `scripts/test-rift-patch-manifest-v1.mjs`
- exact Gradle Kotlin source snapshot
- Workspace/Workspace Records/MCP/build-validation documentation

### Why

Patch Sessions identify writer provenance, but provenance alone does not bind an approval to exact bytes. Patch 4 creates a content-derived candidate identity and a forward hash chain so later validation stages can prove which base tree, result tree, structural changes and retained evidence were evaluated.

### How

The manifest uses deterministic canonical JSON and SHA-256. Tree identity is derived from sorted path/kind/size/content-SHA fields; mtimes and freeze timestamps are excluded. Change-set and structural-diff digests are separate. The sealed manifest contains the operational checkpoint identity, current tree identity, every changed path with before/after content identity, structural identity relations, retained patch-session evidence, current record-chain integrity and the current trusted-checkpoint state.

`freezeCandidate()` is internal-only. It writes a canonical manifest to the private records store under its SHA-256 filename. Existing identical manifests are verified byte-for-byte. No MCP tool exposes freeze or trusted promotion.

New event records are sealed with chain version, chain epoch, previous-record hash and record hash. Legacy pre-Patch-4 event files remain readable but are outside the new chain epoch. Pruning persists a verification anchor and pruned-through sequence before deleting old records. Startup may fast-forward a lagging persisted head only when the retained chain proves the old head is an ancestor; arbitrary mismatches are not silently healed.

Operational checkpoints now carry a recorder sequence. Provenance completeness is decided from checkpoint/pruning sequence boundaries rather than timestamp guesses. Trusted checkpoint fields exist as inert state only; no source path can promote them yet.

### Effects

`rift_workspace_diff` remains read-only but now has source support to report:
- operational checkpoint identity;
- separate trusted-checkpoint state;
- candidate manifest summary;
- record-chain integrity.

Candidate byte changes alter result-tree/change-set identity. Base changes alter base-tree identity. Evidence changes can also alter the manifest SHA, forcing later validation to re-evaluate rather than inherit stale approval.

### Bounds and risks

- maximum frozen manifests: 512;
- maximum one frozen manifest: 8 MiB;
- candidate workspace-path bound: 50000;
- event record retention remains 2000;
- pre-Patch-4 legacy records are not retroactively hash-chained;
- migration from an old operational checkpoint has unknown checkpoint sequence until a new checkpoint is created, so session-evidence completeness must remain false rather than guessed;
- trusted promotion is intentionally absent until the Local Agent policy gate exists.

### Validation

Static source/impact audit verifies deterministic hashing, immutable-by-hash storage, chain sealing/verification, sequence-based pruning evidence, crash-safe head recovery, no MCP freeze mapping, exact Gradle source declaration and focused-test wiring. Android/Gradle compile and device proof remain required before installed-runtime promotion.

### Rollback

Patch 4 can be reverted by removing `RiftPatchManifestV1.kt`, its Workspace Records integration, test/docs/source declaration and private-manifest/chain state readers. Existing private manifest/event files are non-workspace metadata and must not be treated as workspace source during rollback.

## Patch 3 — Patch sessions and provenance

### What changed

Added bounded writer provenance that correlates MCP, native Shell, native Editor, Dev Lab and native Git mutations with asynchronous Workspace Records events.

### Where

Primary source:
- `RiftPatchSessions.kt`
- `RiftWorkspaceRecords.kt`
- writer integrations in `RiftToolSandbox.kt`, `RiftNativeShell.kt`, `RiftNativeWorkspaceApps.kt`, `RiftNativeDevLab.kt`, `RiftNativeGit.kt`
- optional `intent` schema field in `RiftToolHost.kt`

Validation:
- `scripts/test-rift-patch-sessions.mjs`

### Why

FileObserver delivery occurs after a writer may have returned. A simple thread-local or “last writer” value could falsely attribute an unrelated later change.

### How

Writers declare bounded target paths before mutation, capture before-state, and commit short-lived claims after successful mutation. Exact file/deletion claims are correlated against resulting state and SHA-256. Directory replacement flows are explicitly lower-confidence scope claims. Claims expire after 15 seconds and are bounded. Unknown writers are recorded as `unattributed-local`; provenance is never guessed.

Only explicit `workspace/...` and `D:/Workspace/...` forms enter workspace provenance, preventing unrelated RiftFS paths from stealing workspace events.

### Effects

Workspace Records events now contain `patchId` and structured provenance. Dev Lab and Git receipts may surface patch IDs. MCP tool count remains 18. The optional intent field is evidence only and does not change permission classification.

### Bounds and risks

- 512 paths per provenance session;
- 4096 active claims;
- 15-second claim lifetime;
- intent <=500 characters;
- origin/operation/request labels <=120 characters;
- scope-bound directory claims are not cryptographic authorship proof.

### Validation

Source-first impact audit verified all five writer integrations, exact 43-file Kotlin snapshot at Patch 3 freeze, test/package ownership, strict workspace-only ingress, honest unattributed fallback and unchanged 18-tool MCP catalog.

### Rollback

Remove `RiftPatchSessions.kt`, writer hooks, Workspace Records provenance fields, optional intent schema and focused test/docs. Filesystem behavior itself remains owned by the original writer subsystems.

## Patch 2 — File Identity V2

### What changed

Added bounded rename/copy/rewrite identity correlation to Workspace Records.

### Where

Primary source:
- `RiftFileIdentityV2.kt`
- `RiftWorkspaceRecords.kt`
- relation-aware headers in `RiftDiffEngineV2.kt`

Validation:
- `scripts/test-rift-file-identity-v2.mjs`

### Why

Delete+add records could not distinguish structural movement/copy from unrelated file creation, and large rewrites were reported as ordinary modifications.

### How

Exact SHA-256 equality provides exact content-identity evidence for rename/copy candidates. Remaining text candidates use bounded size-prefiltered line similarity. Full reconciliation is limited to 64 candidates per side and 1024 line comparisons. Heuristic results are explicitly marked non-exact and never treated as user intent.

### Effects

Workspace diff/records can surface renamed, copied and rewritten relationships while preserving raw before/after evidence. No mutation or approval authority was added.

### Bounds and risks

Heuristic matching can remain incomplete when the comparison budget is exhausted. Exact content identity does not prove why a user moved or copied a file.

### Validation

Focused source test locks exact-vs-heuristic semantics, bounds, relation-aware Workspace Records wiring and documentation ownership.

### Rollback

Remove `RiftFileIdentityV2.kt` and relation wiring; Workspace Records falls back to independent path changes.

## Patch 1 — Diff Engine V2

### What changed

Replaced Workspace Records' one-middle-block text diff with a deterministic bounded multi-hunk engine.

### Where

Primary source:
- `RiftDiffEngineV2.kt`
- `RiftWorkspaceRecords.kt`

Validation:
- `scripts/test-rift-diff-engine-v2.mjs`

### Why

The previous prefix/suffix algorithm collapsed widely separated edits into one giant replacement and could obscure independent changes.

### How

Normal regions use exact LCS with the actually allocated `(n+1) x (m+1)` matrix capped at 250000 cells. Larger regions use patience-style unique-line anchors with longest-increasing-subsequence ordering and bounded recursion. Huge ambiguous regions fall back to replacement blocks rather than unbounded quadratic allocation.

### Effects

Widely separated edits produce independent hunks. Empty-file creation/deletion and byte-only line-ending changes remain visible. Binary/oversized files remain hash/metadata evidence.

### Bounds and risks

Rendered output remains capped at 64000 characters and 420 changed lines. Large ambiguous regions may intentionally use a non-minimal replacement fallback to protect low-memory Android devices.

### Validation

Focused source test locks algorithm bounds, multi-hunk structure, Workspace Records delegation, source declaration and docs ownership.

### Rollback

Remove `RiftDiffEngineV2.kt` and restore the prior Workspace Records text-diff routine, with the known loss of independent-hunk behavior.


## 2026-09-18 — Rift++ 0.8.0 Gate 1A scalable storage/view candidate

### What changed

- advanced the active Rift++ bootstrap compiler implementation to `0.8.0-bootstrap` while keeping source language `riftpp/1`;
- added persistent `Buffer<T,N>` with capacity up to 100000;
- added read-only zero-copy `Slice<T>` views;
- preserved frozen `Vec<T,N>` capacity/behavior at <=256;
- added RiftVM Buffer/Slice opcodes and persistent 32-way trie Buffer storage;
- Buffer/Slice remain data-only composites and cannot cross generic host-import boundaries;
- Buffer/Slice checkpoint persistence is explicitly denied;
- added `rift-tool semantic-compat` for ongoing frozen-semantic regression checks while leaving `gate0-verify` as the archival exact-reference/drift check;
- updated focused Core/VM/shell tests and strict wiring validation.

### Why

The self-hosted compiler needs token, AST and instruction storage far beyond Vec-256. A separate scalable persistent storage primitive preserves old Vec semantics while providing compiler-scale indexed storage without prematurely introducing pointer/ownership semantics.

Immutable Buffer versions make zero-copy Slice views safe: a Slice references one Buffer version and does not change when later Buffer updates produce a new version.

### Verified before this record

- direct 600-item Buffer/Slice execution passed with stable-view semantics;
- 20000-item Buffer stress passed at 520033 VM steps under the existing 1000000-step VM hard ceiling;
- ordinary compiled 100000-step budget rejected that stress workload as expected;
- full frozen Rift++ semantic compatibility suite passed after Buffer and after Slice;
- RiftLLM+ regression compile remained green;
- RiftOS audit/scan found no new Gate 1A issue beyond the pre-existing RiftSecretStore filename heuristic.

### Remaining promotion gate

Builder/APK/device proof remains required. After installation, run `rift-tool semantic-compat`, the Gate 1A functional fixture, `riftpp self-test`, and host-boundary checks before freezing Gate 1A.


## 2026-09-18 — Rift++ 0.9.0 Gate 1B text/numeric candidate

### What changed

- advanced active compiler implementation to `0.9.0-bootstrap` while retaining source language `riftpp/1` and `rift-exec-v1 / riftvm-1`;
- added `SourceText`, `TextCursor`, persistent `StringBuilder<N>`, numeric text parse/format;
- selected UTF-16 code units for the hot SourceText/cursor/builder representation;
- kept UTF-8 explicit at file/token/provenance/interchange boundaries through on-demand byte accounting;
- preserved frozen compatibility-string UTF-16 code-unit semantics;
- denied SourceText/TextCursor/StringBuilder checkpoint persistence and SourceText hashing through `value_sha256`;
- added independent Core and VM tests;
- added fixed `rift-tool text-model-benchmark` so the installed-device UTF-16/UTF-8 representation costs are recorded under a named benchmark instead of relying on an unpreserved historical multiplier.

### Verified source-side

- UTF-16/code-unit functional Gate 1B program PASS;
- edge/half-surrogate program PASS;
- 70,000-code-unit builder PASS;
- numeric parse/format positive/negative behavior PASS;
- full frozen semantic compatibility suite PASS;
- RiftLLM+ consumer regression compile PASS;
- no language/VM ABI version bump required because changes are additive to valid `riftpp/1` source.

### Promotion boundary

Gate 1B remains **not frozen** until Builder/install/device proof runs `semantic-compat`, exact Gate 1B fixtures, `text-model-benchmark`, self-test, authority-boundary regression and final audit.


## 2026-09-18 — Rift++ Core README maintenance-contract repair

Builder run `35389192989` for source `3c4ed2756ff9874fd011db0b8d22c295109c3efe` failed in `validate-rift-docs.mjs` because `docs/systems/riftpp-core/README.md` was missing the required `## Failure signatures` maintenance heading.

The Gate 1B compiler/runtime code was not the failing gate. The README now restores the required maintenance section with failure-routing signatures derived from the current Core/VM/tooling contracts. The validator itself was not weakened.


## 2026-09-18 — Gate 1B live promotion blockers: UTF-8 determinism + benchmark v2

Installed source `79198ea55704da0e86254ec9e93f93f14611603f` passed semantic compatibility, self-test, primary Gate 1B fixture and host-capability boundaries, but Gate 1B was **not frozen**.

Two blockers were found on-device:

1. `SourceText.utf8_byte_len()` was host-dependent for an unpaired surrogate produced by code-unit slicing. The source-side reference expected canonical U+FFFD UTF-8 length (3 bytes), while the Android/JVM TextEncoder bridge reported 1 byte. RiftVM now computes canonical UTF-8 byte length directly from UTF-16 code units so the result no longer depends on the host encoder.
2. Benchmark v1 was too narrow: it compared raw `charCodeAt` summation with typed-array byte summation. Four live runs consistently favored UTF-8 on that microbenchmark: prepared UTF-8 was roughly 27–30% faster, and UTF-8 prepare+scan roughly 21–23% faster. This result is preserved rather than overridden. Benchmark v2 now separately measures lexer-like sequential traversal and code-unit random access with UTF-8 index preparation/memory cost.

Gate 1B remains pending another Builder/install/device pass with benchmark-v2 evidence.


## 2026-09-18 — Gate 1B benchmark-v2 signed-byte boundary repair

Installed source `35c72ceb49c512ca9fe6a7b667f178f9b4defe05` passed exact-source identity, semantic compatibility, self-test, both positive Gate 1B fixtures, all expected negative diagnostics and capability-boundary tests. Canonical `SourceText.utf8_byte_len()` also matched the 3-byte U+FFFD contract on-device.

The new benchmark-v2 tool itself failed before measurement with `UTF-8 code-unit index length mismatch`. Root cause: the Android QuickJS bridge exposed Kotlin `ByteArray` elements as signed 8-bit values, while the benchmark decoder expected unsigned UTF-8 bytes. The same audit also showed Kotlin/JVM default UTF-8 replacement behavior was not sufficient as the canonical TextEncoder boundary for malformed UTF-16.

The headless runtime now:
- provides one explicit canonical UTF-8 encoder that replaces unpaired UTF-16 surrogates with U+FFFD bytes;
- uses it for all headless UTF-8 byte-limit/hash/state/write paths;
- makes the TextEncoder polyfill normalize bridged bytes into unsigned `Uint8Array` values;
- has focused shell/wiring validation that rejects regression to JVM default `toByteArray(Charsets.UTF_8)` or signed-byte TextEncoder output.

Gate 1B remains unfrozen until the next Builder/install run returns benchmark-v2 measurements.


## 2026-09-18 — Rift++ 0.10 native byte substrate local candidate

After the shared Rift Text reference proved Strict, Replace, streaming, and direct streaming transcode behavior, the next substrate was documented first and then patched locally.

Candidate source:
- compiler `0.10.0-bootstrap`;
- source language remains `riftpp/1`;
- target remains `rift-exec-v1 / riftvm-1`;
- checked `u8` range 0..255;
- existing `Buffer` / `Slice` support `u8`;
- explicit `u8_to_u32`;
- checked `u8_from_u32`;
- u8 participates in checked arithmetic/comparison, display/hash and primitive checkpoint state.

No raw pointer or new host authority was added.

Focused Core/VM tests and strict wiring validation were updated. Builder has no compiler-version pin and already verifies packaged Core/VM bytes against source.

This record does **not** claim runtime PASS: native RiftShell intentionally denies arbitrary Node/process execution and the installed APK still carries the previous compiler. Builder/install/device proof is required before promotion.


## 2026-09-18 — Rift++ bounded u8 device proof route

The existing fixed `riftpp self-test` command was upgraded to schema `riftpp-shell-self-test/3` so the 0.10 native-byte candidate can be proven on-device without adding any generic JS/process execution surface.

The embedded proof covers checked u8 literals/conversions, `Buffer<u8,N>`, `Slice<u8>`, deterministic hashing, compile-time literal overflow rejection and runtime checked arithmetic overflow rejection.

The command still executes with no host imports under the existing Rift++ shell limits. Shell and wiring validators now fail if this bounded u8 proof disappears.


## 2026-09-19 — Native RiftCLI Bootstrap-0 reset

The former Experimental RiftCLI implementation was intentionally retired instead of being used as the foundation for the next CLI architecture.

Retired CLI-only owners:
- `RiftExperimentalCli.kt`;
- `RiftCliPatchLifecycleV1.kt`;
- `RiftDocumentationParityV1.kt`;
- `RiftVerificationPlannerV1.kt`;
- `RiftResearchLedgerV1.kt`;
- `RiftPlusPlusV0.kt`;
- `RiftIrV1.kt` / `RiftIrCliV1.kt`;
- `RiftSwarmCoordinatorV0.kt`;
- `RiftTextEncoderTaskRunner.kt`;
- their Experimental CLI docs, V0 sample and focused regression tests.

Shared RiftOS infrastructure was deliberately retained: Project/Source Intelligence, Workspace Records, Diff/File Identity, Patch Manifest/Sessions, Git, MCP, Dev Lab, RiftBuild, RiftBrowser and Local Agent.

The replacement Bootstrap-0 architecture is:
- C++ canonical core under `android/app/src/main/cpp/riftcli/`;
- thin `RiftCliHost.kt` JNI loader/result adapter only;
- existing `rift-cli` RiftShell command routed directly to that host;
- `riftos-agent` restored to direct `RiftOsLocalAgent` routing with no CLI interception;
- permanent dependency direction `external driver -> MCP/RiftShell -> RiftCLI`;
- no model/API client inside RiftCLI;
- no mutation, tool, network, project-memory, planner or verification authority in Bootstrap-0;
- process-local explicit `CONFIRM-EXPERIMENTAL` enable switch, default OFF;
- explicit UTF-16 <-> standard UTF-8 JNI transcoding;
- primary `arm64-v8a` plus required `armeabi-v7a` support from the first native build.

Gradle now pins NDK `28.2.13676358`, CMake `3.22.1`, both ARM ABI filters and an exact native C++ source snapshot. Source validation was replaced with `test-rift-cli-native-bootstrap.mjs` plus wiring/docs gates.

The public Builder was updated in parallel to install the pinned NDK/CMake, preflight the native contract and require both `lib/arm64-v8a/libriftcli.so` and `lib/armeabi-v7a/libriftcli.so` in the final signed APK while forbidding x86 RiftCLI payloads.

This entry records **source architecture only**. Bootstrap-0 is not promoted until Builder compilation/package/sign/APK verification succeeds and the installed device proves native `rift-cli status`, architecture, enable/disable and restart-reset behavior.


## 2026-09-20 — Codynex MC0 local proof packaging lane

RiftBuild gained a local, bounded proof-packaging entrance for the Codynex MC0 ARM32 machine bootstrap. This is a source-only local candidate; no APK build or Git push was performed while creating this lane.

New architecture:
- RiftOS CMake owns a dumb `codynex_mc0_host` NativeActivity shared library;
- the host maps the exact MC0 seed RW, changes it to RX, invokes it through the frozen ARM32 ABI, provides bounded source/output buffers, changes emitted code RW -> RX, executes generated code and reports PASS/FAIL;
- the host does not parse Codynex source or emit target instructions;
- `riftbuild prepare-codynex-mc0 <codynex-root>` reads the canonical `native/mc0/arm32/mc0_seed.hex`, requires exactly 172 decoded bytes and SHA-256 `3276dcbf29704b1ba7d9d331e7891ceff10d85b16bb7688c62273aeaa3ca311e`;
- prepare extracts only `lib/armeabi-v7a/libcodynex_mc0_host.so` from the installed RiftOS APK and validates it as ARMv7 little-endian ET_DYN;
- the prepared proof stores the compiler authority only as `assets/mc0_seed.bin`;
- package identity is `com.codynex.mc0proof`;
- the existing deterministic APK packer, Android-Keystore APK v2 signer, independent verifier and user-confirmed PackageInstaller remain the downstream pipeline;
- the installer proof-package boundary is now an explicit two-package allowlist: existing `com.riftpp.nativeproof` plus `com.codynex.mc0proof`;
- MC0 is ARM32-only for the first proof so Android selects a 32-bit process for the A32 seed.

The source-oracle project under Codynex `native/mc0/apk-proof` passes the currently installed RiftBuild source validator with `sourceReady=true`. It remains `preparedPackageReady=false` until a future authorized RiftOS build/install contains the new host and the new prepare command is executed.

This change also records the research-order decision that the remaining LR0 live-replacement gates no longer block MC0. LR0 remains the C++ reference/oracle track; MC0 now proceeds independently as the machine-bootstrap truth track.


## 2026-09-20 — Semnexis wiring validator drift repair

Builder run `35497553508` for RiftOS source `01013078d81cdbae7f7371f89e8c6da034910ddb` stopped in `validate-rift-wiring.mjs` before Android compilation.

The Semnexis compiler/runtime was not the failing subsystem. The validator still required historical literal strings `SNIRV2` through `SNIRV6`, while the current compiler constructs versioned SNIR diagnostics dynamically and now exposes frozen V0-V7 compatibility with `SNIRV7` as the latest binary format. The validator also still pinned headless self-test schema `semnexis-bootstrap-self-test/13`, while current headless source emits `/17`.

The wiring gate was updated without changing Semnexis compiler/runtime behavior:
- retain `SNIRV0` compatibility assertion;
- require latest `SNIRV7` / `NATIVE_IR_BINARY_VERSION_V7`;
- require concrete V2-V7 encoder/decoder function ownership;
- retain the existing feature/opcode/runtime hardening assertions;
- update the headless self-test contract to `semnexis-bootstrap-self-test/17`.

This is a validator-parity repair only. The next Builder run remains the compilation/package proof.


## 2026-09-20 — RiftGit GraphQL regression-test escape repair

Builder run `35497823649` for RiftOS source `6772904840c0c21cd7dcf8833086e84f93d72496` stopped in `test-rift-shell-git.mjs` before Android compilation.

`RiftNativeGit.kt` was correct: Kotlin source must escape the GraphQL variable as `\$input` inside the string literal so the runtime payload contains `$input`. The regression test used a JavaScript regex whose `$input` portion was parsed with `$` as an end-of-string anchor, making the assertion impossible to satisfy against the valid Kotlin source.

The test was changed to an exact source-string assertion for `createCommitOnBranch(input: \$input)`. No RiftGit runtime or GraphQL behavior changed.

The next Builder run remains the compilation/package proof.


## 2026-09-20 — active test parity audit after native RiftCLI reset

After Builder exposed two stale source-regression assertions in sequence, the remaining active `npm run check` chain was audited against current source contracts before another build.

Confirmed current:
- Native RiftCLI Bootstrap-0 tests and dual-ABI Gradle/CMake pins;
- retired RiftShell batch tombstone/one-operation MCP contract;
- current RiftBuild/Codynex MC0 source markers, hashes and proof-package ownership;
- retained local-platform compatibility references;
- RiftLLM fixed Provider/training/corpus contracts;
- Rift++ Core `0.10.0-bootstrap` and current RiftVM contracts;
- Semnexis deep compiler/ARM32 tests including frozen SNIRV0-SNIRV7 compatibility;
- bounded QuickJS/Rift++ shell and native shell/WebView-separation tests;
- retained RiftApps/RiftRT package-format reference tests.

One additional stale cluster was found in `scripts/test-semnexis-shell.mjs` before Builder reached it:
- self-test schema was still pinned to `semnexis-bootstrap-self-test/13` instead of current `/17`;
- latest IR format/version was still pinned to SNIRV6/version 6 instead of SNIRV7/version 7;
- compatibility text was pre-Arena/state;
- source-text assertions incorrectly required literal `SNIRV2` through `SNIRV6`, even though current compiler generates intermediate version labels dynamically;
- the shell test duplicated old exact binary/ARM32 byte counts already owned by the dedicated compiler/ARM32 regression suites.

The shell integration test now verifies the current V7 compatibility/export surface and semantic fixture coverage while leaving exact binary byte-size locks to the dedicated compiler/ARM32 tests. No Semnexis compiler/runtime, RiftCLI, Git, RiftLLM or Rift++ runtime behavior changed.


## 2026-09-20 — RiftGit force-flag test drift repair

Builder run `35498648678` for RiftOS source `b5f9353cf9edf3c66537bc0b1f44fda0fbd2600b` passed the Semnexis shell gate and then stopped in `test-rift-shell-git.mjs`.

The Git implementation was current and correct. Native RiftGit uses GitHub GraphQL `createCommitOnBranch` with `expectedHeadOid = remoteSha` for optimistic concurrency. The test still required a historical literal `.put("force", false)` marker that no longer belongs to this GraphQL input shape.

The regression test now asserts the current invariant:
- `expectedHeadOid` must be present and bound to the remote head;
- the atomic push body must expose no force override.

All other positive `RiftNativeGit.kt` assertions in the test were checked against current source; 97 assertions were evaluated and this was the only stale one.

No RiftGit runtime or GraphQL behavior changed.


## 2026-09-20 — RiftBuild native-test missing CMake binding repair

Builder run `35498910295` for RiftOS source `0db984ccc9f08abe14578cfece36939515e79ae2` passed wiring, transport, docs, CLI bootstrap, Git, path compatibility and earlier gates, then stopped in `scripts/test-riftbuild-native.mjs` with a JavaScript `ReferenceError`.

The test asserted CMake ownership for the Codynex MC0 host:
- `codynex_mc0_host` must be compiled with `-fno-exceptions`;
- `codynex_mc0_host` must be compiled with `-fno-rtti`.

Those CMake rules are present and correct in `android/app/src/main/cpp/CMakeLists.txt`, but the test referenced a `cmake` variable that had never been initialized.

The test now explicitly reads `android/app/src/main/cpp/CMakeLists.txt` before those assertions.

A follow-up undefined-binding sweep of the remaining active tests found no additional comparable missing fixture variable; reported heuristic candidates were all valid local declarations, callback parameters or destructured bindings.

No RiftBuild, Codynex MC0, CMake, RiftCLI or runtime behavior changed.


## 2026-09-20 — escaped Semnexis self-test schema assertion repair

Builder run `35499228200` for RiftOS source `5ec147ed9d4f61ca8dc15e6174479dfec2e765ba` passed all source gates through RiftBuild, RiftLLM, Rift++ Core, Semnexis bootstrap and Semnexis ARM32 execution, then stopped in `scripts/test-semnexis-shell.mjs`.

The previous stale-test audit corrected the executable-result assertion to `semnexis-bootstrap-self-test/17`, but one separate source-regression assertion remained escaped inside a JavaScript regex literal as `semnexis-bootstrap-self-test\/13`. A plain-text search for `semnexis-bootstrap-self-test/13` did not match that escaped representation, so the stale check survived.

A fresh read-only clone of GitHub `main` confirmed the Builder was executing the actual committed file and that line 62 still contained the escaped `/13` assertion. The assertion is now updated to `/17`.

An escape-aware follow-up sweep checked both plain and regex-escaped `/13` through `/16` schema forms across the active test set; no additional old Semnexis self-test schema markers were found in the scanned scripts.

No Semnexis compiler/runtime, RiftCLI, Builder checkout, or Android runtime behavior changed.

## 2026-09-20 — RiftBuild PackageInstaller foreground confirmation fix

Observed on live RiftOS:
- MC0 APK preparation, packaging, APK v2 signing and independent verification all passed;
- PackageInstaller reached `STATUS_PENDING_USER_ACTION`;
- `Intent.EXTRA_INTENT` was present;
- Android did not surface the install confirmation UI.

Root cause:
- the PackageInstaller result `IntentSender` targeted `RiftBuildInstallReceiver`;
- the receiver attempted to launch Android's confirmation intent from a background context;
- modern Android background-activity restrictions can suppress that UI launch.

Fix:
- added private translucent/no-history `RiftBuildInstallActivity`;
- PackageInstaller commit callbacks now use `PendingIntent.getActivity(...)`;
- pending-user-action confirmation is launched from that foreground Activity;
- the existing receiver remains for bounded `PACKAGE_FIRST_LAUNCH` evidence only;
- proof package allowlist, APK v2 verification requirement, user confirmation requirement and exact NativeActivity launch boundary remain unchanged.

No silent install authority was added.



## 2026-09-20 — RiftCLI Gate N1 driver protocol + full-authority delegation

Gate N0 was proven on-device on RiftOS source `6f7a61295d6c75ae97cdde59231d767d39eb8152` / run #250: native C++ status/architecture, `armeabi-v7a` execution, explicit process-local enable, fail-closed unsupported command handling and force-stop/restart reset all passed.

Gate N1 source now adds a native C++ external-driver protocol with:
- session/task/project identity, goal, assumptions and evidence references;
- explicit process-local enable as the authority gate;
- full RiftOS authority while enabled through existing RiftOS owners, not raw Android/Linux escape paths;
- one bounded shell action or one bounded direct `rift_*` ToolHost action per accepted request;
- trusted ToolHost delegation that bypasses user-facing MCP read/write toggles only after native CLI authorization while still using the same confined/audited ToolSandbox;
- hard denial of `rift_shell_exec` and `rift_workspace_exec` inside the direct tool lane so shell recursion and the retired/broken workspace-exec batch path cannot return;
- no embedded model/API client and no direct network/process client in the C++ core.

External-driver continuation is bounded rather than recursively autonomous:
- `loopMax` is capped at 8;
- loops are process-local and reset on enable/disable/process restart;
- loop identity binds session/task/project/loopMax;
- continuations must advance exactly one step;
- the final loop step cannot request more information;
- only a new external-driver request may advance a loop.

RiftShell now owns the N1 dispatch bridge:
- shell dispatch executes one existing native RiftShell command;
- direct tool dispatch invokes the trusted ToolHost lane;
- CLI shell mutations retain `RiftPatchSessions` provenance;
- direct tool mutations continue through ToolSandbox's existing patch/provenance path;
- dispatch failures are returned as structured CLI results instead of silently becoming success.

Focused source validation was expanded with `scripts/test-rift-cli-driver-protocol.mjs` and stronger wiring/bootstrap assertions. The N1 audit also fixed one Kotlin named/positional argument merge hazard in the concurrently modified ToolHost debugger integration.

Source audit/scan after the patch remained clean apart from the existing filename-only `RiftSecretStore.kt` heuristic finding. Builder compile/package and installed-device N1 proof are still required before Gate N1 promotion.


## 2026-09-20 — RiftCLI N1 end-to-end authority hardening

A follow-up end-to-end audit hardened Gate N1 before Builder/device promotion.

Authority execution:
- both RiftShell and direct ToolHost authority lanes now submit process-owned live-poll jobs instead of blocking the MCP request;
- the CLI path has no fixed wall-clock timeout; normal MCP requests retain their existing bounded timeout;
- `rift_cli_job_list`, `rift_cli_job_poll` and `rift_cli_job_cancel` provide external observation/recovery/cancellation;
- job-control requests are idempotent single-step controls and remain available while CLI authority is disabled;
- a shared `RiftCliExecutionGate` permits exactly one outstanding CLI authority job globally across shell + ToolHost, rejecting a second authority action instead of silently queueing future mutations.

Replay/idempotency:
- every authority-bearing driver request requires a bounded `request-id`;
- accepted request IDs are retained without eviction for the entire RiftOS process lifetime;
- duplicate request IDs are rejected;
- the protection set fails closed at 4096 unique authority requests rather than evicting old IDs;
- disable/re-enable clears driver-loop state but does not clear replay protection; replay state resets only with RiftOS process restart;
- lost submit responses can be recovered by listing jobs filtered by the original request ID without replaying the authority action.

Cancellation truthfulness:
- queued cancellation is reported as `cancelled`;
- a running request first reports `cancelling`;
- successful completion after a cancellation request reports `completed_after_cancel_request`;
- interruption after state may already have changed reports `cancelled_may_have_applied`, never a false rollback claim;
- disabling RiftCLI requests cancellation of both authority lanes, while job controls remain available to verify the terminal result.

Retention/privacy:
- both job lanes retain at most 16 jobs for 5 minutes;
- retained terminal output/result is capped at 2 MiB per job;
- oversized successful results become `completed_result_too_large` and retain only bounded metadata;
- job-list recovery is metadata-only; full stored output/result is returned only by explicit poll of a concrete job ID;
- shell job history retains the operation name rather than the full original command/arguments.

Native boundary:
- JNI ingress remains bounded to 512 arguments, 128 KiB per argument, 512 KiB total arguments and 4096 bytes of cwd text;
- explicit UTF-16/UTF-8 transcoding remains in place;
- the native core still owns no direct process/network/model client.

Validation after hardening:
- `test-rift-cli-driver-protocol.mjs` passes against the live working-tree source;
- `test-rift-cli-native-bootstrap.mjs` passes against the live working-tree source;
- JavaScript validator/test syntax and `package.json` syntax pass;
- Rift audit/scan cover 237 files and remain clean apart from the existing filename-only `RiftSecretStore.kt` heuristic finding.

Builder compile/package and installed-device N1 proof remain required before Gate N1 promotion.


## 2026-09-20 — Builder wiring validator class/escape parity repair

Builder run `35505319625` for RiftOS source `0f611a30270e4fafe68eb6bd59f52f772ecf227f` stopped in the first source wiring gate before Android compilation.

Two validator assumptions were stale while runtime source was already correct:

- `AndroidManifest.xml` declares `RiftBuildInstallActivity`, and the class exists in `RiftBuildInstaller.kt`. The wiring gate incorrectly assumed every manifest Activity must live in a same-named Kotlin file (`RiftBuildInstallActivity.kt`). The gate now resolves manifest activities by actual Kotlin class declarations across the Android source set, and checks all Activity declarations in each Kotlin file against the manifest.
- the RiftCLI N1 replay/tool-execution checks compared escaped C++ JSON source using ordinary JavaScript string literals. Quoted JSON string values such as `driverReplayReset` and `driverToolExecution` could lose a backslash during JavaScript literal decoding and falsely report architecture drift. Those assertions now use `String.raw` for the exact C++ source representation.

A follow-up scan of every active `.mjs` test/validator found no additional same-name Activity-file assumptions or non-`String.raw` escaped quoted-value assertions of this class.

No RiftBuild installer runtime, RiftCLI runtime, JNI, authority model, or Android manifest behavior changed. The next Builder run remains the compile/package proof.


## 2026-09-20 — Builder patch-session provenance test parity

Builder run `35506014725` for RiftOS source `c346c3b7d33a8847b7a03130c829fe65e072ee20` passed wiring/docs and then stopped in `scripts/test-rift-patch-sessions.mjs`.

The runtime provenance path was already correct and intentionally generalized for RiftCLI N1:
- `RiftToolSandbox.executeRequest(raw, origin)` owns the shared implementation;
- `RiftPatchSessions.begin(... origin = origin ...)` records the supplied writer origin;
- normal MCP calls use `executeRequest(raw, "mcp")`;
- RiftCLI live-poll jobs use `executeRequest(raw, "rift-cli")`.

The test still required the pre-N1 implementation string `origin = "mcp"`, so it falsely rejected the generalized owner. The test now requires the generalized provenance assignment plus both concrete call-site origins.

A scan across all active `.mjs` tests/validators found no additional hardcoded `origin = "mcp"` provenance assumptions.

No runtime provenance, ToolSandbox, RiftCLI authority, or patch-session behavior changed.

## 2026-09-20 — Codynex MC1-A machine proof lane

Added the next machine-bootstrap pressure stage after the real-device MC0 ARM32 PASS.

RiftOS additions:
- separate `codynex_mc1a_host` NativeActivity test host;
- CMake + exact native-source snapshot wiring;
- bounded `prepare-codynex-mc1a` RiftBuild command;
- frozen MC1-A seed size/hash/package/library constants;
- separate binary manifest encoder for `com.codynex.mc1aproof`;
- exact `assets/mc1a_seed.bin` compiler-authority receipt;
- PackageInstaller allowlist + package visibility for MC1-A;
- RiftShell help and static RiftBuild contract assertions.

MC0 remains a separate frozen proof path. The new lane does not refactor or replace the MC0 compiler artifact.

No general compiler authority, shell authority, silent install authority or heap/runtime semantics were added.

## 2026-09-20 — RiftCLI N1.5 persistent push + N1.6 Batch V2 source completion

RiftCLI pre-N2 work was hardened and re-audited after the live-proven N1 baseline at Builder run #255 / source `121edf6b3255beca33a45351d3952c7026b5cb4b`.

N1.5 persistent push:
- `RiftCliEventBus` now owns a bounded 256-event process-local replay ring, 96 KiB event ceiling and 48 KiB inline-result ceiling;
- event sequences start from a wall-clock-derived high base so a normal RiftOS process restart does not reset new events below a relay/driver cursor retained from the previous process;
- event type and extra metadata keys are bounded;
- batch step coalescing includes `stepId`;
- oversized events collapse to a bounded metadata-only event while preserving their allocated sequence instead of creating a synthetic replay gap;
- the Android relay client forwards `cli.event` over the existing persistent WSS and handles replay requests plus ACKs;
- the relay maintains independent WebSocket/SSE cursors, filters replay already consumed by each subscriber and advances cursors only forward;
- device reconnect uses the oldest active subscriber cursor through `cliResumeAfter`;
- WebSocket and SSE event subscribers share one four-client ceiling;
- slow SSE subscribers fail closed on backpressure rather than building an unbounded write queue;
- Durable Object event payload persistence remains absent; device memory owns replay.

N1.6 RiftCLI Batch V2:
- `rift_cli_batch` accepts at most 16 fully prevalidated sequential steps;
- whole-plan and per-step byte limits, unique step IDs, explicit `validate` / `execute` modes and `stop` / `continue` failure policies are enforced;
- tool steps reject nested/control/batch/workspace-exec targets and shell steps use an explicit allowlist while rejecting recursive `rift-cli` and retired `batch`;
- one Batch V2 job reserves the same global `RiftCliExecutionGate` for its entire plan;
- per-step events include `stepId`, index/count/kind/operation and bounded results;
- `completed_with_failures` is a true terminal push state;
- `InterruptedException` is rethrown rather than being converted to an ordinary failed step, and cancellation is checked before and after every step;
- shell and ToolSandbox mutation provenance use `rift-cli-batch`;
- retired RiftShell `batch` and public multi-op `rift_workspace_exec` remain fail-fast disabled.

Validation/hardening:
- repaired interrupted pre-handoff source tests that contained literal escaped newline text;
- replaced remaining fragile escaped RiftCLI C++ JSON checks in the wiring gate with `String.raw`;
- source-level N1.5 and N1.6 gates pass through the bounded read-only QuickJS harness;
- modified relay and RiftCLI JS/MJS gates parse cleanly;
- same-class scan is clean apart from intentional retired-batch tombstone assertions;
- documentation, roadmap, relay protocol, subsystem READMEs and test inventory were synchronized to push-first + Batch V2 semantics.

This source is not yet promoted as an installed N1.5/N1.6 build. Builder compile/package and target-device push/reconnect/batch proof remain required before N1.7/N2 promotion.



## 2026-09-20 — Codynex MC1-B / MC1.2 proof lane

Added a separate bounded ARM32 proof path for the next Codynex machine-bootstrap pressure stage without modifying the frozen MC1-A oracle.

Changes:

- added `codynex_mc1b_host` NativeActivity test host;
- added exact native-source snapshot ownership for `mc1/codynex_mc1b_host.cpp`;
- added `prepare-codynex-mc1b` RiftBuild command;
- added frozen 552-byte seed / SHA-256 checks;
- added `com.codynex.mc1bproof` bounded binary manifest generation;
- added installer allowlist and package visibility for the MC1-B proof app;
- added exact `assets/mc1b_seed.bin` compiler-authority receipt;
- added source tests requiring 12-byte runtime `MOV + ADD + BX` evidence and 96-check proof coverage;
- retained no-raw-process and bounded-package rules.

MC1-B is not yet claimed as a device pass. A rebuilt RiftOS APK is required so the new ARM32 host can be extracted and materialized into the proof APK, followed by the frozen real-device 96-check run.
