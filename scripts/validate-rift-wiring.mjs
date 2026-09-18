import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const root = process.cwd();
const failures = [];
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');
const exists = relative => fs.existsSync(path.join(root, relative));
const fail = message => failures.push(message);
const stripCodeComments = text => text
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .split('\n').filter(line => !line.trimStart().startsWith('//')).join('\n');
const hasWebKitDependency = text => /(?:^|\n)\s*import\s+(?:android|androidx)\.webkit\.|(?:android|androidx)\.webkit\./m.test(stripCodeComments(text));

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
function requireFile(relative, reason = 'required file is missing') {
  if (!exists(relative)) fail(`${reason}: ${relative}`);
}

const syntaxFiles = [
  ...walk('src').filter(file => file.endsWith('.js')),
  ...walk('android/app/src/main/assets').filter(file => file.endsWith('.js')),
  ...walk('workspace-live').filter(file => file.endsWith('.js')),
  ...walk('relay').filter(file => file.endsWith('.js')),
  ...walk('scripts').filter(file => file.endsWith('.mjs')),
];
for (const file of [...new Set(syntaxFiles)].sort()) {
  const result = spawnSync(process.execPath, ['--check', path.join(root, file)], { encoding: 'utf8' });
  if (result.status !== 0) fail(`JavaScript syntax check failed: ${file}: ${(result.stderr || result.stdout || '').trim()}`);
}

