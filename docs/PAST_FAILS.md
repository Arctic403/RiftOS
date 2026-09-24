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

**Status:** OPEN — documented; source fix not recorded here yet.

**Date:** 2026-09-24

**RiftOS source:** `8ecdfefb30545598b6937925b6e68da3a1d92b04`

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

