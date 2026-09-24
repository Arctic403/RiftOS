import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const read = (file) => fs.readFileSync(path.join(root, file), 'utf8');

const claimsPath = 'android/app/src/main/java/com/riftos/app/RiftDocumentationClaimsV1.kt';
const sandboxPath = 'android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt';
const gradlePath = 'android/app/build.gradle.kts';

const claims = read(claimsPath);
const sandbox = read(sandboxPath);
const gradle = read(gradlePath);
const ownership = read('docs/SOURCE_OWNERSHIP.md');
const roadmap = read('ROADMAP.md');
const projectStatus = read('docs/PROJECT_STATUS.md');
const observer = read('docs/systems/workspace/REPOSITORY_CONSISTENCY_OBSERVER.md');

for (const marker of [
  'const val SCHEMA = "rift-documentation-claims-v1"',
  'const val PHASE = "N1.8.4"',
  'private const val MAX_FILES = 4_096',
  'private const val MAX_FILE_BYTES = 2L * 1024L * 1024L',
  'private const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L',
  'private const val MAX_CLAIMS = 8_192',
  'private const val MAX_FINDINGS = 1_024',
  'private const val MAX_PREVIEW_ROWS = 240',
  '.put("authority", "source-build-runtime-over-documentation")',
  '.put("direction", "authority->claims")',
  '.put("documentationMayOverrideAuthority", false)',
  '.put("freeFormProseInference", false)',
  '"historical-document-classification"',
  '"todo-lifecycle"',
  '"project-status-promoted-source"',
  '"project-status-promoted-run"',
  '"documentation-authority-policy"',
  '"roadmap-phase-state"',
  '"ownership-ledger-coverage"',
  '"relative-markdown-link"',
  'claimsSha256',
]) {
  assert.ok(claims.includes(marker), `missing claims-oracle contract marker: ${marker}`);
}

assert.ok(
  claims.indexOf('documentationMayOverrideAuthority", false') >= 0 &&
  claims.indexOf('freeFormProseInference", false') >= 0,
  'N1.8.4 must never let docs override authority or infer free-form prose as truth',
);
assert.ok(
  claims.includes('sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))'),
  'claim scan traversal must be deterministic',
);
assert.ok(
  claims.includes('if (incomplete.isNotEmpty())') ||
  claims.includes('val initiallyIncomplete = incomplete.isNotEmpty()'),
  'claim scan must expose fail-closed incomplete state',
);

assert.ok(
  sandbox.includes('if (kind == "claims") return RiftDocumentationClaimsV1(workspaceRoot).analyze(base)'),
  'claims project view is not dispatched',
);
for (const view of ['graph', 'impact', 'validation', 'consistency', 'integrity', 'propagation', 'contracts', 'claims']) {
  assert.ok(sandbox.includes(`"${view}"`), `projectViews metadata lost ${view}`);
}
assert.ok(
  gradle.includes('"src/main/java/com/riftos/app/RiftDocumentationClaimsV1.kt"'),
  'claims oracle is missing from mandatory Android source snapshot',
);
assert.ok(
  ownership.includes(`| \`${claimsPath}\` |`),
  'claims oracle is missing from source ownership',
);

const phaseAuthority = new Map([
  ['N1.8.0', '9d196567e38e781d97a24bb2c808b47cbc2303eb'],
  ['N1.8.1', '198a3f31e22a5d385378fee087aa5f115aed6d5a'],
  ['N1.8.2', '9cc74b25c94fd3e23e93f64d3d132e65e63fe3a6'],
  ['N1.8.3', 'e6de353ead6e9377e36e1602e301e3e8231a5e43'],
]);
for (const [phase, sha] of phaseAuthority) {
  assert.ok(claims.includes(`PhaseAuthority("${phase}", "promoted", "${sha}"`), `${phase} source authority missing`);
  const statusLine = projectStatus.split('\n').find(line => line.startsWith(`- **${phase}`));
  assert.ok(statusLine, `${phase} project-status line missing`);
  const observed = statusLine.match(/PROMOTED on installed source `([0-9a-f]{40})`/)?.[1];
  assert.equal(observed, sha, `${phase} project-status source claim drifted from source authority`);
}
for (const [phase, run] of [['N1.8.2', '317'], ['N1.8.3', '322']]) {
  assert.ok(claims.includes(`"${phase}", "promoted"`) && claims.includes(`"${run}"`), `${phase} run authority missing`);
  const statusLine = projectStatus.split('\n').find(line => line.startsWith(`- **${phase}`));
  assert.equal(statusLine?.match(/run number `([0-9]+)`/)?.[1], run, `${phase} promoted run drifted from source authority`);
}
for (const marker of [
  'Documentation is **UNVERIFIED by default**',
  'Ownership does **not** imply that a source is packaged, live, verified, trusted or device-proven.',
  'documentation agreement cannot prove',
]) {
  assert.ok(claims.includes(marker), `source authority-policy marker missing: ${marker}`);
}

