# RiftOS Patch History

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-18.**

This file records source-first implementation patches. It is not authority by itself: source code, Gradle packaging, manifest state, focused tests and direct audits outrank this history. Each entry describes what changed, where, why, how it works, what it affects, validation performed, limits/risks and rollback scope.

## Patch 10.1 — Builder documentation-gate repair

Builder run `35419961262` for source `9052a0ffa913986142e32f79d3e12a8c32d61b32` stopped in `validate-rift-docs.mjs` before Android compilation.

The failure was documentation-only:
- RiftBuild README lacked the required exact `## Source ownership` maintenance section;
- its verification text did not match the validator's required source-verification marker;
- two SOURCE_OWNERSHIP table insertions contained literal `\\n` text, causing the RiftBuild executor and local-platform test ownership rows to be invisible to the row parser.

Repair:
- added the canonical `**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**` marker while preserving the explicit Android compile/device-pending boundary;
- added RiftBuild's exact source-ownership section;
- replaced both literal `\\n` table separators with real newlines.

No Kotlin, Gradle, MCP, CLI, build authority or runtime behavior changed in this repair.

## Patch 10 — Native RiftBuild bounded Android build core

### What changed

Promoted RiftBuild from an inactive JavaScript design plus non-executing app façade to a native, workspace-bounded Android build controller.

`RiftBuildLocalExecutor` now owns:
- source/project validation for workspace Android projects;
- deterministic project identity and bounded run records;
- fixed ARM32 / ARM64 / universal planning;
- the existing capability-gated `build.local` app methods;
- a native `riftbuild` shell command family;
- a real prepared-artifact APK ZIP stage under `D:/Builds`.

The prepared package stage only accepts compiled Android binary manifest input plus selected ABI `.so` payloads and optional bounded assets/resources. It writes an **unsigned** APK plus SHA-256 receipt and explicitly records `signed=false` and `installableClaimed=false`.

### Security boundary

This patch does not add `ProcessBuilder`, raw `exec`, downloaded toolchain execution, automatic Git push, experimental CLI enablement or any new MCP tool. Projects remain confined to `D:/Workspace`; outputs remain confined to `D:/Builds`.

Missing direct ELF emission, signing or PackageInstaller ownership produces a blocker rather than fake build success.

### Validation

`scripts/test-riftbuild-native.mjs` locks:
- workspace/output confinement;
- binary-manifest requirement;
- dual-ABI package expectations;
- unsigned/non-installable honesty;
- no process/CLI/MCP authority expansion;
- retained `src/riftbuild.js` remaining unpackaged reference source;
- native shell and existing `build.local` wiring.

The Android source snapshot is now exact 50/50 with `RiftBuildLocalExecutor.kt` included.

Actual Android compilation of this new Kotlin source and on-device RiftBuild execution remain Builder/install proof steps; source validation is not called APK/device proof.

### Next dependency

Implement direct Rift++ ARMv7/AArch64 ELF/shared-object emission into the prepared-artifact contract, then add bounded APK signing/verification and explicit PackageInstaller integration.

## Patch 9 — Impact-derived verification planner

### What changed

Added `RiftVerificationPlannerV1` and bound it into the manual OBSERVE-only CLI lifecycle.

The planner derives one exact `rift.verification-plan/1` from the final Patch Manifest / PI-v2 candidate and current repository state.

It owns:
- impacted test targets and bounded repository-check fallback;
- security targets from changed source/build config/direct dependents plus API/dependency-surface reasons;
- required repository `rift-audit` / `rift-scan` checks for source/build candidates;
- exact added/removed dependency delta reviews;
- changed build-config reviews;
- current dependency/build manifest reviews;
- deterministic check ids for every required verification action.

Security, dependencies and tests evidence now require:

`verificationPlan.schema = rift.verification-evidence/1`

with the exact `planSha256` and evidence kind from:

`rift-cli lifecycle verification-plan <sessionId>`

The generic evidence `checks` list must contain every planned check id with PASS status, and `targets` must contain every required plan target. Extra checks may be reported, but the required set cannot be replaced by a caller-selected subset.

Final evaluation recomputes the plan and binds `verificationPlanSha256` into the evaluator subject. Missing/incomplete/stale security/dependencies/tests plan evidence therefore denies the candidate.

### Fail-closed behavior

- source/build/test change with no impact test and no derivable repository validation fallback -> `NO_TEST_OR_VALIDATION_TARGET`;
- missing planned test -> hard plan issue;
- missing audit/scan/target/dependency/build-config check -> incomplete verification evidence;
- candidate mutation after verification -> `VERIFICATION_PLAN_STALE`.

