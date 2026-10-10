# Android Host

## 2026-10-10 — Generic external DEX module host (Gate 2, source checked; user device proof pending)

`RiftGenericModuleService.kt` declares the **single** nonexported `:riftModuleHost` process for approved generic DEX entrypoints; it does not contain application-specific module classes and is not an independent Android security UID. The Core-owned `RiftCoreModuleManifest.kt`, `RiftCoreModuleStore.kt` and `RiftCoreModuleActivation.kt` validate strict module identity/digest, stage sealed bytes, and coordinate a bounded one-use activation with previous-version rollback and nonce/PID execution receipt. The user must separately approve stage and activation in the trusted native UI. The original fixed ProbeV1 remains available, and generic runtime-provider APK registration stays restricted. This is a source/ownership checkpoint, **not a successful signed APK or device proof**. See `docs/GENERIC_MODULE_IMPORTER_GATE2.md` and the user-owned test plan `docs/GENERIC_MODULE_IMPORTER_DEVICE_TEST.md`.

## 2026-10-09 — Trusted native external DEX proof user flow (SOURCE ONLY)

Admin Approvals owns system picker for separately compiled ProbeV1 DEX; its two Core signed/PID-checked one-use approvals first stage read-only SAF descriptor bytes, then activate precisely the verified SHA-256 through separate nonexported `:riftBootstrapProbe`. A user-manual external javac+D8 artifact workflow, independent from user-manual RiftOS APK Builder, supplies the DEX. Android Core/Shell manifest and process boundaries remain unchanged; critical dynamic replacement remains disabled. No external DEX binary or installed physical proof exists yet. Detailed gates in `BOOTSTRAP_HOST_MIGRATION.md`.

## 2026-10-09 — Immutable staged probe component architecture (SOURCE ONLY)

With signed RiftOS #680 live and the previous C2-A positive registry test confirmed by screenshot plus Core audit, the bootstrap host now gains `RiftBootstrapComponentStore`. It can stage content-addressed read-only DEX revisions and atomically select an internal noncritical `probe` activation with previous-revision fallback and boot-interruption recovery. The installed Core/Shell remain embedded and protected; external Core/Shell activation is explicitly disabled until device proof. The nonexported `:riftBootstrapProbe` Service is separately process-scoped and stays inert without future native Core authorization. There is **no user-facing installer/consent IPC, loaded external probe, new signed APK, or live hot swap** at this checkpoint. See `BOOTSTRAP_HOST_MIGRATION.md`; user alone starts Builder.

## 2026-10-09 — Bootstrap Host compatibility boundary (SOURCE ONLY)

`RiftCoreApplication` now delegates default-process Core startup and remote-`:riftShell` WebView preparation to `RiftBootstrapHost`. With no external activation file (the only shipping configuration), the original embedded Core recovery, Core runtime, Shell recovery, MCP relay and browser data-directory sequence remains unchanged. An optional hash-verified, read-only, app-private DEX entrypoint is defined for future separately delivered Core/Shell implementations at **process startup only**. It is NOT a working hot-swap, a component installer, or a completed Core/Shell extraction, and it has not been built/device tested.

See [Bootstrap Host Migration](BOOTSTRAP_HOST_MIGRATION.md) for exact contract, guards, blockers and remaining gates. The user manually runs the existing signed Builder; source proof does not promote C2-A, C2-B2 or this migration to Android DEVICE PASS.

## 2026-10-09 — C1.4-C1 Core process startup interrupted journal recovery SOURCE CANDIDATE

Default-process `RiftCoreApplication.onCreate` attempts `RiftCoreAdminRollbackProof.recover` before `RiftCoreRuntime.initialize`: only a fixed journalled Core virtual-C: canary `/C:/RiftOS/.c14c-rollback.txt` can be removed after an interrupted first privileged proof transaction. A recovery failure is logged, and future proof calls independently re-run recovery and fail closed; no unrelated app/RAPP lifecycle is stopped. No secondary shell or RAPP process can perform this recovery. Android app sandbox, OS filesystem restrictions and no-root assumption are preserved. No build/device process-crash proof yet. Separate C1.3-E real production graphical process restart remains previously signed-device-proven, but B-ticket old-shell-PID revocation across another actual kill remains a C1.4-C hardening test requiring specific user approval.


## 2026-10-09 — C1.3-E Android background/process restart physically verified

