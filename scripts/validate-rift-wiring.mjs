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
]) if (!manifest.includes(required)) fail(`Android RiftBuild install contract is missing ${required}`);
// C0.2: RiftOS no longer predeclares either project editor as a package dependency.
for (const editorPackage of ["com.riftpp.editor", "com.codynex.editor"]) {
  if (manifest.includes(`<package android:name="${editorPackage}" />`)) {
    fail(`project editor package visibility resurfaced in RiftOS: ${editorPackage}`);
  }
}
for (const forbidden of [
  '<package android:name="com.riftpp.editor.nativev1" />',
  '<package android:name="com.riftpp.editor.adapterr1" />',
  '<package android:name="com.riftpp.nativeproof" />',
]) if (manifest.includes(forbidden)) {
  fail('Android RiftBuild install contract must not pin Rift++ proof package visibility: ' + forbidden);
}
const kotlinDir = 'android/app/src/main/java/com/riftos/app';
const kotlinFiles = walk(kotlinDir).filter(file => file.endsWith('.kt'));
const manifestComponents = [...manifest.matchAll(
  /<(application|activity|service|receiver|provider)\b[^>]*\bandroid:name="\.([^"]+)"/g
)].map(match => ({ kind: match[1], name: match[2] }));
const manifestComponentNames = new Set(manifestComponents.map(component => component.name));
const manifestActivities = new Set(
  manifestComponents
    .filter(component => component.kind === 'activity')
    .map(component => component.name)
);
const manifestApplications = manifestComponents.filter(component => component.kind === 'application');
if (manifestApplications.length !== 1) {
  fail('AndroidManifest must name exactly one Core Application bootstrap');
}
const kotlinTexts = new Map(kotlinFiles.map(file => [file, read(file)]));
for (const component of manifestApplications) {
  const applicationPattern = new RegExp(`\\bclass\\s+${component.name}\\s*:\\s*Application\\s*\\(`);
  if (!kotlinFiles.some(file => applicationPattern.test(kotlinTexts.get(file) || ''))) {
    fail(`AndroidManifest application is not backed by an Application subclass: ${component.name}`);
  }
}
for (const component of manifestComponents) {
  const declarationPattern = new RegExp(`\\b(?:class|object)\\s+${component.name}\\b`);
  const owner = kotlinFiles.find(file => declarationPattern.test(kotlinTexts.get(file) || ''));
  if (!owner) fail(`AndroidManifest ${component.kind} has no Kotlin class/object source: ${component.name}`);
}
for (const activity of manifestActivities) {
  const activityPattern = new RegExp(`\\bclass\\s+${activity}\\s*:\\s*Activity\\s*\\(`);
  const owner = kotlinFiles.find(file => activityPattern.test(kotlinTexts.get(file) || ''));
  if (!owner) fail(`AndroidManifest activity is not backed by an Activity subclass: ${activity}`);
}
for (const file of kotlinFiles) {
  const text = kotlinTexts.get(file) || '';
  for (const match of text.matchAll(/\bclass\s+(\w+)\s*:\s*Activity\s*\(/g)) {
    if (!manifestActivities.has(match[1])) fail(`Activity source is not declared in AndroidManifest.xml: ${match[1]}`);
  }
}

const kotlinTypes = new Map(kotlinFiles.map(file => [path.basename(file, '.kt'), { file, text: read(file) }]));
const reachable = new Set([...manifestComponentNames]);
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
  if (!reachable.has(name)) fail(`Kotlin source is unreachable from an Android manifest component: ${node.file}`);
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
const localAgentBatch = read(`${kotlinDir}/RiftLocalAgentBatch.kt`);
const buildInstaller = read(`${kotlinDir}/RiftBuildInstaller.kt`);
if (!buildInstaller.includes('class RiftBuildInstallReceiver : BroadcastReceiver()')) fail('RiftBuild manifest receiver source is missing');
const headless = read(`${kotlinDir}/RiftHeadlessJsRuntime.kt`);
const runtime = read(`${kotlinDir}/RiftMcpRuntime.kt`);
const cliEvents = read(`${kotlinDir}/RiftMcpEventBus.kt`);
const relayClient = read(`${kotlinDir}/RiftMcpRelayClient.kt`);
const relayWorker = read('relay/src/index.js');
const browserWindow = read(`${kotlinDir}/RiftBrowserWindow.kt`);
const browserEngine = read(`${kotlinDir}/RiftBrowserAndroidWebViewEngine.kt`);
const browserHost = read(`${kotlinDir}/RiftBrowserAppHost.kt`);
const riftosJs = read('src/riftos.js');
const desktop = read(`${kotlinDir}/RiftNativeDesktop.kt`);
const workspaceApps = read(`${kotlinDir}/RiftNativeWorkspaceApps.kt`);
const browserBridge = read(`${kotlinDir}/RiftBrowserMcpAppBridge.kt`);
const toolHost = read(`${kotlinDir}/RiftToolHost.kt`);
const toolSandbox = read(`${kotlinDir}/RiftToolSandbox.kt`);
const cliCmake = read('android/app/src/main/cpp/CMakeLists.txt');

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

// C1.0 Core/Shell split: only Core owns installation/runtime/build authority.
const coreApplication = read(`${kotlinDir}/RiftCoreApplication.kt`);
const coreRuntime = read(`${kotlinDir}/RiftCoreRuntime.kt`);
const rappHost = read(`${kotlinDir}/RiftRappHost.kt`);
const platformBuild = read(`${kotlinDir}/RiftBuildPlatformTools.kt`);
for (const required of ['class RiftCoreApplication : Application()', 'RiftCoreRuntime.initialize(this)',
  'Application.getProcessName()', 'applicationInfo.processName']) {
  if (!coreApplication.includes(required)) fail(`C1.0 app-process bootstrap missing: ${required}`);
}
for (const required of ['object RiftCoreRuntime', 'fun initialize(context: Context)',
  'fun packages(context: Context)', 'fun runtimes(context: Context)',
  'fun buildPlatform(context: Context)', 'fun status(context: Context)',
  'riftos.core.status/1', '"desktopRequired", false', '"separateCoreProcess", false']) {
  if (!coreRuntime.includes(required)) fail(`C1.0 process-owned Core service missing: ${required}`);
}
if (/RiftNativeDesktop|MainActivity|RiftNativeShell|RiftRappHost|\bActivity\b/.test(
  coreRuntime.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '')
)) fail('Core implementation must not have RiftShell or desktop code references');
if (!manifest.includes('android:name=".RiftCoreApplication"')) fail('Android app manifest must bootstrap RiftOS Core');
for (const f of ['RiftCoreApplication.kt', 'RiftCoreRuntime.kt']) {
  if (!gradle.includes(`"src/main/java/com/riftos/app/${f}"`)) fail(`C1.0 exact Gradle source missing: ${f}`);
}
if (!nativeShell.includes('RiftCoreRuntime.buildPlatform(appContext)') ||
    !nativeShell.includes('"core" -> {') ||
    !nativeShell.includes('RiftCoreRuntime.status(appContext)')) {
  fail('RiftShell is still owning or not exposing the Core platform status');
}
if (!rappHost.includes('RiftCoreRuntime.packages(activity.applicationContext)') ||
    !platformBuild.includes('RiftCoreRuntime.packages(appContext)')) {
  fail('RAPP package ownership has not moved into RiftOS Core');
}

// C1.1-A: Core owns RAPP identity/opaque program state independently of UI.
const coreSessions = read(`${kotlinDir}/RiftCoreAppSessions.kt`);
const coreExecutor = read(`${kotlinDir}/RiftCoreAppExecutor.kt`);
const coreAppLifecycle = read(`${kotlinDir}/RiftCoreAppLifecycle.kt`);
for (const required of [
  'class RiftCoreAppSessions', 'riftos.core.sessions/1',
  'fun attach(', 'fun detach(attachment: Attachment)',
  'fun close(attachment: Attachment)', 'fun isAttached(attachment: Attachment)',
  'fun programSnapshot()', 'fun commitState(value: ByteArray)',
  'fun nextEventSequence()', 'fun list(): JSONObject',
  '"headlessExecution", true', '"appExecutionIndependentOfDesktop", true',
]) if (!coreSessions.includes(required)) fail(`Core RAPP session contract missing: ${required}`);
if (/\b(?:Activity|View|RiftNativeDesktop|RiftNativeShell|RiftRappHost)\b/.test(
  stripCodeComments(coreSessions)
)) fail('Core session registry must not depend on desktop/UI implementation classes');
for (const required of [
  'private val coreLifecycle = RiftCoreRuntime.lifecycle(activity.applicationContext)',
  'coreLifecycle.openForShell(id)',
  'coreLifecycle.offerEvent(session.id, session.generation, event)',
  'coreLifecycle.stop(id)',
  'coreSurfaces.subscribe { change ->',
  'coreSurfaces.unsubscribe(surfaceSubscription)'
]) if (!rappHost.includes(required)) fail(`C1.3-C UI presentation-only delegation missing: ${required}`);
for (const forbidden of ['coreSessions.attach(', 'coreSessions.detach(', 'coreSessions.close(',
  'coreSessions.offerEvent(', 'coreSessions.finishEvent(', 'coreExecutor.executeChained(',
  'dispatchCoreEvent(', 'runEventStep(', 'RiftAppAbi.RuntimePayload(']) {
  if (rappHost.includes(forbidden)) fail(`C1.3-C desktop retained app execution: ${forbidden}`);
}
if (!coreRuntime.includes('fun sessions(context: Context): RiftCoreAppSessions') ||
    !coreRuntime.includes('sessions(context).summary()')) {
  fail('RiftOS Core must own and report RAPP sessions');
}
if (!gradle.includes('"src/main/java/com/riftos/app/RiftCoreAppSessions.kt"')) {
  fail('Core session registry must be Gradle-mandatory');
}
if (!nativeShell.includes('RiftCoreRuntime.sessions(appContext).list()')) {
  fail('Read-only core sessions command missing');
}