Patch 9 remains a planner/evidence gate, not an autonomous test runner. It adds no MCP tool, process runner, dependency installer, trust promotion or publication authority.

### Regression

`scripts/test-rift-verification-planner-v1.mjs` locks schemas, bounds, test/security/dependency derivation, exact-check identity, lifecycle binding, Gradle/source ownership and zero MCP/trust expansion.

## Patch 8 — Documentation / project-state parity gate

### What changed

Added `RiftDocumentationParityV1` and bound it into the manual OBSERVE-only CLI lifecycle.

The new deterministic plan is derived from the exact Patch Manifest / PI-v2 candidate and checks:
- substantive owner documentation separately from bookkeeping (`PATCH_HISTORY` / `SOURCE_OWNERSHIP` cannot alone satisfy an owner-doc update);
- maintained source/build/test ownership;
- stale ownership after deletion;
- owner-document existence;
- mandatory owner-document updates for added/type-changed/API/dependency/build-config changes;
- ownership-ledger updates for added/deleted maintained files;
- PATCH_HISTORY updates for maintained source/build/test candidates;
- explicit review coverage for README, ROADMAP, PROJECT_STATUS, SOURCE_OWNERSHIP and PATCH_HISTORY when present.

Documentation evidence now requires a `documentationParity` object generated against:

`rift-cli lifecycle documentation-plan <sessionId>`

The normalized evidence must review every maintained changed path and every governance surface using the exact deterministic owner set. Final evaluation recomputes the plan and binds `documentationParityPlanSha256` into the evaluator subject; stale parity evidence therefore denies the candidate.

### What Patch 8 does not claim

The gate does not claim arbitrary English prose can be proven true by local Kotlin. `UNCHANGED_VALID` remains a bounded structured claim and the independent evaluator must re-check prose against exact source/impact evidence.

Patch 8 remains OBSERVE-only:
- no MCP tool;
- no trusted promotion;
- no publication;
- no autonomous documentation rewrite.

### Regression

`scripts/test-rift-documentation-parity-v1.mjs` locks the plan/evidence schemas, ownership rules, governance review rules, lifecycle binding, Gradle/source ownership and absence of MCP/trust expansion.

## RiftGit read-only history update

Added bounded native `git log` support to RiftGit before Patch 8. The command reads commit history through GitHub's commits API for the already attached repository and validated current branch; it does not invoke a local Git process or widen mutation authority.

Supported forms:
- `git log`
- `git log -n N`
- `git log -nN`
- `git log --max-count=N`
- optional `--oneline`

The default is 20 commits and the hard per-request cap is 100. RiftGit also now exposes `git head` and `git rev-parse HEAD`, while `git status` prints recorded HEAD explicitly. `git log` reports recorded local HEAD versus remote branch HEAD and whether they match. Results include structured SHA/message/author/committer/parent data. History reads do not write `.riftgit.json`, create Workspace Records checkpoints, change branch state or expose arbitrary remote/ref queries.

## Builder hotfix — lifecycle regression expectation after stress-foundation repair

Builder run `35405254326` for source `fdbb30c2e62f4d2c4b4c82540c64f8c107a6f1c3` passed source integrity and reached the focused lifecycle test, then stopped because `test-rift-cli-patch-lifecycle-v1.mjs` still asserted the pre-repair scope expression `understanding -> governance + buildManifests`.

The implementation was correct: the stress-foundation repair intentionally changed pre-patch UNDERSTAND/DESIGN to the acquired base inventory while post-patch evidence unions base + current inventory. The regression test now locks both sides of that contract instead of the obsolete expression. No runtime authority, lifecycle policy, MCP surface or trust behavior changed.

## Pre-Patch-8 Stress-foundation repair

### Why

Live abuse of RiftCLI Patch Lifecycle V1 exposed two real blockers before the documentation parity gate could be trusted:

1. a candidate that added new governance files could mark DOCUMENT_AUDIT complete while omitting newly introduced `TODO.md`, `docs/PATCH_HISTORY.md` and `docs/SOURCE_OWNERSHIP.md`;
2. process recreation could reset Experimental authority correctly but lifecycle-session durability was inconsistent, and an unexplained external workspace change/delete across restart could invalidate the candidate underneath evaluation.

### Repair

`RiftCliPatchLifecycleV1` now:

