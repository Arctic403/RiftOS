# Build and Validation System

## 2026-10-09 — C1.3-E separate real graphical shell process-loss gate SOURCE ONLY

C1.3-D signed manual Builder #660 remains last hardware-promoted checkpoint. E builds on native real `:riftShell` but is NOT yet built or installed. Gradle mandatory source `RiftCoreShellRecovery.kt`, canonical `docs/SOURCE_OWNERSHIP.md` entry, RiftOS `validate-rift-wiring.mjs` static contract and Builder `riftos-build.sh`/selftest/signed-DEX schema marker `riftos.core.shell-recovery/1` are required before user triggers Builder. Check default Core initialization/watchdog (not shell), OS PID/UID/process-name checks, bounded retry and crash-loop cooldown, foreground-background distinction, versioned Binder snapshot claim, no-BOOT exact-generation Core `reattachForShell`, Core focus revocation, native clamped window restoration, and disposable-only guarded real-PID QA hook. Existing C/D protections and build workflow must not be weakened.

**Device gate requires a separately user-manually signed/installed E APK** (do not auto-build). Capture Core PID, real production shell PID, disposable Core-running RAPP generation/surface/text and native desktop window BEFORE proof. Invoke the guarded Core-side QA real graphical-shell process kill once; DO NOT kill Core or `:riftShellProbe` or touch four installed originals. Confirm stable Core PID, RAPP generation/state and published frame; **automatic** new shell PID and restored graphical desktop/window without manual reopen, persisted text and new ACTION/TEXT_INPUT, heartbeat/recovery diagnostics, then Core stop/uninstall disposable. Android may block background Activity launch; an observed blocked restart or required user tap is an **E FAIL/blocker**, not a pass. Also do normal background switch without kill to prove no false restart. Never conflate Activity.recreate with actual shell PROCESS death.


## 2026-10-09 — C1.3-D signed Builder #660 final terminal + separate-process DEVICE PASS

User manually installed signed Builder **#660**, run `37891844448`, executable source `d43a30e29ae2d98abbcf7a54ccbe9a8841613e55`; it is **device-proven** for C1.3-D. Core PID25396 != actual production `:riftShell` graphical PID25436. Native desktop/taskbar/window manager, Installed Apps all four protected RAPPs, Files workspace listing, and browser WebView worked. Known-SHA disposable input probe Core-only BOOT gen1, remote graphical same-gen launch, ACTION count1 and TEXT_INPUT `C13D_660_REMOTE`, Core surface rev6; real remote graphical close stopped Core app, cleaned session/surface/focus and app uninstall returned `cleanupComplete:true`. Four original RAPPs preserved. The last remaining acceptance step was the native shell terminal itself: user screenshot shows `/ $ ps` and output `protected kernel RiftKernel`, `protected desktop Rift Desktop`, `protected shell Native RiftShell`, `focused terminal RiftShell`. Post-screenshot Core `ps` sees remote PID25436 with focused terminal; clean Core PID25396 with zero apps/sessions/surfaces/queue. **C1.3-D DEVICE PASS / PROMOTED.** Do not request a redundant Builder. Documentation-only head updates after installed source do not mean newer executable code was installed.

**C1.3-E is separately required** for real production-shell crash/restart with automatic desktop/window reconstruction and a Core-running disposable RAPP persisting across that shell death; NO C1.3-E implementation or device experiment happened during D. User-manual Builder policy stays unchanged.


## 2026-10-09 — C1.3-D #660 green/install physical main gate PASS, native terminal acceptance pending

User-manual Builder #660/run 37891844448 produced signed, installed source `d43a30e29ae2d98abbcf7a54ccbe9a8841613e55`. Device showed Core PID25396 vs real production `:riftShell` PID25436, distinct. Previous #659 exact-process Binder auth failed; #660 successfully read four Core-owned installed RAPPs and exposed live remote PID/window state through `ps`. Native Files/Workspace and browser WebView read-only windows worked. Disposable SHA-verified input-probe was Core-only started generation1 and surface rev3; remote graphical launch `already-started`/queued, GUI delivered ACTION target10 count1 and TEXT_INPUT target20 `C13D_660_REMOTE`, Core rev6 unchanged PID/gen; remote graphical close stopped Core RAPP and cleared windows/sessions/surfaces/queue/focus. Disposable uninstall complete; all four original RAPPs protected.

**Only remaining planned C1.3-D smoke:** native RiftShell terminal GUI opened, but accessibility Local Agent could not focus its visible EditText, so it could not submit a read-only command. User manually type `ps` in native RiftShell window and share screenshot; verify Core-backed result. Do not misclassify UI automation focus failure as a Core IPC terminal bug or claim terminal command passed without proof. Do not promote D yet; no additional build required for this manual-only check. C1.3-E separate real shell process crash/restart/auto-restore NOT STARTED.


