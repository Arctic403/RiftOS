import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';
const observer = read(k + 'RiftRepositoryConsistencyObserver.kt');
const sandbox = read(k + 'RiftToolSandbox.kt');
const gradle = read('android/app/build.gradle.kts');

function canonical(value) {
  if (value === null || value === undefined) return 'null';
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (typeof value === 'object') {
    return '{' + Object.keys(value).sort()
      .map(key => JSON.stringify(key) + ':' + canonical(value[key]))
      .join(',') + '}';
  }
  if (typeof value === 'string') return JSON.stringify(value);
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'number') {
    assert.ok(Number.isFinite(value), 'canonical graph numbers must be finite');
    return String(value);
  }
  return JSON.stringify(String(value));
}

const sha = value => createHash('sha256').update(canonical(value)).digest('hex');
const FACT_SCHEMA = 2;

const factId = (kind, stableKey) =>
  'fact-' + sha({ schema: FACT_SCHEMA, kind, stableKey }).slice(0, 32);

const edgeId = (relation, sourceFactId, targetFactId, stableKey) =>
  'edge-' + sha({ schema: FACT_SCHEMA, relation, sourceFactId, targetFactId, stableKey }).slice(0, 32);

function fact(kind, stableKey, attributes = {}, evidenceType = 'project-intelligence-v2') {
  const body = {
    kind,
    stableKey,
    path: stableKey,
    line: null,
    evidenceType,
    provenance: 'project-intelligence-v2',
    attributes
  };
  return { ...body, id: factId(kind, stableKey), contentSha256: sha(body) };
}

function edge(relation, source, target, stableKey, attributes = {}, evidenceType = 'project-intelligence-v2') {
  const body = {
    relation,
    sourceFactId: source.id,
    targetFactId: target.id,
    stableKey,
    evidenceType,
    provenance: 'project-intelligence-v2',
    attributes
  };
  return { ...body, id: edgeId(relation, source.id, target.id, stableKey), contentSha256: sha(body) };
}

function graphHash(facts, edges) {
  const payload = {
    format: 'rift-repository-fact-graph-v2',
    version: 2,
    phase: 'N1.8.0',
    projectRoot: 'workspace/Test',
    projectIntelligence: 'v2',
    sourceOfTruth: 'project-intelligence-v2-content-verified',
    authoritative: false,
    rebuildableCache: true,
    complete: true,
    incompleteReasons: [],
    facts: [...facts].sort((a, b) => a.id.localeCompare(b.id)),
    edges: [...edges].sort((a, b) => a.id.localeCompare(b.id)),
    findings: []
  };
  return sha(payload);
}

const a = fact(
  'file',
  'workspace/Test/a.kt',
  { size: 40, sha256: 'a'.repeat(64), semanticStatus: 'indexed', semanticReason: null },
  'project-intelligence-v2-file-evidence'
);
const b = fact(
  'file',
  'workspace/Test/b.kt',
  { size: 20, sha256: 'b'.repeat(64), semanticStatus: 'indexed', semanticReason: null },
  'project-intelligence-v2-file-evidence'
);
const dep = fact('dependency-specifier', 'workspace/Test/a.kt|import|2|test.b');
const e1 = edge('declares-dependency', a, dep, dep.stableKey);
const e2 = edge('resolves-to', dep, b, dep.stableKey);

assert.equal(
  graphHash([a, b, dep], [e1, e2]),
  graphHash([dep, a, b], [e2, e1]),
  'graph hash must be traversal-order independent'
);

const aChangedBytes = fact(
  'file',
  'workspace/Test/a.kt',
  { size: 40, sha256: 'c'.repeat(64), semanticStatus: 'indexed', semanticReason: null },
  'project-intelligence-v2-file-evidence'
);
assert.equal(a.id, aChangedBytes.id, 'same path must retain stable file fact identity');
assert.notEqual(a.contentSha256, aChangedBytes.contentSha256, 'file byte SHA must change file fact content hash');
assert.notEqual(
  graphHash([a, b, dep], [e1, e2]),
  graphHash([aChangedBytes, b, dep], [e1, e2]),
  'content-only file changes must change canonical repository graph hash'
);

