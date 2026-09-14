const core=window.RiftOSCore;
const workspace=window.RiftWorkspace;
if(!core?.fs||!workspace?.available)throw new Error("Rift Dev Lab requires RiftOSCore + RiftWorkspace");

const LAB_ROOT="/system/devlab";
const STAGE_ROOT=`${LAB_ROOT}/stage`;
const SNAPSHOT_ROOT=`${LAB_ROOT}/snapshots`;
const RUN_ROOT=`${LAB_ROOT}/runs`;
const PUBLICATION_ROOT=`${LAB_ROOT}/publications`;
const STATE_PATH=`${LAB_ROOT}/state.json`;
const PROJECT_ROOT="RiftOS-main";
const TARGET_REPO="Arctic403/RiftOS";
const TARGET_BRANCH="main";
const MAX_TEXT_BYTES=2*1024*1024;
const TEXT_EXTENSIONS=new Set(["txt","md","markdown","json","jsonl","js","mjs","cjs","ts","tsx","jsx","css","html","htm","xml","svg","csv","tsv","yaml","yml","toml","ini","conf","cfg","log","kt","kts","java","py","rs","go","c","cc","cpp","cxx","h","hpp","hh","cs","sh","bash","zsh","gradle","properties"]);
const activeCss=new Map();
let readyPromise=null;

