const root = document.documentElement;
const os = document.querySelector('#os');
const stage = document.querySelector('#stage');
const workspace = document.querySelector('#workspace');
const dock = document.querySelector('.dock');
const statusbar = document.querySelector('.statusbar');

if (!os || !stage || !workspace || !dock || !statusbar) {
  throw new Error('RiftDesktop requires the RiftOS shell DOM');
}

const STATE_KEY = 'rift.desktop.mode';
const GEOMETRY_KEY = app => `rift.desktop.geometry.${app || 'app'}`;
const MIN_W = 320;
const MIN_H = 240;
const inputState = { mouse: false, keyboard: false, virtualMouse: false };
let desktopPreference = localStorage.getItem(STATE_KEY) || 'auto';
let activeWindow = null;
let zCounter = 80;
let cursorX = Math.max(24, innerWidth * 0.5);
let cursorY = Math.max(24, innerHeight * 0.5);
let longPressTimer = 0;
let padMoved = false;
const padPointers = new Map();

const controls = document.createElement('div');
controls.className = 'rift-desktop-controls';
controls.innerHTML = `
  <button type="button" id="riftDesktopToggle" title="Toggle desktop mode">Desktop</button>
  <button type="button" id="riftMouseToggle" title="Touch mouse / trackpad">Mouse</button>
  <span id="riftInputState">TOUCH</span>`;
statusbar.insertBefore(controls, statusbar.querySelector('time'));

const desktopBrand = document.createElement('div');
desktopBrand.className = 'rift-desktop-brand';
desktopBrand.innerHTML = '<strong>RiftOS</strong><span>Samsung Desktop</span>';
workspace.prepend(desktopBrand);

const cursor = document.createElement('div');
cursor.id = 'riftVirtualCursor';
cursor.setAttribute('aria-hidden', 'true');
cursor.innerHTML = '<i></i>';
document.body.append(cursor);

const touchpad = document.createElement('section');
touchpad.id = 'riftTouchpad';
touchpad.setAttribute('aria-label', 'RiftOS virtual trackpad');
touchpad.innerHTML = `
  <div class="rift-touchpad-surface" id="riftTouchpadSurface"><span>TRACKPAD</span></div>
  <div class="rift-touchpad-buttons">
    <button type="button" data-mouse-button="0">Left</button>
    <button type="button" data-mouse-button="2">Right</button>
  </div>`;
document.body.append(touchpad);

function autoDesktop() {
  return innerWidth >= 720 || (innerWidth > innerHeight && innerWidth >= 600);
}

function desktopEnabled() {
  return desktopPreference === 'on' || (desktopPreference === 'auto' && autoDesktop());
}

function applyDesktopMode() {
  const enabled = desktopEnabled();
  root.classList.toggle('rift-desktop-mode', enabled);
  root.dataset.riftDesktop = enabled ? 'desktop' : 'mobile';
  controls.querySelector('#riftDesktopToggle').classList.toggle('active', enabled);
  controls.querySelector('#riftDesktopToggle').textContent = enabled ? 'Desktop ✓' : 'Desktop';

  if (enabled && activeWindow && !activeWindow.classList.contains('rift-minimized')) {
    workspace.classList.remove('hidden');
    stage.classList.remove('hidden');
  }
  if (!enabled && activeWindow && !activeWindow.classList.contains('rift-minimized')) {
    workspace.classList.add('hidden');
    maximizeForMobile(activeWindow);
  }
  if (enabled && activeWindow) restoreDesktopGeometry(activeWindow);
}

function cycleDesktopPreference() {
  desktopPreference = desktopPreference === 'auto' ? 'on' : desktopPreference === 'on' ? 'off' : 'auto';
  localStorage.setItem(STATE_KEY, desktopPreference);
  applyDesktopMode();
}

function setVirtualMouse(enabled) {
  inputState.virtualMouse = Boolean(enabled);
  root.classList.toggle('rift-virtual-mouse', inputState.virtualMouse);
  controls.querySelector('#riftMouseToggle').classList.toggle('active', inputState.virtualMouse);
  touchpad.classList.toggle('visible', inputState.virtualMouse);
  cursor.classList.toggle('visible', inputState.virtualMouse);
  if (inputState.virtualMouse) moveCursor(cursorX, cursorY);
  updateInputLabel();
}

