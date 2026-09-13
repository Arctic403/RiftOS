import assert from 'node:assert/strict';
import { createHash, webcrypto } from 'node:crypto';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const normalize=value=>{
  const parts=[];
  for(const part of String(value||'/').replace(/\\/g,'/').split('/')){if(!part||part==='.')continue;if(part==='..'){parts.pop();continue;}parts.push(part);}
  return '/'+parts.join('/');
};
const encode=text=>Buffer.from(text).toString('base64');
const gitSha=base64=>{const bytes=Buffer.from(base64,'base64');return createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');};
const files=new Map([['/home/RiftOS-main/README.md',encode('RiftOS\n')]]),directories=new Set(['/','/home','/home/RiftOS-main','/workspace']);
const fs={
  async stat(path){path=normalize(path);if(files.has(path))return {path,kind:'file',size:Buffer.from(files.get(path),'base64').length};if(directories.has(path)||[...files].some(([key])=>key.startsWith(path+'/')))return {path,kind:'directory',size:0};return null;},
  async get(path){path=normalize(path);if(!files.has(path))return this.stat(path);return {path,kind:'file',content:Buffer.from(files.get(path),'base64').toString(),size:Buffer.from(files.get(path),'base64').length};},
  async readBase64(path){path=normalize(path);if(!files.has(path))throw new Error(`missing ${path}`);return files.get(path);},
  async writeBase64(path,base64){path=normalize(path);files.set(path,base64);directories.add(path.slice(0,path.lastIndexOf('/'))||'/');return this.stat(path);},
  async writeText(path,text){return this.writeBase64(path,encode(String(text)));},
  async mkdir(path){directories.add(normalize(path));return this.stat(path);},
  async remove(path){path=normalize(path);files.delete(path);for(const key of [...files.keys()])if(key.startsWith(path+'/'))files.delete(key);for(const key of [...directories])if(key===path||key.startsWith(path+'/'))directories.delete(key);return true;},
  async move(from,to){from=normalize(from);to=normalize(to);for(const [key,value] of [...files])if(key===from||key.startsWith(from+'/')){files.delete(key);files.set(to+key.slice(from.length),value);}for(const key of [...directories])if(key===from||key.startsWith(from+'/')){directories.delete(key);directories.add(to+key.slice(from.length));}return this.stat(to);},
  async copy(from,to){from=normalize(from);to=normalize(to);for(const [key,value] of [...files])if(key===from||key.startsWith(from+'/'))files.set(to+key.slice(from.length),value);for(const key of [...directories])if(key===from||key.startsWith(from+'/'))directories.add(to+key.slice(from.length));return this.stat(to);},
  async list(path){path=normalize(path);return [...files.keys()].filter(key=>key.startsWith(path+'/')).map(key=>({path:key,kind:'file',size:Buffer.from(files.get(key),'base64').length}));}
};
const remoteReadme=gitSha(files.get('/home/RiftOS-main/README.md'));
let pushedTree=null,pushedRef=null,remoteHead='head0',commitParent=null,afterBlob=null;
const response=(body,status=200)=>({ok:status>=200&&status<300,status,json:async()=>body});
const fetch=async(url,options={})=>{
  const path=new URL(url).pathname,method=options.method||'GET';
  if(path==='/repos/Arctic403/RiftOS')return response({default_branch:'main'});
  if(path.endsWith('/branches/main'))return response({commit:{sha:remoteHead}});
  if(path.endsWith('/git/trees/main')||path.endsWith('/git/trees/head0')||path.endsWith('/git/trees/head2'))return response({truncated:false,tree:[{path:'README.md',type:'blob',mode:'100644',size:7,sha:remoteReadme}]});
  if(path.endsWith('/git/blobs/'+remoteReadme))return response({encoding:'base64',content:encode('RiftOS\n')});
  if(path.endsWith('/git/commits/head0')||path.endsWith('/git/commits/head2'))return response({tree:{sha:'tree0'}});
  if(path.endsWith('/git/blobs')&&method==='POST'){
    const sha=gitSha(JSON.parse(options.body).content);
    if(afterBlob)await afterBlob();
    return response({sha});
  }
  if(path.endsWith('/git/trees')&&method==='POST'){pushedTree=JSON.parse(options.body);return response({sha:'tree1'});}
  if(path.endsWith('/git/commits')&&method==='POST'){commitParent=JSON.parse(options.body).parents[0];return response({sha:'commit1'});}
  if(path.includes('/git/refs/heads/main')&&method==='PATCH'){
    if(commitParent!==remoteHead)return response({message:'Remote advanced; retry'},409);
    pushedRef=JSON.parse(options.body);remoteHead=pushedRef.sha;return response({object:{sha:'commit1'}});
  }
  if(path==='/user')return response({login:'tester'});
  throw new Error(`unexpected fetch ${method} ${path}`);
};
const storage=new Map([['riftgit-token','test-token']]);
const sessionStorage={getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,String(value)),removeItem:key=>storage.delete(key)};
const context={window:{RiftOSCore:{ready:Promise.resolve(),fs}},fetch,sessionStorage,crypto:webcrypto,TextEncoder,Uint8Array,atob:value=>Buffer.from(value,'base64').toString('binary'),console,prompt:()=>''};
context.window.window=context.window;Object.assign(context,context.window);vm.createContext(context);
vm.runInContext(readFileSync('src/riftgit.js','utf8'),context,{filename:'src/riftgit.js'});
const output=[];const print=value=>output.push(String(value));

