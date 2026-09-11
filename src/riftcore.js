const CORE_VERSION = "2.0.0-android-native";
const ROOT_MOUNT = "__riftfs__";
const PROTECTED_RIFT_ROOTS = new Set(["/home","/apps","/system","/workspace","/downloads","/documents","/mounts"]);

const SYSTEM_APPS = [
  {id:"system",name:"RiftKernel",trusted:true,permissions:["fs.read","fs.write","process.read","process.manage","system.settings","native.read","native.files"]},
  {id:"desktop",name:"Rift Desktop",trusted:true,permissions:["fs.read","process.read"]},
  {id:"files",name:"Files",trusted:true,permissions:["fs.read","fs.write","native.files"]},
  {id:"editor",name:"Editor",trusted:true,permissions:["fs.read","fs.write"]},
  {id:"terminal",name:"RiftShell",trusted:true,permissions:["fs.read","fs.write","process.read","process.manage","system.settings","native.read","native.files"]},
  {id:"riftdev",name:"RiftDev",trusted:true,permissions:["fs.read","fs.write","network","clipboard.read","clipboard.write","process.read","native.files"]},
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
  const parts=[];
  for(const part of raw.split("/")){
    if(!part||part===".") continue;
    if(part==="..") parts.pop();
    else parts.push(part);
  }
  return "/"+parts.join("/");
}
function parentPath(path){
  const parts=normalizePath(path).split("/").filter(Boolean);
  parts.pop();
  return "/"+parts.join("/");
}
function basename(path){return normalizePath(path).split("/").filter(Boolean).pop()||"";}
function joinPath(...parts){return normalizePath(parts.join("/"));}
function safeMountName(value){
  return String(value||"mount").trim().replace(/[\/\\]+/g,"-").replace(/\s+/g," ").slice(0,80)||"mount";
}
function sizeOf(value){return new TextEncoder().encode(String(value??"")).byteLength;}

class RiftNativeBridge extends EventTarget{
  constructor(){
    super();
    this.seq=0;
    this.pending=new Map();
    this.timeout=30000;
    this.transport=globalThis.RiftNativeTransport||null;
  }
  timeoutFor(method){
    if(method==="files.pickDirectory")return 10*60*1000;
    if(method==="fs.copy"||method==="fs.move"||method==="fs.remove")return 15*60*1000;
    if(method==="fs.list"||method==="fs.readText"||method==="fs.writeText")return 2*60*1000;
    return this.timeout;
  }
  get connected(){return !!this.transport?.postMessage;}
  capabilities(){
    return {
      nativeHost:this.connected,
      platform:"android",
      android:true,
      samsung:true,
      nativeFilesystem:true,
      saf:true,
      opfs:false,
      serviceWorker:false,
      backgroundSync:false,
      share:true,
      notifications:true,
      clipboard:true,
      browser:true,
      workspace:true,
      jsonPatches:true,
      patchRollback:true
    };
  }
  call(method,args={}){
    if(!this.connected)return Promise.reject(new Error("RiftAndroid native transport is not connected."));
    const id=`ra-${Date.now()}-${++this.seq}`;
    return new Promise((resolve,reject)=>{
      const timeout=this.timeoutFor(method);
      const timer=setTimeout(()=>{
        this.pending.delete(id);
        reject(new Error(`RiftAndroid timeout after ${Math.round(timeout/1000)}s: ${method}`));
      },timeout);
      this.pending.set(id,{resolve,reject,timer});
      try{this.transport.postMessage({id,method,args});}
      catch(error){clearTimeout(timer);this.pending.delete(id);reject(error);}
    });
  }
  resolve(id,ok,value,error){
    const pending=this.pending.get(id);
    if(!pending)return;
    clearTimeout(pending.timer);
    this.pending.delete(id);
    ok?pending.resolve(value):pending.reject(new Error(error||"RiftAndroid native error"));
  }
}