// C1.1-B1: interpreter execution, deadlines and persisted state belong to Core.
for (const required of [
  'class RiftCoreAppExecutor', 'RiftBoundedAsync.submit(',
  'RiftNativeBufferCompilerService.compile(', 'RiftRappQuickJsExecutor()',
  'adapter.encodeEvent(', 'adapter.decodeOutput(', 'sessions.commitFromExecution(',
  'fun execute(', 'RiftCoreRuntime.runtimes(app)', 'sessions.matchesExecution('
]) if (!coreExecutor.includes(required)) fail(`C1.1-B1 Core execution missing: ${required}`);
if (/\b(?:Activity|View|RiftNativeDesktop|RiftNativeShell|RiftRappHost)\b/.test(
  stripCodeComments(coreExecutor)
)) fail('Core RAPP executor references UI/desktop types');
for (const required of [
  'private val executor = RiftCoreRuntime.appExecutor(app)',
  'executor.executeChained(', 'private fun dispatch(',
  'sessions.authorizeQueuedEventDispatch(', 'sessions.offerEvent(',
  'sessions.finishEvent('
]) if (!coreAppLifecycle.includes(required)) fail(`C1.3-C Core execution dispatch missing: ${required}`);
for (const forbidden of [
  'RiftRappQuickJsExecutor()', 'RiftNativeBufferCompilerService.compile(',
  'RiftBoundedAsync.submit(', 'Executors.newSingleThreadExecutor',
  'session.coreAttachment.record.nextEventSequence()',
]) if (rappHost.includes(forbidden)) fail(`C1.1-B1 desktop regained Core execution: ${forbidden}`);
if (!coreRuntime.includes('fun appExecutor(context: Context): RiftCoreAppExecutor')) {
  fail('C1.1-B1 Core executor service ownership missing');
}
if (!gradle.includes('"src/main/java/com/riftos/app/RiftCoreAppExecutor.kt"')) {
  fail('C1.1-B1 Core executor missing from mandatory Kotlin snapshot');
}
if (!coreSessions.includes('fun commitFromExecution(')) {
  fail('C1.1-B1 Core program commit must validate attachment generation');
}

// C1.1-B2-A: Core owns FIFO event tickets and bounded queue state, never UI callbacks.
for (const required of [
  'data class EventTicket(', 'data class OfferedEvent(', 'MAX_PENDING_EVENTS = 64',
  'MAX_PENDING_EVENT_BYTES = 1024 * 1024', 'private val waitingEvents',
  'private var runningEvent', 'private var pendingBytes',
  'fun offerEvent(attachment: Attachment', 'fun finishEvent(attachment: Attachment',
  'resetPendingEvents()', 'event.copy(bytes = event.bytes.copyOf())',
  '"eventQueueOwner", "riftos-core"', '"headlessExecution", true',
]) if (!coreSessions.includes(required)) fail(`C1.1-B2-A Core FIFO contract missing: ${required}`);
for (const required of [
  'sessions.offerEvent(entry.attachment, event)',
  'sessions.finishEvent(entry.attachment, ticket)',
  'if (offered.startNow) dispatch(entry, offered.ticket)'
]) if (!coreAppLifecycle.includes(required)) fail(`C1.3-C Core FIFO driver missing: ${required}`);
for (const forbidden of ['val pendingEvents =', 'var eventBusy:', 'data class PendingEvent(']) {
  if (rappHost.includes(forbidden)) fail(`C1.1-B2-A desktop regained event queue: ${forbidden}`);
}

// C1.1-B2-B: capability execution belongs to Core; only UI requests cross to shell.
const coreConsent = read(`${kotlinDir}/RiftCoreShellCapabilityRequests.kt`);
const shellCapabilityClient = read(`${kotlinDir}/RiftRappShellCapabilityClient.kt`);
const capabilityBroker = read(`${kotlinDir}/RiftRappCapabilityBroker.kt`);
for (const [data, required] of [
  [coreConsent, 'riftos.core.capability-consent/1'],
  [coreConsent, 'riftos.core.ui-effect/1'],
  [coreConsent, 'MAX_PENDING = 64'],
  [shellCapabilityClient, 'RiftCoreShellCapabilityRequests.subscribe('],
  [shellCapabilityClient, '.setNeutralButton("Cancel")'],
  [shellCapabilityClient, '.setOnCancelListener'],
  [capabilityBroker, 'RiftCoreShellCapabilityRequests.requestConsent('],
  [capabilityBroker, 'RiftCoreShellCapabilityRequests.requestUiEffect('],
  [coreExecutor, 'fun executeChained('],
  [coreExecutor, 'capabilityBroker.execute('],
]) if (!data.includes(required)) fail(`C1.1-B2-B missing: ${required}`);
if (rappHost.includes('resolveHostEffect(') || rappHost.includes('capabilityBroker.execute(')) {
  fail('C1.1-B2-B effect loop remains in desktop');
}
for (const name of ['RiftCoreShellCapabilityRequests.kt', 'RiftRappShellCapabilityClient.kt']) {
  if (!gradle.includes(`"src/main/java/com/riftos/app/${name}"`)) fail(`Missing required Kotlin source ${name}`);
}

// C1.2-A: Core-owned generic immutable surface snapshots; shell no longer
// needs to own the only copy of the final app UI frame.
const coreSurfaces = read(`${kotlinDir}/RiftCoreAppSurfaces.kt`);
for (const required of [
  'class RiftCoreAppSurfaces', 'riftos.core.app-surfaces/1',
  'data class Snapshot(', 'data class Change(',
  'MAX_SURFACES = 128', 'MAX_SUBSCRIBERS = 32',
  'MAX_SURFACE_TEXT_BYTES = 256 * 1024',
  'MAX_TOTAL_SURFACE_TEXT_BYTES = 4 * 1024 * 1024',
  'frame.nodes.map { it.copy() }', 'fun snapshot(', 'fun publish(',
  'fun remove(', 'fun subscribe(', 'fun unsubscribe('
]) if (!coreSurfaces.includes(required)) fail(`C1.2-A Core surfaces missing: ${required}`);
if (/\b(?:Activity|View|RiftNativeDesktop|RiftRappHost)\b/.test(stripCodeComments(coreSurfaces))) {
  fail('C1.2-A Core surface registry imports graphical shell implementation types');
}
for (const required of [
  'fun surfaces(context: Context): RiftCoreAppSurfaces',
  '.put("appSurfaces", surfaces(context).list())'
]) if (!coreRuntime.includes(required)) fail(`C1.2-A Core surfaces service missing: ${required}`);
for (const required of [
  'publishSurfaceFromExecution(', 'surfaces.remove(attachment.record.id)',
  'surfaces.remove(id)'
]) if (!coreSessions.includes(required)) fail(`C1.2-A lifecycle surface publication missing: ${required}`);
if (!coreExecutor.includes('sessions.publishSurfaceFromExecution(') ||
    !nativeShell.includes('"surfaces" -> RiftCoreRuntime.surfaces(appContext).list()') ||
    !gradle.includes('"src/main/java/com/riftos/app/RiftCoreAppSurfaces.kt"')) {
  fail('C1.2-A Core surface publication, diagnostic or mandatory Gradle snapshot missing');
}

// C1.2-B1: graphical RiftShell reads authoritative Core surface snapshots.
for (const required of [
  'RiftCoreRuntime.surfaces(activity.applicationContext)',
  'coreSurfaces.subscribe { change ->',
  'coreSurfaces.snapshot(session.id)',
  'it.attachmentGeneration == session.generation',
  'session.onFrame = { next -> applyFrame(next) }'
]) if (!rappHost.includes(required)) fail(`C1.3-C Core surface presentation client missing: ${required}`);
if (rappHost.includes('EventOutcome(frame = result.frame')) {
  fail('C1.2-B1 RiftShell still renders raw Core executor callback frame');
}

// C1.2-B2-B1: versioned Core-owned focus lease; shell only requests focus.
const coreInputFocus = read(`${kotlinDir}/RiftCoreInputFocus.kt`);
const desktopFocusClient = read(`${kotlinDir}/RiftNativeDesktop.kt`);
const activityFocusClient = read(`${kotlinDir}/MainActivity.kt`);
for (const marker of [
  'riftos.core.input-focus/1', 'data class Lease(',
  'requestVerified(', 'fun revoke(', 'focusEnforcedForInput", true'
]) if (!coreInputFocus.includes(marker)) fail(`C1.2-B2-B1 Core focus lease missing: ${marker}`);
if (/import android\.(app|view)\./.test(coreInputFocus) ||
    coreInputFocus.includes('RiftNativeDesktop')) {
  fail('C1.2-B2-B1 Core focus lease cannot depend on Activity or desktop');
}
for (const marker of [
  'private val inputFocus = RiftCoreInputFocus()',
  'fun requestFocusFromShell(', 'record?.activeGeneration()',
  'inputFocus.revoke(payload.id)', 'inputFocus.revoke(id)',
  'fun focusStatus(): JSONObject'
]) if (!coreSessions.includes(marker)) fail(`C1.2-B2-B1 Core focus authority missing: ${marker}`);
for (const marker of [
  'focusRequestSink: (String?) -> Unit',
  'focusRequestSink(visibleFocusId)',
  'reason == "open"'
]) if (!desktopFocusClient.includes(marker)) fail(`C1.2-B2-B1 shell focus client missing: ${marker}`);
if (!activityFocusClient.includes('focusRequestSink = { id ->') ||
    !activityFocusClient.includes('id.takeIf { hasWindowFocus() && !isFinishing && !isDestroyed }') ||
    !nativeShell.includes('"focus" -> RiftCoreRuntime.sessions(appContext).focusStatus()') ||
    !gradle.includes('"src/main/java/com/riftos/app/RiftCoreInputFocus.kt"') ||
    !coreRuntime.includes('.put("inputFocus", sessions(context).focusStatus())')) {
  fail('C1.3-C foreground-only Core focus client or diagnostic missing');
}
for (const marker of [
  'private fun restoreCoreWindowFocus()', 'override fun onWindowFocusChanged(hasFocus: Boolean)',
  'restoreCoreWindowFocus()', 'override fun onPause()', 'requestFocusFromShell(null)',
  'window.optBoolean("focused")', '!window.optBoolean("minimized")'
]) if (!activityFocusClient.includes(marker)) fail(`C1.3-C Activity focus lifecycle missing: ${marker}`);
const osLocalAgent = read(`${kotlinDir}/RiftVortexLocalAgent.kt`);
for (const marker of [
  'recreate-main-activity-proof', 'context is MainActivity && context.hasWindowFocus()',
  'RiftCoreRuntime.lifecycle(context.applicationContext)',
  'entry.optString("state") == "running"', 'context.recreate()',
  'riftos.qa.activity-recreate/1', 'coreProcessTermination", false'
]) if (!osLocalAgent.includes(marker)) fail(`C1.3-C controlled Activity recreation proof missing: ${marker}`);

