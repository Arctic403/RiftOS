import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const normalize=value=>'/'+String(value||'/').replace(/\\/g,'/').split('/').filter(Boolean).join('/');
const files=new Map([['/workspace/original.txt','before']]),directories=new Set(['/','/workspace','/system']);
const fs={
  async stat(path){path=normalize(path);if(files.has(path))return {kind:'file',size:files.get(path).length};if(directories.has(path)||[...files.keys()].some(key=>key.startsWith(path+'/')))return {kind:'directory',size:0};return null;},
  async mkdir(path){directories.add(normalize(path));return {kind:'directory'};},
  async remove(path){path=normalize(path);files.delete(path);for(const key of [...files.keys()])if(key.startsWith(path+'/'))files.delete(key);for(const key of [...directories])if(key===path||key.startsWith(path+'/'))directories.delete(key);return true;},
  async copy(from,to){from=normalize(from);to=normalize(to);if(files.has(from)){files.set(to,files.get(from));return {kind:'file'};}directories.add(to);for(const [key,value] of [...files])if(key.startsWith(from+'/'))files.set(to+key.slice(from.length),value);return {kind:'directory'};}
};
let allowed=true;
const context={window:{RiftOSCore:{fs,permissions:{has:async()=>allowed}}},console,Math,Date,TextEncoder};context.window.window=context.window;Object.assign(context,context.window);vm.createContext(context);
vm.runInContext(readFileSync('src/riftshell-batch.js','utf8'),context,{filename:'src/riftshell-batch.js'});
const resolve=(cwd,value)=>normalize(String(value).startsWith('/')?value:`${cwd}/${value}`),state={cwd:'/workspace'},output=[];
const execute=async command=>{
  const [cmd,path,...rest]=command.split(/\s+/);
  if(cmd==='write'){files.set(resolve(state.cwd,path),rest.join(' '));return;}
  if(cmd==='mkdir'){directories.add(resolve(state.cwd,path));return;}
  if(cmd==='cd'){state.cwd=resolve(state.cwd,path);return;}
  if(cmd==='stat')throw new Error('planned failure');
};

await assert.rejects(()=>context.window.RiftShellBatch.run('write original.txt changed ; mkdir temp ; stat original.txt',{state,execute,resolve,print:value=>output.push(String(value))}),/rolled back/);
assert.equal(files.get('/workspace/original.txt'),'before');assert(!directories.has('/workspace/temp'));assert.equal(state.cwd,'/workspace');
await context.window.RiftShellBatch.run('write original.txt after ; mkdir complete',{state,execute,resolve,print:value=>output.push(String(value))});
assert.equal(files.get('/workspace/original.txt'),'after');assert(directories.has('/workspace/complete'));
await context.window.RiftShellBatch.run('write original.txt ignored',{state,execute,resolve,dryRun:true,print:value=>output.push(String(value))});assert.equal(files.get('/workspace/original.txt'),'after');
assert.deepEqual(Array.from(context.window.RiftShellBatch.split('write a "x;y"; write b z')),['write a "x;y"','write b z']);
await assert.rejects(()=>context.window.RiftShellBatch.run('write original.txt changed ; git push',{state,execute,resolve,print:()=>{}}),/cannot run inside an atomic batch/);assert.equal(files.get('/workspace/original.txt'),'after');
console.log('ok - failed local batches restore files and directories');
console.log('ok - successful and dry-run batch modes');
console.log('ok - non-reversible commands are blocked and rolled back');
for(const script of ['unknown thing','cp only-one','ls --bad','head original.txt nope','write /workspace x','git push','workspace rollback','write /mounts/card/file x']){
  await assert.rejects(()=>context.window.RiftShellBatch.run(script,{state,execute,resolve,dryRun:true,print:()=>{}}));
}
allowed=false;
await assert.rejects(()=>context.window.RiftShellBatch.run('write original.txt x',{state,execute,resolve,dryRun:true,print:()=>{}}),/permission denied/);
allowed=true;
await context.window.RiftShellBatch.run('mkdir planned ; cd planned ; write child hello ; cat child',{state,execute,resolve,dryRun:true,print:()=>{}});
assert(!directories.has('/workspace/planned'));assert.equal(state.cwd,'/workspace');
const normalCopy=fs.copy;
fs.copy=async(from,to)=>{if(from.startsWith('/system/riftshell-batches/'))throw new Error('restore failure');return normalCopy(from,to);};
await assert.rejects(()=>context.window.RiftShellBatch.run('write original.txt x ; stat original.txt',{state,execute,resolve,print:()=>{}}),/Recovery files retained/);
assert([...files].some(([path,value])=>path.startsWith('/system/riftshell-batches/')&&value==='after'));
fs.copy=normalCopy;
console.log('ok - dry run rejects invalid commands, arguments, permissions and rollback targets');
console.log('ok - dry run models planned directories without mutations; failed recovery retains backups');
