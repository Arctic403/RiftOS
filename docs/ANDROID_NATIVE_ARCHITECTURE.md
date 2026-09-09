# RiftOS Android Native Architecture

`android-apk` is an Android-only RiftOS distribution. It does not emulate an Apple host and it does not use OPFS, IndexedDB, PWA manifests, service workers, or Safari-specific runtime shims for RiftOS storage.

## Runtime stack

RiftOS HTML/JS UI → RiftKernel Android API → `RiftNativeTransport` → `RiftAndroid` WebMessage → Kotlin → Android APIs.

RiftFS lives in `filesDir/riftfs`. External user folders mount through Android Storage Access Framework and persisted URI permissions. RiftWorkspace is implemented on top of the native RiftFS `/workspace` directory, so JSON patches, history, rollback, RiftDev, and GitHub workspace operations all share one Android-backed filesystem.

## Native services

- Android internal RiftFS
- Storage Access Framework mounts
- Android Keystore encrypted secrets
- Clipboard, share sheet, vibration and notifications
- Android notification permission request
- Native `RiftBrowserWindow` WebView surface hosted inside the RiftOS desktop window manager
- Native `RiftPreviewActivity` that serves RiftDev workspace files directly without a service worker
- Android system file chooser for `<input type=file>`
- Android DownloadManager for normal HTTP(S) downloads
- Android Back integration, lifecycle pause/resume and Samsung/DeX-resizable activities

## RiftDev

The active Android editor is `apps/riftdev/riftdev-android.js`. Its legacy IndexedDB-shaped storage calls are redirected at build time to `RiftDevAndroidDB`, a request/transaction compatibility facade backed by `RiftWorkspace`, not browser storage. GitHub PAT persistence is mirrored to the Android Keystore and removed from RiftDev localStorage when its page is left.

Local Test opens `RiftPreviewActivity` directly against the native workspace. No Cache API or service worker is required.

## Browser

The Android branch uses Android System WebView as its native RiftBrowser backend. The WebView is hosted inside `MainActivity` and positioned over the RiftOS browser window content area, so RiftOS owns the title bar, taskbar, move/resize/minimize/maximize behavior while Android owns page rendering, ChatGPT login, downloads, and the sandbox bridge. The old standalone full-screen browser Activity is not part of this build. The old WebKit-WASM/Wisp browser pipeline belongs to the web/iPhone line and is intentionally not packaged by the Android APK.

## Signing

GitHub Actions supports production signing through `RIFTOS_KEYSTORE_B64`, `RIFTOS_KEYSTORE_PASSWORD`, `RIFTOS_KEY_ALIAS`, and `RIFTOS_KEY_PASSWORD` secrets. Until those secrets are configured, CI falls back to the stable alpha key so test installs can update in place.
