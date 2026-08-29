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
  const completionSentinel = `__RIFT_JSC_DONE__:${id}`;
  const wrappedSource = `${source}\n;print(${JSON.stringify(completionSentinel)});`;

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