// C1.2-C1: installed RAPP BOOT/stop runs in Core without a RiftShell
// window, Activity or graphical subscriber. Device proof still pending.
// C1.3-C supersedes the old destructive C2 attachment-claim handshake:
 // opening an existing app reuses Core generation without taking ownership.
for (const marker of [
  'fun openForShell(id: String): JSONObject = start(id)',
  'return entryJson(prior).put("accepted", false).put("reason", "already-started")',
  'fun offerEvent(id: String, generation: Long, event: RiftAppAbi.Event)',
  'private fun dispatch(entry: Entry, ticket: RiftCoreAppSessions.EventTicket)',
  'sessions.close(entry.attachment)'
]) if (!coreAppLifecycle.includes(marker)) fail(`C1.3-C Core-only app ownership missing: ${marker}`);
for (const marker of [
  'coreLifecycle.openForShell(id)',
  'state.getLong("attachmentGeneration")',
  'it.attachmentGeneration == session.generation'
]) if (!rappHost.includes(marker)) fail(`C1.3-C desktop Core reuse missing: ${marker}`);

for (const marker of [
  'riftos.core.apps/1',
  'fun start(id: String): JSONObject',
  'fun stop(id: String): JSONObject',
  'fun status(): JSONObject',
  'packages.loadInstalled(id)',
  'sessions.hasAttachedApp(id)',
  'sessions.attach(payload, adapter)',
  'executor.executeChained(',
  'RiftAppAbi.Event(kind = RiftAppAbi.EventKind.BOOT)',
  'sessions.close(attachment)',
  'RiftCorePackageEvents.subscribe',
  'coreOnlyBootSupported", true'
]) if (!coreAppLifecycle.includes(marker)) fail(`C1.2-C1 Core-only RAPP lifecycle missing: ${marker}`);
if (coreAppLifecycle.includes('RiftRappHost') ||
    coreAppLifecycle.includes('RiftNativeDesktop') ||
    /import android\.(app|view)\./.test(coreAppLifecycle)) {
  fail('C1.2-C1 Core-only RAPP lifecycle imports shell or Activity');
}
for (const marker of [
  'fun hasAttachedApp(id: String): Boolean'
]) if (!coreSessions.includes(marker)) fail(`C1.2-C1 attached-session guard missing: ${marker}`);
for (const marker of [
  'fun lifecycle(context: Context): RiftCoreAppLifecycle',
  '.put("coreApps", lifecycle(context).status())'
]) if (!coreRuntime.includes(marker)) fail(`C1.2-C1 process-owned lifecycle wiring missing: ${marker}`);
for (const marker of [
  '"apps" -> RiftCoreRuntime.lifecycle(appContext).status()',
  'RiftCoreRuntime.lifecycle(appContext).start(args[1])',
  'RiftCoreRuntime.lifecycle(appContext).stop(args[1])'
]) if (!nativeShell.includes(marker)) fail(`C1.2-C1 Core-only app control missing: ${marker}`);
if (!gradle.includes('"src/main/java/com/riftos/app/RiftCoreAppLifecycle.kt"')) {
  fail('C1.2-C1 mandatory lifecycle Kotlin source missing');
}

// C1.2-D1: an alternate read-only shell renderer attaches to the same
// immutable Core surfaces concurrently with the existing graphical shell.
const alternateShell = read(`${kotlinDir}/RiftAlternateShellClient.kt`);
for (const marker of [
  'riftos.shell.client.terminal/1',
  'class RiftAlternateShellClient(private val surfaces: RiftCoreAppSurfaces)',
  'fun attach(id: String): JSONObject',
  'fun render(id: String): JSONObject',
  'fun detach(id: String): JSONObject',
  'fun status(): JSONObject',
  'surfaces.subscribe { change -> onChange(change) }',
  'surfaces.snapshot(id)',
  'surfacePresent',
  'lastOperation',
  'renderedText',
  'RiftAppAbi.NodeKind.TEXT_INPUT',
  'RiftAppAbi.NodeKind.ACTION',
  'subscription?.let(surfaces::unsubscribe)'
]) if (!alternateShell.includes(marker)) fail(`C1.2-D1 alternate shell renderer missing: ${marker}`);
for (const forbidden of [
  'private val desktop:', 'private val activity:', '.offerEvent(',
  '.requestFocusFromShell(', 'sessions.attach(', '.executeChained('
]) if (alternateShell.includes(forbidden)) {
  fail(`C1.2-D1 alternate shell cannot own graphical execution: ${forbidden}`);
}
if (/import android\.(view|app)\./.test(alternateShell)) {
  fail('C1.2-D1 alternate shell renderer cannot require Android Activity/View');
}
for (const marker of [
  'RiftAlternateShellClient(RiftCoreRuntime.surfaces(appContext))',
  'alternateShellClient.close()',
  '"alt-list" -> alternateShellClient.status()',
  'alternateShellClient.attach(args[1])',
  'alternateShellClient.render(args[1])',
  'alternateShellClient.detach(args[1])'
]) if (!nativeShell.includes(marker)) fail(`C1.2-D1 alternate shell command missing: ${marker}`);
if (!gradle.includes('"src/main/java/com/riftos/app/RiftAlternateShellClient.kt"')) {
  fail('C1.2-D1 alternate shell mandatory Kotlin source missing');
}

// C1.2-D2: a separate read-only native graphical Activity renders the
// same Core snapshots, without RiftRappHost/RiftNativeDesktop authority.
const alternateGraphical = read(`${kotlinDir}/RiftAlternateGraphicalShellActivity.kt`);
for (const marker of [
  'class RiftAlternateGraphicalShellActivity : Activity()',
  'riftos.core.alt-graphic-app-id',
  'RiftCoreRuntime.surfaces(applicationContext)',
  'surfaces.subscribe { change ->',
  'if (change.appId == appId)',
  'surfaces.snapshot(appId)',
  'frame.layout == RiftAppAbi.Layout.ABSOLUTE',
  'RiftAppAbi.NodeKind.TEXT_INPUT',
  'RiftAppAbi.NodeKind.ACTION',
  'RiftAppAbi.NodeKind.IMAGE',
  'subscription?.let(surfaces::unsubscribe)',
  'isClickable = false',
  'isFocusable = false',
  'No application input'
]) if (!alternateGraphical.includes(marker)) fail(`C1.2-D2 graphical client missing: ${marker}`);
for (const forbidden of [
  'RiftRappHost(', 'RiftNativeDesktop(', '.offerEvent(',
  '.requestFocusFromShell(', '.executeChained(', 'sessions.attach('
]) if (alternateGraphical.includes(forbidden)) fail(`C1.2-D2 graphical client has forbidden input/execution authority: ${forbidden}`);
for (const marker of [
  'RiftAlternateGraphicalShellActivity::class.java',
  'Intent.FLAG_ACTIVITY_NEW_TASK',
  'RiftAlternateGraphicalShellActivity.EXTRA_APP_ID',
  'RiftCoreRuntime.surfaces(appContext).snapshot(id)',
  '"riftos.shell.client.graphical/1"'
]) if (!nativeShell.includes(marker)) fail(`C1.2-D2 graphical shell control missing: ${marker}`);
if (!manifest.includes('android:name=".RiftAlternateGraphicalShellActivity"') ||
    !gradle.includes('"src/main/java/com/riftos/app/RiftAlternateGraphicalShellActivity.kt"')) {
  fail('C1.2-D2 graphical Activity manifest or mandatory Kotlin source missing');
}

