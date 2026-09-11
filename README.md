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
Android filesDir     +-- Files / Settings / RiftDev / Rift AI / Rift MCP / apps
+ SAF mounts         |
                    Rift AI HTML workspace
                         |
               hidden ChatGPT Web transport
                         |
                  native RiftBrowserWindow
                         |
                  Android System WebView
                         |
                ChatGPT Web compatibility
                         |
                 in-process MCP JSON-RPC
                         |
                    RiftMcpServer
                         |
                    RiftToolHost
                         |
                    riftfs/workspace
```

There is **no remote Rift MCP relay** in the active architecture. No WSS device client, pairing key, public MCP endpoint or process-start relay provider is required for the RiftBrowser local MCP path.

### Core runtime

- **RiftKernel**: app/process/service authority and native bridge client.
- **RiftFS**: app-private filesystem rooted at `filesDir/riftfs`.
- **RiftWorkspace**: JSON-safe workspace API backed by native RiftFS on Android.
- **SAF mounts**: user-selected external folders through Android Storage Access Framework.
- **RiftDesktop**: draggable/resizable/minimizable/maximizable desktop windows and taskbar.
- **RiftRT v1**: worker/iframe/WASM application runtime integrated with RiftDesktop.
- **RiftDev**: Android editor using a RiftWorkspace-backed IndexedDB compatibility facade.
- **Rift AI**: shell-rendered project/assistant/log/diff workspace backed only by hidden authenticated ChatGPT Web.
- **Rift MCP**: local system app for MCP tool permissions and recent tool activity.

## RiftBrowser

RiftBrowser is a normal RiftOS desktop window. RiftOS owns its title bar, address bar, taskbar entry, focus, move/resize/minimize/maximize state; Android currently owns the System WebView content surface positioned inside that window.

On ChatGPT Web, RiftBrowser installs the exact-origin `rift-mcp-app-v1` compatibility adapter. It obtains the live tool manifest from the in-process MCP server and brokers strict structured calls/results. ChatGPT never receives a general filesystem JavaScript object or `RiftNativeDispatcher` access.

The compatibility asset is performance-gated: it must not rescan the complete conversation on every streaming DOM mutation, and it injects a compact manifest once per conversation route rather than on every message.

RiftBrowser is planned to migrate from Android System WebView to RiftEngine/Servo after the compatibility gate in `docs/RIFTBROWSER_ENGINE_MIGRATION.md` passes on real hardware.

## Rift AI Workspace

Rift AI is a local HTML cockpit rendered by the existing RiftOS shell. It does not create another WebView and it does not call a model API. The existing authenticated ChatGPT WebView becomes an invisible transport while an AI task runs. Assistant output, structured logs, project tree and local diffs are shown by RiftOS instead of exposing the tool-call conversation as the normal live view.

Before each AI task, Rift AI can target a **new chat**, the **current ChatGPT page**, a DOM-discovered existing **chat**, a **Project** (which starts a new chat inside that project), or an existing **project chat**. **Browse all…** reveals the same authenticated ChatGPT WebView and opens ChatGPT's own search UI for destinations that are not currently loaded in the sidebar. No private ChatGPT backend API is used for target discovery, and discovered chat titles/URLs are kept ephemeral rather than written into the Rift AI journal.

`RiftFS/workspace` is intentionally user-owned and starts empty on a fresh install; RiftOS no longer seeds project/document/download/patch/history folders inside it. Workspace history metadata lives under `riftfs/system/riftworkspace`, outside AI scope.

The selected conversation receives a compact `RIFT_PROJECT_V2` top-level descriptor for `RiftFS/workspace`, not a recursive project dump. Full project reachability is exposed through `rift_workspace_exec` (Rift Code Mode), which can combine local project/list/search/ranged-read and transactional write/replace/patch/mkdir/remove/move/rename/copy operations into one model-visible call. Every MCP filesystem path is hard-scoped to that one `workspace/` root; RiftOS system roots, downloads, documents, SAF mounts and other storage are not addressable through the AI capability. RiftBrowser privately correlates each AI-owned `tools/call` with both the native AI session ID and the model `call_id` before accepting a result.

The persistent **Changes** view provides additions/deletions, bounded unified-style text diffs, **Accept all** and **Revert all** without cloning the whole project. Accept/revert are locked while ChatGPT transport is active, and a new task cannot replace a session with unreviewed changes. Rollback data lives under `filesDir/rift-ai`, outside the MCP-visible sandbox.

The **Show ChatGPT** control reveals the same WebView for sign-in or debugging; it is not a second transport. When an invisible task reaches complete/stopped/error state, the hidden renderer is released instead of being kept alive by the persistent session record. See `docs/RIFT_AI_WORKSPACE.md`.

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
riftfs/workspace
```

