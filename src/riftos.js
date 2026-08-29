const APPS = [
  {id:"files",name:"Files",icon:"▣",desc:"Persistent RiftFS"},
  {id:"terminal",name:"RiftShell",icon:">_",desc:"System command shell"},
  {id:"browser",name:"RiftBrowser",icon:"◎",desc:"Web transport v0.2"},
  {id:"riftengine",name:"RiftEngine",icon:"◉",desc:"Local WASM WebKit experiment"},
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
  const body=openWindow("browser","RiftBrowser","WEB / V0.2");
  body.innerHTML=`
    <div class="browser rift-browser">
      <div class="browser-chrome">
        <form class="browser-bar" id="browserForm">
          <button type="button" class="browser-icon" id="browserBack" aria-label="Back">‹</button>
          <button type="button" class="browser-icon" id="browserHome" aria-label="Home">⌂</button>
          <input id="browserAddress" aria-label="Search or enter address" placeholder="Search Google or enter a URL" autocomplete="off" autocapitalize="off" spellcheck="false">
          <button type="submit">Go</button>
        </form>
        <div class="browser-status">
          <span id="browserState">Ready</span>
          <button class="browser-link" id="browserExternal" type="button">Open in Safari ↗</button>
        </div>
      </div>
      <div class="browser-surface" id="browserSurface"></div>
    </div>`;

  const form=body.querySelector("#browserForm");
  const input=body.querySelector("#browserAddress");
  const surface=body.querySelector("#browserSurface");
  const state=body.querySelector("#browserState");
  const external=body.querySelector("#browserExternal");
  const back=body.querySelector("#browserBack");
  const home=body.querySelector("#browserHome");

  const history=[];
  let historyIndex=-1;
  let currentUrl="";

  const esc=s=>String(s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
  const isProbablyUrl=value=>/^(https?:\/\/|localhost(?::\d+)?(?:\/|$)|(?:[\w-]+\.)+[a-z]{2,}(?:[/:?#]|$))/i.test(value.trim());

  function resolveInput(value){
    const v=value.trim();
    if(!v) return {type:"home"};
    if(isProbablyUrl(v)){
      let url=v;
      if(!/^https?:\/\//i.test(url)) url="https://"+url;
      return {type:"url",url};
    }
    return {type:"search",url:"https://www.google.com/search?q="+encodeURIComponent(v),query:v};
  }

  function setState(text,busy=false){
    state.textContent=text;
    state.classList.toggle("busy",busy);
  }

  function renderHome(){
    currentUrl="";
    input.value="";
    setState("RiftBrowser ready");
    surface.innerHTML=`
      <section class="browser-start">
        <div class="browser-logo">R</div>
        <h2>RiftBrowser</h2>
        <p>Search the web or enter an address above.</p>
        <div class="browser-quick">
          <button data-query="GitHub">GitHub</button>
          <button data-query="RiftCity">RiftCity</button>
          <button data-query="OpenAI">OpenAI</button>
        </div>
        <div class="browser-capability">
          <strong>v0.2 web transport</strong>
          <span>RiftOS can directly render CORS-enabled pages. Sites that block browser-to-browser fetching or embedding can be handed to Safari until the Rift remote transport is connected.</span>
        </div>
      </section>`;
    surface.querySelectorAll("[data-query]").forEach(btn=>{
      btn.onclick=()=>{input.value=btn.dataset.query; navigate(input.value)};
    });
  }

  function renderBlocked(url,reason){
    currentUrl=url;
    setState("Site requires external/remote transport");
    surface.innerHTML=`
      <section class="browser-blocked">
        <div class="browser-warning">↗</div>
        <h2>This site won't render directly inside RiftOS yet.</h2>
        <p>${esc(reason||"The destination blocks cross-origin fetching or embedding.")}</p>
        <code>${esc(url)}</code>
        <div class="browser-block-actions">
          <button class="action" id="blockedExternal">Open in Safari</button>
          <button class="action secondary" id="blockedTryFrame">Try embedded view</button>
        </div>
        <small>This is a web-platform security boundary, not a RiftOS crash. The planned remote transport will sit behind RiftBrowser and remove the iframe dependency.</small>
      </section>`;
    surface.querySelector("#blockedExternal").onclick=()=>openExternal(url);
    surface.querySelector("#blockedTryFrame").onclick=()=>renderFrame(url);
  }

  function renderFrame(url){
    currentUrl=url;
    setState("Embedded compatibility mode");
    surface.innerHTML=`
      <div class="browser-frame-wrap">
        <div class="browser-frame-hint">If the page below stays blank, the site blocks iframe embedding. <button id="frameExternal">Open externally</button></div>
        <iframe class="webview" referrerpolicy="no-referrer" sandbox="allow-forms allow-scripts allow-same-origin allow-popups" src="${esc(url)}"></iframe>
      </div>`;
    surface.querySelector("#frameExternal").onclick=()=>openExternal(url);
  }

  function sanitizeDocument(text,url){
    const doc=new DOMParser().parseFromString(text,"text/html");
    doc.querySelectorAll("script,object,embed,applet,meta[http-equiv='refresh']").forEach(n=>n.remove());
    doc.querySelectorAll("iframe").forEach(n=>n.remove());
    for(const el of doc.querySelectorAll("*")){
      for(const attr of [...el.attributes]){
        if(/^on/i.test(attr.name)) el.removeAttribute(attr.name);
      }
    }
    const base=doc.createElement("base");
    base.href=url;
    doc.head.prepend(base);
    const style=doc.createElement("style");
    style.textContent="html,body{max-width:100%;overflow-wrap:anywhere}img,video{max-width:100%;height:auto}";
    doc.head.append(style);
    return "<!doctype html>"+doc.documentElement.outerHTML;
  }

  async function renderDirect(url){
    currentUrl=url;
    input.value=url;
    setState("Fetching directly…",true);
    surface.innerHTML=`<div class="browser-loading"><i></i><span>Connecting to ${esc(new URL(url).hostname)}…</span></div>`;
    try{
      const controller=new AbortController();
      const timer=setTimeout(()=>controller.abort(),9000);
      const res=await fetch(url,{method:"GET",mode:"cors",redirect:"follow",credentials:"omit",signal:controller.signal});
      clearTimeout(timer);
      if(!res.ok) throw new Error(`HTTP ${res.status}`);
      const type=(res.headers.get("content-type")||"").toLowerCase();
      if(!type.includes("text/html") && !type.includes("text/plain") && !type.includes("application/xhtml+xml")){
        throw new Error(`Unsupported direct content type: ${type||"unknown"}`);
      }
      const text=await res.text();
      const srcdoc=sanitizeDocument(text,res.url||url);
      surface.innerHTML=`<iframe class="webview direct-view" sandbox="allow-forms allow-popups" referrerpolicy="no-referrer"></iframe>`;
      const frame=surface.querySelector("iframe");
      frame.srcdoc=srcdoc;
      setState(`Direct · ${new URL(res.url||url).hostname}`);
      frame.addEventListener("load",()=>{
        try{
          const fdoc=frame.contentDocument;
          if(!fdoc) return;
          fdoc.addEventListener("click",ev=>{
            const a=ev.target.closest?.("a[href]");
            if(!a) return;
            const href=a.href;
            if(/^https?:/i.test(href)){
              ev.preventDefault();
              navigate(href);
            }
          });
        }catch(_){}
      },{once:true});
      return true;
    }catch(err){
      const reason=err?.name==="AbortError" ? "The direct request timed out." :
        "The site did not allow RiftOS to fetch its page directly (usually CORS), or returned content the lightweight renderer cannot safely display.";
      renderBlocked(url,reason);
      return false;
    }
  }

  function openExternal(url=currentUrl){
    if(!url) return;
    const w=window.open(url,"_blank","noopener,noreferrer");
    if(!w) location.href=url;
  }

  async function navigate(value,push=true){
    const target=resolveInput(value);
    if(target.type==="home"){renderHome();return}
    currentUrl=target.url;
    input.value=target.type==="search" ? target.query : target.url;
    if(push){
      history.splice(historyIndex+1);
      history.push({value:input.value,url:target.url});
      historyIndex=history.length-1;
    }
    back.disabled=historyIndex<=0;

    if(target.type==="search"){
      setState("Google search requires external/remote transport");
      surface.innerHTML=`
        <section class="browser-search-fallback">
          <div class="browser-logo small">G</div>
          <h2>Search Google</h2>
          <p>Google blocks the iframe/direct-fetch tricks a static GitHub Pages app can use. RiftBrowser can hand this search to Safari now; the remote transport phase will bring it back inside RiftOS.</p>
          <button class="action" id="searchExternal">Search “${esc(target.query)}” ↗</button>
          <button class="action secondary" id="searchFrame">Try Google embedded anyway</button>
        </section>`;
      surface.querySelector("#searchExternal").onclick=()=>openExternal(target.url);
      surface.querySelector("#searchFrame").onclick=()=>renderFrame(target.url);
      return;
    }

    await renderDirect(target.url);
  }

  form.onsubmit=e=>{e.preventDefault();navigate(input.value)};
  external.onclick=()=>currentUrl ? openExternal(currentUrl) : null;
  home.onclick=renderHome;
  back.onclick=()=>{
    if(historyIndex<=0) return;
    historyIndex--;
    const item=history[historyIndex];
    input.value=item.value;
    back.disabled=historyIndex<=0;
    if(isProbablyUrl(item.value)) renderDirect(item.url);
    else navigate(item.value,false);
  };

  renderHome();
}


function openRiftEngine(){
  const body=openWindow("riftengine","RiftEngine","WASM WEBKIT / EXPERIMENT");
  body.innerHTML=`
    <div class="engine-lab">
      <section class="engine-head">
        <div>
          <strong>Local browser-engine experiment</strong>
          <p>Attempts to boot a non-pthread WebKit/WASM build entirely on this device. No remote desktop or remote browser rendering.</p>
        </div>
        <span class="engine-badge" id="engineBadge">CHECKING</span>
      </section>
      <div class="engine-actions">
        <button class="action" id="engineBoot">Boot embedded engine</button>
        <button class="action secondary" id="engineDemo">Load local demo</button>
        <button class="action secondary" id="engineReload">Re-check assets</button>
      </div>
      <div class="engine-diagnostics" id="engineDiagnostics"></div>
      <div class="engine-viewport" id="engineViewport">
        <canvas id="riftEngineCanvas" tabindex="0"></canvas>
        <div class="engine-empty" id="engineEmpty">
          <strong>RiftEngine viewport</strong>
          <span>The WebKit/WASM build will paint here once the generated engine assets are present.</span>
        </div>
      </div>
      <pre class="engine-log" id="engineLog"></pre>
    </div>`;

  const badge=body.querySelector("#engineBadge");
  const diag=body.querySelector("#engineDiagnostics");
  const log=body.querySelector("#engineLog");
  const canvas=body.querySelector("#riftEngineCanvas");
  const empty=body.querySelector("#engineEmpty");
  const boot=body.querySelector("#engineBoot");
  const demo=body.querySelector("#engineDemo");
  const reload=body.querySelector("#engineReload");
  let moduleInstance=null;

  const write=(msg)=>{log.textContent+=`[${new Date().toLocaleTimeString()}] ${msg}\n`;log.scrollTop=log.scrollHeight};
  const asset="./riftengine/engine/webcore.js";

  async function check(){
    badge.textContent="CHECKING";
    const rows=[
      ["WebAssembly",typeof WebAssembly==="object"],
      ["WebGL2",!!canvas.getContext("webgl2")],
      ["SharedArrayBuffer",typeof SharedArrayBuffer!=="undefined"],
      ["crossOriginIsolated",self.crossOriginIsolated===true],
      ["OPFS",!!navigator.storage?.getDirectory]
    ];
    let engine=false;
    try{
      const r=await fetch(asset,{method:"HEAD",cache:"no-store"});
      engine=r.ok;
    }catch(_){}
    rows.push(["Generated WebKit engine",engine]);
    diag.innerHTML=rows.map(([k,v])=>`<div><span>${k}</span><b class="${v?"ok":"no"}">${v?"YES":"NO"}</b></div>`).join("");
    badge.textContent=engine?"ENGINE READY":"BUILD NEEDED";
    badge.classList.toggle("ok",engine);
    write(engine
      ?"Generated engine loader found. Ready to attempt local boot."
      :"Engine binary is not in this snapshot yet. The included GitHub Actions experiment can build the non-pthread WebKit/WASM assets; RiftOS itself remains static.");
    return engine;
  }

  async function bootEngine(){
    if(moduleInstance){write("Engine already booted.");return}
    if(!(await check())){write("Boot stopped: generated engine assets are missing.");return}
    badge.textContent="BOOTING";
    boot.disabled=true;
    try{
      const mod=await import("../riftengine/engine/webcore.js");
      const factory=mod.default||mod.createWebCoreModule||window.createWebCoreModule;
      if(typeof factory!=="function") throw new Error("Engine module factory was not exported in the expected Emscripten shape.");
      moduleInstance=await factory({
        canvas,
        locateFile:(name)=>new URL(`../riftengine/engine/${name}`,import.meta.url).href,
        print:(t)=>write(String(t)),
        printErr:(t)=>write("ERR "+String(t))
      });
      empty.classList.add("hidden");
      badge.textContent="RUNNING";
      badge.classList.add("ok");
      write("WebKit/WASM module initialized locally.");
    }catch(err){
      badge.textContent="BOOT ERROR";
      write(String(err?.stack||err));
    }finally{boot.disabled=false}
  }

  demo.onclick=()=>{
    write("Local demo requested. This validates the RiftOS viewport/input shell; real page loading is enabled after the engine build is wired to its embedder API.");
    empty.innerHTML="<strong>Local renderer shell is alive.</strong><span>Next checkpoint: WebKit paints this canvas itself.</span>";
  };
  boot.onclick=bootEngine;
  reload.onclick=check;

  const forwardPointer=(ev)=>{
    canvas.focus();
    // Reserved adapter point for WebKit embedder mouse/touch events.
    canvas.dataset.lastPointer=`${ev.type}:${Math.round(ev.offsetX)},${Math.round(ev.offsetY)}`;
  };
  ["pointerdown","pointermove","pointerup"].forEach(type=>canvas.addEventListener(type,forwardPointer,{passive:true}));
  check();
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
    if(id==="riftengine")return openRiftEngine();
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
