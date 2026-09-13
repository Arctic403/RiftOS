const core=globalThis.RiftOSCore;
const baseApps=globalThis.RiftApps;
if(!core||!baseApps)throw new Error('RiftRT requires RiftOSCore and RiftApps');

const VERSION='1.0.0';
const RUNTIME_FILE='riftrt.json';
const DATA_ROOT='/apps/data';
const externalWindows=new Map();
const sessions=new Map();
const messageInstances=new Map();
const originalWM=globalThis.RiftOSWindowManager;
const stage=document.querySelector('#stage');
const template=document.querySelector('#windowTemplate');
if(!originalWM||!stage||!template)throw new Error('RiftRT requires the RiftDesktop window host');

const esc=value=>String(value??'').replace(/[&<>"']/g,ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[ch]));
const safeId=value=>String(value||'app').replace(/[^a-z0-9._:-]/gi,'-').slice(0,96);
const sessionId=id=>`riftrt:${safeId(id)}`;
const uid=()=>globalThis.crypto?.randomUUID?.()||`rt-${Date.now()}-${Math.random().toString(36).slice(2)}`;
const num=(value,fallback=0)=>Number.isFinite(Number(value))?Number(value):fallback;
const clamp=(value,min,max)=>Math.min(max,Math.max(min,value));

function injectStyles(){
  if(document.querySelector('#riftRTStyle'))return;
  const style=document.createElement('style');style.id='riftRTStyle';style.textContent=`
    .riftrt-host{height:100%;min-height:0;display:grid;grid-template-rows:auto minmax(0,1fr);background:#080d12;color:#eef5fa}
    .riftrt-toolbar{min-height:36px;display:flex;align-items:center;gap:7px;padding:6px 8px;border-bottom:1px solid #26313d;background:#0d151d}.riftrt-toolbar strong{font-size:11px}.riftrt-toolbar small{color:#8191a2;font-size:9px}.riftrt-toolbar .grow{flex:1}.riftrt-chip{padding:4px 7px;border:1px solid #31404e;border-radius:999px;color:#8ea0b1;font-size:8px;letter-spacing:.08em}.riftrt-chip.ok{border-color:#315b4c;color:#78f6c7}
    .riftrt-surface-wrap{position:relative;min-height:0;overflow:hidden;background:#05090d}.riftrt-canvas{display:block;width:100%;height:100%;touch-action:none;outline:none}.riftrt-frame{display:block;width:100%;height:100%;border:0;background:#0b1017}
    .riftrt-manager{height:100%;overflow:auto;padding:12px;background:#080d12}.riftrt-hero{display:grid;grid-template-columns:1fr auto;gap:12px;padding:14px;border:1px solid #283543;border-radius:10px;background:#0e1720}.riftrt-hero h2{margin:0;font-size:18px}.riftrt-hero p{margin:6px 0 0;color:#8fa0b1;font-size:11px;line-height:1.45}.riftrt-actions{display:flex;gap:7px;align-items:start;flex-wrap:wrap}.riftrt-btn{border:1px solid #344454;background:#15212c;color:#edf5fa;border-radius:7px;padding:8px 10px;font-size:10px;font-weight:700}.riftrt-btn.primary{border-color:#386451;background:#133026;color:#8cffcf}.riftrt-btn.danger{color:#ff9c9c}.riftrt-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:8px;margin-top:10px}.riftrt-card{border:1px solid #263441;border-radius:9px;background:#0c141c;padding:11px;display:flex;flex-direction:column;gap:8px}.riftrt-card header{display:flex;gap:8px;align-items:center}.riftrt-icon{width:35px;height:35px;border-radius:8px;display:grid;place-items:center;background:#142531;color:#78f6c7;font-weight:800}.riftrt-card strong,.riftrt-card small{display:block}.riftrt-card small{color:#8192a3;font-size:8px}.riftrt-card p{margin:0;color:#98a7b5;font-size:10px;line-height:1.4;min-height:28px}.riftrt-card footer{display:flex;gap:6px;margin-top:auto}.riftrt-capabilities{display:flex;gap:5px;flex-wrap:wrap;margin-top:10px}.riftrt-log{position:absolute;left:8px;bottom:8px;max-width:min(520px,80%);max-height:34%;overflow:auto;margin:0;padding:7px 9px;border:1px solid #263744;border-radius:6px;background:#071018d9;color:#92a7b7;font:9px ui-monospace,monospace;white-space:pre-wrap;pointer-events:none}.riftrt-empty{padding:22px;text-align:center;color:#8192a3}
  `;document.head.append(style);
}
injectStyles();

function parseRuntime(app){
  let spec=null;
  try{if(app?.files?.[RUNTIME_FILE])spec=JSON.parse(app.files[RUNTIME_FILE]);}catch(error){console.warn('[RiftRT] invalid riftrt.json',app?.id,error);}
  if(!spec)return{engine:'iframe',entry:app?.manifest?.entry||'index.html',capabilities:[]};
  const engine=String(spec.engine||'worker-js').toLowerCase();
  return{
    engine:['worker-js','wasm-base64','iframe','native-arm64'].includes(engine)?engine:'worker-js',
    entry:String(spec.entry||spec.main||app?.manifest?.entry||'main.js').replace(/^\/+/,''),
    capabilities:[...new Set(Array.isArray(spec.capabilities)?spec.capabilities.map(String):[])],
    fps:clamp(num(spec.fps,60),1,120),
    window:spec.window&&typeof spec.window==='object'?spec.window:{},
    abi:String(spec.abi||'riftrt-1')
  };
}

function wmRecord(record){return{id:record.id,title:record.title,pid:record.process?.pid,minimized:record.win.classList.contains('rift-minimized'),window:record.win,win:record.win,process:record.process,runtime:true};}
function findExternal(target){
  if(!target)return null;
  if(typeof target==='string')return externalWindows.get(target)||null;
  const win=target.closest?.('.window')||target;
  for(const record of externalWindows.values())if(record.win===win)return record;
  return null;
}
function focusExternal(target){
  const record=findExternal(target);if(!record)return false;
  record.win.classList.remove('rift-minimized');stage.classList.remove('hidden');
  record.lastFocus=Date.now();
  globalThis.dispatchEvent(new CustomEvent('riftos:window-activate',{detail:{id:record.id,window:record.win,pid:record.process?.pid}}));
  return true;
}
function closeExternal(target,{fromProcess=false}={}){
  const record=findExternal(target);if(!record)return false;
  externalWindows.delete(record.id);
  const session=sessions.get(record.id);if(session){sessions.delete(record.id);try{session.dispose?.();}catch(error){console.warn('[RiftRT] dispose failed',error);}}
  for(const [token,instance] of [...messageInstances])if(instance.record===record){instance.urls?.forEach(URL.revokeObjectURL);messageInstances.delete(token);}
  record.win.remove();
  if(!fromProcess&&record.process?.pid)core.kernel.kill(record.process.pid);
  globalThis.dispatchEvent(new CustomEvent('riftos:window-close',{detail:{id:record.id,pid:record.process?.pid}}));
  return true;
}
function showDesktop(){
  for(const record of externalWindows.values())record.win.classList.add('rift-minimized');
  originalWM.showDesktop?.();
  globalThis.dispatchEvent(new Event('riftos:show-desktop'));
}
function createWindow(id,title,kicker='RIFTRT'){
  const existing=externalWindows.get(id);if(existing){focusExternal(existing);return{record:existing,body:existing.win.querySelector('.window-body')};}
  const win=template.content.firstElementChild.cloneNode(true);win.dataset.app=id;win.dataset.windowId=`${id}-${uid()}`;
  win.querySelector('.window-title').textContent=title;win.querySelector('.window-kicker').textContent=kicker;
  const record={id,title,win,lastFocus:Date.now(),process:null};
  const process=core.kernel.launchProcess(id,title,{kind:'riftrt',runtime:'RiftRT',onTerminate:()=>closeExternal(record,{fromProcess:true})});record.process=process;
  externalWindows.set(id,record);
  win.querySelector('.window-close').onclick=()=>closeExternal(record);
  stage.classList.remove('hidden');stage.append(win);
  globalThis.dispatchEvent(new CustomEvent('riftos:window-open',{detail:{id,window:win,pid:process.pid,title}}));
  return{record,body:win.querySelector('.window-body')};
}

// Extend the existing RiftOS window manager instead of creating a second desktop.
globalThis.RiftOSWindowManager=Object.freeze({
  ...originalWM,
  list:()=>[...originalWM.list(),...externalWindows.values()].map(item=>item.win?wmRecord(item):item),
  get:id=>externalWindows.has(id)?externalWindows.get(id):originalWM.get(id),
  focus:target=>findExternal(target)?focusExternal(target):originalWM.focus(target),
  close:target=>findExternal(target)?closeExternal(target):originalWM.close(target),
  showDesktop
});

async function capability(app,cap){
  if(!cap)return true;
  const spec=parseRuntime(app);if(!spec.capabilities.includes(cap))throw new Error(`${cap} is not declared in riftrt.json`);
  if(await core.permissions.has(app.id,cap))return true;
  return core.permissions.request(app.id,cap,app.manifest.name);
}
async function appStorage(app){return core.fs.readJSON(`${DATA_ROOT}/${app.id}/riftrt-storage.json`,{});}
async function storageGet(app,key){return(await appStorage(app))[String(key)]??null;}
async function storageSet(app,key,value){const path=`${DATA_ROOT}/${app.id}`;await core.fs.mkdir(path);const data=await appStorage(app);data[String(key).slice(0,160)]=value;const encoded=JSON.stringify(data);if(encoded.length>1024*1024)throw new Error('RiftRT app storage exceeds 1 MB');await core.fs.writeJSON(`${path}/riftrt-storage.json`,data);return true;}
async function storageRemove(app,key){const path=`${DATA_ROOT}/${app.id}`;await core.fs.mkdir(path);const data=await appStorage(app);delete data[String(key)];await core.fs.writeJSON(`${path}/riftrt-storage.json`,data);return true;}

async function hostCall(app,method,args={}){
  if(method==='storage.get')return storageGet(app,args.key);
  if(method==='storage.set')return storageSet(app,args.key,args.value);
  if(method==='storage.remove')return storageRemove(app,args.key);
  if(method==='fs.readText'){if(!(await capability(app,'fs.read')))throw new Error('fs.read denied');return core.fs.readText(String(args.path||'/home'));}
  if(method==='fs.writeText'){if(!(await capability(app,'fs.write')))throw new Error('fs.write denied');return core.fs.writeText(String(args.path||'/home/riftrt.txt'),String(args.text??''));}
  if(method==='fs.list'){if(!(await capability(app,'fs.read')))throw new Error('fs.read denied');return core.fs.list(String(args.path||'/'),{recursive:args.recursive===true});}
  if(method==='clipboard.read'){if(!(await capability(app,'clipboard.read')))throw new Error('clipboard.read denied');return core.native.call('clipboard.readText',{});}
  if(method==='clipboard.write'){if(!(await capability(app,'clipboard.write')))throw new Error('clipboard.write denied');return core.native.call('clipboard.writeText',{text:String(args.text??'')});}
  if(method==='share'){if(!(await capability(app,'share')))throw new Error('share denied');return core.native.call('share.text',{text:String(args.text??''),title:app.manifest.name});}
  throw new Error(`Unsupported RiftRT host call: ${method}`);
}

function materializeAsset(app,path,urls){
  const clean=String(path||'').replace(/^\.\//,'').replace(/^\//,'').split(/[?#]/)[0];if(!(clean in app.files))return path;
  const ext=clean.split('.').pop()?.toLowerCase(),types={js:'text/javascript',mjs:'text/javascript',css:'text/css',json:'application/json',svg:'image/svg+xml',png:'image/png',jpg:'image/jpeg',jpeg:'image/jpeg',webp:'image/webp',txt:'text/plain'};
  const url=URL.createObjectURL(new Blob([app.files[clean]],{type:types[ext]||'text/plain'}));urls.push(url);return url;
}
function iframeHtml(app,token,urls){
  let html=String(app.files[app.manifest.entry]||'');
  html=html.replace(/\b(src|href)=(['"])([^'"]+)\2/gi,(m,attr,q,value)=>`${attr}=${q}${materializeAsset(app,value,urls)}${q}`);
  const network=app.manifest.permissions?.includes('network');
  const policy=`default-src 'none'; script-src 'unsafe-inline' blob:; style-src 'unsafe-inline' blob:; img-src data: blob:${network?' https: http:':''}; font-src data: blob:${network?' https: http:':''}; connect-src ${network?'https: http:':"'none'"}; media-src data: blob:${network?' https: http:':''}; frame-src 'none'`;
  const bridge=`<script>(()=>{const TOKEN=${JSON.stringify(token)};let seq=0;const pending=new Map();const call=(method,args={})=>new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});parent.postMessage({__riftrt:true,token:TOKEN,id,method,args},'*')});addEventListener('message',event=>{const msg=event.data;if(!msg||msg.__riftrtHost!==true||msg.token!==TOKEN)return;const p=pending.get(msg.id);if(!p)return;pending.delete(msg.id);msg.ok?p.resolve(msg.value):p.reject(new Error(msg.error||'RiftRT host error'))});Object.defineProperty(window,'Rift',{value:Object.freeze({version:${JSON.stringify(VERSION)},app:Object.freeze({close:()=>call('app.close'),setTitle:title=>call('window.title',{title})}),storage:Object.freeze({get:key=>call('storage.get',{key}),set:(key,value)=>call('storage.set',{key,value}),remove:key=>call('storage.remove',{key})}),fs:Object.freeze({readText:path=>call('fs.readText',{path}),writeText:(path,text)=>call('fs.writeText',{path,text}),list:path=>call('fs.list',{path})}),clipboard:Object.freeze({readText:()=>call('clipboard.read'),writeText:text=>call('clipboard.write',{text})})}),writable:false});parent.postMessage({__riftrt:true,token:TOKEN,method:'app.ready',args:{}},'*')})();<\/script>`;
  const meta=`<meta http-equiv="Content-Security-Policy" content="${policy.replace(/"/g,'&quot;')}">`;
  if(/<head[^>]*>/i.test(html))html=html.replace(/<head([^>]*)>/i,`<head$1>${meta}${bridge}`);else html=meta+bridge+html;
  return html;
}

async function launchIframe(app,record,body){
  const token=uid(),urls=[];body.innerHTML=`<div class="riftrt-host"><div class="riftrt-toolbar"><strong>${esc(app.manifest.name)}</strong><span class="riftrt-chip">IFRAME COMPAT</span><span class="grow"></span><small>RiftRT ${VERSION}</small></div><div class="riftrt-surface-wrap"><iframe class="riftrt-frame" sandbox="allow-scripts allow-forms allow-modals" title="${esc(app.manifest.name)}"></iframe></div></div>`;
  const frame=body.querySelector('iframe');messageInstances.set(token,{app,record,frame,urls});frame.srcdoc=iframeHtml(app,token,urls);
  return()=>{urls.forEach(URL.revokeObjectURL);messageInstances.delete(token);};
}

const workerBootstrap=`
let inputHandler=()=>{},resizeHandler=()=>{};let seq=0;const pending=new Map();
const rpc=(method,args={})=>new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});postMessage({type:'rpc',id,method,args})});
try{self.XMLHttpRequest=undefined;self.WebSocket=undefined;self.EventSource=undefined;self.importScripts=undefined}catch(_){}
self.Rift=Object.freeze({version:'${VERSION}',log:(...values)=>postMessage({type:'log',values:values.map(String)}),window:Object.freeze({setTitle:title=>postMessage({type:'title',title:String(title)})}),surface:Object.freeze({frame:commands=>postMessage({type:'frame',commands:Array.isArray(commands)?commands:[]}),onResize:fn=>{resizeHandler=typeof fn==='function'?fn:()=>{}}}),input:Object.freeze({on:fn=>{inputHandler=typeof fn==='function'?fn:()=>{}}}),storage:Object.freeze({get:key=>rpc('storage.get',{key}),set:(key,value)=>rpc('storage.set',{key,value}),remove:key=>rpc('storage.remove',{key})}),fs:Object.freeze({readText:path=>rpc('fs.readText',{path}),writeText:(path,text)=>rpc('fs.writeText',{path,text}),list:path=>rpc('fs.list',{path})}),clipboard:Object.freeze({readText:()=>rpc('clipboard.read'),writeText:text=>rpc('clipboard.write',{text})})});
self.onmessage=event=>{const msg=event.data||{};if(msg.type==='input')try{inputHandler(msg.event)}catch(error){Rift.log(error.stack||error.message)}else if(msg.type==='resize')try{resizeHandler(msg)}catch(error){Rift.log(error.stack||error.message)}else if(msg.type==='rpc-result'){const p=pending.get(msg.id);if(p){pending.delete(msg.id);msg.ok?p.resolve(msg.value):p.reject(new Error(msg.error||'RiftRT host error'))}}};
`;
function fitCanvas(canvas){const rect=canvas.getBoundingClientRect(),scale=Math.min(devicePixelRatio||1,2);const w=Math.max(1,Math.round(rect.width*scale)),h=Math.max(1,Math.round(rect.height*scale));if(canvas.width!==w)canvas.width=w;if(canvas.height!==h)canvas.height=h;return{width:w,height:h,cssWidth:rect.width,cssHeight:rect.height,scale};}
function drawCommands(canvas,commands){
  const ctx=canvas.getContext('2d',{alpha:false});if(!ctx)return;
  for(const cmd of Array.isArray(commands)?commands:[]){const op=String(cmd?.op||'');
    if(op==='clear'){ctx.save();ctx.fillStyle=String(cmd.color||'#05090d');ctx.fillRect(0,0,canvas.width,canvas.height);ctx.restore();}
    else if(op==='rect'){ctx.save();ctx.fillStyle=String(cmd.color||'#78f6c7');ctx.fillRect(num(cmd.x),num(cmd.y),num(cmd.w),num(cmd.h));ctx.restore();}
    else if(op==='line'){ctx.save();ctx.strokeStyle=String(cmd.color||'#ffffff');ctx.lineWidth=clamp(num(cmd.width,1),.5,20);ctx.beginPath();ctx.moveTo(num(cmd.x1),num(cmd.y1));ctx.lineTo(num(cmd.x2),num(cmd.y2));ctx.stroke();ctx.restore();}
    else if(op==='text'){ctx.save();ctx.fillStyle=String(cmd.color||'#eef5fa');ctx.font=`${clamp(num(cmd.size,14),7,96)}px ui-sans-serif,system-ui`;ctx.textAlign=['left','center','right'].includes(cmd.align)?cmd.align:'left';ctx.fillText(String(cmd.text??'').slice(0,500),num(cmd.x),num(cmd.y));ctx.restore();}
  }
}
function normalizedInput(event,canvas){const rect=canvas.getBoundingClientRect();return{type:event.type,x:(event.clientX-rect.left)*(canvas.width/Math.max(1,rect.width)),y:(event.clientY-rect.top)*(canvas.height/Math.max(1,rect.height)),button:num(event.button),buttons:num(event.buttons),deltaX:num(event.deltaX),deltaY:num(event.deltaY),key:event.key||'',code:event.code||'',ctrl:!!event.ctrlKey,alt:!!event.altKey,shift:!!event.shiftKey,meta:!!event.metaKey,time:performance.now()};}

async function launchWorker(app,spec,record,body){
  const source=app.files[spec.entry];if(typeof source!=='string')throw new Error(`RiftRT worker entry not found: ${spec.entry}`);
  body.innerHTML=`<div class="riftrt-host"><div class="riftrt-toolbar"><strong>${esc(app.manifest.name)}</strong><span class="riftrt-chip ok">WORKER</span><span class="riftrt-chip">CANVAS GPU PATH</span><span class="grow"></span><small>ABI ${esc(spec.abi)}</small></div><div class="riftrt-surface-wrap"><canvas class="riftrt-canvas" tabindex="0"></canvas><pre class="riftrt-log"></pre></div></div>`;
  const canvas=body.querySelector('canvas'),log=body.querySelector('.riftrt-log');const blob=URL.createObjectURL(new Blob([workerBootstrap,'\n',source],{type:'text/javascript'}));const worker=new Worker(blob);
  const logLine=value=>{log.textContent=(log.textContent+'\n'+String(value)).trim().split('\n').slice(-10).join('\n');};
  worker.onmessage=async event=>{const msg=event.data||{};if(msg.type==='frame')drawCommands(canvas,msg.commands);else if(msg.type==='title'){record.title=String(msg.title||app.manifest.name).slice(0,80);record.win.querySelector('.window-title').textContent=record.title;}else if(msg.type==='log')logLine((msg.values||[]).join(' '));else if(msg.type==='rpc'){try{worker.postMessage({type:'rpc-result',id:msg.id,ok:true,value:await hostCall(app,msg.method,msg.args||{})});}catch(error){worker.postMessage({type:'rpc-result',id:msg.id,ok:false,error:String(error?.message||error)});}}};
  worker.onerror=event=>logLine(`worker error: ${event.message||'unknown'}`);
  const resize=()=>{const s=fitCanvas(canvas);worker.postMessage({type:'resize',...s});};
  const ro=globalThis.ResizeObserver?new ResizeObserver(resize):null;ro?.observe(canvas);globalThis.addEventListener('resize',resize);setTimeout(resize,0);
  for(const type of ['pointerdown','pointerup','pointermove','wheel','keydown','keyup'])canvas.addEventListener(type,event=>{if(type==='pointerdown')canvas.focus();worker.postMessage({type:'input',event:normalizedInput(event,canvas)});if(type==='wheel')event.preventDefault();},{passive:false});
  return()=>{ro?.disconnect();globalThis.removeEventListener('resize',resize);worker.terminate();URL.revokeObjectURL(blob);};
}

function decodeBase64(raw){const clean=String(raw||'').replace(/\s+/g,'');const binary=atob(clean);const bytes=new Uint8Array(binary.length);for(let i=0;i<binary.length;i++)bytes[i]=binary.charCodeAt(i);return bytes;}
async function launchWasm(app,spec,record,body){
  if(typeof WebAssembly==='undefined')throw new Error('WebAssembly is unavailable in this Android System WebView');
  const encoded=app.files[spec.entry];if(typeof encoded!=='string')throw new Error(`RiftRT WASM entry not found: ${spec.entry}`);
  body.innerHTML=`<div class="riftrt-host"><div class="riftrt-toolbar"><strong>${esc(app.manifest.name)}</strong><span class="riftrt-chip ok">WASM</span><span class="riftrt-chip">RIFT ABI</span><span class="grow"></span><small>ABI ${esc(spec.abi)}</small></div><div class="riftrt-surface-wrap"><canvas class="riftrt-canvas" tabindex="0"></canvas><pre class="riftrt-log">Loading WASM…</pre></div></div>`;
  const canvas=body.querySelector('canvas'),log=body.querySelector('.riftrt-log');let size=fitCanvas(canvas),instance=null,stopped=false;
  const imports={env:{rift_width:()=>size.width|0,rift_height:()=>size.height|0,rift_log_i32:value=>{log.textContent=`WASM log: ${value|0}`;}}};
  const result=await WebAssembly.instantiate(decodeBase64(encoded),imports);instance=result.instance;const ex=instance.exports;log.textContent=`WASM running · exports: ${Object.keys(ex).join(', ')||'(none)'}`;
  try{if(typeof ex.rift_init==='function')ex.rift_init(size.width|0,size.height|0);else if(typeof ex._start==='function')ex._start();}catch(error){log.textContent=`WASM init error: ${error.message}`;}
  const decoder=new TextDecoder();
  const frame=now=>{if(stopped)return;try{if(typeof ex.rift_tick==='function')ex.rift_tick(now|0);if(ex.memory&&typeof ex.rift_frame==='function'&&typeof ex.rift_frame_len==='function'){const ptr=ex.rift_frame()>>>0,len=clamp(ex.rift_frame_len()>>>0,0,1024*1024);if(ptr+len<=ex.memory.buffer.byteLength){const json=decoder.decode(new Uint8Array(ex.memory.buffer,ptr,len));drawCommands(canvas,JSON.parse(json));}}}catch(error){log.textContent=`WASM frame error: ${error.message}`;}requestAnimationFrame(frame);};requestAnimationFrame(frame);
  const resize=()=>{size=fitCanvas(canvas);try{ex.rift_resize?.(size.width|0,size.height|0);}catch(_){}};const ro=globalThis.ResizeObserver?new ResizeObserver(resize):null;ro?.observe(canvas);
  for(const type of ['pointerdown','pointerup','pointermove','wheel','keydown','keyup'])canvas.addEventListener(type,event=>{if(type==='pointerdown')canvas.focus();const e=normalizedInput(event,canvas);try{if(type.startsWith('pointer')&&typeof ex.rift_pointer==='function')ex.rift_pointer(type==='pointerdown'?1:type==='pointerup'?2:0,e.x|0,e.y|0,e.button|0,e.buttons|0);else if((type==='keydown'||type==='keyup')&&typeof ex.rift_key==='function')ex.rift_key((event.keyCode||0)|0,type==='keydown'?1:0);else if(type==='wheel'&&typeof ex.rift_wheel==='function')ex.rift_wheel(e.deltaX|0,e.deltaY|0);}catch(_){}});
  return()=>{stopped=true;ro?.disconnect();instance=null;};
}

async function launchNative(app,spec,record,body){
  body.innerHTML=`<div class="riftrt-manager"><div class="riftrt-hero"><div><h2>Native ARM64 slot</h2><p>RiftRT recognizes this package as a native target, but Android 10+ does not permit RiftOS to execute arbitrary downloaded ELF code from writable app storage. RiftRT native modules must be compiled and packaged with the APK/approved plugin path. This keeps the runtime fast without creating a dynamic-code security hole.</p></div></div><div class="riftrt-capabilities"><span class="riftrt-chip ok">ARM64 DESIGN</span><span class="riftrt-chip">PACKAGED PLUGIN</span><span class="riftrt-chip">RIFT WINDOW ABI</span></div></div>`;
  return()=>{};
}

async function launch(appId){
  await core.ready;const app=await baseApps.get(appId);if(!app)throw new Error(`Rift app not installed: ${appId}`);const id=sessionId(app.id);if(externalWindows.has(id)){focusExternal(id);return sessions.get(id);}
  const spec=parseRuntime(app),created=createWindow(id,app.manifest.name,`RIFTRT · ${spec.engine.toUpperCase()}`),record=created.record,body=created.body;body.style.padding='0';record.process.kind=`riftrt-${spec.engine}`;record.process.appId=app.id;record.process.runtimeEngine=spec.engine;
  let dispose=()=>{};try{if(spec.engine==='worker-js')dispose=await launchWorker(app,spec,record,body);else if(spec.engine==='wasm-base64')dispose=await launchWasm(app,spec,record,body);else if(spec.engine==='native-arm64')dispose=await launchNative(app,spec,record,body);else dispose=await launchIframe(app,record,body);}catch(error){body.innerHTML=`<div class="riftrt-manager"><div class="riftrt-card"><strong>RiftRT launch failed</strong><p>${esc(error.message||error)}</p></div></div>`;throw error;}
  const session={id,appId:app.id,engine:spec.engine,record,dispose};sessions.set(id,session);return session;
}

async function runtimeCapabilities(){
  const canvas=document.createElement('canvas');let webgl2=false;try{webgl2=!!canvas.getContext('webgl2');}catch(_){}
  let device={};try{device=await core.native.call('device.info',{});}catch(_){}
  return{version:VERSION,platform:'android',device,worker:typeof Worker!=='undefined',wasm:typeof WebAssembly!=='undefined',webgl2,canvas2d:!!canvas.getContext('2d'),hardwareConcurrency:navigator.hardwareConcurrency||1,nativeArm64:'packaged-plugin-only',windowHost:true,riftfs:true};
}

const demoPackage={
  format:'rift-app-v1',
  manifest:{id:'demo.riftrt.canvas',name:'RiftRT Canvas Demo',version:'1.0.0',entry:'index.html',icon:'RT',description:'Worker-isolated RiftRT desktop app with a host-owned canvas surface.',permissions:['storage']},
  files:{
    'index.html':'<!doctype html><meta charset="utf-8"><title>RiftRT package</title>',
    'riftrt.json':JSON.stringify({engine:'worker-js',entry:'main.js',abi:'riftrt-1',fps:30,capabilities:[],window:{width:720,height:480}},null,2),
    'main.js':`let w=800,h=500,px=180,py=180,t=0;Rift.surface.onResize(e=>{w=e.width;h=e.height});Rift.input.on(e=>{if(e.type==='pointerdown'||(e.type==='pointermove'&&e.buttons)){px=e.x;py=e.y}});Rift.log('RiftRT worker online');setInterval(()=>{t+=.045;const x=Math.max(30,Math.min(w-30,px+Math.cos(t)*38)),y=Math.max(40,Math.min(h-30,py+Math.sin(t*1.3)*26));Rift.surface.frame([{op:'clear',color:'#071018'},{op:'text',text:'RiftRT v1 · Worker desktop runtime',x:24,y:34,size:18,color:'#78f6c7'},{op:'text',text:'Tap or drag anywhere in this window',x:24,y:56,size:11,color:'#8da2b2'},{op:'rect',x:x-24,y:y-24,w:48,h:48,color:'#78f6c7'},{op:'line',x1:24,y1:h-36,x2:w-24,y2:h-36,width:1,color:'#29404c'},{op:'text',text:'RiftOS owns this window, input, process and surface.',x:24,y:h-16,size:10,color:'#6f8798'}])},33);`
  }
};

async function installDemo(){const app=await baseApps.installPackageObject(demoPackage);await refreshLauncher();return app;}

async function openManager(){
  const id='riftrt:manager',created=createWindow(id,'RiftRT','RUNTIME MANAGER'),body=created.body;body.style.padding='0';const [apps,caps]=await Promise.all([baseApps.list(),runtimeCapabilities()]);
  body.innerHTML=`<div class="riftrt-manager"><section class="riftrt-hero"><div><h2>RiftRT ${VERSION}</h2><p>RiftOS-native desktop application runtime. Apps stay inside RiftDesktop windows and can target sandboxed HTML, Worker + canvas, or the Rift WASM ABI without carrying a second Linux desktop.</p></div><div class="riftrt-actions"><button class="riftrt-btn primary" id="riftrtDemo">Install RiftRT demo</button><label class="riftrt-btn">Import .rift<input type="file" id="riftrtImport" accept=".rift,*/*" hidden></label></div></section><div class="riftrt-capabilities"><span class="riftrt-chip ${caps.worker?'ok':''}">WORKER ${caps.worker?'ON':'OFF'}</span><span class="riftrt-chip ${caps.wasm?'ok':''}">WASM ${caps.wasm?'ON':'OFF'}</span><span class="riftrt-chip ${caps.webgl2?'ok':''}">WEBGL2 ${caps.webgl2?'ON':'OFF'}</span><span class="riftrt-chip ok">RIFTFS</span><span class="riftrt-chip">${esc(caps.device?.manufacturer||'Android')} ${esc(caps.device?.model||'')}</span><span class="riftrt-chip">${caps.hardwareConcurrency} CPU THREADS</span></div><div class="riftrt-grid">${apps.length?apps.map(app=>{const spec=parseRuntime(app);return`<article class="riftrt-card"><header><div class="riftrt-icon">${esc(app.manifest.icon||'R')}</div><div><strong>${esc(app.manifest.name)}</strong><small>${esc(app.id)} · ${esc(spec.engine)}</small></div></header><p>${esc(app.manifest.description||'Installed Rift application')}</p><footer><button class="riftrt-btn primary" data-rt-launch="${esc(app.id)}">Open</button><button class="riftrt-btn danger" data-rt-remove="${esc(app.id)}">Remove</button></footer></article>`}).join(''):`<div class="riftrt-empty">No Rift apps installed.</div>`}</div></div>`;
  body.querySelector('#riftrtDemo').onclick=async()=>{await installDemo();await openManager();};
  body.querySelector('#riftrtImport').onchange=async event=>{try{await baseApps.installPackageFile(event.target.files?.[0]);await refreshLauncher();await openManager();}catch(error){alert(`Install failed: ${error.message}`);}};
  body.querySelectorAll('[data-rt-launch]').forEach(button=>button.onclick=()=>launch(button.dataset.rtLaunch).catch(error=>alert(error.message)));
  body.querySelectorAll('[data-rt-remove]').forEach(button=>button.onclick=async()=>{const appId=button.dataset.rtRemove;closeExternal(sessionId(appId));await baseApps.remove(appId);await refreshLauncher();await openManager();});
  return created.record;
}

async function refreshLauncher(){
  await baseApps.refreshLauncher?.();const grid=document.querySelector('#appGrid');if(!grid)return;
  const manager=grid.querySelector('[data-rift-app-manager]');if(manager){manager.innerHTML='<span class="app-icon">RT</span><span><strong>RiftRT</strong><br><small>Desktop application runtime</small></span>';manager.title='RiftRT application manager';}
}

// Capture old RiftApps launcher handlers and route them into real desktop windows.
document.addEventListener('click',event=>{
  const appButton=event.target.closest?.('[data-rift-installed-app]');if(appButton){event.preventDefault();event.stopImmediatePropagation();launch(appButton.dataset.riftInstalledApp).catch(error=>alert(error.message));return;}
  const manager=event.target.closest?.('[data-rift-app-manager]');if(manager){event.preventDefault();event.stopImmediatePropagation();openManager().catch(error=>alert(error.message));}
},true);

globalThis.addEventListener('message',async event=>{
  const msg=event.data;if(!msg||msg.__riftrt!==true||!msg.token)return;const instance=messageInstances.get(msg.token);if(!instance||event.source!==instance.frame.contentWindow)return;
  if(msg.method==='app.ready')return;const reply=(ok,value,error)=>instance.frame.contentWindow?.postMessage({__riftrtHost:true,token:msg.token,id:msg.id,ok,value,error},'*');
  try{let value=null;if(msg.method==='app.close'){closeExternal(instance.record);value=true;}else if(msg.method==='window.title'){instance.record.title=String(msg.args?.title||instance.app.manifest.name).slice(0,80);instance.record.win.querySelector('.window-title').textContent=instance.record.title;value=true;}else value=await hostCall(instance.app,msg.method,msg.args||{});reply(true,value,null);}catch(error){reply(false,null,String(error?.message||error));}
});

globalThis.addEventListener('riftos:launcher-ready',()=>refreshLauncher().catch(console.error));
setTimeout(()=>refreshLauncher().catch(console.error),0);

globalThis.RiftRT=Object.freeze({version:VERSION,launch,openManager,installDemo,capabilities:runtimeCapabilities,listSessions:()=>[...sessions.values()].map(s=>({id:s.id,appId:s.appId,engine:s.engine,pid:s.record.process?.pid,minimized:s.record.win.classList.contains('rift-minimized')})),close:id=>closeExternal(id.startsWith?.('riftrt:')?id:sessionId(id))});
console.info(`[RiftRT] ${VERSION} desktop runtime online`);
