# RiftOS Shell UI and System Windows

## Purpose

RiftOS system UI is being split away from the trusted compatibility WebView. Android already owns the desktop/window manager; Patch 2A moves the visible RiftShell Terminal and Task Manager into Android-native Views as well. The trusted web shell remains a temporary compatibility plane for unmigrated built-ins such as Files, Editor, Settings, Dev Lab and Workspace Records plus the remaining JavaScript runtime.

## Ownership boundary

`RiftNativeSystemApps.kt` owns migrated Android-native built-in bodies. `RiftNativeDesktop.kt` continues to own all window chrome, geometry, focus, taskbar and launcher state. `RiftNativeShell.kt` owns shell execution. `src/riftos.js` keeps compatibility routing and the remaining web-built-in bodies, but its `openTerminal()` and `openTasks()` entry points now only dispatch `system.app.open` and never construct those bodies in DOM.

This split is deliberate: moving a built-in native must not duplicate its backend policy. Terminal delegates commands to the existing process-owned `RiftShellExecutor`; Task Manager reads and closes the authoritative native desktop window state instead of inventing a second task registry.

## Source ownership

- `android/app/src/main/java/com/riftos/app/RiftNativeSystemApps.kt` — migrated Android-native Terminal and Task Manager bodies/lifecycle.
- `android/app/src/main/java/com/riftos/app/RiftNativeDesktop.kt` — native window frames/content attachment, focus and window state.
- `src/riftos.js` — compatibility app dispatch plus Files, Editor, Settings, Workspace Records and other not-yet-migrated built-in UI.
- `index.html` / `styles.css` — trusted compatibility shell boot/base styling while that plane still exists.

## Native built-ins

### RiftShell Terminal

The Terminal is a native Android `EditText`/`TextView` surface attached directly to the `terminal` native WindowRecord. Commands call the process-owned `RiftShellExecutor` with the Terminal's current cwd. Native commands therefore never enter Chromium. Command families that have not yet migrated may still use the explicitly temporary `RiftShellBridge` compatibility fallback inside the executor; that fallback is a command-runtime boundary, not the Terminal UI.

Terminal submission is serialized while one command is active so cwd transitions cannot race. Output is bounded and old text is trimmed instead of allowing an unlimited native view buffer.

### Task Manager

Task Manager is an Android-native View attached to the `tasks` WindowRecord. It queries `desktop.window.state` and displays protected RiftOS runtime authorities plus live native desktop windows. End Task closes the selected WindowRecord through `desktop.window.close`; it does not pretend every RiftOS window is a separate Android/Linux PID. The list refreshes on a bounded timer while Task Manager is open and the timer is removed when the window closes.

## Compatibility built-ins

Files, Editor, Settings, Dev Lab, Workspace Records and other remaining web bodies still use the trusted compatibility plane until their own migration cut lands. Their launcher ids and external entry points remain stable while ownership moves underneath them.

## Critical invariants

- `terminal` and `tasks` must open through `RiftNativeSystemApps`, whether launched from native launcher/taskbar or from compatibility `openApp()`.
- `RiftNativeSystemApps.kt` must not import or construct `WebView`.
- Terminal must execute through `RiftShellExecutor`; it must never expose Android `/system/bin/sh` or create a second shell parser.
- Task Manager must use `RiftNativeDesktop` state/close authority and must not manufacture fake Android process IDs.
- Closing a migrated native built-in must clean its native UI state without requiring a JavaScript `closeWindow()` callback.
- Base launcher ids remain stable so migration does not duplicate windows or taskbar entries.

## Failure signatures

- Launcher Terminal/Tasks opens a blank compatibility body -> `MainActivity.openNativeDesktopApp()` native-first routing regressed.
- `open terminal` or compatibility launcher creates DOM -> `src/riftos.js` entry routing regressed.
- Terminal works only while trusted shell renderer is alive for native commands -> `RiftNativeSystemApps` is not using process-owned `RiftNativeShell` correctly.
- Task Manager shows stale/phantom windows -> inspect `desktop.window.state` refresh and close cleanup, not the old JS `ProcessTable`.
- Closing Tasks keeps refreshing -> native Task Manager Handler cleanup regressed.

## Fix map

Native Terminal / Task Manager UI and lifecycle -> `RiftNativeSystemApps.kt`.
Native frame/state/focus/close -> `RiftNativeDesktop.kt`.
Native launcher interception / compatibility request routing -> `MainActivity.kt`.
Compatibility built-ins and stable app entry functions -> `src/riftos.js`.
Shell execution semantics -> `RiftNativeShell.kt` and temporary `RiftShellBridge.kt` fallback.

## Validation

Build-time transport validation asserts that Terminal/Tasks are owned by `RiftNativeSystemApps`, that the class has no WebView dependency, that MainActivity intercepts native launcher opens, and that the compatibility entry points only call `system.app.open`. On-device acceptance must open/focus/minimize/restore/close both apps repeatedly, execute native shell commands and bounded errors, End Task other windows, and verify the RiftOS process/MCP uptime does not reset.