await context.window.RiftGit.run(['init','Arctic403/RiftOS','main'],print,{cwd:'/home/RiftOS-main'});
assert(output.some(line=>line.includes('Attached /home/RiftOS-main')));
output.length=0;await context.window.RiftGit.run(['status'],print,{cwd:'/home/RiftOS-main'});assert(output.includes('working tree clean'));
await fs.remove('/home/RiftOS-main/README.md');
output.length=0;await context.window.RiftGit.run(['pull'],print,{cwd:'/home/RiftOS-main'});
assert(output.some(line=>line.includes('Local checkout is empty; restoring')));
assert.equal(files.get('/home/RiftOS-main/README.md'),encode('RiftOS\n'));
await fs.writeText('/home/RiftOS-main/README.md','local edit\n');
await assert.rejects(()=>context.window.RiftGit.run(['pull'],print,{cwd:'/home/RiftOS-main'}),/Working tree has local changes/);
await fs.writeText('/home/RiftOS-main/README.md','RiftOS\n');
console.log('ok - pull repopulates an empty attached checkout even when remote HEAD is unchanged');
console.log('ok - pull still protects real local modifications');
await fs.writeBase64('/home/RiftOS-main/assets/icon.bin','AP8Q');
output.length=0;await context.window.RiftGit.run(['status'],print,{cwd:'/home/RiftOS-main'});assert(output.includes('?? assets/icon.bin'));
await context.window.RiftGit.run(['commit','-m','binary','folder','sync'],print,{cwd:'/home/RiftOS-main'});
await context.window.RiftGit.run(['push'],print,{cwd:'/home/RiftOS-main'});
assert(pushedTree.tree.some(entry=>entry.path==='assets/icon.bin'&&entry.sha===gitSha('AP8Q')));
assert.deepEqual(pushedRef,{sha:'commit1',force:false});
output.length=0;await context.window.RiftGit.run(['-C','/home/RiftOS-main','status'],print,{cwd:'/workspace'});assert(output.includes('working tree clean'));
output.length=0;await context.window.RiftGit.run(['sync'],print,{cwd:'/home/RiftOS-main'});assert(output.includes('Already synchronized.'));
console.log('ok - existing /home project attaches by cwd');
console.log('ok - nested binary folder content pushes atomically');
console.log('ok - git -C resolves repositories outside current shell directory');
console.log('ok - git sync completes an up-to-date batch in one command');
remoteHead='head2';
const original=new Map([...files].filter(([path])=>path.startsWith('/home/RiftOS-main/'))),copy=fs.copy;
let failRestore=false;
fs.copy=async(from,to)=>{
  if(to==='/home/RiftOS-main'&&from.includes('.riftgit-stage-')&&(!from.endsWith('-backup')||failRestore)){
    files.set(to+'/partial.txt',encode('partial'));throw new Error('injected copy failure');
  }
  return copy.call(fs,from,to);
};
await assert.rejects(()=>context.window.RiftGit.run(['pull'],print,{cwd:'/home/RiftOS-main'}),/Original project preserved/);
assert.deepEqual(new Map([...files].filter(([path])=>path.startsWith('/home/RiftOS-main/'))),original);
failRestore=true;
await assert.rejects(()=>context.window.RiftGit.run(['pull'],print,{cwd:'/home/RiftOS-main'}),/Recovery failed.*Both retained/);
for(const [path,content] of original){const relative=path.slice('/home/RiftOS-main'.length);assert([...files].some(([key,value])=>key.includes('-backup/')&&key.endsWith(relative)&&value===content));}
fs.copy=copy;
await fs.remove('/home/RiftOS-main');for(const [path,content] of original)await fs.writeBase64(path,content);
await context.window.RiftGit.run(['pull'],print,{cwd:'/home/RiftOS-main'});
assert.equal(files.get('/home/RiftOS-main/README.md'),encode('RiftOS\n'));
assert(!files.has('/home/RiftOS-main/assets/icon.bin'));
console.log('ok - failed replacement restores original; failed recovery retains verified backups');
console.log('ok - successful pull installs the pinned tree');

