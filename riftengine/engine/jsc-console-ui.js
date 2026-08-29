import RiftJSC from './jsc-runtime.js';

const DEFAULT_SOURCE = `print("Hello from RiftJSC");
print("2 + 2 = " + (2 + 2));
print("Engine: JavaScriptCore");`;

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
        <strong>JavaScriptCore WASM Console</strong>
        <p>Runs JavaScript inside RiftEngine's compiled JavaScriptCore worker, not Safari's page JavaScript engine.</p>
      </div>
      <span class="engine-badge" id="jscStatus">READY</span>
    </div>
    <div class="engine-actions">
      <button class="action" id="jscRun" type="button">Run in RiftJSC</button>
      <button class="action secondary" id="jscSmoke" type="button">Smoke test</button>
      <button class="action secondary" id="jscReset" type="button">Reset code</button>
      <button class="action secondary" id="jscClear" type="button">Clear output</button>
    </div>
    <textarea id="jscSource" spellcheck="false" autocapitalize="off" autocomplete="off" style="width:100%;min-height:170px;resize:vertical;font:13px/1.5 ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;background:#080b10;color:#e8eef7;border:1px solid rgba(255,255,255,.12);border-radius:12px;padding:12px;box-sizing:border-box"></textarea>
    <div class="engine-diagnostics" id="jscMeta">
      <div><span>Engine</span><b class="ok">JavaScriptCore</b></div>
      <div><span>Runtime</span><b>JSC wasm v1</b></div>
      <div><span>Thread</span><b>Web Worker</b></div>
      <div><span>Last run</span><b id="jscDuration">—</b></div>
    </div>
    <pre class="engine-log" id="jscOutput">RiftJSC console ready.\n</pre>`;

  const viewport = lab.querySelector('.engine-viewport');
  if (viewport) viewport.insertAdjacentElement('beforebegin', panel);
  else lab.append(panel);

  const source = panel.querySelector('#jscSource');
  const output = panel.querySelector('#jscOutput');
  const status = panel.querySelector('#jscStatus');
  const duration = panel.querySelector('#jscDuration');
  const run = panel.querySelector('#jscRun');
  const smoke = panel.querySelector('#jscSmoke');
  const reset = panel.querySelector('#jscReset');
  const clear = panel.querySelector('#jscClear');

  source.value = DEFAULT_SOURCE;

  const write = (line = '') => {
    output.textContent += `${line}\n`;
    output.scrollTop = output.scrollHeight;
  };

  const setBusy = busy => {
    run.disabled = busy;
    smoke.disabled = busy;
    status.textContent = busy ? 'RUNNING' : 'READY';
    status.classList.toggle('ok', !busy);
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
      duration.textContent = formatDuration(result.durationMs);
      status.textContent = 'PASS';
      status.classList.add('ok');
      write(`[${result.engine} · ${formatDuration(result.durationMs)}]`);
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
      run.disabled = false;
      smoke.disabled = false;
    }
  }

  run.onclick = () => execute(source.value, 'Run in JavaScriptCore').catch(() => {});
  smoke.onclick = async () => {
    setBusy(true);
    duration.textContent = '…';
    write('\n> Browser smoke test');
    try {
      const result = await RiftJSC.smoke();
      result.stdout.forEach(line => write(line));
      result.stderr.forEach(line => write(`ERR ${line}`));
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
      run.disabled = false;
      smoke.disabled = false;
    }
  };
  reset.onclick = () => { source.value = DEFAULT_SOURCE; };
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
