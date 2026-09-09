# RiftOS Android Native Architecture

The active RiftOS distribution is a native Android APK targeting Android 8.0 / API 26+.

## Runtime stack

```text
RiftOS HTML/JS shell
    -> RiftKernel / RiftDesktop
    -> RiftNativeTransport
    -> exact-origin RiftAndroid WebMessage
    -> MainActivity / RiftNativeDispatcher
    -> Android APIs
```

`MainActivity` hosts the RiftOS shell WebView through `WebViewAssetLoader` and owns the native browser content surface and system-document pickers.

## Storage

RiftFS lives in `filesDir/riftfs`. Android initializes `home`, `apps`, `system`, `workspace`, `downloads` and `documents`.

External user folders mount through Storage Access Framework with persisted URI permissions. RiftWorkspace maps its common JSON API onto the native `/workspace` tree.

## Native services

- RiftFS and SAF mounts,
- Android Keystore encrypted secrets,
- clipboard/share/vibration/notifications,
- notification permission request,
- Android file chooser,
- DownloadManager,
- preview Activity for RiftDev workspace files,
- privacy-limited System Dump + Save As picker,
- `RiftBrowserWindow` native WebView content plane.

## Desktop browser host

`RiftBrowserWindow` is created inside `MainActivity`. RiftDesktop sends window bounds/visibility/navigation commands through the native bridge. Android positions the WebView over the browser content rectangle while RiftOS retains title bar, address bar, move/resize, minimize/maximize, focus and taskbar behavior.

The obsolete standalone `RiftBrowserActivity` is not part of the current source/build.

## ChatGPT sandbox

The browser installs a `RiftSandbox` WebMessage listener only for exact `https://chatgpt.com` main-frame messages. `RiftSandboxFS` is rooted at `filesDir/riftfs/browser-sandbox`; it cannot access secrets, SAF mounts, arbitrary Android storage or other apps.

Rift Agent is an injected browser adapter layered on that sandbox. It does not widen native filesystem authority.

## System Dump

Settings invokes `system.dump.save`. Android generates a JSON diagnostic snapshot and opens `ACTION_CREATE_DOCUMENT`, allowing the user to choose the provider, folder and filename.

The dump includes app/build, Android/WebView, memory/heap/storage and aggregate RiftFS/sandbox metrics. It excludes file names/content, secrets, account data, Android IDs and installed-app lists.

## RiftDev

The active Android editor is `apps/riftdev/riftdev-android.js`. During APK asset generation, legacy IndexedDB calls are rewritten to `RiftDevAndroidDB`, which stores through RiftWorkspace.

Local Test uses the native preview path rather than a service worker.

## Build/signing

`.github/workflows/riftos-android-apk.yml` builds on `android-apk` and `main`, signs/verifies the APK and publishes `android-latest`.

Production signing may use repository secrets; otherwise the stable alpha key is used for updateable development installs. Emulator CI has been removed.
