# RiftCLI Experimental Brain / Development Swarm

> **EXPERIMENTAL — OFF BY DEFAULT — NOT PRODUCTION.** This subsystem must not be used automatically. It exists only behind an explicit manual process-local switch until the project owner promotes it.

## Purpose

RiftCLI is a native experimental seam for evolving RiftOS from a deterministic Local Agent into a brain-directed development CLI without changing the stable MCP/relay/tool surface. The intended future shape is a lead brain coordinating logical specialist agents (architecture, research, C++/native, Android/Kotlin, JavaScript/shell, debug, build/CI, tests, security, performance, documentation and independent review) over deterministic RiftOS command/tool authorities.

V1 is deliberately conservative. The model backend is **not connected**. Its brain is only a rule-based planning scaffold, planning never executes mutations, and experimental Local Agent routing is a compatibility pass-through to the existing fixed-scope `RiftOsLocalAgent`. A separate manually invoked tokenizer task lane can perform only the pinned RiftTokenizer V1 development operations described below; it does not turn planning into execution and it does not expose a general process runner.

## Source ownership

- `android/app/src/main/java/com/riftos/app/RiftExperimentalCli.kt` — process-local manual gate, role catalog, planning scaffold, tokenizer command gate and the one router directly above `RiftOsLocalAgent`.
- `android/app/src/main/java/com/riftos/app/RiftTextEncoderTaskRunner.kt` — fixed native Kotlin RiftTokenizer V1 task executor. It reads the exact validated RiftCorpus training split plus pinned A/B configs and writes only the two ignored tokenizer artifacts/manifests.
- `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt` — exposes the native `rift-cli` command family without creating a new MCP tool.
- `android/app/src/main/java/com/riftos/app/RiftNativeDispatcher.kt` — sends only `riftos.agent` through `RiftAgentRouter`; other native routes are unchanged.
- `src/riftshell-batch.js` — rejects `rift-cli` inside atomic filesystem batches because mode changes/planning are not filesystem rollback operations.

## Manual gate and lifecycle

The default mode is `legacy`. The enable state is intentionally memory-only and is never persisted. Every RiftOS process start therefore returns to legacy routing even if the experimental mode was enabled previously.

Inspection commands are safe while disabled: `rift-cli status`, `rift-cli team`, `rift-cli architecture`. Planning remains disabled until the exact manual command `rift-cli enable CONFIRM-EXPERIMENTAL` is entered. `rift-cli disable` immediately returns routing to legacy mode. There is no auto-enable path, boot flag, remote setting, MCP setting, or remembered preference.

When experimental mode is enabled, `riftos.agent` calls pass through `RiftAgentRouter` and the experimental CLI records a process-local role classification before delegating the original request to `RiftOsLocalAgent.execute(...)`. V1 does not alter the request, add package targets or add permissions.

`rift-cli tokenizer status|self-test|train-a|train-b|train-a2|train-b2|train-status|train-cancel` is also available only after the same explicit process-local enable. This lane is not a Python launcher: Android has no project-owned Python runtime here and the CLI continues to forbid `ProcessBuilder`/`Runtime.exec`. The Kotlin task mirrors `rift-batch-bpe-v1`, pins the exact A/B config file hashes and canonical config hashes, strict-decodes UTF-8, and reads only the fixed RiftCorpus training source: scalable `RiftLLM/tokenizer/private/build/train/part-*.jsonl` shards or the legacy `train.jsonl` fallback, never both. V1 A/B remain fixed to `tokenizer/private/build/train` and their historical output artifacts; V2 A2/B2 are fixed to `tokenizer/private/build-v2/train`, separate V2 artifacts, and a 24-byte maximum learned-token expansion enforced during ranking. It writes only the four fixed files beneath ignored `RiftLLM/tokenizer/output/`. All `train-*` actions start one process-local background job and return immediately; `train-status` reports bounded progress and `train-cancel` requests cancellation. Disabling RiftCLI also cancels any active tokenizer job. The optimized native batch applicator preserves the original ranked 64-merge semantics, including examined-but-destroyed pairs, while replacing repeated full-corpus per-merge scans with bounded priority/overlap passes. `self-test` compares the optimized batch result with the original sequential semantics on overlapping merges. Training hashes the exact bytes decoded into token sequences. Legacy input uses direct file SHA-256; scalable input uses `rift-shard-set-v1`, SHA-256 over sorted `<name>\t<byte_count>\t<sha256>\n` descriptors for every fixed training shard. The entire source set is re-enumerated/re-hashed before publication, so modifying, adding, removing or renaming a shard rejects publication rather than producing misleading provenance. Artifact and manifest are fsynced to hidden staging files, verified as a pair, then promoted through an atomic-marker + backup transaction; `status` and new training starts recover interrupted promotion before trusting outputs. `status` itself uses a strict streaming metadata/digest pass and never allocates the corpus token arrays just to report counts or the structural 32,504-merge budget.