- derives post-patch governance scope from **base inventory + current inventory + Project Intelligence changed-documentation evidence**;
- derives dependency/security/build scope from **base build manifests + current build manifests + changed-build-config evidence**;
- keeps UNDERSTAND/DESIGN bound to the original base inventory;
- stores lifecycle sessions under `<filesDir>/riftfs/system/rift-cli-patch-lifecycle-v1`;
- migrates legacy sessions from the previous app-private root on access;
- records a process epoch plus last observed source snapshot/candidate manifest;
- if process epoch changes and source/candidate identity drifted, permanently records `restartDriftDetected`;
- blocks new evidence imports and evaluation for a restart-drifted session;
- surfaces the drift receipt in lifecycle status.

This does **not** claim the external deleter/root cause was identified. Source audit found no normal MainActivity/MCP-runtime/RiftGit-constructor path that intentionally deletes arbitrary untracked repository files on startup. The lifecycle therefore treats unexplained restart drift as unsafe instead of guessing intent.

### Regression

Added `scripts/test-rift-cli-stress-foundation.mjs` to the root check chain. It locks:

- current governance/build-manifest discovery;
- changed-documentation/build-config inclusion;
- base-only pre-patch scope;
- RiftFS system session storage + legacy migration;
- process-epoch/restart-drift detection;
- zero MCP/trust expansion.

### Authority

Still OBSERVE-only. No new MCP tool, trust promotion or publication authority.

## CLI Patch Lifecycle V1 — Patches 6/7 core + 8–10/12 foundation

### What changed

Added the manual OBSERVE-only CLI→AI→CLI patch lifecycle requested for real repository work:

```text
acquire full clean repo
→ understand complete layout/ownership
→ research external assumptions
→ document intent
→ patch through existing tools
→ audit documents
→ audit code
→ security/dependency/test/build verification
→ end-to-end/rollback verification
→ freeze exact candidate
→ send bounded evidence bundle to independent AI
→ locally verify returned hashes/verdict
```

### Research performed first

The design was checked against:
- SLSA v1.2 Source/Build guidance for immutable source revision, provenance and verification-summary concepts;
- in-toto attestation concepts for binding claims to exact subjects;
- NIST SSDF lifecycle secure-development practices.

RiftOS does not claim certification against those standards. The implementation adopts the useful patterns locally.

### Primary source

- `RiftCliPatchLifecycleV1.kt`
- `RiftResearchLedgerV1.kt`
- `RiftExperimentalCli.kt`

Existing evidence owners reused rather than duplicated:
- RiftGit;
- Project Export;
- Workspace Records/Patch Manifest;
- Project Intelligence V2;
- ToolHost internal candidate-impact seam.

### What was added beyond the original proposed workflow

- immutable Git HEAD + Project Export snapshot at acquisition;
- clean-tree requirement and optional native Git pull;
- bounded full-repository inventory;
- README/docs/ROADMAP/TODO/TASK/patch-history/status/source-ownership discovery;
- generated/vendor boundary discovery;
- dependency/build manifest discovery;
- source/version/claim research ledger;
- authoritative-source requirement for critical claims;
- pre-patch research→design ordering;
- actual candidate-derived audit targets;
- documentation→code→security/dependency→test/build→E2E/rollback ordering;
- lockfile/SBOM/license/provenance disposition;
- build environment + artifact SHA-256 evidence;
- explicit rollback evidence;
- stale-evidence invalidation against candidate manifest;
- final candidate/semantic/evidence/policy hashes;
- independent evaluator/patch-actor identity separation;
- bounded structured defects;
- evaluation packet size limit with fail-closed behavior;
- no trust promotion/publication.

### Authority

Lifecycle commands are behind the existing manually enabled Experimental RiftCLI.

No new MCP tool, relay method, raw Android shell, model backend, persistent enable flag, trusted-checkpoint promotion or publishing authority was added.

`help` and `contract` are descriptive. Session/evidence/evaluation commands require process-local experimental enablement.

### Evidence ordering

The lifecycle requires:
1. base-bound repository understanding;
2. research;
3. design/document intent;
4. patch;
5. documentation audit;
6. code audit;
7. security;
8. dependency/supply-chain audit;
9. tests when source/build config changed;
10. optional build evidence when available (becomes required/current if supplied; Patch 13 will own mandatory artifact handshake);
11. E2E;
12. rollback;
13. freeze/evaluation.

Non-research evidence cannot be complete with zero checks. WARN/FAIL/NOT_RUN makes the record incomplete. Missing impact-derived targets also makes it incomplete.

### State-of-the-art evidence additions

Complete dependency evidence must disposition:
- lockfiles;
- SBOM;
- licenses;
- provenance.

Complete build evidence must name:
- builder;
- toolchain;
- source revision;
- artifact SHA-256s.

