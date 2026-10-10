# RiftOS Public and Cross-Layer Surfaces

## 2026-10-09 — C2-A physical errno13 does NOT expand Core admin capability

On signed #678 the exact one-use `runtime.register` EMPTY registry proof failed because Android denied hard-link creation (`atomic-create-only-publish / ErrnoException / errno 13`). Core still had no live registry or journal, zero providers or elevated grants. The internal C2-A proof now uses private `Os.open(O_CREAT|O_EXCL|O_NOFOLLOW)`, journalled write/fsync and immediate verified rollback instead; Core runtime registry readers lock out partial changes and fail closed on pending journal. The external Binder admin contract is unchanged: exact native Shell PID+signer+TTL+operation/target, one-use approval and versioned success or safely rolled-back failure with bounded stage/type/errno. This is not a general `runtime.register` API, no RAPP can use it and no C2-B2 provider enrollment is enabled. User-signed on-device success still required.



## 2026-10-09 — C2-A exact registry proof failure visibility (SOURCE; device acceptance pending)

On user-signed #676, `execute-registry-proof` consumed its exact native-approved ticket but raised a Core transaction exception; no registry or journal persisted and Shell displayed `Core IPC response JSON missing`. **Root cause not yet identified.** Same-shape response `riftos.core.admin-registry-proof/1` may now report `transactionCommitted:false`, `rolledBack:false`, `registryRestored:true`, `providerRegistered:false`, `pendingJournal:false`, and sanitized `failureStage`, `failureType`, `failureErrno` **only when Core verifies absence of live registry, scratch and pending journal**. Any unsafe incomplete cleanup still throws, never returns a benign failure response. `core status.adminRegistryProof` additionally exposes conservative `pathStatusAvailable`, `registryExists`, `temporaryRegistryExists`, `lastFailureStage/Type/Errno`; unknown path states are treated as occupied. No user-selected arbitrary targets, app admin rights, credential leakage or general provider enrollment are added. Java NIO create-only hardlink may retry Android kernel `Os.link` under the same exclusive non-replace semantics. Physical retest required.



## 2026-10-09 — C2-B1 read-only Core runtime-provider candidates (SOURCE ONLY)

Authenticated production `:riftShell` native admin Binder adds **`discover-providers`** without creating/approving/consuming a privileged ticket. Core `RiftExternalRuntimeProviders.discoverCandidates` uses Android's installed PackageManager with explicit generic service action `com.riftos.runtime.EXECUTE_V1`, metadata keys `riftos.runtime.provider.id` and `riftos.runtime.executor.kind`, exact exported/enabled service and singleton signing certificate check, supported current executor kinds, bounded candidates and SHA-256 component/signature identity digest. Response: `riftos.core.runtime-candidates/1` with `candidates:[]`, `count`, `enrollmentEnabled:false`, `registryModified:false`. Native Admin Approvals button only renders inventory, no provider installed/registered or authorized. An empty list is a valid result. C2-B2 actual user-approved atomic provider registry changes/rollback remain future; full C2 physical acceptance follows major-milestone policy unless actual Android security boundary forces earlier testing.



## 2026-10-09 — C1.4-C2-A isolated runtime registry proof surface (SOURCE ONLY)

C1.4-C1 was fully device-proven via USER-signed #669 and actual real RiftShell PID change with old approved system.fs.write ticket audit `revoked / shell-replaced`. Newly staged C2-A reuses the exact native-only `shell.admin.consent` Core Binder gateway with operation `runtime.register` and fixed target `core://runtime-providers/registry.json#empty-c2a`. The new action `execute-registry-proof` returns **`riftos.core.admin-registry-proof/1`**, validated ONLY for that action by `RiftShellCoreClient`; all original B/C1 schemas persist. Core enforces signer/PID/UID, 45-second one-use tickets, foreground lease, durable audit and exact-target match; Core owns a journalled temporary EMPTY registry publish/verify/delete and recovery on startup. Read-only `core status.adminRegistryProof` reports pending journal/existing registry without ticket contents. This is **not** an API for actual provider enrollment or generalized runtime register/install authority: the provider array is always empty and configured registry is never overwritten. No external RAPP or terminal may invoke the administrative Binder mutation. SOURCE ONLY pending user-manual signed build/device QA; C2-B real provider admission, C2/C3 and C1.5 are future gates.



## 2026-10-09 — Authenticated admin IPC has action-specific response schemas

The shell.admin.consent request schema stays riftos.shell.admin-consent-request/1. B actions request, decide, revoke, consume-proof, window-closed, and status return riftos.core.admin-consent/1. Only execute-rollback-proof returns riftos.core.admin-rollback-proof/1, including Core PID and fixed transaction result. The Core already produced this schema on signed #668, but Shell was incorrectly requiring admin-consent for all actions. The source correction selects the exact schema from the recognized action and retains existing PID checks; this does not grant any additional authority. C1 effect still lacks signed device proof, and this correction needs a new USER-MANUAL Builder release.

## 2026-10-09 — C1.4-C1 fixed Core-only virtual C: rollback transaction API SOURCE

