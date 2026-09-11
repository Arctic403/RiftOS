const core=window.RiftOSCore;
const workspace=window.RiftWorkspace;
if(!core?.native?.connected||!workspace?.available)throw new Error("Rift Workspace Live requires Android RiftWorkspace");

const CHANNEL="riftworkspace-live-v1";
const nativeListeners=new Set();
let watcherState={active:false};
let activeSurface=null;

globalThis.RiftWorkspaceNative=Object.freeze({
  __event(event){
    const next=event&&typeof event==="object"?event:{};
    for(const listener of [...nativeListeners]){try{listener(next);}catch(error){console.warn("[RiftWorkspaceLive] event listener failed",error);}}
  }
});

function normalize(path=""){
  return workspace.local.normalize(path,{allowRoot:true});
}
function corePath(path="",{allowRoot=true}={}){
  const clean=normalize(path);
  if(!allowRoot&&!clean)throw new Error("Workspace root is not editable");
  return clean?`workspace/${clean}`:"workspace";
}
function fromCorePath(path=""){
  const value=String(path||"").replace(/\\/g,"/").replace(/^\/+/,"");
  return value==="workspace"?"":value.startsWith("workspace/")?value.slice("workspace/".length):value;
}
function normalizeCoreRow(row){
  if(!row||typeof row!=="object")return row;
  const path=fromCorePath(row.path||"");
  return {...row,path,name:row.name||path.split("/").pop()||"workspace"};
}
async function coreCall(method,args={}){
  return core.native.call("workspace.core.call",{method,args});
}
async function read(path){
  const clean=normalize(path);if(!clean)throw new Error("Select a file");
  const target=corePath(clean,{allowRoot:false});
  const [text,stat]=await Promise.all([
    coreCall("fs.readText",{path:target}),
    coreCall("fs.stat",{path:target})
  ]);
  if(!stat)throw new Error(`Workspace file does not exist: ${clean}`);
  const bytes=new TextEncoder().encode(String(text??""));
  const digest=stat.sha256||[...new Uint8Array(await crypto.subtle.digest("SHA-256",bytes))].map(byte=>byte.toString(16).padStart(2,"0")).join("");
  return {path:clean,text:String(text??""),sha256:digest,size:stat.size??bytes.byteLength,modified:stat.modified??0};
}
async function currentHash(path){return (await read(path)).sha256;}
async function invoke(method,args={}){
  switch(method){
    case "info":{
      const info=await coreCall("sandbox.info",{});
      return {...info,surface:"raw-workspace-html",workspaceCore:"shared-rift-tool-sandbox-v1",dualPath:true,rawWorkspaceBridge:true,watch:watcherState};
    }
    case "list":{
      const value=await coreCall("fs.list",{path:corePath(args.path||""),recursive:!!args.recursive});
      return [...(Array.isArray(value)?value:[])].map(normalizeCoreRow);
    }
    case "stat":return normalizeCoreRow(await coreCall("fs.stat",{path:corePath(args.path||"")}));
    case "read":return read(args.path);
    case "write":{
      const path=normalize(args.path);if(!path)throw new Error("Workspace root is not a file");
      if(args.expectedSha256){const actual=await currentHash(path);if(actual!==String(args.expectedSha256))throw new Error("File changed since it was opened");}
      const result=await coreCall("fs.writeText",{path:corePath(path,{allowRoot:false}),text:String(args.text??"")});
      return {...normalizeCoreRow(result),...(await read(path))};
    }
    case "mkdir":return normalizeCoreRow(await coreCall("fs.mkdir",{path:corePath(args.path,{allowRoot:false})}));
    case "remove":return coreCall("fs.remove",{path:corePath(args.path,{allowRoot:false})});
    case "move":return normalizeCoreRow(await coreCall("fs.move",{from:corePath(args.path,{allowRoot:false}),to:corePath(args.newPath,{allowRoot:false}),overwrite:!!args.overwrite}));
    case "copy":return normalizeCoreRow(await coreCall("fs.copy",{from:corePath(args.path,{allowRoot:false}),to:corePath(args.newPath,{allowRoot:false}),overwrite:!!args.overwrite}));
    case "exec":return coreCall("workspace.exec",args);
    // Legacy high-level helpers remain available for local UI features, but raw
    // file I/O above always crosses the single native Workspace Core.
    case "readJSON":return workspace.readJSON(normalize(args.path),args.fallback??null);
    case "writeJSON":return workspace.writeJSON(normalize(args.path),args.value);
    case "previewPatch":return workspace.previewPatch(args.patch);
    case "applyPatch":return workspace.applyPatch(args.patch);
    case "history":return workspace.history();
    case "rollback":return workspace.rollback(args.id);
    case "snapshot":return coreCall("workspace.exec",{operations:[{op:"snapshot",path:corePath(args.path||"")}],finish:false});
    default:throw new Error(`Unsupported live workspace method: ${method}`);
  }
}

