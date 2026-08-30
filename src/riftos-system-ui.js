const core = window.RiftOSCore;
if(!core) throw new Error("RiftOSCore must load before the True OS system UI");

const q=s=>document.querySelector(s);
const escapeHTML=value=>String(value??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
const fmtBytes=n=>{n=Number(n||0);if(n<1024)return `${n} B`;if(n<1024**2)return `${(n/1024).toFixed(1)} KB`;if(n<1024**3)return `${(n/1024**2).toFixed(1)} MB`;return `${(n/1024**3).toFixed(2)} GB`;};
let activeSystemProcess=null;

function setStatus(text){const el=q("#statusText");if(el)el.textContent=text;}
function killActiveSystemProcess(){if(activeSystemProcess){core.kernel.kill(activeSystemProcess.pid);activeSystemProcess=null;}}
function home(){
  killActiveSystemProcess();
  const stage=q("#stage"),workspace=q("#workspace");
  if(stage){stage.innerHTML="";stage.classList.add("hidden");}
  workspace?.classList.remove("hidden");
  document.querySelectorAll(".dock-btn").forEach(x=>x.classList.toggle("active",x.dataset.open==="home"));
  setStatus("Ready");
}
function systemWindow(id,title,kicker="TRUE OS"){
  killActiveSystemProcess();
  const stage=q("#stage"),workspace=q("#workspace"),template=q("#windowTemplate");
  if(!stage||!workspace||!template)throw new Error("RiftOS window host unavailable");
  workspace.classList.add("hidden");stage.classList.remove("hidden");stage.innerHTML="";
  const win=template.content.firstElementChild.cloneNode(true);win.dataset.app=id;
  win.querySelector(".window-title").textContent=title;win.querySelector(".window-kicker").textContent=kicker;
  activeSystemProcess=core.kernel.launchProcess(id,title,{kind:"ui"});
  win.querySelector(".window-close").onclick=home;stage.append(win);
  document.querySelectorAll(".dock-btn").forEach(x=>x.classList.toggle("active",x.dataset.open===id));
  setStatus(title);return win.querySelector(".window-body");
}

function ensureStyles(){
  if(q("#trueOSStyles"))return;
  const style=document.createElement("style");style.id="trueOSStyles";style.textContent=`
  .trueos-head{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:10px}.trueos-head>div{flex:1;min-width:160px}.trueos-head strong{display:block}.trueos-head small,.trueos-muted{color:#95a3b5}.trueos-chip{border:1px solid #344154;border-radius:999px;padding:5px 8px;font:700 11px system-ui}.trueos-chip.ok{color:#8ce0ae}.trueos-chip.warn{color:#f2ce78}.trueos-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:8px}.trueos-card{border:1px solid #293544;border-radius:12px;background:#0d131b;padding:11px}.trueos-card strong{display:block;margin-bottom:5px}.trueos-card small{color:#94a1b2}.trueos-files{display:flex;flex-direction:column;border:1px solid #293544;border-radius:12px;overflow:hidden}.trueos-file{display:flex;align-items:center;gap:8px;border:0;border-bottom:1px solid #222d3a;background:#0c1219;color:#eef4fb;padding:11px;text-align:left;font:inherit}.trueos-file:last-child{border-bottom:0}.trueos-file span{flex:1;overflow-wrap:anywhere}.trueos-file small{color:#94a1b2}.trueos-toolbar{display:flex;gap:7px;flex-wrap:wrap;margin:8px 0}.trueos-btn{border:1px solid #354255;background:#172130;color:#f4f7fb;border-radius:9px;padding:8px 10px;font:700 12px system-ui}.trueos-btn.primary{background:#eef4fb;color:#0a1119}.trueos-editor{display:flex;flex-direction:column;height:min(70dvh,650px);gap:8px}.trueos-editor textarea{flex:1;min-height:300px;resize:none;border:1px solid #293544;border-radius:10px;background:#070b10;color:#edf3fa;padding:12px;font:13px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace}.trueos-table{width:100%;border-collapse:collapse}.trueos-table th,.trueos-table td{text-align:left;padding:8px;border-bottom:1px solid #25303d;font-size:12px}.trueos-table th{color:#93a2b5}.trueos-code{padding:10px;border:1px solid #293544;border-radius:10px;background:#070b10;white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.45 ui-monospace,SFMono-Regular,Menlo,monospace}
  `;document.head.append(style);
}

async function openTrueFiles(){
  ensureStyles();const body=systemWindow("files","Files","RIFTFS / TRUE OS");
  const storage=await core.fs.estimate();const rows=await core.fs.list();
  body.innerHTML=`<div class="trueos-head"><div><strong>RiftFS</strong><small>${escapeHTML(storage.backend)} · ${rows.length} files · ${fmtBytes(storage.usage)} used</small></div><span class="trueos-chip ${core.fs.opfsReady?"ok":"warn"}">${core.fs.opfsReady?"OPFS":"COMPAT"}</span></div><div class="trueos-toolbar"><button class="trueos-btn primary" id="trueNewFile">New file</button><button class="trueos-btn" id="trueSyncFS">Sync mirror</button></div><div class="trueos-files">${rows.length?rows.map(f=>`<button class="trueos-file" data-true-file="${escapeHTML(f.path)}"><span>${escapeHTML(f.path)}</span><small>${fmtBytes(new Blob([f.content]).size)}</small></button>`).join(""):`<div class="trueos-card trueos-muted">RiftFS is empty.</div>`}</div>`;
  body.querySelectorAll("[data-true-file]").forEach(btn=>btn.onclick=()=>openTrueEditor(btn.dataset.trueFile));
  body.querySelector("#trueNewFile").onclick=()=>openTrueEditor("/home/untitled.txt");
  body.querySelector("#trueSyncFS").onclick=async()=>{setStatus("Syncing RiftFS");await core.fs.list();setStatus("RiftFS synchronized");setTimeout(()=>openTrueFiles(),250);};
}

async function openTrueEditor(path="/home/scratch.txt"){
  ensureStyles();path=core.path.normalize(path);const file=await core.fs.get(path);const body=systemWindow("editor","Editor","RIFTFS EDITOR");
  body.innerHTML=`<div class="trueos-editor"><div class="trueos-head"><div><strong>${escapeHTML(path)}</strong><small>${core.fs.opfsReady?"OPFS + compatibility mirror":"IndexedDB compatibility storage"}</small></div><button class="trueos-btn" id="trueBackFiles">Files</button><button class="trueos-btn primary" id="trueSave">Save</button></div><textarea spellcheck="false" autocomplete="off"></textarea></div>`;
  const ta=body.querySelector("textarea");ta.value=file?.content||"";
  body.querySelector("#trueSave").onclick=async()=>{await core.fs.write(path,ta.value);setStatus("Saved to RiftFS");setTimeout(()=>setStatus("Editor"),900);};
  body.querySelector("#trueBackFiles").onclick=openTrueFiles;setTimeout(()=>ta.focus(),40);
}

async function openTrueTasks(){
  ensureStyles();const body=systemWindow("tasks","Tasks","RIFTKERNEL PROCESS TABLE");
  const render=()=>{
    const rows=core.processes.list();
    body.innerHTML=`<div class="trueos-head"><div><strong>RiftKernel processes</strong><small>Kernel uptime ${core.kernel.uptime()}s</small></div><span class="trueos-chip ok">${rows.length} RUNNING</span></div><table class="trueos-table"><thead><tr><th>PID</th><th>Process</th><th>Kind</th><th>State</th><th></th></tr></thead><tbody>${rows.map(p=>`<tr><td>${p.pid}</td><td>${escapeHTML(p.name)}</td><td>${escapeHTML(p.kind||p.appId||"app")}</td><td>${escapeHTML(p.state)}</td><td>${p.protected?"system":`<button class="trueos-btn" data-true-kill="${p.pid}">Kill</button>`}</td></tr>`).join("")}</tbody></table>`;
    body.querySelectorAll("[data-true-kill]").forEach(btn=>btn.onclick=()=>{core.kernel.kill(btn.dataset.trueKill);render();});
  };render();
}

async function openTrueSettings(){
  ensureStyles();const body=systemWindow("settings","Settings","TRUE OS SYSTEM");const info=await core.kernel.info();const caps=info.native;
  body.innerHTML=`<div class="trueos-head"><div><strong>RiftOS ${escapeHTML(info.version)}</strong><small>${escapeHTML(info.mode)} · kernel uptime ${info.uptime}s</small></div><span class="trueos-chip ${caps.nativeHost?"ok":"warn"}">${caps.nativeHost?"NATIVE HOST":"PWA MODE"}</span></div><div class="trueos-grid"><div class="trueos-card"><strong>RiftFS</strong><small>${escapeHTML(info.storage.backend)}<br>${fmtBytes(info.storage.usage)} / ${info.storage.quota?fmtBytes(info.storage.quota):"browser managed"}</small><div class="trueos-toolbar"><button class="trueos-btn" id="truePersist">Request persistent storage</button></div></div><div class="trueos-card"><strong>RiftNative</strong><small>${caps.nativeHost?"Swift bridge connected.":"Web capability fallback. Native bridge API is ready for the iOS host."}</small></div><div class="trueos-card"><strong>Process model</strong><small>${info.processes} active process(es)<br>${info.apps} registered system apps</small></div><div class="trueos-card"><strong>Capabilities</strong><small>OPFS ${caps.opfs?"✓":"—"} · Share ${caps.share?"✓":"—"} · Notifications ${caps.notifications?"✓":"—"} · Clipboard ${caps.clipboard?"✓":"—"}</small></div></div><div class="trueos-toolbar"><button class="trueos-btn" id="trueMounts">Show mounts</button><button class="trueos-btn" id="truePerms">Permission model</button></div><pre class="trueos-code" id="trueSettingsOutput">True OS Core is active.</pre>`;
  const out=body.querySelector("#trueSettingsOutput");
  body.querySelector("#truePersist").onclick=async()=>{const granted=await core.fs.persist();out.textContent=granted?"Persistent browser storage granted/already active.":"Browser did not grant persistent storage. RiftFS remains available under normal origin-storage rules.";};
  body.querySelector("#trueMounts").onclick=()=>{out.textContent=core.kernel.mounts().map(m=>`${m.path}\t${m.type}\t${m.mode}\t${m.label}`).join("\n");};
  body.querySelector("#truePerms").onclick=()=>{out.textContent=`Capability vocabulary:\n${core.permissions.describe().join("\n")}\n\nBuilt-in system apps receive only their declared kernel capabilities.`;};
}

function words(raw){
  const out=[];String(raw||"").replace(/"([^"]*)"|'([^']*)'|([^\s]+)/g,(_,a,b,c)=>{out.push(a??b??c);return "";});return out;
}
const cwdByForm=new WeakMap();
function resolvePath(cwd,value){if(!value)return cwd;return core.path.normalize(String(value).startsWith("/")?value:`${cwd}/${value}`);}
function shellPrint(form,text){const out=form.closest(".shell")?.querySelector(".shell-output");if(!out)return;out.textContent+=String(text)+"\n";out.scrollTop=out.scrollHeight;}

