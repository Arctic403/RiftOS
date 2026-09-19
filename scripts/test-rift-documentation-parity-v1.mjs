import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';
const parity = read(k + 'RiftDocumentationParityV1.kt');
const lifecycle = read(k + 'RiftCliPatchLifecycleV1.kt');
const cli = read(k + 'RiftExperimentalCli.kt');
const host = read(k + 'RiftToolHost.kt');
const gradle = read('android/app/build.gradle.kts');
const ownership = read('docs/SOURCE_OWNERSHIP.md');
const roadmap = read('ROADMAP.md');
const spec = read('docs/systems/experimental-cli/PATCH8_DOCUMENTATION_PARITY.md');

assert.match(parity, /internal object RiftDocumentationParityV1/);
assert.match(parity, /PLAN_SCHEMA = "rift\.documentation-parity-plan\/1"/);
assert.match(parity, /EVIDENCE_SCHEMA = "rift\.documentation-parity\/1"/);
assert.match(parity, /MAX_LEDGER_BYTES = 1024L \* 1024L/);
assert.match(parity, /MAX_LEDGER_ROWS = 5_000/);
assert.match(parity, /MAX_OWNER_DOCS = 32/);
assert.match(parity, /MAX_CHANGED_ROWS = 4_096/);
assert.match(parity, /MAX_SOURCE_REVIEWS = 4_096/);
assert.match(parity, /MAX_GOVERNANCE_REVIEWS = 16/);
assert.match(parity, /MAINTAINED_CATEGORIES = setOf\("source", "build-config", "test"\)/);

for (const code of [
  'OWNERSHIP_ENTRY_MISSING',
  'DELETED_SOURCE_STILL_OWNED',
  'OWNER_DOCUMENT_MISSING',
  'OWNER_DOCUMENT_UPDATE_MISSING',
  'SUBSTANTIVE_OWNER_DOCUMENT_MISSING',
  'OWNERSHIP_LEDGER_UPDATE_MISSING',
  'PATCH_HISTORY_UPDATE_MISSING',
  'SOURCE_REVIEW_MISSING',
  'OWNER_DOC_SET_MISMATCH',
  'MANDATORY_OWNER_UPDATE_NOT_ACKNOWLEDGED',
  'GOVERNANCE_REVIEW_MISSING',
  'GOVERNANCE_UPDATE_REQUIRED'
]) assert.ok(parity.includes('"' + code + '"'), 'missing Patch-8 rule ' + code);

assert.match(parity, /apiSurfaceChanged/);
assert.match(parity, /dependencySurfaceChanged/);
assert.match(parity, /category == "build-config"/);
assert.match(parity, /nearestReadme/);
assert.match(parity, /isBookkeepingDocument/);
assert.match(parity, /changedSubstantiveOwnerDocs/);
assert.match(parity, /docs\/SOURCE_OWNERSHIP\.md/);
assert.match(parity, /docs\/PATCH_HISTORY\.md/);
assert.match(parity, /docs\/PROJECT_STATUS\.md/);
assert.match(parity, /ROADMAP\.md/);
assert.match(parity, /README\.md/);
assert.match(parity, /RiftPatchManifestV1\.sha256Canonical/);
assert.match(parity, /planSha256/);
assert.match(parity, /paritySha256/);
assert.match(parity, /UPDATED/);
assert.match(parity, /UNCHANGED_VALID/);

assert.match(lifecycle, /"documentation-plan" ->/);
assert.match(lifecycle, /private fun documentationPlan/);
assert.match(lifecycle, /RiftDocumentationParityV1\.plan/);
assert.match(lifecycle, /RiftDocumentationParityV1\.validateEvidence/);
assert.match(lifecycle, /Documentation evidence requires documentationParity/);
assert.match(lifecycle, /normalizedDocumentationParity/);
assert.match(lifecycle, /DOCUMENTATION_PARITY_EVIDENCE_MISSING/);
assert.match(lifecycle, /DOCUMENTATION_PARITY_STALE/);
assert.match(lifecycle, /DOCUMENTATION_PARITY_INCOMPLETE/);
assert.match(lifecycle, /documentationParityPlanSha256/);
assert.match(lifecycle, /documentationParityPlan/);

assert.match(cli, /documentation-plan/);
assert.match(gradle, /RiftDocumentationParityV1\.kt/);
assert.match(ownership, /RiftDocumentationParityV1\.kt/);
assert.match(ownership, /test-rift-documentation-parity-v1\.mjs/);
assert.match(roadmap, /Documentation\/README\/roadmap\/patch-note parity gate — OBSERVE implementation complete/);
assert.match(spec, /Patch 8/);

assert.ok(!host.includes('rift_documentation_parity'));
assert.ok(!host.includes('documentation_parity'));
assert.match(lifecycle, /trustedCheckpointPromoted", false/);
assert.match(lifecycle, /published", false/);

console.log('ok - Patch 8 documentation parity is candidate-derived, bounded, evidence-bound and OBSERVE-only');
