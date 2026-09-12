const root=document.documentElement;
const os=document.querySelector('#os');
const stage=document.querySelector('#stage');
const workspace=document.querySelector('#workspace');
const dock=document.querySelector('.dock');
const statusbar=document.querySelector('.statusbar');
if(!os||!stage||!workspace||!dock||!statusbar)throw new Error('RiftDesktop requires the RiftOS shell DOM');

const STATE_KEY='rift.desktop.mode';
const GEOMETRY_KEY=app=>`rift.desktop.geometry.${app||'app'}`;
const ICON_GEOMETRY_KEY='rift.desktop.icon.geometry';
const WALLPAPER_KEY='rift.desktop.wallpaper';
const MIN_W=300,MIN_H=220;
const inputState={mouse:false,keyboard:false,virtualMouse:false};
let desktopPreference='auto';
let desktopWallpaper='';
async function restoreDesktopSettings(){
  try{
    const data=await core?.fs?.readJSON?.('/system/settings/desktop.json',null);
    if(data?.wallpaper!==undefined)desktopWallpaper=String(data.wallpaper||'');
    if(data?.mode==='auto'||data?.mode==='on'||data?.mode==='off')desktopPreference=data.mode;
  }catch(_){ }
}
let activeWindow=null,zCounter=100;
let cursorX=Math.max(24,innerWidth*.5),cursorY=Math.max(24,innerHeight*.5);
let lastTap={time:0,x:0,y:0};
const pointers=new Map();
let gesture=null;

const homeButton=dock.querySelector('.dock-btn[data-open="home"]');
if(homeButton){homeButton.firstChild.textContent='⊞';const label=homeButton.querySelector('span');if(label)label.textContent='Start';homeButton.title='Start';}

const taskbarOpen=document.createElement('div');
taskbarOpen.className='rift-taskbar-open';
const taskbarSpacer=document.createElement('div');
taskbarSpacer.className='rift-taskbar-spacer';
const tray=document.createElement('div');
tray.className='rift-taskbar-tray';
tray.innerHTML=`<button type="button" id="riftMouseToggle" title="Use the full screen as a touch trackpad">Mouse</button><button type="button" id="riftDesktopToggle" title="Desktop mode">Desktop</button><span class="rift-tray-clock" id="riftTrayClock"></span><button type="button" class="rift-show-desktop" id="riftShowDesktop" title="Show desktop" aria-label="Show desktop"></button>`;
dock.append(taskbarOpen,taskbarSpacer,tray);

const startMenu=document.createElement('section');
startMenu.id='riftStartMenu';
startMenu.setAttribute('aria-label','Start menu');
startMenu.innerHTML=`<div class="rift-start-head"><strong>RiftOS</strong><span>Apps</span></div><div class="rift-start-grid">
<button data-open="files"><b>▣</b><span>Files</span></button><button data-open="workspace-live"><b>◈</b><span>Workspace Live</span></button><button data-open="terminal"><b>&gt;_</b><span>RiftShell</span></button><button data-open="browser"><b>◎</b><span>RiftBrowser</span></button><button data-open="editor"><b>{}</b><span>Editor</span></button><button data-open="tasks"><b>≡</b><span>Task Manager</span></button><button data-open="settings"><b>⚙</b><span>Settings</span></button></div>`;
os.append(startMenu);

const cursor=document.createElement('div');
cursor.id='riftVirtualCursor';cursor.setAttribute('aria-hidden','true');cursor.innerHTML='<i></i>';document.body.append(cursor);
const trackpad=document.createElement('div');
trackpad.id='riftTrackpadOverlay';trackpad.setAttribute('aria-label','Full-screen RiftOS touch trackpad');document.body.append(trackpad);

const clamp=(value,min,max)=>Math.min(max,Math.max(min,value));
const wm=()=>window.RiftOSWindowManager;
const allWindows=()=>[...stage.querySelectorAll('.window.rift-desktop-window')];
const autoDesktop=()=>innerWidth>=720||(innerWidth>innerHeight&&innerWidth>=600);
const desktopEnabled=()=>desktopPreference==='on'||(desktopPreference==='auto'&&autoDesktop());

function updateClock(){const el=tray.querySelector('#riftTrayClock');if(!el)return;const now=new Date();el.innerHTML=`<b>${now.toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'})}</b><small>${now.toLocaleDateString([],{month:'numeric',day:'numeric',year:'2-digit'})}</small>`;}
setInterval(updateClock,1000);updateClock();