// C1.3-A: Android Binder read-only snapshots from main Core process
// to :riftShellProbe; this is NOT full RiftShell process isolation yet.
const coreIpcProvider = read(`${kotlinDir}/RiftCoreSurfaceIpcProvider.kt`);
const remoteShell = read(`${kotlinDir}/RiftRemoteShellProbeActivity.kt`);
for (const marker of [
  'riftos.core.surface-ipc/1',
  'com.riftos.app.core-surface-ipc',
  'class RiftCoreSurfaceIpcProvider : ContentProvider()',
  'override fun call(method: String, arg: String?, extras: Bundle?): Bundle',
  'RiftCoreRuntime.surfaces(ctx).snapshot(id)',
  'Process.myPid()',
  'MAX_REPLY_BYTES = 256 * 1024',
  'Core IPC snapshot exceeds bounded Binder payload',
  'Core IPC insert forbidden',
  'Core IPC update forbidden',
  'Core IPC delete forbidden'
]) if (!coreIpcProvider.includes(marker)) fail(`C1.3-A Core IPC provider missing: ${marker}`);
for (const marker of [
  'class RiftRemoteShellProbeActivity : Activity()',
  'RiftCoreSurfaceIpcProvider.AUTHORITY',
  'contentResolver.call(',
  'RiftCoreSurfaceIpcProvider.METHOD_SNAPSHOT',
  'RiftCoreSurfaceIpcProvider.RESULT_JSON',
  'Process.myPid()',
  'Core PID=',
  'Shell PID=',
  'Separate Android processes:',
  'mainHandler.removeCallbacks(poll)',
  'RiftAppAbi.NodeKind.TEXT_INPUT',
  'RiftAppAbi.NodeKind.ACTION'
]) if (!remoteShell.includes(marker)) fail(`C1.3-A remote shell IPC consumer missing: ${marker}`);
for (const marker of [
  'RiftCoreRuntime.', 'RiftRappHost(', 'RiftNativeDesktop(',
  'sessions.attach(', '.offerEvent(', '.requestFocusFromShell('
]) if (remoteShell.includes(marker)) {
  fail(`C1.3-A remote shell directly invokes prohibited Core authority: ${marker}`);
}
if (!manifest.includes('android:name=".RiftCoreSurfaceIpcProvider"') ||
    !manifest.includes('android:authorities="com.riftos.app.core-surface-ipc"') ||
    !manifest.includes('android:name=".RiftRemoteShellProbeActivity"') ||
    !manifest.includes('android:process=":riftShellProbe"')) {
  fail('C1.3-A distinct-process IPC manifest wiring missing');
}
if (!gradle.includes('"src/main/java/com/riftos/app/RiftCoreSurfaceIpcProvider.kt"') ||
    !gradle.includes('"src/main/java/com/riftos/app/RiftRemoteShellProbeActivity.kt"')) {
  fail('C1.3-A IPC mandatory Kotlin compile sources missing');
}
for (const marker of [
  'RiftRemoteShellProbeActivity::class.java',
  'Intent.FLAG_ACTIVITY_NEW_TASK',
  'RiftRemoteShellProbeActivity.EXTRA_APP_ID',
  '"riftos.shell.client.remote-ipc/1"',
  '"ipc-view"'
]) if (!nativeShell.includes(marker)) fail(`C1.3-A IPC control command missing: ${marker}`);

// C1.3-B: isolated remote-shell process loss test. Refuse any
// termination unless real distinct Core PID and exact :riftShellProbe name.
for (const marker of [
  'Terminate isolated shell probe (test)',
  'private var lastVerifiedCorePid: Int = -1',
  'isEnabled = false',
  'lastVerifiedCorePid > 0 && pid != lastVerifiedCorePid',
  'isExactRemoteProbeProcess()',
  'Process.killProcess(pid)',
  'terminateProbe.isEnabled = false',
  'if (separate && present) corePid else -1',
  'private fun isExactRemoteProbeProcess(): Boolean',
  'File("/proc/self/cmdline")',
  'packageName + ":riftShellProbe"'
]) if (!remoteShell.includes(marker)) fail(`C1.3-B remote-only process termination guard missing: ${marker}`);
for (const marker of ['Process.killProcess(', 'Process.sendSignal(']) {
  if (coreIpcProvider.includes(marker) ||
      nativeShell.includes(marker) ||
      coreRuntime.includes(marker)) {
    fail(`C1.3-B process termination must be isolated to test shell Activity: ${marker}`);
  }
}

// C1.3-D: the PRODUCTION desktop/window manager and RAPP renderer now run
// in :riftShell. The read-only :riftShellProbe remains a distinct diagnostic.
// No graphical shell process may instantiate Core app execution singletons.
const productionShell = read(`${kotlinDir}/RiftShellActivity.kt`);
const productionRappHost = read(`${kotlinDir}/RiftShellRappHost.kt`);
const productionClient = read(`${kotlinDir}/RiftShellCoreClient.kt`);
const remoteExecutor = read(`${kotlinDir}/RiftRemoteShellExecutor.kt`);
const remoteUiClient = read(`${kotlinDir}/RiftRemoteShellUiClient.kt`);
const coreUiBroker = read(`${kotlinDir}/RiftCoreShellRemoteUiBroker.kt`);
const coreWindowBridge = read(`${kotlinDir}/RiftCoreShellWindowBridge.kt`);
const coreLaunchQueue = read(`${kotlinDir}/RiftCoreShellLaunchQueue.kt`);
const rappManager = read(`${kotlinDir}/RiftRappManager.kt`);
const nativeSystemApps = read(`${kotlinDir}/RiftNativeSystemApps.kt`);
for (const file of [
  'RiftShellActivity.kt', 'RiftShellRappHost.kt', 'RiftShellCoreClient.kt',
  'RiftRemoteShellExecutor.kt', 'RiftRemoteShellUiClient.kt',
  'RiftCoreShellRemoteUiBroker.kt', 'RiftCoreShellWindowBridge.kt',
  'RiftCoreShellLaunchQueue.kt'
]) if (!gradle.includes(`"src/main/java/com/riftos/app/${file}"`)) {
  fail(`C1.3-D production remote shell mandatory Gradle source missing: ${file}`);
}
if (!/android:name="\.RiftShellActivity"[\s\S]*?android:process=":riftShell"[\s\S]*?<intent-filter>[\s\S]*?android\.intent\.action\.MAIN[\s\S]*?android\.intent\.category\.LAUNCHER/.test(manifest)) {
  fail('C1.3-D actual default graphical launcher must run in independent :riftShell');
}
if (manifest.includes('android:process=":riftShellProbe"') === false ||
    !manifest.includes('android:name=".RiftCoreSurfaceIpcProvider"')) {
  fail('C1.3-D must preserve separate read-only probe and Core Binder provider');
}
for (const required of [
  'class RiftShellActivity : Activity()', 'RiftNativeDesktop(',
  'RiftNativeSystemApps(', 'RiftNativeWorkspaceApps(',
  'RiftBrowserWindow(', 'RiftBrowserAppHost(', 'RiftShellRappHost(',
  'RiftRemoteShellUiClient(', 'RiftShellCoreClient(',
  'desktop.window.state', 'core.focus(null)', 'core.reportDesktop(state)'
]) if (!productionShell.includes(required)) {
  fail(`C1.3-D real graphical shell missing: ${required}`);
}
for (const required of [
  'core.installed()', 'core.start(id)', 'core.snapshot(id)',
  'core.offerEvent(session.id, session.generation, event)',
  'core.stop(id, session.generation)', 'RiftRappAbsoluteView('
]) if (!productionRappHost.includes(required)) {
  fail(`C1.3-D remote RAPP renderer must delegate execution to Core IPC: ${required}`);
}
for (const forbidden of ['RiftCoreRuntime.', 'RiftCoreAppExecutor(', 'RiftRappHost(',
  'RiftCoreAppSessions(', 'RiftMcpRuntime.relayClient(']) {
  if (productionShell.includes(forbidden) || productionRappHost.includes(forbidden)) {
    fail(`C1.3-D production RiftShell retained Core execution ownership: ${forbidden}`);
  }
}
for (const required of [
  'class RiftShellCoreClient(', 'contentResolver', 'resolver.call(',
  'attachmentGeneration', 'RiftCoreSurfaceIpcProvider.METHOD_SHELL_EVENT',
  'RiftCoreSurfaceIpcProvider.METHOD_SHELL_FOCUS',
  'RiftCoreSurfaceIpcProvider.METHOD_SNAPSHOT',
  'pid != Process.myPid()'
]) if (!productionClient.includes(required)) {
  fail(`C1.3-D authenticated bounded remote Core client missing: ${required}`);
}
for (const required of [
  'Binder.getCallingUid()', 'Binder.getCallingPid()',
  'uid == ctx.applicationInfo.uid', 'pid != Process.myPid()',
  'ctx.packageName + ":riftShell"',
  'manager.runningAppProcesses', 'process.pid == pid && process.uid == uid',
  'registryName == expected', 'procName == expected',
  'RiftCoreRuntime.lifecycle(ctx).offerEvent(id, generation, event)',
  'RiftCoreRuntime.lifecycle(ctx).stopForShell(id, expected)',
  'RiftCoreRuntime.sessions(ctx)', 'METHOD_SHELL_DESKTOP_REPORT',
  'METHOD_SHELL_UI_POLL', 'RiftCoreShellRemoteUiBroker.respond('
]) if (!coreIpcProvider.includes(required)) {
  fail(`C1.3-D Core must authenticate, bound and settle cross-process IPC: ${required}`);
}
for (const required of [
  'RiftCoreShellCapabilityRequests.respondConsent(',
  'RiftCoreShellCapabilityRequests.respondUiEffect(',
  'private const val MAX_QUEUE = 64', 'fun poll(): JSONObject'
]) if (!coreUiBroker.includes(required)) {
  fail(`C1.3-D Core-owned capability tickets were not bridged: ${required}`);
}
for (const required of [
  'ipc.pollUi()', 'ipc.respondConsent(', 'ipc.respondEffect(',
  'AlertDialog.Builder(activity)', 'dispatchCommand(method, arg)'
]) if (!remoteUiClient.includes(required)) {
  fail(`C1.3-D remote shell UI work not delivered: ${required}`);
}
for (const required of [
  'RiftCoreShellWindowBridge.status()', 'RiftCoreShellWindowBridge.offer("close", id)',
  'RiftCoreShellWindowBridge.offer("open", id)',
  'RiftCoreShellWindowBridge.offer("browser", url.take(2048))'
]) if (!nativeShell.includes(required)) {
  fail(`C1.3-D MCP shell commands still depend on in-process MainActivity: ${required}`);
}
for (const required of [
  'private const val MAX_COMMANDS = 32', 'fun report(callingPid: Int, json: String)',
  'fun drainCommands(callingPid: Int): JSONArray'
]) if (!coreWindowBridge.includes(required)) {
  fail(`C1.3-D Core remote desktop state bridge missing: ${required}`);
}
if (!coreLaunchQueue.includes('fun offer(id: String): Boolean') ||
    !rappManager.includes('RiftCoreShellLaunchQueue.offer(id)')) {
  fail('C1.3-D Core-first launch must remain independent of shell presentation');
}
for (const required of [
  'private val remoteCore: RiftShellCoreClient? = null',
  'remoteCore?.installRapp(path)', 'remoteCore?.uninstallRapp(id)',
  'remoteCore?.installed()'
]) if (!nativeSystemApps.includes(required)) {
  fail(`C1.3-D native system window must use remote Core package authority: ${required}`);
}
if (!coreApplication.includes('RiftMcpRuntime.relayClient(this).start()') ||
    !coreApplication.includes('RiftBrowserWindow.prepareRemoteShellWebViewDirectory()') ||
    !browserWindow.includes('fun prepareRemoteShellWebViewDirectory()') ||
    !browserWindow.includes('WebView.setDataDirectorySuffix("riftShell")') ||
    hasWebKitDependency(coreApplication)) {
  fail('C1.3-D Core process bootstrap must delegate remote-shell WebKit suffix to RiftBrowser owner');
}

