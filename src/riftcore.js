const CORE_VERSION = "2.2.0-android-native-volumes";
const ROOT_MOUNT = "__riftfs__";
const PROTECTED_RIFT_ROOTS = new Set(["/home","/apps","/system","/workspace","/downloads","/documents","/mounts","/system/riftos","/system/programs","/system/program-data","/system/toolchains","/home/users","/home/projects","/home/temp","/documents/packages","/documents/builds","/documents/vault","/C:","/D:","/C:/RiftOS","/C:/Programs","/C:/ProgramData","/C:/Toolchains","/D:/Users","/D:/Workspace","/D:/Projects","/D:/Packages","/D:/Builds","/D:/Documents","/D:/Downloads","/D:/Vault","/D:/Temp"]);
const RIFT_VOLUMES = Object.freeze({
  "C:":Object.freeze({id:"C",letter:"C:",label:"RiftOS System",backing:"/system/volumes/C",roots:Object.freeze({RiftOS:"/system/riftos",Programs:"/system/programs",ProgramData:"/system/program-data",Toolchains:"/system/toolchains"})}),
  "D:":Object.freeze({id:"D",letter:"D:",label:"User Data",backing:"/system/volumes/D",roots:Object.freeze({Users:"/home/users",Workspace:"/workspace",Projects:"/home/projects",Packages:"/documents/packages",Builds:"/documents/builds",Documents:"/documents",Downloads:"/downloads",Vault:"/documents/vault",Temp:"/home/temp"})})
});
const VOLUME_ROOTS = new Set(Object.keys(RIFT_VOLUMES).map(letter=>`/${letter}`));

const SYSTEM_APPS = [
  {id:"system",name:"RiftKernel",trusted:true,permissions:["fs.read","fs.write","process.read","process.manage","system.settings","native.read","native.files"]},
  {id:"desktop",name:"Rift Desktop",trusted:true,permissions:["fs.read","process.read"]},
  {id:"files",name:"Files",trusted:true,permissions:["fs.read","fs.write","native.files"]},
  {id:"editor",name:"Editor",trusted:true,permissions:["fs.read","fs.write"]},
  {id:"terminal",name:"RiftShell",trusted:true,permissions:["fs.read","fs.write","process.read","process.manage","system.settings","native.read","native.files"]},
  {id:"browser",name:"RiftBrowser",trusted:true,permissions:["network"]},
  {id:"workspace-live",name:"Workspace Records",trusted:true,permissions:["fs.read"]},
  {id:"devlab",name:"RiftOS Dev Lab",trusted:true,permissions:["fs.read","fs.write","process.read","system.settings"]},
  {id:"settings",name:"Settings",trusted:true,permissions:["system.settings","native.read","native.files"]},
  {id:"tasks",name:"Tasks",trusted:true,permissions:["process.read","process.manage"]},
  {id:"rift-apps",name:"Rift Apps",trusted:true,permissions:["fs.read","fs.write","process.read","process.manage","system.settings"]}
];

const CAPABILITIES = Object.freeze([
  "fs.read","fs.write","network",
  "clipboard.read","clipboard.write","share","notifications","build.local","repair.eval",
  "process.read","process.manage","system.settings",
  "native.read","native.files","native.background"
]);

const RIFT_CAPABILITY_REGISTRY = Object.freeze({
  filesystem:["fs.read","fs.write"],
  process:["process.read","process.manage"],
  build:["build.local"],
  repair:["repair.eval"],
  native:["native.read","native.files","native.background"],
  system:["system.settings"]
});