The user-manually built/signed/installed Builder #661 RiftOS source `fc486831` successfully demonstrated actual OS production `:riftShell` PID **10831** terminating and Android starting a different production shell **PID14251** automatically while default Core process PID10730 remained healthy. Core's recovery watcher recorded previous PID, replacement PID, one restart attempt then a stable zero-attempt state, and the pre-existing Core RAPP generation1 state persisted. The native graphical desktop and disposable RAPP window restored without manually launching RiftOS, with saved pre-crash text and post-recovery ACTION/TEXT_INPUT proven. Normal Android background transition did not trigger false restarts. Initial guarded kill was correctly rejected before process termination when a foreground heartbeat had not arrived; separately user-approved one-time retry succeeded after foreground lease verification. **C1.3-E DEVICE PASS on this physical phone.** Android background Activity policy may behave differently under other device/user/battery conditions; this proof covers the observed successful recovery, not a universal API guarantee.


## 2026-10-09 — C1.3-E Core-owned remote shell recovery SOURCE CANDIDATE

After C1.3-D signed Builder #660 DEVICE PASS, the real desktop belongs to Android `:riftShell` while Core remains in the app's default process. C1.3-E adds `RiftCoreShellRecovery` from `RiftCoreApplication.onCreate` ONLY for that default Core process; the graphical Activity never owns Core's process watchdog. A reported foreground, OS-verified dead `:riftShell` process triggers at most three Android `startActivity` requests, spaced at least five seconds, with a 15-second healthy replacement requirement before crash budget reset. Ordinary `onPause`/Home reports background rather than initiating recovery. The default Core process continues RAPP executable sessions. Android may block background Activity launches even when `startActivity` returns without a visible new Activity; C1.3-E will not be device-promoted without automatic real shell PID change and desktop/window restoration in a new USER-manual signed build. A Core-side local QA hook targets only the exact known disposable input probe and OS-attested graphical shell PID, never Core or four protected installed apps. Existing `:riftShellProbe` remains read-only diagnostic. No real process has been killed during source editing.


## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-30.**

This subsystem was rebuilt from `MainActivity.kt`, `AndroidManifest.xml`, Android resources and direct lifecycle/call-site references. Browser, Desktop, MCP, Files, Preview and Accessibility behavior are mentioned only where they cross the Android-host boundary; their subsystem READMEs remain independently unverified until audited.

## Purpose

The Android Host is RiftOS's application/component and Activity-lifecycle boundary.

It owns:
- Android application/component declarations;
- visible desktop Activity composition;
- system-window inset/root View setup;
- launcher population/routing;
- Activity-to-process-runtime registration;
- Android Back routing;
- Activity result dispatch;
- startup/shutdown of Activity-owned objects;
- handoff between process-owned services and UI-only operations.

It does **not** own browser rendering, desktop window policy, shell command semantics, MCP tool policy, filesystem policy or local-agent behavior. Those remain with their narrow subsystem owners.

## Source ownership

