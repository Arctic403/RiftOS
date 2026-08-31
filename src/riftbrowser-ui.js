const core=window.RiftOSCore;
const browser=core?.kernel?.browser;
if(!browser)throw new Error("RiftBrowser UI adapter requires RiftKernel.browser");

const $=selector=>document.querySelector(selector);
const escapeHTML=value=>String(value??"").replace(/[&<>"']/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[ch]));
let browserProcess=null;
let browserWindow=null;
let engineFrame=null;
let engineBuild="";
let engineTarget="";
let frameBridgeTimer=0;

function setStatus(value){const el=$("#statusText");if(el)el.textContent=value;}
function killBrowserProcess(){if(browserProcess){core.kernel.kill(browserProcess.pid);browserProcess=null;}}
function closeWebBrowser(){
  killBrowserProcess();browserWindow=null;engineFrame=null;engineBuild="";engineTarget="";clearInterval(frameBridgeTimer);
  const stage=$("#stage"),workspace=$("#workspace");
  if(stage?.querySelector('[data-app="browser-kernel"]')){stage.innerHTML="";stage.classList.add("hidden");workspace?.classList.remove("hidden");}
  document.querySelectorAll(".dock-btn").forEach(btn=>btn.classList.toggle("active",btn.dataset.open==="home"));
  setStatus("Ready");
}

function createWindow(){
  const stage=$("#stage"),workspace=$("#workspace"),template=$("#windowTemplate");
  if(!stage||!workspace||!template)throw new Error("RiftOS browser window host is unavailable");
  killBrowserProcess();workspace.classList.add("hidden");stage.classList.remove("hidden");stage.innerHTML="";
  const win=template.content.firstElementChild.cloneNode(true);win.dataset.app="browser-kernel";
  win.querySelector(".window-title").textContent="RiftBrowser";
  win.querySelector(".window-kicker").textContent="RIFTWEBKIT WASM · IPHONE";
  win.querySelector(".window-close").onclick=closeWebBrowser;
  stage.append(win);browserProcess=core.kernel.launchProcess("browser","RiftBrowser",{kind:"riftwebkit-browser"});
  document.querySelectorAll(".dock-btn").forEach(btn=>btn.classList.toggle("active",btn.dataset.open==="browser"));
  setStatus("RiftBrowser");browserWindow=win.querySelector(".window-body");return browserWindow;
}

function chromeMarkup(){
  const tab=browser.activeTab(),status=browser.backendStatus(),tabs=browser.listTabs();
  const engine=status.backends[0];
  const readiness=engine?.browsingReady?"WebKit web engine ready":engine?.available?"WebKit proof/runtime ready":"WebKit unavailable";
  return `<div class="kbrowser">
    <div class="kbrowser-tabs">
      <div class="kbrowser-tab-list">${tabs.map(item=>`<button class="kbrowser-tab ${item.id===tab?.id?"active":""}" data-browser-tab="${escapeHTML(item.id)}"><span>${escapeHTML(item.title||"New Tab")}</span><i data-browser-close="${escapeHTML(item.id)}">×</i></button>`).join("")}</div>
      <button class="kbrowser-icon" id="kbNewTab" title="New tab">＋</button>
    </div>
    <form class="kbrowser-bar" id="kbAddressForm">
      <button type="button" class="kbrowser-icon" id="kbBack" ${!tab?.canGoBack?"disabled":""}>‹</button>
      <button type="button" class="kbrowser-icon" id="kbForward" ${!tab?.canGoForward?"disabled":""}>›</button>
      <button type="button" class="kbrowser-icon" id="kbReload">↻</button>
      <input id="kbAddress" value="${escapeHTML(tab?.url||"")}" placeholder="Search or enter a URL" autocomplete="off" autocapitalize="off" spellcheck="false" inputmode="url">
      <button type="submit" class="kbrowser-go">Go</button>
    </form>
    <div class="kbrowser-meta">
      <span id="kbState">${escapeHTML(status.active)}</span>
      <span id="kbReady">${escapeHTML(readiness)}</span>
      <span class="kbrowser-spacer"></span>
      <button type="button" id="kbWisp">Wisp</button>
      <button type="button" id="kbProbe">Status</button>
    </div>
    <div class="kbrowser-surface" id="kbSurface"></div>
  </div>`;
}

function renderChrome({preserveSurface=true}={}){
  const body=browserWindow;if(!body)return;
  const oldSurface=preserveSurface?body.querySelector("#kbSurface"):null;
  const child=oldSurface?.firstElementChild||null;
  body.innerHTML=chromeMarkup();
  const surface=body.querySelector("#kbSurface");
  if(child)surface.append(child);

  body.querySelector("#kbAddressForm").onsubmit=event=>{event.preventDefault();browser.navigate(body.querySelector("#kbAddress").value).catch(showError);};
  body.querySelector("#kbBack").onclick=()=>browser.back().catch(showError);
  body.querySelector("#kbForward").onclick=()=>browser.forward().catch(showError);
  body.querySelector("#kbReload").onclick=()=>browser.reload().catch(showError);
  body.querySelector("#kbNewTab").onclick=()=>browser.newTab().catch(showError);
  body.querySelector("#kbWisp").onclick=()=>configureWisp();
  body.querySelector("#kbProbe").onclick=()=>showStatusPanel();
  body.querySelectorAll("[data-browser-tab]").forEach(button=>button.addEventListener("click",event=>{
    if(event.target.closest("[data-browser-close]"))return;
    const selected=browser.selectTab(button.dataset.browserTab);renderChrome();browser.navigate(selected.url,{tabId:selected.id,replace:true}).catch(showError);
  }));
  body.querySelectorAll("[data-browser-close]").forEach(button=>button.onclick=event=>{
    event.stopPropagation();browser.closeTab(button.dataset.browserClose);if(!browser.activeTab())browser.createTab();renderChrome();browser.reload().catch(showError);
  });
}

function renderStart(){
  const surface=browserWindow?.querySelector("#kbSurface");if(!surface)return;
  const engine=browser.info().engine;
  surface.innerHTML=`<section class="browser-start kbrowser-start"><div class="browser-logo">R</div><h2>RiftWebKit Mobile</h2><p>RiftBrowser now has one engine path: WebCore + JavaScriptCore compiled to WebAssembly for the iPhone-hosted RiftOS runtime.</p><div class="browser-capability"><strong>${engine.available?"RiftWebKit artifact detected":"RiftWebKit artifact unavailable"}</strong><span>${engine.browsingReady?"Networking + guest JavaScript are available in this engine build.":"The proven first-paint artifact is integrated. The full mobile WebKit artifact will replace it automatically when its build is published."}</span></div><div class="browser-block-actions"><button class="action" id="kbProbeEngine">Probe WebKit</button><button class="action" id="kbConfigureWisp">Configure Wisp</button></div></section>`;
  surface.querySelector("#kbProbeEngine").onclick=async()=>{await browser.refreshEngines(true);renderChrome();renderStart();};
  surface.querySelector("#kbConfigureWisp").onclick=()=>configureWisp();
}

function showStatusPanel(){
  const info=browser.info();
  window.alert?.(`RiftWebKit\n\n${JSON.stringify(info.engine,null,2)}`);
}

function configureWisp(){
  const current=browser.backendStatus().wisp||"";
  const next=window.prompt?.("RiftWebKit Wisp endpoint (wss://…/). Leave blank to clear.",current);
  if(next===null||next===undefined)return;
  try{
    browser.setWisp(next);setStatus(next.trim()?"Wisp configured":"Wisp cleared");
    if(browser.activeTab()){
      engineFrame=null;engineBuild="";engineTarget="";
      browser.reload().catch(showError);
    }
  }catch(error){showError(error);}
}

function framePoint(canvas,touch){
  const rect=canvas.getBoundingClientRect();
  return {
    x:Math.max(0,Math.min(canvas.width-1,(touch.clientX-rect.left)*canvas.width/Math.max(1,rect.width))),
    y:Math.max(0,Math.min(canvas.height-1,(touch.clientY-rect.top)*canvas.height/Math.max(1,rect.height)))
  };
}
function moduleFor(frame){try{return frame.contentWindow?.Module||null;}catch{return null;}}
function tapEngine(frame,canvas,touch){
  const Module=moduleFor(frame);if(!Module)return;
  const p=framePoint(canvas,touch);
  try{
    if(typeof Module._bib_mouse_move==="function"&&typeof Module._bib_mouse_button==="function"){
      Module._bib_mouse_move(p.x,p.y,0);Module._bib_mouse_button(1,0,p.x,p.y,1,0);Module._bib_mouse_button(0,0,p.x,p.y,1,0);
    }else if(typeof Module._rift_pointer==="function"){
      Module._rift_pointer(1,p.x,p.y);Module._rift_pointer(2,p.x,p.y);
    }
  }catch(error){console.warn("[RiftBrowser] WebKit tap bridge",error);}
}
function wheelEngine(frame,canvas,touch,dx,dy){
  const Module=moduleFor(frame);if(!Module)return;
  const p=framePoint(canvas,touch);
  try{
    if(typeof Module._bib_wheel==="function")Module._bib_wheel(p.x,p.y,-dx,-dy,0);
    else if(typeof Module._rift_pointer==="function")Module._rift_pointer(0,p.x,p.y);
  }catch(error){console.warn("[RiftBrowser] WebKit scroll bridge",error);}
}

function installMobileBridge(frame){
  let doc;try{doc=frame.contentDocument;}catch{return false;}
  if(!doc)return false;
  const canvas=doc.getElementById("screen");if(!canvas)return false;

  if(!doc.getElementById("riftosWebKitEmbedStyle")){
    const style=doc.createElement("style");style.id="riftosWebKitEmbedStyle";
    style.textContent=`html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#fff!important}body>h1,body>p,#urlrow,#log{display:none!important}#screenwrap{display:block!important;width:100%!important;height:100%!important;line-height:0!important}#screen{display:block!important;width:100%!important;height:100%!important;border:0!important;outline:0!important;touch-action:none!important;-webkit-user-select:none!important;user-select:none!important}#bibfreeze{right:8px!important;bottom:8px!important}`;
    doc.head.append(style);
  }
  if(canvas.dataset.riftTouchBridge==="1")return true;
  canvas.dataset.riftTouchBridge="1";
  canvas.setAttribute("aria-label","RiftWebKit page surface");

  let gesture=null;
  canvas.addEventListener("touchstart",event=>{
    if(event.touches.length!==1)return;
    event.preventDefault();
    const t=event.touches[0];gesture={startX:t.clientX,startY:t.clientY,lastX:t.clientX,lastY:t.clientY,moved:false,lastTouch:t};
  },{passive:false});
  canvas.addEventListener("touchmove",event=>{
    if(!gesture||event.touches.length!==1)return;
    event.preventDefault();
    const t=event.touches[0],dx=t.clientX-gesture.lastX,dy=t.clientY-gesture.lastY;
    if(Math.hypot(t.clientX-gesture.startX,t.clientY-gesture.startY)>7)gesture.moved=true;
    if(gesture.moved)wheelEngine(frame,canvas,t,dx,dy);
    gesture.lastX=t.clientX;gesture.lastY=t.clientY;gesture.lastTouch=t;
  },{passive:false});
  const finish=event=>{
    if(!gesture)return;
    event.preventDefault();
    const touch=event.changedTouches?.[0]||gesture.lastTouch;
    if(!gesture.moved&&touch)tapEngine(frame,canvas,touch);
    gesture=null;
  };
  canvas.addEventListener("touchend",finish,{passive:false});
  canvas.addEventListener("touchcancel",finish,{passive:false});
  return true;
}

function armFrameBridge(frame){
  clearInterval(frameBridgeTimer);
  let tries=0;
  const tick=()=>{
    tries++;
    if(installMobileBridge(frame)){
      const Module=moduleFor(frame);
      if(Module&&(typeof Module._bib_load_url==="function"||typeof Module._rift_pointer==="function")){clearInterval(frameBridgeTimer);frameBridgeTimer=0;}
    }
    if(tries>240){clearInterval(frameBridgeTimer);frameBridgeTimer=0;}
  };
  tick();frameBridgeTimer=setInterval(tick,250);
}

function createEngineFrame(result){
  const surface=browserWindow?.querySelector("#kbSurface");if(!surface)return null;
  surface.innerHTML='<iframe class="kbrowser-engine-frame" title="RiftWebKit mobile engine" allow="clipboard-read; clipboard-write; autoplay; fullscreen"></iframe>';
  engineFrame=surface.querySelector("iframe");engineBuild=String(result.engine?.manifest?.build||result.engine?.manifest?.sourceCommit||"proof");engineTarget=result.url;
  engineFrame.addEventListener("load",()=>armFrameBridge(engineFrame));
  engineFrame.src=result.src;
  return engineFrame;
}

function navigateEngine(result){
  const build=String(result.engine?.manifest?.build||result.engine?.manifest?.sourceCommit||"proof");
  if(!engineFrame||!engineFrame.isConnected||engineBuild!==build)return createEngineFrame(result);
  const Module=moduleFor(engineFrame);
  if(result.browsingReady&&Module&&typeof Module.ccall==="function"&&typeof Module._bib_load_url==="function"){
    try{Module.ccall("bib_load_url",null,["string"],[result.url]);engineTarget=result.url;armFrameBridge(engineFrame);return engineFrame;}
    catch(error){console.warn("[RiftBrowser] in-place WebKit navigation failed; restarting surface",error);}
  }
  if(engineTarget!==result.url){engineTarget=result.url;engineFrame.src=result.src;}
  return engineFrame;
}

function showError(error){
  const surface=browserWindow?.querySelector("#kbSurface");
  if(surface)surface.innerHTML=`<section class="browser-blocked"><div class="browser-warning">!</div><h2>RiftWebKit error</h2><p>${escapeHTML(error?.message||error)}</p></section>`;
}
function renderResult(detail){
  if(!browserWindow)return;
  renderChrome();
  const surface=browserWindow.querySelector("#kbSurface"),state=browserWindow.querySelector("#kbState"),ready=browserWindow.querySelector("#kbReady"),result=detail?.result||{};
  if(state)state.textContent=result.backend||"unknown";
  if(ready&&result.engine)ready.textContent=result.browsingReady?"WebKit web engine ready":"WebKit proof/runtime ready";
  if(result.mode==="riftwebkit")navigateEngine(result);
  else if(result.mode==="unavailable"){
    engineFrame=null;engineBuild="";engineTarget="";
    surface.innerHTML=`<section class="browser-blocked"><div class="browser-warning">!</div><h2>RiftWebKit is unavailable</h2><p>${escapeHTML(result.reason||"The WebKit engine package is missing.")}</p><div class="browser-block-actions"><button class="action" id="kbRetryEngine">Retry WebKit</button></div></section>`;
    surface.querySelector("#kbRetryEngine").onclick=async()=>{await browser.refreshEngines(true);await browser.reload();};
  }
}

async function openBrowser(initial="https://chatgpt.com",{newTab=false}={}){
  createWindow();if(!browser.activeTab())browser.createTab(initial);renderChrome({preserveSurface:false});renderStart();
  await browser.refreshEngines();return browser.open(initial,{newTab});
}

browser.addEventListener("loading",event=>{
  const state=browserWindow?.querySelector("#kbState");if(state)state.textContent=`${event.detail.backend} · loading`;
});
browser.addEventListener("render",event=>renderResult(event.detail));
browser.addEventListener("backend",()=>{if(browserWindow)renderChrome();});

document.addEventListener("click",event=>{
  const target=event.target.closest?.("[data-open]");if(!target)return;
  if(target.dataset.open==="browser"){event.preventDefault();event.stopImmediatePropagation();openBrowser().catch(showError);return;}
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
    if(cmd==="browser"){await openBrowser(parts.join(" ")||"https://chatgpt.com");print("[browser] riftwebkit");return;}
    const sub=(parts.shift()||"status").toLowerCase();
    if(sub==="status")return print(JSON.stringify(browser.info(),null,2));
    if(sub==="tabs")return print(browser.listTabs().map(tab=>`${tab.id===browser.activeTab()?.id?"*":" "} ${tab.id} ${tab.url}`).join("\n")||"(no tabs)");
    if(sub==="probe")return print(JSON.stringify(await browser.refreshEngines(true),null,2));
    if(sub==="wisp")return print(`wisp=${browser.setWisp(parts.join(" "))||"(unset)"}`);
    if(sub==="new")return openBrowser(parts.join(" ")||"https://chatgpt.com",{newTab:true});
    if(sub==="back")return browser.back();
    if(sub==="forward")return browser.forward();
    if(sub==="reload")return browser.reload();
    if(sub==="bookmark")return print(JSON.stringify(browser.bookmark(parts.join(" ")||undefined),null,2));
    if(sub==="bookmarks")return print(JSON.stringify(browser.bookmarks(),null,2));
    if(sub==="close")return print(browser.closeTab(parts[0]||browser.activeTab()?.id));
    print("usage: browserctl [status|tabs|probe|wisp <wss://.../>|new [url]|back|forward|reload|bookmark [url]|bookmarks|close [tabId]]");
  })().catch(error=>print(`browser error: ${error.message}`));
},true);

window.RiftBrowserUI=Object.freeze({open:openBrowser,close:closeWebBrowser});
function labelBrowserLauncher(){document.querySelectorAll('[data-open="browser"] small').forEach(node=>{node.textContent="RiftWebKit mobile browser";});}
window.addEventListener("riftos:launcher-ready",labelBrowserLauncher);
window.addEventListener("riftos:trueos-ready",labelBrowserLauncher);
queueMicrotask(labelBrowserLauncher);
