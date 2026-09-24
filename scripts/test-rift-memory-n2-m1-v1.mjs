import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = file => fs.readFileSync(file, 'utf8');

const model = read('android/app/src/main/java/com/riftos/app/RiftMemoryModelV1.kt');
const store = read('android/app/src/main/java/com/riftos/app/RiftMemoryStoreV1.kt');
const sqlite = read('android/app/src/main/java/com/riftos/app/RiftSqliteMemoryStoreV1.kt');
const selftest = read('android/app/src/main/java/com/riftos/app/RiftMemoryN2M1SelfTest.kt');
const host = read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const gradle = read('android/app/build.gradle.kts');
const pkg = JSON.parse(read('package.json'));
const phase = JSON.parse(read('riftmemory/n2-phase-authority.json'));

for (const marker of [
  'const val SCHEMA_VERSION = 1',
  'const val RECORD_SCHEMA = "rift-memory-record-v1"',
  'const val EVIDENCE_SCHEMA = "rift-memory-evidence-v1"',
  'const val EVENT_SCHEMA = "rift-memory-event-v1"',
  'REALITY',
  'HYPOTHESIS',
  'SIMULATION',
  'COUNTERFACTUAL',
  'VERIFIED',
  'TRUSTED',
  'PROVISIONAL',
  'CONFLICTED',
  'QUARANTINED',
  'SUPERSEDED',
  'INVALIDATED',
  'POLICY_NAMESPACE_PREFIX = "policy/"',
  'CONFIG_NAMESPACE_PREFIX = "config/"',
  'Trusted/verified canonical memory requires evidence provenance.',
]) {
  assert.ok(model.includes(marker), `missing N2.1 canonical-model marker: ${marker}`);
}
assert.notEqual('rift-memory-evidence-v1', 'rift-memory-record-v1');
assert.ok(model.includes('data class RiftMemoryBiTemporalV1'));
assert.ok(model.includes('data class RiftMemoryEvidenceV1'));
assert.ok(model.includes('data class RiftCanonicalMemoryRecordV1'));
assert.ok(model.includes('data class RiftMemoryEventV1'));
assert.ok(model.includes('fun toJson(previousEventHash: String?)'));
assert.ok(model.includes('fun isProtectedNamespace(value: String)'));
assert.ok(model.includes('fun migrateRecord(value: JSONObject): JSONObject'));
assert.ok(model.includes('Unsupported Rift memory record schemaVersion='));
assert.ok(model.includes('it == JSONObject.NULL'), 'optional canonical fields must preserve JSON null rather than literal "null"');
assert.ok(sqlite.includes('json.opt("previousEventHash").takeUnless { it == null || it == JSONObject.NULL }'), 'event-chain integrity must preserve null previous hash');

