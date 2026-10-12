# RiftOS

## 2026-10-11 — manual Builder worker #38194403134 source-test compatibility repair (source only)

The user-manual signed Builder run at RiftOS source `1568f8a08494ed0f9d9aead8395898a6e7996383` passed Builder validation but failed **BEFORE Gradle** in `npm run check` (source-check.log). `scripts/validate-rift-wiring.mjs` reported two incompatible source markers: the manual Core selector removed the old verification catch's `it` diagnostic, and RiftShell selected-Core `coreStatus(appContext)` now spans multiple Kotlin lines. Reinstated **diagnostic-only** rejected-load logging with the exception rethrown (no automatic fallback); made validation require the real selected-Core call regardless of whitespace. Also migrated `scripts/test-simple-recovery.mjs`, which would have failed later, from obsolete automatically accepted backup and restore markers to **user-confirmed Save/Restore** contracts. Static targeted checks passed: both validator expressions matched source, no Core automatic promotion/rollback, and all 68 source-test required markers for the four Recovery owner groups were found. Full Node check, Gradle, signing and installed device manual Recovery proof are **pending user-manual build**; installed APK remains unchanged. [Authoritative final manual-only Recovery policy](docs/systems/android-host/MANUAL_RECOVERY_ONLY.md).


## 2026-10-11 — MANUAL-ONLY Core/RiftShell backup and Recovery (source only)

**User-owned revision selection** supersedes the earlier Core boot-success auto-promotion patch. A verified Core or Shell installed through the independent Recovery app remains selected across normal starts, regardless of feature tests or boot-health scoring. Nothing automatically accepts, quarantines, overwrites the saved recovery backup, or selects embedded/older code because of a crash. Recovery now has separate **Save selected Core/Shell as recovery backup** controls requiring confirmation; only pressing manual **Restore saved backup** or **Restore embedded** changes an existing selection. Protected SHA/DEX/ABI checks, independent `:riftRecovery` and embedded migration fallback stay. See [authoritative manual-only policy](docs/systems/android-host/MANUAL_RECOVERY_ONLY.md). **Source-only: not installed or device-proven; #722 still uses its original gates.**


## 2026-10-11 — post-#715 APK legacy shell cleanup (source only; unsigned)

User-signed #715 physically booted independent external Core v0.6 (schema `riftos.external.core/1`, owner `independent-external-dex`, four installed JSON RAPPs successfully ran/frame/stopped at 15/12/21/26 nodes). This is **manual-trial proof**, not production acceptance, native-buffer external-Core qualification, accepted-backup rollback, or Patch C clearance. The installed #715 APK is unchanged by the cleanup below.

In the **next unbuilt APK source**, unused native RiftShell `riftbuild`, `qjs`, `semx`, `riftpp`, and `rift-tool` commands and their shell-only `RiftHeadlessJsRuntime` host were removed. The three old web/compiler JS assets are no longer bundled by Gradle; their source remains in the repository as historical/test reference. This does **not** remove the actual generic `RiftHostQuickJsV1`/`RiftRappQuickJsExecutor` that serves RAPPs, the embedded Core migration fallback, independent Recovery, registered build.local/D8/signing services, or MCP/relay. Source and Builder checks now reject reintroducing old in-APK language CLIs/assets. No production Core acceptance and no signed build is implied.


## 2026-10-11 — External Core v0.6 manual-trial source checkpoint

The existing independently built Core (not a duplicate embedded Core) now includes transactional RAPP installs/uninstalls, QuickJS and Rift++ native-buffer execution, Core-owned frame/session state, scoped filesystem, fresh and prior permission grants through the existing Shell consent ticket channel, registered `build.local` compiler/D8 and Android Keystore signing identity. The Android APK supplies **generic** host execution/consent/build transport only; no application-specific Core ownership returns to the APK. `EXTERNAL_CORE_MANUAL_DEVICE_TRIAL_ALLOWED=true` permits an explicitly confirmed protected Recovery device trial, but `EXTERNAL_CORE_PROMOTION_PROVEN=false` remains intact.

