import RiftJSC from './jsc-runtime.js';
import { renderRiftDOM } from './rift-dom.js';

const DEFAULT_SOURCE = `const card = document.createElement("section");
card.setAttribute("class", "demo-card");

const title = document.createElement("h1");
title.textContent = "Hello from RiftJSC";

const text = document.createElement("p");
text.textContent = "This DOM tree was created inside JavaScriptCore WASM.";

card.append(title, text);
document.body.appendChild(card);
print("RiftDOM document created");`;

function escapeHTML(value) {
  return String(value).replace(/[&<>"']/g, ch => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;'
  }[ch]));
}

function formatDuration(ms) {
  if (!Number.isFinite(ms)) return '—';
  return `${ms.toFixed(ms < 100 ? 1 : 0)} ms`;
}

function mountConsole(lab) {
  if (!lab || lab.querySelector('[data-rift-jsc-console]')) return;

  const panel = document.createElement('section');
  panel.className = 'engine-jsc-console';
  panel.dataset.riftJscConsole = 'true';
  panel.innerHTML = `
    <div class="engine-head">
      <div>
        <strong>JavaScriptCore + RiftDOM Session</strong>
        <p>RiftOS now keeps one JSC worker alive and attempts warm re-entry into the already-instantiated WASM engine. Session smoke verifies whether the current shell also preserves the same JSC global context.</p>
      </div>
      <span class="engine-badge" id="jscStatus">READY</span>
    </div>
    <div class="engine-actions">
      <button class="action" id="jscSessionSmoke" type="button">Session smoke</button>
      <button class="action" id="jscDomSmoke" type="button">DOM smoke</button>
      <button class="action secondary" id="jscRun" type="button">Run code</button>
      <button class="action secondary" id="jscSmoke" type="button">JSC smoke</button>
      <button class="action secondary" id="jscReset" type="button">Reset session</button>
      <button class="action secondary" id="jscClear" type="button">Clear output</button>
    </div>
    <textarea id="jscSource" spellcheck="false" autocapitalize="off" autocomplete="off" style="width:100%;min-height:190px;resize:vertical;font:13px/1.5 ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;background:#080b10;color:#e8eef7;border:1px solid rgba(255,255,255,.12);border-radius:12px;padding:12px;box-sizing:border-box"></textarea>
    <section style="border:1px solid rgba(255,255,255,.12);border-radius:14px;overflow:hidden;background:#fff;color:#111;min-height:180px">
      <div style="padding:8px 10px;background:#11161d;color:#8995a6;font:10px ui-monospace,SFMono-Regular,Menlo,monospace;border-bottom:1px solid #27303d">RiftDOM preview</div>
      <div id="jscDomPreview" style="min-height:150px;padding:20px;overflow:auto"><p style="margin:0;color:#667085">Run DOM smoke or execute code that modifies document.body.</p></div>
    </section>
    <div class="engine-diagnostics" id="jscMeta">
      <div><span>Engine</span><b class="ok">JavaScriptCore</b></div>
      <div><span>DOM</span><b class="ok">RiftDOM session</b></div>
      <div><span>Worker</span><b id="jscWorkerState">Persistent</b></div>
      <div><span>WASM reuse</span><b id="jscWasmState">Not tested</b></div>
      <div><span>JSC context</span><b id="jscContextState">Not tested</b></div>
      <div><span>Last run</span><b id="jscDuration">—</b></div>
    </div>
    <pre class="engine-log" id="jscOutput">RiftJSC session console ready.\n</pre>`;

  const viewport = lab.querySelector('.engine-viewport');
  if (viewport) viewport.insertAdjacentElement('beforebegin', panel);
  else lab.append(panel);

  const source = panel.querySelector('#jscSource');
  const output = panel.querySelector('#jscOutput');
  const preview = panel.querySelector('#jscDomPreview');
  const status = panel.querySelector('#jscStatus');
  const duration = panel.querySelector('#jscDuration');
  const workerState = panel.querySelector('#jscWorkerState');
  const wasmState = panel.querySelector('#jscWasmState');
  const contextState = panel.querySelector('#jscContextState');
  const run = panel.querySelector('#jscRun');
  const smoke = panel.querySelector('#jscSmoke');
  const domSmoke = panel.querySelector('#jscDomSmoke');
  const sessionSmoke = panel.querySelector('#jscSessionSmoke');
  const reset = panel.querySelector('#jscReset');
  const clear = panel.querySelector('#jscClear');
  const buttons = [run, smoke, domSmoke, sessionSmoke];

  source.value = DEFAULT_SOURCE;

  const write = (line = '') => {
    output.textContent += `${line}\n`;
    output.scrollTop = output.scrollHeight;
  };

  const setBusy = busy => {
    buttons.forEach(button => { button.disabled = busy; });
    status.textContent = busy ? 'RUNNING' : 'READY';
    status.classList.toggle('ok', !busy);
  };

  const renderResult = result => {
    if (!result?.domSnapshot) return;
    renderRiftDOM(result.domSnapshot, preview);
    write(`[${result.domRuntime || 'RiftDOM'} · rendered]`);
  };

  const showSession = result => {
    const session = result?.session || {};
    workerState.textContent = session.workerPersistent === false ? 'Fresh' : 'Persistent';
    if (session.wasmReused === true) {
      wasmState.textContent = 'REUSED';
      wasmState.classList.add('ok');
    } else if (session.fallbackFresh) {
      wasmState.textContent = 'Fresh fallback';
      wasmState.classList.remove('ok');
    }
  };

  async function execute(code, label = 'Execution') {
    if (!code.trim()) return;
    setBusy(true);
    duration.textContent = '…';
    write(`\n> ${label}`);
    try {
      const result = await RiftJSC.evaluate(code);
      result.stdout.forEach(line => write(line));
      result.stderr.forEach(line => write(`ERR ${line}`));
      renderResult(result);
      showSession(result);
      duration.textContent = formatDuration(result.durationMs);
      status.textContent = 'PASS';
      status.classList.add('ok');
      write(`[${result.engine} · ${formatDuration(result.durationMs)}]`);
      if (result.session?.wasmReused) write('[session · warm WASM reused]');
      if (result.session?.fallbackFresh) write('[session · current shell required a fresh-worker fallback]');
      return result;
    } catch (error) {
      (error.stdout || []).forEach(line => write(line));
      (error.stderr || []).forEach(line => write(`ERR ${line}`));
      status.textContent = 'ERROR';
      status.classList.remove('ok');
      duration.textContent = 'failed';
      write(`ERROR ${error.message || error}`);
      throw error;
    } finally {
      buttons.forEach(button => { button.disabled = false; });
    }
  }

  run.onclick = () => execute(source.value, 'Run in JavaScriptCore + RiftDOM').catch(() => {});

  sessionSmoke.onclick = async () => {
    setBusy(true);
    duration.textContent = '…';
    wasmState.textContent = 'Testing…';
    contextState.textContent = 'Testing…';
    write('\n> Persistent session smoke test');
    try {
      const result = await RiftJSC.sessionSmoke();
      result.first?.stdout?.forEach(line => write(line));
      result.second?.stdout?.forEach(line => write(line));
      if (!result.supported) {
        status.textContent = 'PARTIAL';
        status.classList.remove('ok');
        wasmState.textContent = 'No warm re-entry';
        contextState.textContent = 'Needs engine API';
        duration.textContent = formatDuration(result.first?.durationMs);
        write('PARTIAL: persistent worker is live, but this prebuilt JSC shell does not expose warm callMain re-entry.');
        write(`DETAIL: ${result.error?.message || 'warm re-entry unavailable'}`);
        return;
      }

      wasmState.textContent = result.wasmReused ? 'REUSED' : 'NOT REUSED';
      wasmState.classList.toggle('ok', result.wasmReused);
      contextState.textContent = result.contextPersistent ? 'PERSISTENT' : 'RESET PER RUN';
      contextState.classList.toggle('ok', result.contextPersistent);
      duration.textContent = formatDuration(result.warmMs);

      if (result.ok) {
        status.textContent = 'PASS';
        status.classList.add('ok');
        write(`PASS: same WASM + same JSC context. Cold ${formatDuration(result.coldMs)} → warm ${formatDuration(result.warmMs)}`);
      } else if (result.wasmReused) {
        status.textContent = 'PARTIAL';
        status.classList.remove('ok');
        write(`PARTIAL: WASM instance reused (${formatDuration(result.warmMs)} warm), but JSC global state resets between shell invocations.`);
        write('NEXT: persistent JSC evaluation API is required before timers/events can hold real JS callbacks.');
      } else {
        status.textContent = 'FAIL';
        status.classList.remove('ok');
        write('FAIL: warm session reuse did not succeed.');
      }
    } catch (error) {
      status.textContent = 'ERROR';
      status.classList.remove('ok');
      duration.textContent = 'failed';
      write(`ERROR ${error.message || error}`);
    } finally {
      buttons.forEach(button => { button.disabled = false; });
    }
  };

  domSmoke.onclick = async () => {
    setBusy(true);
    duration.textContent = '…';
    write('\n> RiftDOM smoke test');
    try {
      const result = await RiftJSC.domSmoke();
      result.stdout.forEach(line => write(line));
      result.stderr.forEach(line => write(`ERR ${line}`));
      renderResult(result);
      showSession(result);
      duration.textContent = formatDuration(result.durationMs);
      status.textContent = result.ok ? 'PASS' : 'FAIL';
      status.classList.toggle('ok', result.ok);
      write(result.ok
        ? 'PASS: real JSC created a DOM tree and RiftOS rendered it'
        : 'FAIL: RiftDOM snapshot did not contain the expected nodes');
    } catch (error) {
      status.textContent = 'ERROR';
      status.classList.remove('ok');
      duration.textContent = 'failed';
      write(`ERROR ${error.message || error}`);
    } finally {
      buttons.forEach(button => { button.disabled = false; });
    }
  };

  smoke.onclick = async () => {
    setBusy(true);
    duration.textContent = '…';
    write('\n> Browser smoke test');
    try {
      const result = await RiftJSC.smoke();
      result.stdout.forEach(line => write(line));
      result.stderr.forEach(line => write(`ERR ${line}`));
      showSession(result);
      duration.textContent = formatDuration(result.durationMs);
      status.textContent = result.ok ? 'PASS' : 'FAIL';
      status.classList.toggle('ok', result.ok);
      write(result.ok
        ? 'PASS: real RiftJSC returned RIFT_JSC_BROWSER=42'
        : `FAIL: expected RIFT_JSC_BROWSER=42; got ${escapeHTML(result.stdout.join(' | '))}`);
    } catch (error) {
      status.textContent = 'ERROR';
      status.classList.remove('ok');
      duration.textContent = 'failed';
      write(`ERROR ${error.message || error}`);
    } finally {
      buttons.forEach(button => { button.disabled = false; });
    }
  };

  reset.onclick = () => {
    RiftJSC.reset();
    wasmState.textContent = 'Not tested';
    wasmState.classList.remove('ok');
    contextState.textContent = 'Not tested';
    contextState.classList.remove('ok');
    duration.textContent = '—';
    status.textContent = 'READY';
    status.classList.add('ok');
    write('\n[session reset · next run will cold boot JSC WASM]');
  };
  clear.onclick = () => { output.textContent = ''; };
}

function scan() {
  document.querySelectorAll('.engine-lab').forEach(mountConsole);
}

const observer = new MutationObserver(scan);
observer.observe(document.documentElement, { childList: true, subtree: true });
window.addEventListener('rift:jsc-bridge-ready', scan);
document.addEventListener('DOMContentLoaded', scan);
scan();
