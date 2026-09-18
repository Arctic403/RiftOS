import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';
const source = read(k + 'RiftSourceIntelligenceV2.kt');
const records = read(k + 'RiftWorkspaceRecords.kt');
const sandbox = read(k + 'RiftToolSandbox.kt');
const host = read(k + 'RiftToolHost.kt');
const gradle = read('android/app/build.gradle.kts');
const ownership = read('docs/SOURCE_OWNERSHIP.md');

assert.match(source, /internal object RiftSourceIntelligenceV2/);
assert.match(source, /const val VERSION = 2/);
assert.match(source, /MAX_SEMANTIC_DELTA_ENTRIES = 1_000/);
assert.match(source, /fun analyze\(/);
assert.match(source, /fun diff\(/);
assert.match(source, /addedSymbols/);
assert.match(source, /removedSymbols/);
assert.match(source, /changedSignatures/);
assert.match(source, /addedDependencies/);
assert.match(source, /removedDependencies/);
assert.match(source, /apiSurfaceChanged/);
assert.match(source, /isBuildConfigPath/);
assert.match(source, /classifyPath/);

assert.match(records, /fun semanticImpactSeed\(\): JSONObject/);
assert.match(records, /MAX_SEMANTIC_SEED_CHANGES = 4_096/);
assert.match(records, /MAX_SEMANTIC_SEED_SOURCE_FILES = 1_024/);
assert.match(records, /MAX_SEMANTIC_SEED_TEXT_BYTES = 8L \* 1024L \* 1024L/);
assert.match(records, /buildSemanticImpactSeed\(buildCandidateManifest\(\)\)/);
assert.match(records, /"semanticTextComplete"/);
assert.match(records, /"source-text-unavailable"/);
assert.match(records, /"source-text-budget"/);

assert.match(sandbox, /RiftSourceIntelligenceV2\.analyze\(path, text, MAX_SEARCH_PREVIEW_CHARS\)/);
assert.ok(!sandbox.includes('private fun extractSymbols('), 'PI-v2 parser duplication returned');
assert.ok(!sandbox.includes('private fun extractDependencies('), 'PI-v2 dependency parser duplication returned');
assert.ok(!sandbox.includes('private fun languageFor('), 'PI-v2 language parser duplication returned');

assert.match(sandbox, /private fun candidateImpact\(\): JSONObject/);
assert.match(sandbox, /workspaceRecords\.semanticImpactSeed\(\)/);
assert.match(sandbox, /semanticImpactSha256/);
assert.match(sandbox, /ownershipDocsFor/);
assert.match(sandbox, /SOURCE_OWNERSHIP\.md/);
assert.match(sandbox, /MAX_CANDIDATE_PROJECTS = 32/);
assert.match(sandbox, /MAX_CANDIDATE_REFERENCE_SYMBOLS = 80/);
assert.match(sandbox, /MAX_CANDIDATE_REFERENCES = 800/);
assert.match(sandbox, /MAX_CANDIDATE_DEPENDENCIES = 800/);
assert.match(sandbox, /MAX_CANDIDATE_DEPENDENTS = 800/);
assert.match(sandbox, /MAX_CANDIDATE_TESTS = 300/);
assert.match(sandbox, /MAX_CANDIDATE_DOCS = 300/);
assert.match(sandbox, /"changed-symbol-bound"/);
assert.match(sandbox, /"reference-symbol-bound"/);
assert.match(sandbox, /"project-index-truncated"/);
assert.match(sandbox, /RiftPatchManifestV1\.sha256Canonical\(payload\)/);

assert.match(host, /internal fun candidateImpactAsync/);
assert.ok(!host.includes('rift_candidate_impact'));
assert.ok(!host.includes('rift_semantic_impact'));

assert.match(gradle, /RiftSourceIntelligenceV2\.kt/);
assert.match(ownership, /RiftSourceIntelligenceV2\.kt/);
assert.match(ownership, /scripts\/test-rift-semantic-impact-v1\.mjs/);

console.log('ok - Patch 5 semantic impact reuses PI-v2, derives scope from the exact candidate, stays bounded/deterministic and is not an MCP tool');
