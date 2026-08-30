const RIFTDEV_URL = "./apps/riftdev/index.html";

function injectRiftDevStyles(){
  if(document.querySelector("#riftDevHostStyle")) return;
  const style=document.createElement("style");
  style.id="riftDevHostStyle";
  style.textContent=`
    .riftdev-window-body{padding:0!important;overflow:hidden!important;min-height:0!important}
    .riftdev-frame{display:block;width:100%;height:100%;min-height:calc(100dvh - 120px);border:0;background:#11141a}
  `;
  document.head.append(style);
}

function closeRiftDev(){
  const stage=document.querySelector("#stage");
  const workspace=document.querySelector("#workspace");
  if(stage){stage.innerHTML="";stage.classList.add("hidden")}
  workspace?.classList.remove("hidden");
  document.querySelectorAll(".dock-btn").forEach(btn=>btn.classList.toggle("active",btn.dataset.open==="home"));
  const status=document.querySelector("#statusText");
  if(status) status.textContent="Ready";
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
  win.querySelector(".window-kicker").textContent="RIFTDEV / EDITOR CLONE";
  win.querySelector(".window-title").textContent="RiftDev";
  win.querySelector(".window-close").onclick=closeRiftDev;

  const body=win.querySelector(".window-body");
  body.classList.add("riftdev-window-body");
  const frame=document.createElement("iframe");
  frame.className="riftdev-frame";
  frame.title="RiftDev Editor";
  frame.src=RIFTDEV_URL;
  frame.allow="clipboard-read; clipboard-write";
  body.append(frame);

  stage.append(win);
  const status=document.querySelector("#statusText");
  if(status) status.textContent="RiftDev";
}

function addRiftDevLauncher(){
  const grid=document.querySelector("#appGrid");
  if(!grid||grid.querySelector("[data-riftdev-open]")) return;
  const btn=document.createElement("button");
  btn.className="app-card";
  btn.dataset.riftdevOpen="1";
  btn.innerHTML='<span class="app-icon">{ }</span><span><strong>RiftDev</strong><br><small>Cloned Mobile Workspace editor</small></span>';
  grid.append(btn);
}

document.addEventListener("click",event=>{
  const button=event.target.closest("[data-riftdev-open]");
  if(!button) return;
  event.preventDefault();
  event.stopImmediatePropagation();
  try{openRiftDev()}catch(error){
    console.error("[RiftDev] launch failed",error);
    const status=document.querySelector("#statusText");
    if(status) status.textContent=`RiftDev: ${error.message}`;
  }
},true);

const grid=document.querySelector("#appGrid");
if(grid){
  new MutationObserver(addRiftDevLauncher).observe(grid,{childList:true});
  queueMicrotask(addRiftDevLauncher);
}

window.RiftDev=Object.freeze({open:openRiftDev});
console.info("[RiftDev] Editor clone host ready");
