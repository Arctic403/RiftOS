import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const read = file => fs.readFileSync(path.join(root, file), 'utf8');

const proofPath = 'android/app/src/main/java/com/riftos/app/RiftProofObligationsV1.kt';
const sandboxPath = 'android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt';
const proof = read(proofPath);
const sandbox = read(sandboxPath);
const sourceIntelligence = read('android/app/src/main/java/com/riftos/app/RiftSourceIntelligenceV2.kt');
const gradle = read('android/app/build.gradle.kts');
const ownership = read('docs/SOURCE_OWNERSHIP.md');
const pkg = JSON.parse(read('package.json'));

for (const marker of [
  'const val SCHEMA = "rift-proof-obligations-v1"',
  'const val PHASE = "N1.8.5"',
  'private const val HASH_SCOPE = "project-local-plan-v1"',
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
  '"machine-authority-changed"',
  '"authority-consumer-evidence-missing"',
  '"authority-consumer-tests"',
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
  sandbox.includes('val impact = candidateImpact(path)') &&
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

const hashPayloadBody = proof.slice(
  proof.indexOf('val hashPayload = JSONObject()'),
  proof.indexOf('val proofsSha = RiftPatchManifestV1.sha256Canonical(hashPayload)'),
);
assert.match(hashPayloadBody, /"hashScope", HASH_SCOPE/);
assert.ok(!hashPayloadBody.includes('"candidateId"'), 'workspace-global candidateId must not enter project proof identity');
assert.ok(!hashPayloadBody.includes('"semanticImpactSha256"'), 'workspace-global semantic hash must not enter project proof identity');
assert.ok(!hashPayloadBody.includes('"changedSymbols"'), 'workspace-global changed symbols must not enter project proof identity');
assert.match(proof, /if \(hasChanges && !impactComplete\)/);
assert.match(proof, /if \(hasChanges && projectRows\.size > 1\)/);
assert.match(proof, /RiftSourceIntelligenceV2\.isMachineAuthorityPath/);
for (const futureAuthorityPath of [
  'riftarchitecture/n3-contract-v1.json',
  'riftarchitecture/n3-phase-authority.json',
]) {
  assert.ok(
    sourceIntelligence.includes(`\"${futureAuthorityPath}\"`),
    `future N3 machine-authority path must be pre-registered before N3.0 lands: ${futureAuthorityPath}`,
  );
}
assert.match(proof, /impact\.optJSONArray\("directDependents"\)/);
assert.match(proof, /addStrong\(source, "direct-dependent"\)/);
assert.match(proof, /row\.optString\("kind"\) == "config-read"/);
assert.match(proof, /isVerificationScriptPath\(source\)/);
assert.match(proof, /private fun isVerificationScriptPath\(path: String\): Boolean/);
assert.ok(proof.includes('lower.contains("/scripts/validate-")'));
assert.ok(proof.includes('lower.contains("/scripts/verify-")'));

const LIMITS = Object.freeze({
  changes: 1024,
  selectedTests: 512,
  supplementalTests: 512,
  checks: 128,
  obligations: 1024,
  previewRows: 240,
});

for (const [pattern, message] of [
  [/if \(allChanges\.size > MAX_CHANGES\) incomplete \+= "proof-change-bound"/, 'change +1 guard'],
  [/allChanges\.take\(MAX_CHANGES\)/, 'change cap'],
  [/if \(selectedTestsAll\.size > MAX_SELECTED_TESTS\) incomplete \+= "proof-selected-test-bound"/, 'selected-test +1 guard'],
  [/selectedTestsAll\.take\(MAX_SELECTED_TESTS\)/, 'selected-test cap'],
  [/if \(supplementalAll\.size > MAX_SUPPLEMENTAL_TESTS\) incomplete \+= "proof-supplemental-test-bound"/, 'supplemental-test +1 guard'],
  [/supplementalAll\.take\(MAX_SUPPLEMENTAL_TESTS\)/, 'supplemental-test cap'],
  [/selectedChecks\.size >= MAX_CHECKS/, 'check pre-add cap'],
  [/obligations\.size >= MAX_OBLIGATIONS/, 'obligation pre-add cap'],
  [/obligationRows\.size > MAX_PREVIEW_ROWS/, 'obligation preview +1 guard'],
  [/selectedTestRows\.size > MAX_PREVIEW_ROWS/, 'selected preview +1 guard'],
  [/supplementalRows\.size > MAX_PREVIEW_ROWS/, 'supplemental preview +1 guard'],
  [/checkRows\.size > MAX_PREVIEW_ROWS/, 'check preview +1 guard'],
  [/obligationRows\.take\(MAX_PREVIEW_ROWS\)/, 'obligation preview cap'],
  [/selectedTestRows\.take\(MAX_PREVIEW_ROWS\)/, 'selected preview cap'],
  [/supplementalRows\.take\(MAX_PREVIEW_ROWS\)/, 'supplemental preview cap'],
  [/checkRows\.take\(MAX_PREVIEW_ROWS\)/, 'check preview cap'],
]) {
  assert.match(proof, pattern, `missing N1.8.5 bound semantics: ${message}`);
}

function listBound(count, max) {
  return { kept: Math.min(count, max), incomplete: count > max };
}
function guardedAdds(attempts, max) {
  let kept = 0;
  let incomplete = false;
  for (let i = 0; i < attempts; i += 1) {
    if (kept >= max) {
      incomplete = true;
    } else {
      kept += 1;
    }
  }
  return { kept, incomplete };
}

for (const max of [LIMITS.changes, LIMITS.selectedTests, LIMITS.supplementalTests, LIMITS.previewRows]) {
  assert.deepEqual(listBound(max, max), { kept: max, incomplete: false });
  assert.deepEqual(listBound(max + 1, max), { kept: max, incomplete: true });
}
for (const max of [LIMITS.checks, LIMITS.obligations]) {
  assert.deepEqual(guardedAdds(max, max), { kept: max, incomplete: false });
  assert.deepEqual(guardedAdds(max + 1, max), { kept: max, incomplete: true });
}

const addCommandCalls = [...proof.matchAll(/\baddCommand\(/g)].length - 1;
const addObligationCalls = [...proof.matchAll(/\baddObligation\(/g)].length - 1;
assert.equal(addCommandCalls, 4, 'current planner check cardinality changed; reassess MAX_CHECKS reachability');
assert.equal(addObligationCalls, 12, 'current planner obligation cardinality changed; reassess MAX_OBLIGATIONS reachability');
assert.ok(addCommandCalls < LIMITS.checks);
assert.ok(addObligationCalls < LIMITS.obligations);

function plan({ source = false, changedTest = false, directTest = false, directVerification = false, referenceTest = false, heuristicTest = false, api = false, build = false, authority = false, docs = false, deleted = false, incomplete = false, projects = 1 }) {
  const selected = [];
  if (changedTest) selected.push(['tests/changed.test.js', 'changed-test']);
  if (directTest) selected.push(['tests/direct.test.js', 'direct-dependent']);
  if (directVerification) selected.push(['scripts/validate-rift-docs.mjs', 'direct-dependent']);
  if (referenceTest) selected.push(['tests/reference.test.js', 'changed-symbol-reference']);
  const supplemental = heuristicTest ? ['tests/heuristic.test.js'] : [];
  const deepReasons = [];
  const obligations = [];

  const localActivity = source || changedTest || docs || build;
  if (localActivity && incomplete) deepReasons.push('impact-evidence-incomplete');
  if (localActivity && projects > 1) deepReasons.push('multi-project-candidate');
  if (api) deepReasons.push('api-surface-changed');
  if (build) deepReasons.push('build-config-changed');
  if (authority) deepReasons.push('machine-authority-changed');
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
  if (authority) {
    const hasDirectConsumer = selected.some(([, reason]) => reason === 'direct-dependent');
    obligations.push('authority-consumer-tests:' + (hasDirectConsumer ? 'required' : 'unresolved'));
    if (!hasDirectConsumer) deepReasons.push('authority-consumer-evidence-missing');
  }
  if (changedTest) obligations.push('changed-tests');
  if (docs) obligations.push('documentation-claims');
  if (build) obligations.push('build-pipeline');

  return {
    selected,
    supplemental,
    obligations,
    deepReasons: [...new Set(deepReasons)].sort(),
    mode: localActivity
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

const authorityWithConsumer = plan({ build: true, authority: true, directTest: true });
assert.equal(authorityWithConsumer.mode, 'deep');
assert.ok(authorityWithConsumer.deepReasons.includes('machine-authority-changed'));
assert.ok(authorityWithConsumer.obligations.includes('authority-consumer-tests:required'));

const authorityWithVerificationConsumer = plan({ build: true, authority: true, directVerification: true });
assert.equal(authorityWithVerificationConsumer.mode, 'deep');
assert.deepEqual(authorityWithVerificationConsumer.selected, [['scripts/validate-rift-docs.mjs', 'direct-dependent']]);
assert.ok(authorityWithVerificationConsumer.obligations.includes('authority-consumer-tests:required'));

const authorityMissingConsumer = plan({ build: true, authority: true });
assert.equal(authorityMissingConsumer.mode, 'deep');
assert.ok(authorityMissingConsumer.deepReasons.includes('machine-authority-changed'));
assert.ok(authorityMissingConsumer.deepReasons.includes('authority-consumer-evidence-missing'));
assert.ok(authorityMissingConsumer.obligations.includes('authority-consumer-tests:unresolved'));

const docs = plan({ docs: true });
assert.equal(docs.mode, 'focused');
assert.deepEqual(docs.obligations, ['documentation-claims']);

const none = plan({});
assert.equal(none.mode, 'none');
assert.equal(none.obligations.length, 0);

const unrelated = plan({ projects: 2, incomplete: true });
assert.equal(unrelated.mode, 'none');
assert.deepEqual(unrelated.deepReasons, []);
assert.equal(unrelated.obligations.length, 0);

const multi = plan({ source: true, directTest: true, projects: 2 });
assert.equal(multi.mode, 'deep');
assert.ok(multi.deepReasons.includes('multi-project-candidate'));

assert.equal(direct.unrelatedCanSatisfy, false);
assert.equal(direct.generalSuiteCanSubstitute, false);

console.log('N1.8.5 proof obligations regression OK');
