let activeId = null;
let finished = false;

function send(type, payload = {}) {
  self.postMessage({ id: activeId, type, ...payload });
}

function finish(ok, payload = {}) {
  if (finished) return;
  finished = true;
  send(ok ? 'done' : 'error', payload);
}

function buildRiftDOMPrelude() {
  return String.raw`
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
  set innerHTML(value) {
    this.replaceChildren(new RiftDOMRawNode(String(value ?? '')));
  }
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
  __snapshot() { return { version: 'RiftDOM/0.1', body: this.body.__snapshot() }; }
}
var document = new RiftDOMDocument();
var window = globalThis;
window.document = document;
window.window = window;
`;
}

self.onmessage = (event) => {
  if (activeId !== null) return;

  const { id, source } = event.data || {};
  activeId = id;

  if (typeof source !== 'string') {
    finish(false, { error: 'RiftJSC requires JavaScript source text.' });
    return;
  }

  const jscBase = new URL('../jsc-dist/', self.location.href);
  const completionSentinel = `__RIFT_JSC_DONE__:${id}`;
  const domSentinel = `__RIFT_DOM__:${id}:`;
  const wrappedSource = `${buildRiftDOMPrelude()}\n${source}\n;print(${JSON.stringify(domSentinel)} + JSON.stringify(document.__snapshot()));\n;print(${JSON.stringify(completionSentinel)});`;

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
    print(text) {
      const value = String(text);
      if (value === completionSentinel) {
        send('status', { stage: 'javascript-complete' });
        finish(true);
        return;
      }
      if (value.startsWith(domSentinel)) {
        try {
          send('dom', { snapshot: JSON.parse(value.slice(domSentinel.length)) });
          send('status', { stage: 'rift-dom-snapshot' });
        } catch (error) {
          send('stderr', { text: `RiftDOM snapshot parse failed: ${error?.message || error}` });
        }
        return;
      }
      send('stdout', { text: value });
    },
    printErr(text) {
      send('stderr', { text: String(text) });
    },
    onAbort(reason) {
      finish(false, { error: `JSC aborted: ${String(reason)}` });
    },
    postRun() {
      send('status', { stage: 'post-run' });
      finish(true);
    }
  };

  try {
    send('status', { stage: 'loading-jsc-shell' });
    importScripts(new URL('jsc.js', jscBase).href);
    send('status', { stage: 'jsc-shell-loaded' });
  } catch (error) {
    const name = error?.name || '';
    const message = error?.message || String(error);
    if (!finished && name !== 'ExitStatus') {
      finish(false, { error: message });
    }
  }
};
