# RiftOS

RiftOS is an **Android-hosted user-space operating environment** built around RiftKernel, RiftFS, RiftWorkspace, RiftRT and a desktop window manager. The active product line is the native Android APK targeting **Android 8.0 / API 26+**.

RiftOS does not replace the Android/Linux kernel. Android owns process isolation, permissions, storage providers and hardware access; RiftKernel owns the RiftOS app/process model, desktop windows, brokered capabilities and RiftOS filesystem namespace.

## Current architecture

```text
Android 8+ / Samsung / DeX
        |
    MainActivity
        |
  RiftOS shell WebView
        |
     RiftKernel
   /      |       \
RiftFS  RiftRT  RiftDesktop
  |                 |
Android filesDir     +-- Files / Settings / RiftDev / Rift MCP / Workspace Live / apps
+ SAF mounts         |
                    RiftBrowser window
                         |
                 RiftBrowserEngine
                         |
          Android System WebView backend
                         |
                     ChatGPT Web
                         |
                exact-origin WebMessage
                         |
               RiftBrowserMcpAppBridge
                         |
                    RiftMcpServer
                         |
                    RiftToolHost
                         |
                 riftfs/workspace
```

There is **no Rift AI workspace app**, no direct model API integration, no remote Rift MCP relay, no pairing key, no public MCP endpoint, and no separate local-model runtime in the active architecture.

The foundation is intentionally simple: the user talks to ChatGPT in RiftBrowser; the ChatGPT Web compatibility adapter can make structured local MCP calls; RiftOS executes those calls on-device inside the workspace sandbox and returns bounded results.

## Core runtime

- **RiftKernel**: app/process/service authority and native bridge client.
- **RiftFS**: app-private filesystem rooted at `filesDir/riftfs`.
- **RiftWorkspace**: JSON-safe workspace API backed by native RiftFS on Android.
- **SAF mounts**: user-selected external folders through Android Storage Access Framework.
- **RiftDesktop**: draggable/resizable/minimizable/maximizable desktop windows and taskbar.
- **RiftRT v1**: worker/iframe/WASM application runtime integrated with RiftDesktop.
- **RiftDev**: Android editor using the canonical workspace.
- **RiftBrowser**: RiftOS-owned browser/window lifecycle with Android System WebView as the current compatibility renderer.
- **Workspace Live**: sandboxed local HTML workspace surface that watches the canonical workspace and shows MCP/local edits as they happen.
- **Rift MCP**: local system app for MCP tool permissions and recent tool activity.

## RiftBrowser + ChatGPT Web

RiftBrowser is a normal RiftOS desktop window. RiftOS owns its title bar, address bar, taskbar entry, focus, move/resize/minimize/maximize state **and the native renderer surface lifecycle**. The renderer sits behind `RiftBrowserEngine`; Android System WebView is only the current compatibility backend. Hidden/minimized browser surfaces are `GONE`—they are never resized to the full host or parked behind the shell.

On the exact ChatGPT Web origin, RiftBrowser installs the Rift MCP compatibility adapter. The page does **not** receive a general filesystem JavaScript object or unrestricted native dispatcher. It can only send structured MCP JSON-RPC through `RiftBrowserMcpAppBridge`, and `RiftToolHost` remains the device-side capability, permission and audit authority.

This path does not use the OpenAI API. ChatGPT Web still makes its normal network requests as a web application, but RiftOS does not make separately billed model API calls for the local MCP workflow.

## Local Rift MCP

The active tool path is entirely local inside the RiftOS process:

```text
ChatGPT Web
    |
riftbrowser-mcp-app.js
    |
exact-origin WebMessage
    |
RiftBrowserMcpAppBridge
    |
RiftMcpServer
    |
RiftToolHost
    |
RiftToolSandbox
    |
riftfs/workspace
```

Current MCP filesystem scope:

```text
riftfs/
  workspace/        # the only MCP-visible filesystem root
```

Read tools are enabled by default. Write tools remain disabled by default until enabled in the **Rift MCP** system app. `rift_workspace_exec` is read-gated for inspection and additionally write-gated only when a batch contains mutations.
Workspace Live can also be inspected and controlled through `rift_live_page` while its local HTML surface is open. Snapshot/query/html operations are read-gated; UI mutation and page-side JavaScript evaluation are write-gated. This control surface is bound only to Workspace Live, not arbitrary internet pages.

