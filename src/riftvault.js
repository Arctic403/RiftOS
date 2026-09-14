const core=globalThis.RiftOSCore;
if(!core?.fs)throw new Error("RiftVault requires RiftOSCore");

const ROOT="/system/riftvault/v1";
const OBJECT_ROOT=`${ROOT}/objects/sha256`;
const META_ROOT=`${ROOT}/meta`;
const MANIFEST_ROOT=`${ROOT}/manifests`;
const JOB_ROOT=`${ROOT}/jobs`;
const utf8=new TextEncoder();
const nowISO=()=>new Date().toISOString();
const id=(prefix="vault")=>`${prefix}-${Date.now()}-${crypto.randomUUID?.()||Math.random().toString(36).slice(2)}`;
const clone=value=>value==null?value:JSON.parse(JSON.stringify(value));

function normalizePath(value){return core.path.normalize(String(value||"/"));}
function hex(bytes){return [...new Uint8Array(bytes)].map(b=>b.toString(16).padStart(2,"0")).join("");}
async function sha256Bytes(bytes){return hex(await crypto.subtle.digest("SHA-256",bytes));}
async function sha256Text(text){return sha256Bytes(utf8.encode(String(text??"")));}
function objectPath(hash){const clean=String(hash||"").toLowerCase();if(!/^[a-f0-9]{64}$/.test(clean))throw new Error("Invalid SHA-256 object id");return `${OBJECT_ROOT}/${clean.slice(0,2)}/${clean}`;}
function metaPath(hash){return `${META_ROOT}/${String(hash).toLowerCase()}.json`;}

async function ensure(){for(const path of [ROOT,OBJECT_ROOT,META_ROOT,MANIFEST_ROOT,JOB_ROOT])await core.fs.mkdir(path);return true;}
async function nativeSecretPresent(key){try{return (await core.native.call("secrets.get",{key}))?.value!=null;}catch{return false;}}

async function hashFile(path){
  await core.ready;const full=normalizePath(path),stat=await core.fs.stat(full);if(!stat||stat.kind!=="file")throw new Error(`Vault source is not a file: ${full}`);
  const sha256=String(await core.fs.sha256(full)||"").toLowerCase();if(!/^[a-f0-9]{64}$/.test(sha256))throw new Error(`Native SHA-256 returned an invalid digest for ${full}`);return {sha256,size:Number(stat.size||0),modified:Number(stat.modified||0),path:full};
}

async function putFile(path,{pin=false,logicalPath=null}={}){
  await ensure();const info=await hashFile(path),target=objectPath(info.sha256),existing=await core.fs.stat(target);
  if(!existing){await core.fs.mkdir(core.path.parent(target));await core.fs.copy(info.path,target,{overwrite:false});}
  const prior=await core.fs.readJSON(metaPath(info.sha256),{});const meta={format:"riftvault-object-v1",sha256:info.sha256,size:info.size,createdAt:prior.createdAt||nowISO(),lastVerifiedAt:nowISO(),logicalPaths:[...new Set([...(prior.logicalPaths||[]),logicalPath||info.path])],pinned:pin===true||prior.pinned===true,state:"LOCAL_BACKED_UP",providers:{local:{ready:true,verified:true}}};
  await core.fs.writeJSON(metaPath(info.sha256),meta);return clone(meta);
}

async function object(hash){await ensure();const path=objectPath(hash),stat=await core.fs.stat(path);if(!stat)return null;return {sha256:String(hash).toLowerCase(),path,size:Number(stat.size||0),meta:await core.fs.readJSON(metaPath(hash),null)};}
async function restoreObject(hash,destination,{overwrite=false}={}){const record=await object(hash);if(!record)throw new Error(`Vault object not found: ${hash}`);const target=normalizePath(destination),existing=await core.fs.stat(target),temporary=`${target}.riftvault-${Date.now()}-${Math.random().toString(36).slice(2)}.tmp`;if(existing&&!overwrite)throw new Error(`Restore destination exists: ${target}`);await core.fs.mkdir(core.path.parent(target));try{await core.fs.copy(record.path,temporary,{overwrite:false});const verified=await hashFile(temporary);if(verified.sha256!==record.sha256)throw new Error(`Vault restore checksum mismatch: ${target}`);if(existing)await core.fs.remove(target);await core.fs.move(temporary,target,{overwrite:false});return {restored:true,sha256:record.sha256,path:target,size:verified.size};}catch(error){await core.fs.remove(temporary).catch(()=>{});throw error;}}