The standalone Core's **13 Kotlin sources compiled successfully**, and D8 produced a 2,773,132-byte DEX SHA `1f3c3850b0c0dc945944719cced77eec3091dc868a16d004413567d28ea1f3df`. Its eight compile-only APK service classes were stripped. This is NOT yet production qualification, signed Android build proof, or a live external Core. User must manually install the new signed host and run the [complete external-Core device acceptance checklist](docs/systems/android-host/EXTERNAL_CORE_DEVICE_ACCEPTANCE.md), including tested embedded fallback and a real accepted-backup rollback. Signed installed #710 predates these bridges. Do not activate on #710 or begin Patch C.

## 2026-10-11 — External Core v0.5 NativeBuffer host gate (signed device proof pending)

The user-authorized external Core v0.5 now compiles as a standalone Core DEX with production-derived RAPP package manager, typed JSON/Rift++ adapters, Core-owned sessions/capability policy, and generic execution through APK `RiftHostQuickJsV1` or new `RiftHostNativeBufferV1`. The new host primitive delegates to the existing bounded isolated native-buffer worker; the APK does not gain application/permission authority. The independent registered Kotlin/D8 producer removed all SIX host compile-only classfiles and generated one 2,748,192-byte experimental DEX, SHA `c7e13955c4cb23587397e7de3704651fbcc3d7d70fd701b7739e2a968665f713`, retained separately under `workspace/tmp/`.

**Not a qualified release.** Signed installed #710 does not contain the new host ABI, and independent Core promotion is still blocked. Run the next signed APK manually via the normal Builder; then device-test native-buffer host execution, complete fresh-grant consent and privileged platform interfaces, and test real external Core/backup/restore before removing embedded Core or old recovery. See [Core roadmap](docs/systems/android-host/EXTERNAL_CORE_SHELL_ROADMAP.md).

## 2026-10-11 — Patch B source: standalone APK Recovery can install, backup and restore

On manually signed #709, the user proved `RiftOS Recovery` opens directly from its separate Android launcher even after RiftOS was force-stopped, and regular Core/RAPP smoke passed. The APK-owned Recovery screen now has separately confirmed **Restore embedded Core/Shell** and **Restore accepted working backup** controls, plus a bounded Android picker for a trusted external `component.json` and matching DEX: stage/verify, then explicitly confirm install for next start. Only a previously device-accepted external component can be stored as the one last-known-good backup; existing `RiftProtectedComponentInstaller` and SHA/type verifier are reused, not duplicated. Signed embedded Core and RiftShell remain the reliable first-install fallback. Legacy release journal sync remains until Patch C. No hot swap or automated process kill.

This is **Patch B SOURCE ONLY**. The external Core and external graphical Shell are not yet feature-complete, so a green signed build and safe UI proof must precede any genuine qualified external-component update/restore test. Existing signed #709 has only Patch A. Manual signed builds and device acceptance remain under user control. [Authoritative roadmap](docs/systems/android-host/SIMPLE_BOOTSTRAP_RECOVERY_LOCK.md).

## 2026-10-10 — Patch A source: independent Android RiftOS Recovery launcher

RiftOS now has a new `RiftOS Recovery` Android launcher entry in isolated `:riftRecovery` process. Its `Application.onCreate` bypass skips Core/Shell bootstrap, so it can inspect APK-private recovery records even after a bad selected Core. The read-only first screen reports Core/Shell active state, any verified external last-working backup, invalid backup, startup marker and the existing embedded first-install fallback. It uses the established SHA/qualification receipt but cannot yet install/restore or activate anything; **Patch B** adds the protected writer and explicit user-driven restore after Gate A device proof. Patch A needs your manual signed Builder APK and physical Recovery UI test; installed signed #706 remains the pre-Recovery baseline. [Three-patch decision and exact gate](docs/systems/android-host/SIMPLE_BOOTSTRAP_RECOVERY_LOCK.md).

## 2026-10-10 — New target: simple APK bootstrap and manual recovery (decision locked)