// C1.3-E: REAL production shell process recovery is Core-owned. Only a
// named PID authenticated via Binder may claim the old window snapshot;
// actual Core RAPPs must be reattached to the same generation, never BOOTed.
// Device promotion remains separate from these source-only checks.
const coreShellRecovery = read(`${kotlinDir}/RiftCoreShellRecovery.kt`);
const localAgent = read(`${kotlinDir}/RiftVortexLocalAgent.kt`);
if (!gradle.includes('"src/main/java/com/riftos/app/RiftCoreShellRecovery.kt"') ||
    !coreApplication.includes('RiftCoreShellRecovery.initialize(this)')) {
  fail('C1.3-E Core-owned remote-shell recovery watchdog missing from compiled default process');
}
for (const required of [
  'const val SCHEMA = "riftos.core.shell-recovery/1"',
  'fun noteReport(pid: Int, state: JSONObject)',
  'fun claim(pid: Int, context: Context): JSONObject',
  'private const val MAX_ATTEMPTS = 3',
  'private const val ATTEMPT_COOLDOWN_MS = 5_000L',
  'if (!hasSeenShell || !foreground || ownerPid <= 0) return',
  'if (processAlive(context, ownerPid))',
  'RiftCoreRuntime.sessions(context).requestFocusFromShell(null)',
  'context.startActivity(intent)',
  'it.processName == context.packageName + ":riftShell"',
  'fun killShellForProof(context: Context, disposableId: String)',
  'disposableId == "c12b2a-input-probe-20261008"',
  'Process.killProcess(old)',
  'attempts >= MAX_ATTEMPTS',
  'now - connectedSince >= 15_000L'
]) if (!coreShellRecovery.includes(required)) {
  fail(`C1.3-E bounded production shell crash recovery missing: ${required}`);
}
for (const required of [
  'METHOD_SHELL_RECOVERY_CLAIM',
  'METHOD_SHELL_REATTACH',
  'RiftCoreShellRecovery.claim(',
  'RiftCoreShellRecovery.noteReport(',
  'reattachForShell(id, expected)'
]) if (!coreIpcProvider.includes(required)) {
  fail(`C1.3-E authenticated Core recovery/reattach Binder method missing: ${required}`);
}
if (!coreAppLifecycle.includes('fun reattachForShell(id: String, expectedGeneration: Long)') ||
    !coreAppLifecycle.includes('Recovery cannot BOOT a stopped app')) {
  fail('C1.3-E restoration may never BOOT a stale or stopped RAPP');
}
for (const required of [
  'core.claimRecovery()',
  'restoreDesktopWindows(restorePlan)',
  'rapps.openFromRecovery(id, gen)',
  'desktop.window.recoverBounds',
  '.put("riftShellForeground", active && hasWindowFocus())',
  '.put("riftShellForeground", false)'
]) if (!productionShell.includes(required)) {
  fail(`C1.3-E real shell snapshot and explicit background lifecycle missing: ${required}`);
}
for (const required of [
  'fun openFromRecovery(id: String, generation: Long)',
  'core.reattach(id, recoveryGeneration)',
  'core.stop(id, session.generation)'
]) if (!productionRappHost.includes(required)) {
  fail(`C1.3-E Core generation-only graphical reattach missing: ${required}`);
}
for (const required of [
  'RiftCoreSurfaceIpcProvider.METHOD_SHELL_RECOVERY_CLAIM',
  'fun claimRecovery(): JSONObject',
  'RiftCoreSurfaceIpcProvider.METHOD_SHELL_REATTACH',
  'fun reattach(id: String, generation: Long)'
]) if (!productionClient.includes(required)) {
  fail(`C1.3-E versioned Core shell recovery IPC client missing: ${required}`);
}
if (!desktop.includes('"desktop.window.recoverBounds" -> recoverBounds(args)') ||
    !desktop.includes('record.bounds = clampBounds(')) {
  fail('C1.3-E native window restoration must clamp safe-zone bounds');
}
if (!coreRuntime.includes('.put("shellRecovery", RiftCoreShellRecovery.status())') ||
    !localAgent.includes('kill-real-riftshell-process-proof')) {
  fail('C1.3-E Core-only observability and guarded real PID crash proof missing');
}
if (productionShell.includes('Process.killProcess(') ||
    productionRappHost.includes('Process.killProcess(') ||
    coreShellRecovery.includes('RiftCoreRuntime.lifecycle(context).stop(')) {
  fail('C1.3-E accidental graphical recovery Core process death or RAPP stop');
}

// C1.4-A: Core-only fail-closed system/admin capability policy.
// This gate does NOT grant elevated operations; it preserves existing sandbox
// boundaries and adds bounded durable denial audit introspection.
const c14Policy = read(`${kotlinDir}/RiftCoreSystemCapabilities.kt`);
const c14Broker = read(`${kotlinDir}/RiftRappCapabilityBroker.kt`);
if (!gradle.includes('"src/main/java/com/riftos/app/RiftCoreSystemCapabilities.kt"')) {
  fail('C1.4-A mandatory Core system policy source missing from Gradle');
}
for (const marker of [
  'const val SCHEMA = "riftos.core.system-capabilities/1"',
  'const val AUDIT_SCHEMA = "riftos.core.system-capability-audit/1"',
  '"system.fs.read"', '"system.fs.write"', '"software.install"',
  '"runtime.register"', '"process.protected.kill"',
  'fun recordDenied(context: Context, actor: String, operation: String)',
  'fun requireElevated(', 'throw SecurityException(',
  '"adminElevationEnabled", false', '"defaultDecision", "deny"',
  'MAX_ENTRIES = 64', 'MAX_SERIALIZED_BYTES = 24 * 1024',
  '.commit()', 'fun audit(context: Context, limit: Int = 16)'
]) if (!c14Policy.includes(marker)) {
  fail(`C1.4-A Core admin deny/audit policy missing: ${marker}`);
}
if (!coreRuntime.includes('"systemCapabilities", RiftCoreSystemCapabilities.status(context)') ||
    !c14Broker.includes('RiftCoreSystemCapabilities.recordDenied(') ||
    !nativeShell.includes('RiftCoreSystemCapabilities.recordDenied(') ||
    !nativeShell.includes('RiftCoreSystemCapabilities.status(appContext)') ||
    !nativeShell.includes('RiftCoreSystemCapabilities.audit(appContext)')) {
  fail('C1.4-A restricted system paths/processes require Core denial/audit/status');
}
if (c14Policy.includes('fun grant(') ||
    c14Policy.includes('adminElevationEnabled", true') ||
    c14Policy.includes('Runtime.getRuntime().exec(') ||
    c14Policy.includes('android.app.Activity')) {
  fail('C1.4-A policy must not introduce untrusted elevation or duplicate graphical consent');
}

// C1.4-B: Core-authorized, exact Binder+OS process and installed signer
// one-use *proof-only* admin consent. No actual privileged effects in B.
const c14Tickets = read(`${kotlinDir}/RiftCoreAdminConsent.kt`);
const c14AdminUi = read(`${kotlinDir}/RiftNativeAdminApprovals.kt`);
// Check required native Core consent actions independently; future gates can
// add actions without invalidating earlier C1.4 source protection checks.
const adminUiClientBody = productionClient.split('fun adminConsent(')[1]
  ?.split('fun claimRecovery(')[0] ?? '';
const adminUiAllowedActions = adminUiClientBody
  .split('require(action in setOf(')[1]?.split('))')[0] ?? '';
