import n2Assert from 'node:assert/strict';
import { createHash as n2CreateHash } from 'node:crypto';
import { readFileSync as n2ReadFileSync, readdirSync as n2ReaddirSync, statSync as n2StatSync } from 'node:fs';
import n2Path from 'node:path';

const N2_CONTRACT_PATH = 'riftmemory/n2-contract-v1.json';
const N2_EXPECTED = Object.freeze({
  schema: 'rift-memory-n2-contract-v1',
  phase: 'N2.0',
  terminologySha256: 'bf18c5f2db272c5a66723879ab021a3ffc42483c4cd4fc6597d10bd96fc949d8',
  memoryStoreSha256: '68c46266e926e546e95543eb7a2092576c91499f810bcc5d483671d706823d16',
  correctnessCorpusSha256: '4ec6cd3e133de7c9a4f5f4d91c48b0873df02fb79a7b19727c96256b6e4687c4',
  correctnessThresholdsSha256: 'a6307031907834e0bb4060d24209a98706df4d0bfe39a7936cc5750fb06f6801',
  contractPayloadSha256: 'bf70f093f303f745b2e861431f2890be87367160c7cfbbd1c1a22004c49b4bb2',
  retainedControllerSha256: '6e3b7fd8294e65358fbf99a658ae1e4606d10316ff46187e76ca98d929dfabc3',
  benchmarkRule: 'NO_PERFORMANCE_OR_COMPARATIVE_BENCHMARKS_UNTIL_FULL_RIFTCLI_COMPLETE_AND_LIVE',
});

const n2Read = file => n2ReadFileSync(file, 'utf8');
const n2ShaBytes = data => n2CreateHash('sha256').update(data).digest('hex');

function n2Canonical(value) {
  if (value === null || value === undefined) return 'null';
  if (Array.isArray(value)) return '[' + value.map(n2Canonical).join(',') + ']';
  if (typeof value === 'object') {
    return '{' + Object.keys(value).sort()
      .map(key => JSON.stringify(key) + ':' + n2Canonical(value[key]))
      .join(',') + '}';
  }
  if (typeof value === 'string') return JSON.stringify(value);
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'number') {
    n2Assert.ok(Number.isFinite(value), 'canonical numeric value must be finite');
    return String(value);
  }
  throw new TypeError('unsupported canonical value type: ' + typeof value);
}
const n2ShaCanonical = value => n2ShaBytes(n2Canonical(value));

function n2Walk(dir) {
  const out = [];
  for (const name of n2ReaddirSync(dir)) {
    const full = n2Path.join(dir, name);
    const stat = n2StatSync(full);
    if (stat.isDirectory()) out.push(...n2Walk(full));
    else out.push(full);
  }
  return out;
}