The permanent APK owns **Core/Shell selection, verified update installation, one last-working backup and manual recovery**. If an external Core update breaks startup, a user-launchable APK Recovery screen in its **own process** (no Core/Shell dependency) verifies and restores the previous working Core. Clean initial APK installs must provide a known-good Core seed or retained embedded migration implementation. QuickJS and MCP/relay remain APK services; replaceable Core and Shell must not repair/update themselves.

Implement in **three short patches:** A recovery entry + simple backup records; B APK installer/switch/manual restore with signed real-device proof; C remove obsolete auto-supervisor/N-1/complex promotion flows **only after manual recovery works**. [Authoritative decision + gates](docs/systems/android-host/SIMPLE_BOOTSTRAP_RECOVERY_LOCK.md) supersede historical auto-recovery targets. This is documented design, not an implemented Recovery feature. Installed #706 currently uses the existing embedded Core and device-passed shared QuickJS/RAPP smoke.

## 2026-10-10 — Core extraction rule: QuickJS remains a generic APK-host service

**Locked by user:** QuickJS is the one persistent APK-hosted JavaScript engine, **not part of embedded Core ownership**. `RiftHostQuickJsV1` provides bounded, versioned JavaScript execution to both the existing Core and independently compiled replacement Core. Core alone owns RAPP lifecycle, queues, program state, grants/effects and presentation data; graphical Shell remains independently replaceable. No separate independently compiled QuickJS VM is required. This host ABI requires one future manually signed APK build and device validation before external Core can use it; compatible later Core changes should not rebuild the APK. The independent Core remains fail-closed pending broker/install/device N-1 parity. Signed installed #705 already passed >1 MiB ordinary RAPP pack/install/execute, but **not** this external Core ABI. See [external component contract](external-components/README.md), [build evidence](external-components/BUILD_EVIDENCE.md), and [roadmap](docs/systems/android-host/EXTERNAL_CORE_SHELL_ROADMAP.md).

## 2026-10-10 — Real external component DEX compiled, not activated

User-installed signed RiftOS **#701** is live and device-proven for embedded-default host, separate Shell/Core observer, RAPP window smoke and new Admin UI control presence. Separately compiled the first **Core v0.1** and **graphical Shell v0.1** raw DEX files through the registered `kotlin-android` → D8 pipeline; source/build producer live in [external-components/](external-components/README.md) outside the APK, and output DEX/manifests live in `/D:/Builds/Components/{core|shell}`. [Exact D8 hashes and missing gates](external-components/BUILD_EVIDENCE.md). **Do not activate Core**: its external JS RAPP interpreter, input, effects and grants are not yet implemented. Shell graphical parity and protected loading not device-tested. Host verifier source now recognizes bundled Kotlin standard-library support definitions, but #701 still has the old verifier until a future manually signed APK build. No external revision/N-1 is accepted. Recovery GitHub backup remains untouched.

## 2026-10-10 — Main-branch protected Core/Shell update infrastructure (SOURCE, NOT DEVICE-PROVEN)

Current Android host source now supports strict independently built `core.dex`/`shell.dex` manifest staging, binary class/type-closure checks, private per-SHA qualification, exact signer/PID one-use native Admin Approvals stage/next-start activate/post-device accept, last accepted external N-1 journal/recovery, selected-Core execution ABI and separate graphical Shell startup. Full contract: [Protected component format](docs/systems/android-host/PROTECTED_COMPONENT_FORMAT.md). The installed #697 signed E1 embedded-default Core remains last device-proven baseline. **True independent Core RAPP execution implementation, feature-complete independent graphical Shell, compiler artifacts, and physical N-1 crash proof remain pending.** User controls manual signed Builder and all device tests; GitHub recovery snapshot is untouched and active work is pushed on main.

## Current priority — real external Core and RiftShell, host-stable no-repack workflow (2026-10-10)

