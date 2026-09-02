const androidHost=globalThis.RiftAndroid;
const isAndroid=!!androidHost?.postMessage;

Object.defineProperty(globalThis,"RiftPlatform",{
  value:Object.freeze({kind:isAndroid?"android":"web",android:isAndroid,native:isAndroid}),
  configurable:false,
  enumerable:true,
  writable:false
});

if(isAndroid){
  const webkit=globalThis.webkit||{};
  const handlers=webkit.messageHandlers||{};
  handlers.riftNative={
    postMessage(payload){
      const raw=typeof payload==="string"?payload:JSON.stringify(payload);
      androidHost.postMessage(raw);
    }
  };
  webkit.messageHandlers=handlers;
  globalThis.webkit=webkit;

  if(navigator.serviceWorker){
    navigator.serviceWorker.getRegistrations?.().then(rows=>rows.forEach(row=>row.unregister())).catch(()=>{});
    try{
      navigator.serviceWorker.register=async()=>({scope:location.origin+"/",active:null,waiting:null,installing:null});
    }catch{}
  }

  document.documentElement.dataset.riftPlatform="android";
}
