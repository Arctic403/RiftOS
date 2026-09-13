const CHANNEL="riftworkspace-live-v2";
let requestSeq=0;
const pending=new Map();
const state={local:null,git:null,view:"local",selectedPath:"",selectedRecord:null,lastDiff:""};

const $=selector=>document.querySelector(selector);
const liveDot=$("#liveDot"),liveText=$("#liveText"),checkpointText=$("#checkpointText"),localCount=$("#localCount"),recordCount=$("#recordCount"),gitCount=$("#gitCount"),gitHead=$("#gitHead"),filterInput=$("#filterInput"),localTab=$("#localTab"),gitTab=$("#gitTab"),changeList=$("#changeList"),recordList=$("#recordList"),diffTitle=$("#diffTitle"),diffMeta=$("#diffMeta"),diffOutput=$("#diffOutput"),copyDiffBtn=$("#copyDiffBtn"),affectedMeta=$("#affectedMeta"),statusText=$("#statusText");

function rpc(method,args={}){
  const id=`wr-${Date.now()}-${++requestSeq}`;
  return new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{pending.delete(id);reject(new Error(`Workspace Records RPC timeout: ${method}`));},45000);
    pending.set(id,{resolve,reject,timer});
    parent.postMessage({channel:CHANNEL,kind:"request",id,method,args},"*");
  });
}
function escapeHTML(value){return String(value??"").replace(/[&<>"']/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[ch]));}
function fmtTime(value){const n=Number(value||0);return n?new Date(n).toLocaleString():"—";}
function shortSha(value){const text=String(value||"");return text?text.slice(0,12):"—";}
function setLive(on,text=on?"Recording":"Disconnected"){liveDot.classList.toggle("on",!!on);liveText.textContent=text;}
function setStatus(text,error=false){statusText.textContent=text;statusText.classList.toggle("error",!!error);}
function statusBadge(status){return `<i class="status ${escapeHTML(status)}">${escapeHTML(status)}</i>`;}

function localFiles(){return Array.isArray(state.local?.files)?state.local.files:[];}
function gitFiles(){return Array.isArray(state.git?.files)?state.git.files:[];}
function visibleFiles(){return state.view==="git"?gitFiles():localFiles();}
function findVisible(path){return visibleFiles().find(row=>row.path===path)||null;}

function renderSummary(){
  const summary=state.local?.summary||{},checkpoint=state.local?.checkpoint||{};
  localCount.textContent=String(summary.changedFiles??0);
  recordCount.textContent=String(summary.records??0);
  checkpointText.textContent=`Checkpoint ${fmtTime(checkpoint.at)} · ${checkpoint.reason||"initial"}${checkpoint.gitHeadSha?` · ${shortSha(checkpoint.gitHeadSha)}`:""}`;
  const gitSummary=state.git?.summary;
  gitCount.textContent=gitSummary?String(gitSummary.total??0):state.git?.error?"!":"—";
  gitHead.textContent=state.git?.headSha?`${state.git.repo||"Git"} · ${shortSha(state.git.headSha)}`:(state.git?.error||"remote comparison");
}

function renderChanges(){
  localTab.classList.toggle("active",state.view==="local");
  gitTab.classList.toggle("active",state.view==="git");
  affectedMeta.textContent=state.view==="git"?"Remote Git tree diff":"Local checkpoint diff";
  const needle=filterInput.value.trim().toLowerCase();
  const rows=visibleFiles().filter(row=>!needle||String(row.path||"").toLowerCase().includes(needle));
  if(!rows.length){changeList.innerHTML=`<div class="empty">${state.view==="git"&&state.git?.error?escapeHTML(state.git.error):"No changed files in this view."}</div>`;return;}
  changeList.innerHTML=rows.map(row=>`<button class="change-row ${row.path===state.selectedPath?"selected":""}" data-path="${escapeHTML(row.path)}">${statusBadge(row.status||"modified")}<b>${escapeHTML(row.path)}</b><small>${state.view==="git"?(row.binary?"binary":"git diff"):shortSha(row.after?.sha256||row.before?.sha256)}</small></button>`).join("");
}

function renderRecords(){
  const rows=Array.isArray(state.local?.records)?state.local.records:[];
  if(!rows.length){recordList.innerHTML='<div class="empty">No local changes recorded since this recorder was initialized.</div>';return;}
  recordList.innerHTML=rows.map(row=>`<button class="record-row" data-record-id="${escapeHTML(row.id)}"><time>${escapeHTML(new Date(Number(row.at||Date.now())).toLocaleTimeString([], {hour:"2-digit",minute:"2-digit",second:"2-digit"}))}</time>${statusBadge(row.action||"change")}<b>${escapeHTML(row.path||"/")}</b><small>${escapeHTML(row.source||"local")}</small></button>`).join("");
}

function showDiff(row,{record=false}={}){
  if(!row){state.lastDiff="";diffTitle.textContent="Select a changed file";diffMeta.textContent=state.view==="git"?"Remote Git tree diff":"Local checkpoint diff";diffOutput.textContent="No file selected.";copyDiffBtn.disabled=true;return;}
  state.selectedPath=row.path||"";
  state.selectedRecord=record?row:null;
  state.lastDiff=String(row.diff||"No text diff is available for this change.");
  diffTitle.textContent=row.path||"Workspace change";
  diffMeta.textContent=record?`${row.action||"change"} · ${fmtTime(row.at)} · ${row.source||"local"}`:(state.view==="git"?`${row.status||"changed"} · Git remote baseline`:`${row.status||"changed"} · local checkpoint`);
  diffOutput.textContent=state.lastDiff;
  copyDiffBtn.disabled=!state.lastDiff;
  renderChanges();
}

function selectPath(path){const row=findVisible(path);showDiff(row);}
function selectRecord(id){const row=(state.local?.records||[]).find(item=>item.id===id);if(row)showDiff(row,{record:true});}

async function refreshLocal({preserveSelection=true}={}){
  const previous=preserveSelection?state.selectedPath:"";
  state.local=await rpc("records",{limit:220,includeDiff:true});
  renderSummary();renderRecords();renderChanges();
  if(previous){const row=findVisible(previous);if(row)showDiff(row);}
}
async function refreshGit(){
  try{state.git=await rpc("gitDiff",{maxFiles:60,maxChars:360000});}
  catch(error){state.git={error:error.message,files:[],summary:{total:0}};}
  renderSummary();if(state.view==="git")renderChanges();
}
async function refreshAll(){
  setStatus("Refreshing local records…");
  try{
    await refreshLocal();setStatus("Refreshing Git comparison…");await refreshGit();
    setStatus(`Updated ${new Date().toLocaleTimeString()}`);
  }catch(error){setStatus(error.message,true);throw error;}
}

let eventRefreshTimer=0;
function handleNativeEvent(){
  clearTimeout(eventRefreshTimer);
  eventRefreshTimer=setTimeout(()=>refreshLocal().catch(error=>setStatus(error.message,true)),350);
}

window.addEventListener("message",event=>{
  const message=event.data;if(!message||message.channel!==CHANNEL)return;
  if(message.kind==="response"){
    const waiter=pending.get(message.id);if(!waiter)return;clearTimeout(waiter.timer);pending.delete(message.id);message.ok?waiter.resolve(message.value):waiter.reject(new Error(message.error||"Workspace Records RPC failed"));return;
  }
  if(message.kind==="event"&&message.event){handleNativeEvent();return;}
  if(message.kind==="connected"){setLive(!!message.watch?.active,message.watch?.active?"Recording":"Watcher offline");}
});

changeList.addEventListener("click",event=>{const row=event.target.closest("[data-path]");if(row)selectPath(row.dataset.path);});
recordList.addEventListener("click",event=>{const row=event.target.closest("[data-record-id]");if(row)selectRecord(row.dataset.recordId);});
filterInput.addEventListener("input",renderChanges);
localTab.onclick=()=>{state.view="local";state.selectedPath="";state.selectedRecord=null;renderChanges();showDiff(null);};
gitTab.onclick=()=>{state.view="git";state.selectedPath="";state.selectedRecord=null;renderChanges();showDiff(null);};
$("#refreshBtn").onclick=()=>refreshAll().catch(()=>{});
$("#checkpointBtn").onclick=async()=>{
  const button=$("#checkpointBtn");button.disabled=true;setStatus("Creating new local checkpoint…");
  try{await rpc("checkpoint",{reason:"manual-workspace-records"});await refreshLocal({preserveSelection:false});setStatus("New local checkpoint created");}
  catch(error){setStatus(error.message,true);}finally{button.disabled=false;}
};
copyDiffBtn.onclick=async()=>{
  try{await navigator.clipboard.writeText(state.lastDiff);setStatus("Diff copied");}
  catch{setStatus("Clipboard unavailable in sandbox; select and copy the diff manually",true);}
};

parent.postMessage({channel:CHANNEL,kind:"ready"},"*");
rpc("info").then(info=>setLive(!!info?.watch?.active,info?.watch?.active?"Recording":"Watcher offline")).catch(error=>setStatus(error.message,true));
refreshAll().catch(()=>{});
