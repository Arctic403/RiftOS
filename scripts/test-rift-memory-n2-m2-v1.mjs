import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = file => fs.readFileSync(file, 'utf8');

const reconcile = read('android/app/src/main/java/com/riftos/app/RiftMemoryReconciliationV1.kt');
const temporal = read('android/app/src/main/java/com/riftos/app/RiftMemoryTemporalGraphV1.kt');
const selftest = read('android/app/src/main/java/com/riftos/app/RiftMemoryN2M2SelfTest.kt');
const host = read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const gradle = read('android/app/build.gradle.kts');
const phase = JSON.parse(read('riftmemory/n2-phase-authority.json'));
const pkg = JSON.parse(read('package.json'));

for (const marker of [
  'enum class RiftMemoryTransactionOutcomeV1',
  'COMMITTED',
  'PROVISIONAL',
  'QUARANTINED',
  'REJECTED',
  'enum class RiftMemoryAuthorityClassV1',
  'POLICY_AUTHORITY',
  'CONFIG_AUTHORITY',
  'enum class RiftMemoryCandidateActionV1',
  'INVALIDATE',
  'EVENT_RECONCILIATION = "memory.reconciliation"',
  'EVENT_RECORD_VERSION = "memory.record-version"',
  'EVENT_SUPERSESSION = "memory.supersession"',
  'EVENT_INVALIDATION = "memory.invalidation"',
  'EVENT_CONTRADICTION = "memory.contradiction"',
  'private const val MAX_CONFLICT_SCAN = 4_096',
  'repetition-does-not-upgrade-trust',
  'equal-authority-contradiction',
  'stronger-evidence-supersession',
  'protected-authority-mismatch',
  'invalidation-authority-too-weak',
  'private fun persistRejected(',
  'RiftMemoryTransactionOutcomeV1.REJECTED',
  'reconciliation-conflict-scan-bound-exceeded:',
]) {
  assert.ok(reconcile.includes(marker), `missing N2.3 reconciliation marker: ${marker}`);
}
assert.ok(reconcile.includes('RiftMemoryBranchV1.REALITY'));
assert.ok(reconcile.includes('PROJECTION_CURRENT'));
assert.ok(reconcile.includes('PROJECTION_TEMPORAL_GRAPH'));
assert.ok(reconcile.includes('handle.markProjectionDirty('));
assert.ok(reconcile.includes('handle.putCanonicalRecord(tx, superseded)'));
assert.ok(reconcile.includes('appendRecordVersionEvent(handle, tx, superseded, "SUPERSEDED")'));
assert.ok(reconcile.includes('candidateRank == currentRank'));
assert.ok(reconcile.includes('candidateRank > currentRank'));
assert.ok(reconcile.includes('conflict && contradictionCandidate != null && prior != null'), 'contradiction event path must prove nullable prior non-null before call');
assert.ok(reconcile.includes('scope = record.scope'));
assert.ok(reconcile.includes('projectId = record.scope.projectId'));
assert.ok(reconcile.includes('offset = MAX_CONFLICT_SCAN, limit = 1'));
assert.ok((reconcile.match(/runCatching\s*\{\s*handle\.markProjectionDirty/g) || []).length >= 4, 'post-commit projection dirty markers must fail soft');
assert.ok(!/val commit = handle\.commitTransaction\(tx\)\s*handle\.markProjectionDirty/.test(reconcile), 'canonical commit result must not depend on disposable projection marker success');

for (const marker of [
  'const val PROJECTION_SCHEMA = "rift-memory-temporal-graph-v1"',
  'const val POINT_IN_TIME_SCHEMA = "rift-memory-point-in-time-v1"',
  'data class RiftMemoryProjectionScopeV1',
  'Temporal projection scope only supports namespace/projectId/branch.',
  'const val MAX_CURRENT_RECORDS = 4_096',
  'const val MAX_EVENTS = 8_192',
  'const val MAX_DIRTY_PROJECTIONS = 4_096',
  'const val MAX_DEPENDENCIES_PER_RECORD = 256',
  'RiftMemoryGraphEdgeTypeV1.ENTITY',
  'RiftMemoryGraphEdgeTypeV1.DEPENDENCY',
  'RiftMemoryGraphEdgeTypeV1.SUPERSEDES',
  'RiftMemoryGraphEdgeTypeV1.INVALIDATES',
  'RiftMemoryGraphEdgeTypeV1.CONTRADICTS',
  'RiftMemoryGraphEdgeTypeV1.PROVENANCE',
  'fun reconstructAt(',
  'record.time.recordedAt > recordedAt',
  'record.time.validFrom > validAt',
  'record.time.validTo?.let { validAt >= it } == true',
  'offset = MAX_CURRENT_RECORDS, limit = 1',
  'offset = MAX_EVENTS, limit = 1',
  'offset = MAX_DIRTY_PROJECTIONS, limit = 1',
  'dirtyProjectionStateSha256',
  'val rebuilt = rebuild(handle, projection.scope.toQuery())',
  '.put("scope", scope.toJson())',
  'temporal-current-record-bound-exceeded:',
  'temporal-event-bound-exceeded:',
  'temporal-dirty-projection-bound-exceeded:',
  'temporal-dependency-bound-exceeded:',
]) {
  assert.ok(temporal.includes(marker), `missing N2.4 temporal/graph marker: ${marker}`);
}
assert.ok(temporal.includes('RiftMemoryModelV1.canonicalSha256(canonical)'));
assert.ok(!temporal.includes('.put("builtAt"'), 'projection hash must not include wall-clock build time');
assert.ok(!temporal.includes('it.updatedAt > projection.builtAt'), 'projection freshness must not depend on millisecond ordering');

for (const marker of [
  'const val SCHEMA = "rift-memory-n2-m2-selftest-v1"',
  '.put("diagnosticOnly", true)',
  '.put("runtimeAuthority", false)',
  'allOutcomes',
  'evidenceNotTruth',
  'repetitionNoTrustUpgrade',
  'strongerSupersession',
  'conflictNoSilentOverwrite',
  'crossProjectIsolation',
  'simulationIsolation',
  'protectedPolicyIsolation',
  'invalidCandidateRejected',
  'invalidationPreservesHistory',
  'projectionDirtySemantics',
  'biTemporalPointInTime',
  'scopeHashIsolation',
  'pointInTimeScopeHashIsolation',
  'coherentScopeGuard',
  'graphEdges',
  'currentStateProjection',
  'projectionDeleteRebuildExact',
  'closeReopenProjectionRebuild',
]) {
  assert.ok(selftest.includes(marker), `missing M2 self-test marker: ${marker}`);
}

assert.ok(host.includes('private val n2M2Diagnostic: JSONObject'));
assert.ok(host.includes('n2M2Diagnostic = RiftMemoryN2M2SelfTest.run(appContext)'));
assert.equal((host.match(/riftMemoryN2M2/g) || []).length, 3, 'M2 diagnostic must attach to exactly three rift_info lanes');

for (const file of [
  'RiftMemoryReconciliationV1.kt',
  'RiftMemoryTemporalGraphV1.kt',
  'RiftMemoryN2M2SelfTest.kt',
]) {
  assert.ok(
    gradle.includes(`"src/main/java/com/riftos/app/${file}"`),
    `M2 source missing from mandatory Android source snapshot: ${file}`,
  );
}

assert.equal(phase.phases.find(row => row.phase === 'N2.3')?.status, 'promoted');
assert.equal(phase.phases.find(row => row.phase === 'N2.4')?.status, 'promoted');
assert.equal(phase.macroImplementationPlan.find(row => row.patch === 'N2-M2')?.status, 'promoted');
assert.equal(phase.phases.find(row => row.phase === 'N2.3')?.promotedSourceSha, '18f1156075e08cb94573a9392031ac64552313f2');
assert.equal(phase.phases.find(row => row.phase === 'N2.4')?.promotedSourceSha, '18f1156075e08cb94573a9392031ac64552313f2');
assert.equal(phase.phases.find(row => row.phase === 'N2.3')?.builderRunNumber, '350');
assert.equal(phase.phases.find(row => row.phase === 'N2.4')?.builderRunNumber, '350');
assert.ok(phase.runtimeStatus.startsWith('N2 CANONICAL MEMORY RUNTIME INACTIVE'));
assert.ok(pkg.scripts['check:transport'].includes('node scripts/test-rift-memory-n2-m2-v1.mjs'));

for (const authorityPath of [
  'android/app/src/main/java/com/riftos/app/RiftVortexLocalAgent.kt',
  'android/app/src/main/java/com/riftos/app/RiftCliHost.kt',
]) {
  const body = read(authorityPath);
  assert.ok(!body.includes('RiftMemoryReconciliationV1'), 'M2 reconciliation must not be active in Local Agent/RiftCLI yet: ' + authorityPath);
  assert.ok(!body.includes('RiftMemoryTemporalGraphV1'), 'M2 temporal graph must not be active in Local Agent/RiftCLI yet: ' + authorityPath);
}

console.log('ok - N2-M2 source: N2.3 reconciliation/trust + N2.4 temporal/graph are separately gated; diagnostic only, runtime authority inactive');