Host source:
- `android/app/src/main/java/com/riftos/app/MainActivity.kt`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/res/values/styles.xml`

Host-adjacent lifecycle/source boundaries:
- `RiftMcpRuntime.kt` — process-lifetime service singletons and weak MainActivity reference.
- `RiftWorkspaceWatcher.kt` — Activity-created/stopped workspace observation.
- `RiftBrowserWindow.kt` — browser Activity-result/lifecycle recipient.
- `RiftNativeWorkspaceApps.kt` — native Files SAF Activity-result recipient.
- `RiftAppDiagnosticBridge.kt` — process-local allowlisted localhost diagnostic receiver used only around supported Rift++ launches; it owns transport/evidence, not target runtime semantics.

Manifest-declared components whose internal behavior belongs elsewhere:
- `RiftNativeBufferCompilerService.kt` — generic `native-buffer-v1` compiler worker, isolated in `:riftNativeBufferCompiler`; it executes bounded exact-hash native compiler payloads and owns containment/transport only, not any project language semantics.
- `RiftManagedJvmToolService.kt` — generic isolated `:riftJvmToolHot` loader for exact-hash JVM/DEX compiler/tool payloads using the bounded JSON tool ABI.
- `RiftBuildInstallReceiver` in `RiftBuildInstaller.kt` — private PackageInstaller result + protected first-launch proof receiver for the fixed RiftBuild install allowlist;
- `RiftMcpActivity.kt` — MCP configuration/status UI.
- `RiftBrowserPreviewActivity.kt` — bounded preview renderer.
- `RiftVortexAccessibilityService` in `RiftVortexLocalAgent.kt` — user-enabled Accessibility service.

## Android application policy

The manifest currently declares:
- `INTERNET`;
- `VIBRATE`;
- `POST_NOTIFICATIONS`;
- `REQUEST_INSTALL_PACKAGES` — used only by the bounded RiftBuild proof installer and still subject to Android's per-source user trust/confirmation flow.

The source audit found no direct `Vibrator`/vibration call and no runtime `requestPermissions`/direct app notification-posting path in RiftOS Kotlin. These permissions are therefore recorded as **declared manifest permissions**, not evidence of an active host feature.

C1.0 introduces `RiftCoreApplication`, the Android Application entry which initializes a process-scoped `RiftCoreRuntime` **before** `MainActivity` creates the desktop. Worker service processes are excluded from this bootstrap. `RiftCoreRuntime` contains no desktop/Activity reference, and is the sole Core provider for installed packages, runtime registries and build platform commands. This is not a separately protected Android process yet; process death still affects both Core and shell. Canonical Core/Shell boundary: [core-shell](../core-shell/README.md).

C0.2.5 adds a generic Android package-visibility query action `com.riftos.runtime.EXECUTE_V1` for independently installed runtime-provider services. This action is not a package name or a permission grant. The OS validates each provider's exact registered package, exported service, current signing certificate SHA-256, versioned Binder protocol, bounded request/response and call timeouts before dispatch. Registry details: [RiftBuild runtime providers](../riftbuild/RUNTIME_PROVIDERS.md). Standalone QuickJS remains unpromoted until a separate signed provider APK passes device proof.

Package visibility queries are declared for:
- `com.vortex3d.app`;
- `com.riftllm.app`;
- `com.samsung.android.honeyboard`.

These are package-visibility declarations for still-present Vortex/RiftLLM/keyboard integration lanes. C0.2 removed fixed Codynex and Rift++ editor package queries together with the mirrored editor source/JNI payloads. Generic RiftBuild install/launch derives package identity from the verified APK and binds launch to install status. Visibility alone does not grant Android launch, Binder, install or diagnostic authority.

Application flags:
- `android:allowBackup="true"`;
- hardware acceleration enabled;
- resizable Activities enabled;
- RTL support enabled;
- `android:usesCleartextTraffic="false"`;
- theme `Theme.RiftOS`.

There are currently no manifest backup-exclusion/data-extraction rules in this project. Documentation must therefore not describe all app-private state as inherently excluded from Android backup.

## Theme and root window

`Theme.RiftOS` is a Material NoActionBar theme with dark status/navigation/window background and dark-system-bar icon policy.

`MainActivity.onCreate()` also:
- disables decor fitting through `WindowCompat.setDecorFitsSystemWindows(window, false)`;
- applies dark system-bar colors;
- creates one root `FrameLayout`;
- applies system-bar/display-cutout insets as root padding;
- installs the native desktop into that root.

`MainActivity` imports no WebKit API and creates no WebView.

## Manifest components

### MainActivity

Manifest state:
- exported: true;
- launcher Activity;
- launch mode: `singleTask`;
- resizable;
- orientation unspecified;
- soft input mode `adjustResize`;
- declares `configChanges` for keyboard, keyboardHidden, orientation, screenSize, smallestScreenSize and uiMode, so Android does not normally recreate this Activity for those changes. MainActivity does not override `onConfigurationChanged()`.

There is no `onNewIntent()` implementation. The launcher intent carries no RiftOS command/data contract, so `singleTask` must not be described as an external command channel.

### RiftMcpActivity

Manifest state:
- exported: true;
- browsable custom URI: `riftos://mcp`;
- resizable.

The Activity does not read Intent data/extras. External launch therefore opens the fixed MCP permissions/relay configuration UI; it does not dispatch caller-supplied MCP commands.

Its internal permission/relay behavior belongs to the MCP subsystem.

### RiftBrowserPreviewActivity

Manifest state:
- exported: false;
- resizable;
- `adjustResize`.

Only in-app code can launch this Activity. Its renderer/security details belong to Preview/RiftBrowser.

### RiftVortexAccessibilityService

Manifest state:
- exported: true;
- protected by `android.permission.BIND_ACCESSIBILITY_SERVICE`;
- user-enabled Accessibility service metadata.

The XML service configuration scopes observed packages to:
- `com.vortex3d.app`;
- `com.riftos.app`;
- `com.samsung.android.honeyboard`.

It requests interactive-window retrieval and gesture capability. The Android host only declares the service; command policy belongs to the local-agent subsystem.

