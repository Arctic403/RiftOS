# RiftOS Project Status

## Active target

RiftOS ships as an Android APK targeting Android 8.0 / API 26+ with Samsung/DeX-friendly resizing. `main` is the authoritative source branch.

## Active foundation

```text
RiftOS shell
  -> RiftBrowser
  -> AI client transports
      -> MCP compatibility path
      -> Browser injector adapters
  -> RiftOS native capability layer
  -> RiftToolHost
  -> RiftToolSandbox
  -> riftfs/workspace
```

RiftOS provides a local capability layer consumed through independent AI transports. ChatGPT Web uses the MCP compatibility path. Other supported browser AI clients use isolated injector adapters that expose the same RiftOS capability model without sharing MCP transport state.

## Implemented

### Kernel and filesystem

- RiftKernel JavaScript runtime hosted by `MainActivity`.
- RiftShell 2.1 with cwd-aware paths across `/home`, `/workspace`, and Android mounts.
- RiftGit existing-folder attachment, explicit clone destinations, and atomic binary-safe tree synchronization.
- Exact-origin `RiftAndroid` WebMessage bridge for the RiftOS shell.
- App-private RiftFS at `filesDir/riftfs`.
- SAF external-folder mounts with persisted permissions.
- RiftWorkspace Android adapter and JSON-safe workspace surface.
- Android Keystore secret storage.

### Desktop and applications

- RiftDesktop window manager with focus, move, resize, minimize, maximize and taskbar state.
- Explorer-style Files app with create, rename, copy, cut/paste, duplicate, move and delete.
- Settings app with privacy-limited System Dump export and Android Save As picker.
- RiftDev Android editor backed by the canonical workspace.
- RiftRT v1 worker/iframe/WASM application runtime.
- Rift MCP system app for local read/write permissions and recent tool activity.
- RiftBrowser-owned renderer surface with a swappable `RiftBrowserEngine` backend.
- Workspace Live sandboxed local HTML surface with live workspace events, compact diffs and revision-guarded manual saves.

### RiftBrowser

- RiftOS-owned browser surface/container; Android System WebView is the current `RiftBrowserEngine` compatibility backend.
- RiftOS-owned browser chrome and desktop window state.
- ChatGPT/OpenAI authentication handling, cookies and file chooser support.
- Exact-origin ChatGPT Web compatibility adapter.
- No hidden Rift AI task runner, target picker, session controller or AI event channel.
- ChatGPT receives no unrestricted filesystem JavaScript object or general native dispatcher.
- Browser file/content access is disabled at the WebView settings layer.

### Workspace Live

- Packaged local HTML UI; no localhost TCP server or cloud file service.
- Sandboxed iframe omits `allow-same-origin` and receives only a narrow workspace RPC.
- Recursive native watcher is scoped to `filesDir/riftfs/workspace`.
- MCP, RiftFS, git/process and manual edits appear in the live activity feed.
- Open files refresh automatically when clean; unsaved local edits trigger a conflict warning instead of being overwritten.
- Manual saves use SHA-256 revision checks to prevent stale clobbers.

### Rift MCP

- `RiftToolHost` is the canonical device-side capability registry.
- `RiftMcpServer` is an in-process MCP JSON-RPC server with no listening socket.
- Filesystem scope is exactly `filesDir/riftfs/workspace`.
- Read/write permission gates are authoritative on-device.
- `rift_workspace_exec` supports bounded project inspection, symbol/reference lookup, surgical reads, guarded patches, transactional multi-file edits, full file/tree hashing, atomic local archive creation and traversal-safe bounded ZIP extraction.
- Strict `rift-tools-v2` JSON packets are supported with request/call correlation.
- Legacy `<rift_call>` envelopes remain available for compatibility.
- Optional outbound-only WSS relay client, disabled until the user supplies a secure endpoint and pairing token.
- Relay credentials are encrypted through Android Keystore; the relay receives no filesystem authority.

## Removed / inactive

The following are not active RiftOS Android architecture:

- Rift AI workspace app (`src/riftai-workspace.js`),
- native `ai.*` shell command surface,
- hidden ChatGPT task/target/session orchestration in `RiftBrowserWindow`,
- Rift AI event channel in the MCP bridge,
- remote `services/rift-mcp-relay`,
- `RiftMcpInitProvider`,
- `RiftMcpBridgeActivity`,
- `riftbridge-system.js`,
- pairing-key MCP endpoint URLs,
- local gpt-oss/llama.cpp runtime and `libllamaserver.so`,
- Rift Agent V1/V2/V3 DOM-agent runtime,
- direct `RiftSandbox` / `RiftSandboxFS` WebMessage access from `chatgpt.com`.

Historical Git commits and documents may describe removed experiments. They are not current runtime claims.

## Build and verification

The RiftOS source repository is intentionally Actions-free. The public `Arctic403/Riftos-builder` worker builds exact RiftOS commits and returns results through RiftOS releases.

Current builder policy:

- rejects the removed Rift AI workspace source;
- runs the repository source checks before Gradle;
- builds the optional native MCP relay transport without adding a model API path;
- builds, aligns, signs and verifies the APK;
- verifies Android 8+ package/signature requirements;
- verifies the packaged ChatGPT Web/MCP adapter and local workspace tooling;
- rejects reintroduction of removed AI assets.

## Known limitations

- The compatibility adapter depends on ChatGPT Web DOM semantics and can require updates after major ChatGPT UI changes.
- Write tools are disabled by default until explicitly enabled in Rift MCP settings.
- Android System WebView remains memory-heavy on long browser sessions.
- Embedded identity providers may independently reject Android WebView login.
- Native Kotlin/DEX changes require an APK rebuild.

## Planned

See `ROADMAP.md` for RiftEngine/Servo work, project tooling, RiftScript Studio and capability-gated expansion of the local tool registry.
