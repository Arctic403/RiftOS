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

---

## FAIL-2026-09-24-004 — N2-M3 regression asserted shared cognitive constants against the wrong source file

**Status:** RESOLVED — Builder run `36075992479` / run number `355` compiled and installed source `62382a94f50dd6052e1754c1496da2a0f794c0af`; corrected M3 source gates passed, `riftMemoryN2M3.ok=true`, all N2.5/N2.6 diagnostics were true, SQLite integrity was clean, nullable-flow v7 live proof succeeded, regression-literal ownership live proof succeeded, and the 2,048 exact / +1 assertion bound behaved fail-closed as designed.

**Date:** 2026-09-24

**Failed RiftOS source:** `740a10a18f8fcf626b6156b85df1c32aeed82542`

**Fixing source SHA:** `8b337e7812deef9f681a0e9fdd7c323948d4e555` — moves the four shared cognitive-class markers to their actual source owner and adds bounded regression-literal ownership verification to the claims Observer plus its Builder regression. Builder re-verification succeeded on installed source `62382a94f50dd6052e1754c1496da2a0f794c0af`, Builder run `36075992479` / run number `355`.

**Builder run ID:** `36074643676`

**Signing mode:** `alpha-development`

**Failure stage:** Builder source checks, `npm run check` → `npm run check:transport` → `scripts/test-rift-memory-n2-m3-v1.mjs`.

**Observed failure:** `AssertionError: missing N2.6 belief/difference marker: const val BELIEF = "BELIEF"`.

### Root cause

The shared `RiftMemoryCognitiveClassV1` object intentionally lives in `RiftMemoryConsolidationV1.kt` and owns all cognitive class constants, including `BELIEF`, `PREDICTION`, `REFLECTION` and `DISCREPANCY`. The M3 regression correctly checked the N2.5 constants against the consolidation source, but incorrectly repeated those four shared constants in the marker list asserted against `RiftMemoryBeliefDifferenceV1.kt`.

The runtime/source implementation was not missing those classes; the regression oracle pointed at the wrong source owner.

### Why the Observer did not prevent it

Before the regression was fixed, the failed source was checked exactly as HEAD. Claims, consistency, integrity and contracts all reported zero findings and proofs returned `mode=none` / zero obligations because no dirty candidate remained. The installed Observer also had no deterministic rule validating literal marker assertions inside maintained regression scripts against the source file alias they claimed to inspect.

This exposes two related gaps:

1. external Builder execution can disprove a clean pushed HEAD while the repository-only Observer remains unaware of that execution result;
2. regression-oracle literal ownership was not statically validated.

### Observer hardening added

`RiftDocumentationClaimsV1` now includes a bounded `regression-literal-ownership` pass over maintained `scripts/test-*.mjs/js` files. It resolves `const alias = read('path')`, recognizes literal marker loops asserted via `alias.includes(marker)`, and checks every literal against the referenced source file. Missing literals become deterministic blocking findings with rule ID `regression-literal-marker-missing`.

The pass is capped at 2,048 literal assertions and fails closed with `claims-regression-literal-bound`. `scripts/test-rift-documentation-claims-v1.mjs` permanently gates the new Observer rule.

The M3 regression itself is corrected so the four shared cognitive constants are asserted against `RiftMemoryConsolidationV1.kt`; `RiftMemoryBeliefDifferenceV1.kt` is checked only for the N2.6 implementation it actually owns.

### Live proof completed

- installed run `355` produced `regression-literal-marker-missing` for a temporary wrong-owner regression literal and returned claims clean after fixture removal;
- the corrected M3 regression and claims regression passed Builder source gates in run `36075992479`;
- the repository-wide regression-literal cap passed at exactly 2,048 assertions and failed closed at 2,049 with `claims-regression-literal-bound`;
- installed source `62382a94f50dd6052e1754c1496da2a0f794c0af` contains fixing commit `8b337e7812deef9f681a0e9fdd7c323948d4e555` and live-proved `riftMemoryN2M3.ok=true` with clean SQLite integrity.

---

## FAIL-2026-09-24-005 — M3 promotion advanced lifecycle but older M2 regression froze the pre-M3 program summary

**Status:** RESOLVED — Builder run `36082319213` / run number `359` installed source `7be822794fb590be2116e74f9f4302b2a1014bb4`; the live whitespace-only `n2-phase-authority.json` fixture selected contract + M1 + M2 + M3, emitted `authority-consumer-tests=required`, had zero unresolved obligations, and exact restoration returned proofs to `mode=none` / zero changes.

