import fs from 'node:fs';
import assert from 'node:assert/strict';

const read = file => fs.readFileSync(file, 'utf8');
const format = read('android/app/src/main/java/com/riftos/app/RiftTrainDataV2Format.kt');
const runner = read('android/app/src/main/java/com/riftos/app/RiftTrainDataV2TaskRunner.kt');
const bpe = read('android/app/src/main/java/com/riftos/app/RiftFrozenByteBpeV1.kt');
const dedup = read('android/app/src/main/java/com/riftos/app/RiftB2BottomKDedupV1.kt');
const nearIndex = read('android/app/src/main/java/com/riftos/app/RiftB2NearDedupIndexV1.kt');
const thresholdQualification = read('android/app/src/main/java/com/riftos/app/RiftB2ThresholdQualificationV1.kt');
const thresholdTask = read('android/app/src/main/java/com/riftos/app/RiftB2ThresholdQualificationTask.kt');
const adversarialLab = read('android/app/src/main/java/com/riftos/app/RiftTrainDataV2AdversarialLab.kt');
const v1 = read('android/app/src/main/java/com/riftos/app/RiftTrainDataTaskRunner.kt');
const services = read('android/app/src/main/java/com/riftos/app/RiftNativeShellServices.kt');
const gradle = read('android/app/build.gradle.kts');
const pkg = read('package.json');

