import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';

const read = file => fs.readFileSync(file, 'utf8');
const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex');

const CONTRACT_PATH = 'riftmemory/n2-contract-v1.json';
const FINAL_PATH = 'android/app/src/main/java/com/riftos/app/RiftMemoryN2FinalSelfTest.kt';
const contractBytes = fs.readFileSync(CONTRACT_PATH);
const contract = JSON.parse(contractBytes.toString('utf8'));
const finalSource = read(FINAL_PATH);
const host = read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const gradle = read('android/app/build.gradle.kts');
const localAgent = read('android/app/src/main/java/com/riftos/app/RiftVortexLocalAgent.kt');
const cliHost = read('android/app/src/main/java/com/riftos/app/RiftCliHost.kt');
const phase = JSON.parse(read('riftmemory/n2-phase-authority.json'));

const EXPECTED = {
  contractFileSha256: '4317788b26d1dd8ddd28959e026376434c50d950f0988b22c78b74fdd3a9d794',
  terminologySha256: 'bf18c5f2db272c5a66723879ab021a3ffc42483c4cd4fc6597d10bd96fc949d8',
  memoryStoreSha256: '68c46266e926e546e95543eb7a2092576c91499f810bcc5d483671d706823d16',
  correctnessCorpusSha256: '4ec6cd3e133de7c9a4f5f4d91c48b0873df02fb79a7b19727c96256b6e4687c4',
  correctnessThresholdsSha256: 'a6307031907834e0bb4060d24209a98706df4d0bfe39a7936cc5750fb06f6801',
  contractPayloadSha256: 'bf70f093f303f745b2e861431f2890be87367160c7cfbbd1c1a22004c49b4bb2',
  benchmarkRule: 'NO_PERFORMANCE_OR_COMPARATIVE_BENCHMARKS_UNTIL_FULL_RIFTCLI_COMPLETE_AND_LIVE',
};

assert.equal(sha256(contractBytes), EXPECTED.contractFileSha256);
assert.equal(contract.terminologySha256, EXPECTED.terminologySha256);
assert.equal(contract.memoryStoreSha256, EXPECTED.memoryStoreSha256);
assert.equal(contract.correctnessCorpusSha256, EXPECTED.correctnessCorpusSha256);
assert.equal(contract.correctnessThresholdsSha256, EXPECTED.correctnessThresholdsSha256);
assert.equal(contract.contractPayloadSha256, EXPECTED.contractPayloadSha256);
assert.equal(contract.globalBenchmarkRule, EXPECTED.benchmarkRule);

for (const [name, value] of Object.entries(EXPECTED)) {
  if (name === 'benchmarkRule') {
    assert.ok(finalSource.includes('const val BENCHMARK_RULE = "' + value + '"'));
  } else {
    const constant = {
      contractFileSha256: 'FROZEN_CONTRACT_FILE_SHA256',
      terminologySha256: 'TERMINOLOGY_SHA256',
      memoryStoreSha256: 'MEMORY_STORE_SHA256',
      correctnessCorpusSha256: 'CORRECTNESS_CORPUS_SHA256',
      correctnessThresholdsSha256: 'CORRECTNESS_THRESHOLDS_SHA256',
      contractPayloadSha256: 'CONTRACT_PAYLOAD_SHA256',
    }[name];
    assert.ok(finalSource.includes('const val ' + constant + ' = "' + value + '"'), 'final diagnostic lost frozen identity ' + name);
  }
}

const corpusIds = [
  'evidence-not-truth',
  'repetition-no-trust-upgrade',
  'supersession-retains-history',
  'conflict-no-fabricated-consensus',
  'cross-project-isolation',
  'protected-policy-overwrite',
  'projection-loss-rebuild',
  'partial-transaction-crash',
  'committed-restart-reconstruction',
  'commitment-restart',
  'simulation-isolation',
  'content-hash-corruption',
];
assert.deepEqual(contract.correctnessCorpus.map(row => row.id), corpusIds);
for (const id of corpusIds) {
  assert.ok(finalSource.includes('"' + id + '"'), 'N2.12 runtime table missing frozen corpus scenario ' + id);
}
assert.equal(new Set(corpusIds).size, 12);

const thresholdKeys = [
  'falseTrustedMemory',
  'crossProjectContamination',
  'unsupportedTrustedClaims',
  'successfulPolicyOrMemoryPoisoning',
  'lostRequiredPersistentMemory',
  'brokenProvenance',
  'falseCompletionCausedByMemory',
  'irrecoverableProjectionCorruption',
];
assert.deepEqual(
  Object.keys(contract.correctnessThresholds).filter(key => key !== 'performanceComparativeBenchmarks'),
  thresholdKeys,
);
for (const key of thresholdKeys) {
  assert.equal(contract.correctnessThresholds[key], 0);
  assert.ok(finalSource.includes('.put("' + key + '",'), 'N2.12 runtime threshold missing ' + key);
}
assert.equal(
  contract.correctnessThresholds.performanceComparativeBenchmarks,
  'DEFERRED_UNTIL_FULL_RIFTCLI_COMPLETE_AND_LIVE',
);

