const core=window.RiftOSCore;
const workspace=window.RiftWorkspace;
if(!core?.native?.connected||!workspace?.available)throw new Error("Rift Workspace Live requires Android RiftWorkspace");

const CHANNEL="riftworkspace-live-v1";
const nativeListeners=new Set();
let watcherState={active:false};

globalThis.RiftWorkspaceNative=Object.freeze({
  __event(event){
    const next=event&&typeof event==="object"?event:{};
    for(const listener of [...nativeListeners]){try{listener(next);}catch(error){console.warn("[RiftWorkspaceLive] event listener failed",error);}}
  }
});

function normalize(path=""){
  return workspace.local.normalize(path,{allowRoot:true});
}
async function read(path){
  const clean=normalize(path);if(!clean)throw new Error("Select a file");
  const [text,stat]=await Promise.all([workspace.readText(clean),workspace.stat(clean)]);
  const bytes=new TextEncoder().encode(String(text??""));
  const digest=await crypto.subtle.digest("SHA-256",bytes);
  const sha256=[...new Uint8Array(digest)].map(byte=>byte.toString(16).padStart(2,"0")).join("");
  return {path:clean,text:String(text??""),sha256,size:stat?.size??bytes.byteLength,modified:stat?.modified??0};
}
async function currentHash(path){return (await read(path)).sha256;}
async function invoke(method,args={}){
  switch(method){
    case "info":return {...await workspace.info(),surface:"raw-workspace-html",rawWorkspaceBridge:true,watch:watcherState};
    case "list":return (await workspace.list(normalize(args.path||""),{recursive:!!args.recursive,includeHidden:true})).map(row=>({...row,name:row.path.split("/").pop()||row.path}));
    case "stat":return workspace.stat(normalize(args.path||""));
    case "read":return read(args.path);
    case "write":{
      const path=normalize(args.path);if(!path)throw new Error("Workspace root is not a file");
      if(args.expectedSha256){const actual=await currentHash(path);if(actual!==String(args.expectedSha256))throw new Error("File changed since it was opened");}
      await workspace.writeText(path,String(args.text??""));
      return read(path);
    }
    case "mkdir":return workspace.mkdir(normalize(args.path));
    case "remove":return workspace.remove(normalize(args.path));
    case "move":return workspace.move(normalize(args.path),normalize(args.newPath),{overwrite:false});
    case "copy":return workspace.copy(normalize(args.path),normalize(args.newPath),{overwrite:!!args.overwrite});
    case "readJSON":return workspace.readJSON(normalize(args.path),args.fallback??null);
    case "writeJSON":return workspace.writeJSON(normalize(args.path),args.value);
    case "previewPatch":return workspace.previewPatch(args.patch);
    case "applyPatch":return workspace.applyPatch(args.patch);
    case "history":return workspace.history();
    case "rollback":return workspace.rollback(args.id);
    case "snapshot":return workspace.snapshot(normalize(args.path||""),args.options||{});
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
  const onNativeEvent=event=>send({kind:"event",event});
  nativeListeners.add(onNativeEvent);

  const onMessage=async event=>{
    if(destroyed||event.source!==frame.contentWindow)return;
    const message=event.data;if(!message||message.channel!==CHANNEL)return;
    if(message.kind==="ready"){send({kind:"connected",watch:watcherState});return;}
    if(message.kind==="state"){core.native.call("workspace.live.state.set",{state:message.state||{}}).catch(()=>{});return;}
    if(message.kind!=="request"||!message.id)return;
    try{send({kind:"response",id:message.id,ok:true,value:await invoke(message.method,message.args||{})});}
    catch(error){send({kind:"response",id:message.id,ok:false,error:error?.message||String(error)});}
  };
  window.addEventListener("message",onMessage);
  core.native.call("workspace.watch.start",{}).then(state=>{if(destroyed){core.native.call("workspace.watch.stop",{}).catch(()=>{});return;}watcherState=state||{active:true};send({kind:"connected",watch:watcherState});}).catch(error=>{if(!destroyed)send({kind:"response",id:"watch-start",ok:false,error:error.message});});

  const destroy=()=>{
    if(destroyed)return;destroyed=true;nativeListeners.delete(onNativeEvent);window.removeEventListener("message",onMessage);core.native.call("workspace.watch.stop",{}).catch(()=>{});core.native.call("workspace.live.state.clear",{}).catch(()=>{});frame.src="about:blank";
  };
  return {destroy,frame};
}

window.RiftWorkspaceLiveHost=Object.freeze({mount,invoke,get watch(){return {...watcherState};}});
console.info("[RiftWorkspaceLive] raw workspace HTML bridge ready");
