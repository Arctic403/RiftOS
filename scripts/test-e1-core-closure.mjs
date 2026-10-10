import fs from 'node:fs';
import path from 'node:path';

// E1-A is an ownership audit, NOT an independently compiled or activated Core.
// A class with the same name in the APK and an external DEX is not replaceable:
// Android's parent classloader resolves the APK-owned class first.
const root = process.cwd();
const dir = path.join(root, 'android/app/src/main/java/com/riftos/app');
const sourceFiles = fs.readdirSync(dir).filter(name => name.endsWith('.kt')).sort();
const contents = new Map(sourceFiles.map(name => [name.slice(0, -3), fs.readFileSync(path.join(dir, name), 'utf8')]));
const requiredRoots = [
  'RiftCoreRuntime', 'RiftCoreAppLifecycle', 'RiftCoreAppSessions',
  'RiftCoreAppSurfaces', 'RiftCoreInputFocus', 'RiftCoreAppExecutor',
  'RiftRappManager'
];
const hostOwned = [
  'RiftCoreApplication', 'RiftBootstrapHost', 'RiftCoreSurfaceIpcProvider',
  'RiftHostComponentAbiV1', 'RiftCoreCandidateSwitch', 'RiftCoreRecoveryDiagnostics',
  'RiftComponentReleaseLedger', 'RiftProtectedComponentManifest',
  'RiftProtectedDexVerifier', 'RiftProtectedComponentInstaller',
  'RiftProtectedRevisionRecovery', 'RiftCoreRecoverySupervisorService',
  'RiftShellCandidateSwitch', 'RiftShellComponentAbiV1',
  'RiftCoreShellRecovery', 'RiftCoreAdminConsent',
  'RiftCoreAdminRegistryProof', 'RiftCoreAdminRollbackProof',
  'RiftCoreSystemCapabilities', 'RiftShellActivity', 'RiftShellCoreClient',
  'RiftMcpRuntime', 'RiftMcpRelayClient'
];
const fail = message => { throw new Error('E1-A Core ownership: ' + message); };
for (const name of [...requiredRoots, ...hostOwned]) {
  if (!contents.has(name)) fail('source missing: ' + name + '.kt');
}
if (requiredRoots.some(name => hostOwned.includes(name))) fail('host/Core roots overlap');

const abi = contents.get('RiftHostComponentAbiV1');
const bootstrap = contents.get('RiftBootstrapHost');
const provider = contents.get('RiftCoreSurfaceIpcProvider');
const gradle = fs.readFileSync(path.join(root, 'android/app/build.gradle.kts'), 'utf8');
if (!abi.includes('interface RiftCoreComponentV1') ||
    !abi.includes('fun core(): RiftCoreComponentV1 = selected') ||
    !abi.includes('fun initializeAtBoot(application: android.app.Application)') ||
    !abi.includes('selectedKind = "embedded"') ||
    !abi.includes('externalShellEnabled", false') ||
    !bootstrap.includes('if (id != "probe")') ||
    !bootstrap.includes('RiftHostCoreComponents.initializeAtBoot(application)') ||
    !provider.includes('RiftHostCoreComponents.core().snapshot(ctx, id)')) {
  fail('embedded-only E0 selector or protected Core IPC is no longer intact');
}
if (!gradle.includes('"src/main/java/com/riftos/app/RiftCoreRuntime.kt"')) {
  fail('embedded production fallback removed before E1 candidate proof');
}

// Resolve references to actual Kotlin source files, not string/comment names.
// This is a conservative textual approximation, NOT Kotlin compiler closure.
const stripped = text => text
  .replace(/\/\*[\s\S]*?\*\//g, ' ')
  .replace(/(^|\n)\s*\/\/[^\n]*/g, '$1')
  .replace(/"(?:\\.|[^"\\])*"/g, '""');
const symbolOwner = new Map();
for (const [file, source] of contents) {
  symbolOwner.set(file, file);
  for (const match of source.matchAll(/^(?:(?:public|internal|private|protected|open|abstract|sealed|data|enum|annotation|expect|actual)\s+)*(?:class|interface|object)\s+([A-Za-z_]\w*)\b/gm)) {
    if (symbolOwner.has(match[1]) && symbolOwner.get(match[1]) !== file) {
      fail('ambiguous top-level source owner: ' + match[1]);
    }
    symbolOwner.set(match[1], file);
  }
}
const graph = new Map([...contents].map(([file, source]) => {
  const referencedFiles = new Set();
  for (const match of stripped(source).matchAll(/\bRift[A-Za-z_]\w*\b/g)) {
    const referenced = symbolOwner.get(match[0]);
    if (referenced && referenced !== file) referencedFiles.add(referenced);
  }
  return [file, [...referencedFiles].sort()];
}));
const closure = new Set(requiredRoots);
const edges = [];
const queue = [...requiredRoots];
while (queue.length) {
  const source = queue.shift();
  for (const dependency of graph.get(source) ?? []) {
    edges.push([source, dependency]);
    if (!closure.has(dependency)) { closure.add(dependency); queue.push(dependency); }
  }
}
const blocking = edges.filter(([, dependency]) => hostOwned.includes(dependency));
const androidBound = [...closure].filter(name => /\b(?:import android\.|import androidx\.)/.test(contents.get(name)));
const report = {
  schema: 'riftos.e1.core-closure-audit/1',
  gate: 'E1-A_SOURCE_INVENTORY_ONLY',
  baseline: {signedBuild: 695, sourceSha: 'c92d8b72319aa9c2915f94cf687f0a3031794795'},
  hostAbi: 1,
  productionCore: 'embedded',
  externalCoreEnabled: false,
  candidateCompiled: false,
  candidateLoadProven: false,
  roots: requiredRoots,
  closureFileCount: closure.size,
  hostBoundaryEdges: blocking.slice(0, 50),
  hostBoundaryEdgeCount: blocking.length,
  androidImportFiles: androidBound.slice(0, 40),
  candidateReady: false
};
if (closure.size < requiredRoots.length) fail('source closure incomplete');
console.log(JSON.stringify(report, null, 2));
