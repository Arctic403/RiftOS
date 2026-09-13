# RiftShell

## Purpose

RiftShell is the interactive command surface for navigating and operating the wider RiftFS namespace. It also provides atomic local command batches and the execution target behind MCP's narrow `rift_shell_exec` bridge.

## Source ownership

- shell parser/command dispatcher: `runShell()` and helpers in `src/riftos.js`.
- transactional batch engine: `src/riftshell-batch.js`.
- Git subcommands: `src/riftgit.js`.
- MCP shell bridge: `RiftShellBridge.kt` -> trusted-shell-WebView execution/result exchange owned by `MainActivity`.

## Namespace

Unlike normal MCP filesystem tools, RiftShell can operate on RiftOS roots such as `/home`, `/workspace`, `/downloads`, `/documents` and `/mounts` according to kernel permissions. This is why `rift_shell_exec` is treated as a stronger capability than ordinary workspace tools.

## Command flow

The terminal calls `runShell(raw,print,state,context)`. Paths are resolved relative to shell `cwd`. Filesystem commands call `RiftOSCore.fs`; Git commands delegate to RiftGit; `batch` delegates to `RiftShellBatch`.

## Atomic batch engine

`riftshell-batch.js` tokenizes/splits semicolon commands, preflights commands and permissions, computes mutation targets, captures backups, executes sequentially and restores targets if a command fails. `--dry-run` validates without committing. Non-reversible/remote behavior should not be hidden inside a supposedly atomic local batch.

## MCP bridge

`MainActivity` registers `RiftShellBridge` against the trusted RiftOS shell WebView. `RiftShellBridge.execute(command,cwd,reply)` invokes `window.RiftShellMcpNative.request(...)` there. The shell executes through the existing `RiftShellMcp` parser, then sends a one-way exact-origin `mcp.shell.result` message over `RiftAndroid`; native correlates the result back to the pending MCP call. The guest RiftBrowser/ChatGPT page never receives `RiftShellMcp` or shell authority. The bridge has a bounded timeout and still never exposes Android/Linux `/system/bin/sh`.

## Critical invariants

- Shell path resolution must not accidentally reinterpret absolute paths as cwd-relative.
- Batch write classification/preflight occurs before executing mutations.
- Rollback captures every mutation target that a supported batch command can touch.
- Shell MCP bridge requires explicit local write permission and must not become raw Android shell access.
- Git remote actions are handled by RiftGit's own high-level command semantics, not faked as locally reversible file operations.

## Failure signatures

- Terminal command works manually but MCP shell fails -> shell bridge registration/permissions/correlation.
- Batch partially changes files after failure -> mutation target/preflight/rollback bug.
- Relative path acts in wrong directory -> shell state/resolvePath.
- Git command parsing wrong -> handoff between `runShell` and RiftGit.

## Fix map

Individual shell command/parser -> `riftos.js`.
Atomic batch logic -> `riftshell-batch.js`.
Remote Git behavior -> `riftgit.js`.
MCP/native shell bridge -> `RiftShellBridge.kt`, `MainActivity`'s trusted-shell result route, and `RiftShellMcpNative` in `riftos.js`.

## Validation

Run `scripts/test-rift-shell-batch.mjs` and `scripts/test-rift-shell-git.mjs`. Test cwd changes, quoted arguments, failure rollback, dry-run, permission denial and MCP shell execution after any shell protocol change.
