(()=>{
  const params=new URLSearchParams(location.search);
  const base={enabled:true,networkEnabled:true,httpsOnly:true,blockPrivateHosts:true,blockSensitivePorts:true,blockTrackers:true,blockHosts:[],allowHosts:[],sensitivePorts:[]};
  let supplied={};
  try{supplied=JSON.parse(params.get("fw")||"{}");}catch{}
  const policy={...base,...(supplied&&typeof supplied==="object"?supplied:{})};
  policy.blockHosts=Array.isArray(policy.blockHosts)?policy.blockHosts.map(String):[];
  policy.allowHosts=Array.isArray(policy.allowHosts)?policy.allowHosts.map(String):[];
  policy.sensitivePorts=Array.isArray(policy.sensitivePorts)?policy.sensitivePorts.map(Number).filter(Number.isFinite):[];

  function normalizeHost(value=""){
    return String(value??"").trim().toLowerCase().replace(/^\*\./,"").replace(/^\[|\]$/g,"").replace(/^\.+|\.+$/g,"");
  }
  function matches(host,rule){const h=normalizeHost(host),r=normalizeHost(rule);return !!h&&!!r&&(h===r||h.endsWith(`.${r}`));}
  function matching(host,rules){return rules.find(rule=>matches(host,rule))||"";}
  function ipv4(host){if(!/^\d{1,3}(?:\.\d{1,3}){3}$/.test(host))return null;const p=host.split(".").map(Number);return p.every(n=>n>=0&&n<=255)?p:null;}
  function privateHost(value){
    const host=normalizeHost(value);
    if(!host)return true;
    if(host==="localhost"||host.endsWith(".localhost")||host.endsWith(".local")||host.endsWith(".internal"))return true;
    const p=ipv4(host);
    if(p){const [a,b]=p;if(a===0||a===10||a===127)return true;if(a===100&&b>=64&&b<=127)return true;if(a===169&&b===254)return true;if(a===172&&b>=16&&b<=31)return true;if(a===192&&b===168)return true;if(a===198&&(b===18||b===19))return true;if(a>=224)return true;}
    if(host.includes(":")){if(host==="::"||host==="::1"||host.startsWith("fc")||host.startsWith("fd")||/^fe[89ab]/.test(host))return true;}
    return false;
  }
  function decide(host,port){
    host=normalizeHost(host);port=Number(port)||0;
    if(!policy.enabled)return {allow:true,reason:"firewall-disabled"};
    if(!policy.networkEnabled)return {allow:false,reason:"network-kill-switch"};
    if(policy.blockPrivateHosts&&privateHost(host))return {allow:false,reason:"private-host"};
    const allowRule=matching(host,policy.allowHosts),blockRule=matching(host,policy.blockHosts);
    if(blockRule&&!allowRule)return {allow:false,reason:`blocked-host:${blockRule}`};
    if(policy.httpsOnly&&port===80)return {allow:false,reason:"https-only-port-80"};
    if(policy.blockSensitivePorts&&policy.sensitivePorts.includes(port))return {allow:false,reason:`sensitive-port:${port}`};
    return {allow:true,reason:allowRule?`allowed-host:${allowRule}`:"allowed"};
  }

  class DeniedSocket extends EventTarget{
    constructor(url,reason){
      super();this.url=String(url);this.protocol="";this.extensions="";this.binaryType="arraybuffer";this.bufferedAmount=0;this.readyState=3;this.reason=reason;
      queueMicrotask(()=>{
        const error=new Event("error");
        this.dispatchEvent(error);try{this.onerror?.(error);}catch{}
        let close;try{close=new CloseEvent("close",{code:1008,reason,wasClean:false});}catch{close=new Event("close");close.code=1008;close.reason=reason;close.wasClean=false;}
        this.dispatchEvent(close);try{this.onclose?.(close);}catch{}
      });
    }
    send(){throw new DOMException(`RiftFirewall: ${this.reason}`,"SecurityError");}
    close(){}
  }
  Object.assign(DeniedSocket,{CONNECTING:0,OPEN:1,CLOSING:2,CLOSED:3});
  Object.assign(DeniedSocket.prototype,{CONNECTING:0,OPEN:1,CLOSING:2,CLOSED:3});

  const wispServer=params.get("wisp")||"ws://127.0.0.1:5001/";
  function targetFromWispURL(value){
    let raw=String(value??"");
    if(raw.startsWith(wispServer))raw=raw.slice(wispServer.length);
    const split=raw.lastIndexOf(":");
    if(split<=0)return null;
    return {host:raw.slice(0,split),port:Number(raw.slice(split+1))||0};
  }
  function report(host,port,reason){
    state.blocked++;
    state.last={at:Date.now(),host,port,reason};
    try{parent.postMessage({type:"riftbrowser:firewall-block",host,port,reason},location.origin);}catch{}
    console.warn(`[RiftFirewall] blocked ${host}:${port} (${reason})`);
  }

  const state=window.__riftFirewallTransport={policy,blocked:0,last:null,active:false};
  const client=window.wisp_client?.client;
  const NativeWisp=client?.WispWebSocket;
  if(typeof NativeWisp!=="function"){
    console.warn("[RiftFirewall] Wisp client unavailable; transport firewall not installed");
    return;
  }
  function RiftFirewallWispWebSocket(url,...args){
    const target=targetFromWispURL(url);
    if(target){
      const verdict=decide(target.host,target.port);
      if(!verdict.allow){report(target.host,target.port,verdict.reason);return new DeniedSocket(url,verdict.reason);}
    }
    return new NativeWisp(url,...args);
  }
  try{Object.setPrototypeOf(RiftFirewallWispWebSocket,NativeWisp);}catch{}
  RiftFirewallWispWebSocket.prototype=NativeWisp.prototype;
  client.WispWebSocket=RiftFirewallWispWebSocket;
  state.active=true;
  try{parent.postMessage({type:"riftbrowser:firewall-ready",policy},location.origin);}catch{}
  console.info("[RiftFirewall] Wisp transport firewall active",policy);
})();