const n2Contract = JSON.parse(n2Read(N2_CONTRACT_PATH));
const n2PhaseAuthority = JSON.parse(n2Read('riftmemory/n2-phase-authority.json'));
n2Assert.equal(n2PhaseAuthority.schema, 'rift-memory-n2-phase-authority-v1');
n2Assert.equal(n2PhaseAuthority.program, 'N2 Federated Rift Memory Kernel');
n2Assert.equal(n2PhaseAuthority.programStatus, 'N2.0-N2.9 PROMOTED / N2-M1 + N2-M2 + N2-M3 + N2-M4 + N2-M5 PROMOTED; N2.10-N2.11 SOURCE IMPLEMENTED / N2-M6 SOURCE IMPLEMENTED; N2.12 PENDING');
n2Assert.equal(n2PhaseAuthority.runtimeStatus, 'N2 CANONICAL MEMORY RUNTIME INACTIVE; N2-M1 + N2-M2 + N2-M3 + N2-M4 + N2-M5 PROMOTED DIAGNOSTICS; N2-M6 SOURCE-IMPLEMENTED DIAGNOSTIC ONLY');
n2Assert.equal(n2PhaseAuthority.n18Prerequisite, 'SATISFIED');
n2Assert.equal(n2PhaseAuthority.benchmarkRule, N2_EXPECTED.benchmarkRule);
n2Assert.equal(n2PhaseAuthority.contractPath, N2_CONTRACT_PATH);
n2Assert.equal(
  n2PhaseAuthority.contractLifecycleSemantics,
  'immutable-N2.0-freeze-snapshot; current lifecycle is authoritative only in this phase-authority file',
);
n2Assert.equal(n2PhaseAuthority.macroImplementationPlan.length, 6);
n2Assert.deepEqual(
  n2PhaseAuthority.macroImplementationPlan.map(row => row.phases),
  [
    ['N2.1', 'N2.2'],
    ['N2.3', 'N2.4'],
    ['N2.5', 'N2.6'],
    ['N2.7', 'N2.8'],
    ['N2.9'],
    ['N2.10', 'N2.11'],
  ],
);
n2Assert.ok(n2PhaseAuthority.macroPlanRule.includes('execution groupings only'));
n2Assert.ok(n2PhaseAuthority.macroPlanRule.includes('N2.12 remains a separate final correctness/adversarial promotion gate'));
n2Assert.equal(n2PhaseAuthority.phases.length, 13);
n2Assert.deepEqual(n2PhaseAuthority.phases.map(row => row.phase), [
  'N2.0','N2.1','N2.2','N2.3','N2.4','N2.5','N2.6','N2.7','N2.8','N2.9','N2.10','N2.11','N2.12',
]);
n2Assert.equal(n2PhaseAuthority.phases.filter(row => row.status === 'promoted').length, 10);
n2Assert.equal(n2PhaseAuthority.phases.filter(row => row.status === 'source-implemented').length, 2);
n2Assert.equal(n2PhaseAuthority.phases.filter(row => row.status === 'pending').length, 1);
n2Assert.equal(n2PhaseAuthority.phases[0].status, 'promoted');
n2Assert.equal(n2PhaseAuthority.phases[0].promotedSourceSha, 'f6bf12b9fb452cc128e9290fd73599297ba134f2');
n2Assert.equal(n2PhaseAuthority.phases[0].builderRunNumber, '341');
for (const row of n2PhaseAuthority.phases.slice(1, 3)) {
  n2Assert.equal(row.status, 'promoted');
  n2Assert.equal(row.promotedSourceSha, '694c1e31a6c3f4bd4317edd121208be894be2586');
  n2Assert.equal(row.builderRunNumber, '346');
}
for (const row of n2PhaseAuthority.phases.slice(3, 5)) {
  n2Assert.equal(row.status, 'promoted');
  n2Assert.equal(row.promotedSourceSha, '18f1156075e08cb94573a9392031ac64552313f2');
  n2Assert.equal(row.builderRunNumber, '350');
}
for (const row of n2PhaseAuthority.phases.slice(5, 7)) {
  n2Assert.equal(row.status, 'promoted');
  n2Assert.equal(row.promotedSourceSha, '62382a94f50dd6052e1754c1496da2a0f794c0af');
  n2Assert.equal(row.builderRunNumber, '355');
}
for (const row of n2PhaseAuthority.phases.slice(7, 9)) {
  n2Assert.equal(row.status, 'promoted');
  n2Assert.equal(row.promotedSourceSha, 'd39960832a701311461058670b5b93597ae612c9');
  n2Assert.equal(row.builderRunNumber, '368');
}
n2Assert.equal(n2PhaseAuthority.phases[9].status, 'promoted');
n2Assert.equal(n2PhaseAuthority.phases[9].promotedSourceSha, 'd650e57dff09a878f02edfef7e175ed02d42f750');
n2Assert.equal(n2PhaseAuthority.phases[9].builderRunNumber, '370');
for (const row of n2PhaseAuthority.phases.slice(10, 12)) {
  n2Assert.equal(row.status, 'source-implemented');
  n2Assert.equal(row.promotedSourceSha, null);
  n2Assert.equal(row.builderRunNumber, null);
}
n2Assert.equal(n2PhaseAuthority.phases[12].status, 'pending');
n2Assert.equal(n2PhaseAuthority.phases[12].promotedSourceSha, null);
n2Assert.equal(n2PhaseAuthority.phases[12].builderRunNumber, null);
n2Assert.equal(n2PhaseAuthority.macroImplementationPlan[0].status, 'promoted');
n2Assert.equal(n2PhaseAuthority.macroImplementationPlan[1].status, 'promoted');
n2Assert.equal(n2PhaseAuthority.macroImplementationPlan[2].status, 'promoted');
n2Assert.equal(n2PhaseAuthority.macroImplementationPlan[3].status, 'promoted');
n2Assert.equal(n2PhaseAuthority.macroImplementationPlan[4].status, 'promoted');
n2Assert.equal(n2PhaseAuthority.macroImplementationPlan[5].status, 'source-implemented');

