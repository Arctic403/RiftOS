# RiftBrowser Window Coordinator

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

`RiftBrowserWindow` is the native coordinator for the single RiftBrowser desktop window and its bounded internal renderer-tab set.

It owns:
- browser surface container;
- active-tab selection;
- maximum tab count;
- browser navigation delegation;
- file-chooser request/result lifecycle;
- renderer Activity pause/resume coordination;
- current browser state aggregation.

It does not own Desktop window geometry/minimize/maximize/z-order and it does not own WebView policy.

## Source ownership

Primary:
- `RiftBrowserWindow.kt`

Direct composition:
- `MainActivity.kt` opens/attaches/closes the browser, routes Back, Activity results and inspector calls.
- `RiftNativeDesktop.kt` owns the browser WindowRecord and attached content View visibility/geometry.
- `RiftBrowserEngine.kt` defines per-tab renderer operations.
- `RiftBrowserAndroidWebViewEngine.kt` is the current per-tab backend.

Retained `src/riftos.js` browser chrome is not the current packaged browser UI.

## Live open path

`MainActivity.openBrowserWindow(url)`:
1. opens/focuses Desktop window id `browser`;
2. attaches `browserWindow.nativeWindowView()` through Desktop;
3. calls `browserWindow.open(url)`.

Desktop therefore owns the outer content View's bounds and visibility.

BrowserWindow owns only renderer children inside that attached View.

## Corrected visibility ownership

The old BrowserWindow kept private `requestedVisible`/bounds state and could re-show its surface during Activity resume even after Desktop minimized it.

That parallel visibility/geometry path was removed during this audit.

Current behavior:
- Desktop sets the outer browser content View VISIBLE/GONE and its bounds.
- BrowserWindow observes actual attach/detach/visibility.
- hidden or detached surface -> all renderer engines receive `onPause()`;
- visible + attached + Activity resumed -> only the active engine receives `onResume()`;
- inactive engines remain paused;
- Activity resume no longer makes a minimized Desktop window visible.

The old `setVisible()`, `setBounds()` and state-push sink were removed.

## Tabs

BrowserWindow creates one blank initial tab.

Each tab owns one `RiftBrowserEngine`.

Maximum tabs: 8.

Internal tab functions implement:
- new tab;
- select tab;
- close tab.

Closing the final tab immediately creates a fresh blank tab so the coordinator always has one active tab while alive.

When the active tab closes, selection falls back to a neighboring remaining tab.

Only the active engine View is VISIBLE inside the browser surface; inactive tab Views are GONE and paused.

## Implemented versus wired

Current external Kotlin call sites:
- `nativeWindowView()` -> MainActivity attachment;
- `open()` -> MainActivity open path;
- `close()` -> Desktop-close owner callback;
- `inspect()` -> fixed local-agent inspector;
- `onActivityResult()` -> MainActivity;
- `state()` -> Android Back routing;
- `back()` -> Android Back routing;
- `onResume()/onPause()/destroy()` -> MainActivity lifecycle.

Implemented but with no current external Kotlin UI caller:
- `navigate()`;
- `forward()`;
- `reload()`;
- `setDesktopMode()`;
- `newTab()`;
- `selectTab()`;
- `closeTab()`.

Those capabilities are not proof that native address/tab/desktop-mode controls currently exist.

## URL normalization

Start/navigation input:
- blank -> caller-specific default;
- `https://...` -> retained;
- `http://...` -> upgraded to `https://...`;
- dotted token without spaces -> prefixed with `https://`;
- otherwise -> Google HTTPS search query.

Default first browser URL: `https://chatgpt.com`.

Default explicit new-tab URL: `https://www.google.com`.

## File chooser

BrowserWindow owns request code 7002.

Only one chooser callback is retained at a time; starting a new chooser cancels the previous callback with null.

For `*/*` or `.rift` accepts it uses a generic `ACTION_OPEN_DOCUMENT` / OPENABLE picker with read grant.

Otherwise it uses WebView's supplied chooser intent where available.

Multiple selection is enabled when requested by FileChooserParams.

MainActivity routes Activity results to BrowserWindow before Files' SAF picker.

Destroy cancels any outstanding chooser callback.

## Back behavior

MainActivity first obtains browser state.

If RiftBrowser is actually visible and active engine can go back, BrowserWindow `back()` consumes Android Back.

If the browser is minimized/hidden, browser history does not consume Back.

Desktop then receives Back for window/menu behavior.

## Close versus destroy

`close()` hides/pauses the surface but retains tab engines/history for a later reopen during the same Activity instance.

`destroy()`:
- marks the coordinator destroyed;
- pauses renderers;
- destroys every engine;
- cancels file chooser callback;
- removes the surface from any parent;
- removes child Views.

Activity destruction calls `destroy()`.

## State

`state()` merges active engine state with:
- `open`;
- actual `visible` derived from parent attachment + `isShown`;
- surface id;
- active tab id;
- tab count;
- max tabs;
- per-tab title/url/progress/crash/history/desktop-mode/active flags.

State is pull-based. The old no-op state sink was removed.

## Non-ownership boundaries

BrowserWindow does not own:
- outer window geometry/minimize/maximize/z-order -> Desktop;
- WebView settings/network/auth/download/security -> browser backend;
- exact-origin MCP injection -> browser MCP compatibility;
- renderer crash logging and concrete failure detection -> crash guard/backend;
- one bounded same-tab engine replacement after main-renderer loss -> BrowserWindow;
- native browser chrome controls -> none currently wired.

## Critical invariants

- Desktop is sole outer browser View geometry/visibility authority;
- Activity resume cannot override Desktop minimize state;
- hidden/detached browser pauses every renderer;
- only active visible tab is resumed;
- max 8 tabs;
- at least one tab exists while alive;
- file chooser callback is single-owner and cleared on result/destroy;
- Back uses actual visible state;
- retained web browser chrome is not treated as live;
- implemented-but-unwired controls remain documented as unwired.

## Failure signatures

- minimized browser becomes visible after Activity resume -> visibility-ownership regression;
- hidden/minimized browser engine remains resumed -> lifecycle regression;
- BrowserWindow starts setting Desktop pixel bounds -> geometry ownership regression;
- more than 8 tabs created -> bound regression;
- inactive tab stays interactive/resumed -> tab isolation regression;
- chooser callback leaks across destroy -> Activity-result lifecycle regression;
- docs claim native address/tab controls exist without callers -> reachability documentation error.

## Fix map

Window/tab/lifecycle/file chooser -> `RiftBrowserWindow.kt`.

Outer geometry/visibility -> `RiftNativeDesktop.kt`.

Composition/Back/result routing -> `MainActivity.kt`.

Renderer policy -> browser engine/backend subsystem.

MCP page compatibility -> browser MCP compatibility subsystem.

## Validation

Source verification must recheck:
- all public methods and callers;
- actual Desktop attach/visibility ownership;
- removed `setVisible/setBounds/stateSink`;
- tab limit/fallback behavior;
- active/inactive renderer visibility/lifecycle;
- Activity pause/resume;
- close versus destroy;
- file chooser request/result/cancellation;
- Back visibility predicate;
- URL normalization;
- retained versus packaged browser UI.

Installed-device validation must abuse minimize/restore/background/resume, tab operations once wired, file chooser, renderer crash, auth, and Back.
