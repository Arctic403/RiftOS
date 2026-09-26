import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const ownershipPath = path.join(root, 'docs/SOURCE_OWNERSHIP.md');
const ownership = fs.readFileSync(ownershipPath, 'utf8');

const requiredDocs = [
  'docs/README.md',
  'docs/PUBLIC_SURFACES.md',
  'docs/systems/README.md',
  'docs/systems/engine/README.md',
  'docs/systems/boot/README.md',
  'docs/systems/shell-ui/README.md',
  'docs/systems/android-host/README.md',
  'docs/systems/kernel/README.md',
  'docs/systems/riftfs/README.md',
  'docs/systems/native-dispatcher/README.md',
  'docs/systems/transfers/README.md',
  'docs/systems/desktop/README.md',
  'docs/systems/browser/README.md',
  'docs/systems/browser/engine/README.md',
  'docs/systems/browser/engine/android-webview/README.md',
  'docs/systems/browser/mcp-compat/README.md',
  'docs/systems/browser/ai-adapters/README.md',
  'docs/systems/mcp/README.md',
  'docs/systems/mcp/server/README.md',
  'docs/systems/mcp/tool-host/README.md',
  'docs/systems/mcp/sandbox/README.md',
  'docs/systems/mcp/relay/README.md',
  'docs/systems/mcp/project-exporter/README.md',
  'docs/systems/workspace/README.md',
  'docs/systems/workspace/live/README.md',
  'docs/systems/dev-lab/README.md',
  'docs/systems/riftllm-bridge/README.md',
  'docs/systems/apps/README.md',
  'docs/systems/riftrt/README.md',
  'docs/systems/riftrt/engines/README.md',
  'docs/systems/riftrt/engines/native-webview/README.md',
  'docs/systems/riftrt/engines/worker-js/README.md',
  'docs/systems/riftrt/engines/wasm-base64/README.md',
  'docs/systems/riftrt/engines/native-arm64/README.md',
  'docs/systems/riftrt/engines/rift-vm/README.md',
  'docs/systems/riftpp-core/README.md',
  'docs/systems/runtime-capabilities/README.md',
  'docs/systems/shell/README.md',
  'docs/systems/riftcli/README.md',
  'docs/systems/git/README.md',
  'docs/systems/riftrepo/README.md',
  'docs/systems/riftvault/README.md',
  'docs/systems/riftbuild/README.md',
  'docs/systems/riftmemory/README.md',
  'docs/systems/riftmemory/N2_FEDERATED_MEMORY_ROADMAP.md',
  'docs/systems/files-app/README.md',
  'docs/systems/settings/README.md',
  'docs/systems/preview/README.md',
  'docs/systems/diagnostics/README.md',
  'docs/systems/debugger/README.md',
  'docs/systems/secrets/README.md',
  'docs/systems/relay-service/README.md',
  'docs/systems/build-validation/README.md',
  'docs/systems/vortex-agent/README.md',
  'docs/systems/chat-handoff/README.md',
];

const localIndexes = [
  'README.md', 'src/README.md', 'android/README.md', 'android/app/README.md',
  'android/app/src/main/java/com/riftos/app/README.md',
  'android/app/src/main/assets/README.md', 'android/app/src/main/assets/adapters/README.md',
  'android/app/src/main/res/README.md', 'apps/README.md', 'scripts/README.md',
  'workspace-live/README.md', 'relay/README.md',
];

const ownedSources = [
  'index.html', 'styles.css', 'package.json',
  'android/build.gradle.kts', 'android/settings.gradle.kts', 'android/gradle.properties',
  'android/app/build.gradle.kts', 'android/app/src/main/AndroidManifest.xml',
  'android/app/src/main/res/values/styles.xml', 'android/app/src/main/res/xml/vortex_agent_accessibility.xml', 'android/riftos-debug.keystore.b64',
  ...fs.readdirSync(path.join(root, 'src')).filter(name => /\.(?:js|css)$/.test(name)).map(name => `src/${name}`),
  ...fs.readdirSync(path.join(root, 'android/app/src/main/java/com/riftos/app')).filter(name => name.endsWith('.kt')).map(name => `android/app/src/main/java/com/riftos/app/${name}`),
  ...walk('android/app/src/main/assets').filter(file => file.endsWith('.js')),
  ...walk('workspace-live').filter(file => /\.(?:js|html|css)$/.test(file)),
  ...walk('relay').filter(file => file === 'relay/package.json' || file === 'relay/wrangler.jsonc' || file.endsWith('.js')),
  ...walk('scripts').filter(file => file.endsWith('.mjs')),
];