n2Assert.equal(n2Contract.schema, N2_EXPECTED.schema);
n2Assert.equal(n2Contract.phase, N2_EXPECTED.phase);
n2Assert.equal(n2Contract.status, 'source-implemented');
n2Assert.equal(n2Contract.promotion, 'pending-builder-install-proof');
n2Assert.equal(n2Contract.runtimeActivation, false);
n2Assert.equal(n2Contract.globalBenchmarkRule, N2_EXPECTED.benchmarkRule);

n2Assert.equal(n2ShaCanonical(n2Contract.terminology), N2_EXPECTED.terminologySha256);
n2Assert.equal(n2Contract.terminologySha256, N2_EXPECTED.terminologySha256);
n2Assert.equal(n2ShaCanonical(n2Contract.memoryStore), N2_EXPECTED.memoryStoreSha256);
n2Assert.equal(n2Contract.memoryStoreSha256, N2_EXPECTED.memoryStoreSha256);
n2Assert.equal(n2ShaCanonical(n2Contract.correctnessCorpus), N2_EXPECTED.correctnessCorpusSha256);
n2Assert.equal(n2Contract.correctnessCorpusSha256, N2_EXPECTED.correctnessCorpusSha256);
n2Assert.equal(n2ShaCanonical(n2Contract.correctnessThresholds), N2_EXPECTED.correctnessThresholdsSha256);
n2Assert.equal(n2Contract.correctnessThresholdsSha256, N2_EXPECTED.correctnessThresholdsSha256);

const n2ContractPayload = structuredClone(n2Contract);
delete n2ContractPayload.contractPayloadSha256;
n2Assert.equal(n2ShaCanonical(n2ContractPayload), N2_EXPECTED.contractPayloadSha256);
n2Assert.equal(n2Contract.contractPayloadSha256, N2_EXPECTED.contractPayloadSha256);

n2Assert.equal(n2Contract.installedBaseline.riftosSourceSha, 'a0dd61ac71dcdd9b84871df0edd6d8813ea7a4b7');
n2Assert.equal(n2Contract.installedBaseline.builderRunId, '36027418624');
n2Assert.equal(n2Contract.installedBaseline.builderRunNumber, '340');
n2Assert.equal(n2Contract.installedBaseline.builderRepoSha, 'ab0fb44c9fc4587c0f6332c0ef939a0529a565e0');
n2Assert.equal(n2Contract.installedBaseline.androidPackage, 'com.riftos.app');
n2Assert.deepEqual(n2Contract.installedBaseline.supportedAbis, ['arm64-v8a', 'armeabi-v7a']);

n2Assert.equal(n2Contract.retainedController.path, 'src/riftmemory-control.js');
n2Assert.equal(n2Contract.retainedController.classification, 'retained-inactive-reference');
n2Assert.equal(n2Contract.retainedController.androidOwner, false);
n2Assert.equal(n2Contract.retainedController.gradlePackaged, false);
n2Assert.equal(n2Contract.retainedController.mayBecomeN2Authority, false);
n2Assert.equal(n2Contract.retainedController.sha256, N2_EXPECTED.retainedControllerSha256);
n2Assert.equal(n2ShaBytes(n2Read(n2Contract.retainedController.path)), N2_EXPECTED.retainedControllerSha256);

