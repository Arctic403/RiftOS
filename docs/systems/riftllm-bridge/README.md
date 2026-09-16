# RiftLLM Standalone Dev API Bridge

## Purpose

The RiftLLM bridge lets trusted RiftOS development tooling interoperate with the separately installed `com.riftllm.app` APK through RiftLLM's token-gated Android Binder Dev API. It is optional development interoperability only: RiftLLM remains a standalone APK and never depends on RiftOS for installation, inference, memory, Dev Lab staging, snapshots, patch generation, recovery, or normal operation.

The integration follows RiftLLM's authoritative `docs/RIFTOS_DEV_API_HANDOFF.md` contract: RiftLLM owns its app-private Dev Lab; RiftOS owns only the external adapter between `/workspace/RiftLLM` and that public local API.

## Source ownership

- `android/app/src/main/java/com/riftos/app/RiftLlmDevClient.kt` — fixed-purpose Binder client, target/provider identity, API-operation allowlist and Keystore-backed pairing-token use.
- `src/riftllm-bridge.js` — trusted-shell orchestration for exact source sync, staging commands, immutable snapshot selection, patch validation, Workspace preview/apply and publication acknowledgment; it also owns the local-only bounded RiftCorpus build/status helper under `/workspace/RiftLLM/tokenizer/private/`.
- `src/riftos.js` — `riftllm-agent` RiftShell routing plus local Settings pairing/status controls.
- `android/app/src/main/AndroidManifest.xml` — package visibility query for `com.riftllm.app`; this grants no RiftLLM privilege.
- `RiftNativeDispatcher.kt` — finite `riftllm.dev` native route executed on the dedicated agent worker.
- `RiftSecretStore.kt` — encrypted storage authority for `riftllm.dev.token`.
- `scripts/test-riftllm-bridge.mjs` plus cross-layer validators — source contract protection.

## Product boundary

RiftLLM is not a RiftOS subsystem, plugin, hosted engine or runtime dependency. RiftOS never reads RiftLLM private files and never owns its source mirror, staged edits, snapshots, snapshot IDs, publication receipts, token generation/rotation or source classification. Removing either APK leaves the other product's independent functionality intact.

No localhost/network server is introduced. The transport is `ContentResolver.call()` to the fixed URI `content://com.riftllm.app.devlab`. The native client accepts only the documented method family and never accepts an arbitrary URI or provider method from JavaScript/shell input.

## Pairing and secret handling

RiftLLM's Dev Lab must first enable/rotate its API and display the 256-bit token. RiftOS pairing is initiated from Settings or `riftllm-agent pair`; the token is entered only into the local prompt. The native client verifies the candidate by calling RiftLLM `status` before persisting it as `riftllm.dev.token` through `RiftSecretStore`.

The token is never returned by status/pairing, written to RiftFS/workspace, placed in a shell argument, logged by this subsystem, exposed through MCP results, or copied into Git. `unpair` deletes only RiftOS's encrypted copy; RiftLLM remains the authority for disabling/rotating its API token.

## Data and publication flow

For existing files, `sync` reads the exact canonical `RiftLLM/<path>` Workspace text, hashes the same UTF-8 content with SHA-256, then sends `sync_source`. New-file baselines use `sync_missing`, but only after RiftOS proves that the canonical path is absent. A path that is already staged in RiftLLM is not silently resynced underneath that edit.

`stage`, `delete`, `unstage`, `reset`, `snapshot` and snapshot-list/load operations change only RiftLLM's own app-private Dev Lab state until publication. `stage-file` is the exact-payload shell path for multiline edits. The provider's bounded read-only `list_benchmarks` / `get_benchmark` methods are exposed as `benchmarks` / `benchmark`; they never mutate staging or source and still require the same paired local API.

`text-encoding-eval a|b` is a separate fixed-purpose evaluation bridge for the two reviewed RiftTokenizer candidates. RiftOS reads only the pinned A/B artifact under `/workspace/RiftLLM/tokenizer/output/` plus the fixed `/workspace/RiftLLM/tokenizer/private/build/heldout.tsv`, hashes those exact UTF-8 bytes, uploads them through 192 KiB token-gated Binder chunks, verifies every acknowledged offset/commit hash, then asks RiftLLM to run its existing independent `RiftTextEncodingLab`. `text-encoding-status` polls that background app-side job. The command accepts no arbitrary source/destination path; the RiftLLM provider owns the two fixed app-private targets and snapshots both verified inputs before evaluation.

