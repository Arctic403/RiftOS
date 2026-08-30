const core=window.RiftOSCore;
const browser=core?.kernel?.browser;
if(!browser)throw new Error("RiftBrowser UI adapter requires RiftKernel.browser");

const $=selector=>document.querySelector(selector);
const escapeHTML=value=>String(value??"").replace(/[&<>"']/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[ch]));
let browserProcess=null;
let browserWindow=null;

function setStatus(value){const el=$("#statusText");if(el)el.textContent=value;}
function killBrowserProcess(){if(browserProcess){core.kernel.kill(browserProcess.pid);browserProcess=null;}}
function closeWebBrowser(){
  killBrowserProcess();browserWindow=null;
  const stage=$("#stage"),workspace=$("#workspace");
  if(stage?.querySelector('[data-app="browser-kernel"]')){stage.innerHTML="";stage.classList.add("hidden");workspace?.classList.remove("hidden");}
  document.querySelectorAll(".dock-btn").forEach(btn=>btn.classList.toggle("active",btn.dataset.open==="home"));
  setStatus("Ready");
}
function externalOpen(url){if(!url)return;const popup=window.open(url,"_blank","noopener,noreferrer");if(!popup)location.href=url;}

function createWindow(){
  const stage=$("#stage"),workspace=$("#workspace"),template=$("#windowTemplate");
  if(!stage||!workspace||!template)throw new Error("RiftOS browser window host is unavailable");
  killBrowserProcess();workspace.classList.add("hidden");stage.classList.remove("hidden");stage.innerHTML="";
  const win=template.content.firstElementChild.cloneNode(true);win.dataset.app="browser-kernel";
  win.querySelector(".window-title").textContent="RiftBrowser";
  win.querySelector(".window-kicker").textContent="EXPERIMENTAL ENGINE LAB";
  win.querySelector(".window-close").onclick=closeWebBrowser;
  stage.append(win);browserProcess=core.kernel.launchProcess("browser","RiftBrowser",{kind:"experimental-browser"});
  document.querySelectorAll(".dock-btn").forEach(btn=>btn.classList.toggle("active",btn.dataset.open==="browser"));
  setStatus("RiftBrowser");browserWindow=win.querySelector(".window-body");return browserWindow;
}

function renderChrome(){
  const body=browserWindow;if(!body)return;
  const tab=browser.activeTab(),status=browser.backendStatus(),tabs=browser.listTabs();
  const wasm=status.backends.find(item=>item.id==="wasm-gecko");
  body.innerHTML=`<div class="kbrowser">
    <div class="kbrowser-tabs">
      <div class="kbrowser-tab-list">${tabs.map(item=>`<button class="kbrowser-tab ${item.id===tab?.id?"active":""}" data-browser-tab="${escapeHTML(item.id)}"><span>${escapeHTML(item.title||"New Tab")}</span><i data-browser-close="${escapeHTML(item.id)}">×</i></button>`).join("")}</div>
      <button class="kbrowser-icon" id="kbNewTab" title="New tab">＋</button>
    </div>
    <form class="kbrowser-bar" id="kbAddressForm">
      <button type="button" class="kbrowser-icon" id="kbBack" ${!tab||tab.historyIndex<=0?"disabled":""}>‹</button>
      <button type="button" class="kbrowser-icon" id="kbForward" ${!tab||tab.historyIndex>=tab.history.length-1?"disabled":""}>›</button>
      <button type="button" class="kbrowser-icon" id="kbReload">↻</button>
      <input id="kbAddress" value="${escapeHTML(tab?.url||"")}" placeholder="Search or enter a URL" autocomplete="off" autocapitalize="off" spellcheck="false">
      <button type="submit" class="kbrowser-go">Go</button>
    </form>
    <div class="kbrowser-meta">
      <span id="kbState">${escapeHTML(status.active)}</span>
      <span>${wasm?.available?"Gecko WASM ready":"Gecko WASM artifact pending"}</span>
      <span class="kbrowser-spacer"></span>
      <button type="button" id="kbEngine">Engine</button>
      <button type="button" id="kbWisp">Wisp</button>
      <button type="button" id="kbExternal">↗</button>
    </div>
    <div class="kbrowser-surface" id="kbSurface"></div>
  </div>`;
  body.querySelector("#kbAddressForm").onsubmit=event=>{event.preventDefault();browser.navigate(body.querySelector("#kbAddress").value).catch(showError);};
  body.querySelector("#kbBack").onclick=()=>browser.back().catch(showError);
  body.querySelector("#kbForward").onclick=()=>browser.forward().catch(showError);
  body.querySelector("#kbReload").onclick=()=>browser.reload().catch(showError);
  body.querySelector("#kbNewTab").onclick=()=>browser.newTab().catch(showError);
  body.querySelector("#kbExternal").onclick=()=>externalOpen(browser.activeTab()?.url);
  body.querySelector("#kbEngine").onclick=()=>showEnginePanel();
  body.querySelector("#kbWisp").onclick=()=>configureWisp();
  body.querySelectorAll("[data-browser-tab]").forEach(button=>button.addEventListener("click",event=>{
    if(event.target.closest("[data-browser-close]"))return;
    const selected=browser.selectTab(button.dataset.browserTab);renderChrome();browser.navigate(selected.url,{tabId:selected.id,replace:true}).catch(showError);
  }));
  body.querySelectorAll("[data-browser-close]").forEach(button=>button.onclick=event=>{
    event.stopPropagation();browser.closeTab(button.dataset.browserClose);if(!browser.activeTab())browser.createTab();renderChrome();
  });
  renderStart();
}

function renderStart(){
  const surface=browserWindow?.querySelector("#kbSurface");if(!surface)return;
  const status=browser.backendStatus(),wasm=status.backends.find(item=>item.id==="wasm-gecko");
  surface.innerHTML=`<section class="browser-start kbrowser-start"><div class="browser-logo">R</div><h2>RiftBrowser is an engine experiment.</h2><p>There is no fake “stable browser” fallback. RiftKernel stays stable while browser engines are replaceable services.</p><div class="browser-capability"><strong>${wasm?.available?"Gecko WASM engine detected":"Gecko WASM engine not deployed yet"}</strong><span>${wasm?.available?"This unsigned Gecko engine renders into a canvas and uses Wisp for arbitrary network access.":"The shell is ready; the engine package still has to deploy and cross-origin isolation must be active."}</span></div><div class="browser-block-actions"><button class="action" id="kbProbeEngine">Probe engine</button><button class="action" id="kbConfigureWisp">Configure Wisp</button></div></section>`;
  surface.querySelector("#kbProbeEngine").onclick=async()=>{await browser.refreshEngines(true);renderChrome();};
  surface.querySelector("#kbConfigureWisp").onclick=()=>configureWisp();
}

function showEnginePanel(){
  const surface=browserWindow?.querySelector("#kbSurface");if(!surface)return;
  const info=browser.info();
  surface.innerHTML=`<section class="browser-start kbrowser-start"><h2>RiftBrowser engine status</h2><pre class="kbrowser-diagnostics">${escapeHTML(JSON.stringify(info,null,2))}</pre><div class="browser-block-actions"><button class="action" id="kbEngineAuto">Auto</button><button class="action" id="kbEngineWasm">Gecko WASM</button><button class="action" id="kbEngineLegacy">Legacy transport</button></div></section>`;
  surface.querySelector("#kbEngineAuto").onclick=()=>browser.setBackend("auto").then(()=>browser.reload()).catch(showError);
  surface.querySelector("#kbEngineWasm").onclick=()=>browser.setBackend("wasm-gecko").then(()=>browser.reload()).catch(showError);
  surface.querySelector("#kbEngineLegacy").onclick=()=>browser.setBackend("web-transport").then(()=>browser.reload()).catch(showError);
}

function configureWisp(){
  const current=browser.backendStatus().wisp||"";
  const next=window.prompt?.("Wisp WebSocket endpoint (wss://…/). Leave blank to clear.",current);
  if(next===null||next===undefined)return;
  try{browser.setWisp(next);setStatus(next.trim()?"Wisp configured":"Wisp cleared");if(browser.activeTab())browser.reload().catch(showError);}
  catch(error){showError(error);}
}

function showError(error){
  const surface=browserWindow?.querySelector("#kbSurface");if(surface)surface.innerHTML=`<section class="browser-blocked"><div class="browser-warning">!</div><h2>Browser service error</h2><p>${escapeHTML(error?.message||error)}</p></section>`;
}
function renderResult(detail){
  if(!browserWindow)return;
  renderChrome();
  const surface=browserWindow?.querySelector("#kbSurface"),state=browserWindow?.querySelector("#kbState"),result=detail?.result||{};
  if(!surface)return;
  if(state)state.textContent=result.backend||"unknown";
  if(result.mode==="engine-frame"){
    surface.innerHTML='<iframe class="kbrowser-engine-frame" title="RiftBrowser Gecko WASM engine" allow="clipboard-read; clipboard-write; autoplay; fullscreen"></iframe>';
    surface.querySelector("iframe").src=result.src;
  }else if(result.mode==="document"){
    surface.innerHTML='<iframe class="webview direct-view kbrowser-frame" sandbox="allow-forms allow-popups" referrerpolicy="no-referrer"></iframe>';
    surface.querySelector("iframe").srcdoc=result.html||"";
  }else if(result.mode==="external"){
    surface.innerHTML=`<section class="browser-blocked"><div class="browser-warning">↗</div><h2>No full engine is available yet</h2><p>The legacy transport hit normal web security. Once Gecko WASM is ready, auto mode will use that engine instead.</p><code>${escapeHTML(result.url||"")}</code><small>${escapeHTML(result.reason||"")}</small><div class="browser-block-actions"><button class="action" id="kbOpenExternal">Open externally</button><button class="action" id="kbRetryEngine">Probe engine</button></div></section>`;
    surface.querySelector("#kbOpenExternal").onclick=()=>externalOpen(result.url);
    surface.querySelector("#kbRetryEngine").onclick=async()=>{await browser.refreshEngines(true);await browser.reload();};
  }
}

async function openBrowser(initial="https://chatgpt.com",{newTab=false}={}){
  createWindow();if(!browser.activeTab())browser.createTab(initial);renderChrome();
  await browser.refreshEngines();return browser.open(initial,{newTab});
}

browser.addEventListener("loading",event=>{
  const state=browserWindow?.querySelector("#kbState"),surface=browserWindow?.querySelector("#kbSurface");
  if(state)state.textContent=`${event.detail.backend} · loading`;
  if(surface)surface.innerHTML=`<div class="browser-loading"><i></i><span>Loading ${escapeHTML(event.detail.tab.url)}</span></div>`;
});
browser.addEventListener("render",event=>renderResult(event.detail));
browser.addEventListener("native",()=>{if(browserWindow)renderChrome();});

document.addEventListener("click",event=>{
  const target=event.target.closest?.("[data-open]");if(!target)return;
  if(target.dataset.open==="browser"){
    event.preventDefault();event.stopImmediatePropagation();openBrowser().catch(showError);return;
  }
  if(browserWindow)closeWebBrowser();
},true);

document.addEventListener("submit",event=>{
  const form=event.target;if(!form.matches?.("form.shell-line"))return;
  const input=form.querySelector("input"),raw=input?.value?.trim()||"";
  const parts=raw.split(/\s+/),cmd=(parts.shift()||"").toLowerCase();
  if(!["browser","browserctl"].includes(cmd))return;
  event.preventDefault();event.stopImmediatePropagation();if(input)input.value="";
  const out=form.closest(".shell")?.querySelector(".shell-output");
  const print=value=>{if(out){out.textContent+=String(value??"")+"\n";out.scrollTop=out.scrollHeight;}};
  print(`rift$ ${raw}`);
  (async()=>{
    if(cmd==="browser"){await openBrowser(parts.join(" ")||"https://chatgpt.com");print(`[browser] ${browser.backendStatus().active}`);return;}
    const sub=(parts.shift()||"status").toLowerCase();
    if(sub==="status")return print(JSON.stringify(browser.info(),null,2));
    if(sub==="tabs")return print(browser.listTabs().map(tab=>`${tab.id===browser.activeTab()?.id?"*":" "} ${tab.id} ${tab.url}`).join("\n")||"(no tabs)");
    if(sub==="backends")return print(browser.backendStatus().backends.map(item=>`${item.available?"+":"-"} ${item.id} ${item.name}`).join("\n"));
    if(sub==="backend")return print(JSON.stringify(await browser.setBackend(parts[0]||"auto"),null,2));
    if(sub==="probe")return print(JSON.stringify(await browser.refreshEngines(true),null,2));
    if(sub==="wisp")return print(`wisp=${browser.setWisp(parts.join(" "))||"(unset)"}`);
    if(sub==="new")return openBrowser(parts.join(" ")||"https://chatgpt.com",{newTab:true});
    if(sub==="back")return browser.back();
    if(sub==="forward")return browser.forward();
    if(sub==="reload")return browser.reload();
    if(sub==="bookmark")return print(JSON.stringify(browser.bookmark(parts.join(" ")||undefined),null,2));
    if(sub==="bookmarks")return print(JSON.stringify(browser.bookmarks(),null,2));
    if(sub==="close")return print(browser.closeTab(parts[0]||browser.activeTab()?.id));
    print("usage: browserctl [status|tabs|backends|backend <auto|wasm-gecko|native-webkit|web-transport>|probe|wisp <wss://.../>|new [url]|back|forward|reload|bookmark [url]|bookmarks|close [tabId]]");
  })().catch(error=>print(`browser error: ${error.message}`));
},true);

window.RiftBrowserUI=Object.freeze({open:openBrowser,close:closeWebBrowser});

function labelBrowserLauncher(){
  document.querySelectorAll('[data-open="browser"] small').forEach(node=>{node.textContent="Experimental engine browser · Gecko WASM";});
}
window.addEventListener("riftos:launcher-ready",labelBrowserLauncher);
window.addEventListener("riftos:trueos-ready",labelBrowserLauncher);
queueMicrotask(labelBrowserLauncher);
