const CORE_VERSION = "1.0.0-trueos";
const LEGACY_DB = "riftos";
const MIGRATION_KEY = "trueos.opfs-migration-v2";

const SYSTEM_APPS = [
  {id:"system",name:"RiftKernel",trusted:true,permissions:["fs.read","fs.write","process.read","process.manage","system.settings","native.read","native.files"]},
  {id:"desktop",name:"Rift Desktop",trusted:true,permissions:["fs.read","process.read"]},
  {id:"files",name:"Files",trusted:true,permissions:["fs.read","fs.write","native.files"]},
  {id:"editor",name:"Editor",trusted:true,permissions:["fs.read","fs.write"]},
  {id:"terminal",name:"RiftShell",trusted:true,permissions:["fs.read","fs.write","process.read","process.manage","system.settings","native.read","native.files"]},
  {id:"riftdev",name:"RiftDev",trusted:true,permissions:["fs.read","fs.write","network","clipboard.write","process.read"]},
  {id:"browser",name:"RiftBrowser",trusted:true,permissions:["network"]},
  {id:"settings",name:"Settings",trusted:true,permissions:["system.settings","native.read","native.files"]},
  {id:"tasks",name:"Tasks",trusted:true,permissions:["process.read","process.manage"]},
  {id:"rift-apps",name:"Rift Apps",trusted:true,permissions:["fs.read","fs.write","process.read","process.manage","system.settings"]}
];

const CAPABILITIES = Object.freeze([
  "fs.read","fs.write","network",
  "clipboard.read","clipboard.write","share","notifications",
  "process.read","process.manage","system.settings",
  "native.read","native.files","native.background"
]);

function normalizePath(value="/"){
  const raw=String(value||"/").replace(/\\/g,"/");
  const absolute=raw.startsWith("/");
  const parts=[];
  for(const part of raw.split("/")){
    if(!part||part===".") continue;
    if(part==="..") parts.pop();
    else parts.push(part);
  }
  return (absolute?"/":"/")+parts.join("/");
}
function parentPath(path){
  const p=normalizePath(path),parts=p.split("/").filter(Boolean);
  parts.pop();return "/"+parts.join("/");
}
function basename(path){return normalizePath(path).split("/").filter(Boolean).pop()||"";}
function joinPath(...parts){return normalizePath(parts.join("/"));}
function safeMountName(value){
  return String(value||"mount").trim().replace(/[\/\\]+/g,"-").replace(/\s+/g," ").slice(0,80)||"mount";
}
function request(req){return new Promise((resolve,reject)=>{req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);});}

class LegacyStore{
  constructor(){this.db=null;}
  async init(){
    if(this.db)return this.db;
    this.db=await new Promise((resolve,reject)=>{
      const req=indexedDB.open(LEGACY_DB,1);
      req.onupgradeneeded=()=>{
        const db=req.result;
        if(!db.objectStoreNames.contains("files"))db.createObjectStore("files",{keyPath:"path"});
        if(!db.objectStoreNames.contains("settings"))db.createObjectStore("settings",{keyPath:"key"});
      };
      req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);
    });
    return this.db;
  }
  store(name,mode="readonly"){return this.db.transaction(name,mode).objectStore(name);}
  async get(path){await this.init();return request(this.store("files").get(normalizePath(path)));}
  async write(path,content,modified=Date.now()){
    await this.init();
    return request(this.store("files","readwrite").put({path:normalizePath(path),content:String(content),modified:Number(modified)||Date.now()}));
  }
  async remove(path){await this.init();return request(this.store("files","readwrite").delete(normalizePath(path)));}
  async list(){await this.init();return request(this.store("files").getAll());}
  async setting(key){await this.init();return request(this.store("settings").get(key));}
  async setSetting(key,value){await this.init();return request(this.store("settings","readwrite").put({key,value,modified:Date.now()}));}
}

