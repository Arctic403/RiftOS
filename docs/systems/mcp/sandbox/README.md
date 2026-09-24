# RiftToolSandbox and Rift Code Mode

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-20.**

## Purpose

`RiftToolSandbox` is the workspace-only execution engine behind normal Rift MCP filesystem tools, project inspection and Rift Code Mode / Project Intelligence v2.

It owns canonical workspace containment, single-operation filesystem methods, bounded search/read results, per-call transaction/rollback safety, snapshots, project intelligence and archive safety. Model-facing `rift_workspace_exec` is hard-limited by `RiftToolHost` to exactly one operation per call; multi-op/batch execution is fail-fast disabled.

## Source ownership

Primary:
- `RiftToolSandbox.kt`
- `RiftBoundedAsync.kt` — 45-second request deadline and cooperative cancellation

Related narrow owners:
- `RiftToolHost.kt` — schemas/grants/aliases;
- `RiftProjectExporter.kt` — paged whole-project export;
- `RiftWorkspaceRecords.kt` — persistent change records/checkpoint state;
- `RiftPatchSessions.kt` — bounded mutation provenance claims consumed asynchronously by Workspace Records.

## Canonical root

Physical authority is exactly:

`filesDir/riftfs/workspace`

Tool paths are represented as `workspace/...`.

Legacy `tool-sandbox/workspace` and `browser-sandbox/workspace` are one-time migration inputs only. They are never addressable roots after migration.

Old workspace scaffold metadata under `.rift` migrates into app-private system metadata; empty legacy scaffold directories can be removed.

## Core bounds

- ordinary tool file/payload bound: 8 MiB;
- ordinary list: 5000 entries;
- internal Code Mode operation-array ceiling: 192; model-facing `rift_workspace_exec` is currently capped to exactly 1 operation by `RiftToolHost`;
- Code Mode result byte budget: 700 KiB;
- bounded workspace read: 240000 chars;
- search matches: 300;
- total search scan budget: 64 MiB;
- directory-hash content budget: 256 MiB;
- project-index refresh source budget: 128 MiB;
- rollback snapshot: 64 MiB and 50000 entries;
- patch edits: 96;
- Code Mode list entries: 1200;
- searchable/indexed file max: 2 MiB;
- rollback backup set: 64 MiB;
- search preview: 320 chars;
- symbol results: 240;
- reference results: 400;
- index files scanned: 25000;
- graph edges: 600;
- persisted index rows: 4000;
- persisted index file: 8 MiB;
- hunks: 128;
- snapshot files: 50000;
- archive entries: 50000;
- archive source: 256 MiB;
- archive expansion: 512 MiB.

## Single-operation methods

The sandbox dispatcher implements:
- info;
- stat/hash/list/read/write;
- mkdir/remove/move/copy;
- archive/extract;
- workspace audit/scan;
- project export;
- workspace diff;
- workspace exec.

All paths are canonicalized beneath the workspace root.

## Code Mode operation family

`workspace.exec` supports:
- project;
- snapshot;
- stat;
- hash;
- list;
- search;
- symbols;
- references;
- read;
- read_range;
- read_symbol;
- write;
- replace;
- patch;
- patch_range;
- apply_hunks;
- mkdir;
- remove;
- move;
- rename;
- copy;
- archive;
- extract.

Both canonical flat operations and the same unambiguous shorthand normalization recognized by ToolHost are accepted.

## Snapshot guards

Before each `rift_workspace_exec` call:
- `expectedSnapshot` can require an exact scoped project snapshot;
- `expectedExportSnapshot` can require the current RiftProjectExporter snapshot id.

A mismatch rejects the call before mutation.

`returnSnapshot` optionally returns a fresh scoped snapshot after the one-operation call.

## Transactions

Every `workspace.exec` call creates a private cache transaction directory. Public ToolHost requests contain exactly one operation, so this transaction currently protects one visible operation at a time.

For a mutating operation:
- write/replace/patch/patch_range/apply_hunks/mkdir/remove capture `path`;
- move/rename capture both `from` and `to`;
- copy/archive/extract capture `to`.

A path is captured only once, and a child capture is skipped when an ancestor is already snapshotted.

Existing files/directories are copied into the rollback store before mutation. Total rollback material is capped at 64 MiB.

If an operation throws:
1. every captured target is cleared;
2. original file/directory/missing state is restored;
3. index invalidation follows restoration;
4. rollback errors are appended to the surfaced operation error;
5. transaction staging is removed.

The code never silently claims atomicity when rollback itself failed.

## Dry-run

Dry-run allows reads and content-edit operations whose effects can be restored through the transaction.

Structural mkdir/remove/move/rename/copy/archive/extract are rejected in dry-run mode.

After successful dry-run execution the transaction is rolled back; rollback failure is surfaced as an error.