async function runTrueShell(form,raw){
  const args=words(raw),cmd=(args.shift()||"").toLowerCase();let cwd=cwdByForm.get(form)||"/home";
  if(!cmd)return true;
  const print=text=>shellPrint(form,text);
  if(cmd==="help") print("TrueOS commands:\nhelp  sysinfo  mount  df  ps  kill <pid>  apps  permissions  native\npwd  cd <dir>  ls [path]  cat <file>  write <file> <text>  mkdir <dir>  rm <path>  syncfs\nopen <app>  clear  uptime  version\n\nGit commands are still handled by RiftGit.");
  else if(cmd==="sysinfo") print(JSON.stringify(await core.kernel.info(),null,2));
  else if(cmd==="mount") print(core.kernel.mounts().map(m=>`${m.path}\t${m.type}\t${m.mode}\t${m.label}`).join("\n"));
  else if(cmd==="df"){const s=await core.fs.estimate();print(`${s.backend}\nused ${fmtBytes(s.usage)}\nquota ${s.quota?fmtBytes(s.quota):"browser managed"}`);}
  else if(cmd==="ps") print(core.processes.list().map(p=>`${p.pid}\t${p.state}\t${p.kind||p.appId||"app"}\t${p.name}`).join("\n"));
  else if(cmd==="kill") print(core.kernel.kill(args[0])?`terminated ${args[0]}`:`cannot terminate ${args[0]||"(missing pid)"}`);
  else if(cmd==="apps") print([...core.kernel.apps.values()].map(a=>`${a.id}\t${a.name}`).join("\n"));
  else if(cmd==="permissions") print(core.permissions.describe().join("\n"));
  else if(cmd==="native") print(JSON.stringify(core.native.capabilities(),null,2));
  else if(cmd==="pwd") print(cwd);
  else if(cmd==="cd"){cwd=resolvePath(cwd,args[0]||"/home");cwdByForm.set(form,cwd);print(cwd);}
  else if(cmd==="ls"){
    const base=resolvePath(cwd,args[0]||cwd).replace(/\/$/,"");const rows=await core.fs.list();
    const names=new Set();for(const row of rows){if(row.path===base){names.add(core.path.basename(row.path));continue;}if(!row.path.startsWith(base+"/"))continue;const rest=row.path.slice(base.length+1);names.add(rest.split("/")[0]+(rest.includes("/")?"/":""));}print([...names].sort().join("\n")||"(empty)");
  }
  else if(cmd==="cat"){const p=resolvePath(cwd,args[0]);const file=await core.fs.get(p);print(file?file.content:`file not found: ${p}`);}
  else if(cmd==="write"){if(!args[0])print("usage: write <file> <text>");else{const p=resolvePath(cwd,args.shift());await core.fs.write(p,args.join(" "));print(`written ${p}`);}}
  else if(cmd==="mkdir"){if(!args[0])print("usage: mkdir <dir>");else{const p=resolvePath(cwd,args[0]);await core.fs.mkdir(p);print(`created ${p}`);}}
  else if(cmd==="rm"){if(!args[0])print("usage: rm <path>");else{const p=resolvePath(cwd,args[0]);const rows=await core.fs.list();const affected=rows.filter(r=>r.path===p||r.path.startsWith(p+"/"));if(affected.length){for(const f of affected)await core.fs.remove(f.path);}else await core.fs.remove(p);print(`removed ${p}`);}}
  else if(cmd==="syncfs"){const rows=await core.fs.list();print(`RiftFS synchronized: ${rows.length} files`);}
  else if(cmd==="uptime") print(`${core.kernel.uptime()}s`);
  else if(cmd==="version") print(`RiftOS ${core.kernel.version} / RiftKernel True OS Core`);
  else if(cmd==="clear"){const out=form.closest(".shell")?.querySelector(".shell-output");if(out)out.textContent="";}
  else if(cmd==="open"){
    const id=(args[0]||"").toLowerCase();if(!id)print("usage: open <app>");
    else if(id==="riftdev"){print("opening RiftDev...");setTimeout(()=>window.RiftDev?.open?.(),80);}
    else{const launcher=document.querySelector(`[data-open="${CSS.escape(id)}"]`);if(launcher){print(`opening ${id}...`);setTimeout(()=>launcher.click(),80);}else print(`app not found: ${id}`);}
  } else return false;
  return true;
}

