import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';

const normalize=value=>'/'+String(value||'').replace(/\\/g,'/').split('/').filter(Boolean).join('/');
const files=new Map(),directories=new Set(['/','/system','/system/devlab','/workspace']);
const ensureParents=path=>{let current='';for(const part of normalize(path).split('/').filter(Boolean).slice(0,-1)){current+='/'+part;directories.add(current);}};
const fs={
  async mkdir(path){directories.add(normalize(path));return {kind:'directory'};},
  async stat(path){path=normalize(path);if(files.has(path))return {kind:'file',size:files.get(path).length};if(directories.has(path)||[...files.keys()].some(key=>key.startsWith(path+'/')))return {kind:'directory',size:0};return null;},
  async writeText(path,text){path=normalize(path);ensureParents(path);files.set(path,String(text));return {kind:'file',size:String(text).length};},
  async readText(path){return files.get(normalize(path))??null;},
  async writeJSON(path,value){return this.writeText(path,JSON.stringify(value));},
  async readJSON(path,fallback=null){const text=await this.readText(path);if(text==null)return fallback;try{return JSON.parse(text);}catch{return fallback;}},
  async remove(path){path=normalize(path);files.delete(path);for(const key of [...files.keys()])if(key.startsWith(path+'/'))files.delete(key);for(const key of [...directories])if(key===path||key.startsWith(path+'/'))directories.delete(key);return true;},
  async list(path,{recursive=false}={}){path=normalize(path);const out=[];for(const dir of directories){if(dir===path||!dir.startsWith(path+'/'))continue;const rest=dir.slice(path.length+1);if(recursive||!rest.includes('/'))out.push({path:dir,kind:'directory'});}for(const [key,value] of files){if(!key.startsWith(path+'/'))continue;const rest=key.slice(path.length+1);if(recursive||!rest.includes('/'))out.push({path:key,kind:'file',size:value.length});}return out;}
};
const project=new Map([
  ['RiftOS-main/styles.css','body{color:white}'],
  ['RiftOS-main/src/example.js','window.example=1;'],
  ['RiftOS-main/.riftgit.json',JSON.stringify({headSha:'abc123',branch:'main',full:'Arctic403/RiftOS'})]
]);
const sha=async text=>Buffer.from(await webcrypto.subtle.digest('SHA-256',new TextEncoder().encode(text))).toString('hex');
let applyCount=0,lastPatch=null;
const workspace={available:true,
  async stat(path){const value=project.get(String(path));return value==null?null:{path:String(path),kind:'file',size:value.length};},
  async readText(path){if(!project.has(String(path)))throw new Error(`missing ${path}`);return project.get(String(path));},
  async readJSON(path,fallback=null){if(!project.has(String(path)))return fallback;try{return JSON.parse(project.get(String(path)));}catch{return fallback;}},
  async previewPatch(patch){for(const change of patch.changes){const current=project.get(change.path);if(change.base_sha256==null){if(current!=null)throw new Error(`Workspace file changed since the patch was created: ${change.path}`);}else{if(current==null||await sha(current)!==change.base_sha256)throw new Error(`Workspace file changed since the patch was created: ${change.path}`);}}return {valid:true,changes:patch.changes};},
  async applyPatch(patch){await this.previewPatch(patch);applyCount++;lastPatch=structuredClone(patch);for(const change of patch.changes){if(change.action==='write')project.set(change.path,change.content);else if(change.action==='delete')project.delete(change.path);}return {applied:true,historyId:'history-1',changes:patch.changes.length};}
};
const head={append(){}};
const document={head,createElement(){return {dataset:{},textContent:'',remove(){},};}};
const core={ready:Promise.resolve(),fs,path:{normalize,join:(...parts)=>normalize(parts.join('/')),parent:path=>{const parts=normalize(path).split('/').filter(Boolean);parts.pop();return '/'+parts.join('/');}},native:{connected:true},processes:{list:()=>[]}};
const context={window:{RiftOSCore:core,RiftWorkspace:workspace},globalThis:null,document,crypto:webcrypto,TextEncoder,TextDecoder,setTimeout,clearTimeout,console,structuredClone};context.globalThis=context.window;Object.assign(context.window,{window:context.window,globalThis:context.window,document,crypto:webcrypto,TextEncoder,setTimeout,clearTimeout,console});vm.createContext(context);
vm.runInContext(readFileSync('src/riftdevlab.js','utf8'),context,{filename:'src/riftdevlab.js'});
const lab=context.window.RiftDevLab;