Response reports `committed=false`.

## Patch-session provenance

Before a mutating MCP filesystem call or non-dry-run `workspace.exec` call, the sandbox derives the mutation target set from the single normalized operation and opens one `RiftPatchSessions` claim with `origin=mcp`. The MCP request id is retained and `rift_workspace_exec` may carry a bounded optional `intent` string. Intent is evidence only; it never changes permission classification.

After successful mutation the claim is committed against resulting file states. On failure it is aborted. If later filesystem observation cannot correlate the claim, Workspace Records records the event as `unattributed-local` rather than trusting stale metadata.

Dry-run calls create no patch-session claim because their temporary mutation is rolled back by definition.

## Change summary

Before/after snapshots generate bounded changed-file metadata.

For small text files the transaction computes added/removed line deltas. Changed files may include post-change SHA-256 when within ordinary tool bounds.

This is a per-call change summary, not a permanent AI-session journal.

## Result bounding

Each `rift_workspace_exec` result is counted against the 700 KiB Code Mode result budget.

When the budget is exceeded, execution continues but the affected operation receives a compact `resultOmitted=true` summary rather than returning unbounded content.

## Project Intelligence v2

Persistent cache:
`filesDir/rift-project-intelligence-v2.json`

The cache:
- version-checks at load;
- max file size 8 MiB;
- keeps at most 4000 persisted indexed files;
- is advisory;
- is validated against current file mtime/size;
- can be deleted/rebuilt safely;
- is persisted through a temporary file + rename;
- is outside the user workspace.

Live indexing scans at most 25000 source files, skips ignored/generated/binary directories/files, and indexes source files no larger than 2 MiB.

Ignored directories include common build/cache/vendor trees plus `.vortex-bridge`.

## Project views

The existing `project` operation provides bounded:
- graph;
- impact;
- validation;
- consistency;
- integrity views.

Graph resolves project dependency evidence without guessing unresolved system includes into fake local edges.

Impact combines definitions/references/dependencies/dependents/docs/tests.

The promoted N1.8.0 `consistency` view is an evidence-only foundation derived from the existing PI-v2 graph. It does not rescan source or create a second symbol/dependency index. Public `project kind=graph` remains human-preview bounded at 120 matched files and 600 dependency edges, while consistency reuses the same internal PI-v2 builder with observer-only 1024-file/1024-edge input bounds so the current repository can be covered completely without enlarging the public graph response. Consistency forces exact repository-content verification and consumes the separate PI-v2 repository-file evidence set, so every non-policy-excluded file is represented by size/SHA-256 plus semantic status even when it is metadata-only. The promoted cache-integrity contract was PI schema v5. Patch 10.44 initially advanced N1.8.1 to v6 for syntax/local-intent persistence; Patch 10.45 advanced it to v7 for structural-v2/package-aware semantics; Patch 10.46 advanced it to v8 for recursive-template/Kotlin-interpolation/executable-code semantics; Patch 10.47 advances it to v9 so rows produced by the broken structural-v3 regex-resume contract cannot be reused. Producer version/source-SHA and canonical cache integrity checks remain fail-closed. The observer emits stable fact/edge identities, separate content hashes, a deterministic repository graph hash, explicit completeness/bounds, and a verified rebuildable private cache under `filesDir/rift-repository-consistency-v2`. The default response is compact so a whole-repository proof does not exceed Code Mode's result budget; `query=full` explicitly requests complete arrays for smaller scopes/debugging.

N1.8.1 adds a separate read-only `project kind=integrity` lane while the canonical N1.8.0 graph stays frozen. The shared analyzer is v6 and adds conservative bounded structural syntax evidence (`bounded-structural-v4-conservative`) plus explicit local-intent metadata for relative JavaScript/Python dependencies, quoted C/C++ includes and Rust local module forms. Structural-v4 keeps the v3 bounded Kotlin interpolation, recursive JavaScript template masking and executable-code filtering for dynamic `import()`/`require()` edges, and additionally locks the regex-scanner resume convention so scanning resumes exactly at the first token after the regex literal rather than skipping that token. Successfully parsed complex string/template spans are masked rather than interpreted; malformed/unterminated constructs remain visible to the structural scanner. Integrity uses cache/index evidence rather than a second source index, distinguishes `complete` from `clean`, classifies dependencies as `local-resolved`, `local-missing`, `ambiguous-local` or `external-or-unclassified`, and emits deterministic findings only where local intent can be proven. A blank query performs the full clean oracle; a query seeds a focused scan and returns a bounded direct outgoing/reverse frontier for staged expansion. Every integrity result carries `integritySha256`. This lane is promoted on installed source `198a3f31e22a5d385378fee087aa5f115aed6d5a` after Builder/install, full clean-oracle, adversarial syntax/import/path-drift/frontier, warm-cache and real force-stop/restart parity all passed. The structural syntax lane is intentionally not a compiler AST; compiler/build/test proof remains a separate stronger tier.

