const core=window.RiftOSCore;
if(!core?.kernel)throw new Error("RiftRuntime requires RiftOSCore");
const baseKernelInfo=core.kernel.info.bind(core.kernel);

function deliveryMode(){return "android-apk";}
function runtimeCapabilities(){
  return {
    android:true,
    samsung:true,
    nativeHost:true,
    nativeFilesystem:true,
    saf:true,
    opfs:false,
    serviceWorker:false,
    backgroundSync:false,
    workers:typeof Worker==="function",
    webAssembly:typeof WebAssembly==="object",
    nativeShare:true,
    nativeNotifications:true,
    nativeClipboard:true,
    nativeBrowser:true,
    nativePreview:true
  };
}
async function info(){
  const base=await baseKernelInfo();
  return {...base,mode:"android-apk",host:"Android System WebView",delivery:"android-apk",browserEngine:"android-webview",runtimeCapabilities:runtimeCapabilities()};
}
core.kernel.info=info;
window.RiftRuntime=Object.freeze({info,deliveryMode,capabilities:runtimeCapabilities,get unsignedKernel(){return false;}});
document.documentElement.dataset.riftRuntime="android-native";
document.documentElement.dataset.riftDelivery="apk";
console.info("[RiftRuntime] Android native runtime active");