async function backupPath(path,{pin=false}={}){
  await ensure();const root=normalizePath(path),stat=await core.fs.stat(root);if(!stat)throw new Error(`Vault path not found: ${root}`);
  const jobId=id("backup"),jobPath=`${JOB_ROOT}/${jobId}.json`,job={format:"riftvault-job-v1",id:jobId,operation:"backup",path:root,state:"running",startedAt:nowISO(),files:0,bytes:0};await core.fs.writeJSON(jobPath,job);
  try{
    if(stat.kind==="file"){const ref=await putFile(root,{pin,logicalPath:root});Object.assign(job,{state:"complete",completedAt:nowISO(),files:1,bytes:ref.size,ref:`sha256:${ref.sha256}`});await core.fs.writeJSON(jobPath,job);return clone(job);}
    const rows=await core.fs.list(root,{recursive:true}),files=rows.filter(row=>row.kind==="file"),entries=[];
    for(const row of files){const ref=await putFile(row.path,{pin,logicalPath:row.path}),relative=row.path.slice(root.length).replace(/^\/+/,"");entries.push({path:relative,sha256:ref.sha256,size:ref.size});job.files++;job.bytes+=ref.size;}
    entries.sort((a,b)=>a.path.localeCompare(b.path));const manifest={format:"riftvault-manifest-v1",root,createdAt:nowISO(),entries},manifestId=await sha256Text(JSON.stringify(manifest));manifest.id=manifestId;await core.fs.writeJSON(`${MANIFEST_ROOT}/${manifestId}.json`,manifest);Object.assign(job,{state:"complete",completedAt:nowISO(),ref:`manifest:${manifestId}`});await core.fs.writeJSON(jobPath,job);return clone(job);
  }catch(error){Object.assign(job,{state:"failed",failedAt:nowISO(),error:error.message});await core.fs.writeJSON(jobPath,job).catch(()=>{});throw error;}
}

async function restore(ref,destination,{overwrite=false}={}){
  await ensure();const raw=String(ref||"").trim();if(raw.startsWith("sha256:"))return restoreObject(raw.slice(7),destination,{overwrite});
  if(!raw.startsWith("manifest:"))throw new Error("Vault restore ref must be sha256:<hash> or manifest:<id>");
  const manifestId=raw.slice(9),manifest=await core.fs.readJSON(`${MANIFEST_ROOT}/${manifestId}.json`,null);if(!manifest)throw new Error(`Vault manifest not found: ${manifestId}`);const target=normalizePath(destination),existing=await core.fs.stat(target),staged=`${target}.riftvault-restore-${Date.now()}-${Math.random().toString(36).slice(2)}`;if(existing&&!overwrite)throw new Error(`Restore destination exists: ${target}`);await core.fs.mkdir(staged);
  try{for(const entry of manifest.entries||[]){const dest=core.path.join(staged,entry.path);await restoreObject(entry.sha256,dest,{overwrite:false});}if(existing)await core.fs.remove(target);await core.fs.move(staged,target,{overwrite:false});return {restored:true,ref:raw,path:target,files:(manifest.entries||[]).length};}catch(error){await core.fs.remove(staged).catch(()=>{});throw error;}
}

