const RG_META=".riftgit.json";
const RG_CURRENT="/home/.riftgit-current";
const RG_MAX_FILE=512*1024;
const RG_MAX_TOTAL=8*1024*1024;
const RG_MAX_FILES=400;
const RG_TEXT_NAMES=new Set(["README","README.md","LICENSE","LICENSE.md","Makefile","Dockerfile","Gemfile","Procfile"]);
const RG_TEXT_EXT=new Set(["js","mjs","cjs","ts","tsx","jsx","json","html","htm","css","scss","sass","less","md","txt","xml","svg","yml","yaml","toml","ini","cfg","conf","env","sh","bash","zsh","py","rb","php","java","kt","kts","c","h","cc","cpp","cxx","hpp","hh","cs","go","rs","swift","sql","graphql","gql","vue","svelte","astro","properties","gradle"]);

function reqp(req){return new Promise((resolve,reject)=>{req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error)})}
class RGFS{
  constructor(){this.db=null}
  async init(){
    if(this.db)return this.db;
    this.db=await new Promise((resolve,reject)=>{
      const req=indexedDB.open("riftos",1);
      req.onupgradeneeded=()=>{
        const db=req.result;
        if(!db.objectStoreNames.contains("files"))db.createObjectStore("files",{keyPath:"path"});
        if(!db.objectStoreNames.contains("settings"))db.createObjectStore("settings",{keyPath:"key"});
      };
      req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);
    });
    return this.db;
  }
  store(mode="readonly"){return this.db.transaction("files",mode).objectStore("files")}
  async get(path){await this.init();return reqp(this.store().get(path))}
  async write(path,content){await this.init();return reqp(this.store("readwrite").put({path,content,modified:Date.now()}))}
  async remove(path){await this.init();return reqp(this.store("readwrite").delete(path))}
  async list(){await this.init();return reqp(this.store().getAll())}
}
const fs=new RGFS();