## 2026-10-09 — C1.3-D #659 successful manual build, real device authentication failure

Manual signed Builder #659 (37889666166) installed RiftOS source bd6e0b13 and rendered real desktop/taskbar/Installed Apps UI. Core PID 22863 had zero Core applications/sessions/surfaces. Installed Apps failed with 'Core IPC caller is not the production RiftShell process' and Core ps returned remoteShellPid null. Runtime proof blocked: D not promoted. All four installed RAPPs protected, including Rift++ Compiler Lab; no disposable installed or modified.

Source correction uses ActivityManager.runningAppProcesses to attest Binder callingPid/callingUid against trusted Android PID+UID and exact production :riftShell process name; if unavailable, old /proc check still requires exact name. Never accept same UID alone, read-only probe or caller-supplied identity. RiftOS wiring, Builder source markers and Builder selftests enforce this fail-closed rule. Focused static validation only; FULL new Kotlin/Gradle/APK and device proof pending USER-MANUAL Builder, followed by Core-vs-shell PIDs, installed-app/terminal/desktop IPC and disposable Core-first RAPP action/text. C1.3-E not started.


## 2026-10-09 — C1.3-D third manual Builder RED: eight source ownership entries corrected

**User-manual Builder run `37888463412`** installed source input **`b9b708a1d4fa7de26166deafe2cc76408209e1b3`** into CI, passed `validate-rift-wiring.mjs` and `validate-rift-transport.mjs` (including real `:riftShell` and strict RiftBrowser-owned WebKit checks), but failed `scripts/validate-rift-docs.mjs` on **exactly eight undocumented Kotlin files**: `RiftCoreShellLaunchQueue.kt`, `RiftCoreShellRemoteUiBroker.kt`, `RiftCoreShellWindowBridge.kt`, `RiftRemoteShellExecutor.kt`, `RiftRemoteShellUiClient.kt`, `RiftShellActivity.kt`, `RiftShellCoreClient.kt`, and `RiftShellRappHost.kt`. The canonical `docs/SOURCE_OWNERSHIP.md` now registers each of those eight maintained C1.3-D sources exactly once, mapped to existing responsible Core-shell, desktop, public-surface, shell, browser, RiftBuild and build-validation READMEs. Source ownership remains separate from build/device proof; the strict validator remains unchanged. Focused ledger-format check: **8/8 exact unique entries PASS**. Full documentation/npm and Kotlin/Gradle checks have **not** been rerun on this corrected source; no C1.3-D APK is signed or installed. User alone dispatches the next manual Builder. C1.3-C #655 remains the last device-pass gate; C1.3-E untouched.


## 2026-10-09 — C1.3-D second manual Builder RED: WebKit source ownership fixed, device pending

User-manual Builder run `37887642001` progressed beyond the earlier `rappManager` declaration/grep issues and stopped during source validation with `WebKit ownership escaped RiftBrowser: android/app/src/main/java/com/riftos/app/RiftCoreApplication.kt`. The previous C1.3-D process isolation patch directly imported `android.webkit.WebView` and called `WebView.setDataDirectorySuffix("riftShell")` from Core Application initialization. Both `scripts/validate-rift-wiring.mjs` and Gradle's `validateRiftBrowserWebViewOwnership` rightly restrict the actual WebKit API to named RiftBrowser owners.

**Correction:** `RiftCoreApplication` still recognizes the exact `:riftShell` process on API 28+, but calls `RiftBrowserWindow.prepareRemoteShellWebViewDirectory()` rather than importing WebKit. That function resides in the existing allowlisted `RiftBrowserWindow` companion and calls `WebView.setDataDirectorySuffix("riftShell")` before any shell browser/window construction. The owner allowlist is unchanged. RiftOS source validation and Builder source/self-tests assert both delegation and browser implementation. Focused checks 14/14 and validator V8 syntax PASS; full npm/Gradle build **not yet performed on corrected commits**. Normal manual Builder and D installed-device proof remain the next gates. **No C1.3-D signed APK or device pass yet; C1.3-E not started.**


## 2026-10-09 C1.3-D first manual Builder RED — validator initialization fixed

User-manual Builder run `37886597455` attempted RiftOS source `edb53286a09c90d79493417de2ca8e07df8a46a1` and stopped during `npm run check:transport` (before Kotlin/Gradle and signed APK). The first decisive failure is `scripts/validate-rift-wiring.mjs:721`: `ReferenceError: Cannot access 'rappManager' before initialization`. The new C1.3-D checks accessed `rappManager` and `nativeSystemApps` before their `const` definitions at the later C1.1-P section. Fix: move both exact declarations to C1.3-D inputs before first use, with no duplicates. Builder preflight also emitted two `grep: Unmatched ( or \\(` messages from incorrectly double-escaped literal opening parentheses in two `grep -E` source patterns (`PendingEvent\\(` and `RiftRappQuickJsExecutor\\(` group). Builder updates single-escape both ERE patterns. Static correction review verifies declaration ordering, unique definitions, fixed regex literals and validator syntax; **a full check and Kotlin/Gradle have NOT yet run on corrected commits**. Same user-only manual Builder workflow. C1.3-D DEVICE proof still pending; C1.3-E not started.


