# Patch 8 — Documentation / Project-State Parity Gate

## Status

Design contract for the OBSERVE-only Patch 8 implementation.

Patch 8 turns repository/documentation consistency into a deterministic plan plus structured review evidence. It does not give documentation prose automatic trust.

## Authority

Patch 8 stays inside the Local Agent / Experimental RiftCLI validation path. It adds no MCP tool, publication authority, trusted-checkpoint promotion, autonomous prose rewriting, or network authority.

Canonical implementation owner: RiftDocumentationParityV1.kt.

## Inputs

The gate consumes Patch Manifest V1 candidate identity, PI-v2 candidate impact, current repository bytes, docs/SOURCE_OWNERSHIP.md when present, changed-documentation/build-config sets, governance surfaces, and candidate-bound documentation evidence. The caller never supplies its own changed-source list.

## Deterministic parity plan

Schema: rift.documentation-parity-plan/1.

The plan contains project root, candidate manifest identity, maintained source/build/test changes, current ownership mapping, owner docs, API/dependency-change flags, governance surfaces, hard issues, and a deterministic plan SHA-256.

Maintained candidate categories are source, build-config, and test.

### Ownership rules

When maintained paths changed, docs/SOURCE_OWNERSHIP.md is required. Hard failures include missing ownership for changed/added maintained paths, deleted paths still present in ownership, duplicate source entries, unsafe paths, missing owner docs, ownership rows with only bookkeeping owners, and bounded-ledger overflow. `docs/PATCH_HISTORY.md` and `docs/SOURCE_OWNERSHIP.md` are bookkeeping surfaces and cannot by themselves satisfy a substantive owner-document update.

Adding or deleting a maintained path requires the ownership ledger itself to change in the candidate.

### Mandatory owner-document updates

At least one exact substantive owner doc (README/spec, not PATCH_HISTORY/SOURCE_OWNERSHIP bookkeeping) must change when a maintained change is provably documentation-significant:
- file added, deleted, or type-changed;
- source API surface changed according to PI-v2;
- source dependency/import surface changed according to PI-v2;
- build-config changed.

A modified test does not automatically require owner prose changes unless another deterministic rule requires it.

### Patch history

If source/build/test files changed and docs/PATCH_HISTORY.md exists, it must change in the candidate. Patch history remains evidence, not authority.

### Governance reviews

When present, Patch 8 reviews README.md, ROADMAP.md, docs/PROJECT_STATUS.md, docs/SOURCE_OWNERSHIP.md, and docs/PATCH_HISTORY.md. Each surface is UPDATED or UNCHANGED_VALID. UPDATED must match a real documentation change. UNCHANGED_VALID needs a bounded reason. A deterministic changeRequired surface cannot use UNCHANGED_VALID.

## Structured documentation evidence

Generic lifecycle documentation evidence gains a documentationParity object with schema rift.documentation-parity/1, exact planSha256, one source review for every maintained changed path, and one governance review for every present governance surface.

Each source review carries exact sourcePath, exact ownerDocs copied from the plan, disposition UPDATED or UNCHANGED_VALID, and a bounded reason. Missing, duplicate, unknown, or caller-substituted owner lists fail completeness. ownerUpdateRequired requires UPDATED.

Every governance surface gets one exact review. Unknown/missing/duplicate ids fail completeness. changeRequired requires UPDATED; otherwise UNCHANGED_VALID is permitted with a reason.

## Candidate binding

Normalized parity evidence carries candidate manifest SHA-256, semantic-impact SHA-256, and parity-plan SHA-256. Final evaluation recomputes the plan from the current candidate. Any hash mismatch is stale evidence and WOULD_DENY.

## What it proves

Patch 8 can prove ownership coverage, stale/deleted ownership cleanup, owner-doc existence, required owner-doc changes, required patch-history changes, complete source/governance review coverage, and exact candidate binding. It does not pretend arbitrary English prose can be mathematically proven true; the independent evaluator still re-checks prose against source.

## Bounds

- ownership ledger: 1 MiB
- ownership rows: 5000
- owner docs/source: 32
- maintained candidate rows: 4096
- source reviews: 4096
- governance reviews: 16
- review reason: 2000 chars

Bounds fail incomplete rather than silently dropping required evidence.

## Failure signatures

- new source without current ownership row -> deny
- deleted source still owned -> deny
- owner README missing -> deny
- API/dependency/build change without required owner-doc change -> deny
- source/build/test change without required PATCH_HISTORY update -> deny
- source or governance review omitted -> deny
- caller-selected owner-doc subset -> deny
- mandatory update claimed UNCHANGED_VALID -> deny
- roadmap/status/root README silently skipped -> deny
- imported plan hash differs from final plan -> stale evidence / deny
- new MCP/trust/publish authority -> architecture regression

## Existing validator relationship

scripts/validate-rift-docs.mjs remains the repository-wide hygiene validator for global source ownership, required README maintenance sections, verification markers, and broken links. Patch 8 adds candidate-specific parity and staleness binding.