Research collection itself is not called trusted. The final evaluator must independently re-check critical claims. V1 evaluator/patch-actor IDs are declarative separation metadata, not cryptographically authenticated identities.

### Current roadmap effect

- Patch 6 state-machine/policy core: OBSERVE core implemented.
- Patch 7 research ledger: collection/claim schema implemented; final independent re-check remains evaluator responsibility.
- Patch 8 parity gate: target-coverage/order foundation implemented; semantic truth remains validators/evaluator work.
- Patch 9 impact-derived verification planner: target-selection foundation implemented; no autonomous test/build runner.
- Patch 10 stale-result invalidation: candidate/source/evidence binding implemented; hermetic execution is not.
- Patch 11 enforcement/bypass closure: not implemented.
- Patch 12 verification-bundle foundation: implemented; immutable/hash-chained decision trail not yet implemented.
- Patch 13 Builder provenance handshake: not implemented.
- Patch 14 adversarial graduation: pending.

### Limits

- lifecycle session store: 64 sessions;
- imported evidence: 512 KiB/file, 96 records/session;
- checks: 256/record;
- targets: 2000/record;
- full inventory: 50000 files / 512 MiB;
- evaluation packet: 700 KiB and fails rather than truncates;
- evaluator defects: 256.

### Validation

Focused source contract:
- `scripts/test-rift-cli-patch-lifecycle-v1.mjs`

The source test locks manual OBSERVE authority, lifecycle stages, clean acquisition, research rules, evidence completeness/coverage/order, supply-chain/build evidence, stale-result binding, evaluator separation, source ownership and no MCP expansion.

Native shell on-device does not provide Node, so the new JS regression test cannot be honestly claimed executed locally in this source session. It is wired into root `npm run check` and must run in Builder/source-validation environment. Android/Gradle compile and installed-device abuse remain separate gates.

### Rollback

Remove:
- `RiftCliPatchLifecycleV1.kt`;
- `RiftResearchLedgerV1.kt`;
- Experimental CLI lifecycle branch/status/help additions;
- focused test and docs/source declarations.

Existing Patches 1–5 evidence services remain independent and continue to work.

## Build-validation hotfix — verification marker date contract

Builder run `35371827181` for source `84c0a39c7f7e8e2edf27529b566460dd7ef8f087` passed source integrity and all code/wiring checks, then failed only because `scripts/validate-rift-docs.mjs` still hard-coded `2026-09-17` while the subsystems changed by Patches 1–5 had been correctly re-verified on `2026-09-18`.

The validator now checks verification-marker class plus a valid non-future ISO audit date instead of one global hard-coded date. This preserves per-subsystem verification history and removes the false requirement that untouched source must claim a newer audit date. No runtime, MCP, filesystem, Git, Local Agent or acceptance authority changed.

## Patch 5 — Semantic diff and Project Intelligence V2 impact mapping

### What changed

Added a candidate-bound semantic impact layer that reuses Project Intelligence V2 rather than trusting the patch author to declare affected APIs, callers, tests or documentation.

### Where

Primary source:
- `RiftSourceIntelligenceV2.kt`
- `RiftWorkspaceRecords.kt`
- `RiftToolSandbox.kt`
- `RiftToolHost.kt`

Validation:
- `scripts/test-rift-semantic-impact-v1.mjs`
- exact Gradle Kotlin source snapshot
- Workspace/MCP/Engine/build-validation documentation

### Why

Patch 4 proves exactly which bytes changed, but a physical diff cannot by itself determine which symbols, imports, callers, tests, documentation owners or build surfaces can be affected. The semantic layer must derive that scope independently from the frozen candidate rather than accepting a model-provided list.

### How

`RiftSourceIntelligenceV2` is the single lexical parser used by both normal PI-v2 indexing and before/after semantic comparison. The previous private symbol/dependency parser was removed from `RiftToolSandbox` so the two evidence paths cannot drift.

Workspace Records derives an internal semantic seed from the exact Patch Manifest V1 candidate. The seed includes exact changed-path identities plus bounded before/after source text. It is not an MCP method and cannot accept a caller-selected path scope.

PI-v2 then derives project roots from the changed paths, refreshes the current index, computes added/removed symbols, signature changes, added/removed dependencies, conservative API-surface changes, current dependencies, current dependents, changed-symbol references, relevant tests, documentation ownership from `docs/SOURCE_OWNERSHIP.md`, nearest README fallbacks, and global project docs. Build/config and documentation changes are classified separately.

The deterministic evidence payload is hashed as `semanticImpactSha256` using Patch Manifest canonical hashing. Cache-refresh diagnostics are attached after that hash and are not part of semantic identity.

