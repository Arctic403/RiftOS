# RiftOS Core ↔ RiftShell — canonical architecture (C1 series)

**Locked design decision, 2026-10-08:** RiftOS Core is the operating platform; RiftShell is a replaceable desktop/UI client. A RAPP, process, compiler, runtime, installer or filesystem MUST NOT require RiftShell to run.

## Ownership boundary

| RiftOS Core — OS authority | RiftShell — replaceable user interface |
| --- | --- |
| Software installation/update/uninstall, installed package registry, app signatures | Desktop, wallpaper, Start menu, launch shortcuts, taskbar |
| Runtime/tool-provider discovery/registration, execution, supervision | Application/window decorations, titlebars, arrangement, user interaction |
| Filesystem drives, mount policy, permissions, capability broker, system data | File Explorer UI and file dialogs using Core filesystem API |
| Process and app-session lifecycles, app-to-app IPC, background work | Task/window lists and controls that call Core process/window APIs |
| System authentication and admin elevation enforcement | Consent/elevation dialogs as untrusted UI clients |
| Surface, display/input protocols and recovery shell selection | Surface composition, pointer/keyboard routing and UX |

The generic runtime-provider IPC in `docs/systems/riftbuild/RUNTIME_PROVIDERS.md` remains a **Core** API. The independent RiftBuild Hosted RAPP owns recipes and APK compile/preflight/pack/sign/verify orchestration; Core owns only general-purpose execution, storage, identity signing and verification services.

**Security:** Core verifies authorizations itself. A shell can request operations or display consent but must not grant its own capabilities, own cryptographic keys, or install runtimes through shell-specific code. Programs may request elevated system access through a standard, scoped, user-authorized Core capability, not a hardcoded app identity. RiftOS administrator rights do not confer Android root privileges.

**Do not confuse names:** `RiftNativeShell` is the current process-owned **command executor**, not the eventual replaceable **RiftShell graphical desktop**. During migration its generic OS-facing commands become thin clients of Core and its UI-specific commands become shell UI calls or independent clients.

## 2026-10-08 — C1.2-A DEVICE PASS; C1.2-B1 Core snapshot renderer SOURCE checkpoint

