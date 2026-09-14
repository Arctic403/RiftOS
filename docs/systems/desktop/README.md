# RiftDesktop Window Manager

## Purpose

RiftDesktop is the permanent Android-native RiftOS shell. Android owns the visible desktop, launcher, Start menu, taskbar, native window frames, focus/z-order, move/resize, minimize/maximize/restore/close, show-desktop behavior, system insets and Android Accessibility semantics.

The trusted RiftOS WebView still exists during the migration, but it is **not the desktop/window manager**. It is a compatibility content canvas used to keep existing Files, Editor, Settings, RiftShell, Workspace Records and RiftRT app bodies working while those surfaces are migrated selectively. Native Android publishes each window's content rectangle and state; JavaScript positions only the app body inside that rectangle. Window chrome and window authority never come from DOM elements in native mode.

## Source ownership

- `android/app/src/main/java/com/riftos/app/RiftNativeDesktop.kt` — Android-native desktop, launcher, Start menu, taskbar, window records/chrome, bounds, focus/z-order and accessibility controls.
- `android/app/src/main/java/com/riftos/app/MainActivity.kt` — creates the native desktop host and routes the bounded `desktop.*` compatibility requests.
- `src/riftos.js` — compatibility window/process records, native state mirroring, dynamic launcher registry, RiftFS desktop-settings persistence and public `RiftOSWindowManager` / `RiftDesktop` surfaces.
- `src/riftdesktop-native-compat.js` — transparent compatibility-content layout used only when Android native desktop authority is active.
- `src/riftandroid-entry.js` — selects native mode and prevents the legacy DOM desktop modules from loading in that mode.
- `src/riftdesktop-android.js`, `src/riftdesktop-window-host.js`, `src/riftdesktop-android-compat.js` and `src/riftdesktop-android.css` — retained legacy/fallback WebView desktop implementation; they must not own the shell when `RiftNativeDesktop` is available.

## Runtime model

```text
Android MainActivity
  -> RiftNativeDesktop
       -> native wallpaper / launcher / Start / status / taskbar
       -> native window frames + drag/resize + z/focus + controls
       -> contentHost
            -> trusted compatibility WebView
                 -> existing RiftOS app bodies only
            -> RiftBrowser native renderer surface when focused
  -> native chrome layer always above content surfaces
```

At boot `src/riftos.js` calls `desktop.window.bootstrap`. If Android returns native authority, the runtime loads `riftdesktop-native-compat.js`; it does **not** load the old DOM window-manager modules. The base JS `openWindow()` still returns a body synchronously so existing apps do not need an immediate rewrite, but it requests the actual frame from Android. Android publishes authoritative window state (`contentPx`, focus, minimized/maximized state, z-order); JS mirrors that state onto the content body only.

Dynamic launcher entries remain compatible with existing app registration. The hidden `#appGrid` is a registry mirror, not a visible launcher. A MutationObserver sends built-ins, RiftRT/Rift Apps, installed packages and system apps to `desktop.launcher.update`; Android renders the real launcher and Start controls. Because the compatibility WebView is a full native content plane, the native launcher is the desktop/home surface: it is visible when no windows are restored and hides while any window is visible, returning on Show Desktop or after the last visible window minimizes/closes. Show Desktop is a true toggle: the first activation remembers only the windows that were visible and minimizes them, while the next activation restores only that remembered set and refocuses the previously active surviving window. Windows that were already minimized before Show Desktop stay minimized. Any explicit open/focus/minimize/maximize/restore/reset action cancels the remembered toggle set so stale state cannot resurrect windows later. This prevents launcher controls from floating over app content while keeping native touch ownership deterministic.

RiftRT uses the same base window manager in native mode. It may own runtime/session cleanup, but it must not manufacture a second DOM window authority.

## Visual parity contract

Native rendering changes ownership, not the RiftOS visual language. The legacy RiftDesktop stylesheet remains the reference for proportions and app-interior styling: no permanent top status strip, a 48dp taskbar, 38dp title bars, subtle one-pixel window borders, compact 74x80dp desktop icons on narrow screens (84x90dp on wider layouts), three-column Start tiles, icon-first taskbar entries with running/active underlines, and the dark teal RiftOS desktop/window palette. The native launcher is vertically scrollable so installed apps cannot disappear under the taskbar. The visually quiet Show Desktop edge control keeps a 24dp transparent hit target; shrinking the actual clickable control to an 8dp sliver made assistive/injected and human edge taps unreliable on narrow devices.

`riftdesktop-native-compat.js` deliberately adds `rift-desktop-mode` alongside `rift-native-host`. This reuses the existing desktop interior rules for Files, Settings, Browser, Editor, RiftShell and other compatibility bodies while its later native-compat rules still hide the DOM launcher/taskbar/title bars and leave Android as the only window authority.

## Persistence

Wallpaper, taskbar pins and per-window normal geometry continue to use `/system/settings/desktop.json`. Native geometry is converted between Android physical pixels and WebView CSS coordinates at the compatibility boundary. Maximized frames do not overwrite the saved restore geometry. Reset Layout clears persisted native window geometry while keeping the settings file contract stable.

Pinned apps and running windows share the native taskbar. Installed RiftRT apps use their launcher app id for the pinned entry while their live window may use a `riftrt:<id>` runtime id; the native taskbar coalesces those into one entry.

## Critical invariants

