const DEFAULT_TIMEOUT = 120000;

function freshSessionInfo() {
  return {
    workerPersistent: true,
    wasmReused: false,
    contextPersistent: null,
    mode: 'uninitialized',
    reentry: 'not-tested'
  };
}

export class RiftJSCRuntime {
  constructor(options = {}) {
    this.workerURL = options.workerURL || new URL('./jsc-worker.js', import.meta.url);
    this.timeout = options.timeout || DEFAULT_TIMEOUT;
    this.sequence = 0;
    this.worker = null;
    this.pending = new Map();
    this.sessionInfo = freshSessionInfo();
  }

  ensureWorker() {
    if (this.worker) return this.worker;
    const worker = new Worker(this.workerURL);
    this.worker = worker;

    worker.onmessage = event => {
      const message = event.data || {};
      const request = this.pending.get(message.id);
      if (!request) return;

      if (request.kind === 'control') {
        if (message.type === 'runtime-info' || message.type === 'reset') this.finishControl(request, true, message);
        else if (message.type === 'error') this.finishControl(request, false, message);
        return;
      }

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
        if (request.kind === 'control') this.finishControl(request, false, { error: message, code: 'worker-crash' });
        else this.finishRequest(request, false, { error: message, code: 'worker-crash' });
      }
      this.disposeWorker();
    };

    return worker;
  }

  disposeWorker() {
    if (this.worker) this.worker.terminate();
    this.worker = null;
  }

  mergeSession(session = {}) {
    if (session.workerPersistent != null) this.sessionInfo.workerPersistent = Boolean(session.workerPersistent);
    if (session.wasmReused != null) this.sessionInfo.wasmReused = Boolean(session.wasmReused);
    if (session.contextPersistent != null) this.sessionInfo.contextPersistent = Boolean(session.contextPersistent);
    if (session.mode) this.sessionInfo.mode = session.mode;
    if (session.reentry) this.sessionInfo.reentry = session.reentry;
    return { ...this.sessionInfo, ...session };
  }

  finishRequest(request, ok, message = {}) {
    if (!request || request.settled) return;
    request.settled = true;
    clearTimeout(request.timer);
    this.pending.delete(request.id);
    const session = this.mergeSession(message.session || {});

    if (ok) {
      request.resolve({
        stdout: request.stdout,
        stderr: request.stderr,
        lifecycle: request.lifecycle,
        domSnapshot: request.domSnapshot,
        meta: request.meta,
        value: message.value,
        session,
        durationMs: performance.now() - request.started,
        engine: 'JavaScriptCore',
        runtime: session.mode === 'persistent-host' ? 'RiftJSC persistent host v1' : 'RiftEngine JSC wasm v1',
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
    error.session = session;
    request.reject(error);
  }

  finishControl(request, ok, message = {}) {
    if (!request || request.settled) return;
    request.settled = true;
    clearTimeout(request.timer);
    this.pending.delete(request.id);
    const session = this.mergeSession(message.session || {});

    if (ok) {
      request.resolve({ ok: message.ok !== false, workerClosed: Boolean(message.workerClosed), session });
      return;
    }

    const error = new Error(message.error || `RiftJSC ${request.controlType} failed.`);
    error.code = message.code || null;
    error.session = session;
    request.reject(error);
  }

  _evaluateOnce(source, options = {}) {
    const id = ++this.sequence;
    const timeout = options.timeout || this.timeout;
    const worker = this.ensureWorker();

    return new Promise((resolve, reject) => {
      const request = {
        id,
        kind: 'evaluate',
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

  _control(controlType, options = {}) {
    const id = ++this.sequence;
    const timeout = options.timeout || Math.min(this.timeout, 15000);
    const worker = this.ensureWorker();

    return new Promise((resolve, reject) => {
      const request = {
        id,
        kind: 'control',
        controlType,
        settled: false,
        resolve,
        reject,
        timer: null
      };
      request.timer = setTimeout(() => {
        this.finishControl(request, false, {
          error: `RiftJSC ${controlType} timed out after ${timeout} ms.`,
          code: 'control-timeout'
        });
      }, timeout);
      this.pending.set(id, request);
      worker.postMessage({ id, type: controlType });
    });
  }

  async evaluate(source, options = {}) {
    try {
      return await this._evaluateOnce(source, options);
    } catch (error) {
      if (error.code !== 'warm-reentry-unavailable' || options.allowFreshFallback === false) throw error;
      await this.reset();
      const result = await this._evaluateOnce(source, options);
      result.session.fallbackFresh = true;
      result.session.wasmReused = false;
      return result;
    }
  }

  async status() {
    if (!this.worker) return { ok: true, workerClosed: true, session: { ...this.sessionInfo } };
    return this._control('status-query');
  }

  async reset() {
    const existing = this.worker;
    for (const request of [...this.pending.values()]) {
      if (request.kind === 'control') this.finishControl(request, false, { error: 'RiftJSC session reset.', code: 'session-reset' });
      else this.finishRequest(request, false, { error: 'RiftJSC session reset.', code: 'session-reset' });
    }

    if (!existing) {
      this.sessionInfo = freshSessionInfo();
      return { ok: true, workerClosed: true, session: { ...this.sessionInfo } };
    }

    let result;
    try {
      result = await this._control('reset');
    } catch (error) {
      this.disposeWorker();
      this.sessionInfo = freshSessionInfo();
      throw error;
    }

    if (result.workerClosed) {
      this.disposeWorker();
      this.sessionInfo = freshSessionInfo();
      this.sessionInfo.mode = result.session?.mode || 'uninitialized';
      return { ...result, session: { ...this.sessionInfo } };
    }

    this.sessionInfo = {
      ...freshSessionInfo(),
      ...result.session,
      wasmReused: true,
      contextPersistent: result.session?.mode === 'persistent-host'
    };
    return { ...result, session: { ...this.sessionInfo } };
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
    await this.reset();
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
    const persistentHost = second.session?.mode === 'persistent-host';

    await this.reset();
    const afterReset = await this.evaluate(`
      print('RIFT_SESSION_RESET=' + typeof globalThis.__RIFT_USER_STATE__);
    `, { allowFreshFallback: false });
    const resetCleared = afterReset.stdout.includes('RIFT_SESSION_RESET=undefined');

    this.sessionInfo.contextPersistent = persistentHost && sameContext && statePreserved;
    return {
      ok: persistentHost && wasmReused && sameContext && statePreserved && resetCleared,
      supported: persistentHost,
      first,
      second,
      afterReset,
      mode: second.session?.mode,
      wasmReused,
      contextPersistent: sameContext && statePreserved,
      resetCleared,
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
      runtime: 'RiftJSC persistent host v1',
      session: 'persistent-host-v1-with-stock-fallback'
    }
  }));
}

export default RiftJSC;
