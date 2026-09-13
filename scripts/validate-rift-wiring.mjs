import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const root = process.cwd();
const failures = [];
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');
const exists = relative => fs.existsSync(path.join(root, relative));

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

function requireFile(relative, reason) {
  if (!exists(relative)) failures.push(`${reason}: ${relative}`);
}

function validateHtmlAssets(relative) {
  const text = read(relative);
  for (const match of text.matchAll(/(?:src|href)=["']([^"']+)["']/g)) {
    const href = match[1].trim();
    if (!href || href.startsWith('#') || /^(?:https?:|data:|mailto:|tel:)/i.test(href)) continue;
    const clean = href.split(/[?#]/)[0];
    const resolved = path.relative(root, path.resolve(path.dirname(path.join(root, relative)), clean)).replaceAll('\\', '/');
    requireFile(resolved, `HTML asset reference from ${relative} is missing`);
  }
}

function validateCssAssets(relative) {
  const text = read(relative);
  for (const match of text.matchAll(/url\(([^)]+)\)/g)) {
    const raw = match[1].trim().replace(/^['"]|['"]$/g, '');
    if (!raw || raw.startsWith('#') || /^(?:https?:|data:)/i.test(raw)) continue;
    const clean = raw.split(/[?#]/)[0];
    const resolved = path.relative(root, path.resolve(path.dirname(path.join(root, relative)), clean)).replaceAll('\\', '/');
    requireFile(resolved, `CSS asset reference from ${relative} is missing`);
  }
}

function routeMethods(text, startMarker, endMarker) {
  const start = text.indexOf(startMarker);
  if (start < 0) return new Set();
  const end = endMarker ? text.indexOf(endMarker, start) : text.length;
  const block = text.slice(start, end < 0 ? text.length : end);
  const methods = new Set();
  for (const line of block.split('\n')) {
    const arrow = line.indexOf('->');
    if (arrow < 0) continue;
    const left = line.slice(0, arrow);
    for (const match of left.matchAll(/"([^"]+)"/g)) methods.add(match[1]);
  }
  return methods;
}

function extractLiteralNativeCalls(text) {
  const methods = new Set();
  for (const match of text.matchAll(/(?:core|this)\.native\.call\(\s*(["'])([^"']+)\1/g)) methods.add(match[2]);
  return methods;
}

// Every active JavaScript/MJS file must at least parse.
const syntaxFiles = [
  ...walk('src').filter(file => file.endsWith('.js')),
  ...walk('android/app/src/main/assets').filter(file => file.endsWith('.js')),
  ...walk('workspace-live').filter(file => file.endsWith('.js')),
  ...walk('relay').filter(file => file.endsWith('.js')),
  ...walk('scripts').filter(file => file.endsWith('.mjs')),
];
for (const file of [...new Set(syntaxFiles)].sort()) {
  const result = spawnSync(process.execPath, ['--check', path.join(root, file)], { encoding: 'utf8' });
  if (result.status !== 0) failures.push(`JavaScript syntax check failed: ${file}: ${(result.stderr || result.stdout || '').trim()}`);
}

// Android shell module graph: index -> riftandroid-entry -> every src/*.js module.
validateHtmlAssets('index.html');
validateHtmlAssets('workspace-live/index.html');
for (const css of ['styles.css', 'src/riftdesktop-android.css', 'workspace-live/style.css']) validateCssAssets(css);
const indexHtml = read('index.html');
if (!indexHtml.includes('./src/riftandroid-entry.js')) failures.push('index.html does not load src/riftandroid-entry.js');
const entry = read('src/riftandroid-entry.js');
const importedModules = new Set([...entry.matchAll(/import\(\s*["']\.\/([^"']+)["']\s*\)/g)].map(match => match[1]));
const srcModules = fs.readdirSync(path.join(root, 'src')).filter(name => name.endsWith('.js'));
for (const module of srcModules) {
  if (module === 'riftandroid-entry.js') continue;
  if (!importedModules.has(module)) failures.push(`active src module is not imported by riftandroid-entry.js: src/${module}`);
}
for (const module of importedModules) requireFile(`src/${module}`, 'riftandroid-entry.js imports missing module');

// Android workspace override order is deliberate; JSON RPC must resolve the active adapter at call time.
const webIndex = entry.indexOf('import("./riftworkspace-web.js")');
const adapterIndex = entry.indexOf('import("./riftworkspace-android-adapter.js")');
const liveIndex = entry.indexOf('import("./riftworkspace-live-host.js")');
if (!(webIndex >= 0 && adapterIndex > webIndex && liveIndex > adapterIndex)) failures.push('workspace module load order must be web -> android-adapter -> live-host');
const workspaceWeb = read('src/riftworkspace-web.js');
const workspaceAdapter = read('src/riftworkspace-android-adapter.js');
if (!workspaceWeb.includes('const activeWorkspace=window.RiftWorkspace||workspace;')) failures.push('RiftWorkspaceJSON does not resolve the active Android adapter dynamically');
if (!workspaceAdapter.includes('window.RiftWorkspace=workspace;')) failures.push('Android workspace adapter does not replace window.RiftWorkspace');
const workspaceRecordsHost = read('src/riftworkspace-live-host.js');
if (!workspaceRecordsHost.includes('./workspace-live/index.html')) failures.push('Workspace Records host does not reference workspace-live/index.html');
if (!workspaceRecordsHost.includes('riftworkspace-live-v2') || /case \"write\"|case \"remove\"|case \"move\"|case \"copy\"|case \"mkdir\"/.test(workspaceRecordsHost)) failures.push('Workspace Records host regained a direct workspace mutation RPC');

// Manifest component wiring: every Activity declaration has source and every Activity source is declared.
const manifest = read('android/app/src/main/AndroidManifest.xml');
const manifestActivities = new Set([...manifest.matchAll(/<activity\b[^>]*\bandroid:name="\.([^"]+)"/g)].map(match => match[1]).filter(Boolean));
for (const activity of manifestActivities) requireFile(`android/app/src/main/java/com/riftos/app/${activity}.kt`, 'AndroidManifest activity has no Kotlin source');
const kotlinDir = 'android/app/src/main/java/com/riftos/app';
const kotlinFiles = walk(kotlinDir).filter(file => file.endsWith('.kt'));
for (const file of kotlinFiles) {
  const match = read(file).match(/\bclass\s+(\w+)\s*:\s*Activity\s*\(/);
  if (match && !manifestActivities.has(match[1])) failures.push(`Activity source is not declared in AndroidManifest.xml: ${match[1]}`);
}

// Kotlin reachability graph from manifest Activities; catches dead service/helper islands.
const kotlinTypes = new Map(kotlinFiles.map(file => [path.basename(file, '.kt'), { file, text: read(file) }]));
const reachable = new Set([...manifestActivities]);
const queue = [...reachable];
while (queue.length) {
  const current = queue.shift();
  const node = kotlinTypes.get(current);
  if (!node) continue;
  for (const [name] of kotlinTypes) {
    if (reachable.has(name) || name === current) continue;
    if (new RegExp(`\\b${name}\\b`).test(node.text)) {
      reachable.add(name);
      queue.push(name);
    }
  }
}
for (const [name, node] of kotlinTypes) {
  if (!reachable.has(name)) failures.push(`Kotlin source is unreachable from an Android manifest Activity: ${node.file}`);
}

// Gradle must package the complete shell/runtime asset families.
const gradle = read('android/app/build.gradle.kts');
for (const required of ['include("index.html")', 'include("styles.css")', 'include("src/**")', 'include("workspace-live/**")']) {
  if (!gradle.includes(required)) failures.push(`Android asset sync is missing ${required}`);
}

// Browser-injected assets: only the live registry exists, and native injection must load both assets.
const bridge = read('android/app/src/main/java/com/riftos/app/RiftBrowserMcpAppBridge.kt');
for (const asset of ['riftbrowser-mcp-app.js', 'adapters/ai-adapter-registry.js']) {
  requireFile(`android/app/src/main/assets/${asset}`, 'browser injection asset is missing');
  if (!bridge.includes(asset)) failures.push(`RiftBrowserMcpAppBridge does not load active asset: ${asset}`);
}
const adapterModules = walk('android/app/src/main/assets/adapters').filter(file => file.endsWith('.js'));
if (adapterModules.length !== 1 || !adapterModules[0].endsWith('/ai-adapter-registry.js')) failures.push(`AI adapter directory contains unreferenced JS modules: ${adapterModules.join(', ')}`);

// Relay config must point at a real worker and a real Durable Object class.
const wrangler = read('relay/wrangler.jsonc');
const relayMain = wrangler.match(/"main"\s*:\s*"([^"]+)"/)?.[1];
if (!relayMain) failures.push('relay/wrangler.jsonc has no main entrypoint');
else requireFile(`relay/${relayMain}`, 'relay main entrypoint is missing');
const relayClass = wrangler.match(/"class_name"\s*:\s*"([^"]+)"/)?.[1];
if (relayClass && relayMain && exists(`relay/${relayMain}`) && !read(`relay/${relayMain}`).includes(`export class ${relayClass}`)) failures.push(`relay Durable Object class is not exported: ${relayClass}`);

// Root package script file references must exist.
const pkg = JSON.parse(read('package.json'));
for (const [name, command] of Object.entries(pkg.scripts || {})) {
  for (const match of String(command).matchAll(/node(?:\s+--check)?\s+([^\s&]+)/g)) {
    const target = match[1];
    if (!target.startsWith('-')) requireFile(target, `package script ${name} references missing file`);
  }
}

// Native route sources are also used by the trust-boundary checks below.
const dispatcher = read('android/app/src/main/java/com/riftos/app/RiftNativeDispatcher.kt');
const mainActivity = read('android/app/src/main/java/com/riftos/app/MainActivity.kt');

// RiftShell MCP execution must stay in the trusted shell WebView, never the guest browser asset.
const browserAdapter = read('android/app/src/main/assets/riftbrowser-mcp-app.js');
const shellSource = read('src/riftos.js');
const shellBridgeSource = read('android/app/src/main/java/com/riftos/app/RiftShellBridge.kt');
const browserMcpBridgeSource = read('android/app/src/main/java/com/riftos/app/RiftBrowserMcpAppBridge.kt');
if (/RiftShellMcp|RiftShellMcpNative|RiftMcpShellNativeResult|rift_shell_result/.test(browserAdapter)) failures.push('guest browser MCP asset contains RiftShell execution/result authority');
if (!shellSource.includes('window.RiftShellMcpNative = Object.freeze') || !shellSource.includes("method:'mcp.shell.result'")) failures.push('trusted shell does not own the RiftShell MCP shim/result path');
if (!mainActivity.includes('shellBridge = RiftShellBridge(webView)') || !mainActivity.includes('method == "mcp.shell.result"')) failures.push('MainActivity does not own the shell bridge/result route on the trusted shell WebView');
if (browserMcpBridgeSource.includes('RiftShellBridge(') || browserMcpBridgeSource.includes('rift_shell_result')) failures.push('browser MCP compatibility bridge still owns shell execution/result routing');
if (!shellBridgeSource.includes('private val shellWebView: WebView') || !shellBridgeSource.includes('SHELL_TIMEOUT_MS')) failures.push('RiftShellBridge is not bound to the trusted shell WebView with timeout protection');

// Native method callers and handlers must agree in both directions.
const supported = new Set([
  ...routeMethods(dispatcher, 'fun handleAsync(raw: String)', 'fun completeDirectoryPick'),
  ...routeMethods(dispatcher, 'private fun dispatch(method: String', 'private fun normalizeSegments'),
  ...routeMethods(mainActivity, 'private fun handleKernelRequest(raw: String)', 'private fun runKernelCommand'),
]);
const nativeCallers = new Set();
for (const file of srcModules.map(name => `src/${name}`)) {
  for (const method of extractLiteralNativeCalls(read(file))) nativeCallers.add(method);
}
if (/\bnativeCall\s*\(|previousCore\.native\.call\(/.test(workspaceWeb)) failures.push('common RiftWorkspace layer still contains a legacy direct-native workspace path');
const shellUi = read('src/riftos.js');
if (shellUi.includes('core.native.call(`browser.window.${method}`')) {
  for (const match of shellUi.matchAll(/\bnative\(\s*["']([^"']+)["']/g)) nativeCallers.add(`browser.window.${match[1]}`);
}
for (const method of nativeCallers) {
  if (!supported.has(method)) failures.push(`JavaScript native call has no Android handler: ${method}`);
}
const intentionalQueryHandlers = new Set(['browser.window.state', 'workspace.watch.state']);
for (const method of supported) {
  if (!nativeCallers.has(method) && !intentionalQueryHandlers.has(method)) failures.push(`Android native handler has no RiftOS caller/documented query role: ${method}`);
}
if (supported.has('browser.open')) failures.push('obsolete dispatcher browser.open alias is still registered');

// Installed .rift iframe bridge methods must match the host dispatcher.
const appsSource = read('src/riftapps.js');
const bridgeSlice = appsSource.slice(appsSource.indexOf('function injectBridge('), appsSource.indexOf('function materializeHtml('));
const appBridgeCalls = new Set([...bridgeSlice.matchAll(/\bcall\(\s*["']([^"']+)["']/g)].map(match => match[1]));
const appHostSlice = appsSource.slice(appsSource.indexOf('async function handleAppMessage('), appsSource.indexOf('window.addEventListener("message",handleAppMessage)'));
const appHostMethods = new Set([...appHostSlice.matchAll(/msg\.method===\s*["']([^"']+)["']/g)].map(match => match[1]));
for (const method of appBridgeCalls) if (!appHostMethods.has(method)) failures.push(`.rift app bridge method has no host implementation: ${method}`);
for (const method of appHostMethods) if (method !== 'app.ready' && !appBridgeCalls.has(method)) failures.push(`.rift app host branch is not exposed by the injected bridge: ${method}`);

// RiftRT iframe/Worker RPC methods must agree with hostCall; outer-only window/lifecycle methods are handled separately.
const riftrt = read('src/riftrt.js');
const hostStart = riftrt.indexOf('async function hostCall(');
const hostEnd = riftrt.indexOf('function materializeAsset(', hostStart);
const hostSlice = riftrt.slice(hostStart, hostEnd);
const riftrtHostMethods = new Set([...hostSlice.matchAll(/method===\s*["']([^"']+)["']/g)].map(match => match[1]));
const iframeStart = riftrt.indexOf('function iframeHtml(');
const iframeEnd = riftrt.indexOf('async function launchIframe(', iframeStart);
const iframeSlice = riftrt.slice(iframeStart, iframeEnd);
const iframeCalls = new Set([...iframeSlice.matchAll(/\bcall\(\s*["']([^"']+)["']/g)].map(match => match[1]));
const workerStart = riftrt.indexOf('const workerBootstrap=');
const workerEnd = riftrt.indexOf('function fitCanvas(', workerStart);
const workerSlice = riftrt.slice(workerStart, workerEnd);
const workerCalls = new Set([...workerSlice.matchAll(/\brpc\(\s*["']([^"']+)["']/g)].map(match => match[1]));
const outerRiftRtMethods = new Set(['app.close', 'window.title']);
for (const method of iframeCalls) if (!outerRiftRtMethods.has(method) && !riftrtHostMethods.has(method)) failures.push(`RiftRT iframe API has no hostCall implementation: ${method}`);
for (const method of workerCalls) if (!riftrtHostMethods.has(method)) failures.push(`RiftRT Worker API has no hostCall implementation: ${method}`);
for (const method of riftrtHostMethods) if (!iframeCalls.has(method) || !workerCalls.has(method)) failures.push(`RiftRT hostCall is not exposed consistently by iframe and Worker APIs: ${method}`);
for (const method of outerRiftRtMethods) if (!iframeCalls.has(method) || !riftrt.includes(`msg.method==='${method}'`)) failures.push(`RiftRT iframe lifecycle/window method is not wired end-to-end: ${method}`);

// Retired Rift AI task/session/journal architecture must stay absent.
const mcpServerSource = read('android/app/src/main/java/com/riftos/app/RiftMcpServer.kt');
const toolHostSource = read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
if (exists(`${kotlinDir}/RiftAiJournal.kt`)) failures.push('retired RiftAiJournal.kt returned');
if (/RiftMcpAppControl|rift\/ai\/event|riftos\/aiSessionId|pendingApprovedSubmission|queueAiTask|submitAiTask/.test(browserAdapter + mcpServerSource + toolHostSource)) failures.push('retired Rift AI task/session/event architecture returned');
if (!browserAdapter.includes('async function stageComposerMessage') || !browserAdapter.includes('writeComposer(composer')) failures.push('browser compatibility result staging path is missing');

// Every deliberate Rift global/bridge surface must be documented.
const publicSurfaceDoc = read('docs/PUBLIC_SURFACES.md');
const publicSurfaceNames = new Set();
for (const file of srcModules.map(name => `src/${name}`)) {
  const text = read(file);
  for (const match of text.matchAll(/(?:window|globalThis)\.(Rift[A-Za-z0-9_]*)\s*=/g)) publicSurfaceNames.add(match[1]);
  for (const match of text.matchAll(/Object\.defineProperty\(globalThis,\s*["'](Rift[A-Za-z0-9_]*)["']/g)) publicSurfaceNames.add(match[1]);
}
for (const file of walk('android/app/src/main/assets').filter(file => file.endsWith('.js'))) {
  const text = read(file);
  for (const match of text.matchAll(/window\.(Rift[A-Za-z0-9_]*)\s*=/g)) publicSurfaceNames.add(match[1]);
}
for (const nativeSurface of ['RiftAndroid', 'RiftMcpNative']) publicSurfaceNames.add(nativeSurface);
for (const surface of [...publicSurfaceNames].sort()) {
  if (!publicSurfaceDoc.includes(`\`${surface}\``)) failures.push(`Rift public/global surface is undocumented: ${surface}`);
}
if (/RiftShellMcp|RiftShellMcpNative/.test(browserAdapter)) failures.push('trusted-shell RiftShell global leaked into guest browser asset');

// Android RiftDesktop is permanent; the retired desktop/mobile mode switch must not return.
const desktopSource = read('src/riftdesktop-android.js');
const desktopHostSource = read('src/riftdesktop-window-host.js');
if (/riftDesktopToggle|desktopPreference|cycleDesktopPreference|maximizeForMobile|rift\.desktop\.mode/.test(desktopSource + desktopHostSource)) failures.push('retired Android desktop/mobile mode-switch code is present');
if (!desktopSource.includes("get mode(){return 'desktop';}")) failures.push('RiftDesktop public mode is not fixed to desktop');

// Known removed runtime islands must stay removed.
for (const stale of ['RiftTransferJob.kt', 'RiftTransferManager.kt', 'RiftTransferRegistry.kt', 'RiftTransferService.kt']) {
  if (exists(`${kotlinDir}/${stale}`)) failures.push(`retired transfer runtime file returned: ${stale}`);
}

if (failures.length) {
  console.error('RiftOS wiring validation failed:');
  for (const failure of [...new Set(failures)]) console.error(`- ${failure}`);
  process.exit(1);
}

console.log(`RiftOS wiring OK: ${srcModules.length} src modules; ${kotlinFiles.length} Kotlin files reachable; ${nativeCallers.size} native call routes; ${syntaxFiles.length} JS/MJS files parsed.`);
