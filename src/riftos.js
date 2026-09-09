const core=window.RiftOSCore;
if(!core)throw new Error("RiftOSCore must load before RiftOS desktop");

const BUILTIN_APPS=[
  {id:"files",name:"Files",icon:"▣",desc:"Android RiftFS + SAF mounts"},
  {id:"terminal",name:"RiftShell",icon:">_",desc:"RiftKernel command shell"},
  {id:"browser",name:"RiftBrowser",icon:"◎",desc:"In-desktop Android WebView browser"},
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
    for(const listener of [...browserNativeListeners]){try{listener(next);}catch(_){}}
  }
});
const filesNavigation={history:["/"],index:0};

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
  const mountRoot=path.startsWith("/mounts/")&&path.split("/").filter(Boolean).length===2;
  try{rows=await core.fs.list(path,{recursive:mountRoot});}
  catch(error){body.innerHTML=`<div class="rift-explorer-error"><strong>Cannot open ${escapeHTML(path)}</strong><pre>${escapeHTML(error.message)}</pre></div>`;return;}
  const entries=topLevelEntries(rows,path),storage=await core.fs.estimate(),parent=path==="/"?null:core.path.parent(path);
  const roots=[
    ["/home","⌂","Home"],["/documents","▤","Documents"],["/downloads","⇩","Downloads"],
    ["/workspace","◇","Workspace"],["/apps","▦","Apps"],["/mounts","⛓","Android mounts"]
  ];
  const pathParts=path==="/"?[]:path.split("/").filter(Boolean);
  const crumbs=[`<button data-crumb="/">RiftFS</button>`];
  let walk="";
  for(const part of pathParts){walk+=`/${part}`;crumbs.push(`<span>›</span><button data-crumb="${escapeHTML(walk)}">${escapeHTML(part)}</button>`);}
  const canBack=filesNavigation.index>0,canForward=filesNavigation.index<filesNavigation.history.length-1;
  body.innerHTML=`<div class="rift-explorer">
    <div class="rift-explorer-commandbar">
      <div class="rift-explorer-navbuttons">
        <button id="fsBack" title="Back" ${canBack?"":"disabled"}>←</button>
        <button id="fsForward" title="Forward" ${canForward?"":"disabled"}>→</button>
        <button id="fsUp" title="Up" ${parent===null?"disabled":""}>↑</button>
        <button id="fsRefresh" title="Refresh">↻</button>
      </div>
      <div class="rift-explorer-address" id="fsAddress">${crumbs.join("")}</div>
      <button class="rift-explorer-new primary" id="fsNewFile">＋ File</button>
      <button class="rift-explorer-new" id="fsNewFolder">＋ Folder</button>
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
          return `<button class="rift-explorer-item" data-path="${escapeHTML(entry.path)}" data-kind="${escapeHTML(entry.kind)}"><span class="rift-explorer-name"><i>${icon}</i><b>${escapeHTML(name)}</b></span><span>${escapeHTML(type)}</span><span>${folder?"—":fmtBytes(entry.size)}</span></button>`;
        }).join(""):`<div class="rift-explorer-empty"><span>□</span><strong>This folder is empty</strong><small>Create a file or folder to get started.</small></div>`}</div>
      </section>
    </div>
    <footer class="rift-explorer-status"><span>${entries.length} item${entries.length===1?"":"s"}</span><span>${escapeHTML(storage.backend||"Android internal storage")} · ${fmtBytes(storage.usage)} used · ${fmtBytes(storage.free)} free</span></footer>
  </div>`;
  body.querySelector("#fsBack").onclick=()=>{if(filesNavigation.index>0){filesNavigation.index--;openFiles(filesNavigation.history[filesNavigation.index],{record:false});}};
  body.querySelector("#fsForward").onclick=()=>{if(filesNavigation.index<filesNavigation.history.length-1){filesNavigation.index++;openFiles(filesNavigation.history[filesNavigation.index],{record:false});}};
  body.querySelector("#fsUp").onclick=()=>parent!==null&&openFiles(parent);
  body.querySelector("#fsRefresh").onclick=()=>openFiles(path,{record:false});
  body.querySelectorAll("[data-crumb]").forEach(button=>button.onclick=()=>openFiles(button.dataset.crumb));
  body.querySelectorAll("[data-place]").forEach(button=>button.onclick=()=>openFiles(button.dataset.place));
  body.querySelectorAll("[data-path]").forEach(button=>{
    button.onclick=()=>["directory","mount"].includes(button.dataset.kind)?openFiles(button.dataset.path):openEditor(button.dataset.path);
  });
  body.querySelector("#fsNewFile").onclick=()=>{const name=prompt("File name","untitled.txt");if(name)openEditor(core.path.join(path,name));};
  body.querySelector("#fsNewFolder").onclick=async()=>{const name=prompt("Folder name","New Folder");if(!name)return;await core.fs.mkdir(core.path.join(path,name));openFiles(path,{record:false});};
  body.querySelector("#fsMountNative").onclick=async()=>{try{await core.fs.mountNativeDirectory();openFiles("/mounts");}catch(error){alert(error.message);}};
}

