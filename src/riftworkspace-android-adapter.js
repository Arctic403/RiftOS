const core=window.RiftOSCore;
const inherited=window.RiftWorkspace;
const local=inherited?.local;
if(!core?.fs||!local)throw new Error("RiftWorkspace Android adapter requires the workspace core");

async function info(){
  const base=await local.info();
  return {...base,available:true,mode:"android-native",backend:"android-internal",nativeBridge:true,root:"/",storageRoot:"/workspace"};
}
function mountedPath(mountId,path=""){
  const mount=core.fs.mounts.get(String(mountId));
  if(!mount)throw new Error(`Unknown Android SAF mount: ${mountId}`);
  return core.path.join(mount.path,String(path||""));
}
function workspacePath(path,fallback=""){
  const relative=local.normalize(path||fallback,{allowRoot:false});
  return {relative,full:local.fullPath(relative,{allowRoot:false})};
}
async function copyFromMount(mountId,path,destination,options={}){
  const source=mountedPath(mountId,path),sourceStat=await core.fs.stat(source);
  if(!sourceStat)throw new Error(`Mounted source does not exist: ${source}`);
  const target=workspacePath(destination,core.path.basename(source));
  return core.fs.copy(source,target.full,{overwrite:options.overwrite===true});
}
async function copyToMount(source,mountId,path,options={}){
  const sourcePath=workspacePath(source),sourceStat=await core.fs.stat(sourcePath.full);
  if(!sourceStat)throw new Error(`Workspace source does not exist: ${sourcePath.relative}`);
  return core.fs.copy(sourcePath.full,mountedPath(mountId,path||core.path.basename(sourcePath.relative)),{overwrite:options.overwrite===true});
}
async function moveFromMount(mountId,path,destination,options={}){
  const source=mountedPath(mountId,path),sourceStat=await core.fs.stat(source);
  if(!sourceStat)throw new Error(`Mounted source does not exist: ${source}`);
  const target=workspacePath(destination,core.path.basename(source));
  return core.fs.move(source,target.full,{overwrite:options.overwrite===true});
}
async function moveToMount(source,mountId,path,options={}){
  const sourcePath=workspacePath(source),sourceStat=await core.fs.stat(sourcePath.full);
  if(!sourceStat)throw new Error(`Workspace source does not exist: ${sourcePath.relative}`);
  return core.fs.move(sourcePath.full,mountedPath(mountId,path||core.path.basename(sourcePath.relative)),{overwrite:options.overwrite===true});
}
const workspace=Object.freeze({
  available:true,local,mode:"android-native",info,
  list:(path="",options={})=>local.list(path,options),
  stat:path=>local.stat(path),readText:path=>local.readText(path),readJSON:(path,fallback=null)=>local.readJSON(path,fallback),
  writeText:(path,text)=>local.writeText(path,text),writeJSON:(path,value)=>local.writeJSON(path,value),mkdir:path=>local.mkdir(path),
  remove:path=>local.remove(path),move:(path,newPath,options={})=>local.move(path,newPath,options),copy:(path,newPath,options={})=>local.copy(path,newPath,options),previewPatch:patch=>local.previewPatch(patch),
  applyPatch:patch=>local.applyPatch(patch),history:()=>local.history(),rollback:id=>local.rollback(id),snapshot:(path="",options={})=>local.snapshot(path,options),
  copyFromMount,copyToMount,moveFromMount,moveToMount
});
window.RiftWorkspace=workspace;
try{Object.defineProperty(core.kernel,"workspace",{value:workspace,writable:false,configurable:true,enumerable:true});}catch{core.kernel.workspace=workspace;}
window.RiftOSCore=Object.freeze({...core,workspace});
console.info("[RiftWorkspace] Android-native workspace adapter online");