N1.8.2 adds a separate read-only `project kind=propagation` view without changing the promoted consistency/integrity contracts. The query selects an exact `symbolId`, exact symbol name or matching path. Symbol identity is `path|kind|name|ordinal` hashed to a stable ID, while `signatureId` is independently bound to the normalized declaration signature. Reference discovery is intentionally conservative and seed-specific. `RiftSourceIntelligenceV2.referenceCodeMask()` masks comments/string-like regions per supported language before name matching, so false lexical hits inside non-code text are counted as ignored rather than emitted as references. For each remaining name match, same-file candidates take precedence; otherwise only candidates in the existing resolved local dependency targets are considered. A match is retained only when that candidate pool can refer to one of the selected seed symbols; unique pools resolve, multi-candidate pools remain ambiguous, and unrelated same-name matches are counted in `ignoredNameMatches` instead of consuming the propagation reference budget. Caller attribution uses the smallest indexed symbol range containing the retained reference. Type propagation extracts `extends`, `implements`, Kotlin inheritance/interface lists and C++ inheritance, preserving resolved/unresolved/ambiguous status. Reverse closure walks dependency, resolved-reference and type-relation edges with explicit seed/symbol/reference/caller/type/node/edge/depth bounds and emits deterministic `propagationSha256`. The lane is evidence-only with zero mutation authority. N1.8.2 is promoted on installed source `9cc74b25c94fd3e23e93f64d3d132e65e63fe3a6` after Builder/install, exact/+1 bound torture, warm determinism and first-read post-force-stop parity passed.

N1.8.3 adds a separate read-only `project kind=contracts` oracle implemented by `RiftCrossBoundaryContractsV1`. It performs bounded deterministic cross-boundary checks without gaining mutation authority: Android `namespace`/`applicationId` equality, Gradle externalNativeBuild CMake-path existence, manifest component-to-managed-class resolution, `System.loadLibrary` to CMake `add_library` producer resolution, Kotlin `external fun` / Java `native` to JNI resolution plus reverse JNI-export-to-managed-declaration resolution, advertised MCP tool-to-dispatch coverage, exact MCP relay protocol and CLI event-schema mirrors, and the ordered 45s sandbox < 60s shell < 65s MCP server < 70s relay client < 75s relay-worker timeout chain. Results expose `complete` separately from `clean`, explicit fail-closed bound reasons and deterministic `contractsSha256`. Full ordered findings/evidence remain authoritative for counts/hash, while returned findings/evidence arrays are bounded to 240-row previews with explicit truncation metadata so exact-bound torture cannot overflow the response transport. If file-count, per-file-size, aggregate-byte or read bounds make source scanning incomplete, the view now suppresses contract findings from the partial dataset and emits `partialFindingsSuppressed=true`; the separate finding-bound path still retains up to 1024 findings because that limit is reached only after a complete source scan. N1.8.3 is promoted on installed source `e6de353ead6e9377e36e1602e301e3e8231a5e43`, Builder run `35949668024` / run number `322`. Installed evidence covers the complete-scan finding families, exact/+1 finding/per-file-size/file-count bounds, exact 64 MiB aggregate behavior, Patch 10.58's 64 MiB +1 suppression path, deterministic warm reads, first-read post-force-stop parity, and final N1.8.0-N1.8.2 regression continuity.

N1.8.4 adds the separate read-only `project kind=claims` oracle implemented by `RiftDocumentationClaimsV1`. Its authority direction is deliberately one-way: current source/manifests/build configuration and machine-readable repository state establish implementation/configuration truth, while exact Builder/install/runtime evidence establishes build/install/promotion truth; README/docs/ROADMAP/TODO/status/comment text is claim data only and can never override those authorities. The first deterministic coverage set checks required current documents, relative Markdown targets, source-ownership existence/coverage, documentation trust-policy markers, N1.8 ROADMAP state, PROJECT_STATUS promoted source/run bindings, canonical Observer status, structured TODO/FIXME or unchecked-task discovery and deterministic historical-document classification. Unsupported free-form prose is not guessed (`freeFormProseInference=false`). Scans fail closed at 4096 files, 2 MiB/file, 64 MiB aggregate, 8192 claims and 1024 findings, with 240-row previews and deterministic `claimsSha256` over the full ordered authority/claim set. Lifecycle state is now loaded from versioned machine source `observer/phase-authority.json` rather than compiled phase constants; the oracle validates schema/order/lifecycle/source/run fields and a 64 KiB cap fail-closed before evaluating prose. Installed run 327 passed the original clean/mutation/all-bound/warm/restart torture. Final installed run 328 / source `be1e3ddedec2512145ec7132c3a47419c139cb4e` then proved the Patch 10.63 bootstrap refactor with clean full-repo claims, exact missing/invalid authority fail-closed behavior, deterministic warm and first-read post-force-stop parity, and N1.8.0-N1.8.3 continuity. N1.8.4 is promoted.