## C1.3-D (2026-10-09) — mandatory real graphical process split source/Builder gate; physical test PENDING

Enforce Android manifest **launcher** `RiftShellActivity` with `:riftShell` process; `:riftShellProbe` remains different and read-only; Core provider has no remote-process flag. Exact mandatory Gradle sources include `RiftShellActivity`, `RiftShellRappHost`, `RiftShellCoreClient`, `RiftRemoteShellExecutor`, `RiftRemoteShellUiClient`, `RiftCoreShellRemoteUiBroker`, `RiftCoreShellWindowBridge`, `RiftCoreShellLaunchQueue`. Reject Core singleton/executor/session construction in production graphical shell, require Core-side UID/PID process authentication, input generation/focus rechecks and bounds, and Core-owned consent/effect ticket settlement. Require native terminal/install routing, remote window status/commands, and Core-first generic launch queue. Preserve C1.3-C tests without conflating legacy MainActivity non-launcher source with actual remote desktop. Builder's final signed DEX proof requires `riftos.core.shell-control/1`, `riftos.core.shell-ui/1`, `riftos.shell.desktop-ipc/1`, and `riftos.shell.event/1`. Source checks/green APK alone are NOT device proof. User manually triggers Builder and installs exact signed APK; verify Core PID and real `:riftShell` PID are distinct, desktop/taskbar/native windows/browser + terminal IPC functional, disposable Core RAPP action/text persistent same generation over Binder, focus and stop/uninstall cleanup, all three originals untouched. **No C1.3-E shell crash/restart testing at this gate.**


## 2026-10-09 — C1.3-C signed Android/device gate accepted on #655

The user manually built and installed source `8d7608f` using Builder #655/run `37882347339`. Installed RiftOS reported exact SHA. Real Android: disposable RAPP Core-only BOOT/gen1/rev1, graphical attach same gen, guarded real MainActivity.recreate preserved Core PID7528/gen1/session/surface rev3; user manual action and text screenshot and subsequent independent Core `alt-render` verified `C13C_MANUAL_655` persisted and action count increased. Focus revocation on leaving RiftOS eventually showed `focusedAppId:null`; an intermediate during UI switching may hold a lease, so do not use momentary cross-app checks as settled focus proof. Disposable stop/uninstall cleaned Core with exact original three RAPPs intact. **C1.3-C device pass.** User-only manual Builder remains unchanged. Next **C1.3-D**, real desktop/window manager/renderer separate Android process, needs its OWN coherent code+docs+Builder update, signed build and physical device proof. C1.3-E is not started.


## C1.3-C after #654 device discovery — follow-up source gate, not promoted

User-manual #654 installed source 130dea17 and proved Core BOOT, graphical reuse, ACTION/TEXT_INPUT state and cleanup. Android Back backgrounded MainActivity but left Core input focus and GUI subscriber. Following C1.3-C source must revoke focus on pause/window blur, allow focus only for real foreground windows, and restore only non-minimized visible focus on return. A guarded, generic QA-only Local Agent Dev Lab action `recreate-main-activity-proof` requests true Android MainActivity.recreate for one currently running Core RAPP. Require source/Builder tests, signed DEX marker `riftos.qa.activity-recreate/1`, and the user-manual installed-device proof: actual Activity recreation keeps Core PID, generation, surface/state, and no duplicate BOOT. User triggers Builder only. Do not mark C1.3-C passed or begin D/E until proven.


## 2026-10-08 — C1.3-C source / Builder ownership gate (candidate only)

C1.3-C is now a separate implementation and device-proof stage, superseding the earlier proposal to combine C/D/E. `scripts/validate-rift-wiring.mjs` and `scripts/test-riftbuild-native.mjs` must require Core-owned GUI+shell-less RAPP BOOT, queued event dispatch and focus rechecks in `RiftCoreAppLifecycle`, with `RiftRappHost` limited to generic generation-matched surface presentation and Core input requests. The source tests must reject `coreSessions.attach/close/detach/offerEvent`, `executeChained`, pending event callbacks and BOOT/executable payload ownership in the graphical host. `MainActivity` cannot drive RAPP execution from Activity resume/pause; destruction revokes Core focus without stopping Core sessions.

`Riftos-builder-main/scripts/riftos-build.sh` and `scripts/test-builder-contracts.py` mirror those source invariants rather than the retired C1.1-era host-dispatch markers. Signed APK verifier requires the new `eventDispatchOwner` and `fullAppExecutionIndependentOfDesktop` strings, but the presence of these strings alone is not device proof. The user **alone** starts the unchanged manual Builder workflow after source gates pass and synchronized repos are committed. Device testing must prove Core PID/session/generation persistence during GUI recreation, shell-less BOOT, reattach/input/state, explicit close/stop and disposable cleanup; do not promote until installed proof. C1.3-D/E are entirely separate future gates.


## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-28.**

## Purpose

RiftOS uses two separate validation layers:

1. source-owned static/executable checks inside RiftOS;
2. the separate Riftos-builder Android compile/sign/package verifier.

Neither layer replaces installed-device testing.

## Source ownership

RiftOS repository:
- package.json — source-check entrypoint.
- scripts/validate-rift-wiring.mjs — syntax, Android reachability, exact native snapshot, packaging and runtime-wiring checks.
- scripts/validate-rift-transport.mjs — cross-layer security/authority invariants.
- scripts/validate-rift-docs.mjs — documentation trust/ownership/maintenance gate.
- scripts/test-*.mjs — focused active or explicitly retained-reference contracts.
- android/app/build.gradle.kts — Android source snapshot, generated headless assets and WebView ownership preBuild gates.

External Builder mirror audited during this pass:
- workspace/Riftos-builder-main/.github/workflows/riftos-worker.yml
- workspace/Riftos-builder-main/scripts/riftos-build.sh
- workspace/Riftos-builder-main/scripts/verify-riftos-apk.sh

The Builder is a separate repository and is not part of RiftOS SOURCE_OWNERSHIP.

## npm source gate

Root npm run check delegates to check:transport.

Current ordered flow:
1. Builder syntax-preflights the critical source-gate entrypoints before invoking them;
2. validate-rift-wiring.mjs;
3. validate-rift-transport.mjs;
4. validate-rift-docs.mjs;
5. every focused test-rift-*.mjs listed in package.json.

The external Builder also requires package.json to keep the wiring, transport, docs, MCP event replay, Local Agent batching and DebugHub entrypoints reachable from npm run check.

validate-rift-wiring now also auto-discovers every scripts/test-*.mjs and fails if a focused test exists but is not executed by a package script.

Therefore adding a focused test without wiring it into npm run check is a validation failure.

## JavaScript syntax coverage

validate-rift-wiring syntax-checks:
- every src/*.js;
- Android runtime asset JavaScript;
- workspace-live JavaScript;
- relay JavaScript;
- every scripts/*.mjs.

This includes retained/reference JavaScript intentionally kept as migration/regression material.

Passing syntax does not imply a retained module is packaged or live.

The Builder repeats a narrow syntax preflight for the critical source-gate entrypoints before npm run check, so validate-rift-wiring.mjs is not the only mechanism expected to detect its own syntax corruption.

## Android manifest-component/source reachability

The wiring validator:
- parses manifest Activity, Service, Receiver and Provider declarations;
- requires each declared manifest component to have a matching Kotlin class/object source;
- keeps the stricter Activity-subclass check for manifest Activities;
- identifies Kotlin types reachable by textual source references from all manifest component roots;
- fails when a Kotlin source is unreachable from the Android application graph.

This is a static reachability guard, not Kotlin compilation/type resolution. Treating all Android manifest components as roots prevents legitimate Activity/Service/Receiver/Provider entry surfaces from being falsely classified as dead merely because no Activity directly references them.

## Exact mandatory Kotlin snapshot

The current Android Kotlin source set is enforced as an exact snapshot by `android/app/build.gradle.kts::verifyRiftOsAndroidSources`; validation derives the current set instead of documenting a hard-coded file count.

During the original audit the older list covered only a subset of live sources, which is why stale and missing entries now fail closed.

Gradle itself now compares the declared list exactly with the actual top-level Kotlin directory and rejects duplicate, missing or stale entries. The source validator independently performs the same exact-set comparison:
- a live Kotlin file omitted from Gradle -> failure;
- a stale deleted Kotlin path left in Gradle -> failure.

This prevents the final Builder's DEX verification contract from silently lagging behind current native ownership.

## Gradle preBuild gates

preBuild depends on:
- verifyRiftOsAndroidSources;
- verifyCodynexEditorPayload;
- validateRiftBrowserWebViewOwnership;
- syncRiftOsWebAssets.

### WebView ownership

Only explicit RiftBrowser-named owners may contain actual WebKit dependencies/WebView XML. Harmless comments or UI text containing the word `WebView` do not count as renderer ownership:
- RiftBrowserAndroidWebViewEngine.kt
- RiftBrowserWindow.kt
- RiftBrowserMcpAppBridge.kt
- RiftBrowserAppHost.kt
- RiftBrowserPreviewActivity.kt
- RiftBrowserRendererCrashGuard.kt

For Kotlin/Java, the Gradle gate strips block comments and full-line comments, then matches WebKit imports or fully qualified `android.webkit` / `androidx.webkit` references. For XML, it matches an actual `<WebView>` element. Matching dependencies outside that owner set are a Gradle build failure, and the six-owner allowlist must exactly equal the source files that currently use WebKit.

### Headless OS-execution assets

syncRiftOsWebAssets copies exactly:
- src/riftpp-core.js
- src/riftvm.js
- src/semnexis-bootstrap.js

into generated assets/www.

It does not package:
- index.html;
- styles.css;
- broad src/**;
- workspace-live/**.

RiftBrowser Android assets remain under android/app/src/main/assets and are merged separately.

## Documentation validator

validate-rift-docs enforces:
- documentation is unverified by default;
- exact verified-subsystem set;
- SOURCE_OWNERSHIP coverage;
- required local source-area READMEs;
- Markdown relative-link validity;
- retired protocol markers absent;
- historical RiftCLI roadmap documents are not active implementation contracts;
- the frozen N2 contract still names the canonical-kernel/specialist split, replaceable MemoryStore, SQLite reference backend and RiftStore experiment;
- ROADMAP.md and the N2 spec retain the hard N2.12-before-N3 promotion barrier.

This audit removed the brittle manually complete subsystem list assumption.

The validator now auto-discovers every docs/systems/**/README.md and adds it to the required set before maintenance/trust checks.

