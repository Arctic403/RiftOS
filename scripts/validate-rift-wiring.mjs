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
for (const required of [
  'android.permission.REQUEST_INSTALL_PACKAGES',
  'android:name=".RiftBuildInstallReceiver"',
  'android:exported="false"',
  'android.intent.action.PACKAGE_FIRST_LAUNCH',
  'com.riftpp.nativeproof',
]) if (!manifest.includes(required)) fail(`Android RiftBuild install contract is missing ${required}`);
const kotlinDir = 'android/app/src/main/java/com/riftos/app';
const kotlinFiles = walk(kotlinDir).filter(file => file.endsWith('.kt'));
const manifestActivities = new Set([...manifest.matchAll(/<activity\b[^>]*\bandroid:name="\.([^"]+)"/g)].map(match => match[1]));
const kotlinTexts = new Map(kotlinFiles.map(file => [file, read(file)]));
for (const activity of manifestActivities) {
  const activityPattern = new RegExp(`\\bclass\\s+${activity}\\s*:\\s*Activity\\s*\\(`);
  const owner = kotlinFiles.find(file => activityPattern.test(kotlinTexts.get(file) || ''));
  if (!owner) fail(`AndroidManifest activity has no Kotlin class source: ${activity}`);
}
for (const file of kotlinFiles) {
  const text = kotlinTexts.get(file) || '';
  for (const match of text.matchAll(/\bclass\s+(\w+)\s*:\s*Activity\s*\(/g)) {
    if (!manifestActivities.has(match[1])) fail(`Activity source is not declared in AndroidManifest.xml: ${match[1]}`);
  }
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
if (gradle.includes('",\\n        "src/main/java/com/riftos/app/')) {
  fail('Gradle mandatory Kotlin snapshot contains a literal \\n separator instead of a real newline');
}
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
  'include("src/semnexis-bootstrap.js")',
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
const buildInstaller = read(`${kotlinDir}/RiftBuildInstaller.kt`);
if (!buildInstaller.includes('class RiftBuildInstallReceiver : BroadcastReceiver()')) fail('RiftBuild manifest receiver source is missing');
const headless = read(`${kotlinDir}/RiftHeadlessJsRuntime.kt`);
const runtime = read(`${kotlinDir}/RiftMcpRuntime.kt`);
const cliEvents = read(`${kotlinDir}/RiftCliEventBus.kt`);
const relayClient = read(`${kotlinDir}/RiftMcpRelayClient.kt`);
const relayWorker = read('relay/src/index.js');
const browserWindow = read(`${kotlinDir}/RiftBrowserWindow.kt`);
const browserHost = read(`${kotlinDir}/RiftBrowserAppHost.kt`);
const desktop = read(`${kotlinDir}/RiftNativeDesktop.kt`);
const workspaceApps = read(`${kotlinDir}/RiftNativeWorkspaceApps.kt`);
const browserBridge = read(`${kotlinDir}/RiftBrowserMcpAppBridge.kt`);
const cliHost = read(`${kotlinDir}/RiftCliHost.kt`);
const toolHost = read(`${kotlinDir}/RiftToolHost.kt`);
const toolSandbox = read(`${kotlinDir}/RiftToolSandbox.kt`);
const cliCmake = read('android/app/src/main/cpp/CMakeLists.txt');
const cliCore = read('android/app/src/main/cpp/riftcli/rift_cli_core.cpp');
const cliJni = read('android/app/src/main/cpp/riftcli/rift_cli_jni.cpp');

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
if (!main.includes('add("mcp", "Rift MCP", "⇄")')) fail('Rift MCP launcher entry is missing from MainActivity');
if (!main.includes('if (id == "mcp")') || !main.includes('startActivity(Intent(this, RiftMcpActivity::class.java))')) fail('Rift MCP launcher does not open the existing RiftMcpActivity');
if (!desktop.includes('LauncherApp("mcp", "Rift MCP", "⇄")')) fail('Rift MCP is missing from the native desktop fallback launcher');

if (!cliHost.includes('System.loadLibrary("riftcli")') || !cliHost.includes('private external fun nativeExecute')) {
  fail('RiftCLI Kotlin host is not a thin JNI loader');
}
if (!nativeShell.includes('RiftCliHost.executeShell(args, cwd)')) fail('RiftShell does not route rift-cli into the native C++ host');
if (!gradle.includes('ndkVersion = "28.2.13676358"') ||
    !gradle.includes('abiFilters += listOf("arm64-v8a", "armeabi-v7a")') ||
    !gradle.includes('path = file("src/main/cpp/CMakeLists.txt")')) fail('RiftCLI pinned dual-ABI native Gradle wiring is missing');
if (!cliCmake.includes('add_library(') || !cliCmake.includes('riftcli') || !cliCmake.includes('SHARED')) {
  fail('RiftCLI native CMake shared library contract is missing');
}
const cliN1CoreContracts = [
  ['dependency direction', 'external-driver -> MCP/RiftShell -> RiftCLI'],
  ['authority mode', 'full-riftos-when-enabled'],
  ['no direct model backend', String.raw`\"directModelBackend\":false`],
  ['no direct network client', String.raw`\"directNetworkClient\":false`],
  ['external continuation ownership', String.raw`\"driverContinuationExternalOnly\":true`],
  ['driver loop cap', 'kMaxDriverLoopSteps = 8'],
  ['request-id cap', 'kMaxDriverRequestIds = 4096'],
  ['request-id capacity enum', 'RequestReservation::Capacity'],
  ['duplicate request-id rejection', 'duplicate-request-id'],
  ['request-id capacity rejection', 'request-id-capacity'],
  ['driver replay capacity', String.raw`\"driverReplayCapacity\":`],
  ['non-evicting replay ids', String.raw`\"driverReplayEviction\":false`],
  ['process restart replay reset', String.raw`\"driverReplayReset\":\"process-restart-only\"`],
  ['job-control classification', 'isDriverJobControl'],
  ['disabled-state job-control allowance', 'if (!on && !jobControl)'],
  ['job-control reservation bypass', 'jobControl ? RequestReservation::Accepted'],
  ['push-first job execution', String.raw`\"driverToolExecution\":\"push-first-jobs-with-poll-fallback\"`],
  ['persistent relay push', String.raw`\"driverEventDelivery\":\"persistent-relay-push\"`],
  ['Batch V2 enabled', String.raw`\"batchV2\":true`],
  ['Batch V2 step cap', String.raw`\"batchV2MaxSteps\":16`],
];
for (const [name, fragment] of cliN1CoreContracts) {
  if (!cliCore.includes(fragment)) fail(`RiftCLI N1 core contract missing: ${name}`);
}
if (cliCore.includes('g_recentRequestIds.clear()')) {
  fail('RiftCLI N1 replay contract drifted: request IDs must not be cleared in-process');
}
if (!nativeShell.includes('executeCliCommand(cwd, args)') ||
    !nativeShell.includes('executeCliShellDispatch') ||
    !nativeShell.includes('executeCliToolDispatch') ||
    !nativeShell.includes('private val cliWorker = ThreadPoolExecutor(') ||
    !nativeShell.includes('rift_cli_job_list') ||
    !nativeShell.includes('rift_cli_job_poll') ||
    !nativeShell.includes('rift_cli_job_cancel') ||
    !nativeShell.includes('origin = "rift-cli-driver"') ||
    !nativeShell.includes('requestId = cliResult.optString("requestId")') ||
    !nativeShell.includes('Internal RiftCLI recursion is forbidden') ||
    nativeShell.includes('CountDownLatch') ||
    !toolHost.includes('internal fun startCliJob') ||
    !toolHost.includes('internal fun listCliJobs') ||
    !toolHost.includes('internal fun pollCliJob') ||
    !toolHost.includes('internal fun cancelCliJob') ||
    !toolHost.includes('cancelled_may_have_applied') ||
    !nativeShell.includes('cancelled_may_have_applied') ||
    !toolHost.includes('RiftCLI tool lane forbids') ||
    !toolHost.includes('internal object RiftCliExecutionGate') ||
    !toolHost.includes('ReentrantLock(true)') ||
    !toolHost.includes('outstandingJobId = AtomicReference<String?>(null)') ||
    !toolHost.includes('fun tryReserve(jobId: String)') ||
    !toolHost.includes('fun release(jobId: String)') ||
    !nativeShell.includes('RiftCliExecutionGate.tryReserve(jobId)') ||
    !toolHost.includes('RiftCliExecutionGate.tryReserve(jobId)') ||
    !nativeShell.includes('cliShellJobSnapshot(it, includeResult = false)') ||
    !toolHost.includes('cliJobSnapshot(it, includeResult = false)') ||
    !nativeShell.includes('RiftCliExecutionGate.run {') ||
    !toolSandbox.includes('RiftCliExecutionGate.run {') ||
    !toolSandbox.includes('internal fun submitCliJob(') ||
    !toolSandbox.includes('executeRequest(raw, "rift-cli")') ||
    !nativeShell.includes('"rift_cli_batch" -> startCliBatch') ||
    !nativeShell.includes('MAX_CLI_BATCH_STEPS = 16') ||
    !nativeShell.includes('origin = "rift-cli-batch"') ||
    !toolHost.includes('internal fun validateCliBatchTool') ||
    !toolHost.includes('internal fun executeCliBatchTool') ||
    !toolSandbox.includes('internal fun executeCliBatchRequest') ||
    !toolSandbox.includes('executeRequest(raw, "rift-cli-batch")')) fail('RiftCLI N1 dispatcher/provenance/Batch V2 boundary drifted');
if (!gradle.includes('RiftCliEventBus.kt') ||
    !runtime.includes('fun cliEvents(): RiftCliEventBus') ||
    !runtime.includes('RiftMcpRelayClient(') ||\n    !runtime.includes('server(context)') ||\n    !runtime.includes('cliEvents()') ||\n    !runtime.includes('debugHub()') ||
    !cliEvents.includes('SCHEMA = "rift.cli-event/1"') ||
    !cliEvents.includes('MAX_EVENTS = 256') ||
    !cliEvents.includes('stepKey = extra?.optString("stepId")') ||
    !relayClient.includes('cliEvents.addListener(cliEventListener)') ||
    !relayClient.includes('"cli.replay.request"') ||
    !relayClient.includes('"cli.ack"') ||
    !relayWorker.includes('acceptWebSocket(server, ["driver"])') ||
    !relayWorker.includes('notifications/riftcli/event') ||
    relayWorker.includes('ctx.storage')) fail('RiftCLI N1.5 persistent push wiring drifted');
if (cliJni.includes('GetStringUTFChars') || cliJni.includes('NewStringUTF') ||
    !cliJni.includes('GetStringChars') || !cliJni.includes('utf8ToUtf16')) fail('RiftCLI JNI UTF boundary drifted');

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
if (!nativeShell.includes('headlessJs.executeSemnexis(args, cwd)')) fail('Semnexis is not routed through the headless runtime');
if (!nativeShell.includes('dump-ir|emit-arm32-proof|emit-arm32-runtime')) fail('Semnexis IR/backend shell surface is missing');
if (!nativeShell.includes('headlessJs.executeQuickJs(args, cwd)')) fail('bounded qjs is not routed through the headless runtime');
if (/riftclang|RiftNativeToolchain|nativeToolchain/.test(nativeShell)) fail('retired native Semnexis compiler path returned');
if (/compatibilityFallback|RiftShellBridge/.test(nativeShell) || hasWebKitDependency(nativeShell)) fail('native RiftShell regained renderer fallback authority');
if (!runtime.includes('private var nativeShell: RiftNativeShell?') || !runtime.includes('fun shellExecutor(): RiftShellExecutor? = nativeShell')) fail('MCP does not retain process-owned native shell authority');
if (/registerShellBridge|setCompatibilityFallback|clearCompatibilityFallback/.test(runtime)) fail('MCP runtime regained shell-WebView fallback wiring');

for (const required of ['quickJs {', 'preparedVmSource()', 'preparedCoreSource()', 'preparedSemnexisSource()', 'src/riftpp-core.js', 'src/riftvm.js', 'src/semnexis-bootstrap.js']) {
  if (!headless.includes(required)) fail(`headless Rift++ runtime is missing ${required}`);
}
if (hasWebKitDependency(headless) || /ProcessBuilder|Runtime\.getRuntime|Socket\(/.test(headless)) fail('headless Rift++ runtime gained renderer/process/socket authority');
const semnexisSource = read('src/semnexis-bootstrap.js');
for (const required of ['SEMNEXIS_NATIVE_IR_V0', 'SNIRV0', 'SNIRV7', 'NATIVE_IR_BINARY_VERSION_V7', 'encodeNativeIRV2', 'decodeNativeIRV2', 'encodeNativeIRV3', 'decodeNativeIRV3', 'encodeNativeIRV4', 'decodeNativeIRV4', 'encodeNativeIRV5', 'decodeNativeIRV5', 'encodeNativeIRV6', 'decodeNativeIRV6', 'encodeNativeIRV7', 'decodeNativeIRV7', 'zext.u8.i32', 'Slice<u8>', 'slice.len', 'slice.get.u8', 'record.make', 'record.get', 'ret.record', 'phi.record', 'MAX_SEMNEXIS_FLAT_RECORD_WORDS', 'arm32LdrbReg', 'MAX_SEMNEXIS_TYPE_DEPTH', 'SEMNEXIS_ARM32_ELF_PROOF_V0', 'SEMNEXIS_ARM32_RUNTIME_ELF_V0', 'verifyNativeIREffectsAndCapabilities', 'verifyArm32RuntimeElfStructureV0', 'source IR is required for canonical verification', 'MAX_SEMNEXIS_SOURCE_CHARS', 'MAX_SEMNEXIS_EXPRESSION_DEPTH', 'MAX_SEMNEXIS_CFG_BLOCKS', 'MAX_ARM32_RUNTIME_ELF_BYTES', 'advisory-correlation-id-v0', 'linear-scan-r4-r7-v0', 'cfg-spill-v0', 'verifyNativeIRControlFlow', 'phi.i32', 'br.cmp.lt', 'loop_backedge', 'buildArm32RuntimeDivHelperV0', "checkedArithmetic:['add','sub','mul','div']", 'constantEvaluated:false', 'runtimeLowered:true', 'generated-artifact-not-executed-from-riftfs']) {
  if (!semnexisSource.includes(required)) fail(`Semnexis compiler/backend contract is missing ${required}`);
}
if (!headless.includes('/documents/builds/Semnexis/semx-arm32-proof.elf') ||
    !headless.includes('/documents/builds/Semnexis/semx-arm32-runtime.elf') ||
    !headless.includes('__rift_write_semnexis_binary')) fail('Semnexis fixed ARM32 artifact writer is missing');
for (const required of ['MAX_SEMNEXIS_SOURCE_BYTES', 'MAX_SEMNEXIS_OUTPUT_CHARS', 'semnexis-bootstrap-self-test/17', 'sliceIrBinaryFormat', 'arm32SliceBytes', 'recordIrBinaryFormat', 'arm32RecordBytes', 'projectionIrBinaryFormat', 'arm32ProjectionBytes', 'recordConditionalIrBinaryFormat', 'arm32RecordConditionalBytes', 'recordLoopIrBinaryFormat', 'arm32RecordLoopBytes', 'decimalIrBinaryFormat', 'arm32DecimalBytes', 'decimalZextCount', 'hardeningDerivedEffects', 'hardeningCanonicalMachineVerify', 'hardeningExpressionBudget']) {
  if (!headless.includes(required)) fail(`Semnexis hardening host contract is missing ${required}`);
}
const packageJson = read('package.json');
if (!packageJson.includes('test-semnexis-arm32-exec.mjs')) fail('Semnexis independent ARM32 machine execution regression is not wired into npm check');
const qjsStart = headless.indexOf('fun executeQuickJs(args: List<String>, cwd: String): CommandResult');
const qjsEnd = headless.indexOf('fun executeDeveloperTool(args: List<String>): CommandResult', qjsStart);
if (qjsStart < 0 || qjsEnd <= qjsStart) fail('bounded qjs implementation is missing');
const qjsSlice = headless.slice(qjsStart, qjsEnd);
for (const required of ['QJS_EVALUATION_TIMEOUT_MS', 'MAX_QJS_SOURCE_BYTES', 'MAX_QJS_TOTAL_BYTES', 'MAX_QJS_FILES', 'MAX_QJS_OUTPUT_BYTES', 'function("__rift_qjs_read_text")', '.put("riftFsWrite", false)', '.put("processAuthority", false)', '.put("networkAuthority", false)', '.put("androidAuthority", false)']) {
  if (!qjsSlice.includes(required)) fail(`bounded qjs contract is missing ${required}`);
}
if (/__rift_write_text|ProcessBuilder|Runtime\.getRuntime|Socket\(|startActivity|nativeGit/.test(qjsSlice)) fail('bounded qjs gained write/process/socket/Android/Git authority');

if (!browserWindow.includes('WebChromeClient.FileChooserParams') || !browserWindow.includes('onActivityResult(')) fail('RiftBrowser does not own its file chooser lifecycle');
if (!browserHost.includes('appOrigin(app.id)') || !browserHost.includes('https://app-$token.riftos.local') || !browserHost.includes('WebViewCompat.addWebMessageListener')) fail('installed RiftBrowser app host origin/capability bridge is incomplete');
if (browserBridge.includes('RiftShellBridge') || browserBridge.includes('rift_shell_result')) fail('browser compatibility bridge regained RiftShell execution authority');

for (const required of ['Intent.ACTION_OPEN_DOCUMENT_TREE', 'takePersistableUriPermission', 'DocumentFile.fromTreeUri', 'ANDROID_FILES_ROOT', 'MAX_FILES_ROWS', 'writeDocumentBytes', 'Android provider write verification failed', 'Editor target is not a file']) {
  if (!workspaceApps.includes(required)) fail(`native Files external-storage contract is missing ${required}`);
}
if (hasWebKitDependency(workspaceApps)) fail('native workspace apps gained a WebView dependency');

const riftpp = read('src/riftpp-core.js');
const vm = read('src/riftvm.js');
if (!riftpp.includes("RIFTPP_CORE_VERSION='0.10.0-bootstrap'") || !riftpp.includes('MAX_VEC_CAPACITY=256') || !riftpp.includes('MAX_BUFFER_CAPACITY=100000') || !riftpp.includes('MAX_SOURCE_CODE_UNITS=4*1024*1024') || !riftpp.includes('MAX_STRING_BUILDER_UNITS=4*1024*1024') || !riftpp.includes("BUILTIN_VALUE_TYPES=new Set(['SourceText','TextCursor'])") || !riftpp.includes("['Vec','Buffer','Slice','StringBuilder','Option','Result']")) fail('Rift++ Core 0.10.0 text/byte/storage contract regressed');
if (!riftpp.includes("SUPPORTED_PRIMITIVES=new Set(['unit','bool','u8','u32','s32','f64','string'])") || !riftpp.includes("BYTE_BUILTINS=new Set(['u8_to_u32','u8_from_u32'])") || !riftpp.includes("emit({op:'u8_to_u32'})") || !riftpp.includes("emit({op:'u8_from_u32'})") || !vm.includes("u8:[0n,255n]") || !vm.includes("case'u8_to_u32'") || !vm.includes("case'u8_from_u32'")) fail('Rift++ native u8 byte substrate regressed');
if (!vm.includes('maxVecCapacity:256') || !vm.includes('maxBufferCapacity:100000') || !vm.includes('maxSourceTextCodeUnits:4*1024*1024') || !vm.includes('maxStringBuilderUnits:4*1024*1024') || !vm.includes('function canonicalUtf8ByteLength(text)') || !vm.includes("'source_text','source_code_unit_len','source_utf8_byte_len','source_cursor','source_slice','source_to_string'") || !vm.includes("'parse_u32','parse_s32','parse_f64','format_u32','format_s32','format_f64'") || !vm.includes("RIFT_VM_ABI='riftvm-1'")) fail('RiftVM ABI/text/numeric contract regressed');
if (!headless.includes("schema:'riftpp-shell-self-test/3'") || !headless.includes('let max: u8 = 255') || !headless.includes('Buffer<u8, 8>') || !headless.includes('u8_from_u32(256)') || !headless.includes("includes('u8 overflow')")) fail('Rift++ bounded u8 self-test proof regressed');
if (!headless.includes("schema: 'riftpp-text-model-benchmark-v2'") || !headless.includes("alternateCandidate: 'utf8-bytes-plus-code-unit-index'") || !headless.includes('utf8IndexBuildMs') || !headless.includes('lexerLike') || !headless.includes('codeUnitRandomAccess')) fail('Rift++ text-model benchmark v2 contract regressed');
if (!headless.includes('const input = Array.from(view, value => value & 255);') || !headless.includes('const raw = __rift_sha256(input);') || !headless.includes('return out.buffer;')) fail('Rift++ SHA-256 typed-array bridge regressed');
if (!headless.includes('private fun canonicalUtf8Bytes(value: String): ByteArray') || !headless.includes('const out = new Uint8Array(raw.length);') || !headless.includes('out[i] = raw[i] & 255;') || headless.includes('.toByteArray(Charsets.UTF_8)')) fail('Rift++ headless UTF-8 canonicalization contract regressed');

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