Current tool scope:

```text
riftfs/
  workspace/        # the only MCP-visible filesystem root
```

Read tools are enabled by default. Write tools remain disabled by default until enabled in the **Rift MCP** system app. `rift_workspace_exec` is read-gated for inspection and additionally write-gated only when a batch contains mutations. Code Mode executes up to 192 ordered workspace operations locally and rolls back the entire batch if any operation fails. `finish:true` still returns one correlated `[RIFT_MCP_RESULT_V1]` confirmation to ChatGPT Web; the session completes only after that confirmation has produced the final assistant response. The local activity log records tool/path/outcome without storing file contents.

Legacy `tool-sandbox/workspace` and `browser-sandbox/workspace` data are migration sources only. Unique project entries are merged into canonical `riftfs/workspace`; those legacy roots are never exposed as MCP paths.

## Files and Settings

The Files app uses the RiftOS window manager and an Explorer-style UI with navigation, address path, list/details view, multi-select, create file/folder, rename, copy, cut/paste, duplicate, move, delete, mount controls and RiftFS/SAF operations. The user-facing `/workspace`, RiftDev and Rift AI Code Mode all resolve to the same canonical `filesDir/riftfs/workspace` tree.

Settings includes **System diagnostics → Save system dump…**. The dump is privacy-limited and Android opens the system **Save As** picker so the user chooses the destination. Dumps exclude secrets, file contents/names, account data, Android IDs and installed-app lists.

## Android build

The APK workflow is `.github/workflows/riftos-android-apk.yml` and runs on `android-apk` and `main`.

It builds, aligns, signs and verifies the APK; checks API 26+, package/signature and Android-only assets; verifies the local Rift MCP/Rift AI modules, AI-session metadata path, `RIFT_PROJECT_V2`, and Rift Code Mode wiring; rejects model-API/key paths, the discarded second-AI-WebView design, the removed DOM Agent, whole-chat streaming scanner, remote MCP relay/client/provider/Activity and removed local-AI binaries; uploads the artifact; and updates the `android-latest` release.

## Branches

- **`main`** — authoritative project source.
- **`android-apk`** — Android staging/validation branch when a staged promotion is useful.

## Documentation

- [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md) — what is implemented, removed and planned.
- [`docs/TRUE_OS_ARCHITECTURE.md`](docs/TRUE_OS_ARCHITECTURE.md) — current RiftKernel/Android boundary.
- [`docs/ANDROID_NATIVE_ARCHITECTURE.md`](docs/ANDROID_NATIVE_ARCHITECTURE.md) — Android host and native services.
- [`docs/RIFTBROWSER_ARCHITECTURE.md`](docs/RIFTBROWSER_ARCHITECTURE.md) — current browser host and MCP compatibility boundary.
- [`docs/RIFTBROWSER_ENGINE_MIGRATION.md`](docs/RIFTBROWSER_ENGINE_MIGRATION.md) — RiftEngine/Servo migration gate.
- [`docs/RIFT_MCP_APP_ARCHITECTURE.md`](docs/RIFT_MCP_APP_ARCHITECTURE.md) — canonical local MCP architecture.
- [`docs/RIFT_AI_WORKSPACE.md`](docs/RIFT_AI_WORKSPACE.md) — HTML AI cockpit, hidden ChatGPT Web transport, logs and working-tree journal.
- [`docs/RIFT_BROWSER_MCP_APP.md`](docs/RIFT_BROWSER_MCP_APP.md) — ChatGPT Web compatibility protocol.
- [`docs/RIFTWORKSPACE_WEB_ARCHITECTURE.md`](docs/RIFTWORKSPACE_WEB_ARCHITECTURE.md) — workspace boundary.
- [`docs/RIFTRT-v1.md`](docs/RIFTRT-v1.md) — application runtime ABI.
- [`ROADMAP.md`](ROADMAP.md) — planned RiftScript/developer-platform work.

Earlier DOM Agent, remote MCP relay and WebKit/WASM experiments are retained only in Git history and removed/inactive documentation notes. They are not active runtime paths.