function walk(relative) {
  const base = path.join(root, relative);
  if (!fs.existsSync(base)) return [];
  const out = [];
  for (const entry of fs.readdirSync(base, { withFileTypes: true })) {
    const child = `${relative}/${entry.name}`;
    if (entry.isDirectory()) out.push(...walk(child));
    else out.push(child);
  }
  return out;
}

for (const discovered of walk('docs/systems').filter(file => /\/README\.md$/.test(file))) {
  if (!requiredDocs.includes(discovered)) requiredDocs.push(discovered);
}
requiredDocs.sort();

const failures = [];
const systemDocs = requiredDocs.filter(doc => /^docs\/systems\/.+\/README\.md$/.test(doc));
const verifiedSystemDocs = [
  'docs/systems/engine/README.md',
  'docs/systems/boot/README.md',
  'docs/systems/kernel/README.md',
  'docs/systems/runtime-capabilities/README.md',
  'docs/systems/android-host/README.md',
  'docs/systems/desktop/README.md',
  'docs/systems/shell-ui/README.md',
  'docs/systems/riftfs/README.md',
  'docs/systems/native-dispatcher/README.md',
  'docs/systems/transfers/README.md',
  'docs/systems/browser/README.md',
  'docs/systems/browser/engine/README.md',
  'docs/systems/browser/engine/android-webview/README.md',
  'docs/systems/browser/mcp-compat/README.md',
  'docs/systems/browser/ai-adapters/README.md',
  'docs/systems/mcp/README.md',
  'docs/systems/mcp/server/README.md',
  'docs/systems/mcp/tool-host/README.md',
  'docs/systems/mcp/sandbox/README.md',
  'docs/systems/mcp/relay/README.md',
  'docs/systems/mcp/project-exporter/README.md',
  'docs/systems/workspace/README.md',
  'docs/systems/workspace/live/README.md',
  'docs/systems/dev-lab/README.md',
  'docs/systems/riftllm-bridge/README.md',
  'docs/systems/apps/README.md',
  'docs/systems/riftrt/README.md',
  'docs/systems/riftrt/engines/README.md',
  'docs/systems/riftrt/engines/native-webview/README.md',
  'docs/systems/riftrt/engines/worker-js/README.md',
  'docs/systems/riftrt/engines/wasm-base64/README.md',
  'docs/systems/riftrt/engines/native-arm64/README.md',
  'docs/systems/riftrt/engines/rift-vm/README.md',
  'docs/systems/riftpp-core/README.md',
  'docs/systems/shell/README.md',
  'docs/systems/riftcli/README.md',
  'docs/systems/git/README.md',
  'docs/systems/riftrepo/README.md',
  'docs/systems/riftvault/README.md',
  'docs/systems/riftbuild/README.md',
  'docs/systems/riftmemory/README.md',
  'docs/systems/files-app/README.md',
  'docs/systems/settings/README.md',
  'docs/systems/preview/README.md',
  'docs/systems/diagnostics/README.md',
  'docs/systems/debugger/README.md',
  'docs/systems/secrets/README.md',
  'docs/systems/relay-service/README.md',
  'docs/systems/build-validation/README.md',
  'docs/systems/vortex-agent/README.md',
  'docs/systems/vortex-bridge/README.md',
  'docs/systems/chat-handoff/README.md',
];
const repairHeadings = [/^## Source ownership$/m, /^## Failure signatures$/m, /^## Fix map$/m, /^## Validation\b/m];

const VERIFIED_SYSTEM_MARKER =
  /\*\*VERIFIED AGAINST CURRENT (?:SOURCE|GRADLE\/SOURCE) — (\d{4}-\d{2}-\d{2})\.\*\*/;
const ROOT_ENGINE_MARKER =
  /\*\*ENGINE DOCUMENTATION VERIFIED AGAINST CURRENT SOURCE — (\d{4}-\d{2}-\d{2})\.\*\*/;
const PROJECT_STATUS_MARKER =
  /\*\*CURRENT ENGINE STATUS VERIFIED AGAINST SOURCE — (\d{4}-\d{2}-\d{2})\.\*\*/;

function hasValidVerificationMarker(text, pattern) {
  const match = text.match(pattern);
  if (!match) return false;
  const isoDate = match[1];
  const parsed = new Date(`${isoDate}T00:00:00.000Z`);
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== isoDate) return false;
  return parsed.getTime() <= Date.now();
}

