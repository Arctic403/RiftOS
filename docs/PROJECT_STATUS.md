# RiftOS Project Status

## Active target

RiftOS currently ships as an Android APK targeting Android 8.0 / API 26+ with Samsung/DeX-friendly resizing. `main` is the authoritative project branch.

## Implemented

### Kernel and filesystem

- RiftKernel JavaScript runtime hosted by `MainActivity`.
- Exact-origin `RiftAndroid` WebMessage bridge for the RiftOS shell.
- App-private RiftFS at `filesDir/riftfs`.
- SAF external-folder mounts with persisted permissions.
- RiftWorkspace Android adapter and JSON patch/history surface.
- Android Keystore secret storage.

### Desktop and applications

- RiftDesktop window manager with focus, move, resize, minimize, maximize and taskbar state.
- Explorer-style Files app inside a normal RiftOS window.
- Settings app with privacy-limited System Dump export and Android Save As picker.
- RiftDev Android editor backed by RiftWorkspace through `RiftDevAndroidDB` compatibility plumbing.
- RiftRT v1 worker/iframe/WASM application runtime.
- **Rift MCP** registered as a RiftOS system app for local tool permissions and recent tool activity.
- **Rift AI** registered as a RiftOS HTML system app for project tree, assistant output, live logs and staged change review.

### Rift AI Workspace

- Visible AI UI is rendered by the existing RiftOS shell; no second AI WebView is created.
- The only model transport is authenticated ChatGPT Web in the existing `RiftBrowserWindow` WebView.
- AI mode keeps that WebView laid out and alive but Android-`INVISIBLE`; **Show ChatGPT** reveals the same page for sign-in/debugging.
- `ai.start` opens a fresh ChatGPT Web conversation and submits the task with compact project-tree context plus the live local MCP manifest.
- Assistant streaming output is mirrored into the HTML workspace; `<rift_call>` / result chatter remains transport detail rather than the primary live UI.
- Local structured logs include session, transport and MCP tool start/finish events without file contents.
- RiftBrowser injects an internal AI session ID into MCP `_meta` after parsing model tool calls; only calls matching the active Rift AI transport session are journaled.
- `RiftAiJournal` lazily snapshots only paths touched by those AI-scoped mutating MCP tools and stores rollback data outside the MCP sandbox. Ordinary visible-chat MCP writes are not attached to an old AI session.
- Changes view reports additions/deletions and supports bounded unified-style text diff plus session-wide Accept all / Revert all. Accept/revert are blocked while transport is active, and a new task is blocked until prior changes are reviewed.
- Session metadata distinguishes persistent review state from active ChatGPT transport; terminal complete/stopped/error events release an invisible transport WebView while keeping review data on disk.
- Session metadata, logs, latest assistant output and rollback originals persist on disk. On process restart, a previously active transport is recovered as `interrupted`/inactive so stale runtime state cannot block future work; any pending changes remain reviewable.
- Active source contains no OpenAI API endpoint/key flow or alternate model transport.

### RiftBrowser

- Native Android System WebView currently hosted inside the RiftOS browser window.
- RiftOS-owned browser chrome and desktop window state.
- ChatGPT/OpenAI authentication handling, cookies and file chooser support.
- Exact-origin `rift-mcp-app-v1` compatibility adapter on ChatGPT Web.
- Adapter performs in-process MCP `initialize`, `tools/list` and `tools/call` through `RiftMcpServer`.
- Live compact MCP tool manifest is supplied once per conversation route; strict `<rift_call>...</rift_call>` envelopes are validated and routed locally.
- Streaming DOM work is mutation-scoped and batched; the removed whole-chat scanner is guarded against in CI.
- ChatGPT never receives direct `RiftSandbox`, `RiftSandboxFS` or general `RiftNativeDispatcher` access.
- Agent V1/V2/V3 remains removed and is not used as fallback.

### Local Rift MCP

- `RiftToolHost` is the canonical device-side capability registry.
- `RiftMcpServer` is an in-process MCP JSON-RPC server with no listening socket.
- Local tool scope is `filesDir/riftfs/tool-sandbox`.
- Existing alpha data is migrated from `filesDir/riftfs/browser-sandbox` on first use.
- Local read/write permission gates are authoritative.
- Read tools: `rift_info`, `rift_stat`, `rift_list`, `rift_read_text`.
- Write tools: `rift_write_text`, `rift_mkdir`, `rift_remove`, `rift_move`.
- Read is enabled by default; write remains disabled by default until enabled in Rift MCP settings.
- Recent activity log records canonical tool, target/path, outcome and time without storing file contents.
- No remote relay, WSS device client, pairing key, public MCP endpoint, process-start MCP provider or relay dependency is active.
- Legacy bridge preferences are migrated for grants/audit, then cleared; the old pairing secret is removed.

### Diagnostics/build

- Privacy-limited JSON system dump.
- User-selected dump destination.
- GitHub Actions build/sign/verify/publish pipeline.
- APK verification requires the Rift MCP App asset and local `riftmcp-system.js` module.
- CI rejects the removed DOM Agent, Agent V3 marker, whole-chat mutation scanner, remote relay/client/provider/Activity and `libllamaserver.so`; it verifies the shell Rift AI module, internal `riftos/aiSessionId` threading and rejects model-API endpoint/key patterns across the active Rift AI/browser transport sources.
- No emulator smoke test or compatibility matrix in normal CI.

## Removed / inactive

The following are not active RiftOS Android architecture:

- remote `services/rift-mcp-relay` Node service,
- remote `RiftMcpRelayClient` WSS device adapter,
- `RiftMcpInitProvider` background relay startup,
- `RiftMcpBridgeActivity` pairing/endpoint UI,
- `riftbridge-system.js`,
- pairing-key MCP endpoint URLs,
- local gpt-oss/llama.cpp runtime and `libllamaserver.so`,
- full-screen `RiftBrowserActivity`,
- WebKit-WASM/Wisp and Gecko WASM browser experiments,
- Android emulator CI matrix/smoke tests,
- PWA/service-worker/iOS-only assets in the Android APK,
- Rift Agent V1/V2/V3 fenced `rift-tool` protocol and response-node tracking,
- direct `RiftSandbox` / `RiftSandboxFS` WebMessage access from `chatgpt.com`.

Historical Git commits may mention those experiments. They are not current runtime claims.

## Known limitations

- RiftBrowser compatibility mode depends on the ChatGPT composer, stop control and semantic rendered-message attributes. ChatGPT UI changes can break task submission/tool continuation/completion detection without weakening the local capability boundary.
- Write tools are locally disabled by default until explicitly enabled in Rift MCP.
- Android System WebView remains memory-heavy on long ChatGPT conversations; RiftEngine/Servo migration is planned but not yet shipped.
- Embedded identity providers may independently reject Android WebView login.
- Current review controls are session-wide rather than per-file/per-hunk, and text diff generation is deliberately bounded.
- Native Kotlin/DEX cannot be arbitrarily hot-swapped; APK rebuild is required.

## Planned

See `ROADMAP.md`, especially Rift AI working-tree refinement, project search/patch tooling, RiftEngine/Servo integration, RiftScript Studio and capability-gated expansion of the local tool registry.