The process-owned ToolHost exposes only an internal `candidateImpactAsync` seam for the future Local Agent. It is deliberately absent from `tools()`, aliases and MCP backend mappings.

### Bounds and completeness

Semantic seed:
- maximum 4096 changed paths;
- maximum 1024 changed source files;
- maximum 8 MiB combined before/after source text.

Impact:
- maximum 32 derived project roots;
- maximum 1000 changed symbol names in the output;
- maximum 80 changed symbols used for one-pass textual reference discovery;
- maximum 800 references;
- maximum 800 dependency rows;
- maximum 800 dependent rows;
- maximum 300 tests;
- maximum 300 documentation targets;
- maximum 128 changed source targets for test-affinity expansion.

Crossing a bound, missing before/after source text, a truncated PI index, or a truncated semantic delta adds an explicit incomplete reason. The system never silently calls partial impact evidence complete.

### Effects

Patch 5 can discover affected code/tests/docs from the candidate itself and bind that analysis to the candidate hash. It still cannot accept, deny, publish, advance trusted state or block existing workflows. Development mode remains OBSERVE.

The parser is intentionally a bounded lexical Project Intelligence layer, not a compiler AST or proof of correctness. Later validation patches must still require language/compiler/build/tests and may deny incomplete semantic evidence.

### Validation

Source audit verifies one shared parser owner, no returned private parser duplicate, exact candidate-derived seed, bounded incomplete semantics, deterministic impact hashing, ownership-ledger lookup, internal ToolHost routing, unchanged 18-tool MCP catalog, exact source declaration and focused-test wiring.

### Rollback

Remove `RiftSourceIntelligenceV2.kt`, restore the previous private PI-v2 parser in `RiftToolSandbox`, remove candidate-impact/semantic-seed integration, focused test/docs/source declaration, and leave Patch 4 physical manifest evidence intact.

## Patch 4 — Immutable candidate manifest and tamper-evident record chain

### What changed

Added deterministic candidate identity and tamper-evident evidence primitives without enabling patch blocking or trusted-state promotion.

### Where

Primary source:
- `RiftPatchManifestV1.kt`
- `RiftWorkspaceRecords.kt`

Validation:
- `scripts/test-rift-patch-manifest-v1.mjs`
- exact Gradle Kotlin source snapshot
- Workspace/Workspace Records/MCP/build-validation documentation

### Why

Patch Sessions identify writer provenance, but provenance alone does not bind an approval to exact bytes. Patch 4 creates a content-derived candidate identity and a forward hash chain so later validation stages can prove which base tree, result tree, structural changes and retained evidence were evaluated.

### How

The manifest uses deterministic canonical JSON and SHA-256. Tree identity is derived from sorted path/kind/size/content-SHA fields; mtimes and freeze timestamps are excluded. Change-set and structural-diff digests are separate. The sealed manifest contains the operational checkpoint identity, current tree identity, every changed path with before/after content identity, structural identity relations, retained patch-session evidence, current record-chain integrity and the current trusted-checkpoint state.

`freezeCandidate()` is internal-only. It writes a canonical manifest to the private records store under its SHA-256 filename. Existing identical manifests are verified byte-for-byte. No MCP tool exposes freeze or trusted promotion.

New event records are sealed with chain version, chain epoch, previous-record hash and record hash. Legacy pre-Patch-4 event files remain readable but are outside the new chain epoch. Pruning persists a verification anchor and pruned-through sequence before deleting old records. Startup may fast-forward a lagging persisted head only when the retained chain proves the old head is an ancestor; arbitrary mismatches are not silently healed.

Operational checkpoints now carry a recorder sequence. Provenance completeness is decided from checkpoint/pruning sequence boundaries rather than timestamp guesses. Trusted checkpoint fields exist as inert state only; no source path can promote them yet.

### Effects

`rift_workspace_diff` remains read-only but now has source support to report:
- operational checkpoint identity;
- separate trusted-checkpoint state;
- candidate manifest summary;
- record-chain integrity.

Candidate byte changes alter result-tree/change-set identity. Base changes alter base-tree identity. Evidence changes can also alter the manifest SHA, forcing later validation to re-evaluate rather than inherit stale approval.

### Bounds and risks

- maximum frozen manifests: 512;
- maximum one frozen manifest: 8 MiB;
- candidate workspace-path bound: 50000;
- event record retention remains 2000;
- pre-Patch-4 legacy records are not retroactively hash-chained;
- migration from an old operational checkpoint has unknown checkpoint sequence until a new checkpoint is created, so session-evidence completeness must remain false rather than guessed;
- trusted promotion is intentionally absent until the Local Agent policy gate exists.