function desktopBounds(){const rect=stage.getBoundingClientRect();return{width:rect.width||innerWidth,height:rect.height||innerHeight};}
function defaultGeometry(win){const bounds=desktopBounds();const count=allWindows().indexOf(win);const width=clamp(Math.round(bounds.width*.68),MIN_W,Math.max(MIN_W,bounds.width-30));const height=clamp(Math.round(bounds.height*.72),MIN_H,Math.max(MIN_H,bounds.height-30));const offset=Math.max(0,count)*24;return{left:clamp(34+offset,4,Math.max(4,bounds.width-width-4)),top:clamp(30+offset,4,Math.max(4,bounds.height-height-4)),width,height};}
function readGeometry(win){return defaultGeometry(win);}
async function readStoredGeometry(win){
  try{
    const data=await core?.fs?.readJSON?.('/system/settings/desktop.json',{})||{};
    const saved=data.windows?.[win.dataset.app];
    if(saved&&Number.isFinite(saved.left)&&Number.isFinite(saved.top)&&Number.isFinite(saved.width)&&Number.isFinite(saved.height))return saved;
  }catch(_){ }
  return defaultGeometry(win);
}
function applyGeometry(win,g){const bounds=desktopBounds();const width=clamp(g.width,MIN_W,Math.max(MIN_W,bounds.width-8));const height=clamp(g.height,MIN_H,Math.max(MIN_H,bounds.height-8));const left=clamp(g.left,2,Math.max(2,bounds.width-width-2));const top=clamp(g.top,2,Math.max(2,bounds.height-height-2));Object.assign(win.style,{left:`${left}px`,top:`${top}px`,width:`${width}px`,height:`${height}px`,right:'auto',bottom:'auto',inset:'auto'});}
function saveGeometry(win){if(!desktopEnabled()||win.classList.contains('rift-maximized'))return;const rect=win.getBoundingClientRect(),stageRect=stage.getBoundingClientRect();persistWindowGeometry(win.dataset.app,{left:rect.left-stageRect.left,top:rect.top-stageRect.top,width:rect.width,height:rect.height});}
async function persistWindowGeometry(id,value){try{const current=await core?.fs?.readJSON?.('/system/settings/desktop.json',{})||{};await core?.fs?.writeJSON?.('/system/settings/desktop.json',{...current,windows:{...(current.windows||{}),[id]:value},updated:Date.now()});}catch(_){}}
function maximizeForMobile(win){win.classList.remove('rift-maximized');Object.assign(win.style,{inset:'',left:'',top:'',width:'',height:'',right:'',bottom:''});}
async function restoreDesktopGeometry(win){if(!desktopEnabled())return;if(win.classList.contains('rift-maximized'))maximize(win,true);else applyGeometry(win,await readStoredGeometry(win));}

function focusVisual(win,requestCore=true){if(!win||!document.contains(win))return;win.classList.remove('rift-minimized');activeWindow=win;zCounter+=1;win.style.zIndex=String(zCounter);allWindows().forEach(item=>item.classList.toggle('rift-focused',item===win));if(requestCore)wm()?.focus?.(win.dataset.app);syncTaskbar();}
function nextVisible(except){return allWindows().filter(win=>win!==except&&!win.classList.contains('rift-minimized')).sort((a,b)=>(Number(b.style.zIndex)||0)-(Number(a.style.zIndex)||0))[0]||null;}
function announceVisibility(win,visible,reason){if(!win)return;window.dispatchEvent(new CustomEvent('riftos:window-visibility',{detail:{id:win.dataset.app,window:win,visible:!!visible,reason}}));}
function minimize(win){if(!win)return;announceVisibility(win,false,'minimize');win.classList.add('rift-minimized');win.classList.remove('rift-focused');if(activeWindow===win)activeWindow=null;const next=nextVisible(win);if(next)focusVisual(next);else workspace.classList.remove('hidden');syncTaskbar();}
function restore(win){if(!win)return;win.classList.remove('rift-minimized');stage.classList.remove('hidden');if(!desktopEnabled())workspace.classList.add('hidden');restoreDesktopGeometry(win);focusVisual(win);announceVisibility(win,true,'restore');}
function maximize(win,force=false){if(!win||!desktopEnabled())return;if(!force&&win.classList.contains('rift-maximized')){win.classList.remove('rift-maximized');applyGeometry(win,readGeometry(win));return;}if(!win.classList.contains('rift-maximized'))saveGeometry(win);win.classList.add('rift-maximized');Object.assign(win.style,{left:'0px',top:'0px',width:'100%',height:'100%',inset:'auto',right:'auto',bottom:'auto'});}

