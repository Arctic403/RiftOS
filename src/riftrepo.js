const core=globalThis.RiftOSCore;
const vault=globalThis.RiftVault;
if(!core?.fs||!vault)throw new Error("RiftRepo requires RiftOSCore and RiftVault");

const ROOT="/system/riftrepo/v1";
const REGISTRY=`${ROOT}/registry.json`;
const DEFAULT_IGNORES=[".riftgit.json","node_modules/","android/.gradle/","android/app/build/","build/"];
const nowISO=()=>new Date().toISOString();
const uid=(prefix="repo")=>`${prefix}-${crypto.randomUUID?.()||`${Date.now()}-${Math.random().toString(36).slice(2)}`}`;
const clone=value=>value==null?value:JSON.parse(JSON.stringify(value));

async function ensure(){await core.ready;await core.fs.mkdir(ROOT);if(!(await core.fs.stat(REGISTRY)))await core.fs.writeJSON(REGISTRY,{format:"riftrepo-registry-v1",version:1,repos:[]});}
async function registry(){await ensure();return await core.fs.readJSON(REGISTRY,{format:"riftrepo-registry-v1",version:1,repos:[]});}
async function saveRegistry(value){await core.fs.writeJSON(REGISTRY,value);return value;}
function normalizeWorkspacePath(value,cwd="/workspace"){
  const raw=String(value||cwd||"/workspace").trim();const full=core.path.normalize(raw.startsWith("/")?raw:core.path.join(cwd||"/workspace",raw));
  if(full!=="/workspace"&&!full.startsWith("/workspace/"))throw new Error("RiftRepo working trees must live under /workspace");return full;
}
function repoDir(id){return `${ROOT}/repos/${id}`;}
function configPath(id){return `${repoDir(id)}/config.json`;}
function checkpointPath(id,checkpoint){return `${repoDir(id)}/checkpoints/${checkpoint}.json`;}
function manifestPath(id,manifest){return `${repoDir(id)}/manifests/${manifest}.json`;}
function ignored(relative,patterns=DEFAULT_IGNORES){return patterns.some(pattern=>pattern.endsWith("/")?relative===pattern.slice(0,-1)||relative.startsWith(pattern):relative===pattern);}
async function loadConfig(id){const config=await core.fs.readJSON(configPath(id),null);if(!config)throw new Error(`RiftRepo repository not found: ${id}`);return config;}
async function saveConfig(config){config.updatedAt=nowISO();await core.fs.writeJSON(configPath(config.id),config);const reg=await registry(),entry=reg.repos.find(row=>row.id===config.id);const summary={id:config.id,name:config.name,root:config.root,currentBranch:config.currentBranch,updatedAt:config.updatedAt};if(entry)Object.assign(entry,summary);else reg.repos.push(summary);reg.repos.sort((a,b)=>a.name.localeCompare(b.name));await saveRegistry(reg);return config;}

async function init(path,cwd="/workspace",options={}){
  await ensure();const root=normalizeWorkspacePath(path||cwd,cwd),stat=await core.fs.stat(root);if(!stat||stat.kind!=="directory")throw new Error(`Repository root is not a directory: ${root}`);
  const reg=await registry(),existing=reg.repos.find(row=>row.root===root);if(existing)return loadConfig(existing.id);
  const id=uid("repo"),name=String(options.name||core.path.basename(root)||id).trim().slice(0,96),config={format:"riftrepo-config-v1",version:1,id,name,root,createdAt:nowISO(),updatedAt:nowISO(),currentBranch:"main",branches:{main:null},tags:{},releases:[],ignores:[...DEFAULT_IGNORES]};
  for(const dir of [repoDir(id),`${repoDir(id)}/checkpoints`,`${repoDir(id)}/manifests`,`${repoDir(id)}/releases`])await core.fs.mkdir(dir);await saveConfig(config);return clone(config);
}