**Date:** 2026-09-24

**Failed RiftOS source:** `754fc0e860a6d4f3a97697c62f84733081f73286`

**Fixing source SHA:** `deaa85a7ff20eff75e3339419f28beded354843a` — removes mutable whole-program lifecycle assertions from M2/M3 regressions, adds bounded machine-authority config-read direct-dependent discovery, and adds required/unresolved authority-consumer proof obligations. Builder and installed live verification are still required before this entry becomes RESOLVED.

**Builder run ID:** `36078454699`

**Signing mode:** `alpha-development`

**Failure stage:** Builder source checks, `npm run check` → `npm run check:transport` → `scripts/test-rift-memory-n2-m2-v1.mjs`.

**Observed failure:** `AssertionError: assert.ok(phase.programStatus.includes('N2.0-N2.4 PROMOTED / N2-M1 + N2-M2 PROMOTED'))`.

### Root cause

M3 promotion correctly advanced mutable N2 lifecycle authority to N2.0-N2.6 promoted. The older M2 regression already checks the stable structured invariants that actually belong to M2 — N2.3/N2.4 status, exact promoted source `18f1156075e08cb94573a9392031ac64552313f2`, exact Builder run `350`, M2 macro promoted and canonical N2 runtime inactive — but it also retained a redundant assertion against the mutable whole-program `programStatus` summary.

That summary is expected to change as later N2 phases promote. The assertion therefore made a frozen M2 regression depend on unrelated later lifecycle wording.

### Why the Observer did not prevent it

The failed HEAD was inspected before any source fix. Claims, consistency, integrity and contracts all returned complete/clean with zero findings; proofs returned `mode=none`, zero selected tests, zero obligations and exact clean baseline `a4f92d9d654407528cbf7a48c30d5f12b4aa5ab503d2903ce8952d1ecbf0aecc`.

During the preceding M3 promotion candidate, proofs selected only `test-rift-memory-n2-contract-v1.mjs` and `test-rift-memory-n2-m3-v1.mjs`. It did not select `test-rift-memory-n2-m2-v1.mjs`, even though that maintained regression directly reads `riftmemory/n2-phase-authority.json`. Builder then disproved the candidate by executing that omitted consumer.

This confirms the authority-consumer impact gap already foreshadowed by FAIL-003: direct test/config consumers of mutable machine authority are not guaranteed to enter the proof closure, and older phase regressions can still freeze mutable global summaries.

### Observer hardening added

1. PI-v2 semantic impact now scans bounded maintained test sources for exact literal reads of changed machine-authority/build-config files and emits deterministic `config-read` direct-dependent edges. Supported forms cover the current `read(...)`, `*Read(...)`, direct `readFileSync(...)` and `readFileSync(path.join(...))` consumers. The scan is bounded by the existing candidate target/test limits and fails closed with `config-read-target-bound` or `config-read-test-bound`.
2. `RiftSourceIntelligenceV2.isMachineAuthorityPath(...)` now exposes the same machine-authority definition used by build-config classification so the proof planner does not maintain a separate authority list.
3. `RiftProofObligationsV1` now emits an explicit `authority-consumer-tests` obligation for machine-authority changes. Direct consumers make it `required`; no directly evidenced consumer makes it `unresolved` and adds `authority-consumer-evidence-missing`.
4. `scripts/test-rift-semantic-impact-v1.mjs` permanently gates the config-read dependency scan and verifies that the current N2 phase-authority reader set contains contract + M1 + M2 + M3.
5. `scripts/test-rift-proof-obligations-v1.mjs` permanently gates required-vs-unresolved machine-authority consumer behavior.
6. The stale M2 global-summary assertion is removed, and the same mutable-summary assertion is proactively removed from M3. Their structured per-phase/macro promotion evidence remains authoritative.
7. Exact-candidate execution remains Builder authority; the new Observer layer guarantees the complete bounded direct-consumer set is explicit before Builder.

### Live proof completed

- run `359` passed the corrected M2/M3 regressions plus semantic-impact and proof-obligation gates;
- a whitespace-only valid JSON edit to `riftmemory/n2-phase-authority.json` made live proofs select exactly contract + M1 + M2 + M3;
- `authority-consumer-tests` was `required` with all four tests in `satisfiesBy`, zero unresolved obligations and no incomplete reasons;
- exact JSON restoration returned proofs to `mode=none`, zero changes/tests/obligations and a clean Git tree.

---

## FAIL-2026-09-24-006 — Semantic-impact regression silently scanned zero test files because its discovery regex was over-escaped