The [authoritative host/Core/Shell migration roadmap](docs/systems/android-host/EXTERNAL_CORE_SHELL_ROADMAP.md) supersedes earlier source-only aspirations. The Android APK is to become a **stable bootstrap/verifier/supervisor and MCP/relay host**. Once its independent installer/ABI/consent/watchdog and recovery are signed and device-proven, routine compatible Core and Shell updates use separately compiled, content-addressed verified artifacts **without rebuilding the APK**. First prove real external Core runs without invoking embedded Core execution; retain embedded Core only as migration fallback. Then implement crash records with all evidence Android exposes, version history and automatic rollback to the previous **known-good external Core**; repeat for the real graphical Shell with Core and RAPPs surviving Shell death. Only after both are proven remove embedded implementations in a final manually signed minimal APK. APK rebuilds remain necessary for genuine Android/host/ABI changes and cannot be promised away before host acceptance.

**Evidence distinction:** manually signed installed #695 is an E0 **embedded** compatibility pass. Pushed RiftOS `812dd80d1e0e` and Builder `bb2bd8fc275c` add source-verified E1 candidate-selector groundwork but have not been reinstalled/device-proven in this conversation. No independent external Core/Shell or full watchdog/activation system is established yet.

RiftOS is an Android-hosted user-space operating environment. **Permanent architectural rule:** RiftOS Core owns software installation, runtime management, filesystems, processes, application execution and capabilities. RiftShell is a **replaceable desktop/UI component** providing the desktop, taskbar, Start menu, File Explorer and user interaction; applications must not need RiftShell to execute. Android still owns the underlying process/security boundary. The transition is implemented in [phased Core/Shell gates](docs/systems/core-shell/README.md), starting with process-scoped Core service ownership; app sessions and a truly replaceable shell are not yet independent in C1.0.

## Verification status

**ENGINE DOCUMENTATION VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

The native migration in the local source tree is source-audited. It is **not yet claimed build/device-proven** until the updated source validators, Android Builder, install and live abuse gate pass.

## Current engine

```text
Android process
├─ MainActivity
│  ├─ RiftNativeDesktop
│  ├─ RiftNativeSystemApps
│  ├─ RiftNativeWorkspaceApps
│  ├─ RiftBrowserWindow
│  ├─ RiftBrowserAppHost
│  ├─ RiftWorkspaceRecords
│  └─ RiftWorkspaceWatcher
│
├─ RiftMcpRuntime
│  ├─ RiftNativeShell
│  ├─ RiftNativeGit
│  ├─ RiftToolHost
│  │  └─ RiftToolSandbox
│  ├─ RiftMcpServer
│  ├─ RiftMcpRelayClient
│  └─ RiftVortexBridgeClient
│
├─ RiftHeadlessJsRuntime
│  └─ QuickJS -> Rift++ Core + RiftVM
│
└─ app-private RiftFS / preferences / Keystore
```

`RiftKernel` is the logical name of the protected RiftOS engine identity. It is not a JavaScript kernel hosted in a shell WebView.

## Native desktop and built-ins

`MainActivity` boots the native desktop directly. `RiftNativeDesktop` owns window frames, bounds, focus, z-order, minimize/maximize/restore/close, launcher and taskbar behavior.

Current built-in bodies are native Android Views:
- Files
- Editor
- Dev Lab
- Workspace Records
- Settings
- RiftShell Terminal
- Task Manager

RiftBrowser is a separate renderer surface attached to native desktop windows.

## RiftFS

RiftOS storage is rooted at app-private `filesDir/riftfs`.

`RiftVolumePaths` supplies fixed display aliases:
- `C:/` — RiftOS system/program state
- `D:/` — user/workspace data

`D:/Workspace` maps to the canonical workspace backing tree. MCP filesystem/Code Mode operations are independently hard-scoped to `filesDir/riftfs/workspace`.

Native Files also supports user-granted Android Storage Access Framework document-tree mounts under its `/Android` view. Those mounts are not raw filesystem escape paths and are not exposed to workspace MCP tools.

## Native RiftShell

`RiftNativeShell` is process-owned through `RiftMcpRuntime`. It does not depend on a particular Activity or WebView lifetime while the Android process remains alive, and it does not expose Android/Linux `/system/bin/sh`. Android process death destroys the in-memory singleton and a new process recreates it lazily.

