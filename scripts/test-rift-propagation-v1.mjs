import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';
const source = read(k + 'RiftSourceIntelligenceV2.kt');
const sandbox = read(k + 'RiftToolSandbox.kt');
const observer = read(k + 'RiftRepositoryConsistencyObserver.kt');
const pkg = JSON.parse(read('package.json'));

assert.match(source, /fun referenceCodeMask\(path: String, text: String\): BooleanArray/);
assert.match(source, /private fun genericReferenceCodeMask\(language: String, text: String\): BooleanArray/);
assert.match(source, /javascriptDependencyCodeMask\(normalized\)/);

for (const token of [
  'MAX_PROPAGATION_SEEDS = 64',
  'MAX_PROPAGATION_SYMBOLS = 8_192',
  'MAX_PROPAGATION_REFERENCES = 1_024',
  'MAX_PROPAGATION_CALLERS = 512',
  'MAX_PROPAGATION_TYPE_RELATIONS = 512',
  'MAX_PROPAGATION_CLOSURE_NODES = 1_024',
  'MAX_PROPAGATION_CLOSURE_EDGES = 4_096',
  'MAX_PROPAGATION_DEPTH = 16',
  'MAX_PROPAGATION_PREVIEW = 240',
]) {
  assert.ok(sandbox.includes(token), 'missing propagation bound: ' + token);
}

assert.match(sandbox, /if \(kind == "propagation"\) return projectPropagation\(path, query, requestedLimit\)/);
assert.match(sandbox, /private fun projectPropagation\(path: String, query: String, requestedLimit: Int\): JSONObject/);
assert.match(sandbox, /"rift-semantic-propagation-v1"/);
assert.match(sandbox, /"N1\.8\.2"/);
assert.match(sandbox, /"authority", "evidence-only"/);
assert.match(sandbox, /refreshSymbolIndex\(base, verifyContent = true\)/);

