const core=window.RiftOSCore;
if(!core)throw new Error("RiftOSCore must load before RiftOS desktop");

const BUILTIN_APPS=[
  {id:"files",name:"Files",icon:"▣",desc:"Android RiftFS + SAF mounts"},
  {id:"terminal",name:"RiftShell",icon:">_",desc:"RiftKernel command shell"},
  {id:"browser",name:"RiftBrowser",icon:"◎",desc:"RiftOS-owned browser · WebView compatibility renderer"},
  {id:"workspace-live",name:"Workspace Records",icon:"◈",desc:"Private local change history + diffs"},
  {id:"devlab",name:"Dev Lab",icon:"⚗",desc:"Live test · snapshot · publish to workspace"},
  {id:"editor",name:"Editor",icon:"{}",desc:"Native-backed RiftFS editor"},
  {id:"tasks",name:"Tasks",icon:"≡",desc:"RiftKernel processes"},
  {id:"settings",name:"Settings",icon:"⚙",desc:"Samsung / Android system"}
];

const $=selector=>document.querySelector(selector);
const escapeHTML=value=>String(value??"").replace(/[&<>"']/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[ch]));
const fmtBytes=value=>{const n=Number(value||0);if(n<1024)return `${n} B`;if(n<1024**2)return `${(n/1024).toFixed(1)} KB`;if(n<1024**3)return `${(n/1024**2).toFixed(1)} MB`;return `${(n/1024**3).toFixed(2)} GB`;};
const stage=$("#stage"),workspace=$("#workspace");
const windows=new Map();
let windowSerial=0;
const browserNativeListeners=new Set();
globalThis.RiftBrowserNative=Object.freeze({
  __state(state){
    const next=state&&typeof state==="object"?state:{};
    for(const listener of [...browserNativeListeners]){try{listener(next);}catch(_){} }
  }
});
const transferListeners=new Set();
function renderTransferState(value){
  const panel=$("#transferPanel");
  if(!panel)return;
  const state=value||{};
  const total=Number(state.totalBytes||0);
  const current=Number(state.bytes||0);
  const percent=total?Math.min(100,Math.round(current/total*100)):0;
  panel.textContent=`${state.operation||"transfer"} ${state.phase||""} ${percent}% ${state.currentPath||""}`;
  panel.classList.toggle("hidden",!state.transferId||state.phase==="complete"||state.phase==="failed");
}
globalThis.RiftTransferUI=Object.freeze({
  onProgress(listener){ if(typeof listener==="function") transferListeners.add(listener); return ()=>transferListeners.delete(listener); },
  __progress(value){ const next=value&&typeof value==="object"?value:{}; renderTransferState(next); for(const listener of [...transferListeners]){try{listener(next);}catch(_){}} }
});
const filesNavigation={history:["/"],index:0};
const filesClipboard={mode:"copy",paths:[]};
const protectedFileRoots=new Set(["/home","/apps","/system","/workspace","/downloads","/documents","/mounts"]);
let nativeDesktopEnabled=false,nativeDesktopBootstrap=null,nativeDesktopState=null,nativeDesktopSequence=-1,nativeLauncherTimer=0,nativeLauncherObserver=null,nativeDesktopPersistTimer=0,nativeDesktopSettings={};
try{
  await core.ready;
  nativeDesktopSettings=await core.fs.readJSON('/system/settings/desktop.json',{})||{};
  nativeDesktopBootstrap=await core.native.call("desktop.window.bootstrap",{});
  nativeDesktopEnabled=nativeDesktopBootstrap?.native===true;
}catch(error){console.warn("[RiftDesktop] native desktop bootstrap unavailable; using compatibility shell",error);}
if(nativeDesktopEnabled)document.documentElement.classList.add("rift-native-host");

function setStatus(value){const el=$("#statusText");if(el)el.textContent=value;}
function tick(){const clock=$("#clock");if(clock)clock.textContent=new Date().toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"});}
setInterval(tick,1000);tick();

function desktopMode(){return nativeDesktopEnabled||document.documentElement.classList.contains("rift-desktop-mode");}
function recordFor(target){
  if(!target)return null;
  if(typeof target==="string")return windows.get(target)||null;
  const win=target.closest?.(".window")||target;
  for(const record of windows.values())if(record.win===win)return record;
  return null;
}
function visibleRecords(){return [...windows.values()].filter(record=>!record.win.classList.contains("rift-minimized"));}
const nativeDesktopCall=(method,args={})=>core.native.call(`desktop.${method}`,args);
function nativeCssRect(rect={}){const scale=Number(window.devicePixelRatio||1)||1;return{left:Number(rect.left||0)/scale,top:Number(rect.top||0)/scale,width:Number(rect.width||0)/scale,height:Number(rect.height||0)/scale};}
async function persistNativeDesktopSettings(patch={}){nativeDesktopSettings={...nativeDesktopSettings,...patch,updated:Date.now()};try{await core.fs.writeJSON('/system/settings/desktop.json',nativeDesktopSettings);}catch(_){} }
function scheduleNativeGeometryPersist(state){
  if(!nativeDesktopEnabled||!state?.windows)return;clearTimeout(nativeDesktopPersistTimer);nativeDesktopPersistTimer=setTimeout(()=>{
    const saved={...(nativeDesktopSettings.windows||{})};for(const row of state.windows){if(row?.maximized)continue;const rect=nativeCssRect(row?.framePx||{});if(rect.width>0&&rect.height>0)saved[String(row.id)]={left:rect.left,top:rect.top,width:rect.width,height:rect.height};}
    persistNativeDesktopSettings({windows:saved});
  },120);
}
function applyNativeDesktopState(state){
  if(!nativeDesktopEnabled||!state||state.native!==true)return;
  const sequence=Number(state.sequence??-1);if(sequence>=0&&sequence<=nativeDesktopSequence)return;
  if(sequence>=0)nativeDesktopSequence=sequence;
  nativeDesktopState=state;
  const nativeRows=new Map((Array.isArray(state.windows)?state.windows:[]).map(row=>[String(row.id),row]));
  for(const record of [...windows.values()]){
    const row=nativeRows.get(record.id);
    if(!row){if(record.nativeOpened&&!record.nativePending&&!record.closing)closeWindow(record.win,{fromNative:true});continue;}
    record.nativeOpened=true;record.nativePending=false;record.lastFocus=Number(row.z||record.lastFocus||0);
    const wasMinimized=record.win.classList.contains("rift-minimized"),wasFocused=record.win.classList.contains("rift-focused");
    const minimized=Boolean(row.minimized),focused=Boolean(row.focused),rect=nativeCssRect(row.contentPx||{});
    record.win.classList.toggle("rift-minimized",minimized);record.win.classList.toggle("rift-focused",focused);record.win.classList.add("rift-native-content-window");
    Object.assign(record.win.style,{left:`${rect.left}px`,top:`${rect.top}px`,width:`${Math.max(1,rect.width)}px`,height:`${Math.max(1,rect.height)}px`,zIndex:String(Number(row.z||1)),display:minimized?"none":"block"});
    if(wasMinimized!==minimized)window.dispatchEvent(new CustomEvent("riftos:window-visibility",{detail:{id:record.id,window:record.win,visible:!minimized,reason:state.reason||"native"}}));
    if(focused&&!wasFocused){setStatus(record.title);window.dispatchEvent(new CustomEvent("riftos:window-activate",{detail:{id:record.id,window:record.win,pid:record.process?.pid}}));}
  }
  if(state.reason==="show-desktop")window.dispatchEvent(new Event("riftos:show-desktop"));
  if(["bounds","move","resize","restore","layout-reset","host-layout"].includes(String(state.reason||"")))scheduleNativeGeometryPersist(state);
  syncShellState();
}
globalThis.RiftNativeDesktop=Object.freeze({get enabled(){return nativeDesktopEnabled;},get state(){return nativeDesktopState;},__state:applyNativeDesktopState,request:(method,args={})=>nativeDesktopCall(method,args).then(value=>{applyNativeDesktopState(value);return value;})});
if(nativeDesktopBootstrap)applyNativeDesktopState(nativeDesktopBootstrap);
function syncShellState(){
  const visible=visibleRecords();
  if(nativeDesktopEnabled){stage.classList.remove("hidden");if(!visible.length)setStatus("Ready");return;}
  stage.classList.toggle("hidden",windows.size===0);
  if(desktopMode())workspace.classList.remove("hidden");
  else workspace.classList.toggle("hidden",visible.length>0);
  document.querySelectorAll(".dock-btn").forEach(btn=>btn.classList.toggle("active",btn.dataset.open==="home"&&visible.length===0));
  if(!visible.length)setStatus("Ready");
}
function focusWindow(target){
  const record=recordFor(target);if(!record)return false;
  record.lastFocus=Date.now();
  if(nativeDesktopEnabled){nativeDesktopCall("window.focus",{id:record.id}).then(applyNativeDesktopState).catch(error=>setStatus(error.message));return true;}
  record.win.classList.remove("rift-minimized");stage.classList.remove("hidden");if(!desktopMode())workspace.classList.add("hidden");setStatus(record.title);
  window.dispatchEvent(new CustomEvent("riftos:window-activate",{detail:{id:record.id,window:record.win,pid:record.process?.pid}}));
  return true;
}
function closeWindow(target,{fromProcess=false,fromNative=false}={}){
  const record=recordFor(target);if(!record)return false;if(record.closing)return true;record.closing=true;
  if(!fromProcess){try{if(record.process?.pid)core.kernel.kill(record.process.pid);}catch(_){}}
  windows.delete(record.id);record.win.remove();
  if(nativeDesktopEnabled&&!fromNative)nativeDesktopCall("window.close",{id:record.id}).then(applyNativeDesktopState).catch(()=>{});
  window.dispatchEvent(new CustomEvent("riftos:window-close",{detail:{id:record.id,pid:record.process?.pid}}));syncShellState();
  if(!nativeDesktopEnabled){const next=[...windows.values()].filter(item=>!item.win.classList.contains("rift-minimized")).sort((a,b)=>(b.lastFocus||0)-(a.lastFocus||0))[0];if(next)focusWindow(next.id);}
  return true;
}
function showDesktop(){
  if(nativeDesktopEnabled){nativeDesktopCall("window.showDesktop",{}).then(applyNativeDesktopState).catch(error=>setStatus(error.message));return true;}
  for(const record of windows.values())record.win.classList.add("rift-minimized");workspace.classList.remove("hidden");stage.classList.remove("hidden");window.dispatchEvent(new Event("riftos:show-desktop"));syncShellState();return true;
}
function setWindowTitle(target,title,kicker){
  const record=recordFor(target);if(!record)return false;record.title=String(title||record.title).slice(0,96);if(kicker!==undefined)record.kicker=String(kicker||"").slice(0,96);
  const titleNode=record.win.querySelector(".window-title"),kickerNode=record.win.querySelector(".window-kicker");if(titleNode)titleNode.textContent=record.title;if(kickerNode&&kicker!==undefined)kickerNode.textContent=record.kicker;
  if(nativeDesktopEnabled)nativeDesktopCall("window.title",{id:record.id,title:record.title,kicker:record.kicker||""}).then(applyNativeDesktopState).catch(()=>{});return true;
}
function openWindow(id,title,kicker="RIFT APP",processDetails={}){
  const existing=windows.get(id);
  if(existing){setWindowTitle(existing,title,kicker);const body=existing.win.querySelector(".window-body");body.innerHTML="";focusWindow(id);return body;}
  const template=$("#windowTemplate");if(!template)throw new Error("RiftOS window template is missing");
  const win=template.content.firstElementChild.cloneNode(true);win.dataset.app=id;win.dataset.windowId=`${id}-${++windowSerial}`;
  win.querySelector(".window-title").textContent=title;win.querySelector(".window-kicker").textContent=kicker;
  if(nativeDesktopEnabled)win.classList.add("rift-native-content-window");
  let record=null;const externalTerminate=typeof processDetails?.onTerminate==="function"?processDetails.onTerminate:null;const details={...processDetails};delete details.onTerminate;if(!details.kind)details.kind="ui";
  const process=core.kernel.launchProcess(id,title,{...details,onTerminate:()=>{try{externalTerminate?.();}finally{closeWindow(win,{fromProcess:true});}}});
  record={id,title,kicker,win,process,lastFocus:Date.now(),closing:false,nativePending:nativeDesktopEnabled,nativeOpened:false};windows.set(id,record);
  win.querySelector(".window-close").onclick=()=>closeWindow(win);win.addEventListener("pointerdown",()=>{if(nativeDesktopEnabled)focusWindow(id);},{capture:true});stage.classList.remove("hidden");stage.append(win);
  syncShellState();window.dispatchEvent(new CustomEvent("riftos:window-open",{detail:{id,window:win,pid:process?.pid,title}}));
  if(nativeDesktopEnabled){nativeDesktopCall("window.open",{id,title,kicker,pid:process?.pid||0,dpr:Number(window.devicePixelRatio||1)||1,boundsCss:nativeDesktopSettings.windows?.[id]||null}).then(state=>{record.nativePending=false;record.nativeOpened=true;applyNativeDesktopState(state);}).catch(error=>{record.nativePending=false;setStatus(`Native window failed: ${error.message}`);closeWindow(win,{fromNative:true});});}
  else focusWindow(id);
  return win.querySelector(".window-body");
}
function collectNativeLauncherApps(){
  const grid=$("#appGrid");if(!grid)return[];const rows=[],seen=new Set();
  for(const button of grid.querySelectorAll(".app-card,[data-rift-installed-app],[data-rift-app-manager],[data-rift-system-app]")){
    let id=button.dataset.open||button.dataset.riftInstalledApp||button.dataset.riftSystemApp||"";
    if(button.dataset.riftAppManager!==undefined)id=(globalThis.RiftRT||button.textContent?.includes("RiftRT"))?"riftrt":"riftapps";
    id=String(id||"").trim();if(!id||id==="home"||seen.has(id))continue;seen.add(id);
    const name=button.querySelector("strong")?.textContent?.trim()||button.querySelector("span")?.textContent?.trim()||id;const icon=button.querySelector(".app-icon,b")?.textContent?.trim()||"□";rows.push({id,name,icon});
  }
  return rows;
}
function syncNativeLauncher(){if(!nativeDesktopEnabled)return;clearTimeout(nativeLauncherTimer);nativeLauncherTimer=setTimeout(()=>nativeDesktopCall("launcher.update",{apps:collectNativeLauncherApps(),pins:Array.isArray(nativeDesktopSettings.taskbarPins)?nativeDesktopSettings.taskbarPins:[]}).then(applyNativeDesktopState).catch(()=>{}),20);}
function startNativeLauncherMirror(){if(!nativeDesktopEnabled||nativeLauncherObserver)return;const grid=$("#appGrid");if(!grid)return;nativeLauncherObserver=new MutationObserver(syncNativeLauncher);nativeLauncherObserver.observe(grid,{childList:true,subtree:true,characterData:true,attributes:true});syncNativeLauncher();}
function appGrid(){
  const grid=$("#appGrid");if(!grid)return;grid.innerHTML=BUILTIN_APPS.map(app=>`<button class="app-card" data-open="${app.id}"><span class="app-icon">${app.icon}</span><span><strong>${app.name}</strong><br><small>${app.desc}</small></span></button>`).join("");window.dispatchEvent(new Event("riftos:launcher-ready"));syncNativeLauncher();
}
function topLevelEntries(rows,path="/"){
  const base=core.path.normalize(path),map=new Map();
  for(const row of rows){
    if(row.path===base||!row.path.startsWith(base==="/"?"/":base+"/"))continue;
    const rest=row.path.slice(base==="/"?1:base.length+1);if(!rest)continue;
    const first=rest.split("/")[0],childPath=core.path.join(base,first),direct=rest===first,existing=map.get(first);
    if(!existing||direct)map.set(first,direct?row:{path:childPath,kind:"directory",size:0,modified:0,backend:row.backend});
  }
  return [...map.values()].sort((a,b)=>(a.kind==="directory"||a.kind==="mount"?-1:1)-(b.kind==="directory"||b.kind==="mount"?-1:1)||a.path.localeCompare(b.path));
}

function cleanLeafName(value){
  const name=String(value||"").trim();
  if(!name||name==="."||name===".."||/[\\/\0]/.test(name))throw new Error("Name cannot be empty or contain / or \\\\.");
  return name;
}
function duplicateBaseName(name){
  const dot=name.lastIndexOf(".");
  if(dot>0)return `${name.slice(0,dot)} copy${name.slice(dot)}`;
  return `${name} copy`;
}
async function uniqueChildPath(directory,preferred){
  const clean=cleanLeafName(preferred);
  let candidate=core.path.join(directory,clean),index=2;
  if(!(await core.fs.stat(candidate)))return candidate;
  const dot=clean.lastIndexOf("."),stem=dot>0?clean.slice(0,dot):clean,ext=dot>0?clean.slice(dot):"";
  while(await core.fs.stat(candidate))candidate=core.path.join(directory,`${stem} (${index++})${ext}`);
  return candidate;
}

const MAX_RIFT_EDITOR_BYTES=2*1024*1024;
const TEXT_EXTENSIONS=new Set(["txt","md","markdown","json","jsonl","js","mjs","cjs","ts","tsx","jsx","css","html","htm","xml","svg","csv","tsv","yaml","yml","toml","ini","conf","cfg","log","kt","kts","java","py","rs","go","c","cc","cpp","cxx","h","hpp","cs","sh","bash","zsh","fish","gradle","properties"]);
function looksTextEntry(entry){
  const mime=String(entry?.mime||entry?.mimeType||"").toLowerCase();
  if(mime.startsWith("text/")||mime.includes("json")||mime.includes("xml")||mime.includes("javascript"))return true;
  const name=core.path.basename(entry?.path||"");
  const ext=(name.includes(".")?name.split(".").pop():"").toLowerCase();
  return TEXT_EXTENSIONS.has(ext)||["readme","license","makefile","dockerfile"].includes(name.toLowerCase());
}
async function openFileEntry(entry){
  if(!entry)return;
  if(["directory","mount"].includes(entry.kind)){await openFiles(entry.path);return;}
  const size=Number(entry.size||0);
  if(entry.backend==="android-saf"&&(!looksTextEntry(entry)||size>MAX_RIFT_EDITOR_BYTES)){
    await core.fs.openNative(entry.path);
    setStatus("Files · opened with Android");
    return;
  }
  if(size>MAX_RIFT_EDITOR_BYTES)throw new Error(`Rift Editor opens text files up to ${fmtBytes(MAX_RIFT_EDITOR_BYTES)}. Copy this file to an Android mount and use Open there, or use another app.`);
  if(!looksTextEntry(entry))throw new Error("This file is not a supported text document. Files does not load binary data into the Rift Editor.");
  await openEditor(entry.path);
}

function createVirtualListRenderer(options={}){
  const rowHeight=Number(options.rowHeight||34);
  const overscan=Number(options.overscan||8);
  let rows=[];
  let viewport=null;
  let renderRow=()=>"";
  const state={start:0,end:0};
  function update(){
    if(!viewport)return;
    const top=viewport.scrollTop||0;
    const height=viewport.clientHeight||0;
    state.start=Math.max(0,Math.floor(top/rowHeight)-overscan);
    state.end=Math.min(rows.length,Math.ceil((top+height)/rowHeight)+overscan);
    const fragment=document.createDocumentFragment();
    for(let i=state.start;i<state.end;i++){
      const holder=document.createElement("div");
      holder.innerHTML=renderRow(rows[i],i).trim();
      fragment.appendChild(holder.firstElementChild);
    }
    const content=document.createElement("div");
    content.style.height=`${rows.length*rowHeight}px`;
    content.style.position="relative";
    content.appendChild(fragment);
    viewport.replaceChildren(content);
  }
  return {
    mount(target,data,rowRenderer){viewport=target;rows=data||[];renderRow=rowRenderer||(()=>"");update();viewport.onscroll=update;},
    setRows(data){rows=data||[];update();},
    refresh:update
  };
}

async function openFiles(path="/",options={}){
  await core.ready;
  path=core.path.normalize(path);
  if(options.record!==false){
    const current=filesNavigation.history[filesNavigation.index];
    if(current!==path){
      filesNavigation.history=filesNavigation.history.slice(0,filesNavigation.index+1);
      filesNavigation.history.push(path);
      filesNavigation.index=filesNavigation.history.length-1;
    }
  }
  const body=openWindow("files","Files","ANDROID RIFTFS");
  body.classList.add("rift-files-window-body");
  let rows=[];
  try{rows=await core.fs.list(path,{recursive:false});}
  catch(error){body.innerHTML=`<div class="rift-explorer-error"><strong>Cannot open ${escapeHTML(path)}</strong><pre>${escapeHTML(error.message)}</pre></div>`;return;}
  const entries=topLevelEntries(rows,path),storage=await core.fs.estimate(),parent=path==="/"?null:core.path.parent(path);
  const byPath=new Map(entries.map(entry=>[entry.path,entry]));
  const selected=new Set();
  let selectionBox=null;
  const roots=[
    ["/home","⌂","Home"],["/documents","▤","Documents"],["/downloads","⇩","Downloads"],
    ["/workspace","◇","Workspace"],["/apps","▦","Apps"],["/mounts","⛓","Android mounts"]
  ];
  const pathParts=path==="/"?[]:path.split("/").filter(Boolean);
  const crumbs=[`<button data-crumb="/">RiftFS</button>`];
  let walk="";
  for(const part of pathParts){walk+=`/${part}`;crumbs.push(`<span>›</span><button data-crumb="${escapeHTML(walk)}">${escapeHTML(part)}</button>`);}
  const canBack=filesNavigation.index>0,canForward=filesNavigation.index<filesNavigation.history.length-1;
  const virtualMountsRoot=path==="/mounts";
  body.innerHTML=`<div class="rift-explorer">
    <div class="rift-explorer-commandbar">
      <div class="rift-explorer-navbuttons">
        <button id="fsBack" title="Back" aria-label="Back" ${canBack?"":"disabled"}>←</button>
        <button id="fsForward" title="Forward" aria-label="Forward" ${canForward?"":"disabled"}>→</button>
        <button id="fsUp" title="Up" aria-label="Up" ${parent===null?"disabled":""}>↑</button>
        <button id="fsRefresh" title="Refresh" aria-label="Refresh">↻</button>
      </div>
      <div class="rift-explorer-address" id="fsAddress">${crumbs.join("")}</div>
      <button class="rift-explorer-new primary" id="fsNewFile" ${virtualMountsRoot?"disabled":""}>＋ File</button>
      <button class="rift-explorer-new" id="fsNewFolder" ${virtualMountsRoot?"disabled":""}>＋ Folder</button>
    </div>
    <div class="rift-explorer-actionbar">
      <button id="fsOpen" disabled>Open</button>
      <button id="fsRename" disabled>Rename</button>
      <button id="fsCopy" disabled>Copy</button>
      <button id="fsCut" disabled>Cut</button>
      <button id="fsPaste" ${filesClipboard.paths.length&&!virtualMountsRoot?"":"disabled"}>Paste${filesClipboard.paths.length?` (${filesClipboard.paths.length})`:""}</button>
      <button id="fsDuplicate" disabled>Duplicate</button>
      <button id="fsMove" disabled>Move</button>
      <button id="fsDelete" class="danger" disabled>Delete</button>
      <button id="fsSelectAll" ${entries.length?"":"disabled"}>Select all</button>
    </div>
    <div class="rift-explorer-main">
      <aside class="rift-explorer-sidebar">
        <strong>Quick access</strong>
        ${roots.map(([target,icon,label])=>`<button data-place="${target}" class="${path===target?"active":""}"><span>${icon}</span>${label}</button>`).join("")}
        <div class="rift-explorer-sidebar-sep"></div>
        <button id="fsMountNative"><span>＋</span>Mount Android folder</button>
      </aside>
      <section class="rift-explorer-content">
        <div class="rift-explorer-columns"><span>Name</span><span>Type</span><span>Size</span></div>
        <div class="rift-explorer-list">${entries.length?entries.map(entry=>{
          const folder=entry.kind==="directory"||entry.kind==="mount";
          const name=core.path.basename(entry.path)||entry.path;
          const icon=entry.kind==="mount"?"⛓":folder?"▰":"▤";
          const type=entry.kind==="mount"?"Android mount":folder?"Folder":((name.split(".").pop()||"file").toUpperCase()+" file");
          return `<button class="rift-explorer-item" data-path="${escapeHTML(entry.path)}" data-kind="${escapeHTML(entry.kind)}" aria-pressed="false"><span class="rift-explorer-name"><i>${icon}</i><b>${escapeHTML(name)}</b></span><span>${escapeHTML(type)}</span><span>${folder?"—":fmtBytes(entry.size)}</span></button>`;
        }).join(""):`<div class="rift-explorer-empty"><span>□</span><strong>This folder is empty</strong><small>Create a file or folder to get started.</small></div>`}</div>
      </section>
    </div>
    <footer class="rift-explorer-status"><span id="fsSelectionStatus">${entries.length} item${entries.length===1?"":"s"}</span><span>${escapeHTML(storage.backend||"Android internal storage")} · ${fmtBytes(storage.usage)} used · ${fmtBytes(storage.free)} free</span></footer>
  </div>`;

  const itemButtons=[...body.querySelectorAll(".rift-explorer-item")];
  const actionIds=["fsOpen","fsRename","fsCopy","fsCut","fsDuplicate","fsMove","fsDelete"];
  async function openDestinationPicker(startPath,suggestedName,folderOnly=false){
    const start=folderOnly?core.path.normalize(startPath):core.path.parent(startPath);
    return new Promise((resolve,reject)=>{
      const overlay=document.createElement("div");
      overlay.className="rift-destination-picker";
      let current=start;
      const finish=value=>{overlay.remove();resolve(value);};
      const fail=error=>{overlay.remove();reject(error);};
      const render=async()=>{
        const rows=await core.fs.list(current,{recursive:false});
        overlay.innerHTML=`<div class="rift-destination-dialog"><header>Select destination</header><div class="rift-destination-path"><button data-up aria-label="Up">↑</button> ${escapeHTML(current)}</div><div class="rift-destination-list">${rows.filter(row=>row.kind==="directory"||row.kind==="mount").map(row=>`<button data-path="${escapeHTML(row.path)}">${escapeHTML(core.path.basename(row.path)||row.path)}</button>`).join("")||"<span>Empty folder</span>"}</div><input id="destinationName" value="${escapeHTML(suggestedName)}" ${folderOnly?"hidden":""}><footer><button data-cancel>Cancel</button><button data-confirm>Confirm</button></footer></div>`;
        overlay.querySelectorAll("[data-path]").forEach(button=>button.onclick=()=>{current=button.dataset.path;render().catch(fail);});
        overlay.querySelector("[data-up]").onclick=()=>{const parent=core.path.parent(current);if(parent&&parent!==current){current=parent;render().catch(fail);}};
        overlay.querySelector("[data-cancel]").onclick=()=>finish(null);
        overlay.querySelector("[data-confirm]").onclick=async()=>{try{const name=overlay.querySelector("#destinationName")?.value.trim()||"";if(!folderOnly&&!name)return;const target=folderOnly?current:core.path.join(current,name);if(await core.fs.stat(target)&&!folderOnly&&!confirm("Destination exists. Continue and overwrite/merge where supported?"))return;finish(target);}catch(error){fail(error);}};
      };
      document.body.appendChild(overlay);
      render().catch(fail);
    });
  }
  async function chooseArchiveDestination(sourcePath,mode){
    const base=core.path.basename(sourcePath).replace(/\.zip$/i,"");
    const suggested=mode==="zip"?`${core.path.basename(sourcePath)}.zip`:base;
    return openDestinationPicker(sourcePath,suggested);
  }
  function showFileMenu(entry,x,y){
    document.querySelector('.rift-file-context-menu')?.remove();
    const menu=document.createElement('div'); menu.className='rift-file-context-menu';
    menu.innerHTML='<button data-action="open">Open</button><button data-action="copy">Copy</button><button data-action="cut">Move</button><button data-action="paste">Paste</button><button data-action="delete">Delete</button><button data-action="rename">Rename</button><button data-action="zip">Zip</button><button data-action="unzip">Unzip</button>';
    menu.style.left=x+'px'; menu.style.top=y+'px'; document.body.appendChild(menu);
    menu.onclick=async e=>{const action=e.target.dataset.action;if(!action)return;menu.remove();if(action==='open')await openEntry(entry);if(action==='copy')body.querySelector('#fsCopy').click();if(action==='cut')body.querySelector('#fsCut').click();if(action==='paste'){const destination=entry.kind==='directory'||entry.kind==='mount'?entry.path:path;runFileAction(filesClipboard.mode==="cut"?"Move":"Paste",()=>pasteInto(destination));}if(action==='delete')body.querySelector('#fsDelete').click();if(action==='rename')body.querySelector('#fsRename').click();if(action==='zip'&&!protectedFileRoots.has(entry.path))runFileAction("Zip",async()=>{const target=await chooseArchiveDestination(entry.path,"zip");if(!target)throw new Error("Cancelled");await core.fs.zip(entry.path,target);});if(action==='unzip')runFileAction("Unzip",async()=>{const target=await chooseArchiveDestination(entry.path,"unzip");if(!target)throw new Error("Cancelled");await core.fs.unzip(entry.path,target);});};
  }
  function startSelectionBox(x,y){
    selectionBox=document.createElement('div');
    selectionBox.className='rift-selection-box';
    document.body.appendChild(selectionBox);
    const origin={x,y};
    const update=event=>{
      const left=Math.min(origin.x,event.clientX), top=Math.min(origin.y,event.clientY);
      const width=Math.abs(event.clientX-origin.x), height=Math.abs(event.clientY-origin.y);
      selectionBox.style.left=left+'px';
      selectionBox.style.top=top+'px';
      selectionBox.style.width=width+'px';
      selectionBox.style.height=height+'px';
      const box={left,top,right:left+width,bottom:top+height};
      for(const button of itemButtons){
        const rect=button.getBoundingClientRect();
        const hit=rect.right>=box.left&&rect.left<=box.right&&rect.bottom>=box.top&&rect.top<=box.bottom;
        button.classList.toggle('selected',hit);
        if(hit) selected.add(button.dataset.path); else selected.delete(button.dataset.path);
      }
      syncSelection();
    };
    const finish=()=>{
      window.removeEventListener('pointermove',update);
      window.removeEventListener('pointerup',finish);
      window.removeEventListener('pointercancel',finish);
      selectionBox?.remove();
      selectionBox=null;
    };
    window.addEventListener('pointermove',update);
    window.addEventListener('pointerup',finish,{once:true});
    window.addEventListener('pointercancel',finish,{once:true});
  }
  const selectionStatus=body.querySelector("#fsSelectionStatus");
  function chosen(){return [...selected].map(value=>byPath.get(value)).filter(Boolean);}
  function syncSelection(){
    for(const button of itemButtons){const active=selected.has(button.dataset.path);button.classList.toggle("selected",active);button.setAttribute("aria-pressed",active?"true":"false");}
    const picked=chosen(),has=picked.length>0,hasMount=picked.some(entry=>entry.kind==="mount"),hasProtected=picked.some(entry=>protectedFileRoots.has(entry.path));
    const one=picked.length===1;
    for(const id of actionIds){const button=body.querySelector(`#${id}`);if(button)button.disabled=!has||hasMount||hasProtected;}
    body.querySelector("#fsOpen").disabled=!one;
    body.querySelector("#fsRename").disabled=!one||hasMount||hasProtected;
    selectionStatus.textContent=has?`${picked.length} selected · ${entries.length} item${entries.length===1?"":"s"}`:`${entries.length} item${entries.length===1?"":"s"}`;
  }
  async function refresh(){await openFiles(path,{record:false});}
  async function openEntry(entry){
    if(!entry)return;
    try{await openFileEntry(entry);}
    catch(error){setStatus("Files");alert(`Open failed: ${error?.message||error}`);}
  }
  let fileActionBusy=false;
  async function runFileAction(label,work){
    if(fileActionBusy)return;
    fileActionBusy=true;
    try{
      setStatus(`Files · ${label} queued`);
      await new Promise(resolve=>requestAnimationFrame(resolve));
      await work();
      setStatus(`Files · ${label} complete`);
      await refresh();
    }
    catch(error){setStatus("Files");alert(`${label} failed: ${error?.message||error}`);syncSelection();}
    finally{fileActionBusy=false;}
  }
  async function pasteInto(destinationDir){
    const pending=[...filesClipboard.paths];if(!pending.length)return;
    const completed=[];
    for(const source of pending){
      if(!(await core.fs.stat(source)))continue;
      if(filesClipboard.mode==="cut"&&core.path.parent(source)===destinationDir){completed.push(source);continue;}
      let destination=core.path.join(destinationDir,core.path.basename(source));
      if(await core.fs.stat(destination))destination=await uniqueChildPath(destinationDir,duplicateBaseName(core.path.basename(source)));
      if(filesClipboard.mode==="cut")await core.fs.move(source,destination);else await core.fs.copy(source,destination);
      completed.push(source);
    }
    if(filesClipboard.mode==="cut"){filesClipboard.paths=filesClipboard.paths.filter(source=>!completed.includes(source));if(!filesClipboard.paths.length)filesClipboard.mode="copy";}
  }

  body.querySelector("#fsBack").onclick=()=>{if(filesNavigation.index>0){filesNavigation.index--;openFiles(filesNavigation.history[filesNavigation.index],{record:false});}};
  body.querySelector("#fsForward").onclick=()=>{if(filesNavigation.index<filesNavigation.history.length-1){filesNavigation.index++;openFiles(filesNavigation.history[filesNavigation.index],{record:false});}};
  body.querySelector("#fsUp").onclick=()=>parent!==null&&openFiles(parent);
  body.querySelector("#fsRefresh").onclick=()=>refresh();
  body.querySelectorAll("[data-crumb]").forEach(button=>button.onclick=()=>openFiles(button.dataset.crumb));
  body.querySelectorAll("[data-place]").forEach(button=>button.onclick=()=>openFiles(button.dataset.place));
  for(const button of itemButtons){
    let pressStart=null,longPressTimer=0;
    const cancelLongPress=()=>{clearTimeout(longPressTimer);longPressTimer=0;pressStart=null;};
    button.onclick=event=>{
      const target=button.dataset.path;
      if(event.ctrlKey||event.metaKey||event.shiftKey){if(selected.has(target))selected.delete(target);else selected.add(target);}
      else{selected.clear();selected.add(target);}
      syncSelection();
    };
    button.oncontextmenu=event=>{event.preventDefault();selected.clear();selected.add(button.dataset.path);syncSelection();showFileMenu(byPath.get(button.dataset.path),event.clientX,event.clientY);};
    button.onpointerdown=event=>{cancelLongPress();pressStart={x:event.clientX,y:event.clientY};const x=event.clientX,y=event.clientY;longPressTimer=setTimeout(()=>{selected.clear();selected.add(button.dataset.path);syncSelection();showFileMenu(byPath.get(button.dataset.path),x,y);cancelLongPress();},550);};
    button.onpointermove=event=>{if(pressStart&&Math.hypot(event.clientX-pressStart.x,event.clientY-pressStart.y)>8)cancelLongPress();};
    button.onpointerup=cancelLongPress;
    button.onpointercancel=cancelLongPress;
    button.ondblclick=()=>openEntry(byPath.get(button.dataset.path));
  }
  body.querySelector("#fsSelectAll").onclick=()=>{if(selected.size===entries.length)selected.clear();else entries.forEach(entry=>selected.add(entry.path));syncSelection();};
  body.querySelector('.rift-explorer-list').onpointerdown=event=>{
    if(event.target.closest('.rift-explorer-item'))return;
    selected.clear();
    syncSelection();
    startSelectionBox(event.clientX,event.clientY);
  };
  body.querySelector("#fsOpen").onclick=()=>openEntry(chosen()[0]);
  body.querySelector("#fsNewFile").onclick=async()=>{
    try{const name=prompt("File name","untitled.txt");if(!name)return;const target=core.path.join(path,cleanLeafName(name));if(await core.fs.stat(target))throw new Error("A file or folder with that name already exists.");await core.fs.createFile(target,"");await refresh();await openEditor(target);}
    catch(error){alert(`Create file failed: ${error?.message||error}`);}
  };
  body.querySelector("#fsNewFolder").onclick=()=>runFileAction("Create folder",async()=>{const name=prompt("Folder name","New Folder");if(!name)throw new Error("Cancelled");const target=core.path.join(path,cleanLeafName(name));if(await core.fs.stat(target))throw new Error("A file or folder with that name already exists.");await core.fs.mkdir(target);});
  body.querySelector("#fsRename").onclick=()=>runFileAction("Rename",async()=>{const entry=chosen()[0];if(!entry)return;const current=core.path.basename(entry.path),name=prompt("New name",current);if(!name||name===current)throw new Error("Cancelled");const destination=core.path.join(core.path.parent(entry.path),cleanLeafName(name));if(await core.fs.stat(destination))throw new Error("A file or folder with that name already exists.");await core.fs.rename(entry.path,destination);});
  body.querySelector("#fsCopy").onclick=()=>{filesClipboard.mode="copy";filesClipboard.paths=chosen().map(entry=>entry.path);const paste=body.querySelector("#fsPaste");paste.disabled=!filesClipboard.paths.length||virtualMountsRoot;paste.textContent=`Paste${filesClipboard.paths.length?` (${filesClipboard.paths.length})`:""}`;setStatus(`Files · ${filesClipboard.paths.length} copied`);};
  body.querySelector("#fsCut").onclick=()=>{filesClipboard.mode="cut";filesClipboard.paths=chosen().map(entry=>entry.path);const paste=body.querySelector("#fsPaste");paste.disabled=!filesClipboard.paths.length||virtualMountsRoot;paste.textContent=`Paste${filesClipboard.paths.length?` (${filesClipboard.paths.length})`:""}`;setStatus(`Files · ${filesClipboard.paths.length} cut`);};
  body.querySelector("#fsPaste").onclick=()=>runFileAction(filesClipboard.mode==="cut"?"Move":"Paste",()=>pasteInto(path));
  body.querySelector("#fsDuplicate").onclick=()=>runFileAction("Duplicate",async()=>{for(const entry of chosen()){const destination=await uniqueChildPath(path,duplicateBaseName(core.path.basename(entry.path)));await core.fs.copy(entry.path,destination);}});
  body.querySelector("#fsMove").onclick=()=>runFileAction("Move",async()=>{
    const destinationRaw=await openDestinationPicker(path,"",true);if(!destinationRaw)throw new Error("Cancelled");const destinationDir=core.path.normalize(destinationRaw);const stat=await core.fs.stat(destinationDir);if(!stat||!["directory","mount"].includes(stat.kind))throw new Error("Destination folder does not exist.");
    for(const entry of chosen()){if(core.path.parent(entry.path)===destinationDir)continue;let destination=core.path.join(destinationDir,core.path.basename(entry.path));if(await core.fs.stat(destination))destination=await uniqueChildPath(destinationDir,duplicateBaseName(core.path.basename(entry.path)));await core.fs.move(entry.path,destination);}
  });
  body.querySelector("#fsDelete").onclick=()=>runFileAction("Delete",async()=>{const picked=chosen();if(!picked.length)return;if(!confirm(`Delete ${picked.length} selected item${picked.length===1?"":"s"}? This cannot be undone.`))throw new Error("Cancelled");for(const entry of picked)await core.fs.remove(entry.path);});
  body.querySelector("#fsMountNative").onclick=async()=>{try{await core.fs.mountNativeDirectory();openFiles("/mounts");}catch(error){alert(error.message);}};
  syncSelection();
}

async function openEditor(path="/home/scratch.txt"){
  await core.ready;path=core.path.normalize(path);
  const meta=await core.fs.stat(path);
  if(meta?.kind==="directory"||meta?.kind==="mount"){openFiles(path);return;}
  if(meta&&Number(meta.size||0)>MAX_RIFT_EDITOR_BYTES)throw new Error(`Rift Editor opens text files up to ${fmtBytes(MAX_RIFT_EDITOR_BYTES)}.`);
  if(meta&&!looksTextEntry(meta))throw new Error("Rift Editor only opens text documents.");
  const file=await core.fs.get(path),body=openWindow("editor","Editor","ANDROID RIFTFS EDITOR");
  body.innerHTML=`<div class="trueos-editor"><div class="trueos-head"><div><strong>${escapeHTML(path)}</strong><small>${escapeHTML(file?.backend||"android-internal")}</small></div><button class="trueos-btn" id="editorFiles">Files</button><button class="trueos-btn primary" id="editorSave">Save</button></div><textarea id="editorText" aria-label="Editor text" spellcheck="false" autocomplete="off"></textarea></div>`;
  const textarea=body.querySelector("textarea");textarea.value=file?.content||"";let editRevision=0;textarea.addEventListener("input",()=>{editRevision++;setStatus("Editor · unsaved");});
  body.querySelector("#editorSave").onclick=async()=>{const revision=editRevision,text=textarea.value;try{await core.fs.writeText(path,text);if(editRevision===revision){setStatus("Saved");setTimeout(()=>{if(editRevision===revision)setStatus("Editor");},800);}else setStatus("Editor · unsaved");}catch(error){setStatus("Editor · save failed");alert(`Save failed: ${error?.message||error}`);}};
  body.querySelector("#editorFiles").onclick=()=>openFiles(core.path.parent(path));setTimeout(()=>textarea.focus(),40);
}

let tasksProcessListener=null;
function clearTasksProcessListener(){if(!tasksProcessListener)return;core.processes.removeEventListener("change",tasksProcessListener);tasksProcessListener=null;}
async function openTasks(){
  await core.ready;clearTasksProcessListener();
  const body=openWindow("tasks","Task Manager","RIFTKERNEL PROCESS TABLE",{onTerminate:clearTasksProcessListener});
  const render=()=>{const rows=core.processes.list();body.innerHTML=`<div class="trueos-head"><div><strong>RiftKernel processes</strong><small>Uptime ${core.kernel.uptime()}s</small></div><span class="trueos-chip ok">${rows.length} RUNNING</span></div><table class="trueos-table"><thead><tr><th>PID</th><th>Process</th><th>Kind</th><th></th></tr></thead><tbody>${rows.map(process=>`<tr><td>${process.pid}</td><td>${escapeHTML(process.name)}</td><td>${escapeHTML(process.kind||process.appId)}</td><td>${process.protected?"system":`<button class="trueos-btn" data-kill="${process.pid}" aria-label="End task ${escapeHTML(process.name)} PID ${process.pid}">End task</button>`}</td></tr>`).join("")}</tbody></table>`;body.querySelectorAll("[data-kill]").forEach(button=>button.onclick=()=>{const pid=Number(button.dataset.kill);const record=[...windows.values()].find(item=>Number(item.process?.pid)===pid);if(record)closeWindow(record.win);else core.kernel.kill(pid);});};
  tasksProcessListener=()=>{if(body.isConnected)render();else clearTasksProcessListener();};core.processes.addEventListener("change",tasksProcessListener);render();
}

async function openSettings(){
  await core.ready;
  const body=openWindow("settings","Settings","ANDROID SYSTEM"),info=await core.kernel.info(),device=await core.native.call("device.info",{});
  body.classList.add("rift-settings-window-body");
  body.innerHTML=`<div class="rift-settings-shell">
    <aside class="rift-settings-nav"><strong>Settings</strong><button class="active" data-settings-view="system">System</button><button data-settings-view="personalization">Personalization</button><button data-settings-view="storage">Storage</button><button data-settings-view="diagnostics">Diagnostics</button></aside>
    <section class="rift-settings-page">
      <div class="trueos-head"><div><strong>RiftOS ${escapeHTML(info.version)}</strong><small>${escapeHTML(info.mode)} · ${escapeHTML(device.manufacturer||"Android")} ${escapeHTML(device.model||"")}</small></div><span class="trueos-chip ok">ANDROID NATIVE</span></div>
      <div class="trueos-grid"><div class="trueos-card"><strong>RiftFS</strong><small>${escapeHTML(info.storage.backend)}<br>${fmtBytes(info.storage.usage)} / ${fmtBytes(info.storage.quota)}</small></div><div class="trueos-card"><strong>Android</strong><small>${escapeHTML(device.androidRelease||"")} · API ${escapeHTML(device.sdk||"")}<br>${escapeHTML(device.device||"")}</small></div><div class="trueos-card"><strong>Kernel</strong><small>${info.processes} process(es)<br>${info.apps} app(s)<br>${info.mounts} mount(s)</small></div></div>
      <div class="rift-settings-group"><div><strong>System diagnostics</strong><small>Create a privacy-limited RiftOS system dump and choose exactly where it is saved.</small></div><button class="trueos-btn primary" id="settingsDump">Save system dump…</button></div>
      <div class="rift-settings-group"><div><strong>Android files</strong><small>Mount a folder through Android's Storage Access Framework.</small></div><button class="trueos-btn" id="settingsMount">Mount folder…</button></div>
      <div class="rift-settings-group"><div><strong>Desktop personalization</strong><small>Change wallpaper and reset desktop layout.</small></div><div class="rift-settings-actions"><input class="trueos-btn" id="desktopWallpaperInput" type="text" placeholder="Wallpaper URL or value"><input class="trueos-btn" id="desktopWallpaperFile" type="file" accept="image/*"><button class="trueos-btn" id="desktopWallpaperApply">Apply wallpaper</button><button class="trueos-btn" id="desktopWallpaperClear">Clear wallpaper</button><button class="trueos-btn" id="desktopLayoutReset">Reset layout</button></div></div>
      <div class="rift-settings-actions"><button class="trueos-btn" id="settingsNotify">Notification permission</button><button class="trueos-btn" id="settingsMounts">Mount table</button><button class="trueos-btn" id="settingsPermissions">Capabilities</button></div>
      <pre class="trueos-code" id="settingsOutput">Samsung / Android native host is active.</pre>
    </section>
  </div>`;
  const out=body.querySelector("#settingsOutput");
  body.querySelectorAll("[data-settings-view]").forEach(button=>button.onclick=async()=>{
    body.querySelectorAll("[data-settings-view]").forEach(item=>item.classList.toggle("active",item===button));
    if(button.dataset.settingsView==="personalization"){out.textContent="Desktop personalization controls are available below.";return;}
    if(button.dataset.settingsView==="diagnostics"){body.querySelector("#settingsDump")?.closest(".rift-settings-group")?.scrollIntoView({behavior:"smooth",block:"center"});return;}
    if(button.dataset.settingsView==="storage"){try{out.textContent=JSON.stringify(await core.fs.estimate(),null,2);}catch(error){out.textContent=error.message;}return;}
    body.querySelector(".rift-settings-page")?.scrollTo({top:0,behavior:"smooth"});
  });
  body.querySelector("#settingsDump").onclick=async()=>{try{out.textContent="Opening Android Save As…";const result=await core.native.call("system.dump.save",{});out.textContent=result?.cancelled?"System dump save cancelled.":`System dump saved as ${result?.name||"selected file"} (${fmtBytes(result?.bytes||0)}).`;}catch(error){out.textContent=error.message;}};
  body.querySelector("#desktopWallpaperApply").onclick=()=>{window.RiftDesktop?.setWallpaper?.(body.querySelector("#desktopWallpaperInput")?.value||"");out.textContent="Wallpaper updated.";};
  body.querySelector("#desktopWallpaperClear").onclick=()=>{window.RiftDesktop?.setWallpaper?.("");out.textContent="Wallpaper cleared.";};
  body.querySelector("#desktopWallpaperFile").onchange=event=>{const file=event.target.files?.[0];if(!file)return;const reader=new FileReader();reader.onload=()=>{window.RiftDesktop?.setWallpaper?.(String(reader.result||""));out.textContent="Wallpaper loaded.";};reader.readAsDataURL(file);};
  body.querySelector("#desktopLayoutReset").onclick=()=>{window.RiftDesktop?.resetLayout?.();out.textContent="Desktop layout reset.";};
  body.querySelector("#settingsMount").onclick=async()=>{try{const mount=await core.fs.mountNativeDirectory();out.textContent=`Mounted ${mount.path}`;}catch(error){out.textContent=error.message;}};
  body.querySelector("#settingsNotify").onclick=async()=>{try{out.textContent=JSON.stringify(await core.native.call("notifications.request",{}),null,2);}catch(error){out.textContent=error.message;}};
  body.querySelector("#settingsMounts").onclick=()=>{out.textContent=core.kernel.mounts().map(m=>`${m.path}\t${m.type}\t${m.mode}\t${m.label}`).join("\n");};
  body.querySelector("#settingsPermissions").onclick=()=>{out.textContent=core.permissions.describe().join("\n");};
}

async function openBrowser(startUrl="https://chatgpt.com"){
  await core.ready;
  const body=openWindow("browser","RiftBrowser","RIFT BROWSER WINDOW");
  body.classList.add("rift-browser-window-body");
  const win=body.closest(".window");
  const url=String(startUrl||"https://chatgpt.com").trim()||"https://chatgpt.com";
  body.innerHTML=`<div class="rift-browser-window">
    <div class="rift-browser-tabsbar">
      <div class="rift-browser-tab-list" id="browserTabList" role="tablist" aria-label="Browser tabs"></div>
      <button id="browserNewTab" class="rift-browser-new-tab" title="New tab" aria-label="New tab">＋</button>
    </div>
    <div class="rift-browser-windowbar">
      <button id="browserBack" title="Back" aria-label="Browser Back" disabled>←</button><button id="browserForward" title="Forward" aria-label="Browser Forward" disabled>→</button><button id="browserReload" title="Reload" aria-label="Browser Reload">↻</button>
      <input id="browserUrl" aria-label="Browser address" value="${escapeHTML(url)}" autocomplete="off" autocapitalize="none" spellcheck="false" inputmode="url">
      <button class="primary" id="browserGo">Go</button>
    </div>
    <div class="rift-browser-meta"><span id="browserState">RiftBrowser Engine</span><span id="browserMode">WebView compatibility backend · ChatGPT MCP isolated</span></div>
    <div class="rift-browser-native-surface" id="riftBrowserNativeSurface"><div><span>◎</span><strong>RiftBrowser</strong><small>Only the selected tab owns the visible renderer surface.</small></div></div>
  </div>`;
  const surface=body.querySelector("#riftBrowserNativeSurface"),input=body.querySelector("#browserUrl"),stateEl=body.querySelector("#browserState"),modeEl=body.querySelector("#browserMode"),tabList=body.querySelector("#browserTabList"),back=body.querySelector("#browserBack"),forward=body.querySelector("#browserForward");
  let closed=false,lastBounds="",syncTimer=0,activeTabId="";
  const native=async(method,args={})=>core.native.call(`browser.window.${method}`,args);
  const renderTabs=state=>{
    const tabs=Array.isArray(state?.tabs)?state.tabs:[];
    activeTabId=String(state?.activeTabId||tabs.find(tab=>tab?.active)?.id||"");
    tabList.innerHTML=tabs.map(tab=>{
      const id=escapeHTML(tab?.id||""),rawLabel=String(tab?.title||tab?.url||"New tab"),label=escapeHTML(rawLabel==="RiftBrowser"&&tab?.url?tab.url:rawLabel);
      return `<div class="rift-browser-tab${tab?.active?" active":""}" data-browser-tab-wrap="${id}" role="presentation"><button class="rift-browser-tab-main" data-browser-tab="${id}" role="tab" aria-selected="${tab?.active?"true":"false"}" title="${label}"><span>${label}</span></button><button class="rift-browser-tab-close" data-browser-tab-close="${id}" title="Close tab" aria-label="Close ${label}">×</button></div>`;
    }).join("");
    tabList.querySelectorAll("[data-browser-tab]").forEach(button=>button.onclick=()=>native("tab.select",{tabId:button.dataset.browserTab}).then(updateState).catch(error=>{stateEl.textContent=error.message;}));
    tabList.querySelectorAll("[data-browser-tab-close]").forEach(button=>button.onclick=event=>{event.stopPropagation();native("tab.close",{tabId:button.dataset.browserTabClose}).then(updateState).catch(error=>{stateEl.textContent=error.message;});});
    tabList.querySelector(".rift-browser-tab.active")?.scrollIntoView({block:"nearest",inline:"nearest"});
  };
  const updateState=state=>{
    if(closed||!document.contains(body))return;
    renderTabs(state||{});
    if(state?.url&&document.activeElement!==input)input.value=state.url;
    back.disabled=!state?.canGoBack;forward.disabled=!state?.canGoForward;
    stateEl.textContent=state?.crashed?"Renderer restarted":Number(state?.progress||0)<100?`Loading ${state?.progress||0}%`:(state?.title||"RiftBrowser");
    const count=Number(state?.tabCount||state?.tabs?.length||1),max=Number(state?.maxTabs||8);modeEl.textContent=`${count}/${max} tabs · WebView compatibility backend · ChatGPT MCP isolated`;
  };
  browserNativeListeners.add(updateState);
  const visible=()=>document.contains(surface)&&!win.classList.contains("rift-minimized")&&win.classList.contains("rift-focused")&&!document.querySelector("#riftStartMenu.open");
  const syncBounds=force=>{
    clearTimeout(syncTimer);
    if(closed||!document.contains(surface))return;
    const immediateRect=surface.getBoundingClientRect(),immediateShow=visible()&&immediateRect.width>4&&immediateRect.height>4;
    if(!immediateShow){lastBounds="";native("visible",{visible:false}).catch(()=>{});return;}
    syncTimer=setTimeout(async()=>{
      if(closed||!document.contains(surface))return;
      const rect=surface.getBoundingClientRect(),show=visible()&&rect.width>4&&rect.height>4;
      if(!show){lastBounds="";try{await native("visible",{visible:false});}catch(_){}return;}
      const key=[Math.round(rect.left),Math.round(rect.top),Math.round(rect.width),Math.round(rect.height),show].join(":");
      if(force||key!==lastBounds){lastBounds=key;try{await native("bounds",{left:rect.left,top:rect.top,width:rect.width,height:rect.height,dpr:window.devicePixelRatio||1});await native("visible",{visible:true});}catch(_){}}
    },24);
  };
  const navigate=async()=>{try{updateState(await native("navigate",{url:input.value.trim()||"https://chatgpt.com"}));}catch(error){stateEl.textContent=error.message;}};
  const newTab=async()=>{try{updateState(await native("tab.new",{url:"https://www.google.com"}));}catch(error){stateEl.textContent=error.message;}};
  const closeActiveTab=async()=>{if(!activeTabId)return;try{updateState(await native("tab.close",{tabId:activeTabId}));}catch(error){stateEl.textContent=error.message;}};
  body.querySelector("#browserGo").onclick=navigate;body.querySelector("#browserNewTab").onclick=newTab;input.addEventListener("keydown",event=>{if(event.key==="Enter")navigate();});
  back.onclick=()=>native("back").then(updateState).catch(()=>{});forward.onclick=()=>native("forward").then(updateState).catch(()=>{});body.querySelector("#browserReload").onclick=()=>native("reload").then(updateState).catch(()=>{});
  const keyHandler=event=>{if(closed||!win.classList.contains("rift-focused")||!(event.ctrlKey||event.metaKey))return;const key=String(event.key||"").toLowerCase();if(key==="t"){event.preventDefault();newTab();}else if(key==="w"){event.preventDefault();closeActiveTab();}else if(key==="l"){event.preventDefault();input.focus();input.select();}};
  window.addEventListener("keydown",keyHandler);
  const resizeObserver=new ResizeObserver(()=>syncBounds(false));resizeObserver.observe(surface);resizeObserver.observe(win);
  const windowObserver=new MutationObserver(()=>syncBounds(false));windowObserver.observe(win,{attributes:true,attributeFilter:["class","style"]});
  const startMenu=document.querySelector("#riftStartMenu"),startObserver=startMenu?new MutationObserver(()=>syncBounds(true)):null;if(startMenu)startObserver.observe(startMenu,{attributes:true,attributeFilter:["class"]});
  const activation=()=>syncBounds(true);
  const visibilityHandler=event=>{if(event.detail?.id!=="browser")return;if(event.detail.visible===false){clearTimeout(syncTimer);lastBounds="";native("visible",{visible:false}).catch(()=>{});}else syncBounds(true);};
  const showDesktopHandler=()=>{clearTimeout(syncTimer);lastBounds="";native("visible",{visible:false}).catch(()=>{});};
  window.addEventListener("riftos:window-activate",activation);window.addEventListener("riftos:window-visibility",visibilityHandler);window.addEventListener("riftos:show-desktop",showDesktopHandler);window.addEventListener("resize",activation);
  const closeHandler=event=>{if(event.detail?.id!=="browser")return;closed=true;clearTimeout(syncTimer);native("visible",{visible:false}).catch(()=>{});browserNativeListeners.delete(updateState);resizeObserver.disconnect();windowObserver.disconnect();startObserver?.disconnect();window.removeEventListener("keydown",keyHandler);window.removeEventListener("riftos:window-activate",activation);window.removeEventListener("riftos:window-visibility",visibilityHandler);window.removeEventListener("riftos:show-desktop",showDesktopHandler);window.removeEventListener("resize",activation);window.removeEventListener("riftos:window-close",closeHandler);native("close").catch(()=>{});};
  window.addEventListener("riftos:window-close",closeHandler);
  requestAnimationFrame(()=>{syncBounds(true);native("open",{url}).then(updateState).catch(error=>{stateEl.textContent=error.message;});});
  return true;
}
function tokenize(raw){const out=[];String(raw||"").replace(/"([^"]*)"|'([^']*)'|([^\s]+)/g,(_,a,b,c)=>{out.push(a??b??c);return "";});return out;}
const shellRootAliases=new Set(["home","workspace","downloads","documents","mounts","apps","system"]);
function resolvePath(cwd,value){
  if(!value)return cwd;
  let raw=String(value).trim().replace(/\\/g,"/");
  if(raw==="~"||raw.startsWith("~/"))raw=`/home${raw.slice(1)}`;
  if(!raw.startsWith("/")&&shellRootAliases.has(raw.split("/")[0].toLowerCase()))raw=`/${raw}`;
  if(!raw.startsWith("/"))raw=`${cwd}/${raw}`;
  const parts=[];
  for(const part of raw.split("/")){if(!part||part===".")continue;if(part===".."){parts.pop();continue;}parts.push(part);}
  return "/"+parts.join("/");
}
function vortexDisplay(value){
  if(!value||typeof value!=="object")return value;
  const copy=JSON.parse(JSON.stringify(value));
  if(copy._riftImage){
    copy._riftImage={attached:true,mimeType:copy._riftImage.mimeType||"image/jpeg",name:copy._riftImage.name||"vortex-preview.jpg",bytes:Number(copy._riftImage.bytes||0)};
  }
  return copy;
}
async function runVortexShell(args,print,state){
  const sub=(args.shift()||"help").toLowerCase();
  const call=async payload=>{
    const result=await core.native.call("vortex.bridge",payload);
    print(JSON.stringify(vortexDisplay(result),null,2));
    return result;
  };
  const session=async payload=>{
    const result=await core.native.call("vortex.session",payload);
    print(JSON.stringify(vortexDisplay(result),null,2));
    return result;
  };
  if(sub==="help")return print(`Vortex3D live bridge\nvortex status\nvortex catalog\nvortex api\nvortex snapshot\nvortex ui-tree [limit]\nvortex screenshot [name]\nvortex click <tag-or-content-description>\nvortex touch <down|move|up|cancel|0..3> <x> <y>\nvortex test [all|system|case-id]\nvortex test-wait <system|case-id>\nvortex script <RiftFS-path> [--unsafe] [--live]\nvortex script-wait <RiftFS-path> [--unsafe] [--live]\nvortex job <id> [--image]\nvortex pull <artifact-id> [filename]\nvortex cleanup`);
  if(sub==="status"||sub==="catalog"||sub==="api"||sub==="snapshot"||sub==="cleanup")return call({op:sub});
  if(sub==="ui-tree"||sub==="ui_tree")return call({op:"ui_tree",limit:Math.max(1,Math.min(1024,Number(args[0])||256))});
  if(sub==="screenshot")return call({op:"screenshot",name:args[0]||"current",includeImage:true});
  if(sub==="click"){if(!args[0])throw new Error("usage: vortex click <tag-or-content-description>");return call({op:"click",target:args.join(" ")});}
  if(sub==="touch"){
    if(args.length<3)throw new Error("usage: vortex touch <down|move|up|cancel|0..3> <x> <y>");
    const actions={down:0,up:1,move:2,cancel:3},rawAction=String(args[0]).toLowerCase(),action=Object.prototype.hasOwnProperty.call(actions,rawAction)?actions[rawAction]:Number(rawAction);
    if(!Number.isInteger(action)||action<0||action>3)throw new Error("touch action must be down/move/up/cancel or 0..3");
    const x=Number(args[1]),y=Number(args[2]);if(!Number.isFinite(x)||!Number.isFinite(y))throw new Error("touch coordinates must be finite numbers");
    return call({op:"touch",action,x,y});
  }
  if(sub==="test"||sub==="validate")return call({op:"validate",target:args[0]||"all"});
  if(sub==="test-wait"||sub==="validate-wait"){
    if(!args[0])throw new Error("usage: vortex test-wait <system|case-id>");
    return session({kind:"validation",target:args[0],includeImage:true});
  }
  if(sub==="script"){
    const unsafe=args.includes("--unsafe"),live=args.includes("--live"),pathArg=args.find(arg=>arg!=="--unsafe"&&arg!=="--live");
    if(!pathArg)throw new Error("usage: vortex script <RiftFS-path> [--unsafe] [--live]");
    const path=resolvePath(state.cwd,pathArg),source=await core.fs.readText(path);if(source==null)throw new Error(`script not found: ${path}`);
    return call({op:"script",source,unsafe,live,name:(path.split("/").pop()||"riftos").replace(/\.[^.]+$/,'')});
  }
  if(sub==="script-wait"){
    const unsafe=args.includes("--unsafe"),live=args.includes("--live"),pathArg=args.find(arg=>arg!=="--unsafe"&&arg!=="--live");
    if(!pathArg)throw new Error("usage: vortex script-wait <RiftFS-path> [--unsafe] [--live]");
    const path=resolvePath(state.cwd,pathArg),source=await core.fs.readText(path);if(source==null)throw new Error(`script not found: ${path}`);
    return session({kind:"script",source,unsafe,live,name:(path.split("/").pop()||"riftos").replace(/\.[^.]+$/,''),includeImage:true});
  }
  if(sub==="job"){if(!args[0])throw new Error("usage: vortex job <id> [--image]");return call({op:"job",id:args[0],includeImage:args.includes("--image")});}
  if(sub==="pull"){if(!args[0])throw new Error("usage: vortex pull <artifact-id> [filename]");return call({op:"pull_artifact",id:args[0],name:args[1]||""});}
  throw new Error(`unknown vortex command: ${sub}`);
}
async function runLocalAgentShell(commandName,nativeMethod,title,args,print){
  const sub=(args.shift()||"help").toLowerCase();
  const call=async payload=>{const result=await core.native.call(nativeMethod,payload);print(JSON.stringify(result,null,2));return result;};
  if(sub==="help")return print(`${title}\n${commandName} status\n${commandName} open\n${commandName} tree [limit]\n${commandName} click <text|content-description|view-id>\n${commandName} tap <x> <y>\n${commandName} swipe <x1> <y1> <x2> <y2> [ms]\n${commandName} type <target> <text>\n${commandName} back`);
  if(sub==="status"||sub==="open"||sub==="back")return call({op:sub});
  if(sub==="tree")return call({op:"tree",limit:Math.max(1,Math.min(1024,Number(args[0])||256))});
  if(sub==="click"){if(!args.length)throw new Error(`usage: ${commandName} click <text|content-description|view-id>`);return call({op:"click",target:args.join(" ")});}
  if(sub==="tap"){if(args.length<2)throw new Error(`usage: ${commandName} tap <x> <y>`);const x=Number(args[0]),y=Number(args[1]);if(!Number.isFinite(x)||!Number.isFinite(y))throw new Error("tap coordinates must be finite numbers");return call({op:"tap",x,y});}
  if(sub==="swipe"){if(args.length<4)throw new Error(`usage: ${commandName} swipe <x1> <y1> <x2> <y2> [ms]`);const values=args.slice(0,4).map(Number);if(values.some(value=>!Number.isFinite(value)))throw new Error("swipe coordinates must be finite numbers");const durationMs=args[4]===undefined?350:Number(args[4]);if(!Number.isFinite(durationMs))throw new Error("swipe duration must be numeric");return call({op:"swipe",x1:values[0],y1:values[1],x2:values[2],y2:values[3],durationMs});}
  if(sub==="type"){if(args.length<2)throw new Error(`usage: ${commandName} type <target> <text>`);const target=args.shift();return call({op:"type",target,text:args.join(" ")});}
  throw new Error(`unknown ${commandName} command: ${sub}`);
}
async function runVortexAgentShell(args,print){return runLocalAgentShell("vortex-agent","vortex.agent","RiftOS Vortex local agent",args,print);}
function parseDevLabAgentRequest(args,state){
  const action=(args.shift()||"help").toLowerCase(),request={action,cwd:state.cwd};
  if(action==="help")return request;
  if(["status","open","staged","reset"].includes(action))return request;
  if(["load","delete","unstage","css","css-off"].includes(action)){if(!args[0])throw new Error(`usage: riftos-agent devlab ${action} <project-path>`);request.path=args[0];return request;}
  if(action==="stage"){if(args.length<2)throw new Error("usage: riftos-agent devlab stage <project-path> <text>");request.path=args.shift();request.text=args.join(" ");return request;}
  if(action==="stage-file"){if(args.length<2)throw new Error("usage: riftos-agent devlab stage-file <project-path> <riftfs-source-file>");request.path=args.shift();request.sourcePath=args.shift();return request;}
  if(action==="run"){if(!args.length)throw new Error("usage: riftos-agent devlab run <script>");request.source=args.join(" ");return request;}
  if(action==="run-file"){if(!args[0])throw new Error("usage: riftos-agent devlab run-file <riftfs-script-file>");request.sourcePath=args[0];return request;}
  if(action==="snapshot"){request.note=args.join(" ");return request;}
  if(["preview","publish","load-snapshot"].includes(action)){request.snapshotId=args[0]||"latest";return request;}
  if(["snapshots","runs"].includes(action)){if(args[0]!==undefined){const limit=Number(args[0]);if(!Number.isFinite(limit))throw new Error("Dev Lab list limit must be numeric");request.limit=limit;}return request;}
  throw new Error(`unknown riftos-agent devlab action: ${action}`);
}
async function runRiftOsAgentShell(args,print,state){
  if((args[0]||"").toLowerCase()==="devlab"){
    args.shift();const request=parseDevLabAgentRequest(args,state);
    if(request.action==="help")return print(`RiftOS local-agent Dev Lab controller\nriftos-agent devlab status\nriftos-agent devlab open\nriftos-agent devlab load <project-path>\nriftos-agent devlab staged\nriftos-agent devlab stage <project-path> <text>\nriftos-agent devlab stage-file <project-path> <riftfs-source-file>\nriftos-agent devlab delete <project-path>\nriftos-agent devlab unstage <project-path>\nriftos-agent devlab css <project-path>\nriftos-agent devlab css-off <project-path>\nriftos-agent devlab run <script>\nriftos-agent devlab run-file <riftfs-script-file>\nriftos-agent devlab runs [limit]\nriftos-agent devlab snapshot [note]\nriftos-agent devlab snapshots [limit]\nriftos-agent devlab load-snapshot [snapshot-id|latest]\nriftos-agent devlab preview [snapshot-id|latest]\nriftos-agent devlab publish [snapshot-id|latest]\nriftos-agent devlab reset`);
    const result=await core.native.call("riftos.agent",{op:"devlab",request});print(JSON.stringify(result,null,2));return result;
  }
  return runLocalAgentShell("riftos-agent","riftos.agent","RiftOS self UI agent",args,print);
}
async function runDevLabShell(args,print,state){
  const lab=window.RiftDevLab;if(!lab)throw new Error("RiftOS Dev Lab is not loaded");
  const sub=(args.shift()||"help").toLowerCase();
  const show=value=>{print(typeof value==="string"?value:JSON.stringify(value,null,2));return value;};
  const snapshotId=async value=>{
    const requested=String(value||"latest").trim();if(requested&&requested!=="latest")return requested;
    const status=await lab.status();if(status.latestSnapshot)return status.latestSnapshot;
    const latest=(await lab.listSnapshots(1))[0]?.id;if(!latest)throw new Error("No Dev Lab snapshot is available");return latest;
  };
  if(sub==="rpc"){
    if(!args[0]||args.length!==1)throw new Error("usage: devlab rpc <base64-json>");
    let request;try{const bytes=Uint8Array.from(atob(args[0]),char=>char.charCodeAt(0));request=JSON.parse(new TextDecoder().decode(bytes));}catch{throw new Error("invalid Dev Lab RPC payload");}
    return show(await lab.executeAgentRequest(request));
  }
  if(sub==="help")return print(`RiftOS Dev Lab\ndevlab status\ndevlab open\ndevlab staged\ndevlab stage <project-path> <text>\ndevlab stage-file <project-path> <riftfs-source-file>\ndevlab delete <project-path>\ndevlab unstage <project-path>\ndevlab css <project-path>\ndevlab css-off <project-path>\ndevlab run <script>\ndevlab run-file <riftfs-script-file>\ndevlab snapshot [note]\ndevlab snapshots\ndevlab preview [snapshot-id|latest]\ndevlab publish [snapshot-id|latest]\ndevlab reset`);
  if(sub==="status")return show(await lab.status());
  if(sub==="open")return show({opened:await lab.open()});
  if(sub==="staged")return show(await lab.listStaged());
  if(sub==="stage"){
    if(args.length<2)throw new Error("usage: devlab stage <project-path> <text>");
    const path=args.shift();return show(await lab.stageEdit(path,args.join(" ")));
  }
  if(sub==="stage-file"){
    if(args.length<2)throw new Error("usage: devlab stage-file <project-path> <riftfs-source-file>");
    const path=args.shift(),sourcePath=resolvePath(state.cwd,args.shift()),source=await core.fs.readText(sourcePath);if(source==null)throw new Error(`source file not found: ${sourcePath}`);return show(await lab.stageEdit(path,source,{reason:`staged from ${sourcePath}`}));
  }
  if(sub==="delete"){if(!args[0])throw new Error("usage: devlab delete <project-path>");return show(await lab.stageDelete(args[0]));}
  if(sub==="unstage"){if(!args[0])throw new Error("usage: devlab unstage <project-path>");return show({unstaged:await lab.unstage(args[0]),path:args[0]});}
  if(sub==="css"){if(!args[0])throw new Error("usage: devlab css <project-path>");return show(await lab.applyLiveCss(args[0]));}
  if(sub==="css-off"){if(!args[0])throw new Error("usage: devlab css-off <project-path>");return show({removed:lab.removeLive(args[0]),path:args[0]});}
  if(sub==="run"){if(!args.length)throw new Error("usage: devlab run <script>");return show(await lab.runScript(args.join(" ")));}
  if(sub==="run-file"){
    if(!args[0])throw new Error("usage: devlab run-file <riftfs-script-file>");const path=resolvePath(state.cwd,args[0]),source=await core.fs.readText(path);if(source==null)throw new Error(`script file not found: ${path}`);return show(await lab.runScript(source));
  }
  if(sub==="snapshot")return show(await lab.createSnapshot(args.join(" ")));
  if(sub==="snapshots")return show(await lab.listSnapshots());
  if(sub==="preview")return show(await lab.previewSnapshot(await snapshotId(args[0])));
  if(sub==="publish")return show(await lab.publishSnapshot(await snapshotId(args[0])));
  if(sub==="reset")return show({reset:await lab.resetStage()});
  throw new Error(`unknown devlab command: ${sub}`);
}
async function runChatShell(args,print,state){
  const sub=(args.shift()||"help").toLowerCase();
  const call=async payload=>{const result=await core.native.call("chat.handoff",payload);print(JSON.stringify(result,null,2));return result;};
  if(sub==="help")return print(`RiftOS chat handoff bundles\nchat handoff <payload.json> [name]\nchat export <payload.json> [name]\nchat list\nchat inspect <bundle.riftchat>\nchat resume <bundle.riftchat>\nchat transcript <bundle.riftchat> [offset-chars] [max-chars]`);
  if(sub==="list")return call({op:"list"});
  if(sub==="handoff"||sub==="export"){
    if(!args[0])throw new Error(`usage: chat ${sub} <payload.json> [name]`);
    const payloadPath=resolvePath(state.cwd,args.shift());
    return call({op:"create",payloadPath,name:args.join(" ")});
  }
  if(sub==="inspect"||sub==="resume"){
    if(!args[0])throw new Error(`usage: chat ${sub} <bundle.riftchat>`);
    const path=resolvePath(state.cwd,args[0]);
    return call({op:sub,path});
  }
  if(sub==="transcript"){
    if(!args[0])throw new Error("usage: chat transcript <bundle.riftchat> [offset-chars] [max-chars]");
    const path=resolvePath(state.cwd,args[0]);
    const offsetChars=Math.max(0,Number(args[1])||0),maxChars=Math.max(1,Math.min(65536,Number(args[2])||32768));
    return call({op:"transcript",path,offsetChars,maxChars});
  }
  throw new Error(`unknown chat command: ${sub}`);
}
async function runShell(raw,print,state,context={}){
  const batchMatch=String(raw||"").trim().match(/^batch(?:\s+(--dry-run))?\s+([\s\S]+)$/i);
  if(batchMatch){
    if(context.inBatch)throw new Error("Nested batches are not supported");
    if(!window.RiftShellBatch?.run)throw new Error("RiftShell batch runtime is not loaded");
    return window.RiftShellBatch.run(batchMatch[2],{state,print,resolve:resolvePath,dryRun:!!batchMatch[1],execute:command=>runShell(command,print,state,{inBatch:true})});
  }
  const args=tokenize(raw),cmd=(args.shift()||"").toLowerCase();if(!cmd)return;
  if(/^(git|gh|github)$/i.test(cmd)){if(!window.RiftGit?.run)throw new Error("RiftGit is not loaded");return window.RiftGit.run(args,print,{cwd:state.cwd});}
  if(cmd==="rift"){if(!window.RiftLocalPlatform?.run)throw new Error("Rift local platform is not loaded");return window.RiftLocalPlatform.run(args,print,{cwd:state.cwd});}
  if(cmd==="vortex")return runVortexShell(args,print,state);
  if(cmd==="vortex-agent")return runVortexAgentShell(args,print);
  if(cmd==="riftos-agent")return runRiftOsAgentShell(args,print,state);
  if(cmd==="chat")return runChatShell(args,print,state);
  if(cmd==="devlab")return runDevLabShell(args,print,state);
  if(cmd==="help")return print(`RiftShell / Android Native\nhelp  sysinfo  mount  umount  df  ps  kill <pid>  apps  permissions  native\npwd  cd <dir>  home  workspace [cd|info|ls|history|rollback|status|push]\nworkspace status | workspace push [message]  compare or publish RiftOS-main to GitHub main\nls [-R] [path]  tree [path]  stat <path>  cat <file>  head <file>  tail <file>\nwrite <file> <text>  touch <file>  mkdir <dir>  cp <from> <to>  mv <from> <to>  rm <path>\nzip <from> <archive.zip>  unzip <archive.zip> <folder>\nbatch <command> ; <command>       atomic local batch\nbatch --dry-run <commands>        validate without changes\nopen <app>  browser [url]  clear  uptime  version\nvortex help                       live Vortex3D debug bridge\nvortex-agent help                 Vortex-only local Android UI agent\nriftos-agent help                 RiftOS-self local Android UI + Dev Lab agent\nriftos-agent devlab help          structured Dev Lab controller\ndevlab help                       isolated live test/snapshot/local-workspace publish\nchat help                         local .riftchat development-session handoffs\nrift help                         local-first repo/vault/build/memory platform\ngit help\n\nRoot shortcuts: cd home | workspace | downloads | documents | mounts | apps | system`);
  if(cmd==="sysinfo")return print(JSON.stringify(await core.kernel.info(),null,2));
  if(cmd==="mount"){if((args[0]||"").toLowerCase()==="native"){const mount=await core.fs.mountNativeDirectory();return print(`mounted ${mount.path}`);}return print(core.kernel.mounts().map(m=>`${m.path}\t${m.type}\t${m.mode}\t${m.label}`).join("\n"));}
  if(cmd==="umount"){if(!args[0])return print("usage: umount <path>");return print(await core.fs.unmount(resolvePath(state.cwd,args[0]))?"unmounted":"mount not found");}
  if(cmd==="df"){const storage=await core.fs.estimate();return print(`${storage.backend}\nused ${fmtBytes(storage.usage)}\nquota ${fmtBytes(storage.quota)}\nfree ${fmtBytes(storage.free)}`);}
  if(cmd==="ps")return print(core.processes.list().map(p=>`${p.pid}\t${p.state}\t${p.kind||p.appId}\t${p.name}`).join("\n"));
  if(cmd==="kill")return print(core.kernel.kill(args[0])?`terminated ${args[0]}`:`cannot terminate ${args[0]||"(missing pid)"}`);
  if(cmd==="apps"){const built=[...core.kernel.apps.values()].map(app=>`${app.id}\t${app.name}`),installed=await window.RiftApps?.list?.()||[];return print([...built,...installed.map(app=>`${app.id}\t${app.manifest?.name||app.id}\tinstalled`)].join("\n"));}
  if(cmd==="permissions")return print(core.permissions.describe().join("\n"));
  if(cmd==="native")return print(JSON.stringify(core.native.capabilities(),null,2));
  if(cmd==="browser"){const url=args.join(" ").trim()||"https://chatgpt.com";await openBrowser(url);return print(`opened RiftBrowser window · ${url}`);}
  if(cmd==="workspace"){
    if(["status","push"].includes((args[0]||"").toLowerCase())){
      if(!window.RiftGit?.workspace)throw new Error("RiftGit is not loaded");
      return window.RiftGit.workspace(args,print);
    }
    const ws=window.RiftWorkspace;if(!ws?.available)return print("RiftWorkspace unavailable");
    const sub=(args.shift()||"info").toLowerCase();if(sub==="info")return print(JSON.stringify(await ws.info(),null,2));
    if(sub==="cd"){state.cwd="/workspace";return print(state.cwd);}
    if(sub==="ls")return print((await ws.list(args[0]||"",{recursive:false})).map(row=>`${row.kind==="directory"?"d":"-"}\t${row.path}`).join("\n")||"(empty)");
    if(sub==="history")return print(JSON.stringify(await ws.history(),null,2));if(sub==="rollback")return print(JSON.stringify(await ws.rollback(args[0]||null),null,2));
    return print("usage: workspace [cd|info|ls [path]|history|rollback [historyId]]");
  }
  if(cmd==="pwd")return print(state.cwd);
  if(cmd==="home"){state.cwd="/home";return print(state.cwd);}
  if(cmd==="cd"){const next=resolvePath(state.cwd,args[0]||"/home");const stat=await core.fs.stat(next);if(!stat||!["directory","mount"].includes(stat.kind))throw new Error(`not a directory: ${next}`);state.cwd=next;return print(state.cwd);}
  if(cmd==="ls"){const recursive=args.some(arg=>/^-[^-]*[Rr]/.test(arg)),target=args.find(arg=>!arg.startsWith("-")),path=resolvePath(state.cwd,target||state.cwd),rows=await core.fs.list(path,{recursive});return print(rows.map(row=>`${row.kind==="directory"||row.kind==="mount"?"d":"-"}\t${row.path}`).join("\n")||"(empty)");}
  if(cmd==="tree"){const path=resolvePath(state.cwd,args[0]||state.cwd),rows=await core.fs.list(path,{recursive:true});return print(rows.map(row=>`${row.kind==="directory"||row.kind==="mount"?"d":"-"}\t${row.path}`).join("\n")||"(empty)");}
  if(cmd==="stat"){const path=resolvePath(state.cwd,args[0]);const stat=await core.fs.stat(path);if(!stat)throw new Error(`path not found: ${path}`);return print(JSON.stringify(stat,null,2));}
  if(cmd==="cat"){const path=resolvePath(state.cwd,args[0]);const text=await core.fs.readText(path);if(text==null)throw new Error(`file not found: ${path}`);return print(text);}
  if(cmd==="head"||cmd==="tail"){const path=resolvePath(state.cwd,args[0]),text=await core.fs.readText(path);if(text==null)throw new Error(`file not found: ${path}`);const lines=text.split("\n"),count=Math.max(1,Number(args[1])||10);return print((cmd==="head"?lines.slice(0,count):lines.slice(-count)).join("\n"));}
  if(cmd==="write"){const path=resolvePath(state.cwd,args.shift());await core.fs.writeText(path,args.join(" "));return print(`wrote ${path}`);}
  if(cmd==="touch"){const path=resolvePath(state.cwd,args[0]);if(!await core.fs.stat(path))await core.fs.writeText(path,"");return print(`touched ${path}`);}
  if(cmd==="mkdir"){const path=resolvePath(state.cwd,args[0]);await core.fs.mkdir(path);return print(`created ${path}`);}
  if(cmd==="cp"||cmd==="mv"){if(args.length<2)throw new Error(`usage: ${cmd} <from> <to>`);const from=resolvePath(state.cwd,args[0]),to=resolvePath(state.cwd,args[1]);await core.fs[cmd==="cp"?"copy":"move"](from,to,{overwrite:args.includes("--force")||args.includes("-f")});return print(`${cmd==="cp"?"copied":"moved"} ${from} -> ${to}`);}
  if(cmd==="zip"){if(args.length<2)throw new Error("usage: zip <from> <archive.zip>");const from=resolvePath(state.cwd,args[0]),to=resolvePath(state.cwd,args[1]);await core.fs.zip(from,to);return print(`archived ${from} -> ${to}`);}
  if(cmd==="unzip"){if(args.length<2)throw new Error("usage: unzip <archive.zip> <folder>");const from=resolvePath(state.cwd,args[0]),to=resolvePath(state.cwd,args[1]);await core.fs.unzip(from,to);return print(`extracted ${from} -> ${to}`);}
  if(cmd==="rm"){const path=resolvePath(state.cwd,args[0]);await core.fs.remove(path);return print(`removed ${path}`);}
  if(cmd==="open"){const app=(args[0]||"home").toLowerCase();const opened=await openApp(app);if(opened===false)throw new Error(`app not found: ${app}`);return print(`opened ${app}`);}
  if(cmd==="clear")return {clear:true};
  if(cmd==="uptime")return print(`${core.kernel.uptime()}s`);
  if(cmd==="version")return print(core.version);
  throw new Error(`unknown command: ${cmd}`);
}

async function openTerminal(){
  await core.ready;const body=openWindow("terminal","RiftShell","ANDROID NATIVE SHELL"),state={cwd:"/"};
  body.innerHTML=`<div class="shell"><pre class="shell-output" id="shellOutput">RiftShell ${escapeHTML(core.version)}\nAndroid-native RiftFS ready. Type help.</pre><form id="shellForm" class="shell-form"><span id="shellPrompt">/ $</span><input id="shellInput" autocomplete="off" autocapitalize="none" spellcheck="false"></form></div>`;
  const out=body.querySelector("#shellOutput"),form=body.querySelector("#shellForm"),input=body.querySelector("#shellInput"),promptEl=body.querySelector("#shellPrompt");
  const print=value=>{out.textContent+=(out.textContent?"\n":"")+String(value??"");out.scrollTop=out.scrollHeight;};
  form.onsubmit=async event=>{event.preventDefault();const raw=input.value;input.value="";if(!raw.trim())return;print(`${state.cwd} $ ${raw}`);try{const result=await runShell(raw,print,state);if(result?.clear)out.textContent="";}catch(error){print(`error: ${error.message}`);}promptEl.textContent=`${state.cwd} $`;};
  setTimeout(()=>input.focus(),60);
}

async function openWorkspaceLive(){
  await core.ready;
  const body=openWindow("workspace-live","Workspace Records","PRIVATE LOCAL WORKSPACE RECORDS");
  if(!window.RiftWorkspaceLiveHost?.mount)throw new Error("Workspace Records host is unavailable");
  const mounted=window.RiftWorkspaceLiveHost.mount(body);
  const closeHandler=event=>{
    if(event.detail?.id!=="workspace-live")return;
    window.removeEventListener("riftos:window-close",closeHandler);
    mounted.destroy();
  };
  window.addEventListener("riftos:window-close",closeHandler);
  return true;
}

async function openApp(id){
  id=String(id||"home").trim();
  if(id==="home")return showDesktop();
  if(id==="files")return openFiles("/");
  if(id==="terminal")return openTerminal();
  if(id==="browser")return openBrowser();
  if(id==="workspace-live")return openWorkspaceLive();
  if(id==="devlab"){if(!globalThis.RiftDevLab?.open)return false;return globalThis.RiftDevLab.open();}
  if(id==="editor")return openEditor();
  if(id==="tasks")return openTasks();
  if(id==="settings")return openSettings();
  if(id==="mcp"){if(!globalThis.RiftMcp?.open)return false;return globalThis.RiftMcp.open();}
  if(id==="riftrt"){if(!globalThis.RiftRT?.openManager)return false;return globalThis.RiftRT.openManager();}
  if(id==="riftapps"){if(!globalThis.RiftApps?.openManager)return false;return globalThis.RiftApps.openManager();}
  let installed=null;try{installed=await globalThis.RiftApps?.get?.(id);}catch(_){}
  if(installed&&globalThis.RiftRT?.launch)return globalThis.RiftRT.launch(id);if(installed&&globalThis.RiftApps?.launch)return globalThis.RiftApps.launch(id);return false;
}

document.addEventListener("click",event=>{
  const button=event.target.closest("[data-open]");if(!button)return;
  event.preventDefault();const id=button.dataset.open;
  if(id!=="home"&&focusWindow(id))return;
  openApp(id).catch(error=>{console.error(error);setStatus(error.message);});
});

window.RiftOSWindowManager=Object.freeze({
  list:()=>[...windows.values()].map(record=>({id:record.id,title:record.title,pid:record.process?.pid,minimized:record.win.classList.contains("rift-minimized"),window:record.win,win:record.win,process:record.process,native:nativeDesktopEnabled})),
  get:id=>windows.get(id)||null,
  open:(id,title,kicker="RIFT APP",details={})=>openWindow(String(id),String(title),String(kicker),details||{}),
  focus:focusWindow,
  close:closeWindow,
  setTitle:setWindowTitle,
  showDesktop,
  sync:syncShellState,
  native:nativeDesktopEnabled
});
window.RiftShellMcp = Object.freeze({
  async execute(command, cwd = '/') {
    const state = { cwd: String(cwd || '/') };
    const output = [];
    const print = value => output.push(String(value ?? ''));
    try {
      const result = await runShell(String(command || ''), print, state);
      return { ok: true, output: output.join('\\n'), cwd: state.cwd, result: result ?? null };
    } catch (error) {
      return { ok: false, output: output.join('\\n'), cwd: state.cwd, error: error.message };
    }
  }
});
window.RiftShellMcpNative = Object.freeze({
  request(raw) {
    let payload = null;
    const send = result => {
      try { globalThis.RiftNativeTransport?.postMessage({method:'mcp.shell.result',args:{result}}); }
      catch (_) {}
    };
    try {
      payload = typeof raw === 'string' ? JSON.parse(raw) : raw;
      const id = String(payload?.id || '');
      const command = String(payload?.command || '').trim();
      const cwd = String(payload?.cwd || '/');
      if (!id) throw new Error('Missing shell bridge id');
      if (!command) throw new Error('Missing shell command');
      Promise.resolve(window.RiftShellMcp.execute(command, cwd)).then(result => {
        send({id,...(result && typeof result === 'object' ? result : {ok:true,result})});
      }).catch(error => send({id,ok:false,error:String(error?.message || error)}));
    } catch (error) {
      send({id:String(payload?.id || ''),ok:false,error:String(error?.message || error)});
    }
  }
});

window.RiftDesktop=Object.freeze({openApp,openFiles,openEditor,openTerminal,openSettings,openBrowser,openWorkspaceLive,closeWindow,showDesktop,setStatus,get mode(){return nativeDesktopEnabled?"native":"compatibility";},get taskbarPins(){return Array.isArray(nativeDesktopSettings.taskbarPins)?[...nativeDesktopSettings.taskbarPins]:[];},async pinTaskbar(id,pinned=true){if(!nativeDesktopEnabled)return false;const key=String(id||'').trim();if(!key||key==='home')return false;const pins=new Set(Array.isArray(nativeDesktopSettings.taskbarPins)?nativeDesktopSettings.taskbarPins:[]);if(pinned)pins.add(key);else pins.delete(key);await persistNativeDesktopSettings({taskbarPins:[...pins]});syncNativeLauncher();return true;},async setWallpaper(value){if(!nativeDesktopEnabled)return;const wallpaper=String(value||"");await persistNativeDesktopSettings({wallpaper});return nativeDesktopCall("wallpaper.set",{value:wallpaper}).then(applyNativeDesktopState);},async resetLayout(){if(!nativeDesktopEnabled)return;await persistNativeDesktopSettings({windows:{},icons:{}});return nativeDesktopCall("layout.reset",{}).then(applyNativeDesktopState);}});

(async()=>{
  try{
    await core.ready;appGrid();if(nativeDesktopEnabled)startNativeLauncherMirror();
    const boot=$("#boot"),os=$("#os");
    setTimeout(()=>{boot?.classList.add("hidden");os?.classList.remove("hidden");syncShellState();setStatus(nativeDesktopEnabled?"Native desktop ready":"Ready");},220);
  }catch(error){
    const boot=$("#boot");if(boot)boot.innerHTML=`<div class="boot-title">RiftOS boot failed</div><pre>${escapeHTML(error.stack||error.message)}</pre>`;
  }
})();
