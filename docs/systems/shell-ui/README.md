# RiftOS Shell UI and Native System Windows

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

This document was rebuilt from `RiftNativeSystemApps.kt`, `MainActivity.kt`, `RiftNativeDesktop.kt`, the `RiftShellExecutor` contract and current call sites, then re-audited independently against those sources.

## Purpose

`RiftNativeSystemApps` owns exactly two Android-native system-window bodies:

- RiftShell Terminal — window id `terminal`;
- Task Manager — window id `tasks`.

It does not own native window chrome/geometry and it does not own RiftShell command semantics.

Window lifecycle belongs to `RiftNativeDesktop`. Command execution belongs to the process-owned `RiftShellExecutor` supplied by `RiftMcpRuntime.nativeShell()`.

No WebView/Chromium object is constructed or imported by this subsystem.

## Source ownership

Primary live source:
- `android/app/src/main/java/com/riftos/app/RiftNativeSystemApps.kt`

Direct live dependencies/callers:
- `MainActivity.kt` — constructs the subsystem, routes launcher opens, routes desktop-close callbacks and destroys Activity-owned UI state;
- `RiftNativeDesktop.kt` — owns WindowRecords, content attachment and close lifecycle;
- `RiftShellExecutor.kt` — bounded command execution interface;
- `RiftNativeShell.kt` — current process-owned implementation of that executor.

Retained `src/riftos.js` UI code is not packaged as the live Android shell UI.

## Public subsystem boundary

The current public Kotlin surface is intentionally small:

- `openFromLauncher(id): Boolean` — claims/opens `terminal` or `tasks`, otherwise returns false;
- `onDesktopClosed(id): Boolean` — releases system-window UI state after Desktop closes one of those ids;
- `destroy()` — tears down Activity-owned system-window state.

`handles()` and all open/render/refresh helpers are private.

The old unused `system.app.open`, `system.app.close` and `system.app.state` dispatcher/state surface was removed during this audit because no live caller existed.

## Launcher and window ownership

MainActivity routes launcher ids to `RiftNativeSystemApps.openFromLauncher()` before workspace-app or installed-program dispatch.

Opening either system app first opens/focuses the matching native Desktop WindowRecord:

- Terminal title: `RiftShell`;
- Terminal kicker: `ANDROID NATIVE SHELL`;
- Tasks title: `Task Manager`;
- Tasks kicker: `ANDROID NATIVE TASKS`.

The UI root View is then attached through `RiftNativeDesktop.attachContent()`.

Opening an already-open/minimized system app reuses the existing UI state and reattaches/focuses the same content rather than creating a second system-app body.

## RiftShell Terminal

### UI structure

Terminal is fully Android-native:

- vertical root layout;
- selectable monospace output `TextView`;
- scrollable output;
- monospace prompt `TextView`;
- single-line command `EditText`.

The input node has accessibility description `RiftShell command`.

The initial output reports the current RiftOS version and that the process-owned shell is ready.

### Terminal-local state

Each Terminal UI instance owns:

- current display cwd;
- output text;
- busy flag;
- current input control.

A newly created Terminal starts at cwd `/`.

Minimizing/focusing the existing window does not destroy this state.

Closing the Terminal destroys the UI state reference. Reopening after a real close creates a new Terminal UI and resets its UI cwd/output state to the initial values.

Activity destruction also drops Terminal UI state. This does **not** close the process-owned RiftShell executor.

### Command submission

Submission occurs on IME Done or Enter key-down.

Rules:

1. if this is no longer the current Terminal state, submission is ignored;
2. if a command is already busy, another command is not submitted;
3. input text is cleared immediately;
4. blank input is ignored;
5. the command line is echoed as `<cwd> $ <command>`;
6. exact command `clear` is intercepted by the Terminal UI and clears output without calling RiftShell;
7. every other command is sent to `RiftShellExecutor.execute(raw, cwd, callback)`;
8. input is disabled while that Terminal command is outstanding;
9. the executor callback is marshalled back onto the Activity UI thread;
10. successful results can update Terminal cwd from the returned `cwd`;
11. non-empty successful output is appended;
12. failed results append `error: <message>`;
13. the prompt is refreshed and input is re-enabled/focused.

The Terminal itself does not parse or implement RiftShell commands other than UI-local `clear`.

### Async close behavior

The process-owned executor may continue a command after the Terminal window is closed.

