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
  for(const prefix of ["/workspace/RiftLLM/","workspace/RiftLLM/","/D:/Workspace/RiftLLM/","D:/Workspace/RiftLLM/","/RiftLLM/","RiftLLM/"])if(raw.startsWith(prefix)){raw=raw.slice(prefix.length);break;}
  raw=raw.replace(/^\/+/,"");
  if(!raw)throw new Error("RiftLLM project path is required");
  const parts=raw.split("/");
  if(parts.some(part=>!part||part==="."||part===".."||part.includes("\0")))throw new Error("Invalid RiftLLM project path");
  return parts.join("/");
}
function canonicalPath(relative){return `${PROJECT_ROOT}/${normalizeProjectPath(relative)}`;}
function resolveRiftFs(cwd,value){const raw=String(value||"").trim();if(!raw)throw new Error("RiftFS source path is required");return core.path.isAbsolute(raw)?core.path.normalize(raw):core.path.join(cwd||"/",raw);}
function hex(bytes){return [...new Uint8Array(bytes)].map(byte=>byte.toString(16).padStart(2,"0")).join("");}
async function sha256Text(text){return hex(await crypto.subtle.digest("SHA-256",utf8.encode(String(text??""))));}

const CORPUS_FORMAT="rift-corpus-v1";
const CORPUS_BUILDER="rift-corpus-builder-v1";
const CORPUS_ROOT="/workspace/RiftLLM/tokenizer/private";
const CORPUS_DEFAULT_INPUT=`${CORPUS_ROOT}/samples.jsonl`;
const CORPUS_DEFAULT_OUTPUT=`${CORPUS_ROOT}/build`;
const CORPUS_DEFAULT_SEED="rift-corpus-v1-split-a";
const CORPUS_DEFAULT_HELDOUT=1000;
const CORPUS_MAX_SAMPLES=2000000;
const CORPUS_MAX_SAMPLE_BYTES=16*1024;
const CORPUS_CATEGORIES=Object.freeze(["prose","code","non_ascii"]);
const CORPUS_ORIGINS=Object.freeze(["original","public_reference_rewrite"]);
const CORPUS_BLOCKED_PROJECTS=Object.freeze(["riftllm","riftos","vortex3d","vtxbuilder","vortexscript"]);
const CORPUS_BLOCKED_URIS=Object.freeze([
  "github.com/arctic403/riftllm","github.com/arctic403/riftos","github.com/arctic403/vortex3d",
  "github.com/arctic403/vtxbuilder","github.com/arctic403/vortexscript"
]);
const CORPUS_ID_RE=/^[A-Za-z0-9._:-]{1,160}$/;
const CORPUS_FIELDS=new Set(["id","category","domain","origin","text","source_project","reference_uri","reference_title","reference_license","notes"]);

