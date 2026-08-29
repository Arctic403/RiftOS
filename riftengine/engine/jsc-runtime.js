const DEFAULT_TIMEOUT = 120000;

export class RiftJSCRuntime {
  constructor(options = {}) {
    this.workerURL = options.workerURL || new URL('./jsc-worker.js', import.meta.url);
    this.timeout = options.timeout || DEFAULT_TIMEOUT;
    this.sequence = 0;
  }

  evaluate(source, options = {}) {
    const id = ++this.sequence;
    const timeout = options.timeout || this.timeout;
    const stdout = [];
    const stderr = [];
    const lifecycle = [];
    let domSnapshot = null;
    const started = performance.now();
    const worker = new Worker(this.workerURL);

    return new Promise((resolve, reject) => {
      let settled = false;

      const cleanup = () => {
        worker.terminate();
        clearTimeout(timer);
      };

      const fail = (message) => {
        if (settled) return;
        settled = true;
        cleanup();
        const lastStage = lifecycle.at(-1);
        const detail = lastStage ? `${message || 'RiftJSC execution failed.'} Last stage: ${lastStage}.` : (message || 'RiftJSC execution failed.');
        const error = new Error(detail);
        error.stdout = stdout;
        error.stderr = stderr;
        error.lifecycle = lifecycle;
        error.domSnapshot = domSnapshot;
        reject(error);
      };

      const succeed = () => {
        if (settled) return;
        settled = true;
        cleanup();
        resolve({
          stdout,
          stderr,
          lifecycle,
          domSnapshot,
          durationMs: performance.now() - started,
          engine: 'JavaScriptCore',
          runtime: 'RiftEngine JSC wasm v1',
          domRuntime: domSnapshot?.version || null
        });
      };

      const timer = setTimeout(() => {
        fail(`RiftJSC timed out after ${timeout} ms.`);
      }, timeout);

      worker.onmessage = (event) => {
        const message = event.data || {};
        if (message.id !== id) return;

        if (message.type === 'stdout') stdout.push(String(message.text ?? ''));
        else if (message.type === 'stderr') stderr.push(String(message.text ?? ''));
        else if (message.type === 'status') lifecycle.push(String(message.stage ?? 'unknown'));
        else if (message.type === 'dom') domSnapshot = message.snapshot || null;
        else if (message.type === 'done') succeed();
        else if (message.type === 'error') fail(message.error);
      };

      worker.onerror = (event) => {
        fail(event.message || 'RiftJSC worker crashed.');
      };

      worker.postMessage({ id, source: String(source) });
    });
  }

  async smoke() {
    const result = await this.evaluate('print("RIFT_JSC_BROWSER=" + (20 + 22));');
    result.ok = result.stdout.includes('RIFT_JSC_BROWSER=42');
    return result;
  }

  async domSmoke() {
    const result = await this.evaluate(`
      const title = document.createElement('h1');
      title.textContent = 'Hello from JavaScriptCore';
      const note = document.createElement('p');
      note.textContent = 'Rendered through RiftDOM v0.1';
      document.body.append(title, note);
    `);
    result.ok = result.domSnapshot?.body?.children?.length >= 2;
    return result;
  }
}

export const RiftJSC = new RiftJSCRuntime();

if (typeof window !== 'undefined') {
  window.RiftJSC = RiftJSC;
  window.dispatchEvent(new CustomEvent('rift:jsc-bridge-ready', {
    detail: { engine: 'JavaScriptCore', runtime: 'RiftEngine JSC wasm v1' }
  }));
}

export default RiftJSC;
