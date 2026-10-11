import assert from 'node:assert/strict';
import fs from 'node:fs';
const dir = new URL('../external-components/', import.meta.url);
const root = new URL('../', dir);
const read = name => fs.readFileSync(new URL(name, dir), 'utf8');
const host = fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftHostComponentAbiV1.kt', root),'utf8')
 + fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/RiftShellComponentAbiV1.kt', root),'utf8');
const stub=read('HostAbiCompileOnly.kt'), core=read('IndependentCoreV1.kt'), shell=read('IndependentGraphicalShellV1.kt'), producer=read('runtime.js');
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
assert.match(core,/unavailable\("BOOT"\)/);
assert.match(shell,/services\(\)\.snapshot\(win\.id\)/);
assert.match(shell,/services\(\)\.reattach\(id, gen\)/);
for(const name of names) assert.ok(producer.includes(name+'.class'),'stub not removed '+name);
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
console.log('4 host ABI mirrors, 2 external sources, 4 stripped stubs and protected manifests: source contract OK (NOT device acceptance)');
