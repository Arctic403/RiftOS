# Patch 9 — Impact-Derived Verification Planner

## Status

Design contract for the OBSERVE-only Patch 9 implementation.

Patch 9 upgrades the existing PI-v2 target-selection foundation into one exact candidate-bound verification plan for tests, security and dependency/supply-chain evidence.

## Authority

Patch 9 remains inside the existing Local Agent / Experimental RiftCLI lifecycle.

It adds:
- no MCP tool;
- no raw shell/process authority;
- no autonomous test runner;
- no dependency installer;
- no trusted-checkpoint promotion;
- no publication authority.

Canonical implementation owner: RiftVerificationPlannerV1.kt.

## Command

rift-cli lifecycle verification-plan <sessionId>

The command returns schema rift.verification-plan/1 for the exact current candidate.

## Inputs

The planner consumes:
- Patch Manifest V1 candidate identity;
- PI-v2 semantic impact;
- current repository bytes;
- current dependency/build manifest inventory;
- package.json scripts when present.

The caller cannot supply changed paths, tests, dependency deltas or security targets.

## Plan sections

### Tests

The planner derives:
- PI-v2 impacted test paths;
- changed test paths;
- deterministic test checks for each runnable discovered test;
- a bounded repository-check fallback when source/build changed but no impact test is available.

For JavaScript .js/.mjs tests, the plan may expose a bounded node <path> command as execution guidance.

If package.json contains a check script, npm run check may be used as the fallback repository validation command.

If source/build/test changed and neither an impact test nor repository check can be derived, the plan is incomplete with NO_TEST_OR_VALIDATION_TARGET. Test-only changes therefore cannot bypass test evidence.

Missing planned test files fail the plan rather than being silently dropped.

### Security

Security targets are derived from:
- changed source;
- changed build config;
- direct dependent source paths.

The plan records deterministic reasons including:
- CHANGED_SOURCE;
- CHANGED_BUILD_CONFIG;
- DIRECT_DEPENDENT;
- API_SURFACE_CHANGED;
- DEPENDENCY_SURFACE_CHANGED.

When source/build changed, the plan also requires bounded repository audit/scan checks. Each security target receives an exact target-review check.

Patch 9 plans security verification; it does not claim local lexical analysis proves security correctness.

### Dependencies

Dependency verification derives:
- exact PI-v2 added dependency rows;
- exact PI-v2 removed dependency rows;
- changed build-config paths;
- current dependency/build manifests.

Each added/removed dependency receives a deterministic review check bound to source, relation, dependency kind, specifier and resolved target when available.

Changed build config receives an exact dependency/build-config review check.

The existing lifecycle supplyChain fields remain mandatory for complete dependency evidence:
- lockfileStatus;
- sbomStatus;
- licenseStatus;
- provenanceStatus.

## Check identity

Every planned verification check receives a deterministic bounded id derived from its normalized type/target/detail tuple.

Evidence cannot replace the plan's required check ids with caller-selected equivalents.

## Structured evidence binding

Security, dependencies and tests evidence gain:

verificationPlan:
- schema: rift.verification-evidence/1
- planSha256
- kind: security | dependencies | tests

The generic evidence checks array must contain every check id required by that plan section with PASS status.

The generic evidence targets array must contain every target required by that plan section.

Extra checks may be reported, but required plan checks cannot be omitted.

Normalized verification evidence records:
- exact planSha256;
- candidate manifest SHA-256;
- semantic-impact SHA-256;
- evidence kind;
- required/missing check ids;
- required targets;
- complete state.

## Candidate binding and staleness

Final evaluation recomputes rift.verification-plan/1 from the still-current candidate.

The evaluator subject gains verificationPlanSha256.

For each required security/dependencies/tests evidence row:
- candidate manifest must still match;
- normalized verification plan SHA must match final plan SHA;
- normalized verification evidence must be complete.

Any mismatch is VERIFICATION_PLAN_STALE and WOULD_DENY.

The independent evaluator must echo verificationPlanSha256 exactly.

## Bounds

Initial V1 bounds:
- maintained candidate changes: 4096;
- security targets: 2048;
- dependency changes: 2048;
- dependency/build manifests: 512;
- test targets: 512;
- checks per section: 256 (matches Lifecycle V1 evidence-check bound);
- check id length: 96;
- command length: 1000.

Bound overflow fails the plan incomplete rather than truncating acceptance-critical verification scope.

## What Patch 9 proves

Patch 9 can prove:
- the required verification scope came from the exact final candidate;
- discovered affected tests were not silently omitted;
- source/build changes without a derivable test/check path fail closed;
- changed/dependent security surfaces received exact reviews;
- audit/scan checks are required when source/build changed;
- added/removed dependencies received exact review checks;
- changed build config and build manifests remain inside dependency evidence;
- security/dependency/test evidence belongs to the final verification plan.

Patch 9 does not prove:
- a reported test result is cryptographically authentic;
- a security review is semantically correct merely because it says PASS;
- remote dependency metadata was independently fetched;
- builds are hermetic/reproducible.

Those later trust/evidence properties remain for subsequent patches.

## Failure signatures

- source/build change with no test or repository validation path -> deny;
- planned test file missing -> deny;
- required security target omitted -> incomplete evidence;
- required audit/scan check omitted -> incomplete evidence;
- added/removed dependency review omitted -> incomplete evidence;
- changed build-config review omitted -> incomplete evidence;
- wrong verification plan hash -> incomplete/stale evidence;
- candidate mutation after security/dependency/test review -> VERIFICATION_PLAN_STALE;
- new MCP/trust/publish authority -> architecture regression.