`corpus-synth`, `corpus-build` and `corpus-status` are different: they are **local RiftFS operations**, not Binder calls. They require no RiftLLM pairing token and are hard-confined to `/workspace/RiftLLM/tokenizer/private/`. `corpus-synth` mirrors RiftCorpus Synthesizer V1's finite original-data recipe: pinned template/seed identity, stable IDs, 12,000 generated records per category by default, reviewed maximum 20,000/category, single-file authored-seed preservation and duplicate collision checks. It writes deterministic `synthesized/part-*.jsonl` shards capped at 3 MiB plus `synth-manifest.json`; no individual text operation crosses RiftOS's fixed 4 MiB bridge limit. `corpus-build` accepts either a legacy single JSONL file or an ordered JSONL shard directory, performs the same schema/provenance/dedup/split validation, and writes `build/train/part-*.jsonl`, complete `build/heldout/part-*.jsonl`, a bounded category-balanced `heldout.tsv` (4,096 rows, 3 MiB total, 4 KiB/sample), and a hashed manifest. Shard-set provenance uses `rift-shard-set-v1`, SHA-256 over sorted `<name>\t<byte_count>\t<sha256>\n` descriptors. Output replacement uses a staged directory + backup/restore sequence rather than partially rewriting the active build folder. After a successful replacement, inability to remove the retired backup is reported as `backupCleanupPending=true` instead of falsely marking the new build as failed.

For preview/publication, RiftOS resolves `latest` to a concrete immutable snapshot ID, requests `get_patch`, then independently requires patch format v2, repo `Arctic403/RiftLLM`, branch `main`, a bounded change count, only write/delete actions, exact `RiftLLM/...` paths, preserved `base_sha256`, and text content for writes. It then delegates to the existing RiftWorkspace `previewPatch`/`applyPatch` transaction. Baseline conflicts abort before mutation and are never bypassed.

Only after `applyPatch` returns a Workspace `historyId` does RiftOS call `ack_publish`. If that final receipt call fails, the bridge reports `published:true, acknowledged:false` with the Workspace history ID rather than reapplying or pretending the local publication did not happen. `riftllm-agent ack` is a bounded recovery operation and first requires a matching Workspace history record for the exact RiftLLM repo/branch.

Publication means local Workspace mutation only. The bridge never authorizes Git push, Android build or install.

## RiftFS source-path compatibility

`stage-file` and other RiftFS-source operations use the shared RiftCore absolute-path rule. Bare drive paths such as `D:/Workspace/...` are accepted as absolute instead of being joined to the shell cwd. Project-path normalization also recognizes `D:/Workspace/RiftLLM/...` as the same canonical RiftLLM project namespace as `/workspace/RiftLLM/...`; publication still targets guarded Workspace-relative `RiftLLM/...` paths only.

## Shell surface

`riftllm-agent` provides fixed semantic commands for status/pair/unpair, source sync, load/staged/stage/stage-file/delete/unstage/reset, snapshots, read-only benchmark records, fixed `text-encoding-eval a|b` / `text-encoding-status`, local `corpus-synth` / `corpus-build` / `corpus-status`, preview, publish and receipt recovery. Corpus commands remain local and private-data-path confined; all other RiftLLM Dev API commands retain the Binder/pairing boundary. The family is deliberately excluded from generic RiftShell atomic batch because Binder-side Dev Lab mutations, corpus directory replacement and Workspace publication each have their own transaction/receipt semantics.

No dedicated MCP tool family is added. Existing trusted `rift_shell_exec` may invoke the bounded shell commands, but the pairing token itself can only be entered in the local RiftOS prompt.

## Security invariants

