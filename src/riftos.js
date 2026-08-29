const APPS = [
  {id:"files",name:"Files",icon:"▣",desc:"Persistent RiftFS"},
  {id:"terminal",name:"RiftShell",icon:">_",desc:"System command shell"},
  {id:"browser",name:"Browser",icon:"◎",desc:"Web view prototype"},
  {id:"editor",name:"Editor",icon:"{}",desc:"Pocket code editor"},
  {id:"tasks",name:"Tasks",icon:"≡",desc:"Runtime processes"},
  {id:"settings",name:"Settings",icon:"⚙",desc:"System controls"}
];

class RiftFS {
  constructor(){this.db=null}
  async init(){
    this.db = await new Promise((resolve,reject)=>{
      const req=indexedDB.open("riftos",1);
      req.onupgradeneeded=()=>{
        const db=req.result;
        if(!db.objectStoreNames.contains("files")) db.createObjectStore("files",{keyPath:"path"});
        if(!db.objectStoreNames.contains("settings")) db.createObjectStore("settings",{keyPath:"key"});
      };
      req.onsuccess=()=>resolve(req.result); req.onerror=()=>reject(req.error);
    });
    if(!(await this.get("/home/readme.txt"))) await this.write("/home/readme.txt","Welcome to RiftOS.\n\nThis file lives in RiftFS (IndexedDB) and survives reloads.");
  }
  store(name,mode="readonly"){return this.db.transaction(name,mode).objectStore(name)}
  req(req){return new Promise((res,rej)=>{req.onsuccess=()=>res(req.result);req.onerror=()=>rej(req.error)})}
  get(path){return this.req(this.store("files").get(path))}
  write(path,content){return this.req(this.store("files","readwrite").put({path,content,modified:Date.now()}))}
  remove(path){return this.req(this.store("files","readwrite").delete(path))}
  list(){return this.req(this.store("files").getAll())}
  setting(key){return this.req(this.store("settings").get(key))}
  setSetting(key,value){return this.req(this.store("settings","readwrite").put({key,value}))}
}

class RiftKernel {
  constructor(){this.fs=new RiftFS();this.tasks=new Map();this.seq=0}
  async boot(){await this.fs.init();this.spawn("system","Rift Runtime");this.spawn("shell-ui","Desktop Shell")}
  spawn(id,name){const pid=++this.seq;this.tasks.set(pid,{pid,id,name,state:"running",started:Date.now()});return pid}
  kill(pid){if(pid>2)this.tasks.delete(Number(pid))}
  uptime(){return Math.floor(performance.now()/1000)}
}

const kernel=new RiftKernel();
const $=s=>document.querySelector(s);
const stage=$("#stage"), workspace=$("#workspace");

