# Past Fails

This document is a permanent failure ledger for RiftOS/Builder work.

Its purpose is not to replace source, tests, Builder logs, installed-device evidence, or machine phase authority. It preserves failures that escaped pre-push/local Observer checks so later Observer hardening can turn those failure classes into deterministic pre-build evidence.

## Recording rule

From 2026-09-24 forward, every failed RiftOS build must be appended here before the failure is considered closed.

Each entry must preserve:

- date and failure ID;
- exact RiftOS source SHA;
- Builder run ID/run number when available;
- failing stage and exact failing gate;
- observed error;
- root cause after source/log audit;
- whether source/runtime semantics were actually broken or the gate itself was wrong;
- why the current Observer/proof system did not catch it before Builder;
- the concrete Observer evidence/check that would have prevented the failure;
- resolution status and the fixing source SHA once known.

A selected test is **not** equivalent to an executed/passed test. Future Observer work should preserve that distinction explicitly.

Do not delete old entries after fixes. Mark them resolved and retain them as regression/hardening evidence.

## Execution order

Build/runtime defects are fixed immediately when discovered so CLI development does not knowingly continue on broken source. The corresponding Observer prevention lesson is recorded here at the same time, but Observer itself is not redesigned mid-phase merely to absorb each new lesson.

Observer hardening from this ledger is deferred into one dedicated post-N2.12 gate. After the full RiftCLI stack is complete, every still-applicable prevention lesson in this file must be implemented, regression-fixtured, live-proven and returned to a clean Observer baseline before any comparative/performance benchmark begins.

Required order: **fix failures as found → finish RiftCLI/N2.12 → harden all PAST_FAILS Observer lessons → live correctness proof → benchmark.**

---

## FAIL-2026-09-24-001 — N2-M2 source gate regex false-failed fail-soft projection markers

**Status:** RESOLVED — Builder run `36060038399` passed the corrected N2-M2 source gate on source `2bb9fd24e26fdc82e71e9fe8f05f35accc346467`; the build later failed at Kotlin compilation for a separate issue recorded as FAIL-2026-09-24-002.

**Date:** 2026-09-24

**Failed RiftOS source:** `8ecdfefb30545598b6937925b6e68da3a1d92b04`

**Fixing source SHA:** `e0344344c11fb8d83d20bb8baddb76ff1118c4cd` — corrected the over-escaped fail-soft matcher and replaced the adjacent post-commit check with a real whitespace-aware regex. Builder re-verification is still required before this entry becomes RESOLVED.

**Builder run ID:** `36058344281`

**Signing mode:** `alpha-development`

**Failure stage:** Builder source checks, `npm run check` → `npm run check:transport` → `scripts/test-rift-memory-n2-m2-v1.mjs`.

**Observed failure:**

`AssertionError [ERR_ASSERTION]: post-commit projection dirty markers must fail soft`

The assertion at the M2 regression gate required at least four matches from:

`reconcile.match(/runCatching \\{\\s*handle\\.markProjectionDirty/g)`

The match count was zero, so the build stopped before Android/Gradle compilation.

### Root cause

The failure was in the **regression oracle**, not in the intended N2.3 canonical transaction semantics.

`RiftMemoryReconciliationV1.kt` contained the fail-soft projection-marker wrappers, including four `runCatching { ... handle.markProjectionDirty(...) ... }` sites. The JavaScript regex literal was over-escaped. Inside a regex literal, the doubled backslashes changed the pattern being searched for, so the gate did not match the Kotlin source it was intended to validate.

This was therefore a **false build failure caused by a malformed source-test matcher**.

The Builder behaved correctly by failing closed. Gradle/Kotlin compilation was never reached, so this failure provides no evidence either way about Kotlin compilation for source `8ecdfefb...`.

### Why the Observer did not prevent it

Before the push, the Repository Consistency Observer reported clean claims/consistency/integrity/contracts and the proof planner selected the three N2 memory regressions with deep verification and zero unresolved obligations.

The hole is that **test selection was treated as sufficient planning evidence even though the newly changed test gate had not actually executed successfully**. The device-side native shell could not run Node, so there was no fresh execution proof for `scripts/test-rift-memory-n2-m2-v1.mjs` before the Builder run.

The Observer also had no static oracle-sanity check capable of noticing that a changed regex/cardinality assertion could not match the current target source.

### Observer coverage that would have prevented this failure

Primary prevention requirement:

1. **Changed-test execution evidence obligation.** When a regression/source-gate script is added or modified, the proof planner must keep an unresolved obligation until there is fresh execution evidence for that exact test against the exact candidate source SHA. “Selected test” must never be promoted to “verified test” without execution/pass evidence. If the local environment cannot execute the required runner, the candidate must remain verification-required rather than returning zero unresolved obligations.

Secondary hardening:

2. **Static source-oracle sanity.** For deterministic source-pattern assertions introduced or changed in maintained regression scripts, Observer/Validator should detect obvious impossible matcher/cardinality combinations where practical. In this case, a matcher claiming `>= 4` required source occurrences should have been cross-checked against the target file before the proof was considered satisfiable.

3. **Evidence provenance binding.** Any future executed-test evidence used to discharge the obligation should bind at minimum the candidate source SHA, test path/hash, runner identity/version, exit status, and relevant output hash so stale test results cannot satisfy a later candidate.

### Future regression target

When Observer is upgraded to consume execution evidence, this failure should become a fixture proving:

- changed gate selected but not executed → unresolved;
- changed gate executed and failed → unresolved/failing;
- changed gate executed and passed against the exact candidate → resolvable;
- stale pass from another source SHA → rejected;
- malformed static matcher/cardinality detectable where the static oracle can prove the mismatch.

---

## FAIL-2026-09-24-002 — N2-M2 Kotlin nullability compile failure in contradiction event path

**Status:** RESOLVED — Builder run `36067080197` / run number `350` compiled and installed source `18f1156075e08cb94573a9392031ac64552313f2`; live `riftMemoryN2M2.ok=true` and the nullability fix is therefore verified.

**Date:** 2026-09-24

**Failed RiftOS source:** `2bb9fd24e26fdc82e71e9fe8f05f35accc346467`

**Fixing source SHA:** `3ab31e33bda34972c9de4abb8598a51c650c74db` — added the explicit `prior != null` guard on the contradiction-event call path and permanently gated that non-null proof in the M2 regression. Builder re-verification is required before this entry becomes RESOLVED.

**Builder run ID:** `36060038399`

**Signing mode:** `alpha-development`

**Failure stage:** Android/Gradle Kotlin compilation after all RiftOS source gates passed.

**Observed failure:**

`RiftMemoryReconciliationV1.kt:527:78 Argument type mismatch: actual type is 'RiftCanonicalMemoryRecordV1?', but 'RiftCanonicalMemoryRecordV1' was expected.`

The failing call was:

`appendContradictionEvent(handle, tx, contradictionCandidate, prior)`

inside a branch that checked `conflict && contradictionCandidate != null`, but did not also prove `prior != null` to the Kotlin compiler.

### Root cause

`persistRecordDecision(...)` accepts `prior: RiftCanonicalMemoryRecordV1?`.

The equal-authority contradiction caller logically supplies a non-null prior/current record, but the callee's branch only guarded `conflict` and `contradictionCandidate`. Kotlin correctly refused to infer that `prior` must be non-null from those unrelated booleans.

This is a real compile-time source defect, not a false Builder failure. Source-owned JavaScript regressions passed, but they did not type-check Kotlin.

### Why the Observer did not prevent it

The proof planner selected the M2 regression and returned zero unresolved obligations, and all source-owned regressions passed. However, the validation view explicitly treats Android/Gradle compilation as an external check. No fresh Kotlin compiler result was required before the candidate was considered locally obligation-complete.

The current Observer also has no Kotlin type-aware nullability analysis capable of proving that a nullable argument reaches a non-null parameter at this call site.

### Observer coverage that would have prevented this failure

Primary prevention requirement:

1. **Compiler execution evidence obligation for Kotlin changes.** Any candidate that changes maintained Kotlin/Java/native build sources must retain an unresolved build-validation obligation until a compiler result is bound to the exact candidate SHA. Source-pattern regressions alone cannot discharge compile/type obligations.

Secondary hardening:

2. **Kotlin nullability/type-flow evidence.** Future semantic analysis may add compiler-grade or language-server-grade diagnostics for nullable-to-non-null calls, smart-cast invalidation, overload resolution, and other type errors. If implemented, those diagnostics should be evidence-only and must not replace the real compiler gate.

3. **Proof-state separation.** Observer should distinguish `source-regressions-passed` from `compiler-passed`. A candidate may be source-clean while still remaining build-unverified.

4. **Evidence provenance binding.** Compiler evidence must bind candidate source SHA, Builder/Gradle/Kotlin compiler identity, ABI/build variant, exit status, and log/output hashes so stale compile success cannot satisfy a later source candidate.