document.addEventListener("submit",async event=>{
  const form=event.target;if(!(form instanceof HTMLFormElement)||!form.classList.contains("shell-line"))return;
  const input=form.querySelector("input");const raw=String(input?.value||"").trim();if(!raw)return;
  if(/^(git|gh|github)(?:\s|$)/i.test(raw))return;
  const command=(words(raw)[0]||"").toLowerCase();
  const supported=new Set(["help","sysinfo","mount","df","ps","kill","apps","permissions","native","pwd","cd","ls","cat","write","mkdir","rm","syncfs","uptime","version","clear","open"]);
  if(!supported.has(command))return;
  event.preventDefault();event.stopImmediatePropagation();if(input)input.value="";shellPrint(form,`rift$ ${raw}`);
  try{await core.ready;await runTrueShell(form,raw);}catch(err){shellPrint(form,`trueos: ${err?.message||err}`);}
},true);

document.addEventListener("click",event=>{
  const button=event.target.closest("[data-open]");if(!button)return;const id=button.dataset.open;
  if(id==="files"||id==="editor"||id==="tasks"||id==="settings"){
    event.preventDefault();event.stopImmediatePropagation();
    core.ready.then(()=>id==="files"?openTrueFiles():id==="editor"?openTrueEditor():id==="tasks"?openTrueTasks():openTrueSettings()).catch(err=>setStatus(`TrueOS: ${err.message}`));
    return;
  }
  if(id!=="home"&&id!=="terminal"){
    killActiveSystemProcess();activeSystemProcess=core.kernel.launchProcess(id,id,{kind:"legacy-ui"});
  }
},true);

window.addEventListener("message",event=>{
  if(event.origin!==location.origin||!event.data||typeof event.data!=="object")return;
  if(event.data.type==="riftdev:fs-write"&&event.data.path) core.fs.write(event.data.path,event.data.content??"").catch(console.error);
  if(event.data.type==="riftdev:fs-read"&&event.data.id&&event.data.path){core.fs.get(event.data.path).then(file=>event.source?.postMessage({type:"riftdev:fs-response",id:event.data.id,ok:true,file},event.origin)).catch(error=>event.source?.postMessage({type:"riftdev:fs-response",id:event.data.id,ok:false,error:error.message},event.origin));}
});

core.ready.then(async()=>{
  ensureStyles();document.documentElement.dataset.riftKernel="trueos";
  const info=await core.kernel.info();console.info("[TrueOS] booted",info);
  window.dispatchEvent(new CustomEvent("riftos:trueos-ready",{detail:info}));
}).catch(error=>console.error("[TrueOS] system UI failed",error));

window.RiftSystemUI=Object.freeze({files:openTrueFiles,editor:openTrueEditor,tasks:openTrueTasks,settings:openTrueSettings,home});
