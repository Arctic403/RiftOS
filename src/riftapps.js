const core=window.RiftOSCore;
if(!core)throw new Error("RiftOSCore must load before RiftApps");

const MAX_PACKAGE_BYTES=8*1024*1024;
const PROGRAM_ROOT="/C:/Programs";
const PROGRAM_DATA_ROOT="/C:/ProgramData";
const INSTALLER_ROOT=`${PROGRAM_DATA_ROOT}/Installer`;
const STAGING_ROOT=`${INSTALLER_ROOT}/staging`;
const ROLLBACK_ROOT=`${INSTALLER_ROOT}/rollback`;
const USER_APPDATA_ROOT="/D:/Users/Default/AppData";
const LEGACY_PACKAGE_ROOT="/apps/packages";
const LEGACY_DATA_ROOT="/apps/data";
const LEGACY_INDEXED_DB_KEY="riftapps.fs-migration-v1";
const DRIVE_LAYOUT_MIGRATION_KEY="riftapps.drive-layout-v1";
const ALLOWED_DECLARED_PERMISSIONS=new Set(["storage","fs.read","fs.write","network","clipboard.read","clipboard.write","share","notifications","build.local","repair.eval","software.eval","native.files","native.background"]);

const escapeHtml=value=>String(value??"").replace(/[&<>"']/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[ch]));
const normalizeAssetPath=value=>String(value||"").replace(/^\.\//,"").replace(/^\/+/,"").split("/").filter(part=>part&&part!=="."&&part!=="..").join("/");
const appRoot=id=>`${PROGRAM_ROOT}/${id}`;
const packagePath=id=>`${appRoot(id)}/package.json`;
const installPath=id=>`${appRoot(id)}/install.json`;
const appDataRoot=id=>`${USER_APPDATA_ROOT}/${id}`;
const dataPath=id=>`${appDataRoot(id)}/storage.json`;
const txId=()=>`${Date.now()}-${crypto.randomUUID?.()||Math.random().toString(36).slice(2)}`;

async function legacyRequest(req){return new Promise((resolve,reject)=>{let done=false;const finish=(ok,value)=>{if(done)return;done=true;clearTimeout(timer);req.onsuccess=null;req.onerror=null;ok?resolve(value):reject(value);};const timer=setTimeout(()=>finish(false,new Error("Legacy IndexedDB request timed out")),10000);req.onsuccess=()=>finish(true,req.result);req.onerror=()=>finish(false,req.error||new Error("Legacy IndexedDB request failed"));});}
async function openLegacyAppsDB(){
  return new Promise((resolve,reject)=>{
    const req=indexedDB.open("riftapps",1);let done=false;
    const finish=(ok,value)=>{if(done)return;done=true;clearTimeout(timer);req.onsuccess=null;req.onerror=null;req.onblocked=null;ok?resolve(value):reject(value);};
    const timer=setTimeout(()=>finish(false,new Error("Legacy IndexedDB open timed out")),10000);
    req.onupgradeneeded=()=>{const db=req.result;if(!db.objectStoreNames.contains("apps"))db.createObjectStore("apps",{keyPath:"id"});if(!db.objectStoreNames.contains("data"))db.createObjectStore("data",{keyPath:"key"});};
    req.onsuccess=()=>finish(true,req.result);req.onerror=()=>finish(false,req.error||new Error("Legacy IndexedDB open failed"));req.onblocked=()=>finish(false,new Error("Legacy IndexedDB open was blocked"));
  });
}

function validatePackage(pkg){
  if(!pkg||typeof pkg!=="object"||Array.isArray(pkg))throw new Error("Invalid .rift package.");
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
  const files={};let bytes=0;
  for(const [rawPath,rawContent] of Object.entries(pkg.files)){
    const path=normalizeAssetPath(rawPath);if(!path)continue;
    if(typeof rawContent!=="string")throw new Error(`RiftApp v1 file must be text: ${path}`);
    bytes+=new Blob([rawContent]).size;if(bytes>MAX_PACKAGE_BYTES)throw new Error(".rift package is larger than 8 MB.");files[path]=rawContent;
  }
  if(!(entry in files))throw new Error(`Entry file not found: ${entry}`);
  return {id:String(manifest.id),format:"rift-app-v1",manifest:{id:String(manifest.id),name:String(manifest.name).slice(0,80),version:String(manifest.version).slice(0,32),entry,icon:String(manifest.icon||"R").slice(0,8),description:String(manifest.description||"").slice(0,240),permissions},files,installedAt:Number(pkg.installedAt)||0,packageFormat:"rift-app-v1"};
}

class RiftAppRegistry{
  constructor(){this.readyPromise=null;}
  async init(){
    if(this.readyPromise)return this.readyPromise;
    this.readyPromise=(async()=>{
      await core.ready;
      for(const path of [PROGRAM_ROOT,PROGRAM_DATA_ROOT,INSTALLER_ROOT,STAGING_ROOT,ROLLBACK_ROOT,USER_APPDATA_ROOT])await core.fs.mkdir(path);
      await this.migrateIndexedDb();
      await this.migrateLegacyLayout();
      return this;
    })();
    return this.readyPromise;
  }
  async installAtomic(app,{migration=false}={}){
    const id=app.id,now=Date.now(),existing=await core.fs.readJSON(packagePath(id),null).catch(()=>null),tx=txId();
    const stage=`${STAGING_ROOT}/${id}-${tx}`,backup=`${ROLLBACK_ROOT}/${id}-${tx}`;
    const installed={...app,installedAt:Number(existing?.installedAt)||Number(app.installedAt)||now,updatedAt:now,packageFormat:"rift-app-v1",install:{layout:"rift-drive-v1",programPath:appRoot(id),dataPath:appDataRoot(id),migration:migration===true}};
    await core.fs.mkdir(stage);
    await core.fs.writeJSON(`${stage}/package.json`,installed);
    await core.fs.writeJSON(`${stage}/install.json`,{format:"rift-install-v1",id,version:installed.manifest.version,installedAt:installed.installedAt,updatedAt:installed.updatedAt,programPath:appRoot(id),dataPath:appDataRoot(id)});
    let backedUp=false;
    try{
      if(await core.fs.stat(appRoot(id))){await core.fs.move(appRoot(id),backup);backedUp=true;}
      await core.fs.move(stage,appRoot(id));
      if(backedUp)await core.fs.remove(backup).catch(()=>{});
      return installed;
    }catch(error){
      await core.fs.remove(stage).catch(()=>{});
      if(backedUp){
        await core.fs.remove(appRoot(id)).catch(()=>{});
        await core.fs.move(backup,appRoot(id)).catch(()=>{});
      }
      throw error;
    }
  }
  async migrateIndexedDb(){
    if((await core.fs.setting(LEGACY_INDEXED_DB_KEY))?.value)return;
    let apps=[],data=[];
    try{const db=await openLegacyAppsDB();if(db.objectStoreNames.contains("apps"))apps=await legacyRequest(db.transaction("apps").objectStore("apps").getAll());if(db.objectStoreNames.contains("data"))data=await legacyRequest(db.transaction("data").objectStore("data").getAll());}catch(error){console.warn("[RiftApps] IndexedDB migration skipped",error);}
    let migrated=0;
    for(const raw of apps){try{const app=validatePackage({format:"rift-app-v1",manifest:raw.manifest,files:raw.files,installedAt:raw.installedAt});if(!(await core.fs.stat(appRoot(app.id)))){await this.installAtomic(app,{migration:true});migrated++;}}catch(error){console.warn("[RiftApps] failed IndexedDB app migration",raw?.id,error);}}
    const grouped=new Map();for(const row of data){const [id,...keyParts]=String(row.key||"").split(":");if(!id||!keyParts.length)continue;const obj=grouped.get(id)||{};obj[keyParts.join(":")]=row.value;grouped.set(id,obj);}
    for(const [id,obj] of grouped){if(!(await core.fs.stat(dataPath(id)))){await core.fs.mkdir(appDataRoot(id));await core.fs.writeJSON(dataPath(id),obj);}}
    await core.fs.setSetting(LEGACY_INDEXED_DB_KEY,{at:Date.now(),apps:migrated});
  }
  async migrateLegacyLayout(){
    if((await core.fs.setting(DRIVE_LAYOUT_MIGRATION_KEY))?.value)return;
    let apps=0,data=0;
    for(const row of await core.fs.list(LEGACY_PACKAGE_ROOT,{recursive:true}).catch(()=>[])){
      if(row.kind!=="file"||!row.path.endsWith("/package.json"))continue;
      try{const raw=await core.fs.readJSON(row.path,null);if(!raw)continue;const app=validatePackage(raw);if(!(await core.fs.stat(appRoot(app.id)))){await this.installAtomic(app,{migration:true});apps++;}}catch(error){console.warn("[RiftApps] old /apps package migration failed",row.path,error);}
    }
    for(const row of await core.fs.list(LEGACY_DATA_ROOT,{recursive:true}).catch(()=>[])){
      if(row.kind!=="file"||!row.path.endsWith("/storage.json"))continue;
      const parts=row.path.split("/").filter(Boolean),id=parts[2];if(!id)continue;
      try{if(!(await core.fs.stat(dataPath(id)))){const value=await core.fs.readJSON(row.path,{});await core.fs.mkdir(appDataRoot(id));await core.fs.writeJSON(dataPath(id),value);data++;}}catch(error){console.warn("[RiftApps] old /apps data migration failed",row.path,error);}
    }
    await core.fs.setSetting(DRIVE_LAYOUT_MIGRATION_KEY,{at:Date.now(),apps,data,legacyRetained:true});
  }
  async list(){await this.init();const rows=await core.fs.list(PROGRAM_ROOT,{recursive:true}),apps=[];for(const row of rows){if(row.kind!=="file"||!row.path.endsWith("/package.json"))continue;const app=await core.fs.readJSON(row.path,null);if(app?.id)apps.push(app);}return apps.sort((a,b)=>a.manifest.name.localeCompare(b.manifest.name));}
  async get(id){await this.init();return core.fs.readJSON(packagePath(id),null);}
  async put(app){await this.init();return this.installAtomic(app);}
  async remove(id){await this.init();await core.fs.remove(appRoot(id)).catch(()=>{});await core.fs.remove(appDataRoot(id)).catch(()=>{});core.kernel.unregisterApp(id);return true;}
  async dataObject(id){await this.init();return core.fs.readJSON(dataPath(id),{});}
  async dataGet(id,key){return (await this.dataObject(id))[key];}
  async dataSet(id,key,value){const data=await this.dataObject(id);data[key]=value;const encoded=JSON.stringify(data);if(encoded.length>1024*1024)throw new Error("App storage exceeds 1 MB.");await core.fs.mkdir(appDataRoot(id));await core.fs.writeJSON(dataPath(id),data);return true;}
  async dataRemove(id,key){const data=await this.dataObject(id);delete data[key];await core.fs.mkdir(appDataRoot(id));await core.fs.writeJSON(dataPath(id),data);return true;}
}

const registry=new RiftAppRegistry();
let overlay=null;

function injectStyles(){
  if(document.querySelector("#riftAppsStyle"))return;
  const style=document.createElement("style");style.id="riftAppsStyle";style.textContent=`
    .rift-app-overlay{position:fixed;inset:0;z-index:5000;background:rgba(4,7,11,.76);backdrop-filter:blur(18px);padding:max(10px,env(safe-area-inset-top)) 10px max(10px,env(safe-area-inset-bottom));display:grid;place-items:center}
    .rift-app-overlay.hidden{display:none}.rift-app-shell{width:min(900px,100%);height:min(760px,100%);display:flex;flex-direction:column;background:#0b1017;border:1px solid #334053;border-radius:16px;overflow:hidden;box-shadow:0 25px 80px rgba(0,0,0,.55)}
    .rift-app-shell>header{display:flex;align-items:center;gap:10px;padding:10px 12px;border-bottom:1px solid #273242}.rift-app-shell>header>div{flex:1}.rift-app-shell>header small,.rift-app-shell>header strong{display:block}.rift-app-shell>header small{color:#8090a4;font:10px system-ui;letter-spacing:.12em}.rift-app-shell>header button{width:34px;height:34px;border-radius:50%;border:1px solid #3a4657;background:#151e2a;color:white;font-size:22px}
    .rift-app-body{flex:1;min-height:0;overflow:auto;-webkit-overflow-scrolling:touch}.rift-app-manager{padding:14px}.rift-app-hero{display:flex;gap:12px;align-items:flex-start;flex-wrap:wrap;padding:14px;border:1px solid #2a3544;border-radius:14px;background:#0f1620}.rift-app-hero>div:first-child{flex:1;min-width:210px}.rift-app-hero p{color:#9aa8b8}.rift-app-actions{display:flex;gap:8px;flex-wrap:wrap}.rift-app-action,.rift-app-list button{border:1px solid #39475a;background:#1a2533;color:#f5f8fc;border-radius:9px;padding:8px 10px;font:700 12px system-ui}.rift-app-action.secondary{background:#111923}.rift-app-list{display:grid;gap:8px;margin-top:10px}.rift-app-list article{display:grid;grid-template-columns:auto 1fr auto;gap:10px;align-items:center;padding:11px;border:1px solid #293544;border-radius:12px;background:#0d131b}.rift-app-list article small,.rift-app-list article p{display:block;color:#91a0b2;margin:2px 0}.rift-app-icon{width:40px;height:40px;display:grid;place-items:center;border-radius:11px;background:#172232;font-weight:800}.rift-app-list .danger{color:#ff9b9b}.rift-app-empty{padding:20px;color:#8f9daf;text-align:center}.rift-app-format{margin-top:12px}.rift-app-format pre{white-space:pre-wrap;background:#06090d;padding:12px;border-radius:10px}
  `;document.head.append(style);
}
function ensureOverlay(){if(overlay)return overlay;injectStyles();overlay=document.createElement("section");overlay.className="rift-app-overlay hidden";overlay.innerHTML=`<div class="rift-app-shell"><header><div><small>RIFT PROGRAM MANAGER</small><strong id="riftAppTitle">Programs</strong></div><button type="button" data-rift-close>×</button></header><div class="rift-app-body" id="riftAppBody"></div></div>`;document.body.append(overlay);overlay.querySelector("[data-rift-close]").onclick=closeOverlay;return overlay;}
function showOverlay(title){const el=ensureOverlay();el.classList.remove("hidden");el.querySelector("#riftAppTitle").textContent=title;return el.querySelector("#riftAppBody");}
function closeOverlay(){if(!overlay)return;overlay.classList.add("hidden");overlay.querySelector("#riftAppBody").innerHTML="";}

async function installPackageObject(pkg){const app=validatePackage(pkg),installed=await registry.put(app);core.kernel.registerApp({id:installed.id,name:installed.manifest.name,installed:true,permissions:installed.manifest.permissions});await refreshLauncher();return installed;}
async function installPackageFile(file){if(!file)throw new Error("No package selected.");if(!String(file.name||"").toLowerCase().endsWith(".rift"))throw new Error("Selected file is not a .rift package.");if(file.size>MAX_PACKAGE_BYTES)throw new Error(".rift package is larger than 8 MB.");let parsed;try{parsed=JSON.parse(await file.text());}catch{throw new Error(".rift v1 packages are JSON containers.");}return installPackageObject(parsed);}
async function launchInstalled(id){const app=await registry.get(id);if(!app)throw new Error(`RiftApp not installed: ${id}`);closeOverlay();if(!globalThis.RiftRT?.launch)throw new Error("RiftRT native application runtime is unavailable");return globalThis.RiftRT.launch(id);}

const demoPackage={format:"rift-app-v1",manifest:{id:"demo.hello",name:"Hello Rift",version:"1.0.0",entry:"index.html",icon:"✦",description:"First locally installed RiftApp.",permissions:["storage"]},files:{"index.html":"<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font-family:system-ui;background:#0b0f16;color:#f5f7fb;margin:0;padding:28px}</style></head><body><h1>Hello from a native RiftOS app surface ✦</h1><p>This program was installed under C:\\Programs and launched by RiftRT.</p></body></html>"}};

async function openManager(){
  const body=showOverlay("Programs"),apps=await registry.list();
  body.innerHTML=`<section class="rift-app-manager"><div class="rift-app-hero"><div><strong>RiftOS Programs</strong><p>.rift files are installer packages. Installation is transactional into C:\\Programs. User app state lives separately under D:\\Users\\Default\\AppData so upgrades do not overwrite personal state.</p></div><div class="rift-app-actions"><label class="rift-app-action">Install .rift<input type="file" id="riftPackageInput" accept=".rift,*/*" hidden></label><button class="rift-app-action secondary" id="riftDemoInstall">Install demo</button></div></div><div class="rift-app-list">${apps.length?apps.map(app=>`<article><div class="rift-app-icon">${escapeHtml(app.manifest.icon||"R")}</div><div><strong>${escapeHtml(app.manifest.name)}</strong><small>${escapeHtml(app.id)} · v${escapeHtml(app.manifest.version)}</small><p>${escapeHtml(app.manifest.description||"Installed RiftOS program")}</p></div><div><button data-rift-launch="${escapeHtml(app.id)}">Open</button><button class="danger" data-rift-remove="${escapeHtml(app.id)}">Uninstall</button></div></article>`).join(""):`<div class="rift-app-empty">No user programs installed.</div>`}</div><details class="rift-app-format"><summary>Program layout</summary><pre>C:\\Programs\\&lt;app-id&gt;     installed executable/package payload
D:\\Users\\Default\\AppData\\&lt;app-id&gt;     user state
C:\\ProgramData\\Installer     transactional staging/rollback</pre></details></section>`;
  body.querySelector("#riftPackageInput").onchange=async event=>{try{await installPackageFile(event.target.files?.[0]);await openManager();}catch(error){alert(`Install failed: ${error.message}`);}};
  body.querySelector("#riftDemoInstall").onclick=async()=>{try{await installPackageObject(demoPackage);await openManager();}catch(error){alert(`Demo install failed: ${error?.message||error}`);}};
  body.querySelectorAll("[data-rift-launch]").forEach(button=>button.onclick=()=>launchInstalled(button.dataset.riftLaunch).catch(error=>alert(`Open failed: ${error?.message||error}`)));
  body.querySelectorAll("[data-rift-remove]").forEach(button=>button.onclick=async()=>{const appId=button.dataset.riftRemove;if(!confirm(`Uninstall ${appId}? Program files and this app's D: AppData will be removed.`))return;try{globalThis.RiftRT?.close?.(appId);await registry.remove(appId);await refreshLauncher();await openManager();}catch(error){alert(`Uninstall failed: ${error?.message||error}`);}});
}

async function refreshLauncher(){const grid=document.querySelector("#appGrid");if(!grid)return;grid.querySelectorAll("[data-rift-installed-app],[data-rift-app-manager]").forEach(node=>node.remove());const manager=document.createElement("button");manager.className="app-card";manager.dataset.riftAppManager="1";manager.innerHTML='<span class="app-icon">＋</span><span><strong>Programs</strong><br><small>Install / manage RiftOS programs</small></span>';manager.onclick=()=>openManager();grid.append(manager);for(const app of await registry.list()){core.kernel.registerApp({id:app.id,name:app.manifest.name,installed:true,permissions:app.manifest.permissions});const button=document.createElement("button");button.className="app-card";button.dataset.riftInstalledApp=app.id;button.innerHTML=`<span class="app-icon">${escapeHtml(app.manifest.icon||"R")}</span><span><strong>${escapeHtml(app.manifest.name)}</strong><br><small>${escapeHtml(app.manifest.description||"Installed RiftOS program")}</small></span>`;button.onclick=()=>launchInstalled(app.id);grid.append(button);}}

window.addEventListener("riftos:launcher-ready",()=>refreshLauncher().catch(console.error));
document.addEventListener("DOMContentLoaded",()=>refreshLauncher().catch(console.error));
window.RiftApps=Object.freeze({installPackageObject,installPackageFile,list:()=>registry.list(),get:id=>registry.get(id),remove:id=>registry.remove(id),launch:launchInstalled,openManager,close:closeOverlay,refreshLauncher,paths:Object.freeze({programRoot:PROGRAM_ROOT,userAppDataRoot:USER_APPDATA_ROOT,installerRoot:INSTALLER_ROOT})});
registry.init().then(refreshLauncher).catch(error=>console.error("[RiftApps] program registry init failed",error));
console.info("[RiftApps] C:/Programs registry ready; imported .rift packages are installers, not iframe payloads");