for (const doc of requiredDocs) {
  const full = path.join(root, doc);
  if (!fs.existsSync(full)) {
    failures.push(`missing required README: ${doc}`);
    continue;
  }
  const text = fs.readFileSync(full, 'utf8');
  if (text.trim().length < 200) failures.push(`README is too small to be useful: ${doc}`);
  if (systemDocs.includes(doc)) {
    for (const heading of repairHeadings) {
      if (!heading.test(text)) failures.push(`system README is missing maintenance section ${heading}: ${doc}`);
    }
  }
}

for (const doc of verifiedSystemDocs) {
  const text = fs.readFileSync(path.join(root, doc), 'utf8');
  if (!/^## Verification status$/m.test(text) || !hasValidVerificationMarker(text, VERIFIED_SYSTEM_MARKER)) {
    failures.push(`verified engine document lacks a valid source-verification marker: ${doc}`);
  }
}
for (const doc of systemDocs) {
  if (verifiedSystemDocs.includes(doc)) continue;
  const text = fs.readFileSync(path.join(root, doc), 'utf8');
  if (/\*\*VERIFIED AGAINST CURRENT/.test(text)) {
    failures.push(`unaudited subsystem document claims VERIFIED status: ${doc}`);
  }
}

const rootReadme = fs.readFileSync(path.join(root, 'README.md'), 'utf8');
if (!hasValidVerificationMarker(rootReadme, ROOT_ENGINE_MARKER)) {
  failures.push('root README does not carry a valid engine verification status');
}
const projectStatus = fs.readFileSync(path.join(root, 'docs/PROJECT_STATUS.md'), 'utf8');
if (!hasValidVerificationMarker(projectStatus, PROJECT_STATUS_MARKER)) {
  failures.push('PROJECT_STATUS does not carry a valid engine verification status');
}
const docsTrust = fs.readFileSync(path.join(root, 'docs/README.md'), 'utf8');
if (!docsTrust.includes('Documentation is **UNVERIFIED by default**')) {
  failures.push('docs/README.md does not enforce unverified-by-default trust policy');
}
const ownershipLedger = fs.readFileSync(path.join(root, 'docs/SOURCE_OWNERSHIP.md'), 'utf8');
if (!ownershipLedger.includes('Ownership does **not** imply that a source is packaged, live, verified, trusted or device-proven.')) {
  failures.push('SOURCE_OWNERSHIP does not separate documentation ownership from runtime activation/trust');
}
const srcIndex = fs.readFileSync(path.join(root, 'src/README.md'), 'utf8');
if (!srcIndex.includes('This directory is **not** the active Android shell source tree.') || !srcIndex.includes('riftpp-core.js') || !srcIndex.includes('riftvm.js')) {
  failures.push('src/README.md does not document the current packaged-vs-retained JavaScript boundary');
}

for (const index of localIndexes) {
  const full = path.join(root, index);
  if (!fs.existsSync(full)) failures.push(`missing local source-area README: ${index}`);
  else if (fs.readFileSync(full, 'utf8').trim().length < 120) failures.push(`local source-area README is too small: ${index}`);
}