function updateInputLabel() {
  const parts = [];
  if (inputState.mouse) parts.push('MOUSE');
  if (inputState.keyboard) parts.push('KEYBOARD');
  if (inputState.virtualMouse) parts.push('TOUCH-MOUSE');
  controls.querySelector('#riftInputState').textContent = parts.join(' + ') || 'TOUCH';
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function moveCursor(x, y) {
  cursorX = clamp(x, 2, innerWidth - 3);
  cursorY = clamp(y, 2, innerHeight - 3);
  cursor.style.transform = `translate3d(${cursorX}px,${cursorY}px,0)`;
}

function pointerTarget() {
  const was = cursor.style.pointerEvents;
  cursor.style.pointerEvents = 'none';
  const target = document.elementFromPoint(cursorX, cursorY);
  cursor.style.pointerEvents = was;
  return target;
}

function dispatchVirtualClick(button = 0) {
  const target = pointerTarget();
  if (!target || touchpad.contains(target)) return;
  const common = { bubbles: true, cancelable: true, composed: true, clientX: cursorX, clientY: cursorY, button, buttons: button === 2 ? 2 : 1, pointerType: 'mouse' };
  try { target.dispatchEvent(new PointerEvent('pointerdown', common)); } catch (_) {}
  target.dispatchEvent(new MouseEvent('mousedown', common));
  try { target.dispatchEvent(new PointerEvent('pointerup', { ...common, buttons: 0 })); } catch (_) {}
  target.dispatchEvent(new MouseEvent('mouseup', { ...common, buttons: 0 }));
  if (button === 2) target.dispatchEvent(new MouseEvent('contextmenu', common));
  else target.dispatchEvent(new MouseEvent('click', common));
  if (typeof target.focus === 'function' && button === 0) target.focus({ preventScroll: true });
}

function dispatchVirtualScroll(deltaY) {
  const target = pointerTarget();
  if (!target || touchpad.contains(target)) return;
  target.dispatchEvent(new WheelEvent('wheel', { bubbles: true, cancelable: true, clientX: cursorX, clientY: cursorY, deltaY, deltaMode: WheelEvent.DOM_DELTA_PIXEL }));
  let scrollNode = target;
  while (scrollNode && scrollNode !== document.body) {
    if (scrollNode.scrollHeight > scrollNode.clientHeight) {
      scrollNode.scrollTop += deltaY;
      break;
    }
    scrollNode = scrollNode.parentElement;
  }
}

function desktopBounds(win) {
  const rect = stage.getBoundingClientRect();
  return { width: rect.width || innerWidth, height: rect.height || innerHeight };
}

function defaultGeometry(win) {
  const bounds = desktopBounds(win);
  const width = clamp(Math.round(bounds.width * 0.72), MIN_W, Math.max(MIN_W, bounds.width - 32));
  const height = clamp(Math.round(bounds.height * 0.76), MIN_H, Math.max(MIN_H, bounds.height - 32));
  const offset = (Number(win.dataset.desktopIndex || 0) % 5) * 22;
  return {
    left: clamp(Math.round((bounds.width - width) / 2) + offset, 8, Math.max(8, bounds.width - width - 8)),
    top: clamp(22 + offset, 8, Math.max(8, bounds.height - height - 8)),
    width,
    height
  };
}

function readGeometry(win) {
  try {
    const saved = JSON.parse(localStorage.getItem(GEOMETRY_KEY(win.dataset.app)) || 'null');
    if (saved && Number.isFinite(saved.left) && Number.isFinite(saved.top) && Number.isFinite(saved.width) && Number.isFinite(saved.height)) return saved;
  } catch (_) {}
  return defaultGeometry(win);
}

function applyGeometry(win, geometry) {
  const bounds = desktopBounds(win);
  const width = clamp(geometry.width, MIN_W, Math.max(MIN_W, bounds.width - 16));
  const height = clamp(geometry.height, MIN_H, Math.max(MIN_H, bounds.height - 16));
  const left = clamp(geometry.left, 4, Math.max(4, bounds.width - width - 4));
  const top = clamp(geometry.top, 4, Math.max(4, bounds.height - height - 4));
  Object.assign(win.style, { left: `${left}px`, top: `${top}px`, width: `${width}px`, height: `${height}px`, right: 'auto', bottom: 'auto', inset: 'auto' });
}

function saveGeometry(win) {
  if (!desktopEnabled() || win.classList.contains('rift-maximized')) return;
  const rect = win.getBoundingClientRect();
  const stageRect = stage.getBoundingClientRect();
  localStorage.setItem(GEOMETRY_KEY(win.dataset.app), JSON.stringify({ left: rect.left - stageRect.left, top: rect.top - stageRect.top, width: rect.width, height: rect.height }));
}

function restoreDesktopGeometry(win) {
  if (!desktopEnabled()) return;
  workspace.classList.remove('hidden');
  stage.classList.remove('hidden');
  stage.classList.remove('rift-stage-minimized');
  win.classList.remove('rift-minimized');
  if (win.classList.contains('rift-maximized')) maximize(win, true);
  else applyGeometry(win, readGeometry(win));
}

function maximizeForMobile(win) {
  win.classList.remove('rift-maximized');
  Object.assign(win.style, { inset: '', left: '', top: '', width: '', height: '', right: '', bottom: '' });
}

function focusWindow(win) {
  activeWindow = win;
  zCounter += 1;
  win.style.zIndex = String(zCounter);
  document.querySelectorAll('.window.rift-desktop-window').forEach(item => item.classList.toggle('rift-focused', item === win));
  document.querySelectorAll('.dock-btn').forEach(btn => btn.classList.toggle('rift-running-active', btn.dataset.open === win.dataset.app));
}

function minimize(win) {
  if (!win) return;
  win.classList.add('rift-minimized');
  stage.classList.add('rift-stage-minimized');
  workspace.classList.remove('hidden');
  document.querySelectorAll('.dock-btn').forEach(btn => btn.classList.toggle('rift-running-active', false));
}

function restore(win) {
  if (!win) return;
  win.classList.remove('rift-minimized');
  stage.classList.remove('rift-stage-minimized');
  stage.classList.remove('hidden');
  if (desktopEnabled()) workspace.classList.remove('hidden');
  else workspace.classList.add('hidden');
  restoreDesktopGeometry(win);
  focusWindow(win);
}

function maximize(win, force = false) {
  if (!win || !desktopEnabled()) return;
  if (!force && win.classList.contains('rift-maximized')) {
    win.classList.remove('rift-maximized');
    applyGeometry(win, readGeometry(win));
    return;
  }
  if (!win.classList.contains('rift-maximized')) saveGeometry(win);
  win.classList.add('rift-maximized');
  Object.assign(win.style, { left: '6px', top: '6px', width: 'calc(100% - 12px)', height: 'calc(100% - 12px)', inset: 'auto', right: 'auto', bottom: 'auto' });
}

function startDrag(event, win) {
  if (!desktopEnabled() || win.classList.contains('rift-maximized') || event.target.closest('button,input,textarea,select,a')) return;
  event.preventDefault();
  focusWindow(win);
  const stageRect = stage.getBoundingClientRect();
  const rect = win.getBoundingClientRect();
  const startX = event.clientX;
  const startY = event.clientY;
  const baseLeft = rect.left - stageRect.left;
  const baseTop = rect.top - stageRect.top;
  const pointerId = event.pointerId;
  event.currentTarget.setPointerCapture?.(pointerId);
  const move = moveEvent => {
    const bounds = desktopBounds(win);
    const next = {
      left: clamp(baseLeft + moveEvent.clientX - startX, 2, Math.max(2, bounds.width - rect.width - 2)),
      top: clamp(baseTop + moveEvent.clientY - startY, 2, Math.max(2, bounds.height - rect.height - 2)),
      width: rect.width,
      height: rect.height
    };
    applyGeometry(win, next);
  };
  const end = () => {
    event.currentTarget.removeEventListener('pointermove', move);
    event.currentTarget.removeEventListener('pointerup', end);
    event.currentTarget.removeEventListener('pointercancel', end);
    saveGeometry(win);
  };
  event.currentTarget.addEventListener('pointermove', move);
  event.currentTarget.addEventListener('pointerup', end, { once: true });
  event.currentTarget.addEventListener('pointercancel', end, { once: true });
}

function startResize(event, win) {
  if (!desktopEnabled() || win.classList.contains('rift-maximized')) return;
  event.preventDefault();
  event.stopPropagation();
  focusWindow(win);
  const rect = win.getBoundingClientRect();
  const startX = event.clientX;
  const startY = event.clientY;
  const pointerId = event.pointerId;
  event.currentTarget.setPointerCapture?.(pointerId);
  const move = moveEvent => applyGeometry(win, {
    left: parseFloat(win.style.left) || 0,
    top: parseFloat(win.style.top) || 0,
    width: rect.width + moveEvent.clientX - startX,
    height: rect.height + moveEvent.clientY - startY
  });
  const end = () => {
    event.currentTarget.removeEventListener('pointermove', move);
    event.currentTarget.removeEventListener('pointerup', end);
    event.currentTarget.removeEventListener('pointercancel', end);
    saveGeometry(win);
  };
  event.currentTarget.addEventListener('pointermove', move);
  event.currentTarget.addEventListener('pointerup', end, { once: true });
  event.currentTarget.addEventListener('pointercancel', end, { once: true });
}

function upgradeWindow(win) {
  if (!(win instanceof HTMLElement) || !win.classList.contains('window') || win.dataset.riftDesktopUpgraded) return;
  win.dataset.riftDesktopUpgraded = '1';
  win.dataset.desktopIndex = String(zCounter);
  win.classList.add('rift-desktop-window');

  const bar = win.querySelector('.window-bar');
  const close = win.querySelector('.window-close');
  if (!bar || !close) return;

  const actions = document.createElement('div');
  actions.className = 'rift-window-actions';
  const minimizeButton = document.createElement('button');
  minimizeButton.type = 'button';
  minimizeButton.className = 'rift-window-minimize';
  minimizeButton.title = 'Minimize';
  minimizeButton.textContent = '—';
  const maximizeButton = document.createElement('button');
  maximizeButton.type = 'button';
  maximizeButton.className = 'rift-window-maximize';
  maximizeButton.title = 'Maximize';
  maximizeButton.textContent = '□';
  close.parentNode?.removeChild(close);
  actions.append(minimizeButton, maximizeButton, close);
  bar.append(actions);

  const resizer = document.createElement('div');
  resizer.className = 'rift-window-resizer';
  resizer.setAttribute('aria-hidden', 'true');
  win.append(resizer);

  minimizeButton.addEventListener('click', event => { event.stopPropagation(); minimize(win); });
  maximizeButton.addEventListener('click', event => { event.stopPropagation(); maximize(win); });
  bar.addEventListener('dblclick', event => { if (!event.target.closest('button')) maximize(win); });
  bar.addEventListener('pointerdown', event => startDrag(event, win));
  resizer.addEventListener('pointerdown', event => startResize(event, win));
  win.addEventListener('pointerdown', () => focusWindow(win), { capture: true });

  activeWindow = win;
  focusWindow(win);
  document.querySelectorAll('.dock-btn').forEach(btn => btn.classList.toggle('rift-running', btn.dataset.open === win.dataset.app));
  if (desktopEnabled()) restoreDesktopGeometry(win);
  else maximizeForMobile(win);
}

const observer = new MutationObserver(records => {
  for (const record of records) {
    for (const node of record.addedNodes) {
      if (node instanceof HTMLElement) {
        if (node.classList.contains('window')) upgradeWindow(node);
        node.querySelectorAll?.('.window').forEach(upgradeWindow);
      }
    }
  }
  const current = stage.querySelector('.window');
  if (!current && activeWindow && !document.contains(activeWindow)) {
    activeWindow = null;
    stage.classList.remove('rift-stage-minimized');
    document.querySelectorAll('.dock-btn').forEach(btn => btn.classList.remove('rift-running', 'rift-running-active'));
  }
});
observer.observe(stage, { childList: true, subtree: true });
stage.querySelectorAll('.window').forEach(upgradeWindow);

// Dock click restores a minimized app instead of destroying/recreating it.
dock.addEventListener('click', event => {
  const button = event.target.closest('.dock-btn');
  if (!button || !activeWindow || !activeWindow.classList.contains('rift-minimized')) return;
  if (button.dataset.open === activeWindow.dataset.app) {
    event.preventDefault();
    event.stopImmediatePropagation();
    restore(activeWindow);
  }
}, true);

controls.querySelector('#riftDesktopToggle').addEventListener('click', cycleDesktopPreference);
controls.querySelector('#riftMouseToggle').addEventListener('click', () => setVirtualMouse(!inputState.virtualMouse));

const pad = touchpad.querySelector('#riftTouchpadSurface');
pad.addEventListener('pointerdown', event => {
  event.preventDefault();
  pad.setPointerCapture?.(event.pointerId);
  padPointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
  padMoved = false;
  clearTimeout(longPressTimer);
  if (padPointers.size === 1) longPressTimer = setTimeout(() => { dispatchVirtualClick(2); padMoved = true; }, 550);
});
pad.addEventListener('pointermove', event => {
  const prior = padPointers.get(event.pointerId);
  if (!prior) return;
  const dx = event.clientX - prior.x;
  const dy = event.clientY - prior.y;
  padPointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
  if (Math.abs(dx) + Math.abs(dy) > 2) {
    padMoved = true;
    clearTimeout(longPressTimer);
  }
  if (padPointers.size >= 2) dispatchVirtualScroll(dy * 2.3);
  else moveCursor(cursorX + dx * 1.45, cursorY + dy * 1.45);
});
const endPadPointer = event => {
  const wasSingle = padPointers.size === 1;
  padPointers.delete(event.pointerId);
  clearTimeout(longPressTimer);
  if (wasSingle && !padMoved) dispatchVirtualClick(0);
};
pad.addEventListener('pointerup', endPadPointer);
pad.addEventListener('pointercancel', endPadPointer);
touchpad.querySelectorAll('[data-mouse-button]').forEach(button => button.addEventListener('click', () => dispatchVirtualClick(Number(button.dataset.mouseButton || 0))));

window.addEventListener('pointerdown', event => {
  if (event.pointerType === 'mouse') {
    inputState.mouse = true;
    root.classList.add('rift-hardware-mouse');
    updateInputLabel();
  }
}, true);

window.addEventListener('keydown', event => {
  inputState.keyboard = true;
  root.classList.add('rift-hardware-keyboard');
  updateInputLabel();

  if (event.altKey && event.key === 'Tab') {
    event.preventDefault();
    const buttons = [...document.querySelectorAll('.dock-btn[data-open]')].filter(btn => btn.dataset.open !== 'home');
    if (!buttons.length) return;
    const active = buttons.findIndex(btn => btn.classList.contains('active') || btn.classList.contains('rift-running-active'));
    buttons[(active + (event.shiftKey ? -1 : 1) + buttons.length) % buttons.length]?.click();
    return;
  }
  if (event.ctrlKey && event.altKey && event.key.toLowerCase() === 'd') {
    event.preventDefault();
    cycleDesktopPreference();
    return;
  }
  if (event.ctrlKey && event.altKey && event.key.toLowerCase() === 'm') {
    event.preventDefault();
    setVirtualMouse(!inputState.virtualMouse);
    return;
  }
  if (event.key === 'Escape' && desktopEnabled() && activeWindow && !event.target.matches('input,textarea,select')) {
    minimize(activeWindow);
  }
}, true);

window.addEventListener('resize', () => {
  applyDesktopMode();
  if (activeWindow && desktopEnabled() && !activeWindow.classList.contains('rift-maximized') && !activeWindow.classList.contains('rift-minimized')) {
    const rect = activeWindow.getBoundingClientRect();
    const stageRect = stage.getBoundingClientRect();
    applyGeometry(activeWindow, { left: rect.left - stageRect.left, top: rect.top - stageRect.top, width: rect.width, height: rect.height });
  }
});

window.addEventListener('riftos:launcher-ready', () => {
  applyDesktopMode();
  document.querySelectorAll('.app-card').forEach(card => card.setAttribute('draggable', 'false'));
});

applyDesktopMode();
updateInputLabel();
moveCursor(cursorX, cursorY);

window.RiftDesktop = Object.freeze({
  get mode() { return desktopEnabled() ? 'desktop' : 'mobile'; },
  get input() { return { ...inputState }; },
  enable() { desktopPreference = 'on'; localStorage.setItem(STATE_KEY, desktopPreference); applyDesktopMode(); },
  disable() { desktopPreference = 'off'; localStorage.setItem(STATE_KEY, desktopPreference); applyDesktopMode(); },
  auto() { desktopPreference = 'auto'; localStorage.setItem(STATE_KEY, desktopPreference); applyDesktopMode(); },
  virtualMouse(enabled = true) { setVirtualMouse(enabled); },
  restore() { restore(activeWindow); },
  minimize() { minimize(activeWindow); },
  maximize() { maximize(activeWindow); }
});
