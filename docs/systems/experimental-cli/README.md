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

`rift-cli tokenizer status|self-test|train-a|train-b` is also available only after the same explicit process-local enable. This lane is not a Python launcher: Android has no project-owned Python runtime here and the CLI continues to forbid `ProcessBuilder`/`Runtime.exec`. The Kotlin task mirrors `rift-batch-bpe-v1`, pins the exact A/B config file hashes and canonical config hashes, strict-decodes UTF-8, reads only `RiftLLM/tokenizer/private/build/train.jsonl`, and writes only the two fixed files beneath ignored `RiftLLM/tokenizer/output/`. `status` also computes the hard structural token budget implied by emitting 32,504 merges: every accepted merge must reduce at least one token and every non-empty sample must retain at least one token. Training fails before the expensive loop when even that optimistic lower bound is impossible; the real pair-diversity requirement is stricter.

## Brain / swarm boundary

The role graph models a development team, but roles are logical coordination contexts rather than separate always-running models. The intended future backend can let one model serve multiple specialist contexts or can dispatch to multiple backends without changing the CLI command/execution layer.

V1 `rift-cli plan <goal>` emits a structured task graph only. It does not call Git, Workspace, builds, filesystem mutation, Android actions or the Local Agent. `modelBackendConnected=false` and `autoMutation=false` are explicit status fields and must remain truthful.

## Invariants

- Legacy Local Agent routing is the default on every process start.
- Enabling requires an explicit manual confirmation phrase and is never persisted.
- No new MCP tool is added; the current MCP catalog stays stable.
- No relay protocol change is required.
- The experiment must never expose raw Android/Linux shell execution, arbitrary package control, ADB, `ProcessBuilder`, or `Runtime.exec`.
- Existing `RiftOsLocalAgent` package scope, password restrictions, semantic ambiguity checks, gesture bounds and user-granted Accessibility boundary remain authoritative.
- Planning cannot execute or mutate anything in V1. Tokenizer tasks are a separate explicit command family and cannot be reached through `plan`.
- Tokenizer tasks require the process-local experimental enable, use fixed paths/config hashes, and may write only the pinned ignored tokenizer outputs.
- No generic Python, `ProcessBuilder`, `Runtime.exec`, arbitrary script path or arbitrary output path may be introduced.
- Atomic `batch` rejects `rift-cli`.

## Failure signatures

- RiftOS starts in experimental mode without a manual command -> process-local default/reset invariant regressed.
- MCP tool count changes after this subsystem changes -> the experiment leaked above the Local Agent/shell boundary.
- `rift-cli plan` changes files, Git state, UI, builds or device state -> planning/execution separation regressed.
- `rift-cli tokenizer ...` works while the experimental switch is disabled -> manual gate regressed.
- tokenizer task accepts a caller-selected script/config/output path -> fixed-task confinement regressed.
- tokenizer status says the current corpus can fill 32K when its byte-token budget is below the structural merge-count lower bound -> feasibility guard regressed.
- Experimental mode can target another package or bypass Accessibility restrictions -> Local Agent authority was widened.
- Restart preserves the experimental enable state -> a persistence path was accidentally introduced.
- Legacy `riftos-agent` behavior differs while RiftCLI is disabled -> router is no longer a transparent legacy path.

## Fix map

Manual mode, team graph, planning scaffold, tokenizer gate or router behavior -> `RiftExperimentalCli.kt`.
Tokenizer V1 fixed-path training/status/self-test implementation -> `RiftTextEncoderTaskRunner.kt`.
Native `rift-cli` parsing/visibility -> `RiftNativeShell.kt`.
Native `riftos.agent` entry seam -> `RiftNativeDispatcher.kt` only; do not change MCP/relay/tool schemas to repair a CLI issue.
Local UI action semantics/authority -> `RiftVortexLocalAgent.kt`; do not duplicate those rules in RiftCLI.
Batch rejection -> `src/riftshell-batch.js`.

## Validation

Run `npm run check`. Source validation must prove that the subsystem defaults to legacy mode, uses a process-local volatile switch, exposes no new MCP tool, keeps the dispatcher seam to one router, delegates to the existing Local Agent, contains no raw process/shell APIs, labels itself experimental, and is rejected by atomic batch. `scripts/test-rift-text-encoder-task.mjs` additionally locks the 32K artifact constants, exact candidate/config hashes, fixed paths, structural feasibility guard and absence of generic process/Python execution. Any Kotlin change also requires the normal Android APK build before promotion.

Manual acceptance while the experiment is still unpromoted should cover `rift-cli status`, `team`, `architecture`, explicit enable/disable, non-mutating `plan`, then `rift-cli tokenizer self-test` and `status`. Full `train-a`/`train-b` should run only after status proves the corpus satisfies the lower-bound budget; actual trainer exhaustion can still require more pair diversity beyond that mathematical minimum.
