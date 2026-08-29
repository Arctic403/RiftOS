let engineBooted = false;
let engineLoading = false;
let activeRequest = null;
let runtimeMode = 'uninitialized';
let persistentModule = null;
let persistentCreate = null;
let persistentEvaluate = null;
let persistentDestroy = null;
let persistentAlive = null;
let persistentEvalCount = 0;
const queue = [];

function sendFor(id, type, payload = {}) {
  self.postMessage({ id, type, ...payload });
}

function send(type, payload = {}) {
  if (!activeRequest) return;
  sendFor(activeRequest.id, type, payload);
}

function sessionPayload(request = activeRequest) {
  const persistent = runtimeMode === 'persistent-host';
  return {
    workerPersistent: true,
    wasmReused: Boolean(request?.warm),
    contextPersistent: persistent ? true : null,
    mode: runtimeMode,
    reentry: request?.reentry || (persistent ? 'host-eval' : (request?.warm ? 'unknown' : 'initial-run'))
  };
}

function finishRequest(ok, payload = {}) {
  if (!activeRequest || activeRequest.finished) return;
  const request = activeRequest;
  request.finished = true;
  sendFor(request.id, ok ? 'done' : 'error', {
    session: sessionPayload(request),
    ...payload
  });
  activeRequest = null;
  queueMicrotask(processQueue);
}

function buildRiftDOMPrelude() {
  return String.raw`
if (!globalThis.__RIFT_DOM_BOOTSTRAPPED__) {
  class RiftDOMNode {
    constructor(type, tag = '', text = '') {
      this.__type = type;
      this.tagName = tag ? String(tag).toUpperCase() : '';
      this.nodeName = this.tagName || (type === 'text' ? '#text' : '#node');
      this.parentNode = null;
      this.childNodes = [];
      this.attributes = {};
      this.__text = String(text || '');
    }
    appendChild(node) {
      if (!node || typeof node !== 'object') throw new TypeError('appendChild requires a RiftDOM node');
      if (node.parentNode) node.parentNode.removeChild(node);
      node.parentNode = this;
      this.childNodes.push(node);
      return node;
    }
    append(...nodes) {
      for (const node of nodes) this.appendChild(typeof node === 'string' ? document.createTextNode(node) : node);
    }
    removeChild(node) {
      const index = this.childNodes.indexOf(node);
      if (index < 0) throw new Error('Node is not a child of this parent');
      this.childNodes.splice(index, 1);
      node.parentNode = null;
      return node;
    }
    replaceChildren(...nodes) {
      for (const child of this.childNodes) child.parentNode = null;
      this.childNodes = [];
      this.append(...nodes);
    }
    setAttribute(name, value) { this.attributes[String(name)] = String(value); }
    getAttribute(name) { return Object.prototype.hasOwnProperty.call(this.attributes, String(name)) ? this.attributes[String(name)] : null; }
    removeAttribute(name) { delete this.attributes[String(name)]; }
    get children() { return this.childNodes.filter(node => node.__type === 'element'); }
    get textContent() {
      if (this.__type === 'text') return this.__text;
      return this.childNodes.map(node => node.textContent).join('');
    }
    set textContent(value) {
      if (this.__type === 'text') { this.__text = String(value ?? ''); return; }
      this.replaceChildren(document.createTextNode(String(value ?? '')));
    }
    set innerHTML(value) { this.replaceChildren(new RiftDOMRawNode(String(value ?? ''))); }
    get innerHTML() { return this.childNodes.map(node => node.__type === 'raw' ? node.__html : node.textContent).join(''); }
    __snapshot() {
      if (this.__type === 'text') return { type: 'text', text: this.__text };
      return {
        type: 'element',
        tag: this.tagName.toLowerCase(),
        attrs: { ...this.attributes },
        children: this.childNodes.map(node => node.__snapshot())
      };
    }
  }
  class RiftDOMRawNode extends RiftDOMNode {
    constructor(html) { super('raw'); this.__html = String(html); }
    get textContent() { return this.__html; }
    set textContent(value) { this.__html = String(value ?? ''); }
    __snapshot() { return { type: 'raw', html: this.__html }; }
  }
  class RiftDOMDocument {
    constructor() {
      this.documentElement = new RiftDOMNode('element', 'html');
      this.head = new RiftDOMNode('element', 'head');
      this.body = new RiftDOMNode('element', 'body');
      this.documentElement.appendChild(this.head);
      this.documentElement.appendChild(this.body);
    }
    createElement(tag) { return new RiftDOMNode('element', String(tag)); }
    createTextNode(text) { return new RiftDOMNode('text', '', String(text)); }
    getElementById(id) {
      const target = String(id);
      const walk = node => {
        if (node.__type === 'element' && node.getAttribute('id') === target) return node;
        for (const child of node.childNodes || []) { const found = walk(child); if (found) return found; }
        return null;
      };
      return walk(this.documentElement);
    }
    __snapshot() { return { version: 'RiftDOM/0.3-persistent-host', body: this.body.__snapshot() }; }
  }
  globalThis.RiftDOMNode = RiftDOMNode;
  globalThis.RiftDOMRawNode = RiftDOMRawNode;
  globalThis.RiftDOMDocument = RiftDOMDocument;
  globalThis.document = new RiftDOMDocument();
  globalThis.window = globalThis;
  globalThis.window.document = globalThis.document;
  globalThis.__RIFT_DOM_BOOTSTRAPPED__ = true;
}
var document = globalThis.document;
var window = globalThis;
globalThis.__RIFT_SESSION_EVAL_COUNT__ = (globalThis.__RIFT_SESSION_EVAL_COUNT__ || 0) + 1;
if (!globalThis.__RIFT_CONTEXT_TOKEN__) globalThis.__RIFT_CONTEXT_TOKEN__ = String(Date.now()) + ':' + String(Math.random());
`;
}

