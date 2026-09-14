const core=globalThis.RiftOSCore;
const nativeDesktop=globalThis.RiftNativeDesktop;
if(!core||!nativeDesktop?.enabled)throw new Error('Native desktop compatibility renderer requires RiftOS native desktop authority');

const root=document.documentElement;
root.classList.add('rift-native-host','rift-desktop-mode');
root.dataset.riftDesktop='native';

const style=document.createElement('style');
style.id='riftNativeDesktopCompatStyles';
style.textContent=`
  html.rift-native-host,html.rift-native-host body{margin:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:transparent!important}
  html.rift-native-host #boot{display:none!important}
  html.rift-native-host #os{display:block!important;position:absolute!important;inset:0!important;width:100%!important;height:100%!important;background:transparent!important;pointer-events:none!important;overflow:hidden!important}
  html.rift-native-host #os>.statusbar,html.rift-native-host #workspace,html.rift-native-host .dock{display:none!important}
  html.rift-native-host #content,html.rift-native-host #stage{display:block!important;position:absolute!important;inset:0!important;width:100%!important;height:100%!important;min-height:0!important;background:transparent!important;pointer-events:none!important;overflow:hidden!important}
  html.rift-native-host #stage>.window.rift-native-content-window{display:block;position:absolute!important;right:auto!important;bottom:auto!important;margin:0!important;max-width:none!important;max-height:none!important;min-width:0!important;min-height:0!important;border:0!important;border-radius:0!important;box-shadow:none!important;background:#0b1118!important;overflow:hidden!important;pointer-events:auto!important;transition:none!important}
  html.rift-native-host #stage>.window.rift-native-content-window.rift-minimized{display:none!important;pointer-events:none!important}
  html.rift-native-host .rift-native-content-window>.window-bar{display:none!important}
  html.rift-native-host .rift-native-content-window>.window-body{position:absolute!important;inset:0!important;width:auto!important;height:auto!important;min-width:0!important;min-height:0!important;overflow:auto!important;background:#0b1118!important}
  html.rift-native-host .rift-native-content-window.rift-focused{outline:0!important}
`;
document.head.append(style);

// Restore the persisted wallpaper into Android. Window geometry itself is now native-owned.
try{
  const settings=await core.fs.readJSON('/system/settings/desktop.json',{});
  if(settings?.wallpaper!==undefined)await nativeDesktop.request('wallpaper.set',{value:String(settings.wallpaper||'')});
}catch(_){}

window.RiftDesktopPlatform=Object.freeze({
  platform:'android-native',
  nativeDesktop:true,
  samsungDesktopReady:true,
  maxTouchPoints:navigator.maxTouchPoints||0,
  finePointer:matchMedia?.('(pointer:fine)').matches||false,
  hoverPointer:matchMedia?.('(hover:hover)').matches||false,
  hardwareKeyboardDetected:()=>false,
  hardwareMouseDetected:()=>false
});

console.info('[RiftDesktop] Android-native desktop authority online; WebView is compatibility content only');
