# RiftOS Shell UI and System Windows

## Purpose

The shell UI is the trusted web surface that turns RiftKernel services into the visible RiftOS environment: boot/status/workspace layout, launcher cards, base window records, system app dispatch, task manager, built-in text editing surface, terminal window and the common shell event/state glue used by desktop mode.

## Why this boundary exists

RiftDesktop manages desktop behavior, while individual apps own their domain logic. The shell layer sits between them: it creates common windows and routes the user to Files, Settings, Browser, Terminal, Tasks, Workspace Records and installed apps without owning the backend implementation of those systems.

## Source ownership

- `index.html` — trusted shell DOM and boot/workspace/window containers.
- `styles.css` — base shell/boot/status/window styling shared before desktop-specific styling.
- `src/riftos.js` — base window registry, launcher/system-app dispatch, shell status/clock, task manager, simple editor, Files/Settings/Browser/Terminal/Workspace Records entry points and cross-system UI glue.
- `src/riftdesktop-android.js/.css` — desktop behavior layered over shell windows, documented separately.

## Window model

`openWindow(id,title,kicker)` creates/reuses a base shell window and its record. `focusWindow`, `closeWindow`, `showDesktop`, `syncShellState`, and the window map keep the base state coherent. RiftDesktop upgrades these windows for desktop geometry/taskbar behavior; runtime apps may also participate through the public window-manager surface.

The shell must not become a second implementation of filesystem, browser rendering, MCP or Git. Entry functions should delegate to the owning system.

## Built-in surfaces

- `openTasks()` reads the kernel process table and presents process/uptime state.
- `openEditor(path)` is the current lightweight text editor used for ordinary text files; it is not the removed RiftDev IDE.
- `openTerminal()` wraps RiftShell.
- `openApp(id)` dispatches system apps or installed package/runtime apps.
- `appGrid()`/launcher refresh hooks expose installed/system app launchers.

## Critical invariants

- Base window IDs are stable identities; avoid duplicate windows for the same system surface unless explicitly designed.
- Closing/focusing a shell window must keep process/window/taskbar state consistent with RiftDesktop.
- System UI must call subsystem APIs instead of reaching around their security or storage boundaries.
- Boot/status elements remain usable before desktop enhancement finishes.
- The simple editor must await RiftFS writes and must not pretend to be a full IDE/project manager.

## Failure signatures

- App button does nothing but subsystem works directly -> launcher/openApp dispatch.
- Duplicate or stale windows -> base window map/open/close synchronization.
- Task list is wrong -> kernel process table or task rendering, not desktop DOM enumeration.
- Text editor saves wrong/stale path -> editor path/state/RiftFS call.
- Desktop-specific movement/minimize issue -> RiftDesktop, not base shell window creation.

## Fix map

Launcher, shared shell windows, status/task/editor/terminal entry wiring -> `riftos.js`.
Base static structure -> `index.html`.
Base look/boot/status styling -> `styles.css`.
Desktop geometry/taskbar/gestures -> desktop subsystem.
Backend behavior -> owning subsystem README from `docs/README.md`.

## Validation

After shell changes, cold boot and open every system surface. Open/focus/close multiple windows, check task list/process records, edit/save a text file, launch installed apps and verify desktop mode can still upgrade/reuse the same base windows.
