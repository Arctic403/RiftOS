# RiftLLM Standalone Dev / Training Bridge

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-23.**

## Purpose

RiftOS communicates with the standalone RiftLLM APK through one fixed local Android ContentProvider contract, the frozen V1 canary training-data controller, and a separate fixed-path RiftTrainData V2 production-data candidate/qualification stack.

RiftOS does not own RiftLLM model state/private storage, does not expose arbitrary Provider method names, and adds no dedicated MCP tool.

## Source ownership

Primary provider client:
- `RiftLlmDevClient.kt`

Fixed canary-data controller:
- `RiftTrainDataTaskRunner.kt`

Shared frozen tokenizer runtime:
- `RiftFrozenByteBpeV1.kt`

RiftTrainData V2 candidate owners:
- `RiftTrainDataV2Format.kt`
- `RiftTrainDataV2TaskRunner.kt`
- `RiftB2BottomKDedupV1.kt`
- `RiftB2NearDedupIndexV1.kt`
- `RiftB2ThresholdQualificationV1.kt`
- `RiftB2ThresholdQualificationTask.kt`
- `RiftTrainDataV2AdversarialLab.kt`

Live shell routing:
- `RiftNativeShellServices.kt`
- `RiftNativeShell.kt`

Secure pairing UI:
- native Settings in `RiftNativeWorkspaceApps.kt`

Secret storage:
- `RiftSecretStore.kt`

Package visibility:
- Android manifest query for `com.riftllm.app`.

The former Experimental CLI `RiftTextEncoderTaskRunner.kt` was retired during the Native RiftCLI reset. It was never part of the stable RiftLLM bridge authority.

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
- train-v2-status
- train-v2-build
- train-v2-build-status
- train-v2-build-cancel
- train-v2-dedup-qualify-status
- train-v2-dedup-qualify-start
- train-v2-dedup-qualify-job-status
- train-v2-dedup-qualify-cancel
- train-v2-adversarial-status
- train-v2-adversarial-lab

The `train-v2-*` routes are local fixed-path RiftOS qualification/build owners; they do not widen the RiftLLM Provider method catalog and do not accept caller-selected filesystem paths. During this audit stale aliases that called nonexistent Provider methods were corrected.

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

## RiftTrainData V2 production-data candidate

The V2 lane is separate from the frozen V1 canary path. It does not change the Provider upload/canary contract and it does not make the current corpus production eligible.

Fixed owners:
- `RiftFrozenByteBpeV1` — one runtime authority for the frozen B2 artifact/parser/fast+reference encoders;
- `RiftTrainDataV2Format` — canonical binary pack writer/parser, random-access index and deep validation;
- `RiftTrainDataV2TaskRunner` — fixed-path immutable generation builder/status/cancel owner;
- `RiftB2BottomKDedupV1` — 13-token / bottom-128 deterministic sketch and integer similarity contract;
- `RiftB2NearDedupIndexV1` — disk-backed cross-split comparator with fail-closed work bounds;
- `RiftB2ThresholdQualificationV1` + `RiftB2ThresholdQualificationTask` — labeled threshold evidence only;
- `RiftTrainDataV2AdversarialLab` — current-APK parser corruption proof.

V2 invariants:
- exact frozen architecture/tokenizer identity;
- train/validation/challenge are separate immutable packs;
- repository/source-group isolation determines train-vs-validation assignment;
- challenge material is independently isolated;
- global exact source-id/sample/content dedup uses bounded SQLite state rather than unbounded RAM;
- pack headers, generation descriptor, canonical provenance, final manifest and CURRENT pointer are cross-validated;
- generation files are synced, the staging directory is fsynced, publication is atomic, the generations parent is fsynced, the moved generation is revalidated, then CURRENT is atomically replaced and its parent fsynced;
- all V2 fixed paths reject traversal/symlink substitution;
- each record is bounded to the qualified 2048-token context and source records are never silently truncated.

Near-dedup candidate:
- `rift-b2-bottomk-v1`: 13-token shingles, SHA-256-derived 64-bit fingerprints, bottom-128 unique sketch;
- global cross-split comparison is implemented with a bounded SQLite inverted index;
- successful comparison records candidate-pair count, positive-pair count, maximum score/pair and deterministic histogram;
- the comparator fails closed above 16,000,000 indexed fingerprints or 5,000,000 scored candidate pairs and checks cancellation during hot loops;
- threshold freeze is separate: the fixed `near-dedup-cases.jsonl` qualification lane computes only an admissible integer interval from labeled near-duplicate/distinct evidence;
- threshold evidence is valid only when its input SHA, frozen tokenizer identity and current installed APK SHA-256 still match; `train-v2-status` exposes `thresholdEvidenceExists`, `thresholdEvidenceValid` and `thresholdQualifiedIntervalExists` separately so file presence cannot be mistaken for a passed qualification gate.

