const core=window.RiftOSCore;
const browser=core?.kernel?.browser;
if(!browser)throw new Error("RiftBrowser UI adapter requires RiftKernel.browser");

const $=selector=>document.querySelector(selector);
const escapeHTML=value=>String(value??"").replace(/[&<>"']/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[ch]));
const FIREWALL_HOST_SCRIPT=new URL("./riftbrowser-firewall-host.js",import.meta.url).href;
const WARM_ENGINE_MS=60_000;
const PREWARM_DELAY_MS=2_000;
const PERF_POLL_MS=100;
const PERF_TIMEOUT_MS=45_000;

let browserProcess=null;
let browserWindow=null;
let engineFrame=null;
let engineBuild="";
let engineTarget="";
let frameBridgeTimer=0;
let perfPollTimer=0;
let warmReleaseTimer=0;
let prewarmTimer=0;
let prewarmStarted=false;
let prewarmReady=false;
let firewallTransportReady=false;
let browserVisible=false;

const perfState={seq:0,current:null,recent:[],lastWarmResume:null,lastPrewarm:null};
function perfNow(){return performance.now();}
function beginPerf(target,{warm=false,reason="navigate"}={}){
  const metric={id:++perfState.seq,target:String(target||""),reason,warm,startedAt:Date.now(),t0:perfNow(),marks:{request:0},bytes0:0,frames0:null};
  perfState.current=metric;perfState.recent.unshift(metric);perfState.recent=perfState.recent.slice(0,12);
  return metric;
}
function perfMark(name,extra={}){
  const metric=perfState.current;if(!metric||metric.marks[name]!==undefined)return;
  metric.marks[name]=Math.round((perfNow()-metric.t0)*10)/10;
  Object.assign(metric,extra);
  console.info(`[RiftBrowser perf] ${name} +${metric.marks[name]}ms`,metric.target||"");
}
function frameCounter(win){
  try{
    const value=Number(win?.__bib?.frames??win?.bib?.frames);
    return Number.isFinite(value)?value:null;
  }catch{return null;}
}
function perfSnapshot(){
  const clean=metric=>metric?{id:metric.id,target:metric.target,reason:metric.reason,warm:metric.warm,startedAt:metric.startedAt,marks:{...metric.marks}}:null;
  return {warmEngineMs:WARM_ENGINE_MS,prewarmDelayMs:PREWARM_DELAY_MS,warmAlive:!!(engineFrame?.isConnected&&!browserVisible),prewarmStarted,prewarmReady,lastPrewarm:perfState.lastPrewarm,lastWarmResume:perfState.lastWarmResume,current:clean(perfState.current),recent:perfState.recent.slice(0,8).map(clean)};
}
function stopPerfPoll(){clearInterval(perfPollTimer);perfPollTimer=0;}
function startPerfPoll(frame,metric=perfState.current){
  stopPerfPoll();if(!frame||!metric)return;
  let win;try{win=frame.contentWindow;}catch{return;}
  try{metric.bytes0=Number(win?.__bibWispBytes||0);metric.frames0=frameCounter(win);}catch{}
  const deadline=perfNow()+PERF_TIMEOUT_MS;
  perfPollTimer=setInterval(()=>{
    if(!engineFrame||frame!==engineFrame||!frame.isConnected||perfState.current!==metric||perfNow()>deadline){stopPerfPoll();return;}
    try{
      const w=frame.contentWindow;
      const bytes=Number(w?.__bibWispBytes||0);
      if(bytes>metric.bytes0)perfMark("firstNetworkByte");
      const frames=frameCounter(w);
      if(metric.frames0!==null&&frames!==null&&frames>metric.frames0)perfMark("firstNewFrame");
      if(metric.marks.firstNetworkByte!==undefined&&metric.marks.firstNewFrame!==undefined)stopPerfPoll();
    }catch{}
  },PERF_POLL_MS);
}

function setStatus(value){const el=$("#statusText");if(el)el.textContent=value;}
function killBrowserProcess(){if(browserProcess){core.kernel.kill(browserProcess.pid);browserProcess=null;}}
function flushEngineState(){try{const Module=moduleFor(engineFrame);if(Module?._bib_persist_now)Module._bib_persist_now();}catch{}}
function discardEngineFrame(){
  clearInterval(frameBridgeTimer);frameBridgeTimer=0;stopPerfPoll();firewallTransportReady=false;prewarmReady=false;
  try{engineFrame?.remove();}catch{}
  engineFrame=null;engineBuild="";engineTarget="";
}
function hardReleaseBrowserHost(){
  clearTimeout(warmReleaseTimer);warmReleaseTimer=0;discardEngineFrame();
  const stage=$("#stage");
  if(browserWindow?.isConnected&&stage?.contains(browserWindow))stage.innerHTML="";
  browserWindow=null;browserVisible=false;
}
function scheduleWarmRelease(){clearTimeout(warmReleaseTimer);warmReleaseTimer=setTimeout(()=>{if(!browserVisible)hardReleaseBrowserHost();},WARM_ENGINE_MS);}
function closeWebBrowser({hard=false}={}){
  killBrowserProcess();flushEngineState();browserVisible=false;
  clearInterval(frameBridgeTimer);frameBridgeTimer=0;stopPerfPoll();
  const stage=$("#stage"),workspace=$("#workspace");
  if(stage?.querySelector('[data-app="browser-kernel"]')){stage.classList.add("hidden");workspace?.classList.remove("hidden");}
  document.querySelectorAll(".dock-btn").forEach(btn=>btn.classList.toggle("active",btn.dataset.open==="home"));
  setStatus("Ready");
  if(hard||!engineFrame?.isConnected)hardReleaseBrowserHost();else scheduleWarmRelease();
}

function createWindow(){
  const stage=$("#stage"),workspace=$("#workspace"),template=$("#windowTemplate");
  if(!stage||!workspace||!template)throw new Error("RiftOS browser window host is unavailable");
  clearTimeout(warmReleaseTimer);warmReleaseTimer=0;
  if(browserWindow&&!browserWindow.isConnected){browserWindow=null;engineFrame=null;engineBuild="";engineTarget="";firewallTransportReady=false;prewarmReady=false;}
  const existing=stage.querySelector('[data-app="browser-kernel"]');
  const reused=!!(browserWindow?.isConnected&&existing&&existing.contains(browserWindow));
  killBrowserProcess();workspace.classList.add("hidden");stage.classList.remove("hidden");browserVisible=true;
  if(!reused){
    stage.innerHTML="";
    const win=template.content.firstElementChild.cloneNode(true);win.dataset.app="browser-kernel";
    win.querySelector(".window-title").textContent="RiftBrowser";
    win.querySelector(".window-kicker").textContent="RIFTWEBKIT WASM · IPHONE";
    win.querySelector(".window-close").onclick=()=>closeWebBrowser();
    stage.append(win);browserWindow=win.querySelector(".window-body");
  }else{
    perfState.lastWarmResume=Date.now();beginPerf(browser.activeTab()?.url||engineTarget,{warm:true,reason:prewarmReady?"prewarm-resume":"warm-resume"});perfMark(prewarmReady?"prewarmResume":"warmResume");
  }
  browserProcess=core.kernel.launchProcess("browser","RiftBrowser",{kind:"riftwebkit-browser",warm:reused,prewarmed:prewarmReady});
  document.querySelectorAll(".dock-btn").forEach(btn=>btn.classList.toggle("active",btn.dataset.open==="browser"));
  setStatus(reused?"RiftBrowser · warm":"RiftBrowser");
  return {body:browserWindow,reused};
}

function chromeMarkup(){
  const tab=browser.activeTab(),status=browser.backendStatus(),tabs=browser.listTabs(),fw=browser.firewallSummary();
  const engine=status.backends[0];
  const readiness=engine?.browsingReady?"WebKit web engine ready":engine?.available?"WebKit proof/runtime ready":"WebKit unavailable";
  const fwLabel=!fw.enabled?"Firewall off":!fw.networkEnabled?"Network off":"Firewall";
  return `<div class="kbrowser"><div class="kbrowser-tabs"><div class="kbrowser-tab-list">${tabs.map(item=>`<button class="kbrowser-tab ${item.id===tab?.id?"active":""}" data-browser-tab="${escapeHTML(item.id)}"><span>${escapeHTML(item.title||"New Tab")}</span><i data-browser-close="${escapeHTML(item.id)}">×</i></button>`).join("")}</div><button class="kbrowser-icon" id="kbNewTab" title="New tab">＋</button></div><form class="kbrowser-bar" id="kbAddressForm"><button type="button" class="kbrowser-icon" id="kbBack" ${!tab?.canGoBack?"disabled":""}>‹</button><button type="button" class="kbrowser-icon" id="kbForward" ${!tab?.canGoForward?"disabled":""}>›</button><button type="button" class="kbrowser-icon" id="kbReload">↻</button><input id="kbAddress" value="${escapeHTML(tab?.url||"")}" placeholder="Search or enter a URL" autocomplete="off" autocapitalize="off" spellcheck="false" inputmode="url"><button type="submit" class="kbrowser-go">Go</button></form><div class="kbrowser-meta"><span id="kbState">${escapeHTML(status.active)}</span><span id="kbReady">${escapeHTML(readiness)}</span><span class="kbrowser-spacer"></span><button type="button" id="kbFirewall" class="${fw.enabled&&fw.networkEnabled?"fw-on":"fw-off"}">${escapeHTML(fwLabel)}</button><button type="button" id="kbWisp">Wisp</button><button type="button" id="kbProbe">Status</button></div><div class="kbrowser-surface" id="kbSurface"></div></div>`;
}
function bindChrome(root=browserWindow){
  if(!root)return;
  root.querySelector("#kbAddressForm")?.addEventListener("submit",event=>{event.preventDefault();browser.navigate(root.querySelector("#kbAddress").value).catch(showError);});
  const bind=(sel,fn)=>{const el=root.querySelector(sel);if(el)el.onclick=fn;};
  bind("#kbBack",()=>browser.back().catch(showError));bind("#kbForward",()=>browser.forward().catch(showError));bind("#kbReload",()=>browser.reload().catch(showError));bind("#kbNewTab",()=>browser.newTab().catch(showError));bind("#kbFirewall",showFirewallPanel);bind("#kbWisp",configureWisp);bind("#kbProbe",showStatusPanel);
  root.querySelectorAll("[data-browser-tab]").forEach(button=>button.onclick=event=>{if(event.target.closest("[data-browser-close]"))return;const selected=browser.selectTab(button.dataset.browserTab);syncChrome();browser.navigate(selected.url,{tabId:selected.id,replace:true}).catch(showError);});
  root.querySelectorAll("[data-browser-close]").forEach(button=>button.onclick=event=>{event.stopPropagation();browser.closeTab(button.dataset.browserClose);if(!browser.activeTab())browser.createTab();syncChrome();browser.reload().catch(showError);});
}
function syncChrome({reset=false}={}){
  const body=browserWindow;if(!body)return;
  const current=body.querySelector(".kbrowser");
  if(reset||!current){body.innerHTML=chromeMarkup();bindChrome(body);return;}
  const tpl=document.createElement("template");tpl.innerHTML=chromeMarkup().trim();const fresh=tpl.content.firstElementChild;
  for(const selector of [".kbrowser-tabs",".kbrowser-bar",".kbrowser-meta"]){const oldNode=current.querySelector(selector),newNode=fresh.querySelector(selector);if(oldNode&&newNode)oldNode.replaceWith(newNode);}
  bindChrome(body);
}

function renderStart(){
  const surface=browserWindow?.querySelector("#kbSurface");if(!surface)return;
  const engine=browser.info().engine;
  surface.innerHTML=`<section class="browser-start kbrowser-start"><div class="browser-logo">R</div><h2>RiftWebKit Mobile</h2><p>WebCore + JavaScriptCore running inside RiftOS.</p><div class="browser-capability"><strong>${engine.available?"RiftWebKit artifact detected":"RiftWebKit artifact unavailable"}</strong><span>${engine.browsingReady?"Networking + guest JavaScript are ready.":"Waiting for the full mobile engine."}</span></div><div class="browser-block-actions"><button class="action" id="kbProbeEngine">Probe WebKit</button><button class="action" id="kbConfigureWisp">Configure Wisp</button><button class="action" id="kbStartFirewall">RiftFirewall</button></div></section>`;
  surface.querySelector("#kbProbeEngine").onclick=async()=>{await browser.refreshEngines(true);syncChrome();renderStart();};surface.querySelector("#kbConfigureWisp").onclick=configureWisp;surface.querySelector("#kbStartFirewall").onclick=showFirewallPanel;
}
function showStatusPanel(){const info=browser.info();window.alert?.(`RiftWebKit + RiftFirewall\n\n${JSON.stringify({engine:info.engine,firewall:info.firewall,wispMode:info.engine?.wispMode,transportFirewall:firewallTransportReady,performance:perfSnapshot()},null,2)}`);}

function firewallPanelMarkup(){
  const settings=browser.firewallSettings(),summary=browser.firewallSummary(),checked=value=>value?"checked":"";
  const recent=(summary.recentBlocks||[]).slice(0,6).map(row=>`<li><b>${escapeHTML(row.host||row.url||"request")}${row.port?`:${row.port}`:""}</b><span>${escapeHTML(row.reason)}</span></li>`).join("")||'<li class="empty">No blocks recorded this session.</li>';
  return `<section class="kb-firewall-panel" id="kbFirewallPanel" role="dialog" aria-modal="true" aria-label="RiftFirewall rules"><div class="kb-firewall-card"><header><div><small>RIFTKERNEL NETWORK POLICY</small><h2>RiftFirewall</h2></div><button type="button" id="kbFwClose" aria-label="Close firewall">×</button></header><p class="kb-fw-intro">Rules are enforced before navigation and at the WebKit SOCKFS → Wisp transport.</p><div class="kb-fw-toggles"><label><span><b>Firewall enabled</b><small>Master policy switch</small></span><input id="kbFwEnabled" type="checkbox" ${checked(settings.enabled)}></label><label class="danger"><span><b>Network access</b><small>Immediate browser kill switch</small></span><input id="kbFwNetwork" type="checkbox" ${checked(settings.networkEnabled)}></label><label><span><b>HTTPS only</b><small>Blocks HTTP and port 80</small></span><input id="kbFwHttps" type="checkbox" ${checked(settings.httpsOnly)}></label><label><span><b>Block local/private hosts</b><small>localhost, LAN and private IPs</small></span><input id="kbFwPrivate" type="checkbox" ${checked(settings.blockPrivateHosts)}></label><label><span><b>Block sensitive ports</b><small>SSH, DB, SMB, RDP and similar</small></span><input id="kbFwPorts" type="checkbox" ${checked(settings.blockSensitivePorts)}></label><label><span><b>Block trackers</b><small>WebKit resource blocklist</small></span><input id="kbFwTrackers" type="checkbox" ${checked(settings.blockTrackers)}></label></div><label class="kb-fw-rules"><b>Blocked hosts</b><small>One domain per line; subdomains match too.</small><textarea id="kbFwBlockHosts" spellcheck="false">${escapeHTML((settings.blockHosts||[]).join("\n"))}</textarea></label><label class="kb-fw-rules"><b>Allowed host exceptions</b><small>Overrides custom host blocks only.</small><textarea id="kbFwAllowHosts" spellcheck="false">${escapeHTML((settings.allowHosts||[]).join("\n"))}</textarea></label><div class="kb-fw-recent"><b>Recent blocks</b><ul>${recent}</ul></div><div class="kb-fw-actions"><button type="button" id="kbFwClearData">Clear site data</button><button type="button" id="kbFwReset">Reset</button><button type="button" id="kbFwApply" class="primary">Apply + reload</button></div></div></section>`;
}
function closeFirewallPanel(){browserWindow?.querySelector("#kbFirewallPanel")?.remove();}
function showFirewallPanel(){
  const root=browserWindow?.querySelector(".kbrowser");if(!root)return;closeFirewallPanel();root.insertAdjacentHTML("beforeend",firewallPanelMarkup());const panel=root.querySelector("#kbFirewallPanel");
  panel.querySelector("#kbFwClose").onclick=closeFirewallPanel;panel.addEventListener("click",event=>{if(event.target===panel)closeFirewallPanel();});
  panel.querySelector("#kbFwApply").onclick=async()=>{browser.setFirewall({enabled:panel.querySelector("#kbFwEnabled").checked,networkEnabled:panel.querySelector("#kbFwNetwork").checked,httpsOnly:panel.querySelector("#kbFwHttps").checked,blockPrivateHosts:panel.querySelector("#kbFwPrivate").checked,blockSensitivePorts:panel.querySelector("#kbFwPorts").checked,blockTrackers:panel.querySelector("#kbFwTrackers").checked,blockHosts:panel.querySelector("#kbFwBlockHosts").value.split(/[\n,]+/),allowHosts:panel.querySelector("#kbFwAllowHosts").value.split(/[\n,]+/)});closeFirewallPanel();discardEngineFrame();syncChrome({reset:true});if(browser.activeTab())await browser.reload();};
  panel.querySelector("#kbFwReset").onclick=async()=>{browser.resetFirewall();closeFirewallPanel();discardEngineFrame();syncChrome({reset:true});if(browser.activeTab())await browser.reload();};
  panel.querySelector("#kbFwClearData").onclick=async()=>{if(!window.confirm?.("Clear RiftWebKit cookies and site storage from this browser profile?"))return;discardEngineFrame();await browser.clearBrowserSiteData();closeFirewallPanel();syncChrome({reset:true});if(browser.activeTab())await browser.reload();};
}
function configureWisp(){const current=browser.backendStatus().wisp||"",next=window.prompt?.("RiftWebKit Wisp endpoint (wss://…/). Leave blank to use the temporary demo transport.",current);if(next==null)return;try{browser.setWisp(next);setStatus(next.trim()?"Wisp configured":"Demo Wisp selected");if(browser.activeTab()){discardEngineFrame();browser.reload().catch(showError);}}catch(error){showError(error);}}

function framePoint(canvas,touch){const rect=canvas.getBoundingClientRect();return{x:Math.max(0,Math.min(canvas.width-1,(touch.clientX-rect.left)*canvas.width/Math.max(1,rect.width))),y:Math.max(0,Math.min(canvas.height-1,(touch.clientY-rect.top)*canvas.height/Math.max(1,rect.height)))};}
function moduleFor(frame){try{return frame?.contentWindow?.Module||null;}catch{return null;}}
function tapEngine(frame,canvas,touch){const Module=moduleFor(frame);if(!Module)return;const p=framePoint(canvas,touch);try{if(typeof Module._bib_mouse_move==="function"&&typeof Module._bib_mouse_button==="function"){Module._bib_mouse_move(p.x,p.y,0);Module._bib_mouse_button(1,0,p.x,p.y,1,0);Module._bib_mouse_button(0,0,p.x,p.y,1,0);}else if(typeof Module._rift_pointer==="function"){Module._rift_pointer(1,p.x,p.y);Module._rift_pointer(2,p.x,p.y);}}catch(error){console.warn("[RiftBrowser] WebKit tap bridge",error);}}
function wheelEngine(frame,canvas,touch,dx,dy){const Module=moduleFor(frame);if(!Module)return;const p=framePoint(canvas,touch);try{if(typeof Module._bib_wheel==="function")Module._bib_wheel(p.x,p.y,-dx,-dy,0);else if(typeof Module._rift_pointer==="function")Module._rift_pointer(0,p.x,p.y);}catch(error){console.warn("[RiftBrowser] WebKit scroll bridge",error);}}
function installMobileBridge(frame){
  let doc;try{doc=frame.contentDocument;}catch{return false;}if(!doc)return false;const canvas=doc.getElementById("screen");if(!canvas)return false;
  if(!doc.getElementById("riftosWebKitEmbedStyle")){const style=doc.createElement("style");style.id="riftosWebKitEmbedStyle";style.textContent=`html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#fff!important}body>h1,body>p,#urlrow,#log{display:none!important}#screenwrap{display:block!important;width:100%!important;height:100%!important;line-height:0!important}#screen{display:block!important;width:100%!important;height:100%!important;border:0!important;outline:0!important;touch-action:none!important;-webkit-user-select:none!important;user-select:none!important}#bibfreeze{right:8px!important;bottom:8px!important}`;doc.head.append(style);}
  if(canvas.dataset.riftTouchBridge==="1")return true;canvas.dataset.riftTouchBridge="1";canvas.setAttribute("aria-label","RiftWebKit page surface");let gesture=null;
  canvas.addEventListener("touchstart",event=>{if(event.touches.length!==1)return;event.preventDefault();const t=event.touches[0];gesture={startX:t.clientX,startY:t.clientY,lastX:t.clientX,lastY:t.clientY,moved:false,lastTouch:t};},{passive:false});
  canvas.addEventListener("touchmove",event=>{if(!gesture||event.touches.length!==1)return;event.preventDefault();const t=event.touches[0],dx=t.clientX-gesture.lastX,dy=t.clientY-gesture.lastY;if(Math.hypot(t.clientX-gesture.startX,t.clientY-gesture.startY)>7)gesture.moved=true;if(gesture.moved)wheelEngine(frame,canvas,t,dx,dy);gesture.lastX=t.clientX;gesture.lastY=t.clientY;gesture.lastTouch=t;},{passive:false});
  const finish=event=>{if(!gesture)return;event.preventDefault();const touch=event.changedTouches?.[0]||gesture.lastTouch;if(!gesture.moved&&touch)tapEngine(frame,canvas,touch);gesture=null;};canvas.addEventListener("touchend",finish,{passive:false});canvas.addEventListener("touchcancel",finish,{passive:false});return true;
}
function installTransportFirewall(frame){let doc,win;try{doc=frame.contentDocument;win=frame.contentWindow;}catch{return false;}if(!doc||!win)return false;if(win.__riftFirewallTransport?.active){if(!firewallTransportReady){firewallTransportReady=true;perfMark("firewallReady");}return true;}if(!doc.getElementById("riftFirewallHostBridge")){const script=doc.createElement("script");script.id="riftFirewallHostBridge";script.src=FIREWALL_HOST_SCRIPT;script.async=false;script.addEventListener("error",()=>console.error("[RiftBrowser] RiftFirewall host bridge failed to load"));doc.head.append(script);}return false;}
function dispatchProtectedNavigation(frame,target){const Module=moduleFor(frame);if(!target||!Module||typeof Module.ccall!=="function"||typeof Module._bib_load_url!=="function")return false;try{perfMark("navigationDispatch");startPerfPoll(frame);Module.ccall("bib_load_url",null,["string"],[target]);frame.dataset.riftLoadedTarget=target;engineTarget=target;prewarmReady=false;return true;}catch(error){console.warn("[RiftBrowser] protected WebKit navigation failed",error);return false;}}
function armFrameBridge(frame,target=""){
  clearInterval(frameBridgeTimer);let tries=0;
  const tick=()=>{
    tries++;let win=null;try{win=frame.contentWindow;}catch{}
    if(win?.__bib)perfMark("hostRuntimeReady");
    const Module=moduleFor(frame);if(Module)perfMark("moduleObjectReady");
    const touchReady=installMobileBridge(frame),firewallReady=installTransportFirewall(frame);
    if(win?.__bib?.ready===true)perfMark("webkitRuntimeReady");
    if(Module&&typeof Module._bib_load_url==="function")perfMark("engineApiReady");
    if(firewallReady&&Module&&typeof Module.ccall==="function"&&typeof Module._bib_load_url==="function"){
      if(target&&frame.dataset.riftLoadedTarget!==target)dispatchProtectedNavigation(frame,target);
      if(!target&&!browserVisible){prewarmReady=true;perfState.lastPrewarm=Date.now();perfMark("prewarmReady");}
      if(touchReady){clearInterval(frameBridgeTimer);frameBridgeTimer=0;return;}
    }
    if(tries>480){clearInterval(frameBridgeTimer);frameBridgeTimer=0;}
  };
  tick();frameBridgeTimer=setInterval(tick,100);
}
function createEngineFrame(result){
  const surface=browserWindow?.querySelector("#kbSurface");if(!surface)return null;surface.innerHTML='<iframe class="kbrowser-engine-frame" title="RiftWebKit mobile engine" allow="clipboard-read; clipboard-write; autoplay; fullscreen"></iframe>';
  engineFrame=surface.querySelector("iframe");engineBuild=String(result.engine?.manifest?.build||result.engine?.manifest?.sourceCommit||"proof");engineTarget=result.url;firewallTransportReady=false;perfMark("iframeCreated");
  const bootURL=new URL(result.src);bootURL.searchParams.delete("url");bootURL.searchParams.set("demo","interactive");engineFrame.addEventListener("load",()=>{perfMark("iframeLoad");armFrameBridge(engineFrame,result.url);});engineFrame.src=bootURL.href;return engineFrame;
}
function navigateEngine(result){
  const build=String(result.engine?.manifest?.build||result.engine?.manifest?.sourceCommit||"proof");if(!engineFrame||!engineFrame.isConnected||engineBuild!==build)return createEngineFrame(result);
  const Module=moduleFor(engineFrame);let protectedTransport=false;try{protectedTransport=!!engineFrame.contentWindow?.__riftFirewallTransport?.active;}catch{}
  if(result.browsingReady&&protectedTransport&&Module&&typeof Module.ccall==="function"&&typeof Module._bib_load_url==="function"){dispatchProtectedNavigation(engineFrame,result.url);armFrameBridge(engineFrame);return engineFrame;}engineTarget=result.url;armFrameBridge(engineFrame,result.url);return engineFrame;
}

async function idlePrewarm(){
  if(prewarmStarted||browserVisible||engineFrame?.isConnected)return false;
  const stage=$("#stage"),workspace=$("#workspace"),template=$("#windowTemplate");if(!stage||!workspace||!template)return false;
  prewarmStarted=true;beginPerf("local://riftwebkit-prewarm",{warm:false,reason:"idle-prewarm"});
  try{
    const {reused}=createWindow();
    if(!browser.activeTab())browser.createTab("https://chatgpt.com");
    syncChrome({reset:!browserWindow.querySelector(".kbrowser")});renderStart();
    // Hide the host again in the same task: the user never sees the prewarm UI.
    killBrowserProcess();browserVisible=false;stage.classList.add("hidden");workspace.classList.remove("hidden");
    document.querySelectorAll(".dock-btn").forEach(btn=>btn.classList.toggle("active",btn.dataset.open==="home"));
    await browser.refreshEngines();
    if(browserVisible||engineFrame?.isConnected)return !!engineFrame?.isConnected;
    const engine=browser.info().engine,adapter=window.RiftBrowserEngines;
    if(!engine?.available||!adapter?.frameURL)throw new Error("RiftWebKit prewarm host unavailable");
    const src=adapter.frameURL("",{firewall:browser.firewallSettings?.()||null});
    createEngineFrame({engine,src,url:"",browsingReady:engine.browsingReady});
    scheduleWarmRelease();
    return true;
  }catch(error){console.warn("[RiftBrowser] idle prewarm skipped",error);hardReleaseBrowserHost();return false;}
}
function scheduleIdlePrewarm(){
  clearTimeout(prewarmTimer);if(prewarmStarted||browserVisible||engineFrame?.isConnected)return;
  const run=()=>{prewarmTimer=0;if(typeof requestIdleCallback==="function")requestIdleCallback(()=>idlePrewarm(),{timeout:1500});else idlePrewarm();};
  prewarmTimer=setTimeout(run,PREWARM_DELAY_MS);
}

function showError(error){const surface=browserWindow?.querySelector("#kbSurface");if(surface)surface.innerHTML=`<section class="browser-blocked"><div class="browser-warning">!</div><h2>RiftWebKit error</h2><p>${escapeHTML(error?.message||error)}</p></section>`;}
function renderResult(detail){
  if(!browserWindow)return;syncChrome();const surface=browserWindow.querySelector("#kbSurface"),state=browserWindow.querySelector("#kbState"),ready=browserWindow.querySelector("#kbReady"),result=detail?.result||{};if(state)state.textContent=result.backend||"unknown";if(ready&&result.engine)ready.textContent=result.browsingReady?"WebKit web engine ready":"WebKit proof/runtime ready";
  if(result.mode==="riftwebkit")navigateEngine(result);else if(result.mode==="firewall-blocked"){discardEngineFrame();surface.innerHTML=`<section class="browser-blocked kb-firewall-blocked"><div class="browser-warning">FW</div><h2>RiftFirewall blocked this connection</h2><p>${escapeHTML(result.url||"")}</p><code>${escapeHTML(result.reason||"blocked")}</code><div class="browser-block-actions"><button class="action" id="kbOpenFirewall">Firewall rules</button></div></section>`;surface.querySelector("#kbOpenFirewall").onclick=showFirewallPanel;}else if(result.mode==="unavailable"){discardEngineFrame();surface.innerHTML=`<section class="browser-blocked"><div class="browser-warning">!</div><h2>RiftWebKit is unavailable</h2><p>${escapeHTML(result.reason||"The WebKit engine package is missing.")}</p><div class="browser-block-actions"><button class="action" id="kbRetryEngine">Retry WebKit</button></div></section>`;surface.querySelector("#kbRetryEngine").onclick=async()=>{await browser.refreshEngines(true);await browser.reload();};}
}

async function openBrowser(initial="https://chatgpt.com",{newTab=false}={}){
  clearTimeout(prewarmTimer);prewarmTimer=0;
  const {reused}=createWindow();if(!browser.activeTab())browser.createTab(initial);syncChrome({reset:!browserWindow.querySelector(".kbrowser")});if(!engineFrame?.isConnected)renderStart();await browser.refreshEngines();
  if(reused&&engineFrame?.isConnected&&!newTab&&engineTarget){syncChrome();const ready=browserWindow?.querySelector("#kbReady");if(ready)ready.textContent="WebKit warm · ready";return {warm:true,tab:browser.activeTab(),performance:perfSnapshot()};}
  const target=newTab?initial:(browser.activeTab()?.url||initial);return browser.open(target,{newTab});
}

browser.addEventListener("loading",event=>{beginPerf(event.detail?.tab?.url||engineTarget,{warm:!!engineFrame?.isConnected,reason:event.detail?.backend==="riftwebkit"?"navigation":"backend"});const state=browserWindow?.querySelector("#kbState");if(state)state.textContent=`${event.detail.backend} · loading`;});
browser.addEventListener("render",event=>renderResult(event.detail));browser.addEventListener("backend",()=>{if(browserWindow)syncChrome();});browser.addEventListener("firewall-blocked",event=>{const state=browserWindow?.querySelector("#kbState");if(state)state.textContent=`Firewall · ${event.detail.reason}`;});
window.addEventListener("message",event=>{if(event.origin!==location.origin||!engineFrame||event.source!==engineFrame.contentWindow||!event.data||typeof event.data!=="object")return;if(event.data.type==="riftbrowser:firewall-ready"){if(!firewallTransportReady){firewallTransportReady=true;perfMark("firewallReady");}const ready=browserWindow?.querySelector("#kbReady");if(ready)ready.textContent="RiftFirewall protected";return;}if(event.data.type==="riftbrowser:firewall-block"){browser.noteFirewallBlock(event.data);const state=browserWindow?.querySelector("#kbState");if(state)state.textContent=`Firewall blocked ${event.data.host}:${event.data.port}`;}});
window.addEventListener("pagehide",()=>hardReleaseBrowserHost());

document.addEventListener("click",event=>{const target=event.target.closest?.("[data-open]");if(!target)return;if(target.dataset.open==="browser"){event.preventDefault();event.stopImmediatePropagation();openBrowser().catch(showError);return;}if(browserVisible)closeWebBrowser();},true);
document.addEventListener("submit",event=>{
  const form=event.target;if(!form.matches?.("form.shell-line"))return;const input=form.querySelector("input"),raw=input?.value?.trim()||"",parts=raw.split(/\s+/),cmd=(parts.shift()||"").toLowerCase();if(!["browser","browserctl"].includes(cmd))return;event.preventDefault();event.stopImmediatePropagation();if(input)input.value="";const out=form.closest(".shell")?.querySelector(".shell-output"),print=value=>{if(out){out.textContent+=String(value??"")+"\n";out.scrollTop=out.scrollHeight;}};print(`rift$ ${raw}`);
  (async()=>{if(cmd==="browser"){await openBrowser(parts.join(" ")||"https://chatgpt.com");print("[browser] riftwebkit");return;}const sub=(parts.shift()||"status").toLowerCase();if(sub==="status")return print(JSON.stringify(browser.info(),null,2));if(sub==="perf")return print(JSON.stringify(perfSnapshot(),null,2));if(sub==="prewarm")return print(JSON.stringify({started:await idlePrewarm(),performance:perfSnapshot()},null,2));if(sub==="tabs")return print(browser.listTabs().map(tab=>`${tab.id===browser.activeTab()?.id?"*":" "} ${tab.id} ${tab.url}`).join("\n")||"(no tabs)");if(sub==="probe")return print(JSON.stringify(await browser.refreshEngines(true),null,2));if(sub==="wisp")return print(`wisp=${browser.setWisp(parts.join(" "))||"(unset)"}`);if(sub==="firewall"){const action=(parts.shift()||"status").toLowerCase();if(action==="status")return print(JSON.stringify(browser.firewallSummary(),null,2));if(action==="on")return print(JSON.stringify(browser.setFirewall({enabled:true,networkEnabled:true}),null,2));if(action==="off")return print(JSON.stringify(browser.setFirewall({enabled:false}),null,2));if(action==="kill")return print(JSON.stringify(browser.setFirewall({enabled:true,networkEnabled:false}),null,2));if(action==="resume")return print(JSON.stringify(browser.setFirewall({enabled:true,networkEnabled:true}),null,2));if(action==="reset")return print(JSON.stringify(browser.resetFirewall(),null,2));return print("usage: browserctl firewall [status|on|off|kill|resume|reset]");}if(sub==="clear-data")return print(`siteDataCleared=${await browser.clearBrowserSiteData()}`);if(sub==="new")return openBrowser(parts.join(" ")||"https://chatgpt.com",{newTab:true});if(sub==="back")return browser.back();if(sub==="forward")return browser.forward();if(sub==="reload")return browser.reload();if(sub==="bookmark")return print(JSON.stringify(browser.bookmark(parts.join(" ")||undefined),null,2));if(sub==="bookmarks")return print(JSON.stringify(browser.bookmarks(),null,2));if(sub==="close")return print(browser.closeTab(parts[0]||browser.activeTab()?.id));print("usage: browserctl [status|perf|prewarm|tabs|probe|wisp <wss://.../>|firewall ...|clear-data|new [url]|back|forward|reload|bookmark [url]|bookmarks|close [tabId]]");})().catch(error=>print(`browser error: ${error.message}`));
},true);

window.RiftBrowserPerf=Object.freeze({snapshot:perfSnapshot,release:()=>hardReleaseBrowserHost(),prewarm:idlePrewarm});window.RiftBrowserUI=Object.freeze({open:openBrowser,close:closeWebBrowser,firewall:showFirewallPanel,performance:perfSnapshot,prewarm:idlePrewarm});
function labelBrowserLauncher(){document.querySelectorAll('[data-open="browser"] small').forEach(node=>{node.textContent="RiftWebKit mobile browser";});}
window.addEventListener("riftos:launcher-ready",()=>{labelBrowserLauncher();scheduleIdlePrewarm();});window.addEventListener("riftos:trueos-ready",()=>{labelBrowserLauncher();scheduleIdlePrewarm();});queueMicrotask(()=>{labelBrowserLauncher();scheduleIdlePrewarm();});