for (const source of ['RiftCoreAdminConsent.kt', 'RiftNativeAdminApprovals.kt']) {
  if (!gradle.includes(`"src/main/java/com/riftos/app/${source}"`)) {
    fail(`C1.4-B mandatory source not compiled: ${source}`);
  }
}
for (const marker of [
  'const val SCHEMA = "riftos.core.admin-consent/1"',
  'private const val TTL_MS = 45_000L',
  'private const val MAX_TICKETS = 8',
  'SecureRandom()', 'Binder.getCallingPid() == pid',
  'manager.runningAppProcesses?.any', 'context.packageName + ":riftShell"',
  'PackageManager.GET_SIGNING_CERTIFICATES', 'signingInfo?.apkContentsSigners',
  'fun request(context: Context, callerPid: Int, operation: String, target: String)',
  'fun decide(', 'fun revoke(', 'fun consumeProof(',
  'fun revokeForShellReplacement(', 'validScope(operation, target)',
  '"system.fs.read" && target == "/C:/System"',
  'executedPrivilegedEffect', '"grantPersistence", "none"'
]) if (!c14Tickets.includes(marker)) fail(`C1.4-B Core admin ephemeral PID+signer/scope ticket missing: ${marker}`);
if (c14Tickets.includes('FileOutputStream(') ||
    c14Tickets.includes('Process.killProcess(') ||
    c14Tickets.includes('RiftCoreRuntime.buildPlatform(') ||
    c14Tickets.includes('Runtime.getRuntime().exec(')) {
  fail('C1.4-B tickets MUST NOT execute system privileges');
}
for (const marker of [
  'fun recordDecision(', '"requested", "approved", "denied", "revoked", "expired", "consumed"',
  '.put("outcome", outcome)', '.commit()'
]) if (!c14Policy.includes(marker)) fail(`C1.4-B Core durable bounded decision audit missing: ${marker}`);
if (!coreRuntime.includes('"adminConsent", RiftCoreAdminConsent.status(context)') ||
    !coreShellRecovery.includes('RiftCoreAdminConsent.revokeForShellReplacement(')) {
  fail('C1.4-B Core admin token status or real shell restart revocation missing');
}
// The Android org.json getString API is nullable in the Kotlin compiler.
// Follow-up source fixes seven Gradle release errors in admin request
// parsing: ticket/operation/target MUST become bounded non-null strings.
for (const marker of [
  'val bearer = request.getString("ticket").orEmpty()',
  'require(bearer.length <= 64)',
  'val operation = request.getString("operation").orEmpty()',
  'val target = request.getString("target").orEmpty()',
  'require(operation.length <= 64 && target.length <= 128)'
]) if (!coreIpcProvider.includes(marker)) {
  fail(`C1.4-B Kotlin nullable admin IPC String must be bounded and non-null: ${marker}`);
}
for (const marker of [
  'const val METHOD_SHELL_ADMIN_CONSENT = "shell.admin.consent"',
  'METHOD_SHELL_ADMIN_CONSENT ->',
  'RiftCoreAdminConsent.request(ctx, caller, operation, target)',
  'RiftCoreAdminConsent.decide(ctx, caller, bearer,',
  'RiftCoreAdminConsent.revoke(ctx, caller, bearer)',
  'RiftCoreAdminConsent.consumeProof(',
  'requireProductionShellCaller()'
]) if (!coreIpcProvider.includes(marker)) fail(`C1.4-B authenticated Binder admin consent missing: ${marker}`);
for (const marker of [
  'fun adminConsent(', 'RiftCoreSurfaceIpcProvider.METHOD_SHELL_ADMIN_CONSENT',
  '"riftos.shell.admin-consent-request/1"'
]) if (!productionClient.includes(marker)) fail(`C1.4-B native Core consent IPC client missing: ${marker}`);
for (const marker of [
  'class RiftNativeAdminApprovals(', 'RiftOS administrator approvals',
  'Request scoped administrator test', 'Consume once — no privileged effect',
  'Revoke current approval', '.setPositiveButton("Allow once")',
  '.setNegativeButton("Deny")', '.setNeutralButton("Cancel")',
  'activity.hasWindowFocus()', 'client.adminConsent("request"',
  'client.adminConsent("consume-proof"', 'client.adminConsent("revoke"'
]) if (!c14AdminUi.includes(marker)) fail(`C1.4-B trusted native UI missing: ${marker}`);
if (!nativeSystemApps.includes('"admin-permissions"') ||
    !productionShell.includes('Triple("admin-permissions", "Admin Approvals"')) {
  fail('C1.4-B administrator proof must be an installed Core-owned native system window');
}

// C1.4-C1: first actual narrowly scoped Core-owned system effect is only a
// fixed, temporary C: test canary, journaled before write and rolled back in
// the same Core operation; NEVER a general-purpose privileged file writer.
const c14Rollback = read(`${kotlinDir}/RiftCoreAdminRollbackProof.kt`);
if (!gradle.includes('"src/main/java/com/riftos/app/RiftCoreAdminRollbackProof.kt"')) {
  fail('C1.4-C1 mandatory Core rollback source missing from Gradle');
}
for (const marker of [
  'const val SCHEMA = "riftos.core.admin-rollback-proof/1"',
  'const val OPERATION = "system.fs.write"',
  'const val TARGET = "/C:/RiftOS/.c14c-rollback.txt"',
  'RiftVolumePaths.resolveRelative(TARGET)',
  'check(parent.isDirectory)',
  'prefs.edit().putBoolean(PENDING, true).commit()',
  'file.createNewFile()', 'output.fd.sync()',
  'file.readBytes().contentEquals(MARKER)',
  'file.delete()', 'prefs.edit().remove(PENDING).commit()',
  'fun recover(context: Context)', 'fun writeAndRollback(context: Context)',
  '"rolledBack", true', '"canaryExists"',
  '"generalAdminEffectsEnabled", false'
]) if (!c14Rollback.includes(marker)) fail(`C1.4-C1 reversible Core canary + journal missing: ${marker}`);
for (const marker of [
  'RiftCoreAdminRollbackProof.OPERATION',
  'RiftCoreAdminRollbackProof.TARGET',
  'fun executeRollbackProof(', 'ticket(context, callerPid, bearer)',
  'shell.optBoolean("foregroundLease", false)',
  'tickets.remove(bearer)',
  'RiftCoreAdminRollbackProof.writeAndRollback(context)',
  'fun revokeForWindowClose(',
  '"rolled-back"', '"failed"'
]) if (!(c14Tickets + c14Policy).includes(marker)) {
  fail(`C1.4-C1 exact-scope Core authorization/revocation/audit missing: ${marker}`);
}
if (!coreApplication.includes('RiftCoreAdminRollbackProof.recover(this)') ||
    !coreRuntime.includes('"adminRollbackProof", RiftCoreAdminRollbackProof.status(context)') ||
    !coreIpcProvider.includes('"execute-rollback-proof" -> RiftCoreAdminConsent.executeRollbackProof(') ||
    !coreIpcProvider.includes('"window-closed" -> RiftCoreAdminConsent.revokeForWindowClose(') ||
    !adminUiAllowedActions.includes('"execute-rollback-proof"') ||
    !adminUiAllowedActions.includes('"window-closed"')) {
  fail('C1.4-C1 Core interrupted transaction recovery, authenticated Binder method or introspection missing');
}
for (const marker of [
  'Toggle isolated rollback proof scope',
  'Execute Core write and rollback once',
  '.setTitle("RiftOS administrator consent — fixed-scope only")',
  'client.adminConsent("execute-rollback-proof"',
  'core?.adminConsent("window-closed")'
]) if (!c14AdminUi.includes(marker)) fail(`C1.4-C1 native safe write/rollback consent or close revocation UI missing: ${marker}`);
if (c14Rollback.includes('Process.killProcess(') ||
    c14Rollback.includes('PackageInstaller(') ||
    c14Rollback.includes('runtime.register') ||
    c14Rollback.includes('File(context.filesDir, target)')) {
  fail('C1.4-C1 must never execute generalized privileged operations');
}

// The Core C1.4-C1 effect already executes before its response reaches the
// client: rejecting its valid dedicated schema would falsely report failure
// after the one-use ticket is consumed. Preserve both exact response schemas.
const adminResponse = productionClient
  .split('RiftCoreSurfaceIpcProvider.METHOD_SHELL_ADMIN_CONSENT ->')[1]
  ?.split('else -> SCHEMA')[0]?.replace(/\s+/g, ' ') ?? '';
for (const [action, schema] of [
  ['execute-rollback-proof', 'RiftCoreAdminRollbackProof.SCHEMA'],
  ['execute-registry-proof', 'RiftCoreAdminRegistryProof.SCHEMA'],
  ['discover-providers', '"riftos.core.runtime-candidates/1"']
]) {
  if (!adminResponse.includes(`getString("action") == "${action}") { ${schema} }`)) {
    fail(`C1.4 Core IPC response schema mismatch for ${action}`);
  }
}
if (!adminResponse.includes('} else { RiftCoreAdminConsent.SCHEMA }')) {
  fail('C1.4 original Core consent response schema fallback missing');
}

// C1.4-C2-A: fixed empty provider-registry transaction, not a new provider
// admission path. The real registry is never overwritten, and a Core crash
// leaves a recoverable journal; no RAPP receives runtime.register authority.
const c14Registry = read(`${kotlinDir}/RiftCoreAdminRegistryProof.kt`);
if (!gradle.includes('"src/main/java/com/riftos/app/RiftCoreAdminRegistryProof.kt"')) {
  fail('C1.4-C2-A Core registry proof source missing from Gradle');
}
for (const marker of [
  'const val SCHEMA = "riftos.core.admin-registry-proof/1"',
  'const val OPERATION = "runtime.register"',
  'const val TARGET = "core://runtime-providers/registry.json#empty-c2a"',
  'const val PENDING = "registryPending"',
  'File(base, "system/runtime-providers/registry.json")',
  'getPackageInfo(', 'GET_SIGNING_CERTIFICATES',
  '"providers", JSONArray()', '"installedCoreSignerSha256"',
  'check(!target.exists() && !scratch.exists())',
  'prefs.edit().putBoolean(PENDING, true)',
  'stream.fd.sync()', 'java.nio.file.Files.createLink(target.toPath(), scratch.toPath())',
  'published.getJSONArray("providers").length() == 0',
  'check(file.delete())', '.remove(PENDING).remove(DIR_CREATED).commit()',
  'fun recover(context: Context)', 'fun writeAndRollback(context: Context)',
  '"providerRegistered", false', '"registryRestored", true',
  '"generalRuntimeRegistrationEnabled", false'
]) if (!c14Registry.includes(marker)) fail(`C1.4-C2-A Core signer-stamped empty registry rollback guard missing: ${marker}`);
for (const marker of [
  'RiftCoreAdminRegistryProof.OPERATION', 'RiftCoreAdminRegistryProof.TARGET',
  'fun executeRegistryProof(', 'RiftCoreAdminRegistryProof.writeAndRollback(context)',
  '"isolated-registry-proof"', '"registry-restored"'
]) if (!c14Tickets.includes(marker)) fail(`C1.4-C2-A Core ticket/scope/audit guard missing: ${marker}`);
for (const marker of [
  '"execute-registry-proof" -> RiftCoreAdminConsent.executeRegistryProof(',
  'RiftCoreAdminRegistryProof.recover(this)',
  '"adminRegistryProof", RiftCoreAdminRegistryProof.status(context)',
  'Select isolated runtime registry proof scope',
  'Execute Core empty registry and rollback once',
  'client.adminConsent("execute-registry-proof"'
]) if (!(coreIpcProvider + coreApplication + coreRuntime +
  productionClient + c14AdminUi).includes(marker)) {
  fail(`C1.4-C2-A trusted Core Binder + native UI or interrupted cleanup missing: ${marker}`);
}
if (!adminUiAllowedActions.includes('"execute-registry-proof"')) {
  fail('C1.4-C2-A native admin allowlist excludes registry proof action');
}
if (c14Registry.includes('PackageInstaller(') ||
    c14Registry.includes('killProcess(') ||
    c14Registry.includes('addProvider(') ||
    c14Registry.includes('providers", JSONArray().put(')) {
  fail('C1.4-C2-A MUST remain a no-provider reversible registry proof');
}

