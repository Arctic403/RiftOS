# Boot and Module Loading

## Purpose

The boot layer turns the Android-hosted static web bundle into a running RiftOS shell in a deterministic order. Its most important job is dependency ordering: modules that create shared globals must finish before modules that consume them.

## Why this boundary exists

RiftOS is packaged as web assets but depends on native Android services. A browser-only bootstrap would allow the shell to begin before the exact-origin `RiftAndroid` bridge exists. The Android boot path therefore fails closed if the native host is missing and imports modules sequentially.

## Source ownership

- `index.html` — initial shell DOM, boot surface and module entry.
- `src/riftandroid-entry.js` — ordered Android module imports.
- `src/riftandroid-preload.js` — requires the `RiftAndroid` WebMessage host and creates the native transport expected by core code.
- `src/riftandroid-platform.js` — Android-specific platform integration after core initialization.
- `src/riftos.js` — final shell/application startup and boot-screen dismissal.
- `MainActivity.kt` — loads `https://appassets.androidplatform.net/assets/www/index.html` and installs the native message listener before page startup.

## Runtime flow

```text
MainActivity
  -> install RiftAndroid WebMessage listener
  -> load appassets index.html
  -> riftandroid-entry.js
      -> riftandroid-preload.js
      -> riftcore.js
      -> Android/workspace/runtime/app/git/shell/desktop modules
      -> riftos.js
      -> late desktop/MCP/runtime compatibility modules
```

`riftandroid-entry.js` uses sequential `await import(...)` calls. Do not convert this to uncontrolled parallel imports unless dependencies are explicitly removed.

## Critical invariants

- `RiftAndroid` must exist before `riftandroid-preload.js` completes.
- `riftcore.js` must evaluate before any consumer reads `globalThis.RiftOSCore`.
- A top-level exception in any early imported module prevents later imports, including `riftos.js`; the visible symptom is often an endless animated boot splash.
- Android packaged assets come from Gradle's generated `www` asset tree; source changes require a new APK build to reach the installed app.

## Failure signatures

**Boot animation never ends:** first suspect a top-level exception in an early module. Inspect `riftandroid-entry.js` order, then `riftandroid-preload.js` and `riftcore.js` for missing globals/classes or syntax errors. A previous example was a constructed `RiftTransferQueue` whose class definition was missing, which stopped `riftcore.js` during evaluation.

**Works in a normal browser but not APK:** inspect `riftandroid-preload.js`, the exact-origin listener installation in `MainActivity`, generated assets, and Android WebView console errors.

**Old code after rebuild:** inspect `syncRiftOsWebAssets` in `android/app/build.gradle.kts` and confirm the exact source commit the external builder consumed.

## Fix map

- Native bridge missing or wrong origin -> `MainActivity.kt` / `riftandroid-preload.js`.
- Wrong module dependency/order -> `riftandroid-entry.js`.
- Core global missing -> `riftcore.js`.
- Boot UI never dismissed after all modules load -> startup tail in `riftos.js`.
- APK contains stale web files -> Gradle asset sync/build pipeline.

## Validation

`npm run check` syntax-checks critical JS assets and runs repository validators. Android build verification also checks required source presence and packages generated web assets. For boot changes, additionally verify a clean launch rather than only warm WebView state restoration.

## Safe extension points

New modules should be imported at the earliest point where all dependencies already exist and before their first consumer. Keep imports explicit and ordered. A module that is optional should catch its own optional failure rather than making core boot depend on it.