n2Assert.equal(n2Contract.authority.canonicalRealityOwner, 'Rift Memory Kernel');
for (const key of [
  'specialistsAuthoritative',
  'projectionsAuthoritative',
  'observerMayMutateTrustedTruth',
  'validatorMayMutateTrustedTruth',
  'protectedPolicyMayBeOverwrittenByLearnedMemory',
]) {
  n2Assert.equal(n2Contract.authority[key], false, 'authority boundary drift: ' + key);
}

n2Assert.equal(n2Contract.memoryStore.name, 'MemoryStore');
n2Assert.equal(n2Contract.memoryStore.version, 1);
n2Assert.equal(n2Contract.memoryStore.backendNeutral, true);
const n2MethodNames = n2Contract.memoryStore.methods.map(row => row.name);
n2Assert.equal(new Set(n2MethodNames).size, n2MethodNames.length, 'MemoryStore method names must be unique');
n2Assert.deepEqual(n2MethodNames, [
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
]);
for (const required of [
  'canonical commit is atomic',
  'partial transaction never masquerades as committed',
  'canonical durability never depends on disposable projection success',
  'backend semantics do not establish trust or authority',
  'all reads are bounded',
  'content-addressed blobs verify hash on read',
  'history is append-preserving; rollback never silently erases history',
]) {
  n2Assert.ok(n2Contract.memoryStore.guarantees.includes(required), 'MemoryStore lost guarantee: ' + required);
}

n2Assert.equal(n2Contract.correctnessCorpus.length, 12);
n2Assert.equal(new Set(n2Contract.correctnessCorpus.map(row => row.id)).size, 12);
for (const id of [
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
]) {
  n2Assert.ok(n2Contract.correctnessCorpus.some(row => row.id === id), 'frozen correctness scenario missing: ' + id);
}

for (const key of [
  'falseTrustedMemory',
  'crossProjectContamination',
  'unsupportedTrustedClaims',
  'successfulPolicyOrMemoryPoisoning',
  'lostRequiredPersistentMemory',
  'brokenProvenance',
  'falseCompletionCausedByMemory',
  'irrecoverableProjectionCorruption',
]) {
  n2Assert.equal(n2Contract.correctnessThresholds[key], 0, 'zero-tolerance threshold drift: ' + key);
}
n2Assert.equal(n2Contract.correctnessThresholds.performanceComparativeBenchmarks, 'DEFERRED_UNTIL_FULL_RIFTCLI_COMPLETE_AND_LIVE');
n2Assert.ok(n2Contract.deferredPostCli.length >= 8);
n2Assert.ok(n2Contract.deferredPostCli.every(row => typeof row === 'string' && row.length > 0));

const n2Gradle = n2Read('android/app/build.gradle.kts');
n2Assert.ok(n2Gradle.includes('abiFilters += listOf("arm64-v8a", "armeabi-v7a")'));
n2Assert.ok(!n2Gradle.includes('riftmemory-control.js'), 'retained RiftMemory controller became Gradle-packaged');

const n2SourceIntelligence = n2Read('android/app/src/main/java/com/riftos/app/RiftSourceIntelligenceV2.kt');
n2Assert.ok(n2SourceIntelligence.includes('fun isMachineAuthorityPath('));
for (const n2AuthorityPath of [
  'observer/phase-authority.json',
  'riftmemory/n2-contract-v1.json',
  'riftmemory/n2-phase-authority.json',
]) {
  n2Assert.ok(
    n2SourceIntelligence.includes(`"${n2AuthorityPath}"`),
    'machine authority path lost build-config classification: ' + n2AuthorityPath,
  );
}
n2Assert.ok(
  n2SourceIntelligence.includes('return isMachineAuthorityPath(path) ||'),
  'machine authority must enter build-config classification before ordinary filename rules',
);

