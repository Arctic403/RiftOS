# RiftOS Android Host

This directory is the native Android host for RiftOS.

## v1 boundary

- Kotlin/Android application shell
- WebView-hosted RiftOS surface
- `RiftNative` JavaScript bridge
- native device CPU/RAM/storage information
- private RiftFS rooted at `filesDir/riftfs`
- boot directories: `/system`, `/home`, `/tmp`, `/apps`
- file read/write/list/mkdir/remove operations
- native process execution restricted to app-private `filesDir/riftbin`

The existing web runtime remains untouched while the Android host is brought up. The next migration step is to package the RiftKernel web assets into the APK and have RiftKernel select native RiftHost storage/process capabilities when `window.RiftNative` is available.

## Build

From this directory with JDK 17 and Android SDK 35 available:

```bash
gradle :app:assembleDebug
```

The debug APK will be written under `app/build/outputs/apk/debug/`.
