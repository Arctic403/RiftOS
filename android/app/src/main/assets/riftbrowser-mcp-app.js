(() => {
  'use strict';
  if (window.__RIFT_MCP_APP_V1__) return;
  window.__RIFT_MCP_APP_V1__ = true;

  const VERSION = 'rift-mcp-app-v1';
  const CONTEXT_MARKER = '[RIFT_MCP_APP_V1]';
  const RESULT_MARKER = '[RIFT_MCP_RESULT_V1]';
  const CALL_OPEN = '<rift_call>';
  const CALL_CLOSE = '</rift_call>';
  const MAX_CONTEXT_CHARS = 14000;
  const MAX_CALLS_PER_MINUTE = 24;

  const pending = new Map();
  const processedCalls = new Set();
  const recentCallTimes = [];
  let requestCounter = 0;
  let tools = [];
  let enabled = true;
  let suppressDecoration = false;
  let callQueue = Promise.resolve();
  let badge = null;

  function now() { return Date.now(); }

  function postRpc(method, params) {
    return new Promise((resolve, reject) => {
      const id = `rift-web-${now()}-${++requestCounter}`;
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`Rift MCP timeout: ${method}`));
      }, 30000);
      pending.set(id, { resolve, reject, timer });
      try {
        if (!window.RiftMcpNative || typeof window.RiftMcpNative.postMessage !== 'function') {
          throw new Error('Rift MCP native bridge unavailable');
        }
        window.RiftMcpNative.postMessage(JSON.stringify({
          jsonrpc: '2.0',
          id,
          method,
          params: params || {}
        }));
      } catch (error) {
        clearTimeout(timer);
        pending.delete(id);
        reject(error);
      }
    });
  }

  window.RiftMcpAppNative = Object.freeze({
    __receive(response) {
      if (!response || response.id == null) return;
      const slot = pending.get(String(response.id));
      if (!slot) return;
      pending.delete(String(response.id));
      clearTimeout(slot.timer);
      if (response.error) slot.reject(new Error(response.error.message || 'Rift MCP request failed'));
      else slot.resolve(response.result || {});
    }
  });

  function toolManifest() {
    const compact = tools.map((tool) => ({
      name: tool.name,
      description: tool.description,
      inputSchema: tool.inputSchema || { type: 'object' }
    }));
    return JSON.stringify(compact).slice(0, MAX_CONTEXT_CHARS);
  }

  function contextBlock() {
    return `${CONTEXT_MARKER}\nRiftBrowser has a local MCP tool host. Available tools: ${toolManifest()}\n` +
      `When a Rift tool is needed, reply with ONLY one ${CALL_OPEN}{"call_id":"unique-id","name":"tool_name","args":{}}${CALL_CLOSE} envelope and no markdown fence or extra prose. ` +
      `Wait for a ${RESULT_MARKER} message before continuing. Never claim a Rift tool succeeded without that result. Use only listed tools.`;
  }

  function isVisible(element) {
    if (!(element instanceof Element)) return false;
    const rect = element.getBoundingClientRect();
    const style = getComputedStyle(element);
    return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
  }

  function findComposer() {
    const preferred = document.querySelector('#prompt-textarea');
    if (preferred && isVisible(preferred)) return preferred;
    const candidates = Array.from(document.querySelectorAll('textarea,[contenteditable="true"]')).filter(isVisible);
    let best = null;
    let bestScore = -Infinity;
    for (const element of candidates) {
      const rect = element.getBoundingClientRect();
      const id = (element.id || '').toLowerCase();
      const testId = (element.getAttribute('data-testid') || '').toLowerCase();
      const label = (element.getAttribute('aria-label') || '').toLowerCase();
      let score = rect.bottom;
      if (id.includes('prompt')) score += 5000;
      if (testId.includes('prompt') || testId.includes('composer')) score += 4000;
      if (label.includes('message') || label.includes('prompt')) score += 3000;
      if (rect.top > innerHeight * 0.45) score += 1000;
      if (score > bestScore) { best = element; bestScore = score; }
    }
    return best;
  }

  function readComposer(element) {
    if (!element) return '';
    if ('value' in element) return String(element.value || '');
    return String(element.innerText || element.textContent || '');
  }

  function setNativeValue(element, value) {
    const proto = Object.getPrototypeOf(element);
    const descriptor = proto && Object.getOwnPropertyDescriptor(proto, 'value');
    if (descriptor && typeof descriptor.set === 'function') descriptor.set.call(element, value);
    else element.value = value;
  }

  function writeComposer(element, value) {
    if (!element) return false;
    element.focus();
    if ('value' in element) setNativeValue(element, value);
    else element.textContent = value;
    try {
      element.dispatchEvent(new InputEvent('input', {
        bubbles: true,
        inputType: 'insertText',
        data: value
      }));
    } catch (_) {
      element.dispatchEvent(new Event('input', { bubbles: true }));
    }
    element.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }

  function looksLikeSendButton(button) {
    if (!(button instanceof Element) || !isVisible(button)) return false;
    const testId = (button.getAttribute('data-testid') || '').toLowerCase();
    const label = (button.getAttribute('aria-label') || '').toLowerCase();
    if (testId === 'send-button' || testId.includes('send-button')) return true;
    return label === 'send' || label.includes('send message');
  }

  function findSendButton() {
    const direct = document.querySelector('[data-testid="send-button"]');
    if (direct && looksLikeSendButton(direct)) return direct;
    return Array.from(document.querySelectorAll('button')).find(looksLikeSendButton) || null;
  }

  function decorateOutgoingPrompt() {
    if (!enabled || suppressDecoration || tools.length === 0) return;
    const composer = findComposer();
    if (!composer) return;
    const text = readComposer(composer).trim();
    if (!text || text.includes(CONTEXT_MARKER) || text.startsWith(RESULT_MARKER)) return;
    writeComposer(composer, `${text}\n\n${contextBlock()}`);
  }

  async function waitForSendButton(timeoutMs) {
    const deadline = now() + timeoutMs;
    while (now() < deadline) {
      const button = findSendButton();
      if (button && !button.disabled) return button;
      await new Promise((resolve) => setTimeout(resolve, 120));
    }
    return null;
  }

  async function submitToolResult(payload) {
    const message = `${RESULT_MARKER}\n${JSON.stringify(payload)}\nContinue the answer using this result. If another Rift tool is required, emit exactly one ${CALL_OPEN}...${CALL_CLOSE} envelope.`;
    const composer = findComposer();
    if (!composer) throw new Error('ChatGPT composer unavailable');
    suppressDecoration = true;
    writeComposer(composer, message);
    const button = await waitForSendButton(12000);
    if (!button) {
      suppressDecoration = false;
      throw new Error('ChatGPT send button unavailable');
    }
    button.click();
    setTimeout(() => { suppressDecoration = false; }, 800);
  }

  function rateLimitAllowsCall() {
    const cutoff = now() - 60000;
    while (recentCallTimes.length && recentCallTimes[0] < cutoff) recentCallTimes.shift();
    if (recentCallTimes.length >= MAX_CALLS_PER_MINUTE) return false;
    recentCallTimes.push(now());
    return true;
  }

  function normalizeCall(packet) {
    if (!packet || typeof packet !== 'object') throw new Error('Tool call must be an object');
    const callId = String(packet.call_id || '').trim();
    const name = String(packet.name || '').trim();
    const args = packet.args && typeof packet.args === 'object' && !Array.isArray(packet.args) ? packet.args : {};
    if (!callId || callId.length > 160) throw new Error('Invalid call_id');
    if (!tools.some((tool) => tool.name === name)) throw new Error(`Unknown Rift tool: ${name}`);
    return { call_id: callId, name, args };
  }

  async function executeCall(packet) {
    let call;
    try {
      call = normalizeCall(packet);
    } catch (error) {
      await submitToolResult({ ok: false, error: String(error.message || error) });
      return;
    }
    const signature = `${call.call_id}:${call.name}:${JSON.stringify(call.args)}`;
    if (processedCalls.has(signature)) return;
    processedCalls.add(signature);
    if (!rateLimitAllowsCall()) {
      await submitToolResult({ call_id: call.call_id, name: call.name, ok: false, error: 'Rift MCP browser rate limit reached' });
      return;
    }
    setBadge('busy');
    try {
      const result = await postRpc('tools/call', { name: call.name, arguments: call.args });
      const structured = result.structuredContent || {};
      const ok = !result.isError && structured.ok !== false;
      await submitToolResult({
        call_id: call.call_id,
        name: call.name,
        ok,
        result: structured.value !== undefined ? structured.value : null,
        error: structured.error || null
      });
      setBadge(ok ? 'ready' : 'error');
    } catch (error) {
      await submitToolResult({
        call_id: call.call_id,
        name: call.name,
        ok: false,
        error: String(error.message || error)
      });
      setBadge('error');
    }
  }

  function extractCalls(text) {
    const calls = [];
    let cursor = 0;
    while (true) {
      const start = text.indexOf(CALL_OPEN, cursor);
      if (start < 0) break;
      const end = text.indexOf(CALL_CLOSE, start + CALL_OPEN.length);
      if (end < 0) break;
      const raw = text.slice(start + CALL_OPEN.length, end).trim();
      try { calls.push(JSON.parse(raw)); } catch (_) {}
      cursor = end + CALL_CLOSE.length;
    }
    return calls;
  }

  function scanAssistantMessages() {
    if (!enabled || tools.length === 0) return;
    const messages = document.querySelectorAll('[data-message-author-role="assistant"]');
    for (const message of messages) {
      const text = String(message.innerText || message.textContent || '');
      if (!text.includes(CALL_OPEN) || !text.includes(CALL_CLOSE)) continue;
      for (const packet of extractCalls(text)) {
        callQueue = callQueue.then(() => executeCall(packet)).catch(() => setBadge('error'));
      }
    }
  }

  function compactInjectedUserMessages() {
    const messages = document.querySelectorAll('[data-message-author-role="user"]');
    for (const message of messages) {
      const content = message.querySelector('.whitespace-pre-wrap');
      if (!content || content.childElementCount > 0) continue;
      const text = String(content.textContent || '');
      if (text.includes(CONTEXT_MARKER)) {
        content.textContent = text.split(`\n\n${CONTEXT_MARKER}`)[0];
      } else if (text.startsWith(RESULT_MARKER)) {
        let label = '↔ Rift tool result';
        try {
          const line = text.split('\n')[1];
          const parsed = JSON.parse(line || '{}');
          if (parsed.name) label += ` · ${parsed.name}`;
          label += parsed.ok === false ? ' · blocked/error' : ' · ok';
        } catch (_) {}
        content.textContent = label;
      }
    }
  }

  function ensureBadge() {
    if (badge && badge.isConnected) return badge;
    badge = document.createElement('button');
    badge.id = 'rift-mcp-app-badge';
    badge.type = 'button';
    badge.style.cssText = 'position:fixed;right:14px;bottom:14px;z-index:2147483646;border:1px solid rgba(127,127,127,.35);border-radius:999px;padding:7px 10px;background:rgba(18,20,24,.92);color:#fff;font:12px system-ui,sans-serif;box-shadow:0 4px 16px rgba(0,0,0,.22);cursor:pointer;';
    badge.addEventListener('click', () => {
      enabled = !enabled;
      setBadge(enabled ? 'ready' : 'off');
    });
    (document.documentElement || document.body).appendChild(badge);
    return badge;
  }

  function setBadge(state) {
    const element = ensureBadge();
    const suffix = state === 'busy' ? ' • running' : state === 'error' ? ' • error' : state === 'off' ? ' • off' : '';
    element.textContent = `Rift MCP • ${tools.length}${suffix}`;
    element.title = enabled ? `${VERSION}: local MCP tools enabled for this tab` : `${VERSION}: click to enable`;
  }

  async function boot() {
    ensureBadge();
    setBadge('busy');
    try {
      await postRpc('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'riftbrowser-mcp-app', version: VERSION } });
      const result = await postRpc('tools/list', {});
      tools = Array.isArray(result.tools) ? result.tools : [];
      setBadge('ready');
    } catch (_) {
      tools = [];
      setBadge('error');
    }

    document.addEventListener('click', (event) => {
      const button = event.target instanceof Element ? event.target.closest('button') : null;
      if (button && looksLikeSendButton(button)) decorateOutgoingPrompt();
    }, true);

    document.addEventListener('keydown', (event) => {
      if (event.key !== 'Enter' || event.shiftKey || event.ctrlKey || event.metaKey || event.altKey) return;
      const composer = findComposer();
      if (!composer) return;
      if (event.target === composer || composer.contains(event.target)) decorateOutgoingPrompt();
    }, true);

    const observer = new MutationObserver(() => {
      scanAssistantMessages();
      compactInjectedUserMessages();
      ensureBadge();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
    scanAssistantMessages();
    compactInjectedUserMessages();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot, { once: true });
  else boot();
})();
