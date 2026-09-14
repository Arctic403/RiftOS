const core=globalThis.RiftOSCore;
const vault=globalThis.RiftVault;
if(!core?.fs||!vault)throw new Error("RiftMemory requires RiftOSCore and RiftVault");

const ROOT="/system/riftmemory/v1";
const CACHE_ROOT=`${ROOT}/cache`;
const INDEX=`${ROOT}/index.json`;
const nowISO=()=>new Date().toISOString();

async function ensure(){await core.ready;await core.fs.mkdir(ROOT);await core.fs.mkdir(CACHE_ROOT);if(!(await core.fs.stat(INDEX)))await core.fs.writeJSON(INDEX,{format:"riftmemory-index-v1",version:1,entries:{},updatedAt:nowISO()});}
async function load(){await ensure();return await core.fs.readJSON(INDEX,{format:"riftmemory-index-v1",version:1,entries:{}});}
async function save(index){index.updatedAt=nowISO();await core.fs.writeJSON(INDEX,index);return index;}
function cachePath(hash){const clean=String(hash||"").toLowerCase();if(!/^[a-f0-9]{64}$/.test(clean))throw new Error("Invalid cache object hash");return `${CACHE_ROOT}/${clean.slice(0,2)}/${clean}`;}

async function cacheFile(path,{pin=false}={}){
  const source=core.path.normalize(path),ref=await vault.putFile(source,{logicalPath:source,pin}),target=cachePath(ref.sha256);if(!(await core.fs.stat(target))){const object=await vault.object(ref.sha256);await core.fs.mkdir(core.path.parent(target));await core.fs.copy(object.path,target,{overwrite:false});}
  const index=await load();index.entries[ref.sha256]={sha256:ref.sha256,source,size:ref.size,pinned:pin===true||index.entries[ref.sha256]?.pinned===true,lastAccess:nowISO(),state:"WARM"};await save(index);return index.entries[ref.sha256];
}
async function prefetch(path,{pin=false}={}){
  const root=core.path.normalize(path),stat=await core.fs.stat(root);if(!stat)throw new Error(`RiftMemory source not found: ${root}`);const results=[];
  if(stat.kind==="file")results.push(await cacheFile(root,{pin}));else{const rows=await core.fs.list(root,{recursive:true});for(const row of rows.filter(r=>r.kind==="file"))results.push(await cacheFile(row.path,{pin}));}
  return {prefetched:results.length,bytes:results.reduce((sum,row)=>sum+Number(row.size||0),0),entries:results};
}
async function findByPath(path,index){const normalized=core.path.normalize(path);return Object.values(index.entries||{}).filter(row=>row.source===normalized);}
async function pin(path){const result=await prefetch(path,{pin:true});for(const row of result.entries)await vault.setPinned(row.sha256,true);return result;}
async function unpin(value){const index=await load(),raw=String(value||"").trim(),matches=/^[a-f0-9]{64}$/i.test(raw)?[index.entries[raw.toLowerCase()]].filter(Boolean):await findByPath(raw,index);for(const row of matches){row.pinned=false;row.lastAccess=nowISO();await vault.setPinned(row.sha256,false).catch(()=>{});}await save(index);return {unpinned:matches.length};}
async function status(){const index=await load(),entries=Object.values(index.entries||{}),bytes=entries.reduce((sum,row)=>sum+Number(row.size||0),0),pinned=entries.filter(row=>row.pinned).length;return {available:true,version:1,mode:"local-flash-control-plane",nativeAccelerator:false,hotTier:"Android process memory (not directly managed in this MVP)",warmTier:CACHE_ROOT,coldTier:"RiftVault",entries:entries.length,bytes,pinned,blockingRemoteMisses:0,note:"The C++/JNI accelerator remains capability-gated until a native build proves that data plane."};}
async function prune(maxBytes=512*1024*1024){const limit=Math.max(0,Number(maxBytes)||0),index=await load(),entries=Object.values(index.entries||{}),current=()=>Object.values(index.entries||{}).reduce((sum,row)=>sum+Number(row.size||0),0);let removed=0,reclaimed=0;for(const row of entries.filter(row=>!row.pinned).sort((a,b)=>String(a.lastAccess).localeCompare(String(b.lastAccess)))){if(current()<=limit)break;await core.fs.remove(cachePath(row.sha256)).catch(()=>{});reclaimed+=Number(row.size||0);delete index.entries[row.sha256];removed++;}await save(index);return {removed,reclaimed,bytes:current(),limit};}
async function flush(){const index=await load();await save(index);return {flushed:true,entries:Object.keys(index.entries||{}).length};}

async function run(args,print=console.log,context={}){
  const list=[...args],cmd=(list.shift()||"status").toLowerCase();if(cmd==="help")return print("rift memory status | prefetch <path> | pin <path> | unpin <path|sha256> | prune [max-bytes] | flush");if(cmd==="status")return print(JSON.stringify(await status(),null,2));
  const resolve=value=>core.path.normalize(String(value||"").startsWith("/")?value:core.path.join(context.cwd||"/",value));if(cmd==="prefetch"){if(!list[0])throw new Error("usage: rift memory prefetch <path>");return print(JSON.stringify(await prefetch(resolve(list[0])),null,2));}if(cmd==="pin"){if(!list[0])throw new Error("usage: rift memory pin <path>");return print(JSON.stringify(await pin(resolve(list[0])),null,2));}if(cmd==="unpin"){if(!list[0])throw new Error("usage: rift memory unpin <path|sha256>");const value=/^[a-f0-9]{64}$/i.test(list[0])?list[0]:resolve(list[0]);return print(JSON.stringify(await unpin(value),null,2));}if(cmd==="prune")return print(JSON.stringify(await prune(Number(list[0])||512*1024*1024),null,2));if(cmd==="flush")return print(JSON.stringify(await flush(),null,2));throw new Error(`unknown rift memory command: ${cmd}`);
}

await ensure();
globalThis.RiftMemory=Object.freeze({version:1,root:ROOT,prefetch,pin,unpin,status,prune,flush,run});