Authenticated Core `shell.admin.consent` now permits two additional strictly validated actions: `execute-rollback-proof` requires Core-issued approved exact-scope `system.fs.write` one-use token, live OS-attested production real-shell PID/signature and Core foreground lease, and performs ONLY a fixed virtual C: canary write/fsync/readback/delete with durable crash journal; `window-closed` revokes unused tickets for that exact real shell PID without trusting UI bearer bookkeeping. New Core `riftos.core.admin-rollback-proof/1` status `adminRollbackProof` reports `pendingJournal` and `canaryExists`. Explicit native fixed-mode selector/confirmation UI is not a RAPP or terminal privilege interface. Existing C1.4-B `consume-proof` remains NO-EFFECT and `systemCapabilities.adminElevationEnabled=false`, grantCount0/default-deny; all other privileged effects rejected. Signed-device proof pending, not a generalized public filesystem API or Android root.


## 2026-10-09 — C1.4-B `shell.admin.consent` real signed-device consent proof PASS

User manually signed/installed Builder #667 executable `bba25e69`. Exact Core `riftos.core.admin-consent/1` status verified 45,000ms timeout, max8 tickets, no effect, zero persistence. Native Admin Approvals made real `shell.admin.consent` authenticated calls; user-facing Android dialog displayed fixed `system.fs.read` / `/C:/System` scope. `request` / native Allow once / `consume-proof` succeeded and replay was denied; separate Deny, Cancel, Revoke and 45s expiration each invalidated authorization. Core policy remained admin elevation disabled, effective admin grants0; read-only audit events included requested, approved, denied, revoked, expired, consumed, with no ticket secret, target path or signer field. Final Core no app/sessions/surfaces/queued events, all four original RAPPs installed. **Consent-only B DEVICE PASS**; PID-replacement and close-with-unused-approval revocation still source-constrained but not separately hardware tested. `shell.admin.consent` grants NO real protected operation. C1.4-C effects and rollback not started.


## 2026-10-09 — C1.4-B trusted Core admin consent-only IPC SOURCE CANDIDATE

Authenticated **`shell.admin.consent`** Core ContentProvider method, strict request schema `riftos.shell.admin-consent-request/1`, response schema `riftos.core.admin-consent/1`, supports native graphical system UI request, decide(Allow once/Deny/Cancel), revoke, status, and consume-proof. Exact Binder PID/UID/process name and installed RiftOS signer checked, Core-selected actor, fixed restricted `system.fs.read`/`/C:/System` demonstration target, 45s TTL, max8, secure ephemeral 128-bit bearer; no persistence or leaking token to `core status`, only aggregate counts. Real-shell PID change revokes stale tickets. Audit extends existing `riftos.core.system-capability-audit/1` bounded metadata for requested/approved/denied/revoked/expired/consumed. A true Core-approved ticket has **NO privileged effect adapter** in B; it authorizes only an atomic no-effect consumption demonstration, not actual system file reads, software install, runtime registration or protected process kills. Native terminal and RAPP cannot self-grant admin; Core default deny remains. This API is source-only until user-manual signed build/device verification; C1.4-C future actual narrowly scoped effects/rollback.


## 2026-10-09 — C1.4-A Core deny/audit APIs verified on-device (#663)

Signed Builder #663 source `74f2688e` device-verified `riftos.core.system-capabilities/1` and `riftos.core.system-capability-audit/1`: Core PID29992 separate graphical RiftShell PID30093, `adminElevationEnabled:false`, `defaultDecision:deny`, `grantCount:0`, native read-only `permissions policy` and `permissions audit`. Rejected native protected `kill kernel` logged `native-shell/process.protected.kill`. Separate disposable Core RAPP `c14a-admin-denial-probe-20261009` obtained ordinary `fs.write` user consent but system file request `/C:/System/c14a-policy-negative-denied.txt` was denied with effect token44 and logged `rapp:c14a-admin-denial-probe-20261009/system.fs.write`; nonexistent forbidden path remained absent. After remote Close/managed uninstall, four production RAPPs still installed, disposable grant revoked, Core session/surface/event/focus clean and persisted audit count2. **C1.4-A DEVICE PASS**, only deny/audit foundation. B/C trusted system elevation and actual restricted operations not yet implemented; no Android root access.


## 2026-10-09 — C1.4-A Core admin policy/audit introspection SOURCE CANDIDATE

Added Core `riftos.core.system-capabilities/1` (read-only `core status.systemCapabilities` or native `permissions policy`) with `adminElevationEnabled:false`, `defaultDecision:deny`, `grantCount:0` and exact restricted system operation families; bounded durable `riftos.core.system-capability-audit/1` (native read-only `permissions audit`) records denied actor/operation/reason/timestamp metadata without secret payloads or raw filesystem paths. Normal app `fs.read`/`fs.write` grants do NOT authorize system files, software installation, runtime registration or protected processes. `RiftRappCapabilityBroker` and native `kill` keep the pre-existing denial barriers and now audit attempted escalation. No grant/mutation/authorization API exposed for elevated operations in A. Android OS user permissions and root privileges unchanged. **C1.4-A source-only until user manual signed Builder and device negative tests; B/C actual trusted elevation not started.**


