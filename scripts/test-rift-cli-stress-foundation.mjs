import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';
const lifecycle = read(k + 'RiftCliPatchLifecycleV1.kt');
const host = read(k + 'RiftToolHost.kt');
const roadmap = read('ROADMAP.md');
const patchHistory = read('docs/PATCH_HISTORY.md');

assert.match(lifecycle, /PROCESS_EPOCH = "process-" \+ UUID\.randomUUID\(\)\.toString\(\)/);
assert.match(lifecycle, /restartDriftDetected/);
assert.match(lifecycle, /Lifecycle session detected workspace drift across process restart/);
assert.match(lifecycle, /lastObservedSourceSnapshotId/);
assert.match(lifecycle, /lastObservedCandidateManifestSha256/);
assert.match(lifecycle, /previousProcessEpoch/);
assert.match(lifecycle, /currentProcessEpoch/);

assert.match(lifecycle, /riftfs\/system\/rift-cli-patch-lifecycle-v1/);
assert.ok(lifecycle.includes('"riftfs/system/rift-cli-patch-lifecycle-v1"'));
assert.match(lifecycle, /legacySessionRoot/);
assert.match(lifecycle, /private fun copyLegacySession\(source: File, destination: File\)/);
assert.match(lifecycle, /RiftDeadline\.check\("CLI lifecycle migration"\)/);
assert.match(lifecycle, /CLI lifecycle migration exceeds \$MAX_INVENTORY_FILES entries/);
assert.match(lifecycle, /CLI lifecycle migration exceeds \$\{MAX_INVENTORY_BYTES \/ \(1024 \* 1024\)\} MiB/);
assert.doesNotMatch(lifecycle, /copyRecursively\(/);

assert.match(lifecycle, /val currentInventory = inventory\(projectTarget\(context, base\.getString\("projectDisplay"\)\)\.file\)/);
assert.match(lifecycle, /val governance = baseGovernance \+ currentGovernance/);
assert.match(lifecycle, /val buildManifests = baseBuildManifests \+ currentBuildManifests/);
assert.match(lifecycle, /changedDocumentation/);
assert.match(lifecycle, /changedBuildConfig/);
assert.match(lifecycle, /"documentation" -> governance \+ affectedDocs \+ changedDocs/);
assert.match(lifecycle, /"security", "dependencies", "tests" ->/);
assert.match(lifecycle, /verificationPlanFor\(context, session, impact\)/);
assert.match(lifecycle, /plan\.getJSONObject\(kind\)\.optJSONArray\("targets"\)/);
assert.match(lifecycle, /normalizedVerificationPlanEvidence/);

for (const governanceName of [
  'readme.md','roadmap.md','todo.md','todos.md','tasks.md',
  'patch_history.md','project_status.md','source_ownership.md'
]) {
  assert.ok(lifecycle.includes('"' + governanceName + '"'), 'governance discovery lost ' + governanceName);
}

assert.match(lifecycle, /"understanding" -> baseGovernance \+ baseBuildManifests/);
assert.match(lifecycle, /"design" -> baseGovernance \+ baseBuildManifests/);

assert.ok(!host.includes('rift_cli_lifecycle'));
assert.match(lifecycle, /trustedCheckpointPromoted", false/);
assert.match(lifecycle, /published", false/);

assert.match(roadmap, /Pre-Patch-8 stress-foundation repair/);
assert.match(patchHistory, /Stress-foundation repair/);

console.log('ok - stress-foundation repair preserves current-governance scope and fail-closed restart durability');
