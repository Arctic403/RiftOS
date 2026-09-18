# RiftDesktop Window Manager

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

This document was rebuilt from `RiftNativeDesktop.kt` plus every current Kotlin caller/reference. Retained JavaScript desktop modules were treated as historical/reference source only.

## Purpose

`RiftNativeDesktop` is the single live Android-native authority for RiftOS desktop/window state.

It owns:
- desktop launcher rendering;
- Start menu;
- taskbar and task buttons;
- clock;
- one native WindowRecord per open window id;
- focus and z-order;
- native title bars and window controls;
- move/resize geometry;
- minimize/maximize/restore/close;
- Show Desktop behavior;
- content View attachment/detachment;
- desktop Back behavior after browser history routing;
- current native desktop state snapshots.

It does **not** own app body behavior. Native built-ins, RiftBrowser and installed-app hosts attach their own Views into desktop-owned window geometry.

## Source ownership

Primary live source:
- `android/app/src/main/java/com/riftos/app/RiftNativeDesktop.kt`

Current composition/callers:
- `MainActivity.kt`
- `RiftNativeSystemApps.kt`
- `RiftNativeWorkspaceApps.kt`
- `RiftBrowserAppHost.kt`

Retained/non-live desktop references:
- `src/riftos.js`
- `src/riftdesktop-native-compat.js`
- `src/riftdesktop-android.js`
- `src/riftdesktop-window-host.js`
- `src/riftdesktop-android-compat.js`
- `src/riftdesktop-android.css`
- `src/riftandroid-entry.js`

Gradle does not package those retained modules as the current Android desktop.

## Layering

The native host receives three primary layers in this order:

```text
root FrameLayout
├─ wallpaper
├─ contentHost
└─ chromeHost
   ├─ desktop launcher
   ├─ native window chrome
   ├─ taskbar
   └─ Start menu
```

App/browser content Views live in `contentHost`.

Desktop launcher, title bars, borders, resize handles, taskbar and Start menu live in `chromeHost`.

Because the launcher is in the chrome layer above `contentHost`, current `syncTaskbar()` behavior hides the launcher whenever at least one window is not minimized. The launcher becomes visible when no window is visible. This is current source behavior; it is not equivalent to a desktop where icons remain visibly behind open windows.

## Window record

Each open id has one `WindowRecord` containing:
- id;
- title;
- kicker/type label;
- title-bar/button/border/resize Views;
- frame bounds;
- optional maximize restore bounds;
- minimized/maximized flags;
- z value;
- optional attached content View.

Opening an already-existing id does not create a second window. It updates title/kicker, unminimizes and focuses the existing record.

Window ids are therefore unique desktop task identities.

## Current command surface

`handle(method, args)` implements:

- `desktop.window.bootstrap`
- `desktop.window.open`
- `desktop.window.focus`
- `desktop.window.close`
- `desktop.window.minimize`
- `desktop.window.maximize`
- `desktop.window.restore`
- `desktop.window.title`
- `desktop.window.showDesktop`
- `desktop.window.state`
- `desktop.launcher.update`
- `desktop.wallpaper.set`
- `desktop.layout.reset`

### Live external callers

Current Kotlin call sites use:
- bootstrap;
- open;
- close;
- title;
- state;
- launcher update.

### Native-UI-active routes

Even though no external Kotlin caller currently invokes their string command form, the following behaviors are active through native UI listeners:
- focus;
- minimize;
- maximize/restore;
- Show Desktop.

Taskbar clicks can focus or minimize windows. Title-bar controls invoke minimize/maximize/restore/close directly.

### Implemented but currently unwired

The source implements, but current Kotlin composition does not call:
- `desktop.wallpaper.set`;
- `desktop.layout.reset`;
- taskbar pin input through `desktop.launcher.update(... pins ...)`.

These are implementation capabilities, not currently wired user-facing features.

The old web-desktop `boundsCss`/DPR import path was removed during this audit because its only producer was retained `src/riftos.js` and no live native caller remained.

## Launcher and Start menu

Before bootstrap, Start/Show Desktop and launcher tiles are disabled.

After `desktop.window.bootstrap`, launcher interactions become active.

`desktop.launcher.update` accepts launcher rows with:
- id;
- name;
- icon.

Ids are deduplicated. Names are bounded to 64 characters and icons to 4 characters.

If a non-empty app update is supplied it replaces the launcher list.

The same app list drives:
- desktop launcher tiles;
- Start-menu tiles;
- icon lookup for taskbar items.

Launcher tile activation calls the `appOpenSink` supplied by MainActivity. Desktop itself does not decide whether the app body is Files, Terminal, RiftBrowser or an installed program.

## Taskbar

The taskbar is fixed at 48 dp and contains:
- Start button;
- horizontally scrollable task strip;
- 68 dp clock;
- 24 dp Show Desktop edge control.

The task strip is horizontally scrollable, so many open windows do not have to run underneath the clock/off-screen.

Open WindowRecords appear as task buttons ordered by z.

If taskbar pins are ever supplied:
- inactive pinned apps can appear;
- a matching open window reuses the pinned slot;
- compact desktop uses icon-only buttons;
- wider desktop may show labels for non-pinned open windows.