function startDrag(event,win){if(!desktopEnabled()||inputState.virtualMouse||win.classList.contains('rift-maximized')||event.target.closest('button,input,textarea,select,a'))return;event.preventDefault();focusVisual(win);const stageRect=stage.getBoundingClientRect(),rect=win.getBoundingClientRect(),startX=event.clientX,startY=event.clientY,baseLeft=rect.left-stageRect.left,baseTop=rect.top-stageRect.top;event.currentTarget.setPointerCapture?.(event.pointerId);const move=e=>applyGeometry(win,{left:baseLeft+e.clientX-startX,top:baseTop+e.clientY-startY,width:rect.width,height:rect.height});const end=()=>{event.currentTarget.removeEventListener('pointermove',move);event.currentTarget.removeEventListener('pointerup',end);event.currentTarget.removeEventListener('pointercancel',end);saveGeometry(win);};event.currentTarget.addEventListener('pointermove',move);event.currentTarget.addEventListener('pointerup',end,{once:true});event.currentTarget.addEventListener('pointercancel',end,{once:true});}
function startResize(event,win){if(!desktopEnabled()||inputState.virtualMouse||win.classList.contains('rift-maximized'))return;event.preventDefault();event.stopPropagation();focusVisual(win);const rect=win.getBoundingClientRect(),startX=event.clientX,startY=event.clientY;event.currentTarget.setPointerCapture?.(event.pointerId);const move=e=>applyGeometry(win,{left:parseFloat(win.style.left)||0,top:parseFloat(win.style.top)||0,width:rect.width+e.clientX-startX,height:rect.height+e.clientY-startY});const end=()=>{event.currentTarget.removeEventListener('pointermove',move);event.currentTarget.removeEventListener('pointerup',end);event.currentTarget.removeEventListener('pointercancel',end);saveGeometry(win);};event.currentTarget.addEventListener('pointermove',move);event.currentTarget.addEventListener('pointerup',end,{once:true});event.currentTarget.addEventListener('pointercancel',end,{once:true});}

function upgradeWindow(win){if(!(win instanceof HTMLElement)||!win.classList.contains('window')||win.dataset.riftDesktopUpgraded)return;win.dataset.riftDesktopUpgraded='1';win.classList.add('rift-desktop-window');const bar=win.querySelector('.window-bar'),close=win.querySelector('.window-close');if(!bar||!close)return;const actions=document.createElement('div');actions.className='rift-window-actions';const min=document.createElement('button');min.type='button';min.className='rift-window-minimize';min.title='Minimize';min.textContent='—';const max=document.createElement('button');max.type='button';max.className='rift-window-maximize';max.title='Maximize';max.textContent='□';close.parentNode?.removeChild(close);actions.append(min,max,close);bar.append(actions);const resizer=document.createElement('div');resizer.className='rift-window-resizer';win.append(resizer);min.addEventListener('click',e=>{e.stopPropagation();minimize(win);});max.addEventListener('click',e=>{e.stopPropagation();maximize(win);});bar.addEventListener('dblclick',e=>{if(!e.target.closest('button'))maximize(win);});bar.addEventListener('pointerdown',e=>startDrag(e,win));resizer.addEventListener('pointerdown',e=>startResize(e,win));win.addEventListener('pointerdown',()=>{if(!inputState.virtualMouse)focusVisual(win);},{capture:true});if(desktopEnabled())restoreDesktopGeometry(win);else maximizeForMobile(win);focusVisual(win,false);}

function syncTaskbar(){const list=wm()?.list?.()||[];const byId=new Map(list.map(item=>[item.id,item]));dock.querySelectorAll('.dock-btn[data-open]').forEach(btn=>{if(btn.dataset.open==='home')return;const item=byId.get(btn.dataset.open);btn.classList.toggle('rift-running',!!item);btn.classList.toggle('rift-running-active',!!item&&item.window===activeWindow&&!item.minimized);});const pinned=new Set([...dock.querySelectorAll('.dock-btn[data-open]')].map(btn=>btn.dataset.open));taskbarOpen.innerHTML=list.filter(item=>!pinned.has(item.id)).map(item=>`<button type="button" class="rift-task-window${item.window===activeWindow&&!item.minimized?' active':''}" data-task-window="${item.id}"><span>${item.id==='editor'?'{}':item.id==='tasks'?'≡':'□'}</span><b>${item.title}</b></button>`).join('');stage.classList.toggle('rift-stage-active',list.some(item=>!item.minimized));}