When its callback eventually reaches the Activity, the UI updates only if the original `TerminalState` is still the current Terminal instance. A closed/replaced Terminal therefore ignores stale completion callbacks.

Closing Terminal does not cancel or close the process-owned shell.

### Output bound

Terminal output is bounded to exactly `MAX_TERMINAL_CHARS = 200000` characters.

When a new append would exceed the cap, old output is trimmed and the retained tail is prefixed with:

`… older terminal output trimmed …`

The marker is counted inside the 200000-character limit.

The output view auto-scrolls to the bottom after append.

## Task Manager

Task Manager is also fully Android-native.

It owns:
- summary text;
- vertically scrollable task rows;
- one main-thread `Handler` refresh loop while Task Manager UI state exists.

### Authoritative data source

Task Manager calls:

`desktop.window.state`

once on open and then every 1000 ms while its UI state remains open.

It does **not** enumerate Android/Linux PIDs.

The summary reports the number of current Desktop WindowRecords:

`Native Task Manager · N desktop window task(s)`

The three protected system rows are additional logical identities and are not included in that window count.

### Protected logical rows

Task Manager always shows:

- `RiftKernel` — `system · protected`;
- `Rift Desktop` — `native window authority · protected`;
- `Native RiftShell` — `process-owned control plane · protected`.

These are descriptive protected RiftOS logical identities, not Android/Linux process records.

They have no End Task button.

### Desktop window rows

For each current Desktop WindowRecord, Task Manager displays:

- title;
- id;
- kicker/type;
- focused flag when applicable;
- minimized flag when applicable;
- maximized flag when applicable.

Every window except Task Manager's own `tasks` record receives an `End task` button.

Task Manager's own row is labeled `current` rather than `system` and has no self-End-Task button. It can still be closed normally through Desktop window controls/Back.

### End Task

End Task routes through:

`desktop.window.close`

It does not directly destroy another app body's internal state. Desktop removes the WindowRecord/content and synchronously invokes MainActivity's close-owner callback, which routes resource cleanup to the owning subsystem.

The Task Manager refreshes immediately after an End Task click.

Close exceptions are currently swallowed by the UI's bounded `runCatching` block and are reflected only by the subsequent refreshed state; there is no Task Manager error dialog.

### Refresh lifecycle

The 1000 ms refresh loop continues as long as the `TaskState` object remains current, including while the Task Manager window is minimized.

Closing Task Manager calls `stopTasks()`, which removes all callbacks/messages from its Handler and clears the TaskState.

Activity destruction does the same.

Reopening a still-existing Task Manager reuses its existing state and does not create a second refresh loop.

## Desktop close lifecycle

`RiftNativeDesktop.close()` removes the WindowRecord/content first, then synchronously calls MainActivity's `windowClosedSink`.

For these system windows MainActivity calls `RiftNativeSystemApps.onDesktopClosed(id)`:

- `terminal` -> clears Terminal UI state;
- `tasks` -> stops the refresh Handler and clears TaskState.

The old internal `close()` path that repeated `onDesktopClosed()` after Desktop had already invoked the callback was removed during this audit.

## Activity/process lifecycle

`RiftNativeSystemApps` is Activity-owned.

MainActivity creates it with:
- the current Activity;
- current `RiftNativeDesktop`;
- the process-owned `RiftMcpRuntime.nativeShell(this)` executor.

MainActivity `onDestroy()` calls `RiftNativeSystemApps.destroy()`, clearing Terminal state and stopping Task Manager refresh callbacks.

The visible Terminal/Task Manager UI therefore does not survive Activity destruction/recreation.

The underlying `RiftNativeShell` can survive same-process Activity replacement because its owner is `RiftMcpRuntime`. Android process death destroys that in-memory process owner.

## Current source cleanup performed by this audit

Removed from `RiftNativeSystemApps.kt`:

- stale “Patch 2A migration” class wording;
- `MIGRATED_IDS` migration-era naming;
- unused public `system.app.open/close/state` dispatcher;
- unused system-app state JSON surface;
- redundant second close cleanup path;
- unused `TaskState.refresh` field;
- unused `BORDER` constant;
- public visibility from the internal `handles()` helper;
- misleading Task Manager “WebView not required” summary text;
- retained `src/riftos.js` calls to the removed `system.app.open` bridge; the retained functions now fail explicitly because Terminal/Tasks are native-only.

