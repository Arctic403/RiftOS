const CORE_VERSION = "0.2.0-trueos";
const LEGACY_DB = "riftos";
const MIGRATION_KEY = "trueos.opfs-migration-v1";
const SYSTEM_APPS = [
  {id:"system",name:"RiftKernel",trusted:true,permissions:["fs.read","fs.write","process.manage","system.settings"]},
  {id:"desktop",name:"Rift Desktop",trusted:true,permissions:["fs.read","process.read"]},
  {id:"files",name:"Files",trusted:true,permissions:["fs.read","fs.write"]},
  {id:"editor",name:"Editor",trusted:true,permissions:["fs.read","fs.write"]},
  {id:"terminal",name:"RiftShell",trusted:true,permissions:["fs.read","fs.write","process.read","process.manage","native.read"]},
  {id:"riftdev",name:"RiftDev",trusted:true,permissions:["fs.read","fs.write","network","clipboard.write"]},
  {id:"browser",name:"RiftBrowser",trusted:true,permissions:["network"]},
  {id:"settings",name:"Settings",trusted:true,permissions:["system.settings","native.read"]},
  {id:"tasks",name:"Tasks",trusted:true,permissions:["process.read","process.manage"]}
];

const cleanPath = value => {
  const raw = String(value || "/").replace(/\\/g,"/");
  const parts=[];
  for(const part of raw.split("/")){
    if(!part || part === ".") continue;
    if(part === "..") parts.pop();
    else parts.push(part);
  }
  return "/" + parts.join("/");
};
const parentPath = path => {
  const p=cleanPath(path);const parts=p.split("/").filter(Boolean);parts.pop();return "/"+parts.join("/");
};
const basename = path => cleanPath(path).split("/").filter(Boolean).pop() || "";
const idbRequest = req => new Promise((resolve,reject)=>{req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error)});

class LegacyStore {
  constructor(){this.db=null;}
  async init(){
    if(this.db) return this.db;
    this.db=await new Promise((resolve,reject)=>{
      const req=indexedDB.open(LEGACY_DB,1);
      req.onupgradeneeded=()=>{
        const db=req.result;
        if(!db.objectStoreNames.contains("files")) db.createObjectStore("files",{keyPath:"path"});
        if(!db.objectStoreNames.contains("settings")) db.createObjectStore("settings",{keyPath:"key"});
      };
      req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);
    });
    return this.db;
  }
  store(name,mode="readonly"){return this.db.transaction(name,mode).objectStore(name);}
  async get(path){await this.init();return idbRequest(this.store("files").get(cleanPath(path)));}
  async write(path,content,modified=Date.now()){await this.init();return idbRequest(this.store("files","readwrite").put({path:cleanPath(path),content:String(content),modified}));}
  async remove(path){await this.init();return idbRequest(this.store("files","readwrite").delete(cleanPath(path)));}
  async list(){await this.init();return idbRequest(this.store("files").getAll());}
  async setting(key){await this.init();return idbRequest(this.store("settings").get(key));}
  async setSetting(key,value){await this.init();return idbRequest(this.store("settings","readwrite").put({key,value,modified:Date.now()}));}
}

class OPFSStore {
  constructor(){this.root=null;this.volume=null;}
  async init(){
    if(this.volume) return this.volume;
    if(!navigator.storage?.getDirectory) throw new Error("OPFS unavailable");
    this.root=await navigator.storage.getDirectory();
    this.volume=await this.root.getDirectoryHandle("riftos",{create:true});
    return this.volume;
  }
  async dir(path,create=false){
    await this.init();let current=this.volume;
    for(const part of cleanPath(path).split("/").filter(Boolean)) current=await current.getDirectoryHandle(part,{create});
    return current;
  }
  async fileHandle(path,create=false){
    const parent=await this.dir(parentPath(path),create);
    return parent.getFileHandle(basename(path),{create});
  }
  async get(path){
    try{const handle=await this.fileHandle(path,false);const file=await handle.getFile();return {path:cleanPath(path),content:await file.text(),modified:file.lastModified||0};}
    catch(err){if(err?.name==="NotFoundError") return null;throw err;}
  }
  async write(path,content){
    const handle=await this.fileHandle(path,true);const writable=await handle.createWritable();await writable.write(String(content));await writable.close();
    const file=await handle.getFile();return {path:cleanPath(path),content:String(content),modified:file.lastModified||Date.now()};
  }
  async mkdir(path){await this.dir(path,true);return cleanPath(path);}
  async remove(path){
    const p=cleanPath(path);if(p==="/") throw new Error("Cannot remove root volume");
    const parent=await this.dir(parentPath(p),false);
    try{await parent.removeEntry(basename(p),{recursive:true});}catch(err){if(err?.name!=="NotFoundError") throw err;}
  }
  async list(){
    await this.init();const out=[];
    const walk=async(dir,prefix="")=>{
      for await(const [name,handle] of dir.entries()){
        const rel=`${prefix}/${name}`;
        if(handle.kind==="directory") await walk(handle,rel);
        else{const file=await handle.getFile();out.push({path:cleanPath(rel),content:await file.text(),modified:file.lastModified||0});}
      }
    };
    await walk(this.volume,"");return out;
  }
}

