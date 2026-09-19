# RiftLLM Standalone Dev / Training Bridge

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

## Purpose

RiftOS communicates with the standalone RiftLLM APK through one fixed local Android ContentProvider contract plus one fixed-purpose canary training-data controller.

RiftOS does not own RiftLLM model state/private storage, does not expose arbitrary Provider method names, and adds no dedicated MCP tool.

## Source ownership

Primary provider client:
- `RiftLlmDevClient.kt`

Fixed canary-data controller:
- `RiftTrainDataTaskRunner.kt`

Live shell routing:
- `RiftNativeShellServices.kt`
- `RiftNativeShell.kt`

Secure pairing UI:
- native Settings in `RiftNativeWorkspaceApps.kt`

Secret storage:
- `RiftSecretStore.kt`

Package visibility:
- Android manifest query for `com.riftllm.app`.

`RiftTextEncoderTaskRunner.kt` belongs to the Experimental CLI subsystem, not this bridge.

Retained `src/riftllm-bridge.js` is historical/reference implementation and is not the current packaged authority.

## Android provider identity

Target package:
`com.riftllm.app`

Provider authority:
`com.riftllm.app.devlab`

URI:
`content://com.riftllm.app.devlab`

Before pairing, Android package-manager resolution must return that exact provider package.

RiftOS does not accept a caller-selected package, authority or URI.

## Pairing

Pairing token must match exactly 64 hexadecimal characters.

Pair flow:
1. native Settings receives the token in a password field;
2. field is immediately cleared after button submission;
3. `RiftLlmDevClient.pair()` verifies token format;
4. verifies the expected provider is installed/visible;
5. calls fixed provider `status` with the token;
6. only after successful provider response stores the token via `RiftSecretStore`.

The token is not exposed through RiftShell.

`riftllm-agent pair ...` deliberately fails and instructs the user to use native Settings.

During this audit native Settings pairing was implemented because the documented secure route previously did not exist.

Settings also provides:
- unpair;
- refresh/status.

Status reports installed/paired/API reachability but never returns token material.

## Secret storage

Token key:
`riftllm.dev.token`

`RiftSecretStore` encrypts values with Android-Keystore-backed AES-GCM before storing ciphertext in private preferences.

Unpair removes the stored secret.

## Provider request boundary

Provider request JSON is capped at 512 KiB UTF-8.

Provider response JSON is also capped at 512 KiB UTF-8 before JSONTokener parsing.

`ContentResolver.call()` is synchronous and has no platform timeout, so RiftOS isolates Provider RPC behind a capped two-worker, no-queue executor. Each call has a 12-second deadline. If both workers are already occupied by stalled Provider calls, new bridge calls fail fast instead of creating an unbounded thread/queue leak.

The provider receives a Bundle containing:
- fixed token extra;
- JSON request extra.

The client expects one JSON string response and parses it through JSONTokener.

There is no process execution, filesystem path selection or arbitrary Provider method parameter at this transport layer.

## Fixed Provider method catalog

Exactly 26 method names are allowed:

- sync_source
- sync_missing
- load_source
- list_staged
- stage
- delete
- unstage
- reset
- snapshot
- list_snapshots
- get_snapshot
- get_patch
- list_benchmarks
- get_benchmark
- text_encoding_begin
- text_encoding_append
- text_encoding_commit
- text_encoding_start
- text_encoding_status
- train_data_begin
- train_data_append
- train_data_commit
- train_data_status
- train_canary_start
- train_canary_status
- ack_publish

Anything else fails before ContentResolver.call.

Special local-only client operations:
- pair;
- unpair;
- status.

## Native shell routes

Current shell provides:
- status
- unpair
- staged -> provider `list_staged`
- reset -> provider `reset`
- snapshots -> provider `list_snapshots`
- benchmarks -> provider `list_benchmarks`
- text-encoding-status -> provider `text_encoding_status`
- train-data-status
- train-data-build
- train-data-build-status
- train-data-build-cancel
- train-data-upload
- train-data-remote-status
- train-canary-start
- train-canary-status

During this audit stale aliases that called nonexistent Provider methods were corrected.

## Preview / publish

The old retained JavaScript bridge implemented `preview` and `publish` as composite operations:
- fetch RiftLLM patch;
- run a retired Workspace preview/apply-patch engine;
- acknowledge publication back to RiftLLM.

That native Workspace patch publisher does not currently exist.

Therefore native shell `preview` and `publish` now **fail closed** rather than pretending they are direct Provider methods.

A future implementation must introduce a separately audited bounded native patch preview/apply owner before re-enabling them.

## Legacy helpers

Unknown/legacy corpus helpers are unavailable and fail closed.

They must be reintroduced only through explicit bounded native/headless owners.

## Fixed RiftTrainData controller

`RiftTrainDataTaskRunner` accepts no caller-selected:
- project path;
- tokenizer identity;
- training shard directory;
- output path;
- process command.

Fixed project:
`filesDir/riftfs/workspace/RiftLLM`

Fixed artifact/shard/output paths are constants below that project.

This audit only verifies source wiring; it does **not** run or modify the frozen B2/V2 training inputs.

## Frozen input contract

Fixed tokenizer:
- candidate `rift-token-b-balanced-v2`;
- vocab 32768;
- 256 byte tokens;
- 32504 merges;
- max token bytes 24;
- fixed artifact SHA;
- fixed training-corpus SHA;
- fixed trainer-config SHA.

Build requires the exact artifact and exact V2 shard-set hash before encoding.

After encoding, it recomputes the training shard-set hash again and rejects if source changed mid-build.