The shell owns native navigation/file commands and finite routes for native Git, chat handoff, Dev Lab, Vortex, local agents, RiftLLM fixed services, the Codynex LR0 Binder bridge, production Rift++, Semnexis, and experimental RiftCLI.

The legacy shell `mount`/`umount` and generic `rift` wrappers are retired in the current source.

## Rift++

Rift++ compiler authority is project-owned and hot-swappable through RiftBuild's managed compiler registry. Native compiler payloads execute through the bounded `native-buffer-v1` engine in the generic isolated `:riftNativeBufferCompiler` process; JVM/DEX compiler payloads use the generic `dex-json-v1` engine. RiftOS exposes only project-neutral compiler status/run plus generic JVM DEX conversion. The external build provider owns Compile → Preflight → Pack → Sign → Verify and uses those generic capabilities without a Rift++-specific or Kotlin-specific RiftOS build route.

The former `riftpp-host` / `:riftppCompiler` / `RiftppCompilerService` path and the special `prepare-riftpp-*` proof/package routes are retired. RiftOS does not pin Rift++ compiler generations or own Rift++ parsing/code-generation semantics; compiler identity comes from exact-hash managed payload manifests.

The older `riftpp` command family remains present as a **legacy compatibility/reference route** through bounded non-browser QuickJS:

```text
RiftNativeShell
 -> RiftHeadlessJsRuntime
 -> src/riftpp-core.js
 -> src/riftvm.js
```

That legacy path is not the compiler authority for the new machine-code Rift++ work. Gradle still packages its retained assets for compatibility while the new host awaits Builder compilation and installed-device proof.

## Semnexis

`semx` uses the same bounded headless QuickJS host with the separately packaged `src/semnexis-bootstrap.js` compiler asset. Current source is `0.7.0-quickjs-bootstrap` with versioned `SNIRV0`–`SNIRV7`, borrowed `Slice<u8>`, flat records, Arena-backed AST storage, typed Arena load/store, bounded 256-frame native recursion, and source/machine-proven recursive-descent parsing on ARM32. The source-embedded gate is `semnexis-bootstrap-self-test/17`; installed-device promotion waits for the next RiftOS APK build/install.

## RiftBrowser

Chromium is not the OS engine. It is owned only by explicit `RiftBrowser*` classes.

`RiftBrowserEngine` is the renderer interface and `RiftBrowserAndroidWebViewEngine` is the current implementation. Gradle has a hard preBuild validator that rejects WebKit/WebView ownership outside the explicit RiftBrowser source allowlist.

`RiftBrowserWindow` implements a bounded tab/navigation/desktop-mode API, but the current native composition only has live callers for open, Back, state and the bounded inspector. Forward/reload/direct navigation/desktop-mode/tab-management methods are implemented but currently unwired; they are not claimed as active UI features. File chooser ownership and the exact-origin MCP compatibility path remain inside RiftBrowser-owned code.

## Installed Rift programs

`MainActivity` scans valid package manifests already present under `C:/Programs`, and `RiftBrowserAppHost` can run those HTML/JS programs in dedicated capability-gated WebViews at `https://app.riftos.local`.

Current source contains the bounded `RiftBuildInstaller` proof installer, restricted to verified signed `com.riftpp.nativeproof` artifacts, Android-managed unknown-source/user confirmation, persisted install status and an exact NativeActivity launch proof. That newest signer/install path is still source-only until the next Builder/install pass; it is **not** a general Rift app installer. The old `src/riftapps.js` installer/runtime remains retained repository/reference source and is not packaged by Gradle.

## Rift MCP

`RiftToolHost` is the canonical device-side capability registry. The current source catalog is exactly 21 model-visible tools, including persistent read-only `rift_mcp_reconcile` for ambiguous/frozen client-turn recovery.

Ordinary filesystem/Code Mode tools execute in `RiftToolSandbox` and are confined to the workspace. `rift_shell_exec` is a separately permissioned route to the process-owned native shell.