**A real-device proof completed** on user-installed RiftOS source `0dc4c2777b566c7e321790d89130e608efadf402`, manual Builder run `37788211066` (#637), Android Core PID 11301. `core surfaces` baseline was zero; a disposable JSON RAPP `c11b2b-capability-probe-20261008` was installed/launched and published its typed `RiftAppAbi.Frame` as 5 nodes at revision 3, 79 cached text bytes. A real UI `fs.read` event with CANCEL returned denied token 43 and advanced the Core surface to revision 6/157 bytes without detaching the session. Same-package replacement invalidated the old session and removed the stale surface (count=0). Relaunch recreated a surface at revision 9. Core-managed uninstall while attached removed the surface and session; count=0, storedTextBytes=0, queued events=0, original three RAPPs intact. **C1.2-A is device-proven; no installed-system code was modified by the test.**

**Next source slice C1.2-B1:** `RiftRappHost` no longer renders the interpreter callback's frame directly. Instead it looks up `RiftCoreAppSurfaces.snapshot(appId)`, checks its `attachmentGeneration` matches the Core session token, and uses that immutable typed copy to render. Missing/stale snapshots fail visibly instead of trusting a stale callback. Core owns the published result; graphical UI only consumes it. This changes no window, app-specific adapter, permission, build or input policy, and remains in the same Android process.

**Pending:** User-only manual Builder on new RiftOS/Builder source commits, plus device proof that a disposable RAPP renders initially, updates after a button or consent result, and tears down normally. Later C1.2 gates still must extract input/focus authorization and app execution lifecycle from `RiftRappHost`, demonstrate shell-less launch and a genuinely alternate shell client. C1.3 is still the separate shell APK/process/reconnect gate. Do not claim headless execution.

## 2026-10-08 — C1.2-A Core-owned generic app surface snapshots (source candidate)

**Scope:** `RiftCoreAppSurfaces` owns a versioned `riftos.core.app-surfaces/1` registry of immutable `RiftAppAbi.Frame` snapshots keyed by application ID and monotonic revision, with a bounded 128-surface / 32-subscriber generic change feed. It holds no Android `View`, `Activity`, desktop implementation or RAPP language-specific reference. `RiftCoreAppSessions` validates the active executable and session attachment under its own synchronization lock before publication; incompatible session replacement, close and package update/uninstall remove stale surfaces. The Core interpreter publishes its final frame before the disposable graphical callback. `core surfaces` is a read-only diagnostic for schema, revision, attachment generation, node count and layout; `core status.appSurfaces` reports the same summary.

**Device gate:** Source only. User manually triggers Builder for the new Core/Builder commits. Signed APK must contain the new Core surface class and `riftos.core.app-surfaces/1` schema; on device open a disposable generic RAPP and verify a Core surface appears with a positive revision/node count, advances on an event, and disappears on close/update/uninstall. Repeat core status and prior C1.1-B2-B consent tests. The first primitive is intentionally read-only: shell still owns submission of app input/focus, Activity still creates the graphical attachment, and there is no alternate-shell or shell-less launch proof. Do **not** set `headlessExecution`, `appExecutionIndependentOfDesktop` or `separateCoreProcess` to true.

**Next C1.2 work after A device proof:** move generic input/focus tickets and app lifecycle out of `RiftRappHost` into Core, expose a typed shell surface subscribe/attach contract, then demonstrate a shell-less app session and an alternate shell-client rendering the same Core snapshot before promoting C1.2. C1.3 remains the separate-APK/process isolation gate.

## 2026-10-08 — C1.1-B2-B explicit Cancel user-build GREEN and device PASS

User installed RiftOS source `60fb0e70068f2172dea44dac858edb943663562d` (manual Builder run `37777098652`, #635). Core PID 28575, 3 original RAPPs, 0 sessions/queued events at start. Reinstalled only disposable `c11b2b-capability-probe-20261008`. A real Android dialog displayed **CANCEL / DENY / ALLOW** for `fs.write`. Local Agent clicked **CANCEL**, and the still-attached app displayed and persisted `CORE EFFECT RESULT fs.write DENIED/FAILED token=44 fs.write permission denied or expired`. No `probe-v2-write.txt` was created. Core session stayed attached during cancellation. The disposable RAPP was uninstalled; Core PID stayed the same, original three RAPPs remained, count and pending events returned to zero. Combined with prior user screenshots of fs.read/fs.write success, denied responses and grant revocation on package update, the **C1.1-B2-B capability/consent device gate is complete**. This does **not** imply generic shell-less execution, persistent background app sessions, or C1.3 shell process isolation.

## 2026-10-08 — B2-B live permission/effect proof and explicit Cancel follow-up

Installed RiftOS `174f0144` green/device-live. Read-only Core diagnostics confirmed correct running source, no stale sessions and original RAPP registry intact. A disposable JSON RAPP on device proved Core-owned window-title dispatch, **fs.read success/token 43**, **fs.write success/token 44** with file contents independently read through MCP, replacement revoking prior grants, session invalidation, and `fs.read` plus `fs.write` Deny results delivered via `HOST_EFFECT_RESULT`. Uninstall of the disposable test stopped its active session and restored the three original apps with zero pending Core event tickets.

The platform Back gesture during a permission dialog detached the app session, so a distinct in-session Cancel callback was not proven. Next source checkpoint adds explicit `setNeutralButton("Cancel")` in `RiftRappShellCapabilityClient`; as with Deny and default dialog cancellation it calls `respondConsent(subscription,ticket,false)` on a one-use Core ticket. Core remains the only authority and never interprets the shell's label as an elevated approval. Manual Builder and device-level Cancel proof required before finishing the edge-case acceptance. No new separate-process or headless claims.

## 2026-10-08 — C1.1-B2-B Core effect chaining and shell consent client (source candidate)

**Source implementation; not yet promoted by manual Builder or signed device.** Following the user-confirmed C1.1-P lifecycle test, all interpreter effect/result continuations now run through Core-owned `RiftCoreAppExecutor.executeChained`, with a 1024-step ceiling and generation-bound attachment checks. A graphical `RiftRappHost` receives only the final frame/error and retains disposable UI event callbacks. The Core-owned `RiftRappCapabilityBroker` now uses application Context and never holds an Activity, desktop or AlertDialog. It verifies declared permissions, validates active installed identity, decides persisted grants, executes bounded non-UI effects and routes authorized UI effects via a client.

`RiftCoreShellCapabilityRequests` defines `riftos.core.capability-consent/1` and `riftos.core.ui-effect/1`. Pending requests are bounded (64), ticketed once, and tied to a subscribed shell client. Replies from an obsolete client or consumed ticket are rejected, and unsubscribe fails requests closed. `RiftRappShellCapabilityClient` owns only user-facing Allow/Deny/Cancel dialogs, clipboard, share and window-title rendering; it cannot write Core grants or run filesystem/build/signing work. Core persists allowed grants synchronously and refuses stale session replies.

**Important limitations:** Transport is still in the same Android application process, and interactive consent/UI effects still need an attached shell. The UI host still submits and completes event tickets against the Core-owned FIFO, which must be moved to an independent lifecycle before true headless operation. Current truthful flags remain `headlessExecution=false`, `capabilityEffectsIndependentOfDesktop=false`, `appExecutionIndependentOfDesktop=false`, and `separateCoreProcess=false`. This is **not** C1.3 shell-crash-survival proof.

**Next manual promotion:** user manually runs Builder for the pushed RiftOS/Builder pair; require exact Kotlin source reachability, full checks, real Kotlin/Gradle compile, signed DEX markers, and device checks. Exercise permission Allow/Deny/Cancel, Core grant persistence/revocation after an update, an effect that returns to Core runtime, Activity recreation during pending consent (fail closed), ordinary apps, RiftBuild Hosted compile/preflight/pack/sign/verify, and MCP/Files/RiftShell. No auto Builder dispatch and no uninstall of real apps.

## 2026-10-08 — C1.1-P Core RAPP package management (source, manual Builder pending)

The user confirmed C1.1-B2-A green and installed. Live `core status` showed Core PID 32623, five installed RAPPs, zero attached/queued sessions, `eventQueueOwner=riftos-core`, and `appExecutionIndependentOfDesktop=false`. The package lifecycle was still incomplete: `RiftRappManager` had install/replace/list/launch but no uninstall, and installation called `RiftRappHost.notifyProgramsChanged()` directly.

**Implementation:** `RiftRappManager.uninstall(id)` accepts only validated managed RAPPs under `C:/Programs`. It stages removal by same-volume rename into the Core staging root, revokes matching saved grants synchronously (`setting:permissions:<id>`), invalidates the Core session/event queue, then publishes a `riftos.core.packages.change/1` event. Uninstall removes the package's `state.bin` with its other files; if physical cleanup fails after the visibility commit, the receipt reports `uninstalled-cleanup-pending` instead of falsely reporting deletion. If grant revocation fails, uninstall attempts to restore the original managed directory before returning an error. Recursive stage cleanup is bounded and does not traverse symlinks outside its root.

**Updates:** existing same-ID RAPP replacement still uses staging/backup. Successful replacement revokes grants inherited from the prior installed code, invalidates stale Core sessions, and publishes `updated`; new installs publish `installed`. The manager no longer imports the graphical `RiftRappHost`. In-process listeners subscribe to `RiftCorePackageEvents`; they must unsubscribe when their shell UI closes. A future separately installed shell will consume the versioned change contract through inter-process notifications rather than sharing this in-process observer object.

**Interfaces:** `riftbuild pack-rapp`, `install-rapp`, `uninstall-rapp <id>`, `rapp-list`, and `launch-rapp` are generic Core platform routes. The native RiftShell `Installed Apps` window is only a client: it lists registered RAPPs, accepts artifacts from `D:/Builds`, and offers an explicitly confirmed uninstall with clear data-loss disclosure. Installer, identity/authority, filesystem confinement, grant revocation and session invalidation remain Core-owned. This does not manage native Android APKs, unrelated program folders or external runtime-provider APKs; those need separate policies. Keeping app data through uninstall is **not implemented**; no `--keep-data` shortcut.

**P-gate device test:** next user-manual signed Builder + install; confirm Installed Apps launcher/menu and accurate list; install a noncritical test RAPP from `D:/Builds`, test update and grants reset, launch/close, then uninstall that test RAPP and verify it disappears from `rapp-list`, Core sessions, launcher and grants. Confirm cancellation does nothing, uninstall refuses built-in/non-RAPP IDs, and Files, MCP, RiftBuild Hosted and existing programs remain intact. Do not uninstall any user app without user selection and explicit confirmation.

**Remaining architecture:** the native UI and CLI still occupy the same Android app process as Core. `RiftRappManager.launch()` now dispatches a versioned `riftos.core.app-launch/1` request through `RiftCoreAppLaunchRequests` and the current graphical shell subscribes/unsubscribes; the manager no longer calls `RiftRappHost`. With no subscribed shell, launch correctly reports `accepted=false` and no headless app execution is claimed. Shell-independent execution and replaceable shell installer/upgrade require the later C1.1-B2-B, C1.2 and C1.3 proof gates. App-data retention/backup, managed cleanup retry and system-level runtime APK installation remain follow-on capabilities.

## 2026-10-08 — C1.1-B2-A Core-owned event FIFO (source, pending manual Builder)

The user confirmed C1.1-B1 green, installed and live. Live RiftOS MCP reported Core PID 27438, `eventExecutorOwner=riftos-core`, no active RAPP sessions, five installed programs and a working empty external runtime registry. This proves Core diagnostics only: it does not prove RAPP event interaction or state restoration yet.

C1.1-B2-A migrates the **64-event bounded FIFO**, current running event ticket, payload-byte budget (1 MiB per session), and FIFO completion order from Activity-owned `RiftRappHost` to **`RiftCoreAppSessions`**. Core stores plain `RiftAppAbi.Event` payload copies and monotonically increasing ticket identifiers, never Java/Kotlin callbacks, Views, Activities, or desktop references. The current desktop holds a map of disposable completion callbacks keyed by ticket ID, and asks Core `offerEvent`/`finishEvent` which event to dispatch next. Explicit app-window close removes the Core session; Activity destruction invalidates outstanding Core event tickets and drops UI callbacks while preserving program state and runtime event sequence. Reattachment resets the queue safely. This is an in-process migration; Core is not yet a separate Android process.

**Not yet shell-independent**: Core now owns event ordering, interpreter threads and opaque state; `RiftRappHost` still pumps responses and resolves capability host effects, UI consent, and user interaction. Core sessions still report `headlessExecution=false`, `capabilityEffectsIndependentOfDesktop=false`, `appExecutionIndependentOfDesktop=false`, and `separateCoreProcess=false`. Never claim a detached RAPP continues executing. The next C1.1-B2-B gate must establish a versioned Core-to-UI effect/consent interface with Core-owned scheduling and capability enforcement, separate from replaceable desktop code.

**Device promotion:** require manually built signed APK green and installed; `core sessions` reports `eventQueueOwner=riftos-core` and truthful false headless flags; launch an installed RAPP and verify attached/pending count and preserved program state under repeated input and close; exercise RiftBuild Hosted build effects/permissions; validate Activity recreation and stale completion isolation; keep Files/MCP/terminal working. This source checkpoint alone is not device proof.

## 2026-10-08 — C1.1-B1 Core event execution extraction (source; pending user-manual Builder)

The user confirmed the C1.1-A APK green, installed and running. Live Rift MCP reads showed `core status` PID 22686, `riftos.core.sessions/1`, five installed RAPPs and an empty attached/detached session registry, plus `riftbuild runtime-status` reporting zero external providers and legacy fallback. This validates the registry and commands **while no RAPP was running**, not yet app attach/typing/relaunch/crash behavior.

C1.1-B1 extracts generic **adapter event encoding/decoding, native-buffer/QuickJS runtime invocation via generic provider dispatch, bounded event execution watchdog and persisted program-state promotion** into `RiftCoreAppExecutor.kt`. It is obtained from `RiftCoreRuntime.appExecutor(context)` using only application context and Core services, not the desktop. `RiftRappHost` submits an attached session's event and receives frame/effect/error results for UI presentation. `RiftCoreAppSessions.commitFromExecution` checks attachment generation and persists state atomically before promoting it to memory, ensuring stale UI workers cannot overwrite a newly attached session. Core processes events through its own single bounded worker and watchdog threads even if an Activity is recreated; the desktop must not shut those workers down.

**Still NOT headless RAPP execution:** event queue orchestration, user-facing host-effect/capability consent handling, UI callbacks and `RiftRappManager.launch` still depend on `RiftRappHost` in the Activity. Detached sessions remain suspended; `eventExecutorOwner="riftos-core"` is accurate but `capabilityEffectsIndependentOfDesktop=false`, `headlessExecution=false`, `appExecutionIndependentOfDesktop=false`, `separateCoreProcess=false`. This gate does not claim any process survival after shell death and is not a replacement shell package. Keep the existing runtime ABI, user-controlled Builder workflow and installed app handling unchanged.

**B1 required device gate:** after user-manual Builder green and installation, check `core status`/`core sessions`, launch `rapp-notepad`, interact and verify persisted program state, close and relaunch, confirm RiftBuild Hosted host effects and compile flow still work, and test UI Activity recreation without changing Core PID or incorrectly committing a stale event. `riftbuild runtime-status`, MCP and Files should continue responding. Do not promote or start B2 until the device behavior is proven.

**Next C1.1-B2:** move host event serialization/queues, capability-effect requests and user-approval synchronization to a versioned Core↔shell consent protocol, and make Core responsible for headless app lifecycle and process inventory. Then C1.2 generic window/surface protocol and C1.3 replaceable shell process/package.

## 2026-10-08 — C1.0 green/device-live; C1.1-A session-state migration pending manual Builder

The user confirmed RiftOS C1.0 on `fd64c612` build-green and installed. A live `core status` read reported `riftos.core.status/1`, process ID 14909, 5 installed RAPPs, no external runtime provider and `appExecutionIndependentOfDesktop=false`; `riftbuild runtime-status` returned 0 providers with legacy fallback. Core package/runtime/build foundation is device-live.

C1.1-A now adds `RiftCoreAppSessions.kt`, the **Core-owned in-process record of RAPP session identity, opaque program bytes and event sequence**. `RiftRappHost` remains a disposable UI/event attachment. It attaches to Core records with generation tokens, explicitly closes Core sessions on user window close, and only detaches them when its Activity is destroyed. Stale host workers cannot commit state to a reattached Core session. Reopening an installed RAPP can reuse Core state instead of restarting its Core event sequence. Core status exposes a bounded session summary and `core sessions` reports attached versus detached session records with no view/Activity references.

**C1.1-A is NOT full C1.1**: the event loop, effect broker, UI callbacks and generic application launch still depend on an Activity-owned `RiftRappHost`. Detached sessions are **suspended records**, not concurrently running headless apps. Core and shell still share the Android app process; Android process death clears in-memory records, while persisted RAPP program state remains managed by `RiftRappManager`. Source pending the user's next manual Builder, signed-APK and real-device proof; no guarantee of uninterrupted app execution across shell crash is made yet.

**C1.1-A device promotion:** user-manual Builder green, install, `core status` includes `appSessions` with truthful `headlessExecution=false`, `core sessions` is empty initially, launching `rapp-notepad` or another known installed RAPP creates one attached record, typing/actions persist program state, explicitly closing the window removes the record, and Activity recreation detaches but does not delete the Core state. Relaunch should restore installed app's persisted behavior and preserve Core session event sequencing. Verify RiftBuild Hosted/RAPP Proof, Files and MCP still operate; test only on a real signed APK. If these fail, do not promote to C1.1-B.

**C1.1-B next:** move bounded app event queues, runtime execution, capability effects and app lifecycle to Core-controlled executor outside Activity; attach/detach should change rendering only. Replace `ps` window-derived process list with real Core session/process state and migrate `kill` to a Core policy API. Then C1.2 surface protocol and C1.3 independently installed replaceable graphical shell.

## Current code baseline — C1.0 previously device proven

* Android `RiftCoreApplication` initializes `RiftCoreRuntime` from the **main application process**, before creating `MainActivity` or desktop windows. Services in Android worker processes do not initialize a duplicate Core.
* `RiftCoreRuntime` is one process-wide application-context-only authority for `RiftRappManager`, `RiftExternalRuntimeProviders` and `RiftBuildPlatformTools`. `RiftNativeShell`, `RiftRappHost` and `RiftBuildPlatformTools` ask Core for these services instead of constructing separate instances. The existing RAPP lifecycle and UI behavior is unchanged.
* `core status` exposes `riftos.core.status/1`, installed RAPP count, registered runtime-provider count, process ID and monotonic uptime **without accessing the desktop Activity**.
* `desktopRequired=false` and `shellRequired=false` mean **Core service initialization and catalogue access** do not require them. Status also explicitly says `appExecutionIndependentOfDesktop=false` and `separateCoreProcess=false`. Do not misreport C1.0 as app-survives-shell-crash proof.
* No app lifecycle or UI host has yet been moved out of `MainActivity` / `RiftRappHost`; the Android process still owns both Core and UI. No user-visible replaceable shell package exists. C1.0 has no installer/runtime capability changes beyond ownership.
* All RiftOS Builder builds remain **manually triggered by the user**. Do not auto-start Builder or change its workflow. Advance a gate only after source/Builder AND real-device behavior checks pass.

## Required next gates

**C1.0 — Core authority extraction (this gate).** Green Builder + installed-device checks: desktop/terminal/Files/RAPP launcher still work; `core status` shows `riftos.core.status/1`, process ID, stable Core uptime across Activity recreation; `riftbuild runtime-status` works; RiftBuild Hosted remains usable; generic native-buffer RAPP still launches. Do not promote until user proves.

**C1.1 — Process and app session authority.** Move session IDs, execution queues, application lifecycle, background jobs and process registry from Activity-owned `RiftRappHost`/`RiftNativeDesktop` to Core; renderer retains only views. Prove app session and state survive desktop Activity recreation. `ps` must report real Core process/session state rather than inferring processes from window rows. `kill` must target authorized Core process/session IDs, not window IDs. No application-specific runtime hardcoding.

**C1.2 — Generic app surface and shell client contract.** Core owns app surface registration/lifecycle, UI input/output protocol and focus authorization. RiftShell owns decorations/layout/taskbar/Start/File Explorer presentation. No RAPP can depend on implementation classes in `RiftNativeDesktop`; new shell clients use stable typed Core APIs. At least one shell-less app launch and one alternate shell client proof are mandatory.

**C1.3 — Shell process/lifecycle isolation and recovery.** Move replaceable shell out of Core APK, with a correctly installed/signed shell package, robust reconnect/recovery and no Core teardown when shell closes, crashes or updates. Android OS background-process restrictions and user-visible foreground-service requirements must be accounted for rather than hand-waved. Prove app continues executing through shell process death, process registry remains intact, user can select/restart another shell and regain controls.

**C1.4 — System capability/admin elevation.** Generic user-consented capability policies for system files, software installation, runtime registration and protected processes; revocable grants and audit trail enforced by Core. No implicit blanket system write permissions; elevated operations require explicit trusted policy. Preserve safe installation/rollback.

**C1.5 — Embedded language-engine retirement and Core enforcement.** Per latest user instruction, legacy QuickJS RAPP compatibility can be broken rather than preserved. Remove embedded QuickJS, project-specific shell execution and obsolete JS assets from Core; externalize language engines/compilers, including subsequent Kotlin/D8 work, behind generic installed provider contracts. Source + APK negative proof and real-device regression are required. Then continue C0.3-specific project cleanups.


## Source ownership

This document owns the Core/Shell separation policy and phased C1 migration boundaries. The implementation remains inside RiftOS-main during C1.0, with external shell packaging deferred to C1.3.

| Source | Owner and authority |
| --- | --- |
| `android/app/src/main/java/com/riftos/app/RiftCoreApplication.kt` | Android Application bootstrap; initializes Core without creating desktop UI |
| `android/app/src/main/java/com/riftos/app/RiftCoreRuntime.kt` | Main-process Core service catalogue, generic package manager, runtime registry and build-platform owner |
| `android/app/src/main/java/com/riftos/app/RiftCoreAppSessions.kt` | C1.1-A non-UI RAPP session identity, bounded opaque state, monotonic sequence, UI attach/detach token authority |
| `android/app/src/main/java/com/riftos/app/RiftCoreAppExecutor.kt` | C1.1-B1/B2-B process-scoped bounded interpreter invocation, persisted state, and effect/result chaining; not graphical UI authority |
| `android/app/src/main/java/com/riftos/app/RiftCoreShellCapabilityRequests.kt` | Core-owned one-shot pending consent and UI-effect ticket registry with timeout and shell unsubscribe invalidation |
| `android/app/src/main/java/com/riftos/app/RiftRappManager.kt` | Generic installed RAPP packaging, verification, installation catalogue and state |
| `android/app/src/main/java/com/riftos/app/RiftRappCapabilityBroker.kt` | Core-owned generic grant policy, filesystem/build/signing and non-UI effect executor |
| `android/app/src/main/java/com/riftos/app/RiftRappShellCapabilityClient.kt` | Disposable graphical shell consent dialogs, clipboard/share/window UI-effect client |
| `android/app/src/main/java/com/riftos/app/RiftCorePackageEvents.kt` | Core-owned versioned install/update/uninstall change feed; shell observes and unsubscribes |
| `android/app/src/main/java/com/riftos/app/RiftCorePackageGrants.kt` | Synchronous revocation of per-ID stored RAPP grants on replacement/removal |
| `android/app/src/main/java/com/riftos/app/RiftExternalRuntimeProviders.kt` | Signer-pinned runtime-provider registration lookup and bounded external execution IPC |
| `android/app/src/main/java/com/riftos/app/RiftBuildPlatformTools.kt` | Core-owned generic compiler, verifier, install and package services; shell-facing commands are adapters only |
| `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt` | Existing native command executor and Core client, not the future replaceable graphical RiftShell |
| `android/app/src/main/java/com/riftos/app/RiftRappHost.kt` | **Temporary C1.0 limitation:** Activity-owned RAPP event sessions, rendering, input and window attachment |
| `android/app/src/main/java/com/riftos/app/RiftNativeDesktop.kt`, `MainActivity.kt` | **Temporary C1.0 limitation:** graphical desktop and Android Activity composition; to be separated from application execution |

`scripts/validate-rift-wiring.mjs` validates source reachability and Core dependency direction. `android/app/build.gradle.kts` owns the exact Kotlin source list. RiftOS Builder's `scripts/riftos-build.sh` and `scripts/verify-riftos-apk.sh` enforce source and signed-APK constraints. The user alone launches the manual Builder.

## Failure signatures

| Symptom | Likely boundary or gate |
| --- | --- |
| `Kotlin source is unreachable from an Android manifest component: ...RiftCoreApplication.kt` | Source reachability forgot that `<application android:name>` is a manifest entry point; fix validator, not the Core bootstrap |
| `system README is missing maintenance section` for this document | Documentation gate requires Source ownership, Failure signatures, Fix map and Validation headings in every system README |
| `Builder C1.0 Core source missing` or `Core Kotlin source not Gradle-mandatory` | Missing `RiftCoreApplication.kt` or `RiftCoreRuntime.kt` from exact source snapshot |
| `Core Application bootstrap missing` from source or signed APK | Android manifest does not declare the app-scoped Core Application, or APK manifest packaging regressed |
| `Core service ... missing` / `RiftShell is still owning` | Generic package/runtime/build authority was accidentally constructed in desktop or shell rather than via Core |
| `core status` unavailable or missing `riftos.core.status/1` | Core bootstrap, shell-to-Core diagnostic routing or main-process singleton unavailable |
| RAPP state resets or app closes when the Activity is destroyed | **Expected unpromoted limitation in C1.0:** actual execution sessions still live in `RiftRappHost`; address in C1.1, do not falsely mark C1.0 as shell independent |

Do not treat a source-check or documentation failure as a Kotlin compiler failure. Examine the first failing stage in the user-supplied Builder logs before changing implementation.

## Fix map

1. **Source/document validation:** repair the exact assertion or missing maintenance section; preserve the architecture, manifest startup and existing service contracts. Run the documentation check before asking the user for another Builder run.
2. **Bootstrap/manager ownership:** inspect `RiftCoreApplication`, `RiftCoreRuntime`, AndroidManifest, the Gradle Kotlin source snapshot, and the calls from `RiftNativeShell`, `RiftBuildPlatformTools` and `RiftRappHost`. Reuse one Core manager instead of creating UI-owned duplicates.
3. **Runtime-provider failures:** inspect the signer pin, provider status and bounded Binder IPC defined in `docs/systems/riftbuild/RUNTIME_PROVIDERS.md`. C1.0 intentionally retains existing QuickJS until a later cleanup gate; do not solve core regressions by inventing QuickJS-specific platform exceptions.
4. **Application session lost on shell destruction:** defer to C1.1 Core session/process extraction and C1.2 generic app surfaces; do not patch an individual RAPP as a substitute for the OS-level contract.
5. **Shell restart/crash recovery:** prove real process isolation and generic reconnect in C1.3; a same-process singleton and relaunch of `MainActivity` do not constitute that proof.
6. **Security/elevation:** authorization must be enforced by Core. User-facing approval dialogs belong to shell/UI clients; no shell should hold the signing key or grant itself privileged access.

## Validation

**Current status: C1.0 source changed, full build/device proof pending.** Do not declare the gate green based on these written checks alone.

- Source check: `npm run check:transport` must pass the full source/transport suite, including `scripts/validate-rift-wiring.mjs` and `scripts/validate-rift-docs.mjs`. The latter dynamically checks these four maintenance headings, ownership ledger entries and documentation links.
- Android/Builder: user manually runs RiftOS Builder; require exact Gradle Kotlin snapshot, Android Application bootstrap, Kotlin/Android compile, signed APK DEX/manifest smoke, and a clean signed artifact.
- Installed-device: verify `core status` reports `riftos.core.status/1` with nonzero process ID and stable uptime across desktop Activity recreation, while explicitly reporting `appExecutionIndependentOfDesktop=false` and `separateCoreProcess=false`.
- Regression: `riftbuild runtime-status`, MCP, RiftShell commands, Files, launcher, installed RAPP catalogue and a native-buffer RAPP must continue behaving normally. Verify RiftBuild Hosted separately if its runtime remains installed.
- Promotion to C1.1 and later requires application-session survival and eventually a separate replaceable shell package; these capabilities are not claimed by C1.0.

## Non-negotiable release proof

1. A new signed RiftOS APK passes source integrity, complete project tests, Kotlin/Android build and signed APK smoke.
2. On-device `core status` and `riftbuild runtime-status` work with no desktop-side dependency.
3. Once shell isolation is built, terminating/restarting/changing RiftShell must NOT kill Core-owned app sessions or services.
4. A new shell must not need Core rebuild, and a Core-only upgrade must not require a shell rebuild unless a versioned public API intentionally changes.
5. Core code cannot import shell/desktop implementation classes; Core security grants are never implemented in a GUI process.
6. The user always triggers RiftOS Builder manually; only fix/test/commit/push via internal Rift MCP/RiftGit.
