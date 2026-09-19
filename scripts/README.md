# RiftOS Source Validation Scripts

These scripts are fast source/protocol regression tests run by root `npm run check` before an Android build. Their owner and maintenance rules are documented in [`../docs/systems/build-validation/README.md`](../docs/systems/build-validation/README.md).

- `validate-rift-wiring.mjs` — full runtime wiring/reachability/import/native-route/asset/syntax validation.
- `validate-rift-transport.mjs` — cross-layer architecture/source invariants.
- `validate-rift-docs.mjs` — active-source ownership and required system README coverage.
- `test-rift-workspace-records.mjs` — retained Workspace Records HTML adapter regression oracle with explicit proof that `workspace-live` remains un-packaged and the native Workspace Records owner is present.
- `test-rift-diff-engine-v2.mjs` — live Diff Engine V2 contract: bounded exact-LCS threshold, patience-style anchors, independent hunks, Android-framework independence, Workspace Records wiring, exact Gradle source declaration and documentation ownership.
- `test-rift-file-identity-v2.mjs` — live File Identity V2 contract: exact SHA rename/copy evidence, bounded heuristic similarity, rewrite thresholds, relation-aware Workspace Records/diff wiring and documentation ownership.
- `test-rift-patch-sessions.mjs` — live Patch Session V1 contract: state-bound provenance claims, honest unattributed fallback, writer integrations, optional MCP intent metadata, Gradle declaration and ownership coverage.
- `test-rift-patch-manifest-v1.mjs` — deterministic candidate-manifest/hash-chain contract: canonical hashing, private immutable freeze bounds, checkpoint/pruning sequence evidence, crash-safe chain-head recovery, inert trusted-state fields and no MCP freeze mapping.
- `test-rift-semantic-impact-v1.mjs` — Patch 5 contract: shared PI-v2 parser, exact candidate-derived semantic seed, bounded/incomplete impact semantics, ownership-ledger lookup, deterministic semantic hash and internal-only ToolHost routing.
- `test-rift-cli-patch-lifecycle-v1.mjs` — manual OBSERVE-only CLI patch lifecycle contract: clean/synchronized acquisition, research/design order, governance/impact-derived coverage, supply-chain/build evidence, stale-result invalidation, bounded independent evaluation and zero trust/MCP expansion.
- `test-rift-cli-stress-foundation.mjs` — regressions from live torture testing: current candidate-created governance/build-manifest scope, RiftFS-system session durability, legacy migration and fail-closed process-restart drift detection.
- `test-rift-documentation-parity-v1.mjs` — Patch 8 candidate-specific ownership/governance parity, mandatory owner-doc updates, structured review binding and stale-plan denial.
- `test-rift-verification-planner-v1.mjs` — Patch 9 impact-derived test/security/dependency targets, deterministic check ids, exact plan-bound evidence and stale-plan denial.
- `test-rift-dev-lab.mjs` — native Dev Lab staging/snapshot/guarded-publish regression contract.
- `test-rift-ai-adapters.mjs` — AI site adapter contract.
- `test-rift-raw-protocol.mjs` — browser raw tool-call parser/protocol.
- `test-rift-shell-batch.mjs` — retained RiftShellBatch preflight/rollback regression oracle plus explicit proof that batch JS remains un-packaged and absent from native RiftShell.
- `test-rift-shell-bridge.mjs` — native RiftShell migration guard: process-owned shell authority, no renderer fallback, headless QuickJS and browser-only Chromium ownership; WebView exclusion is based on actual WebKit dependencies rather than harmless comments/text.
- `test-rift-shell-git.mjs` — native RiftGit source contract: Keystore credential boundary, bounded GitHub transport, metadata validation, stable atomic push, staged pull/switch/clone rollback, path confinement and Workspace Records checkpoints. Retained `src/riftgit.js` is not treated as live authority.
- `test-rift-path-compat.mjs` — retained cross-module C:/D: compatibility oracle for older JS modules; current native path authority is audited separately.
- `test-rift-local-platform.mjs` — RiftRepo/RiftVault/RiftBuild/RiftMemory wiring, fail-closed capability and shell-surface contract.
- `test-riftllm-bridge.mjs` — live native RiftLLM Dev API/Binder shell contract plus retained `riftllm-bridge.js` regression checks; explicitly proves the retained JS is un-packaged and not a live public surface.
- `test-riftllm-text-encoding-bridge.mjs` — executes the retained Text Encoding bridge oracle against a mocked native `riftllm.dev` route, verifies bounded chunks/identity/path rules, and proves the JS bridge stays un-packaged.
- `test-riftllm-training-bridge.mjs` — locks the stable frozen-B2 RiftTrainData packer, fixed private paths/hashes, uint16 BOS/text/EOS records, post-encode source rehash, 192 KiB training upload, fixed canary commands and no arbitrary path/process authority.
- `test-riftllm-corpus.mjs` — executes the retained RiftCorpus bridge oracle against an in-memory RiftFS mock, verifies deterministic shard/path contracts, and proves the JS bridge stays un-packaged.
- `test-rift-text-encoder-task.mjs` — locks the experimental fixed-path V1/V2 native RiftTokenizer contract, pinned configs/sources, V2 24-byte learned-token cap, async train/status/cancel surface, optimized-batch parity hooks, provenance, streaming status, transactional artifact/manifest recovery, 32K arithmetic and no-generic-process boundary.
- `test-rift-plus-plus-v0.mjs` — locks Rift++ V0 non-executable Swarm IR, workspace-only source confinement, finite role/capability policy, preview-only BrainBackend coordinator and no-new-MCP-tool boundary.
- `test-rift-ir-v1.mjs` — locks the language-independent `rift.ir/1` swarm-core schema, defense-in-depth graph/policy validation, resource accounting, inspect-only execution policy, experimental CLI adapter and no-new-MCP-tool boundary.
- `test-rift-plus-plus-core-v1.mjs` — exercises the current Rift++ Core lexer/parser/type/control-flow/module/effect pipeline into `rift-exec-v1`, including bounded structured data/Vec/Buffer/Slice, checked `u8` + explicit byte conversions, finite-f64 parameter compute, checkpoint/storage authority, Gate 6D.2 string-repair primitives, module-graph bounds and fail-closed diagnostics.
- `test-semnexis-bootstrap.mjs` — executes the QuickJS-hosted Semnexis compiler against graph/plan/Native-IR goldens, `SNIRV0` binary round-trip/corruption checks, checked-i32 semantics, the constant ARM32 proof backend and runtime-valued ARM32 add/sub/call lowering including branch-target verification.
- `test-semnexis-shell.mjs` — locks the fixed `semx` shell route, packaged compiler asset, fixed ARM32 artifact output, QuickJS authority boundary and permanent absence of the retired RiftNativeToolchain/riftclang path.
- `test-qjs-shell.mjs` — locks the bounded generic `qjs` developer route: timeout/source/output/file bounds, classic-script execution, read-only RiftFS text access and explicit absence of write/process/network/Android authority.
- `test-riftpp-shell.mjs` — locks the normal RiftShell Core `riftpp` family, its separation from experimental RiftCLI, root-confined deterministic module-path loading, module identity/graph bounds, no-host-import execution boundary, timer/macrotask yielding, output/VM limits and atomic-batch exclusion.
- `test-rift-vm.mjs` — executes/attacks current `rift-exec-v1`: bounded scalar/composite/Vec behavior, finite-f64/value hashing, Gate 6D.2 string primitives, state schema validation, executable/string/depth/output limits, host-boundary denial, overflow/step limits and no eval/process path.
- `test-rift-app-import.mjs` — retained RiftApps/RiftRT package-format regression oracle with guards proving those JavaScript installers/managers remain un-packaged; current APK only hosts already-installed packages.

When behavior intentionally changes, update the owning subsystem code, README and focused test together. Do not weaken a validator only to make an unintended regression pass.