class RiftFS2 {
  constructor(){this.legacy=new LegacyStore();this.opfs=new OPFSStore();this.opfsReady=false;this.ready=false;}
  async init(){
    if(this.ready) return this;
    await this.legacy.init();
    try{await this.opfs.init();this.opfsReady=true;}catch(err){console.warn("[RiftFS2] OPFS unavailable, using IndexedDB",err);}
    if(this.opfsReady) await this.migrateLegacy();
    if(!(await this.get("/home/readme.txt"))) await this.write("/home/readme.txt","Welcome to RiftOS True OS Core.\n\nRiftFS now prefers OPFS and mirrors IndexedDB for compatibility.");
    await this.mkdir("/home");await this.mkdir("/apps");await this.mkdir("/system");await this.mkdir("/mounts");
    this.ready=true;return this;
  }
  async migrateLegacy(){
    const done=(await this.legacy.setting(MIGRATION_KEY))?.value;
    if(done) return;
    const rows=await this.legacy.list();
    for(const row of rows){if(!(await this.opfs.get(row.path))) await this.opfs.write(row.path,row.content);}
    await this.legacy.setSetting(MIGRATION_KEY,{at:Date.now(),files:rows.length});
  }
  async get(path){
    const p=cleanPath(path);
    if(this.opfsReady){
      const op=await this.opfs.get(p);const legacy=await this.legacy.get(p);
      if(op&&legacy&&Number(legacy.modified||0)>Number(op.modified||0)){const next=await this.opfs.write(p,legacy.content);return {...next,content:legacy.content};}
      if(op){if(!legacy||Number(op.modified||0)>Number(legacy.modified||0)) await this.legacy.write(p,op.content,op.modified);return op;}
      if(legacy){await this.opfs.write(p,legacy.content);return legacy;}
      return null;
    }
    return this.legacy.get(p);
  }
  async write(path,content){
    const p=cleanPath(path);let record={path:p,content:String(content),modified:Date.now()};
    if(this.opfsReady) record=await this.opfs.write(p,content);
    await this.legacy.write(p,content,record.modified);return record;
  }
  async mkdir(path){if(this.opfsReady) return this.opfs.mkdir(path);return cleanPath(path);}
  async remove(path){const p=cleanPath(path);if(this.opfsReady) await this.opfs.remove(p);await this.legacy.remove(p);}
  async list(){
    const legacy=await this.legacy.list();if(!this.opfsReady) return legacy.sort((a,b)=>a.path.localeCompare(b.path));
    const opfs=await this.opfs.list();const all=new Map();
    for(const row of legacy) all.set(cleanPath(row.path),row);
    for(const row of opfs){
      const p=cleanPath(row.path),old=all.get(p);
      if(!old||Number(row.modified||0)>=Number(old.modified||0)){all.set(p,row);if(!old||Number(row.modified||0)>Number(old.modified||0)) await this.legacy.write(p,row.content,row.modified);}
      else await this.opfs.write(p,old.content);
    }
    for(const row of legacy){if(!opfs.some(o=>cleanPath(o.path)===cleanPath(row.path))) await this.opfs.write(row.path,row.content);}
    return [...all.values()].sort((a,b)=>a.path.localeCompare(b.path));
  }
  setting(key){return this.legacy.setting(key);}
  setSetting(key,value){return this.legacy.setSetting(key,value);}
  async estimate(){
    const e=await navigator.storage?.estimate?.()||{};return {usage:Number(e.usage||0),quota:Number(e.quota||0),backend:this.opfsReady?"OPFS + IndexedDB mirror":"IndexedDB compatibility"};
  }
  async persist(){return navigator.storage?.persist?navigator.storage.persist():false;}
}

class ProcessTable extends EventTarget {
  constructor(){super();this.seq=99;this.items=new Map();}
  spawn(appId,name,details={}){const pid=++this.seq;const proc={pid,appId,name,state:"running",started:Date.now(),...details};this.items.set(pid,proc);this.dispatchEvent(new Event("change"));return proc;}
  kill(pid){pid=Number(pid);const proc=this.items.get(pid);if(!proc||proc.protected) return false;proc.state="terminated";proc.ended=Date.now();this.items.delete(pid);this.dispatchEvent(new Event("change"));return true;}
  list(){return [...this.items.values()].sort((a,b)=>a.pid-b.pid);}
}