async function saveIconGeometry(card){
  try{
    const bounds=workspace.getBoundingClientRect();
    const rect=card.getBoundingClientRect();
    const key=card.dataset.open||card.textContent.trim();
    const current=await core?.fs?.readJSON?.('/system/settings/desktop.json',{})||{};
    const icons=current.icons||{};
    icons[key]={left:clamp(rect.left-bounds.left,0,Math.max(0,bounds.width-90)),top:clamp(rect.top-bounds.top,0,Math.max(0,bounds.height-90))};
    await persistDesktopSettings({icons});
  }catch(_){ }
}
async function restoreIconGeometry(card){
  try{
    const data=await core?.fs?.readJSON?.('/system/settings/desktop.json',{})||{};
    const saved=data.icons?.[card.dataset.open||card.textContent.trim()];
    if(saved){card.style.position='absolute';card.style.left=`${Number(saved.left)||0}px`;card.style.top=`${Number(saved.top)||0}px`;}
  }catch(_){ }
}
function applyWallpaper(){
  document.documentElement.style.setProperty('--rift-desktop-wallpaper',desktopWallpaper||'');
}
function resetDesktopLayout(){
  persistDesktopSetting('icons',{});
  persistDesktopSetting('icons',{});
  document.querySelectorAll('.app-card').forEach(card=>{
    card.style.position='';
    card.style.left='';
    card.style.top='';
  });
}
async function persistDesktopSettings(patch){
  try{
    const current=await core?.fs?.readJSON?.('/system/settings/desktop.json',{})||{};
    await core?.fs?.writeJSON?.('/system/settings/desktop.json',{...current,...patch,updated:Date.now()});
  }catch(_){ }
}
function persistDesktopSetting(key,value){persistDesktopSettings({[key]:value});}
function setWallpaper(value=''){
  desktopWallpaper=String(value||'');
  persistDesktopSetting('wallpaper',desktopWallpaper);
  applyWallpaper();
}
function applyDesktopMode(){const enabled=desktopEnabled();root.classList.toggle('rift-desktop-mode',enabled);root.dataset.riftDesktop=enabled?'desktop':'mobile';tray.querySelector('#riftDesktopToggle').classList.toggle('active',enabled);if(enabled){workspace.classList.remove('hidden');allWindows().forEach(win=>restoreDesktopGeometry(win));}else{startMenu.classList.remove('open');allWindows().forEach(maximizeForMobile);const visible=allWindows().filter(win=>!win.classList.contains('rift-minimized'));workspace.classList.toggle('hidden',visible.length>0);}syncTaskbar();}
function cycleDesktopPreference(){desktopPreference=desktopPreference==='auto'?'on':desktopPreference==='on'?'off':'auto';persistDesktopSetting('mode',desktopPreference);applyDesktopMode();}

