const DEMO_PACKAGE = {
  format: "rift-app-v1",
  manifest: {
    id: "demo.hello",
    name: "Hello Rift",
    version: "1.0.0",
    entry: "index.html",
    icon: "✦",
    description: "First locally installed RiftApp.",
    permissions: ["storage"]
  },
  files: {
    "index.html": "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font-family:system-ui;background:#0b0f16;color:#f5f7fb;margin:0;padding:28px}button{font:inherit;border:0;border-radius:12px;padding:12px 16px;margin:6px 6px 6px 0}input{font:inherit;padding:12px;border-radius:10px;border:1px solid #3a4352;background:#111723;color:white;width:min(320px,90%)}</style></head><body><h1>Hello from a .rift app ✦</h1><p>This page is running in its own sandbox inside RiftOS.</p><input id='name' placeholder='Type something'><div><button id='save'>Save locally</button><button id='load'>Load</button><button id='close'>Close app</button></div><pre id='out'></pre><script>const out=document.querySelector('#out');document.querySelector('#save').onclick=async()=>{await Rift.storage.set('demo',document.querySelector('#name').value);out.textContent='Saved inside this app sandbox.'};document.querySelector('#load').onclick=async()=>{out.textContent='Stored: '+await Rift.storage.get('demo')};document.querySelector('#close').onclick=()=>Rift.app.close();<\/script></body></html>"
  }
};

function safeFilename(value){
  return String(value || "rift-app")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, "-")
    .replace(/^-+|-+$/g, "") || "rift-app";
}

function portablePackage(app){
  return {
    format: "rift-app-v1",
    manifest: {
      id: app.manifest.id,
      name: app.manifest.name,
      version: app.manifest.version,
      entry: app.manifest.entry || "index.html",
      icon: app.manifest.icon || "R",
      description: app.manifest.description || "",
      permissions: Array.isArray(app.manifest.permissions) ? [...app.manifest.permissions] : []
    },
    files: { ...(app.files || {}) }
  };
}

async function savePackage(pkg, filename){
  const name = filename.endsWith(".rift") ? filename : `${filename}.rift`;
  const text = JSON.stringify(pkg, null, 2);
  const file = new File([text], name, { type: "application/json" });

  // iPhone/iPad PWAs are most reliable through the native share sheet.
  // "Save to Files" there gives the user a real .rift package they can
  // later re-import. Desktop browsers use a normal download instead.
  try{
    if(navigator.share && navigator.canShare?.({ files: [file] })){
      await navigator.share({ files: [file], title: name });
      return true;
    }
  }catch(error){
    if(error?.name === "AbortError") return false;
    console.warn("[RiftApps] native file share unavailable, falling back to download", error);
  }

  const url = URL.createObjectURL(file);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = name;
  anchor.style.display = "none";
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(()=>URL.revokeObjectURL(url), 30000);
  return true;
}

async function saveInstalled(id){
  const apps = await window.RiftApps?.list?.();
  const app = apps?.find(item=>item.id === id);
  if(!app) throw new Error(`RiftApp not installed: ${id}`);
  return savePackage(portablePackage(app), `${safeFilename(app.manifest.name)}-${safeFilename(app.manifest.version)}`);
}

async function saveDemo(){
  return savePackage(DEMO_PACKAGE, "hello-rift-demo.rift");
}

function renameImportControl(manager){
  const input = manager.querySelector("#riftPackageInput");
  const label = input?.closest("label");
  if(!label) return;

  const textNode = [...label.childNodes].find(node=>node.nodeType === Node.TEXT_NODE);
  if(textNode) textNode.textContent = "Import .rift";
  else label.insertBefore(document.createTextNode("Import .rift"), input);

  label.title = "Choose an existing .rift package from Files and install it";
  label.setAttribute("aria-label", "Import a .rift package from Files");
}

function addDemoSaveButton(manager){
  const actions = manager.querySelector(".rift-app-actions");
  if(!actions || actions.querySelector("#riftDemoSave")) return;

  const button = document.createElement("button");
  button.type = "button";
  button.id = "riftDemoSave";
  button.className = "rift-app-action secondary";
  button.textContent = "Save demo .rift";
  button.onclick = async()=>{
    try{
      const saved = await saveDemo();
      if(saved) document.querySelector("#statusText")?.replaceChildren("Saved demo .rift");
    }catch(error){
      alert(`Save failed: ${error?.message || error}`);
    }
  };
  actions.append(button);
}

function addInstalledSaveButtons(manager){
  manager.querySelectorAll("[data-rift-launch]").forEach(openButton=>{
    const id = openButton.dataset.riftLaunch;
    const controls = openButton.parentElement;
    if(!id || !controls || controls.querySelector(`[data-rift-save="${CSS.escape(id)}"]`)) return;

    const button = document.createElement("button");
    button.type = "button";
    button.dataset.riftSave = id;
    button.textContent = "Save .rift";
    button.onclick = async event=>{
      event.preventDefault();
      event.stopPropagation();
      try{
        const saved = await saveInstalled(id);
        if(saved) document.querySelector("#statusText")?.replaceChildren("Saved .rift package");
      }catch(error){
        alert(`Save failed: ${error?.message || error}`);
      }
    };
    controls.insertBefore(button, controls.querySelector(".danger"));
  });
}

function clarifyManagerCopy(manager){
  const paragraph = manager.querySelector(".rift-app-hero p");
  if(!paragraph || paragraph.dataset.riftFileCopy === "1") return;
  paragraph.dataset.riftFileCopy = "1";
  paragraph.textContent = ".rift apps live locally in RiftOS. Import .rift chooses an existing package from Files. Save .rift creates a package you can keep, share, or import again.";
}

function enhanceManager(){
  const manager = document.querySelector(".rift-app-manager");
  if(!manager) return;
  renameImportControl(manager);
  addDemoSaveButton(manager);
  addInstalledSaveButtons(manager);
  clarifyManagerCopy(manager);
}

const observer = new MutationObserver(enhanceManager);
observer.observe(document.documentElement, { childList: true, subtree: true });
document.addEventListener("DOMContentLoaded", enhanceManager, { once: true });
enhanceManager();

window.RiftAppFiles = Object.freeze({
  saveDemo,
  saveInstalled
});
