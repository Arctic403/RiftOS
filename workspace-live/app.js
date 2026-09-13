export function mountWorkspaceRecords(root,invoke,subscribe){
let destroyed=false;
const state={local:null,git:null,view:"local",selectedPath:"",selectedRecord:null,lastDiff:""};

const $=selector=>root.querySelector(selector);
const liveDot=$("#liveDot"),liveText=$("#liveText"),checkpointText=$("#checkpointText"),localCount=$("#localCount"),recordCount=$("#recordCount"),gitCount=$("#gitCount"),gitHead=$("#gitHead"),filterInput=$("#filterInput"),localTab=$("#localTab"),gitTab=$("#gitTab"),changeList=$("#changeList"),recordList=$("#recordList"),diffTitle=$("#diffTitle"),diffMeta=$("#diffMeta"),diffOutput=$("#diffOutput"),copyDiffBtn=$("#copyDiffBtn"),affectedMeta=$("#affectedMeta"),statusText=$("#statusText");

const rpc=(method,args={})=>invoke(method,args);
function escapeHTML(value){return String(value??"").replace(/[&<>"']/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[ch]));}
function fmtTime(value){const n=Number(value||0);return n?new Date(n).toLocaleString():"—";}
function shortSha(value){const text=String(value||"");return text?text.slice(0,12):"—";}
function setLive(on,text=on?"Local recording on":"Local watcher unavailable"){liveDot.classList.toggle("on",!!on);liveText.textContent=text;}
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
  checkpointText.textContent=`Local history · comparison baseline ${fmtTime(checkpoint.at)}${checkpoint.reason?.startsWith("git:")?" (Git sync)":""}`;
  const gitSummary=state.git?.summary;
  gitCount.textContent=gitSummary?String(gitSummary.total??0):state.git?.error?"!":"—";
  gitHead.textContent=state.git?.headSha?`${state.git.repo||"Git"} · ${shortSha(state.git.headSha)}`:(state.git?.error||"open Git tab to compare");
}

function renderChanges(){
  localTab.classList.toggle("active",state.view==="local");
  gitTab.classList.toggle("active",state.view==="git");
  affectedMeta.textContent=state.view==="git"?"Remote Git tree diff":"Local checkpoint diff";
  const needle=filterInput.value.trim().toLowerCase();
  const rows=visibleFiles().filter(row=>!needle||String(row.path||"").toLowerCase().includes(needle));
  if(!rows.length){changeList.innerHTML=`<div class="empty">${state.view==="git"&&state.git?.error?escapeHTML(state.git.error):state.view==="local"?"No changes since the last baseline. Recent writes are in Local activity.":"No Git changes."}</div>`;return;}
  changeList.innerHTML=rows.map(row=>`<button class="change-row ${row.path===state.selectedPath?"selected":""}" data-path="${escapeHTML(row.path)}">${statusBadge(row.status||"modified")}<b>${escapeHTML(row.path)}</b><small>${state.view==="git"?(row.binary?"binary":"git diff"):shortSha(row.after?.sha256||row.before?.sha256)}</small></button>`).join("");
}

function renderRecords(){
  const rows=Array.isArray(state.local?.records)?state.local.records:[];
  if(!rows.length){recordList.innerHTML='<div class="empty">No workspace writes recorded yet.</div>';return;}
  recordList.innerHTML=rows.map(row=>`<button class="record-row${row.id===state.selectedRecord?.id?" selected":""}" data-record-id="${escapeHTML(row.id)}"><time>${escapeHTML(new Date(Number(row.at||Date.now())).toLocaleTimeString([], {hour:"2-digit",minute:"2-digit",second:"2-digit"}))}</time>${statusBadge(row.action||"change")}<b>${escapeHTML(row.path||"/")}</b><small>${escapeHTML(row.source||"local")}</small></button>`).join("");
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
  renderChanges();renderRecords();
}

function selectPath(path){const row=findVisible(path);showDiff(row);}
function selectRecord(id){const row=(state.local?.records||[]).find(item=>item.id===id);if(row)showDiff(row,{record:true});}

async function refreshLocal({preserveSelection=true}={}){
  const previous=preserveSelection?state.selectedPath:"";
  const result=await rpc("records",{limit:220,includeDiff:true});
  if(destroyed)return;
  state.local=result;
  renderSummary();renderRecords();renderChanges();
  if(previous){const row=findVisible(previous);if(row)showDiff(row);}
  else if(!state.selectedRecord&&state.local.records?.length)showDiff(state.local.records[0],{record:true});
}
async function refreshGit(){
  try{const result=await rpc("gitDiff",{maxFiles:60,maxChars:360000});if(destroyed)return;state.git=result;}
  catch(error){if(destroyed)return;state.git={error:error.message,files:[],summary:{total:0}};}
  renderSummary();if(state.view==="git")renderChanges();
}
async function refreshAll(){
  setStatus("Refreshing local records…");
  try{
    await refreshLocal();
    if(destroyed)return;
    setStatus(`Updated ${new Date().toLocaleTimeString()}`);
  }catch(error){if(destroyed)return;setStatus(error.message,true);checkpointText.textContent=`Could not read local history: ${error.message}`;recordList.innerHTML='<div class="empty">Local records unavailable. Tap Refresh to retry.</div>';changeList.innerHTML='<div class="empty">Local records unavailable.</div>';throw error;}
}

let eventRefreshTimer=0;
function handleNativeEvent(){
  if(destroyed)return;
  clearTimeout(eventRefreshTimer);
  eventRefreshTimer=setTimeout(()=>refreshLocal().catch(error=>setStatus(error.message,true)),350);
}
const unsubscribe=subscribe(handleNativeEvent);

changeList.addEventListener("click",event=>{const row=event.target.closest("[data-path]");if(row)selectPath(row.dataset.path);});
recordList.addEventListener("click",event=>{const row=event.target.closest("[data-record-id]");if(row)selectRecord(row.dataset.recordId);});
filterInput.addEventListener("input",renderChanges);
localTab.onclick=()=>{state.view="local";state.selectedPath="";state.selectedRecord=null;renderChanges();showDiff(null);};
gitTab.onclick=()=>{state.view="git";state.selectedPath="";state.selectedRecord=null;renderChanges();showDiff(null);if(!state.git){setStatus("Comparing with Git…");refreshGit().then(()=>setStatus("Git comparison updated")).catch(error=>setStatus(error.message,true));}};
$("#refreshBtn").onclick=()=>refreshAll().catch(()=>{});
copyDiffBtn.onclick=async()=>{
  try{await navigator.clipboard.writeText(state.lastDiff);setStatus("Diff copied");}
  catch{setStatus("Clipboard unavailable; select and copy the diff manually",true);}
};

rpc("info").then(info=>{if(!destroyed)setLive(!!info?.watch?.active);}).catch(error=>{if(!destroyed){setLive(false);setStatus(error.message,true);}});
refreshAll().catch(()=>{});
return {destroy(){destroyed=true;clearTimeout(eventRefreshTimer);unsubscribe();}};
}
