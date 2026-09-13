import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const ownershipPath = path.join(root, 'docs/SOURCE_OWNERSHIP.md');
const ownership = fs.readFileSync(ownershipPath, 'utf8');

const requiredDocs = [
  'docs/README.md',
  'docs/PUBLIC_SURFACES.md',
  'docs/systems/README.md',
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
  'docs/systems/apps/README.md',
  'docs/systems/riftrt/README.md',
  'docs/systems/riftrt/engines/README.md',
  'docs/systems/riftrt/engines/iframe/README.md',
  'docs/systems/riftrt/engines/worker-js/README.md',
  'docs/systems/riftrt/engines/wasm-base64/README.md',
  'docs/systems/riftrt/engines/native-arm64/README.md',
  'docs/systems/runtime-capabilities/README.md',
  'docs/systems/shell/README.md',
  'docs/systems/git/README.md',
  'docs/systems/files-app/README.md',
  'docs/systems/settings/README.md',
  'docs/systems/preview/README.md',
  'docs/systems/diagnostics/README.md',
  'docs/systems/secrets/README.md',
  'docs/systems/relay-service/README.md',
  'docs/systems/build-validation/README.md',
];

const localIndexes = [
  'README.md', 'src/README.md', 'android/README.md', 'android/app/README.md',
  'android/app/src/main/java/com/riftos/app/README.md',
  'android/app/src/main/assets/README.md', 'android/app/src/main/assets/adapters/README.md',
  'android/app/src/main/res/README.md', 'apps/README.md', 'scripts/README.md',
  'workspace-live/README.md', 'relay/README.md',
];

const activeSources = [
  'index.html', 'styles.css', 'package.json',
  'android/build.gradle.kts', 'android/settings.gradle.kts', 'android/gradle.properties',
  'android/app/build.gradle.kts', 'android/app/src/main/AndroidManifest.xml',
  'android/app/src/main/res/values/styles.xml', 'android/riftos-debug.keystore.b64',
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

const failures = [];
const systemDocs = requiredDocs.filter(doc => /^docs\/systems\/.+\/README\.md$/.test(doc));
const repairHeadings = [/^## Source ownership$/m, /^## Failure signatures$/m, /^## Fix map$/m, /^## Validation\b/m];

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

for (const index of localIndexes) {
  const full = path.join(root, index);
  if (!fs.existsSync(full)) failures.push(`missing local source-area README: ${index}`);
  else if (fs.readFileSync(full, 'utf8').trim().length < 120) failures.push(`local source-area README is too small: ${index}`);
}

const active = [...new Set(activeSources)].sort();
const ledgerSources = new Set([...ownership.matchAll(/^\| `([^`]+)` \|/gm)].map(match => match[1]));
for (const source of active) {
  if (!ledgerSources.has(source)) failures.push(`active source has no ownership entry: ${source}`);
}
for (const source of ledgerSources) {
  if (!fs.existsSync(path.join(root, source))) failures.push(`ownership ledger points to missing source: ${source}`);
}

const ownerDocs = new Set([...ownership.matchAll(/`(docs\/systems\/[^`]+\/README\.md)`/g)].map(match => match[1]));
for (const owner of ownerDocs) {
  if (!fs.existsSync(path.join(root, owner))) failures.push(`ownership ledger points to missing owner README: ${owner}`);
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

const docsIndex = fs.readFileSync(path.join(root, 'docs/README.md'), 'utf8');
for (const discoverable of ['../LOCAL_MCP_MODE.md', '../ROADMAP.md', 'RIFT_RAW_CHAT_PROTOCOL.md']) {
  if (!docsIndex.includes(discoverable)) failures.push(`docs/README.md does not link operational document: ${discoverable}`);
}

if (failures.length) {
  console.error('RiftOS documentation validation failed:');
  for (const failure of [...new Set(failures)]) console.error(`- ${failure}`);
  process.exit(1);
}

console.log(`RiftOS documentation OK: ${requiredDocs.length} required docs; ${systemDocs.length} repair READMEs; ${active.length} active source files owned.`);
