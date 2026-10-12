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
const gradle=fs.readFileSync(new URL('android/app/build.gradle.kts',root),'utf8');
assert.ok(!gradle.includes('external-components/'));
console.log('External Core manual-trial source contract PASS: dual runtime, async Core-owned grants and signed host capability bridges. Physical trial, native-buffer behavior and recovery not accepted.');
