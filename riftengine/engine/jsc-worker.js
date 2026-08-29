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

self.onmessage = (event) => {
  if (activeId !== null) return;

  const { id, source } = event.data || {};
  activeId = id;

  if (typeof source !== 'string') {
    finish(false, { error: 'RiftJSC requires JavaScript source text.' });
    return;
  }

  const jscBase = new URL('../jsc-dist/', self.location.href);

  self.Module = {
    arguments: ['-e', source],
    locateFile(name) {
      return new URL(name, jscBase).href;
    },
    print(text) {
      send('stdout', { text: String(text) });
    },
    printErr(text) {
      send('stderr', { text: String(text) });
    },
    onAbort(reason) {
      finish(false, { error: `JSC aborted: ${String(reason)}` });
    },
    postRun() {
      finish(true);
    }
  };

  try {
    importScripts(new URL('jsc.js', jscBase).href);
  } catch (error) {
    const name = error?.name || '';
    const message = error?.message || String(error);
    if (!finished && name !== 'ExitStatus') {
      finish(false, { error: message });
    }
  }
};