### Validation

Static source/impact audit verifies deterministic hashing, immutable-by-hash storage, chain sealing/verification, sequence-based pruning evidence, crash-safe head recovery, no MCP freeze mapping, exact Gradle source declaration and focused-test wiring. Android/Gradle compile and device proof remain required before installed-runtime promotion.

### Rollback

Patch 4 can be reverted by removing `RiftPatchManifestV1.kt`, its Workspace Records integration, test/docs/source declaration and private-manifest/chain state readers. Existing private manifest/event files are non-workspace metadata and must not be treated as workspace source during rollback.

## Patch 3 — Patch sessions and provenance

### What changed

Added bounded writer provenance that correlates MCP, native Shell, native Editor, Dev Lab and native Git mutations with asynchronous Workspace Records events.

### Where

Primary source:
- `RiftPatchSessions.kt`
- `RiftWorkspaceRecords.kt`
- writer integrations in `RiftToolSandbox.kt`, `RiftNativeShell.kt`, `RiftNativeWorkspaceApps.kt`, `RiftNativeDevLab.kt`, `RiftNativeGit.kt`
- optional `intent` schema field in `RiftToolHost.kt`

Validation:
- `scripts/test-rift-patch-sessions.mjs`

### Why

FileObserver delivery occurs after a writer may have returned. A simple thread-local or “last writer” value could falsely attribute an unrelated later change.

### How

Writers declare bounded target paths before mutation, capture before-state, and commit short-lived claims after successful mutation. Exact file/deletion claims are correlated against resulting state and SHA-256. Directory replacement flows are explicitly lower-confidence scope claims. Claims expire after 15 seconds and are bounded. Unknown writers are recorded as `unattributed-local`; provenance is never guessed.

Only explicit `workspace/...` and `D:/Workspace/...` forms enter workspace provenance, preventing unrelated RiftFS paths from stealing workspace events.

### Effects

Workspace Records events now contain `patchId` and structured provenance. Dev Lab and Git receipts may surface patch IDs. MCP tool count remains 18. The optional intent field is evidence only and does not change permission classification.

### Bounds and risks

- 512 paths per provenance session;
- 4096 active claims;
- 15-second claim lifetime;
- intent <=500 characters;
- origin/operation/request labels <=120 characters;
- scope-bound directory claims are not cryptographic authorship proof.

### Validation

Source-first impact audit verified all five writer integrations, exact 43-file Kotlin snapshot at Patch 3 freeze, test/package ownership, strict workspace-only ingress, honest unattributed fallback and unchanged 18-tool MCP catalog.

### Rollback

Remove `RiftPatchSessions.kt`, writer hooks, Workspace Records provenance fields, optional intent schema and focused test/docs. Filesystem behavior itself remains owned by the original writer subsystems.

## Patch 2 — File Identity V2

### What changed

Added bounded rename/copy/rewrite identity correlation to Workspace Records.

### Where

Primary source:
- `RiftFileIdentityV2.kt`
- `RiftWorkspaceRecords.kt`
- relation-aware headers in `RiftDiffEngineV2.kt`

Validation:
- `scripts/test-rift-file-identity-v2.mjs`

### Why

Delete+add records could not distinguish structural movement/copy from unrelated file creation, and large rewrites were reported as ordinary modifications.

### How

Exact SHA-256 equality provides exact content-identity evidence for rename/copy candidates. Remaining text candidates use bounded size-prefiltered line similarity. Full reconciliation is limited to 64 candidates per side and 1024 line comparisons. Heuristic results are explicitly marked non-exact and never treated as user intent.

### Effects

Workspace diff/records can surface renamed, copied and rewritten relationships while preserving raw before/after evidence. No mutation or approval authority was added.

### Bounds and risks

Heuristic matching can remain incomplete when the comparison budget is exhausted. Exact content identity does not prove why a user moved or copied a file.

### Validation

Focused source test locks exact-vs-heuristic semantics, bounds, relation-aware Workspace Records wiring and documentation ownership.

### Rollback

Remove `RiftFileIdentityV2.kt` and relation wiring; Workspace Records falls back to independent path changes.

## Patch 1 — Diff Engine V2

### What changed

Replaced Workspace Records' one-middle-block text diff with a deterministic bounded multi-hunk engine.

### Where

Primary source:
- `RiftDiffEngineV2.kt`
- `RiftWorkspaceRecords.kt`

Validation:
- `scripts/test-rift-diff-engine-v2.mjs`

### Why

The previous prefix/suffix algorithm collapsed widely separated edits into one giant replacement and could obscure independent changes.

### How

