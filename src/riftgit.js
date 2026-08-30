const RIFT_GIT_META = ".riftgit.json";
const RIFT_GIT_CURRENT = "/home/.riftgit-current";
const RIFT_GIT_MAX_FILE = 512 * 1024;
const RIFT_GIT_MAX_TOTAL = 8 * 1024 * 1024;
const RIFT_GIT_MAX_FILES = 400;
const TEXT_NAMES = new Set(["README","README.md","LICENSE","LICENSE.md","Makefile","Dockerfile","Gemfile","Procfile"]);
const TEXT_EXT = new Set(["js","mjs","cjs","ts","tsx","jsx","json","html","htm","css","scss","sass","less","md","txt","xml","svg","yml","yaml","toml","ini","cfg","conf","env","sh","bash","zsh","py","rb","php","java","kt","kts","c","h","cc","cpp","cxx","hpp","hh","cs","go","rs","swift","sql","graphql","gql","vue","svelte","astro","properties","gradle"]);

function rgRequest(req){
  return new Promise((resolve,reject)=>{
    req.onsuccess=()=>resolve(req.result);
    req.onerror=()=>reject(req.error);
  });
}

class RiftGitFS {
  constructor(){this.db=null;}
  async init(){
    if(this.db) return this.db;
    this.db=await new Promise((resolve,reject)=>{
      const req=indexedDB.open("riftos",1);
      req.onupgradeneeded=()=>{
        const db=req.result;
        if(!db.objectStoreNames.contains("files")) db.createObjectStore("files",{keyPath:"path"});
        if(!db.objectStoreNames.contains("settings")) db.createObjectStore("settings",{keyPath:"key"});
      };
      req.onsuccess=()=>resolve(req.result);
      req.onerror=()=>reject(req.error);
    });
    return this.db;
  }
  store(mode="readonly"){return this.db.transaction("files",mode).objectStore("files");}
  async get(path){await this.init();return rgRequest(this.store().get(path));}
  async write(path,content){await this.init();return rgRequest(this.store("readwrite").put({path,content,modified:Date.now()}));}
  async remove(path){await this.init();return rgRequest(this.store("readwrite").delete(path));}
  async list(){await this.init();return rgRequest(this.store().getAll());}
}

const riftGitFS=new RiftGitFS();

