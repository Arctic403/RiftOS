const ENGINE_ID="riftwebkit";
const ENGINE_ROOT=new URL("../engines/webkit/",import.meta.url);
const MANIFEST_FILE=new URL("engine-manifest.json",ENGINE_ROOT);
const HOST_FILE=new URL("index.html",ENGINE_ROOT);
const WISP_KEY="riftos.browser.wisp.v2";
// MercuryWorkshop's public demo endpoint is throttled and is only our bring-up
// transport. A user-configured/self-hosted endpoint always overrides it.
const DEFAULT_WISP="wss://wisp.mercurywork.shop/";

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
function storedWisp(){try{return normalizeWisp(localStorage.getItem(WISP_KEY)||"");}catch{return "";}}
function configuredWisp(){return storedWisp()||DEFAULT_WISP;}
function usingDefaultWisp(){return !storedWisp();}
function setWisp(value=""){
  const next=normalizeWisp(value);
  if(next&&next!==DEFAULT_WISP)localStorage.setItem(WISP_KEY,next);else localStorage.removeItem(WISP_KEY);
  return configuredWisp();
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
  url.searchParams.set("gpu",prefersDirectGPU()?"1":"0");
  const normalized=String(target||"").trim();
  const needsWisp=manifestRequiresWisp();
  const canNavigate=!needsWisp||!!wisp;

  if(normalized&&canNavigate)url.searchParams.set("url",normalized);
  else if(normalized&&!canNavigate)url.searchParams.set("demo","interactive");

  if(wisp)url.searchParams.set("wisp",wisp);

  // Keep the official throttled demo transport on the fast startup path while
  // we prove real HTTPS navigation. This skips the 13+ MB Binaryen guest-WASM
  // compiler and profile restore. A custom/self-hosted Wisp restores the full
  // compatibility/persistence boot automatically.
  if(usingDefaultWisp()){
    url.searchParams.set("fastboot","1");
    url.searchParams.set("persist","0");
  }
  return url.href;
}
function info(){
  const manifest=state.manifest;
  const wisp=configuredWisp();
  const requiresWisp=manifestRequiresWisp(manifest);
  const transport=transportReady(manifest);
  const ready=browsingReady(manifest);
  const directGPU=prefersDirectGPU(manifest);
  const defaultWisp=usingDefaultWisp();
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
    wispMode:defaultWisp?"mercury-demo":"custom",
    wispDemo:defaultWisp,
    requiresWebAssembly:true,
    requiresSharedArrayBuffer:manifest?.requiresSharedArrayBuffer===true,
    requiresCrossOriginIsolation:manifest?.requiresCrossOriginIsolation===true,
    requiresWisp,
    networking:manifest?.networking===true,
    guestJavaScript:manifest?.guestJavaScript===true,
    persistence:manifest?.persistence===true,
    threaded:manifest?.threaded===true,
    directGPU,
    localFastBoot:defaultWisp,
    presentation:directGPU?"gpu-implicit-webgl2":"raster-2d",
    viewport:manifest?.viewport||{width:390,height:844},
    source:"theogbob/WebkitWasm pinned Emscripten WebCore/JSC port"
  };
}

window.RiftBrowserEngines=Object.freeze({primary:ENGINE_ID,probe,info,frameURL,configuredWisp,setWisp,DEFAULT_WISP});
console.info("[RiftBrowser] RiftWebKit-only engine registry ready",info());
