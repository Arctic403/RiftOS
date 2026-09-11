const CHANNEL="riftworkspace-live-v1";
let requestSeq=0;
const pending=new Map();
const state={cwd:"",rows:[],selected:"",revision:"",baseline:"",dirty:false,conflict:false,events:[]};

const $=selector=>document.querySelector(selector);
const fileList=$("#fileList"),pathLabel=$("#pathLabel"),filterInput=$("#filterInput"),editor=$("#editor"),fileName=$("#fileName"),revision=$("#revision"),saveBtn=$("#saveBtn"),followLive=$("#followLive"),activityLog=$("#activityLog"),diffOutput=$("#diffOutput"),diffMeta=$("#diffMeta"),conflict=$("#conflict"),liveDot=$("#liveDot"),liveText=$("#liveText");

function rpc(method,args={}){
  const id=`rw-${Date.now()}-${++requestSeq}`;
  return new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{pending.delete(id);reject(new Error(`Workspace RPC timeout: ${method}`));},30000);
    pending.set(id,{resolve,reject,timer});
    parent.postMessage({channel:CHANNEL,kind:"request",id,method,args},"*");
  });
}
function fmtBytes(value){const n=Number(value||0);if(n<1024)return `${n} B`;if(n<1024**2)return `${(n/1024).toFixed(1)} KB`;return `${(n/1024**2).toFixed(1)} MB`;}
function parentPath(path){const parts=String(path||"").split("/").filter(Boolean);parts.pop();return parts.join("/");}
function leaf(path){return String(path||"").split("/").filter(Boolean).pop()||"workspace";}
function eventTouchesDirectory(path,dir){const parent=parentPath(path);return parent===dir||path===dir||(!dir&&!path.includes("/"));}
function setLive(on,text=on?"Live":"Disconnected"){liveDot.classList.toggle("on",on);liveText.textContent=text;}
function setConflict(message=""){state.conflict=!!message;conflict.classList.toggle("hidden",!message);conflict.textContent=message;}
function setRevision(hash="",extra=""){state.revision=hash||"";revision.textContent=hash?`${hash.slice(0,12)}${extra?` · ${extra}`:""}`:(extra||"No revision");}

