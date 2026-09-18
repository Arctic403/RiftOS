import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';
const lifecycle = read(k + 'RiftCliPatchLifecycleV1.kt');
const research = read(k + 'RiftResearchLedgerV1.kt');
const cli = read(k + 'RiftExperimentalCli.kt');
const host = read(k + 'RiftToolHost.kt');
const records = read(k + 'RiftWorkspaceRecords.kt');
const sandbox = read(k + 'RiftToolSandbox.kt');
const gradle = read('android/app/build.gradle.kts');
const ownership = read('docs/SOURCE_OWNERSHIP.md');
const roadmap = read('ROADMAP.md');
const pipeline = read('docs/RIFT_AI_PATCH_PIPELINE.md');

assert.match(lifecycle, /internal object RiftCliPatchLifecycleV1/);
assert.match(lifecycle, /const val SCHEMA = "rift\.cli-patch-lifecycle\/1"/);
assert.match(lifecycle, /mode", "OBSERVE"/);
assert.match(lifecycle, /trustedCheckpointPromoted", false/);
assert.match(lifecycle, /published", false/);
assert.match(lifecycle, /publishAllowed", false/);

for (const stage of [
  'ACQUIRE','UNDERSTAND','RESEARCH','DOCUMENT_INTENT','PATCH','DOCUMENT_AUDIT',
  'CODE_AUDIT','SUPPLY_CHAIN_SECURITY','TEST_BUILD','END_TO_END_VERIFY',
  'FREEZE','AI_EVALUATION','LOCAL_VERIFY'
]) assert.ok(lifecycle.includes('"' + stage + '"'), 'missing lifecycle stage ' + stage);

assert.match(lifecycle, /"begin-sync"/);
assert.match(lifecycle, /listOf\("-C", target\.displayPath, "pull"\)/);
assert.match(lifecycle, /isGitClean\(status\)/);
assert.match(lifecycle, /RiftProjectExporter\.snapshotId/);
assert.match(lifecycle, /inventory\(target\.file\)/);
assert.match(lifecycle, /MAX_INVENTORY_FILES = 50_000/);
assert.match(lifecycle, /MAX_INVENTORY_BYTES = 512L \* 1024L \* 1024L/);
assert.match(lifecycle, /preCandidate\.optInt\("changedFiles", 0\) == 0/);
assert.match(lifecycle, /reason", "cli:lifecycle-begin"/);
assert.match(lifecycle, /operationalCheckpoint/);

assert.match(lifecycle, /governanceDocuments/);
assert.match(lifecycle, /dependencyAndBuildManifests/);
assert.match(lifecycle, /generatedVendorBoundaries/);
assert.match(lifecycle, /"readme\.md"/i);
assert.match(lifecycle, /"roadmap\.md"/i);
assert.match(lifecycle, /"todo\.md"/i);
assert.match(lifecycle, /"tasks\.md"/i);
assert.match(lifecycle, /"patch_history\.md"/i);
assert.match(lifecycle, /"project_status\.md"/i);
assert.match(lifecycle, /"source_ownership\.md"/i);

assert.match(lifecycle, /D:\/Documents or D:\/Temp/);
assert.match(lifecycle, /relative\.startsWith\("documents\/"\) \|\| relative\.startsWith\("home\/temp\/"\)/);
assert.ok(!lifecycle.includes('relative.startsWith("workspace/") || relative.startsWith("documents/")'));

assert.match(research, /internal object RiftResearchLedgerV1/);
assert.match(research, /rift\.research-ledger\/1/);
assert.match(research, /MAX_ENTRIES = 128/);
assert.match(research, /MAX_ASSUMPTIONS = 64/);
assert.match(research, /official/);
assert.match(research, /specification/);
assert.match(research, /standards-body/);
assert.match(research, /criticalWithoutAuthoritativeSource/);
assert.match(research, /Supported research assumption has no source/);
assert.match(research, /Research source references unknown supported assumption/);
assert.match(research, /Instant\.parse/);
assert.match(research, /independentVerificationRequired/);
assert.match(research, /RiftPatchManifestV1\.sha256Canonical/);
assert.match(research, /networkAuthority", false/);

assert.match(lifecycle, /kind != "research" && complete && checks\.length\(\) == 0/);
assert.match(lifecycle, /UNDERSTANDING_EVIDENCE_MISSING/);
assert.match(lifecycle, /existingEvidence\["understanding"\]/);
assert.match(lifecycle, /"understanding" -> baseGovernance \+ baseBuildManifests/);
assert.match(lifecycle, /"design" -> baseGovernance \+ baseBuildManifests/);
assert.match(lifecycle, /val governance = baseGovernance \+ currentGovernance/);
assert.match(lifecycle, /val buildManifests = baseBuildManifests \+ currentBuildManifests/);
assert.match(lifecycle, /"documentation" -> governance \+ affectedDocs \+ changedDocs/);
assert.match(lifecycle, /"dependencies" -> buildManifests \+ changedBuildConfig/);
assert.match(lifecycle, /nonPassChecks/);
assert.match(lifecycle, /missingTargets/);
assert.match(lifecycle, /expectedTargetsForEvidence/);
assert.match(lifecycle, /POST_EVIDENCE_ORDER/);
assert.match(lifecycle, /EVIDENCE_ORDER_INVALID/);

for (const field of ['lockfileStatus','sbomStatus','licenseStatus','provenanceStatus']) {
  assert.ok(lifecycle.includes('"' + field + '"'), 'missing supply-chain field ' + field);
}
for (const field of ['builder','toolchain','sourceRevision']) {
  assert.ok(lifecycle.includes('"' + field + '"'), 'missing build environment field ' + field);
}
assert.match(lifecycle, /Build artifact sha256 is invalid/);
assert.match(lifecycle, /rollbackPlan/);

assert.match(lifecycle, /candidateManifestSha256AtImport/);
assert.match(lifecycle, /STALE_EVIDENCE/);
assert.match(lifecycle, /semanticImpactSha256/);
assert.match(lifecycle, /evidenceBundleSha256/);
assert.match(lifecycle, /policySha256/);
assert.match(lifecycle, /evaluationBundleSha256/);
assert.match(lifecycle, /val records = RiftWorkspaceRecords\.get\(context\)/);
assert.match(lifecycle, /records\.freezeCandidate\(\)/);
assert.match(lifecycle, /Candidate changed between semantic impact and freeze/);
assert.match(lifecycle, /MAX_EVALUATION_PACKET_BYTES = 700 \* 1024/);
assert.match(lifecycle, /refuse to truncate verification evidence/);

assert.match(lifecycle, /MAX_EVALUATION_DEFECTS = 256/);
assert.match(lifecycle, /ACCEPTABLE_CANDIDATE/);
assert.match(lifecycle, /RETURN_DEFECTS/);
for (const field of ['code','severity','reason','fix']) {
  assert.ok(lifecycle.includes('defect.optString("' + field + '")'), 'missing defect field ' + field);
}
assert.match(lifecycle, /evaluatorId != patchActorId/);
assert.match(lifecycle, /identitySeparated/);
assert.match(lifecycle, /independenceAuthenticated", false/);
assert.ok(!lifecycle.includes('required += "build"'), 'Patch 13 artifact handshake is not implemented; build cannot be mandatory yet');
assert.match(lifecycle, /wouldAccept/);
assert.match(lifecycle, /wouldDeny/);

assert.match(cli, /"lifecycle" ->/);
assert.match(cli, /Patch lifecycle sessions require explicit process-local enable/);
assert.match(cli, /patchLifecycleMode", "OBSERVE"/);
assert.match(cli, /patchLifecycleTrustedPromotion", false/);

assert.match(host, /internal fun candidateImpactAsync/);
assert.ok(!host.includes('rift_cli_lifecycle'));
assert.ok(!host.includes('rift_candidate_evaluate'));
assert.match(records, /fun freezeCandidate\(\): JSONObject/);
assert.match(sandbox, /internal fun candidateImpactAsync/);

assert.match(gradle, /RiftCliPatchLifecycleV1\.kt/);
assert.match(gradle, /RiftResearchLedgerV1\.kt/);
assert.match(ownership, /RiftCliPatchLifecycleV1\.kt/);
assert.match(ownership, /RiftResearchLedgerV1\.kt/);
assert.match(ownership, /test-rift-cli-patch-lifecycle-v1\.mjs/);
assert.match(roadmap, /Local Agent validation state machine\/policy core/);
assert.match(pipeline, /ACQUIRE/);
assert.match(pipeline, /AI_EVALUATION/);
assert.match(pipeline, /LOCAL_VERIFY/);

console.log('ok - CLI patch lifecycle V1 is manual OBSERVE-only, candidate-bound, evidence-complete and evaluation-separated');