Normal regions use exact LCS with the actually allocated `(n+1) x (m+1)` matrix capped at 250000 cells. Larger regions use patience-style unique-line anchors with longest-increasing-subsequence ordering and bounded recursion. Huge ambiguous regions fall back to replacement blocks rather than unbounded quadratic allocation.

### Effects

Widely separated edits produce independent hunks. Empty-file creation/deletion and byte-only line-ending changes remain visible. Binary/oversized files remain hash/metadata evidence.

### Bounds and risks

Rendered output remains capped at 64000 characters and 420 changed lines. Large ambiguous regions may intentionally use a non-minimal replacement fallback to protect low-memory Android devices.

### Validation

Focused source test locks algorithm bounds, multi-hunk structure, Workspace Records delegation, source declaration and docs ownership.

### Rollback

Remove `RiftDiffEngineV2.kt` and restore the prior Workspace Records text-diff routine, with the known loss of independent-hunk behavior.


## 2026-09-18 — Rift++ 0.8.0 Gate 1A scalable storage/view candidate

### What changed

- advanced the active Rift++ bootstrap compiler implementation to `0.8.0-bootstrap` while keeping source language `riftpp/1`;
- added persistent `Buffer<T,N>` with capacity up to 100000;
- added read-only zero-copy `Slice<T>` views;
- preserved frozen `Vec<T,N>` capacity/behavior at <=256;
- added RiftVM Buffer/Slice opcodes and persistent 32-way trie Buffer storage;
- Buffer/Slice remain data-only composites and cannot cross generic host-import boundaries;
- Buffer/Slice checkpoint persistence is explicitly denied;
- added `rift-tool semantic-compat` for ongoing frozen-semantic regression checks while leaving `gate0-verify` as the archival exact-reference/drift check;
- updated focused Core/VM/shell tests and strict wiring validation.

### Why

The self-hosted compiler needs token, AST and instruction storage far beyond Vec-256. A separate scalable persistent storage primitive preserves old Vec semantics while providing compiler-scale indexed storage without prematurely introducing pointer/ownership semantics.

Immutable Buffer versions make zero-copy Slice views safe: a Slice references one Buffer version and does not change when later Buffer updates produce a new version.

### Verified before this record

- direct 600-item Buffer/Slice execution passed with stable-view semantics;
- 20000-item Buffer stress passed at 520033 VM steps under the existing 1000000-step VM hard ceiling;
- ordinary compiled 100000-step budget rejected that stress workload as expected;
- full frozen Rift++ semantic compatibility suite passed after Buffer and after Slice;
- RiftLLM+ regression compile remained green;
- RiftOS audit/scan found no new Gate 1A issue beyond the pre-existing RiftSecretStore filename heuristic.

### Remaining promotion gate

Builder/APK/device proof remains required. After installation, run `rift-tool semantic-compat`, the Gate 1A functional fixture, `riftpp self-test`, and host-boundary checks before freezing Gate 1A.


## 2026-09-18 — Rift++ 0.9.0 Gate 1B text/numeric candidate

### What changed

- advanced active compiler implementation to `0.9.0-bootstrap` while retaining source language `riftpp/1` and `rift-exec-v1 / riftvm-1`;
- added `SourceText`, `TextCursor`, persistent `StringBuilder<N>`, numeric text parse/format;
- selected UTF-16 code units for the hot SourceText/cursor/builder representation;
- kept UTF-8 explicit at file/token/provenance/interchange boundaries through on-demand byte accounting;
- preserved frozen compatibility-string UTF-16 code-unit semantics;
- denied SourceText/TextCursor/StringBuilder checkpoint persistence and SourceText hashing through `value_sha256`;
- added independent Core and VM tests;
- added fixed `rift-tool text-model-benchmark` so the installed-device UTF-16/UTF-8 representation costs are recorded under a named benchmark instead of relying on an unpreserved historical multiplier.

### Verified source-side

- UTF-16/code-unit functional Gate 1B program PASS;
- edge/half-surrogate program PASS;
- 70,000-code-unit builder PASS;
- numeric parse/format positive/negative behavior PASS;
- full frozen semantic compatibility suite PASS;
- RiftLLM+ consumer regression compile PASS;
- no language/VM ABI version bump required because changes are additive to valid `riftpp/1` source.

### Promotion boundary

Gate 1B remains **not frozen** until Builder/install/device proof runs `semantic-compat`, exact Gate 1B fixtures, `text-model-benchmark`, self-test, authority-boundary regression and final audit.


## 2026-09-18 — Rift++ Core README maintenance-contract repair