Therefore a newly created subsystem README cannot silently escape:
- minimum useful size;
- Source ownership;
- Failure signatures;
- Fix map;
- Validation sections;
- VERIFIED-marker trust enforcement.

This also closes the discovered gap where docs/systems/vortex-bridge/README.md existed but was not in the old requiredDocs array.

## Retained-reference focused tests

Some focused tests deliberately execute retained JavaScript as migration/regression oracles.

They are not live-runtime proof.

Current examples:
- test-rift-shell-batch.mjs — retained RiftShellBatch transaction oracle; now also asserts batch JS is not packaged and native RiftShell has no batch command.
- test-rift-app-import.mjs — retained RiftApps/RiftRT package-format oracle; now asserts those JS implementations are not packaged and current package execution is Android-owned.
- test-rift-path-compat.mjs — retained cross-module C:/D: compatibility oracle.

The scripts index was rewritten to label these honestly.

## Manual proof scripts

scripts/gate6d2-*.js and gate6d3-*.js are not test-*.mjs and are not automatically executed by npm run check.

They are retained/manual proof fixtures.

Frozen RiftLLM+ proof artifacts are not rerun simply because source validation runs.

## Build provenance

Gradle compiles:
- RIFT_SOURCE_SHA
- RIFT_BUILD_RUN_ID
- RIFT_BUILD_RUN_NUMBER

from Builder/environment values, defaulting to local when not supplied.

Native shell/MCP diagnostics consume this provenance.

## External Builder source resolution

The audited Riftos-builder workflow is workflow_dispatch only.

It:
1. resolves the requested private RiftOS ref to an exact commit SHA;
2. checks out that exact SHA;
3. verifies HEAD equals SOURCE_SHA;
4. requires the checked-out tree to have no tracked drift or untracked files;
5. syntax-checks both Builder shell scripts;
6. preflights Builder assumptions against the source Gradle contract (namespace/application ID, compile/target Android 36, minSdk 26, Java 17, release minification off and filename-to-DEX declaration shape);
7. runs the exact source commit's npm run check;
8. runs the dedicated Gradle validation tasks into `gradle-validation.log` before compilation.

Local unpushed workspace changes are never built by that worker.

## External Android build

riftos-build.sh:
- requires signing identity variables;
- runs npm run check;
- runs `verifyRiftOsAndroidSources` and `validateRiftBrowserWebViewOwnership` as a dedicated Gradle validation phase captured in `gradle-validation.log`; `verifyCodynexEditorPayload` remains a normal preBuild dependency;
- runs Gradle release assemble with Java 17/Android 36 only after that validation phase passes;
- requires unsigned APK output;
- zipaligns;
- signs with apksigner;
- verifies zip alignment;
- verifies signature/certificate;
- runs verify-riftos-apk.sh against the final signed APK.

A source-check pass therefore does not imply an APK exists.

## Final APK native verification

verify-riftos-apk.sh reads the current Gradle mandatory Kotlin list.

For every listed top-level Kotlin filename it requires the corresponding com/riftos/app class descriptor in packaged DEX.

It also explicitly requires private top-level RiftDevLabLocalAgent and embedded SOURCE_SHA.

For the current MCP relay, the final signed DEX must retain the passive push-diagnostic markers `mcp.event-bus`, `mcp.relay`, `event.created`, `cli.event.send`, `relay.ready`, `cli.replay.request`, `cli.replay.send` and `cli.ack`. Mandatory class descriptors prove the Kotlin owners exist; these markers prove the specific event/relay instrumentation survived compilation into the final artifact.