## 2026-10-09 — C1.3-E production graphical Core recovery IPC DEVICE PASS

Signed installed Builder #661 source `fc486831` physically validated `riftos.core.shell-recovery/1`: Core's bounded real-shell watcher kept default Android Core PID10730 and disposable Core RAPP generation1 running across an actual OS-confirmed production shell process termination (PID10831). It observed a newly automatically running graphical shell PID14251, restored old native desktop/window snapshot through authenticated `shell.recovery.claim`, reattached the exact still-running RAPP generation without BOOT, and displayed `C13E_PRECRASH_661` in its recovered window before manual relaunch. Afterward the real new UI sent accepted ACTION count2 and TEXT_INPUT `C13E_POSTRECOVERY_661`. Foreground/background guard worked; earliest QA invocation was rejected fail closed; user-approved subsequent kill succeeded once. Core stop/uninstall cleaned only disposable; four protected originals untouched. **C1.3-E DEVICE PASS**, not a proof of browser transient view restoration or all possible Android process-policy modes. C1.3-C/D stay separately accepted.


## 2026-10-09 — C1.3-E Core real-shell recovery IPC SOURCE CANDIDATE, no device contract yet

Following device-proven C1.3-D #660, source adds the **authenticated Core-only `shell.recovery.claim`** Binder method, versioned schema `riftos.core.shell-recovery/1`, and **`shell.reattach`** with strict Core-owned original generation validation and NO BOOT. Production `:riftShell` first claims Core-held previous native window snapshot before reporting the new PID; normal backgrounding reports foreground false. Core-owned `RiftCoreShellRecovery` observes the real named process, bounds restart attempts/cooldown, retains last graphical snapshot, revokes dead input focus, attempts Android Activity relaunch, exposes honest recovery diagnostics, and reserves an exact-process verified disposable-only devlab test hook. Native reconstruction restores visible windows and clamped frame geometry, but does not promise arbitrary browser/session-specific transient state. C1.3-E has NOT been compiled, signed, installed or device tested; in particular Android background launch policy may prevent automatic restart. **No claim of automatic graphical process recovery until user-manual E build and actual process-loss proof.** C1.3-D remains fully device-proven separately.


## 2026-10-09 — C1.3-D REAL production RiftShell cross-process IPC DEVICE PASS

Builder **#660**, run `37891844448`, signed installed executable source `d43a30e` on the user's Android device PROVES default launcher `RiftShellActivity` actually hosts graphical desktop/taskbar/window manager/RAPP rendering in distinct `:riftShell` PID25436, Core PID25396. Real bounded/generation-scoped Core Binder IPC: remote Installed Apps returned four Core-owned packages, Core-first disposable RAPP gen1 published surface and remote ACTION/TEXT_INPUT `C13D_660_REMOTE`, successful GUI close/stop and disposable uninstall, all original RAPPs untouched. Native Files and RiftBrowser WebView worked. User manually typed `ps` into the actual remote RiftShell terminal; screenshot shows protected kernel/desktop/shell and focused terminal. This is the final terminal command Core-delegation acceptance. **C1.3-D PROMOTED**. Core remains execution/grant authority, and `:riftShellProbe` remains read-only diagnostic, NOT production shell. Future C1.3-E will need an independent signed manual build + hardware proof of killing/restarting **production** shell and reconstructing desktop/windows while Core and RAPP sessions survive; no claim of process-death recovery yet.


## C1.3-D — real `:riftShell` IPC source candidate (2026-10-09, user-manual build/device pending)

Production graphical RiftShell is the default Android launcher Activity `RiftShellActivity` declared with `android:process=":riftShell"`, distinct from the read-only `:riftShellProbe`. The shell owns desktop, taskbar, windows, RAPP rendering and Android input but cannot instantiate `RiftCoreRuntime` or `RiftCoreAppExecutor`. The Core-owned `RiftCoreSurfaceIpcProvider` retains existing read-only snapshot method and adds same-UID/exact-process-name-gated, bounded Core RAPP list/start/stop/focus/event, terminal, package, UI consent and desktop state operations. Public/private protocol schema markers: `riftos.core.shell-control/1`, `riftos.shell.event/1`, `riftos.core.shell-ui/1`, `riftos.shell.desktop-ipc/1`; Core still validates generation/focus and settles single-use effect/consent tickets. Shell can submit only supported graphical/input events (not BOOT or Core effect results); separate Core/UI ownership is verified by manifest/source/Gradle and signed DEX markers. Real installed-device proof needed before calling this public contract stable; C1.3-E automatic shell restart remains distinct and unimplemented.


## 2026-10-09 — C1.3-C DEVICE PASS / same-process Core execution contract

Signed manual Builder #655 source `8d7608f` ran the verified disposable RAPP in Core and survived actual `MainActivity.recreate()` without Core process restart, extra BOOT, or new app generation. User screenshot and separate Core subscriber confirmed working ACTION and `C13C_MANUAL_655` TEXT_INPUT with generation1 and advancing immutable surface revisions. Background Core focus settled to null and cleanup left zero Core runtime state and the original three installed RAPPs. Production process-separated graphical client and IPC are NOT part of this contract: **C1.3-D NEXT** and **C1.3-E AFTER D** require separate user-manual signed device proofs.