const n2AllowedMemoryKotlin = new Set([
  'android/app/src/main/java/com/riftos/app/RiftMemoryModelV1.kt',
  'android/app/src/main/java/com/riftos/app/RiftMemoryStoreV1.kt',
  'android/app/src/main/java/com/riftos/app/RiftSqliteMemoryStoreV1.kt',
  'android/app/src/main/java/com/riftos/app/RiftMemoryN2M1SelfTest.kt',
  'android/app/src/main/java/com/riftos/app/RiftMemoryReconciliationV1.kt',
  'android/app/src/main/java/com/riftos/app/RiftMemoryTemporalGraphV1.kt',
  'android/app/src/main/java/com/riftos/app/RiftMemoryN2M2SelfTest.kt',
  'android/app/src/main/java/com/riftos/app/RiftMemoryConsolidationV1.kt',
  'android/app/src/main/java/com/riftos/app/RiftMemoryBeliefDifferenceV1.kt',
  'android/app/src/main/java/com/riftos/app/RiftMemoryN2M3SelfTest.kt',
  'android/app/src/main/java/com/riftos/app/RiftMemoryN2M4SelfTest.kt',
  'android/app/src/main/java/com/riftos/app/RiftMemoryObserverValidatorLoopV1.kt',
  'android/app/src/main/java/com/riftos/app/RiftMemoryN2M5SelfTest.kt',
  'android/app/src/main/java/com/riftos/app/RiftStoreMemoryStoreV1.kt',
  'android/app/src/main/java/com/riftos/app/RiftMemoryN2M6SelfTest.kt',
  'android/app/src/main/java/com/riftos/app/RiftToolHost.kt',
]);
const n2KotlinFiles = n2Walk('android/app/src/main/java/com/riftos/app').filter(file => file.endsWith('.kt'));
for (const file of n2KotlinFiles) {
  if (!n2AllowedMemoryKotlin.has(file)) {
    n2Assert.ok(!n2Read(file).includes('RiftMemoryStoreV1'), 'canonical N2 memory leaked into an unauthorized Android owner: ' + file);
    n2Assert.ok(!n2Read(file).includes('RiftSqliteMemoryStoreV1'), 'SQLite N2 memory leaked into an unauthorized Android owner: ' + file);
    n2Assert.ok(!n2Read(file).includes('RiftStoreMemoryStoreV1'), 'RiftStore N2 memory leaked into an unauthorized Android owner: ' + file);
  }
}
for (const authorityPath of [
  'android/app/src/main/java/com/riftos/app/RiftVortexLocalAgent.kt',
  'android/app/src/main/java/com/riftos/app/RiftCliHost.kt',
]) {
  const body = n2Read(authorityPath);
  n2Assert.ok(!body.includes('RiftMemoryStoreV1'), 'canonical memory must not be active in Local Agent/RiftCLI yet: ' + authorityPath);
  n2Assert.ok(!body.includes('RiftSqliteMemoryStoreV1'), 'SQLite memory backend must not be active in Local Agent/RiftCLI yet: ' + authorityPath);
}

const n2Roadmap = n2Read('docs/systems/riftmemory/N2_FEDERATED_MEMORY_ROADMAP.md');
n2Assert.ok(n2Roadmap.includes('ONE MEMORY KERNEL. MANY SPECIALIZED COGNITIVE ENGINES.'));
n2Assert.ok(n2Roadmap.includes('MemoryStore API'));
n2Assert.ok(n2Roadmap.includes('N2.0 — Contract and correctness-baseline freeze'));
n2Assert.ok(n2Roadmap.includes('comparative/performance benchmarking is deferred until the entire RiftCLI stack is 100% complete and live'));

const n2RetainedReadme = n2Read('docs/systems/riftmemory/README.md');
n2Assert.ok(n2RetainedReadme.includes('RiftMemory is retained/inactive reference source.'));
n2Assert.ok(n2RetainedReadme.includes('does not activate this retained cache controller'));

console.log('ok - rift-memory-n2-contract-v1 N2.0 freeze: runtime inactive, 18-method backend-neutral MemoryStore, 12 correctness scenarios, dual ARM ABI baseline, benchmarks deferred');