await fs.mkdir('/workspace/RiftOS-main');
await fs.writeText('/workspace/RiftOS-main/README.md','RiftOS\n');
await fs.writeBase64('/workspace/RiftOS-main/src/feature.bin','AP8Q');
const workspace=context.window.RiftGit.workspace;
output.length=0;
await workspace(['status'],print);
assert(output.some(line=>line.includes('root /workspace/RiftOS-main')));
assert(output.includes('?? src/feature.bin'));
sessionStorage.removeItem('riftgit-token');
await assert.rejects(()=>workspace(['push','test'],print),/GitHub auth/);
sessionStorage.setItem('riftgit-token','test-token');
assert.equal(files.has('/workspace/RiftOS-main/.riftgit.json'),false);

afterBlob=async()=>{await fs.writeText('/workspace/RiftOS-main/src/feature.bin','changed while uploading');afterBlob=null;};
await assert.rejects(()=>workspace(['push','first attempt'],print),/Workspace changed during upload/);
assert.equal(remoteHead,'head2');
assert.equal(commitParent,'head0');
afterBlob=async()=>{remoteHead='head3';afterBlob=null;};
await assert.rejects(()=>workspace(['push','second attempt'],print),/Remote advanced/);
assert.equal(remoteHead,'head3');
remoteHead='head2';
output.length=0;
await context.window.RiftGit.run(['workspace','push','workspace','source','update'],print,{cwd:'/home'});
assert(output.some(line=>line.includes('Publishing /workspace/RiftOS-main')));
assert(pushedTree.tree.some(entry=>entry.path==='src/feature.bin'));
assert.equal(pushedTree.base_tree,'tree0');
assert.equal(commitParent,'head2');
assert.deepEqual(pushedRef,{sha:'commit1',force:false});
assert.equal(files.has('/workspace/RiftOS-main/.riftgit.json'),false);
assert.equal(files.has('/home/.riftgit-current'),true);
assert.equal(Buffer.from(files.get('/home/.riftgit-current'),'base64').toString(),'/home/RiftOS-main');
console.log('ok - workspace status and push work without attaching or changing current repo');
console.log('ok - no auth, local edits and remote races stop before publishing');