`RiftToolSandbox` includes:
- bounded filesystem operations;
- SHA-256 file/tree hashing;
- snapshots;
- local project audit/scan;
- deterministic project export;
- Project Intelligence v2 indexing;
- symbol/reference/dependency views;
- guarded range/hunk patches;
- guarded one-operation Code Mode mutations with copy-on-write rollback; multi-op/batch execution is fail-fast disabled;
- bounded archive/extract;
- private Workspace Records integration.

The optional relay is outbound-only and does not widen filesystem authority.

## Workspace Records

`RiftWorkspaceRecords` and `RiftWorkspaceWatcher` are native/process-local. They observe the canonical workspace tree and persist private record/checkpoint data outside the workspace itself.

The current built-in Workspace Records UI is native. The old `workspace-live` HTML/JS component remains repository reference source and is not the live built-in.

## Git and secrets

The live Git owner is `RiftNativeGit`, not `src/riftgit.js`.

Git credentials are accessed through `RiftSecretStore`/Android Keystore-backed storage and native Settings. Shell arguments do not carry the GitHub token.

## JavaScript source status

The repository still contains the former web-shell runtime under `src/`, including `riftcore.js`, `riftos.js`, `riftrt.js`, `riftgit.js`, `riftworkspace-*.js` and other modules.

Except for `riftpp-core.js`, `riftvm.js`, and `semnexis-bootstrap.js`, those files are **not packaged as active Android headless-runtime assets**. They are retained for tests, migration/reference behavior and future porting work.

Presence in `src/` does not mean “live APK code.”

## Android target

Current Gradle source declares:
- package: `com.riftos.app`
- compile SDK: 36
- target SDK: 36
- minimum SDK: 26
- Java source/target: 17
- version name: `0.11.11-relay-client`
- release minification: disabled

Manifest runtime components are the native launcher Activity, Rift MCP Activity, RiftBrowser preview Activity and the user-enabled fixed-scope Accessibility service.

## Current promotion gate

For this native migration, the order is:

1. code-first source audit;
2. documentation rebuilt from source;
3. second independent audit for missed/stale claims;
4. `npm run check` / native architecture validators;
5. explicit push only when authorized;
6. external Android Builder;
7. install exact artifact;
8. cold-start + lifecycle + browser-renderer + MCP/shell abuse;
9. only then treat the migrated runtime as device-proven.

## Documentation

Start with:
- [`docs/systems/engine/README.md`](docs/systems/engine/README.md) — verified engine authority map;
- [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md) — current source status and unproven boundaries;
- [`docs/README.md`](docs/README.md) — subsystem maintenance index;
- [`docs/SOURCE_OWNERSHIP.md`](docs/SOURCE_OWNERSHIP.md) — source-to-owner ledger;
- [`docs/TRUE_OS_ARCHITECTURE.md`](docs/TRUE_OS_ARCHITECTURE.md) — host/security boundary;
- [`docs/ANDROID_NATIVE_ARCHITECTURE.md`](docs/ANDROID_NATIVE_ARCHITECTURE.md) — Android-native composition;
- [`docs/systems/build-validation/README.md`](docs/systems/build-validation/README.md) — promotion gates.

No documentation is authoritative merely because it exists. Treat a subsystem README as trusted only after it has been audited against its current owning source.

## RiftDebugHub

Current source includes a process-wide passive debugger foundation:

- `RiftDebugHub` retains a bounded in-memory event timeline and active-span table;
- `RiftDebugAdapter` and `RiftDebugSink` are the common plug for additional subsystems;
- MCP Server and Tool Host already publish correlated parent/child spans;
- MCP results return `riftos/traceId`;
- the read-only `rift_debug` tool exposes `status`, `events`, `active` and `components`;
- secret-like attribute keys are redacted, payload bodies are not retained, and all fields/collections are bounded;
- the debugger has no execution, mutation, cancellation, filesystem, network or model authority.

See [the verified debugger subsystem document](docs/systems/debugger/README.md).
