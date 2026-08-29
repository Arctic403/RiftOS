const DB_NAME = "riftapps";
const DB_VERSION = 1;
const MAX_PACKAGE_BYTES = 8 * 1024 * 1024;
const MANAGER_ID = "rift-apps";

const escapeHtml = value => String(value ?? "").replace(/[&<>"']/g, ch => ({
  "&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"
}[ch]));

function request(req){
  return new Promise((resolve,reject)=>{
    req.onsuccess=()=>resolve(req.result);
    req.onerror=()=>reject(req.error);
  });
}

class RiftAppRegistry {
  constructor(){ this.db = null; }

  async init(){
    if(this.db) return this.db;
    this.db = await new Promise((resolve,reject)=>{
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded=()=>{
        const db=req.result;
        if(!db.objectStoreNames.contains("apps")) db.createObjectStore("apps",{keyPath:"id"});
        if(!db.objectStoreNames.contains("data")) db.createObjectStore("data",{keyPath:"key"});
      };
      req.onsuccess=()=>resolve(req.result);
      req.onerror=()=>reject(req.error);
    });
    return this.db;
  }

  store(name,mode="readonly"){
    return this.db.transaction(name,mode).objectStore(name);
  }

  async list(){ await this.init(); return request(this.store("apps").getAll()); }
  async get(id){ await this.init(); return request(this.store("apps").get(id)); }
  async put(app){ await this.init(); return request(this.store("apps","readwrite").put(app)); }
  async remove(id){
    await this.init();
    await request(this.store("apps","readwrite").delete(id));
    const all=await request(this.store("data").getAllKeys());
    const tx=this.db.transaction("data","readwrite");
    const store=tx.objectStore("data");
    for(const key of all){ if(String(key).startsWith(id+":")) store.delete(key); }
  }
  async dataGet(appId,key){ await this.init(); return request(this.store("data").get(`${appId}:${key}`)); }
  async dataSet(appId,key,value){ await this.init(); return request(this.store("data","readwrite").put({key:`${appId}:${key}`,value,modified:Date.now()})); }
  async dataRemove(appId,key){ await this.init(); return request(this.store("data","readwrite").delete(`${appId}:${key}`)); }
}

const registry = new RiftAppRegistry();
const instances = new Map();
let overlay = null;
let refreshQueued = false;