class RiftFS extends EventTarget{
  constructor(native){
    super();
    this.native=native;
    this.mounts=new Map();
    this.ready=false;
    this.opfsReady=false;
    this.nativeRootReady=false;
  }
  route(value){
    const path=normalizePath(value);
    const mount=this.resolveMount(path);
    if(mount)return {path,mountId:mount.mountId,relative:mount.relative||"",backend:"android-saf",mount};
    return {path,mountId:ROOT_MOUNT,relative:path.replace(/^\/+/,""),backend:"android-internal",mount:null};
  }
  async init(){
    if(this.ready)return this;
    if(!this.native.connected)throw new Error("RiftFS requires the Android native host on this branch.");
    await this.restoreNativeMounts().catch(error=>console.warn("[RiftFS] SAF mount restore failed",error));
    for(const path of ["home","apps","system","workspace","downloads","documents"]){
      await this.native.call("fs.mkdir",{mountId:ROOT_MOUNT,path});
    }
    this.nativeRootReady=true;
    if(!(await this.get("/home/readme.txt"))){
      await this.writeText("/home/readme.txt","Welcome to RiftOS Android.\n\nRiftFS is backed directly by Android app storage. External folders mount through Android Storage Access Framework.");
    }
    this.ready=true;
    this.dispatchEvent(new Event("ready"));
    return this;
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
    const rows=await this.native.call("files.mounts",{});
    this.mounts.clear();
    for(const row of Array.isArray(rows)?rows:[])this.attachNativeMount(row);
  }
  attachNativeMount(mount){
    if(!mount?.mountId)return null;
    const id=String(mount.mountId);
    let name=safeMountName(mount.name||id),path=`/mounts/${name}`,i=2;
    while([...this.mounts.values()].some(item=>item.path===path&&item.mountId!==id))path=`/mounts/${name}-${i++}`;
    const record={mountId:id,name,path,type:"android-saf",mode:"rw",persistent:mount.persistent!==false,system:mount.system===true};
    this.mounts.set(id,record);
    this.dispatchEvent(new CustomEvent("mount",{detail:record}));
    return record;
  }
  async mountNativeDirectory(){
    const result=await this.native.call("files.pickDirectory",{});
    return this.attachNativeMount(result);
  }
  async unmount(pathOrId){
    const normalized=String(pathOrId||"");
    const mount=[...this.mounts.values()].find(item=>item.mountId===normalized||item.path===normalizePath(normalized));
    if(!mount||mount.system)return false;
    await this.native.call("files.unmount",{mountId:mount.mountId}).catch(()=>{});
    this.mounts.delete(mount.mountId);
    this.dispatchEvent(new CustomEvent("unmount",{detail:mount}));
    return true;
  }
  async stat(value){
    const target=this.route(value);
    if(target.path==="/mounts")return {path:"/mounts",kind:"directory",size:0,modified:0,backend:"android-virtual"};
    if(target.mount&&!target.relative)return {path:target.path,kind:"mount",size:0,modified:0,backend:"android-saf",mountId:target.mountId};
    const stat=await this.native.call("fs.stat",{mountId:target.mountId,path:target.relative});
    return stat?{...stat,path:target.path,backend:target.backend}:null;
  }
  async openNative(value){
    const target=this.route(value);
    if(!target.mount||!target.relative)throw new Error("Native open is available for files inside Android mounts only");
    return this.native.call("files.open",{mountId:target.mountId,path:target.relative});
  }
  async get(value){
    const target=this.route(value);
    if(target.path==="/mounts")return {path:"/mounts",kind:"directory",size:0,modified:0,backend:"android-virtual"};
    if(target.mount&&!target.relative)return {path:target.path,kind:"mount",size:0,modified:0,backend:"android-saf",mountId:target.mountId};
    const stat=await this.native.call("fs.stat",{mountId:target.mountId,path:target.relative});
    if(!stat)return null;
    if(stat.kind==="directory")return {...stat,path:target.path,backend:target.backend};
    const content=await this.native.call("fs.readText",{mountId:target.mountId,path:target.relative});
    return {...stat,path:target.path,content:String(content??""),backend:target.backend};
  }
  async readText(value){return (await this.get(value))?.content??null;}
  async write(value,content){
    const target=this.route(value);
    if(target.path==="/mounts"||target.mount&&!target.relative)throw new Error("Cannot write over a mount root");
    const text=String(content??"");
    const stat=await this.native.call("fs.writeText",{mountId:target.mountId,path:target.relative,text});
    const record={...stat,path:target.path,content:text,size:Number(stat?.size??sizeOf(text)),backend:target.backend};
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"write",path:target.path}}));
    return record;
  }
  writeText(value,content){return this.write(value,content);}
  async mkdir(value){
    const target=this.route(value);
    if(target.path==="/mounts")return {path:"/mounts",kind:"directory",backend:"android-virtual"};
    if(target.mount&&!target.relative)return {path:target.path,kind:"mount",backend:"android-saf"};
    const stat=await this.native.call("fs.mkdir",{mountId:target.mountId,path:target.relative});
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"mkdir",path:target.path}}));
    return {...stat,path:target.path,backend:target.backend};
  }
  async remove(value){
    const target=this.route(value);
    if(target.path==="/"||PROTECTED_RIFT_ROOTS.has(target.path))throw new Error("Cannot delete a RiftFS system root");
    if(target.mount&&!target.relative)throw new Error("Unmount the folder instead of deleting the mount root");
    await this.native.call("fs.remove",{mountId:target.mountId,path:target.relative,recursive:true});
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"remove",path:target.path}}));
    return true;
  }
  async copy(fromValue,toValue,{overwrite=false}={}){
    const source=this.route(fromValue),destination=this.route(toValue);
    if(source.path==="/"||source.path==="/mounts"||source.mount&&!source.relative)throw new Error("Cannot copy a filesystem root or mount root");
    if(destination.path==="/"||destination.path==="/mounts"||destination.mount&&!destination.relative)throw new Error("Cannot replace a filesystem root or mount root");
    const stat=await this.native.call("fs.copy",{
      fromMountId:source.mountId,from:source.relative,
      toMountId:destination.mountId,to:destination.relative,overwrite:overwrite===true
    });
    const record={...stat,path:destination.path,backend:destination.backend};
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"copy",path:source.path,newPath:destination.path}}));
    return record;
  }
  async move(fromValue,toValue,{overwrite=false}={}){
    const source=this.route(fromValue),destination=this.route(toValue);
    if(source.path==="/"||PROTECTED_RIFT_ROOTS.has(source.path)||source.mount&&!source.relative)throw new Error("Cannot move a RiftFS system root or mount root");
    if(destination.path==="/"||destination.path==="/mounts"||destination.mount&&!destination.relative)throw new Error("Cannot replace a filesystem root or mount root");
    const stat=await this.native.call("fs.move",{
      fromMountId:source.mountId,from:source.relative,
      toMountId:destination.mountId,to:destination.relative,overwrite:overwrite===true
    });
    const record={...stat,path:destination.path,backend:destination.backend};
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"move",path:source.path,newPath:destination.path}}));
    return record;
  }
  rename(value,newValue,options={}){return this.move(value,newValue,options);}
  createFile(value,text=""){return this.writeText(value,text);}
  async list(value="/",options={}){
    const path=normalizePath(value),recursive=options.recursive!==false;
    if(path==="/mounts"){
      return [...this.mounts.values()].map(m=>({path:m.path,kind:"mount",size:0,modified:0,backend:"android-saf",mountId:m.mountId})).sort((a,b)=>a.path.localeCompare(b.path));
    }
    const target=this.route(path);
    if(target.mount&&!target.relative&&recursive===false)return [];
    const rows=await this.native.call("fs.list",{mountId:target.mountId,path:target.relative,recursive});
    const mapped=(Array.isArray(rows)?rows:[]).map(row=>({...row,path:joinPath(path,row.path||row.name||""),backend:target.backend}));
    if(path==="/"){
      mapped.push({path:"/mounts",kind:"directory",size:0,modified:0,backend:"android-virtual"});
      if(recursive)for(const mount of this.mounts.values())mapped.push({path:mount.path,kind:"mount",size:0,modified:0,backend:"android-saf",mountId:mount.mountId});
    }
    return mapped.sort((a,b)=>a.path.localeCompare(b.path));
  }
  setting(key){return this.native.call("settings.get",{key:String(key)});}
  setSetting(key,value){return this.native.call("settings.set",{key:String(key),value});}
  estimate(){return this.native.call("system.storage",{});}
  async persist(){return true;}
  async syncLegacy(){return {copiedToOPFS:0,copiedToLegacy:0,native:true};}
  async readJSON(path,fallback=null){const text=await this.readText(path);if(text==null)return fallback;try{return JSON.parse(text);}catch{return fallback;}}
  writeJSON(path,value){return this.writeText(path,JSON.stringify(value,null,2));}
}