Patch 5 extracts source parsing into `RiftSourceIntelligenceV2`, which is now the single parser used by both the persistent PI-v2 index and candidate before/after semantic deltas. The candidate path is not model-scoped: `RiftWorkspaceRecords.semanticImpactSeed()` derives its changed paths from the exact Patch Manifest V1 candidate, then the sandbox derives project roots, symbols/signature deltas, dependency deltas, current dependents, one-pass changed-symbol references, test affinity, `docs/SOURCE_OWNERSHIP.md` owners, nearest READMEs and global project docs.

Candidate semantic working-set bounds are 4096 changed paths, 1024 source files and 8 MiB before/after source text. Impact bounds are 32 project roots, 1000 output changed symbols, 80 reference-search symbols, 800 references, 800 dependency rows, 800 dependent rows, 300 tests, 300 documentation targets and 128 test-affinity targets. Missing text, truncated index/delta or any exceeded bound records an explicit incomplete reason.

The deterministic impact payload is SHA-256 bound as `semanticImpactSha256` using Patch Manifest canonical JSON. Cache refresh diagnostics are attached after the semantic hash and do not alter evidence identity.

The analyzer is lexical Project Intelligence, not a compiler AST or correctness proof. Compiler/build/test gates remain separate requirements.

Validation discovers repository check/test/build guidance.

These are Project Intelligence v2 behavior behind the existing tool schema, not separate MCP tools.

## Search / symbols / references

Text search is bounded by file size, match count and preview size.

Symbol/reference results are bounded independently.

Index invalidation is coalesced within a mutation call and persisted after refresh/invalidation.

Workspace files remain authoritative; stale cache rows cannot override source.

## Archive security

Archive/extract use staged destinations.

Extraction rejects:
- absolute paths;
- drive-letter paths;
- `.` and `..`;
- duplicate entries;
- targets escaping the staging root.

Entry count and expanded-byte limits are enforced before publish.

## Request lifecycle

Sandbox calls execute through one serialized worker with a bounded 16-request queue and a separate watchdog. Each request has a 45-second deadline beginning at submission time, including queue delay. Timeout returns one terminal error, interrupts the Future and exposes the same monotonic deadline to long filesystem loops through `RiftDeadline.check()`.

Code Mode checks the deadline around expensive archive/search/hash/index work and before/after the one normalized operation. Project Intelligence invalidations are coalesced within the call and flushed once. If cancellation occurs during a transactional mutation, the worker clears the interrupt only long enough to complete bounded rollback before accepting later work.

## Shutdown

Shutdown attempts to persist dirty PI-v2 state, then stops both the bounded sandbox worker and its watchdog.

## Non-ownership boundaries

Sandbox does not own:
- model-visible tool names/grants -> Tool Host;
- shell execution -> RiftNativeShell;
- external SAF folders -> Files;
- whole-project export paging format -> Project Exporter;
- permanent workspace record UI/checkpoint -> Workspace Records.

## Critical invariants

- every tool path remains beneath physical workspace;
- mutation capture happens before mutation;
- rollback max remains 64 MiB;
- failed rollback is surfaced;
- dry-run never permits structural operations;
- expected snapshots reject stale edits before mutation;
- result payload remains bounded;
- PI cache is advisory/app-private/versioned;
- mutation invalidation keeps index coherent;
- archive traversal/duplicate/expansion attacks are rejected.

## Failure signatures

- workspace tool accesses sibling RiftFS root -> containment regression;
- failed one-operation mutation leaves partial target state -> rollback regression;
- rollback failure reported as success -> atomicity regression;
- dry-run structural op succeeds -> dry-run contract regression;
- stale PI cache overrides changed file -> cache-validation regression;
- generated `.vortex-bridge` evidence appears as source -> indexing regression;
- Code Mode result grows beyond the per-call budget -> result-bound regression;
- ZIP traversal or duplicate path accepted -> archive regression.

## Fix map

Path/mutation/search/index/transaction behavior -> `RiftToolSandbox.kt`.

Tool schemas/grants -> `RiftToolHost.kt`.

Export paging/snapshot id -> `RiftProjectExporter.kt`.

Workspace persistent records -> `RiftWorkspaceRecords.kt`.

## Validation

Second source audit must recheck canonical root/path guards, operation set, 192-op bound, 700 KiB result bound, transaction capture-before-execute, 64 MiB rollback cap, dry-run restriction/rollback, snapshot guards, index persistence/version/bounds/ignored paths, search/symbol/reference limits, archive staging/traversal and shutdown persistence.