for (const marker of [
  'const val SCHEMA = "rift-memory-n2-final-selftest-v1"',
  '.put("diagnosticOnly", true)',
  '.put("runtimeAuthority", false)',
  '.put("phase", "N2.12")',
  'RiftMemoryN2M1SelfTest.run(context)',
  'RiftMemoryN2M2SelfTest.run(context)',
  'RiftMemoryN2M3SelfTest.run(context)',
  'RiftMemoryN2M4SelfTest.run(context)',
  'RiftMemoryN2M5SelfTest.run(context)',
  'RiftMemoryN2M6SelfTest.run(context)',
  '.put("scenarioCount", 12)',
  '.put("comparativePerformanceBenchmarksExecuted", false)',
  '.put("benchmarkDeferredUntilFullRiftCliComplete", true)',
  '.put("arm64InstalledDeviceExecutionClaimed", false)',
  '.put("sourceSha", sourceSha)',
  '.put("builderRunId", runId)',
  '.put("builderRunNumber", runNumber)',
  'BuildConfig.RIFT_SOURCE_SHA',
  'BuildConfig.RIFT_BUILD_RUN_ID',
  'BuildConfig.RIFT_BUILD_RUN_NUMBER',
  'm6.optBoolean("restartPromotionReady")',
  'n210.optBoolean("fixtureConformance")',
  'n211.optBoolean("arm32BoundedResourceContract")',
]) {
  assert.ok(finalSource.includes(marker), 'N2.12 final diagnostic missing marker: ' + marker);
}

assert.ok(gradle.includes('"src/main/java/com/riftos/app/RiftMemoryN2FinalSelfTest.kt"'));
assert.ok(gradle.includes('buildConfigField("String", "RIFT_SOURCE_SHA"'));
assert.ok(gradle.includes('buildConfigField("String", "RIFT_BUILD_RUN_ID"'));
assert.ok(gradle.includes('buildConfigField("String", "RIFT_BUILD_RUN_NUMBER"'));
assert.ok(gradle.includes('abiFilters += listOf("arm64-v8a", "armeabi-v7a")'));

assert.ok(host.includes('private val n2FinalDiagnostic: JSONObject'));
assert.ok(host.includes('n2FinalDiagnostic = RiftMemoryN2FinalSelfTest.run(appContext)'));
assert.equal((host.match(/riftMemoryN2Final/g) || []).length, 3, 'final N2 diagnostic must attach to exactly three rift_info lanes');

assert.equal(phase.phases[12].phase, 'N2.12');
assert.equal(phase.phases[12].status, 'promoted');
assert.equal(phase.phases[12].promotedSourceSha, '9e75b0f76fd61ba80ca4c241a41532253bdb4c47');
assert.equal(phase.phases[12].builderRunNumber, '378');
assert.equal(phase.phases.filter(row => row.status === 'promoted').length, 13);
assert.equal(phase.phases.filter(row => row.status === 'source-implemented').length, 0);
assert.equal(phase.phases.filter(row => row.status === 'pending').length, 0);
assert.equal(phase.programStatus, 'N2.0-N2.12 PROMOTED / N2-M1 + N2-M2 + N2-M3 + N2-M4 + N2-M5 + N2-M6 PROMOTED; N2 COMPLETE');
assert.equal(phase.runtimeStatus, 'N2 CANONICAL MEMORY RUNTIME INACTIVE; N2-M1 + N2-M2 + N2-M3 + N2-M4 + N2-M5 + N2-M6 PROMOTED DIAGNOSTICS; N2.12 FINAL CORRECTNESS GATE PROMOTED');
assert.equal(phase.benchmarkRule, EXPECTED.benchmarkRule);

for (const body of [localAgent, cliHost]) {
  assert.ok(!body.includes('RiftMemoryN2FinalSelfTest'), 'N2.12 final diagnostic must not become Local Agent/RiftCLI authority');
}

assert.ok(!finalSource.includes('benchmark('));
assert.ok(!finalSource.includes('System.nanoTime'));
assert.ok(!finalSource.includes('measureTime'));

console.log('ok - N2.12 source: frozen 12-scenario correctness/adversarial evidence aggregator, zero-tolerance gate, no benchmark, runtime authority inactive');
