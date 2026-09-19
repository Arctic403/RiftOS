# Experimental RiftCLI / Rift++ V0 / Rift IR

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-18.**

## Purpose

Experimental RiftCLI is a manually enabled, process-local development surface layered above the stable native RiftShell and existing RiftOS Local Agent.

It currently provides:
- a non-mutating planning/team scaffold;
- an experimental routing seam that classifies Local Agent operations but preserves the same Local Agent authority;
- Rift++ V0 declarative swarm compilation;
- Rift IR V1 inspect-only lowering/validation;
- fixed-path native tokenizer development tasks;
- the manual OBSERVE-only CLI→AI patch lifecycle with candidate-bound verification/evaluation evidence.

It is not a replacement for production RiftShell, MCP ToolHost, Rift++ Core, RiftVM or RiftLLM.

## Source ownership

Primary router/shell surface:
- RiftExperimentalCli.kt

Patch lifecycle/evidence:
- RiftCliPatchLifecycleV1.kt
- RiftResearchLedgerV1.kt

V0 language and preview:
- RiftPlusPlusV0.kt
- RiftSwarmCoordinatorV0.kt

IR:
- RiftIrV1.kt
- RiftIrCliV1.kt

Tokenizer tasks:
- RiftTextEncoderTaskRunner.kt

Live shell entry:
- RiftNativeShell.kt
- RiftNativeShellServices.kt

Focused specs/tests:
- docs/systems/experimental-cli/RIFT_PLUS_PLUS_V0.md
- docs/systems/experimental-cli/RIFT_IR_V1.md
- scripts/test-rift-plus-plus-v0.mjs
- scripts/test-rift-ir-v1.mjs
- scripts/test-rift-text-encoder-task.mjs
- scripts/test-rift-cli-patch-lifecycle-v1.mjs
- docs/systems/experimental-cli/PATCH_LIFECYCLE_V1.md
- docs/systems/experimental-cli/PATCH8_DOCUMENTATION_PARITY.md

## Packaging

All current Kotlin experimental sources are included by Android source/build validation.

There is no separate APK/component/service for RiftCLI.

The command surface is reached through native RiftShell:

rift-cli ...

No rift_cli / RiftCLI MCP tool family exists.

MCP can only reach it indirectly through the already-audited permission-gated rift_shell_exec command.

## Default/off state

The in-process mode variable initializes to LEGACY.

No SharedPreferences or persistent flag stores enablement.

Enable requires exactly:

rift-cli enable CONFIRM-EXPERIMENTAL

Enablement lasts only for the current Android process.

Process restart reconstructs the object in LEGACY mode.

Disable:
- restores LEGACY mode;
- clears last route metadata;
- requests cancellation of any active experimental tokenizer training job.

## Commands available while disabled

Descriptive/read-only scaffolding remains available:
- help
- status
- team
- architecture
- enable
- disable
- riftpp help
- riftpp sample
- ir help

Planning, V0 compile/preview, IR compile/validate/inspect, tokenizer actions and lifecycle session/evidence/evaluation actions require explicit enablement. Lifecycle `help` and `contract` remain descriptive/read-only while disabled.

## Planner

rift-cli plan <goal> is available only while enabled.

It:
- uses a fixed 13-role catalog;
- performs keyword-based role selection;
- emits a deterministic staged task graph;
- sets executionRequested=false;
- sets autoMutation=false;
- does not call shell, Git, workspace writes, builds, Local Agent or model backends.

The current brain backend is a rule-planner scaffold.

No model backend is connected.

## Patch lifecycle V1

`rift-cli lifecycle` implements the manual repository work path documented in [`PATCH_LIFECYCLE_V1.md`](PATCH_LIFECYCLE_V1.md).

It is OBSERVE-only and deliberately reuses existing authority. Its flow is acquire/sync → base-bound understanding evidence → research → document intent → patch → document audit → code/security/dependency/test/build/E2E audit → freeze → independent AI evaluation → local hash verification.

