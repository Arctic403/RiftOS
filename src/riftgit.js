const core=window.RiftOSCore;
if(!core)throw new Error("RiftOSCore must load before RiftGit");

const META_NAME=".riftgit.json";
const CURRENT_PATH="/home/.riftgit-current";
const MAX_FILE=512*1024;
const MAX_TOTAL=8*1024*1024;
const MAX_FILES=500;
const TEXT_NAMES=new Set(["README","README.md","LICENSE","LICENSE.md","Makefile","Dockerfile","Gemfile","Procfile"]);
const TEXT_EXT=new Set(["js","mjs","cjs","ts","tsx","jsx","json","html","htm","css","scss","sass","less","md","txt","xml","svg","yml","yaml","toml","ini","cfg","conf","env","sh","bash","zsh","py","rb","php","java","kt","kts","c","h","cc","cpp","cxx","hpp","hh","cs","go","rs","swift","sql","graphql","gql","vue","svelte","astro","properties","gradle"]);

const fs={
  async get(path){await core.ready;return core.fs.get(path);},
  async write(path,content){await core.ready;return core.fs.write(path,content);},
  async remove(path){await core.ready;return core.fs.remove(path);},
  async list(path="/"){await core.ready;return core.fs.list(path,{recursive:true});}
};

function token(){return sessionStorage.getItem("riftgit-token")||"";}
function headers(extra={}){
  const out={Accept:"application/vnd.github+json","X-GitHub-Api-Version":"2022-11-28",...extra};
  if(token())out.Authorization=`Bearer ${token()}`;
  return out;
}
async function api(path,options={}){
  const response=await fetch(`https://api.github.com${path}`,{...options,headers:headers(options.headers||{})});
  if(!response.ok){
    let detail="";try{detail=(await response.json())?.message||"";}catch{}
    throw new Error(`GitHub ${response.status}${detail?`: ${detail}`:""}`);
  }
  if(response.status===204)return null;
  return response.json();
}
function parseRepo(value){
  const clean=String(value||"").trim().replace(/\.git$/i,"");
  const match=clean.match(/(?:https?:\/\/github\.com\/)?([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)$/i);
  if(!match)throw new Error("Use owner/repo or https://github.com/owner/repo");
  return {owner:match[1],repo:match[2],full:`${match[1]}/${match[2]}`};
}
function rootFor(repo){return `/home/repos/${repo.owner}/${repo.repo}`;}
function legacyRootFor(repo){return `/home/repos/${repo.repo}`;}
function isText(path,size=0){
  if(size>MAX_FILE)return false;
  const name=path.split("/").pop()||"";
  if(TEXT_NAMES.has(name))return true;
  const ext=name.includes(".")?name.split(".").pop().toLowerCase():"";
  return TEXT_EXT.has(ext);
}
function decodeBase64(content){
  const raw=atob(String(content||"").replace(/\s/g,"")),bytes=new Uint8Array(raw.length);
  for(let i=0;i<raw.length;i++)bytes[i]=raw.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}
function encodeBase64(text){
  const bytes=new TextEncoder().encode(String(text));let binary="";
  for(let i=0;i<bytes.length;i+=0x8000)binary+=String.fromCharCode(...bytes.subarray(i,i+0x8000));
  return btoa(binary);
}
async function hash(text){
  const digest=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(String(text)));
  return [...new Uint8Array(digest)].map(b=>b.toString(16).padStart(2,"0")).join("");
}
async function mapLimit(items,limit,fn){
  let next=0;
  await Promise.all(Array.from({length:Math.min(limit,items.length)},async()=>{
    while(next<items.length){const index=next++;await fn(items[index],index);}
  }));
}
async function branchInfo(repo,branch){
  return api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/branches/${encodeURIComponent(branch)}`);
}
async function treeFor(repo,branch){
  const result=await api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/git/trees/${encodeURIComponent(branch)}?recursive=1`);
  if(result.truncated)throw new Error("Repository tree is too large for the current RiftGit workspace profile.");
  return result.tree.filter(item=>item.type==="blob"&&isText(item.path,item.size||0));
}
async function blobText(repo,sha){
  const blob=await api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/git/blobs/${encodeURIComponent(sha)}`);
  if(blob.encoding!=="base64")throw new Error("Unsupported GitHub blob encoding");
  const text=decodeBase64(blob.content);
  if(text.includes("\u0000"))throw new Error("binary");
  return text;
}
async function findMetaRoot(repo){
  for(const root of [rootFor(repo),legacyRootFor(repo)]){
    const file=await fs.get(`${root}/${META_NAME}`);
    if(file)return {root,file};
  }
  return null;
}
async function loadMeta(){
  const current=await fs.get(CURRENT_PATH);
  if(!current)throw new Error("No current repo. Run: git clone owner/repo");
  const file=await fs.get(`${current.content}/${META_NAME}`);
  if(!file)throw new Error("Current repository metadata is missing");
  return JSON.parse(file.content);
}
async function saveMeta(meta){
  await fs.write(`${meta.root}/${META_NAME}`,JSON.stringify(meta,null,2));
  await fs.write(CURRENT_PATH,meta.root);
}
async function localFileMap(meta){
  const rows=await fs.list(meta.root);
  const map=new Map();
  for(const row of rows){
    if(row.kind!=="file"||row.path===`${meta.root}/${META_NAME}`)continue;
    const rel=row.path.slice(meta.root.length+1);if(rel)map.set(rel,row);
  }
  return map;
}

async function importBranch(meta,branch,print,{replace=true}={}){
  const repo={owner:meta.owner,repo:meta.repo},info=await branchInfo(repo,branch);
  let tree=await treeFor(repo,branch);
  if(tree.length>MAX_FILES)tree=tree.slice(0,MAX_FILES);
  let total=0;tree=tree.filter(item=>{total+=item.size||0;return total<=MAX_TOTAL;});
  if(replace){
    for(const path of Object.keys(meta.tracked||{}))await fs.remove(`${meta.root}/${path}`).catch(()=>{});
  }
  const tracked={};let done=0,skipped=0;
  await mapLimit(tree,6,async item=>{
    try{
      const text=await blobText(repo,item.sha);
      await fs.write(`${meta.root}/${item.path}`,text);
      tracked[item.path]={blobSha:item.sha,baseHash:await hash(text)};
    }catch{skipped++;}
    done++;if(print&&(done%30===0||done===tree.length))print(`  ${done}/${tree.length} files`);
  });
  meta.branch=branch;meta.headSha=info.commit.sha;meta.tracked=tracked;meta.updatedAt=Date.now();
  await saveMeta(meta);
  return {imported:Object.keys(tracked).length,skipped,headSha:info.commit.sha};
}

async function clone(repoArg,branchArg,print){
  const repo=parseRepo(repoArg),info=await api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}`),branch=branchArg||info.default_branch||"main";
  const meta={format:"riftgit-v2",owner:repo.owner,repo:repo.repo,full:repo.full,branch,root:rootFor(repo),headSha:null,tracked:{},clonedAt:Date.now(),updatedAt:Date.now()};
  print(`Cloning ${repo.full}#${branch} into ${meta.root}...`);
  await core.fs.remove(meta.root).catch(()=>{});
  await core.fs.mkdir(meta.root);
  const result=await importBranch(meta,branch,print,{replace:true});
  print(`Done. ${result.imported} text files imported${result.skipped?`, ${result.skipped} skipped`:""}.`);
}

