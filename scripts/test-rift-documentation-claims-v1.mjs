import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const read = (file) => fs.readFileSync(path.join(root, file), 'utf8');

const claimsPath = 'android/app/src/main/java/com/riftos/app/RiftDocumentationClaimsV1.kt';
const authorityPath = 'observer/phase-authority.json';
const sandboxPath = 'android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt';
const gradlePath = 'android/app/build.gradle.kts';

const claims = read(claimsPath);
const authorityText = read(authorityPath);
const authority = JSON.parse(authorityText);
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
  'private const val PHASE_AUTHORITY_PATH = "observer/phase-authority.json"',
  'private const val PHASE_AUTHORITY_SCHEMA = "rift-observer-phase-authority-v1"',
  'private const val MAX_PHASE_AUTHORITY_BYTES = 64L * 1024L',
  'claims-phase-authority-missing',
  'claims-phase-authority-size-bound',
  'claims-phase-authority-read-failure',
  'claims-phase-authority-invalid',
  '.put("authority", "source-build-runtime-over-documentation")',
  '.put("direction", "authority->claims")',
  '.put("documentationMayOverrideAuthority", false)',
  '.put("freeFormProseInference", false)',
  '"machine-readable-phase-authority"',
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

assert.ok(!claims.includes('private val PHASE_AUTHORITY ='), 'compiled phase registry must not return');
assert.ok(!claims.includes('private const val ROADMAP_STATUS'), 'compiled ROADMAP lifecycle must not return');
assert.ok(!claims.includes('private const val OBSERVER_STATUS'), 'compiled Observer lifecycle must not return');

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
assert.ok(ownership.includes(`| \`${claimsPath}\` |`), 'claims oracle is missing from source ownership');
assert.ok(ownership.includes(`| \`${authorityPath}\` |`), 'phase authority is missing from source ownership');
assert.ok(Buffer.byteLength(authorityText, 'utf8') <= 64 * 1024, 'phase authority exceeded 64 KiB');

function validateAuthority(value) {
  if (!value || value.schema !== 'rift-observer-phase-authority-v1') return false;
  if (typeof value.roadmapStatus !== 'string' || !value.roadmapStatus.trim() || value.roadmapStatus.length > 4096) return false;
  if (typeof value.observerStatus !== 'string' || !value.observerStatus.trim() || value.observerStatus.length > 4096) return false;
  if (!Array.isArray(value.phases) || value.phases.length !== 8) return false;

  const rank = new Map([['promoted', 0], ['source-implemented', 1], ['pending', 2]]);
  let previous = -1;
  let sourceImplementedCount = 0;

  for (let index = 0; index < value.phases.length; index++) {
    const row = value.phases[index];
    if (!row || row.phase !== `N1.8.${index}`) return false;
    const order = rank.get(row.status);
    if (order === undefined || order < previous) return false;

    const source = row.promotedSourceSha ?? null;
    const run = row.builderRunNumber ?? null;
    if (source !== null && !/^[0-9a-f]{40}$/.test(source)) return false;
    if (run !== null && !/^[0-9]{1,20}$/.test(String(run))) return false;

    if (row.status === 'promoted') {
      if (source === null) return false;
    } else if (source !== null || run !== null) {
      return false;
    }

    if (row.status === 'source-implemented') sourceImplementedCount++;
    if (sourceImplementedCount > 1) return false;
    previous = order;
  }
  return true;
}

assert.equal(validateAuthority(authority), true, 'phase authority file is invalid');
assert.equal(validateAuthority({...authority, schema: 'broken'}), false, 'bad authority schema was accepted');
assert.equal(
  validateAuthority({...authority, phases: authority.phases.slice(0, 7)}),
  false,
  'short phase authority was accepted',
);
const duplicateSourceImplemented = JSON.parse(JSON.stringify(authority));
duplicateSourceImplemented.phases[5] = {
  phase: 'N1.8.5',
  status: 'source-implemented',
  promotedSourceSha: null,
  builderRunNumber: null,
};
assert.equal(validateAuthority(duplicateSourceImplemented), false, 'multiple source-implemented phases were accepted');

for (const phase of authority.phases.filter(row => row.status === 'promoted')) {
  const statusLine = projectStatus.split('\n').find(line => line.startsWith(`- **${phase.phase}`));
  assert.ok(statusLine, `${phase.phase} project-status line missing`);
  const observedSource = statusLine.match(/PROMOTED on installed source `([0-9a-f]{40})`/)?.[1];
  assert.equal(observedSource, phase.promotedSourceSha, `${phase.phase} source claim drifted from machine authority`);
  if (phase.builderRunNumber !== null) {
    const observedRun = statusLine.match(/run number `([0-9]+)`/)?.[1];
    assert.equal(observedRun, String(phase.builderRunNumber), `${phase.phase} run claim drifted from machine authority`);
  }
}

assert.ok(roadmap.includes(authority.roadmapStatus), 'ROADMAP current N1.8 phase state disagrees with machine authority');
assert.ok(observer.includes(authority.observerStatus), 'canonical Observer state disagrees with machine authority');

const current = [...authority.phases].reverse().find(row => row.status !== 'pending');
if (current?.status === 'source-implemented') {
  assert.ok(
    projectStatus.split('\n').some(line =>
      line.startsWith(`- **${current.phase}`) &&
      /SOURCE-IMPLEMENTED/i.test(line) &&
      /promotion-pending/i.test(line)
    ),
    `${current.phase} project status does not expose source-implemented/promotion-pending`,
  );
}

function roadmapStateIsValid(text) {
  return text.includes(authority.roadmapStatus);
}
assert.equal(roadmapStateIsValid(roadmap), true, 'clean ROADMAP fixture should match machine authority');
assert.equal(
  roadmapStateIsValid(roadmap.replace(authority.roadmapStatus, 'N1.8 BROKEN STATUS')),
  false,
  'stale ROADMAP state mutation was not detected',
);

function promotedSourceMatches(text, phase, expected) {
  const line = text.split('\n').find(row => row.startsWith(`- **${phase}`));
  return line?.match(/PROMOTED on installed source `([0-9a-f]{40})`/)?.[1] === expected;
}
const mutationPhase = [...authority.phases].reverse().find(row => row.status === 'promoted');
assert.ok(mutationPhase, 'no promoted phase available for mutation fixture');
assert.equal(promotedSourceMatches(projectStatus, mutationPhase.phase, mutationPhase.promotedSourceSha), true);
const mutationLine = projectStatus.split('\n').find(line => line.startsWith(`- **${mutationPhase.phase}`));
assert.ok(mutationLine, `${mutationPhase.phase} status line missing`);
const mutatedProjectStatus = projectStatus.replace(
  mutationLine,
  mutationLine.replace(mutationPhase.promotedSourceSha, '0000000000000000000000000000000000000000'),
);
assert.equal(
  promotedSourceMatches(mutatedProjectStatus, mutationPhase.phase, mutationPhase.promotedSourceSha),
  false,
  'promotion-source mutation was not detected',
);

for (const marker of [
  'Documentation is **UNVERIFIED by default**',
  'Ownership does **not** imply that a source is packaged, live, verified, trusted or device-proven.',
  'documentation agreement cannot prove',
]) {
  assert.ok(claims.includes(marker), `source authority-policy marker missing: ${marker}`);
}

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
