const core=window.RiftOSCore;
if(!core?.kernel)throw new Error("RiftRuntime requires RiftOSCore");

const baseKernelInfo=core.kernel.info.bind(core.kernel);

function deliveryMode(){
  if(window.matchMedia?.("(display-mode: standalone)")?.matches)return "home-screen-web-app";
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
    riftWebKit:!!window.RiftBrowserEngines
  };
}

async function info(){
  const base=await baseKernelInfo();
  return {
    ...base,
    mode:"riftkernel-webkit",
    host:"Apple WebKit",
    delivery:deliveryMode(),
    appSigningRequiredForKernel:false,
    nativeExecutableSigningRequired:false,
    browserEngine:"riftwebkit",
    runtimeCapabilities:runtimeCapabilities()
  };
}

core.kernel.info=info;

window.RiftRuntime=Object.freeze({
  info,
  deliveryMode,
  capabilities:runtimeCapabilities,
  get unsignedKernel(){return true;}
});

document.documentElement.dataset.riftRuntime="webkit";
document.documentElement.dataset.riftDelivery=deliveryMode();

const relabelLegacyMode=()=>{
  for(const node of document.querySelectorAll(".trueos-chip,.trueos-card small,.trueos-head small")){
    if(node.textContent?.includes("PWA MODE"))node.textContent=node.textContent.replace("PWA MODE","WEBKIT HOST");
    if(node.textContent?.includes("PWA preview"))node.textContent=node.textContent.replace("PWA preview","RiftKernel · WebKit host");
    if(node.textContent?.includes("web-pwa"))node.textContent=node.textContent.replace("web-pwa","riftkernel-webkit");
  }
};
new MutationObserver(relabelLegacyMode).observe(document.documentElement,{subtree:true,childList:true,characterData:true});
queueMicrotask(relabelLegacyMode);
