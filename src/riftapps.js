const core=window.RiftOSCore;
if(!core)throw new Error("RiftOSCore must load before RiftApps");

const MAX_PACKAGE_BYTES=8*1024*1024;
const PACKAGE_ROOT="/apps/packages";
const DATA_ROOT="/apps/data";
const LEGACY_MIGRATION_KEY="riftapps.fs-migration-v1";
const ALLOWED_DECLARED_PERMISSIONS=new Set(["storage","network","clipboard.read","clipboard.write","share","notifications","native.files"]);

const escapeHtml=value=>String(value??"").replace(/[&<>"']/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[ch]));
const normalizeAssetPath=value=>String(value||"").replace(/^\.\//,"").replace(/^\/+/,"").split("/").filter(part=>part&&part!=="."&&part!=="..").join("/");
const appRoot=id=>`${PACKAGE_ROOT}/${id}`;
const packagePath=id=>`${appRoot(id)}/package.json`;
const dataPath=id=>`${DATA_ROOT}/${id}/storage.json`;

async function legacyRequest(req){return new Promise((resolve,reject)=>{req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);});}
async function openLegacyAppsDB(){
  return new Promise((resolve,reject)=>{
    const req=indexedDB.open("riftapps",1);
    req.onupgradeneeded=()=>{
      const db=req.result;
      if(!db.objectStoreNames.contains("apps"))db.createObjectStore("apps",{keyPath:"id"});
      if(!db.objectStoreNames.contains("data"))db.createObjectStore("data",{keyPath:"key"});
    };
    req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);
  });
}

function validatePackage(pkg){
  if(!pkg||typeof pkg!=="object")throw new Error("Invalid .rift package.");
  if(pkg.format!=="rift-app-v1")throw new Error("Unsupported package format. Expected rift-app-v1.");
  const manifest=pkg.manifest;
  if(!manifest||typeof manifest!=="object")throw new Error("Package manifest is missing.");
  if(!/^[a-z0-9][a-z0-9._-]{1,63}$/i.test(manifest.id||""))throw new Error("Manifest id must be 2-64 safe characters.");
  if(!String(manifest.name||"").trim())throw new Error("Manifest name is required.");
  if(!String(manifest.version||"").trim())throw new Error("Manifest version is required.");
  const entry=normalizeAssetPath(manifest.entry||"index.html");
  if(!pkg.files||typeof pkg.files!=="object"||typeof pkg.files[entry]!=="string")throw new Error(`Entry file not found: ${entry}`);
  const permissions=[...new Set(Array.isArray(manifest.permissions)?manifest.permissions.map(String):[])];
  for(const permission of permissions)if(!ALLOWED_DECLARED_PERMISSIONS.has(permission))throw new Error(`Unsupported permission: ${permission}`);
  const files={};
  let bytes=0;
  for(const [rawPath,rawContent] of Object.entries(pkg.files)){
    const path=normalizeAssetPath(rawPath);
    if(!path)continue;
    if(typeof rawContent!=="string")throw new Error(`RiftApp v1 file must be text: ${path}`);
    bytes+=new Blob([rawContent]).size;
    if(bytes>MAX_PACKAGE_BYTES)throw new Error(".rift package is larger than 8 MB.");
    files[path]=rawContent;
  }
  if(!(entry in files))throw new Error(`Entry file not found: ${entry}`);
  return {
    id:String(manifest.id),
    format:"rift-app-v1",
    manifest:{
      id:String(manifest.id),name:String(manifest.name).slice(0,80),version:String(manifest.version).slice(0,32),entry,
      icon:String(manifest.icon||"R").slice(0,8),description:String(manifest.description||"").slice(0,240),permissions
    },
    files,installedAt:Number(pkg.installedAt)||Date.now(),packageFormat:"rift-app-v1"
  };
}

class RiftAppRegistry{
  constructor(){this.readyPromise=null;}
  async init(){
    if(this.readyPromise)return this.readyPromise;
    this.readyPromise=(async()=>{
      await core.ready;await core.fs.mkdir(PACKAGE_ROOT);await core.fs.mkdir(DATA_ROOT);await this.migrateLegacy();return this;
    })();
    return this.readyPromise;
  }
  async migrateLegacy(){
    if((await core.fs.setting(LEGACY_MIGRATION_KEY))?.value)return;
    let apps=[],data=[];
    try{
      const db=await openLegacyAppsDB();
      if(db.objectStoreNames.contains("apps"))apps=await legacyRequest(db.transaction("apps").objectStore("apps").getAll());
      if(db.objectStoreNames.contains("data"))data=await legacyRequest(db.transaction("data").objectStore("data").getAll());
    }catch(error){console.warn("[RiftApps] legacy migration skipped",error);}
    let migrated=0;
    for(const app of apps){
      try{
        if(!(await core.fs.get(packagePath(app.id)))){await core.fs.writeJSON(packagePath(app.id),validatePackage({format:"rift-app-v1",manifest:app.manifest,files:app.files,installedAt:app.installedAt}));migrated++;}
      }catch(error){console.warn("[RiftApps] failed to migrate app",app?.id,error);}
    }
    const grouped=new Map();
    for(const row of data){
      const [id,...keyParts]=String(row.key||"").split(":");if(!id||!keyParts.length)continue;
      const key=keyParts.join(":"),obj=grouped.get(id)||{};obj[key]=row.value;grouped.set(id,obj);
    }
    for(const [id,obj] of grouped)if(!(await core.fs.get(dataPath(id))))await core.fs.writeJSON(dataPath(id),obj);
    await core.fs.setSetting(LEGACY_MIGRATION_KEY,{at:Date.now(),apps:migrated});
  }
  async list(){
    await this.init();
    const rows=await core.fs.list(PACKAGE_ROOT,{recursive:true}),apps=[];
    for(const row of rows){
      if(row.kind!=="file"||!row.path.endsWith("/package.json"))continue;
      const app=await core.fs.readJSON(row.path,null);if(app?.id)apps.push(app);
    }
    return apps.sort((a,b)=>a.manifest.name.localeCompare(b.manifest.name));
  }
  async get(id){await this.init();return core.fs.readJSON(packagePath(id),null);}
  async put(app){await this.init();await core.fs.mkdir(appRoot(app.id));await core.fs.writeJSON(packagePath(app.id),app);return app;}
  async remove(id){await this.init();await core.fs.remove(appRoot(id)).catch(()=>{});await core.fs.remove(`${DATA_ROOT}/${id}`).catch(()=>{});core.kernel.unregisterApp(id);return true;}
  async dataObject(id){await this.init();return core.fs.readJSON(dataPath(id),{});}
  async dataGet(id,key){return (await this.dataObject(id))[key];}
  async dataSet(id,key,value){
    const data=await this.dataObject(id);data[key]=value;
    const encoded=JSON.stringify(data);if(encoded.length>1024*1024)throw new Error("App storage exceeds 1 MB.");
    await core.fs.writeJSON(dataPath(id),data);return true;
  }
  async dataRemove(id,key){const data=await this.dataObject(id);delete data[key];await core.fs.writeJSON(dataPath(id),data);return true;}
}

const registry=new RiftAppRegistry();
const instances=new Map();
let overlay=null;

function capabilityForPermission(permission){
  if(permission==="storage")return null;
  return permission;
}
async function requirePermission(app,permission){
  if(permission==="storage")return true;
  if(!app.manifest.permissions.includes(permission))throw new Error(`${permission} is not declared by this app`);
  const capability=capabilityForPermission(permission);
  if(await core.permissions.has(app.id,capability))return true;
  if(!(await core.permissions.request(app.id,capability,app.manifest.name)))throw new Error(`${permission} permission denied`);
  return true;
}

function injectContentSecurityPolicy(html,app){
  const canNetwork=app.manifest.permissions.includes("network");
  const policy=[
    "default-src 'none'",
    "script-src 'unsafe-inline' blob:",
    "style-src 'unsafe-inline' blob:",
    `img-src data: blob:${canNetwork?" https: http:":""}`,
    `font-src data: blob:${canNetwork?" https: http:":""}`,
    `connect-src ${canNetwork?"https: http:":"'none'"}`,
    `media-src blob: data:${canNetwork?" https: http:":""}`,
    "frame-src 'none'"
  ].join("; ");
  const meta=`<meta http-equiv="Content-Security-Policy" content="${policy.replace(/"/g,"&quot;")}">`;
  return /<head[^>]*>/i.test(html)?html.replace(/<head([^>]*)>/i,`<head$1>${meta}`):meta+html;
}

function injectBridge(html,app,token){
  const manifest=JSON.stringify(app.manifest).replace(/</g,"\\u003c");
  const bridge=`<script>(()=>{const TOKEN=${JSON.stringify(token)},MANIFEST=${manifest};let seq=0;const pending=new Map();function call(method,args={}){return new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});parent.postMessage({__riftApp:true,token:TOKEN,id,method,args},"*");});}addEventListener("message",event=>{const msg=event.data;if(!msg||msg.__riftHost!==true||msg.token!==TOKEN)return;const p=pending.get(msg.id);if(!p)return;pending.delete(msg.id);msg.ok?p.resolve(msg.value):p.reject(new Error(msg.error||"Rift host error"));});Object.defineProperty(window,"Rift",{value:Object.freeze({app:Object.freeze({info:()=>MANIFEST,close:()=>call("app.close")}),storage:Object.freeze({get:key=>call("storage.get",{key}),set:(key,value)=>call("storage.set",{key,value}),remove:key=>call("storage.remove",{key})}),permissions:Object.freeze({request:name=>call("permissions.request",{permission:name})}),clipboard:Object.freeze({readText:()=>call("clipboard.readText"),writeText:text=>call("clipboard.writeText",{text})}),share:Object.freeze({text:text=>call("share.text",{text})}),notifications:Object.freeze({request:()=>call("notifications.request"),schedule:options=>call("notifications.schedule",options||{})})}),writable:false});parent.postMessage({__riftApp:true,token:TOKEN,method:"app.ready",args:{}},"*");})();<\\/script>`;
  html=injectContentSecurityPolicy(html,app);
  if(/<\/head>/i.test(html))return html.replace(/<\/head>/i,bridge+"</head>");
  return bridge+html;
}

function materializeHtml(app,token){
  let html=app.files[app.manifest.entry],urls=[];
  const resolveAsset=raw=>{
    if(!raw||/^(data:|blob:|https?:|#|\/\/)/i.test(raw))return raw;
    const clean=normalizeAssetPath(raw.split(/[?#]/)[0]);if(!(clean in app.files))return raw;
    const ext=clean.split(".").pop()?.toLowerCase(),types={js:"text/javascript",mjs:"text/javascript",css:"text/css",json:"application/json",svg:"image/svg+xml",png:"image/png",jpg:"image/jpeg",jpeg:"image/jpeg",webp:"image/webp",txt:"text/plain"};
    const url=URL.createObjectURL(new Blob([app.files[clean]],{type:types[ext]||"text/plain"}));urls.push(url);return url;
  };
  html=html.replace(/\b(src|href)=(['"])([^'"]+)\2/gi,(match,attr,q,value)=>`${attr}=${q}${resolveAsset(value)}${q}`);
  return {html:injectBridge(html,app,token),urls};
}

async function fallbackClipboardRead(){if(!navigator.clipboard?.readText)throw new Error("Clipboard read unavailable");return navigator.clipboard.readText();}
async function fallbackClipboardWrite(text){if(!navigator.clipboard?.writeText)throw new Error("Clipboard write unavailable");await navigator.clipboard.writeText(String(text));return true;}
async function shareText(text){
  if(core.native.connected)return core.native.call("share.text",{text:String(text)});
  if(!navigator.share)throw new Error("Share sheet unavailable");await navigator.share({text:String(text)});return true;
}

async function handleAppMessage(event){
  const msg=event.data;if(!msg||msg.__riftApp!==true||!msg.token)return;
  const instance=instances.get(msg.token);if(!instance||event.source!==instance.frame.contentWindow)return;
  if(msg.method==="app.ready")return;
  const respond=(ok,value,error)=>instance.frame.contentWindow?.postMessage({__riftHost:true,token:msg.token,id:msg.id,ok,value,error},"*");
  try{
    const app=instance.app,args=msg.args||{};let value=null;
    if(msg.method==="app.close"){closeOverlay();}
    else if(msg.method==="storage.get"){await requirePermission(app,"storage");value=await registry.dataGet(app.id,String(args.key??"").slice(0,160))??null;}
    else if(msg.method==="storage.set"){
      await requirePermission(app,"storage");const key=String(args.key??"").slice(0,160),encoded=JSON.stringify(args.value);
      if(encoded&&encoded.length>262144)throw new Error("Storage value exceeds 256 KB.");value=await registry.dataSet(app.id,key,args.value);
    }
    else if(msg.method==="storage.remove"){await requirePermission(app,"storage");value=await registry.dataRemove(app.id,String(args.key??"").slice(0,160));}
    else if(msg.method==="permissions.request"){
      const permission=String(args.permission||"");if(!app.manifest.permissions.includes(permission))throw new Error(`${permission} is not declared by this app`);
      value=await requirePermission(app,permission);
    }
    else if(msg.method==="clipboard.readText"){await requirePermission(app,"clipboard.read");value=core.native.connected?await core.native.call("clipboard.readText",{}):await fallbackClipboardRead();}
    else if(msg.method==="clipboard.writeText"){await requirePermission(app,"clipboard.write");value=core.native.connected?await core.native.call("clipboard.writeText",{text:String(args.text??"")}):await fallbackClipboardWrite(args.text??"");}
    else if(msg.method==="share.text"){await requirePermission(app,"share");value=await shareText(args.text??"");}
    else if(msg.method==="notifications.request"){
      await requirePermission(app,"notifications");
      if(core.native.connected)value=await core.native.call("notifications.request",{});
      else if("Notification" in window)value=(await Notification.requestPermission())==="granted";
      else throw new Error("Notifications unavailable");
    }
    else if(msg.method==="notifications.schedule"){
      await requirePermission(app,"notifications");
      if(!core.native.connected)throw new Error("Scheduled notifications require RiftOS Native");
      value=await core.native.call("notifications.schedule",{title:String(args.title||app.manifest.name),body:String(args.body||""),seconds:Number(args.seconds||1)});
    }
    else throw new Error(`Unknown Rift API method: ${msg.method}`);
    respond(true,value,null);
  }catch(error){respond(false,null,String(error?.message||error));}
}
window.addEventListener("message",handleAppMessage);

function injectStyles(){
  if(document.querySelector("#riftAppsStyle"))return;
  const style=document.createElement("style");style.id="riftAppsStyle";style.textContent=`
    .rift-app-overlay{position:fixed;inset:0;z-index:5000;background:rgba(4,7,11,.76);backdrop-filter:blur(18px);padding:max(10px,env(safe-area-inset-top)) 10px max(10px,env(safe-area-inset-bottom));display:grid;place-items:center}
    .rift-app-overlay.hidden{display:none}.rift-app-shell{width:min(900px,100%);height:min(760px,100%);display:flex;flex-direction:column;background:#0b1017;border:1px solid #334053;border-radius:16px;overflow:hidden;box-shadow:0 25px 80px rgba(0,0,0,.55)}
    .rift-app-shell>header{display:flex;align-items:center;gap:10px;padding:10px 12px;border-bottom:1px solid #273242}.rift-app-shell>header>div{flex:1}.rift-app-shell>header small,.rift-app-shell>header strong{display:block}.rift-app-shell>header small{color:#8090a4;font:10px system-ui;letter-spacing:.12em}.rift-app-shell>header button{width:34px;height:34px;border-radius:50%;border:1px solid #3a4657;background:#151e2a;color:white;font-size:22px}
    .rift-app-body{flex:1;min-height:0;overflow:auto;-webkit-overflow-scrolling:touch}.rift-app-frame{display:block;width:100%;height:100%;border:0;background:white}
    .rift-app-manager{padding:14px}.rift-app-hero{display:flex;gap:12px;align-items:flex-start;flex-wrap:wrap;padding:14px;border:1px solid #2a3544;border-radius:14px;background:#0f1620}.rift-app-hero>div:first-child{flex:1;min-width:210px}.rift-app-hero p{color:#9aa8b8}.rift-app-actions{display:flex;gap:8px;flex-wrap:wrap}.rift-app-action,.rift-app-list button{border:1px solid #39475a;background:#1a2533;color:#f5f8fc;border-radius:9px;padding:8px 10px;font:700 12px system-ui}.rift-app-action.secondary{background:#111923}.rift-app-list{display:grid;gap:8px;margin-top:10px}.rift-app-list article{display:grid;grid-template-columns:auto 1fr auto;gap:10px;align-items:center;padding:11px;border:1px solid #293544;border-radius:12px;background:#0d131b}.rift-app-list article small,.rift-app-list article p{display:block;color:#91a0b2;margin:2px 0}.rift-app-icon{width:40px;height:40px;display:grid;place-items:center;border-radius:11px;background:#172232;font-weight:800}.rift-app-list .danger{color:#ff9b9b}.rift-app-empty{padding:20px;color:#8f9daf;text-align:center}.rift-app-format{margin-top:12px}.rift-app-format pre{white-space:pre-wrap;background:#06090d;padding:12px;border-radius:10px}
  `;document.head.append(style);
}
function ensureOverlay(){
  if(overlay)return overlay;injectStyles();
  overlay=document.createElement("section");overlay.className="rift-app-overlay hidden";
  overlay.innerHTML=`<div class="rift-app-shell"><header><div><small>RIFT APP RUNTIME</small><strong id="riftAppTitle">Rift Apps</strong></div><button type="button" data-rift-close>×</button></header><div class="rift-app-body" id="riftAppBody"></div></div>`;
  document.body.append(overlay);overlay.querySelector("[data-rift-close]").onclick=closeOverlay;return overlay;
}
function showOverlay(title){const el=ensureOverlay();el.classList.remove("hidden");el.querySelector("#riftAppTitle").textContent=title;return el.querySelector("#riftAppBody");}
function closeOverlay(){
  if(!overlay)return;
  for(const [token,instance] of [...instances]){
    instance.urls.forEach(URL.revokeObjectURL);core.kernel.kill(instance.process?.pid);instances.delete(token);
  }
  overlay.classList.add("hidden");overlay.querySelector("#riftAppBody").innerHTML="";
}

async function installPackageObject(pkg){
  const app=validatePackage(pkg);await registry.put(app);
  core.kernel.registerApp({id:app.id,name:app.manifest.name,installed:true,permissions:app.manifest.permissions});
  await refreshLauncher();return app;
}
async function installPackageFile(file){
  if(!file)throw new Error("No package selected.");if(file.size>MAX_PACKAGE_BYTES)throw new Error(".rift package is larger than 8 MB.");
  let parsed;try{parsed=JSON.parse(await file.text());}catch{throw new Error(".rift v1 packages are JSON containers.");}
  return installPackageObject(parsed);
}

async function launchInstalled(id){
  const app=await registry.get(id);if(!app)throw new Error(`RiftApp not installed: ${id}`);
  closeOverlay();
  const body=showOverlay(app.manifest.name),token=crypto.randomUUID?.()||`${Date.now()}-${Math.random()}`,materialized=materializeHtml(app,token);
  body.innerHTML=`<iframe class="rift-app-frame" title="${escapeHtml(app.manifest.name)}" sandbox="allow-scripts allow-forms allow-modals"></iframe>`;
  const frame=body.querySelector("iframe"),process=core.kernel.launchProcess(app.id,app.manifest.name,{kind:"rift-app"});
  instances.set(token,{app,frame,urls:materialized.urls,process});frame.srcdoc=materialized.html;
}

const demoPackage={
  format:"rift-app-v1",
  manifest:{id:"demo.hello",name:"Hello Rift",version:"1.0.0",entry:"index.html",icon:"✦",description:"First locally installed RiftApp.",permissions:["storage"]},
  files:{"index.html":"<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font-family:system-ui;background:#0b0f16;color:#f5f7fb;margin:0;padding:28px}button{font:inherit;border:0;border-radius:12px;padding:12px 16px;margin:6px 6px 6px 0}input{font:inherit;padding:12px;border-radius:10px;border:1px solid #3a4352;background:#111723;color:white;width:min(320px,90%)}</style></head><body><h1>Hello from a .rift app ✦</h1><p>This page runs in its own RiftOS sandbox.</p><input id='name' placeholder='Type something'><div><button id='save'>Save locally</button><button id='load'>Load</button><button id='close'>Close app</button></div><pre id='out'></pre><script>const out=document.querySelector('#out');document.querySelector('#save').onclick=async()=>{await Rift.storage.set('demo',document.querySelector('#name').value);out.textContent='Saved in RiftFS app storage.'};document.querySelector('#load').onclick=async()=>{out.textContent='Stored: '+await Rift.storage.get('demo')};document.querySelector('#close').onclick=()=>Rift.app.close();<\\/script></body></html>"}
};

async function openManager(){
  const body=showOverlay("Rift Apps"),apps=await registry.list();
  body.innerHTML=`<section class="rift-app-manager"><div class="rift-app-hero"><div><strong>Rift Apps</strong><p>.rift packages are stored under RiftFS /apps and launch in isolated sandbox contexts. Declared capabilities are granted on first use.</p></div><div class="rift-app-actions"><label class="rift-app-action">Import .rift<input type="file" id="riftPackageInput" accept=".rift,application/json" hidden></label><button class="rift-app-action secondary" id="riftDemoInstall">Install demo</button></div></div><div class="rift-app-list">${apps.length?apps.map(app=>`<article><div class="rift-app-icon">${escapeHtml(app.manifest.icon||"R")}</div><div><strong>${escapeHtml(app.manifest.name)}</strong><small>${escapeHtml(app.id)} · v${escapeHtml(app.manifest.version)}</small><p>${escapeHtml(app.manifest.description||"Installed RiftApp")}</p></div><div><button data-rift-launch="${escapeHtml(app.id)}">Open</button><button class="danger" data-rift-remove="${escapeHtml(app.id)}">Remove</button></div></article>`).join(""):`<div class="rift-app-empty">No RiftApps installed yet.</div>`}</div><details class="rift-app-format"><summary>.rift v1 permissions</summary><pre>storage
network
clipboard.read
clipboard.write
share
notifications
native.files</pre></details></section>`;
  body.querySelector("#riftPackageInput").onchange=async event=>{try{await installPackageFile(event.target.files?.[0]);await openManager();}catch(error){alert(`Install failed: ${error.message}`);}};
  body.querySelector("#riftDemoInstall").onclick=async()=>{await installPackageObject(demoPackage);await openManager();};
  body.querySelectorAll("[data-rift-launch]").forEach(button=>button.onclick=()=>launchInstalled(button.dataset.riftLaunch));
  body.querySelectorAll("[data-rift-remove]").forEach(button=>button.onclick=async()=>{await registry.remove(button.dataset.riftRemove);await refreshLauncher();await openManager();});
}

async function refreshLauncher(){
  const grid=document.querySelector("#appGrid");if(!grid)return;
  grid.querySelectorAll("[data-rift-installed-app],[data-rift-app-manager]").forEach(node=>node.remove());
  const manager=document.createElement("button");manager.className="app-card";manager.dataset.riftAppManager="1";
  manager.innerHTML='<span class="app-icon">＋</span><span><strong>Rift Apps</strong><br><small>Install / manage .rift apps</small></span>';manager.onclick=()=>openManager();grid.append(manager);
  for(const app of await registry.list()){
    core.kernel.registerApp({id:app.id,name:app.manifest.name,installed:true,permissions:app.manifest.permissions});
    const button=document.createElement("button");button.className="app-card";button.dataset.riftInstalledApp=app.id;
    button.innerHTML=`<span class="app-icon">${escapeHtml(app.manifest.icon||"R")}</span><span><strong>${escapeHtml(app.manifest.name)}</strong><br><small>${escapeHtml(app.manifest.description||"Installed RiftApp")}</small></span>`;
    button.onclick=()=>launchInstalled(app.id);grid.append(button);
  }
}

window.addEventListener("riftos:launcher-ready",()=>refreshLauncher().catch(console.error));
document.addEventListener("DOMContentLoaded",()=>refreshLauncher().catch(console.error));

window.RiftApps=Object.freeze({
  installPackageObject,installPackageFile,list:()=>registry.list(),get:id=>registry.get(id),remove:id=>registry.remove(id),
  launch:launchInstalled,openManager,close:closeOverlay,refreshLauncher
});

registry.init().then(refreshLauncher).catch(error=>console.error("[RiftApps] init failed",error));
console.info("[RiftApps] RiftFS package runtime ready");
