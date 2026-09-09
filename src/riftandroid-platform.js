const core=globalThis.RiftOSCore;
if(!core?.native?.connected)throw new Error("RiftAndroid platform requires the native core");

await core.ready;

const system=Object.freeze({
  info:()=>core.native.call("system.info",{}),
  storage:()=>core.native.call("system.storage",{}),
  saveDump:()=>core.native.call("system.dump.save",{})
});

const api=Object.freeze({
  info:()=>core.native.call("device.info",{}),
  system,
  vibrate:(milliseconds=40)=>core.native.call("device.vibrate",{milliseconds}),
  clipboard:Object.freeze({
    read:()=>core.native.call("clipboard.read",{}),
    write:text=>core.native.call("clipboard.write",{text:String(text??"")})
  }),
  share:(text,title="Share from RiftOS")=>core.native.call("share.text",{text:String(text??""),title}),
  open:url=>core.native.call("intent.open",{url:String(url)}),
  browser:url=>globalThis.RiftDesktop?.openBrowser?.(String(url||"https://chatgpt.com"))??core.native.call("browser.window.open",{url:String(url||"https://chatgpt.com")}),
  preview:(root="",entry="index.html")=>core.native.call("preview.open",{root:String(root||""),entry:String(entry||"index.html")}),
  notify:(title,body="")=>core.native.call("notifications.show",{title:String(title||"RiftOS"),body:String(body||"")}),
  requestNotifications:()=>core.native.call("notifications.request",{}),
  secrets:Object.freeze({
    get:key=>core.native.call("secrets.get",{key:String(key)}),
    set:(key,value)=>core.native.call("secrets.set",{key:String(key),value:String(value??"")}),
    remove:key=>core.native.call("secrets.remove",{key:String(key)})
  })
});

globalThis.RiftAndroidAPI=api;
globalThis.RiftKernel=system;

// Called by MainActivity before it exits. RiftDev and RiftOS windows consume Back first.
globalThis.RiftAndroidBack=()=>{
  if(document.documentElement.classList.contains("riftdev-active")&&globalThis.RiftDev?.close){
    globalThis.RiftDev.close();
    return true;
  }
  const stage=document.querySelector("#stage");
  if(stage&&!stage.classList.contains("hidden")&&globalThis.RiftDesktop?.closeWindow){
    globalThis.RiftDesktop.closeWindow();
    return true;
  }
  return false;
};

window.addEventListener("pageshow",()=>document.documentElement.dataset.riftActivity="resumed");
window.addEventListener("pagehide",()=>document.documentElement.dataset.riftActivity="paused");
console.info("[RiftAndroid] Samsung/Android platform services + system dump online");