async function openEditor(path="/home/scratch.txt"){
  await core.ready;path=core.path.normalize(path);const file=await core.fs.get(path),body=openWindow("editor","Editor","ANDROID RIFTFS EDITOR");
  if(file?.kind==="directory"||file?.kind==="mount"){openFiles(path);return;}
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
    <aside class="rift-settings-nav"><strong>Settings</strong><button class="active" data-settings-view="system">System</button><button data-settings-view="storage">Storage</button><button data-settings-view="diagnostics">Diagnostics</button></aside>
    <section class="rift-settings-page">
      <div class="trueos-head"><div><strong>RiftOS ${escapeHTML(info.version)}</strong><small>${escapeHTML(info.mode)} · ${escapeHTML(device.manufacturer||"Android")} ${escapeHTML(device.model||"")}</small></div><span class="trueos-chip ok">ANDROID NATIVE</span></div>
      <div class="trueos-grid"><div class="trueos-card"><strong>RiftFS</strong><small>${escapeHTML(info.storage.backend)}<br>${fmtBytes(info.storage.usage)} / ${fmtBytes(info.storage.quota)}</small></div><div class="trueos-card"><strong>Android</strong><small>${escapeHTML(device.androidRelease||"")} · API ${escapeHTML(device.sdk||"")}<br>${escapeHTML(device.device||"")}</small></div><div class="trueos-card"><strong>Kernel</strong><small>${info.processes} process(es)<br>${info.apps} app(s)<br>${info.mounts} mount(s)</small></div></div>
      <div class="rift-settings-group"><div><strong>System diagnostics</strong><small>Create a privacy-limited RiftOS system dump and choose exactly where it is saved.</small></div><button class="trueos-btn primary" id="settingsDump">Save system dump…</button></div>
      <div class="rift-settings-group"><div><strong>Android files</strong><small>Mount a folder through Android's Storage Access Framework.</small></div><button class="trueos-btn" id="settingsMount">Mount folder…</button></div>
      <div class="rift-settings-actions"><button class="trueos-btn" id="settingsNotify">Notification permission</button><button class="trueos-btn" id="settingsMounts">Mount table</button><button class="trueos-btn" id="settingsPermissions">Capabilities</button></div>
      <pre class="trueos-code" id="settingsOutput">Samsung / Android native host is active.</pre>
    </section>
  </div>`;
  const out=body.querySelector("#settingsOutput");
  body.querySelectorAll("[data-settings-view]").forEach(button=>button.onclick=async()=>{
    body.querySelectorAll("[data-settings-view]").forEach(item=>item.classList.toggle("active",item===button));
    if(button.dataset.settingsView==="diagnostics"){body.querySelector("#settingsDump")?.closest(".rift-settings-group")?.scrollIntoView({behavior:"smooth",block:"center"});return;}
    if(button.dataset.settingsView==="storage"){try{out.textContent=JSON.stringify(await core.fs.estimate(),null,2);}catch(error){out.textContent=error.message;}return;}
    body.querySelector(".rift-settings-page")?.scrollTo({top:0,behavior:"smooth"});
  });
  body.querySelector("#settingsDump").onclick=async()=>{try{out.textContent="Opening Android Save As…";const result=await core.native.call("system.dump.save",{});out.textContent=result?.cancelled?"System dump save cancelled.":`System dump saved as ${result?.name||"selected file"} (${fmtBytes(result?.bytes||0)}).`;}catch(error){out.textContent=error.message;}};
  body.querySelector("#settingsMount").onclick=async()=>{try{const mount=await core.fs.mountNativeDirectory();out.textContent=`Mounted ${mount.path}`;}catch(error){out.textContent=error.message;}};
  body.querySelector("#settingsNotify").onclick=async()=>{try{out.textContent=JSON.stringify(await core.native.call("notifications.request",{}),null,2);}catch(error){out.textContent=error.message;}};
  body.querySelector("#settingsMounts").onclick=()=>{out.textContent=core.kernel.mounts().map(m=>`${m.path}\t${m.type}\t${m.mode}\t${m.label}`).join("\n");};
  body.querySelector("#settingsPermissions").onclick=()=>{out.textContent=core.permissions.describe().join("\n");};
}

async function openBrowser(startUrl="https://chatgpt.com"){
  await core.ready;
  const body=openWindow("browser","RiftBrowser","ANDROID WEBVIEW WINDOW");
  body.classList.add("rift-browser-window-body");
  const win=body.closest(".window");
  const url=String(startUrl||"https://chatgpt.com").trim()||"https://chatgpt.com";
  body.innerHTML=`<div class="rift-browser-window">
    <div class="rift-browser-windowbar">
      <button id="browserBack" title="Back" disabled>←</button><button id="browserForward" title="Forward" disabled>→</button><button id="browserReload" title="Reload">↻</button>
      <input id="browserUrl" value="${escapeHTML(url)}" autocomplete="off" autocapitalize="none" spellcheck="false" inputmode="url">
      <button class="primary" id="browserGo">Go</button>
    </div>
    <div class="rift-browser-meta"><span id="browserState">Android WebView</span><span>ChatGPT sandbox enabled on chatgpt.com</span></div>
    <div class="rift-browser-native-surface" id="riftBrowserNativeSurface"><div><span>◎</span><strong>RiftBrowser</strong><small>Native WebView appears here while this window is focused.</small></div></div>
  </div>`;
  const surface=body.querySelector("#riftBrowserNativeSurface"),input=body.querySelector("#browserUrl"),stateEl=body.querySelector("#browserState"),back=body.querySelector("#browserBack"),forward=body.querySelector("#browserForward");
  let closed=false,lastBounds="",syncTimer=0;
  const updateState=state=>{if(closed||!document.contains(body))return;if(state.url&&document.activeElement!==input)input.value=state.url;back.disabled=!state.canGoBack;forward.disabled=!state.canGoForward;stateEl.textContent=state.crashed?"WebView renderer restarted":state.progress<100?`Loading ${state.progress||0}%`:(state.title||"Android WebView");};
  browserNativeListeners.add(updateState);
  const native=async(method,args={})=>core.native.call(`browser.window.${method}`,args);
  const visible=()=>document.contains(surface)&&!win.classList.contains("rift-minimized")&&win.classList.contains("rift-focused")&&!document.querySelector("#riftStartMenu.open");
  const syncBounds=force=>{
    clearTimeout(syncTimer);
    syncTimer=setTimeout(async()=>{
      if(closed||!document.contains(surface))return;
      const rect=surface.getBoundingClientRect(),show=visible()&&rect.width>4&&rect.height>4;
      const key=[Math.round(rect.left),Math.round(rect.top),Math.round(rect.width),Math.round(rect.height),show].join(":");
      if(force||key!==lastBounds){lastBounds=key;try{if(show)await native("bounds",{left:rect.left,top:rect.top,width:rect.width,height:rect.height,dpr:window.devicePixelRatio||1});await native("visible",{visible:show});}catch(_){}}
    },35);
  };
  const navigate=async()=>{try{await native("navigate",{url:input.value.trim()||"https://chatgpt.com"});}catch(error){stateEl.textContent=error.message;}};
  body.querySelector("#browserGo").onclick=navigate;input.addEventListener("keydown",event=>{if(event.key==="Enter")navigate();});
  back.onclick=()=>native("back").catch(()=>{});forward.onclick=()=>native("forward").catch(()=>{});body.querySelector("#browserReload").onclick=()=>native("reload").catch(()=>{});
  const resizeObserver=new ResizeObserver(()=>syncBounds(false));resizeObserver.observe(surface);resizeObserver.observe(win);
  const windowObserver=new MutationObserver(()=>syncBounds(false));windowObserver.observe(win,{attributes:true,attributeFilter:["class","style"]});
  const startMenu=document.querySelector("#riftStartMenu"),startObserver=startMenu?new MutationObserver(()=>syncBounds(true)):null;if(startMenu)startObserver.observe(startMenu,{attributes:true,attributeFilter:["class"]});
  const activation=()=>syncBounds(true);window.addEventListener("riftos:window-activate",activation);window.addEventListener("resize",activation);
  const closeHandler=event=>{if(event.detail?.id!=="browser")return;closed=true;clearTimeout(syncTimer);browserNativeListeners.delete(updateState);resizeObserver.disconnect();windowObserver.disconnect();startObserver?.disconnect();window.removeEventListener("riftos:window-activate",activation);window.removeEventListener("resize",activation);window.removeEventListener("riftos:window-close",closeHandler);native("close").catch(()=>{});};
  window.addEventListener("riftos:window-close",closeHandler);
  requestAnimationFrame(()=>{syncBounds(true);native("open",{url}).then(updateState).catch(error=>{stateEl.textContent=error.message;});});
  return true;
}

function tokenize(raw){const out=[];String(raw||"").replace(/"([^"]*)"|'([^']*)'|([^\s]+)/g,(_,a,b,c)=>{out.push(a??b??c);return "";});return out;}
function resolvePath(cwd,value){if(!value)return cwd;return core.path.normalize(String(value).startsWith("/")?value:core.path.join(cwd,value));}
async function runShell(raw,print,state){
  const args=tokenize(raw),cmd=(args.shift()||"").toLowerCase();if(!cmd)return;
  if(/^(git|gh|github)$/i.test(cmd)){if(!window.RiftGit?.run)throw new Error("RiftGit is not loaded");return window.RiftGit.run(args,print);}
  if(cmd==="help")return print(`RiftShell / Android Native\nhelp  sysinfo  mount  umount  df  ps  kill <pid>  apps  permissions  native\npwd  cd <dir>  ls [path]  cat <file>  write <file> <text>  mkdir <dir>  rm <path>\nopen <app>  browser [url]  workspace [info|ls|history|rollback]  clear  uptime  version\ngit help`);
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
    const ws=window.RiftWorkspace;if(!ws?.available)return print("RiftWorkspace unavailable");
    const sub=(args.shift()||"info").toLowerCase();if(sub==="info")return print(JSON.stringify(await ws.info(),null,2));
    if(sub==="ls")return print((await ws.list(args[0]||"",{recursive:false})).map(row=>`${row.kind==="directory"?"d":"-"}\t${row.path}`).join("\n")||"(empty)");
    if(sub==="history")return print(JSON.stringify(await ws.history(),null,2));if(sub==="rollback")return print(JSON.stringify(await ws.rollback(args[0]||null),null,2));
    return print("usage: workspace [info|ls [path]|history|rollback [historyId]]");
  }
  if(cmd==="pwd")return print(state.cwd);
  if(cmd==="cd"){const next=resolvePath(state.cwd,args[0]||"/home");const stat=await core.fs.stat(next);if(!stat||!["directory","mount"].includes(stat.kind))throw new Error(`not a directory: ${next}`);state.cwd=next;return print(state.cwd);}
  if(cmd==="ls"){const path=resolvePath(state.cwd,args[0]||state.cwd),rows=await core.fs.list(path,{recursive:false});return print(rows.map(row=>`${row.kind==="directory"||row.kind==="mount"?"d":"-"}\t${row.path}`).join("\n")||"(empty)");}
  if(cmd==="cat"){const path=resolvePath(state.cwd,args[0]);const text=await core.fs.readText(path);if(text==null)throw new Error(`file not found: ${path}`);return print(text);}
  if(cmd==="write"){const path=resolvePath(state.cwd,args.shift());await core.fs.writeText(path,args.join(" "));return print(`wrote ${path}`);}
  if(cmd==="mkdir"){const path=resolvePath(state.cwd,args[0]);await core.fs.mkdir(path);return print(`created ${path}`);}
  if(cmd==="rm"){const path=resolvePath(state.cwd,args[0]);await core.fs.remove(path);return print(`removed ${path}`);}
  if(cmd==="open"){const app=args[0]||"home";document.querySelector(`[data-open="${CSS.escape(app)}"]`)?.click();return print(`opened ${app}`);}
  if(cmd==="clear")return {clear:true};
  if(cmd==="uptime")return print(`${core.kernel.uptime()}s`);
  if(cmd==="version")return print(core.version);
  throw new Error(`unknown command: ${cmd}`);
}

async function openTerminal(){
  await core.ready;const body=openWindow("terminal","RiftShell","ANDROID NATIVE SHELL"),state={cwd:"/home"};
  body.innerHTML=`<div class="shell"><pre class="shell-output" id="shellOutput">RiftShell ${escapeHTML(core.version)}\nAndroid-native RiftFS ready. Type help.</pre><form id="shellForm" class="shell-form"><span id="shellPrompt">/home $</span><input id="shellInput" autocomplete="off" autocapitalize="none" spellcheck="false"></form></div>`;
  const out=body.querySelector("#shellOutput"),form=body.querySelector("#shellForm"),input=body.querySelector("#shellInput"),promptEl=body.querySelector("#shellPrompt");
  const print=value=>{out.textContent+=(out.textContent?"\n":"")+String(value??"");out.scrollTop=out.scrollHeight;};
  form.onsubmit=async event=>{event.preventDefault();const raw=input.value;input.value="";if(!raw.trim())return;print(`${state.cwd} $ ${raw}`);try{const result=await runShell(raw,print,state);if(result?.clear)out.textContent="";}catch(error){print(`error: ${error.message}`);}promptEl.textContent=`${state.cwd} $`;};
  setTimeout(()=>input.focus(),60);
}

async function openApp(id){
  if(id==="home")return showDesktop();
  if(id==="files")return openFiles("/");
  if(id==="terminal")return openTerminal();
  if(id==="browser")return openBrowser();
  if(id==="editor")return openEditor();
  if(id==="tasks")return openTasks();
  if(id==="settings")return openSettings();
  if(id==="riftdev"&&window.RiftDev?.open)return window.RiftDev.open();
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
  focus:focusWindow,
  close:closeWindow,
  showDesktop,
  sync:syncShellState
});
window.RiftDesktop=Object.freeze({openApp,openFiles,openEditor,openTerminal,openSettings,openBrowser,closeWindow,showDesktop,setStatus});

(async()=>{
  try{
    await core.ready;appGrid();
    const boot=$("#boot"),os=$("#os");
    setTimeout(()=>{boot?.classList.add("hidden");os?.classList.remove("hidden");syncShellState();setStatus("Ready");},220);
  }catch(error){
    const boot=$("#boot");if(boot)boot.innerHTML=`<div class="boot-title">RiftOS boot failed</div><pre>${escapeHTML(error.stack||error.message)}</pre>`;
  }
})();