## MainActivity composition

On creation, MainActivity:

1. registers itself with `RiftMcpRuntime`;
2. configures root/system-window behavior;
3. obtains `RiftWorkspaceRecords`;
4. creates `RiftWorkspaceWatcher` and starts its bounded tree installation on a daemon background thread;
5. creates `RiftNativeDesktop`;
6. installs the root View;
7. creates `RiftBrowserWindow`;
8. creates `RiftBrowserAppHost`;
9. creates native system apps;
10. creates native workspace apps;
11. publishes built-in launcher entries immediately, then scans installed `C:/Programs` packages on a bounded background thread and republishes the completed launcher;
12. bootstraps the desktop;
13. starts the process-owned outbound relay client.

The launcher always includes Files, Workspace Records, RiftShell, RiftBrowser, Editor, Dev Lab, Tasks, Settings and Rift MCP. Rift MCP launches the existing `RiftMcpActivity`; MainActivity does not duplicate relay/MCP state.

It additionally scans `C:/Programs` for existing package directories off the UI thread. The installed-program scan is capped at 5 seconds, 128 candidate directories and 32 MiB total package bytes. Launcher manifests are accepted only when:
- `package.json` exists and is 1 byte through 8 MiB;
- JSON contains a `manifest` object;
- id is unique and matches `^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$`.

The launcher scan is discovery only. It is not a package installer.

## UI bridge exposed to process-owned code

MainActivity exposes a small UI-facing method surface used by native shell/headless/local-agent code.

Live referenced methods:
- `nativeDesktopStateForShell()`;
- `closeNativeWindowFromShell()`;
- `openAppFromNativeShell()`;
- `openBrowserFromNativeShell()`;
- `inspectActiveBrowser()`.

`onUiSync()` marshals synchronous UI queries/actions onto the main thread with a 5-second bound.

Currently defined but with no current Kotlin call site:
- `nativeAppsForShell()`;
- `openDesktopBrowser()`;
- `openPreviewFromNativeShell()`.

Those methods are **implemented-but-unwired** and must not be presented as active command routes without a caller.

## Activity result routing

MainActivity uses the legacy Activity-result callback as a bounded compatibility router.

Dispatch order:
1. `RiftBrowserWindow.onActivityResult()`;
2. `RiftNativeWorkspaceApps.onActivityResult()`.

Current request-code ownership:
- browser file chooser: `7002`;
- native Files Android-folder SAF picker: `7101`.

The codes are distinct.

Browser result behavior belongs to RiftBrowser. SAF mount policy belongs to Files.

## Back routing

`MainActivity.onBackPressed()` resolves Back in this order:

1. if RiftBrowser is visible and its active engine can go back, browser navigation consumes Back;
2. otherwise `RiftNativeDesktop.handleBack()` gets the event;
3. otherwise Android `Activity.onBackPressed()` is used.

This is host routing only; browser-history and desktop Back semantics belong to their subsystems.

## Activity/process lifecycle

### Same-process lifecycle

`RiftMcpRuntime` stores a weak reference to the latest registered MainActivity.

MainActivity registers on:
- `onCreate`;
- `onResume`;
- window-focus gain.

`onPause` does **not** unregister it. Therefore `RiftMcpRuntime.activeActivity()` means the last registered MainActivity that is not finishing/destroyed; it is not a strict foreground/resumed-state predicate.

On `onDestroy`, unregistering clears the weak reference only if it still points at that exact Activity instance. That prevents an old Activity from clearing a newer replacement instance.

While the Android process remains alive, `RiftMcpRuntime` singletons for native shell, MCP host/server/relay, native Git and Vortex bridge are not destroyed by MainActivity teardown. C0.1 removed the project-specific Codynex/Rift++ editor shell clients; no editor bridge is now a native-shell singleton.

### Activity-owned teardown

`MainActivity.onDestroy()` shuts down/destroys:
- workspace watcher;
- browser window;
- native system-app surfaces;
- native workspace-app surfaces;
- installed-app browser host;
- native desktop.

### Process death

“Process-owned” does **not** mean process-death persistent.

Android process death destroys `RiftMcpRuntime` in-memory singletons. A new process reconstructs them lazily.

MainActivity does not save or restore native desktop/window state through `savedInstanceState`, and `RiftNativeDesktop` has no SharedPreferences/saved-state persistence path. A recreated desktop is freshly composed/bootstrapped from current source/storage state rather than restoring the prior in-memory window layout.

