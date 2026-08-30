# RiftOS True OS Architecture

## Goal

RiftOS has one userspace runtime above the host browser/native APIs. It does not attempt to replace the iOS kernel or ship its own WebKit fork.

```text
RiftOS Desktop / RiftDev / RiftApps
                |
            RiftKernel
       _________|___________
      |         |           |
   RiftFS   Processes   Capabilities
      |                     |
 OPFS/IDB                RiftNative
                            |
                       Swift / iOS
                            |
              RiftBrowser + Apple WebKit
```

## 1. One kernel

`src/riftcore.js` owns process lifecycle, app registration, permissions, mounts, system information, native bridge state and RiftFS.

No subsystem should create a second kernel, process table, filesystem authority or capability broker.

## 2. One filesystem

RiftFS uses OPFS where available and keeps IndexedDB as a compatibility mirror for older RiftOS data.

```text
/
├── home/
│   └── repos/<owner>/<repo>
├── apps/
│   ├── packages/
│   └── data/
├── system/
└── mounts/
```

Native user-approved Files directories and `RiftWorkspace` appear below `/mounts`.

## 3. Process model

Visible built-ins, RiftDev sessions and installed RiftApps receive RiftKernel process records. Protected system processes cannot be terminated by normal app controls.

## 4. Capability model

Trusted built-ins declare capabilities such as filesystem, network, clipboard, sharing, notifications, process management and native access. Installed RiftApps receive a narrower brokered permission surface.

## 5. Native bridge

Only the trusted top-level RiftOS shell WKWebView receives the `riftNative` script-message handler.

The bridge covers:

- device information
- Files directory/document picking
- persistent external mounts
- native filesystem operations
- clipboard/share/notifications
- RiftBrowser open/close
- RiftWorkspace operations
- JSON patch preview/apply/history/rollback

Browser tabs do not receive this bridge.

## 6. Browser service

`src/riftbrowser-kernel.js` installs `RiftKernel.browser`.

It owns logical browser state:

- tabs
- active tab
- URL normalization
- lightweight history
- bookmarks
- backend selection

The active browser architecture has only two backends:

```text
RiftKernel.browser
      |
      +-- native-webkit   -> RiftNative -> RiftBrowser.swift -> WKWebView
      |
      `-- web-transport   -> PWA-only constrained fallback
```

There is no RiftEngine/WebCore/JSC/WASM production backend.

## 7. Native RiftBrowser

`native/ios/RiftOSNative/RiftBrowser.swift` is the production browser.

RiftOS owns the chrome and tab model while Apple WebKit performs web-platform work. Each tab uses a normal `WKWebView` with persistent website data.

Desktop website mode is enabled by default through `WKWebpagePreferences.preferredContentMode = .desktop`. Each tab can switch between Desktop and Mobile mode and reload with the selected preference.

This gives RiftOS a custom browser without maintaining a browser-engine fork.

## 8. RiftWorkspace

The native host creates:

```text
RiftWorkspace/
├── projects/
├── downloads/
├── documents/
├── patches/
└── .rift/
    ├── workspace.json
    └── history/
```

Trusted RiftOS code can list/stat/read/write/move/remove files and use transactional JSON patches with rollback. `.rift` metadata is protected.

## 9. RiftGit

RiftGit uses RiftFS workspaces at `/home/repos/<owner>/<repo>`. Push creates one Git commit for the local change set and checks the remote head before replacing it.

## 10. Rift Apps

`.rift` packages live below `/apps` and launch in sandboxed frames. Apps use scoped storage and brokered permissions rather than direct native access.

## 11. RiftDev

RiftDev is a pinned mirror of `Arctic403/Editor`. The Pages workflow stages the pinned Editor source and injects the RiftOS overlay into the staged copy only.

## 12. Build boundaries

Normal RiftOS development must stay lightweight.

Active CI consists of:

- Pages shell validation/deploy
- native iOS compile validation

Custom JSC/WebCore/Emscripten build jobs and large prebuilt WASM artifacts are intentionally absent.

## 13. Compatibility rule

System services are reached through `window.RiftOSCore` and its kernel-owned services. Compatibility stores may exist only for migration/read-through and must not become new sources of truth.
