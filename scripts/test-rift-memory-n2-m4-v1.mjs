import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = file => fs.readFileSync(file, 'utf8');

const procedural = read('android/app/src/main/java/com/riftos/app/RiftMemoryProceduralFailureV1.kt');
const retrieval = read('android/app/src/main/java/com/riftos/app/RiftMemoryRetrievalContextV1.kt');
const selftest = read('android/app/src/main/java/com/riftos/app/RiftMemoryN2M4SelfTest.kt');
const host = read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const localAgent = read('android/app/src/main/java/com/riftos/app/RiftVortexLocalAgent.kt');
const cliHost = read('android/app/src/main/java/com/riftos/app/RiftCliHost.kt');
const gradle = read('android/app/build.gradle.kts');
const contractTest = read('scripts/test-rift-memory-n2-contract-v1.mjs');
const phase = JSON.parse(read('riftmemory/n2-phase-authority.json'));
const pkg = JSON.parse(read('package.json'));

for (const marker of [
  'object RiftMemorySpecialistClassV1',
  'const val SKILL = "SKILL"',
  'const val FAILURE = "FAILURE"',
  'const val CAUSAL = "CAUSAL"',
  'const val COMMITMENT = "COMMITMENT"',
  'const val POLICY = "POLICY"',
  'const val MAX_SOURCE_RECORDS = 32',
  'const val MAX_SKILL_STEPS = 32',
  'const val MAX_FAILURE_SCAN = 4_096',
  'const val MAX_OPEN_COMMITMENTS = 256',
  'fun createSkillCandidate(',
  'fun createFailureCandidate(',
  'fun detectFailureRecurrence(',
  'fun createCausalCandidate(',
  'fun createCommitmentCandidate(',
  'fun openCommitments(',
  'fun createPolicyCandidate(',
  '.put("derivedTrustCeiling", RiftMemoryTrustStateV1.PROVISIONAL.name)',
  '.put("explicitEvidenceBacked", true)',
  '"causal-source-not-strong:"',
  '"causal-source-evidence-missing:"',
  '"specialist-cross-scope-forbidden"',
  '"specialist-cross-branch-forbidden"',
  '.put("authorityMustBeExplicit", true)',
  'authorityClass = RiftMemoryAuthorityClassV1.ORDINARY',
  'RiftMemoryModelV1.POLICY_NAMESPACE_PREFIX + policyKey',
]) {
  assert.ok(procedural.includes(marker), `missing N2.7 specialist marker: ${marker}`);
}

for (const marker of [
  'enum class RiftMemoryRetrievalModeV1',
  'NO,',
  'FAST,',
  'DEEP,',
  'FORENSIC',
  'enum class RiftMemoryRetrievalLaneV1',
  'EXACT,',
  'ENTITY,',
  'PROJECT,',
  'TEMPORAL,',
  'GRAPH,',
  'BM25,',
  'VECTOR',
  'const val MAX_QUERY_CHARS = 4_096',
  'const val MAX_QUERY_TOKENS = 128',
  'const val MAX_PROJECT_RECORDS = 4_096',
  'const val MAX_DOCUMENT_CHARS = 8_192',
  'const val MAX_DOCUMENT_FIELDS = 64',
  'const val MAX_DOCUMENT_ARRAY_ITEMS = 64',
  'const val MAX_DOCUMENT_DEPTH = 4',
  'const val MAX_DOCUMENT_NODES = 512',
  'const val MAX_DOCUMENT_SCALAR_CHARS = 2_048',
  'const val MAX_RESULTS = 64',
  'const val MAX_CONTEXT_TOKENS = 8_192',
  'fun route(mode: RiftMemoryRetrievalModeV1)',
  'fun retrieve(',
  'fun compileContext(',
  'val projectOnlyFallback =',
  'hits.values.forEach { hit -> hit.lanes += RiftMemoryRetrievalLaneV1.PROJECT }',
  '"retrieval-project-isolation-violation"',
  '"retrieval-cross-project-hit:"',
  'RiftMemoryTemporalGraphV1.reconstructAt(',
  'RiftMemoryTemporalGraphV1.rebuild(',
  'ln((n + 1.0) / (df + 1.0)) + 1.0',
  'private fun embed(tokens: List<String>): IntArray',
  'private fun vectorSimilarity(a: IntArray, b: IntArray): Int',
  'compareByDescending<RiftMemoryRetrievalHitV1> { it.requiredEvidence }',
  '.thenByDescending { trustRank(it.record.trustState) }',
  '.thenByDescending { it.evidenceWeight }',
  '.thenByDescending { lanePriority(it.lanes) }',
  '"context-budget-insufficient-for-required-evidence"',
  '"retrieval-document-scalar-bound-exceeded:$MAX_DOCUMENT_SCALAR_CHARS"',
  '"retrieval-document-char-bound-exceeded:$MAX_DOCUMENT_CHARS"',
  '"retrieval-document-depth-bound-exceeded:$MAX_DOCUMENT_DEPTH"',
  '"retrieval-document-node-bound-exceeded:$MAX_DOCUMENT_NODES"',
  '"retrieval-document-field-bound-exceeded:$MAX_DOCUMENT_FIELDS"',
  '"retrieval-document-array-bound-exceeded:$MAX_DOCUMENT_ARRAY_ITEMS"',
  '.put("payloadProjection", boundedProjection(hit.record.payload))',
]) {
  assert.ok(retrieval.includes(marker), `missing N2.8 retrieval marker: ${marker}`);
}