Persistent subsystem data may survive independently through RiftFS/preferences/Keystore, but that must be established by each subsystem audit.

## Workspace watcher lifecycle

MainActivity creates one `RiftWorkspaceWatcher` during Activity creation but starts its recursive observer installation on a daemon worker so launch is not blocked by workspace tree size. Its external event sink is currently a no-op; the live side effect is Workspace Records observation through the shared records owner.

The watcher is shut down with the Activity. A replacement MainActivity creates a replacement watcher.

This watcher is therefore Activity-owned, unlike `RiftMcpRuntime` process singletons.

## Non-ownership boundaries

Android Host does not own:
- window geometry/state semantics → Desktop;
- browser rendering/navigation/security → RiftBrowser;
- installed HTML app capabilities → Apps/RiftBrowser app host;
- preview content security → Preview;
- MCP schemas/grants/audit → MCP;
- native shell command semantics → RiftShell;
- Git transactions → RiftGit;
- SAF mount/filesystem semantics → Files;
- Accessibility/local-agent command policy → Vortex/local-agent;
- persistent records semantics → Workspace Records;

MainActivity is composition and lifecycle glue; subsystem policy must not migrate into it.

## Critical invariants

- no WebKit import in MainActivity;
- Chromium imports remain only in explicit RiftBrowser-owned classes;
- no broad native dispatcher or shell WebView returns;
- Activity teardown cannot explicitly destroy process-owned RiftMcpRuntime services;
- unregistering an old Activity cannot clear a newer registered Activity;
- Activity-result request codes remain non-colliding;
- preview Activity remains non-exported;
- MCP deep link remains fixed configuration UI rather than command dispatch;
- Accessibility service remains permission-gated and package-scoped;
- UI-only shell operations fail clearly when no usable MainActivity exists;
- docs distinguish same-process Activity recreation from process death;
- Android backup policy is described from manifest truth, not assumed;

## Failure signatures

- MainActivity imports/creates WebView → renderer ownership regression;
- browser/app renderer crash tears down native desktop/shell/MCP → host isolation regression;
- same-process Activity replacement loses native shell/MCP singleton state → process-owner regression;
- process death is documented as preserving in-memory shell/MCP state → documentation error;
- stale Activity unregister clears a newer Activity reference → activity-reference race;
- shell UI command silently acts without a usable Activity → host/UI availability bug;
- browser picker result reaches Files or Files picker reaches browser → request-code/routing regression;
- externally supplied `riftos://mcp` data is interpreted as a command → exported-Activity boundary regression;
- preview becomes exported → preview attack-surface regression;
- docs claim backup exclusion while `allowBackup=true` and no exclusion rules exist → manifest/documentation mismatch;
- docs call implemented-but-unwired MainActivity methods active routes → reachability documentation error;

## Fix map

Android component declarations/permissions/application policy → `AndroidManifest.xml`.

Root Activity composition, lifecycle, launcher discovery, Back and result routing → `MainActivity.kt`.

System-window theme → `styles.xml`.

Process/UI Activity registration → `RiftMcpRuntime.kt`.

Workspace watcher lifetime → `RiftWorkspaceWatcher.kt`.

Browser picker lifecycle → `RiftBrowserWindow.kt`.

Files SAF picker lifecycle → `RiftNativeWorkspaceApps.kt`.



MCP settings Activity internals → MCP subsystem, not Android Host.

Accessibility command semantics → local-agent subsystem, not Android Host.

## Validation

Source validation for this subsystem must verify:
- manifest components exactly match existing source classes;
- export/permission/deep-link flags;
- MainActivity contains no WebKit imports;
- all WebKit imports remain RiftBrowser-owned;
- process runtime uses Application context for singleton services;
- Activity weak-reference register/unregister semantics;
- Activity result request-code uniqueness/routing;
- current MainActivity public method reachability;
- manifest permissions/package queries/application flags;
- backup policy declaration;
- Rift MCP launcher routes only to the existing `RiftMcpActivity`;
- no saved-state/window-layout persistence is falsely claimed.

Build/device promotion still requires:
- Android compilation/package/sign gate;
- cold launch;
- background/foreground;
- same-process Activity recreation where reproducible;
- true process-kill/relaunch behavior;
- MCP Activity deep-link launch;
- browser and SAF picker return paths;
- preview launch;
- Accessibility enable/disable lifecycle;
- browser renderer failure while native host remains alive.

Source verification does not substitute for those Builder/device gates.