### Future regression target

When Observer gains compiler-evidence obligations, this failure should become a fixture proving:

- Kotlin source changed + no compile evidence → unresolved;
- source regressions pass but Kotlin compile fails → build-unverified/failing;
- stale compile pass for a different SHA → rejected;
- exact candidate compile pass → compiler obligation resolvable;
- nullable argument into non-null parameter is surfaced by semantic diagnostics when that analysis exists.

---

## FAIL-2026-09-24-003 — N2-M2 promotion metadata rejected by stale N2-M1 lifecycle assertion

**Status:** RESOLVED — Builder run `36069462067` / run number `352` compiled and installed source `0b35904c90039a5ee5acef3e801085a89ffdb2c0`; the corrected M1 lifecycle gate passed, live M1/M2 diagnostics remained green, and post-install Observer continuity returned zero findings with proofs `mode=none`.

**Date:** 2026-09-24

**Failed RiftOS source:** `86adc7c59700e36f031500130d4e7920ace73eb7`

**Fixing source SHA:** `9c21d16f3cc008307cd463b624d814cc8aeb4374` — replaced the stale whole-program M1 lifecycle-summary assertion with structured N2-M1 macro evidence and relaxed the later-stage runtime check to the invariant that canonical N2 runtime remains inactive. Builder re-verification is required before this entry becomes RESOLVED.

**Builder run ID:** `36068835303`

**Signing mode:** `alpha-development`

**Failure stage:** Builder source checks, `npm run check` → `npm run check:transport` → `scripts/test-rift-memory-n2-m1-v1.mjs`.

**Observed failure:**

`AssertionError: assert.ok(phase.programStatus.includes('N2.0-N2.2 PROMOTED / N2-M1 PROMOTED'))`

The N2-M2 promotion commit correctly advanced machine lifecycle authority to:

`N2.0-N2.4 PROMOTED / N2-M1 + N2-M2 PROMOTED; N2.5-N2.12 PENDING`

but the older M1 regression still required the previous exact program-status wording.

### Root cause

This was a **stale regression-oracle dependency**, not a failure of N2-M1 or N2-M2 runtime semantics.

The M1 regression already independently checks the actual N2.1/N2.2 phase statuses, promotion source SHA and Builder run number. Its additional `programStatus.includes(...)` assertion unnecessarily coupled an M1 invariant to a mutable whole-program lifecycle summary. Promoting a later phase therefore made the older regression fail even though all M1 evidence remained unchanged and valid.

The correct invariant for the M1 gate is that N2.1/N2.2 remain promoted with their frozen evidence and the N2-M1 macro remains promoted. It must not freeze later-phase lifecycle wording.

### Why the Observer did not prevent it

The N2-M2 promotion candidate changed `riftmemory/n2-phase-authority.json` and the Observer proof plan reported deep verification with two selected tests and zero unresolved obligations. The affected M1 regression was **not selected**, even though it consumes the changed phase-authority file.

This exposes a dependency-impact hole: changing machine lifecycle authority did not propagate to every maintained regression that reads that authority.

### Observer coverage that would have prevented this failure

Primary prevention requirement:

1. **Test-dependency impact propagation.** A change to a machine-authority/config source must select every maintained regression that directly or semantically consumes that source. `riftmemory/n2-phase-authority.json` changes must therefore pull in N2 contract, M1, M2 and any later phase/lifecycle tests that read it.

2. **Mutable-summary coupling detection.** Phase-specific regressions should not freeze mutable whole-program summary strings when more stable structured phase/macro fields exist. Observer/Validator should flag exact or partial lifecycle-summary assertions in older phase tests when the same invariant can be expressed against structured machine authority.

3. **Selected-test execution evidence.** Impact selection must still require fresh execution/pass evidence for every selected affected test on the exact candidate SHA before obligations resolve.

4. **Authority-consumer graph.** Future Project Intelligence should model `readFile/readJson` test dependencies on machine-authority files so lifecycle/config edits produce deterministic reverse-impact closure.

### Future regression target

When Observer is hardened around this failure, fixtures should prove:

- phase-authority edit selects every direct consumer regression;
- later-phase promotion does not invalidate frozen earlier-phase evidence;
- older phase tests assert structured phase/macro invariants rather than mutable whole-program wording;
- omitted authority consumer leaves an unresolved impact/proof obligation;
- exact candidate execution of all impacted lifecycle tests is required before promotion metadata is considered green.



