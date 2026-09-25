import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = file => fs.readFileSync(file, 'utf8');

const consolidation = read('android/app/src/main/java/com/riftos/app/RiftMemoryConsolidationV1.kt');
const cognitive = read('android/app/src/main/java/com/riftos/app/RiftMemoryBeliefDifferenceV1.kt');
const selftest = read('android/app/src/main/java/com/riftos/app/RiftMemoryN2M3SelfTest.kt');
const host = read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const gradle = read('android/app/build.gradle.kts');
const phase = JSON.parse(read('riftmemory/n2-phase-authority.json'));
const pkg = JSON.parse(read('package.json'));

for (const marker of [
  'object RiftMemoryCognitiveClassV1',
  'const val OBSERVATION = "OBSERVATION"',
  'const val EPISODE = "EPISODE"',
  'const val EPISODE_CLUSTER = "EPISODE_CLUSTER"',
  'const val PATTERN = "PATTERN"',
  'const val SEMANTIC = "SEMANTIC"',
  'const val BELIEF = "BELIEF"',
  'const val PREDICTION = "PREDICTION"',
  'const val REFLECTION = "REFLECTION"',
  'const val DISCREPANCY = "DISCREPANCY"',
  'const val MAX_OBSERVATIONS_PER_EPISODE = 64',
  'const val MAX_EPISODES_PER_CLUSTER = 64',
  'const val MAX_CLUSTERS_PER_PATTERN = 32',
  'const val MAX_PATTERNS_PER_SEMANTIC = 32',
  'fun createEpisodeCandidate(',
  'fun createEpisodeClusterCandidate(',
  'fun extractPatternCandidate(',
  'fun createSemanticCandidate(',
  '.put("reversible", true)',
  '.put("derivedTrustCeiling", RiftMemoryTrustStateV1.PROVISIONAL.name)',
  'trustState = RiftMemoryTrustStateV1.PROVISIONAL',
  'cross-scope-consolidation-forbidden',
  'cross-branch-consolidation-forbidden',
  'consolidation-source-count-out-of-range:',
  'consolidation-evidence-bound-exceeded:',
  'RiftMemoryModelV1.canonicalSha256(record.toJson())',
  'RiftMemoryTrustStateV1.CONFLICTED',
]) {
  assert.ok(consolidation.includes(marker), `missing N2.5 consolidation marker: ${marker}`);
}

for (const marker of [
  'data class RiftMemoryDifferenceResultV1',
  'fun createBeliefCandidate(',
  'fun createPredictionCandidate(',
  'fun comparePredictionToObservation(',
  'fun createReflectionCandidate(',
  'fun resolveDiscrepancyCandidate(',
  'branch = RiftMemoryBranchV1.HYPOTHESIS',
  '.put("status", "UNRESOLVED")',
  '.put("status", "RESOLVED")',
  '.put("surpriseScore", surprise)',
  '.put("importanceScore", importanceScore)',
  'RiftMemoryAuthorityClassV1.VERIFIED_SOURCE',
  'discrepancy-resolution-requires-stronger-observation',
  'previousDiscrepancyVersionSha256',
  'difference-cross-scope-forbidden',
  'RiftMemoryTrustStateV1.CONFLICTED',
]) {
  assert.ok(cognitive.includes(marker), `missing N2.6 belief/difference marker: ${marker}`);
}

for (const marker of [
  'const val SCHEMA = "rift-memory-n2-m3-selftest-v1"',
  '.put("diagnosticOnly", true)',
  '.put("runtimeAuthority", false)',
  'observationsVerified',
  'observationToEpisode',
  'episodeClustering',
  'patternExtraction',
  'semanticCandidate',
  'unsupportedTrustBlocked',
  'provenancePreserved',
  'reversibleLineage',
  'sourceBoundFailClosed',
  'projectIsolation',
  'conflictedSourceRejected',
  'objectSeparation',
  'discrepancyCreated',
  'surpriseImportanceScored',
  'unresolvedConflictState',
  'reflectionCreated',
  'matchingPredictionNoDiscrepancy',
  'strongerObservationReconciles',
  'nonDestructiveHistory',
  'historicalAfterResolution',
  'closeReopenCognitiveState',
  'integrityClean',
  'RiftMemoryTemporalGraphV1.reconstructAt(',
]) {
  assert.ok(selftest.includes(marker), `missing M3 self-test marker: ${marker}`);
}

