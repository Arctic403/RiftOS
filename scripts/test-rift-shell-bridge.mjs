import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../src/riftos.js', import.meta.url), 'utf8');
const shim = source.slice(source.indexOf('// Keep running commands locked'), source.indexOf('\n\nwindow.RiftDesktop'));
const flush = () => new Promise(resolve => setImmediate(resolve));
function runtime(execute, transport) {
  const errors = [];
  const ctx = {window:{RiftShellMcp:{execute}},console:{error:(...args)=>errors.push(args)}};
  if (transport) ctx.RiftNativeTransport = {postMessage:transport};
  vm.runInNewContext(shim,ctx);
  return {bridge:ctx.window.RiftShellMcpNative,errors};
}
let calls = 0, finish;
const sent=[];
const {bridge} = runtime(() => {calls++;return new Promise(resolve=>finish=resolve);},value=>sent.push(value));
assert.equal(bridge.request('{').accepted,false);
assert.equal(bridge.request({id:'a',command:''}).accepted,false);
assert.equal(bridge.request({id:'expired',command:'git push',expiresAt:Date.now()-1}).accepted,false);
assert.equal(bridge.request({id:'a',command:'git push'}).accepted,true);
assert.equal(bridge.request({id:'a',command:'git push'}).accepted,true);
await flush();
assert.equal(calls,1);
assert.equal(bridge.request({id:'retry',command:'git push'}).accepted,false);
assert.equal(bridge.poll('a').state,'running');
finish({ok:true,output:'done'});
await flush();
assert.equal(bridge.poll('a').result.output,'done');
assert.equal(sent.length,1);
assert.equal(bridge.request({id:'next',command:'git push'}).accepted,true);

for (const transport of [undefined,()=>{throw Error('channel down');}]) {
  const {bridge,errors}=runtime(async()=>({ok:true,output:'recovered'}),transport);
  assert.equal(bridge.request({id:'lost',command:'read'}).accepted,true);
  await flush();
  assert.equal(bridge.poll('lost').result.output,'recovered');
  assert.equal(errors.length,1);
}
for (const execute of [()=>{throw Error('sync');},async()=>{throw Error('async');}]) {
  const {bridge}=runtime(execute,()=>{});
  bridge.request({id:'failure',command:'test'});
  await flush();
  assert.equal(bridge.poll('failure').result.ok,false);
  assert.equal(bridge.request({id:'retry',command:'test'}).accepted,true);
  await flush();
}
const nested=runtime(async command=>({ok:true,output:command}),()=>{}).bridge;
assert.equal(nested.request({id:'parent',command:'riftos-agent devlab status'}).accepted,true);
assert.equal(nested.request({id:'child',command:'devlab rpc payload'}).accepted,true);
await flush();
assert.equal(nested.poll('child').state,'completed');
for(let i=0;i<12;i++){nested.request({id:'cache'+i,command:'test'});await flush();}
assert.equal(nested.poll('parent').state,'unknown');
assert.equal(nested.poll('cache11').state,'completed');
const stuck=runtime(()=>new Promise(()=>{}),()=>{}).bridge;
for(let i=0;i<8;i++) assert.equal(stuck.request({id:''+i,command:'task'+i}).accepted,true);
assert.equal(stuck.request({id:'overflow',command:'other'}).accepted,false);
assert.equal(stuck.poll('0').state,'running');
console.log('RiftShell bridge behavior tests passed');
