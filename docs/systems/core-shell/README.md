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

## Current code baseline — C1.0 source gate (awaits manual Builder + device proof)

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

## Non-negotiable release proof

1. A new signed RiftOS APK passes source integrity, complete project tests, Kotlin/Android build and signed APK smoke.
2. On-device `core status` and `riftbuild runtime-status` work with no desktop-side dependency.
3. Once shell isolation is built, terminating/restarting/changing RiftShell must NOT kill Core-owned app sessions or services.
4. A new shell must not need Core rebuild, and a Core-only upgrade must not require a shell rebuild unless a versioned public API intentionally changes.
5. Core code cannot import shell/desktop implementation classes; Core security grants are never implemented in a GUI process.
6. The user always triggers RiftOS Builder manually; only fix/test/commit/push via internal Rift MCP/RiftGit.