## C1.3-C #654 DEVICE PARTIAL, foreground focus repair pending

Build #654 source 130dea17 verified Core-only BOOT, graphical same-generation ACTION/TEXT_INPUT state, and Core-initiated stop. Android Back backgrounded the Activity but kept a focus lease and graphical subscriber; it did NOT prove Android Activity destruction. The next source corrects focus gating on pause/blur and foreground resume. Authorized Local Agent Dev Lab `recreate-main-activity-proof` (requiring an active disposable Core RAPP and foreground MainActivity) schedules actual Android `Activity.recreate()` without stopping Core. Require new user-manual signed APK/device proof. C1.3-D/E are not started.


## C1.3-C — Core app execution SOURCE CANDIDATE (2026-10-08; device proof pending)

Internal Core app control is `riftos.core.apps/1`: `start(id)`, `openForShell(id)`, `offerEvent(id, currentAttachmentGeneration, genericEvent)`, `stop(id)`, `status()`. Core verifies installed executable, owns BOOT/FIFO/queued focus authorization/executeChained and publishes immutable `riftos.core.app-surfaces/1` frames. RiftRappHost consumes surfaces and submits input with the Core generation only. MainActivity lifecycle events no longer trigger RAPP execution or session detach. Activity disposal revokes GUI focus. This is same-process code, NOT authorized remote Binder input: production separate RiftShell IPC belongs to C1.3-D, and automatic restart to C1.3-E. Manual Builder + installed-device regression remain pending.


This inventory separates live packaged authority from retained reference/compatibility JavaScript. A name in retained source is not automatically a live APK surface.

## Live native/system surfaces

| Surface | Owner | Purpose |
| --- | --- | --- |
| `RiftShellExecutor` | `RiftNativeShell.kt` | Process-owned bounded shell executor used by native Terminal and MCP. |
| Rift++ headless runtime | `RiftHeadlessJsRuntime.kt` | Loads trusted Rift++ Core/RiftVM assets in QuickJS; no DOM/WebView/network/process authority. |
| Semnexis bootstrap runtime | `RiftHeadlessJsRuntime.kt` + `RiftNativeShell.kt` | Fixed bounded `semx` compiler/IR/backend commands over the packaged Semnexis asset; source/output/artifact budgets are enforced, only the two ARM32 artifact commands may write to exact fixed RiftFS paths, generated artifacts are not executed, and the host has no process/network authority. |
| Bounded `qjs` developer runtime | `RiftHeadlessJsRuntime.kt` + `RiftNativeShell.kt` | Evaluates bounded classic JavaScript with captured output and read-only confined RiftFS text access; no file-write/process/network/Android/Git authority. |
| MCP tool catalog | `RiftToolHost.kt` | Canonical fixed 21-tool schemas/permissions/audit, including passive `rift_debug`, persistent read-only `rift_mcp_reconcile`, and the persistent `rift_local_agent_batch` control plane. Batch may orchestrate bounded device/UI work plus existing ToolHost/Sandbox engineering operations such as read/write/patch/search/symbol/reference/audit/scan/diff/export; raw RiftShell batching remains disabled. |
| Workspace/Code Mode | `RiftToolSandbox.kt` + `RiftToolHost.kt` | Workspace-only filesystem and Project Intelligence; model-facing `rift_workspace_exec` is one operation per call with per-call rollback, while multi-op/batch execution is disabled. |
| Workspace Records | `RiftWorkspaceRecords.kt` | Private local history/diff record source. |
| Native desktop | `RiftNativeDesktop.kt` | Android window/taskbar/z-order/geometry authority. |
| Native built-ins | `RiftNativeSystemApps.kt`, `RiftNativeWorkspaceApps.kt` | Terminal, Task Manager, Files, Editor, Dev Lab, Workspace Records and Settings. |
| Native Git | `RiftNativeGit.kt` | Git/GitHub workflow with Android Keystore credential access. |
| Generic build-provider platform | `RiftLocalBuildCapability.kt`, `RiftJvmDexService.kt`, `RiftBuildManagedToolchains.kt`, `RiftManagedJvmToolService.kt`, `RiftNativeBufferCompilerService.kt`, `RiftBuildPlatformTools.kt`, `RiftApkV2Verifier.kt`, `RiftBuildInstaller.kt`, `RiftRappCapabilityBroker.kt` | Reusable provider-facing execution/DEX/RAPP/verification/install capabilities only. Build recipes, prepared-tree production, APK packing and APK-v2 signing live in the external provider. `build.local` is compiler execution + JVM DEX; `signing.identity` keeps the private key in Android Keystore; RiftOS independently verifies the finished APK before PackageInstaller. |
| Rift++ app diagnostics | `RiftAppDiagnosticBridge.kt` + `RiftNativeShell.kt` | Allowlisted localhost-UDP receiver for fixed Rift++ proof/editor packages. `riftcrash` controls bounded start/status/capture/latest/reset state and persists advisory evidence under `D:/Diagnostics/riftpp`; it does not parse Rift++/ELF semantics or provide privileged tombstone access. |
| Vortex bridge/agents | `RiftVortexBridgeClient.kt`, `RiftVortexLocalAgent.kt` | Fixed local Binder/accessibility development surfaces. |
| RiftLLM Dev/training service | `RiftLlmDevClient.kt`, `RiftTrainDataTaskRunner.kt`, `RiftNativeShellServices.kt` | Fixed bounded standalone RiftLLM API/training commands, including fixed frozen-B2 priming, no-argument RiftPack qualification start/status, and fixed no-argument real process-death recovery start/status. |

