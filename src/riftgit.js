const core=window.RiftOSCore;
if(!core)throw new Error("RiftOSCore must load before RiftGit");

const META_NAME=".riftgit.json";
const CURRENT_PATH="/home/.riftgit-current";
const MAX_FILE=48*1024*1024;
const MAX_TOTAL=256*1024*1024;
const MAX_FILES=10000;
const WORKSPACE_PROJECT="/workspace/RiftOS-main";
const WORKSPACE_REPO={owner:"Arctic403",repo:"RiftOS",full:"Arctic403/RiftOS"};
const WORKSPACE_BRANCH="main";

const fs={
  async get(path){await core.ready;return core.fs.get(path);},
  async stat(path){await core.ready;return core.fs.stat(path);},
  async readBase64(path){await core.ready;return core.fs.readBase64(path);},
  async writeBase64(path,base64){await core.ready;return core.fs.writeBase64(path,base64);},
  async write(path,content){await core.ready;return core.fs.writeText(path,content);},
  async mkdir(path){await core.ready;return core.fs.mkdir(path);},
  async remove(path){await core.ready;return core.fs.remove(path);},
  async move(from,to,options={}){await core.ready;return core.fs.move(from,to,options);},
  async copy(from,to,options={}){await core.ready;return core.fs.copy(from,to,options);},
  async list(path="/"){await core.ready;return core.fs.list(path,{recursive:true});}
};