function mount(container){
  if(!(container instanceof HTMLElement))throw new Error("Workspace Live mount requires an HTML container");
  container.classList.add("rift-workspace-live-window-body");
  container.innerHTML='<iframe class="rift-workspace-live-frame" title="Rift Workspace Live" src="./workspace-live/index.html" sandbox="allow-scripts allow-modals"></iframe>';
  const frame=container.querySelector("iframe");
  let destroyed=false;
  const send=message=>{if(!destroyed&&frame.contentWindow)frame.contentWindow.postMessage({channel:CHANNEL,...message},"*");};
  const surface={sendControl(request){if(destroyed)return false;send({kind:"control",request});return true;}};
  activeSurface=surface;
  const onNativeEvent=event=>send({kind:"event",event});
  nativeListeners.add(onNativeEvent);

  const onMessage=async event=>{
    if(destroyed||event.source!==frame.contentWindow)return;
    const message=event.data;if(!message||message.channel!==CHANNEL)return;
    if(message.kind==="ready"){send({kind:"connected",watch:watcherState});return;}
    if(message.kind==="state"){core.native.call("workspace.live.state.set",{state:message.state||{}}).catch(()=>{});return;}
    if(message.kind==="controlResult"){
      core.native.call("workspace.live.control.result",{response:message.response||{}}).catch(error=>console.warn("[RiftWorkspaceLive] control result delivery failed",error));
      return;
    }
    if(message.kind!=="request"||!message.id)return;
    try{send({kind:"response",id:message.id,ok:true,value:await invoke(message.method,message.args||{})});}
    catch(error){send({kind:"response",id:message.id,ok:false,error:error?.message||String(error)});}
  };
  window.addEventListener("message",onMessage);
  core.native.call("workspace.watch.start",{}).then(state=>{if(destroyed){core.native.call("workspace.watch.stop",{}).catch(()=>{});return;}watcherState=state||{active:true};send({kind:"connected",watch:watcherState});}).catch(error=>{if(!destroyed)send({kind:"response",id:"watch-start",ok:false,error:error.message});});

  const destroy=()=>{
    if(destroyed)return;destroyed=true;if(activeSurface===surface)activeSurface=null;nativeListeners.delete(onNativeEvent);window.removeEventListener("message",onMessage);core.native.call("workspace.watch.stop",{}).catch(()=>{});core.native.call("workspace.live.state.clear",{}).catch(()=>{});frame.src="about:blank";
  };
  return {destroy,frame};
}

window.RiftWorkspaceLiveHost=Object.freeze({
  mount,
  invoke,
  __mcpControl(request){return !!activeSurface?.sendControl(request);},
  get connected(){return !!activeSurface;},
  get watch(){return {...watcherState};}
});
console.info("[RiftWorkspaceLive] raw workspace HTML bridge + MCP page control ready");
