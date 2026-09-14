await import("./riftandroid-preload.js");
await import("./riftcore.js");
await import("./riftandroid-platform.js");
await import("./riftworkspace-web.js");
await import("./riftworkspace-android-adapter.js");
await import("./riftworkspace-live-host.js");
await import("./riftruntime.js");
await import("./riftapps.js");
await import("./riftapps-files.js");
await import("./riftgit.js");
await import("./riftvault.js");
await import("./riftrepo.js");
await import("./riftmemory-control.js");
await import("./riftbuild.js");
await import("./riftlocal-platform.js");
await import("./riftllm-bridge.js");
await import("./riftshell-batch.js");
await import("./riftdevlab.js");
await import("./riftos.js");
if(globalThis.RiftNativeDesktop?.enabled){
  await import("./riftdesktop-native-compat.js");
}else{
  await import("./riftdesktop-window-host.js");
  await import("./riftdesktop-android.js");
}
await import("./riftmcp-system.js");
await import("./riftrt.js");
if(!globalThis.RiftNativeDesktop?.enabled)await import("./riftdesktop-android-compat.js");
// Remove the retired iframe editor's plaintext credential cache after upgrade.
try{localStorage.removeItem("gh_token");}catch{}
