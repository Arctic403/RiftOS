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


