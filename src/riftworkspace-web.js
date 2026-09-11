const previousCore=window.RiftOSCore;
if(!previousCore?.kernel||!previousCore?.fs)throw new Error("RiftWorkspace Web requires RiftOSCore");

const WORKSPACE_ROOT="/workspace";
const HISTORY_ROOT="/system/riftworkspace/history";
const ROLLED_BACK_ROOT="/system/riftworkspace/rolled-back";
const LEGACY_META_ROOT=`${WORKSPACE_ROOT}/.rift`;
const LEGACY_SCAFFOLD_DIRS=["projects","downloads","documents","patches"];
const LAYOUT_MIGRATION_MARKER="/system/riftworkspace/layout-v2-migrated";
const MAX_PATCH_CHANGES=500;

function clone(value){return value==null?value:JSON.parse(JSON.stringify(value));}
function isObject(value){return !!value&&typeof value==="object"&&!Array.isArray(value);}
function nowISO(){return new Date().toISOString();}
function randomID(){return crypto.randomUUID?.()||`rift-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;}

class RiftWorkspaceWeb extends EventTarget{
  constructor(core){
    super();
    this.core=core;
    this.fs=core.fs;
    this.root=WORKSPACE_ROOT;
    this.ready=this.init();
  }

  async init(){
    await this.core.ready;
    await this.fs.mkdir(WORKSPACE_ROOT);
    await this.migrateLegacyWorkspaceScaffold();
    return this;
  }

  async migrateLegacyWorkspaceScaffold(){
    if(await this.fs.stat(LAYOUT_MIGRATION_MARKER).catch(()=>null))return;
    const legacyMeta=await this.fs.stat(LEGACY_META_ROOT).catch(()=>null);
    if(legacyMeta?.kind==="directory"){
      const rows=await this.fs.list(LEGACY_META_ROOT,{recursive:true}).catch(()=>[]);
      for(const row of rows.filter(item=>item.kind==="directory").sort((a,b)=>a.path.length-b.path.length)){
        const suffix=row.path.slice(LEGACY_META_ROOT.length).replace(/^\/+/,"");
        if(suffix)await this.fs.mkdir(`/system/riftworkspace/${suffix}`).catch(()=>{});
      }
      for(const row of rows.filter(item=>item.kind==="file")){
        const suffix=row.path.slice(LEGACY_META_ROOT.length).replace(/^\/+/,"");
        if(!suffix)continue;
        const destination=`/system/riftworkspace/${suffix}`;
        const exists=await this.fs.stat(destination).catch(()=>null);
        if(!exists){
          const content=await this.fs.readText(row.path).catch(()=>null);
          if(content!=null)await this.fs.writeText(destination,String(content));
        }
      }
      await this.fs.remove(LEGACY_META_ROOT).catch(()=>{});
    }
    for(const name of LEGACY_SCAFFOLD_DIRS){
      const path=this.fullPath(name,{allowRoot:true});
      const stat=await this.fs.stat(path).catch(()=>null);
      if(stat?.kind!=="directory")continue;
      const children=await this.fs.list(path,{recursive:false}).catch(()=>[]);
      if(children.length===0)await this.fs.remove(path).catch(()=>{});
    }
    await this.fs.writeText(LAYOUT_MIGRATION_MARKER,"1").catch(()=>{});
  }

  normalize(path="",{allowRoot=true}={}){
    const raw=String(path??"").trim().replace(/\\/g,"/");
    if(raw.includes("\0"))throw new Error("Workspace path contains a null byte");
    const normalized=this.core.path.normalize("/"+raw).replace(/^\/+/,"");
    if(!allowRoot&&!normalized)throw new Error("Workspace root is not editable");
    return normalized;
  }

  fullPath(path="",options={}){
    const relative=this.normalize(path,options);
    const full=relative?this.core.path.join(WORKSPACE_ROOT,relative):WORKSPACE_ROOT;
    if(full!==WORKSPACE_ROOT&&!full.startsWith(WORKSPACE_ROOT+"/"))throw new Error("Workspace path escaped the sandbox");
    return full;
  }

  relativePath(fullPath){
    const full=this.core.path.normalize(fullPath);
    if(full===WORKSPACE_ROOT)return "";
    if(!full.startsWith(WORKSPACE_ROOT+"/"))throw new Error("Path is outside RiftWorkspace");
    return full.slice(WORKSPACE_ROOT.length+1);
  }

  assertEditable(path){
    const relative=this.normalize(path,{allowRoot:false});
    if(relative===".rift"||relative.startsWith(".rift/"))throw new Error(".rift is reserved for RiftKernel workspace history");
    return relative;
  }

  isHidden(path){return this.normalize(path).split("/").some(part=>part.startsWith("."));}

  async info(){
    await this.ready;
    const storage=await this.fs.estimate();
    return {
      available:true,
      mode:"local-sandbox",
      root:"/",
      storageRoot:WORKSPACE_ROOT,
      backend:this.fs.opfsReady?"opfs":"indexeddb",
      nativeBridge:!!this.core.native?.connected,
      jsonRPC:true,
      patchFormat:"riftcity-ai-patch",
      patchVersions:[1,2],
      patchActions:["write","delete","move","rename"],
      rollback:true,
      storage
    };
  }

  async list(path="",options={}){
    await this.ready;
    const relative=this.normalize(path);
    const rows=await this.fs.list(this.fullPath(relative),{recursive:options.recursive!==false});
    return rows
      .map(row=>({...row,path:this.relativePath(row.path)}))
      .filter(row=>options.includeHidden===true||!this.isHidden(row.path));
  }

  async stat(path=""){
    await this.ready;
    const relative=this.normalize(path);
    const row=await this.fs.stat(this.fullPath(relative));
    return row?{...row,path:relative}:null;
  }

  async readText(path){
    await this.ready;
    const relative=this.normalize(path,{allowRoot:false});
    const stat=await this.stat(relative);
    if(!stat)throw new Error(`Workspace file does not exist: ${relative}`);
    if(stat.kind!=="file")throw new Error(`Workspace path is not a file: ${relative}`);
    return String(await this.fs.readText(this.fullPath(relative))??"");
  }

  async readJSON(path,fallback=null){
    const text=await this.readText(path);
    try{return JSON.parse(text);}catch{return fallback;}
  }

  async writeText(path,text){
    await this.ready;
    const relative=this.assertEditable(path);
    const row=await this.fs.writeText(this.fullPath(relative),String(text??""));
    const result={...row,path:relative};
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"write",path:relative}}));
    return result;
  }

  writeJSON(path,value){return this.writeText(path,JSON.stringify(value,null,2));}

  async mkdir(path){
    await this.ready;
    const relative=this.assertEditable(path);
    await this.fs.mkdir(this.fullPath(relative));
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"mkdir",path:relative}}));
    return {path:relative,kind:"directory"};
  }

  async remove(path){
    await this.ready;
    const relative=this.assertEditable(path);
    await this.fs.remove(this.fullPath(relative));
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"remove",path:relative}}));
    return true;
  }

  async copyTree(sourceFull,destinationFull){
    const source=await this.fs.stat(sourceFull);
    if(!source)throw new Error(`Source does not exist: ${this.relativePath(sourceFull)}`);
    if(source.kind==="file"){
      await this.fs.writeText(destinationFull,String(await this.fs.readText(sourceFull)??""));
      return;
    }
    await this.fs.mkdir(destinationFull);
    const rows=await this.fs.list(sourceFull,{recursive:true});
    for(const row of rows.filter(item=>item.kind==="directory").sort((a,b)=>a.path.length-b.path.length)){
      const suffix=row.path.slice(sourceFull.length).replace(/^\/+/,"");
      await this.fs.mkdir(this.core.path.join(destinationFull,suffix));
    }
    for(const row of rows.filter(item=>item.kind==="file")){
      const suffix=row.path.slice(sourceFull.length).replace(/^\/+/,"");
      await this.fs.writeText(this.core.path.join(destinationFull,suffix),String(await this.fs.readText(row.path)??""));
    }
  }

  async copy(path,newPath,{overwrite=false}={}){
    await this.ready;
    const sourceRelative=this.assertEditable(path);
    const destinationRelative=this.assertEditable(newPath);
    if(sourceRelative===destinationRelative)throw new Error("Copy source and destination are identical");
    if(destinationRelative.startsWith(sourceRelative+"/"))throw new Error("Cannot copy a directory inside itself");
    const sourceFull=this.fullPath(sourceRelative),destinationFull=this.fullPath(destinationRelative);
    if(!(await this.fs.stat(sourceFull)))throw new Error(`Workspace source does not exist: ${sourceRelative}`);
    if(await this.fs.stat(destinationFull)){
      if(!overwrite)throw new Error(`Workspace destination already exists: ${destinationRelative}`);
    }
    if(typeof this.fs.copy==="function")await this.fs.copy(sourceFull,destinationFull,{overwrite});
    else await this.copyTree(sourceFull,destinationFull);
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"copy",path:sourceRelative,newPath:destinationRelative}}));
    return {path:sourceRelative,newPath:destinationRelative};
  }

  async move(path,newPath,{overwrite=false}={}){
    await this.ready;
    const sourceRelative=this.assertEditable(path);
    const destinationRelative=this.assertEditable(newPath);
    if(sourceRelative===destinationRelative)throw new Error("Move source and destination are identical");
    if(destinationRelative.startsWith(sourceRelative+"/"))throw new Error("Cannot move a directory inside itself");
    const sourceFull=this.fullPath(sourceRelative),destinationFull=this.fullPath(destinationRelative);
    if(!(await this.fs.stat(sourceFull)))throw new Error(`Workspace source does not exist: ${sourceRelative}`);
    if(await this.fs.stat(destinationFull)){
      if(!overwrite)throw new Error(`Workspace destination already exists: ${destinationRelative}`);
    }
    if(typeof this.fs.move==="function")await this.fs.move(sourceFull,destinationFull,{overwrite});
    else {await this.copyTree(sourceFull,destinationFull);await this.fs.remove(sourceFull);}
    this.dispatchEvent(new CustomEvent("change",{detail:{type:"move",path:sourceRelative,newPath:destinationRelative}}));
    return {path:sourceRelative,newPath:destinationRelative};
  }

  async sha256(path){
    const bytes=new TextEncoder().encode(await this.readText(path));
    const digest=await crypto.subtle.digest("SHA-256",bytes);
    return [...new Uint8Array(digest)].map(byte=>byte.toString(16).padStart(2,"0")).join("");
  }

  decodePatch(input){
    let patch=input;
    if(typeof patch==="string"){
      try{patch=JSON.parse(patch);}catch(error){throw new Error(`Patch JSON is invalid: ${error.message}`);}
    }
    if(!isObject(patch))throw new Error("Patch must be a JSON object");
    if(patch.format!=null&&patch.format!=="riftcity-ai-patch")throw new Error(`Unsupported patch format: ${patch.format}`);
    if(patch.version!==1&&patch.version!==2)throw new Error("Patch version must be 1 or 2");
    if(!Array.isArray(patch.changes)||patch.changes.length===0)throw new Error("Patch has no changes");
    if(patch.changes.length>MAX_PATCH_CHANGES)throw new Error(`Patch exceeds the ${MAX_PATCH_CHANGES}-change limit`);
    return {
      ...patch,
      changes:patch.changes.map((change,index)=>{
        if(!isObject(change))throw new Error(`Patch change ${index+1} is invalid`);
        const action=String(change.action||"").toLowerCase()==="rename"?"move":String(change.action||"").toLowerCase();
        return {...change,action};
      })
    };
  }

  assertNoOverlap(path,used){
    for(const other of used){
      if(path===other||path.startsWith(other+"/")||other.startsWith(path+"/"))throw new Error(`Patch paths overlap: ${path} and ${other}`);
    }
  }

  async validatePatch(input){
    await this.ready;
    const patch=this.decodePatch(input),used=[],rows=[];
    for(const change of patch.changes){
      if(!["write","delete","move"].includes(change.action))throw new Error(`Unsupported patch action: ${change.action}`);
      if(patch.version===1&&change.action==="move")throw new Error("Move/rename requires patch version 2");
      const path=this.assertEditable(change.path);
      this.assertNoOverlap(path,used);used.push(path);
      const stat=await this.stat(path),exists=!!stat;
      let actualHash=null;
      if(exists&&stat.kind==="file")actualHash=await this.sha256(path);
      const hasBase=Object.prototype.hasOwnProperty.call(change,"base_sha256");
      if(hasBase){
        if(change.base_sha256==null){
          if(change.action!=="write")throw new Error(`${change.action} cannot use base_sha256:null: ${path}`);
          if(exists)throw new Error(`Patch expected a new file, but it already exists: ${path}`);
        }else{
          if(!exists)throw new Error(`Patch expected this file to exist: ${path}`);
          if(stat.kind!=="file")throw new Error(`Patch hash target is not a file: ${path}`);
          if(String(change.base_sha256).toLowerCase()!==actualHash.toLowerCase())throw new Error(`Workspace file changed since the patch was created: ${path}`);
        }
      }
      if(change.action==="write"&&change.content==null)throw new Error(`Write is missing content: ${path}`);
      if(change.action!=="write"&&!exists)throw new Error(`Patch source does not exist: ${path}`);
      const row={action:change.action,path,exists,baseHashMatches:hasBase?((change.base_sha256==null&&!exists)||String(change.base_sha256).toLowerCase()===String(actualHash).toLowerCase()):null};
      if(change.reason!=null)row.reason=String(change.reason);
      if(change.action==="move"){
        if(!change.new_path)throw new Error(`Move is missing new_path: ${path}`);
        const newPath=this.assertEditable(change.new_path);
        if(newPath===path)throw new Error(`Move source and destination are identical: ${path}`);
        this.assertNoOverlap(newPath,used);used.push(newPath);
        if(await this.stat(newPath))throw new Error(`Move destination already exists: ${newPath}`);
        row.newPath=newPath;
      }
      rows.push(row);
    }
    return {patch,rows};
  }

  async previewPatch(input){
    const {patch,rows}=await this.validatePatch(input);
    return {valid:true,title:String(patch.title||"AI patch"),targetRepo:patch.target_repo??null,targetBranch:patch.target_branch??null,changes:rows};
  }

  async snapshotPath(path){
    const relative=this.normalize(path,{allowRoot:false}),stat=await this.stat(relative);
    if(!stat)return {path:relative,existed:false};
    if(stat.kind==="file")return {path:relative,existed:true,kind:"file",content:await this.readText(relative)};
    const entries=await this.list(relative,{recursive:true,includeHidden:true});
    const payload=[];
    for(const entry of entries){
      const sub=entry.path.slice(relative.length).replace(/^\/+/,"");
      if(entry.kind==="file")payload.push({path:sub,kind:"file",content:await this.readText(entry.path)});
      else payload.push({path:sub,kind:"directory"});
    }
    return {path:relative,existed:true,kind:"directory",entries:payload};
  }

  async rawRestore(snapshot){
    const full=this.fullPath(snapshot.path,{allowRoot:false});
    if(await this.fs.stat(full))await this.fs.remove(full);
    if(!snapshot.existed)return;
    if(snapshot.kind==="file"){
      await this.fs.writeText(full,snapshot.content??"");
      return;
    }
    await this.fs.mkdir(full);
    for(const entry of (snapshot.entries||[]).filter(item=>item.kind==="directory").sort((a,b)=>a.path.length-b.path.length))await this.fs.mkdir(this.core.path.join(full,entry.path));
    for(const entry of (snapshot.entries||[]).filter(item=>item.kind==="file"))await this.fs.writeText(this.core.path.join(full,entry.path),entry.content??"");
  }

  affectedPaths(patch){
    const paths=[];
    for(const change of patch.changes){
      paths.push(this.assertEditable(change.path));
      if(change.action==="move")paths.push(this.assertEditable(change.new_path));
    }
    return [...new Set(paths)].sort();
  }

  async applyPatch(input){
    const {patch}=await this.validatePatch(input);
    const id=randomID().toLowerCase(),historyDir=`${HISTORY_ROOT}/${id}`;
    const affected=this.affectedPaths(patch),backups=[];
    for(const path of affected)backups.push(await this.snapshotPath(path));
    try{
      for(const change of patch.changes){
        if(change.action==="write")await this.writeText(change.path,String(change.content??""));
        else if(change.action==="delete")await this.remove(change.path);
        else if(change.action==="move"){
          await this.move(change.path,change.new_path);
          if(change.content!=null)await this.writeText(change.new_path,String(change.content));
        }
      }
      const record={
        id,
        title:String(patch.title||"AI patch").slice(0,160),
        appliedAt:nowISO(),
        patchCreatedAt:patch.created_at??null,
        targetRepo:patch.target_repo??null,
        targetBranch:patch.target_branch??null,
        changes:patch.changes.length,
        backups
      };
      await this.fs.mkdir(historyDir);
      await this.fs.writeJSON(`${historyDir}/record.json`,record);
      await this.fs.writeJSON(`${historyDir}/patch.json`,patch);
      this.dispatchEvent(new CustomEvent("patch",{detail:{type:"apply",id,title:record.title,changes:record.changes}}));
      return {applied:true,historyId:id,title:record.title,changes:record.changes,rollbackAvailable:true};
    }catch(error){
      for(const snapshot of backups.sort((a,b)=>b.path.length-a.path.length))await this.rawRestore(snapshot).catch(()=>{});
      await this.fs.remove(historyDir).catch(()=>{});
      throw error;
    }
  }

  async history(){
    await this.ready;
    let rows=[];
    try{rows=await this.fs.list(HISTORY_ROOT,{recursive:false});}catch{return [];}
    const out=[];
    for(const row of rows.filter(item=>item.kind==="directory")){
      const record=await this.fs.readJSON(`${row.path}/record.json`,null);
      if(record)out.push({id:record.id,title:record.title,appliedAt:record.appliedAt,changes:record.changes,targetRepo:record.targetRepo??null,targetBranch:record.targetBranch??null});
    }
    return out.sort((a,b)=>String(b.appliedAt).localeCompare(String(a.appliedAt)));
  }

  async rollback(historyId=null){
    await this.ready;
    const id=historyId||((await this.history())[0]?.id);
    if(!id)throw new Error("No workspace patch history is available");
    if(!/^[a-zA-Z0-9-]+$/.test(String(id)))throw new Error("Invalid history id");
    const historyDir=`${HISTORY_ROOT}/${id}`;
    const record=await this.fs.readJSON(`${historyDir}/record.json`,null);
    if(!record)throw new Error(`Workspace history entry not found: ${id}`);
    for(const snapshot of [...record.backups].sort((a,b)=>b.path.length-a.path.length))await this.rawRestore(snapshot);
    await this.fs.mkdir(ROLLED_BACK_ROOT);
    await this.fs.writeJSON(`${ROLLED_BACK_ROOT}/${id}-${Date.now()}.json`,{...record,rolledBackAt:nowISO()});
    await this.fs.remove(historyDir);
    this.dispatchEvent(new CustomEvent("patch",{detail:{type:"rollback",id,title:record.title,changes:record.changes}}));
    return {rolledBack:true,historyId:id,title:record.title,changes:record.changes};
  }

  async snapshot(path="",options={}){
    const root=this.normalize(path),rows=await this.list(root,{recursive:true,includeHidden:options.includeHidden===true});
    const files=[];
    for(const row of rows){
      const item={path:row.path,kind:row.kind,size:row.size||0,modified:row.modified||0};
      if(row.kind==="file"&&options.includeContent!==false)item.content=await this.readText(row.path);
      files.push(item);
    }
    return {format:"riftos-workspace-snapshot",version:1,root,createdAt:nowISO(),files};
  }
}

const localWorkspace=new RiftWorkspaceWeb(previousCore);

function useNative(){return !!previousCore.native?.connected;}
function nativeCall(method,args={}){return previousCore.native.call(method,args);}

const workspace=Object.freeze({
  get available(){return true;},
  get local(){return localWorkspace;},
  get mode(){return useNative()?"native-workspace":"local-sandbox";},
  info:()=>useNative()?nativeCall("workspace.info",{}):localWorkspace.info(),
  list:(path="",options={})=>useNative()?nativeCall("workspace.list",{path,recursive:options.recursive!==false,includeHidden:options.includeHidden===true}):localWorkspace.list(path,options),
  stat:(path)=>useNative()?nativeCall("workspace.stat",{path}):localWorkspace.stat(path),
  readText:(path)=>useNative()?nativeCall("workspace.readText",{path}):localWorkspace.readText(path),
  readJSON:(path,fallback=null)=>useNative()?nativeCall("workspace.readText",{path}).then(text=>{try{return JSON.parse(text);}catch{return fallback;}}):localWorkspace.readJSON(path,fallback),
  writeText:(path,text)=>useNative()?nativeCall("workspace.writeText",{path,text:String(text??"")}):localWorkspace.writeText(path,text),
  writeJSON:(path,value)=>useNative()?nativeCall("workspace.writeText",{path,text:JSON.stringify(value,null,2)}):localWorkspace.writeJSON(path,value),
  mkdir:(path)=>useNative()?nativeCall("workspace.mkdir",{path}):localWorkspace.mkdir(path),
  remove:(path)=>useNative()?nativeCall("workspace.remove",{path}):localWorkspace.remove(path),
  move:(path,newPath,options={})=>useNative()?nativeCall("workspace.move",{path,newPath,...options}):localWorkspace.move(path,newPath,options),
  copy:(path,newPath,options={})=>useNative()?nativeCall("workspace.copy",{path,newPath,...options}):localWorkspace.copy(path,newPath,options),
  previewPatch:(patch)=>useNative()?nativeCall("workspace.previewPatch",{patch}):localWorkspace.previewPatch(patch),
  applyPatch:(patch)=>useNative()?nativeCall("workspace.applyPatch",{patch}):localWorkspace.applyPatch(patch),
  history:()=>useNative()?nativeCall("workspace.history",{}):localWorkspace.history(),
  rollback:(historyId=null)=>useNative()?nativeCall("workspace.rollback",historyId?{historyId}:{}):localWorkspace.rollback(historyId),
  snapshot:(path="",options={})=>localWorkspace.snapshot(path,options),
  copyFromMount:(mountId,path,destination)=>useNative()?nativeCall("workspace.copyFromMount",{mountId,path,destination}):Promise.reject(new Error("External Files mounts require the optional native host")),
  copyToMount:(source,mountId,path)=>useNative()?nativeCall("workspace.copyToMount",{source,mountId,path}):Promise.reject(new Error("External Files mounts require the optional native host"))
});

async function invoke(request={}){
  const id=request?.id??null,method=String(request?.method||""),args=isObject(request?.args)?request.args:{};
  const methods={
    "workspace.info":()=>workspace.info(),
    "workspace.list":()=>workspace.list(args.path||"",args),
    "workspace.stat":()=>workspace.stat(args.path||""),
    "workspace.readText":()=>workspace.readText(args.path),
    "workspace.readJSON":()=>workspace.readJSON(args.path,args.fallback??null),
    "workspace.writeText":()=>workspace.writeText(args.path,args.text??""),
    "workspace.writeJSON":()=>workspace.writeJSON(args.path,args.value),
    "workspace.mkdir":()=>workspace.mkdir(args.path),
    "workspace.remove":()=>workspace.remove(args.path),
    "workspace.move":()=>workspace.move(args.path,args.newPath??args.new_path,{overwrite:args.overwrite===true}),
    "workspace.copy":()=>workspace.copy(args.path,args.newPath??args.new_path,{overwrite:args.overwrite===true}),
    "workspace.previewPatch":()=>workspace.previewPatch(args.patch??args.json),
    "workspace.applyPatch":()=>workspace.applyPatch(args.patch??args.json),
    "workspace.history":()=>workspace.history(),
    "workspace.rollback":()=>workspace.rollback(args.historyId??null),
    "workspace.snapshot":()=>workspace.snapshot(args.path||"",args)
  };
  if(!methods[method])return {id,ok:false,error:`Unsupported RiftWorkspace JSON method: ${method}`};
  try{return {id,ok:true,result:clone(await methods[method]())};}
  catch(error){return {id,ok:false,error:error?.message||String(error)};}
}

window.RiftWorkspace=workspace;
try{Object.defineProperty(previousCore.kernel,"workspace",{value:workspace,writable:false,configurable:true,enumerable:true});}
catch{previousCore.kernel.workspace=workspace;}
window.RiftOSCore=Object.freeze({...previousCore,workspace});
window.RiftWorkspaceJSON=Object.freeze({invoke});

window.addEventListener("message",event=>{
  if(event.origin!==location.origin||event.data?.type!=="riftworkspace:request")return;
  invoke(event.data.request).then(response=>event.source?.postMessage({type:"riftworkspace:response",response},event.origin));
});

console.info("[RiftWorkspace] OPFS/IndexedDB sandbox + JSON patch bridge online",{root:WORKSPACE_ROOT,native:useNative()});