const roadmapStatus =
  'N1.8.0 + N1.8.1 + N1.8.2 + N1.8.3 PROMOTED; N1.8.4 ACTIVE; N1.8.5-N1.8.7 PENDING';
assert.ok(roadmap.includes(roadmapStatus), 'ROADMAP current N1.8 phase state disagrees with source authority');

const observerStatus =
  'N1.8.0 + N1.8.1 + N1.8.2 + N1.8.3 PROMOTED ON INSTALLED ARM32-COMPATIBLE ANDROID TARGET; N1.8.4 SOURCE-IMPLEMENTED / PROMOTION PENDING; N1.8.5+ PENDING';
assert.ok(observer.includes(observerStatus), 'canonical Observer current phase state disagrees with source authority');
assert.ok(
  projectStatus.split('\n').some(line =>
    line.startsWith('- **N1.8.4') &&
    /SOURCE-IMPLEMENTED/i.test(line) &&
    /promotion-pending/i.test(line)
  ),
  'PROJECT_STATUS does not expose N1.8.4 as source-implemented/promotion-pending',
);

function roadmapStateIsValid(text) {
  return text.includes(roadmapStatus);
}
assert.equal(roadmapStateIsValid(roadmap), true, 'clean ROADMAP fixture should match source authority');
assert.equal(
  roadmapStateIsValid(roadmap.replace('N1.8.4 ACTIVE', 'N1.8.4 PENDING')),
  false,
  'stale ROADMAP state mutation was not detected',
);

function promotedSourceMatches(text, phase, expected) {
  const line = text.split('\n').find(row => row.startsWith(`- **${phase}`));
  return line?.match(/PROMOTED on installed source `([0-9a-f]{40})`/)?.[1] === expected;
}
assert.equal(promotedSourceMatches(projectStatus, 'N1.8.3', phaseAuthority.get('N1.8.3')), true);
const n183StatusLine = projectStatus.split('\n').find(line => line.startsWith('- **N1.8.3'));
assert.ok(n183StatusLine, 'N1.8.3 status line missing');
const mutatedProjectStatus = projectStatus.replace(
  n183StatusLine,
  n183StatusLine.replace(
    phaseAuthority.get('N1.8.3'),
    '0000000000000000000000000000000000000000',
  ),
);
assert.equal(
  promotedSourceMatches(
    mutatedProjectStatus,
    'N1.8.3',
    phaseAuthority.get('N1.8.3'),
  ),
  false,
  'promotion-source mutation was not detected',
);

function isHistoricalDocumentation(file) {
  const lower = file.toLowerCase();
  const name = lower.split('/').at(-1);
  return lower === 'docs/patch_history.md' ||
    ['changelog.md', 'history.md', 'release_notes.md'].includes(name) ||
    lower.includes('/archive/') ||
    lower.includes('/archives/') ||
    lower.includes('/history/');
}
assert.equal(isHistoricalDocumentation('docs/PATCH_HISTORY.md'), true);
assert.equal(isHistoricalDocumentation('docs/PROJECT_STATUS.md'), false);
assert.equal(isHistoricalDocumentation('docs/archive/old.md'), true);

function relativeLinkState(docPath, href, existing) {
  if (!href || href.startsWith('#') || /^(?:https?:|mailto:|tel:)/i.test(href)) return 'ignored';
  const target = href.split('#')[0];
  if (!target) return 'ignored';
  const normalized = path.posix.normalize(path.posix.join(path.posix.dirname(docPath), target));
  if (normalized === '..' || normalized.startsWith('../')) return 'contradicted';
  return existing.has(normalized) ? 'verified' : 'contradicted';
}
const fixtureFiles = new Set(['docs/ok.md']);
assert.equal(relativeLinkState('README.md', 'docs/ok.md', fixtureFiles), 'verified');
assert.equal(relativeLinkState('README.md', 'docs/missing.md', fixtureFiles), 'contradicted');
assert.equal(relativeLinkState('README.md', 'https://example.com', fixtureFiles), 'ignored');
assert.equal(relativeLinkState('docs/README.md', '../../escape.md', fixtureFiles), 'contradicted');

assert.ok(
  observer.includes('documentation is never repository truth') &&
  observer.includes('documentation agreement cannot prove'),
  'canonical N1.8.4 authority rule was weakened',
);

console.log('N1.8.4 documentation claims regression OK');