for (const marker of [
  'const val SCHEMA = "rift-memory-n2-m4-selftest-v1"',
  '.put("diagnosticOnly", true)',
  '.put("runtimeAuthority", false)',
  'sourceEvidenceCommitted',
  'proceduralTransfer',
  'failureRecurrence',
  'causalConfidence',
  'causalWeakSourceBlocked',
  'unfinishedTaskRestart',
  'protectedPolicy',
  'derivedTrustCeiling',
  'retrievalSourcesReady',
  'routerModes',
  'specialistIndexes',
  'evidencePrecedence',
  'projectIsolation',
  'minimalSufficientContext',
  'contextBudgetFailClosed',
  'noMode',
  'boundedFailClosed',
  'documentProjectionFailClosed',
  'integrityClean',
  '.put("n2_7", n27)',
  '.put("n2_8", n28)',
]) {
  assert.ok(selftest.includes(marker), `missing M4 self-test marker: ${marker}`);
}

assert.ok(host.includes('private val n2M4Diagnostic: JSONObject'));
assert.ok(host.includes('n2M4Diagnostic = RiftMemoryN2M4SelfTest.run(appContext)'));
assert.equal((host.match(/riftMemoryN2M4/g) || []).length, 3, 'M4 diagnostic must attach to exactly three rift_info lanes');

for (const file of [
  'RiftMemoryProceduralFailureV1.kt',
  'RiftMemoryRetrievalContextV1.kt',
  'RiftMemoryN2M4SelfTest.kt',
]) {
  assert.ok(
    gradle.includes(`"src/main/java/com/riftos/app/${file}"`),
    `M4 source missing from mandatory Android source snapshot: ${file}`,
  );
}

assert.equal(phase.phases.find(row => row.phase === 'N2.7')?.status, 'promoted');
assert.equal(phase.phases.find(row => row.phase === 'N2.8')?.status, 'promoted');
assert.equal(phase.phases.find(row => row.phase === 'N2.7')?.promotedSourceSha, 'd39960832a701311461058670b5b93597ae612c9');
assert.equal(phase.phases.find(row => row.phase === 'N2.8')?.promotedSourceSha, 'd39960832a701311461058670b5b93597ae612c9');
assert.equal(phase.phases.find(row => row.phase === 'N2.7')?.builderRunNumber, '368');
assert.equal(phase.phases.find(row => row.phase === 'N2.8')?.builderRunNumber, '368');
assert.equal(phase.macroImplementationPlan.find(row => row.patch === 'N2-M4')?.status, 'promoted');
assert.ok(phase.runtimeStatus.startsWith('N2 CANONICAL MEMORY RUNTIME INACTIVE'));
assert.ok(phase.runtimeStatus.includes('N2-M4 PROMOTED DIAGNOSTICS'));
assert.ok(pkg.scripts['check:transport'].includes('node scripts/test-rift-memory-n2-m4-v1.mjs'));

for (const marker of [
  "n2PhaseAuthority.phases.filter(row => row.status === 'promoted').length, 9",
  "n2PhaseAuthority.macroImplementationPlan[3].status, 'promoted'",
  'd39960832a701311461058670b5b93597ae612c9',
  "row.builderRunNumber, '368'",
  'android/app/src/main/java/com/riftos/app/RiftMemoryN2M4SelfTest.kt',
]) {
  assert.ok(contractTest.includes(marker), `N2 contract lifecycle regression missing M4 marker: ${marker}`);
}

for (const body of [localAgent, cliHost]) {
  assert.ok(!body.includes('RiftMemoryProceduralFailureV1'), 'N2.7 specialist engine must remain inactive in Local Agent/RiftCLI');
  assert.ok(!body.includes('RiftMemoryRetrievalContextV1'), 'N2.8 retrieval/context engine must remain inactive in Local Agent/RiftCLI');
  assert.ok(!body.includes('RiftMemoryN2M4SelfTest'), 'M4 diagnostic must not become Local Agent/RiftCLI authority');
}

console.log('ok - N2-M4 promoted: N2.7 + N2.8 promotion evidence is pinned to installed run 368; diagnostic only, runtime authority inactive');