Current MainActivity sends no pin list, so native taskbar pins are presently unwired.

Task-button behavior:
- no live record → open app through `appOpenSink`;
- active visible record → minimize;
- otherwise → focus/unminimize.

## Focus and z-order

Focusing a window:
1. makes it active;
2. clears minimized state;
3. increments the desktop z counter;
4. updates focus borders;
5. raises its content View;
6. raises its window chrome;
7. applies current layout;
8. raises taskbar/Start-menu system chrome.

Content Views also receive `translationZ = record.z`.

Desktop chrome and content have separate hosts. Window chrome remains above content, while system taskbar/Start-menu are re-raised above window chrome.

## Window controls

Every window has native:
- Minimize;
- Maximize/Restore;
- Close;
- bottom-right resize handle.

Minimize hides title bar, borders, resize handle and content View. If the active window is minimized, the highest-z non-minimized window becomes active.

Maximize:
- saves restore bounds on first maximize;
- clears minimized state;
- fills `workspaceBounds()`;
- hides the resize handle;
- changes the maximize button to Restore.

Restore:
- unminimizes;
- if maximized, clamps saved restore bounds back into the current workspace;
- returns the button to Maximize.

Close:
1. removes the WindowRecord;
2. removes content/chrome Views;
3. focuses the highest-z remaining visible window if one exists;
4. updates taskbar;
5. synchronously invokes `windowClosedSink(id)` so the owning subsystem can release its own state/resources.

Content owners must therefore treat a successful desktop close as destructive: when `desktop.window.close` returns, desktop ownership of that window/content is gone.

## Show Desktop

Show Desktop is a two-state native behavior.

When visible windows exist:
- all visible windows are marked minimized;
- their ids are remembered in z order;
- the previously active id is remembered;
- launcher becomes visible;
- Start menu is closed.

When no windows are visible and the remembered set still exists:
- remembered windows are restored;
- the previously active window is preferred;
- otherwise the highest-z restored window is focused.

Any independent open/focus/minimize/maximize/restore action clears the remembered Show Desktop restore set.

## Move and resize

Dragging the title bar:
- focuses the window on ACTION_DOWN;
- moves only when not maximized;
- uses raw pointer deltas from the initial frame;
- clamps the result into the workspace.

The resize handle:
- focuses on ACTION_DOWN;
- resizes bottom/right only;
- is disabled visually while maximized;
- clamps minimum and maximum size.

Minimum window target:
- width 300 dp, capped down to available workspace width;
- height 220 dp, capped down to available workspace height.

Drag/resize applies geometry directly while the pointer moves; ACTION_UP/CANCEL records the completed bounds revision. Host relayout increments the desktop revision without constructing an unused pushed-state payload.

There is no edge/snap/tiling system in the current source.

## Workspace geometry

`workspaceBounds()` uses the native desktop host size and excludes only the 48 dp taskbar at the bottom.

There is no permanent top desktop status bar.

Default windows use approximately:
- 68% of available width;
- 72% of available height;
- 24 dp cascaded offsets, cycling every 6 windows.

All non-maximized bounds are clamped to the current workspace.

On host-size/layout change:
- launcher columns/compact profile are recalculated;
- Start-menu width is adjusted;
- maximized windows refill the workspace;
- normal windows are clamped into the new workspace.

## Compact layout

Compact desktop is selected when native desktop width is below 700 dp.

Compact mode changes:
- launcher tile size;
- launcher icon/text sizing;
- taskbar task-label behavior.

Launcher column count is calculated from actual host width.

Start-menu width is capped at 420 dp while retaining at least a bounded small-screen width target.

## Content attachment

`attachContent(id, view)` requires an existing WindowRecord.

It:
- removes any prior content View from `contentHost`;
- removes the new View from any old parent;
- adds it to `contentHost`;
- stores it on the WindowRecord;
- applies window geometry and z-order.

Current content producers include:
- Terminal;
- Tasks;
- Files;
- Editor;
- Dev Lab;
- Workspace Records;
- Settings;
- main RiftBrowser;
- installed RiftBrowser-hosted apps.

`detachContent` is currently used by the installed-app host. Closing a WindowRecord also removes any attached content View.

## Back behavior

Desktop Back is called by MainActivity only after RiftBrowser history has had first refusal.

Desktop Back:
1. closes the Start menu if it is open;
2. otherwise closes the active desktop window;
3. returns false only when there is no menu and no active window.

It does not minimize on Back.

## Window state API

`desktop.window.state` returns:
- `native: true`;
- sequence;
- reason;
- active id;
- desktop-visible flag;
- Android density;
- workspace pixel bounds;
- all windows sorted by z.

Each window includes:
- id;
- title;
- kicker;
- minimized;
- maximized;
- focused;
- z;
- frame pixel bounds;
- content pixel bounds.

The Task Manager consumes this state, including `kicker`.

There is no live desktop state push consumer. The old desktop state callback was removed during this audit because MainActivity supplied only a no-op sink. Current consumers pull state explicitly.

## Persistence

`RiftNativeDesktop` does not use SharedPreferences, saved-instance state or RiftFS persistence.

