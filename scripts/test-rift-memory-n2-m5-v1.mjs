import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = file => fs.readFileSync(file, 'utf8');

const loop = read('android/app/src/main/java/com/riftos/app/RiftMemoryObserverValidatorLoopV1.kt');
const selftest = read('android/app/src/main/java/com/riftos/app/RiftMemoryN2M5SelfTest.kt');
const host = read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const localAgent = read('android/app/src/main/java/com/riftos/app/RiftVortexLocalAgent.kt');
const cliHost = read('android/app/src/main/java/com/riftos/app/RiftCliHost.kt');
const gradle = read('android/app/build.gradle.kts');
const contractTest = read('scripts/test-rift-memory-n2-contract-v1.mjs');
const phase = JSON.parse(read('riftmemory/n2-phase-authority.json'));
const pkg = JSON.parse(read('package.json'));

for (const marker of [
  'enum class RiftMemoryVerificationOriginV1',
  'OBSERVER,',
  'VALIDATOR',
  'data class RiftMemoryVerificationSnapshotV1',
  'const val MAX_SUBJECT_CHARS = 256',
  'const val MAX_VALUE_CHARS = 4_096',
  'const val MAX_DETAILS_FIELDS = 64',
  'const val MAX_DETAILS_BYTES = 16_384',
  'const val MAX_SNAPSHOT_BYTES = 65_536',
  'fun createObservationCandidate(',
  'fun processState(',
  'fun processCommitment(',
  '"closed-loop-source-incomplete"',
  '"closed-loop-cross-scope-forbidden"',
  '"verification-snapshot-stale-or-replayed:',
  '"verification-snapshot-replayed:',
  '.put("evidenceOnly", true)',
  '.put("directTruthMutation", false)',
  'RiftMemoryTrustStateV1.VERIFIED',
  'RiftMemoryAuthorityClassV1.VERIFIED_SOURCE',
  'reconcile.reconcile(',
  '"commitment-correction-requires-validator"',
  '"validator-value-must-match-commitment-status"',
]) {
  assert.ok(loop.includes(marker), `missing N2.9 closed-loop marker: ${marker}`);
}

assert.ok(!loop.includes('RiftSqliteMemoryStoreV1()'), 'closed-loop adapter must stay backend-neutral');
assert.ok(!loop.includes('writeCanonicalRecord'), 'Observer/Validator adapter must not directly write trusted truth');

for (const marker of [
  'const val SCHEMA = "rift-memory-n2-m5-selftest-v1"',
  '.put("diagnosticOnly", true)',
  '.put("runtimeAuthority", false)',
  'initialStateReady',
  'incompleteEvidenceProvisional',
  'incompleteCannotMutateTarget',
  'staleStateReconciled',
  'staleObserverBlocked',
  'failedValidationReconciled',
  'falseCompletionReopened',
  'crossProjectBlocked',
  'boundedFailClosed',
  'differenceRecordsPersisted',
  'restartRecovery',
  'evidenceOnlyAuthority',
  'integrityClean',
  '.put("n2_9", n29)',
  'n2-m5-proof.sqlite',
]) {
  assert.ok(selftest.includes(marker), `missing M5 self-test marker: ${marker}`);
}

assert.ok(host.includes('private val n2M5Diagnostic: JSONObject'));
assert.ok(host.includes('n2M5Diagnostic = RiftMemoryN2M5SelfTest.run(appContext)'));
assert.equal((host.match(/riftMemoryN2M5/g) || []).length, 3, 'M5 diagnostic must attach to exactly three rift_info lanes');

for (const file of [
  'RiftMemoryObserverValidatorLoopV1.kt',
  'RiftMemoryN2M5SelfTest.kt',
]) {
  assert.ok(
    gradle.includes(`"src/main/java/com/riftos/app/${file}"`),
    `M5 source missing from mandatory Android source snapshot: ${file}`,
  );
  assert.ok(
    contractTest.includes(`'android/app/src/main/java/com/riftos/app/${file}'`),
    `N2 owner allowlist missing M5 source: ${file}`,
  );
}

const n29 = phase.phases.find(row => row.phase === 'N2.9');
assert.equal(n29?.status, 'promoted');
assert.equal(n29?.promotedSourceSha, 'd650e57dff09a878f02edfef7e175ed02d42f750');
assert.equal(n29?.builderRunNumber, '370');
assert.equal(phase.macroImplementationPlan.find(row => row.patch === 'N2-M5')?.status, 'promoted');
assert.ok(phase.runtimeStatus.startsWith('N2 CANONICAL MEMORY RUNTIME INACTIVE'));
const parsePromotedDiagnostics = runtimeStatus => {
  const clause = String(runtimeStatus)
    .split(';')
    .map(part => part.trim())
    .find(part => part.endsWith('PROMOTED DIAGNOSTICS')) ?? '';
  return clause
    .replace(/ PROMOTED DIAGNOSTICS$/, '')
    .split(' + ')
    .filter(Boolean);
};
assert.deepEqual(
  parsePromotedDiagnostics('N2 CANONICAL MEMORY RUNTIME INACTIVE; N2-M4 + N2-M5 PROMOTED DIAGNOSTICS; N2-M6 SOURCE-IMPLEMENTED DIAGNOSTIC ONLY'),
  ['N2-M4', 'N2-M5'],
);
const promotedDiagnostics = parsePromotedDiagnostics(phase.runtimeStatus);
assert.ok(promotedDiagnostics.includes('N2-M5'));

for (const marker of [
  "n2PhaseAuthority.phases[9].status, 'promoted'",
  "n2PhaseAuthority.macroImplementationPlan[4].status, 'promoted'",
  'd650e57dff09a878f02edfef7e175ed02d42f750',
  "n2PhaseAuthority.phases[9].builderRunNumber, '370'",
  'android/app/src/main/java/com/riftos/app/RiftMemoryObserverValidatorLoopV1.kt',
  'android/app/src/main/java/com/riftos/app/RiftMemoryN2M5SelfTest.kt',
]) {
  assert.ok(contractTest.includes(marker), `N2 contract lifecycle regression missing M5 marker: ${marker}`);
}

assert.ok(pkg.scripts['check:transport'].includes('node scripts/test-rift-memory-n2-m5-v1.mjs'));

for (const body of [localAgent, cliHost]) {
  assert.ok(!body.includes('RiftMemoryObserverValidatorLoopV1'), 'N2.9 closed-loop adapter must remain inactive in Local Agent/RiftCLI');
  assert.ok(!body.includes('RiftMemoryN2M5SelfTest'), 'M5 diagnostic must not become Local Agent/RiftCLI authority');
}

console.log('ok - N2-M5 promoted: N2.9 closed-loop evidence is pinned to installed run 370; diagnostic only, runtime authority inactive');
