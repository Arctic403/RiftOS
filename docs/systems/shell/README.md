# RiftShell

## Purpose

RiftShell is the command surface for navigating and operating the wider RiftFS namespace. Its control plane is migrating to a process-owned native Kotlin executor so MCP diagnostics and workspace inspection do not depend on Chromium. During the migration, the native executor owns the supported core commands and delegates only not-yet-ported command families to the trusted shell WebView compatibility executor.

## Source ownership

- native process-owned core executor: `RiftNativeShell.kt`.
- compatibility shell parser/command dispatcher for not-yet-ported families: `runShell()` and helpers in `src/riftos.js`.
- transactional batch engine: `src/riftshell-batch.js`.
- Git subcommands: `src/riftgit.js`.
- local-first `rift repo|vault|build|memory` dispatch: `src/riftlocal-platform.js` plus its four subsystem modules.
- MCP executor contract / temporary WebView fallback: `RiftShellBridge.kt`.
- process ownership and fallback attachment: `RiftMcpRuntime.kt`.

## Namespace

Unlike normal MCP filesystem tools, RiftShell can operate across RiftOS roots and virtual drives according to kernel permissions. The preferred OS paths are `C:/` for system/program/toolchain state and `D:/` for user/workspace data; legacy roots such as `/workspace`, `/home`, `/downloads` and `/documents` remain compatibility aliases. SAF folders remain under `/mounts`. This is why `rift_shell_exec` is treated as a stronger capability than ordinary workspace tools.

## Command flow

MCP calls enter `RiftNativeShell` first. Patch 1 handles `help`, `pwd`, `home`, `drives`, `df`, `sysinfo`, `native`, `uptime`, `version`, `ls`, `tree`, `stat`, `cat`, `head`, `tail`, and `workspace cd|info|ls|status` without a WebView. Paths are resolved relative to shell `cwd`; `C:/...` and `D:/...` map through the shared `RiftVolumePaths` contract. Listing a virtual volume root synthesizes its declared roots (for example `D:/Workspace`) and merges any real backing-only entries; recursive `tree` traverses those mapped roots under the same bounded row limit rather than showing an empty backing directory. Commands not yet native are explicitly delegated to the compatibility executor instead of silently changing semantics. The visible Terminal is now owned by `RiftNativeSystemApps.kt`: its Android input/output View calls the process-owned `RiftShellExecutor` directly, so native commands never require a DOM/WebView terminal. The JavaScript `runShell(raw,print,state,context)` parser remains only as the temporary fallback implementation for command families not yet ported native. Filesystem commands call `RiftOSCore.fs`; Git commands delegate to RiftGit; `batch` delegates to `RiftShellBatch`; `open` resolves normal launcher targets plus the dynamic `mcp` and `riftrt` system surfaces and errors instead of claiming success for an unknown app; the `vortex` command family delegates to the native `vortex.bridge` Binder client documented in `../vortex-bridge/README.md`; `vortex-agent` delegates to the fixed Vortex-only local Android UI agent and `riftos-agent` delegates to the fixed RiftOS-self UI agent documented in `../vortex-agent/README.md`. `riftos-agent devlab ...` is a structured self-agent controller that reaches the authoritative Dev Lab API through the current shell bridge without exposing arbitrary shell execution; `chat` delegates to the local `.riftchat` handoff store documented in `../chat-handoff/README.md`. The `rift` family delegates to `RiftLocalPlatform`, which routes `repo`, `vault`, `build` and `memory` without expanding the MCP tool catalog. `riftllm-agent` delegates to `RiftLlmBridge`; its RiftLLM Dev API operations use the fixed Binder adapter, while V1/V2 `corpus-synth*` / `corpus-build*` / `corpus-status*` are local-only RiftFS helpers hard-confined to `/workspace/RiftLLM/tokenizer/private/`; fixed Text Encoding evaluation accepts only reviewed A/B/A2/B2 artifacts and heldout/challenge lanes. Pairing accepts no token argument and instead opens a local secure prompt, while source publication remains guarded by RiftWorkspace preview/apply. Corpus helpers do not expose Python, Android shell, arbitrary process execution or a new MCP tool. Scalable corpus synth/build uses deterministic 3 MiB JSONL shards and `rift-shard-set-v1` aggregate provenance so the helpers stay below the fixed 4 MiB RiftFS text bridge instead of raising that native boundary. Normal `riftpp` is a separate Core V1 compiler/runtime shell family provided by the compatibility shell: `riftpp check <source.riftpp>`, `compile <source.riftpp> [output.rxe]`, `inspect <source.riftpp|program.rxe>`, `run <source.riftpp>`, `exec <program.rxe>`, `self-test`, `version`, and `help`. `self-test` compiles and executes an embedded Core program and verifies exact output (`Rift++ shell self-test`, `42`, `true`) without reading or writing a file. It targets the standalone `RiftPlusPlusCore`/RiftVM path, not RiftCLI. For Gate 4 sources with `use`, the shell derives one module root from the root source's declared module path and resolves each dependency only as `<module-root>/<module/path>.riftpp`; module identities must match, total graph size is capped at 64 modules, and no ambient directory search/package download occurs. The compiler then links the graph into one closed `.rxe`. `run` and `exec` grant no RiftRT host imports, cap guest output to 64 KiB / 256 writes, cap VM execution to the bootstrap step/stack/call-depth limits, and yield through a real zero-delay timer every 512 VM steps so long-running shell execution returns to the UI/macrotask queue; `compile` writes only the requested `.rxe` through ordinary RiftFS authority. The entire `riftpp` family is rejected inside atomic `batch` because `compile` can write an output file and the batch planner does not model that target. Native `rift-cli` is a separate **experimental, process-local, off-by-default** brain/swarm scaffold documented in `../experimental-cli/README.md`; it adds no MCP tool, does not persist its enable state, and V1 planning cannot execute mutations. Its `riftpp` family compiles/previews Rift++ V0, while `ir help|compile|validate|inspect` exposes the new language-independent `rift.ir/1` swarm-core foundation. Rift IR remains inspect-only: there is no `run` command, backend/tool/Local-Agent invocation is disabled, and mutation remains false. After explicit enable, its separate `tokenizer status|self-test|train-a|train-b|train-a2|train-b2|train-status|train-cancel` lane uses fixed native Kotlin tasks only: V1 and V2 have separate exact corpus/config/output paths, pinned candidate config hashes, V2 enforces a 24-byte learned-token ceiling during ranking, and all candidates share one cancellable process-local background job, optimized-batch parity checks, exact corpus provenance with source-set mutation rejection, streaming status metadata, recoverable staged artifact/manifest publication, no generic Python/process execution, and no caller-selected script or destination.