async function resolveRepo(value=null,cwd="/workspace"){
  const reg=await registry(),raw=String(value||"").trim();
  if(raw){const direct=reg.repos.find(row=>row.id===raw||row.name===raw||row.root===normalizeWorkspacePath(raw,cwd));if(direct)return loadConfig(direct.id);}
  const here=normalizeWorkspacePath(cwd,cwd),matches=reg.repos.filter(row=>here===row.root||here.startsWith(row.root+"/")).sort((a,b)=>b.root.length-a.root.length);if(matches[0])return loadConfig(matches[0].id);if(reg.repos.length===1)return loadConfig(reg.repos[0].id);throw new Error("No RiftRepo is selected. Run: rift repo init <workspace-path>");
}
async function listRepos(){const reg=await registry();return clone(reg.repos);}

async function scan(config,{storeObjects=false}={}){
  const rows=await core.fs.list(config.root,{recursive:true}),files=rows.filter(row=>row.kind==="file").filter(row=>{const relative=row.path.slice(config.root.length).replace(/^\/+/,"");return relative&&!ignored(relative,config.ignores||DEFAULT_IGNORES);}),entries=[];
  for(const row of files){const relative=row.path.slice(config.root.length).replace(/^\/+/,"");const ref=storeObjects?await vault.putFile(row.path,{logicalPath:`${config.id}:${relative}`}):await vault.hashFile(row.path);entries.push({path:relative,sha256:ref.sha256,size:Number(ref.size||row.size||0)});}
  entries.sort((a,b)=>a.path.localeCompare(b.path));return entries;
}
async function loadManifest(config,id){if(!id)return {format:"riftrepo-manifest-v1",id:null,entries:[]};const manifest=await core.fs.readJSON(manifestPath(config.id,id),null);if(!manifest)throw new Error(`RiftRepo manifest missing: ${id}`);return manifest;}
async function headCheckpoint(config){const id=config.branches?.[config.currentBranch]||null;return id?await core.fs.readJSON(checkpointPath(config.id,id),null):null;}
async function checkpoint(message="checkpoint",repoValue=null,cwd="/workspace"){
  const config=await resolveRepo(repoValue,cwd),entries=await scan(config,{storeObjects:true}),manifestBody={format:"riftrepo-manifest-v1",version:1,repoId:config.id,entries},manifestId=await vault.sha256Text(JSON.stringify(manifestBody));manifestBody.id=manifestId;manifestBody.createdAt=nowISO();await core.fs.writeJSON(manifestPath(config.id,manifestId),manifestBody);
  const parent=config.branches?.[config.currentBranch]||null,checkpointId=`cp-${Date.now()}-${manifestId.slice(0,12)}`,record={format:"riftrepo-checkpoint-v1",version:1,id:checkpointId,repoId:config.id,branch:config.currentBranch,parent,manifestId,message:String(message||"checkpoint").slice(0,240),createdAt:nowISO()};await core.fs.writeJSON(checkpointPath(config.id,checkpointId),record);config.branches[config.currentBranch]=checkpointId;await saveConfig(config);window.dispatchEvent?.(new CustomEvent("riftrepo:checkpoint",{detail:{repoId:config.id,checkpointId,branch:config.currentBranch}}));return clone(record);
}

function compareEntries(beforeEntries=[],afterEntries=[]){const before=new Map(beforeEntries.map(row=>[row.path,row])),after=new Map(afterEntries.map(row=>[row.path,row])),paths=[...new Set([...before.keys(),...after.keys()])].sort(),changes=[];for(const path of paths){const a=before.get(path),b=after.get(path);if(!a&&b)changes.push({path,status:"added",after:b});else if(a&&!b)changes.push({path,status:"deleted",before:a});else if(a.sha256!==b.sha256)changes.push({path,status:"modified",before:a,after:b});}return changes;}
async function status(repoValue=null,cwd="/workspace"){
  const config=await resolveRepo(repoValue,cwd),head=await headCheckpoint(config),base=await loadManifest(config,head?.manifestId||null),current=await scan(config,{storeObjects:false}),changes=compareEntries(base.entries,current);return {repo:{id:config.id,name:config.name,root:config.root},branch:config.currentBranch,head:head?.id||null,clean:changes.length===0,changes,counts:{added:changes.filter(x=>x.status==="added").length,modified:changes.filter(x=>x.status==="modified").length,deleted:changes.filter(x=>x.status==="deleted").length}};
}
async function diff(ref=null,repoValue=null,cwd="/workspace"){
  const config=await resolveRepo(repoValue,cwd),checkpointId=ref||config.branches?.[config.currentBranch]||null,record=checkpointId?await core.fs.readJSON(checkpointPath(config.id,checkpointId),null):null,base=await loadManifest(config,record?.manifestId||null),current=await scan(config,{storeObjects:false});return {repoId:config.id,base:checkpointId,changes:compareEntries(base.entries,current)};
}
async function history(limit=30,repoValue=null,cwd="/workspace"){
  const config=await resolveRepo(repoValue,cwd),out=[];let id=config.branches?.[config.currentBranch]||null;for(let i=0;id&&i<Math.max(1,Math.min(500,Number(limit)||30));i++){const row=await core.fs.readJSON(checkpointPath(config.id,id),null);if(!row)break;out.push(row);id=row.parent||null;}return clone(out);
}

