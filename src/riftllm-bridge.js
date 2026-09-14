const core=globalThis.RiftOSCore;
const workspace=globalThis.RiftWorkspace;
if(!core?.native||!workspace)throw new Error("RiftLLM bridge requires RiftOS native + workspace authority");

const PROJECT_ROOT="RiftLLM";
const TARGET_REPO="Arctic403/RiftLLM";
const TARGET_BRANCH="main";
const MAX_PATCH_CHANGES=500;
const utf8=new TextEncoder();

function clone(value){return value==null?value:JSON.parse(JSON.stringify(value));}
function normalizeProjectPath(value){
  let raw=String(value??"").trim().replace(/\\/g,"/");
  for(const prefix of ["/workspace/RiftLLM/","workspace/RiftLLM/","/RiftLLM/","RiftLLM/"])if(raw.startsWith(prefix)){raw=raw.slice(prefix.length);break;}
  raw=raw.replace(/^\/+/,"");
  if(!raw)throw new Error("RiftLLM project path is required");
  const parts=raw.split("/");
  if(parts.some(part=>!part||part==="."||part===".."||part.includes("\0")))throw new Error("Invalid RiftLLM project path");
  return parts.join("/");
}
function canonicalPath(relative){return `${PROJECT_ROOT}/${normalizeProjectPath(relative)}`;}
function resolveRiftFs(cwd,value){const raw=String(value||"").trim();if(!raw)throw new Error("RiftFS source path is required");return raw.startsWith("/")?core.path.normalize(raw):core.path.join(cwd||"/",raw);}
function hex(bytes){return [...new Uint8Array(bytes)].map(byte=>byte.toString(16).padStart(2,"0")).join("");}
async function sha256Text(text){return hex(await crypto.subtle.digest("SHA-256",utf8.encode(String(text??""))));}
async function native(op,request={},extra={}){return core.native.call("riftllm.dev",{op,request,...extra});}

async function status(){return native("status");}
async function pair(){
  if(typeof prompt!=="function")throw new Error("Secure RiftLLM token entry is unavailable");
  const token=prompt("RiftLLM Dev API token (stored only in Android Keystore-backed RiftOS secret storage):","");
  if(token==null||!String(token).trim())return {paired:false,cancelled:true};
  return native("pair",{}, {token:String(token).trim()});
}
async function unpair(){return native("unpair");}
async function listStaged(){return native("list_staged");}
async function stagedEntry(relative){const rows=await listStaged();return Array.isArray(rows)?rows.find(row=>row?.path===relative)||null:null;}

