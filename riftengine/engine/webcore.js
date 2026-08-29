import createCore from '../dist/riftengine-core.js';

export default async function createWebCoreModule(options = {}) {
  const canvas = options.canvas;
  if (!canvas) throw new Error('RiftEngine requires a canvas.');

  const module = await createCore({
    print: options.print || console.log,
    printErr: options.printErr || console.error,
    locateFile: (name) => new URL(`../dist/${name}`, import.meta.url).href
  });

  const ctx = canvas.getContext('2d', { alpha: false });
  if (!ctx) throw new Error('RiftEngine prototype requires Canvas 2D.');

  let imageData = null;
  let running = true;
  let lastWidth = 0;
  let lastHeight = 0;

  const resize = () => {
    const rect = canvas.getBoundingClientRect();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const width = Math.max(1, Math.round(rect.width * dpr));
    const height = Math.max(1, Math.round(rect.height * dpr));
    if (width === lastWidth && height === lastHeight) return;

    lastWidth = width;
    lastHeight = height;
    canvas.width = width;
    canvas.height = height;
    module._rift_engine_resize(width, height);
    imageData = new ImageData(width, height);
  };

  const draw = (now) => {
    if (!running) return;
    resize();
    module._rift_engine_tick(now);

    const width = module._rift_engine_width();
    const height = module._rift_engine_height();
    const ptr = module._rift_engine_pixels();
    const bytes = width * height * 4;

    if (!imageData || imageData.width !== width || imageData.height !== height) {
      imageData = new ImageData(width, height);
    }

    imageData.data.set(module.HEAPU8.subarray(ptr, ptr + bytes));
    ctx.putImageData(imageData, 0, 0);
    requestAnimationFrame(draw);
  };

  const pointer = (event) => {
    const rect = canvas.getBoundingClientRect();
    if (!rect.width || !rect.height) return;
    const x = ((event.clientX - rect.left) / rect.width) * canvas.width;
    const y = ((event.clientY - rect.top) / rect.height) * canvas.height;
    const type = event.type === 'pointerdown' ? 1 : event.type === 'pointerup' ? 2 : 0;
    module._rift_engine_pointer(type, x, y);
  };

  canvas.addEventListener('pointerdown', pointer, { passive: true });
  canvas.addEventListener('pointermove', pointer, { passive: true });
  canvas.addEventListener('pointerup', pointer, { passive: true });

  resize();
  module._rift_engine_create(canvas.width, canvas.height);
  requestAnimationFrame(draw);

  module.riftEngine = {
    stage: 'port-harness',
    stop() { running = false; },
    loadHTML(html) {
      const length = module.lengthBytesUTF8(html) + 1;
      const ptr = module._malloc(length);
      try {
        module.stringToUTF8(html, ptr, length);
        return !!module._rift_engine_load_html(ptr);
      } finally {
        module._free(ptr);
      }
    }
  };

  return module;
}

export { createWebCoreModule };