## Rift++ V0 language / Swarm IR

Rift++ V0 is now the declarative language layer for the experimental swarm design. The native compiler is `RiftPlusPlusV0.kt`; the full language contract is [`RIFT_PLUS_PLUS_V0.md`](RIFT_PLUS_PLUS_V0.md) and a complete sample lives at `examples/riftpp/riftos-dev-team.riftpp`.

V0 recognizes `backend`, `brain`, `agent`, `swarm`, and `task` declarations. It validates references, bounded context/retry/reviewer limits, explicit capability allow/deny lists, read-only `review`/`security` roles and acyclic swarm flow. Successful compilation emits `rift.swarm-ir/0` with a source SHA-256 and `executable=false`. Scripts can only be read from RiftFS `workspace/` and V0 exposes no import, loop, arbitrary expression, shell escape, write primitive or generic process runner.

`RiftBrainBackend` is the native future-facing backend interface and `RiftSwarmCoordinatorV0` is a preview-only coordinator. Preview resolves a task, swarm, lead backend and deterministic specialist assignment order but never invokes `RiftBrainBackend.respond(...)`, tools, Local Agent, Git, build or workspace mutation. The interface exists now so future local/remote/RiftLLM brain implementations can be hot-swapped without changing the Rift++ language or Swarm IR contract.

Commands are `rift-cli riftpp help|sample|validate|compile|preview`. `help`/`sample` are descriptive; validate/compile/preview require the same explicit process-local experimental enable as the rest of the CLI.

## Brain / swarm boundary

The role graph models a development team, but roles are logical coordination contexts rather than separate always-running models. The intended future backend can let one model serve multiple specialist contexts or can dispatch to multiple backends without changing the CLI command/execution layer.

V1 `rift-cli plan <goal>` emits the original built-in structured task graph only. Rift++ V0 is the first external declarative graph format, but it is still compile/preview-only. Neither path calls Git, Workspace, builds, filesystem mutation, Android actions or the Local Agent. `modelBackendConnected=false` and `autoMutation=false` remain truthful.

## Invariants

- Legacy Local Agent routing is the default on every process start.
- Enabling requires an explicit manual confirmation phrase and is never persisted.
- No new MCP tool is added; the current MCP catalog stays stable.
- No relay protocol change is required.
- The experiment must never expose raw Android/Linux shell execution, arbitrary package control, ADB, `ProcessBuilder`, or `Runtime.exec`.
- Existing `RiftOsLocalAgent` package scope, password restrictions, semantic ambiguity checks, gesture bounds and user-granted Accessibility boundary remain authoritative.
- Planning cannot execute or mutate anything in V1. Rift++ V0 compile/preview is also non-executable and cannot invoke a brain backend or tool. Tokenizer tasks are a separate explicit command family and cannot be reached through `plan` or Rift++.
- Rift++ source is confined to `workspace/`, bounded to 128 KiB, finite grammar/capabilities only, and compiles to `rift.swarm-ir/0` with `executable=false`.
- `review` and `security` Rift++ roles are compile-time read-only; cyclic flow graphs, unknown references/capabilities, permission overlap and unmet task-role requirements are rejected.
- Tokenizer tasks require the process-local experimental enable, use fixed paths/config hashes, and may write only the pinned ignored tokenizer outputs; at most one background training job may run, and disabling the CLI requests its cancellation.
- Training provenance is the exact source digest: `file-sha256` for the legacy single file or `rift-shard-set-v1` for the ordered scalable shard set; publication fails if source membership or bytes no longer match.
- Artifact/manifest publication is recoverable as one logical transaction: staged pair verification, atomic commit marker, previous-pair backups, pair validation, and marker-last cleanup.
- Tokenizer `status` may recover an interrupted output transaction but must inspect training metadata by streaming; it must not materialize training token arrays.
- No generic Python, `ProcessBuilder`, `Runtime.exec`, arbitrary script path or arbitrary output path may be introduced.
- Atomic `batch` rejects `rift-cli`.

## Failure signatures