function buildStockWrappedSource(request) {
  const completionSentinel = `__RIFT_JSC_DONE__:${request.id}`;
  const domSentinel = `__RIFT_DOM__:${request.id}:`;
  const metaSentinel = `__RIFT_META__:${request.id}:`;
  request.completionSentinel = completionSentinel;
  request.domSentinel = domSentinel;
  request.metaSentinel = metaSentinel;
  return `${buildRiftDOMPrelude()}\n${request.source}\n;print(${JSON.stringify(domSentinel)} + JSON.stringify(document.__snapshot()));\n;print(${JSON.stringify(metaSentinel)} + JSON.stringify({evalCount: globalThis.__RIFT_SESSION_EVAL_COUNT__, contextToken: globalThis.__RIFT_CONTEXT_TOKEN__}));\n;print(${JSON.stringify(completionSentinel)});`;
}

function buildPersistentWrappedSource(request) {
  const source = JSON.stringify(String(request.source));
  return `${buildRiftDOMPrelude()}
globalThis.__RIFT_CAPTURE_STDOUT__ = [];
globalThis.print = (...values) => globalThis.__RIFT_CAPTURE_STDOUT__.push(values.map(value => String(value)).join(' '));
(() => {
  let ok = true;
  let error = null;
  let result = null;
  try {
    const value = (0, eval)(${source});
    result = value === undefined ? 'undefined' : String(value);
  } catch (caught) {
    ok = false;
    error = String(caught && caught.stack ? caught.stack : caught);
  }
  return JSON.stringify({
    protocol: 'RiftJSC/host-v1',
    ok,
    error,
    result,
    stdout: globalThis.__RIFT_CAPTURE_STDOUT__.slice(),
    dom: document.__snapshot(),
    meta: {
      evalCount: globalThis.__RIFT_SESSION_EVAL_COUNT__,
      contextToken: globalThis.__RIFT_CONTEXT_TOKEN__
    }
  });
})()`;
}

function handleStdout(text) {
  if (!activeRequest) return;
  const value = String(text);
  if (value === activeRequest.completionSentinel) {
    send('status', { stage: 'javascript-complete' });
    finishRequest(true);
    return;
  }
  if (value.startsWith(activeRequest.domSentinel)) {
    try {
      send('dom', { snapshot: JSON.parse(value.slice(activeRequest.domSentinel.length)) });
      send('status', { stage: 'rift-dom-snapshot' });
    } catch (error) {
      send('stderr', { text: `RiftDOM snapshot parse failed: ${error?.message || error}` });
    }
    return;
  }
  if (value.startsWith(activeRequest.metaSentinel)) {
    try {
      send('meta', { meta: JSON.parse(value.slice(activeRequest.metaSentinel.length)) });
    } catch (error) {
      send('stderr', { text: `RiftJSC session metadata parse failed: ${error?.message || error}` });
    }
    return;
  }
  send('stdout', { text: value });
}