function sortedJsonValue(value){
  if(Array.isArray(value))return value.map(sortedJsonValue);
  if(value&&typeof value==="object")return Object.fromEntries(Object.keys(value).sort().map(key=>[key,sortedJsonValue(value[key])]));
  return value;
}
function canonicalJson(value){
  if(value===null||typeof value!=="object")return JSON.stringify(value);
  if(Array.isArray(value))return `[${value.map(canonicalJson).join(",")}]`;
  return `{${Object.keys(value).sort().map(key=>`${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(",")}}`;
}
function requiredCorpusString(row,key,line){const value=row?.[key];if(typeof value!=="string"||!value.trim())throw new Error(`line ${line}: ${key} must be a non-empty string`);return value;}
function normalizeCorpusPath(value,fallback){
  const raw=String(value||fallback||"").trim();if(!raw)throw new Error("RiftCorpus path is required");
  const display=core.path.normalize(core.path.isAbsolute(raw)?raw:core.path.join("/workspace/RiftLLM",raw));
  const path=core.path.canonical(display);
  if(path!==CORPUS_ROOT&&!path.startsWith(`${CORPUS_ROOT}/`))throw new Error(`RiftCorpus path must stay under ${CORPUS_ROOT}`);
  return path;
}
async function validateCorpusSample(row,line){
  if(!row||typeof row!=="object"||Array.isArray(row))throw new Error(`line ${line}: each JSONL record must be an object`);
  const unknown=Object.keys(row).filter(key=>!CORPUS_FIELDS.has(key)).sort();if(unknown.length)throw new Error(`line ${line}: unknown fields: ${unknown.join(", ")}`);
  const id=requiredCorpusString(row,"id",line);if(!CORPUS_ID_RE.test(id))throw new Error(`line ${line}: invalid id`);
  const category=requiredCorpusString(row,"category",line);if(!CORPUS_CATEGORIES.includes(category))throw new Error(`line ${line}: invalid category`);
  const domain=requiredCorpusString(row,"domain",line).trim();
  const origin=requiredCorpusString(row,"origin",line);if(!CORPUS_ORIGINS.includes(origin))throw new Error(`line ${line}: invalid origin`);
  const text=requiredCorpusString(row,"text",line),textBytes=utf8.encode(text);if(textBytes.byteLength>CORPUS_MAX_SAMPLE_BYTES)throw new Error(`line ${line}: sample exceeds ${CORPUS_MAX_SAMPLE_BYTES} UTF-8 bytes`);if(text.includes("\0"))throw new Error(`line ${line}: NUL bytes are not allowed`);
  const sourceProject=String(row.source_project||"").trim(),normalizedProject=sourceProject.toLowerCase().replace(/[^a-z0-9]+/g,"");
  if(CORPUS_BLOCKED_PROJECTS.some(name=>normalizedProject.includes(name)))throw new Error(`line ${line}: unfinished Rift project source is excluded in RiftCorpus V1: ${sourceProject}`);
  if(origin==="public_reference_rewrite"){
    if(!sourceProject)throw new Error(`line ${line}: public_reference_rewrite requires source_project provenance`);
    const uri=requiredCorpusString(row,"reference_uri",line).trim();if(!/^https?:\/\//i.test(uri))throw new Error(`line ${line}: public reference URI must be http(s)`);
    if(CORPUS_BLOCKED_URIS.some(fragment=>uri.toLowerCase().includes(fragment)))throw new Error(`line ${line}: unfinished Rift project references are excluded in this phase`);
    requiredCorpusString(row,"reference_title",line);requiredCorpusString(row,"reference_license",line);
  }else for(const key of ["reference_uri","reference_title","reference_license"])if(String(row[key]||"").trim())throw new Error(`line ${line}: ${key} is only valid for public_reference_rewrite`);
  const out={id,category,domain,origin,text,source_project:sourceProject};
  for(const key of ["reference_uri","reference_title","reference_license","notes"]){const value=row[key];if(typeof value==="string"&&value.trim())out[key]=value.trim();}
  out.text_sha256=await sha256Text(text);return out;
}
async function loadCorpusSamples(inputPath){
  const source=await core.fs.readText(inputPath);if(source==null)throw new Error(`RiftCorpus input not found: ${inputPath}`);
  const samples=[],ids=new Set(),textHashes=new Map(),lines=String(source).split(/\r?\n/);
  for(let index=0;index<lines.length;index++){
    const raw=lines[index];if(!raw.trim())continue;let parsed;try{parsed=JSON.parse(raw);}catch(error){throw new Error(`line ${index+1}: invalid JSON: ${error?.message||error}`);}
    const sample=await validateCorpusSample(parsed,index+1);if(ids.has(sample.id))throw new Error(`line ${index+1}: duplicate sample id ${sample.id}`);ids.add(sample.id);
    const prior=textHashes.get(sample.text_sha256);if(prior)throw new Error(`line ${index+1}: duplicate text matches sample ${prior}`);textHashes.set(sample.text_sha256,sample.id);
    samples.push(sample);if(samples.length>CORPUS_MAX_SAMPLES)throw new Error(`corpus exceeds ${CORPUS_MAX_SAMPLES} samples`);
  }
  if(!samples.length)throw new Error("corpus contains no samples");return {source,samples};
}
async function corpusSplitBucket(sample,seed){
  const digest=new Uint8Array(await crypto.subtle.digest("SHA-256",utf8.encode(`${seed}\0${sample.id}\0${sample.text_sha256}`)));
  let value=0;for(let index=0;index<8;index++)value=(value*256+digest[index])%10000;return value;
}
async function splitCorpus(samples,seed,heldoutPermyriad){
  const heldoutValue=Number(heldoutPermyriad);if(!Number.isInteger(heldoutValue)||heldoutValue<1||heldoutValue>5000)throw new Error("heldoutPermyriad must be an integer in 1..5000");
  const train=[],heldout=[];for(const sample of [...samples].sort((a,b)=>a.id.localeCompare(b.id)))(await corpusSplitBucket(sample,seed)<heldoutValue?heldout:train).push(sample);
  for(const category of CORPUS_CATEGORIES){if(!train.some(row=>row.category===category))throw new Error(`training split has no ${category} samples; add more custom data`);if(!heldout.some(row=>row.category===category))throw new Error(`held-out split has no ${category} samples; add more custom data`);}
  return {train,heldout};
}
function corpusCounts(rows,key){const counts={};for(const row of rows){const value=String(row[key]||"");counts[value]=(counts[value]||0)+1;}return Object.fromEntries(Object.keys(counts).sort().map(key=>[key,counts[key]]));}
function escapeCorpusTsv(text){return String(text).replace(/\\/g,"\\\\").replace(/\t/g,"\\t").replace(/\r/g,"\\r").replace(/\n/g,"\\n");}
async function ensureCorpusDir(path){if(!(await core.fs.stat(path)))await core.fs.mkdir(path);const stat=await core.fs.stat(path);if(!stat||stat.kind!=="directory")throw new Error(`RiftCorpus output is not a directory: ${path}`);}
async function corpusBuild(input=CORPUS_DEFAULT_INPUT,outputDir=CORPUS_DEFAULT_OUTPUT,options={}){
  const inputPath=normalizeCorpusPath(input,CORPUS_DEFAULT_INPUT),target=normalizeCorpusPath(outputDir,CORPUS_DEFAULT_OUTPUT),seed=String(options.seed||CORPUS_DEFAULT_SEED),heldoutPermyriad=options.heldoutPermyriad==null?CORPUS_DEFAULT_HELDOUT:Number(options.heldoutPermyriad);
  if(target===inputPath||inputPath.startsWith(`${target}/`))throw new Error("RiftCorpus output cannot contain its input file");
  const {source,samples}=await loadCorpusSamples(inputPath),{train,heldout}=await splitCorpus(samples,seed,heldoutPermyriad);
  const trainText=train.map(canonicalJson).join("\n")+"\n",heldoutJsonl=heldout.map(canonicalJson).join("\n")+"\n",heldoutTsv=heldout.map(row=>`${row.category}\t${escapeCorpusTsv(row.text)}`).join("\n")+"\n";
  const manifest={format:CORPUS_FORMAT,builder:CORPUS_BUILDER,splitAlgorithm:"sha256(seed\\0id\\0text_sha256)-u64-mod10000",splitSeed:seed,heldoutPermyriad,inputSha256:await sha256Text(source),sampleCount:samples.length,trainCount:train.length,heldoutCount:heldout.length,categoryCounts:corpusCounts(samples,"category"),domainCounts:corpusCounts(samples,"domain"),originCounts:corpusCounts(samples,"origin"),trainSha256:await sha256Text(trainText),heldoutJsonlSha256:await sha256Text(heldoutJsonl),heldoutTsvSha256:await sha256Text(heldoutTsv),unfinishedRiftSourceExcluded:[...CORPUS_BLOCKED_PROJECTS].sort(),normalization:"identity-utf8"};
  const manifestText=JSON.stringify(sortedJsonValue(manifest),null,2)+"\n",manifestSha256=await sha256Text(manifestText);
  const stage=normalizeCorpusPath(`${target}.stage-${Date.now()}-${Math.random().toString(36).slice(2,10)}`),backup=normalizeCorpusPath(`${target}.backup-${Date.now()}-${Math.random().toString(36).slice(2,10)}`);
  await ensureCorpusDir(stage);
  try{
    await core.fs.writeText(`${stage}/train.jsonl`,trainText);await core.fs.writeText(`${stage}/heldout.jsonl`,heldoutJsonl);await core.fs.writeText(`${stage}/heldout.tsv`,heldoutTsv);await core.fs.writeText(`${stage}/corpus-manifest.json`,manifestText);
    const existing=await core.fs.stat(target);if(existing){if(existing.kind!=="directory")throw new Error(`RiftCorpus output exists and is not a directory: ${target}`);await core.fs.move(target,backup,{overwrite:false});}
    try{await core.fs.move(stage,target,{overwrite:false});}catch(error){if(await core.fs.stat(backup))await core.fs.move(backup,target,{overwrite:false}).catch(()=>{});throw error;}
    let backupCleanupPending=false;
    if(await core.fs.stat(backup))backupCleanupPending=!(await core.fs.remove(backup).catch(()=>false));
    return {...manifest,manifestSha256,inputPath,outputDir:target,backupCleanupPending};
  }catch(error){if(await core.fs.stat(stage))await core.fs.remove(stage).catch(()=>{});throw error;}

}
async function corpusStatus(outputDir=CORPUS_DEFAULT_OUTPUT){
  const target=normalizeCorpusPath(outputDir,CORPUS_DEFAULT_OUTPUT),manifestPath=`${target}/corpus-manifest.json`,stat=await core.fs.stat(manifestPath);if(!stat)return {available:false,outputDir:target};
  const text=await core.fs.readText(manifestPath);let manifest;try{manifest=JSON.parse(text);}catch{throw new Error(`Invalid RiftCorpus manifest: ${manifestPath}`);}
  return {available:true,outputDir:target,manifest,manifestSha256:await sha256Text(text)};
}

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
  if(cmd==="help")return print(`RiftLLM standalone Dev API bridge\nriftllm-agent status\nriftllm-agent pair\nriftllm-agent unpair\nriftllm-agent sync <project-path>\nriftllm-agent sync-missing <project-path>\nriftllm-agent load <project-path>\nriftllm-agent staged\nriftllm-agent stage <project-path> <text>\nriftllm-agent stage-file <project-path> <riftfs-source-file>\nriftllm-agent delete <project-path>\nriftllm-agent unstage <project-path>\nriftllm-agent reset\nriftllm-agent snapshot [note]\nriftllm-agent snapshots [limit]\nriftllm-agent get-snapshot [id|latest]\nriftllm-agent benchmarks [limit]\nriftllm-agent benchmark [record-id|latest]\nriftllm-agent corpus-build [input] [output-dir] [heldout-permyriad] [seed]\nriftllm-agent corpus-status [output-dir]\nriftllm-agent preview [id|latest]\nriftllm-agent publish [id|latest]\nriftllm-agent ack <id|latest> <workspace-history-id>\nCorpus commands are local-only and confined to /workspace/RiftLLM/tokenizer/private. Pairing token is entered only in the local secure prompt, never as a shell argument.`);
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
  if(cmd==="corpus-build"){
    if(list.length>4)throw new Error("usage: riftllm-agent corpus-build [input] [output-dir] [heldout-permyriad] [seed]");
    const input=list[0]||CORPUS_DEFAULT_INPUT,output=list[1]||CORPUS_DEFAULT_OUTPUT,heldout=list[2]===undefined?CORPUS_DEFAULT_HELDOUT:Number(list[2]),seed=list[3]||CORPUS_DEFAULT_SEED;
    return show(await corpusBuild(input,output,{heldoutPermyriad:heldout,seed}));
  }
  if(cmd==="corpus-status"){if(list.length>1)throw new Error("usage: riftllm-agent corpus-status [output-dir]");return show(await corpusStatus(list[0]||CORPUS_DEFAULT_OUTPUT));}
  if(cmd==="preview")return show(await preview(list[0]||"latest"));
  if(cmd==="publish")return show(await publish(list[0]||"latest"));
  if(cmd==="ack"){if(list.length<2)throw new Error("usage: riftllm-agent ack <id|latest> <workspace-history-id>");const {snapshotId}=await concreteSnapshot(list[0]);return show(await acknowledge(snapshotId,list[1],{recovery:true}));}
  throw new Error(`unknown riftllm-agent command: ${cmd}`);
}

globalThis.RiftLlmBridge=Object.freeze({version:1,status,pair,unpair,sync,syncMissing,load,listStaged,stage,stageDelete,unstage,reset,snapshot,listSnapshots,getSnapshot,listBenchmarks,getBenchmark,corpusBuild,corpusStatus,preview,publish,acknowledge,run});
