import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';

const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';
const source = read(k + 'RiftSourceIntelligenceV2.kt');
const records = read(k + 'RiftWorkspaceRecords.kt');
const sandbox = read(k + 'RiftToolSandbox.kt');
const host = read(k + 'RiftToolHost.kt');
const gradle = read('android/app/build.gradle.kts');
const ownership = read('docs/SOURCE_OWNERSHIP.md');

assert.match(source, /internal object RiftSourceIntelligenceV2/);
assert.match(source, /const val VERSION = 7/);
assert.match(source, /MAX_SEMANTIC_DELTA_ENTRIES = 1_000/);
assert.match(source, /fun analyze\(/);
assert.match(source, /fun diff\(/);
assert.match(source, /addedSymbols/);
assert.match(source, /removedSymbols/);
assert.match(source, /changedSignatures/);
assert.match(source, /addedDependencies/);
assert.match(source, /removedDependencies/);
assert.match(source, /apiSurfaceChanged/);
assert.match(source, /isMachineAuthorityPath/);
assert.match(source, /isBuildConfigPath/);
assert.match(source, /classifyPath/);

assert.match(records, /fun semanticImpactSeed\(\): JSONObject/);
assert.match(records, /MAX_SEMANTIC_SEED_CHANGES = 4_096/);
assert.match(records, /MAX_SEMANTIC_SEED_SOURCE_FILES = 1_024/);
assert.match(records, /MAX_SEMANTIC_SEED_TEXT_BYTES = 8L \* 1024L \* 1024L/);
assert.match(records, /buildSemanticImpactSeed\(buildCandidateManifest\(\)\)/);
assert.match(records, /prepareForRead\("semantic-impact-seed", requireCurrent = true\)/);
assert.doesNotMatch(records, /semanticImpactSeed\(\)[\s\S]{0,300}reconcileAll\("semantic-impact-seed"\)/);
assert.match(records, /MAX_RECORDS = 256/);
assert.match(records, /candidateSessionRows\(changedPaths\)/);
assert.match(records, /"semanticTextComplete"/);
assert.match(records, /"source-text-unavailable"/);
assert.match(records, /"source-text-budget"/);

assert.match(sandbox, /RiftSourceIntelligenceV2\.analyze\(path, text, MAX_SEARCH_PREVIEW_CHARS\)/);
assert.ok(!sandbox.includes('private fun extractSymbols('), 'PI-v2 parser duplication returned');
assert.ok(!sandbox.includes('private fun extractDependencies('), 'PI-v2 dependency parser duplication returned');
assert.ok(!sandbox.includes('private fun languageFor('), 'PI-v2 language parser duplication returned');

assert.match(sandbox, /private fun candidateImpact\(\): JSONObject/);
assert.match(sandbox, /workspaceRecords\.semanticImpactSeed\(\)/);
const candidateImpactBody = sandbox.slice(
  sandbox.indexOf('private fun candidateImpact(): JSONObject'),
  sandbox.indexOf('private fun candidateReferences(')
);
assert.match(candidateImpactBody, /refreshSymbolIndex\(sandboxFile\(root\)\)/);
assert.match(candidateImpactBody, /"candidate-projects"/);
assert.match(candidateImpactBody, /"no-candidate-changes"/);
assert.ok(!candidateImpactBody.includes('refreshSymbolIndex(workspaceRoot)'), 'candidate impact must never refresh the entire workspace');
assert.match(candidateImpactBody, /candidateStateSha256/,'semantic candidate must carry stable state identity');
assert.match(candidateImpactBody, /candidate\.put\("evidenceManifestSha256", candidateSeed\.getString\("manifestSha256"\)\)/,'full evidence manifest must be attached only as diagnostics');
const semanticHashIndex = candidateImpactBody.indexOf('val semanticSha = RiftPatchManifestV1.sha256Canonical(payload)');
const evidenceManifestIndex = candidateImpactBody.indexOf('candidate.put("evidenceManifestSha256"');
assert.ok(semanticHashIndex >= 0 && evidenceManifestIndex > semanticHashIndex,'evidence manifest SHA must not participate in semantic impact identity');
assert.match(sandbox, /semanticImpactSha256/);
assert.match(sandbox, /ownershipDocsFor/);
assert.match(sandbox, /SOURCE_OWNERSHIP\.md/);
assert.match(sandbox, /MAX_CANDIDATE_PROJECTS = 32/);
assert.match(sandbox, /MAX_CANDIDATE_REFERENCE_SYMBOLS = 80/);
assert.match(sandbox, /MAX_CANDIDATE_REFERENCES = 800/);
const candidateReferencesBody = sandbox.slice(
  sandbox.indexOf('private fun candidateReferences('),
  sandbox.indexOf('private fun ownershipDocsFor(')
);
assert.match(candidateReferencesBody, /symbolTargets: Map<String, Set<String>>/);
assert.match(candidateReferencesBody, /RiftSourceIntelligenceV2\.isSourcePath\(path\)/);
assert.match(candidateReferencesBody, /RiftSourceIntelligenceV2\.referenceCodeMask\(path, referenceText\)/);
assert.match(candidateReferencesBody, /resolveDependency\(projectRoot, path, dependency, resolutionPaths\)/);
assert.match(candidateReferencesBody, /val sameFile = path in targets/);
assert.match(candidateReferencesBody, /val resolvedDependency = targets\.any \{ it in dependencyTargets \}/);
assert.match(candidateReferencesBody, /if \(!sameFile && !resolvedDependency\) return@matchLoop/);
assert.match(sandbox, /candidateReferences\(indexed, referenceNames, changedSymbolTargets, resolutionPaths\)/);
assert.match(sandbox, /MAX_CANDIDATE_DEPENDENCIES = 800/);
assert.match(sandbox, /MAX_CANDIDATE_DEPENDENTS = 800/);
assert.match(sandbox, /MAX_CANDIDATE_TESTS = 300/);
assert.match(sandbox, /MAX_CANDIDATE_DOCS = 300/);
assert.match(candidateImpactBody, /config-read-target-bound/);
assert.match(candidateImpactBody, /config-read-test-bound/);
assert.match(candidateImpactBody, /\.put\("kind", "config-read"\)/);
assert.match(candidateImpactBody, /val configReadConsumerPathsAll = indexed\.keys\.filter\(::isVerificationScriptPath\)\.sorted\(\)/);
assert.match(candidateImpactBody, /changedTests \+= consumerPath/);
assert.match(candidateImpactBody, /RiftSourceIntelligenceV2\.referenceCodeMask\(consumerPath, consumerText\)/);
assert.match(sandbox, /private fun isVerificationScriptPath\(path: String\): Boolean/);
assert.match(sandbox, /"changed-symbol-bound"/);
assert.match(sandbox, /"reference-symbol-bound"/);
assert.match(sandbox, /changedSymbolTargets/);
assert.match(sandbox, /RiftSourceIntelligenceV2\.referenceCodeMask\(path, referenceText\)/);
assert.match(sandbox, /RiftSourceIntelligenceV2\.isSourcePath\(path\)/);
assert.match(sandbox, /resolveDependency\(projectRoot, path, dependency, resolutionPaths\)/);
assert.match(sandbox, /val sameFile = path in targets/);
assert.match(sandbox, /val resolvedDependency = targets\.any \{ it in dependencyTargets \}/);
assert.match(sandbox, /if \(!sameFile && !resolvedDependency\) return@matchLoop/);
assert.doesNotMatch(
  sandbox.slice(sandbox.indexOf('private fun candidateReferences('), sandbox.indexOf('private fun ownershipDocsFor(')),
  /bufferedReader\(Charsets\.UTF_8\)\.useLines/,
  'candidate references must use code-masked semantic text, not raw line scanning',
);
assert.match(sandbox, /"project-index-truncated"/);
assert.match(sandbox, /RiftPatchManifestV1\.sha256Canonical\(payload\)/);

assert.match(host, /internal fun candidateImpactAsync/);
assert.ok(!host.includes('rift_candidate_impact'));
assert.ok(!host.includes('rift_semantic_impact'));

assert.match(gradle, /RiftSourceIntelligenceV2\.kt/);
assert.match(ownership, /RiftSourceIntelligenceV2\.kt/);
assert.match(ownership, /scripts\/test-rift-semantic-impact-v1\.mjs/);


const authorityRelativePath = 'riftmemory/n2-phase-authority.json';
const authorityLiteral = authorityRelativePath.replace(/[-/\\^$*+?.()|[\]{}]/g, '\\$&');
const directReadPatterns = [
  new RegExp('\\b(?:read|[A-Za-z_][A-Za-z0-9_]*Read)\\s*\\(\\s*[\\\'"]' + authorityLiteral + '[\\\'"]'),
  new RegExp('\\breadFileSync\\s*\\(\\s*(?:path\\.)?join\\([^\\n)]*[\\\'"]' + authorityLiteral + '[\\\'"]'),
  new RegExp('\\breadFileSync\\s*\\(\\s*[\\\'"]' + authorityLiteral + '[\\\'"]'),
];
const authorityConsumers = readdirSync('scripts')
  .filter(name => /^(?:test-|validate-|verify-).*\.(?:mjs|js)$/.test(name))
  .filter(name => directReadPatterns.some(pattern => pattern.test(read('scripts/' + name))))
  .sort();

for (const expected of [
  'test-rift-memory-n2-contract-v1.mjs',
  'test-rift-memory-n2-m1-v1.mjs',
  'test-rift-memory-n2-m2-v1.mjs',
  'test-rift-memory-n2-m3-v1.mjs',
  'test-rift-memory-n2-m4-v1.mjs',
  'test-rift-memory-n2-m5-v1.mjs',
  'test-rift-memory-n2-m6-v1.mjs',
  'validate-rift-docs.mjs',
]) {
  assert.ok(authorityConsumers.includes(expected), 'phase-authority direct consumer not detected: ' + expected);
}

console.log('ok - Patch 5 semantic impact reuses PI-v2, derives scope from the exact candidate, stays bounded/deterministic and is not an MCP tool');