async function sync(path){
  const relative=normalizeProjectPath(path),full=canonicalPath(relative),stat=await workspace.stat(full);
  if(!stat)throw new Error(`Canonical RiftLLM source does not exist: ${full}`);
  if(stat.kind!=="file")throw new Error(`Canonical RiftLLM source is not a file: ${full}`);
  const content=await workspace.readText(full),sha256=await sha256Text(content);
  const result=await native("sync_source",{path:relative,content,sha256});
  if(String(result?.sha256||"").toLowerCase()!==sha256)throw new Error(`RiftLLM sync verification failed: ${relative}`);
  return result;
}
async function syncMissing(path){
  const relative=normalizeProjectPath(path),full=canonicalPath(relative);
  if(await workspace.stat(full))throw new Error(`sync-missing refused because canonical source exists: ${full}`);
  return native("sync_missing",{path:relative});
}
async function prepareBaseline(path){
  const relative=normalizeProjectPath(path),active=await stagedEntry(relative);
  if(active)return {relative,alreadyStaged:true};
  const full=canonicalPath(relative),stat=await workspace.stat(full);
  return {relative,alreadyStaged:false,baseline:stat?await sync(relative):await syncMissing(relative)};
}
async function load(path){const {relative}=await prepareBaseline(path);return native("load_source",{path:relative});}
async function stage(path,content,reason="RiftOS RiftLLM bridge"){
  const {relative}=await prepareBaseline(path);
  return native("stage",{path:relative,content:String(content??""),reason:String(reason||"RiftOS RiftLLM bridge")});
}
async function stageDelete(path,reason="RiftOS RiftLLM bridge"){
  const relative=normalizeProjectPath(path),active=await stagedEntry(relative);
  if(!active){const full=canonicalPath(relative),stat=await workspace.stat(full);if(!stat||stat.kind!=="file")throw new Error(`Cannot delete canonical RiftLLM file that does not exist: ${full}`);await sync(relative);}
  return native("delete",{path:relative,reason:String(reason||"RiftOS RiftLLM bridge")});
}
async function unstage(path){return native("unstage",{path:normalizeProjectPath(path)});}
async function reset(){return native("reset");}
async function snapshot(note=""){return native("snapshot",{note:String(note||"")});}
async function listSnapshots(limit=50){const value=Math.max(1,Math.min(200,Number(limit)||50));return native("list_snapshots",{limit:value});}
async function getSnapshot(id="latest"){return native("get_snapshot",{snapshotId:String(id||"latest")});}
async function listBenchmarks(limit=50){const value=Math.max(1,Math.min(200,Number(limit)||50));return native("list_benchmarks",{limit:value});}
async function getBenchmark(id="latest"){return native("get_benchmark",{recordId:String(id||"latest")});}
async function concreteSnapshot(id="latest"){
  const snapshot=await getSnapshot(id),snapshotId=String(snapshot?.id||"").trim();
  if(!snapshotId)throw new Error("RiftLLM snapshot response has no immutable id");
  return {snapshotId,snapshot};
}
function validatePatch(patch){
  if(!patch||typeof patch!=="object"||Array.isArray(patch))throw new Error("RiftLLM patch must be a JSON object");
  if(patch.format!=="riftcity-ai-patch"||Number(patch.version)!==2)throw new Error("RiftLLM patch format/version mismatch");
  if(patch.target_repo!==TARGET_REPO||patch.target_branch!==TARGET_BRANCH)throw new Error("RiftLLM patch target repo/branch mismatch");
  if(!Array.isArray(patch.changes)||patch.changes.length<1||patch.changes.length>MAX_PATCH_CHANGES)throw new Error("RiftLLM patch has an invalid change count");
  for(const change of patch.changes){
    if(!change||typeof change!=="object"||Array.isArray(change))throw new Error("RiftLLM patch change must be an object");
    if(!["write","delete"].includes(change.action))throw new Error(`Unsupported RiftLLM patch action: ${change.action}`);
    const expected=canonicalPath(change.path);
    if(change.path!==expected)throw new Error(`RiftLLM patch path escaped canonical project prefix: ${change.path}`);
    if(!Object.prototype.hasOwnProperty.call(change,"base_sha256"))throw new Error(`RiftLLM patch is missing base_sha256: ${change.path}`);
    if(change.base_sha256!=null&&!/^[a-fA-F0-9]{64}$/.test(String(change.base_sha256)))throw new Error(`RiftLLM patch has an invalid baseline hash: ${change.path}`);
    if(change.action==="delete"&&change.base_sha256==null)throw new Error(`RiftLLM delete cannot target an untracked baseline: ${change.path}`);
    if(change.action==="write"&&typeof change.content!=="string")throw new Error(`RiftLLM write is missing text content: ${change.path}`);
  }
  return patch;
}
async function patchForSnapshot(snapshotId){return validatePatch(await native("get_patch",{snapshotId}));}
async function preview(id="latest"){
  const {snapshotId}=await concreteSnapshot(id),patch=await patchForSnapshot(snapshotId),previewResult=await workspace.previewPatch(patch);
  return {snapshotId,valid:previewResult?.valid===true,preview:previewResult};
}
async function acknowledge(snapshotId,historyId,details={}){
  const id=String(historyId||"").trim();if(!id)throw new Error("Workspace history id is required for RiftLLM publication acknowledgment");
  const history=await workspace.history(),record=history.find(item=>item?.id===id);
  if(!record||record.targetRepo!==TARGET_REPO||record.targetBranch!==TARGET_BRANCH)throw new Error("RiftLLM publication acknowledgment requires a matching Workspace history record");
  return native("ack_publish",{snapshotId:String(snapshotId),historyId:id,details:{source:"RiftOS RiftLLM bridge",previewed:true,...clone(details)}});
}
async function publish(id="latest"){
  const {snapshotId}=await concreteSnapshot(id),patch=await patchForSnapshot(snapshotId),previewResult=await workspace.previewPatch(patch);
  if(previewResult?.valid!==true)throw new Error("RiftLLM Workspace preview did not validate");
  const applied=await workspace.applyPatch(patch);
  try{
    const receipt=await native("ack_publish",{snapshotId,historyId:applied.historyId,details:{source:"RiftOS RiftLLM bridge",previewed:true}});
    return {published:true,acknowledged:true,snapshotId,preview:previewResult,workspace:applied,receipt};
  }catch(error){
    return {published:true,acknowledged:false,snapshotId,preview:previewResult,workspace:applied,acknowledgementError:error?.message||String(error)};
  }
}