## Drive-path compatibility

RiftShell is the display-path authority, but subsystem identity checks use the shared `RiftOSCore.path.isAbsolute()` / `canonical()` contract. A bare `D:/...` or `C:/...` argument must never be joined onto the current directory. `rift repo`, `rift build`, RiftGit, RiftVault, RiftMemory, Dev Lab and RiftLLM bridge commands consume that same rule instead of inventing their own absolute-path parser.

Atomic batch preflight must model the same cwd transitions as real execution: `home` enters `/D:/Users/Default`, `workspace cd` enters `/D:/Workspace`, while bare `cd` with no argument keeps its legacy `/home` behavior. Root protection checks both drive aliases and canonical backing roots before a rollback plan is accepted.

## Atomic batch engine

`riftshell-batch.js` tokenizes/splits semicolon commands, preflights commands and permissions, computes mutation targets, captures backups, executes sequentially and restores targets if a command fails. `--dry-run` validates without committing. Preflight models planned filesystem state, including deferred/unknown descendants created by an `unzip`, so later commands in the same batch can address extracted paths without weakening runtime rollback. Non-reversible/remote behavior should not be hidden inside a supposedly atomic local batch.

## MCP bridge

`RiftMcpRuntime` owns one process-wide `RiftNativeShell`; `RiftToolHost` always targets that executor, so the MCP shell control plane remains present even if the trusted compatibility renderer disappears. `MainActivity` may attach one identity-checked `RiftShellBridge` as a temporary fallback while the compatibility shell WebView exists. Native commands never enter that WebView. Unsupported native commands may delegate to the fallback; if the renderer is gone, they fail closed with an explicit compatibility-unavailable error while native core commands keep working. The guest RiftBrowser/ChatGPT page never receives shell authority. The compatibility bridge keeps its bounded timeout rules and never exposes Android/Linux `/system/bin/sh`.

## Critical invariants

