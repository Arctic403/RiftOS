# RiftCLI Patch Lifecycle V1

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-18.**

## Purpose

RiftCLI Patch Lifecycle V1 is the manual, OBSERVE-only orchestration path for AI-assisted repository changes.

Its core rule is:

> The AI may reason about and propose a patch, but it may not choose its own authoritative impact scope or declare its own patch complete. The CLI/Local Agent derives scope and verification evidence from the repository after the patch.

The lifecycle is intentionally layered above the existing RiftOS authorities:
- native RiftGit owns repository synchronization;
- RiftProjectExporter owns deterministic bounded source snapshot identity;
- Workspace Records owns operational checkpoint, patch records, candidate manifests and record-chain integrity;
- Project Intelligence V2 owns semantic impact;
- existing MCP/workspace tools perform permitted edits;
- Experimental RiftCLI owns only the manual orchestration/evidence contract.

It does not add an MCP tool, direct model backend, raw Android shell, hidden process runner, trusted-checkpoint promotion or publication authority.

## Research basis

The architecture was compared against current secure software supply-chain and development-lifecycle guidance before implementation.

Primary references:
- SLSA v1.2 specification: https://slsa.dev/spec/v1.2/
- SLSA Source Track requirements: https://slsa.dev/spec/v1.2/source-requirements
- SLSA Build Track: https://slsa.dev/spec/v1.2/build-track
- in-toto specifications/attestation framework: https://in-toto.io/docs/specs/
- NIST Secure Software Development Framework: https://csrc.nist.gov/projects/ssdf

RiftOS does not claim SLSA certification, in-toto compliance or NIST certification. The useful design patterns adopted locally are:
- immutable source revision identity;
- evidence bound to exact subjects;
- source/build provenance fields;
- separate test/security/SBOM/license evidence;
- continuous/stale-evidence invalidation;
- independent evaluation;
- fail-closed incomplete evidence;
- reproducible verification summaries.

## Source ownership

Primary lifecycle:
- `RiftCliPatchLifecycleV1.kt`

External-research ledger:
- `RiftResearchLedgerV1.kt`

Manual command owner:
- `RiftExperimentalCli.kt`

Evidence providers:
- `RiftNativeGit.kt`
- `RiftProjectExporter.kt`
- `RiftWorkspaceRecords.kt`
- `RiftPatchManifestV1.kt`
- `RiftToolHost.kt`
- `RiftToolSandbox.kt`
- `RiftSourceIntelligenceV2.kt`

Focused regression contract:
- `scripts/test-rift-cli-patch-lifecycle-v1.mjs`

## Enablement and authority

The lifecycle is reachable through:

```text
rift-cli lifecycle ...
```

Only `help` and `contract` are available while Experimental RiftCLI is disabled.

All session/evidence/evaluation actions require the existing process-local:

```text
rift-cli enable CONFIRM-EXPERIMENTAL
```

Process restart returns Experimental RiftCLI to LEGACY/OFF.

Lifecycle V1:
- mode: `OBSERVE`;
- productionReady: false;
- newMcpTools: 0;
- trusted promotion: false;
- publication: false;
- persistent enablement: false.

## Canonical lifecycle

### 1. ACQUIRE

Preferred command:

```text
rift-cli lifecycle begin-sync D:/Workspace/<project> <goal...>
```

The CLI:
1. resolves one project under `D:/Workspace`;
2. requires native Git status clean;
3. performs native RiftGit pull when `begin-sync` is used;
4. requires the tree clean after pull;
5. captures repository, branch and 40-hex Git HEAD;
6. captures deterministic `RiftProjectExporter.snapshotId`;
7. scans the bounded full repository layout;
8. discovers governance documents, dependency/build manifests and generated/vendor boundaries;
9. refuses acquisition if the inventory exceeds 50,000 files or 512 MiB;
10. refuses to reset the global operational baseline if Workspace Records already has uncheckpointed changes elsewhere;
11. creates a new **operational**, never trusted, lifecycle checkpoint.

`begin` performs the same acquisition without pull and therefore represents an explicitly local clean revision.

### 2. UNDERSTAND

The AI work-request packet instructs the model to inspect the repository before patching:
- full top-level/file layout;
- source ownership;
- imports/exports;
- symbols/callers/dependents/references;
- tests;
- CI/build files;
- generated/vendor boundaries;
- public surfaces;
- README/docs;
- ROADMAP;
- TODO/TASK files when present;
- patch history;
- project status;
- source ownership.

