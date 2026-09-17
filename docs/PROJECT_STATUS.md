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
- Atomic RiftShell command batches with guarded local backups, failure rollback, dry-run validation, and one-command Git synchronization.
- Exact-origin `RiftAndroid` WebMessage bridge for the RiftOS shell.
- App-private RiftFS at `filesDir/riftfs`.
- SAF external-folder mounts with persisted permissions.
- RiftWorkspace Android adapter and JSON-safe workspace surface.
- Android Keystore secret storage.

### Desktop and applications

- RiftDesktop window manager with focus, move, resize, minimize, maximize and taskbar state.
- Explorer-style Files app with create, rename, copy, cut/paste, duplicate, move and delete.
- Settings app with privacy-limited System Dump export and Android Save As picker.
- RiftRT v2 installed-program runtime: native Android-owned app surfaces by default, Worker/WASM compatibility engines, bounded `rift-vm` execution for `rift-exec-v1` `.rxe` payloads, and no iframe execution path.
- Rift++ Core `0.6.0-bootstrap` remains installed/device-proven through Gate 5 on RiftOS source commit `972206a0d4446811572aa697e0f69cf46097dc8c`: exact transitive `allow [storage]` effects, bounded `checkpoint_save/load/remove`, canonical checkpoint type descriptors, and explicit `state.save/state.load/state.remove` RiftVM imports. The installed RiftLLM+ two-launch proof restored complete BrainState across a real RiftRT close/relaunch boundary and verified post-clear fallback. State authority remains app-private behind the existing RiftApp/RiftRT `storage` declaration; no global kernel capability, MCP tool, generic filesystem authority, or RiftCLI route was added. RiftLLM+ Gate 5.5 then stress-tested verified memory across 33 RiftRT sessions without changing the language. Local source now stages Core `0.7.0-bootstrap` Gate 6A: finite `f64`, bounded `Vec<f64,N>` parameter state, deterministic numeric compute, canonical negative-zero handling, and data-only `value_sha256` parameter identity with no new host import/capability. Gate 6A still requires CI/build and installed two-launch parameter checkpoint proof; it performs zero parameter updates, and parameter learning remains unproven until Gate 6C. Core stays separate from the non-executable RiftCLI/V0 swarm DSL.
- `.rift` transactional installer layout under `C:/Programs` with per-user app state separated under `D:/Users/Default/AppData`.
- RiftFS virtual `C:/` system/program and `D:/` user/workspace volumes with canonical legacy-path compatibility.
- Rift MCP system app for local read/write permissions and recent tool activity.
- RiftBrowser-owned renderer surface with a swappable `RiftBrowserEngine` backend.
- Workspace Records trusted-shell dashboard with persistent writer-agnostic history, local checkpoint diffs and optional remote Git comparison.

### RiftBrowser

- RiftOS-owned browser surface/container; Android System WebView is the current `RiftBrowserEngine` compatibility backend.
- RiftOS-owned browser chrome and desktop window state.
- ChatGPT/OpenAI authentication handling, cookies and file chooser support.
- Exact-origin ChatGPT Web compatibility adapter.
- No hidden Rift AI task runner, target picker, session controller or AI event channel.
- ChatGPT receives no unrestricted filesystem JavaScript object or general native dispatcher.
- Browser file/content access is disabled at the WebView settings layer.

### Workspace Records

- Packaged local HTML records dashboard; no localhost TCP server or cloud file service.
- Direct trusted-shell component mounts in a shadow root, reads local records via native calls, and makes the optional Git comparison only on demand. The shadow root separates styles, not shell privileges.
- Recursive native watcher starts with the RiftOS shell and is scoped to `filesDir/riftfs/workspace`.
- `RiftWorkspaceRecords` persists event history and local checkpoint state outside the workspace tree.
- Files, MCP, RiftWorkspace, RiftGit, shell/process and other local writers converge into the same record stream.
- `rift_workspace_diff` exposes the local checkpoint diff/records read-only to MCP.
- RiftGit provides the separate remote Git comparison and checkpoints records after successful workspace push/pull.
- Open files refresh automatically when clean; unsaved local edits trigger a conflict warning instead of being overwritten.
- Workspace Records is observational; it does not own file-save/approval semantics or mutate project files.