The final DEX smoke also rejects retired native migration descriptors (`RiftShellBridge`, `RiftSystemDump`, `AndroidWebViewBrowserEngine`, `RiftNativeAppHost`, `RiftPreviewActivity`, `RiftRendererCrashGuard`, `RiftNativeDispatcher`, `RiftTransferManifest`) so stale build-cache output cannot silently reintroduce removed native classes.

C1.1-P requires `RiftCorePackageEvents.kt` and `RiftCorePackageGrants.kt` in the exact Kotlin snapshot, signed-Dex retention of `riftos.core.packages.change/1`, `riftos.core.package-uninstall/1` and `uninstall-rapp`, and safe managed uninstall staging/rollback/grant cleanup. `RiftRappManager.kt` must have no `RiftRappHost` reference; installed-apps shell UI must subscribe/unsubscribe and call only Core package APIs. No actual RAPP is removed during source tests; signed APK user-manual Builder and live install/update/uninstall of a disposable test RAPP are promotion requirements.

C1.1-B2-A requires Core RAPP session FIFO/tickets, max-64 pending event count, max-1MiB queued payload budget, attachment-generation validation, and no UI/Activity callback references. The disposable RAPP host may keep callback map only; Source/Builder checks reject `PendingEvent`/`eventBusy`/host-owned FIFO and signed DEX must retain `eventQueueOwner`. User alone triggers Builder, with signed APK and device proof mandatory.

C1.1-B1 adds `RiftCoreAppExecutor.kt` to the mandatory source list. Source and Builder must verify that adapter encoding/decoding, QuickJS/native runtime dispatch, `RiftBoundedAsync`, and verified persisted state commits are Core-owned; `RiftRappHost` may only request execution and handle GUI/capability effects, never construct runtime engines or event workers. Signed DEX must retain `rift-core-rapp-event` and `eventExecutorOwner`. User triggers the Builder and supplies real device proof; `headlessExecution=false` until future capability-effect migration.

C1.1-A adds `RiftCoreAppSessions.kt` to the mandatory Kotlin source list; source validation and Builder preflight must prove that RAPP UI hosts attach/detach to Core records and use Core-owned program/event sequence, never Activity-owned identity/state. The signed APK must retain `riftos.core.sessions/1` and the honest `headlessExecution=false` marker. Only user's manual Builder and installed signed APK can promote this source change.

C1.0 mandates `RiftCoreApplication.kt` and `RiftCoreRuntime.kt` in the exact Kotlin source snapshot and the compiled APK; the manifest must name the Core Application, and RAPP/Build/Shell clients must delegate package/runtime/build calls into `RiftCoreRuntime`. `core status` identifies Core process state while accurately marking `appExecutionIndependentOfDesktop=false` until subsequent gates. Builder source and signed-APK guards must not allow the shell to regain Core service ownership. See `docs/systems/core-shell/README.md`.

C0.2.5 adds the generic `RiftExternalRuntimeProviders.kt` source to the exact Kotlin snapshot. Source + Builder must verify Android package-visibility action `com.riftos.runtime.EXECUTE_V1`, signer-pinned provider registry, bounded Binder call, and DEX-visible registry/IPC/status schemas. The provider APK and QuickJS deletion remain future device gates; `quickjs-kt`, existing shell QuickJS consumers and the compatibility fallback are deliberately retained until migration proof.

Gradle enforces an exact RiftOS-owned Kotlin-source snapshot and a generic native CMake source snapshot. C0.2 permanently removes mirrored Codynex/Rift++ editor Kotlin payloads, editor JNI source/targets, their source SHA locks, and the legacy Rift++ RPA2 adapter tied to the editor UI codec. Builder checks now forbid those sources/DEX classes/JNI libs and retain required generic RiftOS compiler, RAPP, signing and MCP proofs; full signed-APK/device verification is pending the user's manual build.

## Final APK asset verification

The Builder verifier was critically stale before this audit.

It still required:
- assets/www/index.html;
- assets/www/styles.css;
- every src file;
- workspace-live files;
- Workspace Records HTML marker.

That directly contradicted current native Gradle packaging and would reject a correct native APK.

This audit replaced the obsolete block.

Current Builder final-APK rules:
- use Build-Tools `aapt2 dump packagename` for packaged application ID and `aapt2 dump xmltree --file AndroidManifest.xml` for compiled minSdk 26 / targetSdk 36 values; the parser accepts AAPT2's decimal (`=26`, `=36`) or typed-hex rendering of those same integers. `badging` is retained only for the non-debuggable release assertion. SDK mismatches print the observed compiled-manifest SDK lines into the private smoke log;
- reject duplicate ZIP entries and unsafe absolute/`..` paths;
- reject packaged Kotlin/Java source, `.git` content and keystore material;
- require assets/www/src/riftpp-core.js byte-for-byte equal source;
- require assets/www/src/riftvm.js byte-for-byte equal source;
- require assets/www/src/semnexis-bootstrap.js byte-for-byte equal source;
- reject every other file under assets/www;
- explicitly reject index.html, styles.css, workspace-live, PWA/service-worker content;
- verify every non-Markdown asset under android/app/src/main/assets byte-for-byte.

