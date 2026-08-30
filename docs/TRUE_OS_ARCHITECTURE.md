# RiftOS True OS Core

This refactor changes RiftOS from a collection of browser-app simulations into a layered operating environment that can run in two modes: web/PWA today and a thin native iOS host later.

## Layer model

1. **RiftKernel** (`src/riftcore.js`)
   - process table and PID lifecycle
   - app registry
   - capability/permission vocabulary
   - system information and mount table
   - a stable native-bridge boundary

2. **RiftFS 2**
   - OPFS is the preferred browser filesystem when available
   - the existing `riftos` IndexedDB files store remains as a compatibility mirror
   - legacy files are migrated into OPFS once, without deleting IndexedDB data
   - reads reconcile the newer copy and writes update both layers
   - this lets existing RiftGit/RiftOS code continue to operate during migration

3. **RiftShell / system UI** (`src/riftos-system-ui.js`)
   - Files, Editor, Tasks and Settings are backed by the new kernel
   - RiftShell adds filesystem navigation, mounts, storage stats, process management and capability diagnostics
   - Git commands remain delegated to RiftGit

4. **RiftDev**
   - remains a pinned clone of the separate Editor repository
   - only RiftOS's deployed clone gets RiftOS integration overlays
   - the source Editor repository is not modified by RiftOS development

5. **RiftNative**
   - JavaScript calls `RiftNative.call(method, args)`
   - in a normal PWA the API reports that no native host is connected
   - in a WKWebView host, Swift receives messages through the `riftNative` script-message channel and resolves the JavaScript request

## Initial filesystem namespace

```text
/
├── home/
├── apps/
├── system/
└── mounts/
```

`/` is backed by OPFS in supported browsers. IndexedDB remains a mirrored compatibility layer while old components are migrated. A future native host can expose user-approved iOS Files locations below `/mounts`.

## Capability vocabulary

The first kernel capability set is:

- `fs.read`
- `fs.write`
- `network`
- `clipboard.read`
- `clipboard.write`
- `notifications`
- `share`
- `process.read`
- `process.manage`
- `system.settings`
- `native.read`
- `native.files`
- `native.background`

Built-in system apps declare their kernel capabilities. Installed RiftApps can move onto the same broker in a later package-format revision.

## RiftShell additions

```text
sysinfo
mount
df
ps
kill <pid>
apps
permissions
native
pwd
cd <dir>
ls [path]
cat <file>
write <file> <text>
mkdir <dir>
rm <path>
syncfs
open <app>
```

Existing Git commands continue through RiftGit.

## Native iOS host

`native/ios` contains a SwiftUI/WKWebView host scaffold. The first bridge supports:

- device/capability information
- user-selected Files directory mounts for the current native session
- directory listing and UTF-8 text read/write within those mounts
- document picking
- clipboard read/write
- share sheet
- local-notification permission and scheduling

This does **not** bypass the iOS sandbox. Native files are exposed only through user-approved document-picker locations or normal iOS APIs.

The native project is generated with XcodeGen. `.github/workflows/riftos-native-ios.yml` is intentionally `workflow_dispatch` only, so normal RiftOS pushes do not consume macOS build minutes.

## Migration rule

Do not rewrite every existing RiftOS component at once. New system-level features should use `window.RiftOSCore`. Old components may continue using IndexedDB during migration because RiftFS 2 mirrors that store. Once a subsystem is migrated, it should stop creating its own filesystem/process/permission implementation.

## Browser engine status

RiftEngine/WebCore work is intentionally independent of the True OS refactor. The native-capability and filesystem work does not require completing the custom browser engine first.