Code Mode supports project snapshots, bounded listing/search, symbol and reference lookup, surgical range/symbol reads, guarded text patches, multi-hunk edits, transactional multi-file mutations, scoped snapshot guards, dry-run validation and local ZIP archive creation. The workspace sandbox rejects path traversal and cannot address RiftOS system roots, SAF mounts or arbitrary Android storage.

Tool Protocol V2 uses strict JSON request/call IDs and correlated result packets. Legacy `<rift_call>` envelopes remain supported for compatibility.

## Workspace Live

Workspace Live is a local HTML app packaged inside RiftOS. It runs in a sandboxed iframe with a narrow `postMessage` RPC to the trusted shell, watches only `filesDir/riftfs/workspace`, and refreshes open files/diffs when MCP or other local writers change them. It does not create a localhost listener and does not give the HTML page a general Android or filesystem bridge.

## Workspace boundary

`RiftFS/workspace` is user-owned and starts empty on a fresh install. RiftDev, the Files app workspace view and the ChatGPT/MCP tool path resolve to the same canonical tree.

The MCP capability is intentionally narrower than the rest of RiftOS. Other RiftFS roots, external SAF mounts, downloads and Android system storage are not reachable through MCP tools.

## RiftDev

RiftDev is the local Android development/editor surface. It works against the canonical workspace and does not need a separate AI orchestration app. ChatGPT can inspect or patch RiftDev projects through the browser MCP path when the user enables the required MCP permissions.

## Files and Settings

The Files app uses the RiftOS window manager and an Explorer-style UI with navigation, address path, list/details view, multi-select, create file/folder, rename, copy, cut/paste, duplicate, move, delete, mount controls and RiftFS/SAF operations.

Settings includes **System diagnostics → Save system dump…**. The dump is privacy-limited and Android opens the system **Save As** picker so the user chooses the destination. Dumps exclude secrets, file contents/names, account data, Android IDs and installed-app lists.

## Android build

The private/source RiftOS repository is intentionally Actions-free. Android builds are performed by the public `Arctic403/Riftos-builder` worker against an exact RiftOS commit.

The builder:

- validates source policy and the local transport checks;
- rejects the removed Rift AI workspace app and remote MCP relay paths;
- builds the Android release APK;
- aligns, signs and verifies Android 8+ compatibility;
- verifies the packaged ChatGPT Web/MCP transport and workspace tooling;
- returns successful artifacts or private failure diagnostics to RiftOS releases.

## Branches

- **`main`** — authoritative project source.
- **`android-apk`** — Android staging/validation branch when staged promotion is useful.

## Documentation

- [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md) — implementation status.
- [`docs/TRUE_OS_ARCHITECTURE.md`](docs/TRUE_OS_ARCHITECTURE.md) — current RiftKernel/Android boundary.
- [`docs/ANDROID_NATIVE_ARCHITECTURE.md`](docs/ANDROID_NATIVE_ARCHITECTURE.md) — Android host and native services.
- [`docs/RIFTBROWSER_ARCHITECTURE.md`](docs/RIFTBROWSER_ARCHITECTURE.md) — browser host and compatibility boundary.
- [`docs/RIFTBROWSER_ENGINE_MIGRATION.md`](docs/RIFTBROWSER_ENGINE_MIGRATION.md) — future browser-engine migration gate.
- [`docs/RIFT_MCP_APP_ARCHITECTURE.md`](docs/RIFT_MCP_APP_ARCHITECTURE.md) — canonical local MCP architecture.
- [`docs/RIFT_BROWSER_MCP_APP.md`](docs/RIFT_BROWSER_MCP_APP.md) — ChatGPT Web compatibility protocol.
- [`docs/RIFTWORKSPACE_WEB_ARCHITECTURE.md`](docs/RIFTWORKSPACE_WEB_ARCHITECTURE.md) — workspace boundary.
- [`docs/RIFTRT-v1.md`](docs/RIFTRT-v1.md) — application runtime ABI.
- [`ROADMAP.md`](ROADMAP.md) — planned developer-platform work.

Earlier Rift AI workspace, DOM Agent, remote MCP relay and WebKit/WASM experiments are retained only in Git history or explicitly historical notes. They are not active runtime paths.