class ProcessTable extends EventTarget{
  constructor(){super();this.seq=99;this.items=new Map();}
  spawn(appId,name,details={}){
    const pid=++this.seq;
    const proc={pid,appId:String(appId),name:String(name||appId),state:"running",started:Date.now(),protected:false,...details};
    this.items.set(pid,proc);
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"spawn",process:proc}}));
    return proc;
  }
  kill(pid,reason="terminated"){
    const proc=this.items.get(Number(pid));
    if(!proc||proc.protected)return false;
    proc.state=reason;proc.ended=Date.now();this.items.delete(Number(pid));
    try{proc.onTerminate?.(proc);}catch(error){console.warn("[RiftKernel] process cleanup failed",error);}
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"kill",process:proc}}));
    return true;
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
    const set=new Set(await this.grants(appId));set.add(capability);
    await this.fs.setSetting(`permissions:${appId}`,[...set].sort());
    this.dispatchEvent(new CustomEvent("change",{detail:{appId,capability,granted:true}}));
    return true;
  }
  async revoke(appId,capability){
    const set=new Set(await this.grants(appId));set.delete(capability);
    await this.fs.setSetting(`permissions:${appId}`,[...set].sort());
    this.dispatchEvent(new CustomEvent("change",{detail:{appId,capability,granted:false}}));
    return true;
  }
}