## C1.3-B — Remote IPC client process termination/relaunch verified (#653)

Signed manually built RiftOS source `31f4a8d4`, Builder #653, demonstrated that `:riftShellProbe` PID20026 can terminate itself through its explicitly guarded UI control while Core PID19386 and the disposable RAPP's Core generation1/revision1 READY frame survive. The user-triggered control only kills the exact validated remote-process PID, never Core. Explicit `core ipc-view` relaunched a new remote PID20077 and rendered the SAME Core frame/generation/revision over private Binder. Closed remote, stopped/uninstalled disposable; Core clean and original 3 RAPPs intact. **DEVICE PASS for manual remote shell-probe process recovery**, not production RiftShell migration, automatic recovery, Core death survival or writable/authenticated IPC.

## C1.3-A — Separate-process Core snapshot IPC device verification (#652)

Manual Builder #652 source `e7ceae80` device-proved read-only Binder Core snapshot transport. Core PID13231 and different `:riftShellProbe` PID15670 rendered READY frame gen1/rev1; remote close did not stop Core-owned disposable app, normal GUI ACTION/TEXT_INPUT persisted `IPC_C13A`; reopened remote IPC viewer rendered gen1/rev5 updated text. Closing viewer did not stop GUI; further ACTION accepted count2. Core session/surface cleanup and uninstall succeeded with three originals intact. **DEVICE PASS for read-only separate-process frame IPC**, not full shell process crash recovery or authenticated input channel.

## C1.3-A — Private read-only Core surface IPC (source-only)

`RiftCoreSurfaceIpcProvider` is a non-exported Android ContentProvider owned by main Core process (authority `com.riftos.app.core-surface-ipc`), used exclusively through explicit `ContentResolver.call(uri, "snapshot", appId, null)`. It returns a versioned `riftos.core.surface-ipc/1` Bundle JSON with Core PID, present/absent flag, generation, revision, layout and bounded 256 typed node objects. ID ≤128 chars, encoded reply ≤256 KiB; query/insert/delete/update unsupported. `RiftRemoteShellProbeActivity` in independent `:riftShellProbe` Android process reads snapshots through Binder only and renders them read-only. `core ipc-view <id>` dispatches proof viewer only for a live Core surface. Remote client never instantiates Core singleton or executable application session and cannot send input/focus events. This is a **private diagnostic proof**, not public authenticated Core shell IPC, subscription transport, automatic reconnect or a full separate RiftShell desktop. SOURCE-ONLY pending manual signed APK + actual different-PID live RAPP test. Original same-process D2 client and working app host remain unchanged.

## C1.2-D2 — Alternate graphical client verified on device (#651)

User's manual RiftOS Builder #651, source `4e8ea83f`, verified `core alt-graphic-open <id>` actually launches the non-exported alternate graphical Activity. It displayed Core generation 1/revision 1, READY plus read-only action/text-input nodes. Closing it did not stop the Core-only RAPP. After normal RiftShell GUI accepted and persisted ACTION/TEXT_INPUT `D2_GRAPHIC`, reopening the alternate Activity showed the same updated Core frame at gen 1/revision 5. Closing this reader did not stop or reset the original GUI (which continued handling ACTION, count 2). Disposable cleanup left Core app/session/surface zero and three original RAPPs intact. **Read-only same-process alternate graphical consumer device PASS**, not a standalone process or crash-surviving IPC shell. C1.3 must prove these separately.

## C1.2-D2 — Alternate native graphical shell surface consumer (source-only)

`RiftAlternateGraphicalShellActivity` is a second graphical client for Core-owned immutable `RiftAppAbi.Frame` snapshots. It is intentionally independent of the existing `RiftNativeDesktop` and `RiftRappHost`, and owns **no app lifecycle, focus, event input, or executable**. It subscribes to `RiftCoreAppSurfaces` updates when visible, unregisters on stop, renders generic flow/absolute nodes into read-only Android views, and displays Core attachment generation/surface revision. `core alt-graphic-open <id>` requires an existing Core surface and launches the non-exported Activity using a scoped intent. Signed-APK source/manifest/Builder gates verify that wiring. It is **not** a separate Android process, and cannot survive Core Android process death or prove C1.3 shell IPC/crash recovery. Normal RiftShell rendering remains unchanged. C1.2-D1 terminal/GUI dual-client updates device-proven on manual Builder #650; D2 source-only pending user build/device proof.

