const core=globalThis.RiftOSCore;
if(!core?.kernel||!core?.fs)throw new Error("RiftAndroid platform requires RiftOSCore");

if(globalThis.RiftPlatform?.android&&core.native.connected){
  await core.ready;

  const fs=core.fs;
  const native=core.native;
  const pathApi=core.path;
  const ROOT_MOUNT="__riftfs__";
  const original={
    get:fs.get.bind(fs),
    list:fs.list.bind(fs),
    setting:fs.setting.bind(fs),
    setSetting:fs.setSetting.bind(fs)
  };

  const rel=value=>pathApi.normalize(value).replace(/^\/+/,"");
  const route=value=>{
    const path=pathApi.normalize(value),mount=fs.resolveMount(path);
    if(mount)return {path,mountId:mount.mountId,relative:mount.relative||"",backend:"android-saf",mount};
    return {path,mountId:ROOT_MOUNT,relative:rel(path),backend:"android-internal",mount:null};
  };
  const mapped=(stat,path,backend)=>stat?{...stat,path,backend}:null;

  async function migrateBrowserRiftFS(){
    let done=false;
    try{done=(await native.call("settings.get",{key:"android.riftfsMigration.v1"}))?.value===true;}catch{}
    if(done)return;
    let rows=[];
    try{rows=await original.list("/",{recursive:true});}catch{}
    const localRows=rows.filter(row=>!String(row.backend||"").includes("native")&&!row.path.startsWith("/mounts"));
    const dirs=localRows.filter(row=>row.kind==="directory").sort((a,b)=>a.path.length-b.path.length);
    const files=localRows.filter(row=>row.kind==="file");
    for(const row of dirs){
      await native.call("fs.mkdir",{mountId:ROOT_MOUNT,path:rel(row.path)}).catch(()=>{});
    }
    let copied=0;
    for(const row of files){
      try{
        const exists=await native.call("fs.stat",{mountId:ROOT_MOUNT,path:rel(row.path)});
        if(exists)continue;
        const source=await original.get(row.path);
        if(source?.kind!=="file")continue;
        await native.call("fs.writeText",{mountId:ROOT_MOUNT,path:rel(row.path),text:String(source.content??"")});
        copied++;
      }catch(error){console.warn("[RiftAndroid] migration skipped",row.path,error);}
    }
    await native.call("settings.set",{key:"android.riftfsMigration.v1",value:true}).catch(()=>{});
    console.info(`[RiftAndroid] RiftFS migration complete · ${copied} file(s) copied`);
  }

  await migrateBrowserRiftFS();

  fs.get=async value=>{
    const target=route(value);
    if(target.path==="/mounts")return {path:"/mounts",kind:"directory",size:0,modified:0,backend:"android-virtual"};
    if(target.mount&&!target.relative)return {path:target.path,kind:"directory",size:0,modified:0,backend:"android-saf"};
    const stat=await native.call("fs.stat",{mountId:target.mountId,path:target.relative});
    if(!stat)return null;
    if(stat.kind==="directory")return mapped(stat,target.path,target.backend);
    const content=await native.call("fs.readText",{mountId:target.mountId,path:target.relative});
    return {...stat,path:target.path,content:String(content??""),backend:target.backend};
  };
  fs.readText=async value=>(await fs.get(value))?.content??null;

  fs.write=async(value,content)=>{
    const target=route(value);
    if(target.path==="/mounts"||target.mount&&!target.relative)throw new Error("Cannot write over a mount root");
    const stat=await native.call("fs.writeText",{mountId:target.mountId,path:target.relative,text:String(content)});
    const record={...stat,path:target.path,content:String(content),backend:target.backend};
    fs.dispatchEvent(new CustomEvent("change",{detail:{type:"write",path:target.path}}));
    return record;
  };
  fs.writeText=(value,content)=>fs.write(value,content);

  fs.mkdir=async value=>{
    const target=route(value);
    if(target.path==="/mounts")return {path:"/mounts",kind:"directory",backend:"android-virtual"};
    if(target.mount&&!target.relative)return {path:target.path,kind:"directory",backend:"android-saf"};
    const stat=await native.call("fs.mkdir",{mountId:target.mountId,path:target.relative});
    fs.dispatchEvent(new CustomEvent("change",{detail:{type:"mkdir",path:target.path}}));
    return {...stat,path:target.path,backend:target.backend};
  };

  fs.remove=async value=>{
    const target=route(value);
    if(target.path==="/mounts")throw new Error("Cannot delete the mount table");
    if(target.mount&&!target.relative)throw new Error("Unmount the directory instead of deleting the mount root");
    await native.call("fs.remove",{mountId:target.mountId,path:target.relative,recursive:true});
    fs.dispatchEvent(new CustomEvent("change",{detail:{type:"remove",path:target.path}}));
    return true;
  };

  fs.stat=async value=>{
    const target=route(value);
    if(target.path==="/mounts")return {path:"/mounts",kind:"directory",size:0,modified:0,backend:"android-virtual"};
    if(target.mount&&!target.relative)return {path:target.path,kind:"directory",size:0,modified:0,backend:"android-saf"};
    return mapped(await native.call("fs.stat",{mountId:target.mountId,path:target.relative}),target.path,target.backend);
  };

  fs.list=async(value="/",options={})=>{
    const path=pathApi.normalize(value),recursive=options.recursive!==false;
    if(path==="/mounts")return [...fs.mounts.values()].map(m=>({path:m.path,kind:"mount",size:0,modified:0,backend:"android-saf",mountId:m.mountId}));
    const target=route(path);
    if(target.mount&&!target.relative&&recursive===false)return [];
    const rows=await native.call("fs.list",{mountId:target.mountId,path:target.relative,recursive});
    const mappedRows=(Array.isArray(rows)?rows:[]).map(row=>({...row,path:pathApi.join(path,row.path||row.name||""),backend:target.backend}));
    if(path==="/"){
      mappedRows.push({path:"/mounts",kind:"directory",size:0,modified:0,backend:"android-virtual"});
      if(recursive)for(const mount of fs.mounts.values())mappedRows.push({path:mount.path,kind:"mount",size:0,modified:0,backend:"android-saf",mountId:mount.mountId});
    }
    return mappedRows.sort((a,b)=>a.path.localeCompare(b.path));
  };

  fs.setting=key=>native.call("settings.get",{key:String(key)});
  fs.setSetting=(key,value)=>native.call("settings.set",{key:String(key),value});
  fs.estimate=()=>native.call("system.storage",{});
  fs.persist=async()=>true;
  fs.syncLegacy=async()=>({copiedToOPFS:0,copiedToLegacy:0,native:true});
  fs.readJSON=async(value,fallback=null)=>{const text=await fs.readText(value);if(text==null)return fallback;try{return JSON.parse(text);}catch{return fallback;}};
  fs.writeJSON=(value,data)=>fs.write(value,JSON.stringify(data,null,2));
  fs.mountNativeDirectory=async()=>fs.attachNativeMount(await native.call("files.pickDirectory",{}));
  fs.opfsReady=false;
  fs.nativeRootReady=true;

  const baseCapabilities=native.capabilities.bind(native);
  native.capabilities=()=>({
    ...baseCapabilities(),nativeHost:true,platform:"android",android:true,opfs:false,
    serviceWorker:false,backgroundSync:false,share:true,notifications:true,clipboard:true,
    browser:true,workspace:true,jsonPatches:true,patchRollback:true
  });

  core.kernel.mounts=()=>[
    {path:"/",type:"android-internal",mode:"rw",label:"RiftFS"},
    ...[...fs.mounts.values()].map(m=>({path:m.path,type:"android-saf",mode:m.mode,label:m.name,mountId:m.mountId}))
  ];
  core.kernel.info=async()=>({
    name:"RiftOS",version:core.kernel.version,mode:"android-apk",uptime:core.kernel.uptime(),
    storage:await fs.estimate(),native:native.capabilities(),processes:core.processes.list().length,
    apps:core.kernel.apps.size,mounts:core.kernel.mounts().length
  });

  globalThis.RiftAndroidAPI=Object.freeze({
    info:()=>native.call("device.info",{}),
    vibrate:(milliseconds=40)=>native.call("device.vibrate",{milliseconds}),
    clipboard:Object.freeze({read:()=>native.call("clipboard.read",{}),write:text=>native.call("clipboard.write",{text:String(text??"")})}),
    share:(text,title="Share from RiftOS")=>native.call("share.text",{text:String(text??""),title}),
    open:url=>native.call("intent.open",{url:String(url)}),
    notify:(title,body="")=>native.call("notifications.show",{title:String(title||"RiftOS"),body:String(body||"")})
  });

  const relabel=()=>{
    document.querySelectorAll("button").forEach(el=>{if(el.textContent?.trim()==="Mount iOS folder")el.textContent="Mount Android folder";});
    document.querySelectorAll("small").forEach(el=>{if(el.textContent?.includes("Apple WebKit · desktop default"))el.textContent=el.textContent.replace("Apple WebKit · desktop default","RiftWebKit · Android host");});
    document.querySelectorAll(".trueos-chip").forEach(el=>{if(el.textContent?.trim()==="COMPAT")el.textContent="ANDROID";if(el.textContent?.trim()==="PWA MODE")el.textContent="ANDROID";});
  };
  new MutationObserver(relabel).observe(document.documentElement,{subtree:true,childList:true});
  queueMicrotask(relabel);

  console.info("[RiftAndroid] native platform adapter online");
}
