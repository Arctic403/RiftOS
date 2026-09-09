# RiftWorkspace Architecture

> Historical filename retained for existing links. The active runtime described here is Android, not the previous Web/OPFS deployment.

RiftWorkspace is the controlled project/workspace boundary between RiftOS apps and RiftFS.

## Android runtime path

```text
RiftOS app / RiftDev
      |
RiftWorkspace API / JSON bridge
      |
riftworkspace-android-adapter.js
      |
RiftAndroid fs.* native calls
      |
filesDir/riftfs/workspace
```

`src/riftworkspace-web.js` remains the common high-level workspace contract; `src/riftworkspace-android-adapter.js` redirects storage to native RiftFS on Android.

## Public operations

The workspace supports controlled list/stat/read/write/mkdir/remove/move operations plus snapshot, patch preview/apply, history and rollback surfaces used by RiftDev and project tooling.

Path normalization prevents escaping the workspace/RiftFS boundary.

## RiftDev compatibility

The Android APK packages `riftdev-android.js`. During asset generation its legacy IndexedDB calls are redirected to `RiftDevAndroidDB`, a compatibility facade backed by RiftWorkspace. This lets older editor code keep transaction-style calls without making IndexedDB the Android source of truth.

## Browser separation

RiftWorkspace and RiftBrowser are separate capabilities:

```text
RiftKernel
  |-- RiftWorkspace -> RiftFS/native storage
  |
  `-- RiftBrowser -> Android System WebView -> guest web content
```

Normal guest webpages never receive `RiftWorkspace` or unrestricted RiftFS authority.

ChatGPT receives only the separate `browser-sandbox` filesystem, not the main RiftWorkspace tree.

## Historical web mode

Earlier RiftOS web/iOS experiments backed RiftWorkspace with OPFS/IndexedDB. That design explains the common API naming but is not the current Android persistence path.
