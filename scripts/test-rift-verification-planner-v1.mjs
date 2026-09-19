import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';
const planner = read(k + 'RiftVerificationPlannerV1.kt');
const lifecycle = read(k + 'RiftCliPatchLifecycleV1.kt');
const cli = read(k + 'RiftExperimentalCli.kt');
const host = read(k + 'RiftToolHost.kt');
const gradle = read('android/app/build.gradle.kts');
const ownership = read('docs/SOURCE_OWNERSHIP.md');
const roadmap = read('ROADMAP.md');
const spec = read('docs/systems/experimental-cli/PATCH9_VERIFICATION_PLANNER.md');

assert.match(planner, /internal object RiftVerificationPlannerV1/);
assert.match(planner, /PLAN_SCHEMA = "rift\.verification-plan\/1"/);
assert.match(planner, /EVIDENCE_SCHEMA = "rift\.verification-evidence\/1"/);
assert.match(planner, /MAX_CHANGED_ROWS = 4_096/);
assert.match(planner, /MAX_SECURITY_TARGETS = 2_048/);
assert.match(planner, /MAX_DEPENDENCY_CHANGES = 2_048/);
assert.match(planner, /MAX_BUILD_MANIFESTS = 512/);
assert.match(planner, /MAX_TEST_TARGETS = 512/);
assert.match(planner, /MAX_CHECKS_PER_SECTION = 256/);

for (const code of [
  'SEMANTIC_IMPACT_INCOMPLETE',
  'VERIFICATION_CHANGE_BOUND',
  'DEPENDENCY_CHANGE_BOUND',
  'SECURITY_TARGET_BOUND',
  'BUILD_MANIFEST_BOUND',
  'BUILD_MANIFEST_MISSING',
  'TEST_TARGET_MISSING',
  'TEST_TARGET_BOUND',
  'NO_TEST_OR_VALIDATION_TARGET',
  'SECURITY_CHECK_BOUND',
  'DEPENDENCY_CHECK_BOUND',
  'TEST_CHECK_BOUND'
]) assert.ok(planner.includes('"' + code + '"'), 'missing Patch-9 rule ' + code);

for (const reason of [
  'CHANGED_SOURCE',
  'CHANGED_BUILD_CONFIG',
  'DIRECT_DEPENDENT',
  'API_SURFACE_CHANGED',
  'DEPENDENCY_SURFACE_CHANGED'
]) assert.ok(planner.includes('"' + reason + '"'), 'missing security reason ' + reason);

for (const checkType of [
  'security-target-review',
  'rift-audit',
  'rift-scan',
  'security-no-change',
  'supply-chain-review',
  'build-manifest-review',
  'build-config-dependency-review',
  'dependency-change-review',
  'test-target',
  'repository-check'
]) assert.ok(planner.includes('type = "' + checkType + '"'), 'missing check type ' + checkType);

assert.match(planner, /relation !in setOf\("added", "removed"\)/);
assert.match(planner, /packageCheckCommand/);
assert.match(planner, /"test" -> testRelevantChanged = true/);
assert.match(lifecycle, /category == "test"/);
assert.match(planner, /"npm run check"/);
assert.match(planner, /RiftPatchManifestV1\.sha256Canonical/);
assert.match(planner, /verificationEvidenceSha256/);
assert.match(planner, /missingCheckIds/);
assert.match(planner, /missingTargets/);

assert.match(lifecycle, /"verification-plan" ->/);
assert.match(lifecycle, /private fun verificationPlan/);
assert.match(lifecycle, /private fun verificationPlanFor/);
assert.match(lifecycle, /RiftVerificationPlannerV1\.plan/);
assert.match(lifecycle, /RiftVerificationPlannerV1\.validateEvidence/);
assert.match(lifecycle, /normalizedVerificationPlanEvidence/);
assert.match(lifecycle, /VERIFICATION_PLAN_EVIDENCE_MISSING/);
assert.match(lifecycle, /VERIFICATION_PLAN_STALE/);
assert.match(lifecycle, /VERIFICATION_PLAN_INCOMPLETE/);
assert.match(lifecycle, /verificationPlanSha256/);
assert.match(lifecycle, /"security", "dependencies", "tests"/);

assert.match(cli, /verification-plan/);
assert.match(gradle, /RiftVerificationPlannerV1\.kt/);
assert.match(ownership, /RiftVerificationPlannerV1\.kt/);
assert.match(ownership, /test-rift-verification-planner-v1\.mjs/);
assert.match(roadmap, /Impact-derived tests\/security\/dependency verification planner — OBSERVE implementation complete/);
assert.match(spec, /Patch 9/);

assert.ok(!host.includes('rift_verification_plan'));
assert.ok(!host.includes('verification_plan'));
assert.match(lifecycle, /trustedCheckpointPromoted", false/);
assert.match(lifecycle, /published", false/);

console.log('ok - Patch 9 verification planning is candidate-derived, exact-check-bound, stale-safe and OBSERVE-only');
