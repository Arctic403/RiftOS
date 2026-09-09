const core=globalThis.RiftOSCore;
if(!core?.native?.connected)throw new Error("RiftAndroid platform requires the native core");

await core.ready;

const GPT_OSS_MODEL_URL="https://huggingface.co/ggml-org/gpt-oss-20b-GGUF/resolve/main/gpt-oss-20b-MXFP4.gguf?download=true";
const sleep=milliseconds=>new Promise(resolve=>setTimeout(resolve,milliseconds));

async function awaitLocalAiJob(jobId,{pollMs=1000,timeoutMs=15*60*1000}={}){
  const deadline=Date.now()+Math.max(10_000,Number(timeoutMs)||0);
  const interval=Math.max(250,Number(pollMs)||1000);
  while(Date.now()<deadline){
    const state=await core.native.call("ai.local.chatResult",{jobId});
    if(state?.state==="completed"){
      await core.native.call("ai.local.chatResult",{jobId,consume:true}).catch(()=>{});
      return state.response;
    }
    if(state?.state==="failed"){
      await core.native.call("ai.local.chatResult",{jobId,consume:true}).catch(()=>{});
      throw new Error(state.error||"Local gpt-oss inference failed");
    }
    await sleep(interval);
  }
  throw new Error("Local gpt-oss inference timed out in RiftOS");
}

const localAI=Object.freeze({
  info:()=>core.native.call("ai.local.info",{}),
  status:()=>core.native.call("ai.local.status",{}),
  models:()=>core.native.call("ai.local.models",{}),
  modelDownloadUrl:GPT_OSS_MODEL_URL,
  downloadModel:()=>core.native.call("browser.open",{url:GPT_OSS_MODEL_URL}),
  start:(model,options={})=>core.native.call("ai.local.start",{
    model:String(model||""),
    contextSize:Number(options.contextSize)||2048,
    threads:Number(options.threads)>0?Number(options.threads):undefined
  }),
  stop:()=>core.native.call("ai.local.stop",{}),
  log:()=>core.native.call("ai.local.log",{}),
  submit:(messages,options={})=>core.native.call("ai.local.chat",{
    messages:Array.isArray(messages)?messages:undefined,
    prompt:typeof messages==="string"?messages:undefined,
    temperature:options.temperature,
    maxTokens:options.maxTokens
  }),
  async chat(messages,options={}){
    const {pollMs=1000,timeoutMs=15*60*1000,...generation}=options||{};
    const job=await this.submit(messages,generation);
    return awaitLocalAiJob(job.jobId,{pollMs,timeoutMs});
  },
  prompt(prompt,options={}){return this.chat(String(prompt??""),options);},
  async promptText(prompt,options={}){
    const response=await this.prompt(prompt,options);
    return response?.choices?.[0]?.message?.content??"";
  }
});

const api=Object.freeze({
  info:()=>core.native.call("device.info",{}),
  vibrate:(milliseconds=40)=>core.native.call("device.vibrate",{milliseconds}),
  clipboard:Object.freeze({
    read:()=>core.native.call("clipboard.read",{}),
    write:text=>core.native.call("clipboard.write",{text:String(text??"")})
  }),
  share:(text,title="Share from RiftOS")=>core.native.call("share.text",{text:String(text??""),title}),
  open:url=>core.native.call("intent.open",{url:String(url)}),
  browser:url=>core.native.call("browser.open",{url:String(url||"https://chatgpt.com")}),
  preview:(root="",entry="index.html")=>core.native.call("preview.open",{root:String(root||""),entry:String(entry||"index.html")}),
  notify:(title,body="")=>core.native.call("notifications.show",{title:String(title||"RiftOS"),body:String(body||"")}),
  requestNotifications:()=>core.native.call("notifications.request",{}),
  secrets:Object.freeze({
    get:key=>core.native.call("secrets.get",{key:String(key)}),
    set:(key,value)=>core.native.call("secrets.set",{key:String(key),value:String(value??"")}),
    remove:key=>core.native.call("secrets.remove",{key:String(key)})
  }),
  localAI
});

globalThis.RiftAndroidAPI=api;
globalThis.RiftLocalAI=localAI;

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
console.info("[RiftAndroid] Samsung/Android platform services + local gpt-oss online");
