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
  win.querySelector(".window-kicker").textContent="RIFTKERNEL BROWSER SERVICE";
  win.querySelector(".window-close").onclick=closeWebBrowser;
  stage.append(win);browserProcess=core.kernel.launchProcess("browser","RiftBrowser",{kind:"kernel-browser"});
  document.querySelectorAll(".dock-btn").forEach(btn=>btn.classList.toggle("active",btn.dataset.open==="browser"));
  setStatus("RiftBrowser");browserWindow=win.querySelector(".window-body");return browserWindow;
}

function renderChrome(){
  const body=browserWindow;if(!body)return;
  const tab=browser.activeTab(),status=browser.backendStatus(),tabs=browser.listTabs();
  body.innerHTML=`<div class="kbrowser">
    <div class="kbrowser-tabs">
      <div class="kbrowser-tab-list">${tabs.map(item=>`<button class="kbrowser-tab ${item.id===tab?.id?"active":""}" data-browser-tab="${escapeHTML(item.id)}"><span>${escapeHTML(item.title||"New Tab")}</span><i data-browser-close="${escapeHTML(item.id)}">×</i></button>`).join("")}</div>
      <button class="kbrowser-icon" id="kbNewTab" title="New tab">＋</button>
    </div>
    <form class="kbrowser-bar" id="kbAddressForm">
      <button type="button" class="kbrowser-icon" id="kbBack" ${!tab||(!tab.canGoBack&&tab.historyIndex<=0)?"disabled":""}>‹</button>
      <button type="button" class="kbrowser-icon" id="kbForward" ${!tab||(!tab.canGoForward&&tab.historyIndex>=tab.history.length-1)?"disabled":""}>›</button>
      <button type="button" class="kbrowser-icon" id="kbReload">↻</button>
      <input id="kbAddress" value="${escapeHTML(tab?.url||"")}" placeholder="Search or enter a URL" autocomplete="off" autocapitalize="off" spellcheck="false">
      <button type="submit" class="kbrowser-go">Go</button>
    </form>
    <div class="kbrowser-meta"><span id="kbState">${escapeHTML(status.active)}</span><span class="kbrowser-spacer"></span><button type="button" id="kbBookmark">☆ Bookmark</button><button type="button" id="kbExternal">Open externally ↗</button></div>
    <div class="kbrowser-surface" id="kbSurface"></div>
  </div>`;
  body.querySelector("#kbAddressForm").onsubmit=event=>{event.preventDefault();browser.navigate(body.querySelector("#kbAddress").value).catch(showError);};
  body.querySelector("#kbBack").onclick=()=>browser.back().catch(showError);
  body.querySelector("#kbForward").onclick=()=>browser.forward().catch(showError);
  body.querySelector("#kbReload").onclick=()=>browser.reload().catch(showError);
  body.querySelector("#kbNewTab").onclick=()=>browser.newTab().catch(showError);
  body.querySelector("#kbBookmark").onclick=()=>{browser.bookmark();setStatus("Bookmarked");};
  body.querySelector("#kbExternal").onclick=()=>externalOpen(browser.activeTab()?.url);
  body.querySelectorAll("[data-browser-tab]").forEach(button=>button.addEventListener("click",event=>{
    if(event.target.closest("[data-browser-close]"))return;
    const selected=browser.selectTab(button.dataset.browserTab);renderChrome();
    if(browser.backendStatus().active!=="native-webkit")browser.navigate(selected.url,{tabId:selected.id,replace:true}).catch(showError);
  }));
  body.querySelectorAll("[data-browser-close]").forEach(button=>button.onclick=event=>{
    event.stopPropagation();browser.closeTab(button.dataset.browserClose);if(!browser.activeTab())browser.createTab();renderChrome();
  });
  renderStart();
}
function renderStart(){
  const surface=browserWindow?.querySelector("#kbSurface");if(!surface)return;
  surface.innerHTML=`<section class="browser-start kbrowser-start"><div class="browser-logo">R</div><h2>RiftBrowser is native-first.</h2><p>The installed RiftOS app boots its own bundled RiftKernel shell and controls the Apple WebKit browser through the kernel bridge. GitHub Pages is only a development preview.</p><div class="browser-capability"><strong>Production renderer: WKWebView</strong><span>Desktop mode is the default. Tabs, session state, popups, downloads, share and find-on-page are implemented by RiftOS around Apple WebKit.</span></div></section>`;
}
function showError(error){
  const surface=browserWindow?.querySelector("#kbSurface");if(surface)surface.innerHTML=`<section class="browser-blocked"><div class="browser-warning">!</div><h2>Browser service error</h2><p>${escapeHTML(error?.message||error)}</p></section>`;
}
function renderResult(detail){
  if(!browserWindow)return;
  renderChrome();
  const surface=browserWindow?.querySelector("#kbSurface"),state=browserWindow?.querySelector("#kbState"),result=detail?.result||{};
  if(!surface)return;
  if(state)state.textContent=result.backend||"web-transport";
  if(result.mode==="document"){
    surface.innerHTML='<iframe class="webview direct-view kbrowser-frame" sandbox="allow-forms allow-popups" referrerpolicy="no-referrer"></iframe>';
    surface.querySelector("iframe").srcdoc=result.html||"";
  }else if(result.mode==="external"){
    surface.innerHTML=`<section class="browser-blocked"><div class="browser-warning">↗</div><h2>Native RiftBrowser required</h2><p>The web development preview cannot embed this destination because of normal browser security restrictions.</p><code>${escapeHTML(result.url||"")}</code><small>${escapeHTML(result.reason||"")}</small><div class="browser-block-actions"><button class="action" id="kbOpenExternal">Open externally</button></div></section>`;
    surface.querySelector("#kbOpenExternal").onclick=()=>externalOpen(result.url);
  }
}