- RiftOS starts in experimental mode without a manual command -> process-local default/reset invariant regressed.
- MCP tool count changes after this subsystem changes -> the experiment leaked above the Local Agent/shell boundary.
- `rift-cli plan` changes files, Git state, UI, builds or device state -> planning/execution separation regressed.
- Rift++ compile/preview invokes a backend, tool, Local Agent, Git/build action or writes a workspace file -> V0 non-execution boundary regressed.
- Rift++ accepts traversal/outside-workspace scripts, generic code execution, cyclic swarm flow, unknown capabilities or mutation permissions on `review`/`security` -> compiler confinement/policy regressed.
- `rift-cli tokenizer ...` works while the experimental switch is disabled -> manual gate regressed.
- tokenizer task accepts a caller-selected script/config/output path -> fixed-task confinement regressed.
- any `train-a` / `train-b` / `train-a2` / `train-b2` action blocks the shell/relay until the full 32K run finishes instead of returning a background job -> asynchronous execution regressed.
- A2/B2 trains from V1 `build/train`, accepts a learned token over 24 bytes, or uses an unpinned V2 config -> V2 generalization boundary regressed.
- optimized batch self-test disagrees with the sequential reference semantics -> training parity regressed; do not trust produced artifacts.
- disabling RiftCLI leaves tokenizer training active -> manual gate/cancellation authority regressed.
- published artifact provenance differs from the exact bytes loaded for training, or a corpus mutation during the run is silently accepted -> provenance gate regressed.
- a crash can leave a final artifact without its matching validated manifest, or an interrupted commit cannot restore/retain a complete pair -> output transaction regressed.
- tokenizer `status` calls the full token-array loader instead of the streaming metadata scanner -> lightweight status regressed.
- tokenizer status says the current corpus can fill 32K when its byte-token budget is below the structural merge-count lower bound -> feasibility guard regressed.
- Experimental mode can target another package or bypass Accessibility restrictions -> Local Agent authority was widened.
- Restart preserves the experimental enable state -> a persistence path was accidentally introduced.
- Legacy `riftos-agent` behavior differs while RiftCLI is disabled -> router is no longer a transparent legacy path.

## Fix map

Manual mode, team graph, planning scaffold, Rift++ command gate, tokenizer gate or router behavior -> `RiftExperimentalCli.kt`.
Rift++ V0 lexer/parser/semantic compiler/Swarm IR -> `RiftPlusPlusV0.kt` + `RIFT_PLUS_PLUS_V0.md`.
Brain backend contract and preview coordinator -> `RiftSwarmCoordinatorV0.kt`.
Tokenizer V1/V2 fixed-path training/status/self-test implementation -> `RiftTextEncoderTaskRunner.kt`.
Native `rift-cli` parsing/visibility -> `RiftNativeShell.kt`.
Native `riftos.agent` entry seam -> `RiftNativeDispatcher.kt` only; do not change MCP/relay/tool schemas to repair a CLI issue.
Local UI action semantics/authority -> `RiftVortexLocalAgent.kt`; do not duplicate those rules in RiftCLI.
Batch rejection -> `src/riftshell-batch.js`.

## Validation

Run `npm run check`. Source validation must prove that the subsystem defaults to legacy mode, uses a process-local volatile switch, exposes no new MCP tool, keeps the dispatcher seam to one router, delegates to the existing Local Agent, contains no raw process/shell APIs, labels itself experimental, and is rejected by atomic batch. `scripts/test-rift-plus-plus-v0.mjs` locks the V0 non-executable Swarm IR schema, workspace-only source confinement, finite grammar/capability vocabulary, read-only reviewer/security policy, acyclic flow/schedule behavior, BrainBackend preview-only boundary, sample program and absence of a new MCP tool. `scripts/test-rift-text-encoder-task.mjs` additionally locks the 32K artifact constants, exact candidate/config hashes, fixed paths, structural feasibility guard, asynchronous job/cancel surface, optimized-batch parity hooks, exact-input provenance, streaming status, staged/synced transactional pair publication + recovery, and absence of generic process/Python execution. Any Kotlin change also requires the normal Android APK build before promotion.

Manual acceptance while the experiment is still unpromoted should cover `rift-cli status`, `team`, `architecture`, explicit enable/disable, non-mutating `plan`, then `rift-cli tokenizer self-test` and `status`. Full V1 `train-a`/`train-b` or V2 `train-a2`/`train-b2` should run only after the intended corpus source satisfies the lower-bound budget; poll with `train-status`, verify the shell remains responsive, and test `train-cancel`/CLI disable before trusting long runs. Actual trainer exhaustion can still require more pair diversity beyond that mathematical minimum.
