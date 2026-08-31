const STORAGE_KEY="riftos.browser.firewall.v1";
const PROFILE_FILE="bib-state-v1.json";
const SENSITIVE_PORTS=Object.freeze([20,21,22,23,25,53,69,110,111,135,137,138,139,143,389,445,465,512,513,514,587,631,993,995,1433,1521,2049,2375,2376,3306,3389,5432,5900,5984,6379,8086,9200,11211,27017]);
const DEFAULTS=Object.freeze({
  enabled:true,
  networkEnabled:true,
  httpsOnly:true,
  blockPrivateHosts:true,
  blockSensitivePorts:true,
  blockTrackers:true,
  blockHosts:[],
  allowHosts:[]
});

function clone(value){return JSON.parse(JSON.stringify(value));}
function safeJSON(raw,fallback){try{return raw?JSON.parse(raw):fallback;}catch{return fallback;}}
function normalizeHost(value=""){
  let raw=String(value??"").trim().toLowerCase();
  if(!raw)return "";
  raw=raw.replace(/^\*\./,"");
  try{
    const parsed=new URL(raw.includes("://")?raw:`https://${raw}`);
    raw=parsed.hostname;
  }catch{
    raw=raw.split("/")[0];
    if(raw.startsWith("[")&&raw.includes("]"))raw=raw.slice(1,raw.indexOf("]"));
    else raw=raw.split(":")[0];
  }
  return raw.replace(/^\[|\]$/g,"").replace(/^\.+|\.+$/g,"");
}
function normalizeRules(values){
  const input=Array.isArray(values)?values:String(values??"").split(/[\n,]+/);
  return [...new Set(input.map(normalizeHost).filter(Boolean))].slice(0,256);
}
function hostMatches(host,rule){
  const h=normalizeHost(host),r=normalizeHost(rule);
  return !!h&&!!r&&(h===r||h.endsWith(`.${r}`));
}
function matchingRule(host,rules=[]){return rules.find(rule=>hostMatches(host,rule))||"";}
function ipv4Parts(host){
  if(!/^\d{1,3}(?:\.\d{1,3}){3}$/.test(host))return null;
  const parts=host.split(".").map(Number);return parts.every(n=>n>=0&&n<=255)?parts:null;
}
function isPrivateHost(value=""){
  const host=normalizeHost(value);
  if(!host)return true;
  if(host==="localhost"||host.endsWith(".localhost")||host.endsWith(".local")||host.endsWith(".internal"))return true;
  const ip=ipv4Parts(host);
  if(ip){
    const [a,b]=ip;
    if(a===0||a===10||a===127)return true;
    if(a===100&&b>=64&&b<=127)return true;
    if(a===169&&b===254)return true;
    if(a===172&&b>=16&&b<=31)return true;
    if(a===192&&b===168)return true;
    if(a===198&&(b===18||b===19))return true;
    if(a>=224)return true;
  }
  const v6=host.toLowerCase();
  if(v6.includes(":")){
    if(v6==="::"||v6==="::1"||v6.startsWith("fc")||v6.startsWith("fd")||/^fe[89ab]/.test(v6))return true;
  }
  return false;
}
function sanitize(input={}){
  const raw={...DEFAULTS,...(input&&typeof input==="object"?input:{})};
  return {
    enabled:raw.enabled!==false,
    networkEnabled:raw.networkEnabled!==false,
    httpsOnly:raw.httpsOnly!==false,
    blockPrivateHosts:raw.blockPrivateHosts!==false,
    blockSensitivePorts:raw.blockSensitivePorts!==false,
    blockTrackers:raw.blockTrackers!==false,
    blockHosts:normalizeRules(raw.blockHosts),
    allowHosts:normalizeRules(raw.allowHosts)
  };
}
function effectivePort(url){
  if(url.port)return Number(url.port)||0;
  if(url.protocol==="https:"||url.protocol==="wss:")return 443;
  if(url.protocol==="http:"||url.protocol==="ws:")return 80;
  return 0;
}

