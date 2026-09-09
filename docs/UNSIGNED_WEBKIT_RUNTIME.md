# Archived: Unsigned WebKit Runtime Research

This document is retained as historical context for the earlier iPhone/iPad RiftOS research line.

The old design explored RiftKernel as web content hosted by Apple WebKit, OPFS-backed RiftFS and a nested WebKit-WASM/Wisp RiftBrowser engine. It demonstrated useful architectural ideas, but it is **not the active RiftOS runtime described by `main`**.

## Current status

The active RiftOS target is the native Android APK:

- Android `MainActivity` hosts the RiftOS shell,
- RiftFS uses Android app-private storage and SAF mounts,
- RiftBrowser uses Android System WebView inside a RiftDesktop window,
- the WebKit-WASM/Wisp browser pipeline is not packaged,
- PWA/service-worker/iOS-specific assets are excluded from the APK.

## Historical security principle retained

The earlier research correctly separated a user-space RiftKernel from the real host OS kernel. That principle still applies on Android: RiftKernel does not bypass Android/Linux privileges or code-signing/application-sandbox rules.

For current architecture, see `TRUE_OS_ARCHITECTURE.md` and `ANDROID_NATIVE_ARCHITECTURE.md`.