const owned = [...new Set(ownedSources)].sort();
const ledgerSources = new Set([...ownership.matchAll(/^\| `([^`]+)` \|/gm)].map(match => match[1]));
for (const source of owned) {
  if (!ledgerSources.has(source)) failures.push(`maintained source has no ownership entry: ${source}`);
}
for (const source of ledgerSources) {
  if (!fs.existsSync(path.join(root, source))) failures.push(`ownership ledger points to missing source: ${source}`);
}

const ownerDocs = new Set(
  [...ownership.matchAll(/`((?:docs\/[^`]+|README)\.md)`/g)].map(match => match[1])
);
for (const owner of ownerDocs) {
  if (!fs.existsSync(path.join(root, owner))) failures.push(`ownership ledger points to missing owner document: ${owner}`);
}

const markdownFiles = [...new Set([
  'README.md', 'ROADMAP.md', 'LOCAL_MCP_MODE.md', ...requiredDocs, ...localIndexes,
  ...walk('docs').filter(file => file.endsWith('.md')),
])];
for (const file of markdownFiles) {
  const full = path.join(root, file);
  if (!fs.existsSync(full)) continue;
  const text = fs.readFileSync(full, 'utf8');
  for (const match of text.matchAll(/\[[^\]]*\]\(([^)]+)\)/g)) {
    const href = match[1].trim();
    if (!href || href.startsWith('#') || /^(?:https?:|mailto:|tel:)/i.test(href)) continue;
    const target = href.split('#')[0];
    if (!target) continue;
    const resolved = path.resolve(path.dirname(full), target);
    if (!fs.existsSync(resolved)) failures.push(`broken relative Markdown link in ${file}: ${href}`);
  }
}

const activeDocsText = ['README.md', 'ROADMAP.md', 'LOCAL_MCP_MODE.md', ...walk('docs').filter(file => file.endsWith('.md'))]
  .filter(file => fs.existsSync(path.join(root, file)))
  .map(file => fs.readFileSync(path.join(root, file), 'utf8'))
  .join('\n');
for (const retired of ['rift-tools-v2', 'RIFT_TOOL_RESULT_V2', '<rift_call>', 'RIFT_MCP_RESULT_V1']) {
  if (activeDocsText.includes(retired)) failures.push(`active documentation still references retired chat protocol marker: ${retired}`);
}

const n2MemoryRoadmap = fs.readFileSync(
  path.join(root, 'docs/systems/riftmemory/N2_FEDERATED_MEMORY_ROADMAP.md'),
  'utf8'
);
for (const required of [
  '**N2.0 CONTRACT/CORRECTNESS BASELINE PROMOTED on installed source `f6bf12b9fb452cc128e9290fd73599297ba134f2`, Builder run 341; N2 RUNTIME INACTIVE — 2026-09-24.**',
  'ONE MEMORY KERNEL. MANY SPECIALIZED COGNITIVE ENGINES.',
  'MemoryStore API',
  'SQLite reference backend',
  'RiftStore experimental backend',
  '### N2.12 — Final correctness, adversarial hardening and promotion',
  'comparative/performance benchmarking is deferred until the entire RiftCLI stack is 100% complete and live',
  '**N3 MUST NOT START until N2.12 is promoted.**',
]) {
  if (!n2MemoryRoadmap.includes(required)) {
    failures.push(`N2 federated memory roadmap lost required frozen contract: ${required}`);
  }
}