for (const unsafe of [
  'cluster.payload.optString("dominantValue")',
  'pattern.payload.optString("value")',
  'semantic.payload.optString("value")',
  'belief.payload.optString("memoryClass")',
  'prediction.branch ==',
  'prediction.payload.optString("memoryClass")',
  'prediction.evidenceRefs.isEmpty()',
  'unresolvedDiscrepancy.payload.optString("status")',
  'reflection.payload.optString("memoryClass")',
  'resolvedDiscrepancy.trustState ==',
]) {
  assert.ok(!selftest.includes(unsafe), `M3 nullable canonical-record result must not be dereferenced unsafely: ${unsafe}`);
}

assert.ok(host.includes('private val n2M3Diagnostic: JSONObject'));
assert.ok(host.includes('n2M3Diagnostic = RiftMemoryN2M3SelfTest.run(appContext)'));
assert.equal((host.match(/riftMemoryN2M3/g) || []).length, 3, 'M3 diagnostic must attach to exactly three rift_info lanes');

for (const file of [
  'RiftMemoryConsolidationV1.kt',
  'RiftMemoryBeliefDifferenceV1.kt',
  'RiftMemoryN2M3SelfTest.kt',
]) {
  assert.ok(
    gradle.includes(`"src/main/java/com/riftos/app/${file}"`),
    `M3 source missing from mandatory Android source snapshot: ${file}`,
  );
}

assert.equal(phase.phases.find(row => row.phase === 'N2.5')?.status, 'promoted');
assert.equal(phase.phases.find(row => row.phase === 'N2.6')?.status, 'promoted');
assert.equal(phase.phases.find(row => row.phase === 'N2.5')?.promotedSourceSha, '62382a94f50dd6052e1754c1496da2a0f794c0af');
assert.equal(phase.phases.find(row => row.phase === 'N2.6')?.promotedSourceSha, '62382a94f50dd6052e1754c1496da2a0f794c0af');
assert.equal(phase.phases.find(row => row.phase === 'N2.5')?.builderRunNumber, '355');
assert.equal(phase.phases.find(row => row.phase === 'N2.6')?.builderRunNumber, '355');
assert.equal(phase.macroImplementationPlan.find(row => row.patch === 'N2-M3')?.status, 'promoted');
assert.ok(phase.runtimeStatus.startsWith('N2 CANONICAL MEMORY RUNTIME INACTIVE'));
assert.ok(phase.runtimeStatus.includes('N2-M3 DIAGNOSTIC ONLY'));
assert.ok(pkg.scripts['check:transport'].includes('node scripts/test-rift-memory-n2-m3-v1.mjs'));

for (const authorityPath of [
  'android/app/src/main/java/com/riftos/app/RiftVortexLocalAgent.kt',
  'android/app/src/main/java/com/riftos/app/RiftCliHost.kt',
]) {
  const body = read(authorityPath);
  assert.ok(!body.includes('RiftMemoryConsolidationV1'), 'M3 consolidation must not be active in Local Agent/RiftCLI yet: ' + authorityPath);
  assert.ok(!body.includes('RiftMemoryBeliefDifferenceV1'), 'M3 belief/difference must not be active in Local Agent/RiftCLI yet: ' + authorityPath);
}

console.log('ok - N2-M3 source: N2.5 consolidation/semantic + N2.6 belief/prediction/difference are separately gated; diagnostic only, runtime authority inactive');