const token=()=>sessionStorage.getItem("riftgit-token")||"";
function headers(extra={}){
  const h={Accept:"application/vnd.github+json","X-GitHub-Api-Version":"2022-11-28",...extra};
  if(token())h.Authorization=`Bearer ${token()}`;
  return h;
}
async function api(path,options={}){
  const res=await fetch(`https://api.github.com${path}`,{...options,headers:headers(options.headers||{})});
  if(!res.ok){let d="";try{d=(await res.json())?.message||""}catch{};throw new Error(`GitHub ${res.status}${d?`: ${d}`:""}`)}
  if(res.status===204)return null;
  return res.json();
}
function parseRepo(value){
  const v=String(value||"").trim().replace(/\.git$/i,"");
  const m=v.match(/(?:https?:\/\/github\.com\/)?([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)$/i);
  if(!m)throw new Error("Use owner/repo or a GitHub repository URL.");
  return {owner:m[1],repo:m[2],full:`${m[1]}/${m[2]}`};
}
const rootFor=r=>`/home/repos/${r.repo}`;
function isText(path,size=0){
  if(size>RG_MAX_FILE)return false;
  const name=path.split("/").pop()||"";
  if(RG_TEXT_NAMES.has(name))return true;
  const ext=name.includes(".")?name.split(".").pop().toLowerCase():"";
  return RG_TEXT_EXT.has(ext);
}
function decode64(content){
  const raw=atob(String(content||"").replace(/\s/g,""));
  const bytes=new Uint8Array(raw.length);for(let i=0;i<raw.length;i++)bytes[i]=raw.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}
function encode64(text){
  const bytes=new TextEncoder().encode(String(text));let bin="";
  for(let i=0;i<bytes.length;i+=0x8000)bin+=String.fromCharCode(...bytes.subarray(i,i+0x8000));
  return btoa(bin);
}
async function hash(text){
  const digest=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(String(text)));
  return [...new Uint8Array(digest)].map(b=>b.toString(16).padStart(2,"0")).join("");
}
async function blobText(meta,sha){
  const b=await api(`/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/git/blobs/${encodeURIComponent(sha)}`);
  if(b.encoding!=="base64")throw new Error("Unsupported GitHub blob encoding.");
  const text=decode64(b.content);if(text.includes("\u0000"))throw new Error("Binary file");return text;
}
async function remoteTree(meta,branch=meta.branch){
  const data=await api(`/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/git/trees/${encodeURIComponent(branch)}?recursive=1`);
  if(data.truncated)throw new Error("Repository tree is too large for the current RiftGit workspace limit.");
  return data.tree.filter(x=>x.type==="blob");
}
async function branchInfo(meta,branch=meta.branch){return api(`/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/branches/${encodeURIComponent(branch)}`)}
async function mapLimit(items,limit,fn){let next=0;const workers=Array.from({length:Math.min(limit,items.length)},async()=>{while(next<items.length){const i=next++;await fn(items[i],i)}});await Promise.all(workers)}
async function saveMeta(meta){await fs.write(`${meta.root}/${RG_META}`,JSON.stringify(meta,null,2));await fs.write(RG_CURRENT,meta.root)}
async function currentMeta(){
  const cur=await fs.get(RG_CURRENT);if(!cur)return null;
  const file=await fs.get(`${cur.content}/${RG_META}`);if(!file)return null;
  try{return JSON.parse(file.content)}catch{return null}
}
async function clonedRepos(){
  const rows=await fs.list();const out=[];
  for(const row of rows.filter(r=>r.path.endsWith("/"+RG_META))){try{const m=JSON.parse(row.content);if(m?.full&&m?.root)out.push(m)}catch{}}
  return out.sort((a,b)=>a.full.localeCompare(b.full));
}
async function state(meta){
  const rows=await fs.list();
  const local=rows.filter(f=>f.path.startsWith(meta.root+"/")&&!f.path.endsWith("/"+RG_META));
  const byRel=new Map(local.map(f=>[f.path.slice(meta.root.length+1),f]));
  const modified=[],deleted=[],untracked=[];
  for(const [path,base] of Object.entries(meta.tracked||{})){
    const f=byRel.get(path);if(!f){deleted.push(path);continue}
    if(await hash(f.content)!==base.baseHash)modified.push(path);byRel.delete(path);
  }
  for(const path of byRel.keys())untracked.push(path);
  return {meta,modified,deleted,untracked,clean:!modified.length&&!deleted.length&&!untracked.length};
}
async function importBranch(meta,branch,progress=()=>{}){
  let tree=(await remoteTree(meta,branch)).filter(x=>isText(x.path,x.size||0)).slice(0,RG_MAX_FILES);
  let total=0;tree=tree.filter(x=>{total+=x.size||0;return total<=RG_MAX_TOTAL});
  const tracked={};let done=0,skipped=0;
  await mapLimit(tree,6,async item=>{
    try{
      const text=await blobText(meta,item.sha);await fs.write(`${meta.root}/${item.path}`,text);
      tracked[item.path]={blobSha:item.sha,baseHash:await hash(text),mode:item.mode||"100644"};
    }catch{skipped++}
    done++;if(done%20===0||done===tree.length)progress(`${done}/${tree.length} files`);
  });
  const b=await branchInfo(meta,branch);
  meta.branch=branch;meta.headSha=b.commit.sha;meta.tracked=tracked;meta.updatedAt=Date.now();
  await saveMeta(meta);return {count:Object.keys(tracked).length,skipped};
}
async function cloneRepo(value,branch,progress=()=>{}){
  const p=parseRepo(value);const info=await api(`/repos/${encodeURIComponent(p.owner)}/${encodeURIComponent(p.repo)}`);
  const root=rootFor(p);const existing=await fs.get(`${root}/${RG_META}`);
  if(existing){const old=JSON.parse(existing.content);if(old.full!==p.full)throw new Error(`Workspace ${root} already belongs to ${old.full}.`)}
  const meta={format:"riftgit-v1",owner:p.owner,repo:p.repo,full:p.full,branch:branch||info.default_branch||"main",root,tracked:{},clonedAt:Date.now(),updatedAt:Date.now()};
  progress(`Cloning ${meta.full}#${meta.branch}…`);
  const result=await importBranch(meta,meta.branch,progress);progress(`Imported ${result.count} files${result.skipped?`, ${result.skipped} skipped`:""}.`);return meta;
}
async function useRepo(meta){await fs.write(RG_CURRENT,meta.root);return meta}
async function branches(meta){const data=await api(`/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/branches?per_page=100`);return data.map(x=>x.name)}
async function switchBranch(meta,newBranch,progress=()=>{}){
  const s=await state(meta);if(!s.clean)throw new Error("Commit or discard local changes before switching branches.");
  if(newBranch===meta.branch)return meta;
  progress(`Switching to ${newBranch}…`);
  for(const path of Object.keys(meta.tracked||{}))await fs.remove(`${meta.root}/${path}`);
  await importBranch(meta,newBranch,progress);return meta;
}
async function pull(meta,progress=()=>{}){
  const s=await state(meta);if(!s.clean)throw new Error("Commit or discard local changes before pulling.");
  progress(`Pulling ${meta.full}#${meta.branch}…`);
  for(const path of Object.keys(meta.tracked||{}))await fs.remove(`${meta.root}/${path}`);
  const r=await importBranch(meta,meta.branch,progress);progress(`Pull complete: ${r.count} files synced.`);return meta;
}
async function diffFile(meta,path){
  const local=await fs.get(`${meta.root}/${path}`);const tracked=meta.tracked?.[path];
  const before=tracked?await blobText(meta,tracked.blobSha):"";return {before,after:local?.content??""};
}
async function discard(meta,path){
  const tracked=meta.tracked?.[path];
  if(!tracked){await fs.remove(`${meta.root}/${path}`);return}
  const text=await blobText(meta,tracked.blobSha);await fs.write(`${meta.root}/${path}`,text);
  tracked.baseHash=await hash(text);
  await saveMeta(meta);
}
async function pushCommit(meta,message,progress=()=>{}){
  if(!token())throw new Error("Authenticate with GitHub before pushing.");
  const s=await state(meta);const paths=[...s.modified,...s.deleted,...s.untracked];if(!paths.length)throw new Error("Working tree is clean.");
  const ref=await api(`/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/git/ref/heads/${encodeURIComponent(meta.branch)}`);
  const head=ref.object.sha;
  if(meta.headSha&&head!==meta.headSha)throw new Error("Remote branch changed since your last pull. Pull before pushing.");
  const parent=await api(`/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/git/commits/${encodeURIComponent(head)}`);
  const remote=await remoteTree(meta,meta.branch);const remoteByPath=new Map(remote.map(x=>[x.path,x]));
  const entries=[];const newBlobs=new Map();let done=0;
  for(const path of paths){
    const local=await fs.get(`${meta.root}/${path}`);const remoteItem=remoteByPath.get(path);
    if(!local){entries.push({path,mode:remoteItem?.mode||meta.tracked?.[path]?.mode||"100644",type:"blob",sha:null});done++;continue}
    const b=await api(`/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/git/blobs`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({content:encode64(local.content),encoding:"base64"})});
    entries.push({path,mode:remoteItem?.mode||meta.tracked?.[path]?.mode||"100644",type:"blob",sha:b.sha});newBlobs.set(path,{sha:b.sha,hash:await hash(local.content),mode:remoteItem?.mode||"100644"});
    done++;progress(`Prepared ${done}/${paths.length}`);
  }
  const tree=await api(`/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/git/trees`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({base_tree:parent.tree.sha,tree:entries})});
  const commit=await api(`/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/git/commits`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({message:String(message||"RiftOS workspace update").trim(),tree:tree.sha,parents:[head]})});
  await api(`/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/git/refs/heads/${encodeURIComponent(meta.branch)}`,{method:"PATCH",headers:{"Content-Type":"application/json"},body:JSON.stringify({sha:commit.sha,force:false})});
  for(const path of s.deleted)delete meta.tracked[path];
  for(const [path,v] of newBlobs)meta.tracked[path]={blobSha:v.sha,baseHash:v.hash,mode:v.mode};
  meta.headSha=commit.sha;meta.updatedAt=Date.now();await saveMeta(meta);progress(`Pushed ${commit.sha.slice(0,7)}.`);return commit;
}
async function actionRuns(meta){const d=await api(`/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/actions/runs?branch=${encodeURIComponent(meta.branch)}&per_page=20`);return d.workflow_runs||[]}
async function actionJobs(meta,runId){const d=await api(`/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/actions/runs/${runId}/jobs?per_page=100`);return d.jobs||[]}
async function jobLogs(meta,jobId){
  const res=await fetch(`https://api.github.com/repos/${encodeURIComponent(meta.owner)}/${encodeURIComponent(meta.repo)}/actions/jobs/${jobId}/logs`,{headers:headers({Accept:"text/plain"}),redirect:"follow"});
  if(!res.ok)throw new Error(`GitHub ${res.status}: logs require Actions read access.`);
  const text=await res.text();return text.length>260000?`…log truncated to last 260 KB…\n${text.slice(-260000)}`:text;
}