function normalizePath(path){
  return String(path||"").replace(/^\.\//,"").replace(/^\//,"");
}

function validatePackage(pkg){
  if(!pkg || typeof pkg!=="object") throw new Error("Invalid .rift package.");
  if(pkg.format!=="rift-app-v1") throw new Error("Unsupported package format. Expected rift-app-v1.");
  const manifest=pkg.manifest;
  if(!manifest || typeof manifest!=="object") throw new Error("Package manifest is missing.");
  if(!/^[a-z0-9][a-z0-9._-]{1,63}$/i.test(manifest.id||"")) throw new Error("Manifest id must be 2-64 safe characters.");
  if(!String(manifest.name||"").trim()) throw new Error("Manifest name is required.");
  if(!String(manifest.version||"").trim()) throw new Error("Manifest version is required.");
  const entry=normalizePath(manifest.entry||"index.html");
  if(!pkg.files || typeof pkg.files!=="object" || typeof pkg.files[entry]!=="string") throw new Error(`Entry file not found: ${entry}`);
  const permissions=Array.isArray(manifest.permissions)?manifest.permissions:[];
  const allowed=new Set(["storage"]);
  for(const permission of permissions){ if(!allowed.has(permission)) throw new Error(`Unsupported permission: ${permission}`); }
  return {
    id:manifest.id,
    manifest:{
      id:manifest.id,
      name:String(manifest.name).slice(0,80),
      version:String(manifest.version).slice(0,32),
      entry,
      icon:String(manifest.icon||"R").slice(0,8),
      description:String(manifest.description||"").slice(0,240),
      permissions
    },
    files:pkg.files,
    installedAt:Date.now(),
    packageFormat:"rift-app-v1"
  };
}

async function installPackageObject(pkg){
  const app=validatePackage(pkg);
  await registry.put(app);
  await refreshLauncher();
  return app;
}

async function installPackageFile(file){
  if(!file) throw new Error("No package selected.");
  if(file.size>MAX_PACKAGE_BYTES) throw new Error(".rift package is larger than 8 MB.");
  const text=await file.text();
  let parsed;
  try{ parsed=JSON.parse(text); }catch{ throw new Error(".rift v1 packages are JSON containers."); }
  return installPackageObject(parsed);
}

function injectBridge(html, app, token){
  const manifest=JSON.stringify(app.manifest).replace(/</g,"\\u003c");
  const bridge=`<script>(()=>{const TOKEN=${JSON.stringify(token)};const MANIFEST=${manifest};let seq=0;const pending=new Map();function call(method,args={}){return new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});parent.postMessage({__riftApp:true,token:TOKEN,id,method,args},"*");});}addEventListener("message",e=>{const m=e.data;if(!m||m.__riftHost!==true||m.token!==TOKEN)return;const p=pending.get(m.id);if(!p)return;pending.delete(m.id);m.ok?p.resolve(m.value):p.reject(new Error(m.error||"Rift host error"));});Object.defineProperty(window,"Rift",{value:Object.freeze({app:Object.freeze({info:()=>MANIFEST,close:()=>call("app.close")}),storage:Object.freeze({get:key=>call("storage.get",{key}),set:(key,value)=>call("storage.set",{key,value}),remove:key=>call("storage.remove",{key})})}),writable:false});parent.postMessage({__riftApp:true,token:TOKEN,method:"app.ready",args:{}},"*");})();<\/script>`;
  if(/<\/head>/i.test(html)) return html.replace(/<\/head>/i,bridge+"</head>");
  return bridge+html;
}

function materializeHtml(app, token){
  let html=app.files[app.manifest.entry];
  const urls=[];
  const resolveAsset=(raw)=>{
    if(!raw || /^(data:|blob:|https?:|#|\/\/)/i.test(raw)) return raw;
    const clean=normalizePath(raw.split(/[?#]/)[0]);
    if(!(clean in app.files)) return raw;
    const ext=clean.split(".").pop()?.toLowerCase();
    const types={js:"text/javascript",mjs:"text/javascript",css:"text/css",json:"application/json",svg:"image/svg+xml",png:"image/png",jpg:"image/jpeg",jpeg:"image/jpeg",webp:"image/webp",txt:"text/plain"};
    const blob=new Blob([app.files[clean]],{type:types[ext]||"text/plain"});
    const url=URL.createObjectURL(blob); urls.push(url); return url;
  };
  html=html.replace(/\b(src|href)=(['"])([^'"]+)\2/gi,(m,attr,q,value)=>`${attr}=${q}${resolveAsset(value)}${q}`);
  return {html:injectBridge(html,app,token),urls};
}

async function handleAppMessage(event){
  const msg=event.data;
  if(!msg || msg.__riftApp!==true || !msg.token) return;
  const instance=instances.get(msg.token);
  if(!instance || event.source!==instance.frame.contentWindow) return;
  if(msg.method==="app.ready") return;
  const respond=(ok,value,error)=>instance.frame.contentWindow?.postMessage({__riftHost:true,token:msg.token,id:msg.id,ok,value,error},"*");
  try{
    const app=instance.app;
    const key=String(msg.args?.key??"").slice(0,160);
    let value=null;
    if(msg.method==="app.close"){
      closeOverlay();
    }else if(msg.method==="storage.get"){
      if(!app.manifest.permissions.includes("storage")) throw new Error("storage permission not granted");
      value=(await registry.dataGet(app.id,key))?.value ?? null;
    }else if(msg.method==="storage.set"){
      if(!app.manifest.permissions.includes("storage")) throw new Error("storage permission not granted");
      const encoded=JSON.stringify(msg.args?.value);
      if(encoded && encoded.length>262144) throw new Error("Storage value exceeds 256 KB.");
      await registry.dataSet(app.id,key,msg.args?.value);
      value=true;
    }else if(msg.method==="storage.remove"){
      if(!app.manifest.permissions.includes("storage")) throw new Error("storage permission not granted");
      await registry.dataRemove(app.id,key); value=true;
    }else{
      throw new Error(`Unknown Rift API method: ${msg.method}`);
    }
    respond(true,value,null);
  }catch(err){ respond(false,null,String(err?.message||err)); }
}
window.addEventListener("message",handleAppMessage);

function ensureOverlay(){
  if(overlay) return overlay;
  overlay=document.createElement("section");
  overlay.className="rift-app-overlay hidden";
  overlay.innerHTML=`<div class="rift-app-shell"><header><div><small>RIFT APP RUNTIME</small><strong id="riftAppTitle">Rift Apps</strong></div><button type="button" data-rift-close>×</button></header><div class="rift-app-body" id="riftAppBody"></div></div>`;
  document.body.append(overlay);
  overlay.querySelector("[data-rift-close]").onclick=closeOverlay;
  overlay.addEventListener("click",e=>{ if(e.target===overlay) closeOverlay(); });
  return overlay;
}

function showOverlay(title){
  const el=ensureOverlay();
  el.classList.remove("hidden");
  el.querySelector("#riftAppTitle").textContent=title;
  return el.querySelector("#riftAppBody");
}

function closeOverlay(){
  if(!overlay) return;
  for(const [token,instance] of instances){
    if(instance.frame.closest(".rift-app-overlay")===overlay){
      instance.urls.forEach(URL.revokeObjectURL);
      instances.delete(token);
    }
  }
  overlay.classList.add("hidden");
  overlay.querySelector("#riftAppBody").innerHTML="";
}

async function launchInstalled(id){
  const app=await registry.get(id);
  if(!app) throw new Error(`RiftApp not installed: ${id}`);
  const body=showOverlay(app.manifest.name);
  const token=crypto.randomUUID?.() || `${Date.now()}-${Math.random()}`;
  const materialized=materializeHtml(app,token);
  body.innerHTML=`<iframe class="rift-app-frame" title="${escapeHtml(app.manifest.name)}" sandbox="allow-scripts allow-forms allow-modals"></iframe>`;
  const frame=body.querySelector("iframe");
  instances.set(token,{app,frame,urls:materialized.urls});
  frame.srcdoc=materialized.html;
}

const demoPackage={
  format:"rift-app-v1",
  manifest:{id:"demo.hello",name:"Hello Rift",version:"1.0.0",entry:"index.html",icon:"✦",description:"First locally installed RiftApp.",permissions:["storage"]},
  files:{
    "index.html":"<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font-family:system-ui;background:#0b0f16;color:#f5f7fb;margin:0;padding:28px}button{font:inherit;border:0;border-radius:12px;padding:12px 16px;margin:6px 6px 6px 0}input{font:inherit;padding:12px;border-radius:10px;border:1px solid #3a4352;background:#111723;color:white;width:min(320px,90%)}</style></head><body><h1>Hello from a .rift app ✦</h1><p>This page is running in its own sandbox inside RiftOS.</p><input id='name' placeholder='Type something'><div><button id='save'>Save locally</button><button id='load'>Load</button><button id='close'>Close app</button></div><pre id='out'></pre><script>const out=document.querySelector('#out');document.querySelector('#save').onclick=async()=>{await Rift.storage.set('demo',document.querySelector('#name').value);out.textContent='Saved inside this app sandbox.'};document.querySelector('#load').onclick=async()=>{out.textContent='Stored: '+await Rift.storage.get('demo')};document.querySelector('#close').onclick=()=>Rift.app.close();<\/script></body></html>"
  }
};

async function openManager(){
  const body=showOverlay("Rift Apps");
  const apps=await registry.list();
  body.innerHTML=`
    <section class="rift-app-manager">
      <div class="rift-app-hero"><div><strong>Install apps inside RiftOS</strong><p>.rift v1 packages live locally and launch in isolated sandbox contexts. They do not install native iOS or Android code.</p></div><div class="rift-app-actions"><label class="rift-app-action">Install .rift<input type="file" id="riftPackageInput" accept=".rift,application/json" hidden></label><button class="rift-app-action secondary" id="riftDemoInstall">Install demo</button></div></div>
      <div class="rift-app-list">${apps.length?apps.map(app=>`<article><div class="rift-app-icon">${escapeHtml(app.manifest.icon||"R")}</div><div><strong>${escapeHtml(app.manifest.name)}</strong><small>${escapeHtml(app.id)} · v${escapeHtml(app.manifest.version)}</small><p>${escapeHtml(app.manifest.description||"Installed RiftApp")}</p></div><div><button data-rift-launch="${escapeHtml(app.id)}">Open</button><button class="danger" data-rift-remove="${escapeHtml(app.id)}">Remove</button></div></article>`).join(""):'<div class="rift-app-empty">No RiftApps installed yet.</div>'}</div>
      <details class="rift-app-format"><summary>.rift v1 format</summary><pre>{\n  \"format\": \"rift-app-v1\",\n  \"manifest\": {\n    \"id\": \"my.app\",\n    \"name\": \"My App\",\n    \"version\": \"1.0.0\",\n    \"entry\": \"index.html\",\n    \"permissions\": [\"storage\"]\n  },\n  \"files\": { \"index.html\": \"...\" }\n}</pre></details>
    </section>`;
  body.querySelector("#riftPackageInput").onchange=async e=>{
    try{ const app=await installPackageFile(e.target.files?.[0]); await openManager(); flash(`Installed ${app.manifest.name}`); }
    catch(err){ alert(`Install failed: ${err.message}`); }
  };
  body.querySelector("#riftDemoInstall").onclick=async()=>{ const app=await installPackageObject(demoPackage); await openManager(); flash(`Installed ${app.manifest.name}`); };
  body.querySelectorAll("[data-rift-launch]").forEach(btn=>btn.onclick=()=>launchInstalled(btn.dataset.riftLaunch));
  body.querySelectorAll("[data-rift-remove]").forEach(btn=>btn.onclick=async()=>{ await registry.remove(btn.dataset.riftRemove); await refreshLauncher(); await openManager(); });
}

function flash(text){
  const status=document.querySelector("#statusText");
  if(!status) return;
  const old=status.textContent; status.textContent=text; setTimeout(()=>{ if(status.textContent===text) status.textContent=old; },1400);
}

function injectStyles(){
  if(document.querySelector("#riftAppsStyle")) return;
  const style=document.createElement("style");
  style.id="riftAppsStyle";
  style.textContent=`
  .rift-app-overlay{position:fixed;inset:0;z-index:5000;background:rgba(4,7,11,.76);backdrop-filter:blur(18px);padding:max(env(safe-area-inset-top),12px) 12px max(env(safe-area-inset-bottom),12px);display:flex;align-items:stretch;justify-content:center}.rift-app-overlay.hidden{display:none}.rift-app-shell{width:min(980px,100%);height:100%;background:#0b0f16;border:1px solid #273141;border-radius:20px;overflow:hidden;display:flex;flex-direction:column;box-shadow:0 30px 90px #0009}.rift-app-shell>header{height:62px;display:flex;align-items:center;justify-content:space-between;padding:0 18px;border-bottom:1px solid #222b39;background:#101620}.rift-app-shell>header div{display:flex;flex-direction:column}.rift-app-shell>header small{font-size:10px;letter-spacing:.16em;color:#7e8b9f}.rift-app-shell>header strong{font-size:16px;color:#f5f7fb}.rift-app-shell>header button{width:38px;height:38px;border:0;border-radius:12px;background:#202937;color:#fff;font-size:24px}.rift-app-body{flex:1;min-height:0;overflow:auto;background:#0b0f16}.rift-app-frame{border:0;width:100%;height:100%;background:white}.rift-app-manager{padding:18px;color:#ecf1f8}.rift-app-hero{display:flex;gap:18px;align-items:flex-start;justify-content:space-between;padding:18px;border:1px solid #273141;border-radius:18px;background:#111824}.rift-app-hero strong{font-size:20px}.rift-app-hero p{color:#9ca8b8;max-width:620px;line-height:1.45}.rift-app-actions{display:flex;gap:8px;flex-wrap:wrap}.rift-app-action,.rift-app-list button{border:0;border-radius:11px;background:#e8eef8;color:#10141b;font:inherit;font-weight:700;padding:10px 13px;cursor:pointer}.rift-app-action.secondary,.rift-app-list button.danger{background:#202a38;color:#dce5f2}.rift-app-list{display:grid;gap:10px;margin-top:14px}.rift-app-list article{display:grid;grid-template-columns:48px 1fr auto;gap:12px;align-items:center;padding:14px;border:1px solid #222c3b;border-radius:16px;background:#0e141e}.rift-app-list article small,.rift-app-list article p{display:block;color:#8e9aac;margin:4px 0 0}.rift-app-list article p{font-size:13px}.rift-app-list article>div:last-child{display:flex;gap:7px}.rift-app-icon{width:46px;height:46px;border-radius:14px;background:#202a38;display:grid;place-items:center;font-size:22px}.rift-app-empty{padding:30px;text-align:center;color:#8e9aac;border:1px dashed #2a3545;border-radius:16px}.rift-app-format{margin-top:16px;color:#aab5c5}.rift-app-format pre{white-space:pre-wrap;overflow:auto;background:#080b10;padding:14px;border-radius:12px}.rift-installed-badge{font-size:9px;letter-spacing:.08em;color:#78d49b}@media(max-width:640px){.rift-app-hero{flex-direction:column}.rift-app-list article{grid-template-columns:42px 1fr}.rift-app-list article>div:last-child{grid-column:1/-1}.rift-app-shell{border-radius:16px}}
  `;
  document.head.append(style);
}

async function refreshLauncher(){
  if(refreshQueued) return;
  refreshQueued=true;
  queueMicrotask(async()=>{
    refreshQueued=false;
    const grid=document.querySelector("#appGrid");
    if(!grid) return;
    grid.querySelectorAll("[data-rift-generated]").forEach(el=>el.remove());
    const manager=document.createElement("button");
    manager.className="app-card"; manager.dataset.riftManager="1"; manager.dataset.riftGenerated="1";
    manager.innerHTML=`<span class="app-icon">⬡</span><span><strong>Rift Apps</strong><br><small>Install & manage .rift apps</small></span>`;
    grid.append(manager);
    const apps=await registry.list();
    for(const app of apps){
      const card=document.createElement("button");
      card.className="app-card"; card.dataset.riftLaunch=app.id; card.dataset.riftGenerated="1";
      card.innerHTML=`<span class="app-icon">${escapeHtml(app.manifest.icon||"R")}</span><span><strong>${escapeHtml(app.manifest.name)}</strong><br><small><span class="rift-installed-badge">RIFTAPP</span> · ${escapeHtml(app.manifest.description||app.id)}</small></span>`;
      grid.append(card);
    }
  });
}

function watchLauncher(){
  const grid=document.querySelector("#appGrid");
  if(!grid){ setTimeout(watchLauncher,80); return; }
  const observer=new MutationObserver(mutations=>{
    if(mutations.some(m=>[...m.addedNodes].some(n=>n.nodeType===1 && !n.hasAttribute?.("data-rift-generated")))) refreshLauncher();
  });
  observer.observe(grid,{childList:true});
  refreshLauncher();
}

document.addEventListener("click",event=>{
  const manager=event.target.closest?.("[data-rift-manager]");
  const launch=event.target.closest?.("[data-rift-launch]");
  if(manager){ event.preventDefault(); event.stopImmediatePropagation(); openManager(); }
  else if(launch){ event.preventDefault(); event.stopImmediatePropagation(); launchInstalled(launch.dataset.riftLaunch).catch(err=>alert(err.message)); }
},true);

injectStyles();
registry.init().then(watchLauncher).catch(err=>console.error("[RiftApps] boot failed",err));

window.RiftApps=Object.freeze({
  format:"rift-app-v1",
  list:()=>registry.list(),
  install:installPackageObject,
  launch:launchInstalled,
  remove:id=>registry.remove(id),
  openManager
});