function moveCursor(x,y){cursorX=clamp(x,2,innerWidth-3);cursorY=clamp(y,2,innerHeight-3);cursor.style.transform=`translate3d(${cursorX}px,${cursorY}px,0)`;}
function pointerTarget(){const oldOverlay=trackpad.style.pointerEvents,oldCursor=cursor.style.pointerEvents;trackpad.style.pointerEvents='none';cursor.style.pointerEvents='none';const target=document.elementFromPoint(cursorX,cursorY);trackpad.style.pointerEvents=oldOverlay;cursor.style.pointerEvents=oldCursor;return target;}
function mouseInit(type,button=0,buttons=0,target=pointerTarget()){if(!target)return null;const common={bubbles:true,cancelable:true,composed:true,clientX:cursorX,clientY:cursorY,button,buttons};try{target.dispatchEvent(new PointerEvent(type.startsWith('pointer')?type:`pointer${type}`,{...common,pointerType:'mouse',pointerId:1,isPrimary:true}));}catch(_){}return target;}
function dispatchVirtualClick(button=0){const target=pointerTarget();if(!target)return;const buttons=button===2?2:1;const common={bubbles:true,cancelable:true,composed:true,clientX:cursorX,clientY:cursorY,button,buttons};try{target.dispatchEvent(new PointerEvent('pointerdown',{...common,pointerType:'mouse',pointerId:1,isPrimary:true}));}catch(_){}target.dispatchEvent(new MouseEvent('mousedown',common));try{target.dispatchEvent(new PointerEvent('pointerup',{...common,buttons:0,pointerType:'mouse',pointerId:1,isPrimary:true}));}catch(_){}target.dispatchEvent(new MouseEvent('mouseup',{...common,buttons:0}));if(button===2)target.dispatchEvent(new MouseEvent('contextmenu',common));else{target.dispatchEvent(new MouseEvent('click',common));if(typeof target.focus==='function')try{target.focus({preventScroll:true});}catch(_){target.focus();}}}
function dispatchDoubleClick(){const target=pointerTarget();if(target)target.dispatchEvent(new MouseEvent('dblclick',{bubbles:true,cancelable:true,composed:true,clientX:cursorX,clientY:cursorY,button:0,buttons:0,detail:2}));}
function dispatchVirtualScroll(deltaY){const target=pointerTarget();if(!target)return;target.dispatchEvent(new WheelEvent('wheel',{bubbles:true,cancelable:true,clientX:cursorX,clientY:cursorY,deltaY,deltaMode:WheelEvent.DOM_DELTA_PIXEL}));let node=target;while(node&&node!==document.body){if(node.scrollHeight>node.clientHeight){node.scrollTop+=deltaY;break;}node=node.parentElement;}}
function setVirtualMouse(enabled){inputState.virtualMouse=Boolean(enabled);root.classList.toggle('rift-virtual-mouse',inputState.virtualMouse);tray.querySelector('#riftMouseToggle').classList.toggle('active',inputState.virtualMouse);trackpad.classList.toggle('active',inputState.virtualMouse);cursor.classList.toggle('visible',inputState.virtualMouse);if(inputState.virtualMouse)moveCursor(cursorX,cursorY);}

function beginHold(){if(!gesture||gesture.maxPointers!==1||gesture.moved)return;const target=pointerTarget();if(!target)return;const win=target.closest?.('.window.rift-desktop-window');if(win&&target.closest('.window-bar')&&!target.closest('button')){const rect=win.getBoundingClientRect(),stageRect=stage.getBoundingClientRect();gesture.hold={kind:'window',win,left:rect.left-stageRect.left,top:rect.top-stageRect.top,width:rect.width,height:rect.height};focusVisual(win);return;}if(win&&target.closest('.rift-window-resizer')){const rect=win.getBoundingClientRect(),stageRect=stage.getBoundingClientRect();gesture.hold={kind:'resize',win,left:rect.left-stageRect.left,top:rect.top-stageRect.top,width:rect.width,height:rect.height};focusVisual(win);return;}gesture.hold={kind:'mouse',target};const common={bubbles:true,cancelable:true,composed:true,clientX:cursorX,clientY:cursorY,button:0,buttons:1};try{target.dispatchEvent(new PointerEvent('pointerdown',{...common,pointerType:'mouse',pointerId:1,isPrimary:true}));}catch(_){}target.dispatchEvent(new MouseEvent('mousedown',common));}
trackpad.addEventListener('pointerdown',event=>{if(!inputState.virtualMouse)return;event.preventDefault();trackpad.setPointerCapture?.(event.pointerId);pointers.set(event.pointerId,{x:event.clientX,y:event.clientY});if(!gesture)gesture={maxPointers:1,moved:false,totalX:0,totalY:0,hold:null,holdTimer:setTimeout(beginHold,260)};gesture.maxPointers=Math.max(gesture.maxPointers,pointers.size);if(pointers.size>1){clearTimeout(gesture.holdTimer);gesture.holdTimer=0;}});
trackpad.addEventListener('pointermove',event=>{const prior=pointers.get(event.pointerId);if(!prior||!gesture)return;const dx=event.clientX-prior.x,dy=event.clientY-prior.y;pointers.set(event.pointerId,{x:event.clientX,y:event.clientY});gesture.totalX+=dx;gesture.totalY+=dy;if(Math.abs(gesture.totalX)+Math.abs(gesture.totalY)>5)gesture.moved=true;if(pointers.size>=2){clearTimeout(gesture.holdTimer);dispatchVirtualScroll(dy*2.1);return;}if(gesture.hold?.kind==='window'){applyGeometry(gesture.hold.win,{left:gesture.hold.left+gesture.totalX,top:gesture.hold.top+gesture.totalY,width:gesture.hold.width,height:gesture.hold.height});return;}if(gesture.hold?.kind==='resize'){applyGeometry(gesture.hold.win,{left:gesture.hold.left,top:gesture.hold.top,width:gesture.hold.width+gesture.totalX,height:gesture.hold.height+gesture.totalY});return;}moveCursor(cursorX+dx*1.35,cursorY+dy*1.35);if(gesture.hold?.kind==='mouse'){const target=gesture.hold.target;const common={bubbles:true,cancelable:true,composed:true,clientX:cursorX,clientY:cursorY,button:0,buttons:1};try{target.dispatchEvent(new PointerEvent('pointermove',{...common,pointerType:'mouse',pointerId:1,isPrimary:true}));}catch(_){}target.dispatchEvent(new MouseEvent('mousemove',common));}else if(gesture.moved)clearTimeout(gesture.holdTimer);});
function endTrackpadPointer(event){if(!gesture)return;pointers.delete(event.pointerId);if(pointers.size)return;clearTimeout(gesture.holdTimer);if(gesture.hold?.kind==='window'||gesture.hold?.kind==='resize')saveGeometry(gesture.hold.win);else if(gesture.hold?.kind==='mouse'){const target=gesture.hold.target,common={bubbles:true,cancelable:true,composed:true,clientX:cursorX,clientY:cursorY,button:0,buttons:0};try{target.dispatchEvent(new PointerEvent('pointerup',{...common,pointerType:'mouse',pointerId:1,isPrimary:true}));}catch(_){}target.dispatchEvent(new MouseEvent('mouseup',common));}else if(!gesture.moved){if(gesture.maxPointers>=2)dispatchVirtualClick(2);else{const now=Date.now(),isDouble=now-lastTap.time<340&&Math.hypot(cursorX-lastTap.x,cursorY-lastTap.y)<24;dispatchVirtualClick(0);if(isDouble)dispatchDoubleClick();lastTap={time:now,x:cursorX,y:cursorY};}}gesture=null;}
trackpad.addEventListener('pointerup',endTrackpadPointer);trackpad.addEventListener('pointercancel',endTrackpadPointer);