class OPFSStore{
  constructor(){this.originRoot=null;this.root=null;}
  async init(){
    if(this.root)return this.root;
    if(!navigator.storage?.getDirectory)throw new Error("OPFS unavailable");
    this.originRoot=await navigator.storage.getDirectory();
    this.root=await this.originRoot.getDirectoryHandle("riftos",{create:true});
    return this.root;
  }
  async directory(path,create=false){
    await this.init();let dir=this.root;
    for(const part of normalizePath(path).split("/").filter(Boolean))dir=await dir.getDirectoryHandle(part,{create});
    return dir;
  }
  async fileHandle(path,create=false){
    const p=normalizePath(path);
    if(p==="/")throw new Error("Root is not a file");
    return (await this.directory(parentPath(p),create)).getFileHandle(basename(p),{create});
  }
  async read(path){
    try{
      const file=await (await this.fileHandle(path,false)).getFile();
      return {path:normalizePath(path),kind:"file",content:await file.text(),size:file.size,modified:file.lastModified||0,backend:"opfs"};
    }catch(error){if(error?.name==="NotFoundError"||error?.name==="TypeMismatchError")return null;throw error;}
  }
  async stat(path){
    const p=normalizePath(path);
    if(p==="/")return {path:"/",kind:"directory",size:0,modified:0,backend:"opfs"};
    try{
      const fh=await this.fileHandle(p,false),file=await fh.getFile();
      return {path:p,kind:"file",size:file.size,modified:file.lastModified||0,backend:"opfs"};
    }catch(error){
      if(error?.name!=="NotFoundError"&&error?.name!=="TypeMismatchError")throw error;
    }
    try{
      await this.directory(p,false);
      return {path:p,kind:"directory",size:0,modified:0,backend:"opfs"};
    }catch(error){if(error?.name==="NotFoundError"||error?.name==="TypeMismatchError")return null;throw error;}
  }
  async write(path,content){
    const p=normalizePath(path),handle=await this.fileHandle(p,true),writable=await handle.createWritable();
    await writable.write(String(content));await writable.close();
    const file=await handle.getFile();
    return {path:p,kind:"file",content:String(content),size:file.size,modified:file.lastModified||Date.now(),backend:"opfs"};
  }
  async mkdir(path){await this.directory(path,true);return {path:normalizePath(path),kind:"directory",backend:"opfs"};}
  async remove(path){
    const p=normalizePath(path);if(p==="/")throw new Error("Cannot remove RiftFS root");
    try{await (await this.directory(parentPath(p),false)).removeEntry(basename(p),{recursive:true});}
    catch(error){if(error?.name!=="NotFoundError")throw error;}
    return true;
  }
  async list(path="/",recursive=true){
    const base=normalizePath(path),root=await this.directory(base,false),out=[];
    const walk=async(dir,prefix)=>{
      for await(const [name,handle] of dir.entries()){
        const full=joinPath(prefix,name);
        if(handle.kind==="directory"){
          out.push({path:full,kind:"directory",size:0,modified:0,backend:"opfs"});
          if(recursive)await walk(handle,full);
        }else{
          const file=await handle.getFile();
          out.push({path:full,kind:"file",size:file.size,modified:file.lastModified||0,backend:"opfs"});
        }
      }
    };
    await walk(root,base==="/"?"/":base);
    return out.sort((a,b)=>a.path.localeCompare(b.path));
  }
}

class RiftNativeBridge extends EventTarget{
  constructor(){super();this.seq=0;this.pending=new Map();this.timeout=30000;}
  get connected(){return !!window.webkit?.messageHandlers?.riftNative;}
  capabilities(){
    return {
      nativeHost:this.connected,
      opfs:!!navigator.storage?.getDirectory,
      share:!!navigator.share,
      notifications:"Notification" in window,
      clipboard:!!navigator.clipboard,
      serviceWorker:"serviceWorker" in navigator,
      backgroundSync:"serviceWorker" in navigator&&"SyncManager" in window
    };
  }
  call(method,args={}){
    if(!this.connected)return Promise.reject(new Error("RiftNative host is not connected. RiftOS is running in web/PWA mode."));
    const id=`rn-${Date.now()}-${++this.seq}`;
    return new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error(`RiftNative timeout: ${method}`));},this.timeout);
      this.pending.set(id,{resolve,reject,timer});
      window.webkit.messageHandlers.riftNative.postMessage({id,method,args});
    });
  }
  resolve(id,ok,value,error){
    const pending=this.pending.get(id);if(!pending)return;
    clearTimeout(pending.timer);this.pending.delete(id);
    ok?pending.resolve(value):pending.reject(new Error(error||"RiftNative error"));
  }
}