The lifecycle stores session/evidence state outside Workspace, derives final impact from Patch Manifest V1 + Project Intelligence V2, and never lets an evaluator response promote `trustedCheckpoint` or publish source.

Patch 8 adds `rift-cli lifecycle documentation-plan <sessionId>` and `RiftDocumentationParityV1`. Documentation evidence must now be bound to the exact parity plan and review every deterministic source-owner/governance surface; final evaluation recomputes the plan and rejects stale parity evidence.

No direct model backend is connected. The AI-facing work/evaluation packets return through the existing shell/MCP response path.

## Local Agent router

RiftAgentRouter is the one experimental seam above the existing RiftOS Local Agent.

During this audit a wiring gap was fixed: the router previously existed but had zero callers.

Native shell service riftos-agent now calls:

RiftAgentRouter.execute(...)

Behavior:
- LEGACY -> directly delegates to RiftOsLocalAgent;
- EXPERIMENTAL -> classifies the operation to a logical worker, records lastRoute, then delegates to the same RiftOsLocalAgent.

It does not widen Local Agent operations, package authority, accessibility authority or filesystem authority.

No swarm worker executes independently yet.

## Rift++ V0

Rift++ V0 is not the production Rift++ language frontend.

V0:
- begins with riftpp 0;
- is declarative;
- compiles to rift.swarm-ir/0;
- always marks executable=false;
- describes backend/brain/agent/swarm/task graphs only.

Source is confined to RiftFS workspace/ and .riftpp/.rift++ files.

Key bounds:
- source <=128 KiB;
- declarations <=64;
- list values <=64;
- actor context <=65536 tokens;
- retries <=5;
- reviewers <=8;
- swarm workers <=32;
- task goal <=4096 characters.

Capability vocabulary is finite and declarative.

Review/security roles cannot allow mutating capabilities.

Tools must be explicitly allowed and may not also be denied.

Graph flow is validated and topologically ordered.

There is no ProcessBuilder, Runtime.exec, socket endpoint, shell execution or arbitrary backend endpoint in V0.

## V0 preview

RiftSwarmCoordinatorV0.preview consumes non-executable Swarm IR and returns a deterministic assignment preview.

RiftBrainBackend exists as a future interface, but:
- there is no registered implementation;
- preview never calls respond();
- backendConnected=false;
- backendInvoked=false;
- execution=false.

Preview does not invoke tools or Local Agent.

## Rift IR V1

Rift IR schema:
- rift.ir/1
- version 1
- profile swarm-core
- source schema rift.swarm-ir/0

RiftIrV1 independently revalidates the V0 output rather than trusting frontend validation flags.

It rechecks:
- identities/references;
- role/memory/capability policy;
- graph membership, duplicate edges and cycles;
- deterministic canonical schedule;
- reviewer counts;
- task requirements;
- resource/context totals;
- declared capability union.

Current bounds include:
- context <=65536 per actor;
- retries <=5;
- reviewers <=8;
- workers <=32.

Execution policy is hard-coded inspect-only:
- executable=false;
- mutationAllowed=false;
- rawShellAllowed=false;
- arbitraryProcessAllowed=false;
- backendInvocation=false;
- toolInvocation=false;
- localAgentInvocation=false;
- defaultConcurrency=1.

RiftIrCliV1 only supports:
- compile
- validate
- inspect

There is no run command.

## Tokenizer development runner

RiftTextEncoderTaskRunner is a fixed-purpose native Kotlin tokenizer trainer.

It is not:
- a Python runner;
- a process runner;
- a caller-selected path executor;
- a generic RiftLLM training engine.

Project root is fixed:

<filesDir>/riftfs/workspace/RiftLLM

All config/training/output paths are source-controlled constants beneath that root and canonicalized.

## Tokenizer candidates