function toggleStart(){if(!desktopEnabled())return;startMenu.classList.toggle('open');}
homeButton?.addEventListener('click',event=>{if(!desktopEnabled())return;event.preventDefault();event.stopImmediatePropagation();toggleStart();},true);
startMenu.addEventListener('click',event=>{if(event.target.closest('[data-open]'))startMenu.classList.remove('open');});
workspace.addEventListener('pointerdown',event=>{if(!event.target.closest('.app-card'))startMenu.classList.remove('open');});
tray.querySelector('#riftDesktopToggle').addEventListener('click',cycleDesktopPreference);
tray.querySelector('#riftMouseToggle').addEventListener('click',()=>setVirtualMouse(!inputState.virtualMouse));
tray.querySelector('#riftShowDesktop').addEventListener('click',()=>{startMenu.classList.remove('open');wm()?.showDesktop?.();activeWindow=null;syncTaskbar();});

dock.addEventListener('click',event=>{const task=event.target.closest('[data-task-window]');if(task){event.preventDefault();event.stopImmediatePropagation();const item=wm()?.get?.(task.dataset.taskWindow);if(item){if(item.win===activeWindow&&!item.win.classList.contains('rift-minimized'))minimize(item.win);else restore(item.win);}return;}const button=event.target.closest('.dock-btn[data-open]');if(!button||button.dataset.open==='home')return;const item=wm()?.get?.(button.dataset.open);if(!item)return;event.preventDefault();event.stopImmediatePropagation();if(item.win===activeWindow&&!item.win.classList.contains('rift-minimized'))minimize(item.win);else restore(item.win);},true);

const observer=new MutationObserver(records=>{for(const record of records)for(const node of record.addedNodes)if(node instanceof HTMLElement){if(node.classList.contains('window'))upgradeWindow(node);node.querySelectorAll?.('.window').forEach(upgradeWindow);}allWindows().filter(win=>!document.contains(win));syncTaskbar();});
observer.observe(stage,{childList:true,subtree:true});stage.querySelectorAll('.window').forEach(upgradeWindow);
window.addEventListener('riftos:window-open',event=>{upgradeWindow(event.detail.window);focusVisual(event.detail.window,false);syncTaskbar();});
window.addEventListener('riftos:window-activate',event=>{focusVisual(event.detail.window,false);startMenu.classList.remove('open');});
window.addEventListener('riftos:window-close',()=>{if(activeWindow&&!document.contains(activeWindow))activeWindow=null;syncTaskbar();});
window.addEventListener('riftos:show-desktop',()=>{activeWindow=null;syncTaskbar();});

