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
- `test-riftllm-bridge.mjs` — standalone RiftLLM Binder bridge, secure pairing, guarded patch publication and shell-boundary contract.
- `test-riftllm-corpus.mjs` — executes the local RiftCorpus helper against an in-memory RiftFS mock and verifies deterministic split output, private-path confinement and unfinished-Rift-source rejection.
- `test-rift-text-encoder-task.mjs` — locks the experimental fixed-path native RiftTokenizer task contract, pinned configs, 32K arithmetic and no-generic-process boundary.
- `test-rift-app-import.mjs` — `.rift` package import/validation.

When behavior intentionally changes, update the owning subsystem code, README and focused test together. Do not weaken a validator only to make an unintended regression pass.