const expectedStoreMethods = [
  'open',
  'close',
  'beginTransaction',
  'appendEvidence',
  'appendEvent',
  'putCanonicalRecord',
  'putContentBlob',
  'commitTransaction',
  'rollbackTransaction',
  'getCanonicalRecord',
  'scanCanonicalRecords',
  'readEvents',
  'readEvidence',
  'getContentBlob',
  'createSnapshot',
  'verifyIntegrity',
  'markProjectionDirty',
  'listDirtyProjections',
];
const interfaceMethods = [...store.matchAll(/^    (?:override )?fun ([A-Za-z0-9_]+)\(/gm)].map(match => match[1]);
assert.equal(interfaceMethods.length, 18, 'MemoryStore source surface must expose exactly 18 frozen methods');
assert.deepEqual([...interfaceMethods].sort(), [...expectedStoreMethods].sort());
assert.ok(store.includes('interface RiftMemoryStoreV1'));
assert.ok(store.includes('interface RiftMemoryStoreHandleV1 : AutoCloseable'));

for (const marker of [
  'PRAGMA journal_mode=DELETE',
  'PRAGMA synchronous=FULL',
  'PRAGMA foreign_keys=ON',
  'CREATE TABLE IF NOT EXISTS transactions(',
  'CREATE TABLE IF NOT EXISTS blobs(',
  'CREATE TABLE IF NOT EXISTS evidence(',
  'CREATE TABLE IF NOT EXISTS record_versions(',
  'CREATE TABLE IF NOT EXISTS records_current(',
  'CREATE TABLE IF NOT EXISTS events(',
  'CREATE TABLE IF NOT EXISTS snapshots(',
  'CREATE TABLE IF NOT EXISTS dirty_projections(',
  'db.beginTransaction()',
  'db.setTransactionSuccessful()',
  'db.endTransaction()',
  'Content-address collision or corrupted existing blob',
  'previous_event_hash',
  'event_hash',
  'PRAGMA integrity_check',
  'integrity-bound-exceeded:',
  'records_current c JOIN record_versions',
]) {
  assert.ok(sqlite.includes(marker), `missing N2.2 SQLite marker: ${marker}`);
}
assert.ok(sqlite.includes('class RiftSqliteMemoryStoreV1 : RiftMemoryStoreV1'));
assert.ok(sqlite.includes('Single memory content blob exceeds 8 MiB bound.'));
assert.ok(sqlite.includes('Snapshot scope exceeds bounded N2.2 baseline of 1000 current records.'));
assert.ok(sqlite.includes('if (db.isOpen && db.inTransaction())'));
assert.ok(sqlite.includes('runCatching { db.endTransaction() }'));

for (const marker of [
  'const val SCHEMA = "rift-memory-n2-m1-selftest-v1"',
  '.put("diagnosticOnly", true)',
  '.put("runtimeAuthority", false)',
  'currentReconstruction',
  'historyReconstruction',
  'provenancePreserved',
  'rollbackInvisibleBeforeClose',
  'rollbackInvisibleAfterReopen',
  'closeReopenRecovery',
  'processRestartRecovered',
  'priorProcessToken',
  'processToken',
  'contentAddressedEvidence',
  'protectedNamespaceGuard',
  'branchRoundTrip',
  'structuredMigration',
  'crashRollbackRecovered',
  'crashProbeArmed',
  'corruptionDetected',
  'restartPromotionReady',
  'process-death-rollback',
  'n2-m1-tamper-proof.sqlite',
  'handle.close()',
  'handle = store.open(config)',
]) {
  assert.ok(selftest.includes(marker), `missing M1 self-test marker: ${marker}`);
}
assert.equal((host.match(/riftMemoryN2M1/g) || []).length, 3);
assert.ok(host.includes('n2M1Diagnostic = RiftMemoryN2M1SelfTest.run(appContext)'));
assert.ok(!host.includes('runtimeAuthority", true'));
assert.ok(selftest.includes('.put("restartPromotionReady", processRestartRecovered && crashRollbackRecovered)'));
assert.ok(selftest.includes('SQLiteDatabase.openDatabase('));
assert.ok(selftest.includes('private var crashProbeHandle: RiftMemoryStoreHandleV1? = null'));

for (const file of [
  'RiftMemoryModelV1.kt',
  'RiftMemoryStoreV1.kt',
  'RiftSqliteMemoryStoreV1.kt',
  'RiftMemoryN2M1SelfTest.kt',
]) {
  assert.ok(
    gradle.includes(`"src/main/java/com/riftos/app/${file}"`),
    `M1 source missing from mandatory Android source snapshot: ${file}`,
  );
}

assert.equal(phase.phases.find(row => row.phase === 'N2.0')?.status, 'promoted');
assert.equal(phase.phases.find(row => row.phase === 'N2.1')?.status, 'source-implemented');
assert.equal(phase.phases.find(row => row.phase === 'N2.2')?.status, 'source-implemented');
assert.ok(phase.programStatus.includes('N2.1 + N2.2 SOURCE-IMPLEMENTED / N2-M1 PROMOTION PENDING'));
assert.equal(phase.runtimeStatus, 'N2 CANONICAL MEMORY RUNTIME INACTIVE; N2-M1 DIAGNOSTIC ONLY');
assert.ok(pkg.scripts['check:transport'].includes('node scripts/test-rift-memory-n2-m1-v1.mjs'));

console.log('ok - N2-M1 source: N2.1 canonical model/ledger + N2.2 SQLite reference backend are independently gated; diagnostic only, runtime authority inactive');