Corrected:
- Task Manager self-row now says `current` instead of `system`;
- Terminal output trimming now respects the 200000-character cap including the trim marker.

## Non-ownership boundaries

Shell UI does not own:

- Desktop chrome, z-order, geometry, minimize/maximize/close -> Desktop;
- shell parsing/commands/filesystem/Git/Rift++ services -> RiftShell;
- MCP tool execution -> MCP;
- Files/Editor/Dev Lab/Workspace Records/Settings -> native workspace-app subsystem;
- RiftBrowser/browser renderer -> RiftBrowser;
- Android process lifecycle -> Android Host.

Terminal is a UI client of RiftShell, not the shell authority itself.

Task Manager is a UI client of Desktop state, not an Android process manager.

## Critical invariants

- exactly `terminal` and `tasks` are handled by this subsystem;
- no WebKit imports or WebView objects;
- launcher opens route directly through `openFromLauncher()`;
- no dead `system.app.*` dispatcher returns;
- Terminal cannot execute a second Terminal command while busy;
- Terminal `clear` remains UI-local;
- command callbacks cannot update a closed/replaced TerminalState;
- Terminal output remains within 200000 characters;
- closing Terminal does not close the process-owned shell;
- Task Manager reads authoritative Desktop WindowRecords;
- protected logical rows are never exposed with End Task;
- Task Manager cannot End Task its own row;
- Task Manager refresh callbacks stop on close/destroy;
- opening an existing system window does not create duplicate UI state;
- no system-window class imports/constructs Chromium.

## Failure signatures

- Terminal/Tasks opens in WebView/HTML -> native Shell UI regression;
- more than one Terminal or Tasks UI state exists for the same WindowRecord -> identity/lifecycle regression;
- Terminal cwd/output disappears merely after minimize/focus -> state reuse regression;
- closing/reopening Terminal incorrectly preserves old UI cwd/output -> close cleanup regression;
- stale shell callback writes into a newly opened Terminal -> TerminalState identity regression;
- Terminal output exceeds 200000 characters -> output-bound regression;
- Task Manager shows Android/Linux PID claims -> authority/documentation regression;
- Task Manager protected rows gain End Task buttons -> protection regression;
- Task Manager self-row says system/protected or gains End Task -> self-row regression;
- closing Task Manager leaves its 1-second callback loop alive -> Handler lifecycle leak;
- Task Manager summary claims all listed windows are WebView-free -> stale renderer assumption;
- `system.app.*`, `MIGRATED_IDS` or Patch-2A language returns -> migration residue;
- docs say Terminal owns RiftShell execution semantics -> subsystem-boundary error.

## Fix map

Terminal and Task Manager View/state/lifecycle behavior -> `RiftNativeSystemApps.kt`.

Window chrome/minimize/maximize/focus/close/content attachment -> `RiftNativeDesktop.kt`.

Launcher routing and desktop-close ownership dispatch -> `MainActivity.kt`.

RiftShell executor contract -> `RiftShellExecutor.kt`.

RiftShell command behavior -> `RiftNativeShell.kt` and its fixed native service owners.

## Validation

Source verification must recheck:

- exact public method surface;
- only `terminal` and `tasks` ids;
- launcher caller path;
- desktop-close callback path;
- absence of `system.app.*`;
- absence of WebKit imports;
- Terminal input submission conditions;
- UI-local `clear`;
- busy/input-disable behavior;
- executor callback UI-thread marshalling;
- stale TerminalState callback guard;
- cwd update/prompt update;
- exact 200000-character output bound;
- Task Manager state source;
- protected logical rows;
- self-row behavior;
- End Task close route;
- 1-second Handler scheduling;
- Handler shutdown on close/destroy;
- Activity-owned versus process-owned lifecycle;
- all stale migration names/fields removed.

Installed-device acceptance still requires:
- open/minimize/refocus Terminal without losing cwd/output;
- successful/failed/cwd-changing shell commands;
- `clear`;
- large-output trimming;
- close Terminal during a long-running command then reopen;
- open/minimize/refocus Task Manager;
- End Task each type of closable native/browser/app window;
- close Task Manager and verify refresh stops;
- repeated open/close cycles;
- Activity recreation;
- process kill/relaunch;
- accessibility keyboard/Enter and End Task controls.

Source verification is not a substitute for Android Builder or device abuse.