Adversarial parser proof:
- `train-v2-adversarial-lab` builds a valid control with the real V2 writer/parser;
- malformed cases cover truncation, trailing bytes, header corruption, nonzero index-reserved bytes and BOS-boundary corruption;
- deep index/BOS cases repair their affected region SHA-256 first so rejection must reach the ABI invariant rather than stop at the checksum layer;
- persisted evidence is accepted only for the same package/version and exact installed APK SHA-256.

Current qualification state:
- `productionPretrainingEligible=false`;
- near-dedup threshold is not frozen and rejection is not yet enabled;
- labeled threshold corpus/evidence is still required;
- installed-device adversarial evidence must be valid for the current APK;
- Hardware Target A streaming/storage evidence remains required;
- V2 must remain fail-closed until every production gate is explicitly frozen.

## Retired Experimental CLI text encoder

The former `RiftTextEncoderTaskRunner` / `RiftExperimentalCli` path was removed during the Native RiftCLI reset. No replacement tokenizer authority was added to this bridge.

Stable RiftLLM bridge behavior remains unchanged; any future native RiftCLI training integration requires its own explicit bridge contract and promotion gate.

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
- extracted one shared frozen B2 runtime and kept the V1 canary lane on fast-vs-reference parity;
- added the separate fixed-path RiftTrainData V2 binary format/builder, exact/group-safe dedup, bounded global near-dedup comparator, threshold qualification task and adversarial parser lab;
- added current-input/current-APK evidence binding for threshold/adversarial qualification and immutable deep-validated generation publication;
- confirmed frozen canary controller remains fixed-input and `productionPretrainingEligible` remains false.

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
- V2 accepts no caller-selected project/input/output path and never reuses V1 canary purpose/eligibility semantics;
- V2 descriptor/provenance/packs/manifest/CURRENT identities are deep-cross-validated before publication;
- V2 exact dedup is global across source/challenge inputs and source groups cannot cross train/validation/challenge roles;
- global near-dedup work is fail-closed at 16,000,000 indexed fingerprints and 5,000,000 candidate pairs;
- threshold/adversarial evidence is rejected when its input/tokenizer/current APK identity is stale;
- productionPretrainingEligible remains false until every V2 policy/threshold/device/parser gate is frozen;
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
- canary starts with different remote SHA -> canary-integrity regression;
- V2 accepts a caller-selected path or symlink-substituted fixed path -> authority/confinement regression;
- V2 CURRENT points at a generation that was not deep-validated after atomic publication -> publication regression;
- V2 pack/descriptor/manifest/provenance identities disagree but status/build still succeeds -> generation-integrity regression;
- threshold/adversarial evidence remains valid after labeled input or installed APK bytes change -> stale-evidence regression;
- near-dedup silently truncates work above its fingerprint/pair ceiling -> qualification-integrity regression;
- V2 reports `productionPretrainingEligible=true` before frozen threshold/policy/device/parser evidence -> production-gate regression.

## Fix map

Provider identity/methods/pairing transport -> `RiftLlmDevClient.kt`.

Secure pairing UI -> `RiftNativeWorkspaceApps.kt`.

Shell command mapping -> `RiftNativeShellServices.kt`.

Fixed canary pack build/upload/start -> `RiftTrainDataTaskRunner.kt`.

Frozen B2 artifact/runtime parity -> `RiftFrozenByteBpeV1.kt`.

RiftTrainData V2 binary ABI + parser/writer -> `RiftTrainDataV2Format.kt`.

V2 fixed-path generation build/status/publication -> `RiftTrainDataV2TaskRunner.kt`.

V2 bottom-k sketches/global comparator -> `RiftB2BottomKDedupV1.kt` + `RiftB2NearDedupIndexV1.kt`.

V2 threshold evidence -> `RiftB2ThresholdQualificationV1.kt` + `RiftB2ThresholdQualificationTask.kt`.

V2 parser corruption proof/current-APK evidence -> `RiftTrainDataV2AdversarialLab.kt`.

Retired Experimental CLI text encoder -> removed; no active owner in RiftOS.

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
- all fixed `train-v2-*` shell routes and absence of caller-selected path parameters;
- shared B2 fast/reference parity and exact frozen artifact identity;
- V2 canonical pack/header/index/record/provenance/descriptor/manifest/CURRENT cross-validation;
- global source-id/sample/content dedup and source-group split isolation;
- bounded global near-dedup comparator, fail-closed work ceilings and cancellation;
- threshold evidence current-input/current-tokenizer/current-APK validation;
- adversarial parser evidence exact-case/current-APK validation;
- `productionPretrainingEligible=false` until explicit freeze/device gates pass;
- no direct local-agent/MCP expansion.

Installed-device validation is still required for actual ContentProvider visibility, token pairing, process restart persistence, upload/canary lifecycle, V2 adversarial-lab evidence and Hardware Target A production-pack streaming/storage proof.