const n2Contract = JSON.parse(fs.readFileSync(path.join(root, 'riftmemory/n2-contract-v1.json'), 'utf8'));
const n2PhaseAuthority = JSON.parse(fs.readFileSync(path.join(root, 'riftmemory/n2-phase-authority.json'), 'utf8'));
if (n2Contract.schema !== 'rift-memory-n2-contract-v1' ||
    n2Contract.phase !== 'N2.0' ||
    n2Contract.status !== 'source-implemented' ||
    n2Contract.runtimeActivation !== false ||
    n2Contract.globalBenchmarkRule !== 'NO_PERFORMANCE_OR_COMPARATIVE_BENCHMARKS_UNTIL_FULL_RIFTCLI_COMPLETE_AND_LIVE') {
  failures.push('N2.0 machine contract lifecycle/benchmark rule drifted');
}
if (n2Contract.terminologySha256 !== 'bf18c5f2db272c5a66723879ab021a3ffc42483c4cd4fc6597d10bd96fc949d8' ||
    n2Contract.memoryStoreSha256 !== '68c46266e926e546e95543eb7a2092576c91499f810bcc5d483671d706823d16' ||
    n2Contract.correctnessCorpusSha256 !== '4ec6cd3e133de7c9a4f5f4d91c48b0873df02fb79a7b19727c96256b6e4687c4' ||
    n2Contract.correctnessThresholdsSha256 !== 'a6307031907834e0bb4060d24209a98706df4d0bfe39a7936cc5750fb06f6801') {
  failures.push('N2.0 machine contract frozen component hash drifted');
}
const n2ExpectedMacroPhases = [
  ['N2.1', 'N2.2'],
  ['N2.3', 'N2.4'],
  ['N2.5', 'N2.6'],
  ['N2.7', 'N2.8'],
  ['N2.9'],
  ['N2.10', 'N2.11'],
];
if (n2PhaseAuthority.schema !== 'rift-memory-n2-phase-authority-v1' ||
    n2PhaseAuthority.programStatus !== 'N2.0-N2.12 PROMOTED / N2-M1 + N2-M2 + N2-M3 + N2-M4 + N2-M5 + N2-M6 PROMOTED; N2 COMPLETE' ||
    n2PhaseAuthority.runtimeStatus !== 'N2 CANONICAL MEMORY RUNTIME INACTIVE; N2-M1 + N2-M2 + N2-M3 + N2-M4 + N2-M5 + N2-M6 PROMOTED DIAGNOSTICS; N2.12 FINAL CORRECTNESS GATE PROMOTED' ||
    n2PhaseAuthority.n18Prerequisite !== 'SATISFIED' ||
    n2PhaseAuthority.contractLifecycleSemantics !== 'immutable-N2.0-freeze-snapshot; current lifecycle is authoritative only in this phase-authority file' ||
    n2PhaseAuthority.phases?.length !== 13 ||
    n2PhaseAuthority.phases?.[0]?.status !== 'promoted' ||
    n2PhaseAuthority.phases?.[0]?.promotedSourceSha !== 'f6bf12b9fb452cc128e9290fd73599297ba134f2' ||
    String(n2PhaseAuthority.phases?.[0]?.builderRunNumber) !== '341' ||
    n2PhaseAuthority.phases?.slice(1, 3).some(row => row.status !== 'promoted' || row.promotedSourceSha !== '694c1e31a6c3f4bd4317edd121208be894be2586' || String(row.builderRunNumber) !== '346') ||
    n2PhaseAuthority.phases?.slice(3, 5).some(row => row.status !== 'promoted' || row.promotedSourceSha !== '18f1156075e08cb94573a9392031ac64552313f2' || String(row.builderRunNumber) !== '350') ||
    n2PhaseAuthority.phases?.slice(5, 7).some(row => row.status !== 'promoted' || row.promotedSourceSha !== '62382a94f50dd6052e1754c1496da2a0f794c0af' || String(row.builderRunNumber) !== '355') ||
    n2PhaseAuthority.phases?.slice(7, 9).some(row => row.status !== 'promoted' || row.promotedSourceSha !== 'd39960832a701311461058670b5b93597ae612c9' || String(row.builderRunNumber) !== '368') ||
    n2PhaseAuthority.phases?.[9]?.status !== 'promoted' ||
    n2PhaseAuthority.phases?.[9]?.promotedSourceSha !== 'd650e57dff09a878f02edfef7e175ed02d42f750' ||
    String(n2PhaseAuthority.phases?.[9]?.builderRunNumber) !== '370' ||
    n2PhaseAuthority.phases?.slice(10, 12).some(row => row.status !== 'promoted' || row.promotedSourceSha !== '921d32ff295921be8783ca4ce8395ba5ee029553' || String(row.builderRunNumber) !== '376') ||
    n2PhaseAuthority.phases?.[12]?.status !== 'promoted' ||
    n2PhaseAuthority.phases?.[12]?.promotedSourceSha !== '9e75b0f76fd61ba80ca4c241a41532253bdb4c47' ||
    String(n2PhaseAuthority.phases?.[12]?.builderRunNumber) !== '378' ||
    n2PhaseAuthority.macroImplementationPlan?.[0]?.status !== 'promoted' ||
    n2PhaseAuthority.macroImplementationPlan?.[1]?.status !== 'promoted' ||
    n2PhaseAuthority.macroImplementationPlan?.[2]?.status !== 'promoted' ||
    n2PhaseAuthority.macroImplementationPlan?.[3]?.status !== 'promoted' ||
    n2PhaseAuthority.macroImplementationPlan?.[4]?.status !== 'promoted' ||
    n2PhaseAuthority.macroImplementationPlan?.[5]?.status !== 'promoted' ||
    JSON.stringify(n2PhaseAuthority.macroImplementationPlan?.map(row => row.phases)) !== JSON.stringify(n2ExpectedMacroPhases) ||
    !String(n2PhaseAuthority.macroPlanRule || '').includes('execution groupings only') ||
    !String(n2PhaseAuthority.macroPlanRule || '').includes('N2.12 remains a separate final correctness/adversarial promotion gate')) {
  failures.push('N2 phase authority lifecycle/macro-plan drifted');
}

