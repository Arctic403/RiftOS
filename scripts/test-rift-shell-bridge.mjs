import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = file => fs.readFileSync(file, 'utf8');
const stripCodeComments = text => text
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .split('\n').filter(line => !line.trimStart().startsWith('//')).join('\n');
const hasWebKitDependency = text => /(?:^|\n)\s*import\s+(?:android|androidx)\.webkit\.|(?:android|androidx)\.webkit\./m.test(stripCodeComments(text));
const k = 'android/app/src/main/java/com/riftos/app/';
const shell = read(k + 'RiftNativeShell.kt');
const runtime = read(k + 'RiftMcpRuntime.kt');
const headless = read(k + 'RiftHeadlessJsRuntime.kt');
const main = read(k + 'MainActivity.kt');
const browserBridge = read(k + 'RiftBrowserMcpAppBridge.kt');
const gradle = read('android/app/build.gradle.kts');

assert.equal(fs.existsSync(k + 'RiftShellBridge.kt'), false, 'trusted shell WebView bridge must stay removed');
assert.equal(fs.existsSync(k + 'RiftSystemDump.kt'), false, 'obsolete shell renderer dump must stay removed');
assert.match(shell, /class RiftNativeShell\(context: Context\) : RiftShellExecutor/);
assert.match(shell, /\.put\("webViewRequired", false\)/);
assert.match(shell, /headlessJs\.executeRiftpp\(args, cwd\)/);
assert.doesNotMatch(shell, /compatibilityFallback|RiftShellBridge/);
assert.equal(hasWebKitDependency(shell), false);
assert.match(runtime, /private var nativeShell: RiftNativeShell\?/);
assert.match(runtime, /fun shellExecutor\(\): RiftShellExecutor\? = nativeShell/);
assert.doesNotMatch(runtime, /registerShellBridge|setCompatibilityFallback|clearCompatibilityFallback/);
assert.equal(hasWebKitDependency(main), false);
assert.doesNotMatch(main, /RiftShellBridge|addJavascriptInterface/);
assert.match(headless, /quickJs \{/);
assert.match(headless, /src\/riftpp-core\.js/);
assert.match(headless, /src\/riftvm\.js/);
assert.equal(hasWebKitDependency(headless), false);
assert.doesNotMatch(headless, /ProcessBuilder|Runtime\.getRuntime|Socket\(/);
assert.doesNotMatch(browserBridge, /RiftShellBridge|rift_shell_result|RiftShellMcpNative/);
assert.match(gradle, /validateRiftBrowserWebViewOwnership/);
assert.match(gradle, /include\("src\/riftpp-core\.js"\)/);
assert.match(gradle, /include\("src\/riftvm\.js"\)/);
assert.doesNotMatch(gradle, /include\("index\.html"\)|include\("styles\.css"\)|include\("workspace-live\/\*\*"\)/);

console.log('ok - RiftShell is process-owned Android-native with no renderer fallback');
console.log('ok - Rift++ Core runs through bounded headless QuickJS');
console.log('ok - Chromium authority remains RiftBrowser-only');