function normalizePath(value="/"){
  const raw=String(value||"/").replace(/\\/g,"/");
  const parts=[];
  for(const part of raw.split("/")){
    if(!part||part===".") continue;
    if(part==="..") throw new Error("Path traversal is not allowed");
    parts.push(part);
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
function isAbsolutePath(value){
  const raw=String(value??"").trim().replace(/\\/g,"/");
  return raw.startsWith("/")||/^[A-Za-z]:($|\/)/.test(raw);
}
function canonicalPath(value="/"){
  const normalized=normalizePath(value),mapped=volumeForPath(normalized);
  return mapped?mapped.physical:normalized;
}
function safeMountName(value){
  return String(value||"mount").trim().replace(/[\/\\]+/g,"-").replace(/\s+/g," ").slice(0,80)||"mount";
}
function sizeOf(value){return new TextEncoder().encode(String(value??"")).byteLength;}
function volumeForPath(value){
  const path=normalizePath(value),parts=path.split("/").filter(Boolean),letter=String(parts[0]||"").toUpperCase();
  const volume=RIFT_VOLUMES[letter];if(!volume)return null;
  if(parts.length===1)return {volume,path,virtualRoot:true,physical:volume.backing,rootName:null,tail:[]};
  const requestedRoot=parts[1],rootName=Object.keys(volume.roots).find(name=>name.toLowerCase()===requestedRoot.toLowerCase())||null;
  const tail=parts.slice(2),physical=rootName?joinPath(volume.roots[rootName],...tail):joinPath(volume.backing,...parts.slice(1));
  return {volume,path,virtualRoot:false,physical,rootName,tail};
}
function volumeDescriptors(){return Object.values(RIFT_VOLUMES).map(volume=>({letter:volume.letter,label:volume.label,path:`/${volume.letter}`,roots:Object.keys(volume.roots)}));}

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
    if(method==="fs.copy"||method==="fs.move"||method==="fs.remove"||method==="fs.sha256")return 15*60*1000;
    if(method==="fs.list"||method==="fs.readText"||method==="fs.writeText"||method==="fs.readBase64"||method==="fs.writeBase64")return 2*60*1000;
    if(method==="vortex.bridge")return 90*1000;
    if(method==="vortex.session")return 95*1000;
    if(method==="vortex.agent")return 15*1000;
    if(method==="riftos.agent")return 90*1000;
    if(method==="riftllm.train-data")return 2*60*1000;
    if(method==="chat.handoff")return 55*1000;
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
      localBuildExecutor:false,
      jsonPatches:true,
      patchRollback:true,
      vortexDevBridge:true,
      chatHandoff:true,
      vortexLocalAgent:true,
      riftOsLocalAgent:true
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


class RiftTransferQueue{
  constructor(){
    this.tail=Promise.resolve();
    this.pending=0;
  }
  run(task){
    if(typeof task!=="function")return Promise.reject(new TypeError("RiftTransferQueue task must be a function"));
    const execute=async()=>{
      this.pending++;
      try{return await task();}
      finally{this.pending=Math.max(0,this.pending-1);}
    };
    const result=this.tail.then(execute,execute);
    this.tail=result.then(()=>undefined,()=>undefined);
    return result;
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
    this.transferQueue=new RiftTransferQueue();
  }
  route(value){
    const path=normalizePath(value),mapped=volumeForPath(path);
    if(mapped)return {path,mountId:ROOT_MOUNT,relative:mapped.physical.replace(/^\/+/,""),backend:"rift-volume",mount:null,volume:mapped.volume,virtualVolumeRoot:mapped.virtualRoot,volumeRootName:mapped.rootName};
    const mount=this.resolveMount(path);
    if(mount)return {path,mountId:mount.mountId,relative:mount.relative||"",backend:"android-saf",mount,volume:null,virtualVolumeRoot:false};
    return {path,mountId:ROOT_MOUNT,relative:path.replace(/^\/+/,""),backend:"android-internal",mount:null,volume:null,virtualVolumeRoot:false};
  }
  async init(){
    if(this.ready)return this;
    if(!this.native.connected)throw new Error("RiftFS requires the Android native host on this branch.");
    await this.restoreNativeMounts().catch(error=>console.warn("[RiftFS] SAF mount restore failed",error));
    for(const path of ["home","apps","system","workspace","downloads","documents","system/riftos","system/programs","system/program-data","system/program-data/registry","system/program-data/installer","system/program-data/installer/staging","system/program-data/installer/rollback","system/toolchains","system/volumes","system/volumes/C","system/volumes/D","home/users","home/users/Default","home/users/Default/AppData","home/projects","home/temp","documents/packages","documents/builds","documents/vault"]){
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
    if(target.virtualVolumeRoot)return {path:target.path,kind:"directory",size:0,modified:0,backend:"rift-volume",volume:target.volume.letter,label:target.volume.label};
    if(target.mount&&!target.relative)return {path:target.path,kind:"mount",size:0,modified:0,backend:"android-saf",mountId:target.mountId};
    const stat=await this.native.call("fs.stat",{mountId:target.mountId,path:target.relative});
    return stat?{...stat,path:target.path,backend:target.backend,volume:target.volume?.letter||null}:null;
  }
  async openNative(value){
    const target=this.route(value);
    if(!target.mount||!target.relative)throw new Error("Native open is available for files inside Android mounts only");
    return this.native.call("files.open",{mountId:target.mountId,path:target.relative});
  }
  async get(value){
    const target=this.route(value);
    if(target.path==="/mounts")return {path:"/mounts",kind:"directory",size:0,modified:0,backend:"android-virtual"};
    if(target.virtualVolumeRoot)return {path:target.path,kind:"directory",size:0,modified:0,backend:"rift-volume",volume:target.volume.letter,label:target.volume.label};
    if(target.mount&&!target.relative)return {path:target.path,kind:"mount",size:0,modified:0,backend:"android-saf",mountId:target.mountId};
    const stat=await this.native.call("fs.stat",{mountId:target.mountId,path:target.relative});
    if(!stat)return null;
    if(stat.kind==="directory")return {...stat,path:target.path,backend:target.backend};
    const content=await this.native.call("fs.readText",{mountId:target.mountId,path:target.relative});
    return {...stat,path:target.path,content:String(content??""),backend:target.backend};
  }
  async readText(value){return (await this.get(value))?.content??null;}
  async readBase64(value){
    const target=this.route(value);
    if(target.path==="/mounts"||target.virtualVolumeRoot||target.mount&&!target.relative)throw new Error("Binary reads require a file path");
    return this.native.call("fs.readBase64",{mountId:target.mountId,path:target.relative});
  }
  async sha256(value){
    const target=this.route(value);
    if(target.path==="/mounts"||target.virtualVolumeRoot||target.mount&&!target.relative)throw new Error("SHA-256 requires a file path");
    return this.native.call("fs.sha256",{mountId:target.mountId,path:target.relative});
  }
  async write(value,content){
    const target=this.route(value);
    if(target.path==="/mounts"||target.virtualVolumeRoot||target.mount&&!target.relative)throw new Error("Cannot write over a mount or volume root");
    const text=String(content??"");
    const stat=await this.native.call("fs.writeText",{mountId:target.mountId,path:target.relative,text});
    const record={...stat,path:target.path,content:text,size:Number(stat?.size??sizeOf(text)),backend:target.backend};
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"write",path:target.path}}));
    return record;
  }
  writeText(value,content){return this.write(value,content);}
  async writeBase64(value,base64){
    const target=this.route(value);
    if(target.path==="/mounts"||target.virtualVolumeRoot||target.mount&&!target.relative)throw new Error("Binary writes require a file path");
    const stat=await this.native.call("fs.writeBase64",{mountId:target.mountId,path:target.relative,base64:String(base64||"")});
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"write",path:target.path}}));
    return {...stat,path:target.path,backend:target.backend};
  }
  async mkdir(value){
    const target=this.route(value);
    if(target.path==="/mounts")return {path:"/mounts",kind:"directory",backend:"android-virtual"};
    if(target.virtualVolumeRoot)return {path:target.path,kind:"directory",backend:"rift-volume",volume:target.volume.letter};
    if(target.mount&&!target.relative)return {path:target.path,kind:"mount",backend:"android-saf"};
    const stat=await this.native.call("fs.mkdir",{mountId:target.mountId,path:target.relative});
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"mkdir",path:target.path}}));
    return {...stat,path:target.path,backend:target.backend};
  }
  async remove(value){
    const target=this.route(value);
    if(target.path==="/"||PROTECTED_RIFT_ROOTS.has(target.path))throw new Error("Cannot delete a RiftFS system root");
    if(target.mount&&!target.relative)throw new Error("Unmount the folder instead of deleting the mount root");
    const removed=await this.native.call("fs.remove",{mountId:target.mountId,path:target.relative,recursive:true});
    if(removed!==true)throw new Error(`Android storage provider could not delete ${target.path}`);
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"remove",path:target.path}}));
    return true;
  }
  async zip(fromValue,toValue,{transferId=null}={}){
    const source=this.route(fromValue),destination=this.route(toValue);
    if(source.path==="/"||source.path==="/mounts"||source.virtualVolumeRoot||source.mount&&!source.relative)throw new Error("Cannot archive a filesystem, volume or mount root");
    if(destination.path==="/"||destination.path==="/mounts"||destination.virtualVolumeRoot||destination.mount&&!destination.relative)throw new Error("Archive destination must be a file");
    const stat=await this.transferQueue.run(()=>this.native.call("fs.zip",{
      fromMountId:source.mountId,from:source.relative,toMountId:destination.mountId,to:destination.relative,
      transferId:transferId||crypto.randomUUID?.()||`transfer-${Date.now()}`
    }));
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"zip",path:source.path,newPath:destination.path}}));
    return {...stat,path:destination.path,backend:destination.backend};
  }
  async unzip(fromValue,toValue,{transferId=null}={}){
    const source=this.route(fromValue),destination=this.route(toValue);
    if(source.path==="/"||source.path==="/mounts"||source.virtualVolumeRoot||source.mount&&!source.relative)throw new Error("Archive source must be a file");
    if(destination.path==="/mounts"||destination.virtualVolumeRoot||destination.mount&&!destination.relative)throw new Error("Choose a folder inside the destination volume or mounted filesystem");
    const stat=await this.transferQueue.run(()=>this.native.call("fs.unzip",{
      fromMountId:source.mountId,from:source.relative,toMountId:destination.mountId,to:destination.relative,
      transferId:transferId||crypto.randomUUID?.()||`transfer-${Date.now()}`
    }));
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"unzip",path:source.path,newPath:destination.path}}));
    return {...stat,path:destination.path,backend:destination.backend};
  }
  async copy(fromValue,toValue,{overwrite=false,transferId=null}={}){
    const source=this.route(fromValue),destination=this.route(toValue);
    if(source.path==="/"||source.path==="/mounts"||source.virtualVolumeRoot||source.mount&&!source.relative)throw new Error("Cannot copy a filesystem, volume or mount root");
    if(destination.path==="/"||destination.path==="/mounts"||destination.virtualVolumeRoot||destination.mount&&!destination.relative)throw new Error("Cannot replace a filesystem, volume or mount root");
    const stat=await this.transferQueue.run(()=>this.native.call("fs.copy",{
      fromMountId:source.mountId,from:source.relative,
      toMountId:destination.mountId,to:destination.relative,overwrite:overwrite===true,
      transferId:transferId||crypto.randomUUID?.()||`transfer-${Date.now()}`
    }));
    const record={...stat,path:destination.path,backend:destination.backend};
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"copy",path:source.path,newPath:destination.path}}));
    return record;
  }
  async move(fromValue,toValue,{overwrite=false,transferId=null}={}){
    const source=this.route(fromValue),destination=this.route(toValue);
    if(source.path==="/"||PROTECTED_RIFT_ROOTS.has(source.path)||source.virtualVolumeRoot||source.mount&&!source.relative)throw new Error("Cannot move a RiftFS system, volume or mount root");
    if(destination.path==="/"||destination.path==="/mounts"||destination.virtualVolumeRoot||destination.mount&&!destination.relative)throw new Error("Cannot replace a filesystem, volume or mount root");
    const stat=await this.transferQueue.run(()=>this.native.call("fs.move",{
      fromMountId:source.mountId,from:source.relative,
      toMountId:destination.mountId,to:destination.relative,overwrite:overwrite===true,
      transferId:transferId||crypto.randomUUID?.()||`transfer-${Date.now()}`
    }));
    const record={...stat,path:destination.path,backend:destination.backend};
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"move",path:source.path,newPath:destination.path}}));
    return record;
  }
  rename(value,newValue,options={}){return this.move(value,newValue,options);}
  createFile(value,text=""){return this.writeText(value,text);}
  async listVolumeRoot(path,volume,recursive){
    const out=[],seen=new Set(),push=row=>{if(!seen.has(row.path)){seen.add(row.path);out.push(row);}};
    const backing=await this.native.call("fs.list",{mountId:ROOT_MOUNT,path:volume.backing.replace(/^\/+/,""),recursive}).catch(()=>[]);
    for(const row of Array.isArray(backing)?backing:[])push({...row,path:joinPath(path,row.path||row.name||""),backend:"rift-volume",volume:volume.letter});
    for(const [name,physical] of Object.entries(volume.roots)){
      const display=joinPath(path,name);push({path:display,kind:"directory",size:0,modified:0,backend:"rift-volume",volume:volume.letter,systemAlias:true});
      if(!recursive)continue;
      const rows=await this.native.call("fs.list",{mountId:ROOT_MOUNT,path:physical.replace(/^\/+/,""),recursive}).catch(()=>[]);
      for(const row of Array.isArray(rows)?rows:[])push({...row,path:joinPath(display,row.path||row.name||""),backend:"rift-volume",volume:volume.letter,systemAlias:true});
    }
    return out.sort((a,b)=>a.path.localeCompare(b.path));
  }
  async list(value="/",options={}){
    const path=normalizePath(value),recursive=options.recursive!==false;
    if(path==="/mounts"){
      return [...this.mounts.values()].map(m=>({path:m.path,kind:"mount",size:0,modified:0,backend:"android-saf",mountId:m.mountId})).sort((a,b)=>a.path.localeCompare(b.path));
    }
    const volumeRoot=volumeForPath(path);if(volumeRoot?.virtualRoot)return this.listVolumeRoot(path,volumeRoot.volume,recursive);
    const target=this.route(path);
    const rows=await this.native.call("fs.list",{mountId:target.mountId,path:target.relative,recursive});
    const mapped=(Array.isArray(rows)?rows:[]).map(row=>({...row,path:joinPath(path,row.path||row.name||""),backend:target.backend,volume:target.volume?.letter||null}));
    if(path==="/"){
      mapped.push({path:"/C:",kind:"directory",size:0,modified:0,backend:"rift-volume",volume:"C:",label:"RiftOS System"});
      mapped.push({path:"/D:",kind:"directory",size:0,modified:0,backend:"rift-volume",volume:"D:",label:"User Data"});
      mapped.push({path:"/mounts",kind:"directory",size:0,modified:0,backend:"android-virtual"});
      if(recursive)for(const mount of this.mounts.values())mapped.push({path:mount.path,kind:"mount",size:0,modified:0,backend:"android-saf",mountId:mount.mountId});
    }
    return mapped.sort((a,b)=>a.path.localeCompare(b.path));
  }
  volumes(){return volumeDescriptors();}
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
  path:Object.freeze({normalize:normalizePath,parent:parentPath,basename,join:joinPath,isAbsolute:isAbsolutePath,canonical:canonicalPath})
});

console.info(`[RiftOS Android] RiftKernel ${CORE_VERSION} loading`);