async function applyManifest(config,manifest){
  const desired=new Map((manifest.entries||[]).map(row=>[row.path,row])),rows=await core.fs.list(config.root,{recursive:true}),files=rows.filter(row=>row.kind==="file");
  for(const row of files){const relative=row.path.slice(config.root.length).replace(/^\/+/,"");if(!relative||ignored(relative,config.ignores||DEFAULT_IGNORES))continue;if(!desired.has(relative))await core.fs.remove(row.path);}
  for(const entry of manifest.entries||[]){const destination=core.path.join(config.root,entry.path);await vault.restoreObject(entry.sha256,destination,{overwrite:true});}
  return {restored:true,files:(manifest.entries||[]).length};
}
async function rollback(checkpointId,repoValue=null,cwd="/workspace",{safety=true}={}){
  if(!checkpointId)throw new Error("usage: rift repo rollback <checkpoint>");const config=await resolveRepo(repoValue,cwd),target=await core.fs.readJSON(checkpointPath(config.id,checkpointId),null);if(!target)throw new Error(`Checkpoint not found: ${checkpointId}`);const manifest=await loadManifest(config,target.manifestId),safetyRecord=safety?await checkpoint(`auto safety before rollback to ${checkpointId}`,config.id,cwd):null;
  try{await applyManifest(config,manifest);config.branches[config.currentBranch]=checkpointId;await saveConfig(config);window.dispatchEvent?.(new CustomEvent("riftrepo:rollback",{detail:{repoId:config.id,checkpointId}}));return {rolledBack:true,checkpointId,safetyCheckpoint:safetyRecord?.id||null};}
  catch(error){if(safetyRecord){const safeManifest=await loadManifest(config,safetyRecord.manifestId);await applyManifest(config,safeManifest).catch(()=>{});config.branches[config.currentBranch]=safetyRecord.id;await saveConfig(config).catch(()=>{});}throw error;}
}
async function branch(name=null,repoValue=null,cwd="/workspace"){
  const config=await resolveRepo(repoValue,cwd);if(!name)return {current:config.currentBranch,branches:clone(config.branches)};const value=String(name).trim();if(!/^[A-Za-z0-9._/-]{1,120}$/.test(value)||value.includes(".."))throw new Error("Invalid branch name");if(!(value in config.branches))config.branches[value]=config.branches[config.currentBranch]||null;await saveConfig(config);return {created:value,head:config.branches[value]};
}
async function switchBranch(name,repoValue=null,cwd="/workspace"){
  const config=await resolveRepo(repoValue,cwd),value=String(name||"").trim();if(!(value in config.branches))throw new Error(`Unknown branch: ${value}`);const dirty=await status(config.id,cwd);if(!dirty.clean)throw new Error("Working tree has local RiftRepo changes. Checkpoint or rollback before switching branches.");const oldBranch=config.currentBranch,oldHead=config.branches[oldBranch]||null,head=config.branches[value];
  try{if(head){const record=await core.fs.readJSON(checkpointPath(config.id,head),null),manifest=await loadManifest(config,record.manifestId);await applyManifest(config,manifest);}config.currentBranch=value;await saveConfig(config);return {switched:true,branch:value,head:head||null};}
  catch(error){if(oldHead){const oldRecord=await core.fs.readJSON(checkpointPath(config.id,oldHead),null),oldManifest=await loadManifest(config,oldRecord.manifestId);await applyManifest(config,oldManifest).catch(()=>{});}config.currentBranch=oldBranch;await saveConfig(config).catch(()=>{});throw error;}
}
async function tag(name,repoValue=null,cwd="/workspace"){
  const config=await resolveRepo(repoValue,cwd),value=String(name||"").trim();if(!/^[A-Za-z0-9._-]{1,120}$/.test(value))throw new Error("Invalid tag name");if(config.tags[value])throw new Error(`Tag already exists: ${value}`);const head=config.branches?.[config.currentBranch];if(!head)throw new Error("Cannot tag a repository with no checkpoint");config.tags[value]=head;await saveConfig(config);return {tag:value,checkpointId:head};
}
async function release(name,repoValue=null,cwd="/workspace"){
  const config=await resolveRepo(repoValue,cwd),value=String(name||"").trim();if(!value)throw new Error("usage: rift repo release <name>");const head=config.branches?.[config.currentBranch];if(!head)throw new Error("Create a checkpoint before a release");const record={format:"riftrepo-release-v1",id:uid("release"),name:value.slice(0,160),repoId:config.id,branch:config.currentBranch,checkpointId:head,createdAt:nowISO(),artifacts:[]};await core.fs.writeJSON(`${repoDir(config.id)}/releases/${record.id}.json`,record);config.releases=[...(config.releases||[]),record.id];await saveConfig(config);return clone(record);
}
async function backup(repoValue=null,cwd="/workspace"){
  const config=await resolveRepo(repoValue,cwd),head=config.branches?.[config.currentBranch];if(!head)throw new Error("Create a checkpoint before backup");const job=await vault.backupPath(config.root);const record={repoId:config.id,checkpointId:head,vaultRef:job.ref,backedUpAt:nowISO(),provider:"local"};await core.fs.writeJSON(`${repoDir(config.id)}/last-backup.json`,record);return record;
}