function rgToken(){return sessionStorage.getItem("riftgit-token")||"";}
function rgHeaders(){
  const headers={Accept:"application/vnd.github+json","X-GitHub-Api-Version":"2022-11-28"};
  const token=rgToken();
  if(token) headers.Authorization=`Bearer ${token}`;
  return headers;
}
async function rgApi(path,options={}){
  const res=await fetch(`https://api.github.com${path}`,{...options,headers:{...rgHeaders(),...(options.headers||{})}});
  if(!res.ok){
    let detail="";
    try{detail=(await res.json())?.message||"";}catch(_){}
    throw new Error(`GitHub ${res.status}${detail?`: ${detail}`:""}`);
  }
  if(res.status===204) return null;
  return res.json();
}
function rgParseRepo(value){
  const v=String(value||"").trim().replace(/\.git$/i,"");
  const match=v.match(/(?:https?:\/\/github\.com\/)?([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)$/i);
  if(!match) throw new Error("Use owner/repo or https://github.com/owner/repo");
  return {owner:match[1],repo:match[2],full:`${match[1]}/${match[2]}`};
}
function rgRoot(repo){return `/home/repos/${repo.repo}`;}
function rgIsText(path,size=0){
  if(size>RIFT_GIT_MAX_FILE) return false;
  const name=path.split("/").pop()||"";
  if(TEXT_NAMES.has(name)) return true;
  const ext=name.includes(".")?name.split(".").pop().toLowerCase():"";
  return TEXT_EXT.has(ext);
}
function rgDecode(content){
  const clean=String(content||"").replace(/\s/g,"");
  const raw=atob(clean);
  const bytes=new Uint8Array(raw.length);
  for(let i=0;i<raw.length;i++) bytes[i]=raw.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}
function rgEncode(text){
  const bytes=new TextEncoder().encode(String(text));
  let bin="";
  const step=0x8000;
  for(let i=0;i<bytes.length;i+=step) bin+=String.fromCharCode(...bytes.subarray(i,i+step));
  return btoa(bin);
}
async function rgHash(text){
  const bytes=new TextEncoder().encode(String(text));
  const digest=await crypto.subtle.digest("SHA-256",bytes);
  return [...new Uint8Array(digest)].map(b=>b.toString(16).padStart(2,"0")).join("");
}
async function rgLoadMeta(){
  const current=await riftGitFS.get(RIFT_GIT_CURRENT);
  if(!current) throw new Error("No current repo. Run: git clone owner/repo");
  const metaFile=await riftGitFS.get(`${current.content}/${RIFT_GIT_META}`);
  if(!metaFile) throw new Error("Current repo metadata is missing.");
  return JSON.parse(metaFile.content);
}
async function rgSaveMeta(meta){
  await riftGitFS.write(`${meta.root}/${RIFT_GIT_META}`,JSON.stringify(meta,null,2));
  await riftGitFS.write(RIFT_GIT_CURRENT,meta.root);
}
async function rgBlobText(owner,repo,sha){
  const blob=await rgApi(`/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/git/blobs/${encodeURIComponent(sha)}`);
  if(blob.encoding!=="base64") throw new Error("Unsupported GitHub blob encoding.");
  const text=rgDecode(blob.content);
  if(text.includes("\u0000")) throw new Error("binary");
  return text;
}
async function rgTree(owner,repo,branch){
  const tree=await rgApi(`/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/git/trees/${encodeURIComponent(branch)}?recursive=1`);
  if(tree.truncated) throw new Error("Repository tree is too large for RiftGit Gate 1.");
  return tree.tree.filter(x=>x.type==="blob"&&rgIsText(x.path,x.size||0));
}
async function rgMapLimit(items,limit,fn){
  let next=0;
  const workers=Array.from({length:Math.min(limit,items.length)},async()=>{
    while(next<items.length){const i=next++;await fn(items[i],i);}
  });
  await Promise.all(workers);
}

async function rgClone(repoArg,branchArg,print){
  const parsed=rgParseRepo(repoArg);
  const info=await rgApi(`/repos/${encodeURIComponent(parsed.owner)}/${encodeURIComponent(parsed.repo)}`);
  const branch=branchArg||info.default_branch||"main";
  print(`Cloning ${parsed.full}#${branch} into RiftFS...`);
  let tree=await rgTree(parsed.owner,parsed.repo,branch);
  tree=tree.slice(0,RIFT_GIT_MAX_FILES);
  let total=0;
  tree=tree.filter(item=>{total+=(item.size||0);return total<=RIFT_GIT_MAX_TOTAL;});
  const root=rgRoot(parsed);
  const tracked={};
  let done=0,skipped=0;
  await rgMapLimit(tree,6,async item=>{
    try{
      const text=await rgBlobText(parsed.owner,parsed.repo,item.sha);
      const localPath=`${root}/${item.path}`;
      await riftGitFS.write(localPath,text);
      tracked[item.path]={blobSha:item.sha,baseHash:await rgHash(text)};
    }catch(err){skipped++;}
    done++;
    if(done%25===0||done===tree.length) print(`  ${done}/${tree.length} files`);
  });
  const meta={format:"riftgit-v1",owner:parsed.owner,repo:parsed.repo,full:parsed.full,branch,root,tracked,clonedAt:Date.now(),updatedAt:Date.now()};
  await rgSaveMeta(meta);
  print(`Done. ${Object.keys(tracked).length} text files imported${skipped?`, ${skipped} skipped`:""}.`);
  print(`Workspace: ${root}`);
}

async function rgStatus(print,quiet=false){
  const meta=await rgLoadMeta();
  const rows=await riftGitFS.list();
  const localRows=rows.filter(f=>f.path.startsWith(meta.root+"/")&&!f.path.endsWith("/"+RIFT_GIT_META));
  const byRel=new Map(localRows.map(f=>[f.path.slice(meta.root.length+1),f]));
  const modified=[];const deleted=[];const untracked=[];
  for(const [path,base] of Object.entries(meta.tracked)){
    const file=byRel.get(path);
    if(!file){deleted.push(path);continue;}
    if(await rgHash(file.content)!==base.baseHash) modified.push(path);
    byRel.delete(path);
  }
  for(const path of byRel.keys()) untracked.push(path);
  if(!quiet){
    print(`On ${meta.full} / ${meta.branch}`);
    if(!modified.length&&!deleted.length&&!untracked.length) print("working tree clean");
    modified.forEach(p=>print(` M ${p}`));
    deleted.forEach(p=>print(` D ${p}`));
    untracked.forEach(p=>print(`?? ${p}`));
  }
  return {meta,modified,deleted,untracked};
}

async function rgPull(print){
  const {meta}=await rgStatus(()=>{},true);
  print(`Pulling ${meta.full}#${meta.branch}...`);
  const remote=await rgTree(meta.owner,meta.repo,meta.branch);
  const remoteMap=new Map(remote.map(x=>[x.path,x]));
  let updated=0,added=0,removed=0,conflicts=0;
  for(const [path,base] of Object.entries({...meta.tracked})){
    const remoteItem=remoteMap.get(path);
    const local=await riftGitFS.get(`${meta.root}/${path}`);
    const localClean=local && await rgHash(local.content)===base.baseHash;
    if(!remoteItem){
      if(localClean){await riftGitFS.remove(`${meta.root}/${path}`);delete meta.tracked[path];removed++;}
      else conflicts++;
      continue;
    }
    remoteMap.delete(path);
    if(remoteItem.sha===base.blobSha) continue;
    if(!localClean){conflicts++;continue;}
    try{
      const text=await rgBlobText(meta.owner,meta.repo,remoteItem.sha);
      await riftGitFS.write(`${meta.root}/${path}`,text);
      meta.tracked[path]={blobSha:remoteItem.sha,baseHash:await rgHash(text)};
      updated++;
    }catch(_){}
  }
  for(const [path,item] of remoteMap){
    if(!rgIsText(path,item.size||0)||Object.keys(meta.tracked).length>=RIFT_GIT_MAX_FILES) continue;
    const local=await riftGitFS.get(`${meta.root}/${path}`);
    if(local){conflicts++;continue;}
    try{
      const text=await rgBlobText(meta.owner,meta.repo,item.sha);
      await riftGitFS.write(`${meta.root}/${path}`,text);
      meta.tracked[path]={blobSha:item.sha,baseHash:await rgHash(text)};
      added++;
    }catch(_){}
  }
  meta.updatedAt=Date.now();
  await rgSaveMeta(meta);
  print(`Pull complete: ${updated} updated, ${added} added, ${removed} removed${conflicts?`, ${conflicts} local conflicts kept`:""}.`);
}

async function rgPush(message,print){
  if(!rgToken()) throw new Error("Push needs GitHub auth. Run: git auth");
  const state=await rgStatus(()=>{},true);
  const {meta,modified,deleted,untracked}=state;
  const changes=[...modified,...deleted,...untracked];
  if(!changes.length){print("nothing to push");return;}
  print(`Pushing ${changes.length} file change(s) to ${meta.full}#${meta.branch}...`);
  for(const path of changes){
    const tracked=meta.tracked[path];
    const local=await riftGitFS.get(`${meta.root}/${path}`);
    const endpoint=`/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/contents/${path.split("/").map(encodeURIComponent).join("/")}`;
    const body={message:message||`RiftOS: update ${path}`,branch:meta.branch};
    if(local) body.content=rgEncode(local.content);
    if(tracked?.blobSha) body.sha=tracked.blobSha;
    if(!local){
      if(!tracked?.blobSha) continue;
      const result=await rgApi(endpoint,{method:"DELETE",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)});
      delete meta.tracked[path];
      print(`  pushed delete ${path}`);
      continue;
    }
    const result=await rgApi(endpoint,{method:"PUT",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)});
    meta.tracked[path]={blobSha:result.content.sha,baseHash:await rgHash(local.content)};
    print(`  pushed ${path}`);
  }
  meta.updatedAt=Date.now();
  await rgSaveMeta(meta);
  print("Push complete.");
}