## C1.2-D1 — Alternate terminal shell client (source-only)

The second, read-only in-process `RiftAlternateShellClient` projects versioned Core-owned `riftos.core.app-surfaces/1` immutable frames to `riftos.shell.client.terminal/1` textual and typed-JSON output. Unlike `RiftRappHost`, this client owns **no executable RAPP session or application input**, no Activity/View, no focus lease and no desktop window. It subscribes to Core surface updates and removals; up to eight watched app IDs, bounded 32 KiB terminal rendering, sanitized control characters, nonrecursive parent-depth traversal and an explicit unsubscribe lifecycle. Exposed as `core alt-list`, `core alt-attach <id>`, `core alt-render <id>`, `core alt-detach <id>`; `alt-render` returns current Core frame revision, generation, typed node data and a terminal text rendering. A graphical shell can continue rendering the same RAPP simultaneously. `shellProcessIndependent=false` remains explicit. Source-only until manually signed new APK dual-client tests; C1.2-C2 graphical handoff device-proven on manual Builder #648. This is NOT a separate graphical shell process or C1.3 IPC proof.

## C1.2-C2 — Transfer Core-booted application to graphical shell (source-only)

`RiftCoreAppLifecycle.claimForShell(payload, adapter)` transfers the existing Core RAPP attachment **only** when the app is `running`, its installed executable identity still matches, and its generation-matched immutable Core frame already exists. The active Core-only registry entry is retired, not the Core session. `RiftRappHost.open` then renders that frame directly and does **not** send another BOOT event. The Core program state and session generation remain unchanged, and subsequent GUI ACTION/TEXT_INPUT requests continue on the claimed session. Ordinary GUI starts still work through the existing path. These are same-process clients, not authenticated alternate-shell IPC. C1.2-C1 Core-only BOOT/stop passed real device run #644; this transfer is not yet device-proven.

## C1.2-C1 — Core-only installed RAPP BOOT/stop lifecycle (source candidate)

`RiftCoreAppLifecycle` exposes `riftos.core.apps/1` lifecycle status with a bounded Core app registry (`starting`, `running`, `failed`). Generic `core app-start <id>` resolves the installed program through Core package validation and its runtime adapter, creates an attachment only if no other client owns the app, then sends `BOOT` through the Core executor. That Core execution persists state and publishes a typed Core frame **without opening a graphical desktop window**. `core apps` returns a read-only snapshot; `core app-stop <id>` tears down only that Core-owned attachment. The existing `riftbuild launch-rapp` graphical host path is unchanged.

The status field `coreOnlyBootSupported` distinguishes this bounded path from `fullAppExecutionIndependentOfDesktop=false`, which remains pending full Core-owned event lifecycle and independent shell clients. This is source-only, not yet user-built/device proven; it does not grant shell-independent Android process persistence, a separate Core APK, a second rendering client, or full headless app execution.

## C1.2-B2-B2 — Core focused-input enforcement (source-only)

`RiftCoreInputFocus.requireCurrentLease(appId, attachmentGeneration)` authorizes only the current Core focus lease. `RiftCoreAppSessions.offerEvent` checks this for the generic user input kinds ACTION, POINTER_DOWN/MOVE/UP, KEY_DOWN/UP, and TEXT_INPUT, **in addition to** the previously proven typed target and executable generation checks. Tickets record an `admittedFocusRevision`; `authorizeQueuedEventDispatch` refuses delivery if focus is absent or the lease revision differs, including an away/back focus ABA. BOOT, resize and lifecycle events remain independent of focused user input. On denial the graphical client settles its UI callback without changing app program state and reuses the current immutable Core snapshot, not a fake interpreter frame. `core focus.focusEnforcedForInput=true` is truthful only once this source is built/installed. This is still in-process; separate shell client identity/IPC and shell-less RAPP execution remain later gates.

The B2-B1 Core lease lifecycle was proven on-device in run #641. B2-B2 itself requires a new user-triggered Builder and live-action/text-input regression before promotion.

## C1.2-B2-B1 — Core focus lease/notification contract (source-only)

`riftos.core.input-focus/1` stores a monotonic revision and optional current `{ appId, attachmentGeneration }` focus lease. The replaceable desktop reports preferred foreground window changes to `RiftCoreAppSessions.requestFocusFromShell`, which authorizes only an attached Core app generation and treats system/unregistered windows as requests to clear focus. A new attachment generation, detach, close or package invalidation revokes the previous lease. `core focus` and `core status.inputFocus` are read-only. `focusEnforcedForInput=false` remains until the next source/device gate: shell focus reports are advisory, not independent cryptographically verified client identity or a permission grant. No Android View is held in Core and no shell-less process is claimed.

## C1.2-B2-A — Core authorization of typed app input events (source-only)

The in-process shell can submit generic `RiftAppAbi.Event` requests through `RiftCoreAppSessions.offerEvent`, but Core validates that the named `ACTION` or `TEXT_INPUT` target exists with the correct node kind in `RiftCoreAppSurfaces` for the current session attachment generation. Named keyboard events require a current node ID; untargeted keyboard/canvas pointer events remain supported. No shell may enqueue a forged `HOST_EFFECT_RESULT` event, and only supported runtime-adapter event kinds pass. Invalid input is returned as a generic error through the disposable shell callback; it is not granted permission through a View tag.