const n3Roadmap = fs.readFileSync(
  path.join(root, 'docs/systems/riftcli/N3_ARCHITECTURE_IMPACT_ROADMAP.md'),
  'utf8'
);
for (const required of [
  'N3.0 CONTRACT/BASELINE SOURCE-IMPLEMENTED',
  'N3 is analysis only',
  'N4 may consume N3 only after N3.6 promotion',
  '### N3.0 — Contract and baseline freeze',
  '### N3.6 — Adversarial / restart / final promotion gate',
  'Comparative/performance benchmarks remain deferred until the entire RiftCLI stack is complete and live',
]) {
  if (!n3Roadmap.includes(required)) {
    failures.push(`N3 architecture/impact roadmap lost required frozen contract: ${required}`);
  }
}

const n3Contract = JSON.parse(fs.readFileSync(path.join(root, 'riftarchitecture/n3-contract-v1.json'), 'utf8'));
const n3PhaseAuthority = JSON.parse(fs.readFileSync(path.join(root, 'riftarchitecture/n3-phase-authority.json'), 'utf8'));
if (n3Contract.schema !== 'rift-architecture-n3-contract-v1' ||
    n3Contract.phase !== 'N3.0' ||
    n3Contract.status !== 'source-implemented' ||
    n3Contract.promotion !== 'pending-builder-install-proof' ||
    n3Contract.runtimeAuthority !== false ||
    n3Contract.plannerAuthority !== false ||
    n3Contract.mutationAuthority !== false ||
    n3Contract.memoryRuntimeActivation !== false ||
    n3Contract.installedBaseline?.riftosSourceSha !== '3e9cfb5b7514e3a82d4d739fa0f3d92aba1ba23b' ||
    String(n3Contract.installedBaseline?.builderRunNumber) !== '389' ||
    n3Contract.globalBenchmarkRule !== 'NO_PERFORMANCE_OR_COMPARATIVE_BENCHMARKS_UNTIL_FULL_RIFTCLI_COMPLETE_AND_LIVE') {
  failures.push('N3.0 machine contract lifecycle/authority/baseline/benchmark rule drifted');
}
if (n3Contract.bounds?.maxProjects !== 32 ||
    n3Contract.bounds?.maxChangedFiles !== 4096 ||
    n3Contract.bounds?.maxChangedSymbols !== 1000 ||
    n3Contract.bounds?.maxReferences !== 800 ||
    n3Contract.bounds?.maxDependencies !== 800 ||
    n3Contract.bounds?.maxDependents !== 800 ||
    n3Contract.bounds?.maxTests !== 300 ||
    n3Contract.bounds?.maxDocumentation !== 300 ||
    n3Contract.bounds?.maxPropagationSeeds !== 64 ||
    n3Contract.bounds?.maxPropagationClosureNodes !== 1024 ||
    n3Contract.bounds?.maxPropagationReverseEdges !== 4096 ||
    n3Contract.bounds?.maxPropagationDepth !== 16) {
  failures.push('N3.0 machine contract frozen bounds drifted');
}
if (n3Contract.zeroTolerance?.guessedOwnerWhenAmbiguous !== 0 ||
    n3Contract.zeroTolerance?.missedDirectDependencyImpact !== 0 ||
    n3Contract.zeroTolerance?.crossProjectContamination !== 0 ||
    n3Contract.zeroTolerance?.architectureInvariantBypass !== 0 ||
    n3Contract.zeroTolerance?.securityCapabilityBoundaryOmission !== 0 ||
    n3Contract.zeroTolerance?.staleEvidenceFalseClean !== 0 ||
    n3Contract.zeroTolerance?.memoryAuthorityEscalation !== 0 ||
    n3Contract.zeroTolerance?.plannerOrMutationAuthority !== 0 ||
    n3Contract.zeroTolerance?.silentBoundTruncation !== 0) {
  failures.push('N3.0 zero-tolerance correctness rules drifted');
}
if (n3PhaseAuthority.schema !== 'rift-architecture-n3-phase-authority-v1' ||
    !String(n3PhaseAuthority.programStatus || '').includes('N3.0 SOURCE IMPLEMENTED') ||
    !String(n3PhaseAuthority.programStatus || '').includes('N3.1-N3.6 BLOCKED') ||
    !String(n3PhaseAuthority.runtimeStatus || '').includes('N3 ANALYSIS AUTHORITY INACTIVE') ||
    !String(n3PhaseAuthority.runtimeStatus || '').includes('N2 CANONICAL MEMORY RUNTIME REMAINS INACTIVE') ||
    n3PhaseAuthority.prerequisites?.N3MachineAuthorityPrelude !== 'SATISFIED' ||
    n3PhaseAuthority.phases?.length !== 7 ||
    n3PhaseAuthority.phases?.[0]?.status !== 'source-implemented' ||
    n3PhaseAuthority.phases?.slice(1).some(row => row.status !== 'blocked-until-N3.0-promoted') ||
    JSON.stringify(n3PhaseAuthority.macroImplementationPlan?.map(row => row.phases)) !== JSON.stringify([
      ['N3.1', 'N3.2'],
      ['N3.3', 'N3.4'],
      ['N3.5'],
    ]) ||
    !String(n3PhaseAuthority.macroPlanRule || '').includes('N3.6 remains a separate final adversarial/restart promotion gate')) {
  failures.push('N3 phase authority lifecycle/macro-plan drifted');
}