async function run(args,print=console.log,context={}){
  const list=[...args],cmd=(list.shift()||"help").toLowerCase();
  const show=value=>{print(typeof value==="string"?value:JSON.stringify(value,null,2));return value;};
  if(cmd==="help")return print(`RiftLLM standalone Dev API bridge\nriftllm-agent status\nriftllm-agent pair\nriftllm-agent unpair\nriftllm-agent sync <project-path>\nriftllm-agent sync-missing <project-path>\nriftllm-agent load <project-path>\nriftllm-agent staged\nriftllm-agent stage <project-path> <text>\nriftllm-agent stage-file <project-path> <riftfs-source-file>\nriftllm-agent delete <project-path>\nriftllm-agent unstage <project-path>\nriftllm-agent reset\nriftllm-agent snapshot [note]\nriftllm-agent snapshots [limit]\nriftllm-agent get-snapshot [id|latest]\nriftllm-agent benchmarks [limit]\nriftllm-agent benchmark [record-id|latest]\nriftllm-agent preview [id|latest]\nriftllm-agent publish [id|latest]\nriftllm-agent ack <id|latest> <workspace-history-id>\nPairing token is entered only in the local secure prompt, never as a shell argument.`);
  if(cmd==="status")return show(await status());
  if(cmd==="pair"){if(list.length)throw new Error("usage: riftllm-agent pair (enter the token only in the secure local prompt)");return show(await pair());}
  if(cmd==="unpair"){if(list.length)throw new Error("usage: riftllm-agent unpair");return show(await unpair());}
  if(cmd==="staged")return show(await listStaged());
  if(cmd==="reset")return show(await reset());
  if(cmd==="sync"||cmd==="sync-missing"||cmd==="load"||cmd==="delete"||cmd==="unstage"){
    if(!list[0])throw new Error(`usage: riftllm-agent ${cmd} <project-path>`);
    const fn={sync,"sync-missing":syncMissing,load,delete:stageDelete,unstage}[cmd];return show(await fn(list[0]));
  }
  if(cmd==="stage"){if(list.length<2)throw new Error("usage: riftllm-agent stage <project-path> <text>");const path=list.shift();return show(await stage(path,list.join(" ")));}
  if(cmd==="stage-file"){
    if(list.length<2)throw new Error("usage: riftllm-agent stage-file <project-path> <riftfs-source-file>");
    const path=list.shift(),sourcePath=resolveRiftFs(context.cwd,list.shift()),content=await core.fs.readText(sourcePath);return show(await stage(path,content,`staged from ${sourcePath}`));
  }
  if(cmd==="snapshot")return show(await snapshot(list.join(" ")));
  if(cmd==="snapshots"){if(list[0]!==undefined&&!Number.isFinite(Number(list[0])))throw new Error("snapshot limit must be numeric");return show(await listSnapshots(list[0]));}
  if(cmd==="get-snapshot")return show(await getSnapshot(list[0]||"latest"));
  if(cmd==="benchmarks"){if(list[0]!==undefined&&!Number.isFinite(Number(list[0])))throw new Error("benchmark limit must be numeric");return show(await listBenchmarks(list[0]));}
  if(cmd==="benchmark")return show(await getBenchmark(list[0]||"latest"));
  if(cmd==="preview")return show(await preview(list[0]||"latest"));
  if(cmd==="publish")return show(await publish(list[0]||"latest"));
  if(cmd==="ack"){if(list.length<2)throw new Error("usage: riftllm-agent ack <id|latest> <workspace-history-id>");const {snapshotId}=await concreteSnapshot(list[0]);return show(await acknowledge(snapshotId,list[1],{recovery:true}));}
  throw new Error(`unknown riftllm-agent command: ${cmd}`);
}

globalThis.RiftLlmBridge=Object.freeze({version:1,status,pair,unpair,sync,syncMissing,load,listStaged,stage,stageDelete,unstage,reset,snapshot,listSnapshots,getSnapshot,listBenchmarks,getBenchmark,preview,publish,acknowledge,run});
