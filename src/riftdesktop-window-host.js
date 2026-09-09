const root=document.documentElement;

// RiftOS Android is a desktop shell. Apps are always children of that shell;
// they never replace or hide the desktop itself.
try{localStorage.setItem('rift.desktop.mode','on');}catch(_){}
root.classList.add('rift-desktop-mode');
root.dataset.riftDesktop='desktop';

const style=document.createElement('style');
style.id='riftPersistentWindowHostStyles';
style.textContent=`
  /* The desktop remains interactive around every open window. */
  .rift-desktop-mode #workspace{display:block!important}
  .rift-desktop-mode #stage,
  .rift-desktop-mode #stage.rift-stage-active,
  .rift-desktop-mode #stage.rift-stage-minimized{pointer-events:none!important;background:transparent!important}
  .rift-desktop-mode #stage>.window,
  .rift-desktop-mode #stage .window.rift-desktop-window{pointer-events:auto!important}

  /* "Full screen" means maximized inside the desktop work area. The taskbar stays. */
  .rift-desktop-mode .window.rift-desktop-window.rift-maximized{
    left:0!important;top:0!important;width:100%!important;height:100%!important;
    right:auto!important;bottom:auto!important;inset:auto!important;
    border-radius:0!important;box-shadow:none!important
  }
  .rift-desktop-mode .window.rift-desktop-window.rift-maximized .rift-window-resizer{display:none!important}

  /* Minimize only hides the app window; it never hides the desktop. */
  .rift-desktop-mode .window.rift-minimized{display:none!important}

  /* Android RiftOS no longer has a mobile/app-takeover mode. */
  #riftDesktopToggle{display:none!important}
`;
document.head.append(style);

function keepDesktopAlive(){
  // These guards are important: this function is called by a MutationObserver.
  // Re-writing an already-correct observed attribute can retrigger the observer
  // forever on Android WebView and starve timers (including the boot transition).
  if(!root.classList.contains('rift-desktop-mode'))root.classList.add('rift-desktop-mode');
  if(root.dataset.riftDesktop!=='desktop')root.dataset.riftDesktop='desktop';
  const workspace=document.querySelector('#workspace');
  if(workspace?.classList.contains('hidden'))workspace.classList.remove('hidden');
}

// Anything that previously tried to switch back to the old single-screen/mobile shell
// is corrected only when it actually changes. This also protects old Android/WebView builds.
const desktopObserver=new MutationObserver(()=>keepDesktopAlive());
desktopObserver.observe(root,{attributes:true,attributeFilter:['class','data-rift-desktop']});

for(const eventName of ['riftos:launcher-ready','riftos:window-open','riftos:window-activate','riftos:window-close','riftos:show-desktop']){
  window.addEventListener(eventName,keepDesktopAlive);
}
window.addEventListener('resize',keepDesktopAlive);

// Retire the old desktop-mode toggle/shortcut; RiftDesktop itself is now the host.
document.addEventListener('click',event=>{
  if(event.target.closest?.('#riftDesktopToggle')){
    event.preventDefault();
    event.stopImmediatePropagation();
    keepDesktopAlive();
  }
},true);
window.addEventListener('keydown',event=>{
  if(event.ctrlKey&&event.altKey&&event.key.toLowerCase()==='d'){
    event.preventDefault();
    event.stopImmediatePropagation();
    keepDesktopAlive();
  }
},true);

keepDesktopAlive();

window.RiftDesktopHost=Object.freeze({
  persistent:true,
  keepDesktopAlive,
  workspace:()=>document.querySelector('#workspace'),
  stage:()=>document.querySelector('#stage')
});