class RiftFS extends EventTarget{
  constructor(native){
    super();this.native=native;this.legacy=new LegacyStore();this.opfs=new OPFSStore();
    this.opfsReady=false;this.ready=false;this.mounts=new Map();
  }
  async init(){
    if(this.ready)return this;
    await this.legacy.init();
    try{await this.opfs.init();this.opfsReady=true;}catch(error){console.warn("[RiftFS] OPFS unavailable; IndexedDB compatibility mode active.",error);}
    if(this.opfsReady){
      await this.opfs.mkdir("/home");await this.opfs.mkdir("/apps");await this.opfs.mkdir("/system");await this.opfs.mkdir("/mounts");
      await this.migrateLegacy();
    }
    if(!(await this.get("/home/readme.txt"))){
      await this.write("/home/readme.txt","Welcome to RiftOS True OS Core.\n\nRiftFS uses OPFS when available and keeps an IndexedDB compatibility mirror.");
    }
    if(this.native.connected)await this.restoreNativeMounts().catch(error=>console.warn("[RiftFS] Native mount restore failed",error));
    this.ready=true;this.dispatchEvent(new Event("ready"));return this;
  }
  async migrateLegacy(){
    const done=(await this.legacy.setting(MIGRATION_KEY))?.value;
    if(done)return;
    const rows=await this.legacy.list();let copied=0;
    for(const row of rows){
      const existing=await this.opfs.stat(row.path);
      if(!existing){await this.opfs.write(row.path,row.content);copied++;}
    }
    await this.legacy.setSetting(MIGRATION_KEY,{at:Date.now(),seen:rows.length,copied});
  }
  resolveMount(path){
    const p=normalizePath(path);
    let best=null;
    for(const mount of this.mounts.values()){
      if(p===mount.path||p.startsWith(mount.path+"/")){
        if(!best||mount.path.length>best.path.length)best=mount;
      }
    }
    if(!best)return null;
    return {...best,relative:p.slice(best.path.length).replace(/^\/+/,"")};
  }
  async restoreNativeMounts(){
    const mounts=await this.native.call("files.mounts",{});
    for(const mount of Array.isArray(mounts)?mounts:[])this.attachNativeMount(mount);
  }
  attachNativeMount(mount){
    if(!mount?.mountId)return null;
    let name=safeMountName(mount.name||mount.mountId),path=`/mounts/${name}`,i=2;
    while([...this.mounts.values()].some(item=>item.path===path&&item.mountId!==mount.mountId))path=`/mounts/${name}-${i++}`;
    const record={mountId:String(mount.mountId),name,path,type:"rift-native",mode:"rw",persistent:mount.persistent!==false};
    this.mounts.set(record.mountId,record);this.dispatchEvent(new CustomEvent("mount",{detail:record}));return record;
  }
  async mountNativeDirectory(){
    if(!this.native.connected)throw new Error("Native iOS host is required to mount Files directories.");
    const result=await this.native.call("files.pickDirectory",{});
    return this.attachNativeMount(result);
  }
  async unmount(pathOrId){
    const mount=[...this.mounts.values()].find(item=>item.mountId===pathOrId||item.path===normalizePath(pathOrId));
    if(!mount)return false;
    if(this.native.connected)await this.native.call("files.unmount",{mountId:mount.mountId}).catch(()=>{});
    this.mounts.delete(mount.mountId);this.dispatchEvent(new CustomEvent("unmount",{detail:mount}));return true;
  }
  async get(path){
    const p=normalizePath(path),mount=this.resolveMount(p);
    if(mount){
      if(!mount.relative)return {path:p,kind:"directory",size:0,modified:0,backend:"rift-native"};
      const stat=await this.native.call("fs.stat",{mountId:mount.mountId,path:mount.relative});
      if(!stat||stat.kind==="directory")return stat?{...stat,path:p,backend:"rift-native"}:null;
      const content=await this.native.call("fs.readText",{mountId:mount.mountId,path:mount.relative});
      return {...stat,path:p,content:String(content??""),backend:"rift-native"};
    }
    if(this.opfsReady){
      const local=await this.opfs.read(p);
      if(local){
        const legacy=await this.legacy.get(p);
        if(!legacy||Number(local.modified||0)>Number(legacy.modified||0))await this.legacy.write(p,local.content,local.modified);
        return local;
      }
      const legacy=await this.legacy.get(p);
      if(legacy){const next=await this.opfs.write(p,legacy.content);return {...next,content:legacy.content};}
      return null;
    }
    const legacy=await this.legacy.get(p);
    return legacy?{...legacy,kind:"file",size:new Blob([legacy.content]).size,backend:"indexeddb"}:null;
  }
  async readText(path){return (await this.get(path))?.content??null;}
  async write(path,content){
    const p=normalizePath(path),mount=this.resolveMount(p);
    if(mount){
      if(!mount.relative)throw new Error("Cannot write over a mount root");
      await this.native.call("fs.writeText",{mountId:mount.mountId,path:mount.relative,text:String(content)});
      this.dispatchEvent(new CustomEvent("change",{detail:{type:"write",path:p}}));
      return {path:p,kind:"file",content:String(content),size:new Blob([String(content)]).size,modified:Date.now(),backend:"rift-native"};
    }
    let record={path:p,kind:"file",content:String(content),size:new Blob([String(content)]).size,modified:Date.now(),backend:"indexeddb"};
    if(this.opfsReady)record=await this.opfs.write(p,content);
    await this.legacy.write(p,content,record.modified);
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"write",path:p}}));return record;
  }
  writeText(path,content){return this.write(path,content);}
  async mkdir(path){
    const p=normalizePath(path),mount=this.resolveMount(p);
    if(mount){
      if(!mount.relative)return mount;
      await this.native.call("fs.mkdir",{mountId:mount.mountId,path:mount.relative});
      this.dispatchEvent(new CustomEvent("change",{detail:{type:"mkdir",path:p}}));return {path:p,kind:"directory",backend:"rift-native"};
    }
    if(this.opfsReady)await this.opfs.mkdir(p);
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"mkdir",path:p}}));return {path:p,kind:"directory",backend:this.opfsReady?"opfs":"indexeddb"};
  }
  async remove(path){
    const p=normalizePath(path),mount=this.resolveMount(p);
    if(mount){
      if(!mount.relative)throw new Error("Unmount the directory instead of deleting the mount root");
      await this.native.call("fs.remove",{mountId:mount.mountId,path:mount.relative,recursive:true});
    }else{
      if(this.opfsReady)await this.opfs.remove(p);
      const rows=await this.legacy.list();
      for(const row of rows)if(row.path===p||row.path.startsWith(p+"/"))await this.legacy.remove(row.path);
    }
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"remove",path:p}}));return true;
  }
  async stat(path){
    const p=normalizePath(path),mount=this.resolveMount(p);
    if(mount){
      if(!mount.relative)return {path:p,kind:"directory",size:0,modified:0,backend:"rift-native"};
      const stat=await this.native.call("fs.stat",{mountId:mount.mountId,path:mount.relative});
      return stat?{...stat,path:p,backend:"rift-native"}:null;
    }
    if(this.opfsReady)return this.opfs.stat(p);
    const row=await this.legacy.get(p);
    return row?{path:p,kind:"file",size:new Blob([row.content]).size,modified:row.modified||0,backend:"indexeddb"}:null;
  }
  async list(path="/",options={}){
    const p=normalizePath(path),recursive=options.recursive!==false,mount=this.resolveMount(p);
    if(mount){
      const rows=await this.native.call("fs.list",{mountId:mount.mountId,path:mount.relative||"",recursive});
      return (Array.isArray(rows)?rows:[]).map(row=>({...row,path:joinPath(p,row.path||row.name||""),backend:"rift-native"})).sort((a,b)=>a.path.localeCompare(b.path));
    }
    let rows=[];
    if(this.opfsReady){
      try{rows=await this.opfs.list(p,recursive);}catch(error){if(error?.name!=="NotFoundError")throw error;}
    }else{
      const legacy=await this.legacy.list();
      rows=legacy.filter(row=>row.path===p||row.path.startsWith(p==="/"?"/":p+"/")).map(row=>({path:row.path,kind:"file",size:new Blob([row.content]).size,modified:row.modified||0,backend:"indexeddb"}));
    }
    for(const nativeMount of this.mounts.values()){
      if(p==="/"||nativeMount.path===p||nativeMount.path.startsWith(p+"/"))rows.push({path:nativeMount.path,kind:"mount",size:0,modified:0,backend:"rift-native",mountId:nativeMount.mountId});
    }
    return rows.sort((a,b)=>a.path.localeCompare(b.path));
  }
  async syncLegacy(){
    if(!this.opfsReady)return {copiedToOPFS:0,copiedToLegacy:0};
    const legacy=await this.legacy.list(),opfs=await this.opfs.list("/",true),opMap=new Map(opfs.filter(x=>x.kind==="file").map(x=>[x.path,x]));
    let copiedToOPFS=0,copiedToLegacy=0;
    for(const row of legacy){
      const meta=opMap.get(row.path);
      if(!meta){await this.opfs.write(row.path,row.content);copiedToOPFS++;continue;}
      if(Number(row.modified||0)>Number(meta.modified||0)){await this.opfs.write(row.path,row.content);copiedToOPFS++;}
    }
    for(const meta of opMap.values()){
      const old=legacy.find(row=>row.path===meta.path);
      if(!old||Number(meta.modified||0)>Number(old.modified||0)){
        const file=await this.opfs.read(meta.path);await this.legacy.write(meta.path,file.content,file.modified);copiedToLegacy++;
      }
    }
    return {copiedToOPFS,copiedToLegacy};
  }
  setting(key){return this.legacy.setting(key);}
  setSetting(key,value){return this.legacy.setSetting(key,value);}
  async estimate(){
    const e=await navigator.storage?.estimate?.()||{};
    return {usage:Number(e.usage||0),quota:Number(e.quota||0),backend:this.opfsReady?"OPFS + IndexedDB compatibility mirror":"IndexedDB compatibility"};
  }
  async persist(){return navigator.storage?.persist?navigator.storage.persist():false;}
  async readJSON(path,fallback=null){const text=await this.readText(path);if(text==null)return fallback;try{return JSON.parse(text);}catch{return fallback;}}
  writeJSON(path,value){return this.write(path,JSON.stringify(value,null,2));}
}