// C1.4-C2-B1: READ-ONLY Android-attested runtime candidate discovery.
// Enrollment and signer-pinned registry mutations are intentionally NOT
// authorized by this checkpoint; actual B2 admission needs new exact tickets.
const c14Discover = read(`${kotlinDir}/RiftExternalRuntimeProviders.kt`);
const c14DiscoveryBody = c14Discover.split('fun discoverCandidates(): JSONObject')[1]
  ?.split('private fun installedSignerSha256(')[0] ?? '';
for (const marker of [
  'fun discoverCandidates(): JSONObject',
  'PackageManager.GET_META_DATA',
  'meta.getString("riftos.runtime.provider.id")',
  'meta.getString("riftos.runtime.executor.kind")',
  'kind !in RiftAppExecutionKind.SUPPORTED',
  'installedSignerSha256(pkg)', 'verifyInstalled(provider)',
  'joinToString("\\u0000")',
  '"admissionTarget", "core://runtime-providers/admit/$digest"',
  'require(found.size <= MAX_PROVIDERS)',
  '"enrollmentEnabled", false',
  '"registryModified", false'
]) if (!c14Discover.includes(marker)) {
  fail(`C1.4-C2-B1 bounded read-only runtime candidate discovery missing: ${marker}`);
}
if (c14DiscoveryBody.includes('writeText(') ||
    c14DiscoveryBody.includes('FileOutputStream(') ||
    c14DiscoveryBody.includes('PackageInstaller(') ||
    c14DiscoveryBody.includes('bindService(') ||
    c14DiscoveryBody.includes('registerProvider(')) {
  fail('C1.4-C2-B1 discovery must not mutate or execute an installed provider');
}
for (const marker of [
  '"discover-providers" -> RiftCoreAdminConsent.discoverProviderCandidates(ctx, caller)',
  'fun discoverProviderCandidates(context: Context, callerPid: Int)',
  'authenticated(context, callerPid)',
  'RiftCoreRuntime.runtimes(context).discoverCandidates()',
  'extras?.getString("action") == "discover-providers"',
  '"riftos.core.runtime-candidates/1"',
  'Discover installed runtime candidates (read-only)',
  'client.adminConsent("discover-providers")'
]) if (!(coreIpcProvider + c14Tickets + productionClient + c14AdminUi)
  .includes(marker)) {
  fail(`C1.4-C2-B1 trusted read-only Core discovery/native UI gate missing: ${marker}`);
}
if (!adminUiAllowedActions.includes('"discover-providers"')) {
  fail('C1.4-C2-B1 native admin action allowlist excludes read-only discovery');
}

// Historical Core/alternate client invariants remain; C1.3-D extends them.

// C1.2-B2-B2: Core lease authorization gates admission AND queued delivery.
// Input tickets capture the original focus revision so a refocus cannot
// revive an input that was pending while another window owned focus.
for (const marker of [
  'fun requireCurrentLease(appId: String, attachmentGeneration: Long)',
  'RAPP Core focused input requires current focus lease',
  'focusEnforcedForInput", true'
]) if (!coreInputFocus.includes(marker)) fail(`C1.2-B2-B2 enforced focus lease missing: ${marker}`);
for (const marker of [
  'val admittedFocusRevision: Long? = null',
  'private fun isFocusedInput(kind: Int): Boolean',
  'RiftAppAbi.EventKind.POINTER_DOWN',
  'RiftAppAbi.EventKind.KEY_DOWN',
  'inputFocus.requireCurrentLease(attachment.record.id, attachment.token)',
  'fun authorizeQueuedEventDispatch(attachment: Attachment, ticket: EventTicket)',
  'ticket.admittedFocusRevision == inputFocus.current()?.revision',
  'return attachment.record.offer(event, focusRevision)'
]) if (!coreSessions.includes(marker)) fail(`C1.2-B2-B2 focused input ticket authority missing: ${marker}`);
for (const marker of [
  'sessions.authorizeQueuedEventDispatch(entry.attachment, ticket)',
  'finish(entry, ticket, denial.message',
  'sessions.finishEvent(entry.attachment, ticket)'
]) if (!coreAppLifecycle.includes(marker)) fail(`C1.3-C Core queued-input settlement missing: ${marker}`);

// C1.2-B2-A: Core owns generic input-kind and target authorization.
// The graphical shell may request an input event, never forge effect-result events.
for (const required of [
  'private fun authorizeInputTarget(',
  'RiftAppAbi.EventKind.HOST_EFFECT_RESULT',
  'attachment.record.adapter.supportsEventKind(event.kind)',
  'RiftAppAbi.EventKind.ACTION -> RiftAppAbi.NodeKind.ACTION',
  'RiftAppAbi.EventKind.TEXT_INPUT -> RiftAppAbi.NodeKind.TEXT_INPUT',
  'surface.attachmentGeneration == attachment.token',
  'it.id == event.targetId',
  'authorizeInputTarget(attachment, event)'
]) if (!coreSessions.includes(required)) fail(`C1.2-B2-A Core input authorization missing: ${required}`);
for (const required of [
  'runCatching {',
  'coreLifecycle.offerEvent(session.id, session.generation, event)',
  'Core rejected application input'
]) if (!rappHost.includes(required)) fail(`C1.3-C shell input rejection missing: ${required}`);

// C1.1-P: package manager is Core authority; graphical Installed Apps is only a client.
const corePackageEvents = read(`${kotlinDir}/RiftCorePackageEvents.kt`);
const corePackageGrants = read(`${kotlinDir}/RiftCorePackageGrants.kt`);
for (const required of [
  'object RiftCorePackageEvents', 'riftos.core.packages.change/1',
  'fun subscribe(', 'fun unsubscribe(', 'fun publish(',
  '"installed"', '"updated"', '"uninstalled"',
]) if (!corePackageEvents.includes(required)) fail(`C1.1-P Core package event missing: ${required}`);
for (const required of [
  'object RiftCorePackageGrants', 'setting:permissions:$id',
  'prefs.edit().remove(key).commit()',
]) if (!corePackageGrants.includes(required)) fail(`C1.1-P grant revocation missing: ${required}`);
for (const required of [
  'fun uninstall(id: String)', 'readInstalledMetadata(id)',
  'isManagedRapp(target, id)', 'target.renameTo(quarantine)',
  'RiftCorePackageGrants.revokeAll(appContext, id)',
  'RiftCoreRuntime.sessions(appContext).invalidateInstalled(id)',
  'RiftCorePackageEvents.publish(id, "uninstalled")',
  'RiftCorePackageEvents.publish(id, if (replacing) "updated" else "installed")',
  'uninstalled-cleanup-pending', 'Files.isSymbolicLink('
]) if (!rappManager.includes(required)) fail(`C1.1-P Core package operation missing: ${required}`);
if (rappManager.includes('RiftRappHost')) fail('Core package manager must not call graphical RAPP host');
if (!corePackageEvents.includes('object RiftCoreAppLaunchRequests') ||
    !corePackageEvents.includes('riftos.core.app-launch/1') ||
    !rappManager.includes('RiftCoreAppLaunchRequests.requestLaunch(id)') ||
    !rappManager.includes('RiftCoreRuntime.lifecycle(appContext).start(id)') ||
    !rappManager.includes('presentationDispatched') ||
    rappManager.includes('no-live-riftos-host') ||
    !rappHost.includes('RiftCoreAppLaunchRequests.subscribe') ||
    !rappHost.includes('RiftCoreAppLaunchRequests.unsubscribe')) {
  fail('C1.1-P generic Core launch request/replaceable shell subscription missing');
}
for (const source of [corePackageEvents, corePackageGrants]) {
  if (/\b(?:Activity|View|RiftNativeDesktop|RiftNativeSystemApps|RiftRappHost)\b/.test(
    stripCodeComments(source)
  )) fail('Core package service must not import graphical shell classes');
}
for (const required of [
  'RiftCorePackageEvents.subscribe', 'RiftCorePackageEvents.unsubscribe',
  'desktop.window.close'
]) if (!rappHost.includes(required)) fail(`C1.1-P shell package subscriber missing: ${required}`);
for (const required of [
  '"installed-apps"', 'openInstalledApps()', 'RiftCorePackageEvents.subscribe',
  'uninstallRapp(id)', 'installRapp(path)', 'AlertDialog.Builder(activity)',
]) if (!nativeSystemApps.includes(required)) fail(`C1.1-P Installed Apps window missing: ${required}`);
if (!main.includes('add("installed-apps", "Installed Apps",')) {
  fail('Installed Apps not present in native launcher');
}
if (!desktop.includes('LauncherApp("installed-apps", "Installed Apps",')) {
  fail('Installed Apps not present in native desktop fallback');
}
for (const file of ['RiftCorePackageEvents.kt', 'RiftCorePackageGrants.kt']) {
  if (!gradle.includes(`"src/main/java/com/riftos/app/${file}"`)) {
    fail(`C1.1-P mandatory Core package source missing: ${file}`);
  }
}
if (!platformBuild.includes('"uninstall-rapp" ->') ||
    !platformBuild.includes('rappManager.uninstall(id)')) {
  fail('C1.1-P shell uninstall must route to Core package manager');
}