window.addEventListener('pointerdown',event=>{if(event.pointerType==='mouse'){inputState.mouse=true;root.classList.add('rift-hardware-mouse');}},true);
window.addEventListener('keydown',event=>{inputState.keyboard=true;root.classList.add('rift-hardware-keyboard');if(event.altKey&&event.key==='Tab'){event.preventDefault();const list=wm()?.list?.()||[];if(!list.length)return;const index=Math.max(0,list.findIndex(item=>item.window===activeWindow));const next=list[(index+(event.shiftKey?-1:1)+list.length)%list.length];restore(next.window);return;}if(event.ctrlKey&&event.altKey&&event.key.toLowerCase()==='d'){event.preventDefault();cycleDesktopPreference();return;}if(event.ctrlKey&&event.altKey&&event.key.toLowerCase()==='m'){event.preventDefault();setVirtualMouse(!inputState.virtualMouse);return;}if(event.key==='Escape'&&startMenu.classList.contains('open')){startMenu.classList.remove('open');return;}},true);
window.addEventListener('resize',()=>{applyDesktopMode();allWindows().forEach(win=>{if(desktopEnabled()&&!win.classList.contains('rift-maximized')&&!win.classList.contains('rift-minimized')){const rect=win.getBoundingClientRect(),stageRect=stage.getBoundingClientRect();applyGeometry(win,{left:rect.left-stageRect.left,top:rect.top-stageRect.top,width:rect.width,height:rect.height});}});});
window.addEventListener('riftos:launcher-ready',()=>{
  applyDesktopMode();
  document.querySelectorAll('.app-card').forEach(card=>{
    restoreIconGeometry(card);
    card.setAttribute('draggable','false');
    card.dataset.desktopDragReady='1';
    if(card.dataset.desktopDragBound)return;
    card.dataset.desktopDragBound='1';
    let drag=null;
    card.addEventListener('pointerdown',event=>{
      if(!desktopEnabled()||event.button===2)return;
      const grid=card.parentElement;
      if(!grid?.classList.contains('app-grid'))return;
      const rect=card.getBoundingClientRect();
      drag={x:event.clientX,y:event.clientY,left:rect.left-grid.getBoundingClientRect().left,top:rect.top-grid.getBoundingClientRect().top};
      card.setPointerCapture?.(event.pointerId);
      const move=e=>{
        if(!drag)return;
        if(Math.hypot(e.clientX-drag.x,e.clientY-drag.y)<4)return;
        grid.style.position='relative';
        card.style.position='absolute';
        card.style.left=Math.max(0,drag.left+e.clientX-drag.x)+'px';
        card.style.top=Math.max(0,drag.top+e.clientY-drag.y)+'px';
      };
      const end=()=>{drag=null;saveIconGeometry(card);card.removeEventListener('pointermove',move);card.removeEventListener('pointerup',end);};
      card.addEventListener('pointermove',move);
      card.addEventListener('pointerup',end,{once:true});
    });
  });
});

restoreDesktopSettings().then(()=>{
  applyWallpaper();
  applyDesktopMode();
  syncTaskbar();
  moveCursor(cursorX,cursorY);
});
const appApi=window.RiftDesktop||{};
window.RiftDesktop=Object.freeze({...appApi,get mode(){return desktopEnabled()?'desktop':'mobile';},get input(){return{...inputState};},get wallpaper(){return desktopWallpaper;},enable(){desktopPreference='on';persistDesktopSetting('mode',desktopPreference);applyDesktopMode();},disable(){desktopPreference='off';persistDesktopSetting('mode',desktopPreference);applyDesktopMode();},auto(){desktopPreference='auto';persistDesktopSetting('mode',desktopPreference);applyDesktopMode();},setWallpaper(value){setWallpaper(value);},resetLayout(){resetDesktopLayout();},virtualMouse(enabled=true){setVirtualMouse(enabled);},restore(){restore(activeWindow);},minimize(){minimize(activeWindow);},maximize(){maximize(activeWindow);}});
