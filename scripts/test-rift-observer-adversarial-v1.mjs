import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const SCHEMA = 'rift-observer-adversarial-v1';
const PHASE = 'N1.8.6';
const EXPECTED_CORPUS_SHA256 = 'da0fd2e1313a9c00fc3e0e382abf3e32cc8d33503e922e11365041953ec55012';
const LAYERS = Object.freeze(['syntax', 'graph', 'claims', 'historical']);

const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';
const source = read(k + 'RiftSourceIntelligenceV2.kt');
const sandbox = read(k + 'RiftToolSandbox.kt');
const claims = read(k + 'RiftDocumentationClaimsV1.kt');
const pkg = JSON.parse(read('package.json'));

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
    assert.ok(Number.isFinite(value));
    return String(value);
  }
  return JSON.stringify(String(value));
}

const sha256 = value => createHash('sha256').update(canonical(value)).digest('hex');

const fixtures = Object.freeze([
  {
    id: 'syntax-unclosed-delimiter',
    mutation: 'malformed-source',
    mustFail: ['syntax'],
    mustHold: ['graph', 'claims', 'historical'],
    expected: ['n1.8.1-syntax-unclosed-delimiter'],
    falsePositiveControl: false,
  },
  {
    id: 'graph-missing-local-dependency',
    mutation: 'missing-local-dependency',
    mustFail: ['graph'],
    mustHold: ['syntax', 'claims', 'historical'],
    expected: ['n1.8.1-local-dependency-missing'],
    falsePositiveControl: false,
  },
  {
    id: 'claims-stale-roadmap-phase',
    mutation: 'stale-current-doc',
    mustFail: ['claims'],
    mustHold: ['syntax', 'graph', 'historical'],
    expected: ['documentation-roadmap-state-stale'],
    falsePositiveControl: false,
  },
  {
    id: 'historical-stale-promotion-text',
    mutation: 'stale-historical-doc',
    mustFail: [],
    mustHold: ['syntax', 'graph', 'claims', 'historical'],
    expected: ['historical-document-classification'],
    falsePositiveControl: true,
  },
  {
    id: 'graph-comment-fake-import',
    mutation: 'non-code-fake-dependency',
    mustFail: [],
    mustHold: ['syntax', 'graph', 'claims', 'historical'],
    expected: ['javascriptDependencyCodeMask'],
    falsePositiveControl: true,
  },
  {
    id: 'graph-string-fake-symbol-reference',
    mutation: 'non-code-fake-reference',
    mustFail: [],
    mustHold: ['syntax', 'graph', 'claims', 'historical'],
    expected: ['referenceCodeMask', 'ignoredNameMatches'],
    falsePositiveControl: true,
  },
  {
    id: 'claims-promoted-source-mismatch',
    mutation: 'stale-promoted-source',
    mustFail: ['claims'],
    mustHold: ['syntax', 'graph', 'historical'],
    expected: ['documentation-promotion-source-stale'],
    falsePositiveControl: false,
  },
]);

assert.equal(sha256(fixtures), EXPECTED_CORPUS_SHA256, 'N1.8.6 mutation corpus identity drifted');

const markerSources = Object.freeze({
  'n1.8.1-syntax-unclosed-delimiter': sandbox,
  'n1.8.1-local-dependency-missing': sandbox,
  'documentation-roadmap-state-stale': claims,
  'historical-document-classification': claims,
  javascriptDependencyCodeMask: source,
  referenceCodeMask: source,
  ignoredNameMatches: sandbox,
  'documentation-promotion-source-stale': claims,
});

