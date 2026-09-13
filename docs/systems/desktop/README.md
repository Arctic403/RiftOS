# RiftDesktop Window Manager

## Purpose

RiftDesktop is the permanent Android RiftOS shell. It owns windows, focus/z-order, move/resize, minimize/maximize/restore, taskbar state, show-desktop behavior, desktop icons, wallpaper, persisted geometry and the optional virtual mouse/trackpad layer. Narrow screens change responsive layout; they do not switch RiftOS into a separate mobile/app-takeover mode.

## Source ownership

- `src/riftdesktop-android.js` — main Android desktop behavior.
- `src/riftdesktop-android.css` — desktop/window/taskbar/Files/Settings/browser presentation.
- `src/riftdesktop-window-host.js` — keeps the desktop host/window state synchronized.
- `src/riftdesktop-android-compat.js` — small Android compatibility synchronization.
- base window creation/focus/close records in `src/riftos.js`.

## Runtime model

The shell creates `.window` elements. RiftDesktop upgrades them to `.rift-desktop-window`, adds desktop controls/resize affordances, restores persisted geometry, and tracks active window plus z-index. `RiftOSWindowManager` is the cross-system surface used by RiftRT and shell windows.

Native RiftBrowser content is special: the HTML window owns chrome/geometry while `RiftBrowserWindow` owns the native renderer. Desktop emits `riftos:window-visibility` immediately on minimize/restore so Android can hide/show the native surface without waiting for a later geometry pass.

## Critical invariants

- Window geometry is clamped to the actual stage/visual viewport; windows must not drift off the right/bottom edge.
- Minimized windows are not considered visible/focused.
- Focus updates z-order, visual focused state and taskbar state together.
- The taskbar is demand-driven: Start/tray stay present, pinned apps persist, and unpinned apps appear only while their window is open (including minimized windows) and disappear after close.
- Taskbar pins persist in `/system/settings/desktop.json` as `taskbarPins`; `RiftDesktop.pinTaskbar(id, pinned)` is the programmatic pin/unpin surface.
- Browser minimize/show-desktop must announce visibility before leaving a native renderer onscreen.
- Geometry persistence is keyed by app/window identity and must tolerate smaller future viewports.
- Desktop mode remains usable on narrow Android screens; minimum width/height must never exceed available bounds.

## Failure signatures

- Whole desktop extends past screen -> `desktopBounds`, `applyGeometry`, CSS min-width/max-width or Android insets.
- Window restores offscreen -> stored geometry clamp/restore.
- Clicking one window highlights another -> focus/taskbar record divergence.
- Browser renderer covers another window -> visibility/bounds event path to native browser host.
- Virtual mouse clicks wrong target -> cursor coordinates, overlay pointer-events or `elementFromPoint` target resolution.

## Fix map

Geometry/focus/taskbar/desktop gestures -> `riftdesktop-android.js`.
Visual layout -> `riftdesktop-android.css`.
Base shell window record creation -> `riftos.js`.
Native browser surface mismatch -> browser window integration, not z-index hacks.

## Validation

Test phone portrait, landscape, narrow split-screen and DeX-sized windows. With no pins and no open windows, verify only Start/tray remain. Open unpinned apps and verify they appear while open/minimized and disappear after close. Pin/unpin an app and verify the choice survives desktop reload. Open multiple windows; move/resize/maximize/minimize/restore; show desktop; reopen after viewport shrink; verify browser native surface tracks the HTML content rectangle.

## Safe extension points

New window policies should go through `RiftOSWindowManager` and visibility events. New desktop persistence belongs in `/system/settings/desktop.json`. Keep app-specific behavior out of the generic manager unless it is required for every window.
