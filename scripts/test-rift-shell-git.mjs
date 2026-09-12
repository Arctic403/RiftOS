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
  async list(path){path=normalize(path);return [...files.keys()].filter(key=>key.startsWith(path+'/')).map(key=>({path:key,kind:'file',size:Buffer.from(files.get(key),'base64').length}));}
};
const remoteReadme=gitSha(files.get('/home/RiftOS-main/README.md'));
let pushedTree=null,pushedRef=null;
const response=(body,status=200)=>({ok:status>=200&&status<300,status,json:async()=>body});
const fetch=async(url,options={})=>{
  const path=new URL(url).pathname,method=options.method||'GET';
  if(path==='/repos/Arctic403/RiftOS')return response({default_branch:'main'});
  if(path.endsWith('/branches/main'))return response({commit:{sha:'head0'}});
  if(path.endsWith('/git/trees/main'))return response({truncated:false,tree:[{path:'README.md',type:'blob',mode:'100644',size:7,sha:remoteReadme}]});
  if(path.endsWith('/git/commits/head0'))return response({tree:{sha:'tree0'}});
  if(path.endsWith('/git/blobs')&&method==='POST')return response({sha:gitSha(JSON.parse(options.body).content)});
  if(path.endsWith('/git/trees')&&method==='POST'){pushedTree=JSON.parse(options.body);return response({sha:'tree1'});}
  if(path.endsWith('/git/commits')&&method==='POST')return response({sha:'commit1'});
  if(path.includes('/git/refs/heads/main')&&method==='PATCH'){pushedRef=JSON.parse(options.body);return response({object:{sha:'commit1'}});}
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
await fs.writeBase64('/home/RiftOS-main/assets/icon.bin','AP8Q');
output.length=0;await context.window.RiftGit.run(['status'],print,{cwd:'/home/RiftOS-main'});assert(output.includes('?? assets/icon.bin'));
await context.window.RiftGit.run(['commit','-m','binary','folder','sync'],print,{cwd:'/home/RiftOS-main'});
await context.window.RiftGit.run(['push'],print,{cwd:'/home/RiftOS-main'});
assert(pushedTree.tree.some(entry=>entry.path==='assets/icon.bin'&&entry.sha===gitSha('AP8Q')));
assert.deepEqual(pushedRef,{sha:'commit1',force:false});
output.length=0;await context.window.RiftGit.run(['-C','/home/RiftOS-main','status'],print,{cwd:'/workspace'});assert(output.includes('working tree clean'));
console.log('ok - existing /home project attaches by cwd');
console.log('ok - nested binary folder content pushes atomically');
console.log('ok - git -C resolves repositories outside current shell directory');
