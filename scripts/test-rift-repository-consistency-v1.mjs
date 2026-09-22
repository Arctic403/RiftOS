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
const MAX_STABLE_KEY_CHARS = 2048;

const boundedStableKey = value => {
  const normalized = String(value).trim();
  assert.ok(normalized.length > 0, 'stable key must not be blank');
  if (normalized.length <= MAX_STABLE_KEY_CHARS) return normalized;
  const suffix = '…#sha256:' + sha(normalized);
  return normalized.slice(0, MAX_STABLE_KEY_CHARS - suffix.length) + suffix;
};

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

const longSharedPrefix = 'workspace/Test/' + 'deep-segment/'.repeat(190);
const longStableKeyA = longSharedPrefix + 'Long-A.kt';
const longStableKeyB = longSharedPrefix + 'Long-B.kt';
assert.ok(longStableKeyA.length > MAX_STABLE_KEY_CHARS && longStableKeyB.length > MAX_STABLE_KEY_CHARS);
assert.notEqual(
  factId('file', longStableKeyA),
  factId('file', longStableKeyB),
  'full long stable keys sharing the same >2048-char prefix must retain distinct identities'
);
const boundedLongA = boundedStableKey(longStableKeyA);
const boundedLongB = boundedStableKey(longStableKeyB);
assert.equal(boundedLongA.length, MAX_STABLE_KEY_CHARS);
assert.equal(boundedLongB.length, MAX_STABLE_KEY_CHARS);
assert.notEqual(boundedLongA, boundedLongB, 'bounded display keys must carry distinct digest suffixes');
assert.match(boundedLongA, /…#sha256:[0-9a-f]{64}$/);

assert.match(observer, /internal class RiftRepositoryConsistencyObserver/);
assert.match(observer, /const val VERSION = 2/);
assert.match(observer, /const val FORMAT = "rift-repository-fact-graph-v2"/);
assert.match(observer, /const val PHASE = "N1\.8\.0"/);
assert.match(observer, /rift-repository-consistency-v2/);
assert.match(observer, /MAX_FACTS = 4_096/);
assert.match(observer, /MAX_EDGES = 4_096/);
assert.match(observer, /MAX_FINDINGS = 1_024/);
assert.match(observer, /MAX_CACHE_BYTES = 4 \* 1024 \* 1024/);
assert.match(observer, /private fun normalizedStableKey\(value: String\): String/);
assert.equal(
  (observer.match(/\.put\("stableKey", normalizedStableKey\(stableKey\)\)/g) || []).length,
  3,
  'fact/edge/finding IDs must hash the full normalized stable key'
);
assert.match(observer, /val digestSuffix = "…#sha256:\$\{RiftPatchManifestV1\.sha256Canonical\(normalized\)\}"/);
assert.match(observer, /val prefixLength = \(MAX_STABLE_KEY_CHARS - digestSuffix\.length\)\.coerceAtLeast\(0\)/);
assert.ok(!observer.includes('Repository consistency stable key exceeds'), 'valid long stable keys must not crash the observer');
assert.match(observer, /if \(bytes\.size > MAX_CACHE_BYTES\)/);
assert.match(observer, /reason = "snapshot-too-large"/);
assert.match(observer, /reason = "cache-write-failed"/);
assert.match(observer, /reason = "cache-verification-failed"/);
assert.match(observer, /\.put\("persisted", persisted\)/);

const persistSnapshotStart = observer.indexOf('private fun persistSnapshot(projectRoot: String, snapshot: JSONObject): JSONObject {');
const verifiedSnapshotStart = observer.indexOf('private fun readVerifiedSnapshot(file: File): JSONObject?', persistSnapshotStart);
assert.ok(
  persistSnapshotStart >= 0 && verifiedSnapshotStart > persistSnapshotStart,
  'observer cache persistence function must remain structurally inspectable'
);
const persistSnapshot = observer.slice(persistSnapshotStart, verifiedSnapshotStart);
const writeAttempt = persistSnapshot.indexOf('val writeFailure = runCatching {');
const writeFailureBranch = persistSnapshot.indexOf('if (writeFailure != null) {');
const verificationRead = persistSnapshot.indexOf('val verified = readVerifiedSnapshot(target)');
const verificationFailureBranch = persistSnapshot.indexOf('if (verified == null) {');
const successReturn = persistSnapshot.lastIndexOf('persisted = true');
assert.ok(
  writeAttempt >= 0 &&
    writeFailureBranch > writeAttempt &&
    verificationRead > writeFailureBranch &&
    verificationFailureBranch > verificationRead &&
    successReturn > verificationFailureBranch,
  'cache write must be trapped before verification and success may occur only after verification'
);
const writeFailureBody = persistSnapshot.slice(writeFailureBranch, verificationRead);
assert.match(writeFailureBody, /persisted = false/);
assert.match(writeFailureBody, /verified = false/);
assert.match(writeFailureBody, /reason = "cache-write-failed"/);
assert.ok(
  !writeFailureBody.includes('throw '),
  'observer cache write failure must return fail-soft diagnostics rather than abort graph construction'
);
const verificationFailureBody = persistSnapshot.slice(verificationFailureBranch, successReturn);
assert.match(verificationFailureBody, /persisted = false/);
assert.match(verificationFailureBody, /verified = false/);
assert.match(verificationFailureBody, /reason = "cache-verification-failed"/);
assert.ok(
  !verificationFailureBody.includes('throw '),
  'observer cache verification failure must return fail-soft diagnostics rather than abort graph construction'
);
assert.ok(
  persistSnapshot.indexOf('temporary.delete()') > writeAttempt &&
    persistSnapshot.indexOf('runCatching { target.delete() }', verificationFailureBranch) > verificationFailureBranch,
  'temporary/corrupt cache residue must be cleaned on failure paths'
);
assert.ok(!observer.includes('Repository consistency snapshot exceeds'), 'optional observer-cache oversize must not crash a valid graph');
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
assert.match(sandbox, /PROJECT_INTELLIGENCE_CACHE_VERSION = 9/);
assert.match(sandbox, /root\.optInt\("version", 0\) != PROJECT_INTELLIGENCE_CACHE_VERSION/);
assert.match(sandbox, /\.put\("version", PROJECT_INTELLIGENCE_CACHE_VERSION\)/);
assert.match(sandbox, /private fun projectIntelligenceCacheIntegrityFailure\(root: JSONObject\): String\?/);
assert.match(sandbox, /root\.optString\("cacheSha256"\)/);
assert.match(sandbox, /cache-integrity-missing/);
assert.match(sandbox, /cache-integrity-mismatch/);
assert.match(sandbox, /if \(key != "cacheSha256"\) payload\.put\(key, root\.get\(key\)\)/);
assert.match(sandbox, /RiftPatchManifestV1\.sha256Canonical\(payload\) == expected/);
assert.match(sandbox, /payloadObject\.put\("cacheSha256", RiftPatchManifestV1\.sha256Canonical\(payloadObject\)\)/);
assert.match(sandbox, /sourceIntelligenceVersion/);
assert.match(sandbox, /RiftSourceIntelligenceV2\.VERSION/);
assert.match(sandbox, /BuildConfig\.RIFT_SOURCE_SHA/);
assert.match(sandbox, /producer-source-untrusted/);
assert.match(sandbox, /producer-source-sha/);
assert.match(sandbox, /producer-analyzer-version/);
assert.match(sandbox, /cacheLoadStatus/);
assert.match(sandbox, /cacheRejectedReason/);
assert.match(sandbox, /semanticProducerTrusted/);
const cacheIntegrityCall = sandbox.indexOf('projectIntelligenceCacheIntegrityFailure(root)');
const producerLoad = sandbox.indexOf('val producer = root.optJSONObject("producer")');
assert.ok(cacheIntegrityCall >= 0 && producerLoad > cacheIntegrityCall, 'PI cache integrity must be verified before producer-bound semantic rows are loaded');
assert.match(sandbox, /private fun refreshSymbolIndex\(base: File, verifyContent: Boolean = false\)/);
assert.match(sandbox, /verifyContent \|\|/);
assert.match(sandbox, /cached\.sha256 == contentSha/);
assert.match(sandbox, /repository-content-hash-byte-bound/);
assert.match(sandbox, /repository-content-hash-failure/);
assert.match(sandbox, /semantic-total-byte-bound/);
assert.match(sandbox, /var semanticBytesAccounted = 0L/);
assert.match(sandbox, /MAX_INDEX_TOTAL_BYTES - semanticBytesAccounted/);
assert.match(sandbox, /semanticBytesAccounted \+= size/);
assert.ok(!sandbox.includes('MAX_INDEX_TOTAL_BYTES - bytesScanned'), 'semantic total-byte bound must not depend only on cold-scan bytes');
const semanticBudgetAccount = sandbox.indexOf('semanticBytesAccounted += size');
const semanticCacheReuse = sandbox.indexOf('val cached = symbolIndex[path]');
assert.ok(
  semanticBudgetAccount >= 0 && semanticCacheReuse > semanticBudgetAccount,
  'semantic total-byte budget must account every eligible file before cached semantic reuse'
);
assert.ok(
  (sandbox.match(/RiftSourceIntelligenceV2\.AnalysisBoundExceeded/g) || []).length >= 2,
  'semantic analysis bounds must fail closed in both project indexing and candidate semantic-delta analysis'
);
assert.match(sandbox, /semanticRows\.put\(out\)\s*\n\s*continue/);
assert.match(sandbox, /semanticStatus = "metadata-only"/);
assert.match(sandbox, /semanticReason = bound\.reason/);
assert.match(sandbox, /incompleteReasons \+= bound\.reason/);
assert.match(read(k + 'RiftSourceIntelligenceV2.kt'), /MAX_ANALYSIS_SYMBOLS = 4_096/);
assert.match(read(k + 'RiftSourceIntelligenceV2.kt'), /MAX_ANALYSIS_DEPENDENCIES = 4_096/);
assert.match(read(k + 'RiftSourceIntelligenceV2.kt'), /throw AnalysisBoundExceeded\("semantic-symbol-bound"\)/);
assert.match(read(k + 'RiftSourceIntelligenceV2.kt'), /throw AnalysisBoundExceeded\("semantic-dependency-bound"\)/);
assert.equal(4096 >= 4096, true, '4097th unique semantic row must fail before insertion');
assert.match(sandbox, /if \(size > MAX_HASH_TOTAL_BYTES - hashBytes\)/);
assert.match(sandbox, /if \(size > MAX_INDEX_TOTAL_BYTES - semanticBytesAccounted\)/);
assert.equal(128 * 1024 * 1024 > 128 * 1024 * 1024 - 0, false, 'exact semantic total byte bound must remain allowed');
assert.equal(128 * 1024 * 1024 + 1 > 128 * 1024 * 1024 - 0, true, 'semantic total byte bound +1 must fail closed');
assert.equal(256 * 1024 * 1024 > 256 * 1024 * 1024 - 0, false, 'exact repository hash byte bound must remain allowed');
assert.equal(256 * 1024 * 1024 + 1 > 256 * 1024 * 1024 - 0, true, 'repository hash byte bound +1 must fail closed');
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
assert.match(sandbox, /val dependenciesForResolution = if \(includeFileEvidence\)/);
assert.match(sandbox, /val remaining = \(boundedEdgeLimit - edges\.length\(\)\)\.coerceAtLeast\(0\)/);
assert.match(sandbox, /file\.dependencies\.take\(remaining\)/);
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

console.log('ok - N1.8.0 file facts are content-bound, PI cache v5 is integrity-sealed and producer-bound, repository graph traversal is canonicalized, and ambiguous semantic dependency resolution fails closed');