function token(){return sessionStorage.getItem("riftgit-token")||"";}
function headers(extra={}){const out={Accept:"application/vnd.github+json","X-GitHub-Api-Version":"2022-11-28",...extra};if(token())out.Authorization=`Bearer ${token()}`;return out;}
async function api(path,options={}){
  const response=await fetch(`https://api.github.com${path}`,{...options,headers:headers(options.headers||{})});
  if(!response.ok){let detail="";try{detail=(await response.json())?.message||"";}catch{}throw new Error(`GitHub ${response.status}${detail?`: ${detail}`:""}`);}
  return response.status===204?null:response.json();
}
function normalizePath(value="/"){
  let raw=String(value||"/").trim().replace(/\\/g,"/");if(raw==="~"||raw.startsWith("~/"))raw=`/home${raw.slice(1)}`;
  const parts=[];for(const part of raw.split("/")){if(!part||part===".")continue;if(part===".."){parts.pop();continue;}parts.push(part);}return "/"+parts.join("/");
}
function resolvePath(cwd,value){const raw=String(value||"");return normalizePath(raw.startsWith("/")||raw.startsWith("~")?raw:`${cwd||"/home"}/${raw}`);}
function parentPath(path){const parts=normalizePath(path).split("/").filter(Boolean);parts.pop();return "/"+parts.join("/");}
function basename(path){return normalizePath(path).split("/").filter(Boolean).pop()||"repo";}
function parseRepo(value){
  const clean=String(value||"").trim().replace(/\.git$/i,"");const match=clean.match(/(?:https?:\/\/github\.com\/)?([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)$/i);
  if(!match)throw new Error("Use owner/repo or https://github.com/owner/repo");return {owner:match[1],repo:match[2],full:`${match[1]}/${match[2]}`};
}
function defaultRoot(repo){return `/home/repos/${repo.owner}/${repo.repo}`;}
function decodeBase64(content){const raw=atob(String(content||"").replace(/\s/g,"")),bytes=new Uint8Array(raw.length);for(let i=0;i<raw.length;i++)bytes[i]=raw.charCodeAt(i);return bytes;}
async function gitBlobSha(base64){
  const bytes=decodeBase64(base64),prefix=new TextEncoder().encode(`blob ${bytes.length}\0`),input=new Uint8Array(prefix.length+bytes.length);input.set(prefix);input.set(bytes,prefix.length);
  const digest=await crypto.subtle.digest("SHA-1",input);return [...new Uint8Array(digest)].map(b=>b.toString(16).padStart(2,"0")).join("");
}
async function mapLimit(items,limit,fn){
  let next=0,failure;
  await Promise.all(Array.from({length:Math.min(limit,items.length)},async()=>{
    while(!failure&&next<items.length){const index=next++;try{await fn(items[index],index);}catch(error){failure??=error;}}
  }));
  if(failure)throw failure;
}
async function branchInfo(repo,branch){return api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/branches/${encodeURIComponent(branch)}`);}
async function treeFor(repo,branch){
  const result=await api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/git/trees/${encodeURIComponent(branch)}?recursive=1`);
  if(result.truncated)throw new Error("GitHub returned a truncated tree; sync stopped so files are not silently omitted.");
  const tree=result.tree.filter(item=>item.type==="blob"),total=tree.reduce((sum,item)=>sum+Number(item.size||0),0);
  if(tree.length>MAX_FILES)throw new Error(`Repository has ${tree.length} files; RiftGit limit is ${MAX_FILES}. Nothing was changed.`);
  if(total>MAX_TOTAL)throw new Error(`Repository is ${Math.ceil(total/1048576)} MB; RiftGit limit is ${MAX_TOTAL/1048576} MB. Nothing was changed.`);
  const oversized=tree.find(item=>Number(item.size||0)>MAX_FILE);if(oversized)throw new Error(`${oversized.path} exceeds the ${MAX_FILE/1048576} MB per-file Git sync limit. Nothing was changed.`);return tree;
}
async function blobBase64(repo,sha){const blob=await api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/git/blobs/${encodeURIComponent(sha)}`);if(blob.encoding!=="base64")throw new Error("Unsupported GitHub blob encoding");return String(blob.content||"").replace(/\s/g,"");}
async function findMeta(startPath){
  let current=normalizePath(startPath||"/home");const startStat=await fs.stat(current).catch(()=>null);if(startStat?.kind==="file")current=parentPath(current);
  while(true){const file=await fs.get(`${current}/${META_NAME}`).catch(()=>null);if(file?.content){const meta=JSON.parse(file.content);meta.root=current;return meta;}if(current==="/")break;current=parentPath(current);}return null;
}
async function loadMeta(cwd){
  const nearby=await findMeta(cwd);if(nearby)return nearby;const current=await fs.get(CURRENT_PATH).catch(()=>null);
  if(current?.content){const pointed=await findMeta(current.content);if(pointed)return pointed;throw new Error(`Saved repo path no longer exists: ${current.content}. Run "git init owner/repo branch" inside the project folder.`);}
  throw new Error("No repo is attached here. cd into the project and run: git init owner/repo [branch]");
}
async function saveMeta(meta){meta.format="riftgit-v3";meta.root=normalizePath(meta.root);meta.updatedAt=Date.now();await fs.write(`${meta.root}/${META_NAME}`,JSON.stringify(meta,null,2));await fs.write(CURRENT_PATH,meta.root);}
function ignoredRelative(path){return path===META_NAME||path===".git"||path.startsWith(".git/");}
async function localFileMap(meta){
  const rows=await fs.list(meta.root),map=new Map();for(const row of rows){if(row.kind!=="file")continue;const rel=row.path.slice(meta.root.length).replace(/^\/+/,"");if(rel&&!ignoredRelative(rel))map.set(rel,row);}return map;
}
async function contentSha(meta,path){return gitBlobSha(await fs.readBase64(`${meta.root}/${path}`));}
function decodeTextBase64(base64){
  const bytes=decodeBase64(base64);
  if(bytes.slice(0,4096).some(byte=>byte===0))return null;
  try{return new TextDecoder("utf-8",{fatal:true}).decode(bytes);}catch{return null;}
}
function gitStyleDiff(path,before,after,status){
  if(before==null&&after==null)return `diff --git a/${path} b/${path}\nBinary file changed`;
  const a=String(before??"").split("\n"),b=String(after??"").split("\n");
  let prefix=0;while(prefix<a.length&&prefix<b.length&&a[prefix]===b[prefix])prefix++;
  let suffix=0;while(suffix<a.length-prefix&&suffix<b.length-prefix&&a[a.length-1-suffix]===b[b.length-1-suffix])suffix++;
  const removed=a.slice(prefix,a.length-suffix),added=b.slice(prefix,b.length-suffix),lines=[];
  lines.push(`diff --git a/${path} b/${path}`);
  lines.push(status==="added"?"--- /dev/null":`--- a/${path}`);
  lines.push(status==="deleted"?"+++ /dev/null":`+++ b/${path}`);
  lines.push(`@@ -${prefix+1},${removed.length} +${prefix+1},${added.length} @@`);
  a.slice(Math.max(0,prefix-3),prefix).forEach(line=>lines.push(` ${line}`));
  removed.slice(0,180).forEach(line=>lines.push(`-${line}`));
  added.slice(0,180).forEach(line=>lines.push(`+${line}`));
  const afterStart=b.length-suffix;b.slice(afterStart,afterStart+3).forEach(line=>lines.push(` ${line}`));
  if(removed.length+added.length>360)lines.push(`... diff truncated (${removed.length} removed / ${added.length} added lines)`);
  return lines.join("\n").slice(0,64000);
}

async function verifyCopy(from,to){
  const rows=await fs.list(from),copied=await fs.list(to);
  if(rows.length!==copied.length)throw new Error(`Incomplete project copy: ${to}`);
  for(const row of rows){
    const path=to+row.path.slice(from.length),target=await fs.stat(path);
    if(!target||target.kind!==row.kind)throw new Error(`Missing copied path: ${path}`);
    if(row.kind==="file"&&await fs.readBase64(row.path)!==await fs.readBase64(path))throw new Error(`Copied content differs: ${path}`);
  }
}
const importing=new Set();
async function importBranch(meta,branch,print){
  if(importing.has(meta.root))throw new Error("A project replacement is already running");
  importing.add(meta.root);
  try{return await replaceBranch(meta,branch,print);}finally{importing.delete(meta.root);}
}
async function replaceBranch(meta,branch,print){
  const repo={owner:meta.owner,repo:meta.repo},info=await branchInfo(repo,branch),tree=await treeFor(repo,info.commit.sha);
  const suffix=`${Date.now()}-${crypto.randomUUID()}`,stage=`${parentPath(meta.root)}/.${basename(meta.root)}.riftgit-stage-${suffix}`,backup=stage+"-backup";
  for(const item of tree)if(!item.path||item.path.startsWith("/")||item.path.includes("\\")||item.path.split("/").some(p=>!p||p==="."||p==="..")||ignoredRelative(item.path))throw new Error(`Unsafe repository path: ${item.path}`);
  await fs.mkdir(stage);const tracked={};let done=0,replacing=false,backedUp=false;
  try{
    await mapLimit(tree,4,async item=>{const base64=await blobBase64(repo,item.sha);await fs.writeBase64(`${stage}/${item.path}`,base64);tracked[item.path]={blobSha:item.sha,size:Number(item.size||0),mode:item.mode||"100644"};done++;if(print&&(done%25===0||done===tree.length))print(`  ${done}/${tree.length} files`);});
    const next={...meta,format:"riftgit-v3",branch,headSha:info.commit.sha,tracked,root:meta.root,updatedAt:Date.now()};await fs.write(`${stage}/${META_NAME}`,JSON.stringify(next,null,2));
    if(await fs.stat(meta.root)){
      await fs.copy(meta.root,backup,{overwrite:false});await verifyCopy(meta.root,backup);backedUp=true;
    }
    replacing=true;
    if(await fs.stat(meta.root))if(await fs.remove(meta.root)===false)throw new Error("Could not replace project");
    await fs.copy(stage,meta.root,{overwrite:false});await verifyCopy(stage,meta.root);await saveMeta(next);
  }catch(error){
    if(replacing){
      try{
        if(await fs.stat(meta.root))if(await fs.remove(meta.root)===false)throw new Error("Could not clear incomplete replacement");
        if(backedUp){await fs.copy(backup,meta.root,{overwrite:false});await verifyCopy(backup,meta.root);}
      }catch(recovery){throw new Error(`${error.message}. Recovery failed: ${recovery.message}. Original backup: ${backup}; staged project: ${stage}. Both retained.`);}
    }
    // Keep the complete stage and backup for explicit recovery, even after successful restoration.
    throw new Error(`${error.message}. Original project preserved; recovery files retained at ${stage}${backedUp?` and ${backup}`:""}.`);
  }
  await fs.remove(stage).catch(()=>{});if(backedUp)await fs.remove(backup).catch(()=>{});
  await checkpointWorkspaceRecords(meta.root,"git:pull",info.commit.sha);
  return {imported:tree.length,headSha:info.commit.sha};
}
async function clone(repoArg,branchArg,pathArg,print,cwd){
  const repo=parseRepo(repoArg),info=await api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}`),branch=branchArg||info.default_branch||"main",root=pathArg?resolvePath(cwd,pathArg):resolvePath(cwd,repo.repo);
  if(await fs.stat(root))throw new Error(`Destination already exists: ${root}. cd into it and use git init ${repo.full} ${branch}`);
  const meta={format:"riftgit-v3",owner:repo.owner,repo:repo.repo,full:repo.full,branch,root,headSha:null,tracked:{},clonedAt:Date.now()};print(`Cloning complete tree ${repo.full}#${branch} into ${root}...`);
  const result=await importBranch(meta,branch,print);print(`Done. ${result.imported} files at ${result.headSha.slice(0,12)}.`);
}
async function attach(repoArg,branchArg,pathArg,print,cwd){
  const repo=parseRepo(repoArg),root=resolvePath(cwd,pathArg||cwd),stat=await fs.stat(root);if(!stat||!["directory","mount"].includes(stat.kind))throw new Error(`Project folder not found: ${root}`);
  const info=await api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}`),branch=branchArg||info.default_branch||"main",branchState=await branchInfo(repo,branch),tree=await treeFor(repo,branch),tracked={};
  tree.forEach(item=>tracked[item.path]={blobSha:item.sha,size:Number(item.size||0),mode:item.mode||"100644"});const meta={format:"riftgit-v3",owner:repo.owner,repo:repo.repo,full:repo.full,branch,root,headSha:branchState.commit.sha,tracked,attachedAt:Date.now()};
  await saveMeta(meta);print(`Attached ${root}\nto ${repo.full}#${branch} at ${meta.headSha.slice(0,12)}.`);await status(print,false,root);
}
async function statusFor(meta,print=()=>{},quiet=false){
  const local=await localFileMap(meta),modified=[],deleted=[],untracked=[];
  for(const [path,base] of Object.entries(meta.tracked||{})){const row=local.get(path);if(!row){deleted.push(path);continue;}if(await contentSha(meta,path)!==base.blobSha)modified.push(path);local.delete(path);}for(const path of local.keys())untracked.push(path);
  if(!quiet){print(`On ${meta.full} / ${meta.branch}\nroot ${meta.root}`);if(!modified.length&&!deleted.length&&!untracked.length)print("working tree clean");modified.forEach(path=>print(` M ${path}`));deleted.forEach(path=>print(` D ${path}`));untracked.forEach(path=>print(`?? ${path}`));}return {meta,modified,deleted,untracked};
}
async function status(print=()=>{},quiet=false,cwd="/home"){return statusFor(await loadMeta(cwd),print,quiet);}
async function pull(print,cwd){
  const state=await status(()=>{},true,cwd);if(state.modified.length||state.deleted.length||state.untracked.length)throw new Error("Working tree has local changes. Push or discard them before pulling.");
  const meta=state.meta,repo={owner:meta.owner,repo:meta.repo},info=await branchInfo(repo,meta.branch);if(info.commit.sha===meta.headSha){print("Already up to date.");return;}
  print(`Pulling complete ${meta.full}#${meta.branch} tree...`);const result=await importBranch(meta,meta.branch,print);print(`Pull complete at ${result.headSha.slice(0,12)}.`);
}
async function createBlob(repo,base64){return api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/git/blobs`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({content:base64,encoding:"base64"})});}
async function atomicPush(message,print,cwd,initialMeta=null){
  if(!token())throw new Error("Push needs GitHub auth. Run: git auth");const state=initialMeta?await statusFor(initialMeta,()=>{},true):await status(()=>{},true,cwd),{meta,modified,deleted,untracked}=state,changes=[...modified,...deleted,...untracked];if(!changes.length){print("nothing to push");return;}
  if(changes.length>MAX_FILES)throw new Error(`Change set has ${changes.length} files; limit is ${MAX_FILES}.`);const repo={owner:meta.owner,repo:meta.repo},remote=await branchInfo(repo,meta.branch);
  if(meta.headSha&&remote.commit.sha!==meta.headSha)throw new Error("Remote branch changed since the last clone/pull. Run: git pull");const headCommit=await api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/git/commits/${encodeURIComponent(remote.commit.sha)}`);
  const treeEntries=[],newBlobShas=new Map();let total=0,done=0;print(`Uploading ${changes.length} change(s) as one atomic commit...`);
  for(const path of [...modified,...untracked]){
    const row=await fs.stat(`${meta.root}/${path}`);total+=Number(row?.size||0);if(Number(row?.size||0)>MAX_FILE)throw new Error(`${path} exceeds the ${MAX_FILE/1048576} MB per-file limit.`);if(total>MAX_TOTAL)throw new Error(`Change set exceeds the ${MAX_TOTAL/1048576} MB sync limit.`);
    const base64=await fs.readBase64(`${meta.root}/${path}`),blob=await createBlob(repo,base64);
    if(blob.sha!==await gitBlobSha(base64))throw new Error(`GitHub blob hash mismatch: ${path}`);
    newBlobShas.set(path,blob.sha);treeEntries.push({path,mode:meta.tracked?.[path]?.mode||"100644",type:"blob",sha:blob.sha});done++;if(done%25===0||done===modified.length+untracked.length)print(`  ${done}/${modified.length+untracked.length} files uploaded`);
  }
  for(const path of deleted)treeEntries.push({path,mode:"100644",type:"blob",sha:null});
  if(initialMeta){
    const current=await statusFor(meta,()=>{},true);
    for(const kind of ["modified","deleted","untracked"]){
      if(current[kind].length!==state[kind].length||current[kind].some((path,index)=>path!==state[kind][index]))throw new Error("Workspace changed during upload. Nothing was committed; review and retry.");
    }
    for(const [path,sha] of newBlobShas)if(await contentSha(meta,path)!==sha)throw new Error(`Workspace changed during upload: ${path}. Nothing was committed; retry.`);
  }
  const tree=await api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/git/trees`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({base_tree:headCommit.tree.sha,tree:treeEntries})});
  const commit=await api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/git/commits`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({message:message||meta.pendingMessage||"RiftOS workspace update",tree:tree.sha,parents:[remote.commit.sha]})});
  await api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/git/refs/heads/${meta.branch.split("/").map(encodeURIComponent).join("/")}`,{method:"PATCH",headers:{"Content-Type":"application/json"},body:JSON.stringify({sha:commit.sha,force:false})});
  for(const path of deleted)delete meta.tracked[path];for(const path of [...modified,...untracked])meta.tracked[path]={blobSha:newBlobShas.get(path),size:Number((await fs.stat(`${meta.root}/${path}`))?.size||0),mode:meta.tracked?.[path]?.mode||"100644"};
  meta.headSha=commit.sha;delete meta.pendingMessage;if(!initialMeta)await saveMeta(meta);await checkpointWorkspaceRecords(meta.root,"git:push",commit.sha);print(`Push complete: ${commit.sha.slice(0,12)} · one Git commit.`);
}
async function workspaceDiff(options={}){
  const meta=await workspaceState(),state=await statusFor(meta,()=>{},true);
  const paths=[...state.modified.map(path=>({path,status:"modified"})),...state.deleted.map(path=>({path,status:"deleted"})),...state.untracked.map(path=>({path,status:"added"}))];
  const maxFiles=Math.max(1,Math.min(80,Number(options.maxFiles||40))),maxChars=Math.max(16000,Math.min(512000,Number(options.maxChars||240000)));
  const rows=new Array(Math.min(paths.length,maxFiles));let used=0;
  await mapLimit(paths.slice(0,maxFiles),3,async(item,index)=>{
    const tracked=meta.tracked?.[item.path];
    const before64=tracked?.blobSha?await blobBase64({owner:meta.owner,repo:meta.repo},tracked.blobSha):null;
    const after64=item.status!=="deleted"?await fs.readBase64(`${meta.root}/${item.path}`):null;
    const before=before64==null?"":decodeTextBase64(before64),after=after64==null?"":decodeTextBase64(after64);
    const binary=(before64!=null&&before==null)||(after64!=null&&after==null);
    let diff=binary?`diff --git a/${item.path} b/${item.path}\nBinary file changed`:gitStyleDiff(item.path,before,after,item.status);
    if(used>=maxChars)diff="... diff omitted by response limit";else if(used+diff.length>maxChars)diff=diff.slice(0,maxChars-used)+"\n... diff truncated";
    used+=diff.length;
    rows[index]={path:item.path,status:item.status,binary,diff};
  });
  return {format:"riftgit-workspace-diff-v1",repo:meta.full,branch:meta.branch,headSha:meta.headSha,root:meta.root,summary:{modified:state.modified.length,deleted:state.deleted.length,untracked:state.untracked.length,total:paths.length,returned:rows.length,truncated:paths.length>rows.length},files:rows};
}

async function checkpointWorkspaceRecords(root,reason,headSha){
  const normalized=normalizePath(root);
  if(normalized!=="/workspace"&&!normalized.startsWith("/workspace/"))return;
  if(typeof core?.native?.call!=="function")return;
  const gitRoot=normalized.slice("/workspace/".length);
  await core.native.call("workspace.records.checkpoint",{reason,gitRoot,gitHeadSha:headSha||""}).catch(()=>{});
}

async function workspaceState(){
  const root=await fs.stat(WORKSPACE_PROJECT);
  if(root?.kind!=="directory")throw new Error(`Workspace project folder not found: ${WORKSPACE_PROJECT}`);
  const info=await branchInfo(WORKSPACE_REPO,WORKSPACE_BRANCH),tree=await treeFor(WORKSPACE_REPO,info.commit.sha),tracked={};
  for(const item of tree)if(!ignoredRelative(item.path))tracked[item.path]={blobSha:item.sha,size:Number(item.size||0),mode:item.mode||"100644"};
  return {format:"riftgit-v3",...WORKSPACE_REPO,branch:WORKSPACE_BRANCH,root:WORKSPACE_PROJECT,headSha:info.commit.sha,tracked};
}
let workspacePushRunning=false;
async function workspaceCommand(args,print){
  const command=(args.shift()||"status").toLowerCase();
  if(!["status","push"].includes(command))throw new Error("usage: workspace [status|push [commit message]]");
  if(command==="status"&&args.length)throw new Error("usage: workspace status");
  if(command==="push"){
    if(workspacePushRunning)throw new Error("A workspace push is already running");
    if(!token())throw new Error("Push needs GitHub auth. Run: git auth");
    workspacePushRunning=true;
    try{
      const meta=await workspaceState();
      print(`Publishing ${meta.root} → ${meta.full}#${meta.branch} (remote ${meta.headSha.slice(0,12)})`);
      return await atomicPush(args.join(" ").trim()||"Update RiftOS workspace",print,meta.root,meta);
    }finally{workspacePushRunning=false;}
  }
  return statusFor(await workspaceState(),print,false);
}
async function sync(message,print,cwd){
  const state=await status(()=>{},true,cwd),repo={owner:state.meta.owner,repo:state.meta.repo},remote=await branchInfo(repo,state.meta.branch),dirty=state.modified.length+state.deleted.length+state.untracked.length;
  if(remote.commit.sha!==state.meta.headSha){
    if(dirty)throw new Error("Remote and local files both changed. Pull or resolve the local changes before git sync.");
    print(`Remote advanced to ${remote.commit.sha.slice(0,12)}; pulling first...`);return pull(print,cwd);
  }
  if(!dirty){print("Already synchronized.");return;}
  return atomicPush(message,print,cwd);
}
async function use(value,print,cwd){
  let meta;if(String(value||"").startsWith("/")||String(value||"").startsWith("."))meta=await findMeta(resolvePath(cwd,value));else{const repo=parseRepo(value);for(const root of [defaultRoot(repo),`/home/repos/${repo.repo}`]){meta=await findMeta(root);if(meta)break;}}
  if(!meta)throw new Error(`No attached RiftGit repository found for ${value}`);await saveMeta(meta);print(`Current repo: ${meta.full}#${meta.branch}\n${meta.root}`);
}
async function switchBranch(branch,print,cwd){
  if(!branch)throw new Error("usage: git switch <branch>");const state=await status(()=>{},true,cwd);if(state.modified.length||state.deleted.length||state.untracked.length)throw new Error("Working tree has local changes. Push/discard them before switching branches.");
  if(branch===state.meta.branch){print(`Already on ${branch}`);return;}print(`Switching ${state.meta.full} to ${branch}...`);const result=await importBranch(state.meta,branch,print);print(`Now on ${branch} at ${result.headSha.slice(0,12)}.`);
}
async function listBranches(print,cwd){const meta=await loadMeta(cwd),rows=await api(`/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/branches?per_page=100`);rows.forEach(row=>print(`${row.name===meta.branch?"*":" "} ${row.name}`));}

