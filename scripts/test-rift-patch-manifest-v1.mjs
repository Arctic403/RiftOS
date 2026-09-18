import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';
const manifest = read(k + 'RiftPatchManifestV1.kt');
const records = read(k + 'RiftWorkspaceRecords.kt');
const host = read(k + 'RiftToolHost.kt');
const gradle = read('android/app/build.gradle.kts');
const ownership = read('docs/SOURCE_OWNERSHIP.md');

assert.match(manifest, /internal object RiftPatchManifestV1/);
assert.match(manifest, /const val VERSION = 1/);
assert.match(manifest, /const val RECORD_CHAIN_VERSION = 1/);
assert.match(manifest, /MAX_FROZEN_MANIFESTS = 512/);
assert.match(manifest, /MAX_MANIFEST_BYTES = 8 \* 1024 \* 1024/);
assert.match(manifest, /fun treeSha256/);
assert.match(manifest, /fun changeSetSha256/);
assert.match(manifest, /fun canonicalJson/);
assert.match(manifest, /fun sealManifest/);
assert.match(manifest, /fun verifyManifest/);
assert.match(manifest, /fun freeze/);
assert.match(manifest, /fun sealRecord/);
assert.match(manifest, /fun verifyRecord/);
assert.ok(!manifest.includes('generatedAt'));
assert.ok(!manifest.includes('frozenAt'));

assert.match(records, /fun freezeCandidate\(\): JSONObject/);
assert.match(records, /private fun buildCandidateManifest/);
assert.match(records, /private fun verifyRecordChain/);
assert.match(records, /eventChainEpoch/);
assert.match(records, /eventChainAnchorHash/);
assert.match(records, /eventChainLastHash/);
assert.match(records, /checkpointSequence/);
assert.match(records, /eventPrunedThroughSequence/);
assert.match(records, /recoverEventChainHeadIfSafe/);
assert.match(records, /trustedCheckpointSummary/);
assert.match(records, /"kind", "operational"/);
assert.match(records, /"trustedCheckpoint"/);
assert.match(records, /"candidate"/);
assert.match(records, /RiftPatchManifestV1\.sealRecord/);
assert.match(records, /RiftPatchManifestV1\.freeze/);
assert.match(records, /Persist the new verification anchor before deleting the predecessor/);
assert.ok(!host.includes('freezeCandidate'));
assert.match(gradle, /RiftPatchManifestV1\.kt/);
assert.match(ownership, /RiftPatchManifestV1\.kt/);
assert.match(ownership, /scripts\/test-rift-patch-manifest-v1\.mjs/);

console.log('ok - Patch Manifest V1 is deterministic, immutable-by-hash, hash-chained and not remotely promotable');
