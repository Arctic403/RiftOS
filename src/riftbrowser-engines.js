const ENGINE_ID="wasm-gecko";
const ENGINE_ROOT=new URL("../engines/gecko/",import.meta.url);
const PROBE_FILE=new URL("engine-manifest.json",ENGINE_ROOT);
const HOST_FILE=new URL("index.html",ENGINE_ROOT);
const ENGINE_MODULE=new URL("vendor/gecko.js",ENGINE_ROOT);
const WISP_KEY="riftos.browser.wisp.v1";

const state={checked:false,artifactReady:false,available:false,checking:null,error:null,checkedAt:0,manifest:null};

function normalizeWisp(value=""){
  const raw=String(value??"").trim();
  if(!raw)return "";
  try{
    const url=new URL(raw);
    if(!["ws:","wss:"].includes(url.protocol))throw new Error("Wisp URL must use ws:// or wss://");
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

async function probe({force=false}={}){
  if(state.checking)return state.checking;
  if(state.checked&&!force)return {...state};
  state.checking=(async()=>{
    try{
      const response=await fetch(PROBE_FILE,{cache:"no-store",headers:{Accept:"application/json"}});
      if(!response.ok)throw new Error(`engine manifest HTTP ${response.status}`);
      const manifest=await response.json();
      if(manifest?.id!==ENGINE_ID)throw new Error("engine manifest id mismatch");
      state.manifest=manifest;
      state.artifactReady=true;
      state.error=null;
    }catch(error){
      state.manifest=null;
      state.artifactReady=false;
      state.error=error?.message||String(error);
    }
    state.available=state.artifactReady&&globalThis.crossOriginIsolated===true&&typeof WebAssembly==="object"&&typeof SharedArrayBuffer==="function";
    if(state.artifactReady&&!state.available&&!state.error){
      state.error=globalThis.crossOriginIsolated!==true?"cross-origin isolation requires one reload after the RiftOS service worker update":"SharedArrayBuffer/WebAssembly unavailable";
    }
    state.checked=true;state.checkedAt=Date.now();state.checking=null;
    return {...state};
  })();
  return state.checking;
}

function frameURL(target,{wisp=configuredWisp()}={}){
  const url=new URL(HOST_FILE.href);
  url.searchParams.set("url",String(target||"https://chatgpt.com"));
  if(wisp)url.searchParams.set("wisp",wisp);
  return url.href;
}
function info(){
  return {
    id:ENGINE_ID,
    name:"Gecko WASM",
    kind:"wasm-browser-engine",
    experimental:true,
    available:state.available,
    artifactReady:state.artifactReady,
    checked:state.checked,
    checkedAt:state.checkedAt,
    error:state.error,
    manifest:state.manifest,
    host:HOST_FILE.href,
    artifact:ENGINE_MODULE.href,
    probe:PROBE_FILE.href,
    wisp:configuredWisp(),
    crossOriginIsolated:globalThis.crossOriginIsolated===true,
    requiresWebAssembly:true,
    requiresSharedArrayBuffer:true,
    storage:"engine profile + RiftOS OPFS sandbox",
    source:"HeyPuter/firefox-wasm gecko.js v0.0.1",
    license:"MPL-2.0"
  };
}

window.RiftBrowserEngines=Object.freeze({primary:ENGINE_ID,probe,info,frameURL,configuredWisp,setWisp});
console.info("[RiftBrowser] experimental engine registry ready",info());
