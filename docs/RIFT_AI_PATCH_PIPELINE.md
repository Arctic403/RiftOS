# AI-Assisted Patch Pipeline

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-18.**

This document defines the canonical **input/output path between RiftCLI/Local Agent and an AI** for repository modification.

The executable OBSERVE-only implementation is owned by:
- `RiftCliPatchLifecycleV1.kt`;
- `RiftResearchLedgerV1.kt`;
- Experimental RiftCLI.

Detailed source contract: [Experimental CLI / Patch Lifecycle V1](systems/experimental-cli/PATCH_LIFECYCLE_V1.md).

## Design rule

The AI does not get to say “I looked at these files, therefore the patch is complete.”

The system flow is:

```text
clean repo / immutable source revision
              ↓
CLI acquisition + full bounded inventory
              ↓
AI understands repository + ownership
              ↓
external research + claim ledger
              ↓
document implementation intent
              ↓
guarded patch through existing tools
              ↓
CLI-derived actual candidate
              ↓
document audit
              ↓
code audit
              ↓
security / dependency / test / build evidence
              ↓
end-to-end verification + rollback evidence
              ↓
freeze exact manifest + semantic impact
              ↓
bounded evaluation packet → independent AI
              ↓
AI verdict + exact defects + echoed hashes
              ↓
CLI verifies subject identity
              ↓
WOULD_ACCEPT / WOULD_DENY only
```

## IN path: repository to AI

### ACQUIRE

Prefer:

```text
rift-cli lifecycle begin-sync D:/Workspace/<repo> <goal...>
```

The CLI synchronizes through native RiftGit, requires a clean tree, captures Git HEAD and deterministic Project Export snapshot, enumerates governance/build/generated boundaries, and creates an operational lifecycle checkpoint.

If another Workspace project already has uncheckpointed changes, lifecycle acquisition refuses to move the global operational checkpoint.

### UNDERSTAND

The AI must understand the project before editing:
- tree/layout;
- subsystem ownership;
- APIs/imports/references;
- tests;
- build/CI;
- dependency manifests;
- generated/vendor surfaces;
- docs/README;
- ROADMAP;
- TODO/TASK when the project actually has them;
- source ownership;
- patch history.

RiftOS currently uses `ROADMAP.md` as its backlog owner and has no dedicated TODO/TASK governance file.

The AI then imports complete `understanding` evidence against the still-unchanged acquired base. Research cannot complete before this evidence exists, so UNDERSTAND is a verifiable lifecycle stage rather than advisory prose.

### RESEARCH

Research assumptions are recorded as `rift.research-ledger/1` evidence after complete understanding evidence exists.

Critical claims require authoritative support. Source retrieval is performed by the AI/research environment, not by a hidden RiftOS network agent.

### DOCUMENT_INTENT — document intent

Research must be imported before design evidence.

Design evidence must be imported before source/build-config mutation and describe the intended code/docs/tests/security/compatibility/rollback changes.

## PATCH path

The AI patches through existing permissioned tools.

RiftCLI lifecycle adds no generic writer. Patch Sessions/Workspace Records observe the actual mutation state.

## OUT path: patch result back to AI

The output path is not “patch finished.”

The CLI reconstructs the final candidate from repository truth and requires separate evidence for:
- `DOCUMENT_AUDIT` documentation parity, driven by `rift-cli lifecycle documentation-plan <session>` and exact `rift.documentation-parity/1` evidence;
- `CODE_AUDIT` code audit;
- security;
- dependencies/supply chain;
- tests;
- optional build/artifact evidence when available before Patch 13;
- E2E behavior;
- rollback.

Project Intelligence derives affected targets; the patch author cannot shrink the impact scope manually.

### FREEZE

The final packet binds:
- base Git revision;
- base/current source snapshots;
- candidate manifest;
- base/result tree hashes;
- semantic-impact hash;
- evidence-bundle hash;
- policy hash.

### AI_EVALUATION — AI evaluation

`rift-cli lifecycle evaluation <session>` emits the bounded AI-facing packet.

The independent evaluator must return:
- exact subject hashes;
- evaluator id;
- patch actor id;
- independence claim;
- verdict;
- exact structured defects when present.

### LOCAL_VERIFY — local verify

`rift-cli lifecycle verify ...` checks the response against the still-current candidate.

It reports only WOULD_ACCEPT / WOULD_DENY.

OBSERVE V1 cannot:
- trust;
- publish;
- advance trusted checkpoint;
- bypass a missing gate.

## State-of-the-art additions beyond the original workflow

The initial human workflow was:

```text
pull full repo
→ understand layout
→ research
→ docs/README/TODO/everything
→ patch
→ audit documents
→ verify docs/everything
→ audit code
→ verify end-to-end
→ send back for evaluation
```

The implemented contract adds:
- immutable Git revision and Project Export snapshot;
- generated/vendor boundary discovery;
- source provenance fields;
- dependency/lockfile/SBOM/license disposition;
- security/capability evidence;
- build environment identity;
- artifact SHA-256s;
- explicit rollback evidence;
- stale-result invalidation;
- candidate/semantic/evidence/policy hashes;
- declarative evaluator/patch-actor identity separation (not cryptographically authenticated in V1);
- bounded structured defect responses;
- fail-closed incomplete/oversized evidence;
- no silent trust promotion.

These additions are informed by SLSA 1.2, in-toto attestation concepts and NIST SSDF, without claiming formal certification.

## Authority rule

The invariant is:

> AI reasons; Local Agent/CLI correlates evidence and owns mutation/trust boundaries.

A future ENFORCE mode must run the same verification pipeline as OBSERVE. Only authority may change.
