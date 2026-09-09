const stage = document.querySelector('#stage');

if (stage) {
  const fallbackStyle = document.createElement('style');
  fallbackStyle.textContent = `
    .rift-desktop-mode .stage.rift-stage-active{pointer-events:auto}
    .rift-desktop-mode .stage.rift-stage-active.rift-stage-minimized{pointer-events:none!important}
  `;
  document.head.append(fallbackStyle);

  const syncStageInput = () => {
    const active = [...stage.children].some(node =>
      node instanceof HTMLElement &&
      node.classList.contains('window') &&
      !node.classList.contains('rift-minimized')
    );
    stage.classList.toggle('rift-stage-active', active);
  };

  new MutationObserver(syncStageInput).observe(stage, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['class']
  });
  syncStageInput();

  window.addEventListener('riftos:launcher-ready', syncStageInput);
  window.visualViewport?.addEventListener('resize', syncStageInput);
}

window.RiftDesktopPlatform = Object.freeze({
  platform: 'android',
  samsungDesktopReady: true,
  maxTouchPoints: navigator.maxTouchPoints || 0,
  finePointer: matchMedia?.('(pointer:fine)').matches || false,
  hoverPointer: matchMedia?.('(hover:hover)').matches || false,
  hardwareKeyboardDetected: () => document.documentElement.classList.contains('rift-hardware-keyboard'),
  hardwareMouseDetected: () => document.documentElement.classList.contains('rift-hardware-mouse')
});