### Vortex3D local development bridge

- Explicit local Binder IPC connects the trusted RiftShell `vortex` command family to a co-installed Vortex3D debug APK; there is no localhost/network listener.
- The bridge reuses the existing `rift_shell_exec` MCP tool, so the model-visible tool catalog remains 18 tools.
- Live status/catalog/state/UI/screenshot calls, asynchronous validation/VTXScript jobs, semantic UI clicks/touch replay, bounded screenshot image attachment and evidence pulls into `workspace/.vortex-bridge/` are supported by the source contract.
- Vortex3D's own validation suites/VTXScript/capture runtime remain authoritative; RiftOS is transport/orchestration only.

### Experimental RiftCLI brain / development swarm

- A native `rift-cli` scaffold now exists strictly as an **experimental, manual-only** subsystem below the stable MCP/relay surface.
- It is OFF on every process start; enable state is not persisted and requires the exact manual confirmation command.
- The current brain backend is a non-mutating rule-based planning scaffold with a logical development-team role graph. No model backend is connected yet.
- Rift++ V0 adds a bounded declarative `backend`/`brain`/`agent`/`swarm`/`task` language that compiles workspace-only scripts into non-executable `rift.swarm-ir/0`; `RiftSwarmCoordinatorV0` can preview deterministic assignments through the new `RiftBrainBackend` interface contract without invoking a backend or tool.
- Rift IR V1 adds the language-independent `rift.ir/1` `swarm-core` contract. Rift++ V0 lowers into it without replacing `rift.swarm-ir/0`; the IR independently revalidates graph schedules, capability policy, task gates and declared context/resource totals. Its execution mode is `inspect-only`, single-concurrency by default, with backend/tool/Local-Agent invocation and mutation all disabled.
- When manually enabled, the only live Local Agent behavior is compatibility pass-through through a single router directly above the existing fixed-scope `RiftOsLocalAgent`; no package authority or Android permission is widened.
- It adds zero MCP tools, no relay protocol, no raw Android shell, and no autonomous writes. It is not approved for production/autonomous development until explicitly promoted by the project owner.

### Rift MCP

- `RiftToolHost` is the canonical device-side capability registry.
- `RiftMcpServer` is an in-process MCP JSON-RPC server with no listening socket.
- Filesystem scope is exactly `filesDir/riftfs/workspace`.
- Read/write permission gates are authoritative on-device.
- `rift_workspace_exec` supports bounded project inspection, Project Intelligence v2 restart-persistent symbol/dependency indexing, focused graph/impact/validation views through the existing `project` operation, symbol/reference lookup, surgical reads, guarded patches, transactional multi-file edits, full file/tree hashing, atomic local archive creation and traversal-safe bounded ZIP extraction.
- ChatGPT compatibility calls use bounded plain-text `[RIFT_CALL]` / `[RIFT_END]` blocks with unique call IDs and `[RIFT_RESULT]` continuations.
- MCP JSON-RPC remains private to the trusted browser/native and relay transports; older chat-facing JSON/XML-like envelope formats are removed from the active protocol.
- Optional outbound-only WSS relay client, disabled until the user supplies a secure endpoint and pairing token.
- Relay credentials are encrypted through Android Keystore; the relay receives no filesystem authority.

## Removed / inactive

The following are not active RiftOS Android architecture:

- Rift AI workspace app (`src/riftai-workspace.js`), task controller and persistent AI-session journal (`RiftAiJournal.kt`),
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

See [`../ROADMAP.md`](../ROADMAP.md) for RiftEngine/Servo work, project tooling, RiftScript Studio and capability-gated expansion of the local tool registry.