// C0.2.5 generic external runtime-provider boundary. No project-specific
// package identity or runtime engine enters the RAPP host.
const runtimeProviders = read(`${kotlinDir}/RiftExternalRuntimeProviders.kt`);
for (const required of [
  'riftos-runtime-providers/1', 'riftos-runtime-exec/1',
  'com.riftos.runtime.EXECUTE_V1', 'riftos.runtime.provider/1',
  'signerSha256', 'GET_SIGNING_CERTIFICATES', 'verifyInstalled(',
  'bindService(', 'TimeUnit.MILLISECONDS', 'outputCapacity.coerceAtMost(MAX_RETURN_BYTES)',
  '?: return fallback()', 'registry.json',
]) if (!runtimeProviders.includes(required)) fail(`runtime-provider boundary missing: ${required}`);
for (const required of [
  'coreLifecycle.openForShell(id)',
  'coreLifecycle.offerEvent(session.id, session.generation, event)'
]) if (!rappHost.includes(required)) fail(`RAPP host Core execution delegation missing: ${required}`);
for (const required of [
  'RiftCoreRuntime.runtimes(app)', 'providers.execute(',
]) if (!coreExecutor.includes(required)) fail(`Core runtime provider dispatch missing: ${required}`);
if (!manifest.includes('<action android:name="com.riftos.runtime.EXECUTE_V1" />')) {
  fail('generic external Android runtime provider visibility action missing');
}
if (!gradle.includes('"src/main/java/com/riftos/app/RiftExternalRuntimeProviders.kt"')) {
  fail('external runtime provider is not Gradle-mandatory');
}
for (const required of ['"runtime-status" -> runtimeProviders.status()', 'RiftCoreRuntime.runtimes(appContext)']) {
  if (!platformBuild.includes(required)) fail(`generic runtime status command missing: ${required}`);
}
for (const retiredPkg of ['com.riftpp.editor', 'com.codynex.editor']) {
  if (runtimeProviders.includes(retiredPkg)) fail(`provider registry hardcodes retired project identity: ${retiredPkg}`);
}

// C0.2 removes mirrored editor implementations and their editor-only native ABI.
for (const retiredEditorRoot of [
  'android/app/src/main/java/com/codynex',
  'android/app/src/main/java/com/riftpp',
  'android/app/src/main/cpp/editor',
  `${kotlinDir}/RiftRappRiftppAdapter.kt`,
]) {
  if (exists(retiredEditorRoot)) fail(`project editor source returned: ${retiredEditorRoot}`);
}
for (const staleEditorGate of [
  'verifyCodynexEditorPayload', 'verifyRiftppEditorPayload',
  'src/main/cpp/editor/', 'src/main/java/com/codynex/', 'src/main/java/com/riftpp/',
  'RiftRappRiftppAdapter.kt',
]) {
  if (gradle.includes(staleEditorGate)) fail(`obsolete editor build gate returned: ${staleEditorGate}`);
}
// C0.1: editor command proxies are third-party project code, not OS services.
// An orphaned client must be removed, not exempted from manifest reachability.
for (const legacyClient of ['RiftCodynexEditorBridgeClient.kt', 'RiftppEditorBridgeClient.kt']) {
  if (exists(`${kotlinDir}/${legacyClient}`) || gradle.includes(legacyClient)) {
    fail(`retired project editor bridge client returned: ${legacyClient}`);
  }
}

// Retired RiftCLI must not regain native, shell, ToolHost or sandbox authority.
for (const obsolete of [
  `${kotlinDir}/RiftCliHost.kt`,
  'android/app/src/main/cpp/riftcli/rift_cli_core.cpp',
  'android/app/src/main/cpp/riftcli/rift_cli_core.h',
  'android/app/src/main/cpp/riftcli/rift_cli_jni.cpp',
]) if (exists(obsolete)) fail(`retired RiftCLI source returned: ${obsolete}`);
if (gradle.includes('RiftCliHost.kt') || gradle.includes('libriftcli') ||
    cliCmake.includes('riftcli') ||
    nativeShell.includes('rift-cli') ||
    toolHost.includes('RiftCliExecutionGate') ||
    toolSandbox.includes('RiftCliExecutionGate') ||
    toolSandbox.includes('submitCliJob(') ||
    toolSandbox.includes('executeCliBatchRequest(')) fail('retired RiftCLI execution surface returned');
// The previous CLI retirement briefly left Kotlin compilation broken even though the
// source-only checks passed. Lock independent host ownership and shell cleanup explicitly.
if (!toolHost.includes('private val appContext = context.applicationContext') ||
    !toolHost.includes('private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)') ||
    !toolHost.includes('private val sandbox: RiftToolSandbox') ||
    !toolHost.includes('sandbox = RiftToolSandbox(appContext)') ||
    !toolHost.includes('import java.util.concurrent.atomic.AtomicBoolean')) {
  fail('MCP ToolHost common Kotlin fields or callback import were removed');
}
if (/\b(?:bypassAccess|trusted-cli)\b/.test(toolHost) ||
    /\b(?:cancelAllCliShellJobs|cliWorker)\b/.test(nativeShell)) {
  fail('retired CLI Kotlin references or privileged authorization bypass remain');
}
if (!nativeShell.includes('cancelAllShellJobs("Native RiftShell closed")') ||
    !nativeShell.includes('shellJobWorker.shutdownNow()') ||
    !toolHost.includes('if (!isAllowed(name, args))') ||
    !toolHost.includes('if (!isAllowed(name, normalizedArgs))')) {
  fail('native shell shutdown or MCP access enforcement regressed');
}
if (!toolHost.includes('"rift_local_agent_batch"') ||
    !localAgentBatch.includes('MAX_STEPS = 16') ||
    !localAgentBatch.includes('RiftLocalAgentExecutionGate') ||
    !localAgentBatch.includes('RiftOsLocalAgent.execute(executionContext, request, job.id)') ||
    !toolSandbox.includes('executeLocalAgentBatchRequest')) fail('Local Agent batch execution boundary drifted');
if (!gradle.includes('RiftMcpEventBus.kt') ||
    !runtime.includes('fun mcpEvents(): RiftMcpEventBus') ||
    !runtime.includes('RiftMcpEventBus(debugHub())') ||
    !runtime.includes('RiftMcpRelayClient(') ||
    !cliEvents.includes('class RiftMcpEventBus(') ||
    !cliEvents.includes('MAX_EVENTS = 256') ||
    !cliEvents.includes('MAX_EVENT_BYTES = 96 * 1024') ||
    !cliEvents.includes('stepKey = extra?.optString("stepId")') ||
    !cliEvents.includes('debugHub?.sink("mcp.event-bus")') ||
    !relayClient.includes('mcpEvents.addListener(') ||
    !relayClient.includes('debugHub?.sink("mcp.relay")') ||
    !relayClient.includes('"cli.replay.request"') ||
    !relayClient.includes('"cli.ack"') ||
    !relayWorker.includes('acceptWebSocket(server, ["driver"])') ||
    relayWorker.includes('ctx.storage')) fail('MCP bounded relay event bus contract drifted');
// The relay's cli.* envelope/schema keys are external wire compatibility, not RiftCLI execution.
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

for (const required of [
  'Inspector edit payload is limited to 256 KiB UTF-8',
  'Base64.decode(encoded, Base64.DEFAULT)',
  "Target is not an editable text control",
  'Sensitive form controls cannot be edited',
  'replaceContentEditable',
  'insertReplacementText',
  'editorKind:editorKind(el)',
]) if (!browserEngine.includes(required)) fail(`RiftBrowser bounded editor bridge is missing ${required}`);
for (const required of [
  'browser-inspect edit <selector> <text>',
  'browser-inspect edit-b64 <selector> <base64-utf8>',
  'request.action="edit"',
  'request.textBase64=args.shift()',
]) if (!riftosJs.includes(required)) fail(`RiftBrowser editor shell surface is missing ${required}`);

if (!nativeShell.includes('class RiftNativeShell(context: Context) : RiftShellExecutor')) fail('native RiftShell executor is missing');
if (!nativeShell.includes('.put("webViewRequired", false)')) fail('native RiftShell does not explicitly report WebView-free execution');
// C0.1: individual editor shells are not OS built-ins. The reusable OS job,
// filesystem, generic compiler, and RAPP facilities remain platform-owned.
if (/"(?:riftpp-editor|codynex-editor)"\s*->|execute(?:Riftpp|Codynex)EditorCommand|usage: (?:riftpp-editor|codynex-editor)/.test(nativeShell)) {
  fail('project-owned editor command parser returned to RiftShell');
}
for (const required of ['"riftbuild" ->', '"qjs" ->', '"git" ->', '"ps" ->', '"apps" ->', '"open" ->', '"permissions" ->', 'cancelAllShellJobs("Native RiftShell closed")']) {
  if (!nativeShell.includes(required)) fail('generic OS shell command was lost during editor ownership cleanup: ' + required);
}
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