- Keep `com.riftllm.app` and `com.riftllm.app.devlab` hard-coded.
- Keep the native API method allowlist finite; never accept arbitrary provider methods or content URIs.
- Keep Binder calls off the Android UI thread.
- Never move RiftLLM-owned Dev Lab state into RiftOS.
- Never expose the pairing token in tool/shell results or source-controlled state.
- Never strip, replace, fabricate or ignore `base_sha256`.
- Never manually loop project writes in place of RiftWorkspace guarded apply.
- Never let RiftLLM publication imply Git push/build/install.
- Never add a network listener to work around Binder payload limits.
- Text Encoding Lab transfer must remain fixed to A/B tokenizer artifacts plus the one held-out TSV, use 192 KiB hash/offset-verified chunks, and never become a generic app-private file transfer or arbitrary Binder-method surface.
- Keep `corpus-synth` / `corpus-build` / `corpus-status` confined to `/workspace/RiftLLM/tokenizer/private/`; they must never become arbitrary RiftFS writers or process/script execution surfaces.
- Keep the on-device synthesizer capped at 20,000 records per category, default 12,000/category, and enforce 3 MiB JSONL shards; scaling must happen through the reviewed shard contract rather than widening the fixed 4 MiB bridge limit.
- Corpus build must reject RiftLLM/RiftOS/Vortex3D/VTXBuilder/VortexScript source identities during the unfinished-project exclusion phase and must replace output through staged/backup directory moves.

## Failure signatures

- `provider is not installed or visible` -> RiftLLM is absent or package visibility/provider declaration changed.
- `Dev API is disabled` or token rejected -> enable/rotate in RiftLLM, then pair again; do not discover tokens programmatically.
- `request exceeds 512 KiB` or Binder failure -> V1 IPC payload limit; use the reviewed 192 KiB Text Encoding chunk path only for its two fixed slots, otherwise reduce the operation rather than adding a network shortcut.
- Text Encoding upload offset/hash mismatch -> restart that fixed candidate evaluation; never skip chunks or trust a partial commit.
- corpus source read exceeds the trusted RiftFS text-bridge limit -> keep authoring batches below the current per-file bridge bound or shard the future corpus-builder design; do not expose raw Android shell/Python as a workaround.
- `sync verification failed` -> source content/hash did not describe the same Workspace state; reread and retry.
- baseline replacement rejected while staged -> publish/unstage/reconcile first.
- Workspace `base_sha256` conflict -> canonical source changed after sync; abort, inspect, resync and restage.
- `published:true, acknowledged:false` -> Workspace apply succeeded but RiftLLM receipt failed; inspect the history ID, repair API pairing if needed, then use bounded `ack` recovery exactly once.

## Fix map

Binder/provider identity, operation mapping, request size and token persistence -> `RiftLlmDevClient.kt`.
Source baseline/hash synchronization, patch validation, preview/apply/ack orchestration and bounded local RiftCorpus synth/build/status -> `src/riftllm-bridge.js`.
RiftShell parsing/Settings buttons -> `src/riftos.js`.
Generic atomic-batch exclusion -> `src/riftshell-batch.js`.
Workspace atomicity/history/rollback -> existing `src/riftworkspace-web.js`; do not duplicate it here.
RiftLLM provider/store behavior -> fix RiftLLM itself and update its handoff contract; do not reach into its private app storage from RiftOS.

## Validation

`npm run check` must include `scripts/test-riftllm-bridge.mjs`, `scripts/test-riftllm-corpus.mjs`, wiring, transport and documentation validation. The corpus test executes the real bridge helpers against an in-memory RiftFS mock and verifies deterministic synthesis, finite count/output bounds, synth-to-build composition, successful split/manifest generation, path confinement and unfinished-Rift-source rejection. The Android source verifier must require `RiftLlmDevClient.kt`. Any native change still requires a later manual APK build/install.

On-device acceptance must prove absent-target handling, disabled API rejection, wrong-token rejection, correct pairing/status, exact source sync/hash, isolated RiftLLM staging, immutable snapshot creation, guarded preview, deliberate Workspace-drift conflict with zero partial writes, successful apply + receipt acknowledgment, preservation of newer post-snapshot edits, Workspace rollback, no token in logs/MCP/Git/workspace, and normal standalone RiftLLM behavior when the RiftOS bridge is absent or unpaired.
