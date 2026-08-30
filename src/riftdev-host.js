const RIFTDEV_URL = "./apps/riftdev/index.html?riftos=1";
let riftDevFrame = null;

function injectRiftDevStyles(){
  if(document.querySelector("#riftDevHostStyle")) return;
  const style=document.createElement("style");
  style.id="riftDevHostStyle";
  style.textContent=`
    .riftdev-shell{display:flex!important;flex-direction:column!important;width:100%!important;height:100%!important;min-height:0!important;max-width:none!important;margin:0!important;border-radius:0!important}
    .riftdev-shell>.window-bar{flex:0 0 auto}
    .riftdev-window-body{padding:0!important;overflow:hidden!important;min-height:0!important;flex:1 1 auto!important}
    .riftdev-frame{display:block;width:100%;height:100%;min-height:0;border:0;background:#11141a}
    .riftdev-host-action{border:1px solid #344154;background:#172130;color:#f4f7fb;border-radius:8px;padding:6px 9px;font:700 12px system-ui,-apple-system,sans-serif;margin-left:auto}
    .riftdev-host-action+.window-close{margin-left:6px}
  `;
  document.head.append(style);
}

function setRiftDevStatus(text){
  const status=document.querySelector("#statusText");
  if(status) status.textContent=text;
}

function closeRiftDev(){
  riftDevFrame=null;
  const stage=document.querySelector("#stage");
  const workspace=document.querySelector("#workspace");
  if(stage){stage.innerHTML="";stage.classList.add("hidden")}
  workspace?.classList.remove("hidden");
  document.querySelectorAll(".dock-btn").forEach(btn=>btn.classList.toggle("active",btn.dataset.open==="home"));
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
  const stage=document.querySelector("#stage");
  const workspace=document.querySelector("#workspace");
  const template=document.querySelector("#windowTemplate");
  if(!stage||!workspace||!template) throw new Error("RiftOS window host is unavailable.");

  workspace.classList.add("hidden");
  stage.classList.remove("hidden");
  stage.innerHTML="";

  const win=template.content.firstElementChild.cloneNode(true);
  win.dataset.app="riftdev";
  win.classList.add("riftdev-shell");
  win.querySelector(".window-kicker").textContent="RIFTDEV / RIFTOS WORKSPACE";
  win.querySelector(".window-title").textContent="RiftDev";
  const close=win.querySelector(".window-close");
  close.onclick=closeRiftDev;

  const reload=document.createElement("button");
  reload.className="riftdev-host-action";
  reload.type="button";
  reload.textContent="Reload";
  reload.onclick=()=>{
    if(!riftDevFrame) return;
    setRiftDevStatus("Reloading RiftDev");
    riftDevFrame.src=RIFTDEV_URL+`&reload=${Date.now()}`;
  };
  close.before(reload);

  const body=win.querySelector(".window-body");
  body.classList.add("riftdev-window-body");
  const frame=document.createElement("iframe");
  frame.className="riftdev-frame";
  frame.title="RiftDev Editor";
  frame.src=RIFTDEV_URL;
  frame.allow="clipboard-read; clipboard-write";
  frame.addEventListener("load",()=>setRiftDevStatus("RiftDev"));
  frame.addEventListener("error",()=>setRiftDevStatus("RiftDev failed to load"));
  body.append(frame);
  riftDevFrame=frame;

  stage.append(win);
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
console.info("[RiftDev] native Editor clone host ready");
