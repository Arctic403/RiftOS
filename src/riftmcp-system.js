const core=globalThis.RiftOSCore;
if(!core)throw new Error("Rift MCP requires RiftOSCore");

const APP_ID="mcp";
const APP_NAME="Rift MCP";

function open(){
  location.href="riftos://mcp";
  return true;
}

function ensureLauncherCard(){
  const grid=document.querySelector("#appGrid");
  if(!grid||grid.querySelector('[data-rift-system-app="mcp"]'))return;
  const button=document.createElement("button");
  button.className="app-card";
  button.dataset.riftSystemApp="mcp";
  button.innerHTML='<span class="app-icon">↔</span><span><strong>Rift MCP</strong><br><small>ChatGPT Web tools, permissions & activity</small></span>';
  button.onclick=open;
  grid.append(button);
}

function ensureStartEntry(){
  const grid=document.querySelector("#riftStartMenu .rift-start-grid");
  if(!grid||grid.querySelector('[data-rift-system-app="mcp"]'))return;
  const button=document.createElement("button");
  button.dataset.riftSystemApp="mcp";
  button.innerHTML='<b>↔</b><span>Rift MCP</span>';
  button.onclick=()=>{document.querySelector("#riftStartMenu")?.classList.remove("open");open();};
  grid.append(button);
}

function install(){
  try{core.kernel.registerApp({id:APP_ID,name:APP_NAME,installed:true,system:true,permissions:["sandbox.read","sandbox.write"]});}catch(_){}
  ensureLauncherCard();
  ensureStartEntry();
}

window.addEventListener("riftos:launcher-ready",install);
document.addEventListener("DOMContentLoaded",install);
setTimeout(install,0);

globalThis.RiftMcp=Object.freeze({open});
console.info("[RiftMcp] local ChatGPT Web tools registered");