async function status(print=()=>{},quiet=false){
  const meta=await loadMeta(),local=await localFileMap(meta),modified=[],deleted=[],untracked=[];
  for(const [path,base] of Object.entries(meta.tracked||{})){
    const row=local.get(path);
    if(!row){deleted.push(path);continue;}
    const file=await fs.get(`${meta.root}/${path}`);
    if(await hash(file?.content||"")!==base.baseHash)modified.push(path);
    local.delete(path);
  }
  for(const path of local.keys())untracked.push(path);
  if(!quiet){
    print(`On ${meta.full} / ${meta.branch}`);
    if(!modified.length&&!deleted.length&&!untracked.length)print("working tree clean");
    modified.forEach(path=>print(` M ${path}`));deleted.forEach(path=>print(` D ${path}`));untracked.forEach(path=>print(`?? ${path}`));
  }
  return {meta,modified,deleted,untracked};
}

async function pull(print){
  const state=await status(()=>{},true);
  if(state.modified.length||state.deleted.length||state.untracked.length)throw new Error("Working tree has local changes. Commit/push or discard them before pulling.");
  const meta=state.meta,repo={owner:meta.owner,repo:meta.repo},info=await branchInfo(repo,meta.branch);
  if(info.commit.sha===meta.headSha){print("Already up to date.");return;}
  print(`Pulling ${meta.full}#${meta.branch}...`);
  const result=await importBranch(meta,meta.branch,print,{replace:true});
  print(`Pull complete at ${result.headSha.slice(0,12)}.`);
}