async function rgUse(repoArg,branchArg,print){
  const parsed=rgParseRepo(repoArg);
  const root=rgRoot(parsed);
  const metaFile=await riftGitFS.get(`${root}/${RIFT_GIT_META}`);
  if(!metaFile) throw new Error(`Repo is not cloned in RiftFS: ${parsed.full}`);
  const meta=JSON.parse(metaFile.content);
  if(branchArg&&branchArg!==meta.branch) throw new Error("Gate 1 does not switch branches yet. Clone the branch explicitly.");
  await riftGitFS.write(RIFT_GIT_CURRENT,root);
  print(`Current repo: ${meta.full}#${meta.branch}`);
}

async function rgRun(args,print){
  const cmd=(args.shift()||"help").toLowerCase();
  if(cmd==="help"){
    print("RiftGit Gate 1 commands:\n  git auth                 session-only GitHub token\n  git logout               forget token\n  git clone owner/repo [branch]\n  git use owner/repo\n  git repo                 current workspace\n  git status\n  git pull\n  git push [commit message]\n\nRepos live under /home/repos/<repo> and are editable in Files/Editor.");
  }else if(cmd==="auth"){
    const token=prompt("GitHub token for this RiftOS session only. It is kept in sessionStorage and cleared when the browser session ends:","");
    if(!token){print("auth cancelled");return;}
    sessionStorage.setItem("riftgit-token",token.trim());
    const me=await rgApi("/user");
    print(`Authenticated as ${me.login}. Token is session-only.`);
  }else if(cmd==="logout"){
    sessionStorage.removeItem("riftgit-token");print("GitHub session cleared.");
  }else if(cmd==="clone"){
    if(!args[0]) throw new Error("usage: git clone owner/repo [branch]");
    await rgClone(args[0],args[1],print);
  }else if(cmd==="use"){
    if(!args[0]) throw new Error("usage: git use owner/repo");
    await rgUse(args[0],args[1],print);
  }else if(cmd==="repo"){
    const meta=await rgLoadMeta();print(`${meta.full}#${meta.branch}\n${meta.root}`);
  }else if(cmd==="status"){
    await rgStatus(print);
  }else if(cmd==="pull"){
    await rgPull(print);
  }else if(cmd==="push"){
    await rgPush(args.join(" "),print);
  }else{
    throw new Error(`unknown RiftGit command: ${cmd}`);
  }
}

document.addEventListener("submit",async event=>{
  const form=event.target;
  if(!(form instanceof HTMLFormElement)||!form.classList.contains("shell-line")) return;
  const input=form.querySelector("input");
  const raw=String(input?.value||"").trim();
  const match=raw.match(/^(git|gh|github)(?:\s+(.*))?$/i);
  if(!match) return;
  event.preventDefault();
  event.stopImmediatePropagation();
  if(input) input.value="";
  const shell=form.closest(".shell");
  const out=shell?.querySelector(".shell-output");
  const print=text=>{
    if(!out) return;
    out.textContent+=String(text)+"\n";
    out.scrollTop=out.scrollHeight;
  };
  print(`rift$ ${raw}`);
  try{
    const args=(match[2]||"").trim().split(/\s+/).filter(Boolean);
    await rgRun(args,print);
  }catch(err){
    print(`git: ${err?.message||err}`);
  }
},true);

console.info("[RiftGit] Gate 1 terminal bridge ready");
