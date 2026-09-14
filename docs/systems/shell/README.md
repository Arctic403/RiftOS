# RiftShell

## Purpose

RiftShell is the interactive command surface for navigating and operating the wider RiftFS namespace. It also provides atomic local command batches and the execution target behind MCP's narrow `rift_shell_exec` bridge.

## Source ownership

- shell parser/command dispatcher: `runShell()` and helpers in `src/riftos.js`.
- transactional batch engine: `src/riftshell-batch.js`.
- Git subcommands: `src/riftgit.js`.
- local-first `rift repo|vault|build|memory` dispatch: `src/riftlocal-platform.js` plus its four subsystem modules.
- MCP shell bridge: `RiftShellBridge.kt` -> trusted-shell-WebView execution/result exchange owned by `MainActivity`.

## Namespace

Unlike normal MCP filesystem tools, RiftShell can operate across RiftOS roots and virtual drives according to kernel permissions. The preferred OS paths are `C:/` for system/program/toolchain state and `D:/` for user/workspace data; legacy roots such as `/workspace`, `/home`, `/downloads` and `/documents` remain compatibility aliases. SAF folders remain under `/mounts`. This is why `rift_shell_exec` is treated as a stronger capability than ordinary workspace tools.

## Command flow

The terminal calls `runShell(raw,print,state,context)`. Paths are resolved relative to shell `cwd`; `C:/...` and `D:/...` are accepted as absolute RiftOS paths, `home` enters `D:/Users/Default`, `workspace cd` enters `D:/Workspace`, and `drives` lists the fixed virtual volumes. Filesystem commands call `RiftOSCore.fs`; Git commands delegate to RiftGit; `batch` delegates to `RiftShellBatch`; `open` resolves normal launcher targets plus the dynamic `mcp` and `riftrt` system surfaces and errors instead of claiming success for an unknown app; the `vortex` command family delegates to the native `vortex.bridge` Binder client documented in `../vortex-bridge/README.md`; `vortex-agent` delegates to the fixed Vortex-only local Android UI agent and `riftos-agent` delegates to the fixed RiftOS-self UI agent documented in `../vortex-agent/README.md`. `riftos-agent devlab ...` is a structured self-agent controller that reaches the authoritative Dev Lab API through the current shell bridge without exposing arbitrary shell execution; `chat` delegates to the local `.riftchat` handoff store documented in `../chat-handoff/README.md`. The `rift` family delegates to `RiftLocalPlatform`, which routes `repo`, `vault`, `build` and `memory` without expanding the MCP tool catalog. `riftllm-agent` delegates to the optional `RiftLlmBridge` fixed Binder adapter for the standalone RiftLLM APK; pairing accepts no token argument and instead opens a local secure prompt, while source publication remains guarded by RiftWorkspace preview/apply.

## Atomic batch engine

`riftshell-batch.js` tokenizes/splits semicolon commands, preflights commands and permissions, computes mutation targets, captures backups, executes sequentially and restores targets if a command fails. `--dry-run` validates without committing. Preflight models planned filesystem state, including deferred/unknown descendants created by an `unzip`, so later commands in the same batch can address extracted paths without weakening runtime rollback. Non-reversible/remote behavior should not be hidden inside a supposedly atomic local batch.

## MCP bridge

`MainActivity` owns a `RiftShellBridge` against its trusted RiftOS shell WebView. Android declares that shell Activity as `singleTask`, because RiftOS must have one authoritative desktop/kernel/WebView runtime rather than multiple independent ProcessTables. The process-wide MCP runtime registers that bridge at creation and refreshes ownership from both `MainActivity.onResume()` and focused-window acquisition, so task/background transitions cannot leave MCP attached to a hidden runtime. Destroy unregisters only that exact bridge identity before closing it, and a closed bridge refuses future execution. `RiftShellBridge.execute(command,cwd,reply)` invokes `window.RiftShellMcpNative.request(...)` in the current trusted shell runtime. The shell executes through the existing `RiftShellMcp` parser, then sends a one-way exact-origin `mcp.shell.result` message over `RiftAndroid`; native correlates the result back to the pending MCP call. The guest RiftBrowser/ChatGPT page never receives `RiftShellMcp` or shell authority. The bridge has a bounded timeout and still never exposes Android/Linux `/system/bin/sh`. Normal shell calls retain the 60-second envelope; the explicit `vortex test-wait` / `validate-wait` / `script-wait` foreground-session commands receive a separate 105-second envelope because their native Vortex session is bounded to 85 seconds.

## Critical invariants

- Shell path resolution must not accidentally reinterpret absolute paths as cwd-relative.
- Batch write classification/preflight occurs before executing mutations.
- Planned `unzip` destinations expose deferred descendants during preflight; file-vs-directory truth for those unknown descendants is resolved by real execution, and any mismatch still rolls the whole batch back.
- Rollback captures every mutation target that a supported batch command can touch.
- Shell MCP bridge requires explicit local write permission and must not become raw Android shell access.
- RiftOS must have exactly one `MainActivity` shell runtime (`singleTask`); process-wide MCP shell ownership follows that shell on resume and window focus, unregister is identity-checked, and destroyed bridges reject future execution.
- Git remote actions are handled by RiftGit's own high-level command semantics, not faked as locally reversible file operations.
- Live `vortex` / `vortex-agent` / `riftos-agent` / `riftllm-agent` operations, `chat` bundle creation and the `rift` local-platform family are explicitly listed as non-reversible and rejected by atomic batch preflight; repo/vault/build/memory operations use their own transaction/durability semantics and cannot truthfully participate in the batch engine's ordinary RiftFS rollback contract.

## Failure signatures

- Terminal command works manually but MCP shell fails -> shell bridge registration/permissions/correlation.
- MCP `ps`/`kill` disagrees with visible Task Manager or native windows -> suspect duplicate/stale shell runtime ownership; verify `MainActivity` remains `singleTask`, focus/resume re-registration reaches the same bridge, and destroy/unregister is identity-safe before changing `ProcessTable`.
- Batch partially changes files after failure -> mutation target/preflight/rollback bug.
- Relative path acts in wrong directory -> shell state/resolvePath.
- Git command parsing wrong -> handoff between `runShell` and RiftGit.

## Fix map

Individual shell command/parser -> `riftos.js`.
Atomic batch logic -> `riftshell-batch.js`.
Remote Git behavior -> `riftgit.js`.
MCP/native shell bridge -> `RiftShellBridge.kt`, `MainActivity`'s trusted-shell result route, and `RiftShellMcpNative` in `riftos.js`.

## Validation

Run `scripts/test-rift-shell-batch.mjs`, `scripts/test-rift-shell-git.mjs` and `scripts/test-rift-local-platform.mjs`. Test cwd changes, quoted arguments, failure rollback, dry-run, permission denial and MCP shell execution after any shell protocol change. On Android, also force/reproduce MainActivity reordering or recreation and confirm MCP `ps` matches the visible Task Manager before and after resume/destroy transitions.