await lab.init();
const before=project.get('RiftOS-main/styles.css');
const staged=await lab.stageEdit('styles.css','body{color:lime}');
assert.equal(project.get('RiftOS-main/styles.css'),before,'staging must not mutate project source');
assert.equal(staged.base_sha256,await sha(before));
assert.equal((await lab.listStaged()).length,1);

const snap=await lab.createSnapshot('green test');
assert.equal(snap.entries[0].content,'body{color:lime}');
await lab.stageEdit('styles.css','body{color:cyan}');
assert.equal((await lab.loadSnapshot(snap.id)).entries[0].content,'body{color:lime}','snapshot must freeze staged content');

project.set('RiftOS-main/styles.css','body{color:red}');
await assert.rejects(()=>lab.publishSnapshot(snap.id),/changed since the patch was created/);
assert.equal(applyCount,0,'conflicted publish must not invoke workspace apply');
assert.equal(project.get('RiftOS-main/styles.css'),'body{color:red}');

project.set('RiftOS-main/styles.css',before);
const published=await lab.publishSnapshot(snap.id);
assert.equal(published.applied,true);assert.equal(applyCount,1);
assert.equal(project.get('RiftOS-main/styles.css'),'body{color:lime}');
assert.equal(lastPatch.version,2);assert.equal(lastPatch.changes[0].base_sha256,await sha(before));
assert.equal((await lab.listStaged()).length,1,'newer post-snapshot stage must survive publication cleanup');

const run=await lab.runScript('lab.log("hello"); lab.assert(!workspace.writeText, "workspace must be read-only"); lab.assert(!core.fs.writeText, "core fs must be read-only"); return 7;');
assert.equal(run.ok,true);assert.equal(run.result,7);assert.deepEqual(Array.from(run.logs),['hello']);
await lab.resetStage();
files.set('/documents/agent-source.js','window.agentSource=42;');
const agentStage=await lab.executeAgentRequest({action:'stage-file',path:'src/agent-source.js',sourcePath:'agent-source.js',cwd:'/documents'});
assert.equal(agentStage.action,'write');assert.equal(await fs.readText('/system/devlab/stage/src/agent-source.js'),'window.agentSource=42;');
const agentRun=await lab.executeAgentRequest({action:'run',source:'return 11;'});assert.equal(agentRun.ok,true);assert.equal(agentRun.result,11);
const agentSnapshot=await lab.executeAgentRequest({action:'snapshot',note:'agent snapshot'});assert.equal(agentSnapshot.entries[0].path,'src/agent-source.js');
const agentPreview=await lab.executeAgentRequest({action:'preview',snapshotId:agentSnapshot.id});assert.equal(agentPreview.valid,true);
await assert.rejects(()=>lab.executeAgentRequest({action:'arbitrary-shell',command:'rm /'}),/Unsupported RiftOS Dev Lab agent action/);
await lab.resetStage();
const created=await lab.stageEdit('src/new-lab.js','window.newLab=true;');assert.equal(created.base_sha256,null);
const newSnap=await lab.createSnapshot('new file');await lab.publishSnapshot(newSnap.id);assert.equal(project.get('RiftOS-main/src/new-lab.js'),'window.newLab=true;');
const deleted=await lab.stageDelete('src/example.js');assert.equal(deleted.action,'delete');
const deleteSnap=await lab.createSnapshot('delete file');await lab.publishSnapshot(deleteSnap.id);assert.equal(project.has('RiftOS-main/src/example.js'),false);
const snapshotsBefore=(await lab.listSnapshots()).length,runsBefore=(await lab.listRuns()).length;
await lab.resetStage();assert.equal((await lab.listStaged()).length,0);
assert.equal((await lab.listSnapshots()).length,snapshotsBefore,'reset must retain snapshots');
assert.equal((await lab.listRuns()).length,runsBefore,'reset must retain run evidence');

console.log('ok - Dev Lab staging is isolated from canonical workspace source');
console.log('ok - snapshots freeze content and guarded publish aborts on baseline drift');
console.log('ok - successful publish uses Workspace v2 guarded patches for writes/new files/deletes');
console.log('ok - newer staged edits survive old snapshot publication; reset retains snapshots/runs');
console.log('ok - structured local-agent requests use Dev Lab APIs and reject arbitrary command tunneling');