function warmCallMain(args) {
  if (typeof self.callMain === 'function') return self.callMain(args);
  if (typeof callMain === 'function') return callMain(args);
  if (typeof self.Module?.callMain === 'function') return self.Module.callMain(args);
  throw Object.assign(new Error('This JSC shell does not expose callMain for warm re-entry.'), { code: 'warm-reentry-unavailable' });
}

function runStockWarm(request) {
  activeRequest = request;
  request.warm = true;
  request.reentry = 'callMain';
  const wrappedSource = buildStockWrappedSource(request);
  send('status', { stage: 'stock-shell-warm-reentry' });
  try {
    warmCallMain(['-e', wrappedSource]);
    if (activeRequest === request && !request.finished) finishRequest(true);
  } catch (error) {
    const name = error?.name || '';
    if (request.finished || name === 'ExitStatus') return;
    finishRequest(false, {
      error: error?.message || String(error),
      code: error?.code || null
    });
  }
}

function runPersistent(request) {
  activeRequest = request;
  request.warm = persistentEvalCount > 0;
  request.reentry = 'host-eval';
  send('status', { stage: request.warm ? 'persistent-context-reused' : 'persistent-context-first-eval' });

  try {
    if (!persistentAlive?.()) throw new Error('RiftJSC persistent context is not alive.');
    const raw = persistentEvaluate(buildPersistentWrappedSource(request));
    if (typeof raw !== 'string') throw new Error('RiftJSC host returned a non-string result.');
    if (raw.startsWith('RIFT_ERROR:') || raw.startsWith('RIFT_EXCEPTION:')) throw new Error(raw);

    const packet = JSON.parse(raw);
    persistentEvalCount += 1;
    for (const line of packet.stdout || []) send('stdout', { text: String(line) });
    if (packet.dom) {
      send('dom', { snapshot: packet.dom });
      send('status', { stage: 'rift-dom-snapshot' });
    }
    if (packet.meta) send('meta', { meta: packet.meta });
    if (!packet.ok) {
      finishRequest(false, { error: packet.error || 'JavaScriptCore evaluation failed.', code: 'jsc-exception' });
      return;
    }
    send('status', { stage: 'javascript-complete' });
    finishRequest(true, { value: packet.result });
  } catch (error) {
    finishRequest(false, { error: error?.message || String(error), code: 'persistent-host-eval' });
  }
}

async function loadPersistentHost() {
  if (persistentModule && persistentAlive?.()) return true;
  const jscBase = new URL('../jsc-dist/', self.location.href);
  const customBase = new URL('custom/', jscBase);

  send('status', { stage: 'loading-persistent-host' });
  importScripts(new URL('rift-jsc.js', customBase).href);
  const factory = self.createRiftJSC || (typeof createRiftJSC === 'function' ? createRiftJSC : null);
  if (typeof factory !== 'function') throw new Error('createRiftJSC factory was not exported.');

  persistentModule = await factory({
    noInitialRun: true,
    locateFile(name) {
      const url = new URL(name, customBase).href;
      if (name.endsWith('.wasm')) send('status', { stage: 'persistent-wasm-requested' });
      return url;
    },
    printErr(text) { send('stderr', { text: String(text) }); }
  });

  persistentCreate = persistentModule.cwrap('rift_jsc_create', 'number', []);
  persistentEvaluate = persistentModule.cwrap('rift_jsc_eval', 'string', ['string']);
  persistentDestroy = persistentModule.cwrap('rift_jsc_destroy', null, []);
  persistentAlive = persistentModule.cwrap('rift_jsc_alive', 'number', []);

  if (!persistentCreate() || !persistentAlive()) throw new Error('RiftJSC persistent context failed to initialize.');
  persistentEvalCount = 0;
  runtimeMode = 'persistent-host';
  engineBooted = true;
  send('status', { stage: 'persistent-host-ready' });
  return true;
}

