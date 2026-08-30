const core=window.RiftOSCore;
if(!core)throw new Error("RiftOSCore must load before RiftOS desktop");

const BUILTIN_APPS=[
  {id:"files",name:"Files",icon:"▣",desc:"RiftFS + mounted Files"},
  {id:"terminal",name:"RiftShell",icon:">_",desc:"Kernel command shell"},
  {id:"browser",name:"RiftBrowser",icon:"◎",desc:"Light web transport"},
  {id:"editor",name:"Editor",icon:"{}",desc:"RiftFS text editor"},
  {id:"tasks",name:"Tasks",icon:"≡",desc:"RiftKernel processes"},
  {id:"settings",name:"Settings",icon:"⚙",desc:"System + capabilities"}
];

const $=selector=>document.querySelector(selector);
const escapeHTML=value=>String(value??"").replace(/[&<>"']/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[ch]));
const fmtBytes=value=>{
  const n=Number(value||0);
  if(n<1024)return `${n} B`;
  if(n<1024**2)return `${(n/1024).toFixed(1)} KB`;
  if(n<1024**3)return `${(n/1024**2).toFixed(1)} MB`;
  return `${(n/1024**3).toFixed(2)} GB`;
};

const stage=$("#stage"),workspace=$("#workspace");
let activeProcess=null;

function setStatus(value){const el=$("#statusText");if(el)el.textContent=value;}
function tick(){const clock=$("#clock");if(clock)clock.textContent=new Date().toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"});}
setInterval(tick,1000);tick();

function stopActiveProcess(){
  if(activeProcess){core.kernel.kill(activeProcess.pid);activeProcess=null;}
}

function closeWindow(){
  stopActiveProcess();
  stage.innerHTML="";stage.classList.add("hidden");workspace.classList.remove("hidden");
  document.querySelectorAll(".dock-btn").forEach(btn=>btn.classList.toggle("active",btn.dataset.open==="home"));
  setStatus("Ready");
}

function openWindow(id,title,kicker="RIFT APP"){
  stopActiveProcess();
  workspace.classList.add("hidden");stage.classList.remove("hidden");stage.innerHTML="";
  const template=$("#windowTemplate");
  if(!template)throw new Error("RiftOS window template is missing");
  const win=template.content.firstElementChild.cloneNode(true);
  win.dataset.app=id;
  win.querySelector(".window-title").textContent=title;
  win.querySelector(".window-kicker").textContent=kicker;
  win.querySelector(".window-close").onclick=closeWindow;
  stage.append(win);
  activeProcess=core.kernel.launchProcess(id,title,{kind:"ui"});
  document.querySelectorAll(".dock-btn").forEach(btn=>btn.classList.toggle("active",btn.dataset.open===id));
  setStatus(title);
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
    if(row.path===base)continue;
    if(!row.path.startsWith(base==="/"?"/":base+"/"))continue;
    const rest=row.path.slice(base==="/"?1:base.length+1);
    if(!rest)continue;
    const first=rest.split("/")[0],childPath=core.path.join(base,first),direct=rest===first;
    const existing=map.get(first);
    if(!existing||direct)map.set(first,direct?row:{path:childPath,kind:"directory",size:0,modified:0,backend:row.backend});
  }
  return [...map.values()].sort((a,b)=>(a.kind==="directory"||a.kind==="mount"?-1:1)-(b.kind==="directory"||b.kind==="mount"?-1:1)||a.path.localeCompare(b.path));
}

async function openFiles(path="/"){
  await core.ready;
  path=core.path.normalize(path);
  const body=openWindow("files","Files","RIFTFS");
  let rows=[];
  try{rows=await core.fs.list(path,{recursive:true});}catch(error){
    body.innerHTML=`<p><strong>Cannot open ${escapeHTML(path)}</strong></p><pre class="shell-output">${escapeHTML(error.message)}</pre>`;return;
  }
  const entries=topLevelEntries(rows,path),storage=await core.fs.estimate();
  const parent=path==="/"?null:core.path.parent(path);
  body.innerHTML=`
    <div class="trueos-head">
      <div><strong>${escapeHTML(path)}</strong><small>${escapeHTML(storage.backend)} · ${fmtBytes(storage.usage)} used</small></div>
      <span class="trueos-chip ${core.fs.opfsReady?"ok":"warn"}">${core.fs.opfsReady?"OPFS":"COMPAT"}</span>
    </div>
    <div class="trueos-toolbar">
      ${parent!==null?`<button class="trueos-btn" id="fsUp">↑ Up</button>`:""}
      <button class="trueos-btn primary" id="fsNewFile">New file</button>
      <button class="trueos-btn" id="fsNewFolder">New folder</button>
      ${core.native.connected&&path==="/mounts"?`<button class="trueos-btn" id="fsMountNative">Mount iOS folder</button>`:""}
      <button class="trueos-btn" id="fsSync">Sync mirror</button>
    </div>
    <div class="trueos-files">
      ${entries.length?entries.map(entry=>{
        const folder=entry.kind==="directory"||entry.kind==="mount";
        return `<button class="trueos-file" data-path="${escapeHTML(entry.path)}" data-kind="${escapeHTML(entry.kind)}"><span>${folder?"▸":"·"} ${escapeHTML(core.path.basename(entry.path)||entry.path)}</span><small>${folder?escapeHTML(entry.backend||entry.kind):fmtBytes(entry.size)}</small></button>`;
      }).join(""):`<div class="trueos-card trueos-muted">Empty directory.</div>`}
    </div>`;
  body.querySelector("#fsUp")?.addEventListener("click",()=>openFiles(parent));
  body.querySelectorAll("[data-path]").forEach(button=>button.onclick=()=>["directory","mount"].includes(button.dataset.kind)?openFiles(button.dataset.path):openEditor(button.dataset.path));
  body.querySelector("#fsNewFile").onclick=()=>{
    const name=prompt("File name","untitled.txt");if(name)openEditor(core.path.join(path,name));
  };
  body.querySelector("#fsNewFolder").onclick=async()=>{
    const name=prompt("Folder name","New Folder");if(!name)return;
    await core.fs.mkdir(core.path.join(path,name));openFiles(path);
  };
  body.querySelector("#fsSync").onclick=async()=>{
    setStatus("Syncing RiftFS");const result=await core.fs.syncLegacy();setStatus(`RiftFS synced · ${result.copiedToOPFS+result.copiedToLegacy} change(s)`);setTimeout(()=>openFiles(path),300);
  };
  body.querySelector("#fsMountNative")?.addEventListener("click",async()=>{
    try{await core.fs.mountNativeDirectory();openFiles("/mounts");}catch(error){alert(error.message);}
  });
}

async function openEditor(path="/home/scratch.txt"){
  await core.ready;path=core.path.normalize(path);
  const file=await core.fs.get(path),body=openWindow("editor","Editor","RIFTFS EDITOR");
  if(file?.kind==="directory"){openFiles(path);return;}
  body.innerHTML=`<div class="trueos-editor"><div class="trueos-head"><div><strong>${escapeHTML(path)}</strong><small>${escapeHTML(file?.backend||(core.fs.opfsReady?"opfs":"indexeddb"))}</small></div><button class="trueos-btn" id="editorFiles">Files</button><button class="trueos-btn primary" id="editorSave">Save</button></div><textarea spellcheck="false" autocomplete="off"></textarea></div>`;
  const textarea=body.querySelector("textarea");textarea.value=file?.content||"";
  let dirty=false;
  textarea.addEventListener("input",()=>{dirty=true;setStatus("Editor · unsaved");});
  body.querySelector("#editorSave").onclick=async()=>{
    await core.fs.write(path,textarea.value);dirty=false;setStatus("Saved");setTimeout(()=>setStatus("Editor"),800);
  };
  body.querySelector("#editorFiles").onclick=()=>openFiles(core.path.parent(path));
  setTimeout(()=>textarea.focus(),40);
}

function browserTarget(value){
  const v=String(value||"").trim();
  if(!v)return null;
  if(/^(https?:\/\/|localhost(?::\d+)?(?:\/|$)|(?:[\w-]+\.)+[a-z]{2,}(?:[/:?#]|$))/i.test(v))return /^https?:\/\//i.test(v)?v:`https://${v}`;
  return `https://www.google.com/search?q=${encodeURIComponent(v)}`;
}

function openBrowser(){
  const body=openWindow("browser","RiftBrowser","WEB TRANSPORT");
  body.innerHTML=`
    <div class="browser rift-browser">
      <div class="browser-chrome">
        <form class="browser-bar" id="browserForm">
          <button type="button" class="browser-icon" id="browserHome">⌂</button>
          <input id="browserAddress" placeholder="Search or enter a URL" autocomplete="off" autocapitalize="off" spellcheck="false">
          <button type="submit">Go</button>
        </form>
        <div class="browser-status"><span id="browserState">Ready</span><button class="browser-link" id="browserExternal" type="button">Safari ↗</button></div>
      </div>
      <div class="browser-surface" id="browserSurface"></div>
    </div>`;
  const input=body.querySelector("#browserAddress"),surface=body.querySelector("#browserSurface"),state=body.querySelector("#browserState");
  let current="";
  const home=()=>{
    current="";input.value="";state.textContent="RiftBrowser transport mode";
    surface.innerHTML=`<section class="browser-start"><div class="browser-logo">R</div><h2>RiftBrowser is on hold.</h2><p>The custom WebCore engine is paused while RiftOS True OS work continues. This lightweight transport can still open web destinations through Safari and directly render CORS-enabled text/HTML pages.</p><div class="browser-capability"><strong>Engine source preserved</strong><span>RiftEngine/WebCore workflows remain in the repository, but normal RiftOS deploys no longer rebuild them.</span></div></section>`;
  };
  const openExternal=url=>{if(!url)return;const win=window.open(url,"_blank","noopener,noreferrer");if(!win)location.href=url;};
  const navigate=async value=>{
    const url=browserTarget(value);if(!url){home();return;}current=url;input.value=url;state.textContent="Connecting…";
    surface.innerHTML=`<div class="browser-loading"><i></i><span>Connecting to ${escapeHTML(url)}</span></div>`;
    try{
      const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),8000);
      const response=await fetch(url,{mode:"cors",credentials:"omit",redirect:"follow",signal:controller.signal});clearTimeout(timer);
      if(!response.ok)throw new Error(`HTTP ${response.status}`);
      const type=(response.headers.get("content-type")||"").toLowerCase();
      if(!type.includes("text/html")&&!type.includes("text/plain")&&!type.includes("application/xhtml+xml"))throw new Error("Unsupported direct content type");
      const text=await response.text(),doc=new DOMParser().parseFromString(text,"text/html");
      doc.querySelectorAll("script,object,embed,applet,iframe,meta[http-equiv='refresh']").forEach(node=>node.remove());
      for(const element of doc.querySelectorAll("*"))for(const attr of [...element.attributes])if(/^on/i.test(attr.name))element.removeAttribute(attr.name);
      const base=doc.createElement("base");base.href=response.url||url;doc.head.prepend(base);
      surface.innerHTML=`<iframe class="webview direct-view" sandbox="allow-forms allow-popups" referrerpolicy="no-referrer"></iframe>`;
      surface.querySelector("iframe").srcdoc="<!doctype html>"+doc.documentElement.outerHTML;
      state.textContent=`Direct · ${new URL(response.url||url).hostname}`;
    }catch(error){
      state.textContent="External transport required";
      surface.innerHTML=`<section class="browser-blocked"><div class="browser-warning">↗</div><h2>Open this destination in Safari</h2><p>The site blocks direct browser-to-browser rendering, or the custom RiftEngine is required.</p><code>${escapeHTML(url)}</code><div class="browser-block-actions"><button class="action" id="browserOpenExternal">Open in Safari</button></div></section>`;
      surface.querySelector("#browserOpenExternal").onclick=()=>openExternal(url);
    }
  };
  body.querySelector("#browserForm").onsubmit=event=>{event.preventDefault();navigate(input.value);};
  body.querySelector("#browserHome").onclick=home;
  body.querySelector("#browserExternal").onclick=()=>openExternal(current||browserTarget(input.value));
  home();
}

async function openTasks(){
  await core.ready;const body=openWindow("tasks","Tasks","RIFTKERNEL PROCESS TABLE");
  const render=()=>{
    const rows=core.processes.list();
    body.innerHTML=`<div class="trueos-head"><div><strong>RiftKernel processes</strong><small>Uptime ${core.kernel.uptime()}s</small></div><span class="trueos-chip ok">${rows.length} RUNNING</span></div><table class="trueos-table"><thead><tr><th>PID</th><th>Process</th><th>Kind</th><th></th></tr></thead><tbody>${rows.map(process=>`<tr><td>${process.pid}</td><td>${escapeHTML(process.name)}</td><td>${escapeHTML(process.kind||process.appId)}</td><td>${process.protected?"system":`<button class="trueos-btn" data-kill="${process.pid}">Kill</button>`}</td></tr>`).join("")}</tbody></table>`;
    body.querySelectorAll("[data-kill]").forEach(button=>button.onclick=()=>{core.kernel.kill(button.dataset.kill);render();});
  };
  render();
}

async function openSettings(){
  await core.ready;const body=openWindow("settings","Settings","TRUE OS SYSTEM"),info=await core.kernel.info(),caps=info.native;
  body.innerHTML=`
    <div class="trueos-head"><div><strong>RiftOS ${escapeHTML(info.version)}</strong><small>${escapeHTML(info.mode)} · uptime ${info.uptime}s</small></div><span class="trueos-chip ${caps.nativeHost?"ok":"warn"}">${caps.nativeHost?"NATIVE HOST":"PWA MODE"}</span></div>
    <div class="trueos-grid">
      <div class="trueos-card"><strong>RiftFS</strong><small>${escapeHTML(info.storage.backend)}<br>${fmtBytes(info.storage.usage)} / ${info.storage.quota?fmtBytes(info.storage.quota):"browser managed"}</small><div class="trueos-toolbar"><button class="trueos-btn" id="settingsPersist">Persist storage</button></div></div>
      <div class="trueos-card"><strong>Native bridge</strong><small>${caps.nativeHost?"Swift/WKWebView connected.":"Running entirely as a PWA."}</small><div class="trueos-toolbar">${caps.nativeHost?`<button class="trueos-btn" id="settingsMount">Mount Files folder</button>`:""}</div></div>
      <div class="trueos-card"><strong>Kernel</strong><small>${info.processes} process(es)<br>${info.apps} registered app(s)<br>${info.mounts} mount(s)</small></div>
      <div class="trueos-card"><strong>Capabilities</strong><small>OPFS ${caps.opfs?"✓":"—"} · Share ${caps.share?"✓":"—"} · Notifications ${caps.notifications?"✓":"—"} · Clipboard ${caps.clipboard?"✓":"—"}</small></div>
    </div>
    <div class="trueos-toolbar"><button class="trueos-btn" id="settingsMounts">Mount table</button><button class="trueos-btn" id="settingsPermissions">Capability vocabulary</button></div>
    <pre class="trueos-code" id="settingsOutput">True OS Core 1.0 is active.</pre>`;
  const out=body.querySelector("#settingsOutput");
  body.querySelector("#settingsPersist").onclick=async()=>{out.textContent=(await core.fs.persist())?"Persistent browser storage granted/already active.":"Persistent storage was not granted.";};
  body.querySelector("#settingsMounts").onclick=()=>{out.textContent=core.kernel.mounts().map(m=>`${m.path}\t${m.type}\t${m.mode}\t${m.label}`).join("\n");};
  body.querySelector("#settingsPermissions").onclick=()=>{out.textContent=core.permissions.describe().join("\n");};
  body.querySelector("#settingsMount")?.addEventListener("click",async()=>{try{const mount=await core.fs.mountNativeDirectory();out.textContent=`Mounted ${mount.path}`;}catch(error){out.textContent=error.message;}});
}

function tokenize(raw){
  const out=[];String(raw||"").replace(/"([^"]*)"|'([^']*)'|([^\s]+)/g,(_,a,b,c)=>{out.push(a??b??c);return "";});return out;
}
function resolvePath(cwd,value){if(!value)return cwd;return core.path.normalize(String(value).startsWith("/")?value:core.path.join(cwd,value));}

async function runShell(raw,print,state){
  const args=tokenize(raw),cmd=(args.shift()||"").toLowerCase();
  if(!cmd)return;
  if(/^(git|gh|github)$/i.test(cmd)){
    if(!window.RiftGit?.run)throw new Error("RiftGit is not loaded");
    return window.RiftGit.run(args,print);
  }
  if(cmd==="help")return print(`RiftShell / True OS Core
help  sysinfo  mount  umount  df  ps  kill <pid>  apps  permissions  native
pwd  cd <dir>  ls [path]  cat <file>  write <file> <text>  mkdir <dir>  rm <path>
syncfs  open <app>  clear  uptime  version
git help

Native host:
  mount native        choose an iOS Files directory
  umount <path>       detach a native mount`);
  if(cmd==="sysinfo")return print(JSON.stringify(await core.kernel.info(),null,2));
  if(cmd==="mount"){
    if((args[0]||"").toLowerCase()==="native"){const mount=await core.fs.mountNativeDirectory();return print(`mounted ${mount.path}`);}
    return print(core.kernel.mounts().map(m=>`${m.path}\t${m.type}\t${m.mode}\t${m.label}`).join("\n"));
  }
  if(cmd==="umount"){
    if(!args[0])return print("usage: umount <path>");
    return print(await core.fs.unmount(resolvePath(state.cwd,args[0]))?"unmounted":"mount not found");
  }
  if(cmd==="df"){const storage=await core.fs.estimate();return print(`${storage.backend}\nused ${fmtBytes(storage.usage)}\nquota ${storage.quota?fmtBytes(storage.quota):"browser managed"}`);}
  if(cmd==="ps")return print(core.processes.list().map(p=>`${p.pid}\t${p.state}\t${p.kind||p.appId}\t${p.name}`).join("\n"));
  if(cmd==="kill")return print(core.kernel.kill(args[0])?`terminated ${args[0]}`:`cannot terminate ${args[0]||"(missing pid)"}`);
  if(cmd==="apps"){
    const built=[...core.kernel.apps.values()].map(app=>`${app.id}\t${app.name}`);
    const installed=await window.RiftApps?.list?.()||[];
    return print([...built,...installed.map(app=>`${app.id}\t${app.manifest?.name||app.id}\tinstalled`)].join("\n"));
  }
  if(cmd==="permissions")return print(core.permissions.describe().join("\n"));
  if(cmd==="native")return print(JSON.stringify(core.native.capabilities(),null,2));
  if(cmd==="pwd")return print(state.cwd);
  if(cmd==="cd"){state.cwd=resolvePath(state.cwd,args[0]||"/home");return print(state.cwd);}
  if(cmd==="ls"){
    const path=resolvePath(state.cwd,args[0]||state.cwd),rows=await core.fs.list(path,{recursive:true}),entries=topLevelEntries(rows,path);
    return print(entries.map(entry=>`${entry.kind==="directory"||entry.kind==="mount"?"d":"-"}\t${core.path.basename(entry.path)||entry.path}`).join("\n")||"(empty)");
  }
  if(cmd==="cat"){
    if(!args[0])return print("usage: cat <file>");
    const path=resolvePath(state.cwd,args[0]),file=await core.fs.get(path);return print(file?.kind==="file"?file.content:`file not found: ${path}`);
  }
  if(cmd==="write"){
    if(!args[0])return print("usage: write <file> <text>");
    const path=resolvePath(state.cwd,args.shift());await core.fs.write(path,args.join(" "));return print(`written ${path}`);
  }
  if(cmd==="mkdir"){
    if(!args[0])return print("usage: mkdir <dir>");
    const path=resolvePath(state.cwd,args[0]);await core.fs.mkdir(path);return print(`created ${path}`);
  }
  if(cmd==="rm"){
    if(!args[0])return print("usage: rm <path>");
    const path=resolvePath(state.cwd,args[0]);await core.fs.remove(path);return print(`removed ${path}`);
  }
  if(cmd==="syncfs"){const result=await core.fs.syncLegacy();return print(`RiftFS synchronized · OPFS ${result.copiedToOPFS} / legacy ${result.copiedToLegacy}`);}
  if(cmd==="uptime")return print(`${core.kernel.uptime()}s`);
  if(cmd==="version")return print(`RiftOS ${core.kernel.version} / RiftKernel True OS Core`);
  if(cmd==="clear")return print(null,{clear:true});
  if(cmd==="open"){
    const id=(args[0]||"").toLowerCase();if(!id)return print("usage: open <app>");
    print(`opening ${id}...`);setTimeout(()=>launch(id),50);return;
  }
  throw new Error(`command not found: ${cmd}`);
}

function openTerminal(){
  const body=openWindow("terminal","RiftShell","RIFTKERNEL SHELL");
  body.innerHTML=`<div class="shell"><div class="shell-output">RiftShell 1.0 / True OS Core\nType 'help' for commands.\n\n</div><form class="shell-line"><b>rift$</b><input autocomplete="off" autocapitalize="off" spellcheck="false" autofocus></form></div>`;
  const form=body.querySelector("form"),input=form.querySelector("input"),out=body.querySelector(".shell-output"),state={cwd:"/home"};
  const print=(text,options={})=>{
    if(options.clear){out.textContent="";return;}
    if(text==null)return;
    out.textContent+=String(text)+"\n";out.scrollTop=out.scrollHeight;
  };
  form.onsubmit=async event=>{
    event.preventDefault();const raw=input.value.trim();input.value="";if(!raw)return;
    print(`rift$ ${raw}`);
    try{await core.ready;await runShell(raw,print,state);}catch(error){print(`rift: ${error?.message||error}`);}
  };
  setTimeout(()=>input.focus(),80);
}

async function launch(id){
  try{
    if(id==="home"){closeWindow();return;}
    if(id==="files")return openFiles("/");
    if(id==="terminal")return openTerminal();
    if(id==="browser")return openBrowser();
    if(id==="editor")return openEditor();
    if(id==="tasks")return openTasks();
    if(id==="settings")return openSettings();
    if(id==="riftdev"){closeWindow();return window.RiftDev?.open?.();}
    if(id==="rift-apps"){closeWindow();return window.RiftApps?.openManager?.();}
    if(await window.RiftApps?.get?.(id)){closeWindow();return window.RiftApps.launch(id);}
    throw new Error(`Unknown app: ${id}`);
  }catch(error){
    console.error("[RiftOS] app launch failed",id,error);
    const body=openWindow("error","App Error","RIFTKERNEL");
    body.innerHTML=`<p><strong>${escapeHTML(id)} failed to open.</strong></p><pre class="shell-output">${escapeHTML(error?.stack||error?.message||error)}</pre><button class="action" id="errorHome">Return Home</button>`;
    body.querySelector("#errorHome").onclick=closeWindow;
  }
}

document.addEventListener("click",event=>{
  const button=event.target.closest("[data-open]");if(button)launch(button.dataset.open);
});
window.addEventListener("keydown",event=>{if(event.key==="Escape"&&!document.documentElement.classList.contains("riftdev-active"))closeWindow();});

function injectTrueOSStyles(){
  if($("#trueOSStyles"))return;
  const style=document.createElement("style");style.id="trueOSStyles";style.textContent=`
  .trueos-head{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:10px}.trueos-head>div{flex:1;min-width:160px}.trueos-head strong{display:block}.trueos-head small,.trueos-muted{color:#95a3b5}.trueos-chip{border:1px solid #344154;border-radius:999px;padding:5px 8px;font:700 11px system-ui}.trueos-chip.ok{color:#8ce0ae}.trueos-chip.warn{color:#f2ce78}.trueos-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:8px}.trueos-card{border:1px solid #293544;border-radius:12px;background:#0d131b;padding:11px}.trueos-card strong{display:block;margin-bottom:5px}.trueos-card small{color:#94a1b2}.trueos-files{display:flex;flex-direction:column;border:1px solid #293544;border-radius:12px;overflow:hidden}.trueos-file{display:flex;align-items:center;gap:8px;border:0;border-bottom:1px solid #222d3a;background:#0c1219;color:#eef4fb;padding:11px;text-align:left;font:inherit}.trueos-file:last-child{border-bottom:0}.trueos-file span{flex:1;overflow-wrap:anywhere}.trueos-file small{color:#94a1b2}.trueos-toolbar{display:flex;gap:7px;flex-wrap:wrap;margin:8px 0}.trueos-btn{border:1px solid #354255;background:#172130;color:#f4f7fb;border-radius:9px;padding:8px 10px;font:700 12px system-ui}.trueos-btn.primary{background:#eef4fb;color:#0a1119}.trueos-editor{display:flex;flex-direction:column;height:min(70dvh,650px);gap:8px}.trueos-editor textarea{flex:1;min-height:300px;resize:none;border:1px solid #293544;border-radius:10px;background:#070b10;color:#edf3fa;padding:12px;font:13px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace}.trueos-table{width:100%;border-collapse:collapse}.trueos-table th,.trueos-table td{text-align:left;padding:8px;border-bottom:1px solid #25303d;font-size:12px}.trueos-table th{color:#93a2b5}.trueos-code{padding:10px;border:1px solid #293544;border-radius:10px;background:#070b10;white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.45 ui-monospace,SFMono-Regular,Menlo,monospace}
  `;document.head.append(style);
}

window.RiftDesktop=Object.freeze({launch,openWindow,closeWindow,setStatus,refreshLauncher:appGrid});

(async()=>{
  appGrid();injectTrueOSStyles();
  try{
    await core.ready;
    document.documentElement.dataset.riftKernel="trueos";
    if("serviceWorker" in navigator)navigator.serviceWorker.register("./sw.js").catch(error=>console.warn("[RiftOS] service worker",error));
    const info=await core.kernel.info();console.info("[TrueOS] booted",info);
    window.dispatchEvent(new CustomEvent("riftos:trueos-ready",{detail:info}));
    setTimeout(()=>{$("#boot")?.remove();$("#os")?.classList.remove("hidden");},450);
  }catch(error){
    const sub=$(".boot-sub");if(sub)sub.textContent=`Boot failed: ${error.message}`;
  }
})();
