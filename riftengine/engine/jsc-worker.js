let engineBooted = false;
let engineLoading = false;
let activeRequest = null;
const queue = [];

function sendFor(id, type, payload = {}) {
  self.postMessage({ id, type, ...payload });
}

function send(type, payload = {}) {
  if (!activeRequest) return;
  sendFor(activeRequest.id, type, payload);
}

function finishRequest(ok, payload = {}) {
  if (!activeRequest || activeRequest.finished) return;
  const request = activeRequest;
  request.finished = true;
  sendFor(request.id, ok ? 'done' : 'error', {
    session: {
      workerPersistent: true,
      wasmReused: Boolean(request.warm),
      reentry: request.reentry || (request.warm ? 'unknown' : 'initial-run')
    },
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
    __snapshot() { return { version: 'RiftDOM/0.2-session', body: this.body.__snapshot() }; }
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

function buildWrappedSource(request) {
  const completionSentinel = `__RIFT_JSC_DONE__:${request.id}`;
  const domSentinel = `__RIFT_DOM__:${request.id}:`;
  const metaSentinel = `__RIFT_META__:${request.id}:`;
  request.completionSentinel = completionSentinel;
  request.domSentinel = domSentinel;
  request.metaSentinel = metaSentinel;
  return `${buildRiftDOMPrelude()}\n${request.source}\n;print(${JSON.stringify(domSentinel)} + JSON.stringify(document.__snapshot()));\n;print(${JSON.stringify(metaSentinel)} + JSON.stringify({evalCount: globalThis.__RIFT_SESSION_EVAL_COUNT__, contextToken: globalThis.__RIFT_CONTEXT_TOKEN__}));\n;print(${JSON.stringify(completionSentinel)});`;
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

function runWarm(request) {
  activeRequest = request;
  request.warm = true;
  request.reentry = 'callMain';
  const wrappedSource = buildWrappedSource(request);
  send('status', { stage: 'warm-worker-reused' });
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

function bootEngine(request) {
  activeRequest = request;
  engineLoading = true;
  request.warm = false;
  request.reentry = 'initial-run';
  const wrappedSource = buildWrappedSource(request);
  const jscBase = new URL('../jsc-dist/', self.location.href);

  send('status', { stage: 'worker-started' });

  self.Module = {
    arguments: ['-e', wrappedSource],
    locateFile(name) {
      const url = new URL(name, jscBase).href;
      if (name.endsWith('.wasm')) send('status', { stage: 'wasm-requested' });
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
    send('status', { stage: 'loading-jsc-shell' });
    importScripts(new URL('jsc.js', jscBase).href);
    engineBooted = true;
    engineLoading = false;
    if (activeRequest) send('status', { stage: 'jsc-shell-loaded' });
  } catch (error) {
    engineLoading = false;
    const name = error?.name || '';
    if (activeRequest && !activeRequest.finished && name !== 'ExitStatus') {
      finishRequest(false, { error: error?.message || String(error) });
    }
  }
}

function processQueue() {
  if (activeRequest || engineLoading || queue.length === 0) return;
  const request = queue.shift();
  if (!engineBooted) bootEngine(request);
  else runWarm(request);
}

self.onmessage = (event) => {
  const data = event.data || {};
  if (data.type === 'reset') {
    sendFor(data.id ?? null, 'reset', { ok: true });
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