assert.match(format, /MAGIC = "RIFT_TRAIN_DATA_V2\\n"/);
assert.match(format, /INDEX_ENTRY_BYTES = 24/);
assert.match(format, /RECORD_HEADER_BYTES = 112/);
assert.match(format, /CONTEXT_TOKENS = 2048/);
assert.match(format, /MAX_CONTENT_TOKENS = CONTEXT_TOKENS - 2/);
assert.match(format, /MAX_ORIGINAL_UTF8_BYTES = 16 \* 1024/);
assert.match(format, /SOURCE_KIND_TABLE_VERSION = "rift-engineering-source-kinds-v2"/);
assert.match(format, /SPLIT_POLICY_ID = "rift-prod-group-split-v1"/);
assert.match(format, /VALIDATION_PERMYRIAD = 500/);
assert.match(format, /DEDUP_POLICY_ID = "rift-b2-bottomk-v1"/);
assert.match(format, /BOUNDARY_POLICY = "bos-content-eos-context-2048-v2"/);
assert.match(format, /HEADER_KEYS = setOf/);
assert.match(format, /REQUIRED_POLICY_KEYS = setOf/);
assert.match(format, /V2 pack header fields are non-canonical/);
assert.match(format, /V2 special-token mapping mismatch/);
assert.match(format, /V2 policy hash set mismatch/);
assert.match(format, /V2 production eligibility requires implemented near-dedup/);
assert.match(format, /checkedMultiply\(/);
assert.match(format, /V2 pack has missing\/trailing bytes/);
assert.match(format, /V2 record offsets are non-canonical/);
assert.match(format, /V2 index reserved bytes are nonzero/);
assert.match(format, /V2 record boundary token mismatch/);
assert.match(format, /record\.provenanceIndex < metadata\.provenanceCount/);
assert.match(format, /provenanceIndex < provenanceCount/);
assert.match(format, /V2 pack destination already exists/);
assert.doesNotMatch(
  format,
  /StandardCopyOption\.REPLACE_EXISTING/
);

assert.match(runner, /SOURCE_KIND_TABLE_VERSION = RiftTrainDataV2Format\.SOURCE_KIND_TABLE_VERSION/);
assert.match(runner, /SPLIT_POLICY_ID = RiftTrainDataV2Format\.SPLIT_POLICY_ID/);
assert.match(runner, /VALIDATION_PERMYRIAD = RiftTrainDataV2Format\.VALIDATION_PERMYRIAD/);
assert.match(runner, /DEDUP_POLICY_ID = RiftTrainDataV2Format\.DEDUP_POLICY_ID/);
assert.match(runner, /NEAR_DEDUP_IMPLEMENTED = false/);
assert.match(runner, /PRODUCTION_ELIGIBLE = false/);
assert.match(runner, /GENERATION_CONTRACT_REVISION = 1/);
assert.match(runner, /"contractRevision", GENERATION_CONTRACT_REVISION/);
assert.match(runner, /V2 generation descriptor contract revision mismatch/);
assert.match(runner, /generation-descriptor\.json/);
assert.match(runner, /dataset-manifest\.json/);
assert.match(runner, /seen_source_id/);
assert.match(runner, /duplicate source id/);
assert.match(runner, /seen_content/);
assert.match(runner, /seen_sample/);
assert.match(runner, /challenge\/train-validation source-group leakage detected/);
assert.match(runner, /RiftFrozenByteBpeV1\.loadFrozen/);
assert.match(runner, /encoder\.referenceEncode/);
assert.match(runner, /V2 provenance input changed during build/);
assert.match(runner, /V2 policy artifacts changed during build/);
assert.match(runner, /V2 policy directory contains unexpected\/missing entries/);
assert.match(runner, /private fun validatePolicyArtifact\(/);
assert.match(runner, /rift-train-data-v2-policy/);
assert.match(runner, /"reviewStatus"/);
assert.match(runner, /"draft" \|\| reviewStatus == "reviewed"/);
assert.match(runner, /V2 frozen policy artifact must be reviewed/);
assert.match(runner, /V2 policy artifact rules are empty\/too large/);
assert.match(runner, /source content is out of bounds/);
assert.match(runner, /obj\.getString\("content"\)/);
assert.doesNotMatch(runner, /val content = requiredString\(obj, "content"\)/);
assert.match(runner, /may not contain surrounding whitespace/);
assert.match(runner, /currentPointerValid/);
assert.match(runner, /currentDeepValidationPerformed/);
assert.match(runner, /policyArtifactsValid/);
assert.match(runner, /allPoliciesFrozen/);
assert.match(runner, /thresholdQualification/);
assert.match(runner, /thresholdEvidenceValid/);
assert.match(runner, /thresholdQualifiedIntervalExists/);
assert.match(runner, /adversarialParser/);
assert.match(runner, /hardwareTargetAEvidenceValid/);
assert.match(runner, /currentGenerationDeepValidationPerformed/);
assert.doesNotMatch(runner, /\.put\("currentValid"/);
assert.match(runner, /validateDescriptorAndProvenance\(/);
assert.match(runner, /validatePackAgainstDescriptor\(/);
assert.match(runner, /validateManifest\(/);
assert.match(runner, /validateManifestPack\(/);
assert.match(runner, /NearDedupEvidenceAccumulator/);
assert.match(runner, /RiftB2BottomKDedupV1\.sketch\(encoded\)/);
assert.match(runner, /RiftB2NearDedupIndexV1\.Session\(db\)/);
assert.match(runner, /nearDedupComparator\.observe\(/);
assert.match(runner, /nearDedupComparator\.finish\(\)/);
assert.match(runner, /nearDedupEvidence\.toJson\(nearDedupComparison\)/);
assert.match(runner, /validateNearDedupEvidence\(/);
assert.match(runner, /validateNearDedupComparisonEvidence\(/);
assert.match(runner, /maxIndexedFingerprints/);
assert.match(runner, /maxCandidatePairs/);
assert.match(runner, /indexedFingerprints/);
assert.match(runner, /RiftB2NearDedupIndexV1\.MAX_INDEXED_FINGERPRINTS/);
assert.match(runner, /RiftB2NearDedupIndexV1\.MAX_CANDIDATE_PAIRS/);
assert.match(runner, /globalComparisonImplemented", true/);
assert.match(runner, /rejectionApplied", false/);
assert.match(runner, /V2 near-dedup evidence sample count does not match packs/);
assert.match(runner, /V2 near-dedup comparator indexed-sample count mismatch/);
assert.match(runner, /DB_BATCH_RECORDS = 256/);
assert.match(runner, /db\.beginTransaction\(\)/);
assert.match(runner, /db\.setTransactionSuccessful\(\)/);
assert.match(runner, /V2 canonical provenance row count mismatch/);
assert.match(runner, /V2 canonical provenance row failed schema round-trip/);
assert.match(runner, /validatePacksWithGlobalIdentities\(/);
assert.match(runner, /recordValidatedIdentity\(/);
assert.match(runner, /V2 duplicate sample identity across packs/);
assert.match(runner, /V2 duplicate content identity across packs/);
assert.match(runner, /V2 source-group leakage across dataset splits/);
assert.match(runner, /fixed path resolves through a symlink/);
assert.match(runner, /unexpected\/missing\/symlinked entries/);
assert.match(format, /RecordIdentity/);
assert.match(format, /recordVisitor/);
assert.match(runner, /V2 pack\/descriptor policy hash mismatch/);
assert.match(runner, /V2 manifest pack identity mismatch/);
assert.match(runner, /build_log must identify success or failure/);
assert.match(runner, /test_log must identify success or failure/);
assert.match(runner, /synthetic origin requires synthetic authorship flag/);
assert.match(runner, /validateGeneration\(\s*generationDir,/);
assert.match(runner, /StandardCopyOption\.ATOMIC_MOVE/);
assert.match(runner, /private fun fsyncDirectory\(/);
assert.match(runner, /Os\.fsync\(fd\)/);
assert.match(runner, /fsyncDirectory\(stage\)/);
assert.match(runner, /fsyncDirectory\(generationsDir\)/);
assert.match(runner, /fsyncDirectory\(target\.parentFile\)/);
assert.doesNotMatch(runner, /ProcessBuilder|Runtime\.getRuntime|ServerSocket|DatagramSocket|HttpServer/);
assert.doesNotMatch(runner, /optString\("path"|getString\("path"/);

assert.match(bpe, /CANDIDATE_ID = "rift-token-b-balanced-v2"/);
assert.match(bpe, /314e3a732d4cc4c31c40c9b0add3fffcec38c8a4b40e0d228bdc4eed1addbbd1/);
assert.match(bpe, /class Encoder/);
assert.match(bpe, /fun referenceEncode\(/);
assert.match(bpe, /fun encode\(/);

assert.match(dedup, /ALGORITHM_ID = "rift-b2-bottomk-v1"/);
assert.match(dedup, /SIMILARITY_ID = "bottom-k-union-jaccard-v1"/);
assert.match(dedup, /SHINGLE_TOKENS = 13/);
assert.match(dedup, /BOTTOM_K = 128/);
assert.match(dedup, /fun sketch\(/);
assert.match(dedup, /fun similarityPpm\(/);
assert.match(dedup, /canonicalSketchLine\(/);

assert.match(nearIndex, /INDEX_ID = "rift-b2-bottomk-sqlite-cross-split-v1"/);
assert.match(nearIndex, /HISTOGRAM_BIN_PPM = 10_000/);
assert.match(nearIndex, /MAX_INDEXED_FINGERPRINTS = 16_000_000L/);
assert.match(nearIndex, /MAX_CANDIDATE_PAIRS = 5_000_000L/);
assert.match(nearIndex, /private val ensureRunning: \(\) -> Unit/);
assert.match(nearIndex, /near-dedup fingerprint index limit exceeded/);
assert.match(nearIndex, /near-dedup candidate-pair limit exceeded/);
assert.match(nearIndex, /indexedFingerprints/);
assert.match(nearIndex, /class Session/);
assert.match(nearIndex, /s\.split_id<>\?/);
assert.match(nearIndex, /RiftB2BottomKDedupV1\.similarityPpm\(/);
assert.match(nearIndex, /CREATE TABLE near_fingerprint/);
assert.match(nearIndex, /id INTEGER PRIMARY KEY/);
assert.match(nearIndex, /sketch BLOB NOT NULL/);
assert.match(nearIndex, /sample_id INTEGER NOT NULL/);
assert.match(nearIndex, /JOIN near_fingerprint f ON f\.sample_id=s\.id/);
assert.match(nearIndex, /insertOrThrow\("near_sample"/);
assert.match(nearIndex, /cursor\.getBlob\(2\)/);
assert.doesNotMatch(nearIndex, /sample_sha TEXT NOT NULL,.*PRIMARY KEY\(fp,sample_sha\)/s);
assert.match(nearIndex, /ORDER BY s\.sample_sha/);
assert.match(thresholdQualification, /rift-b2-bottomk-threshold-qualification-v1/);
assert.match(thresholdQualification, /LABEL_NEAR_DUPLICATE/);
assert.match(thresholdQualification, /LABEL_DISTINCT/);
assert.match(thresholdQualification, /MAX_CASES = 100_000/);
assert.match(thresholdQualification, /qualifiedIntervalExists/);
assert.match(thresholdQualification, /minimumQualifiedThresholdPpm/);
assert.match(thresholdQualification, /maximumQualifiedThresholdPpm/);
assert.match(thresholdQualification, /canonicalScoreStreamSha256/);
assert.match(thresholdTask, /near-dedup-cases\.jsonl/);
assert.match(thresholdTask, /near-dedup-threshold-evidence\.json/);
assert.match(thresholdTask, /CodingErrorAction\.REPORT/);
assert.match(thresholdTask, /RiftFrozenByteBpeV1\.Encoder/);
assert.match(thresholdTask, /RiftB2BottomKDedupV1\.similarityPpm/);
assert.match(thresholdTask, /qualifiedIntervalExists/);
assert.match(thresholdTask, /evidenceValid/);
assert.match(thresholdTask, /validateEvidence\(/);
assert.match(thresholdTask, /evidence input SHA mismatch/);
assert.match(thresholdTask, /apkSha256/);
assert.match(thresholdTask, /installed APK path is unavailable for threshold evidence binding/);
assert.match(thresholdTask, /thresholdFrozen", false/);
assert.match(thresholdTask, /productionPretrainingEligible", false/);
assert.doesNotMatch(thresholdTask, /optString\("path"|getString\("path"/);
assert.match(adversarialLab, /rift-train-data-v2-adversarial-parser-v1/);
assert.match(adversarialLab, /"truncate"/);
assert.match(adversarialLab, /"trailing-byte"/);
assert.match(adversarialLab, /"header-corruption"/);
assert.match(adversarialLab, /"index-reserved-byte"/);
assert.match(adversarialLab, /"record-bos-boundary"/);
assert.match(adversarialLab, /repairRegionSha\(/);
assert.match(adversarialLab, /V2 index reserved bytes are nonzero/);
assert.match(adversarialLab, /V2 record boundary token mismatch/);
assert.match(adversarialLab, /allMalformedRejected/);
assert.match(adversarialLab, /apkSha256/);
assert.match(adversarialLab, /installed APK path is unavailable for adversarial evidence binding/);
assert.match(adversarialLab, /expectedCases/);
assert.match(adversarialLab, /adversarial-parser-evidence\.json/);
assert.match(adversarialLab, /evidenceValidForCurrentApp/);
assert.match(adversarialLab, /versionCode/);
assert.match(adversarialLab, /StandardCopyOption\.ATOMIC_MOVE/);
assert.match(adversarialLab, /Os\.fsync\(fd\)/);

assert.match(v1, /RiftFrozenByteBpeV1\.Encoder\(artifact\)/);
assert.match(v1, /encoder\.referenceEncode\(bytes\)/);
assert.match(v1, /encoded\.contentEquals\(reference\)/);
assert.match(v1, /productionPretrainingEligible", false/);

for (const command of [
  'train-v2-status',
  'train-v2-build',
  'train-v2-build-status',
  'train-v2-build-cancel',
  'train-v2-dedup-qualify-status',
  'train-v2-dedup-qualify-start',
  'train-v2-dedup-qualify-job-status',
  'train-v2-dedup-qualify-cancel',
  'train-v2-adversarial-status',
  'train-v2-adversarial-lab'
]) {
  assert.ok(services.includes(command), 'missing fixed V2 native command ' + command);
}
assert.match(services, /"train-v2-status" -> RiftTrainDataV2TaskRunner\.execute/);
assert.match(services, /"train-v2-build" -> RiftTrainDataV2TaskRunner\.execute/);
assert.match(services, /"train-v2-build-status" -> RiftTrainDataV2TaskRunner\.execute/);
assert.match(services, /"train-v2-build-cancel" -> RiftTrainDataV2TaskRunner\.execute/);
assert.match(services, /"train-v2-dedup-qualify-status" -> RiftB2ThresholdQualificationTask\.execute/);
assert.match(services, /"train-v2-dedup-qualify-start" -> RiftB2ThresholdQualificationTask\.execute/);
assert.match(services, /"train-v2-dedup-qualify-job-status" -> RiftB2ThresholdQualificationTask\.execute/);
assert.match(services, /"train-v2-dedup-qualify-cancel" -> RiftB2ThresholdQualificationTask\.execute/);
assert.match(services, /"train-v2-adversarial-status" -> RiftTrainDataV2AdversarialLab\.status/);
assert.match(services, /"train-v2-adversarial-lab" -> RiftTrainDataV2AdversarialLab\.run/);

for (const source of [
  'RiftFrozenByteBpeV1.kt',
  'RiftB2BottomKDedupV1.kt',
  'RiftB2NearDedupIndexV1.kt',
  'RiftB2ThresholdQualificationV1.kt',
  'RiftB2ThresholdQualificationTask.kt',
  'RiftTrainDataV2Format.kt',
  'RiftTrainDataV2TaskRunner.kt',
  'RiftTrainDataV2AdversarialLab.kt'
]) {
  assert.ok(gradle.includes(source), 'requiredSources missing ' + source);
}
assert.ok(
  pkg.includes('node scripts/test-riftllm-training-v2.mjs'),
  'V2 training regression test is missing from check:transport'
);

console.log('RiftLLM production RiftTrainData V2 hardened candidate contract OK');
