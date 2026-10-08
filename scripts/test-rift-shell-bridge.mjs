import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = file => fs.readFileSync(file, 'utf8');
const stripCodeComments = text => text
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .split('\n').filter(line => !line.trimStart().startsWith('//')).join('\n');
const hasWebKitDependency = text => /(?:^|\n)\s*import\s+(?:android|androidx)\.webkit\.|(?:android|androidx)\.webkit\./m.test(stripCodeComments(text));
const k = 'android/app/src/main/java/com/riftos/app/';
const shell = read(k + 'RiftNativeShell.kt');
const shellExecutor = read(k + 'RiftShellExecutor.kt');
const toolHost = read(k + 'RiftToolHost.kt');
const managedJvmTool = read(k + 'RiftManagedJvmToolService.kt');
const riftppEditorClient = read(k + 'RiftppEditorBridgeClient.kt');
const runtime = read(k + 'RiftMcpRuntime.kt');
const headless = read(k + 'RiftHeadlessJsRuntime.kt');
const vmBridge = read('android/app/src/main/java/com/codynex/editorapp/CodynexRuntimeBridge.kt');
const main = read(k + 'MainActivity.kt');
const browserBridge = read(k + 'RiftBrowserMcpAppBridge.kt');
const gradle = read('android/app/build.gradle.kts');

assert.equal(fs.existsSync(k + 'RiftShellBridge.kt'), false, 'trusted shell WebView bridge must stay removed');
assert.equal(fs.existsSync(k + 'RiftSystemDump.kt'), false, 'obsolete shell renderer dump must stay removed');
assert.match(shell, /class RiftNativeShell\(context: Context\) : RiftShellExecutor/);
for (const method of ['fun submit(', 'fun jobStatus(', 'fun jobResult(', 'fun jobCancel(', 'fun jobList(']) {
  assert.ok(shellExecutor.includes(method), `RiftShellExecutor persistent job contract is missing ${method}`);
}
assert.ok(shell.includes('SYNC_SHELL_TIMEOUT_MS = 10 * 60 * 1000L'), 'native RiftShell synchronous ceiling must allow bounded long local commands');
assert.ok(shell.includes('MAX_SHELL_JOBS = 16'), 'native RiftShell persistent job capacity must stay bounded');
assert.ok(shell.includes('SHELL_JOB_RETENTION_MS = 10 * 60 * 1000L'), 'native RiftShell job retention must stay bounded');
assert.ok(shell.includes('MAX_SHELL_JOB_RETAINED_RESULT_BYTES = 2 * 1024 * 1024'), 'native RiftShell retained results must stay bounded');
assert.ok(shell.includes('rift.shell-job/1') && shell.includes('rift.shell-jobs/1'), 'native RiftShell job schemas must remain explicit');
assert.ok(toolHost.includes('shouldSubmitShellJob(command)') && toolHost.includes('"compiler-run"') && toolHost.includes('"jvm-dex"') && !toolHost.includes('"kotlin-compile"'), 'MCP shell auto mode must route only surviving generic RiftBuild work into persistent jobs');
assert.ok(toolHost.includes('"auto", "exec", "submit", "status", "result", "cancel", "list"'), 'MCP shell job-control action family must remain exposed');
assert.ok(managedJvmTool.includes('RUN_TIMEOUT_SECONDS = 10 * 60L'), 'managed JVM compiler window must not regress to 60 seconds');
assert.ok(managedJvmTool.includes('putString("status", "cancelled")') && managedJvmTool.includes('Process.killProcess(remotePid)'), 'managed JVM cancellation must terminate the isolated compiler process');
assert.doesNotMatch(shell, /Rift\+\+ legacy editor development bridge|\[LEGACY EDITOR BINDER BRIDGE\]/);
assert.doesNotMatch(riftppEditorClient, /bridge-enabled legacy editor/);
assert.equal((shell.match(/private fun tokenize\(/g) || []).length, 1, 'native shell must expose exactly one tokenizer helper');
assert.equal((shell.match(/private fun resolveFile\(/g) || []).length, 1, 'native shell must expose exactly one confined file resolver');
assert.match(shell, /private fun normalizeDisplay\(raw: String\): String =\s*RiftVolumePaths\.normalizeDisplay\(raw\)/);
assert.match(shell, /private fun resolveFile\(displayPath: String\): File \{[\s\S]*RiftVolumePaths\.resolveRelative\(display\)[\s\S]*Path escaped RiftFS/);
assert.match(shell, /private fun joinDisplay\(base: String, child: String\): String/);
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
assert.match(headless, /const val POLYFILLS = \"\"\"/);
assert.match(headless, /const val RIFTPP_COMMAND_ENTRY = \"\"\"/);
assert.doesNotMatch(headless, /private const val (?:POLYFILLS|RIFTPP_COMMAND_ENTRY)/);
assert.match(headless, /src\/riftpp-core\.js/);
assert.match(headless, /src\/riftvm\.js/);
assert.doesNotMatch(headless, /CODYNEX_C0_|compileCodynexC0|c0_reference\.js|codynex-c0-ref/);
assert.match(vmBridge, /System\.loadLibrary\("codynex_editor_vm"\)/);
assert.equal(hasWebKitDependency(headless), false);
assert.doesNotMatch(headless, /ProcessBuilder|Runtime\.getRuntime|Socket\(/);
assert.doesNotMatch(browserBridge, /RiftShellBridge|rift_shell_result|RiftShellMcpNative/);
assert.match(gradle, /validateRiftBrowserWebViewOwnership/);
assert.match(gradle, /include\("src\/riftpp-core\.js"\)/);
assert.match(gradle, /include\("src\/riftvm\.js"\)/);
assert.doesNotMatch(gradle, /include\("index\.html"\)|include\("styles\.css"\)|include\("workspace-live\/\*\*"\)/);

console.log('ok - RiftShell is process-owned Android-native with no renderer fallback');
console.log('ok - Rift++ Core runs through bounded headless QuickJS');
console.log('ok - retired RiftOS Codynex C0 compiler/host authority remains absent');
console.log('ok - Codynex native preview bridge remains explicit');
console.log('ok - Chromium authority remains RiftBrowser-only');
