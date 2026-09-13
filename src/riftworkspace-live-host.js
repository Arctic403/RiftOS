const core=window.RiftOSCore;
const workspace=window.RiftWorkspace;
const git=window.RiftGit;
if(!core?.native?.connected||!workspace?.available)throw new Error("Rift Workspace Records requires Android RiftWorkspace");

const CHANNEL="riftworkspace-live-v2";
const LIVE_EVENT_WINDOW_MS=120;
const nativeListeners=new Set();
let watcherState={active:false};

globalThis.RiftWorkspaceNative=Object.freeze({
  __event(event){
    const next=event&&typeof event==="object"?event:{};
    for(const listener of [...nativeListeners]){try{listener(next);}catch(error){console.warn("[RiftWorkspaceRecords] event listener failed",error);}}
  }
});

function normalize(path=""){
  const value=workspace.local.normalize(path,{allowRoot:true});
  if(value===".rift"||value.startsWith(".rift/"))throw new Error("Workspace metadata is not exposed to the records surface");
  return value;
}
async function read(path){
  const clean=normalize(path);if(!clean)throw new Error("Select a file");
  const [text,stat]=await Promise.all([workspace.readText(clean),workspace.stat(clean)]);
  return {path:clean,text:String(text??""),size:stat?.size??0,modified:stat?.modified??0};
}
async function invoke(method,args={}){
  switch(method){
    case "info":return {...await workspace.info(),surface:"private-workspace-records",watch:await core.native.call("workspace.watch.state",{}),mode:"records"};
    case "list":return (await workspace.list(normalize(args.path||""),{recursive:false})).map(row=>({...row,name:row.path.split("/").pop()||row.path}));
    case "read":return read(args.path);
    case "records":return core.native.call("workspace.records.query",{path:normalize(args.path||""),limit:Number(args.limit||160),includeDiff:args.includeDiff!==false});
    case "checkpoint":return core.native.call("workspace.records.checkpoint",{reason:String(args.reason||"manual-workspace-records").slice(0,80)});
    case "gitDiff":{
      if(!git?.workspaceDiff)throw new Error("RiftGit diff provider is unavailable");
      return git.workspaceDiff({maxFiles:Number(args.maxFiles||50),maxChars:Number(args.maxChars||300000)});
    }
    default:throw new Error(`Unsupported workspace records method: ${method}`);
  }
}

function mount(container){
  if(!(container instanceof HTMLElement))throw new Error("Workspace Records mount requires an HTML container");
  container.classList.add("rift-workspace-live-window-body");
  container.innerHTML='<iframe class="rift-workspace-live-frame" title="Rift Workspace Records" src="./workspace-live/index.html" sandbox="allow-scripts"></iframe>';
  const frame=container.querySelector("iframe");
  let destroyed=false;
  const send=message=>{if(!destroyed&&frame.contentWindow)frame.contentWindow.postMessage({channel:CHANNEL,...message},"*");};
  let nativeEventTimer=0,nativeEventCount=0;
  const pendingNativeEvents=new Map();
  const flushNativeEvents=()=>{
    clearTimeout(nativeEventTimer);nativeEventTimer=0;
    if(destroyed||!pendingNativeEvents.size)return;
    const events=[...pendingNativeEvents.values()];pendingNativeEvents.clear();
    const count=nativeEventCount;nativeEventCount=0;
    send({kind:"event",event:{type:"batch",source:"workspace-watcher",count,at:Date.now(),paths:events.map(item=>item.path),events}});
  };
  const onNativeEvent=event=>{
    const next=event&&typeof event==="object"?event:{};
    const key=`${next.path||""}\u0000${next.directory?"d":"f"}`;
    const previous=pendingNativeEvents.get(key);
    pendingNativeEvents.set(key,{...next,occurrences:(previous?.occurrences||0)+1});
    nativeEventCount++;
    clearTimeout(nativeEventTimer);nativeEventTimer=setTimeout(flushNativeEvents,LIVE_EVENT_WINDOW_MS);
  };
  nativeListeners.add(onNativeEvent);

  const onMessage=async event=>{
    if(destroyed||event.source!==frame.contentWindow)return;
    const message=event.data;if(!message||message.channel!==CHANNEL)return;
    if(message.kind==="ready"){
      core.native.call("workspace.watch.state",{}).then(state=>{watcherState=state||{active:false};send({kind:"connected",watch:watcherState});}).catch(()=>send({kind:"connected",watch:{active:false}}));
      return;
    }
    if(message.kind!=="request"||!message.id)return;
    try{send({kind:"response",id:message.id,ok:true,value:await invoke(message.method,message.args||{})});}
    catch(error){send({kind:"response",id:message.id,ok:false,error:error?.message||String(error)});}
  };
  window.addEventListener("message",onMessage);

  const destroy=()=>{
    if(destroyed)return;destroyed=true;clearTimeout(nativeEventTimer);pendingNativeEvents.clear();nativeListeners.delete(onNativeEvent);window.removeEventListener("message",onMessage);frame.src="about:blank";
  };
  return {destroy,frame};
}

window.RiftWorkspaceLiveHost=Object.freeze({mount,invoke,get watch(){return {...watcherState};}});
console.info("[RiftWorkspaceRecords] private workspace records surface ready");