const manifest = read('android/app/src/main/AndroidManifest.xml');
const kotlinDir = 'android/app/src/main/java/com/riftos/app';
const kotlinFiles = walk(kotlinDir).filter(file => file.endsWith('.kt'));
const manifestActivities = new Set([...manifest.matchAll(/<activity\b[^>]*\bandroid:name="\.([^"]+)"/g)].map(match => match[1]));
for (const activity of manifestActivities) requireFile(`${kotlinDir}/${activity}.kt`, 'AndroidManifest activity has no Kotlin source');
for (const file of kotlinFiles) {
  const match = read(file).match(/\bclass\s+(\w+)\s*:\s*Activity\s*\(/);
  if (match && !manifestActivities.has(match[1])) fail(`Activity source is not declared in AndroidManifest.xml: ${match[1]}`);
}

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
  if (!reachable.has(name)) fail(`Kotlin source is unreachable from an Android manifest Activity: ${node.file}`);
}

const rootGradle = read('android/build.gradle.kts');
const gradle = read('android/app/build.gradle.kts');
const preBuildBlock = gradle.match(/tasks\.named\("preBuild"\)\.configure\s*\{([\s\S]*?)\n\}/)?.[1] || '';
const gradleRequiredKotlin = [...gradle.matchAll(/"(src\/main\/java\/com\/riftos\/app\/[A-Za-z0-9_]+\.kt)"/g)].map(match => `android/app/${match[1]}`);
const actualKotlin = [...kotlinFiles].sort();
const declaredKotlin = [...new Set(gradleRequiredKotlin)].sort();
if (JSON.stringify(declaredKotlin) !== JSON.stringify(actualKotlin)) {
  const missingFromGradle = actualKotlin.filter(file => !declaredKotlin.includes(file));
  const staleInGradle = declaredKotlin.filter(file => !actualKotlin.includes(file));
  fail(`Gradle mandatory Kotlin snapshot is not exact. Missing: ${missingFromGradle.join(', ') || 'none'}; stale: ${staleInGradle.join(', ') || 'none'}`);
}
if (!rootGradle.includes('org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10')) fail('Android Kotlin compiler toolchain is not pinned to 2.4.10');
for (const required of [
  'include("src/riftpp-core.js")',
  'include("src/riftvm.js")',
  'validateRiftBrowserWebViewOwnership',
  'RiftOS Android source snapshot is not exact',
  'Actual WebKit dependencies/WebView XML are allowed only in',
  'RiftBrowser WebKit owner set drifted',
  'getByName("release") { isMinifyEnabled = false }',
  'compileSdk = 36',
  'minSdk = 26',
  'targetSdk = 36',
  'JavaVersion.VERSION_17',
  'io.github.dokar3:quickjs-kt:1.0.14',
  'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0',
]) if (!gradle.includes(required)) fail(`Android native/headless Gradle contract is missing ${required}`);
for (const retired of ['include("index.html")', 'include("styles.css")', 'include("src/**")', 'include("workspace-live/**")']) {
  if (gradle.includes(retired)) fail(`retired trusted-shell asset packaging returned: ${retired}`);
}
for (const dependency of ['verifyRiftOsAndroidSources', 'validateRiftBrowserWebViewOwnership', 'syncRiftOsWebAssets']) {
  if (!preBuildBlock.includes(`dependsOn(${dependency})`)) fail(`Gradle preBuild is missing dependency ${dependency}`);
}

const main = read(`${kotlinDir}/MainActivity.kt`);
const nativeShell = read(`${kotlinDir}/RiftNativeShell.kt`);
const headless = read(`${kotlinDir}/RiftHeadlessJsRuntime.kt`);
const runtime = read(`${kotlinDir}/RiftMcpRuntime.kt`);
const browserWindow = read(`${kotlinDir}/RiftBrowserWindow.kt`);
const browserHost = read(`${kotlinDir}/RiftBrowserAppHost.kt`);
const workspaceApps = read(`${kotlinDir}/RiftNativeWorkspaceApps.kt`);
const browserBridge = read(`${kotlinDir}/RiftBrowserMcpAppBridge.kt`);

for (const retired of [
  'RiftShellBridge.kt', 'RiftSystemDump.kt', 'AndroidWebViewBrowserEngine.kt',
  'RiftNativeAppHost.kt', 'RiftPreviewActivity.kt', 'RiftRendererCrashGuard.kt',
  'RiftNativeDispatcher.kt', 'RiftTransferManifest.kt',
]) if (exists(`${kotlinDir}/${retired}`)) fail(`retired migration source returned: ${retired}`);

if (hasWebKitDependency(main) || /RiftShellBridge|addJavascriptInterface|loadUrl\(/.test(main)) fail('MainActivity regained renderer/WebView bridge authority');
for (const required of ['RiftNativeDesktop(', 'RiftBrowserWindow(', 'RiftBrowserAppHost(', 'RiftNativeSystemApps(', 'RiftNativeWorkspaceApps(']) {
  if (!main.includes(required)) fail(`MainActivity native composition is missing ${required}`);
}
if (!main.includes('nativeWorkspaceApps.onActivityResult') || !main.includes('browserWindow.onActivityResult')) fail('MainActivity does not route browser/native Files activity results');

const allowedWebKitOwners = new Set([
  'RiftBrowserAndroidWebViewEngine.kt', 'RiftBrowserWindow.kt', 'RiftBrowserMcpAppBridge.kt',
  'RiftBrowserAppHost.kt', 'RiftBrowserPreviewActivity.kt', 'RiftBrowserRendererCrashGuard.kt',
]);
const actualWebKitOwners = new Set();
for (const file of kotlinFiles) {
  const text = read(file);
  const usesWebKit = hasWebKitDependency(text);
  if (usesWebKit) actualWebKitOwners.add(path.basename(file));
  if (usesWebKit && !allowedWebKitOwners.has(path.basename(file))) fail(`WebKit ownership escaped RiftBrowser: ${file}`);
}
for (const owner of allowedWebKitOwners) if (!actualWebKitOwners.has(owner)) fail(`RiftBrowser WebKit owner allowlist is stale: ${owner}`);

if (!nativeShell.includes('class RiftNativeShell(context: Context) : RiftShellExecutor')) fail('native RiftShell executor is missing');
if (!nativeShell.includes('.put("webViewRequired", false)')) fail('native RiftShell does not explicitly report WebView-free execution');
if (!nativeShell.includes('headlessJs.executeRiftpp(args, cwd)')) fail('Rift++ is not routed through the headless runtime');
if (/compatibilityFallback|RiftShellBridge/.test(nativeShell) || hasWebKitDependency(nativeShell)) fail('native RiftShell regained renderer fallback authority');
if (!runtime.includes('private var nativeShell: RiftNativeShell?') || !runtime.includes('fun shellExecutor(): RiftShellExecutor? = nativeShell')) fail('MCP does not retain process-owned native shell authority');
if (/registerShellBridge|setCompatibilityFallback|clearCompatibilityFallback/.test(runtime)) fail('MCP runtime regained shell-WebView fallback wiring');

for (const required of ['quickJs {', 'preparedVmSource()', 'preparedCoreSource()', 'src/riftpp-core.js', 'src/riftvm.js']) {
  if (!headless.includes(required)) fail(`headless Rift++ runtime is missing ${required}`);
}
if (hasWebKitDependency(headless) || /ProcessBuilder|Runtime\.getRuntime|Socket\(/.test(headless)) fail('headless Rift++ runtime gained renderer/process/socket authority');

if (!browserWindow.includes('WebChromeClient.FileChooserParams') || !browserWindow.includes('onActivityResult(')) fail('RiftBrowser does not own its file chooser lifecycle');
if (!browserHost.includes('appOrigin(app.id)') || !browserHost.includes('https://app-$token.riftos.local') || !browserHost.includes('WebViewCompat.addWebMessageListener')) fail('installed RiftBrowser app host origin/capability bridge is incomplete');
if (browserBridge.includes('RiftShellBridge') || browserBridge.includes('rift_shell_result')) fail('browser compatibility bridge regained RiftShell execution authority');

for (const required of ['Intent.ACTION_OPEN_DOCUMENT_TREE', 'takePersistableUriPermission', 'DocumentFile.fromTreeUri', 'ANDROID_FILES_ROOT', 'MAX_FILES_ROWS', 'writeDocumentBytes', 'Android provider write verification failed', 'Editor target is not a file']) {
  if (!workspaceApps.includes(required)) fail(`native Files external-storage contract is missing ${required}`);
}
if (hasWebKitDependency(workspaceApps)) fail('native workspace apps gained a WebView dependency');

const riftpp = read('src/riftpp-core.js');
const vm = read('src/riftvm.js');
if (!riftpp.includes("RIFTPP_CORE_VERSION='0.8.0-bootstrap'") || !riftpp.includes('MAX_VEC_CAPACITY=256') || !riftpp.includes('MAX_BUFFER_CAPACITY=100000') || !riftpp.includes("['Vec','Buffer','Slice','Option','Result']")) fail('Rift++ Core 0.8.0 / Vec-256 / Buffer-100000 / Slice contract regressed');
if (!vm.includes('maxVecCapacity:256') || !vm.includes('maxBufferCapacity:100000') || !vm.includes("'buffer_slice','slice_len','slice_get'") || !vm.includes("RIFT_VM_ABI='riftvm-1'")) fail('RiftVM ABI/storage-view contract regressed');

for (const asset of ['riftbrowser-mcp-app.js', 'adapters/ai-adapter-registry.js']) {
  requireFile(`android/app/src/main/assets/${asset}`, 'browser injection asset is missing');
  if (!browserBridge.includes(asset)) fail(`RiftBrowserMcpAppBridge does not load active asset: ${asset}`);
}

const pkg = JSON.parse(read('package.json'));
const packageCommands = Object.values(pkg.scripts || {}).join(' && ');
for (const focusedTest of walk('scripts').filter(file => /^scripts\/test-.*\.mjs$/.test(file))) {
  if (!packageCommands.includes(`node ${focusedTest}`)) fail(`focused test is not executed by package scripts: ${focusedTest}`);
}
for (const [name, command] of Object.entries(pkg.scripts || {})) {
  for (const match of String(command).matchAll(/node(?:\s+--check)?\s+([^\s&]+)/g)) {
    const target = match[1];
    if (!target.startsWith('-')) requireFile(target, `package script ${name} references missing file`);
  }
}

const wrangler = read('relay/wrangler.jsonc');
const relayMain = wrangler.match(/"main"\s*:\s*"([^"]+)"/)?.[1];
if (!relayMain) fail('relay/wrangler.jsonc has no main entrypoint');
else requireFile(`relay/${relayMain}`, 'relay main entrypoint is missing');
const relayClass = wrangler.match(/"class_name"\s*:\s*"([^"]+)"/)?.[1];
if (relayClass && relayMain && exists(`relay/${relayMain}`) && !read(`relay/${relayMain}`).includes(`export class ${relayClass}`)) fail(`relay Durable Object class is not exported: ${relayClass}`);

if (failures.length) {
  console.error('RiftOS native wiring validation failed:');
  for (const failure of [...new Set(failures)]) console.error(`- ${failure}`);
  process.exit(1);
}
console.log(`RiftOS native wiring OK: ${kotlinFiles.length} Kotlin files reachable; ${syntaxFiles.length} JS/MJS files parsed; WebKit browser-owned only.`);
