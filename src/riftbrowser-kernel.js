const core=window.RiftOSCore;
if(!core?.kernel)throw new Error("RiftBrowser kernel service requires RiftOSCore");

const DEFAULT_HOME="https://chatgpt.com";
const STATE_KEY="riftos.browser.state.v2";
const LEGACY_STATE_KEY="riftos.browser.state.v1";
const BOOKMARK_KEY="riftos.browser.bookmarks.v1";

function id(prefix="tab"){
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,9)}`;
}
function clone(value){return JSON.parse(JSON.stringify(value));}
function safeJSON(raw,fallback){try{return raw?JSON.parse(raw):fallback;}catch{return fallback;}}
function normalizeTarget(value=""){
  const input=String(value??"").trim();
  if(!input)return DEFAULT_HOME;
  if(/^(about:|https?:\/\/)/i.test(input))return input;
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
    this.tabs=[];
    this.activeTabId=null;
    this.restore();
  }

  restore(){
    const state=safeJSON(localStorage.getItem(STATE_KEY),safeJSON(localStorage.getItem(LEGACY_STATE_KEY),{}));
    this.preferredBackend=["auto","native-webkit","web-transport"].includes(state.preferredBackend)
      ? state.preferredBackend
      : (["auto","native-webkit","web-transport"].includes(state.preferredRenderer)?state.preferredRenderer:"auto");
    this.tabs=Array.isArray(state.tabs)?state.tabs.map(tab=>({
      id:String(tab.id||id()),
      title:String(tab.title||"New Tab"),
      url:String(tab.url||DEFAULT_HOME),
      history:Array.isArray(tab.history)&&tab.history.length?tab.history.map(String):[String(tab.url||DEFAULT_HOME)],
      historyIndex:Number.isInteger(tab.historyIndex)?tab.historyIndex:0,
      updated:Number(tab.updated||Date.now())
    })).slice(-20):[];
    this.activeTabId=state.activeTabId&&this.tabs.some(tab=>tab.id===state.activeTabId)?state.activeTabId:(this.tabs.at(-1)?.id||null);
  }

  persist(){
    localStorage.setItem(STATE_KEY,JSON.stringify({
      preferredBackend:this.preferredBackend,
      activeTabId:this.activeTabId,
      tabs:this.tabs.map(({id,title,url,history,historyIndex,updated})=>({id,title,url,history,historyIndex,updated}))
    }));
  }

  backendStatus(){
    const nativeAvailable=!!core.native?.connected;
    const active=this.preferredBackend==="web-transport"?"web-transport":(nativeAvailable?"native-webkit":"web-transport");
    return {
      preferred:this.preferredBackend,
      active,
      backends:[
        {id:"native-webkit",name:"Apple WebKit",available:nativeAvailable,fullWeb:true,desktopMode:true},
        {id:"web-transport",name:"Web/PWA fallback",available:true,fullWeb:false,desktopMode:false}
      ]
    };
  }

  setBackend(id="auto"){
    if(!["auto","native-webkit","web-transport"].includes(id))throw new Error(`Unknown RiftBrowser backend: ${id}`);
    if(id==="native-webkit"&&!core.native?.connected)throw new Error("Native Apple WebKit is unavailable outside RiftOS Native");
    this.preferredBackend=id;this.persist();this.emit("backend",this.backendStatus());return this.backendStatus();
  }

  activeTab(){return this.tabs.find(tab=>tab.id===this.activeTabId)||null;}
  listTabs(){return clone(this.tabs);}
  createTab(url=DEFAULT_HOME){
    const target=normalizeTarget(url);
    const tab={id:id(),title:"New Tab",url:target,history:[target],historyIndex:0,updated:Date.now()};
    this.tabs.push(tab);this.activeTabId=tab.id;this.persist();this.emit("tabs",this.listTabs());return tab;
  }
  selectTab(tabId){
    if(!this.tabs.some(tab=>tab.id===tabId))throw new Error(`Unknown browser tab: ${tabId}`);
    this.activeTabId=tabId;this.persist();this.emit("tabs",this.listTabs());return clone(this.activeTab());
  }
  closeTab(tabId=this.activeTabId){
    const index=this.tabs.findIndex(tab=>tab.id===tabId);if(index<0)return false;
    this.tabs.splice(index,1);
    if(this.activeTabId===tabId)this.activeTabId=this.tabs[Math.min(index,this.tabs.length-1)]?.id||null;
    this.persist();this.emit("tabs",this.listTabs());return true;
  }

  async open(input=DEFAULT_HOME,{newTab=false}={}){
    const target=normalizeTarget(input);
    let tab=this.activeTab();
    if(newTab||!tab)tab=this.createTab(target);
    return this.navigate(target,{tabId:tab.id,replace:newTab||tab.history.length===0,newTab});
  }
  async newTab(input=DEFAULT_HOME){return this.open(input,{newTab:true});}

  async navigate(input,{tabId=this.activeTabId,replace=false,newTab=false}={}){
    const tab=this.tabs.find(item=>item.id===tabId)||this.createTab(input);
    const target=normalizeTarget(input);
    this.activeTabId=tab.id;
    if(replace){
      tab.history[tab.historyIndex]=target;
    }else if(tab.history[tab.historyIndex]!==target){
      tab.history=tab.history.slice(0,tab.historyIndex+1);
      tab.history.push(target);tab.historyIndex=tab.history.length-1;
    }
    tab.url=target;tab.updated=Date.now();this.persist();

    const backend=this.backendStatus().active;
    this.emit("loading",{tab:clone(tab),backend});
    let result;
    if(backend==="native-webkit"){
      await core.native.call("browser.open",{url:target,newTab:!!newTab});
      result={mode:"native",backend,url:target};
    }else{
      result=await this.openWithWebTransport(target);
    }

    if(result?.url)tab.url=result.url;
    if(result?.title)tab.title=String(result.title);
    else if(tab.title==="New Tab"){
      try{tab.title=new URL(tab.url).hostname||"New Tab";}catch{}
    }
    tab.updated=Date.now();this.persist();
    const detail={tab:clone(tab),result};this.emit("render",detail);this.emit("tabs",this.listTabs());return detail;
  }

  async openWithWebTransport(url){
    const controller=new AbortController();
    const timer=setTimeout(()=>controller.abort(),10000);
    try{
      const response=await fetch(url,{mode:"cors",credentials:"omit",redirect:"follow",signal:controller.signal});
      if(!response.ok)throw new Error(`HTTP ${response.status}`);
      const finalURL=response.url||url;
      const type=(response.headers.get("content-type")||"").toLowerCase();
      if(!type.includes("text/html")&&!type.includes("text/plain")&&!type.includes("application/xhtml+xml"))throw new Error(`Unsupported direct content type: ${type||"unknown"}`);
      const text=await response.text();
      const html=type.includes("text/plain")
        ? `<!doctype html><html><body><pre>${text.replace(/[&<>]/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;"}[ch]))}</pre></body></html>`
        : removeDangerousMarkup(text,finalURL);
      return {mode:"document",backend:"web-transport",url:finalURL,html};
    }catch(error){
      return {mode:"external",backend:"web-transport",url,reason:error?.message||String(error)};
    }finally{clearTimeout(timer);}
  }

  async back(){
    if(this.backendStatus().active==="native-webkit")return false;
    const tab=this.activeTab();if(!tab||tab.historyIndex<=0)return false;
    tab.historyIndex--;return this.navigate(tab.history[tab.historyIndex],{tabId:tab.id,replace:true});
  }
  async forward(){
    if(this.backendStatus().active==="native-webkit")return false;
    const tab=this.activeTab();if(!tab||tab.historyIndex>=tab.history.length-1)return false;
    tab.historyIndex++;return this.navigate(tab.history[tab.historyIndex],{tabId:tab.id,replace:true});
  }
  async reload(){
    const tab=this.activeTab();if(!tab)return false;
    if(this.backendStatus().active==="native-webkit")return this.navigate(tab.url,{tabId:tab.id,replace:true});
    return this.navigate(tab.url,{tabId:tab.id,replace:true});
  }
  async closeSurface(){
    if(this.backendStatus().active==="native-webkit")return core.native.call("browser.close",{});
    return false;
  }

  bookmarks(){return safeJSON(localStorage.getItem(BOOKMARK_KEY),[]);}
  bookmark(url=this.activeTab()?.url,title=this.activeTab()?.title){
    const target=normalizeTarget(url);const rows=this.bookmarks().filter(row=>row.url!==target);
    rows.unshift({url:target,title:String(title||target),created:Date.now()});
    localStorage.setItem(BOOKMARK_KEY,JSON.stringify(rows.slice(0,200)));this.emit("bookmarks",rows);return rows[0];
  }
  removeBookmark(url){
    const target=normalizeTarget(url);const rows=this.bookmarks().filter(row=>row.url!==target);
    localStorage.setItem(BOOKMARK_KEY,JSON.stringify(rows));this.emit("bookmarks",rows);return rows;
  }
  history(limit=100){
    const rows=[];
    for(const tab of this.tabs)for(const url of tab.history)rows.push({tabId:tab.id,url,title:tab.title,updated:tab.updated});
    return rows.sort((a,b)=>b.updated-a.updated).slice(0,limit);
  }
  info(){return {home:DEFAULT_HOME,activeTab:this.activeTab()?clone(this.activeTab()):null,tabs:this.listTabs(),bookmarks:this.bookmarks(),...this.backendStatus()};}
  emit(type,detail){this.dispatchEvent(new CustomEvent(type,{detail}));}
}

const service=new RiftBrowserKernelService();
Object.defineProperty(core.kernel,"browser",{value:service,writable:false,configurable:false,enumerable:true});
window.RiftBrowser=service;

console.info("[RiftBrowser] Apple WebKit browser service online",service.backendStatus());