Frozen provenance/special-token mapping are validated by the parser.

## Training input limits

- sample UTF-8 text <=16 KiB;
- total training source <=64 MiB;
- each shard <=3 MiB;
- maximum shards: 128;
- safe shard filename regex;
- generated pack <=8 MiB.

Shard files are sorted deterministically by name.

Shard-set identity material contains each name, byte count and SHA-256.

## Build job lifecycle

One daemon single-thread executor owns build work.

Only one build may be queued/running/cancelling at a time.

States include:
- queued;
- running;
- cancelling;
- cancelled;
- complete;
- failed.

Cancellation uses an AtomicBoolean and thread interruption checks throughout validation/encoding/pack copying.

During this audit native shell gained `train-data-build-cancel`, exposing the already-existing bounded cancellation operation.

## Output semantics

Output is a private canary token pack and manifest under fixed `workspace/RiftLLM/training/private/canary-v1` paths.

Manifest explicitly records:
- purpose = canary;
- `productionPretrainingEligible=false`;
- architecture id;
- tokenizer provenance;
- training source hash;
- sample/token counts;
- pack size/hash;
- encoding/boundary policy.

Pack/manifest staging uses synced temporary files followed by atomic replacement.

Published pack hash is reverified after replacement.

## Upload

Upload requires a locally valid pack/manifest pair.

Provider flow is fixed:
1. train_data_begin(totalBytes, sha256);
2. Provider must report exact chunk contract 192 KiB;
3. chunks are Base64 encoded and sent with exact offsets;
4. each append must acknowledge the exact cumulative byte count;
5. train_data_commit must return committed=true and the same SHA-256.

No caller-controlled remote method is used.

## Canary start

Canary start requires:
- local pack validates;
- remote train-data status reports available;
- remote SHA equals exact local pack SHA.

Only then is `train_canary_start` called with the pack SHA.

The controller does not mark this pack as production pretraining eligible.

## Experimental text encoder separation

`RiftTextEncoderTaskRunner` is reachable from `RiftExperimentalCli`, not this stable RiftLLM bridge.

Its training/evaluation lifecycle must be audited under Experimental RiftCLI rather than being silently included here.

## No local-agent route

Current RiftVortexLocalAgent source contains no direct RiftLLM bridge command.

RiftLLM bridge operations currently enter through native shell and native Settings pairing only.

## No MCP expansion

There is no `riftllm_*` MCP tool.

Remote/model access, when used, still flows through `rift_shell_exec` and ToolHost grants.

The bridge itself does not widen MCP authority.

## Source fixes in this audit

- corrected shell aliases to real fixed Provider methods;
- `preview/publish` now fail closed instead of calling nonexistent Provider operations;
- implemented the previously missing native Settings pairing/status/unpair surface;
- retained shell rejection of pairing-token arguments;
- exposed existing fixed build cancellation as `train-data-build-cancel`;
- confirmed frozen canary controller remains fixed-input and was not executed.

## Critical invariants

- fixed package/authority/URI;
- exact 64-hex pairing token;
- pairing verifies provider before secret persistence;
- token never accepted through shell arguments;
- exactly 26 provider method names;
- Provider request <=512 KiB;
- Provider response <=512 KiB;
- at most two in-flight Provider IPC workers and 12-second per-call timeout;
- no caller-selected training paths/tokenizer/process;
- frozen tokenizer/shard hashes verified before build;
- training source rehashed after encoding;
- one cancellable build at a time;
- upload uses exact 192 KiB chunk contract and cumulative acknowledgements;
- canary starts only from matching local/remote pack SHA;
- productionPretrainingEligible remains false;
- frozen data is not rerun merely to audit bridge source.

## Failure signatures

- native Settings has no pairing control while shell pair is blocked -> unreachable pairing regression;
- shell `staged` calls `staged` instead of `list_staged` -> alias drift;
- preview/publish invokes arbitrary/nonexistent Provider method -> retired-composite regression;
- caller can supply Provider method/path -> authority regression;
- stalled ContentProvider call permanently consumes Settings/shell worker or spawns unlimited IPC threads -> timeout/isolation regression;
- token appears in status/log/shell output -> secret leak;
- build accepts different tokenizer/shard hash -> frozen-input regression;
- build has no reachable cancellation -> task-lifecycle regression;
- remote append acknowledgement drifts but upload continues -> transfer-integrity regression;
- canary starts with different remote SHA -> canary-integrity regression.

## Fix map

Provider identity/methods/pairing transport -> `RiftLlmDevClient.kt`.

Secure pairing UI -> `RiftNativeWorkspaceApps.kt`.

Shell command mapping -> `RiftNativeShellServices.kt`.

Fixed canary pack build/upload/start -> `RiftTrainDataTaskRunner.kt`.

Experimental encoder/training -> Experimental RiftCLI subsystem.

RiftLLM-side Provider/model behavior -> standalone RiftLLM project, not RiftOS.

## Validation

Second source audit must verify:
- package/authority/manifest visibility;
- exact 26-method catalog;
- 64-hex token and provider-before-save flow;
- Settings pair/unpair/status;
- shell pair rejection and corrected aliases;
- preview/publish fail-closed behavior;
- 512 KiB request + response bounds;
- fixed training paths/SHAs/limits;
- single-job cancellation;
- 192 KiB upload contract and ack checks;
- local/remote canary SHA equality;
- no direct local-agent/MCP expansion.

Installed-device validation is still required for actual ContentProvider visibility, token pairing, process restart persistence, upload and canary lifecycle.
