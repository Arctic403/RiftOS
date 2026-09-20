# Android Host

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

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
- `RiftCliHost.kt` — thin Kotlin loader/envelope adapter for the native C++ RiftCLI core; it owns no CLI planning/memory/policy.
- `android/app/src/main/cpp/riftcli/rift_cli_jni.cpp` — explicit UTF-16/UTF-8 JNI marshalling boundary for RiftCLI.
- `RiftWorkspaceWatcher.kt` — Activity-created/stopped workspace observation.
- `RiftBrowserWindow.kt` — browser Activity-result/lifecycle recipient.
- `RiftNativeWorkspaceApps.kt` — native Files SAF Activity-result recipient.

Manifest-declared components whose internal behavior belongs elsewhere:
- `RiftBuildInstallReceiver` in `RiftBuildInstaller.kt` — private PackageInstaller result + protected first-launch proof receiver for `com.riftpp.nativeproof`;
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

Package visibility queries are declared for:
- `com.vortex3d.app`;
- `com.riftllm.app`;
- `com.riftpp.nativeproof` — fixed RiftBuild bootstrap install/launch target;
- `com.samsung.android.honeyboard`.

Those correspond to fixed Vortex, RiftLLM and Samsung-keyboard integration code paths.

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

While the Android process remains alive, `RiftMcpRuntime` singletons for native shell, MCP host/server/relay, native Git, Vortex bridge and the Codynex LR0 Binder bridge are not destroyed by MainActivity teardown.

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
- RiftCLI engineering logic/project memory/planning/verification → native C++ RiftCLI core.

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
- `RiftCliHost.kt` remains transport-only and does not become a second Kotlin CLI implementation;
- RiftCLI JNI text crosses the host boundary through explicit UTF-16/UTF-8 conversion.

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
- RiftCliHost gains planner/memory/model/network logic → Android-host ownership regression;
- RiftCLI JNI switches to modified-UTF shortcuts and corrupts standard UTF payloads → JNI text-boundary regression.

## Fix map

Android component declarations/permissions/application policy → `AndroidManifest.xml`.

Root Activity composition, lifecycle, launcher discovery, Back and result routing → `MainActivity.kt`.

System-window theme → `styles.xml`.

Process/UI Activity registration → `RiftMcpRuntime.kt`.

Workspace watcher lifetime → `RiftWorkspaceWatcher.kt`.

Browser picker lifecycle → `RiftBrowserWindow.kt`.

Files SAF picker lifecycle → `RiftNativeWorkspaceApps.kt`.

RiftCLI Android loader/result envelope → `RiftCliHost.kt`.

RiftCLI JNI string marshalling → `android/app/src/main/cpp/riftcli/rift_cli_jni.cpp`; CLI engineering behavior remains owned by `docs/systems/riftcli/README.md`.

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
- `RiftCliHost.kt` remains thin JNI transport and JNI uses explicit UTF-16/UTF-8 conversion;
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