function bootStockShell(request) {
  activeRequest = request;
  engineLoading = true;
  runtimeMode = 'stock-shell';
  request.warm = false;
  request.reentry = 'initial-run';
  const wrappedSource = buildStockWrappedSource(request);
  const jscBase = new URL('../jsc-dist/', self.location.href);

  send('status', { stage: 'stock-shell-fallback' });
  self.Module = {
    arguments: ['-e', wrappedSource],
    locateFile(name) {
      const url = new URL(name, jscBase).href;
      if (name.endsWith('.wasm')) send('status', { stage: 'stock-wasm-requested' });
      return url;
    },
    monitorRunDependencies(count) {
      send('status', { stage: count > 0 ? `runtime-loading-${count}` : 'runtime-dependencies-ready' });
    },
    print: handleStdout,
    printErr(text) { send('stderr', { text: String(text) }); },
    onAbort(reason) {
      engineLoading = false;
      finishRequest(false, { error: `JSC aborted: ${String(reason)}` });
    },
    postRun() {
      engineBooted = true;
      engineLoading = false;
      if (activeRequest) {
        send('status', { stage: 'post-run' });
        finishRequest(true);
      }
      processQueue();
    }
  };

  try {
    send('status', { stage: 'loading-stock-jsc-shell' });
    importScripts(new URL('jsc.js', jscBase).href);
    engineBooted = true;
    engineLoading = false;
    if (activeRequest) send('status', { stage: 'stock-jsc-shell-loaded' });
  } catch (error) {
    engineLoading = false;
    const name = error?.name || '';
    if (activeRequest && !activeRequest.finished && name !== 'ExitStatus') {
      finishRequest(false, { error: error?.message || String(error) });
    }
  }
}

async function bootPreferredEngine(request) {
  activeRequest = request;
  engineLoading = true;
  request.warm = false;
  request.reentry = 'host-init';
  send('status', { stage: 'worker-started' });

  try {
    await loadPersistentHost();
    engineLoading = false;
    runPersistent(request);
  } catch (error) {
    persistentModule = null;
    persistentCreate = null;
    persistentEvaluate = null;
    persistentDestroy = null;
    persistentAlive = null;
    persistentEvalCount = 0;
    engineBooted = false;
    engineLoading = false;
    send('stderr', { text: `Persistent RiftJSC host unavailable; using stock fallback: ${error?.message || error}` });
    bootStockShell(request);
  }
}

function processQueue() {
  if (activeRequest || engineLoading || queue.length === 0) return;
  const request = queue.shift();
  if (runtimeMode === 'persistent-host' && engineBooted) runPersistent(request);
  else if (runtimeMode === 'stock-shell' && engineBooted) runStockWarm(request);
  else void bootPreferredEngine(request);
}

function resetPersistentContext(id) {
  try {
    persistentDestroy?.();
    if (persistentAlive?.()) throw new Error('RiftJSC context survived destroy.');
    if (!persistentCreate?.() || !persistentAlive?.()) throw new Error('RiftJSC context failed to recreate.');
    persistentEvalCount = 0;
    sendFor(id, 'reset', {
      ok: true,
      workerClosed: false,
      session: {
        workerPersistent: true,
        wasmReused: true,
        contextPersistent: true,
        mode: 'persistent-host',
        reentry: 'host-reset'
      }
    });
  } catch (error) {
    sendFor(id, 'error', { error: error?.message || String(error), code: 'host-reset-failed' });
  }
}

self.onmessage = (event) => {
  const data = event.data || {};

  if (data.type === 'status-query') {
    sendFor(data.id ?? null, 'runtime-info', { session: sessionPayload(null) });
    return;
  }

  if (data.type === 'reset') {
    if (activeRequest || engineLoading) {
      sendFor(data.id ?? null, 'error', { error: 'RiftJSC is busy and cannot reset yet.', code: 'runtime-busy' });
      return;
    }
    if (runtimeMode === 'persistent-host' && persistentDestroy && persistentCreate) {
      resetPersistentContext(data.id ?? null);
      return;
    }
    sendFor(data.id ?? null, 'reset', {
      ok: true,
      workerClosed: true,
      session: { ...sessionPayload(null), mode: runtimeMode }
    });
    self.close();
    return;
  }

  const { id, source } = data;
  if (typeof source !== 'string') {
    sendFor(id, 'error', { error: 'RiftJSC requires JavaScript source text.' });
    return;
  }
  queue.push({ id, source, finished: false, warm: false, reentry: null });
  processQueue();
};