This is a Core input *target* authorization gate, not yet Core-owned focus arbitration, independent app launch, shell-less execution or the process-separated shell. The C1.2-B1 Core-snapshot GUI path was proven on an installed RAPP in run 638; B2-A requires the next user-triggered Builder/device test.

## C1.2-B1 — Generic graphical client consuming Core snapshots (source-only)

The current RiftShell `RiftRappHost` reads the final `RiftCoreAppSurfaces.snapshot(appId)` as the authoritative frame and checks `attachmentGeneration == coreAttachment.token` before drawing. The raw `RiftCoreAppExecutor.Outcome.frame` is no longer passed directly into the UI renderer. An absent or stale surface returns a bounded presentation error rather than rendering a previous application generation. Core retains the only frame authority, and this graphical client has no access to mutation methods on the frame object. Prior C1.2-A core surface register/revision/remove behavior was user-device-proven with a disposable app on run 637. B1 requires a new manual Builder and installed-device UI regression. This is neither Core-owned input/focus nor a shell-less/alternate-shell client proof.

## C1.2-A — Generic Core application surface snapshots (source-only)

`RiftCoreAppSurfaces` is a process-owned, bounded `riftos.core.app-surfaces/1` typed snapshot and change-subscription API for final `RiftAppAbi.Frame` outputs. A snapshot identifies the app, current Core UI-attachment generation, monotonic revision, layout and copied node list; no Android View or desktop classes enter Core. `RiftCoreAppSessions.publishSurfaceFromExecution` holds Core session authority during publication, and close/update/uninstall invalidates the stored frame. The read-only `core surfaces` command and `core status.appSurfaces` report inventory, not UI implementation details.

This is not yet an external Binder protocol, independently executing app lifecycle, input/focus authorization or shell-less launch. C1.2 device gate requires the user's manual Builder, successful final signed DEX, a disposable RAPP frame revision/change lifecycle proof, then later shell-less app and alternate-client proof. Do not infer the graphical shell process is replaceable until C1.3.

## C1.1-B2-B — Core capability-effect and RiftShell UI protocol (source, device gate pending)

Core owns interpreter `HOST_EFFECT_RESULT` chaining, bounded 1024-effect continuation, attachment validation, declared capability policy, synchronous Core grant storage and non-UI filesystem/build/signing effects. Public in-process contracts are `riftos.core.capability-consent/1` and `riftos.core.ui-effect/1` through the bounded ticket registry `RiftCoreShellCapabilityRequests`. A disposable `RiftRappShellCapabilityClient` only renders consent and UI-bound effects; replies require a valid single-use ticket. Shell teardown fails pending approvals closed. Binder/external shell version negotiation is deferred to C1.3; authorization policies remain a C1.4 gate. These contracts do not claim headless execution.

## C1.1-P — Core-managed installed RAPP lifecycle (source pending device proof)

Generic Core platform commands are `riftbuild pack-rapp <project>`, `install-rapp <artifact.rapp>`, `uninstall-rapp <id>`, `rapp-list` and `launch-rapp <id>`. `RiftRappManager` now validates ownership/confinement of uninstall targets, stages filesystem removal, revokes `setting:permissions:<id>` grants, invalidates Core app sessions, and emits `riftos.core.packages.change/1` events for install/update/uninstall without referencing `RiftRappHost`. Saved `state.bin` is removed on uninstall; optional data retention is not offered. The new native RiftShell Installed Apps window lists/installs/uninstalls through Core APIs with an explicit confirmation dialog and subscribes to Core change events. RAPP launching now uses the separate `riftos.core.app-launch/1` Core request contract, with GUI host subscription limited to its Activity lifetime and `accepted=false` if no shell is available. This does not manage native Android APKs or other non-RAPP installation formats and does not imply shell-process independence. Full Builder and installed-device proof are pending.

## C1.1-B2-A — Core-owned event queue (source pending device proof)

The Core RAPP session registry now offers a bounded language-independent FIFO/ticket protocol: `offerEvent` queues a copy of an app event, `finishEvent` authorizes the next event for the current UI attachment only. The FIFO has a 64-pending-event and 1 MiB pending-payload bound per session, clears on Activity detach/re-attach, and retains no UI callbacks. `core sessions` adds `eventQueueOwner=riftos-core` and `queuedEvents`; app state stays Core-owned. UI callback delivery and capability-effect consent remain attached to the Activity, so headless and shell-independent execution are explicitly **not** claimed in this gate.

## C1.1-B1 — Core-owned bounded RAPP execution (source pending device proof)

`RiftCoreAppExecutor` owns the generic interpreter/provider call, event encoding and output decoding, timeout watchdog, and session-state persistence; it runs with application context and no desktop class dependency. RiftShell/RAPP graphical host now invokes this Core service, then renders returned frames or asks the existing Activity-bound capability broker to handle effects. Core session state commits verify the current attachment generation. `core sessions` indicates `eventExecutorOwner=riftos-core` and `capabilityEffectsIndependentOfDesktop=false`. This is **not yet headless execution**: event-effect chaining, consent and UI lifecycle are still attached to the desktop Activity. Source/build/device promotion remains pending.

