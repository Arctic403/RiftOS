const core=window.RiftOSCore;
if(!core?.kernel)throw new Error("RiftBrowser kernel service requires RiftOSCore");

const engines=window.RiftBrowserEngines||null;
const DEFAULT_HOME="https://chatgpt.com";
const STATE_KEY="riftos.browser.state.v4";
const LEGACY_KEYS=["riftos.browser.state.v3","riftos.browser.state.v2","riftos.browser.state.v1"];
const BOOKMARK_KEY="riftos.browser.bookmarks.v1";
const BACKENDS=["auto","wasm-gecko","native-webkit","web-transport"];

function id(prefix="tab"){
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,9)}`;
}
function clone(value){return JSON.parse(JSON.stringify(value));}
function safeJSON(raw,fallback){try{return raw?JSON.parse(raw):fallback;}catch{return fallback;}}
function normalizeTarget(value=""){
  const input=String(value??"").trim();
  if(!input)return DEFAULT_HOME;
  if(/^(about:|data:|https?:\/\/)/i.test(input))return input;
  if(/^localhost(?::\d+)?(?:\/|$)/i.test(input))return `http://${input}`;
  if(/^(?:[\w-]+\.)+[a-z]{2,}(?:[/:?#]|$)/i.test(input))return `https://${input}`;
  return `https://www.google.com/search?q=${encodeURIComponent(input)}`;
}
function removeDangerousMarkup(html,baseURL){
  const doc=new DOMParser().parseFromString(String(html||""),"text/html");
  doc.querySelectorAll("script,object,embed,applet,iframe,meta[http-equiv='refresh']").forEach(node=>node.remove());
  for(const element of doc.querySelectorAll("*")){
    for(const attr of [...element.attributes]){
      if(/^on/i.test(attr.name))element.removeAttribute(attr.name);
      if(["src","href","action","formaction"].includes(attr.name.toLowerCase())&&/^javascript:/i.test(attr.value.trim()))element.removeAttribute(attr.name);
    }
  }
  const base=doc.createElement("base");base.href=baseURL;doc.head.prepend(base);
  return "<!doctype html>"+doc.documentElement.outerHTML;
}

class RiftBrowserKernelService extends EventTarget{
  constructor(){
    super();
    this.preferredBackend="auto";
    this.activeBackend="probing";
    this.tabs=[];
    this.activeTabId=null;
    this.nativeState=null;
    this.restore();
    queueMicrotask(()=>this.refreshEngines().catch(()=>{}));
  }

  restore(){
    let state=safeJSON(localStorage.getItem(STATE_KEY),null);
    for(const key of LEGACY_KEYS){if(!state)state=safeJSON(localStorage.getItem(key),null);}
    state=state||{};
    this.preferredBackend=BACKENDS.includes(state.preferredBackend)?state.preferredBackend:"auto";
    this.tabs=Array.isArray(state.tabs)?state.tabs.map(tab=>({
      id:String(tab.id||id()),
      title:String(tab.title||"New Tab"),
      url:String(tab.url||DEFAULT_HOME),
      desktop:tab.desktop!==false,
      history:Array.isArray(tab.history)&&tab.history.length?tab.history.map(String):[String(tab.url||DEFAULT_HOME)],
      historyIndex:Number.isInteger(tab.historyIndex)?tab.historyIndex:0,
      canGoBack:!!tab.canGoBack,
      canGoForward:!!tab.canGoForward,
      updated:Number(tab.updated||Date.now())
    })).slice(-20):[];
    this.activeTabId=state.activeTabId&&this.tabs.some(tab=>tab.id===state.activeTabId)?state.activeTabId:(this.tabs.at(-1)?.id||null);
  }

  persist(){
    localStorage.setItem(STATE_KEY,JSON.stringify({
      preferredBackend:this.preferredBackend,
      activeTabId:this.activeTabId,
      tabs:this.tabs.map(({id,title,url,desktop,history,historyIndex,canGoBack,canGoForward,updated})=>({id,title,url,desktop,history,historyIndex,canGoBack,canGoForward,updated}))
    }));
  }

  nativeChannel(){return window.webkit?.messageHandlers?.riftBrowser||null;}
  nativeCommand(method,args={}){
    const channel=this.nativeChannel();
    if(!channel)return false;
    channel.postMessage({method,args});
    return true;
  }

  engineInfo(){return engines?.info?.()||{id:"wasm-gecko",name:"Gecko WASM",available:false,checked:true,error:"engine registry unavailable"};}
  async refreshEngines(force=false){
    if(engines?.probe)await engines.probe({force});
    await this.resolveBackend();
    this.emit("backend",this.backendStatus());
    return this.backendStatus();
  }

  backendStatus(){
    const nativeAvailable=!!core.native?.connected&&!!this.nativeChannel();
    const wasm=this.engineInfo();
    return {
      preferred:this.preferredBackend,
      active:this.activeBackend,
      experimental:true,
      stableBrowser:false,
      nativeShell:location.protocol==="riftos:",
      wisp:engines?.configuredWisp?.()||"",
      backends:[
        {id:"wasm-gecko",name:"Gecko WASM",available:wasm.available===true,fullWeb:true,experimental:true,requiresWisp:true,requiresWasm:true,details:wasm},
        {id:"native-webkit",name:"Apple WebKit native host",available:nativeAvailable,fullWeb:true,experimental:false,requiresSigning:true},
        {id:"web-transport",name:"Legacy CORS transport",available:true,fullWeb:false,experimental:true,fallbackOnly:true}
      ]
    };
  }

  async resolveBackend(){
    const nativeAvailable=!!core.native?.connected&&!!this.nativeChannel();
    if(engines?.probe&&!this.engineInfo().checked)await engines.probe().catch(()=>{});
    const wasmAvailable=this.engineInfo().available===true;
    let next="web-transport";
    if(this.preferredBackend==="wasm-gecko")next=wasmAvailable?"wasm-gecko":"web-transport";
    else if(this.preferredBackend==="native-webkit")next=nativeAvailable?"native-webkit":"web-transport";
    else if(this.preferredBackend==="web-transport")next="web-transport";
    else if(wasmAvailable)next="wasm-gecko";
    else if(nativeAvailable)next="native-webkit";
    this.activeBackend=next;
    return next;
  }

  async setBackend(next="auto"){
    if(!BACKENDS.includes(next))throw new Error(`Unknown RiftBrowser backend: ${next}`);
    if(next==="wasm-gecko"){
      await engines?.probe?.({force:true});
      if(this.engineInfo().available!==true)throw new Error("Gecko WASM engine artifact is not deployed yet or cross-origin isolation is not active");
    }
    if(next==="native-webkit"&&(!core.native?.connected||!this.nativeChannel()))throw new Error("Native Apple WebKit host is unavailable");
    this.preferredBackend=next;this.persist();await this.resolveBackend();this.emit("backend",this.backendStatus());return this.backendStatus();
  }

  setWisp(value=""){
    if(!engines?.setWisp)throw new Error("Browser engine registry unavailable");
    const next=engines.setWisp(value);this.emit("backend",this.backendStatus());return next;
  }

  syncNativeState(state={}){
    if(!Array.isArray(state.tabs))return false;
    const previous=new Map(this.tabs.map(tab=>[tab.id,tab]));
    this.tabs=state.tabs.map(row=>{
      const tabId=String(row.id||id()),url=String(row.url||DEFAULT_HOME),old=previous.get(tabId);
      let history=old?.history?.length?[...old.history]:[url];
      let historyIndex=Number.isInteger(old?.historyIndex)?old.historyIndex:Math.max(0,history.length-1);
      if(history[historyIndex]!==url){history=history.slice(0,historyIndex+1);history.push(url);historyIndex=history.length-1;}
      return {id:tabId,title:String(row.title||old?.title||"New Tab"),url,desktop:row.desktop!==false,history,historyIndex,canGoBack:!!row.canGoBack,canGoForward:!!row.canGoForward,updated:Date.now()};
    }).slice(-20);
    this.activeTabId=state.selectedID&&this.tabs.some(tab=>tab.id===state.selectedID)?String(state.selectedID):(this.tabs[0]?.id||null);
    this.nativeState=clone(state);this.persist();this.emit("native",clone(state));this.emit("tabs",this.listTabs());return true;
  }

  activeTab(){return this.tabs.find(tab=>tab.id===this.activeTabId)||null;}
  listTabs(){return clone(this.tabs);}
  createTab(url=DEFAULT_HOME,{desktop=true}={}){
    const target=normalizeTarget(url);
    const tab={id:id(),title:"New Tab",url:target,desktop:desktop!==false,history:[target],historyIndex:0,canGoBack:false,canGoForward:false,updated:Date.now()};
    this.tabs.push(tab);this.activeTabId=tab.id;this.persist();this.emit("tabs",this.listTabs());return tab;
  }
  selectTab(tabId){
    if(!this.tabs.some(tab=>tab.id===tabId))throw new Error(`Unknown browser tab: ${tabId}`);
    this.activeTabId=tabId;this.persist();
    if(this.activeBackend==="native-webkit")this.nativeCommand("selectTab",{id:tabId});
    this.emit("tabs",this.listTabs());return clone(this.activeTab());
  }
  closeTab(tabId=this.activeTabId){
    const index=this.tabs.findIndex(tab=>tab.id===tabId);if(index<0)return false;
    if(this.activeBackend==="native-webkit")this.nativeCommand("closeTab",{id:tabId});
    this.tabs.splice(index,1);
    if(this.activeTabId===tabId)this.activeTabId=this.tabs[Math.min(index,this.tabs.length-1)]?.id||null;
    this.persist();this.emit("tabs",this.listTabs());return true;
  }

  async open(input=DEFAULT_HOME,{newTab=false,desktop=true}={}){
    const target=normalizeTarget(input);let tab=this.activeTab();
    if(newTab||!tab)tab=this.createTab(target,{desktop});
    return this.navigate(target,{tabId:tab.id,replace:newTab||tab.history.length===0,newTab,desktop});
  }
  async newTab(input=DEFAULT_HOME,options={}){return this.open(input,{...options,newTab:true});}

  async navigate(input,{tabId=this.activeTabId,replace=false,newTab=false,desktop}={}){
    const tab=this.tabs.find(item=>item.id===tabId)||this.createTab(input,{desktop:desktop!==false});
    const target=normalizeTarget(input);this.activeTabId=tab.id;
    if(typeof desktop==="boolean")tab.desktop=desktop;
    if(replace)tab.history[tab.historyIndex]=target;
    else if(tab.history[tab.historyIndex]!==target){tab.history=tab.history.slice(0,tab.historyIndex+1);tab.history.push(target);tab.historyIndex=tab.history.length-1;}
    tab.url=target;tab.updated=Date.now();this.persist();

    const backend=await this.resolveBackend();
    this.emit("loading",{tab:clone(tab),backend});
    let result;
    if(backend==="wasm-gecko"){
      result={mode:"engine-frame",backend,url:target,src:engines.frameURL(target),engine:this.engineInfo()};
    }else if(backend==="native-webkit"){
      const method=newTab?"newTab":"open";
      if(!this.nativeCommand(method,{url:target,newTab:!!newTab,desktop:tab.desktop}))await core.native.call("browser.open",{url:target,newTab:!!newTab});
      result={mode:"native",backend,url:target};
    }else result=await this.openWithWebTransport(target);

    if(result?.url)tab.url=result.url;
    if(result?.title)tab.title=String(result.title);
    else if(tab.title==="New Tab"){try{tab.title=new URL(tab.url).hostname||"New Tab";}catch{}}
    tab.updated=Date.now();this.persist();
    const detail={tab:clone(tab),result};this.emit("render",detail);this.emit("tabs",this.listTabs());return detail;
  }

  async openWithWebTransport(url){
    const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),10000);
    try{
      const response=await fetch(url,{mode:"cors",credentials:"omit",redirect:"follow",signal:controller.signal});
      if(!response.ok)throw new Error(`HTTP ${response.status}`);
      const finalURL=response.url||url,type=(response.headers.get("content-type")||"").toLowerCase();
      if(!type.includes("text/html")&&!type.includes("text/plain")&&!type.includes("application/xhtml+xml"))throw new Error(`Unsupported direct content type: ${type||"unknown"}`);
      const text=await response.text();
      const html=type.includes("text/plain")?`<!doctype html><html><body><pre>${text.replace(/[&<>]/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;"}[ch]))}</pre></body></html>`:removeDangerousMarkup(text,finalURL);
      return {mode:"document",backend:"web-transport",url:finalURL,html};
    }catch(error){return {mode:"external",backend:"web-transport",url,reason:error?.message||String(error)};}
    finally{clearTimeout(timer);}
  }

  async back(){
    if((await this.resolveBackend())==="native-webkit")return this.nativeCommand("back");
    const tab=this.activeTab();if(!tab||tab.historyIndex<=0)return false;
    tab.historyIndex--;return this.navigate(tab.history[tab.historyIndex],{tabId:tab.id,replace:true});
  }
  async forward(){
    if((await this.resolveBackend())==="native-webkit")return this.nativeCommand("forward");
    const tab=this.activeTab();if(!tab||tab.historyIndex>=tab.history.length-1)return false;
    tab.historyIndex++;return this.navigate(tab.history[tab.historyIndex],{tabId:tab.id,replace:true});
  }
  async reload(){
    const backend=await this.resolveBackend();
    if(backend==="native-webkit")return this.nativeCommand("reload");
    const tab=this.activeTab();if(!tab)return false;return this.navigate(tab.url,{tabId:tab.id,replace:true});
  }
  stop(){return this.activeBackend==="native-webkit"?this.nativeCommand("stop"):false;}
  setDesktopMode(enabled=true,tabId=this.activeTabId){
    const tab=this.tabs.find(item=>item.id===tabId);if(tab)tab.desktop=!!enabled;this.persist();
    if(this.activeBackend==="native-webkit")this.nativeCommand("desktop",{id:tabId,enabled:!!enabled});
    this.emit("tabs",this.listTabs());return !!enabled;
  }
  share(){return this.activeBackend==="native-webkit"?this.nativeCommand("share"):false;}
  find(){return this.activeBackend==="native-webkit"?this.nativeCommand("find"):false;}
  async closeSurface(){
    if(this.activeBackend==="native-webkit"){
      if(this.nativeCommand("closeSurface"))return true;
      return core.native.call("browser.close",{});
    }
    return false;
  }

  bookmarks(){return safeJSON(localStorage.getItem(BOOKMARK_KEY),[]);}
  bookmark(url=this.activeTab()?.url,title=this.activeTab()?.title){
    const target=normalizeTarget(url),rows=this.bookmarks().filter(row=>row.url!==target);
    rows.unshift({url:target,title:String(title||target),created:Date.now()});localStorage.setItem(BOOKMARK_KEY,JSON.stringify(rows.slice(0,200)));this.emit("bookmarks",rows);return rows[0];
  }
  removeBookmark(url){const target=normalizeTarget(url),rows=this.bookmarks().filter(row=>row.url!==target);localStorage.setItem(BOOKMARK_KEY,JSON.stringify(rows));this.emit("bookmarks",rows);return rows;}
  history(limit=100){
    const rows=[];for(const tab of this.tabs)for(const url of tab.history)rows.push({tabId:tab.id,url,title:tab.title,updated:tab.updated});
    return rows.sort((a,b)=>b.updated-a.updated).slice(0,limit);
  }
  info(){return {home:DEFAULT_HOME,activeTab:this.activeTab()?clone(this.activeTab()):null,tabs:this.listTabs(),bookmarks:this.bookmarks(),nativeState:this.nativeState,engine:this.engineInfo(),...this.backendStatus()};}
  emit(type,detail){this.dispatchEvent(new CustomEvent(type,{detail}));}
}

const service=new RiftBrowserKernelService();
Object.defineProperty(core.kernel,"browser",{value:service,writable:false,configurable:false,enumerable:true});
window.RiftBrowser=service;

console.info("[RiftBrowser] experimental multi-engine browser service online",service.backendStatus());