class AndroidKernel extends EventTarget{
  constructor(){
    super();
    this.version=CORE_VERSION;
    this.native=new RiftNativeBridge();
    this.fs=new RiftFS(this.native);
    this.processes=new ProcessTable();
    this.permissions=new PermissionBroker(this.fs);
    this.apps=new Map(SYSTEM_APPS.map(app=>[app.id,{...app}]));
    this.booted=false;
    this.bootTime=0;
  }
  async boot(){
    if(this.booted)return this;
    if(!this.native.connected)throw new Error("This android-apk branch requires the RiftAndroid host.");
    this.bootTime=Date.now();
    await this.fs.init();
    this.processes.spawn("system","RiftKernel",{protected:true,kind:"kernel"});
    this.processes.spawn("desktop","Rift Desktop",{protected:true,kind:"system"});
    this.processes.spawn("native","RiftAndroid Bridge",{protected:true,kind:"bridge"});
    await this.fs.setSetting("android.nativeCore.v1",{at:this.bootTime,version:this.version});
    await this.fs.setSetting("riftapps.fs-migration-v1",{at:this.bootTime,androidNative:true});
    await this.fs.setSetting("trueos.lastBoot",{at:this.bootTime,version:this.version,native:true,storage:"android-internal"});
    this.booted=true;
    this.dispatchEvent(new Event("boot"));
    return this;
  }
  uptime(){return this.bootTime?Math.max(0,Math.floor((Date.now()-this.bootTime)/1000)):0;}
  registerApp(manifest){
    if(!manifest?.id)throw new Error("App id required");
    const current=this.apps.get(manifest.id)||{};
    const next={...current,...manifest,id:String(manifest.id)};
    this.apps.set(next.id,next);
    this.dispatchEvent(new CustomEvent("apps-change",{detail:next}));
    return next;
  }
  unregisterApp(id){if(SYSTEM_APPS.some(app=>app.id===id))return false;return this.apps.delete(id);}
  launchProcess(appId,name,details={}){return this.processes.spawn(appId,name||this.apps.get(appId)?.name||appId,details);}
  kill(pid){return this.processes.kill(pid);}
  mounts(){
    return [
      {path:"/",type:"android-internal",mode:"rw",label:"RiftFS"},
      ...[...this.fs.mounts.values()].map(m=>({path:m.path,type:"android-saf",mode:m.mode,label:m.name,mountId:m.mountId}))
    ];
  }
  async info(){
    return {
      name:"RiftOS",version:this.version,mode:"android-apk",host:"Android System WebView",uptime:this.uptime(),
      storage:await this.fs.estimate(),native:this.native.capabilities(),processes:this.processes.list().length,
      apps:this.apps.size,mounts:this.mounts().length
    };
  }
}

const kernel=new AndroidKernel();
const ready=kernel.boot().catch(error=>{console.error("[RiftOS Android] kernel boot failed",error);throw error;});

window.RiftNative=Object.freeze({
  get connected(){return kernel.native.connected;},
  capabilities:()=>kernel.native.capabilities(),
  call:(method,args)=>kernel.native.call(method,args),
  __resolve:(id,ok,value,error)=>kernel.native.resolve(id,!!ok,value,error)
});

window.RiftOSCore=Object.freeze({
  version:CORE_VERSION,ready,kernel,fs:kernel.fs,processes:kernel.processes,permissions:kernel.permissions,native:kernel.native,workspace:null,
  path:Object.freeze({normalize:normalizePath,parent:parentPath,basename,join:joinPath})
});

console.info(`[RiftOS Android] RiftKernel ${CORE_VERSION} loading`);