## C1.1-A — Core-owned RAPP sessions (source pending device proof)

`RiftCoreAppSessions` owns in-process RAPP identity, bounded opaque program bytes, monotonic event sequence and generation-checked UI attach/detach tokens. `RiftRappHost` is temporarily still an Activity-owned UI/event executor, but requests and mutates session state through Core. `core sessions` reports the read-only catalogue, and `core status` includes `appSessions`. **Detached does not mean executing**: `headlessExecution=false`, `appExecutionIndependentOfDesktop=false` and `separateCoreProcess=false` remain true limitations until subsequent device-proven gates. On explicit app-window close the session terminates; on Activity teardown the UI detaches while Core state remains for reattachment. Core authorization never depends on desktop controls.

## RiftOS Core / replaceable RiftShell contract — C1.0

RiftOS Core, not the desktop, is the platform authority for installation, runtime management, filesystem policy, process lifecycles and capabilities. The graphical RiftShell is a replaceable client of Core APIs; it must not be required to execute apps. The process-owned `RiftCoreApplication` bootstrap and `RiftCoreRuntime` own installed RAPP packages, the external provider registry, and generic RiftBuild platform services. The existing `RiftNativeShell` command executor calls Core for those operations; it is NOT the intended replaceable graphical RiftShell. Use `core status` for headless Core-state inspection. This C1.0 change is source-only until the next user-triggered Builder/device proof; Activity-owned RAPP sessions and a single Android process still prevent claiming shell crash survival. See `docs/systems/core-shell/README.md` for the full immutable ownership policy and gates.

## Current source pending installed-device promotion

| Surface | Owner | Purpose |
| --- | --- | --- |

| Generic external runtime-provider IPC (C0.2.5 gate A) | `RiftExternalRuntimeProviders.kt`, `RiftRappHost.kt`, `RiftBuildPlatformTools.kt` | Versioned, signer-pinned explicit Android Binder service discovery and bounded runtime execution, with `riftbuild runtime-status`. The independent QuickJS provider APK/registration and removing QuickJS from RiftOS are NOT complete; no external-runtime installed-device promotion has occurred. |

C0.2 mirrored Codynex/Rift++ editor cleanup is reported device-live by the user (2026-10-08). RiftOS owns generic RAPP hosting, capability broker, filesystem, native-buffer/JVM-D8 execution, APK identity verification and Android installation services, while the standalone RiftBuild Hosted RAPP owns Compile → Preflight → Pack → Sign → Verify orchestration. C0.2.5 preserves compatibility until its external provider is device-proven.

## Live RiftBrowser page surfaces

These exist only in explicit RiftBrowser-owned WebViews and do not provide general Android/shell authority.

| Surface | Owner | Purpose |
| --- | --- | --- |
| `RiftMcpNative` | `RiftBrowserMcpAppBridge.kt` | Exact-origin MCP JSON-RPC channel. |
| `RiftMcpAppNative` | `riftbrowser-mcp-app.js` | Page-side response receiver. |
| `RiftAIAdapters` | `ai-adapter-registry.js` | Supported AI-site semantic selectors. |
| installed-app `Rift` API | `RiftBrowserAppHost.kt` | Capability-gated fixed API for one installed package at its deterministic per-app `https://app-<sha>.riftos.local` origin. |

Guest pages must not receive RiftShell, RiftFS, Workspace, Keystore, generic native dispatch or arbitrary Binder authority.

## Packaged JavaScript

The only JavaScript copied into the generated RiftOS `www` asset namespace for OS execution is:
- `src/riftpp-core.js` -> `RiftPlusPlusCore` compiler surface;
- `src/riftvm.js` -> RiftVM implementation;
- `src/semnexis-bootstrap.js` -> bounded Semnexis bootstrap compiler, versioned SNIRV0–SNIRV7 Native IR codec, Arena/state lowering, ARM32 proof/runtime backends and canonical machine verifier.

They execute under the bounded headless runtime when invoked by native RiftShell.

## Retained reference/compatibility source

The repository still contains older web-runtime/local-first modules such as `src/riftos.js`, `riftcore.js`, `riftgit.js`, `riftdevlab.js`, `riftshell-batch.js`, `riftrt.js`, `riftllm-bridge.js`, `riftrepo.js`, `riftvault.js`, `riftbuild.js`, `riftmemory-control.js`, `riftlocal-platform.js` and related globals. Focused tests and migration/reference logic may use these files, but Gradle does not package the old HTML/DOM shell. In particular, `RiftShellMcp`, `RiftShellMcpNative`, `RiftAndroid` and the old general native-dispatcher path are **not live trusted-shell APK authority**.

## Change rule

When a cross-layer surface is added/removed:
1. identify its owner;
2. update the owning subsystem README and this inventory;
3. update producer/consumer atomically;
4. extend the appropriate architecture validator;
5. avoid preserving unexplained duplicate authority.