RiftOS currently has no dedicated TODO/TASK governance file. `ROADMAP.md` is the current backlog owner. The lifecycle discovers TODO/TASK files automatically in projects that actually use them instead of inventing a duplicate source of truth.

### 3. RESEARCH

External assumptions are represented by `rift.research-ledger/1`.

A bounded ledger records:
- id;
- source type;
- HTTPS URI;
- publisher;
- title;
- retrieval time;
- claim;
- version/date when relevant;
- optional content SHA-256;
- assumptions/claims supported by each source.

Bounds:
- 128 sources;
- 64 assumptions.

Critical supported claims require at least one:
- official;
- specification;
- primary;
- standards-body

source.

Community/secondary material may supplement evidence but cannot alone satisfy a critical claim.

A ledger may say research is not required only when it records a reason.

Research collection is not trust. Final independent AI evaluation is explicitly instructed to re-check critical claims.

Complete base-bound `understanding` evidence must exist before research may complete. Understanding covers the acquired repository layout, governance and build/dependency surfaces and cannot complete after the candidate has already changed.

### 4. DOCUMENT_INTENT

Before source/build-config code changes, the lifecycle requires complete `design` evidence after complete research evidence.

Design evidence must cover the acquired governance and build/dependency surfaces and should state:
- exact problem;
- affected systems;
- implementation plan;
- compatibility;
- tests;
- security/dependency implications;
- documentation changes;
- roadmap/TODO changes;
- risks;
- rollback.

A design report imported after source/build-config code already changed cannot become complete.

### 5. PATCH

Patching stays in existing bounded authorities:
- Rift Code Mode/workspace tools;
- Dev Lab where applicable;
- native Editor/Shell/Git flows already covered by Patch Sessions.

The lifecycle itself does not gain a generic write method.

Expected patch behavior:
- narrow source owner;
- snapshot/hash guards for risky changes;
- transactional edits where available;
- no silent generated/vendor edits;
- no authority expansion;
- no unrelated project changes.

### 6. DOCUMENT_AUDIT

Post-patch documentation evidence is derived from repository truth.

Expected targets include:
- governance docs from both the acquired base **and the current post-patch repository inventory**;
- newly added README/ROADMAP/TODO/TASK/PATCH_HISTORY/PROJECT_STATUS/SOURCE_OWNERSHIP surfaces;
- Project Intelligence owning/changed-documentation docs;
- relevant READMEs;
- ROADMAP;
- TODO/TASK surfaces if present;
- project status;
- source ownership;
- patch history.

The audit must reconcile prose with final source, not the other way around.

### 7. CODE_AUDIT

The next stage must inspect:
- every changed path;
- changed source;
- direct dependents discovered by Project Intelligence;
- API/signature/dependency changes;
- references/callers;
- duplicate functionality;
- dead paths;
- error handling;
- bounds;
- concurrency;
- compatibility.

Code-audit evidence imported before required documentation evidence is flagged by the final policy ordering.

### 8. SUPPLY_CHAIN_SECURITY

Security/dependency evidence covers changed source plus discovered build/dependency manifests.

Dependency/security/build target discovery unions acquired and current dependency/build manifests plus Project Intelligence `changedBuildConfig`, so a patch cannot add a new package/lock/build manifest and omit it from post-patch evidence.

Complete dependency evidence requires a `supplyChain` object with each field explicitly `PASS` or `NOT_APPLICABLE`:
- `lockfileStatus`;
- `sbomStatus`;
- `licenseStatus`;
- `provenanceStatus`.

Security evidence must also cover capabilities/permissions/secrets/boundaries relevant to the changed source.

The lifecycle does not generate an SBOM itself. It requires the project/build pipeline to supply evidence when applicable.

### 9. TEST_BUILD

Project Intelligence V2 selects relevant tests from semantic impact.

For source/build-config changes, Lifecycle V1 requires impact-derived tests evidence before source-candidate evaluation.

External artifact build evidence is **optional in V1** because Patch 13 has not yet implemented a safe candidate→Builder provenance handshake. If build evidence is supplied, it becomes part of the required ordered evidence set and must be complete/current.

Complete supplied build evidence records:
- builder identity;
- toolchain identity;
- source revision;
- one or more artifact names;
- SHA-256 for every artifact.