function esc(v){return String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]))}
function injectStyle(){
  if(document.querySelector("#riftGitAppStyle"))return;
  const s=document.createElement("style");s.id="riftGitAppStyle";s.textContent=`
  .rg-app{height:100%;min-height:0;display:flex;flex-direction:column;gap:10px}.rg-top{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.rg-top select,.rg-top input,.rg-commit input,.rg-search{background:#0d131c;color:#f4f7fb;border:1px solid #2d3746;border-radius:10px;padding:10px;font:inherit;min-width:0}.rg-grow{flex:1 1 180px}.rg-btn{border:1px solid #344154;background:#172130;color:#f5f7fb;border-radius:10px;padding:9px 12px;font:inherit;font-weight:650}.rg-btn.primary{background:#f4f7fb;color:#0b1017}.rg-btn.danger{border-color:#6d3338}.rg-btn:disabled{opacity:.45}.rg-auth{font-size:12px;color:#9eabba}.rg-tabs{display:flex;gap:6px;overflow:auto;padding-bottom:2px}.rg-tabs button{white-space:nowrap}.rg-tabs .active{background:#f4f7fb;color:#0b1017}.rg-main{flex:1;min-height:0;overflow:auto;border:1px solid #263140;border-radius:14px;background:#0a0f16}.rg-panel{padding:12px}.rg-empty{padding:28px;text-align:center;color:#aab5c4}.rg-list{display:flex;flex-direction:column}.rg-row{display:flex;align-items:center;gap:10px;padding:11px;border-bottom:1px solid #202a37}.rg-row:last-child{border-bottom:0}.rg-row button.path{flex:1;text-align:left;background:none;border:0;color:#eef3fa;font:inherit;overflow-wrap:anywhere}.rg-row small{color:#8e9aaa}.rg-badge{font:700 11px/1 system-ui;padding:6px 7px;border:1px solid #334154;border-radius:999px;min-width:28px;text-align:center}.rg-badge.m{color:#ffd37d}.rg-badge.d{color:#ff9696}.rg-badge.u{color:#91e2b4}.rg-summary{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px}.rg-commit{display:flex;gap:8px;padding:12px;border-top:1px solid #263140;position:sticky;bottom:0;background:#0a0f16}.rg-commit input{flex:1}.rg-editor{display:flex;flex-direction:column;height:100%;min-height:420px}.rg-editor-head{display:flex;gap:8px;align-items:center;padding:10px;border-bottom:1px solid #263140}.rg-editor-head strong{flex:1;overflow-wrap:anywhere}.rg-editor textarea{flex:1;min-height:360px;resize:none;border:0;outline:0;background:#070b10;color:#e9eef6;padding:13px;font:13px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace}.rg-diff{margin:0;padding:12px;white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.45 ui-monospace,SFMono-Regular,Menlo,monospace}.rg-diff .ctx{color:#9ca8b8}.rg-diff .minus{color:#ff9b9b}.rg-diff .plus{color:#91e2b4}.rg-actions .rg-row{align-items:flex-start}.rg-run-main{flex:1;min-width:0}.rg-run-main strong,.rg-run-main span{display:block;overflow-wrap:anywhere}.rg-log{white-space:pre-wrap;overflow-wrap:anywhere;margin:0;padding:12px;font:11px/1.4 ui-monospace,SFMono-Regular,Menlo,monospace;color:#dfe7f1}.rg-progress{font:12px/1.3 ui-monospace,SFMono-Regular,Menlo,monospace;color:#9fb0c3;min-height:16px}.rg-file-tools{display:flex;gap:6px;flex-wrap:wrap}.rg-file-tools .rg-btn{padding:6px 8px;font-size:12px}@media(max-width:620px){.rg-top{align-items:stretch}.rg-top>*{flex:1 1 44%}.rg-top .rg-grow{flex-basis:100%}.rg-commit{flex-direction:column}.rg-row{align-items:flex-start}.rg-file-tools{justify-content:flex-end}}
  `;document.head.append(s);
}
function setOSStatus(text){const el=document.querySelector("#statusText");if(el)el.textContent=text}
function closeApp(){
  const stage=document.querySelector("#stage"),workspace=document.querySelector("#workspace");if(stage){stage.innerHTML="";stage.classList.add("hidden")}workspace?.classList.remove("hidden");
  document.querySelectorAll(".dock-btn").forEach(x=>x.classList.toggle("active",x.dataset.open==="home"));setOSStatus("Ready");
}
function appBody(){
  const stage=document.querySelector("#stage"),workspace=document.querySelector("#workspace"),tpl=document.querySelector("#windowTemplate");if(!stage||!workspace||!tpl)throw new Error("RiftOS window host is unavailable.");
  workspace.classList.add("hidden");stage.classList.remove("hidden");stage.innerHTML="";
  const win=tpl.content.firstElementChild.cloneNode(true);win.dataset.app="riftgit";win.querySelector(".window-kicker").textContent="GITHUB WORKSPACE";win.querySelector(".window-title").textContent="RiftGit";win.querySelector(".window-close").onclick=closeApp;stage.append(win);setOSStatus("RiftGit");return win.querySelector(".window-body");
}