async function run(input,print=console.log,context={}){
  const args=[...input];let cwd=normalizePath(context.cwd||"/home");if(args[0]==="-C"){if(!args[1])throw new Error("usage: git -C <folder> <command>");cwd=resolvePath(cwd,args[1]);args.splice(0,2);}const cmd=(args.shift()||"help").toLowerCase();
  if(cmd==="help")return print(`RiftGit / full RiftFS sync\ngit auth | logout\ngit workspace status                compare /workspace/RiftOS-main to Arctic403/RiftOS main\ngit workspace push [message]       publish workspace to main in one commit\ngit clone owner/repo [branch] [destination]\ngit init owner/repo [branch] [folder]   attach an existing folder\ngit use <folder|owner/repo>\ngit root | repo | status | pull\ngit commit -m <message>\ngit push [commit message]\ngit sync [commit message]            pull or atomic push in one command\ngit branches | switch <branch>\ngit -C <folder> <command>\n\nCommands use the shell's current directory. Repositories can live under /home, /workspace, or a mounted Android folder. Full directory trees and binary files are synchronized atomically.`);
  if(cmd==="workspace")return workspaceCommand(args,print);
  if(cmd==="auth"){const value=prompt("GitHub token for this RiftOS session only:","");if(!value)return print("auth cancelled");sessionStorage.setItem("riftgit-token",value.trim());const me=await api("/user");return print(`Authenticated as ${me.login}. Token is session-only.`);}
  if(cmd==="logout"){sessionStorage.removeItem("riftgit-token");return print("GitHub session cleared.");}
  if(cmd==="clone"){if(!args[0])throw new Error("usage: git clone owner/repo [branch] [destination]");return clone(args[0],args[1],args[2],print,cwd);}
  if(cmd==="init"||cmd==="attach"){if(!args[0])throw new Error("usage: git init owner/repo [branch] [folder]");return attach(args[0],args[1],args[2],print,cwd);}
  if(cmd==="use"){if(!args[0])throw new Error("usage: git use <folder|owner/repo>");return use(args[0],print,cwd);}
  if(cmd==="root"||cmd==="repo"){const meta=await loadMeta(cwd);return print(`${meta.full}#${meta.branch}\n${meta.root}`);}
  if(cmd==="status")return status(print,false,args[0]?resolvePath(cwd,args[0]):cwd);if(cmd==="pull")return pull(print,cwd);
  if(cmd==="add")return print("RiftGit tracks the complete attached folder automatically; use git status, then git commit -m <message> or git push <message>.");
  if(cmd==="commit"){
    const state=await status(()=>{},true,cwd),message=(args[0]==="-m"?args.slice(1):args).join(" ").trim();if(!message)throw new Error("usage: git commit -m <message>");if(!state.modified.length&&!state.deleted.length&&!state.untracked.length)return print("nothing to commit");
    state.meta.pendingMessage=message;await saveMeta(state.meta);return print(`Commit message saved for ${state.modified.length+state.deleted.length+state.untracked.length} change(s). Run git push.`);
  }
  if(cmd==="push")return atomicPush(args.join(" "),print,cwd);if(cmd==="sync")return sync(args.join(" "),print,cwd);if(cmd==="branches"||cmd==="branch")return listBranches(print,cwd);if(cmd==="switch"||cmd==="checkout")return switchBranch(args[0],print,cwd);throw new Error(`unknown RiftGit command: ${cmd}`);
}

window.RiftGit=Object.freeze({run,status:(print,cwd)=>status(print||console.log,false,cwd||"/home"),clone:(repo,branch,path,print,cwd)=>clone(repo,branch,path,print||console.log,cwd||"/home"),pull:(print,cwd)=>pull(print||console.log,cwd||"/home"),push:(message,print,cwd)=>atomicPush(message,print||console.log,cwd||"/home"),workspace:(args,print)=>workspaceCommand([...args],print||console.log),workspaceDiff,get token(){return token();}});
console.info("[RiftGit] cwd-aware full-tree filesystem bridge ready");