Window records, geometry, z-order, Show Desktop restore state, current launcher/pin state and wallpaper are in-memory Activity-owned state.

A newly constructed MainActivity/Desktop starts fresh and receives a newly populated launcher. Android process death also destroys this state.

The retired web desktop previously supplied CSS/DPR window geometry persistence. That compatibility import was removed from the live native desktop during this audit.

## Wallpaper

The native desktop initializes a built-in gradient wallpaper.

`desktop.wallpaper.set` can currently parse:
- blank → default gradient;
- base64 image data URI;
- Android color string;
- invalid value → fallback background color.

No current Kotlin caller exposes this capability to the user. It remains implemented-but-unwired.

## Accessibility

Native interactive controls have content descriptions for:
- desktop launcher;
- desktop app collection;
- Start;
- Show Desktop;
- Start menu;
- taskbar items;
- title bars;
- minimize/maximize/restore/close;
- resize handle;
- launcher/start-menu app tiles.

Child icon/text decorations are generally excluded from accessibility so the containing actionable control provides the semantic node.

Full Accessibility behavior remains part of installed-device acceptance; source labels alone are not device proof.

## Source cleanup performed in this audit

The following stale migration residue was removed from live desktop source:
- no-op desktop `stateSink`;
- retained-web-only `boundsCss` / DPR geometry import;
- unattached/dead top `statusBar`;
- unattached/dead `statusTitle` bookkeeping;
- zero-height top-status offset state;
- the old 16 ms pushed-state scheduling path, which had no consumer after the no-op state sink was removed.

No current live caller depended on those paths.

## Non-ownership boundaries

Desktop does not own:
- Activity lifecycle → Android Host;
- native built-in body behavior → Shell UI / Files / Settings / Dev Lab / Workspace Records;
- browser renderer state → RiftBrowser;
- installed-app WebView teardown/capabilities → Apps/RiftBrowser app host;
- process/MCP task authority → MCP/RiftShell;
- persistent desktop preferences → no current live owner.

## Critical invariants

- one WindowRecord per desktop id;
- no retained JavaScript/DOM window authority;
- every content View attaches only to an existing WindowRecord;
- content remains below desktop/system chrome;
- minimized windows hide both content and chrome;
- maximized windows stay inside native workspace bounds;
- task strip remains horizontally scrollable;
- taskbar/clock/Show Desktop remain outside the window workspace;
- closing removes desktop Views before notifying the content owner;
- launcher routing does not decide app implementation;
- Back closes Start menu before active window;
- desktop state is pull-based in current composition;
- no CSS/DPR geometry compatibility import returns;
- no fake persistence claim is made.

## Failure signatures

- two live windows share an id → WindowRecord identity regression;
- minimized window content remains visible → hide/layout regression;
- task buttons overlap clock/off-screen rather than scroll → taskbar regression;
- window can move/resize outside workspace → clamp regression;
- maximize leaves resize handle active/visible → maximize-state regression;
- close leaves content/chrome View attached → lifecycle leak;
- owner resources survive a close unexpectedly → content-owner close callback regression;
- launcher is documented as staying behind open windows → documentation mismatch with current chrome-layer visibility policy;
- wallpaper/pins/layout reset described as current UI features → reachability documentation error;
- old `boundsCss`, no-op state sink or top-status objects return → native migration regression;
- desktop state is documented as persistent across Activity recreation/process death → persistence documentation error.

## Fix map

Window records/state/z-order/geometry/chrome/taskbar/launcher → `RiftNativeDesktop.kt`.

Desktop construction and launcher population → `MainActivity.kt`.

Terminal/Tasks bodies → `RiftNativeSystemApps.kt`.

Files/Editor/Dev Lab/Workspace Records/Settings bodies → `RiftNativeWorkspaceApps.kt`.

Main browser content → `RiftBrowserWindow.kt`.

Installed-app content/teardown → `RiftBrowserAppHost.kt`.

Activity Back/lifecycle → `MainActivity.kt`.

## Validation

Source verification must recheck:
- every `desktop.*` method;
- every caller/reference;
- all native button/touch listeners;
- content attach/detach callers;
- unique id/open behavior;
- z/focus transitions;
- minimize/maximize/restore/close;
- Show Desktop restore-set behavior;
- taskbar overflow/scroll construction;
- launcher visibility/layering policy;
- clamp/default/workspace geometry;
- host-layout response;
- state schema and actual consumers;
- absence of persistence APIs;
- absence of retained JS/CSS geometry authority;
- accessibility labels;
- dead/stale source residues.

Installed-device promotion still requires:
- multiple simultaneous windows;
- repeated focus/z-order swaps;
- minimize/taskbar restore;
- maximize/restore through host resize/orientation;
- drag/resize to every boundary;
- taskbar overflow with many windows;
- Start menu and launcher;
- Show Desktop twice plus interrupted Show Desktop restore;
- Back behavior;
- close/resource cleanup for every content owner;
- compact and wide/DeX-like layouts;
- Activity recreation and process relaunch;
- Accessibility-driven controls.

Source verification is not a substitute for Android Builder or device abuse.
