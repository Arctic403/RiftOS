const core=globalThis.RiftOSCore;
if(!core)throw new Error("Rift Bridge requires RiftOSCore");

const APP_ID="bridge";
const APP_NAME="Rift Bridge";

function open(){
  location.href="riftos://bridge";
  return true;
}

function ensureLauncherCard(){
  const grid=document.querySelector("#appGrid");
  if(!grid||grid.querySelector('[data-rift-system-app="bridge"]'))return;
  const button=document.createElement("button");
  button.className="app-card";
  button.dataset.riftSystemApp="bridge";
  button.innerHTML='<span class="app-icon">↔</span><span><strong>Rift Bridge</strong><br><small>AI tools, pairing, permissions & activity</small></span>';
  button.onclick=open;
  grid.append(button);
}

function ensureStartEntry(){
  const grid=document.querySelector("#riftStartMenu .rift-start-grid");
  if(!grid||grid.querySelector('[data-rift-system-app="bridge"]'))return;
  const button=document.createElement("button");
  button.dataset.riftSystemApp="bridge";
  button.innerHTML='<b>↔</b><span>Rift Bridge</span>';
  button.onclick=()=>{document.querySelector("#riftStartMenu")?.classList.remove("open");open();};
  grid.append(button);
}

function install(){
  runCatchingRegister();
  ensureLauncherCard();
  ensureStartEntry();
}

function runCatchingRegister(){
  try{core.kernel.registerApp({id:APP_ID,name:APP_NAME,installed:true,system:true,permissions:["sandbox.read","sandbox.write"]});}catch(_){}
}

window.addEventListener("riftos:launcher-ready",install);
document.addEventListener("DOMContentLoaded",install);
setTimeout(install,0);

globalThis.RiftBridge=Object.freeze({open});
console.info("[RiftBridge] system app registered");
