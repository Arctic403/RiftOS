# RiftOS Source Validation Scripts

These scripts are fast source/protocol regression tests run by root `npm run check` before an Android build. Their owner and maintenance rules are documented in [`../docs/systems/build-validation/README.md`](../docs/systems/build-validation/README.md).

- `validate-rift-wiring.mjs` — full runtime wiring/reachability/import/native-route/asset/syntax validation.
- `validate-rift-transport.mjs` — cross-layer architecture/source invariants.
- `validate-rift-docs.mjs` — active-source ownership and required system README coverage.
- `test-rift-ai-adapters.mjs` — AI site adapter contract.
- `test-rift-raw-protocol.mjs` — browser raw tool-call parser/protocol.
- `test-rift-shell-batch.mjs` — local shell batch preflight/rollback.
- `test-rift-shell-bridge.mjs` — compatibility RPC acknowledgements, expired requests, duplicate suppression, reply recovery and bounded pending state.
- `test-rift-shell-git.mjs` — RiftGit/shell integration.
- `test-rift-path-compat.mjs` — shared C:/D: absolute-path and canonical-identity contract across RiftCore, Build, Repo, Git, Vault, Memory, Dev Lab, RiftLLM and Files.
- `test-rift-local-platform.mjs` — RiftRepo/RiftVault/RiftBuild/RiftMemory wiring, fail-closed capability and shell-surface contract.
- `test-riftllm-bridge.mjs` — standalone RiftLLM Binder bridge, secure pairing, guarded patch publication, fixed V1/V2 Text Encoding Lab method surface and shell-boundary contract.
- `test-riftllm-text-encoding-bridge.mjs` — executes the fixed A2 challenge uploader against a mocked paired Binder route and verifies 192 KiB chunks, offsets, hashes, candidate identity, fixed challenge lane and no arbitrary artifact/corpus path.
- `test-riftllm-training-bridge.mjs` — locks the stable frozen-B2 RiftTrainData packer, fixed private paths/hashes, uint16 BOS/text/EOS records, post-encode source rehash, 192 KiB training upload, fixed canary commands and no arbitrary path/process authority.
- `test-riftllm-corpus.mjs` — executes V1/V2 local RiftCorpus synth/build helpers against an in-memory RiftFS mock and verifies deterministic 3 MiB shards, shard-set hashes, V2 compositional identity, sharded train/full-heldout composition, bounded Android benchmark output, private-path confinement and unfinished-Rift-source rejection.
- `test-rift-text-encoder-task.mjs` — locks the experimental fixed-path V1/V2 native RiftTokenizer contract, pinned configs/sources, V2 24-byte learned-token cap, async train/status/cancel surface, optimized-batch parity hooks, provenance, streaming status, transactional artifact/manifest recovery, 32K arithmetic and no-generic-process boundary.
- `test-rift-plus-plus-v0.mjs` — locks Rift++ V0 non-executable Swarm IR, workspace-only source confinement, finite role/capability policy, preview-only BrainBackend coordinator and no-new-MCP-tool boundary.
- `test-rift-ir-v1.mjs` — locks the language-independent `rift.ir/1` swarm-core schema, defense-in-depth graph/policy validation, resource accounting, inspect-only execution policy, experimental CLI adapter and no-new-MCP-tool boundary.
- `test-rift-plus-plus-core-v1.mjs` — compiles human-written `riftpp 1` Core source through lexer/parser/type/control-flow checking into `rift-exec-v1`, executes base, Control Flow V1, Structured Data V1 and Collections V1 fixtures, and locks nominal struct/enum correctness, bounded `Vec<T,N>`, `Option<T>` / `Result<T,E>`, exhaustive match, mutability, reachability and fail-closed diagnostics.
- `test-riftpp-shell.mjs` — locks the normal RiftShell Core `riftpp` family, its separation from experimental RiftCLI, no-host-import execution boundary, output/VM limits and atomic-batch exclusion.
- `test-rift-vm.mjs` — executes/attacks the first `rift-exec-v1` Rift++ executable ABI: bounded scalar/composite ops, nominal struct/enum/vector values, raw `Vec` + `Option` / `Result` behavior, declared host imports, composite host-boundary denial, checked overflow, step limits, no eval/process path and the importable `.rift` + `main.rxe` fixture.
- `test-rift-app-import.mjs` — `.rift` package import/validation.

When behavior intentionally changes, update the owning subsystem code, README and focused test together. Do not weaken a validator only to make an unintended regression pass.