**Status:** BUILDER GREEN / LIVE DISCOVERY DETECTION PASSED / CLEAN-RESTORE PENDING ESCAPED-LITERAL FALSE-POSITIVE REBUILD. Builder run `36082319213` / run number `359` installed source `7be822794fb590be2116e74f9f4302b2a1014bb4`; the temporary zero-match discovery fixture produced `regression-discovery-pattern-empty` exactly as required.

**Date:** 2026-09-24

**Failed RiftOS source:** `274facf5148e008b1bc093c2eb62e55d882145cc`

**Fixing source SHA:** `8b4ea956901c` — corrects the over-escaped test discovery regex and adds bounded claims-oracle regression-discovery sanity. **Decoder follow-up source SHA:** `9ea19f59971be96e6933aabb643d7bd70a76f3df` — normalizes escaped JavaScript literals in the generalized claims parser and strengthens the independent documentation-claims regression. One final rebuild/live clean-restoration proof is still required before this entry becomes RESOLVED.

**Builder run ID:** `36080113265`

**Signing mode:** `alpha-development`

**Failure stage:** Builder source checks, `npm run check` → `npm run check:transport` → `scripts/test-rift-semantic-impact-v1.mjs`.

**Observed failure:** `AssertionError: phase-authority direct consumer not detected: test-rift-memory-n2-contract-v1.mjs`.

### Root cause

The new FAIL-005 regression attempted to discover maintained test files with the JavaScript regex literal `/^test-.*\\\\.(?:mjs|js)$/`. In a JavaScript regex literal, `\\\\.` matches a literal backslash followed by any character rather than a literal dot. Normal files such as `test-rift-memory-n2-contract-v1.mjs` therefore did not match, so the regression scanned zero candidate test files and incorrectly concluded that the known direct phase-authority consumer was absent.

The actual phase-authority literal-read matcher was not the failing component; test-file discovery prevented it from seeing any maintained regression files.

### Why the Observer did not prevent it

The failed HEAD was inspected before any source fix. Claims, consistency, integrity and contracts were complete/clean with zero findings; proofs returned `mode=none`, zero selected tests and zero obligations. The claims oracle validates regression literal ownership but does not currently validate that a maintained regression's own discovery/filter regex actually selects any sibling maintained test files.

### Observer hardening added

1. `RiftDocumentationClaimsV1` now scans bounded maintained regressions that explicitly enumerate `scripts` with `readdirSync(...)` and validates their `.filter(name => /.../.test(name))` discovery regexes.
2. Discovery regexes are compiled deterministically against the real sibling maintained `test-*.mjs/js` names. Invalid/unsupported patterns or a compiled regex that matches zero sibling tests become contradicted `regression-discovery-pattern` claims and blocking `regression-discovery-pattern-empty` findings.
3. The scan is bounded by `MAX_REGRESSION_DISCOVERY_PATTERNS=512`; overflow fails closed with `claims-regression-discovery-bound`.
4. `scripts/test-rift-documentation-claims-v1.mjs` permanently gates the new coverage label, bound, helper call, claim kind, finding code and incomplete reason.
5. Exact Builder execution remains authoritative; this claims-layer rule only proves that a maintained regression's own file-discovery oracle is non-vacuous.

### Live proof status

- run `359` passed semantic-impact + documentation-claims + proof-obligation Builder gates;
- a temporary zero-match discovery regex produced `regression-discovery-pattern-empty` exactly as required;
- after fixture removal, that discovery finding disappeared, proving the FAIL-006 detector itself;
- claims did not return fully clean only because FAIL-007's newly generalized direct-literal parser exposed an unrelated escaped-JavaScript-literal false positive in `test-rift-shell-git.mjs`; FAIL-006 will close after the decoder rebuild restores a clean claims baseline.

---

## FAIL-2026-09-24-007 — N2 contract regression froze a refactored Source Intelligence implementation detail

**Status:** SOURCE FIX EXTENDED / AWAITING REBUILD + LIVE CLAIMS VERIFICATION. Builder run `36082319213` / run number `359` passed the original FAIL-007 source repair, but the live claims proof exposed an escaped-JavaScript-literal false positive in the new direct-literal parser.

**Date:** 2026-09-24

**Failed RiftOS source:** `11d8c4abc804767677c2ceccb92f91017fd374b5`