Current fixed candidates:
- train-a -> rift-token-a-frequency-v1
- train-b -> rift-token-b-balanced-v1
- train-a2 -> rift-token-a-frequency-v2
- train-b2 -> rift-token-b-balanced-v2

Each candidate pins:
- config path;
- training path;
- output path;
- exact config-file SHA-256;
- canonical trainer-config SHA-256;
- score mode;
- maximum token width.

Training source:
- <=64 MiB total;
- <=128 shards;
- <=3 MiB/shard;
- safe .jsonl shard names;
- each sample <=16 KiB UTF-8;
- required categories prose/code/non_ascii.

Tokenizer contract:
- vocabulary 32768;
- 256 byte tokens;
- 32504 learned merges;
- eight fixed special literals;
- V2 max token bytes 24.

## Frozen B2 protection

The stable RiftTrainDataTaskRunner declares rift-token-b-balanced-v2 as the frozen B2 tokenizer input and pins its artifact SHA.

Therefore Experimental RiftCLI must not regenerate or overwrite that promoted artifact.

This audit added an explicit frozen candidate set containing:

rift-token-b-balanced-v2

Any attempt to start train-b2 now fails before recovery, output creation or training begins.

Tokenizer status still reports that candidate and marks frozen=true.

The existing frozen B2 artifact is not modified by this guard.

## Tokenizer job lifecycle

Training uses one daemon single-thread executor.

Only one queued/running/cancelling training job may exist at a time.

Job states include queued, running, cancelling, cancelled, complete and failed.

Cancellation uses AtomicBoolean plus thread interruption checks through the training algorithm.

Disabling Experimental RiftCLI requests cancellation.

## Tokenizer status is read-only

Previously tokenizer status called recoverOutputPair() for every candidate. Merely inspecting status could therefore delete stale stages/backups or restore files.

This audit removed that mutation.

Status now:
- scans bounded training metadata;
- validates existing artifact/manifest pairs;
- inspects transaction marker/stage/backup presence;
- uses outputPaths(..., createParent=false);
- reports outputState and recoveryPerformed=false;
- does not call recoverOutputPair.

Actual recovery remains on explicit start-training/commit transaction paths.

## Tokenizer output transaction

For non-frozen trainable candidates, explicit training:
1. recovers prior interrupted output transaction when needed;
2. validates pinned config file SHA and config fields;
3. reads bounded fixed training source;
4. trains through bounded batches with cancellation checks;
5. re-hashes training source after training;
6. stages artifact and manifest;
7. validates the staged pair;
8. rechecks source hash before commit;
9. writes a transaction marker;
10. atomically publishes artifact and manifest with backup recovery.

Existing invalid output pairs are refused rather than silently overwritten.

Recovery logic distinguishes completed, interrupted and partial first commits.

No direct writeText output publication is used.

## Stable training separation

RiftTextEncoderTaskRunner belongs only to Experimental RiftCLI tokenizer development.

RiftTrainDataTaskRunner is separate and owns the stable fixed B2 + frozen V2 shard canary-data pipeline.

This audit does not execute, retrain or modify frozen RiftLLM+ Gate 6D.2 artifacts.

## No generic execution

Experimental CLI sources contain no generic:
- ProcessBuilder;
- Runtime.exec;
- Python;
- arbitrary socket/process runner.

Rift++ V0/IR never become executable merely because their capability declarations contain names such as build.run or device.control.

Those are data in an inspect-only graph.

## No MCP expansion

RiftCLI adds zero MCP tools and no relay protocol methods.

Its shell entry inherits rift_shell_exec's existing read+write MCP grant requirement.

## Source fixes in this audit

- wired the previously zero-caller RiftAgentRouter into riftos-agent shell service;
- preserved identical underlying RiftOsLocalAgent authority;
- made tokenizer status genuinely read-only;
- status no longer creates output directories or performs transaction recovery;
- added explicit outputState / recoveryPerformed=false reporting;
- froze rift-token-b-balanced-v2 against Experimental RiftCLI retraining/overwrite;
- updated focused tests to lock router wiring, read-only status and B2 freeze behavior;
- clarified V0 child spec so its no-RiftLLM-mutation rule is not confused with the separate tokenizer command family.

