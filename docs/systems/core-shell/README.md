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
| `android/app/src/main/java/com/riftos/app/RiftRappManager.kt` | Generic installed RAPP packaging, verification, installation catalogue and state |
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