const escapeHTML=value=>String(value??"").replace(/[&<>"']/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[ch]));
const nowISO=()=>new Date().toISOString();
const uid=prefix=>`${prefix}-${Date.now()}-${(crypto.randomUUID?.()||Math.random().toString(36).slice(2)).slice(0,12)}`;
const byteSize=value=>new TextEncoder().encode(String(value??"")).byteLength;

function sourcePath(value){
  let path=String(value??"").trim().replace(/\\/g,"/").replace(/^\/+/,"");
  if(path.startsWith(PROJECT_ROOT+"/"))path=path.slice(PROJECT_ROOT.length+1);
  if(!path||path==="."||path===".."||path.includes("\0")||path.split("/").some(part=>!part||part==="."||part===".."))throw new Error("Dev Lab source path must be a safe project-relative file path");
  const name=path.split("/").pop()||"",lower=name.toLowerCase(),extension=lower.includes(".")?lower.split(".").pop():"";
  if(!TEXT_EXTENSIONS.has(extension)&&!["readme","license","makefile","dockerfile","cmakelists.txt"].includes(lower))throw new Error(`Dev Lab only stages text/source files: ${path}`);
  return path;
}
function projectPath(path){return `${PROJECT_ROOT}/${sourcePath(path)}`;}
function stagePath(path){return `${STAGE_ROOT}/${sourcePath(path)}`;}
function classify(path){
  const value=sourcePath(path).toLowerCase();
  if(value.endsWith(".css"))return {mode:"live-css",live:true,requiresRebuild:true,label:"Live CSS override + rebuild for permanence"};
  if(value.endsWith(".js")||value.endsWith(".mjs")||value.endsWith(".cjs"))return {mode:"live-script-assisted",live:false,requiresRebuild:true,label:"Stage source; prototype behavior with Live Script"};
  if(value.endsWith(".html")||value.endsWith(".htm"))return {mode:"shell-structure",live:false,requiresRebuild:true,label:"Stage shell markup; rebuild/reload required"};
  if(/\.(?:kt|kts|java|cpp|cc|cxx|c|hpp|hh|h|xml|gradle|properties)$/.test(value)||value.endsWith("androidmanifest.xml"))return {mode:"compiled-native",live:false,requiresRebuild:true,label:"Compiled/native source — rebuild required"};
  return {mode:"staged-source",live:false,requiresRebuild:true,label:"Stage source — rebuild required for installed runtime"};
}
async function sha256Text(value){
  const digest=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(String(value??"")));
  return [...new Uint8Array(digest)].map(byte=>byte.toString(16).padStart(2,"0")).join("");
}
async function ensureDir(path){
  const parts=core.path.normalize(path).split("/").filter(Boolean);let current="";
  for(const part of parts){current+=`/${part}`;await core.fs.mkdir(current).catch(()=>{});}
}
async function ensureParent(path){await ensureDir(core.path.parent(path));}
async function removeQuiet(path){await core.fs.remove(path).catch(()=>{});}
function defaultState(){return {format:"riftos-devlab-state",version:1,project:PROJECT_ROOT,targetRepo:TARGET_REPO,targetBranch:TARGET_BRANCH,baselineHeadSha:null,staged:{},latestSnapshot:null,lastPublished:null,updatedAt:nowISO()};}
async function loadState(){return {...defaultState(),...(await core.fs.readJSON(STATE_PATH,null).catch(()=>null)||{})};}
async function saveState(state){state.updatedAt=nowISO();await core.fs.writeJSON(STATE_PATH,state);return state;}
async function repoMeta(){return workspace.readJSON(`${PROJECT_ROOT}/.riftgit.json`,null).catch(()=>null);}
async function currentHeadSha(){return String((await repoMeta())?.headSha||"unknown");}

async function init(){
  if(readyPromise)return readyPromise;
  readyPromise=(async()=>{
    await core.ready;
    for(const path of [LAB_ROOT,STAGE_ROOT,SNAPSHOT_ROOT,RUN_ROOT,PUBLICATION_ROOT])await ensureDir(path);
    if(!(await core.fs.stat(STATE_PATH).catch(()=>null)))await saveState(defaultState());
    return true;
  })();
  return readyPromise;
}

async function baselineFor(path,state){
  const existing=state.staged?.[path];
  if(existing)return {base_sha256:existing.base_sha256??null,base_exists:existing.base_exists===true};
  const stat=await workspace.stat(projectPath(path)).catch(()=>null);
  if(!stat)return {base_sha256:null,base_exists:false};
  if(stat.kind!=="file")throw new Error(`Dev Lab only stages text files: ${path}`);
  const text=await workspace.readText(projectPath(path));
  return {base_sha256:await sha256Text(text),base_exists:true};
}

async function stageEdit(path,content,{reason=""}={}){
  await init();path=sourcePath(path);content=String(content??"");
  if(byteSize(content)>MAX_TEXT_BYTES)throw new Error(`Dev Lab source editor limit is ${MAX_TEXT_BYTES/1024/1024} MiB per file`);
  const state=await loadState(),baseline=await baselineFor(path,state),head=await currentHeadSha();
  if(!Object.keys(state.staged||{}).length)state.baselineHeadSha=head;
  await ensureParent(stagePath(path));await core.fs.writeText(stagePath(path),content);
  state.staged[path]={path,action:"write",base_sha256:baseline.base_sha256,base_exists:baseline.base_exists,stage_sha256:await sha256Text(content),classification:classify(path),reason:String(reason||"").slice(0,500),stagedAt:nowISO()};
  await saveState(state);return state.staged[path];
}

async function stageDelete(path,{reason=""}={}){
  await init();path=sourcePath(path);const state=await loadState();
  const prior=state.staged?.[path];
  if(prior?.base_exists===false){await unstage(path);return {path,removedNewStage:true};}
  const baseline=await baselineFor(path,state);
  if(!baseline.base_exists)throw new Error(`Cannot stage deletion of missing source: ${path}`);
  if(!Object.keys(state.staged||{}).length)state.baselineHeadSha=await currentHeadSha();
  await removeQuiet(stagePath(path));
  state.staged[path]={path,action:"delete",base_sha256:baseline.base_sha256,base_exists:true,stage_sha256:null,classification:classify(path),reason:String(reason||"").slice(0,500),stagedAt:nowISO()};
  removeLive(path);await saveState(state);return state.staged[path];
}

async function unstage(path){
  await init();path=sourcePath(path);const state=await loadState();
  delete state.staged[path];await removeQuiet(stagePath(path));removeLive(path);
  if(!Object.keys(state.staged).length)state.baselineHeadSha=null;
  await saveState(state);return true;
}

async function resetStage(){
  await init();const state=await loadState();
  for(const path of activeCss.keys())removeLive(path);
  await removeQuiet(STAGE_ROOT);await ensureDir(STAGE_ROOT);
  state.staged={};state.baselineHeadSha=null;await saveState(state);return true;
}

async function listStaged(){const state=await loadState();return Object.values(state.staged||{}).sort((a,b)=>a.path.localeCompare(b.path));}
async function stagedContent(path){
  path=sourcePath(path);const state=await loadState(),entry=state.staged?.[path];
  if(!entry||entry.action!=="write")return null;
  return core.fs.readText(stagePath(path));
}
async function loadSource(path){
  await init();path=sourcePath(path);const state=await loadState(),entry=state.staged?.[path]||null;
  let content="",exists=true;
  if(entry?.action==="write")content=String(await core.fs.readText(stagePath(path))??"");
  else {try{content=await workspace.readText(projectPath(path));}catch{content="";exists=false;}}
  return {path,content,exists,staged:entry,classification:classify(path),workspaceHeadSha:await currentHeadSha(),labBaselineHeadSha:state.baselineHeadSha};
}

async function applyLiveCss(path){
  await init();path=sourcePath(path);if(classify(path).mode!=="live-css")throw new Error("Only staged CSS files have an exact live source override; use Live Script for runtime behavior experiments");
  const content=await stagedContent(path);if(content==null)throw new Error(`Stage a CSS edit before applying it live: ${path}`);
  let style=activeCss.get(path);
  if(!style){style=document.createElement("style");style.dataset.riftDevLabPath=path;document.head.append(style);activeCss.set(path,style);}
  style.textContent=content;return {path,active:true,bytes:byteSize(content)};
}
function removeLive(path){
  try{path=sourcePath(path);}catch{return false;}
  const style=activeCss.get(path);if(!style)return false;style.remove();activeCss.delete(path);return true;
}

function safeResult(value){
  if(value===undefined)return null;
  try{return JSON.parse(JSON.stringify(value));}catch{return String(value);}
}
function liveRuntimeViews(){
  const readOnlyFs=Object.freeze({
    stat:path=>core.fs.stat(path),readText:path=>core.fs.readText(path),readJSON:(path,fallback=null)=>core.fs.readJSON(path,fallback),
    list:(path="/",options={})=>core.fs.list(path,{recursive:options.recursive!==false}),estimate:()=>core.fs.estimate()
  });
  const readOnlyWorkspace=Object.freeze({
    info:()=>workspace.info(),stat:path=>workspace.stat(path),readText:path=>workspace.readText(path),readJSON:(path,fallback=null)=>workspace.readJSON(path,fallback),
    list:(path="",options={})=>workspace.list(path,options),snapshot:(path="",options={})=>workspace.snapshot(path,options)
  });
  const runtime=Object.freeze({version:core.version,fs:readOnlyFs,processes:Object.freeze({list:()=>core.processes.list().map(item=>({...item})),get:pid=>{const item=core.processes.get(pid);return item?{...item}:null;}}),kernel:Object.freeze({uptime:()=>core.kernel.uptime(),info:()=>core.kernel.info()}),native:Object.freeze({get connected(){return core.native.connected;},capabilities:()=>core.native.capabilities()})});
  return {runtime,workspace:readOnlyWorkspace};
}
async function runScript(source){
  await init();source=String(source??"");if(byteSize(source)>MAX_TEXT_BYTES)throw new Error("Live Script exceeds the 2 MiB limit");
  const id=uid("run"),createdAt=nowISO(),logs=[];
  const lab=Object.freeze({
    log:(...values)=>logs.push(values.map(value=>typeof value==="string"?value:JSON.stringify(safeResult(value))).join(" ")),
    assert:(condition,message="assertion failed")=>{if(!condition)throw new Error(String(message));return true;},
    sleep:ms=>new Promise(resolve=>setTimeout(resolve,Math.max(0,Math.min(30000,Number(ms)||0)))),
    status:()=>status(),staged:()=>listStaged()
  });
  let ok=false,result=null,error=null;
  try{
    const AsyncFunction=Object.getPrototypeOf(async function(){}).constructor,{runtime,workspace:readOnlyWorkspace}=liveRuntimeViews();
    result=await new AsyncFunction("core","workspace","desktop","windowManager","lab",`"use strict";\n${source}`)(runtime,readOnlyWorkspace,globalThis.RiftDesktop,globalThis.RiftOSWindowManager,lab);ok=true;
  }catch(failure){error=String(failure?.stack||failure?.message||failure);}
  const record={format:"riftos-devlab-run",version:1,id,createdAt,ok,source,logs,result:safeResult(result),error};
  await core.fs.writeJSON(`${RUN_ROOT}/${id}.json`,record);return record;
}

async function listRuns(limit=20){
  await init();const rows=await core.fs.list(RUN_ROOT,{recursive:false}).catch(()=>[]),candidates=rows.filter(row=>row.kind==="file"&&row.path.endsWith(".json"));
  const values=await Promise.all(candidates.map(row=>core.fs.readJSON(row.path,null).catch(()=>null))),runs=values.filter(Boolean);
  return runs.sort((a,b)=>String(b.createdAt).localeCompare(String(a.createdAt))).slice(0,Math.max(1,Math.min(100,limit)));
}

async function createSnapshot(note=""){
  await init();const state=await loadState(),staged=Object.values(state.staged||{}).sort((a,b)=>a.path.localeCompare(b.path));
  const entries=await Promise.all(staged.map(async entry=>{const item={...entry};if(entry.action==="write")item.content=String(await core.fs.readText(stagePath(entry.path))??"");return item;}));
  if(!entries.length)throw new Error("Nothing is staged in RiftOS Dev Lab");
  const id=uid("snapshot"),createdAt=nowISO(),runs=await listRuns(10),snapshot={
    format:"riftos-devlab-snapshot",version:1,id,createdAt,note:String(note||"").slice(0,1000),project:PROJECT_ROOT,targetRepo:TARGET_REPO,targetBranch:TARGET_BRANCH,
    baselineHeadSha:state.baselineHeadSha||await currentHeadSha(),capturedHeadSha:await currentHeadSha(),entries,evidenceRuns:runs.map(run=>({id:run.id,createdAt:run.createdAt,ok:run.ok,error:run.error||null}))
  };
  await core.fs.writeJSON(`${SNAPSHOT_ROOT}/${id}.json`,snapshot);state.latestSnapshot=id;await saveState(state);return snapshot;
}
async function loadSnapshot(id){
  await init();id=String(id||"").trim();if(!/^[A-Za-z0-9._-]+$/.test(id))throw new Error("Invalid Dev Lab snapshot id");
  const snapshot=await core.fs.readJSON(`${SNAPSHOT_ROOT}/${id}.json`,null).catch(()=>null);if(!snapshot)throw new Error(`Dev Lab snapshot not found: ${id}`);return snapshot;
}
async function listSnapshots(limit=50){
  await init();const rows=await core.fs.list(SNAPSHOT_ROOT,{recursive:false}).catch(()=>[]),out=[];
  for(const row of rows.filter(row=>row.kind==="file"&&row.path.endsWith(".json"))){const value=await core.fs.readJSON(row.path,null).catch(()=>null);if(value)out.push(value);}
  return out.sort((a,b)=>String(b.createdAt).localeCompare(String(a.createdAt))).slice(0,Math.max(1,Math.min(200,limit)));
}
function snapshotPatch(snapshot){
  if(!snapshot?.entries?.length)throw new Error("Snapshot has no staged changes");
  return {
    format:"riftcity-ai-patch",version:2,title:`RiftOS Dev Lab · ${snapshot.id}`,created_at:snapshot.createdAt,target_repo:TARGET_REPO,target_branch:TARGET_BRANCH,
    changes:snapshot.entries.map(entry=>({action:entry.action,path:projectPath(entry.path),...(entry.action==="write"?{content:String(entry.content??"")}:{ }),base_sha256:entry.base_sha256??null,reason:entry.reason||`RiftOS Dev Lab snapshot ${snapshot.id}`}))
  };
}
async function previewSnapshot(id){const snapshot=typeof id==="object"?id:await loadSnapshot(id);return workspace.previewPatch(snapshotPatch(snapshot));}
async function publishSnapshot(id){
  await init();const snapshot=typeof id==="object"?id:await loadSnapshot(id);const patch=snapshotPatch(snapshot);
  const preview=await workspace.previewPatch(patch);const result=await workspace.applyPatch(patch);
  const receipt={format:"riftos-devlab-publication",version:1,id:uid("publish"),publishedAt:nowISO(),snapshotId:snapshot.id,baselineHeadSha:snapshot.baselineHeadSha,historyId:result.historyId,changes:result.changes,preview};
  await core.fs.writeJSON(`${PUBLICATION_ROOT}/${receipt.id}.json`,receipt);
  const state=await loadState();
  for(const snapEntry of snapshot.entries){
    const current=state.staged?.[snapEntry.path];if(!current||current.action!==snapEntry.action||String(current.base_sha256??"")!==String(snapEntry.base_sha256??"")||String(current.stage_sha256??"")!==String(snapEntry.stage_sha256??""))continue;
    delete state.staged[snapEntry.path];await removeQuiet(stagePath(snapEntry.path));removeLive(snapEntry.path);
  }
  if(!Object.keys(state.staged||{}).length)state.baselineHeadSha=null;
  state.lastPublished={snapshotId:snapshot.id,historyId:result.historyId,publishedAt:receipt.publishedAt};await saveState(state);
  return {...result,snapshotId:snapshot.id,receiptId:receipt.id};
}

async function status(){
  await init();const state=await loadState(),snapshots=await listSnapshots(200),runs=await listRuns(200);
  return {available:true,root:LAB_ROOT,project:PROJECT_ROOT,targetRepo:TARGET_REPO,targetBranch:TARGET_BRANCH,workspaceHeadSha:await currentHeadSha(),baselineHeadSha:state.baselineHeadSha,staged:Object.keys(state.staged||{}).length,snapshots:snapshots.length,runs:runs.length,activeLiveCss:[...activeCss.keys()],latestSnapshot:state.latestSnapshot,lastPublished:state.lastPublished};
}
function resolveRiftFsPath(value,cwd="/"){
  const raw=String(value??"").trim();if(!raw)throw new Error("RiftFS source path is required");
  return core.path.isAbsolute(raw)?core.path.normalize(raw):core.path.join(core.path.normalize(cwd||"/"),raw);
}
async function resolveSnapshotId(value){
  const requested=String(value||"latest").trim();if(requested&&requested!=="latest")return requested;
  const state=await loadState();if(state.latestSnapshot)return state.latestSnapshot;
  const latest=(await listSnapshots(1))[0]?.id;if(!latest)throw new Error("No Dev Lab snapshot is available");return latest;
}
async function executeAgentRequest(raw={}){
  await init();const request=raw&&typeof raw==="object"?raw:{};const action=String(request.action||"").trim().toLowerCase();
  if(!action)throw new Error("Dev Lab agent action is required");
  if(action==="status")return status();
  if(action==="open")return {opened:await open()};
  if(action==="load")return loadSource(request.path);
  if(action==="staged")return listStaged();
  if(action==="stage")return stageEdit(request.path,String(request.text??""),{reason:String(request.reason||"RiftOS local agent").slice(0,500)});
  if(action==="stage-file"){
    const sourcePath=resolveRiftFsPath(request.sourcePath,request.cwd),source=await core.fs.readText(sourcePath);if(source==null)throw new Error(`source file not found: ${sourcePath}`);
    return stageEdit(request.path,source,{reason:String(request.reason||`RiftOS local agent staged from ${sourcePath}`).slice(0,500)});
  }
  if(action==="delete")return stageDelete(request.path,{reason:String(request.reason||"RiftOS local agent").slice(0,500)});
  if(action==="unstage")return {unstaged:await unstage(request.path),path:sourcePath(request.path)};
  if(action==="reset")return {reset:await resetStage()};
  if(action==="css")return applyLiveCss(request.path);
  if(action==="css-off")return {removed:removeLive(request.path),path:sourcePath(request.path)};
  if(action==="run")return runScript(String(request.source??""));
  if(action==="run-file"){
    const sourcePath=resolveRiftFsPath(request.sourcePath,request.cwd),source=await core.fs.readText(sourcePath);if(source==null)throw new Error(`script file not found: ${sourcePath}`);return runScript(source);
  }
  if(action==="runs")return listRuns(Number(request.limit)||20);
  if(action==="snapshot")return createSnapshot(String(request.note||""));
  if(action==="snapshots")return listSnapshots(Number(request.limit)||50);
  if(action==="load-snapshot")return loadSnapshot(await resolveSnapshotId(request.snapshotId));
  if(action==="preview")return previewSnapshot(await resolveSnapshotId(request.snapshotId));
  if(action==="publish")return publishSnapshot(await resolveSnapshotId(request.snapshotId));
  throw new Error(`Unsupported RiftOS Dev Lab agent action: ${action}`);
}

async function open(){
  await init();const manager=globalThis.RiftOSWindowManager;if(!manager?.open)throw new Error("RiftOS window manager is unavailable");
  const body=manager.open("devlab","RiftOS Dev Lab","LIVE SOURCE LAB",{kind:"development"});
  body.innerHTML=`<div data-devlab-root style="height:100%;min-height:0;overflow:auto;padding:12px;background:#091219;color:#e9f1f5">
    <div class="trueos-head"><div><strong>RiftOS Dev Lab</strong><small>Experiment in /system/devlab · publish approved snapshots atomically to /workspace/RiftOS-main</small></div><span id="devlabStatusChip" class="trueos-chip">loading</span></div>
    <div style="display:flex;flex-wrap:wrap;gap:6px;margin-bottom:10px"><button id="devlabRefresh" class="trueos-btn">Refresh</button><button id="devlabSnapshot" class="trueos-btn primary">Snapshot</button><button id="devlabPublish" class="trueos-btn primary">Publish snapshot</button><button id="devlabReset" class="trueos-btn">Reset staged</button></div>
    <div class="trueos-card" style="margin-bottom:10px"><strong>Source staging</strong><small id="devlabProvenance">Loading provenance…</small><div style="display:grid;grid-template-columns:minmax(0,1fr) auto;gap:6px;margin-top:8px"><input id="devlabPath" class="trueos-input" value="styles.css" aria-label="Dev Lab source path"><button id="devlabLoad" class="trueos-btn">Load</button></div><div class="trueos-editor" style="height:300px;margin-top:8px"><textarea id="devlabEditor" aria-label="Dev Lab source editor"></textarea></div><div style="display:flex;flex-wrap:wrap;gap:6px;margin-top:8px"><button id="devlabStage" class="trueos-btn primary">Stage edit</button><button id="devlabDelete" class="trueos-btn">Stage delete</button><button id="devlabLiveCss" class="trueos-btn">Apply live CSS</button><button id="devlabRemoveLive" class="trueos-btn">Remove live override</button></div><pre id="devlabFileMeta" class="trueos-code" style="min-height:70px"></pre></div>
    <div class="trueos-card" style="margin-bottom:10px"><strong>Staged changes</strong><small>Project files remain untouched until Publish snapshot.</small><div id="devlabStaged" style="display:grid;gap:5px;margin-top:8px"></div></div>
    <div class="trueos-card" style="margin-bottom:10px"><strong>Live Script</strong><small>Trusted development script against the current shell runtime. core/workspace are read-only views; use lab.log(), lab.assert(), desktop and windowManager for runtime experiments.</small><div class="trueos-editor" style="height:180px;margin-top:8px"><textarea id="devlabScript" aria-label="Dev Lab live script">lab.log(await lab.status());\nlab.assert(core.native.connected, "native bridge missing");\nreturn { processes: core.processes.list().length };</textarea></div><button id="devlabRun" class="trueos-btn primary" style="margin-top:8px">Run live script</button><pre id="devlabRunOutput" class="trueos-code"></pre></div>
    <div class="trueos-card"><strong>Snapshots</strong><small>Frozen staged content + per-file baselines + evidence run references.</small><input id="devlabNote" class="trueos-input" style="width:100%;box-sizing:border-box;margin-top:8px" placeholder="snapshot note / goal" aria-label="Dev Lab snapshot note"><select id="devlabSnapshots" class="trueos-input" style="width:100%;margin-top:8px" aria-label="Dev Lab snapshots"></select><pre id="devlabOutput" class="trueos-code"></pre></div>
  </div>`;
  const q=id=>body.querySelector(id),pathInput=q("#devlabPath"),editor=q("#devlabEditor"),meta=q("#devlabFileMeta"),output=q("#devlabOutput"),runOutput=q("#devlabRunOutput"),snapSelect=q("#devlabSnapshots"),chip=q("#devlabStatusChip"),provenance=q("#devlabProvenance"),stagedBox=q("#devlabStaged");
  const show=(target,value)=>{target.textContent=typeof value==="string"?value:JSON.stringify(value,null,2);};
  async function refresh(){
    const state=await status(),staged=await listStaged(),snapshots=await listSnapshots();chip.textContent=`${state.staged} staged · ${state.snapshots} snapshots`;chip.classList.toggle("ok",state.staged>0);provenance.textContent=`workspace head ${state.workspaceHeadSha} · lab baseline ${state.baselineHeadSha||"not started"} · ${state.activeLiveCss.length} live CSS override(s)`;
    stagedBox.innerHTML=staged.length?staged.map(entry=>`<button class="trueos-btn" data-devlab-load="${escapeHTML(entry.path)}" style="text-align:left;height:auto;min-height:38px"><b>${escapeHTML(entry.action.toUpperCase())}</b> ${escapeHTML(entry.path)} · ${escapeHTML(entry.classification?.mode||"")}</button>`).join(""):"<small>Nothing staged.</small>";
    snapSelect.innerHTML=snapshots.length?snapshots.map(snapshot=>`<option value="${escapeHTML(snapshot.id)}">${escapeHTML(snapshot.id)} · ${snapshot.entries?.length||0} changes${snapshot.note?` · ${escapeHTML(snapshot.note)}`:""}</option>`).join(""):"<option value=\"\">No snapshots</option>";
    return state;
  }
  async function loadCurrent(value=pathInput.value){
    const file=await loadSource(value);pathInput.value=file.path;editor.value=file.content;show(meta,{path:file.path,exists:file.exists,stagedAction:file.staged?.action||null,classification:file.classification,baseSha256:file.staged?.base_sha256??null,stageSha256:file.staged?.stage_sha256??null});return file;
  }
  q("#devlabRefresh").onclick=()=>refresh().catch(error=>show(output,error.message));
  q("#devlabLoad").onclick=()=>loadCurrent().catch(error=>show(output,error.message));
  q("#devlabStage").onclick=async()=>{try{const entry=await stageEdit(pathInput.value,editor.value);show(output,{staged:true,entry});await refresh();await loadCurrent(entry.path);}catch(error){show(output,error.message);}};
  q("#devlabDelete").onclick=async()=>{try{const entry=await stageDelete(pathInput.value);show(output,{deleteStaged:true,entry});await refresh();await loadCurrent(pathInput.value);}catch(error){show(output,error.message);}};
  q("#devlabLiveCss").onclick=async()=>{try{show(output,await applyLiveCss(pathInput.value));await refresh();}catch(error){show(output,error.message);}};
  q("#devlabRemoveLive").onclick=()=>{show(output,{removed:removeLive(pathInput.value)});refresh().catch(()=>{});};
  stagedBox.onclick=event=>{const button=event.target.closest("[data-devlab-load]");if(button)loadCurrent(button.dataset.devlabLoad).catch(error=>show(output,error.message));};
  q("#devlabRun").onclick=async()=>{try{const run=await runScript(q("#devlabScript").value);show(runOutput,{id:run.id,ok:run.ok,logs:run.logs,result:run.result,error:run.error});await refresh();}catch(error){show(runOutput,error.message);}};
  q("#devlabSnapshot").onclick=async()=>{try{const snapshot=await createSnapshot(q("#devlabNote").value);show(output,{snapshot:snapshot.id,changes:snapshot.entries.length,baseline:snapshot.baselineHeadSha,evidenceRuns:snapshot.evidenceRuns.length});await refresh();snapSelect.value=snapshot.id;}catch(error){show(output,error.message);}};
  q("#devlabPublish").onclick=async()=>{try{if(!snapSelect.value)throw new Error("Create/select a snapshot first");const preview=await previewSnapshot(snapSelect.value);show(output,{preview});const result=await publishSnapshot(snapSelect.value);show(output,{published:true,...result});await refresh();}catch(error){show(output,`Publish aborted: ${error.message}`);}};
  q("#devlabReset").onclick=async()=>{if(globalThis.confirm&&!confirm("Clear staged Dev Lab source overrides? Snapshots and run evidence are kept."))return;try{await resetStage();show(output,"Staged Dev Lab overrides cleared; snapshots/evidence kept.");await refresh();await loadCurrent(pathInput.value);}catch(error){show(output,error.message);}};
  await refresh();await loadCurrent("styles.css");return true;
}

const api=Object.freeze({init,status,classify,loadSource,stageEdit,stageDelete,unstage,resetStage,listStaged,applyLiveCss,removeLive,runScript,listRuns,createSnapshot,listSnapshots,loadSnapshot,previewSnapshot,publishSnapshot,executeAgentRequest,open,get root(){return LAB_ROOT;},get project(){return PROJECT_ROOT;}});
window.RiftDevLab=api;
console.info("[RiftDevLab] isolated live-development workspace ready");