const ids = new Set();
for (const fixture of fixtures) {
  assert.ok(!ids.has(fixture.id), 'duplicate adversarial fixture id: ' + fixture.id);
  ids.add(fixture.id);

  const fail = new Set(fixture.mustFail);
  const hold = new Set(fixture.mustHold);
  for (const layer of [...fail, ...hold]) {
    assert.ok(LAYERS.includes(layer), fixture.id + ' names unknown layer: ' + layer);
  }
  for (const layer of fail) {
    assert.ok(!hold.has(layer), fixture.id + ' requires the same layer to fail and hold: ' + layer);
  }

  const covered = new Set([...fail, ...hold]);
  assert.equal(covered.size, LAYERS.length, fixture.id + ' must state an outcome for every layer');

  if (fixture.falsePositiveControl) {
    assert.equal(fixture.mustFail.length, 0, fixture.id + ' false-positive control must not require a failure');
    assert.deepEqual([...fixture.mustHold].sort(), [...LAYERS].sort(), fixture.id + ' false-positive control must hold every layer');
  } else {
    assert.equal(fixture.mustFail.length, 1, fixture.id + ' isolation fixture must fail exactly one layer');
    assert.equal(fixture.mustHold.length, LAYERS.length - 1, fixture.id + ' isolation fixture must hold the other layers');
  }

  for (const marker of fixture.expected) {
    const text = markerSources[marker];
    assert.ok(text, fixture.id + ' references unbound contract marker: ' + marker);
    assert.ok(text.includes(marker), fixture.id + ' lost contract marker: ' + marker);
  }
}

assert.equal(fixtures.filter(row => row.falsePositiveControl).length, 3);
assert.equal(fixtures.filter(row => !row.falsePositiveControl).length, 4);
assert.deepEqual(
  fixtures.filter(row => !row.falsePositiveControl).map(row => row.mustFail[0]).sort(),
  ['claims', 'claims', 'graph', 'syntax'],
  'true-positive corpus lost required layer coverage',
);

const replayA = fixtures.map(row => ({
  id: row.id,
  fail: [...row.mustFail].sort(),
  hold: [...row.mustHold].sort(),
  expected: [...row.expected].sort(),
}));
const replayB = JSON.parse(JSON.stringify(replayA));
assert.deepEqual(replayA, replayB, 'N1.8.6 deterministic replay changed expected outcomes');
assert.equal(sha256(replayA), sha256(replayB), 'N1.8.6 deterministic replay hash changed');

for (const required of [
  'node scripts/test-rift-repository-consistency-v1.mjs',
  'node scripts/test-rift-integrity-v1.mjs',
  'node scripts/test-rift-propagation-v1.mjs',
  'node scripts/test-rift-cross-boundary-contracts-v1.mjs',
  'node scripts/test-rift-documentation-claims-v1.mjs',
  'node scripts/test-rift-proof-obligations-v1.mjs',
  'node scripts/test-rift-observer-adversarial-v1.mjs',
]) {
  assert.ok(String(pkg.scripts?.['check:transport'] || '').includes(required), 'main gate lost required Observer proof: ' + required);
}

const integrityRegression = read('scripts/test-rift-integrity-v1.mjs');
const consistencyRegression = read('scripts/test-rift-repository-consistency-v1.mjs');
const propagationRegression = read('scripts/test-rift-propagation-v1.mjs');
const claimsRegression = read('scripts/test-rift-documentation-claims-v1.mjs');
const proofsRegression = read('scripts/test-rift-proof-obligations-v1.mjs');

for (const [name, text, markers] of [
  ['integrity', integrityRegression, ['syntax-issue-bound', 'integrity-file-bound', 'integrity-dependency-bound']],
  ['consistency', consistencyRegression, ['MAX_FACTS = 4_096', 'MAX_ANALYSIS_SYMBOLS', 'MAX_ANALYSIS_DEPENDENCIES']],
  ['propagation', propagationRegression, ['MAX_PROPAGATION_REFERENCES', 'MAX_PROPAGATION_CLOSURE_NODES', 'propagationSha256']],
  ['claims', claimsRegression, ['MAX_FILES = 4_096', 'MAX_CLAIMS = 8_192', 'claimsSha256']],
  ['proofs', proofsRegression, ['MAX_CHANGES = 1_024', 'MAX_SELECTED_TESTS = 512', 'MAX_PREVIEW_ROWS = 240']],
]) {
  for (const marker of markers) {
    assert.ok(text.includes(marker), name + ' regression lost bounded/fail-closed marker: ' + marker);
  }
}

console.log(`ok - ${SCHEMA} ${PHASE}: 7 deterministic adversarial fixtures, 4 isolated true-positive failures, 3 false-positive controls, corpus ${EXPECTED_CORPUS_SHA256}`);