const metadataOnly = fact(
  'file',
  'workspace/Test/a.kt',
  { size: 40, sha256: 'a'.repeat(64), semanticStatus: 'metadata-only', semanticReason: 'binary-extension' },
  'project-intelligence-v2-file-evidence'
);
assert.equal(a.id, metadataOnly.id, 'semantic classification must not change file identity');
assert.notEqual(a.contentSha256, metadataOnly.contentSha256, 'semantic classification must be represented in fact content');

const renamed = fact(
  'file',
  'workspace/Test/a-renamed.kt',
  { size: 40, sha256: 'a'.repeat(64), semanticStatus: 'indexed', semanticReason: null },
  'project-intelligence-v2-file-evidence'
);
assert.notEqual(a.id, renamed.id, 'path identity changes must produce a new file fact id');

const sameEdge = edge('resolves-to', dep, b, dep.stableKey, { note: 'content-only' });
assert.equal(e2.id, sameEdge.id, 'edge content changes must retain stable edge identity');
assert.notEqual(e2.contentSha256, sameEdge.contentSha256, 'edge content hash must detect changed edge content');

assert.match(observer, /internal class RiftRepositoryConsistencyObserver/);
assert.match(observer, /const val VERSION = 2/);
assert.match(observer, /const val FORMAT = "rift-repository-fact-graph-v2"/);
assert.match(observer, /const val PHASE = "N1\.8\.0"/);
assert.match(observer, /rift-repository-consistency-v2/);
assert.match(observer, /MAX_FACTS = 4_096/);
assert.match(observer, /MAX_EDGES = 4_096/);
assert.match(observer, /MAX_FINDINGS = 1_024/);
assert.match(observer, /MAX_CACHE_BYTES = 4 \* 1024 \* 1024/);
assert.match(observer, /contentHashSeparateFromIdentity/);
assert.match(observer, /fileContentBound/);
assert.match(observer, /sourceOfTruth", "project-intelligence-v2-content-verified"/);
assert.match(observer, /repositoryFileEvidence/);
assert.match(observer, /repository-content-unverified/);
assert.match(observer, /repository-file-content-unavailable/);
assert.match(observer, /repository-file-evidence-missing/);
assert.match(observer, /project-intelligence-v2-file-evidence/);
assert.match(observer, /ATOMIC_MOVE/);
assert.match(observer, /readVerifiedSnapshot/);
assert.match(observer, /private fun findingRecord/);
assert.match(observer, /private fun stableFindingId/);
assert.match(observer, /inferenceMayBlockPromotion", false/);
assert.ok(!observer.includes('walkTopDown('), 'observer must not create a second filesystem scan');
assert.ok(!observer.includes('RiftSourceIntelligenceV2.analyze('), 'observer must consume PI-v2 evidence');
assert.ok(!observer.includes('refreshSymbolIndex('), 'observer must not own PI-v2 refresh');

assert.match(sandbox, /private data class RepositoryFileEvidence\(/);
assert.match(sandbox, /val sha256: String\?/);
assert.match(sandbox, /private val repositoryFileIndex = LinkedHashMap<String, RepositoryFileEvidence>\(\)/);
assert.match(sandbox, /val sha256: String,/);
assert.match(sandbox, /PROJECT_INTELLIGENCE_CACHE_VERSION = 4/);
assert.match(sandbox, /root\.optInt\("version", 0\) != PROJECT_INTELLIGENCE_CACHE_VERSION/);
assert.match(sandbox, /\.put\("version", PROJECT_INTELLIGENCE_CACHE_VERSION\)/);
assert.match(sandbox, /sourceIntelligenceVersion/);
assert.match(sandbox, /RiftSourceIntelligenceV2\.VERSION/);
assert.match(sandbox, /BuildConfig\.RIFT_SOURCE_SHA/);
assert.match(sandbox, /producer-source-untrusted/);
assert.match(sandbox, /producer-source-sha/);
assert.match(sandbox, /producer-analyzer-version/);
assert.match(sandbox, /cacheLoadStatus/);
assert.match(sandbox, /cacheRejectedReason/);
assert.match(sandbox, /semanticProducerTrusted/);
assert.match(sandbox, /private fun refreshSymbolIndex\(base: File, verifyContent: Boolean = false\)/);
assert.match(sandbox, /verifyContent \|\|/);
assert.match(sandbox, /cached\.sha256 == contentSha/);
assert.match(sandbox, /repository-content-hash-byte-bound/);
assert.match(sandbox, /repository-content-hash-failure/);
assert.match(sandbox, /semantic-total-byte-bound/);
assert.match(sandbox, /private fun isPolicyExcludedFile\(/);
assert.match(sandbox, /private fun semanticClassification\(/);
assert.match(sandbox, /"metadata-only" to "binary-extension"/);
assert.match(sandbox, /repositoryFileIndex\.remove/);
assert.match(sandbox, /repositoryFileEvidence/);
assert.match(sandbox, /repositoryContentVerified/);
assert.match(sandbox, /includeFileEvidence = true/);
assert.match(sandbox, /verifyRepositoryContent = true/);
assert.match(sandbox, /fileLimit = MAX_CONSISTENCY_INPUT_FILES/);
assert.match(sandbox, /edgeLimit = MAX_CONSISTENCY_INPUT_EDGES/);
assert.ok(
  !sandbox.includes('cached.modified == file.lastModified() && cached.size == file.length()'),
  'content-verified consistency must not rely on mtime+size identity'
);

assert.match(sandbox, /repositoryConsistencyObserver\.foundationView/);
assert.match(sandbox, /if \(mode == "full"\)/);
assert.match(sandbox, /responseMode", "compact"/);
assert.match(sandbox, /requestedLimit\.coerceIn\(1, 40\)/);
assert.match(gradle, /RiftRepositoryConsistencyObserver\.kt/);

function uniqueDependencyCandidate(candidates) {
  const distinct = [...new Set(candidates)].slice(0, 2);
  return distinct.length === 1 ? distinct[0] : null;
}

assert.equal(
  uniqueDependencyCandidate(['workspace/Test/z/Foo.kt', 'workspace/Test/a/Foo.kt']),
  null,
  'ambiguous dependency candidates must fail closed instead of picking traversal order'
);
assert.equal(
  uniqueDependencyCandidate(['workspace/Test/a/Foo.kt', 'workspace/Test/z/Foo.kt']),
  null,
  'reversing ambiguous candidate order must not create a resolution'
);
assert.equal(
  uniqueDependencyCandidate(['workspace/Test/a/Foo.kt']),
  'workspace/Test/a/Foo.kt',
  'a unique dependency candidate must still resolve'
);

assert.match(sandbox, /val indexed = symbolIndex\.filterKeys \{ isPathWithin\(it, path\) \}\.toSortedMap\(\)/);
assert.match(sandbox, /val repositoryFiles = repositoryFileIndex\.filterKeys \{ isPathWithin\(it, path\) \}\.toSortedMap\(\)/);
assert.match(sandbox, /repositoryFiles\.keys\.toSortedSet\(\)/);
assert.match(sandbox, /indexed\.keys\.toSortedSet\(\)/);
assert.match(sandbox, /private fun uniqueDependencyCandidate\(candidates: Sequence<String>\): String\?/);
assert.match(sandbox, /return distinct\.singleOrNull\(\)/);
assert.match(sandbox, /specifier\.removeSuffix\("\.\*"\)\.trimEnd\('\.'\)\.replace\('\.', '\/'\)/);
const resolverStart = sandbox.indexOf('private fun uniqueDependencyCandidate');
const resolverEnd = sandbox.indexOf('\n    private fun searchText', resolverStart);
assert.ok(resolverStart >= 0 && resolverEnd > resolverStart, 'dependency resolver source must be locatable');
const resolver = sandbox.slice(resolverStart, resolverEnd);
assert.ok(!resolver.includes('allPaths.firstOrNull'), 'Python resolution must not pick the first traversal-order match');
assert.ok(!resolver.includes('symbolIndex.entries.firstOrNull'), 'symbol resolution must not pick the first traversal-order match');

console.log('ok - N1.8.0 file facts are content-bound, PI cache v4 is producer-bound, repository graph traversal is canonicalized, and ambiguous semantic dependency resolution fails closed');
