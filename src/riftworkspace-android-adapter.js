const core=window.RiftOSCore;
const inherited=window.RiftWorkspace;
const local=inherited?.local;
if(!core?.fs||!local)throw new Error("RiftWorkspace Android adapter requires the workspace core");

async function info(){
  const base=await local.info();
  return {...base,available:true,mode:"android-native",backend:"android-internal",nativeBridge:true,root:"/",storageRoot:"/workspace"};
}

async function copyTree(sourceFsPath,destinationWorkspacePath){
  const stat=await core.fs.stat(sourceFsPath);
  if(!stat)throw new Error(`Source does not exist: ${sourceFsPath}`);
  if(stat.kind==="file")return local.writeText(destinationWorkspacePath,String(await core.fs.readText(sourceFsPath)??""));
  await local.mkdir(destinationWorkspacePath).catch(()=>{});
  const rows=await core.fs.list(sourceFsPath,{recursive:true});
  for(const row of rows.filter(x=>x.kind==="directory").sort((a,b)=>a.path.length-b.path.length)){
    const suffix=row.path.slice(sourceFsPath.length).replace(/^\/+/ ,"");
    if(suffix)await local.mkdir(`${destinationWorkspacePath}/${suffix}`).catch(()=>{});
  }
  for(const row of rows.filter(x=>x.kind==="file")){
    const suffix=row.path.slice(sourceFsPath.length).replace(/^\/+/ ,"");
    await local.writeText(`${destinationWorkspacePath}/${suffix}`,String(await core.fs.readText(row.path)??""));
  }
  return true;
}

async function copyFromMount(mountId,path,destination){
  const mount=core.fs.mounts.get(String(mountId));
  if(!mount)throw new Error(`Unknown Android SAF mount: ${mountId}`);
  const source=core.path.join(mount.path,String(path||""));
  return copyTree(source,String(destination||core.path.basename(source)));
}

async function copyToMount(source,mountId,path){
  const mount=core.fs.mounts.get(String(mountId));
  if(!mount)throw new Error(`Unknown Android SAF mount: ${mountId}`);
  const sourceRel=local.normalize(source,{allowRoot:false});
  const sourceFull=local.fullPath(sourceRel,{allowRoot:false});
  const destination=core.path.join(mount.path,String(path||core.path.basename(sourceRel)));
  const stat=await core.fs.stat(sourceFull);
  if(!stat)throw new Error(`Workspace source does not exist: ${sourceRel}`);
  if(stat.kind==="file")return core.fs.writeText(destination,String(await core.fs.readText(sourceFull)??""));
  await core.fs.mkdir(destination);
  const rows=await core.fs.list(sourceFull,{recursive:true});
  for(const row of rows.filter(x=>x.kind==="directory").sort((a,b)=>a.path.length-b.path.length)){
    const suffix=row.path.slice(sourceFull.length).replace(/^\/+/ ,"");
    if(suffix)await core.fs.mkdir(core.path.join(destination,suffix));
  }
  for(const row of rows.filter(x=>x.kind==="file")){
    const suffix=row.path.slice(sourceFull.length).replace(/^\/+/ ,"");
    await core.fs.writeText(core.path.join(destination,suffix),String(await core.fs.readText(row.path)??""));
  }
  return true;
}

const workspace=Object.freeze({
  available:true,local,mode:"android-native",info,
  list:(path="",options={})=>local.list(path,options),
  stat:path=>local.stat(path),readText:path=>local.readText(path),readJSON:(path,fallback=null)=>local.readJSON(path,fallback),
  writeText:(path,text)=>local.writeText(path,text),writeJSON:(path,value)=>local.writeJSON(path,value),mkdir:path=>local.mkdir(path),
  remove:path=>local.remove(path),move:(path,newPath,options={})=>local.move(path,newPath,options),copy:(path,newPath,options={})=>local.copy(path,newPath,options),previewPatch:patch=>local.previewPatch(patch),
  applyPatch:patch=>local.applyPatch(patch),history:()=>local.history(),rollback:id=>local.rollback(id),snapshot:(path="",options={})=>local.snapshot(path,options),
  copyFromMount,copyToMount
});

window.RiftWorkspace=workspace;
try{Object.defineProperty(core.kernel,"workspace",{value:workspace,writable:false,configurable:true,enumerable:true});}catch{core.kernel.workspace=workspace;}
window.RiftOSCore=Object.freeze({...core,workspace});
console.info("[RiftWorkspace] Android-native workspace adapter online");