## Builder publication

On successful build with publish=true:
- a private RiftOS prerelease tag is created against SOURCE_SHA;
- the verified APK is uploaded;
- upload retries are bounded to three attempts;
- a failed partial release is cleaned up.

On failure with publication enabled:
- private logs are zipped and attached to a private RiftOS prerelease.

Private source checkout and restored signing files are removed in the always() cleanup step.

## Signing behavior

Preferred signing uses four repository secrets:
- RIFTOS_KEYSTORE_B64
- RIFTOS_KEYSTORE_PASSWORD
- RIFTOS_KEY_ALIAS
- RIFTOS_KEY_PASSWORD

Current fallback behavior remains:
- decode source/android/riftos-debug.keystore.b64;
- alias riftosdebug;
- store/key password android.

This fallback is an alpha/development signing identity, not strong production publisher identity.

It remains intentionally unchanged in this audit because current project policy still permits alpha/debug builds when production signing secrets are absent.

A production-distribution policy should require private release signing and remove the fallback separately.

## External dependency/reproducibility limitations

The audited Builder uses version tags rather than immutable commit-SHA pins for GitHub Actions such as:
- actions/checkout@v7
- actions/setup-java@v5
- gradle/actions/setup-gradle@v6

Gradle itself is pinned to 9.5.0 and Android build tools to 36.0.0.

The mutable Action-tag trust surface is a current external supply-chain limitation; this audit does not claim byte-for-byte reproducible Builder infrastructure.

## Verification-marker contract

The documentation validator no longer hard-codes one calendar day as the only valid meaning of `CURRENT`. A verified subsystem may retain the date on which that subsystem was actually source-audited while another subsystem advances independently. The validator requires the correct verification heading/marker class, a real ISO date, and rejects future-dated markers.

Stale-document detection belongs to source ownership, changed-source impact, roadmap/patch-history synchronization and later Local Agent policy evidence—not a global `YYYY-MM-DD` constant that invalidates correctly re-verified docs or forces untouched docs to lie about their audit date.

## Source fixes in this audit

- expanded Gradle mandatory Kotlin snapshot from partial 32-file list to the exact current source set;
- source validator now compares Gradle list exactly to actual Kotlin tree;
- fixed Android README wording that still described a packaged web shell/runtime;
- docs validator now auto-discovers all subsystem READMEs;
- wiring validator now requires every test-*.mjs to be executed by package scripts;
- Patch 1 added `test-rift-diff-engine-v2.mjs`, which locks the bounded adaptive multi-hunk engine, Workspace Records delegation, source declaration and documentation ownership;
- Patch 2 added `test-rift-file-identity-v2.mjs`, which locks exact SHA identity semantics, bounded heuristic correlation, Workspace Records/query integration and ownership;
- Patch 3 added `test-rift-patch-sessions.mjs`, which locks state-bound provenance, honest unattributed fallback, writer integrations, optional MCP intent metadata and ownership/source declaration;
- Patch 4 added `test-rift-patch-manifest-v1.mjs`, which locks deterministic canonical/tree/change-set hashing, immutable private freeze bounds, record-chain/pruning/recovery semantics, checkpoint-sequence evidence, inert trusted state and absence of MCP freeze authority;
- Patch 5 added `test-rift-semantic-impact-v1.mjs`, which locks one shared PI-v2 parser, candidate-derived semantic scope, bounded incomplete-evidence behavior, ownership lookup, deterministic semantic hashing and absence of an MCP impact tool;
- Permanent RiftCLI retirement is verified by source-absence checks, preserved MCP event replay/ACK regression tests, Local Agent batch tests and Builder final-APK exclusion of `libriftcli.so`.
- Native RiftBuild added `test-riftbuild-native.mjs`, which locks workspace/output confinement, prepared binary APK inputs, honest unsigned status and zero process/CLI/MCP authority expansion;
- retained-reference tests now assert their JS implementations remain un-packaged/unwired;
- script index labels retained tests honestly;
- external Builder APK verifier replaced obsolete full-web-shell requirements with exact Rift++ Core/RiftVM asset verification;
- Builder README removed RiftNativeAppHost and old workspace-live packaging claims.
- wiring validation now recognizes the installed-app host's dynamic `https://app-<token>.riftos.local` origin instead of requiring the obsolete literal `app.riftos.local` string;
- native workspace-app WebView exclusion now checks actual WebKit imports/FQNs instead of rejecting harmless documentation/UI text containing the word `WebView`;
- transport validation now recognizes the same dynamic installed-app origin and checks Workspace Records against its dedicated owner/API instead of scanning unrelated `RiftNativeWorkspaceApps` strings such as the Settings word `approved`;
- SOURCE_OWNERSHIP now uses the validator's canonical exact trust sentence (`Ownership does **not** imply...`) so the documentation-trust gate checks meaning and wording consistently;
- focused retained-reference tests for RiftLLM and Workspace Records now explicitly prove those JavaScript/HTML adapters remain un-packaged instead of presenting retained globals/UI as live APK surfaces;
- shell/WebView validation now detects actual WebKit dependencies rather than harmless comments containing the word `WebView`;
- the external Builder now preflights namespace/application ID, compile/target Android 36, minSdk 26, Java 17, release-minification-off and filename-to-DEX assumptions before source tests/Gradle, so future verifier drift fails with a direct stale-Builder error;
- Builder now runs the two Gradle validation tasks in a dedicated pre-compilation phase/log;
- final APK smoke now rejects duplicate/unsafe ZIP entries, source/VCS/keystore leakage and retired native DEX descriptors;
- transport/wiring validation now proves the actual `preBuild` dependency wiring instead of matching implementation text from the WebKit regex, preventing validator self-drift when the ownership matcher changes;
- Android root Gradle pins built-in Kotlin KGP `2.4.10` so `quickjs-kt 1.0.14` (published with Kotlin 2.4 metadata) is compiled by a compatible Kotlin toolchain instead of AGP's lower default KGP;
- Builder preflight independently requires the same KGP 2.4.10 + QuickJS 1.0.14 + coroutines 1.11.0 tuple before source tests/Gradle compilation;
- focused shell/Rift++ tests now lock the `RiftNativeShell` helper structure (one tokenizer + one single-argument confined resolver + `joinDisplay`) and the headless QuickJS script-constant visibility exposed by the first successful Kotlin compilation attempts; the resolver signature is intentionally the normalized-display-path form used by all live shell call sites.

