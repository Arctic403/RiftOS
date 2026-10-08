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
for (const required of [
  'class RiftCoreAppSessions', 'riftos.core.sessions/1',
  'fun attach(', 'fun detach(attachment: Attachment)',
  'fun close(attachment: Attachment)', 'fun isAttached(attachment: Attachment)',
  'fun programSnapshot()', 'fun commitState(value: ByteArray)',
  'fun nextEventSequence()', 'fun list(): JSONObject',
  '"headlessExecution", false', '"appExecutionIndependentOfDesktop", false',
]) if (!coreSessions.includes(required)) fail(`Core RAPP session contract missing: ${required}`);
if (/\b(?:Activity|View|RiftNativeDesktop|RiftNativeShell|RiftRappHost)\b/.test(
  stripCodeComments(coreSessions)
)) fail('Core session registry must not depend on desktop/UI implementation classes');
for (const required of [
  'RiftCoreRuntime.sessions(activity.applicationContext)',
  'coreSessions.attach(payload, adapter)',
  'coreSessions.detach(it.coreAttachment)',
  'coreSessions.close(session.coreAttachment)',
  'coreExecutor.executeChained(',
]) if (!rappHost.includes(required)) fail(`RAPP desktop attachment migration missing: ${required}`);
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
  'RiftCoreRuntime.appExecutor(activity.applicationContext)',
  'coreExecutor.executeChained('
]) if (!rappHost.includes(required)) fail(`C1.1-B1 RAPP UI still owns execution: ${required}`);
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
  '"eventQueueOwner", "riftos-core"', '"headlessExecution", false',
]) if (!coreSessions.includes(required)) fail(`C1.1-B2-A Core FIFO contract missing: ${required}`);
for (const required of [
  'coreSessions.offerEvent(session.coreAttachment, event)',
  'coreSessions.finishEvent(session.coreAttachment, ticket)',
  'session.pendingUiCompletions', 'dispatchCoreEvent(session, offered.ticket)',
]) if (!rappHost.includes(required)) fail(`C1.1-B2-A UI/Core ticket contract missing: ${required}`);
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
  'coreSurfaces.snapshot(session.id)',
  'it.attachmentGeneration == session.coreAttachment.token',
  'frame = surface?.frame',
  'Core application surface unavailable for current attachment'
]) if (!rappHost.includes(required)) fail(`C1.2-B1 shell surface client missing: ${required}`);
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
if (!activityFocusClient.includes('requestFocusFromShell(id)') ||
    !nativeShell.includes('"focus" -> RiftCoreRuntime.sessions(appContext).focusStatus()') ||
    !gradle.includes('"src/main/java/com/riftos/app/RiftCoreInputFocus.kt"') ||
    !coreRuntime.includes('.put("inputFocus", sessions(context).focusStatus())')) {
  fail('C1.2-B2-B1 Core focus wiring, diagnostic or exact mandatory source missing');
}

// C1.2-C1: installed RAPP BOOT/stop runs in Core without a RiftShell
// window, Activity or graphical subscriber. Device proof still pending.
const coreAppLifecycle = read(`${kotlinDir}/RiftCoreAppLifecycle.kt`);
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
  'coreSessions.authorizeQueuedEventDispatch(session.coreAttachment, ticket)',
  'val denial = runCatching {',
  'session.pendingUiCompletions.remove(ticket.id)?.invoke(',
  'coreSurfaces.snapshot(session.id)?.takeIf {'
]) if (!rappHost.includes(marker)) fail(`C1.2-B2-B2 queued input settlement missing: ${marker}`);

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
  'coreSessions.offerEvent(session.coreAttachment, event)',
  'Core rejected application input'
]) if (!rappHost.includes(required)) fail(`C1.2-B2-A shell input rejection handling missing: ${required}`);

// C1.1-P: package manager is Core authority; graphical Installed Apps is only a client.
const corePackageEvents = read(`${kotlinDir}/RiftCorePackageEvents.kt`);
const corePackageGrants = read(`${kotlinDir}/RiftCorePackageGrants.kt`);
const rappManager = read(`${kotlinDir}/RiftRappManager.kt`);
const nativeSystemApps = read(`${kotlinDir}/RiftNativeSystemApps.kt`);
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
  'RiftCoreRuntime.appExecutor(activity.applicationContext)', 'coreExecutor.executeChained(',
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