Compatibility RPC now requires an explicit acceptance acknowledgement. Missing handlers fail immediately; a native scheduled deadline starts before the UI dispatch, and expired queued requests are rejected before execution. The trusted JavaScript shim retains up to eight running/completed records and exposes `poll(id)` so native code can recover a completion when WebMessage reply delivery fails. Delivery errors are logged rather than swallowed. Pending native callbacks are removed exactly once, and their deadline tasks are cancelled on completion/close.

A deadline ends waiting, not side effects. An identical command and cwd cannot start again while the original promise is unresolved, even after its MCP waiter expires. Distinct commands remain allowed within the eight-record bound, including the nested `riftos-agent devlab` -> `devlab rpc` route. This is not a global filesystem transaction lock, cross-process deduplication, or cancellation of a Git/native action. Renderer loss can still leave an unknown outcome; callers must inspect state before retrying. Native command migration remains incremental.

`RiftToolHost` records shell command family (arguments omitted), elapsed milliseconds, and the returned error; `RiftMcpActivity` displays the duration. Payload arguments are deliberately excluded because shell calls can include source content or credentials. Bridge failures carry request IDs where outcomes are unknown. Regression coverage: `scripts/test-rift-shell-bridge.mjs` exercises acceptance, duplicate suppression, expired dispatch, lost replies, synchronous/asynchronous errors, bounded state and nested commands.

- Shell path resolution must not accidentally reinterpret absolute paths as cwd-relative.
- Batch write classification/preflight occurs before executing mutations.
- Planned `unzip` destinations expose deferred descendants during preflight; file-vs-directory truth for those unknown descendants is resolved by real execution, and any mismatch still rolls the whole batch back.
- Rollback captures every mutation target that a supported batch command can touch.
- Shell MCP bridge requires explicit local write permission and must not become raw Android shell access.
- MCP's primary shell executor is process-owned native Kotlin and must not depend on Activity/WebView lifetime. `MainActivity` may attach exactly one identity-checked compatibility fallback; renderer loss detaches only that fallback and must leave native commands operational.
- Git remote actions are handled by RiftGit's own high-level command semantics, not faked as locally reversible file operations.
- Live `vortex` / `vortex-agent` / `riftos-agent` / `riftllm-agent` operations, experimental `rift-cli`, Core `riftpp`, `chat` bundle creation and the `rift` local-platform family are explicitly listed as non-reversible and rejected by atomic batch preflight; repo/vault/build/memory operations use their own transaction/durability semantics and cannot truthfully participate in the batch engine's ordinary RiftFS rollback contract.

## Failure signatures

- Native MCP commands fail when the trusted shell renderer is gone -> `RiftMcpRuntime`/`RiftNativeShell` process ownership regressed.
- Native commands work but a not-yet-ported command fails after renderer loss -> expected compatibility fallback boundary; port that command family rather than re-coupling MCP to WebView.
- Terminal command works manually but its fallback MCP path fails -> compatibility bridge registration/permissions/correlation.
- Native Terminal loses cwd/output or native commands fail while MCP stays healthy -> inspect `RiftNativeSystemApps` Terminal state/callback handling before changing the shell executor. The native Task Manager is a WindowRecord view, not a mirror of the legacy JS `ProcessTable`.
- Batch partially changes files after failure -> mutation target/preflight/rollback bug.
- Relative path acts in wrong directory -> shell state/resolvePath.
- Git command parsing wrong -> handoff between `runShell` and RiftGit.

## Fix map

Individual shell command/parser -> `riftos.js`.
Atomic batch logic -> `riftshell-batch.js`.
Remote Git behavior -> `riftgit.js`.
Native MCP shell core -> `RiftNativeShell.kt` + `RiftMcpRuntime.kt`.
Temporary compatibility fallback -> `RiftShellBridge.kt`, `MainActivity`'s trusted-shell result route, and `RiftShellMcpNative` in `riftos.js`.

## Validation

Run `scripts/test-rift-shell-batch.mjs`, `scripts/test-rift-shell-git.mjs` and `scripts/test-rift-local-platform.mjs`. Test cwd changes, quoted arguments, failure rollback, dry-run, permission denial and MCP shell execution after any shell protocol change. On Android, also open/close the native Terminal repeatedly, exercise native and compatibility-fallback commands, confirm cwd transitions, and verify native shell/MCP process uptime survives Task Manager and Activity/window lifecycle changes.
