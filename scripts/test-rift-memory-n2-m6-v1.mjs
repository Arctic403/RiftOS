import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = file => fs.readFileSync(file, 'utf8');

const store = read('android/app/src/main/java/com/riftos/app/RiftStoreMemoryStoreV1.kt');
const selftest = read('android/app/src/main/java/com/riftos/app/RiftMemoryN2M6SelfTest.kt');
const host = read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const localAgent = read('android/app/src/main/java/com/riftos/app/RiftVortexLocalAgent.kt');
const cliHost = read('android/app/src/main/java/com/riftos/app/RiftCliHost.kt');
const gradle = read('android/app/build.gradle.kts');
const contractTest = read('scripts/test-rift-memory-n2-contract-v1.mjs');
const phase = JSON.parse(read('riftmemory/n2-phase-authority.json'));
const pkg = JSON.parse(read('package.json'));

for (const marker of [
  'class RiftStoreMemoryStoreV1 : RiftMemoryStoreV1',
  'AtomicFile',
  'const val FORMAT = "rift-memory-riftstore-v1"',
  'const val STORE_VERSION = 1',
  'const val MAX_STORE_BYTES = 16 * 1024 * 1024',
  'base.length() <= RiftStoreMemoryStoreV1.MAX_STORE_BYTES.toLong()',
  '.put("payloadSha256", RiftMemoryModelV1.canonicalSha256(payload))',
  '"RiftStore state seal mismatch"',
  'RiftStoreCodecV1.cloneState(state)',
  'RiftStoreCodecV1.write(file, next)',
  'state = next',
  'override fun beginTransaction(',
  'override fun putContentBlob(',
  'override fun appendEvidence(',
  'override fun putCanonicalRecord(',
  'override fun appendEvent(',
  'override fun commitTransaction(',
  'override fun rollbackTransaction(',
  'override fun getCanonicalRecord(',
  'override fun scanCanonicalRecords(',
  'override fun readEvidence(',
  'override fun readEvents(',
  'override fun getContentBlob(',
  'override fun createSnapshot(',
  'override fun verifyIntegrity(',
  'override fun markProjectionDirty(',
  'override fun listDirtyProjections(',
]) {
  assert.ok(store.includes(marker), 'missing RiftStore conformance marker: ' + marker);
}

for (const marker of [
  'const val SCHEMA = "rift-memory-n2-m6-selftest-v1"',
  '.put("diagnosticOnly", true)',
  '.put("runtimeAuthority", false)',
  'RiftSqliteMemoryStoreV1()',
  'RiftStoreMemoryStoreV1()',
  'fixtureConformance',
  'atomicCommitConformance',
  'rollbackConformance',
  'blobConformance',
  'recordQueryConformance',
  'evidenceQueryConformance',
  'eventChainConformance',
  'snapshotConformance',
  'projectionConformance',
  'dirtyProjectionConformance',
  'boundedReadConformance',
  'closeReopenConformance',
  'referenceIntegrityClean',
  'riftStoreIntegrityClean',
  'productionReplacement',
  'comparativePerformanceDeferred',
  'memoryFsck',
  'sqliteCrashProbeArmed',
  'sqliteCrashRollbackRecovered',
  'riftStoreCrashProbeArmed',
  'riftStoreCrashRollbackRecovered',
  'snapshotReplayRollback',
  'indexCorruptionRebuild',
  'stateSealTamperDetected',
  'invalidEvidenceRejected',
  'missingEvidenceRejected',
  'oversizeStateRejectedBeforeRead',
  'boundedFailClosed',
  'coldRestartRecovered',
  'restartProbeArmed',
  'arm32BoundedResourceContract',
  'abiNeutralStoreSemantics',
  'restartPromotionReady',
  'n2-m6-reference.sqlite',
  'n2-m6-riftstore.json',
]) {
  assert.ok(selftest.includes(marker), 'missing N2-M6 self-test marker: ' + marker);
}