assert.match(sandbox, /data class PropagationSymbolNode\(/);
assert.match(sandbox, /data class PropagationTypeRelation\(/);
assert.ok(sandbox.includes('val baseKey = path + "|" + symbol.kind + "|" + symbol.name'));
assert.ok(sandbox.includes('val symbolId = "sym-" + sha256(baseKey + "|" + ordinal).take(24)'));
assert.ok(sandbox.includes('val signatureId = "sig-" + sha256(symbolId + "|" + normalizedSignature).take(24)'));
assert.match(sandbox, /"symbolKey", "path\|kind\|name\|ordinal"/);
assert.match(sandbox, /"symbolIdStableAcrossLineShift", true/);
assert.match(sandbox, /"signatureIdChangesWithSignature", true/);

assert.match(sandbox, /integrityResolveDependency\(/);
assert.match(sandbox, /addReverseEdge\(target, sourcePath, "dependency"\)/);
assert.match(sandbox, /dependencyCandidates = nameCandidates\.filter \{ it\.symbol\.path in dependencyTargets \}/);
assert.match(sandbox, /val seedIds = seedNodes\.map \{ it\.symbolId \}\.toSet\(\)/);
assert.match(sandbox, /val relevantToSeed = when/);
assert.match(sandbox, /RiftSourceIntelligenceV2\.referenceCodeMask\(sourcePath, referenceText\)/);
assert.match(sandbox, /val absoluteIndex = lineOffset \+ match\.range\.first/);
assert.match(sandbox, /codeMask\.getOrNull\(absoluteIndex\) != true/);
assert.match(sandbox, /ignoredNameMatches \+= 1/);
assert.match(sandbox, /val relevantRelationRows = relationRows\.filter/);
assert.match(sandbox, /var closureRelevantEdgeCount = 0/);
assert.match(sandbox, /sameFileCandidates\.isNotEmpty\(\)/);
assert.match(sandbox, /"ambiguousReferences"/);
assert.match(sandbox, /"unresolvedReferences"/);

assert.match(sandbox, /caller = nodesByPath\[sourcePath\]/);
assert.match(sandbox, /lineNumber >= it\.symbol\.line/);
assert.match(sandbox, /lineNumber <= it\.symbol\.endLine/);
assert.match(sandbox, /"callerId"/);
assert.match(sandbox, /"callerPath"/);

assert.match(sandbox, /private fun propagationTypeTargets\(/);
assert.match(sandbox, /fun topLevelInheritanceClause\(/);
assert.match(sandbox, /fun splitTopLevelTypes\(/);
const kotlinTypeBlock = sandbox.slice(sandbox.indexOf('if (language == "kotlin") {'), sandbox.indexOf('if (language == "cpp") {'));
assert.ok(!kotlinTypeBlock.includes('tail.substringAfter(\':\', "").substringBefore(\'{\')'), 'Kotlin inheritance must not use the retired constructor-colon heuristic');
assert.ok(sandbox.includes("':' -> if (parenDepth == 0 && angleDepth == 0 && bracketDepth == 0)"), 'Kotlin inheritance colon must be top-level');
assert.match(sandbox, /"extends"/);
assert.match(sandbox, /"implements"/);
assert.match(sandbox, /"inherits-or-implements"/);
assert.match(sandbox, /"inherits"/);
assert.match(sandbox, /"propagation-type-relation-bound"/);

assert.match(sandbox, /val queue = ArrayDeque<Pair<String, Int>>\(\)/);
assert.match(sandbox, /"propagation-depth-bound"/);
assert.match(sandbox, /"propagation-closure-node-bound"/);
assert.match(sandbox, /"propagation-closure-edge-bound"/);
assert.match(sandbox, /"propagation-reference-bound"/);
const relevantSeedIndex = sandbox.indexOf('if (!relevantToSeed) {');
const referenceBoundIndex = sandbox.indexOf('if (referenceCount >= MAX_PROPAGATION_REFERENCES) {');
const referenceEmitIndex = sandbox.indexOf('referenceRows.put(JSONObject()', referenceBoundIndex);
assert.ok(
  relevantSeedIndex >= 0 &&
  referenceBoundIndex > relevantSeedIndex &&
  referenceEmitIndex > referenceBoundIndex,
  'reference bound must run after lexical/seed filtering and immediately before real row emission'
);
assert.match(sandbox, /"propagation-caller-bound"/);
assert.match(sandbox, /"propagation-seed-bound"/);
assert.match(sandbox, /"propagation-symbol-bound"/);
assert.match(sandbox, /"propagation-seed-not-found"/);

assert.match(sandbox, /propagationSha256 = RiftPatchManifestV1\.sha256Canonical\(canonicalPayload\)/);
assert.match(sandbox, /"propagationSha256"/);
assert.match(sandbox, /"reverseClosure"/);
assert.match(sandbox, /"reverseClosurePreviewTruncated"/);
assert.match(sandbox, /"typeRelationsPreviewTruncated"/);
assert.match(sandbox, /"referencesPreviewTruncated"/);

const start = sandbox.indexOf('private fun projectPropagation(path: String, query: String, requestedLimit: Int): JSONObject {');
const end = sandbox.indexOf('private fun projectImpact(', start);
assert.ok(start >= 0 && end > start, 'propagation view must remain structurally isolated');
const body = sandbox.slice(start, end);
for (const forbidden of [
  'commitStaged(',
  'deletePath(',
  'invalidateIndex(',
  'atomicWrite(',
  'writeText(',
  'copyPath(',
]) {
  assert.ok(!body.includes(forbidden), 'propagation view gained mutation authority: ' + forbidden);
}
assert.ok(!body.includes('repositoryConsistencyObserver.foundationView'), 'N1.8.2 must not mutate N1.8.0 canonical observer graph');

assert.match(observer, /const val PHASE = "N1\.8\.0"/);
const check = String(pkg.scripts?.['check:transport'] || '');
assert.ok(
  check.includes('node scripts/test-rift-propagation-v1.mjs'),
  'main source gate must execute the N1.8.2 propagation regression'
);

console.log('ok - N1.8.2 propagation lane has stable symbol/signature identity, dependency-backed references/callers, type relations, bounded reverse closure, deterministic hash, and zero mutation authority');