async function run(args,print=console.log,context={}){
  const list=[...args],cmd=(list.shift()||"status").toLowerCase(),cwd=context.cwd||"/workspace";
  if(cmd==="help")return print("rift repo init [path] | list | status [repo] | checkpoint [message] | diff [checkpoint] | history [limit] | rollback <checkpoint> | branch [name] | switch <branch> | tag <name> | backup | release <name>");
  if(cmd==="init")return print(JSON.stringify(await init(list[0]||cwd,cwd),null,2));if(cmd==="list")return print(JSON.stringify(await listRepos(),null,2));if(cmd==="status")return print(JSON.stringify(await status(list[0]||null,cwd),null,2));
  if(cmd==="checkpoint")return print(JSON.stringify(await checkpoint(list.join(" ")||"checkpoint",null,cwd),null,2));if(cmd==="diff")return print(JSON.stringify(await diff(list[0]||null,null,cwd),null,2));if(cmd==="history")return print(JSON.stringify(await history(Number(list[0])||30,null,cwd),null,2));
  if(cmd==="rollback")return print(JSON.stringify(await rollback(list[0],null,cwd),null,2));if(cmd==="branch")return print(JSON.stringify(await branch(list[0]||null,null,cwd),null,2));if(cmd==="switch")return print(JSON.stringify(await switchBranch(list[0],null,cwd),null,2));if(cmd==="tag")return print(JSON.stringify(await tag(list[0],null,cwd),null,2));if(cmd==="backup")return print(JSON.stringify(await backup(null,cwd),null,2));if(cmd==="release")return print(JSON.stringify(await release(list.join(" "),null,cwd),null,2));
  throw new Error(`unknown rift repo command: ${cmd}`);
}

await ensure();
globalThis.RiftRepo=Object.freeze({version:1,root:ROOT,init,resolveRepo,listRepos,scan,checkpoint,status,diff,history,rollback,branch,switchBranch,tag,release,backup,run});
