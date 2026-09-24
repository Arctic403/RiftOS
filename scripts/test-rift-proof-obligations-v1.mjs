import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const read = file => fs.readFileSync(path.join(root, file), 'utf8');

const proofPath = 'android/app/src/main/java/com/riftos/app/RiftProofObligationsV1.kt';
const sandboxPath = 'android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt';
const proof = read(proofPath);
const sandbox = read(sandboxPath);
const gradle = read('android/app/build.gradle.kts');
const ownership = read('docs/SOURCE_OWNERSHIP.md');
const pkg = JSON.parse(read('package.json'));

for (const marker of [
  'const val SCHEMA = "rift-proof-obligations-v1"',
  'const val PHASE = "N1.8.5"',
  'private const val MAX_CHANGES = 1_024',
  'private const val MAX_SELECTED_TESTS = 512',
  'private const val MAX_SUPPLEMENTAL_TESTS = 512',
  'private const val MAX_CHECKS = 128',
  'private const val MAX_OBLIGATIONS = 1_024',
  'private const val MAX_PREVIEW_ROWS = 240',
  '.put("evidenceOnly", true)',
  '.put("executesVerification", false)',
  '.put("unrelatedTestsCanSatisfyAffectedTest", false)',
  '.put("heuristicTestsCanSatisfyAffectedTest", false)',
  '.put("generalSuiteCanSubstituteForAffectedTest", false)',
  '.put("missingAffectedTestBecomesUnresolvedObligation", true)',
  '"affected-test-evidence-missing"',
  '"only-heuristic-tests"',
  '"api-surface-changed"',
  '"build-config-changed"',
  '"source-deletion"',
  '"multi-project-candidate"',
  '"proofsSha256"',
  '"unresolved"',
  '"path-affinity"',
]) {
  assert.ok(proof.includes(marker), `missing N1.8.5 proof-planner marker: ${marker}`);
}

assert.ok(!proof.includes('ProcessBuilder'), 'proof planner must not execute verification processes');
assert.ok(!proof.includes('Runtime.getRuntime().exec'), 'proof planner must remain evidence-only');
assert.ok(
  sandbox.includes('if (kind == "proofs") return projectProofs(path)'),
  'proofs project view is not dispatched',
);
assert.ok(
  sandbox.includes('val impact = candidateImpact()') &&
  sandbox.includes('val validation = projectValidation(path, "")') &&
  sandbox.includes('RiftProofObligationsV1().analyze('),
  'proofs view must consume exact candidate impact plus existing validation evidence',
);
assert.ok(
  sandbox.includes('"claims", "proofs"'),
  'project view metadata does not advertise claims/proofs',
);
assert.ok(
  gradle.includes('"src/main/java/com/riftos/app/RiftProofObligationsV1.kt"'),
  'proof planner missing from mandatory Android source snapshot',
);
assert.ok(
  ownership.includes(`| \`${proofPath}\` |`),
  'proof planner missing from source ownership ledger',
);
assert.ok(
  pkg.scripts['check:transport'].includes('node scripts/test-rift-proof-obligations-v1.mjs'),
  'N1.8.5 regression must be reachable from npm check',
);

function plan({ source = false, changedTest = false, directTest = false, referenceTest = false, heuristicTest = false, api = false, build = false, docs = false, deleted = false, incomplete = false, projects = 1 }) {
  const selected = [];
  if (changedTest) selected.push(['tests/changed.test.js', 'changed-test']);
  if (directTest) selected.push(['tests/direct.test.js', 'direct-dependent']);
  if (referenceTest) selected.push(['tests/reference.test.js', 'changed-symbol-reference']);
  const supplemental = heuristicTest ? ['tests/heuristic.test.js'] : [];
  const deepReasons = [];
  const obligations = [];

  if (incomplete) deepReasons.push('impact-evidence-incomplete');
  if (projects > 1) deepReasons.push('multi-project-candidate');
  if (api) deepReasons.push('api-surface-changed');
  if (build) deepReasons.push('build-config-changed');
  if (deleted) deepReasons.push('source-deletion');

  if (source) {
    obligations.push('source-integrity', 'semantic-propagation', 'cross-boundary-contracts');
    if (selected.length === 0) {
      obligations.push('affected-tests:unresolved');
      deepReasons.push('affected-test-evidence-missing');
      if (supplemental.length) deepReasons.push('only-heuristic-tests');
    } else {
      obligations.push('affected-tests:required');
    }
  }
  if (changedTest) obligations.push('changed-tests');
  if (docs) obligations.push('documentation-claims');
  if (build) obligations.push('build-pipeline');

  return {
    selected,
    supplemental,
    obligations,
    deepReasons: [...new Set(deepReasons)].sort(),
    mode: source || changedTest || docs || build
      ? (deepReasons.length ? 'deep' : 'focused')
      : 'none',
    unrelatedCanSatisfy: false,
    generalSuiteCanSubstitute: false,
  };
}

const direct = plan({ source: true, directTest: true });
assert.equal(direct.mode, 'focused');
assert.deepEqual(direct.selected, [['tests/direct.test.js', 'direct-dependent']]);
assert.ok(direct.obligations.includes('affected-tests:required'));
assert.equal(direct.supplemental.length, 0);

const heuristicOnly = plan({ source: true, heuristicTest: true });
assert.equal(heuristicOnly.mode, 'deep');
assert.ok(heuristicOnly.obligations.includes('affected-tests:unresolved'));
assert.ok(heuristicOnly.deepReasons.includes('affected-test-evidence-missing'));
assert.ok(heuristicOnly.deepReasons.includes('only-heuristic-tests'));
assert.equal(heuristicOnly.selected.length, 0);
assert.deepEqual(heuristicOnly.supplemental, ['tests/heuristic.test.js']);

const api = plan({ source: true, directTest: true, api: true });
assert.equal(api.mode, 'deep');
assert.ok(api.deepReasons.includes('api-surface-changed'));

const build = plan({ build: true });
assert.equal(build.mode, 'deep');
assert.ok(build.obligations.includes('build-pipeline'));
assert.ok(build.deepReasons.includes('build-config-changed'));

const docs = plan({ docs: true });
assert.equal(docs.mode, 'focused');
assert.deepEqual(docs.obligations, ['documentation-claims']);

const none = plan({});
assert.equal(none.mode, 'none');
assert.equal(none.obligations.length, 0);

const multi = plan({ source: true, directTest: true, projects: 2 });
assert.equal(multi.mode, 'deep');
assert.ok(multi.deepReasons.includes('multi-project-candidate'));

assert.equal(direct.unrelatedCanSatisfy, false);
assert.equal(direct.generalSuiteCanSubstitute, false);

console.log('N1.8.5 proof obligations regression OK');
