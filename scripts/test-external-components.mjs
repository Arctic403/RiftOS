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
assert.doesNotMatch(engine,/DexClassLoader|APPROVED_VM_SHA|IndependentJavascriptVmV1/);
assert.match(engine,/class IndependentRappSessions/);
assert.match(engine,/fun offer\(id: String, generation: Long, payload: JSONObject\)/);
assert.match(engine,/private fun dispatch\(s: Session, ticket: Ticket\)/);
assert.match(engine,/private fun validateFrame\(frame: JSONObject\)/);
assert.match(engine,/require\(s\.queue\.size < MAX_QUEUE\)\s*\{\s*"Core input queue full"\s*\}/);
assert.match(engine,/External Core capability not independently authorized/);
assert.match(engine,/apk-host-quickjs-v1/);
assert.match(engine,/private const val MAX_PROGRAM = 1024 \* 1024/);
assert.match(engine,/rawProgram\.size <= MAX_PROGRAM/);
assert.match(engine,/nextState\.size <= MAX_PROGRAM/);
assert.match(engine,/bytes\.size <= MAX_PROGRAM/);
assert.match(engine,/require\(kind in 1\.\.12 && target >= 0\)/);
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
assert.doesNotMatch(embeddedExecutor,/private val quickJs = RiftRappQuickJsExecutor\(\)/);
assert.doesNotMatch(hostQuickJs,/RiftCoreRuntime|RiftCoreAppExecutor|RiftRappCapabilityBroker/);
const verifier=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftProtectedDexVerifier.kt',root),'utf8');
assert.match(verifier,/Lcom\/riftos\/app\/RiftHostQuickJsV1;/);
const coreLoader=fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftCoreCandidateSwitch.kt',root),'utf8');
assert.match(coreLoader,/val ownCore = name\.startsWith\(PREFIX\)/);
assert.match(coreLoader,/val kotlinSupport = name\.startsWith\("kotlin\."\)/);
assert.match(coreLoader,/if \(ownCore\) \{[\s\S]*?application\.classLoader\.loadClass\(name\)/);
assert.match(core,/private const val EXTERNAL_CORE_PROMOTION_PROVEN = false/);
assert.match(core,/require\(EXTERNAL_CORE_PROMOTION_PROVEN\)/);
assert.ok(producer.includes("'IndependentRappSessionsV1.kt'"), 'External Core build must contain its session engine');
assert.match(shell,/services\(\)\.snapshot\(win\.id\)/);
assert.match(shell,/services\(\)\.reattach\(id, gen\)/);
for(const name of [...names,'RiftHostQuickJsV1']) assert.ok(producer.includes(name+'.class'),'stub not removed '+name);
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
console.log('4 host interface mirrors, host QuickJS V1 primitive, Core-owned sessions/state, 5 stripped stubs and protected manifests: source contract OK (NOT independent Core device acceptance)');