Builder run `35389192989` for source `3c4ed2756ff9874fd011db0b8d22c295109c3efe` failed in `validate-rift-docs.mjs` because `docs/systems/riftpp-core/README.md` was missing the required `## Failure signatures` maintenance heading.

The Gate 1B compiler/runtime code was not the failing gate. The README now restores the required maintenance section with failure-routing signatures derived from the current Core/VM/tooling contracts. The validator itself was not weakened.


## 2026-09-18 — Gate 1B live promotion blockers: UTF-8 determinism + benchmark v2

Installed source `79198ea55704da0e86254ec9e93f93f14611603f` passed semantic compatibility, self-test, primary Gate 1B fixture and host-capability boundaries, but Gate 1B was **not frozen**.

Two blockers were found on-device:

1. `SourceText.utf8_byte_len()` was host-dependent for an unpaired surrogate produced by code-unit slicing. The source-side reference expected canonical U+FFFD UTF-8 length (3 bytes), while the Android/JVM TextEncoder bridge reported 1 byte. RiftVM now computes canonical UTF-8 byte length directly from UTF-16 code units so the result no longer depends on the host encoder.
2. Benchmark v1 was too narrow: it compared raw `charCodeAt` summation with typed-array byte summation. Four live runs consistently favored UTF-8 on that microbenchmark: prepared UTF-8 was roughly 27–30% faster, and UTF-8 prepare+scan roughly 21–23% faster. This result is preserved rather than overridden. Benchmark v2 now separately measures lexer-like sequential traversal and code-unit random access with UTF-8 index preparation/memory cost.

Gate 1B remains pending another Builder/install/device pass with benchmark-v2 evidence.


## 2026-09-18 — Gate 1B benchmark-v2 signed-byte boundary repair

Installed source `35c72ceb49c512ca9fe6a7b667f178f9b4defe05` passed exact-source identity, semantic compatibility, self-test, both positive Gate 1B fixtures, all expected negative diagnostics and capability-boundary tests. Canonical `SourceText.utf8_byte_len()` also matched the 3-byte U+FFFD contract on-device.

The new benchmark-v2 tool itself failed before measurement with `UTF-8 code-unit index length mismatch`. Root cause: the Android QuickJS bridge exposed Kotlin `ByteArray` elements as signed 8-bit values, while the benchmark decoder expected unsigned UTF-8 bytes. The same audit also showed Kotlin/JVM default UTF-8 replacement behavior was not sufficient as the canonical TextEncoder boundary for malformed UTF-16.

The headless runtime now:
- provides one explicit canonical UTF-8 encoder that replaces unpaired UTF-16 surrogates with U+FFFD bytes;
- uses it for all headless UTF-8 byte-limit/hash/state/write paths;
- makes the TextEncoder polyfill normalize bridged bytes into unsigned `Uint8Array` values;
- has focused shell/wiring validation that rejects regression to JVM default `toByteArray(Charsets.UTF_8)` or signed-byte TextEncoder output.

Gate 1B remains unfrozen until the next Builder/install run returns benchmark-v2 measurements.


## 2026-09-18 — Rift++ 0.10 native byte substrate local candidate

After the shared Rift Text reference proved Strict, Replace, streaming, and direct streaming transcode behavior, the next substrate was documented first and then patched locally.

Candidate source:
- compiler `0.10.0-bootstrap`;
- source language remains `riftpp/1`;
- target remains `rift-exec-v1 / riftvm-1`;
- checked `u8` range 0..255;
- existing `Buffer` / `Slice` support `u8`;
- explicit `u8_to_u32`;
- checked `u8_from_u32`;
- u8 participates in checked arithmetic/comparison, display/hash and primitive checkpoint state.

No raw pointer or new host authority was added.

Focused Core/VM tests and strict wiring validation were updated. Builder has no compiler-version pin and already verifies packaged Core/VM bytes against source.

This record does **not** claim runtime PASS: native RiftShell intentionally denies arbitrary Node/process execution and the installed APK still carries the previous compiler. Builder/install/device proof is required before promotion.


## 2026-09-18 — Rift++ bounded u8 device proof route

The existing fixed `riftpp self-test` command was upgraded to schema `riftpp-shell-self-test/3` so the 0.10 native-byte candidate can be proven on-device without adding any generic JS/process execution surface.

The embedded proof covers checked u8 literals/conversions, `Buffer<u8,N>`, `Slice<u8>`, deterministic hashing, compile-time literal overflow rejection and runtime checked arithmetic overflow rejection.

The command still executes with no host imports under the existing Rift++ shell limits. Shell and wiring validators now fail if this bounded u8 proof disappears.
