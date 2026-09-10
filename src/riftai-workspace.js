const core=globalThis.RiftOSCore;
if(!core)throw new Error("Rift AI requires RiftOSCore");

const APP_ID="riftai";
const APP_NAME="Rift AI";
const POLL_MS=750;
let controller=null;

const esc=value=>String(value??"").replace(/[&<>"']/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[ch]));
const fmtTime=value=>{try{return new Date(Number(value)||Date.now()).toLocaleTimeString([],{hour:"2-digit",minute:"2-digit",second:"2-digit"});}catch(_){return "";}};
const fmtBytes=value=>{const n=Number(value||0);if(n<1024)return `${n} B`;if(n<1024**2)return `${(n/1024).toFixed(1)} KB`;return `${(n/1024**2).toFixed(1)} MB`;};
const native=(method,args={})=>core.native.call(`ai.${method}`,args);

function ensureLauncherCard(){
  const grid=document.querySelector("#appGrid");
  if(!grid||grid.querySelector('[data-rift-system-app="riftai"]'))return;
  const button=document.createElement("button");
  button.className="app-card";
  button.dataset.riftSystemApp="riftai";
  button.innerHTML='<span class="app-icon">✦</span><span><strong>Rift AI</strong><br><small>ChatGPT Web project workspace</small></span>';
  button.onclick=()=>open();
  grid.append(button);
}

function ensureStartEntry(){
  const grid=document.querySelector("#riftStartMenu .rift-start-grid");
  if(!grid||grid.querySelector('[data-rift-system-app="riftai"]'))return;
  const button=document.createElement("button");
  button.dataset.riftSystemApp="riftai";
  button.innerHTML='<b>✦</b><span>Rift AI</span>';
  button.onclick=()=>{document.querySelector("#riftStartMenu")?.classList.remove("open");open();};
  grid.append(button);
}

function install(){
  try{core.kernel.registerApp({id:APP_ID,name:APP_NAME,installed:true,system:true,permissions:["sandbox.read","sandbox.write"]});}catch(_){}
  ensureLauncherCard();
  ensureStartEntry();
}

function renderTree(rows){
  if(!rows?.length)return '<div class="rift-ai-empty">workspace is empty</div>';
  return rows.map(row=>{
    const path=String(row.path||"");
    const depth=Math.max(0,path.split("/").length-2);
    const icon=row.kind==="directory"?"▰":"▤";
    return `<div class="rift-ai-tree-row" style="--depth:${depth}"><span>${icon}</span><b>${esc(row.name||path)}</b>${row.kind==="file"?`<small>${fmtBytes(row.size)}</small>`:""}</div>`;
  }).join("");
}

function renderChanges(rows){
  if(!rows?.length)return '<div class="rift-ai-empty">No staged AI changes</div>';
  return rows.map(row=>{
    const plus=Number(row.additions||0),minus=Number(row.deletions||0);
    return `<button class="rift-ai-change" data-diff-path="${esc(row.path)}"><span class="rift-ai-change-status ${esc(row.status)}">${esc(row.status)}</span><b>${esc(row.path)}</b><small><i>+${plus}</i> <em>-${minus}</em></small></button>`;
  }).join("");
}

function eventLine(event){
  const type=String(event.type||"event");
  const data=event.data&&typeof event.data==="object"?event.data:{};
  let detail="";
  if(type==="tool"&&data.target)detail=` · ${data.target}`;
  return `<div class="rift-ai-log-line ${esc(type)}"><time>${fmtTime(event.at)}</time><span>${esc(type)}</span><p>${esc(event.message||type)}${esc(detail)}</p></div>`;
}

async function open(){
  await core.ready;
  const wm=globalThis.RiftOSWindowManager;
  if(!wm?.open)throw new Error("RiftOS window manager does not expose custom windows");
  const body=wm.open(APP_ID,APP_NAME,"CHATGPT WEB WORKSPACE");
  body.classList.add("rift-ai-window-body");
  body.innerHTML=`<div class="rift-ai-workspace">
    <header class="rift-ai-head">
      <div><span class="eyebrow">CHATGPT WEB ONLY</span><h2>Rift AI Workspace</h2><p>Local HTML cockpit · hidden authenticated ChatGPT Web transport · local MCP tools</p></div>
      <div class="rift-ai-head-actions"><button class="secondary" id="riftAiShowWeb">Show ChatGPT</button><button class="secondary" id="riftAiRefresh">Refresh</button></div>
    </header>
    <section class="rift-ai-compose">
      <textarea id="riftAiTask" placeholder="Describe what you want ChatGPT Web to do with the project…" spellcheck="true"></textarea>
      <div><button class="action" id="riftAiRun">Run with ChatGPT Web</button><button class="secondary" id="riftAiStop">Stop</button><span id="riftAiStatus">Ready</span></div>
    </section>
    <section class="rift-ai-targetbar">
      <label><span>Chat target</span><select id="riftAiTarget"><option value="new">New chat</option></select><small id="riftAiTargetHint">Start a clean ChatGPT Web conversation.</small></label>
      <div><button class="secondary" id="riftAiTargetRefresh">Refresh</button><button class="secondary" id="riftAiBrowseTargets">Browse all…</button></div>
    </section>
    <div class="rift-ai-grid">
      <aside class="rift-ai-panel rift-ai-project"><header><b>Project</b><small>tool-sandbox/workspace</small></header><div id="riftAiTree" class="rift-ai-tree"><div class="rift-ai-empty">Loading…</div></div></aside>
      <main class="rift-ai-panel rift-ai-output"><header><b>AI Output</b><small id="riftAiSession">No active session</small></header><article id="riftAiOutput"><div class="rift-ai-empty">Run a task to start a fresh hidden ChatGPT Web session.</div></article></main>
      <aside class="rift-ai-side">
        <section class="rift-ai-panel rift-ai-logs"><header><b>Live Logs</b><small>local structured events</small></header><div id="riftAiLogs"><div class="rift-ai-empty">No events yet</div></div></section>
        <section class="rift-ai-panel rift-ai-changes"><header><b>Changes</b><small id="riftAiChangeCount">0 files</small></header><div id="riftAiChanges"><div class="rift-ai-empty">No staged AI changes</div></div><footer><button class="action" id="riftAiAccept">Accept all</button><button class="secondary danger" id="riftAiRevert">Revert all</button></footer></section>
      </aside>
    </div>
    <section class="rift-ai-diff hidden" id="riftAiDiffPanel"><header><b id="riftAiDiffTitle">Diff</b><button class="secondary" id="riftAiDiffClose">Close</button></header><pre id="riftAiDiff"></pre></section>
  </div>`;

  controller?.stop?.();
  let stopped=false,lastSeq=0,polling=false,projectDirty=true,changesDirty=true,lastTransportActive=false,lastChangeCount=0;
  controller={stop(){stopped=true;}};
  const $=sel=>body.querySelector(sel);
  const status=$("#riftAiStatus"),tree=$("#riftAiTree"),output=$("#riftAiOutput"),logs=$("#riftAiLogs"),changes=$("#riftAiChanges"),changeCount=$("#riftAiChangeCount"),session=$("#riftAiSession");
  const runButton=$("#riftAiRun"),stopButton=$("#riftAiStop"),acceptButton=$("#riftAiAccept"),revertButton=$("#riftAiRevert");
  const targetSelect=$("#riftAiTarget"),targetHint=$("#riftAiTargetHint");
  let targetRegistry=new Map([["new",{mode:"new",kind:"new",label:"New chat",url:"https://chatgpt.com/"}]]);
  const WRITE_TOOLS=new Set(["rift_write_text","rift_mkdir","rift_remove","rift_move"]);

  function describeTarget(target){
    if(!target||target.kind==="new")return "Start a clean ChatGPT Web conversation.";
    if(target.kind==="project")return "Start a new chat inside this ChatGPT Project.";
    if(target.kind==="project-chat")return "Continue this existing chat inside its ChatGPT Project.";
    if(target.kind==="chat")return "Continue this existing ChatGPT conversation.";
    return "Use whatever ChatGPT page is currently open in RiftBrowser.";
  }

  function addTargetOption(group,target,key){
    const option=document.createElement("option");
    option.value=key;
    option.textContent=target.kind==="project"?`${target.label} · new project chat`:target.label;
    group.append(option);
    targetRegistry.set(key,target);
  }

  function renderTargets(result){
    const previous=targetSelect.value||"new";
    targetRegistry=new Map([["new",{mode:"new",kind:"new",label:"New chat",url:"https://chatgpt.com/"}]]);
    targetSelect.innerHTML="";
    const newOption=document.createElement("option");newOption.value="new";newOption.textContent="New chat";targetSelect.append(newOption);
    const current=result?.current&&typeof result.current==="object"?result.current:null;
    if(current){const option=document.createElement("option");option.value="current";option.textContent=`Current · ${current.label||"ChatGPT page"}`;targetSelect.append(option);targetRegistry.set("current",current);}
    const rows=Array.isArray(result?.targets)?result.targets:[];
    const groups=new Map();
    const groupLabel={chat:"Chats",project:"Projects", "project-chat":"Project chats"};
    for(const kind of ["chat","project","project-chat"]){const group=document.createElement("optgroup");group.label=groupLabel[kind];groups.set(kind,group);}
    let index=0;
    for(const row of rows){
      if(!row||!groups.has(row.kind)||!row.url)continue;
      const key=`target-${index++}`;
      addTargetOption(groups.get(row.kind),row,key);
    }
    for(const group of groups.values())if(group.children.length)targetSelect.append(group);
    if(targetRegistry.has(previous))targetSelect.value=previous;else targetSelect.value="new";
    targetHint.textContent=describeTarget(targetRegistry.get(targetSelect.value));
  }

  async function refreshTargets(){
    targetHint.textContent="Reading destinations from authenticated ChatGPT Web…";
    try{renderTargets(await native("targets"));}
    catch(error){targetHint.textContent=`Could not read ChatGPT targets: ${error.message}`;}
  }

  function updateControls(){
    runButton.disabled=lastTransportActive||lastChangeCount>0;
    stopButton.disabled=!lastTransportActive;
    acceptButton.disabled=lastTransportActive||lastChangeCount===0;
    revertButton.disabled=lastTransportActive||lastChangeCount===0;
    runButton.title=lastChangeCount>0&&!lastTransportActive?"Accept or revert the current changes before starting another task":"";
  }

  async function refreshTree(){
    try{tree.innerHTML=renderTree(await native("tree",{path:"workspace"}));projectDirty=false;}catch(error){tree.innerHTML=`<div class="rift-ai-empty error">${esc(error.message)}</div>`;}
  }
  async function refreshState(){
    try{
      const state=await native("state");
      lastTransportActive=Boolean(state?.transportActive);
      const stateStatus=String(state?.status||"idle");
      if(state?.active&&state.session){
        session.textContent=`${state.session.id||"Active session"} · ${stateStatus}`;
        status.textContent=lastTransportActive?"Running through ChatGPT Web":stateStatus==="review"?"Ready for review":stateStatus==="accepted"?"Changes accepted":stateStatus==="reverted"?"Changes reverted":stateStatus==="error"?"Task ended with an error":stateStatus==="stopped"?"Task stopped":stateStatus==="interrupted"?"Previous task was interrupted":"Ready";
      }else{
        session.textContent="No active session";
        status.textContent="Ready";
      }
      const latest=state?.latestAssistant;
      if(latest&&latest.text){output.textContent=latest.text;output.classList.add("has-output");}
      updateControls();
    }catch(_){}
  }
  async function refreshEvents(){
    try{
      const result=await native("events",{afterSeq:lastSeq});
      const rows=Array.isArray(result?.events)?result.events:[];
      if(rows.length){
        if(logs.querySelector(".rift-ai-empty"))logs.innerHTML="";
        logs.insertAdjacentHTML("beforeend",rows.map(eventLine).join(""));
        while(logs.children.length>300)logs.firstElementChild?.remove();
        logs.scrollTop=logs.scrollHeight;
        for(const event of rows){
          const data=event?.data&&typeof event.data==="object"?event.data:{};
          if(event?.type==="tool"&&data.phase==="finish"&&WRITE_TOOLS.has(String(data.tool||""))){projectDirty=true;changesDirty=true;}
          if(event?.type==="review"){projectDirty=true;changesDirty=true;}
          if(event?.type==="session"){projectDirty=true;changesDirty=true;}
        }
      }
      lastSeq=Number(result?.lastSeq||lastSeq);
    }catch(_){}
  }
  async function refreshChanges(){
    try{
      const rows=await native("changes");
      changes.innerHTML=renderChanges(rows);
      lastChangeCount=rows.length;
      changeCount.textContent=`${rows.length} file${rows.length===1?"":"s"}`;
      changes.querySelectorAll("[data-diff-path]").forEach(button=>button.onclick=()=>showDiff(button.dataset.diffPath));
      changesDirty=false;
      updateControls();
    }catch(error){changes.innerHTML=`<div class="rift-ai-empty error">${esc(error.message)}</div>`;}
  }
  async function refreshAll(){projectDirty=true;changesDirty=true;await Promise.all([refreshTree(),refreshState(),refreshEvents(),refreshChanges()]);}
  async function poll(){
    if(stopped||polling||!document.contains(body))return;
    polling=true;
    try{
      await Promise.all([refreshState(),refreshEvents()]);
      const work=[];
      if(changesDirty)work.push(refreshChanges());
      if(projectDirty)work.push(refreshTree());
      if(work.length)await Promise.all(work);
    }finally{polling=false;if(!stopped&&document.contains(body))setTimeout(poll,POLL_MS);}
  }
  async function showDiff(path){
    try{
      const result=await native("diff",{path});
      $("#riftAiDiffTitle").textContent=`Diff · ${path}`;
      $("#riftAiDiff").textContent=result?.diff||"No text diff available";
      $("#riftAiDiffPanel").classList.remove("hidden");
    }catch(error){status.textContent=error.message;}
  }

  $("#riftAiRun").onclick=async()=>{
    const task=$("#riftAiTask").value.trim();if(!task){status.textContent="Enter a task first";return;}
    const target=targetRegistry.get(targetSelect.value)||targetRegistry.get("new");
    status.textContent="Starting hidden ChatGPT Web session…";
    output.textContent="Waiting for ChatGPT Web…";output.classList.remove("has-output");
    logs.innerHTML='<div class="rift-ai-empty">Starting session…</div>';lastSeq=0;
    try{const result=await native("start",{task,target});session.textContent=result?.session?.session?.id||result?.session?.id||"Active session";lastTransportActive=true;projectDirty=true;changesDirty=true;status.textContent=`Running in ${target?.label||"ChatGPT Web"}`;updateControls();await refreshAll();}
    catch(error){status.textContent=error.message;}
  };
  $("#riftAiStop").onclick=async()=>{try{await native("stop");status.textContent="Stop requested";}catch(error){status.textContent=error.message;}};
  $("#riftAiRefresh").onclick=async()=>{await Promise.all([refreshAll(),refreshTargets()]);};
  $("#riftAiTargetRefresh").onclick=refreshTargets;
  targetSelect.onchange=()=>{targetHint.textContent=describeTarget(targetRegistry.get(targetSelect.value));};
  $("#riftAiBrowseTargets").onclick=async()=>{
    try{
      const state=await native("showWeb");
      const url=state?.url||"https://chatgpt.com";
      await globalThis.RiftDesktop?.openBrowser?.(url);
      setTimeout(()=>native("targetSearch").catch(()=>{}),350);
      status.textContent="Browse ChatGPT, choose a chat/project, then return and Refresh targets.";
    }catch(error){status.textContent=error.message;}
  };
  $("#riftAiShowWeb").onclick=async()=>{
    try{
      const state=await native("showWeb");
      const url=state?.url||"https://chatgpt.com";
      await globalThis.RiftDesktop?.openBrowser?.(url);
    }catch(error){status.textContent=error.message;}
  };
  $("#riftAiAccept").onclick=async()=>{try{const result=await native("accept");status.textContent=`Accepted ${result?.changes||0} change(s)`;projectDirty=true;changesDirty=true;await refreshAll();}catch(error){status.textContent=error.message;}};
  $("#riftAiRevert").onclick=async()=>{try{const result=await native("revert");status.textContent=`Reverted ${result?.changes||0} change(s)`;projectDirty=true;changesDirty=true;await refreshAll();}catch(error){status.textContent=error.message;}};
  $("#riftAiDiffClose").onclick=()=>$("#riftAiDiffPanel").classList.add("hidden");

  try{await native("hideWeb");}catch(_){}
  await Promise.all([refreshAll(),refreshTargets()]);
  poll();
  return true;
}

window.addEventListener("riftos:launcher-ready",install);
document.addEventListener("DOMContentLoaded",install);
setTimeout(install,0);

globalThis.RiftAIWorkspace=Object.freeze({open});
console.info("[RiftAI] ChatGPT Web workspace registered");