async function setPinned(hash,pinned){const record=await object(hash);if(!record)throw new Error(`Vault object not found: ${hash}`);const meta=record.meta||{format:"riftvault-object-v1",sha256:record.sha256,size:record.size,logicalPaths:[]};meta.pinned=!!pinned;meta.updatedAt=nowISO();await core.fs.writeJSON(metaPath(record.sha256),meta);return clone(meta);}
async function jobs(limit=30){await ensure();const rows=await core.fs.list(JOB_ROOT,{recursive:false}).catch(()=>[]),out=[];for(const row of rows.filter(r=>r.kind==="file").sort((a,b)=>Number(b.modified||0)-Number(a.modified||0)).slice(0,Math.max(1,Math.min(100,Number(limit)||30)))){const value=await core.fs.readJSON(row.path,null);if(value)out.push(value);}return out;}
async function providers(){return [
  {id:"local",name:"RiftVault Local",ready:true,role:"authoritative local durable object store",rangeReads:true,multipart:false},
  {id:"r2",name:"Cloudflare R2",ready:false,configured:await nativeSecretPresent("riftvault.r2.accessKeyId")&&await nativeSecretPresent("riftvault.r2.secretAccessKey"),role:"frequent remote object backup",reason:"remote transport intentionally capability-gated until the native provider adapter is installed"},
  {id:"terabox",name:"TeraBox",ready:false,configured:false,role:"bulk/archive",reason:"requires confirmed official API access before provider code is enabled"}
];}
async function status(){await ensure();const rows=await core.fs.list(OBJECT_ROOT,{recursive:true}).catch(()=>[]),objects=rows.filter(r=>r.kind==="file"),manifests=(await core.fs.list(MANIFEST_ROOT,{recursive:false}).catch(()=>[])).filter(r=>r.kind==="file"),bytes=objects.reduce((sum,row)=>sum+Number(row.size||0),0);return {available:true,version:1,root:ROOT,objects:objects.length,bytes,manifests:manifests.length,providers:await providers(),jobs:(await jobs(10)).length};}
async function bench(provider="local"){
  const id=String(provider||"local").toLowerCase();if(id!=="local")return {provider:id,available:false,reason:(await providers()).find(p=>p.id===id)?.reason||"Provider unavailable"};
  await ensure();const path=`${ROOT}/bench-${Date.now()}.txt`,payload="riftvault-bench-"+"x".repeat(256*1024),started=performance.now();await core.fs.writeText(path,payload);const writeMs=performance.now()-started,readStarted=performance.now(),read=await core.fs.readText(path),readMs=performance.now()-readStarted;await core.fs.remove(path).catch(()=>{});return {provider:"local",available:true,bytes:utf8.encode(payload).length,writeMs:Math.round(writeMs*100)/100,readMs:Math.round(readMs*100)/100,verified:read===payload};
}
async function login(provider){const id=String(provider||"").toLowerCase();if(id!=="r2")throw new Error("Only the R2 credential slot is defined; TeraBox waits for confirmed official API support");if(typeof prompt!=="function")throw new Error("Interactive credential entry is unavailable");const accessKeyId=prompt("R2 Access Key ID (stored in Android Keystore):","");if(!accessKeyId)return {configured:false};const secretAccessKey=prompt("R2 Secret Access Key (stored in Android Keystore):","");if(!secretAccessKey)return {configured:false};const endpoint=prompt("R2 endpoint / account URL (non-secret):","")||"";await core.native.call("secrets.set",{key:"riftvault.r2.accessKeyId",value:accessKeyId.trim()});await core.native.call("secrets.set",{key:"riftvault.r2.secretAccessKey",value:secretAccessKey.trim()});if(endpoint.trim())await core.fs.setSetting("riftvault.r2.endpoint",endpoint.trim());return {configured:true,provider:"r2",transportReady:false};}

async function run(args,print=console.log,context={}){
  const list=[...args],cmd=(list.shift()||"status").toLowerCase();
  if(cmd==="help")return print("rift vault status | providers | backup <path> | restore <ref> <path> | object <sha256> | pin <sha256> | unpin <sha256> | jobs | bench [local|r2|terabox] | login r2");
  if(cmd==="status")return print(JSON.stringify(await status(),null,2));
  if(cmd==="providers")return print(JSON.stringify(await providers(),null,2));
  if(cmd==="jobs")return print(JSON.stringify(await jobs(Number(list[0])||30),null,2));
  if(cmd==="bench")return print(JSON.stringify(await bench(list[0]||"local"),null,2));
  if(cmd==="login")return print(JSON.stringify(await login(list[0]),null,2));
  const resolve=value=>normalizePath(core.path.isAbsolute(value)?value:core.path.join(context.cwd||"/",value));
  if(cmd==="backup"){if(!list[0])throw new Error("usage: rift vault backup <path>");return print(JSON.stringify(await backupPath(resolve(list[0])),null,2));}
  if(cmd==="restore"){if(list.length<2)throw new Error("usage: rift vault restore <ref> <path>");return print(JSON.stringify(await restore(list[0],resolve(list[1]),{overwrite:list.includes("--force")}),null,2));}
  if(cmd==="object"){if(!list[0])throw new Error("usage: rift vault object <sha256>");return print(JSON.stringify(await object(list[0]),null,2));}
  if(cmd==="pin"||cmd==="unpin"){if(!list[0])throw new Error(`usage: rift vault ${cmd} <sha256>`);return print(JSON.stringify(await setPinned(list[0],cmd==="pin"),null,2));}
  throw new Error(`unknown rift vault command: ${cmd}`);
}

await ensure();
globalThis.RiftVault=Object.freeze({version:1,root:ROOT,hashFile,putFile,object,restoreObject,backupPath,restore,setPinned,jobs,providers,status,bench,login,run,sha256Text});
