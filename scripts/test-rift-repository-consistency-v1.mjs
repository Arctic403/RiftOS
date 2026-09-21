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

const factId = (kind, stableKey) =>
  'fact-' + sha({ schema: 1, kind, stableKey }).slice(0, 32);

const edgeId = (relation, sourceFactId, targetFactId, stableKey) =>
  'edge-' + sha({ schema: 1, relation, sourceFactId, targetFactId, stableKey }).slice(0, 32);

function fact(kind, stableKey, attributes = {}) {
  const body = {
    kind,
    stableKey,
    path: stableKey,
    line: null,
    evidenceType: 'project-intelligence-v2',
    provenance: 'project-intelligence-v2',
    attributes
  };
  return {
    ...body,
    id: factId(kind, stableKey),
    contentSha256: sha(body)
  };
}

function edge(relation, source, target, stableKey, attributes = {}) {
  const body = {
    relation,
    sourceFactId: source.id,
    targetFactId: target.id,
    stableKey,
    evidenceType: 'project-intelligence-v2',
    provenance: 'project-intelligence-v2',
    attributes
  };
  return {
    ...body,
    id: edgeId(relation, source.id, target.id, stableKey),
    contentSha256: sha(body)
  };
}

function graphHash(facts, edges) {
  const payload = {
    format: 'rift-repository-fact-graph-v1',
    version: 1,
    phase: 'N1.8.0',
    projectRoot: 'workspace/Test',
    projectIntelligence: 'v2',
    sourceOfTruth: 'project-intelligence-v2-derived',
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

const a = fact('file', 'workspace/Test/a.kt', { language: 'kotlin' });
const b = fact('file', 'workspace/Test/b.kt', { language: 'kotlin' });
const dep = fact('dependency-specifier', 'workspace/Test/a.kt|import|2|test.b');
const e1 = edge('declares-dependency', a, dep, dep.stableKey);
const e2 = edge('resolves-to', dep, b, dep.stableKey);

assert.equal(
  graphHash([a, b, dep], [e1, e2]),
  graphHash([dep, a, b], [e2, e1]),
  'graph hash must be independent of map/traversal ordering'
);

const aChangedContent = fact('file', 'workspace/Test/a.kt', { language: 'kotlin', changed: true });
assert.equal(a.id, aChangedContent.id, 'fact content changes must retain stable identity');
assert.notEqual(a.contentSha256, aChangedContent.contentSha256, 'fact content hash must detect changed content');
assert.notEqual(
  graphHash([a, b, dep], [e1, e2]),
  graphHash([aChangedContent, b, dep], [e1, e2]),
  'graph hash must change when fact content changes'
);

const renamed = fact('file', 'workspace/Test/a-renamed.kt', { language: 'kotlin' });
assert.notEqual(a.id, renamed.id, 'identity changes must produce a new fact id');

const sameEdge = edge('resolves-to', dep, b, dep.stableKey, { note: 'content-only' });
assert.equal(e2.id, sameEdge.id, 'edge content changes must retain stable edge identity');
assert.notEqual(e2.contentSha256, sameEdge.contentSha256, 'edge content hash must detect changed edge content');

assert.match(observer, /internal class RiftRepositoryConsistencyObserver/);
assert.match(observer, /const val VERSION = 1/);
assert.match(observer, /const val FORMAT = "rift-repository-fact-graph-v1"/);
assert.match(observer, /const val PHASE = "N1\.8\.0"/);
assert.match(observer, /MAX_FACTS = 4_096/);
assert.match(observer, /MAX_EDGES = 4_096/);
assert.match(observer, /MAX_FINDINGS = 1_024/);
assert.match(observer, /MAX_CACHE_BYTES = 4 \* 1024 \* 1024/);
assert.match(observer, /RiftPatchManifestV1\.sha256Canonical/);
assert.match(observer, /contentHashSeparateFromIdentity/);
assert.match(observer, /sourceOfTruth", "project-intelligence-v2-derived"/);
assert.match(observer, /authoritative", false/);
assert.match(observer, /rebuildableCache", true/);
assert.match(observer, /ATOMIC_MOVE/);
assert.match(observer, /readVerifiedSnapshot/);
assert.match(observer, /private fun findingRecord/);
assert.match(observer, /"ruleId", "category", "severity", "deterministic"/);
assert.match(observer, /"graphPath"/);
assert.match(observer, /"blocksPromotion"/);
assert.match(observer, /"evidenceIncomplete"/);
assert.match(observer, /private fun stableFindingId/);
assert.match(observer, /inferenceMayBlockPromotion", false/);
assert.match(observer, /pi-v2-file-bound/);
assert.match(observer, /pi-v2-edge-bound/);

assert.ok(!observer.includes('walkTopDown('), 'observer must not create a second filesystem index');
assert.ok(!observer.includes('RiftSourceIntelligenceV2.analyze('), 'observer must consume PI-v2 instead of reparsing source');
assert.ok(!observer.includes('refreshSymbolIndex('), 'observer must not own PI-v2 index refresh');

assert.match(sandbox, /private val repositoryConsistencyObserver = RiftRepositoryConsistencyObserver\(appContext\)/);
assert.match(sandbox, /if \(kind == "consistency"\) return projectConsistency\(path, query, requestedLimit\)/);
assert.match(sandbox, /private fun projectConsistency\(path: String, query: String, requestedLimit: Int\)/);
assert.match(sandbox, /MAX_GRAPH_FILES_PREVIEW = 120/);
assert.match(sandbox, /MAX_GRAPH_EDGES = 600/);
assert.match(sandbox, /MAX_CONSISTENCY_INPUT_FILES = 1_024/);
assert.match(sandbox, /MAX_CONSISTENCY_INPUT_EDGES = 1_024/);
assert.match(sandbox, /private fun buildProjectGraph\(/);
assert.match(sandbox, /fileLimit = MAX_CONSISTENCY_INPUT_FILES/);
assert.match(sandbox, /edgeLimit = MAX_CONSISTENCY_INPUT_EDGES/);
assert.match(sandbox, /filesTruncated/);
assert.match(sandbox, /edgesTruncated/);
assert.match(sandbox, /repositoryConsistencyObserver\.foundationView/);
assert.match(sandbox, /if \(mode == "full"\)/);
assert.match(sandbox, /responseMode", "compact"/);
assert.match(sandbox, /project kind=consistency query=full/);
assert.match(sandbox, /requestedLimit\.coerceIn\(1, 40\)/);
assert.match(sandbox, /"graph", "impact", "validation", "consistency"/);
assert.match(gradle, /RiftRepositoryConsistencyObserver\.kt/);

console.log('ok - N1.8.0 repository fact graph has stable identities, separate content hashes, deterministic graph hashing, bounded verified private cache, and reuses PI-v2 without a second index');