This is an evidence contract, not yet the Patch-13 cryptographic Builder handshake. External Builder/device proof therefore remains a later promotion/release gate and is not falsely required before the source candidate can be independently evaluated.

### 10. END_TO_END_VERIFY

E2E evidence covers every changed path and re-checks the original goal against the final system.

The intended questions are:
- Was the original request actually satisfied?
- Did every affected route work?
- Did negative/failure cases behave correctly?
- Did docs remain accurate?
- Were any unexplained files introduced?
- Did any adjacent subsystem regress?

### 11. FREEZE

The CLI asks Project Intelligence for current candidate impact and then calls internal Workspace Records `freezeCandidate()`.

The evaluation is bound to:
- session id;
- base Git HEAD;
- base source snapshot;
- current source snapshot;
- candidate id;
- Patch Manifest SHA-256;
- base tree SHA-256;
- result tree SHA-256;
- semantic-impact SHA-256;
- evidence-bundle SHA-256;
- policy SHA-256.

If candidate impact and frozen manifest disagree, evaluation aborts.

### 12. AI_EVALUATION

The CLI returns a bounded `rift.cli-ai-evaluation-request/1` packet through the existing shell/MCP response path.

There is no hidden model backend.

The evaluator is instructed to:
- independently inspect the exact candidate;
- re-check critical research;
- verify documentation parity;
- verify code/semantic impact;
- verify tests/security/dependencies/build/E2E evidence;
- return exact defects rather than a vague rejection;
- echo all subject hashes.

Evaluation packets are capped at 700 KiB. Oversized packets fail instead of truncating evidence.

### 13. LOCAL_VERIFY

Evaluation response schema:

```text
rift.cli-ai-evaluation/1
```

The CLI verifies:
- session id;
- evaluation-bundle SHA;
- manifest SHA;
- semantic-impact SHA;
- evidence-bundle SHA;
- policy SHA;
- `independent=true`;
- non-empty evaluator id;
- non-empty patch actor id;
- evaluator id != patch actor id.

These identity fields are declarative in V1. Local verification reports `independenceAuthenticated=false`; it checks separation claims and subject hashes but does not cryptographically authenticate a human/model identity. ENFORCE must not treat this field pair alone as proof of independence.

Verdicts:
- `ACCEPTABLE_CANDIDATE`;
- `RETURN_DEFECTS`.

Defects are bounded to 256 and must each contain:
- code;
- severity: CRITICAL/HIGH/MEDIUM/LOW/INFO;
- reason;
- fix;
- optional safe path.

Even a valid `ACCEPTABLE_CANDIDATE` response produces only:
- `wouldAccept`;
- `wouldDeny`.

It never sets trusted state and never publishes.

## Evidence import

Evidence files must live outside Workspace under:
- `D:/Documents`; or
- `D:/Temp`.

This prevents evidence input from becoming part of the candidate it describes.

Generic evidence schema:

```text
rift.cli-evidence/1
```

Kinds:
- understanding;
- research;
- design;
- documentation;
- code-audit;
- security;
- dependencies;
- tests;
- build;
- e2e;
- rollback.

Each record has:
- actor;
- summary;
- complete;
- checks;
- targets;
- kind-specific fields.

A non-research record cannot be complete with zero checks.

Any FAIL, WARN or NOT_RUN check makes that evidence incomplete.

Expected targets are derived from acquisition inventory + Patch Manifest/Project Intelligence impact. Missing required targets makes evidence incomplete.

Evidence import is bounded:
- 512 KiB input;
- 96 records/session;
- 256 checks/record;
- 2,000 targets/record.

The importer rejects common secret-bearing field names.

## Stale-result invalidation

Every imported evidence record stores:
- source snapshot id;
- Git HEAD;
- candidate manifest SHA;
- semantic impact SHA.

Understanding must belong to the acquired unchanged base and must precede research.

Research must belong to the acquired base snapshot and must follow complete understanding evidence.

Post-patch evidence must belong to the final manifest.

If another edit changes the candidate after an audit, the older audit becomes stale and final policy reports `STALE_EVIDENCE`.

Pre-patch ordering is:
1. understanding;
2. research;
3. design.

Required post-patch ordering is:
1. documentation;
2. code-audit;
3. security;
4. dependencies;
5. tests;
6. build;
7. e2e;
8. rollback.

Only evidence kinds required for the current candidate participate in order validation.

## Session storage

Lifecycle session/evidence state is app-private inside RiftFS system storage:

```text
<filesDir>/riftfs/system/rift-cli-patch-lifecycle-v1/
```

Legacy sessions from `<filesDir>/rift-cli-patch-lifecycle-v1/` are copied into the canonical system root on access.

It is outside Workspace and cannot be rewritten through normal workspace MCP tools.

Each session records a process epoch plus the last observed project snapshot and candidate manifest. If a later process epoch observes different source/candidate identity, `restartDriftDetected` is permanently recorded; new evidence import and evaluation fail closed and lifecycle status returns the drift receipt. This contains interrupted/external workspace mutation without guessing whether it was intended.

Live stress testing observed untracked fixture deletion across process recreation, but source audit did not prove the deletion owner. The restart-drift guard is therefore a containment/detection control, not a claim that the external deleter was fixed.

This V1 store is bounded evidence state but is not yet the Patch-12 immutable decision trail. Final subject hashes make tampering detectable at evaluation time; later validation patches may add an append-only/hash-chained decision history.

## Commands

```text
rift-cli lifecycle help
rift-cli lifecycle contract
rift-cli lifecycle begin <project> <goal...>
rift-cli lifecycle begin-sync <project> <goal...>
rift-cli lifecycle status <sessionId>
rift-cli lifecycle request <sessionId>
rift-cli lifecycle import <sessionId> <kind> <D:/Documents|D:/Temp json>
rift-cli lifecycle evaluation <sessionId>
rift-cli lifecycle verify <sessionId> <D:/Documents|D:/Temp evaluation.json>
rift-cli lifecycle clear <sessionId>
```

## Critical invariants

- lifecycle mutation commands require manual Experimental CLI enable;
- no new MCP tools;
- no direct model backend;
- no network research fetcher in the lifecycle;
- no raw process/shell authority;
- acquisition must be clean and bounded;
- global operational checkpoint cannot hide unrelated Workspace changes;
- operational checkpoint is not trusted checkpoint;
- understanding precedes research, and research precedes design;
- design precedes source/build-config mutation;
- post-patch audit order is explicit;
- complete non-research evidence has PASS checks;
- impacted targets cannot be silently omitted;
- critical research claims need authoritative support;
- supply-chain status is explicit;
- build artifact digests are explicit;
- stale post-patch evidence cannot inherit approval;
- candidate freeze and semantic impact must agree;
- evaluator and patch actor identities must be separated, but V1 does not cryptographically authenticate those identities;
- evaluator output cannot promote trust;
- oversized evaluation packets fail rather than truncate.

## Failure signatures

- lifecycle action works while Experimental CLI is OFF -> enablement regression;
- a new `rift_cli_*` MCP tool appears -> layering regression;
- acquisition accepts dirty Git state -> source-identity regression;
- lifecycle checkpoints while unrelated Workspace changes exist -> evidence-reset regression;
- truncated full-repo inventory is accepted -> acquisition-completeness regression;
- understanding is missing/not base-bound or imported after candidate mutation -> ordering regression;
- research is imported before understanding or after base source changed -> ordering regression;
- critical research claim has no authoritative source -> research gate regression;
- design completes before research or after code mutation -> sequencing regression;
- complete evidence contains WARN/FAIL/NOT_RUN -> false-completeness regression;
- impacted target missing from a complete report -> scope regression;
- dependency evidence omits SBOM/license/provenance/lockfile disposition -> supply-chain regression;
- complete build evidence lacks environment/artifact SHA -> provenance regression;
- stale candidate evidence passes final policy -> TOCTOU regression;
- candidate impact SHA and frozen manifest disagree -> freeze regression;
- evaluator does not echo exact subject hashes -> evaluation-binding regression;
- same evaluator/patch actor accepted as independent -> independence regression;
- vague/unbounded evaluator defects accepted -> denial-evidence regression;
- `verify` promotes trusted checkpoint or publishes -> authority regression.

## Current limitations / future work

V1 deliberately does not yet:
- fetch research sources itself;
- cryptographically verify remote source content;
- run project tests/builds by itself;
- create SBOMs itself;
- implement hermetic builds;
- cryptographically sign evidence;
- provide cryptographically authenticated evaluator identity/separation;
- provide the Patch-13 Builder provenance handshake;
- provide the Patch-12 immutable/hash-chained decision trail;
- close every mutation bypass for ENFORCE;
- promote trusted state;
- auto-publish;
- auto-enable ENFORCE.

Those remain later validation-program work.
