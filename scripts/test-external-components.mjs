import assert from 'node:assert/strict';
import fs from 'node:fs';
const dir = new URL('../external-components/', import.meta.url);
const root = new URL('../', dir);
const read = name => fs.readFileSync(new URL(name, dir), 'utf8');
const host = fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftHostComponentAbiV1.kt', root),'utf8')
 + fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftShellComponentAbiV1.kt', root),'utf8');
const stub=read('HostAbiCompileOnly.kt'), core=read('IndependentCoreV1.kt'), shell=read('IndependentGraphicalShellV1.kt'), engine=read('IndependentRappSessionsV1.kt'), producer=read('runtime.js');
const names=['RiftCoreComponentV1','RiftCoreExecutionViewV1','RiftShellGraphicalComponentV1','RiftShellPlatformServicesV1'];
function iface(text,name) {
 const start=text.indexOf('interface '+name+' {'); assert.ok(start>=0,'missing interface '+name);
 let open=text.indexOf('{',start),n=1,end=open+1;
 while(n&&end<text.length){if(text[end]==='{')n++;if(text[end]==='}')n--;end++;}
 const block=text.slice(open+1,end-1);
 return [...block.matchAll(/fun\s+(\w+)\s*\(([^)]*)\)\s*(?::\s*([A-Za-z0-9?<>.]+))?/g)]
 .map(x=>x[1]+':'+x[2].replace(/\s/g,'')+':'+(x[3]||'Unit')).sort();
}
for(const name of names) assert.deepEqual(iface(stub,name),iface(host,name),'ABI drift '+name);
assert.match(core,/RiftCoreComponentV1,\s*RiftCoreExecutionViewV1/);
assert.match(shell,/:\s*RiftShellGraphicalComponentV1/);
assert.match(core,/SHA-256/);
assert.match(core,/return sessions\(context\)\.open\(id\)/);
assert.match(engine,/import com\.riftos\.app\.RiftHostQuickJsV1/);
assert.match(engine,/class IndependentRuntimeRegistry/);
assert.match(engine,/RiftHostQuickJsV1\.abiVersion\(\)/);
assert.match(engine,/RiftHostQuickJsV1\.execute\(runtime, input, outputLimit\)/);
assert.match(engine,/import com\.riftos\.app\.RiftHostNativeBufferV1/);
assert.match(engine,/RiftHostNativeBufferV1\.abiVersion\(\)/);
assert.match(engine,/RiftHostNativeBufferV1\.execute\(context, runtime, input, outputLimit\)/);
assert.match(engine,/s\.vm\.execute\(s\.adapter\.executorKind, s\.runtime, envelope, MAX_OUTPUT\)/);
assert.doesNotMatch(engine,/DexClassLoader|APPROVED_VM_SHA|IndependentJavascriptVmV1/);
assert.match(engine,/class IndependentRappSessions/);
// Hosted APK composition chains hundreds of read/write host effects;
// 16 continuations was an accidental usability limit, not a stack bound.
// Keep a large *finite* async chain budget while preserving single-effect
// ordering, bounded queue, and monotonically increasing effect tickets.
assert.match(engine,/private const val MAX_DEPTH = 4096/);
assert.match(engine,/require\(ticket\.depth < MAX_DEPTH\)/);
assert.match(engine,/ticket\.depth \+ 1/);
assert.match(engine,/serial\.execute \{ drain\(s\) \}/);
assert.match(engine,/fun offer\(id: String, generation: Long, payload: JSONObject\)/);
assert.match(engine,/private fun dispatch\(s: Session, ticket: Ticket\)/);
assert.match(engine,/private fun validateFrame\(frame: JSONObject\)/);
assert.match(engine,/require\(s\.queue\.size < MAX_QUEUE\)\s*\{\s*"Core input queue full"\s*\}/);
assert.match(engine,/effects\.executeAsync\(s\.id, s\.name, s\.permissions, effect,/);
assert.match(engine,/s\.effectPending = true/);
assert.match(engine,/s\.effectPending = false/);
assert.match(engine,/RiftAppExecutionKind\.NATIVE_BUFFER/);
assert.match(engine,/RiftHostNativeBufferV1\.execute\(context, runtime, input, outputLimit\)/);
assert.match(engine,/private const val MAX_PROGRAM = 1024 \* 1024/);
assert.match(engine,/rawProgram\.size <= MAX_PROGRAM/);
assert.match(engine,/nextState == null \|\| nextState\.size <= MAX_PROGRAM/);
assert.match(engine,/bytes\.size <= MAX_PROGRAM/);
assert.match(engine,/require\(kind in 1\.\.10 && target >= 0\)/);
assert.match(engine,/if \(kind in 1\.\.7\)/);
assert.match(engine,/Interactive input requires focused RAPP and positive target/);
assert.match(engine,/Host lifecycle event cannot target an app widget/);
assert.match(engine,/apkOwnedCoreExecutorFallbackEnabled", false/);
const hostQuickJs=host.slice(host.indexOf('object RiftHostQuickJsV1 {'), host.indexOf('/** E0 shell-side'));
const stubQuickJs=stub.slice(stub.indexOf('object RiftHostQuickJsV1 {'),stub.indexOf('interface RiftShellGraphicalComponentV1'));
for(const t of [hostQuickJs,stubQuickJs]) {
 assert.match(t,/fun abiVersion\(\): Int = 1/);
 assert.match(t,/fun execute\(runtime: ByteArray, input: ByteArray, outputLimit: Int\): ByteArray/);
}
assert.match(hostQuickJs,/private val engine = RiftRappQuickJsExecutor\(\)/);
assert.match(hostQuickJs,/engine\.execute\(runtime, input, outputLimit\)/);
const embeddedExecutor=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftCoreAppExecutor.kt',root),'utf8');
assert.match(embeddedExecutor,/RiftHostQuickJsV1\.execute\(runtime, envelope, OUTPUT_BYTES\)/);
assert.match(embeddedExecutor,/RiftHostNativeBufferV1\.execute\(app, runtime, envelope, OUTPUT_BYTES\)/);
assert.doesNotMatch(embeddedExecutor,/private val quickJs = RiftRappQuickJsExecutor\(\)/);
assert.doesNotMatch(hostQuickJs,/RiftCoreRuntime|RiftCoreAppExecutor|RiftRappCapabilityBroker/);
const verifier=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftProtectedDexVerifier.kt',root),'utf8');
assert.match(verifier,/Lcom\/riftos\/app\/RiftHostQuickJsV1;/);
assert.match(verifier,/Lcom\/riftos\/app\/RiftHostNativeBufferV1;/);
assert.match(verifier,/Lcom\/riftos\/app\/RiftHostCapabilityUiV1;/);
assert.match(verifier,/Lcom\/riftos\/app\/RiftHostBuildLocalV1;/);
assert.match(host,/object RiftHostCapabilityUiV1/);
assert.match(host,/RiftCoreShellCapabilityRequests\.requestConsent\(/);
assert.match(host,/RiftCoreShellCapabilityRequests\.requestUiEffect\(/);
assert.match(host,/object RiftHostBuildLocalV1/);
assert.match(host,/RiftLocalBuildCapability\(context\.applicationContext\)/);
assert.match(stub,/object RiftHostCapabilityUiV1/);
assert.match(stub,/object RiftHostBuildLocalV1/);
assert.match(core,/authorizeGrant = \{ id, current, persist ->/);
assert.match(producer,/'RiftHostCapabilityUiV1\.class'/);
assert.match(producer,/'RiftHostBuildLocalV1\.class'/);
const nativeHost = host.slice(host.indexOf('object RiftHostNativeBufferV1 {'),
  host.indexOf('/** E0 shell-side'));
const nativeStub = stub.slice(stub.indexOf('object RiftHostNativeBufferV1 {'),
  stub.indexOf('interface RiftShellGraphicalComponentV1'));
for (const t of [nativeHost, nativeStub]) {
  assert.match(t,/fun abiVersion\(\): Int = 1/);
  assert.match(t,/fun execute\(context: Context, runtime: ByteArray, input: ByteArray,/);
}
assert.match(nativeHost,/RiftNativeBufferCompilerService\.compile\(/);
assert.doesNotMatch(nativeHost,/RiftCoreRuntime|RiftCoreAppExecutor|RiftRappCapabilityBroker/);
const coreLoader=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftCoreCandidateSwitch.kt',root),'utf8');
assert.match(coreLoader,/val ownCore = name\.startsWith\(PREFIX\)/);
assert.match(coreLoader,/val kotlinSupport = name\.startsWith\("kotlin\."\)/);
assert.match(coreLoader,/if \(ownCore\) \{[\s\S]*?application\.classLoader\.loadClass\(name\)/);

// #730 device trace: staged graphical Shell DEX is valid, but the boot-time
// Shell class scan rejected the kotlin-stdlib.jar classes bundled by D8.
// Keep the executable Shell namespace strict and mirror the protected DEX
// verifier and already-working external Core's Kotlin support allowance.
const shellLoader=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftShellCandidateSwitch.kt',root),'utf8');
assert.match(verifier,/val kotlinSupport = name\.startsWith\("Lkotlin\/"\)/);
assert.match(shellLoader,/val ownShell = name\.startsWith\(ENTRY_PREFIX\)/);
assert.match(shellLoader,/val kotlinSupport = name\.startsWith\("kotlin\."\)/);
assert.match(shellLoader,/require\(ownShell \|\| kotlinSupport\)/);
assert.match(shellLoader,/if \(ownShell\) \{\s*require\(runCatching \{\s*application\.classLoader\.loadClass\(name\)/);
assert.match(shellLoader,/require\(count <= MAX_CLASSES\)/);
assert.match(shellLoader,/require\(count > 0 && hasEntrypoint\)/);
assert.match(shellLoader,/type\.classLoader === loader/);
const shellAllowedClass = name =>
 name.startsWith('com.riftos.external.shell.') || name.startsWith('kotlin.');
for(const name of [
 'com.riftos.external.shell.IndependentGraphicalShellV1',
 'com.riftos.external.shell.IndependentGraphicalShellV1$Window',
 'kotlin.jvm.internal.Intrinsics',
 'kotlin.collections.CollectionsKt'
]) assert.equal(shellAllowedClass(name),true,'valid Shell DEX class rejected: '+name);
for(const name of [
 'com.riftos.app.RiftShellActivity',
 'com.riftos.external.core.IndependentCoreV1',
 'kotlinx.coroutines.Job', 'kotlinmalicious.Class',
 'java.lang.System', 'android.app.Activity'
]) assert.equal(shellAllowedClass(name),false,'foreign Shell DEX class accepted: '+name);
const recipe=JSON.parse(read('compile-core.json'));
const externalFiles=[
 'ExternalRiftAppAbi.kt','ExternalRiftRappJsonAdapter.kt',
 'ExternalRiftRappRiftppGenericAdapter.kt',
 'ExternalRiftRappRiftppWs15Adapter.kt','ExternalRiftCorePackageEvents.kt',
 'ExternalRiftCorePackageGrants.kt','ExternalRiftVolumePaths.kt',
 'IndependentRappPackagesV1.kt','IndependentRappEffectsV1.kt','IndependentRappSigningV1.kt'
];
const ownership=fs.readFileSync(new URL('docs/SOURCE_OWNERSHIP.md',root),'utf8');
for(const file of externalFiles){
 assert.ok(recipe.sources.includes(file),'not compiled into external Core: '+file);
 assert.ok(producer.includes("'"+file+"'"),'producer must build external Core module: '+file);
 assert.equal(ownership.split('| `external-components/'+file+'` |').length-1,1,
  'maintained source must have exactly one owner: '+file);
 assert.match(read(file),/^package com\.riftos\.external\.core/m,
  'external Core source must not define any APK class');
}
const manager=read('IndependentRappPackagesV1.kt');
assert.match(manager,/class IndependentRappPackages/);
assert.match(manager,/fun install\(artifact: File\): JSONObject/);
assert.match(manager,/fun uninstall\(id: String\): JSONObject/);
assert.match(manager,/RiftCorePackageGrants\.revokeAll\(appContext, id\)/);
assert.doesNotMatch(manager,/RiftCoreRuntime\.|com\.riftos\.app\./);
assert.match(core,/packageManager\(context\)\.install\(file\)/);
assert.match(core,/packageManager\(context\)\.uninstall\(id\)/);
const effects=read('IndependentRappEffectsV1.kt');
assert.match(effects,/class IndependentRappEffects/);
assert.match(effects,/setting:permissions:\$id/);
assert.match(effects,/effect\.capability !in declared/);
assert.match(effects,/Independent platform capability bridge not qualified/);
assert.doesNotMatch(effects,/RiftCoreRuntime\.|RiftRappCapabilityBroker/);
assert.match(effects,/fun executeAsync\(id: String, name: String/);
assert.match(effects,/RiftHostCapabilityUiV1\.consent\(/);
assert.match(effects,/RiftHostCapabilityUiV1\.uiEffect\(/);
assert.match(effects,/RiftHostBuildLocalV1\.execute\(/);
assert.match(effects,/authorizeGrant\(id, stillValid\)/);
assert.match(effects,/TimeUnit\.SECONDS/);
assert.match(manager,/fun withLivePackage\(id: String, stillValid:/);
const signing=read('IndependentRappSigningV1.kt');
assert.match(signing,/AndroidKeyStore/);
assert.match(signing,/riftbuild-apk-v2-rsa-v1/);
assert.match(signing,/signSha256RsaPkcs1/);
assert.match(signing,/verifySha256RsaPkcs1/);
assert.match(engine,/RiftAppAdapters\.require\(metadata\.getString\("adapter"\)\)/);
assert.match(engine,/adapter\.decodeOutput\(raw\)/);
assert.match(engine,/adapter\.encodeEvent\(payload, event, seq\)/);
assert.match(engine,/RiftAppExecutionKind\.QUICKJS/);
assert.match(engine,/adapter\.executorKind in RiftAppExecutionKind\.SUPPORTED/);
assert.match(core,/private const val EXTERNAL_CORE_PROMOTION_PROVEN = false/);
assert.match(core,/EXTERNAL_CORE_MANUAL_DEVICE_TRIAL_ALLOWED = true/);
assert.match(core,/require\(EXTERNAL_CORE_MANUAL_DEVICE_TRIAL_ALLOWED\)/);
assert.match(core,/deviceQualificationComplete", false/);
assert.ok(producer.includes("'IndependentRappSessionsV1.kt'"), 'External Core build must contain its session engine');
assert.match(shell,/services\(\)\.snapshot\(win\.id\)/);
assert.match(shell,/services\(\)\.reattach\(id, gen\)/);
for(const name of [...names,'RiftHostQuickJsV1','RiftHostNativeBufferV1','RiftHostCapabilityUiV1','RiftHostBuildLocalV1']) assert.ok(producer.includes(name+'.class'),'stub not removed '+name);
for(const op of ['toolchainStatus','compilerRun','jvmDex']) assert.ok(producer.includes("'"+op+"'"));
const rapp=JSON.parse(read('riftapp.json'));
assert.deepEqual(rapp.permissions.sort(),['build.local','fs.read','fs.write','window.title'].sort());
for(const kind of ['core','shell']) {
 const manifest=JSON.parse(read(kind+'-component.json'));
 assert.equal(manifest.schema,'riftos.protected-component/1');
 assert.equal(manifest.component,kind);
 assert.match(manifest.sha256,/^[a-f0-9]{64}$/);
 assert.ok(manifest.entrypoint.startsWith('com.riftos.external.'+kind+'.'));
}
// Core v0.7 is a distinct, additive DEX; the proven Core v0.6.1 binary,
 // manually saved Core backup and APK-owned MCP/relay never change.
const coreV062=read('IndependentCoreV062.kt');
const recipeV062=JSON.parse(read('compile-core-v062.json'));
assert.deepEqual(recipeV062.sources,[...JSON.parse(read('compile-core-v061.json')).sources,'IndependentCoreV062.kt']);
assert.equal(recipeV062.outputDir,'build/riftbuild/core-v062-classes');
assert.match(coreV062,/class IndependentCoreV062 : IndependentCoreV1\(\)/);
assert.match(coreV062,/"rappEffectContinuationLimit", 4096/);
assert.ok(producer.includes("coreV062:107"));
assert.ok(producer.includes("trial-v062/"));
assert.ok(producer.includes("'0.6.2'"));
assert.ok(producer.includes("core-v062"));
// APK install is a separate, explicitly granted Core->Shell platform UI effect.
// It may open Android's installer but cannot silently commit installation.
const coreV063=read('IndependentCoreV063.kt');
const coreV064=read('IndependentCoreV064.kt');
const recipeV064=JSON.parse(read('compile-core-v064.json'));
const recipeV063=JSON.parse(read('compile-core-v063.json'));
const sessionEffects=read('IndependentRappEffectsV1.kt');
const externalAbi=read('ExternalRiftAppAbi.kt');
const hostRappAbi=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftAppAbi.kt',root),'utf8');
const uiBridge=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftRemoteShellUiClient.kt',root),'utf8');
const installPaths=fs.readFileSync(new URL('android/app/src/main/res/xml/rift_build_apk_paths.xml',root),'utf8');
const manifest=fs.readFileSync(new URL('android/app/src/main/AndroidManifest.xml',root),'utf8');
assert.deepEqual(recipeV063.sources,[...JSON.parse(read('compile-core-v061.json')).sources,'IndependentCoreV063.kt']);
assert.equal(recipeV063.outputDir,'build/riftbuild/core-v063-classes');
assert.match(coreV063,/"candidateRevision", "0\.6\.3"/);
for(const abi of [externalAbi,hostRappAbi]) {
 assert.match(abi,/const val APK_INSTALL = "apk\.install"/);
 assert.match(abi,/SIGNING_IDENTITY,\s*APK_INSTALL,\s*WINDOW_TITLE/);
}
assert.match(sessionEffects,/effect\.capability == RiftAppAbi\.Capability\.APK_INSTALL/);
assert.match(sessionEffects,/effect\.operation != "open"/);
assert.match(sessionEffects,/apk\.startsWith\("\/D:\/Builds\/"/);
assert.match(sessionEffects,/RiftAppAbi\.Capability\.APK_INSTALL,\s*RiftAppAbi\.Capability\.WINDOW_TITLE/);
assert.match(host,/"share", "apk\.install", "window\.title"/);
assert.match(uiBridge,/RiftAppAbi\.Capability\.APK_INSTALL to "open"/);
assert.match(uiBridge,/FileProvider\.getUriForFile\(/);
assert.match(uiBridge,/Intent\.FLAG_GRANT_READ_URI_PERMISSION/);
assert.match(uiBridge,/APK package identity invalid or attempts to replace RiftOS/);
assert.match(installPaths,/path="riftfs\/documents\/builds\/"/);
assert.match(manifest,/android:name="androidx\.core\.content\.FileProvider"/);
assert.match(manifest,/@xml\/rift_build_apk_paths/);
assert.match(producer,/coreV063:108/);
assert.match(producer,/trial-v063\//);
assert.deepEqual(recipeV064.sources,[...recipeV063.sources,'IndependentCoreV064.kt']);
assert.equal(recipeV064.outputDir,'build/riftbuild/core-v064-classes');
assert.match(coreV063,/open class IndependentCoreV063 : IndependentCoreV1/);
assert.match(coreV064,/class IndependentCoreV064 : IndependentCoreV063\(\), RiftCoreExternalAppConnectionsV1/);
assert.match(coreV064,/"candidateRevision", "0\.6\.4"/);
for(const marker of ['RiftHostExternalAppBinderV1.identity','RiftHostExternalAppBinderV1.execute',
 'signerSha256','Files.isSymbolicLink','External app transfer receipt did not match']) {
 assert.ok(coreV064.includes(marker),'Missing external Core connection invariant: '+marker);
}
assert.match(producer,/coreV064:109/);
assert.match(producer,/trial-v064\//);
assert.match(producer,/'RiftHostExternalAppBinderV1\.class'/);
assert.match(producer,/'RiftCoreExternalAppConnectionsV1\.class'/);
const appBinder=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftHostExternalAppBinderV1.kt',root),'utf8');
const workspaceUi=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftNativeWorkspaceApps.kt',root),'utf8');
const shellActivity=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftShellActivity.kt',root),'utf8');
const mainActivity=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/MainActivity.kt',root),'utf8');
const shellClient=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftShellCoreClient.kt',root),'utf8');
for(const marker of ['fun identity(context: Context','fun execute(context: Context',
 'binder.transact(IBinder.FIRST_CALL_TRANSACTION',
 'binder.transact(IBinder.INTERFACE_TRANSACTION',
 'descriptorReply.readString() == descriptor',
 'context.unbindService(serviceConnection)']) assert.ok(appBinder.includes(marker),'Binder missing '+marker);
assert.match(host,/interface RiftCoreExternalAppConnectionsV1/);
assert.match(host,/fun externalAppConnections\(\): RiftCoreExternalAppConnectionsV1/);
assert.match(stub,/interface RiftCoreExternalAppConnectionsV1/);
assert.match(stub,/object RiftHostExternalAppBinderV1/);
assert.match(verifier,/Lcom\/riftos\/app\/RiftHostExternalAppBinderV1;/);
assert.match(shellClient,/fun externalAppRequest\(request: JSONObject\): JSONObject/);
assert.match(workspaceUi,/fun openConnections\(\): JSONObject/);
assert.match(workspaceUi,/sendFileToConnectedEditor\(display\)/);
// CI compileReleaseKotlin regression from manual run 38333252746:
 // The optional Core connection UI changed this native workspace constructor
 // from two to three arguments; both Activity callers must track it.
assert.match(mainActivity,/RiftNativeWorkspaceApps\(\s*this, nativeDesktop, RiftShellCoreClient\(applicationContext\)\s*\)/);
assert.match(shellActivity,/RiftNativeWorkspaceApps\(this, desktop, core\)/);
assert.match(shellActivity,/Triple\("connections", "App Connections"/);
assert.match(manifest,/<package android:name="com\.codynex\.editor"/);
// Preserve selected Core V1/0.6.3 and authenticated Shell IPC invariants.
assert.match(core,/open class IndependentCoreV1 : RiftCoreComponentV1, RiftCoreExecutionViewV1/);
assert.match(core,/withLiveGrantChange\(context: Context, id: String/);
assert.match(shellLoader,/manualRecoveryOnly/);
const coreIpc=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftCoreSurfaceIpcProvider.kt',root),'utf8');
assert.match(coreIpc,/const val METHOD_SHELL_EXTERNAL_APP = "shell\.external-app"/);
assert.match(coreIpc,/externalAppConnections\(\)\s*\.appConnectionRequest\(ctx, input\)/);
assert.match(coreIpc,/if \(method != METHOD_SNAPSHOT\) requireProductionShellCaller\(\)/);
assert.match(coreIpc,/RiftMcpRuntime\.nativeShell\(ctx\)\.execute\(command, cwd\)/);
assert.doesNotMatch(producer,/core-v07|coreV07|IndependentCoreV07|RiftCoreSystemServicesV1/);
// Shell v2 remains a separately compiled component. System tools use Core's
// authenticated command IPC, but never forge an installed RAPP session.
const shellV2=read('IndependentGraphicalShellV2.kt');
const v1Recipe=JSON.parse(read('compile-shell.json'));
const v2Recipe=JSON.parse(read('compile-shell-v2.json'));
assert.deepEqual(v2Recipe.sources,[
 'HostAbiCompileOnly.kt','IndependentGraphicalShellV2.kt'
]);
assert.ok(!v2Recipe.sources.includes('IndependentGraphicalShellV1.kt'));
assert.notEqual(v2Recipe.outputDir,v1Recipe.outputDir);
assert.match(v2Recipe.outputDir,/shell-v2-classes$/);
assert.match(shellV2,/class IndependentGraphicalShellV2\s*:\s*RiftShellGraphicalComponentV1/);
const v2HostImports=[...shellV2.matchAll(/^import com\.riftos\.app\.(\w+)$/gm)]
 .map(x=>x[1]).sort();
assert.deepEqual(v2HostImports,[
 'RiftShellGraphicalComponentV1','RiftShellPlatformServicesV1'
]);
for(const forbidden of [
 'RiftNativeDesktop','RiftNativeWorkspaceApps','RiftCoreRuntime',
 'RiftRappHost','RiftCoreCandidateSwitch','RiftShellCandidateSwitch'
]) assert.ok(!shellV2.includes(forbidden+'.'), 'V2 must not execute embedded APK owner '+forbidden);
for(const name of [
 'files','terminal','tasks','installed-apps','workspace-live',
 'settings','mcp','admin-permissions','browser','editor','devlab'
]) assert.ok(shellV2.includes('"'+name+'" to '),'V2 system entry missing: '+name);
for(const cmd of [
 'ls ','head ','ps','core status','workspace status','workspace ls',
 'permissions list','sysinfo'
]) assert.ok(shellV2.includes('"'+cmd),'V2 Core command route missing: '+cmd);
assert.match(shellV2,/services\(\)\.execute\(command, "\/"\)/);
assert.match(shellV2,/Thread\(\{[\s\S]*?boundActivity\.runOnUiThread \{/);
assert.match(shellV2,/result\.take\(14000\)/);
assert.match(shellV2,/services\(\)\.installed\(\)/);
assert.match(shellV2,/services\(\)\.open\(id\)/);
assert.match(shellV2,/services\(\)\.snapshot\(win\.id\)/);
assert.match(shellV2,/services\(\)\.reattach\(id, gen\)/);
assert.match(shellV2,/services\(\)\.event\(win\.id, win\.generation/);
assert.match(shellV2,/generation > 0L\) refreshWindow\(win\)/);
assert.match(shellV2,/if \(win\.minimized \|\| win\.generation <= 0L\) continue/);
assert.match(shellV2,/if \(win\.generation > 0L\) \{\s*runCatching \{ services\(\)\.stop\(id, win\.generation\) \}/);
assert.match(shellV2,/id\.takeUnless \{ it\.startsWith\("system:"\) \}/);
assert.match(shellV2,/openWindow\("system:" \+ id, title, 0L\)/);
assert.match(shellV2,/launcher\.add\("@sys:" \+ id to name\)/);
assert.match(shellV2,/if \(id\.startsWith\("@sys:"\)\) \{\s*openSystem\(id\.removePrefix\("@sys:"\), name\)/);
assert.match(shellV2,/require\(!id\.startsWith\("@sys:"\)\)/);
assert.match(shellV2,/id\.startsWith\("@sys:"\)\) continue/);
assert.doesNotMatch(shellV2,/if \(systemEntries\.any \{ it\.first == id \}\)/);
assert.match(shellV2,/services\(\)\.reportDesktop\(JSONObject\(\)/);
assert.match(shellV2,/\.put\("native", true\)/);
assert.match(shellV2,/\.put\("riftShellForeground", resumed\)/);
assert.match(shellV2,/override fun onResume\(\) \{[\s\S]*?ticker\.post\(poll\)[\s\S]*?report\(\)/);
assert.match(shellV2,/\.onFailure \{ android\.util\.Log\.w\(/);
const bridge=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftCoreShellWindowBridge.kt',root),'utf8');
assert.match(bridge,/require\(value\.optBoolean\("native", false\)\)/);
const coreRecovery=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftCoreShellRecovery.kt',root),'utf8');
assert.match(coreRecovery,/state\.optBoolean\("riftShellForeground", false\)/);
assert.match(shellV2,/if \(id\.isBlank\(\) \|\| gen < 1L\) continue/);
assert.match(shellV2,/if \(win\.generation > 0L\)/);
assert.match(shellV2,/if \(id == "mcp"\)/);
assert.match(shellV2,/"com\.riftos\.app\.RiftMcpActivity"/);
assert.doesNotMatch(shellV2,/services\(\)\.respondConsent\(|services\(\)\.installRapp\(|services\(\)\.uninstallRapp\(/);
assert.match(shellV2,/"browser" \|\| id == "editor" \|\| id == "devlab"/);
// Recovery remains a separate pending parity gate: Core filters restored
// system:* IDs, and v2 ignores generation-zero system-window restore.
// Do NOT count external system-window resurrection as device-proven yet.
assert.ok(!coreRecovery.includes('"system:files"'),
 'V2 system window restore is not a completed Core Recovery contract');
const vm=await import('node:vm');
const context={};
vm.runInNewContext(producer,context,{timeout:1200});
assert.equal(typeof context.riftRappMain,'function');
const selected=JSON.parse(context.riftRappMain(JSON.stringify({
 event:{kind:1,targetId:105}
})));
const selectedState=JSON.parse(Buffer.from(selected.stateBase64,'base64').toString('utf8'));
assert.equal(selectedState.mode,'shell-v2');
assert.equal(selected.effect.capability,'build.local');
assert.equal(selected.effect.operation,'toolchainStatus');
const compiled=JSON.parse(context.riftRappMain(JSON.stringify({
 stateBase64:selected.stateBase64,
 event:{kind:13,targetId:selected.effect.requestId,arg0:1,text:JSON.stringify({
  schema:'riftbuild-kotlin-toolchain-status/2',ready:true,dexer:'D8',
  androidJar:{path:'/tmp/android.jar'},kotlinStdlib:{path:'/tmp/kotlin-stdlib.jar'}
 })}
})));
assert.equal(compiled.effect.operation,'compilerRun');
const v2Request=JSON.parse(compiled.effect.text).request;
assert.deepEqual(Array.from(v2Request.sources),v2Recipe.sources);
assert.equal(v2Request.outputDir,v2Recipe.outputDir);
assert.ok(!v2Request.sources.includes('IndependentGraphicalShellV1.kt'));

// Simulate retained v0.6.3 Core build option, without Recovery mutation.
const chosenCore=JSON.parse(context.riftRappMain(JSON.stringify({
 event:{kind:1,targetId:108}
})));
const stateCore=JSON.parse(Buffer.from(chosenCore.stateBase64,'base64').toString('utf8'));
assert.equal(stateCore.mode,'core-v063');
assert.equal(chosenCore.effect.operation,'toolchainStatus');
const requestedCore=JSON.parse(context.riftRappMain(JSON.stringify({
 stateBase64:chosenCore.stateBase64,
 event:{kind:13,targetId:chosenCore.effect.requestId,arg0:1,text:JSON.stringify({
  schema:'riftbuild-kotlin-toolchain-status/2',ready:true,dexer:'D8',
  androidJar:{path:'/tmp/android.jar'},kotlinStdlib:{path:'/tmp/kotlin-stdlib.jar'}
 })}
})));
assert.equal(requestedCore.effect.operation,'compilerRun');
const coreRequest=JSON.parse(requestedCore.effect.text).request;
assert.deepEqual(Array.from(coreRequest.sources),recipeV063.sources);
assert.equal(coreRequest.outputDir,recipeV063.outputDir);
assert.ok(!coreRequest.sources.includes('RiftMcpRuntime.kt'));
// 0.6.4 Core candidate compilation must keep selected 0.6.3 untouched.
const chosen064=JSON.parse(context.riftRappMain(JSON.stringify({event:{kind:1,targetId:109}})));
const state064=JSON.parse(Buffer.from(chosen064.stateBase64,'base64').toString('utf8'));
assert.equal(state064.mode,'core-v064');
assert.equal(chosen064.effect.operation,'toolchainStatus');
const compiled064=JSON.parse(context.riftRappMain(JSON.stringify({
 stateBase64:chosen064.stateBase64,
 event:{kind:13,targetId:chosen064.effect.requestId,arg0:1,text:JSON.stringify({
  schema:'riftbuild-kotlin-toolchain-status/2',ready:true,dexer:'D8',
  androidJar:{path:'/tmp/android.jar'},kotlinStdlib:{path:'/tmp/kotlin-stdlib.jar'}
 })}
})));
assert.equal(compiled064.effect.operation,'compilerRun');
const request064=JSON.parse(compiled064.effect.text).request;
assert.deepEqual(Array.from(request064.sources),recipeV064.sources);
assert.equal(request064.outputDir,recipeV064.outputDir);
assert.match(producer,/k==='shell-v2'\?'v2\/'/);
assert.match(producer,/version:k==='core'\?'0\.6\.1':k==='core-v062'\?'0\.6\.2':k==='core-v063'\?'0\.6\.3':k==='core-v064'\?'0\.6\.4':k==='shell-v2'\?'0\.2\.0'/);
assert.match(producer,/entry:k==='core'\?'com\.riftos\.external\.core\.IndependentCoreV1':k==='core-v062'\?'com\.riftos\.external\.core\.IndependentCoreV062':k==='core-v063'\?'com\.riftos\.external\.core\.IndependentCoreV063':k==='core-v064'\?'com\.riftos\.external\.core\.IndependentCoreV064':k==='shell-v2'\?'com\.riftos\.external\.shell\.IndependentGraphicalShellV2'/);
assert.doesNotMatch(producer,/RiftProtectedComponentInstaller|RiftManualRecoveryStore|acceptProven\(/);
const gradle=fs.readFileSync(new URL('android/app/build.gradle.kts',root),'utf8');
assert.ok(!gradle.includes('external-components/'));
console.log('External Core/V1 and isolated Shell V2 source contract PASS (Core IPC, system menu isolation, real desktop report, Core session boundaries, producer compiler recipe). Signed device V2 boot and system app parity not yet proven.');