class ProcessTable extends EventTarget{
  constructor(){super();this.seq=99;this.items=new Map();}
  spawn(appId,name,details={}){
    const pid=++this.seq,proc={pid,appId:String(appId),name:String(name||appId),state:"running",started:Date.now(),protected:false,...details};
    this.items.set(pid,proc);this.dispatchEvent(new CustomEvent("change",{detail:{type:"spawn",process:proc}}));return proc;
  }
  kill(pid,reason="terminated"){
    pid=Number(pid);const proc=this.items.get(pid);
    if(!proc||proc.protected)return false;
    proc.state=reason;proc.ended=Date.now();this.items.delete(pid);
    try{proc.onTerminate?.(proc);}catch(error){console.warn("[RiftKernel] process cleanup failed",error);}
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"kill",process:proc}}));return true;
  }
  list(){return [...this.items.values()].sort((a,b)=>a.pid-b.pid);}
  get(pid){return this.items.get(Number(pid))||null;}
}

class PermissionBroker extends EventTarget{
  constructor(fs){
    super();this.fs=fs;this.system=new Map(SYSTEM_APPS.map(app=>[app.id,new Set(app.permissions||[])]));
  }
  describe(){return [...CAPABILITIES];}
  async grants(appId){return (await this.fs.setting(`permissions:${appId}`))?.value||[];}
  async has(appId,capability){
    if(!CAPABILITIES.includes(capability))return false;
    if(this.system.get(appId)?.has(capability))return true;
    return (await this.grants(appId)).includes(capability);
  }
  async request(appId,capability,label=appId){
    if(!CAPABILITIES.includes(capability))throw new Error(`Unknown capability: ${capability}`);
    if(await this.has(appId,capability))return true;
    const granted=window.confirm?window.confirm(`${label} wants permission: ${capability}`):false;
    if(granted)await this.grant(appId,capability);
    return granted;
  }
  async grant(appId,capability){
    if(!CAPABILITIES.includes(capability))throw new Error(`Unknown capability: ${capability}`);
    const set=new Set(await this.grants(appId));set.add(capability);await this.fs.setSetting(`permissions:${appId}`,[...set].sort());
    this.dispatchEvent(new CustomEvent("change",{detail:{appId,capability,granted:true}}));return true;
  }
  async revoke(appId,capability){
    const set=new Set(await this.grants(appId));set.delete(capability);await this.fs.setSetting(`permissions:${appId}`,[...set].sort());
    this.dispatchEvent(new CustomEvent("change",{detail:{appId,capability,granted:false}}));return true;
  }
}

