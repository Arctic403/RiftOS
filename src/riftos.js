const core=window.RiftOSCore;
if(!core)throw new Error("RiftOSCore must load before RiftOS desktop");

const BUILTIN_APPS=[
  {id:"files",name:"Files",icon:"▣",desc:"Android RiftFS + SAF mounts"},
  {id:"terminal",name:"RiftShell",icon:">_",desc:"RiftKernel command shell"},
  {id:"browser",name:"RiftBrowser",icon:"◎",desc:"RiftOS-owned browser · WebView compatibility renderer"},
  {id:"workspace-live",name:"Workspace Live",icon:"◈",desc:"Watch ChatGPT + local edits live"},
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

function setStatus(value){const el=$("#statusText");if(el)el.textContent=value;}
function tick(){const clock=$("#clock");if(clock)clock.textContent=new Date().toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"});}
setInterval(tick,1000);tick();

function desktopMode(){return document.documentElement.classList.contains("rift-desktop-mode");}
function recordFor(target){
  if(!target)return null;
  if(typeof target==="string")return windows.get(target)||null;
  const win=target.closest?.(".window")||target;
  for(const record of windows.values())if(record.win===win)return record;
  return null;
}
function visibleRecords(){return [...windows.values()].filter(record=>!record.win.classList.contains("rift-minimized"));}
function syncShellState(){
  const visible=visibleRecords();
  stage.classList.toggle("hidden",windows.size===0);
  if(desktopMode())workspace.classList.remove("hidden");
  else workspace.classList.toggle("hidden",visible.length>0);
  document.querySelectorAll(".dock-btn").forEach(btn=>btn.classList.toggle("active",btn.dataset.open==="home"&&visible.length===0));
  if(!visible.length)setStatus("Ready");
}
function focusWindow(target){
  const record=recordFor(target);if(!record)return false;
  record.win.classList.remove("rift-minimized");
  stage.classList.remove("hidden");
  if(!desktopMode())workspace.classList.add("hidden");
  record.lastFocus=Date.now();
  setStatus(record.title);
  window.dispatchEvent(new CustomEvent("riftos:window-activate",{detail:{id:record.id,window:record.win,pid:record.process?.pid}}));
  return true;
}
function closeWindow(target){
  const record=recordFor(target);if(!record)return false;
  try{if(record.process?.pid)core.kernel.kill(record.process.pid);}catch(_){}
  windows.delete(record.id);
  record.win.remove();
  window.dispatchEvent(new CustomEvent("riftos:window-close",{detail:{id:record.id,pid:record.process?.pid}}));
  syncShellState();
  const next=[...windows.values()].filter(item=>!item.win.classList.contains("rift-minimized")).sort((a,b)=>(b.lastFocus||0)-(a.lastFocus||0))[0];
  if(next)focusWindow(next.id);
  return true;
}
function showDesktop(){
  for(const record of windows.values())record.win.classList.add("rift-minimized");
  workspace.classList.remove("hidden");
  stage.classList.remove("hidden");
  window.dispatchEvent(new Event("riftos:show-desktop"));
  syncShellState();
}
function openWindow(id,title,kicker="RIFT APP"){
  const existing=windows.get(id);
  if(existing){
    existing.title=title;
    existing.win.querySelector(".window-title").textContent=title;
    existing.win.querySelector(".window-kicker").textContent=kicker;
    const body=existing.win.querySelector(".window-body");body.innerHTML="";
    focusWindow(id);
    return body;
  }
  const template=$("#windowTemplate");if(!template)throw new Error("RiftOS window template is missing");
  const win=template.content.firstElementChild.cloneNode(true);
  win.dataset.app=id;win.dataset.windowId=`${id}-${++windowSerial}`;
  win.querySelector(".window-title").textContent=title;
  win.querySelector(".window-kicker").textContent=kicker;
  const process=core.kernel.launchProcess(id,title,{kind:"ui"});
  const record={id,title,win,process,lastFocus:Date.now()};
  windows.set(id,record);
  win.querySelector(".window-close").onclick=()=>closeWindow(win);
  stage.classList.remove("hidden");stage.append(win);
  syncShellState();focusWindow(id);
  window.dispatchEvent(new CustomEvent("riftos:window-open",{detail:{id,window:win,pid:process?.pid,title}}));
  return win.querySelector(".window-body");
}
function appGrid(){
  const grid=$("#appGrid");if(!grid)return;
  grid.innerHTML=BUILTIN_APPS.map(app=>`<button class="app-card" data-open="${app.id}"><span class="app-icon">${app.icon}</span><span><strong>${app.name}</strong><br><small>${app.desc}</small></span></button>`).join("");
  window.dispatchEvent(new Event("riftos:launcher-ready"));
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
  let longPressTimer=0;
  let pressStart=null;
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
        <button id="fsBack" title="Back" ${canBack?"":"disabled"}>←</button>
        <button id="fsForward" title="Forward" ${canForward?"":"disabled"}>→</button>
        <button id="fsUp" title="Up" ${parent===null?"disabled":""}>↑</button>
        <button id="fsRefresh" title="Refresh">↻</button>
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
  const destinationPicker={open:openDestinationPicker};
  async function openDestinationPicker(startPath,suggestedName,folderOnly=false){
    const start=core.path.parent(startPath);
    return new Promise(async resolve=>{
      const overlay=document.createElement("div");
      overlay.className="rift-destination-picker";
      let current=start;
      let selected=current;
      const render=async()=>{
        const rows=await core.fs.list(current,false);
        overlay.innerHTML=`<div class="rift-destination-dialog"><header>Select destination</header><div class="rift-destination-path"><button data-up>↑</button> ${escapeHTML(current)}</div><div class="rift-destination-list">${rows.filter(row=>row.kind==="directory"||row.kind==="mount").map(row=>`<button data-path="${escapeHTML(row.path)}">${escapeHTML(core.path.basename(row.path)||row.path)}</button>`).join("")||"<span>Empty folder</span>"}</div><input id="destinationName" value="${escapeHTML(suggestedName)}"><footer><button data-cancel>Cancel</button><button data-confirm>Confirm</button></footer></div>`;
        overlay.querySelectorAll("[data-path]").forEach(button=>button.onclick=()=>{current=button.dataset.path;render();});
        overlay.querySelector("[data-up]").onclick=()=>{const parent=core.path.parent(current);if(parent&&parent!==current){current=parent;render();}};
        overlay.querySelector("[data-cancel]").onclick=()=>{overlay.remove();resolve(null);};
        overlay.querySelector("[data-confirm]").onclick=async()=>{const name=overlay.querySelector("#destinationName").value.trim();if(!folderOnly&&!name)return;const target=folderOnly?current:core.path.join(current,name);if(await core.fs.stat(target)&&!folderOnly&&!confirm("Destination exists. Continue and overwrite/merge where supported?"))return;overlay.remove();resolve(target);};
      };
      document.body.appendChild(overlay);
      await render();
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
    menu.onclick=async e=>{const action=e.target.dataset.action;if(!action)return;menu.remove(); if(action==='copy')body.querySelector('#fsCopy').click(); if(action==='cut')body.querySelector('#fsCut').click(); if(action==='delete')body.querySelector('#fsDelete').click(); if(action==='rename')body.querySelector('#fsRename').click(); if(action==='zip')runFileAction("Zip",async()=>{const target=await chooseArchiveDestination(entry.path,"zip");if(!target)throw new Error("Cancelled");await core.fs.zip(entry.path,target);}); if(action==='unzip')runFileAction("Unzip",async()=>{const target=await chooseArchiveDestination(entry.path,"unzip");if(!target)throw new Error("Cancelled");await core.fs.unzip(entry.path,target);});};
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
      selectionBox?.remove();
      selectionBox=null;
    };
    window.addEventListener('pointermove',update);
    window.addEventListener('pointerup',finish,{once:true});
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
  core.fs.transferQueue?.addEventListener?.("transfer",event=>{
    const detail=event.detail||{};
    setStatus(`Files · transfer ${detail.state||""}${detail.active?` (${detail.active})`:""}`);
  });

  body.querySelector("#fsBack").onclick=()=>{if(filesNavigation.index>0){filesNavigation.index--;openFiles(filesNavigation.history[filesNavigation.index],{record:false});}};
  body.querySelector("#fsForward").onclick=()=>{if(filesNavigation.index<filesNavigation.history.length-1){filesNavigation.index++;openFiles(filesNavigation.history[filesNavigation.index],{record:false});}};
  body.querySelector("#fsUp").onclick=()=>parent!==null&&openFiles(parent);
  body.querySelector("#fsRefresh").onclick=()=>refresh();
  body.querySelectorAll("[data-crumb]").forEach(button=>button.onclick=()=>openFiles(button.dataset.crumb));
  body.querySelectorAll("[data-place]").forEach(button=>button.onclick=()=>openFiles(button.dataset.place));
  for(const button of itemButtons){
    button.onclick=event=>{
      const target=button.dataset.path;
      if(event.ctrlKey||event.metaKey||event.shiftKey){if(selected.has(target))selected.delete(target);else selected.add(target);}
      else{selected.clear();selected.add(target);}
      syncSelection();
    };
    button.oncontextmenu=event=>{event.preventDefault();selected.clear();selected.add(button.dataset.path);syncSelection();showFileMenu(byPath.get(button.dataset.path),event.clientX,event.clientY);};
    button.onpointerdown=event=>{pressStart={x:event.clientX,y:event.clientY};longPressTimer=setTimeout(()=>showFileMenu(byPath.get(button.dataset.path),event.clientX,event.clientY),550);};
    button.onpointerup=()=>clearTimeout(longPressTimer);
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
  body.querySelector("#fsPaste").onclick=()=>runFileAction(filesClipboard.mode==="cut"?"Move":"Paste",async()=>{
    const pending=[...filesClipboard.paths];if(!pending.length)return;
    const completed=[];
    for(const source of pending){
      if(!(await core.fs.stat(source)))continue;
      let destination=core.path.join(path,core.path.basename(source));
      if(filesClipboard.mode==="cut"&&core.path.parent(source)===path){completed.push(source);continue;}
      if(await core.fs.stat(destination))destination=await uniqueChildPath(path,duplicateBaseName(core.path.basename(source)));
      if(filesClipboard.mode==="cut")await core.fs.move(source,destination);else await core.fs.copy(source,destination);
      completed.push(source);
    }
    if(filesClipboard.mode==="cut"){filesClipboard.paths=filesClipboard.paths.filter(source=>!completed.includes(source));if(!filesClipboard.paths.length)filesClipboard.mode="copy";}
  });
  body.querySelector("#fsDuplicate").onclick=()=>runFileAction("Duplicate",async()=>{for(const entry of chosen()){const destination=await uniqueChildPath(path,duplicateBaseName(core.path.basename(entry.path)));await core.fs.copy(entry.path,destination);}});
  body.querySelector("#fsMove").onclick=()=>runFileAction("Move",async()=>{
    const destinationRaw=await openDestinationPicker(path,"",true);if(!destinationRaw)throw new Error("Cancelled");const destinationDir=core.path.normalize(destinationRaw);const stat=await core.fs.stat(destinationDir);if(!stat||!["directory","mount"].includes(stat.kind))throw new Error("Destination folder does not exist.");
    for(const entry of chosen()){let destination=core.path.join(destinationDir,core.path.basename(entry.path));if(await core.fs.stat(destination))destination=await uniqueChildPath(destinationDir,duplicateBaseName(core.path.basename(entry.path)));await core.fs.move(entry.path,destination);}
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
  body.innerHTML=`<div class="trueos-editor"><div class="trueos-head"><div><strong>${escapeHTML(path)}</strong><small>${escapeHTML(file?.backend||"android-internal")}</small></div><button class="trueos-btn" id="editorFiles">Files</button><button class="trueos-btn primary" id="editorSave">Save</button></div><textarea spellcheck="false" autocomplete="off"></textarea></div>`;
  const textarea=body.querySelector("textarea");textarea.value=file?.content||"";textarea.addEventListener("input",()=>setStatus("Editor · unsaved"));
  body.querySelector("#editorSave").onclick=async()=>{await core.fs.writeText(path,textarea.value);setStatus("Saved");setTimeout(()=>setStatus("Editor"),800);};
  body.querySelector("#editorFiles").onclick=()=>openFiles(core.path.parent(path));setTimeout(()=>textarea.focus(),40);
}

async function openTasks(){
  await core.ready;const body=openWindow("tasks","Task Manager","RIFTKERNEL PROCESS TABLE");
  const render=()=>{const rows=core.processes.list();body.innerHTML=`<div class="trueos-head"><div><strong>RiftKernel processes</strong><small>Uptime ${core.kernel.uptime()}s</small></div><span class="trueos-chip ok">${rows.length} RUNNING</span></div><table class="trueos-table"><thead><tr><th>PID</th><th>Process</th><th>Kind</th><th></th></tr></thead><tbody>${rows.map(process=>`<tr><td>${process.pid}</td><td>${escapeHTML(process.name)}</td><td>${escapeHTML(process.kind||process.appId)}</td><td>${process.protected?"system":`<button class="trueos-btn" data-kill="${process.pid}">End task</button>`}</td></tr>`).join("")}</tbody></table>`;body.querySelectorAll("[data-kill]").forEach(button=>button.onclick=()=>{const pid=Number(button.dataset.kill);const record=[...windows.values()].find(item=>Number(item.process?.pid)===pid);if(record)closeWindow(record.win);else core.kernel.kill(pid);render();});};render();
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
    <div class="rift-browser-windowbar">
      <button id="browserBack" title="Back" disabled>←</button><button id="browserForward" title="Forward" disabled>→</button><button id="browserReload" title="Reload">↻</button>
      <input id="browserUrl" value="${escapeHTML(url)}" autocomplete="off" autocapitalize="none" spellcheck="false" inputmode="url">
      <button class="primary" id="browserGo">Go</button>
    </div>
    <div class="rift-browser-meta"><span id="browserState">RiftBrowser Engine</span><span>WebView compatibility backend · ChatGPT MCP isolated</span></div>
    <div class="rift-browser-native-surface" id="riftBrowserNativeSurface"><div><span>◎</span><strong>RiftBrowser</strong><small>Renderer is owned and clipped by this RiftOS window.</small></div></div>
  </div>`;
  const surface=body.querySelector("#riftBrowserNativeSurface"),input=body.querySelector("#browserUrl"),stateEl=body.querySelector("#browserState"),back=body.querySelector("#browserBack"),forward=body.querySelector("#browserForward");
  let closed=false,lastBounds="",syncTimer=0;
  const updateState=state=>{if(closed||!document.contains(body))return;if(state.url&&document.activeElement!==input)input.value=state.url;back.disabled=!state.canGoBack;forward.disabled=!state.canGoForward;stateEl.textContent=state.crashed?"Renderer restarted":state.progress<100?`Loading ${state.progress||0}%`:(state.title||"RiftBrowser");};
  browserNativeListeners.add(updateState);
  const native=async(method,args={})=>core.native.call(`browser.window.${method}`,args);
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
  const navigate=async()=>{try{await native("navigate",{url:input.value.trim()||"https://chatgpt.com"});}catch(error){stateEl.textContent=error.message;}};
  body.querySelector("#browserGo").onclick=navigate;input.addEventListener("keydown",event=>{if(event.key==="Enter")navigate();});
  back.onclick=()=>native("back").catch(()=>{});forward.onclick=()=>native("forward").catch(()=>{});body.querySelector("#browserReload").onclick=()=>native("reload").catch(()=>{});
  const resizeObserver=new ResizeObserver(()=>syncBounds(false));resizeObserver.observe(surface);resizeObserver.observe(win);
  const windowObserver=new MutationObserver(()=>syncBounds(false));windowObserver.observe(win,{attributes:true,attributeFilter:["class","style"]});
  const startMenu=document.querySelector("#riftStartMenu"),startObserver=startMenu?new MutationObserver(()=>syncBounds(true)):null;if(startMenu)startObserver.observe(startMenu,{attributes:true,attributeFilter:["class"]});
  const activation=()=>syncBounds(true);
  const visibilityHandler=event=>{if(event.detail?.id!=="browser")return;if(event.detail.visible===false){clearTimeout(syncTimer);lastBounds="";native("visible",{visible:false}).catch(()=>{});}else syncBounds(true);};
  const showDesktopHandler=()=>{clearTimeout(syncTimer);lastBounds="";native("visible",{visible:false}).catch(()=>{});};
  window.addEventListener("riftos:window-activate",activation);window.addEventListener("riftos:window-visibility",visibilityHandler);window.addEventListener("riftos:show-desktop",showDesktopHandler);window.addEventListener("resize",activation);
  const closeHandler=event=>{if(event.detail?.id!=="browser")return;closed=true;clearTimeout(syncTimer);native("visible",{visible:false}).catch(()=>{});browserNativeListeners.delete(updateState);resizeObserver.disconnect();windowObserver.disconnect();startObserver?.disconnect();window.removeEventListener("riftos:window-activate",activation);window.removeEventListener("riftos:window-visibility",visibilityHandler);window.removeEventListener("riftos:show-desktop",showDesktopHandler);window.removeEventListener("resize",activation);window.removeEventListener("riftos:window-close",closeHandler);native("close").catch(()=>{});};
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
async function runShell(raw,print,state,context={}){
  const batchMatch=String(raw||"").trim().match(/^batch(?:\s+(--dry-run))?\s+([\s\S]+)$/i);
  if(batchMatch){
    if(context.inBatch)throw new Error("Nested batches are not supported");
    if(!window.RiftShellBatch?.run)throw new Error("RiftShell batch runtime is not loaded");
    return window.RiftShellBatch.run(batchMatch[2],{state,print,resolve:resolvePath,dryRun:!!batchMatch[1],execute:command=>runShell(command,print,state,{inBatch:true})});
  }
  const args=tokenize(raw),cmd=(args.shift()||"").toLowerCase();if(!cmd)return;
  if(/^(git|gh|github)$/i.test(cmd)){if(!window.RiftGit?.run)throw new Error("RiftGit is not loaded");return window.RiftGit.run(args,print,{cwd:state.cwd});}
  if(cmd==="help")return print(`RiftShell / Android Native\nhelp  sysinfo  mount  umount  df  ps  kill <pid>  apps  permissions  native\npwd  cd <dir>  home  workspace [cd|info|ls|history|rollback|status|push]\nworkspace status | workspace push [message]  compare or publish RiftOS-main to GitHub main\nls [-R] [path]  tree [path]  stat <path>  cat <file>  head <file>  tail <file>\nwrite <file> <text>  touch <file>  mkdir <dir>  cp <from> <to>  mv <from> <to>  rm <path>\nzip <from> <archive.zip>  unzip <archive.zip> <folder>\nbatch <command> ; <command>       atomic local batch\nbatch --dry-run <commands>        validate without changes\nopen <app>  browser [url]  clear  uptime  version\ngit help\n\nRoot shortcuts: cd home | workspace | downloads | documents | mounts | apps | system`);
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
  if(cmd==="open"){const app=args[0]||"home";document.querySelector(`[data-open="${CSS.escape(app)}"]`)?.click();return print(`opened ${app}`);}
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
  const body=openWindow("workspace-live","Workspace Live","LOCAL HTML / MCP WORKSPACE");
  if(!window.RiftWorkspaceLiveHost?.mount)throw new Error("Workspace Live host is unavailable");
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
  if(id==="home")return showDesktop();
  if(id==="files")return openFiles("/");
  if(id==="terminal")return openTerminal();
  if(id==="browser")return openBrowser();
  if(id==="workspace-live")return openWorkspaceLive();
  if(id==="editor")return openEditor();
  if(id==="tasks")return openTasks();
  if(id==="settings")return openSettings();
  if(window.RiftApps?.open)return window.RiftApps.open(id);
}

document.addEventListener("click",event=>{
  const button=event.target.closest("[data-open]");if(!button)return;
  event.preventDefault();const id=button.dataset.open;
  if(id!=="home"&&focusWindow(id))return;
  openApp(id).catch(error=>{console.error(error);setStatus(error.message);});
});

window.RiftOSWindowManager=Object.freeze({
  list:()=>[...windows.values()].map(record=>({id:record.id,title:record.title,pid:record.process?.pid,minimized:record.win.classList.contains("rift-minimized"),window:record.win})),
  get:id=>windows.get(id)||null,
  open:(id,title,kicker="RIFT APP")=>openWindow(String(id),String(title),String(kicker)),
  focus:focusWindow,
  close:closeWindow,
  showDesktop,
  sync:syncShellState
});
window.RiftDesktop=Object.freeze({openApp,openFiles,openEditor,openTerminal,openSettings,openBrowser,openWorkspaceLive,closeWindow,showDesktop,setStatus});

(async()=>{
  try{
    await core.ready;appGrid();
    const boot=$("#boot"),os=$("#os");
    setTimeout(()=>{boot?.classList.add("hidden");os?.classList.remove("hidden");syncShellState();setStatus("Ready");},220);
  }catch(error){
    const boot=$("#boot");if(boot)boot.innerHTML=`<div class="boot-title">RiftOS boot failed</div><pre>${escapeHTML(error.stack||error.message)}</pre>`;
  }
})();