let body=null,meta=null,tab="files",progress="";
async function openApp(){injectStyle();body=appBody();await renderShell()}
async function renderShell(){
  const repos=await clonedRepos();meta=await currentMeta();if(!meta&&repos[0]){meta=repos[0];await useRepo(meta)}
  body.innerHTML=`<div class="rg-app"><div class="rg-top"><select id="rgRepo" class="rg-grow" aria-label="Repository">${repos.length?repos.map(r=>`<option value="${esc(r.root)}" ${meta?.root===r.root?"selected":""}>${esc(r.full)}</option>`).join(""):"<option>No cloned repositories</option>"}</select><button class="rg-btn" id="rgClone">Clone</button><select id="rgBranch" ${meta?"":"disabled"}><option>${esc(meta?.branch||"branch")}</option></select><button class="rg-btn" id="rgSwitch" ${meta?"":"disabled"}>Switch</button><button class="rg-btn" id="rgPull" ${meta?"":"disabled"}>Pull</button><button class="rg-btn" id="rgAuth">${token()?"Auth ✓":"Authenticate"}</button></div><div class="rg-auth" id="rgAuthText">${token()?`Session authenticated${sessionStorage.getItem("riftgit-login")?` as ${esc(sessionStorage.getItem("riftgit-login"))}`:""}.`:"Public repos work without auth. Push/private repos/Actions may require a session token."}</div><div class="rg-progress" id="rgProgress">${esc(progress)}</div><div class="rg-tabs"><button class="rg-btn ${tab==="files"?"active":""}" data-rg-tab="files">Files</button><button class="rg-btn ${tab==="changes"?"active":""}" data-rg-tab="changes">Changes</button><button class="rg-btn ${tab==="actions"?"active":""}" data-rg-tab="actions">Actions</button></div><div class="rg-main" id="rgMain"></div></div>`;
  bindShell();if(meta)await loadBranches();await renderTab();
}
function say(text){progress=String(text||"");const el=body?.querySelector("#rgProgress");if(el)el.textContent=progress}
function bindShell(){
  body.querySelector("#rgClone").onclick=async()=>{
    const repo=prompt("GitHub repo (owner/repo):",meta?.full||"Arctic403/RiftOS");if(!repo)return;const branch=prompt("Branch (leave blank for default):","")||"";
    try{say("Starting clone…");meta=await cloneRepo(repo,branch,say);tab="files";await renderShell()}catch(e){say(e.message)}
  };
  const select=body.querySelector("#rgRepo");if(select&&meta)select.onchange=async()=>{const repos=await clonedRepos();const found=repos.find(r=>r.root===select.value);if(found){meta=found;await useRepo(meta);await renderShell()}};
  body.querySelector("#rgAuth").onclick=async()=>{
    if(token()){sessionStorage.removeItem("riftgit-token");sessionStorage.removeItem("riftgit-login");say("GitHub session cleared.");await renderShell();return}
    const t=prompt("GitHub token for this RiftOS session only. It is not written to RiftFS:","");if(!t)return;
    sessionStorage.setItem("riftgit-token",t.trim());try{const me=await api("/user");sessionStorage.setItem("riftgit-login",me.login);say(`Authenticated as ${me.login}.`);await renderShell()}catch(e){sessionStorage.removeItem("riftgit-token");say(e.message)}
  };
  body.querySelectorAll("[data-rg-tab]").forEach(b=>b.onclick=async()=>{tab=b.dataset.rgTab;await renderShell()});
  if(meta){
    body.querySelector("#rgPull").onclick=async()=>{try{say("Pulling…");await pull(meta,say);await renderShell()}catch(e){say(e.message)}};
    body.querySelector("#rgSwitch").onclick=async()=>{const branch=body.querySelector("#rgBranch").value;if(branch===meta.branch)return;if(!confirm(`Switch ${meta.full} from ${meta.branch} to ${branch}?`))return;try{say("Switching…");await switchBranch(meta,branch,say);await renderShell()}catch(e){say(e.message)}};
  }
}
async function loadBranches(){
  const sel=body.querySelector("#rgBranch");try{const list=await branches(meta);sel.innerHTML=list.map(b=>`<option ${b===meta.branch?"selected":""}>${esc(b)}</option>`).join("")}catch(e){sel.innerHTML=`<option>${esc(meta.branch)}</option>`;say(`Branch list: ${e.message}`)}
}
async function renderTab(){const main=body.querySelector("#rgMain");if(!meta){main.innerHTML=`<div class="rg-empty"><strong>No workspace yet.</strong><p>Clone a GitHub repository to put its code in RiftFS.</p></div>`;return}if(tab==="files")return renderFiles(main);if(tab==="changes")return renderChanges(main);return renderActions(main)}
async function localFiles(){const rows=await fs.list();return rows.filter(f=>f.path.startsWith(meta.root+"/")&&!f.path.endsWith("/"+RG_META)).map(f=>({...f,rel:f.path.slice(meta.root.length+1)})).sort((a,b)=>a.rel.localeCompare(b.rel))}
async function renderFiles(main){
  const files=await localFiles();main.innerHTML=`<div class="rg-panel"><input class="rg-search" id="rgSearch" placeholder="Filter ${files.length} files" style="width:100%"></div><div class="rg-list" id="rgFiles"></div>`;
  const list=main.querySelector("#rgFiles"),search=main.querySelector("#rgSearch");
  const draw=()=>{const q=search.value.toLowerCase();const shown=files.filter(f=>f.rel.toLowerCase().includes(q));list.innerHTML=shown.map(f=>`<div class="rg-row"><button class="path" data-rg-edit="${esc(f.rel)}">${esc(f.rel)}</button><small>${new Blob([f.content]).size} B</small></div>`).join("")||`<div class="rg-empty">No matching files.</div>`;list.querySelectorAll("[data-rg-edit]").forEach(b=>b.onclick=()=>openEditor(b.dataset.rgEdit))};search.oninput=draw;draw();
}
async function openEditor(path){
  const file=await fs.get(`${meta.root}/${path}`);const main=body.querySelector("#rgMain");main.innerHTML=`<div class="rg-editor"><div class="rg-editor-head"><button class="rg-btn" id="rgBack">← Files</button><strong>${esc(path)}</strong><button class="rg-btn primary" id="rgSave">Save</button></div><textarea spellcheck="false"></textarea></div>`;
  const ta=main.querySelector("textarea");ta.value=file?.content||"";main.querySelector("#rgBack").onclick=()=>renderFiles(main);main.querySelector("#rgSave").onclick=async()=>{await fs.write(`${meta.root}/${path}`,ta.value);say(`Saved ${path}`)};
}
async function renderChanges(main){
  const s=await state(meta);const rows=[...s.modified.map(p=>["M",p]),...s.deleted.map(p=>["D",p]),...s.untracked.map(p=>["?",p])];
  main.innerHTML=`<div class="rg-panel"><div class="rg-summary"><span class="rg-badge m">${s.modified.length} modified</span><span class="rg-badge d">${s.deleted.length} deleted</span><span class="rg-badge u">${s.untracked.length} new</span></div>${s.clean?"<div class='rg-empty'>Working tree clean.</div>":`<div class="rg-list">${rows.map(([k,p])=>`<div class="rg-row"><span class="rg-badge ${k==="M"?"m":k==="D"?"d":"u"}">${k}</span><button class="path" data-rg-diff="${esc(p)}">${esc(p)}</button><div class="rg-file-tools">${k!=="D"?`<button class="rg-btn" data-rg-edit="${esc(p)}">Edit</button>`:""}<button class="rg-btn danger" data-rg-discard="${esc(p)}">Discard</button></div></div>`).join("")}</div>`}</div>${s.clean?"":`<div class="rg-commit"><input id="rgMessage" placeholder="Commit message"><button class="rg-btn primary" id="rgPush">Commit & Push</button></div>`}`;
  main.querySelectorAll("[data-rg-edit]").forEach(b=>b.onclick=()=>openEditor(b.dataset.rgEdit));main.querySelectorAll("[data-rg-diff]").forEach(b=>b.onclick=()=>showDiff(b.dataset.rgDiff));main.querySelectorAll("[data-rg-discard]").forEach(b=>b.onclick=async()=>{if(!confirm(`Discard local changes to ${b.dataset.rgDiscard}?`))return;try{await discard(meta,b.dataset.rgDiscard);say(`Discarded ${b.dataset.rgDiscard}`);await renderChanges(main)}catch(e){say(e.message)}});
  const push=main.querySelector("#rgPush");if(push)push.onclick=async()=>{const msg=main.querySelector("#rgMessage").value.trim();if(!msg){say("Enter a commit message.");return}push.disabled=true;try{await pushCommit(meta,msg,say);await renderChanges(main)}catch(e){say(e.message)}finally{push.disabled=false}};
}
function simpleDiff(before,after){
  const a=String(before).split("\n"),b=String(after).split("\n");let start=0;while(start<a.length&&start<b.length&&a[start]===b[start])start++;let ai=a.length-1,bi=b.length-1;while(ai>=start&&bi>=start&&a[ai]===b[bi]){ai--;bi--}
  const lines=[];for(let i=Math.max(0,start-3);i<start;i++)lines.push(["ctx",`  ${a[i]}`]);for(let i=start;i<=ai;i++)lines.push(["minus",`- ${a[i]}`]);for(let i=start;i<=bi;i++)lines.push(["plus",`+ ${b[i]}`]);for(let i=ai+1;i<Math.min(a.length,ai+4);i++)lines.push(["ctx",`  ${a[i]}`]);if(!lines.length)lines.push(["ctx","  No textual difference."]);return lines;
}
async function showDiff(path){
  const main=body.querySelector("#rgMain");main.innerHTML=`<div class="rg-editor-head"><button class="rg-btn" id="rgBackChanges">← Changes</button><strong>${esc(path)}</strong></div><pre class="rg-diff">Loading diff…</pre>`;
  try{const d=await diffFile(meta,path);const lines=simpleDiff(d.before,d.after);main.querySelector(".rg-diff").innerHTML=lines.map(([c,t])=>`<span class="${c}">${esc(t)}</span>\n`).join("")}catch(e){main.querySelector(".rg-diff").textContent=e.message}main.querySelector("#rgBackChanges").onclick=()=>renderChanges(main);
}
async function renderActions(main){
  main.innerHTML=`<div class="rg-panel"><button class="rg-btn" id="rgRefreshActions">Refresh Actions</button></div><div class="rg-empty">Loading workflow runs…</div>`;main.querySelector("#rgRefreshActions").onclick=()=>renderActions(main);
  try{const runs=await actionRuns(meta);main.innerHTML=`<div class="rg-panel"><button class="rg-btn" id="rgRefreshActions">Refresh Actions</button></div><div class="rg-list rg-actions">${runs.length?runs.map(r=>`<div class="rg-row"><div class="rg-run-main"><strong>${esc(r.name||r.display_title||"Workflow")}</strong><span>${esc(r.display_title||"")}</span><small>${esc(r.head_branch||"")} · ${esc(r.event||"")} · ${new Date(r.created_at).toLocaleString()}</small></div><span class="rg-badge ${r.conclusion==="failure"?"d":r.conclusion==="success"?"u":"m"}">${esc((r.conclusion||r.status||"?").slice(0,8))}</span><button class="rg-btn" data-rg-run="${r.id}">Jobs</button></div>`).join(""):"<div class='rg-empty'>No workflow runs found for this branch.</div>"}</div>`;main.querySelector("#rgRefreshActions").onclick=()=>renderActions(main);main.querySelectorAll("[data-rg-run]").forEach(b=>b.onclick=()=>showJobs(Number(b.dataset.rgRun)))}catch(e){main.innerHTML=`<div class="rg-empty">${esc(e.message)}</div>`}
}
async function showJobs(runId){
  const main=body.querySelector("#rgMain");main.innerHTML=`<div class="rg-editor-head"><button class="rg-btn" id="rgBackRuns">← Runs</button><strong>Run ${runId}</strong></div><div class="rg-empty">Loading jobs…</div>`;main.querySelector("#rgBackRuns").onclick=()=>renderActions(main);
  try{const jobs=await actionJobs(meta,runId);main.innerHTML=`<div class="rg-editor-head"><button class="rg-btn" id="rgBackRuns">← Runs</button><strong>Run ${runId}</strong></div><div class="rg-list">${jobs.map(j=>`<div class="rg-row"><div class="rg-run-main"><strong>${esc(j.name)}</strong><small>${esc(j.status)} · ${esc(j.conclusion||"running")}</small></div><button class="rg-btn" data-rg-log="${j.id}">Logs</button></div>`).join("")||"<div class='rg-empty'>No jobs.</div>"}</div>`;main.querySelector("#rgBackRuns").onclick=()=>renderActions(main);main.querySelectorAll("[data-rg-log]").forEach(b=>b.onclick=()=>showLog(runId,Number(b.dataset.rgLog)))}catch(e){say(e.message)}
}
async function showLog(runId,jobId){
  const main=body.querySelector("#rgMain");main.innerHTML=`<div class="rg-editor-head"><button class="rg-btn" id="rgBackJobs">← Jobs</button><strong>Job ${jobId}</strong></div><pre class="rg-log">Loading log…</pre>`;main.querySelector("#rgBackJobs").onclick=()=>showJobs(runId);
  try{main.querySelector(".rg-log").textContent=await jobLogs(meta,jobId)}catch(e){main.querySelector(".rg-log").textContent=e.message}
}

function addLauncher(){
  const grid=document.querySelector("#appGrid");if(!grid||grid.querySelector("[data-riftgit-open]"))return;
  const btn=document.createElement("button");btn.className="app-card";btn.dataset.riftgitOpen="1";btn.innerHTML=`<span class="app-icon">⑂</span><span><strong>RiftGit</strong><br><small>GitHub workspace & Actions</small></span>`;grid.append(btn);
}
document.addEventListener("click",e=>{const b=e.target.closest("[data-riftgit-open]");if(!b)return;e.preventDefault();e.stopImmediatePropagation();openApp().catch(err=>{console.error("[RiftGit App]",err);setOSStatus(`RiftGit: ${err.message}`)})},true);
const grid=document.querySelector("#appGrid");if(grid){new MutationObserver(addLauncher).observe(grid,{childList:true});queueMicrotask(addLauncher)}
window.RiftGitApp=Object.freeze({open:openApp});
console.info("[RiftGit] visual workspace app ready");