class TrueKernel extends EventTarget{
  constructor(){
    super();this.version=CORE_VERSION;this.native=new RiftNativeBridge();this.fs=new RiftFS(this.native);
    this.processes=new ProcessTable();this.permissions=new PermissionBroker(this.fs);
    this.apps=new Map(SYSTEM_APPS.map(app=>[app.id,{...app}]));this.booted=false;this.bootTime=0;
  }
  async boot(){
    if(this.booted)return this;
    this.bootTime=Date.now();await this.fs.init();
    this.processes.spawn("system","RiftKernel",{protected:true,kind:"kernel"});
    this.processes.spawn("desktop","Rift Desktop",{protected:true,kind:"system"});
    if(this.native.connected)this.processes.spawn("native","RiftNative Bridge",{protected:true,kind:"bridge"});
    await this.fs.setSetting("trueos.lastBoot",{at:this.bootTime,version:this.version,native:this.native.connected,storage:this.fs.opfsReady?"opfs":"indexeddb"});
    this.booted=true;this.dispatchEvent(new Event("boot"));return this;
  }
  uptime(){return this.bootTime?Math.max(0,Math.floor((Date.now()-this.bootTime)/1000)):0;}
  registerApp(manifest){
    if(!manifest?.id)throw new Error("App id required");
    const current=this.apps.get(manifest.id)||{};
    const next={...current,...manifest,id:String(manifest.id)};
    this.apps.set(next.id,next);this.dispatchEvent(new CustomEvent("apps-change",{detail:next}));return next;
  }
  unregisterApp(id){if(SYSTEM_APPS.some(app=>app.id===id))return false;return this.apps.delete(id);}
  launchProcess(appId,name,details={}){return this.processes.spawn(appId,name||this.apps.get(appId)?.name||appId,details);}
  kill(pid){return this.processes.kill(pid);}
  mounts(){
    return [
      {path:"/",type:this.fs.opfsReady?"opfs":"indexeddb",mode:"rw",label:"RiftFS"},
      {path:"/legacy",type:"indexeddb",mode:"compatibility-mirror",label:"Legacy compatibility mirror"},
      ...[...this.fs.mounts.values()].map(m=>({path:m.path,type:m.type,mode:m.mode,label:m.name,mountId:m.mountId}))
    ];
  }
  async info(){
    const storage=await this.fs.estimate();
    return {name:"RiftOS",version:this.version,mode:this.native.connected?"native-hosted":"web-pwa",uptime:this.uptime(),storage,native:this.native.capabilities(),processes:this.processes.list().length,apps:this.apps.size,mounts:this.mounts().length};
  }
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
  version:CORE_VERSION,ready,kernel,fs:kernel.fs,processes:kernel.processes,permissions:kernel.permissions,native:kernel.native,
  path:Object.freeze({normalize:normalizePath,parent:parentPath,basename,join:joinPath})
});

console.info(`[TrueOS] RiftKernel ${CORE_VERSION} loading`);
