const core=window.RiftOSCore;
const workspace=window.RiftWorkspace;
const git=window.RiftGit;
if(!core?.native?.connected||!workspace?.available)throw new Error("Rift Workspace Records requires Android RiftWorkspace");

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

function subscribe(listener){nativeListeners.add(listener);return()=>nativeListeners.delete(listener);}

function mount(container){
  if(!(container instanceof HTMLElement))throw new Error("Workspace Records mount requires an HTML container");
  container.classList.add("rift-workspace-live-window-body");
  const shadow=container.attachShadow({mode:"open"});
  const stylesheet=document.createElement("link");
  stylesheet.rel="stylesheet";
  stylesheet.href=new URL("../workspace-live/style.css",import.meta.url).href;
  const loading=document.createElement("div");
  loading.className="rift-records-loading";
  loading.textContent="Loading local Workspace Records…";
  shadow.append(stylesheet,loading);
  let destroyed=false,cleanup=null;
  const destroy=()=>{if(destroyed)return;destroyed=true;cleanup?.();shadow.replaceChildren();};
  (async()=>{
    const controller=new AbortController();
    const timer=setTimeout(()=>controller.abort(),15000);
    try{
      const importWithTimeout=Promise.race([
        import("../workspace-live/app.js"),
        new Promise((_,reject)=>setTimeout(()=>reject(new Error("Workspace Records module timed out")),15000))
      ]);
      const [response,view]=await Promise.all([
        fetch(new URL("../workspace-live/index.html",import.meta.url),{signal:controller.signal}),
        importWithTimeout
      ]);
      if(!response.ok)throw new Error(`Workspace Records template failed: ${response.status}`);
      const template=new DOMParser().parseFromString(await response.text(),"text/html").querySelector(".records-app");
      if(!template)throw new Error("Workspace Records template is missing");
      if(destroyed)return;
      shadow.append(document.importNode(template,true));
      loading.remove();
      cleanup=view.mountWorkspaceRecords(shadow,invoke,subscribe).destroy;
    }catch(error){
      if(destroyed)return;
      loading.textContent=`Workspace Records could not start: ${error?.message||error}`;
      console.error("[RiftWorkspaceRecords] mount failed",error);
    }finally{
      clearTimeout(timer);
    }
  })();
  return {destroy,root:shadow};
}

window.RiftWorkspaceLiveHost=Object.freeze({mount,invoke,get watch(){return {...watcherState};}});
console.info("[RiftWorkspaceRecords] private workspace records surface ready");
