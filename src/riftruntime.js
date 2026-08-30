const core=window.RiftOSCore;
if(!core?.kernel)throw new Error("RiftRuntime requires RiftOSCore");

const baseKernelInfo=core.kernel.info.bind(core.kernel);

function deliveryMode(){
  if(location.protocol==="riftos:")return "native-bundle";
  if(window.matchMedia?.("(display-mode: standalone)")?.matches)return "home-screen-web-app";
  if(document.referrer.startsWith("android-app://"))return "home-screen-web-app";
  return "browser-tab";
}

function runtimeCapabilities(){
  return {
    opfs:!!navigator.storage?.getDirectory,
    workspace:!!core.workspace?.available,
    workspaceJSON:!!window.RiftWorkspaceJSON?.invoke,
    workers:typeof Worker==="function",
    serviceWorker:"serviceWorker" in navigator,
    webAssembly:typeof WebAssembly==="object",
    webShare:!!navigator.share,
    notifications:"Notification" in window,
    clipboard:!!navigator.clipboard,
    nativeBridge:!!core.native?.connected
  };
}

async function info(){
  const base=await baseKernelInfo();
  const nativeBridge=!!core.native?.connected;
  return {
    ...base,
    mode:nativeBridge?"riftkernel-webkit+native-capabilities":"riftkernel-webkit",
    host:nativeBridge?"Apple WebKit + optional RiftNative bridge":"Apple WebKit",
    delivery:deliveryMode(),
    appSigningRequiredForKernel:false,
    nativeExecutableSigningRequired:nativeBridge,
    runtimeCapabilities:runtimeCapabilities()
  };
}

// Runtime identity belongs to RiftKernel, not to the delivery mechanism.
// A Home Screen web app is how unsigned RiftOS is launched; it is not a
// separate or reduced kernel mode.
core.kernel.info=info;

window.RiftRuntime=Object.freeze({
  info,
  deliveryMode,
  capabilities:runtimeCapabilities,
  get nativeBridge(){return !!core.native?.connected;},
  get unsignedKernel(){return !core.native?.connected;}
});

document.documentElement.dataset.riftRuntime="webkit";
document.documentElement.dataset.riftDelivery=deliveryMode();

// Remove old presentation-only wording without pretending native-only
// capabilities exist. This observer can disappear when the desktop settings
// view is next refactored; the authoritative identity is core.kernel.info().
const relabelLegacyMode=()=>{
  if(core.native?.connected)return;
  for(const node of document.querySelectorAll(".trueos-chip,.trueos-card small,.trueos-head small")){
    if(node.textContent?.includes("PWA MODE"))node.textContent=node.textContent.replace("PWA MODE","WEBKIT HOST");
    if(node.textContent?.includes("PWA preview"))node.textContent=node.textContent.replace("PWA preview","Web transport · WebKit host");
    if(node.textContent?.includes("web-pwa"))node.textContent=node.textContent.replace("web-pwa","riftkernel-webkit");
  }
};
new MutationObserver(relabelLegacyMode).observe(document.documentElement,{subtree:true,childList:true,characterData:true});
queueMicrotask(relabelLegacyMode);
