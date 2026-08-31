const core=window.RiftOSCore;
if(!core?.kernel)throw new Error("RiftBrowser kernel service requires RiftOSCore");

const engines=window.RiftBrowserEngines||null;
const firewall=window.RiftBrowserFirewall||null;
const DEFAULT_HOME="https://chatgpt.com";
const STATE_KEY="riftos.browser.state.v6";
const MIGRATION_KEYS=["riftos.browser.state.v5","riftos.browser.state.v4","riftos.browser.state.v3","riftos.browser.state.v2","riftos.browser.state.v1"];
const BOOKMARK_KEY="riftos.browser.bookmarks.v1";
const BACKENDS=["riftwebkit"];

function id(prefix="tab"){return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,9)}`;}
function clone(value){return JSON.parse(JSON.stringify(value));}
function safeJSON(raw,fallback){try{return raw?JSON.parse(raw):fallback;}catch{return fallback;}}
function normalizeTarget(value=""){
  const input=String(value??"").trim();
  if(!input)return DEFAULT_HOME;
  if(/^https?:\/\//i.test(input))return input;
  if(/^localhost(?::\d+)?(?:\/|$)/i.test(input))return `http://${input}`;
  if(/^(?:[\w-]+\.)+[a-z]{2,}(?:[/:?#]|$)/i.test(input))return `https://${input}`;
  return `https://www.google.com/search?q=${encodeURIComponent(input)}`;
}

class RiftBrowserKernelService extends EventTarget{
  constructor(){
    super();
    this.activeBackend="probing";
    this.tabs=[];
    this.activeTabId=null;
    this.restore();
    firewall?.addEventListener?.("blocked",event=>this.emit("firewall-blocked",event.detail));
    firewall?.addEventListener?.("change",()=>this.emit("firewall",this.firewallSummary()));
    queueMicrotask(()=>this.refreshEngines().catch(()=>{}));
  }

  restore(){
    let state=safeJSON(localStorage.getItem(STATE_KEY),null);
    for(const key of MIGRATION_KEYS){if(!state)state=safeJSON(localStorage.getItem(key),null);}
    state=state||{};
    this.tabs=Array.isArray(state.tabs)?state.tabs.map(tab=>{
      const url=String(tab.url||DEFAULT_HOME);
      const history=Array.isArray(tab.history)&&tab.history.length?tab.history.map(String):[url];
      const historyIndex=Math.max(0,Math.min(Number.isInteger(tab.historyIndex)?tab.historyIndex:history.length-1,history.length-1));
      return {
        id:String(tab.id||id()),title:String(tab.title||"New Tab"),url,history,historyIndex,
        canGoBack:historyIndex>0,canGoForward:historyIndex<history.length-1,updated:Number(tab.updated||Date.now())
      };
    }).slice(-12):[];
    this.activeTabId=state.activeTabId&&this.tabs.some(tab=>tab.id===state.activeTabId)?state.activeTabId:(this.tabs.at(-1)?.id||null);
    this.persist();
  }

  persist(){localStorage.setItem(STATE_KEY,JSON.stringify({backend:"riftwebkit",activeTabId:this.activeTabId,tabs:this.tabs}));}
  engineInfo(){return engines?.info?.()||{id:"riftwebkit",name:"RiftWebKit Mobile",available:false,browsingReady:false,checked:true,error:"RiftWebKit engine registry unavailable"};}
  firewallSettings(){return firewall?.settings?.()||{enabled:false,networkEnabled:true,blockHosts:[],allowHosts:[]};}
  firewallSummary(){return firewall?.summary?.()||{enabled:false,networkEnabled:true,recentBlocks:[]};}
  setFirewall(patch={}){
    if(!firewall?.update)throw new Error("RiftFirewall service unavailable");
    const next=firewall.update(patch);this.emit("firewall",firewall.summary());return next;
  }
  resetFirewall(){
    if(!firewall?.reset)throw new Error("RiftFirewall service unavailable");
    const next=firewall.reset();this.emit("firewall",firewall.summary());return next;
  }
  noteFirewallBlock(detail={}){return firewall?.record?.(detail)||detail;}
  async clearBrowserSiteData(){
    const cleared=await firewall?.clearGuestProfile?.();
    this.emit("firewall",this.firewallSummary());return !!cleared;
  }

  async refreshEngines(force=false){
    if(engines?.probe)await engines.probe({force});
    await this.resolveBackend();
    const status=this.backendStatus();
    this.emit("backend",status);
    return status;
  }
  async resolveBackend(){
    if(engines?.probe&&!this.engineInfo().checked)await engines.probe().catch(()=>{});
    this.activeBackend=this.engineInfo().available===true?"riftwebkit":"unavailable";
    return this.activeBackend;
  }
  backendStatus(){
    const engine=this.engineInfo();
    return {
      preferred:"riftwebkit",active:this.activeBackend,experimental:true,stableBrowser:false,webkitOnly:true,
      wisp:engines?.configuredWisp?.()||"",firewall:this.firewallSummary(),
      backends:[{
        id:"riftwebkit",name:"RiftWebKit Mobile",available:engine.available===true,browsingReady:engine.browsingReady===true,
        fullWeb:engine.browsingReady===true,experimental:true,requiresWisp:!!engine.requiresWisp,requiresWasm:true,details:engine
      }]
    };
  }
  async setBackend(next="riftwebkit"){
    if(!BACKENDS.includes(next))throw new Error("RiftBrowser is WebKit-only");
    await this.refreshEngines(true);
    if(this.engineInfo().available!==true)throw new Error(this.engineInfo().error||"RiftWebKit is unavailable");
    return this.backendStatus();
  }
  setWisp(value=""){
    if(!engines?.setWisp)throw new Error("RiftWebKit engine registry unavailable");
    const next=engines.setWisp(value);
    this.emit("backend",this.backendStatus());
    return next;
  }

  activeTab(){return this.tabs.find(tab=>tab.id===this.activeTabId)||null;}
  listTabs(){return clone(this.tabs);}
  createTab(url=DEFAULT_HOME){
    const target=normalizeTarget(url);
    const tab={id:id(),title:"New Tab",url:target,history:[target],historyIndex:0,canGoBack:false,canGoForward:false,updated:Date.now()};
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
    const target=normalizeTarget(input);let tab=this.activeTab();if(newTab||!tab)tab=this.createTab(target);
    return this.navigate(target,{tabId:tab.id,replace:newTab||tab.history.length===0});
  }
  async newTab(input=DEFAULT_HOME){return this.open(input,{newTab:true});}

  async navigate(input,{tabId=this.activeTabId,replace=false}={}){
    const target=normalizeTarget(input);
    const tab=this.tabs.find(item=>item.id===tabId)||this.createTab(target);
    this.activeTabId=tab.id;
    if(replace){if(!tab.history.length)tab.history=[target];else tab.history[tab.historyIndex]=target;}
    else if(tab.history[tab.historyIndex]!==target){tab.history=tab.history.slice(0,tab.historyIndex+1);tab.history.push(target);tab.historyIndex=tab.history.length-1;}
    tab.url=target;tab.canGoBack=tab.historyIndex>0;tab.canGoForward=tab.historyIndex<tab.history.length-1;tab.updated=Date.now();
    if(tab.title==="New Tab"){try{tab.title=new URL(target).hostname||"New Tab";}catch{}}
    this.persist();

    const verdict=firewall?.evaluateURL?.(target)||{allow:true,reason:"firewall-unavailable"};
    if(!verdict.allow){
      const blocked=firewall?.record?.({url:target,host:verdict.host,port:verdict.port,reason:verdict.reason})||verdict;
      const result={mode:"firewall-blocked",backend:"riftfirewall",url:target,reason:verdict.reason,firewall:blocked};
      const detail={tab:clone(tab),result};this.emit("render",detail);this.emit("tabs",this.listTabs());return detail;
    }

    const backend=await this.resolveBackend();
    this.emit("loading",{tab:clone(tab),backend});
    let result;
    if(backend==="riftwebkit"){
      const engine=this.engineInfo();
      const firewallPolicy=firewall?.runtimePolicy?.(target)||null;
      result={mode:"riftwebkit",backend:"riftwebkit",url:target,src:engines.frameURL(target,{firewall:firewallPolicy}),engine,browsingReady:engine.browsingReady===true,firewall:firewallPolicy};
    }else result={mode:"unavailable",backend:"unavailable",url:target,reason:this.engineInfo().error||"RiftWebKit rendering engine is unavailable"};

    tab.updated=Date.now();this.persist();
    const detail={tab:clone(tab),result};this.emit("render",detail);this.emit("tabs",this.listTabs());return detail;
  }

  async back(){
    const tab=this.activeTab();if(!tab||tab.historyIndex<=0)return false;
    tab.historyIndex--;tab.canGoBack=tab.historyIndex>0;tab.canGoForward=true;
    return this.navigate(tab.history[tab.historyIndex],{tabId:tab.id,replace:true});
  }
  async forward(){
    const tab=this.activeTab();if(!tab||tab.historyIndex>=tab.history.length-1)return false;
    tab.historyIndex++;tab.canGoBack=true;tab.canGoForward=tab.historyIndex<tab.history.length-1;
    return this.navigate(tab.history[tab.historyIndex],{tabId:tab.id,replace:true});
  }
  async reload(){const tab=this.activeTab();return tab?this.navigate(tab.url,{tabId:tab.id,replace:true}):false;}
  stop(){return false;}

  bookmarks(){return safeJSON(localStorage.getItem(BOOKMARK_KEY),[]);}
  bookmark(url=this.activeTab()?.url,title=this.activeTab()?.title){
    const target=normalizeTarget(url),rows=this.bookmarks().filter(row=>row.url!==target);
    rows.unshift({url:target,title:String(title||target),created:Date.now()});
    localStorage.setItem(BOOKMARK_KEY,JSON.stringify(rows.slice(0,200)));this.emit("bookmarks",rows);return rows[0];
  }
  removeBookmark(url){
    const target=normalizeTarget(url),rows=this.bookmarks().filter(row=>row.url!==target);
    localStorage.setItem(BOOKMARK_KEY,JSON.stringify(rows));this.emit("bookmarks",rows);return rows;
  }
  history(limit=100){
    const rows=[];for(const tab of this.tabs)for(const url of tab.history)rows.push({tabId:tab.id,url,title:tab.title,updated:tab.updated});
    return rows.sort((a,b)=>b.updated-a.updated).slice(0,limit);
  }
  info(){return {home:DEFAULT_HOME,activeTab:this.activeTab()?clone(this.activeTab()):null,tabs:this.listTabs(),bookmarks:this.bookmarks(),engine:this.engineInfo(),firewall:this.firewallSummary(),...this.backendStatus()};}
  emit(type,detail){this.dispatchEvent(new CustomEvent(type,{detail}));}
}

const service=new RiftBrowserKernelService();
Object.defineProperty(core.kernel,"browser",{value:service,writable:false,configurable:false,enumerable:true});
window.RiftBrowser=service;
console.info("[RiftBrowser] WebKit-only browser service online",service.backendStatus());