## Critical invariants

- default mode is LEGACY every process start;
- enable is explicit and non-persistent;
- planner never mutates;
- router cannot widen Local Agent authority;
- V0 and Rift IR remain non-executable;
- no BrainBackend invocation;
- no new MCP/relay surface;
- tokenizer paths remain fixed beneath workspace/RiftLLM;
- status remains read-only;
- only one tokenizer training job at a time;
- tokenizer input/output bounds remain explicit;
- B2 remains frozen and cannot be retrained by Experimental RiftCLI;
- no generic native/process execution;
- patch lifecycle remains OBSERVE-only and manual-enable only;
- lifecycle adds zero MCP tools/model backends and cannot publish/promote trust;
- lifecycle acquisition is clean, bounded and revision-bound;
- research precedes design, and post-patch audits are candidate-bound and ordered;
- incomplete/stale/missing-target evidence cannot produce WOULD_ACCEPT.

## Failure signatures

- experimental mode persists across process restart -> enablement regression;
- riftos-agent bypasses RiftAgentRouter -> routing seam regression;
- router calls an authority other than existing RiftOsLocalAgent -> authority widening;
- plan/preview/IR invokes tools or mutates files -> inspection-only regression;
- Rift IR adds run/backend/tool/local-agent execution -> IR boundary regression;
- tokenizer status calls recoverOutputPair or mkdirs output parent -> read-only regression;
- train-b2 starts a job or writes B2 output -> frozen artifact regression;
- caller can choose arbitrary tokenizer project/config/output path -> confinement regression;
- a generic process/Python/socket runner appears -> execution-surface regression;
- a RiftCLI MCP tool is added -> layering regression;
- lifecycle accepts dirty/truncated acquisition -> source-identity regression;
- lifecycle resets operational checkpoint over unrelated Workspace changes -> evidence-reset regression;
- research/design/post-audit ordering is bypassed -> lifecycle regression;
- incomplete or stale evidence becomes complete -> verification regression;
- evaluator response can promote trust/publish -> authority regression.

## Fix map

Enable/plan/router -> RiftExperimentalCli.kt.

Shell router entry -> RiftNativeShellServices.kt.

V0 frontend -> RiftPlusPlusV0.kt.

Preview -> RiftSwarmCoordinatorV0.kt.

IR lowering/validation -> RiftIrV1.kt.

IR CLI -> RiftIrCliV1.kt.

Tokenizer development -> RiftTextEncoderTaskRunner.kt.

AI patch lifecycle/state machine -> RiftCliPatchLifecycleV1.kt.

External research ledger -> RiftResearchLedgerV1.kt.

Stable frozen-B2 canary pipeline -> RiftTrainDataTaskRunner.kt / RiftLLM bridge subsystem.

## Validation

Second source audit must verify:
- process-local default/enable/disable;
- no persistent enable store;
- actual RiftAgentRouter caller;
- same Local Agent delegation in both modes;
- planning autoMutation=false;
- V0 workspace confinement, bounds and non-executable IR;
- no BrainBackend respond call;
- IR schema/profile, policy revalidation and inspect-only execution flags;
- no IR run command;
- tokenizer fixed paths/hashes/bounds;
- read-only status implementation;
- single-job cancellation;
- atomic/recoverable output transaction;
- B2 frozen guard;
- no generic process execution;
- no MCP tool expansion;
- focused tests/specs reflect current behavior;
- lifecycle acquisition/evidence/order/stale-result/evaluator-separation contracts;
- no lifecycle MCP/model/trust-promotion surface.

Builder/device execution remains a separate gate. No tokenizer training job is required or permitted merely to verify this source contract.