## Critical invariants

- npm check runs before Gradle in Builder;
- every focused test is wired into npm check;
- every subsystem README is discovered by docs validation;
- Gradle mandatory Kotlin list exactly equals current Kotlin source directory;
- Gradle source snapshot and WebKit-owner validation run explicitly before compilation and remain wired into preBuild;
- only Rift++ Core, RiftVM, and the Semnexis bootstrap enter generated assets/www;
- final Builder APK independently proves those three assets byte-for-byte and rejects any additional OS web asset;
- final Builder verifies native DEX/source provenance, alignment and signatures;
- Builder builds a clean exact Git commit, not phone workspace bytes;
- source validation is never called APK/device proof;
- retained-reference tests remain clearly non-live.

## Failure signatures

- Kotlin source exists but not in Gradle mandatory list -> snapshot-gate regression;
- deleted Kotlin path remains in Gradle -> stale snapshot regression;
- subsystem README exists but bypasses docs validator -> discovery regression;
- test-*.mjs exists but npm check never runs it -> test-gate regression;
- Builder verifier requires index.html/styles.css/workspace-live -> stale native-migration regression;
- duplicate/unsafe ZIP path, leaked source/VCS/keystore material or retired native DEX descriptor passes Builder smoke -> artifact-validation regression;
- unexpected assets/www file passes Builder smoke -> packaging regression;
- npm check is moved after Gradle -> waste/gating regression;
- Builder source tree is dirty but proceeds -> provenance regression;
- SOURCE_SHA absent from final DEX when Builder supplied it -> provenance regression;
- zipalign/apksigner verification disappears -> artifact-validation regression;
- docs call retained JS test live proof -> validation/trust regression.

## Fix map

Root source-check sequencing -> package.json.

Source/runtime wiring -> scripts/validate-rift-wiring.mjs.

Cross-layer authority/security -> scripts/validate-rift-transport.mjs.

Docs/trust/ownership -> scripts/validate-rift-docs.mjs.

Focused tests -> scripts/test-*.mjs.

Android preBuild/source/assets/WebView gate -> android/app/build.gradle.kts.

External build execution -> Riftos-builder scripts/riftos-build.sh.

Final APK content/provenance smoke -> Riftos-builder scripts/verify-riftos-apk.sh.

External workflow/signing/publication -> Riftos-builder .github/workflows/riftos-worker.yml.

## Validation

Second source audit must verify:
- package check order;
- every test-*.mjs appears in package commands;
- syntax coverage;
- exact Gradle-vs-Kotlin snapshot;
- preBuild dependencies;
- exact two generated OS assets;
- WebView allowlist;
- dynamic subsystem README discovery;
- SOURCE_OWNERSHIP checks;
- retained test labeling/packaging guards;
- Builder exact-SHA + clean-tree gate;
- Builder npm-check-before-Gradle ordering;
- zipalign/apksigner verification;
- final DEX/native provenance checks;
- final exact two-asset www rule;
- native Android assets byte-match source;
- signing fallback accurately documented;
- external Builder limitations accurately separated from source/device proof.

No npm/Gradle/Builder execution is claimed by this source audit itself.