const rootRoadmap = fs.readFileSync(path.join(root, 'ROADMAP.md'), 'utf8');
if (!rootRoadmap.includes('N2 Federated Rift Memory Kernel — N2.0-N2.12 PROMOTED / N2-M1 + N2-M2 + N2-M3 + N2-M4 + N2-M5 + N2-M6 PROMOTED; N2 COMPLETE; CANONICAL MEMORY RUNTIME INACTIVE') ||
    !rootRoadmap.includes('N3 Architecture/impact engine — N3.0 CONTRACT/BASELINE SOURCE IMPLEMENTED; BUILDER + INSTALL + LIVE PROOF PENDING; N3.1-N3.6 BLOCKED.')) {
  failures.push('ROADMAP.md no longer carries the promoted N2 lifecycle and active N3.0 contract gate');
}

const docsIndex = fs.readFileSync(path.join(root, 'docs/README.md'), 'utf8');
if (!docsIndex.includes('systems/riftmemory/N2_FEDERATED_MEMORY_ROADMAP.md')) {
  failures.push('docs/README.md does not index the N2 federated memory roadmap');
}
if (!docsIndex.includes('systems/riftcli/N3_ARCHITECTURE_IMPACT_ROADMAP.md')) {
  failures.push('docs/README.md does not index the N3 architecture/impact roadmap');
}
for (const discoverable of ['../LOCAL_MCP_MODE.md', '../ROADMAP.md', 'RIFT_RAW_CHAT_PROTOCOL.md']) {
  if (!docsIndex.includes(discoverable)) failures.push(`docs/README.md does not link operational document: ${discoverable}`);
}

if (failures.length) {
  console.error('RiftOS documentation validation failed:');
  for (const failure of [...new Set(failures)]) console.error(`- ${failure}`);
  process.exit(1);
}

console.log(`RiftOS documentation OK: ${requiredDocs.length} required docs; ${systemDocs.length} repair READMEs; ${owned.length} maintained source files owned.`);