async function createBlob(repo,text){
  return api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/git/blobs`,{
    method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({content:encodeBase64(text),encoding:"base64"})
  });
}
async function atomicPush(message,print){
  if(!token())throw new Error("Push needs GitHub auth. Run: git auth");
  const state=await status(()=>{},true),{meta,modified,deleted,untracked}=state,changes=[...modified,...deleted,...untracked];
  if(!changes.length){print("nothing to push");return;}
  const repo={owner:meta.owner,repo:meta.repo},remote=await branchInfo(repo,meta.branch);
  if(meta.headSha&&remote.commit.sha!==meta.headSha)throw new Error("Remote branch changed since the last clone/pull. Run: git pull");
  const headCommit=await api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/git/commits/${encodeURIComponent(remote.commit.sha)}`);
  const treeEntries=[],newBlobShas=new Map();
  print(`Creating one commit with ${changes.length} change(s)...`);
  for(const path of [...modified,...untracked]){
    const file=await fs.get(`${meta.root}/${path}`);if(!file)continue;
    const blob=await createBlob(repo,file.content);newBlobShas.set(path,blob.sha);
    treeEntries.push({path,mode:"100644",type:"blob",sha:blob.sha});
  }
  for(const path of deleted)treeEntries.push({path,mode:"100644",type:"blob",sha:null});
  const tree=await api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/git/trees`,{
    method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({base_tree:headCommit.tree.sha,tree:treeEntries})
  });
  const commit=await api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/git/commits`,{
    method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({message:message||"RiftOS workspace update",tree:tree.sha,parents:[remote.commit.sha]})
  });
  await api(`/repos/${encodeURIComponent(repo.owner)}/${encodeURIComponent(repo.repo)}/git/refs/heads/${meta.branch.split("/").map(encodeURIComponent).join("/")}`,{
    method:"PATCH",headers:{"Content-Type":"application/json"},body:JSON.stringify({sha:commit.sha,force:false})
  });
  for(const path of deleted)delete meta.tracked[path];
  for(const path of [...modified,...untracked]){
    const file=await fs.get(`${meta.root}/${path}`);
    meta.tracked[path]={blobSha:newBlobShas.get(path),baseHash:await hash(file?.content||"")};
  }
  meta.headSha=commit.sha;meta.updatedAt=Date.now();await saveMeta(meta);
  print(`Push complete: ${commit.sha.slice(0,12)} · one Git commit.`);
}

async function use(repoArg,print){
  const repo=parseRepo(repoArg),found=await findMetaRoot(repo);
  if(!found)throw new Error(`Repo is not cloned in RiftFS: ${repo.full}`);
  const meta=JSON.parse(found.file.content);meta.root=found.root;await saveMeta(meta);
  print(`Current repo: ${meta.full}#${meta.branch}\n${meta.root}`);
}

async function switchBranch(branch,print){
  if(!branch)throw new Error("usage: git switch <branch>");
  const state=await status(()=>{},true);
  if(state.modified.length||state.deleted.length||state.untracked.length)throw new Error("Working tree has local changes. Push/discard them before switching branches.");
  if(branch===state.meta.branch){print(`Already on ${branch}`);return;}
  print(`Switching ${state.meta.full} to ${branch}...`);
  const result=await importBranch(state.meta,branch,print,{replace:true});
  print(`Now on ${branch} at ${result.headSha.slice(0,12)}.`);
}

async function listBranches(print){
  const meta=await loadMeta(),rows=await api(`/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/branches?per_page=100`);
  rows.forEach(row=>print(`${row.name===meta.branch?"*":" "} ${row.name}`));
}

async function run(args,print=console.log){
  const cmd=(args.shift()||"help").toLowerCase();
  if(cmd==="help")return print(`RiftGit / True OS
git auth
git logout
git clone owner/repo [branch]
git use owner/repo
git repo
git status
git pull
git push [commit message]
git branches
git switch <branch>

Workspaces live in /home/repos/<owner>/<repo>.
Push creates one atomic Git commit for the full RiftFS change set.`);
  if(cmd==="auth"){
    const value=prompt("GitHub token for this RiftOS session only. It is kept in sessionStorage:","");
    if(!value)return print("auth cancelled");
    sessionStorage.setItem("riftgit-token",value.trim());
    const me=await api("/user");return print(`Authenticated as ${me.login}. Token is session-only.`);
  }
  if(cmd==="logout"){sessionStorage.removeItem("riftgit-token");return print("GitHub session cleared.");}
  if(cmd==="clone"){if(!args[0])throw new Error("usage: git clone owner/repo [branch]");return clone(args[0],args[1],print);}
  if(cmd==="use"){if(!args[0])throw new Error("usage: git use owner/repo");return use(args[0],print);}
  if(cmd==="repo"){const meta=await loadMeta();return print(`${meta.full}#${meta.branch}\n${meta.root}`);}
  if(cmd==="status")return status(print);
  if(cmd==="pull")return pull(print);
  if(cmd==="push")return atomicPush(args.join(" "),print);
  if(cmd==="branches"||cmd==="branch")return listBranches(print);
  if(cmd==="switch"||cmd==="checkout")return switchBranch(args[0],print);
  throw new Error(`unknown RiftGit command: ${cmd}`);
}

window.RiftGit=Object.freeze({
  run,
  status:(print)=>status(print||console.log),
  clone:(repo,branch,print)=>clone(repo,branch,print||console.log),
  pull:(print)=>pull(print||console.log),
  push:(message,print)=>atomicPush(message,print||console.log),
  get token(){return token();}
});

console.info("[RiftGit] True OS filesystem bridge ready");