class PermissionBroker {
  constructor(fs){this.fs=fs;this.system=new Map(SYSTEM_APPS.map(app=>[app.id,new Set(app.permissions||[])]));}
  async grants(appId){return (await this.fs.setting(`permissions:${appId}`))?.value||[];}
  async has(appId,capability){if(this.system.get(appId)?.has(capability)) return true;return (await this.grants(appId)).includes(capability);}
  async grant(appId,capability){const set=new Set(await this.grants(appId));set.add(capability);await this.fs.setSetting(`permissions:${appId}`,[...set].sort());return true;}
  async revoke(appId,capability){const set=new Set(await this.grants(appId));set.delete(capability);await this.fs.setSetting(`permissions:${appId}`,[...set].sort());return true;}
  describe(){return ["fs.read","fs.write","network","clipboard.read","clipboard.write","notifications","share","process.read","process.manage","system.settings","native.read","native.files","native.background"];}
}

class RiftNativeBridge extends EventTarget {
  constructor(){super();this.seq=0;this.pending=new Map();this.timeout=30000;}
  get connected(){return !!window.webkit?.messageHandlers?.riftNative;}
  capabilities(){return {
    nativeHost:this.connected,
    opfs:!!navigator.storage?.getDirectory,
    share:!!navigator.share,
    notifications:"Notification" in window,
    clipboard:!!navigator.clipboard,
    serviceWorker:"serviceWorker" in navigator,
    backgroundSync:"serviceWorker" in navigator && "SyncManager" in window
  };}
  call(method,args={}){
    if(!this.connected) return Promise.reject(new Error("RiftNative host is not connected. RiftOS is running in web/PWA mode."));
    const id=`rn-${Date.now()}-${++this.seq}`;
    return new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error(`RiftNative timeout: ${method}`));},this.timeout);
      this.pending.set(id,{resolve,reject,timer});
      window.webkit.messageHandlers.riftNative.postMessage({id,method,args});
    });
  }
  resolve(id,ok,value,error){const p=this.pending.get(id);if(!p)return;clearTimeout(p.timer);this.pending.delete(id);ok?p.resolve(value):p.reject(new Error(error||"RiftNative error"));}
}

class TrueKernel extends EventTarget {
  constructor(){
    super();this.version=CORE_VERSION;this.fs=new RiftFS2();this.processes=new ProcessTable();this.native=new RiftNativeBridge();this.permissions=new PermissionBroker(this.fs);this.apps=new Map(SYSTEM_APPS.map(a=>[a.id,{...a}]));this.booted=false;this.bootTime=0;
  }
  async boot(){
    if(this.booted) return this;
    this.bootTime=Date.now();await this.fs.init();
    this.processes.spawn("system","RiftKernel",{protected:true,kind:"kernel"});
    this.processes.spawn("desktop","Rift Desktop",{protected:true,kind:"system"});
    if(this.native.connected) this.processes.spawn("native","RiftNative Bridge",{protected:true,kind:"bridge"});
    await this.fs.setSetting("trueos.lastBoot",{at:this.bootTime,version:this.version,native:this.native.connected,storage:this.fs.opfsReady?"opfs":"indexeddb"});
    this.booted=true;this.dispatchEvent(new Event("boot"));return this;
  }
  uptime(){return Math.max(0,Math.floor((Date.now()-this.bootTime)/1000));}
  registerApp(manifest){if(!manifest?.id)throw new Error("App id required");this.apps.set(manifest.id,{...manifest});return manifest;}
  launchProcess(appId,name,details={}){return this.processes.spawn(appId,name||this.apps.get(appId)?.name||appId,details);}
  kill(pid){return this.processes.kill(pid);}
  mounts(){return [
    {path:"/",type:this.fs.opfsReady?"opfs":"indexeddb",mode:"rw",label:"RiftFS"},
    {path:"/legacy",type:"indexeddb",mode:"rw-mirror",label:"Compatibility Mirror"},
    ...(this.native.connected?[{path:"/mounts/native",type:"rift-native",mode:"permissioned",label:"iOS Files"}]:[])
  ];}
  async info(){const storage=await this.fs.estimate();return {name:"RiftOS",version:this.version,mode:this.native.connected?"native-hosted":"web-pwa",uptime:this.uptime(),storage,native:this.native.capabilities(),processes:this.processes.list().length,apps:this.apps.size};}
}

const kernel=new TrueKernel();
const ready=kernel.boot().catch(error=>{console.error("[TrueOS] kernel boot failed",error);throw error;});

window.RiftNative=Object.freeze({
  get connected(){return kernel.native.connected;},
  capabilities:()=>kernel.native.capabilities(),
  call:(method,args)=>kernel.native.call(method,args),
  __resolve:(id,ok,value,error)=>kernel.native.resolve(id,!!ok,value,error)
});
window.RiftOSCore=Object.freeze({
  version:CORE_VERSION,
  ready,
  kernel,
  fs:kernel.fs,
  processes:kernel.processes,
  permissions:kernel.permissions,
  native:kernel.native,
  path:Object.freeze({normalize:cleanPath,parent:parentPath,basename})
});

console.info(`[TrueOS] RiftKernel ${CORE_VERSION} loading`);