**Fixing source SHA:** `33c6e0daa984d338e63f74cd4157a73110e59553` — replaces the stale implementation-detail contract assertion with the stable machine-authority helper surface and generalizes claims regression-literal ownership to maintained `*Read(...)` aliases plus direct/prefixed `*Assert.ok(alias.includes('literal'))` assertions under executable-code masking. **Decoder follow-up source SHA:** `9ea19f59971be96e6933aabb643d7bd70a76f3df` — centralizes minimal deterministic escape normalization across direct + marker-loop literals and upgrades the independent Builder regression to scan the generalized grammar itself. Builder/live verification of this follow-up is still required before this entry becomes RESOLVED.

**Builder run ID:** `36080971930`

**Signing mode:** `alpha-development`

**Failure stage:** Builder source checks, `npm run check` → `npm run check:transport` → `scripts/test-rift-memory-n2-contract-v1.mjs`.

**Observed failure:** `AssertionError: n2Assert.ok(n2SourceIntelligence.includes('val machineAuthority = listOf('))`.

### Root cause

FAIL-005 hardening intentionally refactored machine-authority classification out of a local `val machineAuthority = listOf(...)` inside `isBuildConfigPath(...)` and into the reusable `RiftSourceIntelligenceV2.isMachineAuthorityPath(...)` helper so semantic impact and the proof planner share one authority definition.

The N2 contract regression still asserted the old local implementation text. The behavioral contract remained present — the same three authority paths plus the new shared helper — but the regression froze an obsolete implementation detail and failed before Gradle. The pre-push generalized direct-literal audit also found a second stale assertion, `return machineAuthority ||`; it was corrected in the same repair to `return isMachineAuthorityPath(path) ||` so the full stale implementation-detail cluster is removed rather than waiting for another Builder failure.

### Why the Observer did not prevent it

The exact failed HEAD was inspected before any source fix. Claims, consistency, integrity and contracts were complete/clean with zero findings; proofs returned `mode=none`, zero selected tests and zero obligations.

The claims oracle's existing regression-literal ownership hardening only recognizes marker-loop assertions whose aliases are created with `read(...)`. This failure used a direct `n2Assert.ok(n2SourceIntelligence.includes('literal'))` assertion and an alias created with `n2Read(...)`, so the stale literal claim was outside the current oracle grammar.

### Observer hardening added

1. Regression source aliases now accept deterministic literal-path `read(...)` and maintained `*Read(...)` helpers such as `n2Read(...)`.
2. `checkRegressionLiteralOwnership(...)` now validates direct `assert.ok(alias.includes('literal'))` and prefixed forms such as `n2Assert.ok(...)` in addition to marker loops; marker-loop parsing is generalized to prefixed `*Assert.ok(...)` too.
3. Direct and loop assertions share the existing `MAX_REGRESSION_LITERAL_ASSERTIONS=2048` fail-closed budget and the same blocking `regression-literal-marker-missing` rule.
4. The generalized pass reuses `RiftSourceIntelligenceV2.referenceCodeMask(...)` so comment/string lookalikes do not become source claims.
5. `scripts/test-rift-documentation-claims-v1.mjs` permanently gates `*Read`, `*Assert`, direct-literal and executable-code-mask coverage.
6. Live run `359` exposed one false positive: raw JavaScript literal text such as `\\$input` was compared before JavaScript escape normalization, even though the runtime string and Kotlin source both contain `\$input`. Current source centralizes deterministic minimal escape decoding in `decodeRegressionLiteral(...)` and applies it to both direct assertions and marker-loop assertions.
7. The independent Builder regression now scans maintained `*Read(...)` aliases and direct/prefixed `*Assert.ok(alias.includes('literal'))` assertions itself, using its own `decodeRegressionMarker(...)`; the existing RiftGit escaped `$input` assertion therefore becomes a permanent false-positive control.
8. Exact Builder execution remains authoritative; dynamic/template includes remain outside the deterministic grammar unless the literal target can be resolved exactly.

### Resolution target

- the obsolete `val machineAuthority = listOf(` contract assertion is replaced with the stable `fun isMachineAuthorityPath(` surface while exact authority-path checks remain;
- claims regression-literal ownership is generalized to direct `*Assert.ok(alias.includes('literal'))` assertions and `*Read(...)` aliases with executable-code masking;
- run `359` already passed the original N2 contract + documentation-claims regressions, but the live claims pass exposed the escaped-literal false positive described above;
- the decoder rebuild must pass the strengthened documentation-claims regression, including the existing RiftGit escaped-literal false-positive control;
- after install, baseline claims must be clean, then a temporary direct wrong-literal assertion must produce `regression-literal-marker-missing`, and exact restoration must return claims clean before FAIL-007 is RESOLVED.




