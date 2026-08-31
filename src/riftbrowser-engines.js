const ENGINE_ID="riftwebkit";
const ENGINE_ROOT=new URL("../engines/webkit/",import.meta.url);
const MANIFEST_FILE=new URL("engine-manifest.json",ENGINE_ROOT);
const HOST_FILE=new URL("index.html",ENGINE_ROOT);
const WISP_KEY="riftos.browser.wisp.v2";

const state={checked:false,artifactReady:false,available:false,browsingReady:false,checking:null,error:null,checkedAt:0,manifest:null};

function normalizeWisp(value=""){
  const raw=String(value??"").trim();
  if(!raw)return "";
  try{
    const url=new URL(raw);
    if(!["ws:","wss:"].includes(url.protocol))throw new Error("Wisp URL must use ws:// or wss://");
    if(location.protocol==="https:"&&url.protocol==="ws:"&&!["localhost","127.0.0.1"].includes(url.hostname))throw new Error("HTTPS RiftOS requires a secure wss:// Wisp endpoint");
    if(!url.pathname.endsWith("/"))url.pathname+="/";
    return url.href;
  }catch(error){throw new Error(`Invalid Wisp endpoint: ${error.message}`);}
}
function configuredWisp(){try{return normalizeWisp(localStorage.getItem(WISP_KEY)||"");}catch{return "";}}
function setWisp(value=""){
  const next=normalizeWisp(value);
  if(next)localStorage.setItem(WISP_KEY,next);else localStorage.removeItem(WISP_KEY);
  return next;
}
function validManifest(manifest){return /^riftwebkit(?:-|$)/.test(String(manifest?.id||""));}
function manifestRequiresWisp(manifest=state.manifest){return manifest?.requiresWisp===true||manifest?.requiresWispForArbitraryNetworking===true;}
function transportReady(manifest=state.manifest){return !manifestRequiresWisp(manifest)||!!configuredWisp();}
function browsingReady(manifest=state.manifest){return state.available&&manifest?.networking===true&&manifest?.guestJavaScript===true&&transportReady(manifest);}
function prefersDirectGPU(manifest=state.manifest){return manifest?.id==="riftwebkit-mobile"&&manifest?.threaded===false&&manifest?.gpuCapable===true;}

async function probe({force=false}={}){
  if(state.checking)return state.checking;
  if(state.checked&&!force)return {...state,browsingReady:browsingReady()};
  state.checking=(async()=>{
    try{
      const response=await fetch(MANIFEST_FILE,{cache:"no-store",headers:{Accept:"application/json"}});
      if(!response.ok)throw new Error(`engine manifest HTTP ${response.status}`);
      const manifest=await response.json();
      if(!validManifest(manifest))throw new Error("RiftWebKit engine manifest id mismatch");
      state.manifest=manifest;
      state.artifactReady=manifest.available===true;
      state.available=state.artifactReady&&typeof WebAssembly==="object";
      state.browsingReady=browsingReady(manifest);
      state.error=state.available?null:"RiftWebKit WASM artifact is not available in this deployment";
    }catch(error){
      state.manifest=null;state.artifactReady=false;state.available=false;state.browsingReady=false;
      state.error=error?.message||String(error);
    }
    state.checked=true;state.checkedAt=Date.now();state.checking=null;
    return {...state,browsingReady:browsingReady()};
  })();
  return state.checking;
}

function frameURL(target,{wisp=configuredWisp()}={}){
  const url=new URL(HOST_FILE.href);
  url.searchParams.set("embed","1");
  // The full single-threaded mobile build owns #screen's WebGL2 context directly.
  // This avoids the CPU raster -> JS byte copy -> putImageData path. The pinned
  // helper has its own automatic GPU failure/loss fallback back to ?gpu=0.
  url.searchParams.set("gpu",prefersDirectGPU()?"1":"0");
  const normalized=String(target||"").trim();
  const needsWisp=manifestRequiresWisp();
  const canNavigate=!needsWisp||!!wisp;
  if(normalized&&canNavigate)url.searchParams.set("url",normalized);
  else if(normalized&&!canNavigate)url.searchParams.set("demo","interactive");
  if(wisp)url.searchParams.set("wisp",wisp);
  return url.href;
}
function info(){
  const manifest=state.manifest;
  const wisp=configuredWisp();
  const requiresWisp=manifestRequiresWisp(manifest);
  const transport=transportReady(manifest);
  const ready=browsingReady(manifest);
  const directGPU=prefersDirectGPU(manifest);
  return {
    id:ENGINE_ID,
    name:"RiftWebKit Mobile",
    kind:"webkit-wasm-browser-engine",
    mobile:true,
    experimental:true,
    available:state.available,
    artifactReady:state.artifactReady,
    browsingReady:ready,
    transportReady:transport,
    transportError:state.available&&requiresWisp&&!wisp?"RiftWebKit is running locally, but internet navigation needs a secure Wisp endpoint.":null,
    checked:state.checked,
    checkedAt:state.checkedAt,
    error:state.error,
    manifest,
    host:HOST_FILE.href,
    probe:MANIFEST_FILE.href,
    wisp,
    requiresWebAssembly:true,
    requiresSharedArrayBuffer:manifest?.requiresSharedArrayBuffer===true,
    requiresCrossOriginIsolation:manifest?.requiresCrossOriginIsolation===true,
    requiresWisp,
    networking:manifest?.networking===true,
    guestJavaScript:manifest?.guestJavaScript===true,
    persistence:manifest?.persistence===true,
    threaded:manifest?.threaded===true,
    directGPU,
    presentation:directGPU?"gpu-implicit-webgl2":"raster-2d",
    viewport:manifest?.viewport||{width:390,height:844},
    source:"theogbob/WebkitWasm pinned Emscripten WebCore/JSC port"
  };
}

window.RiftBrowserEngines=Object.freeze({primary:ENGINE_ID,probe,info,frameURL,configuredWisp,setWisp});
console.info("[RiftBrowser] RiftWebKit-only engine registry ready",info());
