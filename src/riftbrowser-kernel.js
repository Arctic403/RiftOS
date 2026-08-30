const core=window.RiftOSCore;
if(!core?.kernel)throw new Error("RiftBrowser kernel service requires RiftOSCore");

const DEFAULT_HOME="https://chatgpt.com";
const STATE_KEY="riftos.browser.state.v1";
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
function rendererSnapshot(renderer){
  return {
    id:renderer.id,
    name:renderer.name||renderer.id,
    priority:Number(renderer.priority||0),
    available:!!renderer.available(),
    capabilities:{...(renderer.capabilities||{})}
  };
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
    this.renderers=new Map();
    this.preferredRenderer="auto";
    this.tabs=[];
    this.activeTabId=null;
    this.restore();
    this.installBuiltins();
  }

  restore(){
    const state=safeJSON(localStorage.getItem(STATE_KEY),{});
    this.preferredRenderer=typeof state.preferredRenderer==="string"?state.preferredRenderer:"auto";
    this.tabs=Array.isArray(state.tabs)?state.tabs.map(tab=>({
      id:String(tab.id||id()),
      title:String(tab.title||"New Tab"),
      url:String(tab.url||DEFAULT_HOME),
      renderer:String(tab.renderer||"auto"),
      history:Array.isArray(tab.history)&&tab.history.length?tab.history.map(String):[String(tab.url||DEFAULT_HOME)],
      historyIndex:Number.isInteger(tab.historyIndex)?tab.historyIndex:0,
      updated:Number(tab.updated||Date.now())
    })).slice(-20):[];
    this.activeTabId=state.activeTabId&&this.tabs.some(tab=>tab.id===state.activeTabId)?state.activeTabId:(this.tabs.at(-1)?.id||null);
  }

  persist(){
    const tabs=this.tabs.map(({id,title,url,renderer,history,historyIndex,updated})=>({id,title,url,renderer,history,historyIndex,updated}));
    localStorage.setItem(STATE_KEY,JSON.stringify({preferredRenderer:this.preferredRenderer,activeTabId:this.activeTabId,tabs}));
  }

  installBuiltins(){
    this.registerRenderer({
      id:"native-webkit",
      name:"Native WebKit",
      priority:300,
      available:()=>!!core.native?.connected,
      capabilities:{fullWeb:true,tabs:true,history:true,downloads:true,nativeSurface:true},
      open:async({url,newTab})=>{
        await core.native.call("browser.open",{url,newTab:!!newTab});
        return {mode:"native",url,renderer:"native-webkit"};
      },
      closeSurface:async()=>core.native.call("browser.close",{})
    });

    this.registerRenderer({
      id:"riftengine",
      name:"RiftEngine WebCore/WASM",
      priority:200,
      available:()=>{
        const backend=window.RiftEngineBrowserBackend;
        try{return !!backend&&(typeof backend.available!=="function"||backend.available());}catch{return false;}
      },
      capabilities:{fullWeb:true,tabs:true,history:true,localEngine:true,experimental:true},
      open:async context=>{
        const backend=window.RiftEngineBrowserBackend;
        if(!backend)throw new Error("RiftEngine browser backend is not registered");
        const fn=backend.open||backend.navigate;
        if(typeof fn!=="function")throw new Error("RiftEngine backend must provide open() or navigate()");
        const result=await fn.call(backend,context);
        return {mode:"riftengine",renderer:"riftengine",url:context.url,...(result||{})};
      },
      back:async context=>window.RiftEngineBrowserBackend?.back?.(context),
      forward:async context=>window.RiftEngineBrowserBackend?.forward?.(context),
      reload:async context=>window.RiftEngineBrowserBackend?.reload?.(context)
    });

    this.registerRenderer({
      id:"web-transport",
      name:"Web transport fallback",
      priority:100,
      available:()=>true,
      capabilities:{fullWeb:false,corsDocuments:true,externalFallback:true,tabs:true,history:true},
      open:async({url})=>{
        const controller=new AbortController();
        const timer=setTimeout(()=>controller.abort(),10000);
        try{
          const response=await fetch(url,{mode:"cors",credentials:"omit",redirect:"follow",signal:controller.signal});
          if(!response.ok)throw new Error(`HTTP ${response.status}`);
          const finalURL=response.url||url;
          const type=(response.headers.get("content-type")||"").toLowerCase();
          if(!type.includes("text/html")&&!type.includes("text/plain")&&!type.includes("application/xhtml+xml")){
            throw new Error(`Unsupported direct content type: ${type||"unknown"}`);
          }
          const text=await response.text();
          const html=type.includes("text/plain")
            ? `<!doctype html><html><body><pre>${text.replace(/[&<>]/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;"}[ch]))}</pre></body></html>`
            : removeDangerousMarkup(text,finalURL);
          return {mode:"document",renderer:"web-transport",url:finalURL,html};
        }catch(error){
          return {mode:"external",renderer:"web-transport",url,reason:error?.message||String(error)};
        }finally{clearTimeout(timer);}
      }
    });
  }

  registerRenderer(renderer){
    if(!renderer?.id||typeof renderer.open!=="function"||typeof renderer.available!=="function")throw new Error("Invalid RiftBrowser renderer contract");
    this.renderers.set(renderer.id,renderer);
    this.emit("renderers",this.rendererStatus());
    return renderer.id;
  }
  unregisterRenderer(rendererId){
    if(["native-webkit","riftengine","web-transport"].includes(rendererId))return false;
    const removed=this.renderers.delete(rendererId);
    if(removed)this.emit("renderers",this.rendererStatus());
    return removed;
  }
  rendererStatus(){
    const renderers=[...this.renderers.values()].map(rendererSnapshot).sort((a,b)=>b.priority-a.priority);
    return {preferred:this.preferredRenderer,active:this.resolveRenderer(this.preferredRenderer)?.id||null,renderers};
  }
  resolveRenderer(requested="auto"){
    const wanted=requested&&requested!=="auto"?requested:this.preferredRenderer;
    if(wanted&&wanted!=="auto"){
      const renderer=this.renderers.get(wanted);
      if(renderer?.available())return renderer;
      if(requested&&requested!=="auto")throw new Error(`RiftBrowser renderer unavailable: ${wanted}`);
    }
    return [...this.renderers.values()].filter(renderer=>renderer.available()).sort((a,b)=>(b.priority||0)-(a.priority||0))[0]||null;
  }
  setRenderer(rendererId="auto"){
    if(rendererId!=="auto"&&!this.renderers.has(rendererId))throw new Error(`Unknown RiftBrowser renderer: ${rendererId}`);
    this.preferredRenderer=rendererId;
    this.persist();
    this.emit("renderers",this.rendererStatus());
    return this.rendererStatus();
  }

  activeTab(){return this.tabs.find(tab=>tab.id===this.activeTabId)||null;}
  listTabs(){return clone(this.tabs);}
  createTab(url=DEFAULT_HOME,renderer="auto"){
    const target=normalizeTarget(url);
    const tab={id:id(),title:"New Tab",url:target,renderer,history:[target],historyIndex:0,updated:Date.now()};
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

  async open(input=DEFAULT_HOME,{newTab=false,renderer="auto"}={}){
    const target=normalizeTarget(input);
    let tab=this.activeTab();
    if(newTab||!tab)tab=this.createTab(target,renderer);
    else tab.renderer=renderer;
    return this.navigate(target,{tabId:tab.id,replace:newTab||tab.history.length===0,renderer,newTab});
  }
  async newTab(input=DEFAULT_HOME,options={}){return this.open(input,{...options,newTab:true});}

  async navigate(input,{tabId=this.activeTabId,replace=false,renderer="auto",newTab=false}={}){
    const tab=this.tabs.find(item=>item.id===tabId)||this.createTab(input,renderer);
    const target=normalizeTarget(input);
    this.activeTabId=tab.id;
    if(replace){
      tab.history[tab.historyIndex]=target;
    }else if(tab.history[tab.historyIndex]!==target){
      tab.history=tab.history.slice(0,tab.historyIndex+1);
      tab.history.push(target);tab.historyIndex=tab.history.length-1;
    }
    tab.url=target;tab.updated=Date.now();
    const selected=this.resolveRenderer(renderer||tab.renderer||"auto");
    if(!selected)throw new Error("No RiftBrowser renderer is available");
    tab.renderer=selected.id;
    this.persist();
    this.emit("loading",{tab:clone(tab),renderer:rendererSnapshot(selected)});
    const result=await selected.open({url:target,tab:clone(tab),newTab:!!newTab,service:this});
    if(result?.url)tab.url=result.url;
    if(result?.title)tab.title=String(result.title);
    else if(tab.title==="New Tab"){
      try{tab.title=new URL(tab.url).hostname||"New Tab";}catch{}
    }
    tab.updated=Date.now();this.persist();
    const detail={tab:clone(tab),result:{renderer:selected.id,...(result||{})}};
    this.emit("render",detail);this.emit("tabs",this.listTabs());return detail;
  }

  async back(){
    const tab=this.activeTab();if(!tab||tab.historyIndex<=0)return false;
    const renderer=this.resolveRenderer(tab.renderer);
    if(renderer?.back){await renderer.back({tab:clone(tab),service:this});return true;}
    tab.historyIndex--;return this.navigate(tab.history[tab.historyIndex],{tabId:tab.id,replace:true,renderer:tab.renderer});
  }
  async forward(){
    const tab=this.activeTab();if(!tab||tab.historyIndex>=tab.history.length-1)return false;
    const renderer=this.resolveRenderer(tab.renderer);
    if(renderer?.forward){await renderer.forward({tab:clone(tab),service:this});return true;}
    tab.historyIndex++;return this.navigate(tab.history[tab.historyIndex],{tabId:tab.id,replace:true,renderer:tab.renderer});
  }
  async reload(){
    const tab=this.activeTab();if(!tab)return false;
    const renderer=this.resolveRenderer(tab.renderer);
    if(renderer?.reload){await renderer.reload({tab:clone(tab),service:this});return true;}
    return this.navigate(tab.url,{tabId:tab.id,replace:true,renderer:tab.renderer});
  }
  async closeSurface(){
    const renderer=this.resolveRenderer(this.activeTab()?.renderer||this.preferredRenderer);
    return renderer?.closeSurface?renderer.closeSurface():false;
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
  info(){return {home:DEFAULT_HOME,activeTab:this.activeTab()?clone(this.activeTab()):null,tabs:this.listTabs(),bookmarks:this.bookmarks(),...this.rendererStatus()};}
  emit(type,detail){this.dispatchEvent(new CustomEvent(type,{detail}));}
}

const service=new RiftBrowserKernelService();
Object.defineProperty(core.kernel,"browser",{value:service,writable:false,configurable:false,enumerable:true});
window.RiftBrowser=service;
window.RiftBrowserRendererContract=Object.freeze({
  register:renderer=>service.registerRenderer(renderer),
  unregister:id=>service.unregisterRenderer(id),
  status:()=>service.rendererStatus()
});

console.info("[RiftBrowser] kernel browser service online",service.rendererStatus());
