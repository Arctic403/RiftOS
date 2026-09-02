const host=globalThis.RiftAndroid;
if(!host?.postMessage)throw new Error("RiftOS Android requires the RiftAndroid WebMessage host.");

const transport=Object.freeze({
  postMessage(payload){
    const raw=typeof payload==="string"?payload:JSON.stringify(payload);
    host.postMessage(raw);
  }
});

Object.defineProperty(globalThis,"RiftPlatform",{
  value:Object.freeze({kind:"android",android:true,native:true,samsung:true,delivery:"apk"}),
  configurable:false,enumerable:true,writable:false
});
Object.defineProperty(globalThis,"RiftNativeTransport",{
  value:transport,configurable:false,enumerable:false,writable:false
});

document.documentElement.dataset.riftPlatform="android";
document.documentElement.dataset.riftDelivery="apk";
console.info("[RiftAndroid] direct native transport online");