class RiftFirewallService extends EventTarget{
  constructor(){super();this.events=[];this._settings=sanitize(safeJSON(localStorage.getItem(STORAGE_KEY),DEFAULTS));this.persist();}
  persist(){localStorage.setItem(STORAGE_KEY,JSON.stringify(this._settings));}
  settings(){return clone(this._settings);}
  summary(){
    const s=this._settings;
    return {enabled:s.enabled,networkEnabled:s.networkEnabled,httpsOnly:s.httpsOnly,blockPrivateHosts:s.blockPrivateHosts,blockSensitivePorts:s.blockSensitivePorts,blockTrackers:s.blockTrackers,blockedHosts:s.blockHosts.length,allowedHosts:s.allowHosts.length,recentBlocks:this.events.slice(-20).reverse()};
  }
  update(patch={}){
    this._settings=sanitize({...this._settings,...patch});this.persist();
    this.dispatchEvent(new CustomEvent("change",{detail:this.settings()}));return this.settings();
  }
  reset(){this._settings=sanitize(DEFAULTS);this.persist();this.dispatchEvent(new CustomEvent("change",{detail:this.settings()}));return this.settings();}
  record(detail={}){
    const row={at:Date.now(),host:String(detail.host||""),port:Number(detail.port||0),reason:String(detail.reason||"blocked"),url:String(detail.url||"")};
    this.events.push(row);if(this.events.length>100)this.events.splice(0,this.events.length-100);
    this.dispatchEvent(new CustomEvent("blocked",{detail:row}));return row;
  }
  evaluateURL(input=""){
    const s=this._settings;
    if(!s.enabled)return {allow:true,reason:"firewall-disabled"};
    if(!s.networkEnabled)return {allow:false,reason:"network-kill-switch"};
    let url;try{url=new URL(String(input));}catch{return {allow:false,reason:"invalid-url"};}
    if(["about:","data:","blob:"].includes(url.protocol))return {allow:true,reason:"local-scheme"};
    if(!["http:","https:"].includes(url.protocol))return {allow:false,reason:`scheme-${url.protocol.replace(":","")}-blocked`};
    const host=normalizeHost(url.hostname);
    if(s.httpsOnly&&url.protocol!=="https:")return {allow:false,reason:"https-only",host,port:effectivePort(url)};
    if(s.blockPrivateHosts&&isPrivateHost(host))return {allow:false,reason:"private-host",host,port:effectivePort(url)};
    const allowRule=matchingRule(host,s.allowHosts);
    const blockRule=matchingRule(host,s.blockHosts);
    if(blockRule&&!allowRule)return {allow:false,reason:`blocked-host:${blockRule}`,host,port:effectivePort(url)};
    const port=effectivePort(url);
    if(s.blockSensitivePorts&&SENSITIVE_PORTS.includes(port))return {allow:false,reason:`sensitive-port:${port}`,host,port};
    return {allow:true,reason:allowRule?`allowed-host:${allowRule}`:"allowed",host,port};
  }
  runtimePolicy(target=""){
    const s=this._settings;
    let firstPartyHost="";try{firstPartyHost=normalizeHost(new URL(String(target)).hostname);}catch{}
    return {...s,firstPartyHost,sensitivePorts:[...SENSITIVE_PORTS]};
  }
  async clearGuestProfile(){
    if(!navigator.storage?.getDirectory)return false;
    const root=await navigator.storage.getDirectory();
    await root.removeEntry(PROFILE_FILE).catch(()=>{});
    this.events=[];
    this.dispatchEvent(new CustomEvent("profile-cleared"));return true;
  }
}

const service=new RiftFirewallService();
window.RiftBrowserFirewall=service;
window.RiftFirewall=service;
console.info("[RiftFirewall] policy service ready",service.summary());
