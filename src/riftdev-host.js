const RIFTDEV_URL = new URL("../apps/riftdev/index.html?riftos=1", import.meta.url).href;
let riftDevFrame = null;
let riftDevHost = null;
let riftDevProcess = null;

function injectRiftDevStyles(){
  if(document.querySelector("#riftDevHostStyle")) return;
  const style=document.createElement("style");
  style.id="riftDevHostStyle";
  style.textContent=`
    html.riftdev-active,html.riftdev-active body{overflow:hidden!important;overscroll-behavior:none!important}
    .riftdev-fullscreen-host{position:fixed!important;inset:0!important;z-index:2147483000!important;width:100vw!important;height:100dvh!important;min-height:100dvh!important;margin:0!important;padding:0!important;border:0!important;background:#11141a!important;overflow:hidden!important}
    .riftdev-frame{display:block!important;width:100%!important;height:100%!important;min-width:0!important;min-height:0!important;border:0!important;background:#11141a!important;overflow:auto!important;-webkit-overflow-scrolling:touch!important;touch-action:auto!important}
    .riftdev-exit{position:absolute;z-index:2147483001;top:max(7px,env(safe-area-inset-top));left:max(7px,env(safe-area-inset-left));width:34px;height:34px;display:grid;place-items:center;border:1px solid rgba(255,255,255,.22);border-radius:50%;background:rgba(7,11,16,.82);color:#fff;font:500 27px/1 system-ui,-apple-system,sans-serif;box-shadow:0 2px 12px rgba(0,0,0,.35);backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);padding:0}
    .riftdev-exit:active{transform:scale(.94);background:rgba(26,35,48,.95)}
  `;
  document.head.append(style);
}

function setRiftDevStatus(text){
  const status=document.querySelector("#statusText");
  if(status) status.textContent=text;
}

function closeRiftDev(){
  riftDevFrame=null;
  riftDevHost?.remove();
  riftDevHost=null;
  document.documentElement.classList.remove("riftdev-active");
  if(riftDevProcess){
    window.RiftOSCore?.kernel?.kill?.(riftDevProcess.pid);
    riftDevProcess=null;
  }
  setRiftDevStatus("Ready");
}

function openShellFromRiftDev(){
  closeRiftDev();
  setTimeout(()=>{
    const shell=document.querySelector('.dock-btn[data-open="terminal"], [data-open="terminal"]');
    shell?.click();
  },0);
}

function openRiftDev(){
  injectRiftDevStyles();
  window.RiftDesktop?.closeWindow?.();
  closeRiftDev();
  document.documentElement.classList.add("riftdev-active");
  riftDevProcess=window.RiftOSCore?.kernel?.launchProcess?.("riftdev","RiftDev",{kind:"app"})||null;

  const host=document.createElement("section");
  host.className="riftdev-fullscreen-host";
  host.setAttribute("role","application");
  host.setAttribute("aria-label","RiftDev");

  const frame=document.createElement("iframe");
  frame.className="riftdev-frame";
  frame.title="RiftDev Editor";
  frame.src=RIFTDEV_URL;
  frame.allow="clipboard-read; clipboard-write";
  frame.setAttribute("scrolling","yes");
  frame.addEventListener("load",()=>setRiftDevStatus("RiftDev"));
  frame.addEventListener("error",()=>setRiftDevStatus("RiftDev failed to load"));

  const exit=document.createElement("button");
  exit.className="riftdev-exit";
  exit.type="button";
  exit.setAttribute("aria-label","Exit RiftDev");
  exit.textContent="×";
  exit.onclick=closeRiftDev;

  host.append(frame,exit);
  document.body.append(host);
  riftDevHost=host;
  riftDevFrame=frame;
  setRiftDevStatus("Loading RiftDev");
}

function addRiftDevLauncher(){
  const grid=document.querySelector("#appGrid");
  if(!grid||grid.querySelector("[data-riftdev-open]")) return;
  const btn=document.createElement("button");
  btn.className="app-card";
  btn.dataset.riftdevOpen="1";
  btn.innerHTML='<span class="app-icon">{ }</span><span><strong>RiftDev</strong><br><small>GitHub IDE · Actions · AI handoff</small></span>';
  grid.append(btn);
}

document.addEventListener("click",event=>{
  const button=event.target.closest("[data-riftdev-open]");
  if(!button) return;
  event.preventDefault();
  event.stopImmediatePropagation();
  try{openRiftDev()}catch(error){
    console.error("[RiftDev] launch failed",error);
    closeRiftDev();
    setRiftDevStatus(`RiftDev: ${error.message}`);
  }
},true);

window.addEventListener("message",event=>{
  if(event.origin!==location.origin||!event.data||typeof event.data!=="object") return;
  if(event.data.type==="riftdev:close"){
    closeRiftDev();
    return;
  }
  if(event.data.type==="riftdev:open-shell"){
    openShellFromRiftDev();
    return;
  }
  if(event.data.type==="riftdev:github-token"){
    const token=String(event.data.token||"").trim();
    if(token) sessionStorage.setItem("riftgit-token",token);
    else sessionStorage.removeItem("riftgit-token");
  }
});

const grid=document.querySelector("#appGrid");
if(grid){
  new MutationObserver(addRiftDevLauncher).observe(grid,{childList:true});
  queueMicrotask(addRiftDevLauncher);
}

window.RiftDev=Object.freeze({open:openRiftDev,close:closeRiftDev});
console.info("[RiftDev] fullscreen Editor clone host ready", RIFTDEV_URL);
