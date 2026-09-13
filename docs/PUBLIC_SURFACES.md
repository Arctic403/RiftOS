# RiftOS Public and Cross-Layer Surfaces

This inventory documents deliberate `Rift*` globals/bridge names. A zero-consumer global is not automatically dead: some names are callbacks or compatibility surfaces consumed by native code, installed applications, developer tools or a future module. The rule is that every exported surface must have an owner and a reason to exist.

## Trusted shell WebView

| Surface | Owner | Purpose / consumer |
| --- | --- | --- |
| `RiftAndroid` | `MainActivity` WebMessage listener | Native exact-origin host injected by Android; consumed by preload only. |
| `RiftPlatform` | `riftandroid-preload.js` | Read-only Android/APK platform descriptor. |
| `RiftNativeTransport` | `riftandroid-preload.js` | Trusted shell JSON message transport to `RiftAndroid`; consumed by kernel and shell-result bridge. |
| `RiftNative` | `riftcore.js` | Small native service facade over the kernel/native bridge. |
| `RiftOSCore` | `riftcore.js` + workspace adapters | Shared kernel/filesystem/process/permission core. Workspace modules replace it immutably to attach the active workspace API. |
| `RiftAndroidAPI` | `riftandroid-platform.js` | Shell-facing Android service facade for clipboard/share/notifications/browser/system operations. |
| `RiftKernel` | `riftandroid-platform.js` | System subset of the Android service facade. |
| `RiftAndroidBack` | `riftandroid-platform.js` | Callback invoked by native Android Back handling before the Activity exits. |
| `RiftApps` | `riftapps.js` | Installed `.rift` package registry/open/install API; consumed by shell, Files and RiftRT. |
| `RiftAppFiles` | `riftapps-files.js` | Package file import/export helper surface for UI/developer use. |
| `RiftDesktop` | `riftos.js` + `riftdesktop-android.js` | Built-in app/window operations plus permanent Android desktop controls. |
| `RiftDesktopPlatform` | `riftdesktop-android-compat.js` | Read-only desktop/platform compatibility descriptor. |
| `RiftDesktopHost` | `riftdesktop-window-host.js` | Window-host compatibility/control surface used by integrations that need shell-window state. |
| `RiftOSWindowManager` | `riftos.js`, wrapped by `riftrt.js` | Canonical window/process registration API shared with RiftRT. |
| `RiftGit` | `riftgit.js` | RiftShell/project GitHub workflow surface. |
| `RiftShellBatch` | `riftshell-batch.js` | Batch parser/executor consumed by RiftShell. |
| `RiftShellMcp` | `riftos.js` | Trusted-shell wrapper around the RiftShell parser for native MCP shell execution. Never exposed to guest pages. |
| `RiftShellMcpNative` | `riftos.js` | Trusted-shell native request shim used only by `RiftShellBridge`; returns `mcp.shell.result` over `RiftNativeTransport`. |
| `RiftWorkspace` | `riftworkspace-web.js`, replaced by `riftworkspace-android-adapter.js` | Active workspace API. Android adapter is authoritative on Android. |
| `RiftWorkspaceJSON` | `riftworkspace-web.js` | JSON-safe trusted compatibility/RPC facade; resolves the active `RiftWorkspace` at call time. |
| `RiftWorkspaceLiveHost` | `riftworkspace-live-host.js` | Compatibility-named parent host for the sandboxed Workspace Records iframe. |
| `RiftWorkspaceNative` | `riftworkspace-live-host.js` | Trusted-shell callback surface receiving native workspace watcher events. |
| `RiftRuntime` | `riftruntime.js` | Read-only runtime/capability report. |
| `RiftRT` | `riftrt.js` | RiftRT launch/session/manager API. |
| `RiftMcp` | `riftmcp-system.js` | Launcher surface for the native Rift MCP Activity (`riftos://mcp`). |
| `RiftBrowserNative` | `riftos.js` | Native browser-state callback consumed by `MainActivity`/`RiftBrowserWindow`. |
| `RiftTransferUI` | `riftos.js` | Native transfer progress callback consumed by `MainActivity.sendNativeProgress`. |

## Guest AI/browser WebView

These names exist only on exact allowed HTTPS origins and are not equivalent to trusted-shell globals.

| Surface | Owner | Purpose / consumer |
| --- | --- | --- |
| `RiftMcpNative` | `RiftBrowserMcpAppBridge` | Exact-origin WebMessage channel carrying MCP JSON-RPC to native. |
| `RiftMcpAppNative` | `riftbrowser-mcp-app.js` | Page-side response receiver invoked by native `deliver()`. |
| `RiftAIAdapters` | `ai-adapter-registry.js` | Site-specific semantic selectors for supported AI web UIs. |

The guest page must **not** contain `RiftAndroid`, `RiftShellMcp`, `RiftShellMcpNative`, RiftFS, RiftWorkspace or a general native dispatcher surface.

## Sandboxed installed-app / Workspace Records surfaces

Installed `.rift` apps and Workspace Records do not receive the trusted globals above. They use narrow `postMessage`/injected `Rift` APIs whose method sets are cross-checked by `scripts/validate-rift-wiring.mjs`.

## Change rule

When adding, renaming or removing a `window.Rift*` / `globalThis.Rift*` export or native-injected Rift bridge name:

1. identify the owning system README;
2. add/update this inventory;
3. update both producer and consumer in one patch;
4. extend wiring validation if the surface crosses a trust boundary;
5. remove obsolete aliases instead of retaining unexplained duplicate paths.