async function loadDirectory(path=state.cwd){
  state.cwd=String(path||"").replace(/^\/+|\/+$/g,"");
  pathLabel.textContent=`/${state.cwd}`.replace(/\/$/,"")||"/";
  const rows=await rpc("list",{path:state.cwd});
  state.rows=Array.isArray(rows)?rows:[];
  renderFiles();
}
function renderFiles(){
  const needle=filterInput.value.trim().toLowerCase();
  const rows=state.rows.filter(row=>!needle||String(row.name||leaf(row.path)).toLowerCase().includes(needle));
  if(!rows.length){fileList.innerHTML='<div class="empty">This folder is empty.</div>';return;}
  fileList.innerHTML=rows.map(row=>{
    const name=row.name||leaf(row.path),dir=row.kind==="directory";
    return `<button class="file-row ${row.path===state.selected?"selected":""}" data-path="${escapeAttr(row.path)}" data-kind="${row.kind}"><span>${dir?"▸":"·"}</span><b>${escapeHTML(name)}</b><small>${dir?"DIR":fmtBytes(row.size)}</small></button>`;
  }).join("");
}
function escapeHTML(value){return String(value??"").replace(/[&<>"']/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[ch]));}
function escapeAttr(value){return escapeHTML(value);}

async function openFile(path,{external=false}={}){
  const result=await rpc("read",{path});
  const previous=state.baseline;
  state.selected=path;
  state.baseline=String(result.text??"");
  editor.value=state.baseline;
  editor.disabled=false;
  state.dirty=false;
  saveBtn.disabled=true;
  fileName.textContent=path;
  setRevision(result.sha256,`${fmtBytes(result.size)} · ${new Date(result.modified||Date.now()).toLocaleTimeString()}`);
  setConflict("");
  renderFiles();
  if(external&&previous!==state.baseline)renderDiff(previous,state.baseline,path);
}

function renderDiff(before,after,path){
  const a=String(before??"").split("\n"),b=String(after??"").split("\n");
  let prefix=0;while(prefix<a.length&&prefix<b.length&&a[prefix]===b[prefix])prefix++;
  let suffix=0;while(suffix<a.length-prefix&&suffix<b.length-prefix&&a[a.length-1-suffix]===b[b.length-1-suffix])suffix++;
  const removed=a.slice(prefix,a.length-suffix),added=b.slice(prefix,b.length-suffix),start=prefix+1;
  const lines=[];
  const contextStart=Math.max(0,prefix-2);for(let i=contextStart;i<prefix;i++)lines.push(`  ${i+1} ${a[i]}`);
  removed.slice(0,20).forEach((line,i)=>lines.push(`- ${start+i} ${line}`));
  added.slice(0,20).forEach((line,i)=>lines.push(`+ ${start+i} ${line}`));
  const afterStart=b.length-suffix;for(let i=afterStart;i<Math.min(b.length,afterStart+2);i++)lines.push(`  ${i+1} ${b[i]}`);
  if(removed.length>20||added.length>20)lines.push(`… ${Math.max(0,removed.length-20)} more removed / ${Math.max(0,added.length-20)} more added lines`);
  diffMeta.textContent=`${path} · line ${start}`;
  diffOutput.textContent=lines.join("\n")||"Metadata changed; text content is unchanged.";
}

async function save(){
  if(!state.selected||!state.dirty)return;
  saveBtn.disabled=true;
  try{
    const result=await rpc("write",{path:state.selected,text:editor.value,expectedSha256:state.revision||null});
    const before=state.baseline;state.baseline=editor.value;state.dirty=false;setRevision(result.sha256,`${fmtBytes(result.size)} · saved`);setConflict("");renderDiff(before,state.baseline,state.selected);
  }catch(error){
    setConflict(`Save blocked: ${error.message}. Reload or copy your changes before retrying.`);
    saveBtn.disabled=false;
  }
}

function addActivity(event){
  state.events.unshift(event);state.events=state.events.slice(0,120);
  activityLog.innerHTML=state.events.map(item=>`<div class="activity-row"><time>${new Date(item.at||Date.now()).toLocaleTimeString([], {hour:"2-digit",minute:"2-digit",second:"2-digit"})}</time><em>${escapeHTML(item.type||"change")}</em><span>${escapeHTML(item.path||"/")}</span></div>`).join("")||'<div class="empty">No events yet.</div>';
}

let refreshTimer=0,selectedReloadTimer=0;
async function handleNativeEvent(event){
  addActivity(event);
  clearTimeout(refreshTimer);
  refreshTimer=setTimeout(()=>loadDirectory(state.cwd).catch(()=>{}),120);
  if(!state.selected||event.directory||event.path!==state.selected)return;
  if(state.dirty){setConflict(`External change detected in ${state.selected} while you have unsaved edits.`);return;}
  if(!followLive.checked)return;
  clearTimeout(selectedReloadTimer);
  selectedReloadTimer=setTimeout(()=>openFile(state.selected,{external:true}).catch(error=>setConflict(error.message)),180);
}

window.addEventListener("message",event=>{
  const message=event.data;if(!message||message.channel!==CHANNEL)return;
  if(message.kind==="response"){
    const waiter=pending.get(message.id);if(!waiter)return;clearTimeout(waiter.timer);pending.delete(message.id);message.ok?waiter.resolve(message.value):waiter.reject(new Error(message.error||"Workspace RPC failed"));return;
  }
  if(message.kind==="event"&&message.event)handleNativeEvent(message.event);
  if(message.kind==="connected")setLive(true,"Live");
});

fileList.addEventListener("click",event=>{const row=event.target.closest("[data-path]");if(!row)return;row.dataset.kind==="directory"?loadDirectory(row.dataset.path).catch(showError):openFile(row.dataset.path).catch(showError);});
filterInput.addEventListener("input",renderFiles);
$("#upBtn").onclick=()=>loadDirectory(parentPath(state.cwd)).catch(showError);
$("#refreshBtn").onclick=()=>loadDirectory(state.cwd).catch(showError);
$("#saveBtn").onclick=()=>save();
$("#clearActivity").onclick=()=>{state.events=[];activityLog.innerHTML='<div class="empty">Activity cleared.</div>';};
editor.addEventListener("input",()=>{state.dirty=editor.value!==state.baseline;saveBtn.disabled=!state.dirty;if(state.dirty)setRevision(state.revision,"unsaved edits");});
editor.addEventListener("keydown",event=>{if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==="s"){event.preventDefault();save();}});
function showError(error){setLive(false,error.message);liveText.classList.add("status-error");}

parent.postMessage({channel:CHANNEL,kind:"ready"},"*");
rpc("info").then(info=>{setLive(!!info?.watch?.active,"Live");return loadDirectory("");}).catch(showError);