function setStatus(v){$("#statusText").textContent=v}
function tick(){ $("#clock").textContent=new Date().toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"}); }
setInterval(tick,1000); tick();

function appGrid(){
  $("#appGrid").innerHTML=APPS.map(a=>`<button class="app-card" data-open="${a.id}"><span class="app-icon">${a.icon}</span><span><strong>${a.name}</strong><br><small>${a.desc}</small></span></button>`).join("");
}

function closeWindow(){
  stage.innerHTML="";
  stage.classList.add("hidden");
  workspace.classList.remove("hidden");
  document.querySelectorAll(".dock-btn").forEach(x=>x.classList.toggle("active",x.dataset.open==="home"));
  setStatus("Ready");
}
function openWindow(id,title,kicker="RIFT APP"){
  workspace.classList.add("hidden");
  stage.classList.remove("hidden");
  stage.innerHTML="";
  const win=$("#windowTemplate").content.firstElementChild.cloneNode(true);
  win.dataset.app=id;win.querySelector(".window-title").textContent=title;win.querySelector(".window-kicker").textContent=kicker;
  win.querySelector(".window-close").onclick=closeWindow;stage.append(win);
  document.querySelectorAll(".dock-btn").forEach(x=>x.classList.toggle("active",x.dataset.open===id));
  setStatus(title);return win.querySelector(".window-body");
}

async function openFiles(){
  const body=openWindow("files","Files","RIFTFS");
  const rows=await kernel.fs.list();
  body.innerHTML=`<p class="muted">Persistent files stored locally on this device.</p><div class="file-list">${rows.map(f=>`<button class="file-row" data-file="${f.path}"><span>${f.path}</span><small>${new Blob([f.content]).size} B</small></button>`).join("")}</div>`;
  body.querySelectorAll("[data-file]").forEach(b=>b.onclick=()=>openEditor(b.dataset.file));
}

async function openEditor(path="/home/scratch.txt"){
  const file=await kernel.fs.get(path);const body=openWindow("editor","Editor","RIFT EDIT");
  body.innerHTML=`<div class="editor"><div><strong>${path}</strong> <button class="action" id="saveFile">Save</button></div><textarea spellcheck="false"></textarea></div>`;
  const ta=body.querySelector("textarea");ta.value=file?.content||"";
  body.querySelector("#saveFile").onclick=async()=>{await kernel.fs.write(path,ta.value);setStatus("Saved");setTimeout(()=>setStatus("Editor"),900)};
}

function openBrowser(){
  const body=openWindow("browser","Browser","EXPERIMENTAL");
  body.innerHTML=`<div class="browser"><div><form class="browser-bar"><input aria-label="URL" value="https://example.com"><button>Go</button></form><p class="browser-note">Prototype: sites may block embedding. The later Rift network/browser layer will replace this limitation.</p></div><iframe class="webview" sandbox="allow-forms allow-scripts allow-same-origin allow-popups"></iframe></div>`;
  const form=body.querySelector("form"), input=form.querySelector("input"), frame=body.querySelector("iframe");
  const go=()=>{let u=input.value.trim();if(!/^https?:\/\//i.test(u))u="https://"+u;frame.src=u};
  form.onsubmit=e=>{e.preventDefault();go()};go();
}

function openTasks(){
  const body=openWindow("tasks","Tasks","KERNEL");
  const render=()=>body.innerHTML=`<p class="muted">Uptime ${kernel.uptime()}s</p><div class="file-list">${[...kernel.tasks.values()].map(t=>`<div class="file-row"><span><strong>${t.name}</strong><br><small>pid ${t.pid} · ${t.state}</small></span>${t.pid>2?`<button class="action" data-kill="${t.pid}">Kill</button>`:""}</div>`).join("")}</div>`;
  render();
}

async function openSettings(){
  const body=openWindow("settings","Settings","SYSTEM");
  const compact=(await kernel.fs.setting("compact"))?.value||false;
  body.innerHTML=`<div class="file-list"><div class="setting-row"><span><strong>RiftOS v0.1</strong><br><small>Browser-native mobile runtime</small></span><span>DEV</span></div><div class="setting-row"><span><strong>Local persistence</strong><br><small>IndexedDB / RiftFS</small></span><span>ON</span></div><div class="setting-row"><span><strong>Installable PWA</strong><br><small>Offline shell cache</small></span><span>ON</span></div><div class="setting-row"><span><strong>Compact mode</strong><br><small>Reduce desktop spacing</small></span><button class="action" id="compact">${compact?"ON":"OFF"}</button></div></div>`;
  body.querySelector("#compact").onclick=async e=>{const n=e.target.textContent!=="ON";await kernel.fs.setSetting("compact",n);e.target.textContent=n?"ON":"OFF";document.documentElement.style.fontSize=n?"14px":""};
}

function openTerminal(){
  const pid=kernel.spawn("terminal","RiftShell");
  const body=openWindow("terminal","RiftShell","SYSTEM SHELL");
  body.innerHTML=`<div class="shell"><div class="shell-output">RiftShell 0.1\nType 'help' for commands.\n\n</div><form class="shell-line"><b>rift$</b><input autocomplete="off" autocapitalize="off" spellcheck="false" autofocus></form></div>`;
  const out=body.querySelector(".shell-output"),form=body.querySelector("form"),input=form.querySelector("input");
  const print=t=>{out.textContent+=t+"\n";out.scrollTop=out.scrollHeight};
  form.onsubmit=async e=>{
    e.preventDefault();const raw=input.value.trim();input.value="";if(!raw)return;print("rift$ "+raw);
    const [cmd,...args]=raw.split(/\s+/);
    if(cmd==="help")print("help  ls  cat <file>  write <file> <text>  rm <file>\nclear  apps  ps  uptime  open <app>  version");
    else if(cmd==="ls"){const rows=await kernel.fs.list();print(rows.map(x=>x.path).join("\n")||"(empty)")}
    else if(cmd==="cat"){const f=await kernel.fs.get(args[0]);print(f?f.content:"file not found")}
    else if(cmd==="write"){if(!args[0])print("usage: write <file> <text>");else{await kernel.fs.write(args[0],args.slice(1).join(" "));print("written")}}
    else if(cmd==="rm"){await kernel.fs.remove(args[0]);print("removed")}
    else if(cmd==="clear")out.textContent="";
    else if(cmd==="apps")print(APPS.map(a=>a.id).join("  "));
    else if(cmd==="ps")print([...kernel.tasks.values()].map(t=>`${t.pid}\t${t.state}\t${t.name}`).join("\n"));
    else if(cmd==="uptime")print(kernel.uptime()+"s");
    else if(cmd==="version")print("RiftOS 0.1.0 / Rift Runtime");
    else if(cmd==="open"){print(args[0]?`opening ${args[0]}...`:"usage: open <app>");if(args[0])setTimeout(()=>launch(args[0]),150)}
    else print(`command not found: ${cmd}`);
  };
  setTimeout(()=>input.focus(),80);
}

async function launch(id){
  try{
    if(id==="home"){closeWindow();return}
    if(id==="files")return await openFiles();
    if(id==="terminal")return openTerminal();
    if(id==="browser")return openBrowser();
    if(id==="editor")return await openEditor();
    if(id==="tasks")return openTasks();
    if(id==="settings")return await openSettings();
    throw new Error(`Unknown app: ${id}`);
  }catch(err){
    console.error("[RiftOS] app launch failed", id, err);
    const body=openWindow("error","App Error","RIFT RUNTIME");
    body.innerHTML=`<p><strong>${id} failed to open.</strong></p><pre class="shell-output">${String(err?.stack||err?.message||err)}</pre><button class="action" id="errorHome">Return Home</button>`;
    body.querySelector("#errorHome").onclick=closeWindow;
  }
}

document.addEventListener("click",e=>{const b=e.target.closest("[data-open]");if(b)launch(b.dataset.open)});
window.addEventListener("keydown",e=>{if(e.key==="Escape")closeWindow()});

(async()=>{
  appGrid();
  try{
    await kernel.boot();
    if("serviceWorker" in navigator) navigator.serviceWorker.register("./sw.js").catch(()=>{});
    setTimeout(()=>{$("#boot").remove();$("#os").classList.remove("hidden")},850);
  }catch(err){
    $(".boot-sub").textContent="Boot failed: "+err.message;
  }
})();
