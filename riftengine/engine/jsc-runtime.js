const DEFAULT_TIMEOUT = 120000;

export class RiftJSCRuntime {
  constructor(options = {}) {
    this.workerURL = options.workerURL || new URL('./jsc-worker.js', import.meta.url);
    this.timeout = options.timeout || DEFAULT_TIMEOUT;
    this.sequence = 0;
    this.worker = null;
    this.pending = new Map();
    this.sessionInfo = {
      workerPersistent: true,
      wasmReused: false,
      contextPersistent: null,
      reentry: 'not-tested'
    };
  }

  ensureWorker() {
    if (this.worker) return this.worker;
    const worker = new Worker(this.workerURL);
    this.worker = worker;

    worker.onmessage = event => {
      const message = event.data || {};
      const request = this.pending.get(message.id);
      if (!request) return;

      if (message.type === 'stdout') request.stdout.push(String(message.text ?? ''));
      else if (message.type === 'stderr') request.stderr.push(String(message.text ?? ''));
      else if (message.type === 'status') request.lifecycle.push(String(message.stage ?? 'unknown'));
      else if (message.type === 'dom') request.domSnapshot = message.snapshot || null;
      else if (message.type === 'meta') request.meta = message.meta || null;
      else if (message.type === 'done') this.finishRequest(request, true, message);
      else if (message.type === 'error') this.finishRequest(request, false, message);
    };

    worker.onerror = event => {
      const message = event.message || 'RiftJSC worker crashed.';
      for (const request of [...this.pending.values()]) {
        this.finishRequest(request, false, { error: message, code: 'worker-crash' });
      }
      this.disposeWorker();
    };

    return worker;
  }

  disposeWorker() {
    if (this.worker) this.worker.terminate();
    this.worker = null;
  }

  finishRequest(request, ok, message = {}) {
    if (!request || request.settled) return;
    request.settled = true;
    clearTimeout(request.timer);
    this.pending.delete(request.id);

    const session = message.session || {};
    if (session.workerPersistent != null) this.sessionInfo.workerPersistent = Boolean(session.workerPersistent);
    if (session.wasmReused != null) this.sessionInfo.wasmReused = Boolean(session.wasmReused);
    if (session.reentry) this.sessionInfo.reentry = session.reentry;

    if (ok) {
      request.resolve({
        stdout: request.stdout,
        stderr: request.stderr,
        lifecycle: request.lifecycle,
        domSnapshot: request.domSnapshot,
        meta: request.meta,
        session: { ...this.sessionInfo, ...session },
        durationMs: performance.now() - request.started,
        engine: 'JavaScriptCore',
        runtime: 'RiftEngine JSC wasm v1',
        domRuntime: request.domSnapshot?.version || null
      });
      return;
    }

    const lastStage = request.lifecycle.at(-1);
    const base = message.error || 'RiftJSC execution failed.';
    const detail = lastStage ? `${base} Last stage: ${lastStage}.` : base;
    const error = new Error(detail);
    error.code = message.code || null;
    error.stdout = request.stdout;
    error.stderr = request.stderr;
    error.lifecycle = request.lifecycle;
    error.domSnapshot = request.domSnapshot;
    error.meta = request.meta;
    error.session = { ...this.sessionInfo, ...session };
    request.reject(error);
  }

  _evaluateOnce(source, options = {}) {
    const id = ++this.sequence;
    const timeout = options.timeout || this.timeout;
    const worker = this.ensureWorker();

    return new Promise((resolve, reject) => {
      const request = {
        id,
        stdout: [],
        stderr: [],
        lifecycle: [],
        domSnapshot: null,
        meta: null,
        started: performance.now(),
        settled: false,
        resolve,
        reject,
        timer: null
      };

      request.timer = setTimeout(() => {
        this.finishRequest(request, false, {
          error: `RiftJSC timed out after ${timeout} ms.`,
          code: 'timeout'
        });
      }, timeout);

      this.pending.set(id, request);
      worker.postMessage({ id, source: String(source) });
    });
  }

  async evaluate(source, options = {}) {
    try {
      return await this._evaluateOnce(source, options);
    } catch (error) {
      if (error.code !== 'warm-reentry-unavailable' || options.allowFreshFallback === false) throw error;
      this.reset();
      const result = await this._evaluateOnce(source, options);
      result.session.fallbackFresh = true;
      result.session.wasmReused = false;
      return result;
    }
  }

  reset() {
    for (const request of [...this.pending.values()]) {
      this.finishRequest(request, false, { error: 'RiftJSC session reset.', code: 'session-reset' });
    }
    this.disposeWorker();
    this.sessionInfo = {
      workerPersistent: true,
      wasmReused: false,
      contextPersistent: null,
      reentry: 'not-tested'
    };
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
      note.textContent = 'Rendered through RiftDOM session bridge';
      document.body.append(title, note);
    `);
    result.ok = result.domSnapshot?.body?.children?.length >= 2;
    return result;
  }

  async sessionSmoke() {
    this.reset();
    const first = await this.evaluate(`
      globalThis.__RIFT_USER_STATE__ = 41;
      print('RIFT_SESSION_FIRST=' + globalThis.__RIFT_USER_STATE__);
    `, { allowFreshFallback: false });

    let second;
    try {
      second = await this.evaluate(`
        globalThis.__RIFT_USER_STATE__ = (globalThis.__RIFT_USER_STATE__ || 0) + 1;
        print('RIFT_SESSION_SECOND=' + globalThis.__RIFT_USER_STATE__);
      `, { allowFreshFallback: false });
    } catch (error) {
      return {
        ok: false,
        supported: false,
        first,
        error,
        wasmReused: false,
        contextPersistent: false
      };
    }

    const sameContext = Boolean(
      first.meta?.contextToken &&
      second.meta?.contextToken &&
      first.meta.contextToken === second.meta.contextToken &&
      Number(second.meta.evalCount) > Number(first.meta.evalCount)
    );
    const statePreserved = second.stdout.includes('RIFT_SESSION_SECOND=42');
    const wasmReused = Boolean(second.session?.wasmReused);
    this.sessionInfo.contextPersistent = sameContext && statePreserved;

    return {
      ok: wasmReused && sameContext && statePreserved,
      supported: true,
      first,
      second,
      wasmReused,
      contextPersistent: sameContext && statePreserved,
      coldMs: first.durationMs,
      warmMs: second.durationMs
    };
  }
}

export const RiftJSC = new RiftJSCRuntime();

if (typeof window !== 'undefined') {
  window.RiftJSC = RiftJSC;
  window.dispatchEvent(new CustomEvent('rift:jsc-bridge-ready', {
    detail: {
      engine: 'JavaScriptCore',
      runtime: 'RiftEngine JSC wasm v1',
      session: 'persistent-worker-v0.2'
    }
  }));
}

export default RiftJSC;