async function openBrowser(initial="https://chatgpt.com",{newTab=false}={}){
  const status=browser.backendStatus();
  if(status.active==="native-webkit")return browser.open(initial,{newTab});
  createWindow();if(!browser.activeTab())browser.createTab(initial);
  renderChrome();return browser.open(initial,{newTab});
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
    if(cmd==="browser"){
      await openBrowser(parts.join(" ")||"https://chatgpt.com");print(`[browser] ${browser.backendStatus().active}`);return;
    }
    const sub=(parts.shift()||"status").toLowerCase();
    if(sub==="status")return print(JSON.stringify(browser.info(),null,2));
    if(sub==="tabs")return print(browser.listTabs().map(tab=>`${tab.id===browser.activeTab()?.id?"*":" "} ${tab.desktop?"D":"M"} ${tab.id} ${tab.url}`).join("\n")||"(no tabs)");
    if(sub==="backends")return print(browser.backendStatus().backends.map(item=>`${item.available?"+":"-"} ${item.id} ${item.name}`).join("\n"));
    if(sub==="new")return openBrowser(parts.join(" ")||"https://chatgpt.com",{newTab:true});
    if(sub==="back")return browser.back();
    if(sub==="forward")return browser.forward();
    if(sub==="reload")return browser.reload();
    if(sub==="stop")return browser.stop();
    if(sub==="desktop")return print(`desktop=${browser.setDesktopMode(!["off","false","0","mobile"].includes((parts[0]||"on").toLowerCase()))}`);
    if(sub==="share")return print(browser.share()?"share sheet opened":"native browser required");
    if(sub==="find")return print(browser.find()?"find-on-page opened":"native browser required");
    if(sub==="bookmark")return print(JSON.stringify(browser.bookmark(parts.join(" ")||undefined),null,2));
    if(sub==="bookmarks")return print(JSON.stringify(browser.bookmarks(),null,2));
    if(sub==="close")return print(browser.closeTab(parts[0]||browser.activeTab()?.id));
    print("usage: browserctl [status|tabs|backends|new [url]|back|forward|reload|stop|desktop <on|off>|share|find|bookmark [url]|bookmarks|close [tabId]]");
  })().catch(error=>print(`browser error: ${error.message}`));
},true);

window.RiftBrowserUI=Object.freeze({open:openBrowser,close:closeWebBrowser});

function labelBrowserLauncher(){
  document.querySelectorAll('[data-open="browser"] small').forEach(node=>{node.textContent=core.native.connected?"Native Apple WebKit · desktop first":"Kernel browser · web dev preview";});
}
window.addEventListener("riftos:launcher-ready",labelBrowserLauncher);
window.addEventListener("riftos:trueos-ready",labelBrowserLauncher);
queueMicrotask(labelBrowserLauncher);