- Native Android owns desktop/window chrome and geometry whenever `RiftNativeDesktop` is available.
- `MainActivity` must not add the shell WebView as the top-level desktop surface; the WebView belongs inside `RiftNativeDesktop.contentHost`.
- Legacy `riftdesktop-android.js` / window-host modules are fallback-only and must not execute in native mode.
- The compatibility WebView may render app bodies, but no DOM title bar, taskbar, launcher, drag/resize implementation or z-order policy may become authoritative in native mode.
- Compatibility CSS must preserve the Android-published inline `left`/`top` content coordinates. Never reset the native content window with an `inset:* !important` shorthand; only non-authoritative `right`/`bottom` edges may be forced to `auto`.
- Window/process close remains idempotent across native close controls, Task Manager, RiftShell `kill`, app self-close and Android Back. Native frame close sends an explicit trusted `windowClosedSink` callback into `RiftDesktop.closeWindow(...,{fromNative:true})` so JS process/body cleanup does not depend on waiting for a later native state snapshot; the ordinary state mirror remains a reconciliation fallback. Task Manager subscribes to ProcessTable changes while open so external termination is reflected immediately, and its listener is released with the Task Manager process. Every non-protected row exposes a unique Accessibility kill label in the form `End task <process name> PID <pid>` so local-agent acceptance never has to guess between identical buttons.
- Native state sequence numbers prevent duplicate request-response/event delivery from replaying older geometry.
- Browser renderer visibility follows the native focused window's compatibility content rectangle and stays under native chrome.
- Taskbar pins, wallpaper and normal geometry continue to persist through RiftFS settings rather than creating an unrelated second settings store.
- Native launcher controls use fixed app ids and only call back into the trusted RiftOS runtime; they do not expose arbitrary Android package launching.
- Native visual proportions stay aligned with the RiftDesktop reference: 48dp taskbar, 38dp title bar, no top status strip, one-pixel frame border and responsive 74/84dp launcher tiles.
- The desktop launcher remains vertically scrollable and must stop above the taskbar even with many installed apps.
- Native compatibility mode reuses `.rift-desktop-mode` for app-interior styling only; native-compat overrides must continue hiding DOM shell chrome.

## Failure signatures

- Only a full-screen WebView appears in Android Accessibility -> native desktop bootstrap/host wiring failed or legacy mode loaded unexpectedly.
- Native frame appears but app body is elsewhere -> first inspect `riftdesktop-native-compat.js` for CSS overriding the inline native `left`/`top`; then inspect physical-pixel/CSS-coordinate conversion or stale native state sequence.
- Window close removes frame but leaves process/body -> native state callback and JS process lifecycle diverged.
- Shell `kill <pid>` removes the process but leaves native frame -> `openWindow()` onTerminate/native close bridge regression.
- RiftRT app gets HTML title bars or its own taskbar entry manager -> `nativeHosted` path failed and RiftRT created legacy windows.
- Browser renderer covers taskbar/title bar -> `RiftBrowserWindow` is mounted above native chrome or visibility/focus state is wrong.
- Pin disappears after restart -> `desktop.json.taskbarPins` mirror or native launcher update omitted pins.
- Geometry resets every launch -> native state persistence/open saved-bounds contract failed.
- Desktop suddenly looks like generic Android widgets or app interiors lose their RiftOS styling -> `RiftNativeDesktop` visual constants/helpers or the `rift-desktop-mode` native-compat class regressed.
- Installed apps disappear below the taskbar -> native launcher `ScrollView`/responsive grid regression.

## Fix map

Native frame/taskbar/launcher/focus/geometry/accessibility -> `RiftNativeDesktop.kt`.
Android root layering/insets/desktop request routing -> `MainActivity.kt`.
JS process/content compatibility and persisted settings -> `riftos.js`.
Compatibility content CSS only -> `riftdesktop-native-compat.js`.
Native-vs-legacy boot selection -> `riftandroid-entry.js`.
RiftRT native-window participation -> `riftrt.js`.
Native browser renderer mismatch -> `RiftBrowserWindow` / browser integration; do not solve it with DOM z-index hacks.
Legacy fallback only -> `riftdesktop-android.js` and related legacy desktop files.

## Validation

Run repository source checks, then an Android/Gradle build. Validation must prove `RiftNativeDesktop.kt` is in the Android source snapshot, MainActivity hosts the compatibility WebView through `RiftNativeDesktop`, native mode conditionally excludes legacy desktop imports, RiftRT delegates windows to the base manager, the MCP tool family does not grow, and the native visual contract retains the RiftOS 48dp taskbar / 38dp title bar / scrollable responsive launcher / desktop-interior compatibility styling.

On device, use the fixed-scope RiftOS self-agent. The Accessibility tree should expose native launcher/taskbar/window controls as Android nodes. Launcher tiles keep the plain app name, Start-menu entries use `Start <app>`, taskbar entries use `Taskbar <app>`, and decorative child icon/label views stay hidden from Accessibility so one visible control does not produce duplicate semantic targets. Self-agent gestures must validate against full real-display bounds, not app-content-only resource metrics, because Accessibility screen coordinates include the system-bar regions around the native desktop. Test multiple windows, overlapping/focus changes, drag, resize, maximize/restore, minimize/taskbar restore, Show Desktop minimize + second-tap restore (including preservation of windows that were already minimized and the 24dp edge hit target), Android Back, close, Task Manager termination and RiftShell `kill`. Task Manager kill buttons must be uniquely targetable as `End task <process name> PID <pid>`; a generic `End task` target should remain ambiguous when multiple rows exist. Test persisted geometry, wallpaper and pins across Activity/app restart. Verify Files/Editor/etc. still render and receive input inside native content rectangles, then progressively migrate individual built-ins only where useful.

## Safe extension points

Add generic window policy to `RiftNativeDesktop` and expose only bounded compatibility requests through the existing trusted native bridge. Keep app-specific behavior inside the app/runtime. A built-in may later become fully native without changing the window-manager contract; Web/Rift apps may continue using managed WebView/runtime surfaces inside native windows where appropriate.