assert.ok(selftest.includes('.put("productionReplacement", false)'));
assert.ok(selftest.includes('.put("comparativePerformanceDeferred", true)'));
assert.ok(!store.includes('RiftSqliteMemoryStoreV1'), 'RiftStore prototype must be independent of SQLite implementation');

assert.ok(host.includes('private val n2M6Diagnostic: JSONObject'));
assert.ok(host.includes('n2M6Diagnostic = RiftMemoryN2M6SelfTest.run(appContext)'));
assert.equal((host.match(/riftMemoryN2M6/g) || []).length, 3, 'M6 diagnostic must attach to exactly three rift_info lanes');

for (const file of [
  'RiftStoreMemoryStoreV1.kt',
  'RiftMemoryN2M6SelfTest.kt',
]) {
  assert.ok(
    gradle.includes('"src/main/java/com/riftos/app/' + file + '"'),
    'M6 source missing from mandatory Android source snapshot: ' + file,
  );
  assert.ok(
    contractTest.includes("'android/app/src/main/java/com/riftos/app/" + file + "'"),
    'N2 owner allowlist missing M6 source: ' + file,
  );
}
assert.ok(contractTest.includes("'RiftStoreMemoryStoreV1'"), 'N2 contract must forbid unauthorized RiftStore owners');

const n210 = phase.phases.find(row => row.phase === 'N2.10');
const n211 = phase.phases.find(row => row.phase === 'N2.11');
assert.equal(n210?.status, 'source-implemented');
assert.equal(n211?.status, 'source-implemented');
assert.equal(n210?.promotedSourceSha, null);
assert.equal(n211?.promotedSourceSha, null);
assert.equal(n210?.builderRunNumber, null);
assert.equal(n211?.builderRunNumber, null);
assert.equal(phase.macroImplementationPlan.find(row => row.patch === 'N2-M6')?.status, 'source-implemented');
assert.ok(phase.runtimeStatus.startsWith('N2 CANONICAL MEMORY RUNTIME INACTIVE'));
assert.equal(phase.phases.find(row => row.phase === 'N2.12')?.status, 'pending');

for (const marker of [
  "n2PhaseAuthority.phases.filter(row => row.status === 'source-implemented').length, 2",
  "n2PhaseAuthority.phases.filter(row => row.status === 'pending').length, 1",
  "n2PhaseAuthority.phases.slice(10, 12)",
  "n2PhaseAuthority.macroImplementationPlan[5].status, 'source-implemented'",
  'android/app/src/main/java/com/riftos/app/RiftStoreMemoryStoreV1.kt',
  'android/app/src/main/java/com/riftos/app/RiftMemoryN2M6SelfTest.kt',
]) {
  assert.ok(contractTest.includes(marker), 'N2 contract lifecycle/ownership regression missing M6 marker: ' + marker);
}

assert.ok(pkg.scripts['check:transport'].includes('node scripts/test-rift-memory-n2-m6-v1.mjs'));

assert.ok(gradle.includes('abiFilters += listOf("arm64-v8a", "armeabi-v7a")'));
assert.ok(gradle.includes('"src/main/java/com/riftos/app/RiftStoreMemoryStoreV1.kt"'));
assert.ok(gradle.includes('"src/main/java/com/riftos/app/RiftMemoryN2M6SelfTest.kt"'));

for (const body of [localAgent, cliHost]) {
  assert.ok(!body.includes('RiftStoreMemoryStoreV1'), 'RiftStore prototype must not become Local Agent/RiftCLI authority during N2');
  assert.ok(!body.includes('RiftMemoryN2M6SelfTest'), 'M6 diagnostic must not become Local Agent/RiftCLI authority');
}

console.log('ok - N2-M6 source: independent RiftStore conformance + N2.11 hardening diagnostic; SQLite remains reference, runtime authority inactive');
