(() => {
  'use strict';
  if (window.__RIFT_MCP_APP_V1__) return;
  window.__RIFT_MCP_APP_V1__ = true;

  const VERSION = 'rift-mcp-app-v1.2-ai-session';
  const CONTEXT_MARKER = '[RIFT_MCP_APP_V1]';
  const RESULT_MARKER = '[RIFT_MCP_RESULT_V1]';
  const CALL_OPEN = '<rift_call>';
  const CALL_CLOSE = '</rift_call>';
  const MAX_CONTEXT_CHARS = 5000;
  const MAX_CALLS_PER_MINUTE = 24;
  const PROCESS_DELAY_MS = 180;

  const pending = new Map();
  const processedCalls = new Set();
  const recentCallTimes = [];
  const touchedAssistantMessages = new Set();
  const touchedUserMessages = new Set();
  const lastAssistantText = new WeakMap();
  let requestCounter = 0;
  let tools = [];
  let mcpReady = false;
  let bootComplete = false;
  let enabled = true;
  let suppressDecoration = false;
  let callQueue = Promise.resolve();
  let badge = null;
  let processTimer = 0;
  let routeKey = location.pathname + location.search;
  let contextSentForRoute = false;
  let activeAiSessionId = '';
  let aiTaskActive = false;
  let aiStopRequested = false;
  let activeToolRoundTrips = 0;
  let assistantSeen = false;
  let lastAssistantUpdateAt = 0;
  let lastToolResultAt = 0;
  let continuationRequired = false;
  let completionTimer = 0;

  function now() { return Date.now(); }

  function sendAiEvent(type, message, data) {
    try {
      if (!window.RiftMcpNative || typeof window.RiftMcpNative.postMessage !== 'function') return;
      const payload = data && typeof data === 'object' ? { ...data } : {};
      if (activeAiSessionId && !payload.sessionId) payload.sessionId = activeAiSessionId;
      window.RiftMcpNative.postMessage(JSON.stringify({
        jsonrpc: '2.0',
        method: 'rift/ai/event',
        params: { type: String(type || 'transport'), message: String(message || '').slice(0, 1000), data: payload }
      }));
    } catch (_) {}
  }

  function sendAiPhase(phase, message, type = 'transport', data = {}) {
    sendAiEvent(type, message, { ...data, phase, sessionId: activeAiSessionId || data.sessionId || '' });
  }

  function clearCompletionTimer() {
    if (completionTimer) clearTimeout(completionTimer);
    completionTimer = 0;
  }

  function stopButtonVisible() {
    const stop = document.querySelector('[data-testid="stop-button"],button[aria-label*="Stop" i]');
    return stop instanceof Element && isVisible(stop) && !stop.disabled;
  }

  function finishAiTask(phase, message, type = 'transport') {
    if (!aiTaskActive && phase !== 'error') return;
    const sessionId = activeAiSessionId;
    clearCompletionTimer();
    sendAiEvent(type, message, { phase, sessionId });
    aiTaskActive = false;
    aiStopRequested = false;
    activeToolRoundTrips = 0;
    continuationRequired = false;
    activeAiSessionId = '';
  }

  function scheduleCompletionCheck(delayMs = 1300) {
    if (!aiTaskActive) return;
    clearCompletionTimer();
    completionTimer = setTimeout(() => {
      completionTimer = 0;
      if (!aiTaskActive) return;
      if (activeToolRoundTrips > 0 || stopButtonVisible()) {
        scheduleCompletionCheck(900);
        return;
      }
      if (aiStopRequested) {
        finishAiTask('stopped', 'ChatGPT Web task stopped');
        return;
      }
      if (!assistantSeen || continuationRequired) {
        scheduleCompletionCheck(900);
        return;
      }
      const stableFor = now() - lastAssistantUpdateAt;
      if (stableFor < 1800 || (lastToolResultAt && now() - lastToolResultAt < 2200)) {
        scheduleCompletionCheck(900);
        return;
      }
      finishAiTask('complete', 'ChatGPT Web task complete');
    }, delayMs);
  }

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

  function compactSchema(schema) {
    const properties = schema && schema.properties && typeof schema.properties === 'object' ? schema.properties : {};
    const out = {};
    for (const [name, spec] of Object.entries(properties)) {
      out[name] = spec && typeof spec === 'object' && spec.type ? spec.type : 'any';
    }
    return {
      args: out,
      required: Array.isArray(schema && schema.required) ? schema.required : []
    };
  }

  function toolManifest() {
    const compact = tools.map((tool) => ({
      name: tool.name,
      ...compactSchema(tool.inputSchema || {})
    }));
    return JSON.stringify(compact).slice(0, MAX_CONTEXT_CHARS);
  }

  function contextBlock() {
    return `${CONTEXT_MARKER}\nLocal Rift MCP tools: ${toolManifest()}\n` +
      `When a Rift tool is needed, reply with ONLY one ${CALL_OPEN}{"call_id":"unique-id","name":"tool_name","args":{}}${CALL_CLOSE} envelope and no extra prose. ` +
      `Wait for ${RESULT_MARKER} before continuing. Never claim success without that result.`;
  }

  function refreshRouteState() {
    const next = location.pathname + location.search;
    if (next === routeKey) return;
    if (contextSentForRoute && (routeKey === '/' || routeKey.startsWith('/?')) && next.startsWith('/c/')) {
      routeKey = next;
      return;
    }
    routeKey = next;
    contextSentForRoute = false;
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
      element.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: value }));
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
    refreshRouteState();
    if (!enabled || suppressDecoration || tools.length === 0 || contextSentForRoute) return;
    const composer = findComposer();
    if (!composer) return;
    const text = readComposer(composer).trim();
    if (!text || text.includes(CONTEXT_MARKER) || text.startsWith(RESULT_MARKER)) return;
    writeComposer(composer, `${text}\n\n${contextBlock()}`);
    contextSentForRoute = true;
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

  async function waitForComposer(timeoutMs) {
    const deadline = now() + timeoutMs;
    while (now() < deadline) {
      const composer = findComposer();
      if (composer) return composer;
      await new Promise((resolve) => setTimeout(resolve, 150));
    }
    return null;
  }

  async function waitForMcpReady(timeoutMs) {
    const deadline = now() + timeoutMs;
    while (now() < deadline) {
      if (mcpReady) return true;
      if (bootComplete && !mcpReady) return false;
      await new Promise((resolve) => setTimeout(resolve, 120));
    }
    return mcpReady;
  }

  async function submitAiTask(payload) {
    const sessionId = String(payload && payload.sessionId || '').trim();
    const task = String(payload && payload.task || '').trim();
    const projectContext = String(payload && payload.projectContext || '').trim();
    if (!sessionId) { sendAiEvent('error', 'Rift AI session id is missing', { phase: 'error' }); return false; }
    if (aiTaskActive) { sendAiEvent('error', 'A Rift AI task is already active', { phase: 'error', sessionId }); return false; }

    activeAiSessionId = sessionId;
    aiTaskActive = true;
    aiStopRequested = false;
    activeToolRoundTrips = 0;
    assistantSeen = false;
    lastAssistantUpdateAt = 0;
    lastToolResultAt = 0;
    continuationRequired = false;
    clearCompletionTimer();

    if (!task) { finishAiTask('error', 'Rift AI task is empty', 'error'); return false; }
    sendAiPhase('waiting', 'Waiting for local MCP manifest');
    if (!await waitForMcpReady(12000)) {
      finishAiTask('error', 'Local Rift MCP did not initialize; task was not submitted', 'error');
      return false;
    }
    sendAiPhase('waiting', 'Waiting for ChatGPT Web composer');
    const composer = await waitForComposer(20000);
    if (!composer) {
      finishAiTask('error', 'ChatGPT Web composer unavailable. Open Web view to sign in or inspect the page.', 'error');
      return false;
    }
    refreshRouteState();
    let message = task;
    if (projectContext) message += `

${projectContext}`;
    if (tools.length && !contextSentForRoute) {
      message += `

${contextBlock()}`;
      contextSentForRoute = true;
    }
    suppressDecoration = true;
    writeComposer(composer, message);
    const button = await waitForSendButton(12000);
    if (!button) {
      suppressDecoration = false;
      finishAiTask('error', 'ChatGPT Web send button unavailable', 'error');
      return false;
    }
    button.click();
    sendAiPhase('submitted', 'Task submitted to ChatGPT Web');
    sendAiPhase('running', 'ChatGPT Web is working');
    setTimeout(() => { suppressDecoration = false; }, 800);
    scheduleCompletionCheck(1800);
    return true;
  }

  function stopAiTask() {
    if (!aiTaskActive) return false;
    aiStopRequested = true;
    const stop = document.querySelector('[data-testid="stop-button"],button[aria-label*="Stop" i]');
    if (stop instanceof HTMLElement && !stop.disabled) stop.click();
    sendAiPhase('stopping', 'Stop requested');
    scheduleCompletionCheck(500);
    return true;
  }

  async function submitToolResult(payload) {
    const message = `${RESULT_MARKER}\n${JSON.stringify(payload)}\nContinue using this result. If another Rift tool is required, emit exactly one ${CALL_OPEN}...${CALL_CLOSE} envelope.`;
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

    const aiSessionId = aiTaskActive ? activeAiSessionId : '';
    if (aiSessionId) activeToolRoundTrips += 1;
    setBadge('busy');
    try {
      const params = { name: call.name, arguments: call.args };
      if (aiSessionId) params._meta = { 'riftos/aiSessionId': aiSessionId };
      const result = await postRpc('tools/call', params);
      const structured = result.structuredContent || {};
      const ok = !result.isError && structured.ok !== false;
      await submitToolResult({
        call_id: call.call_id,
        name: call.name,
        ok,
        result: structured.value !== undefined ? structured.value : null,
        error: structured.error || null
      });
      if (aiSessionId && aiTaskActive && aiSessionId === activeAiSessionId) {
        lastToolResultAt = now();
        continuationRequired = true;
        sendAiPhase('running', `${call.name} result returned to ChatGPT Web`, 'transport', { tool: call.name });
      }
      setBadge(ok ? 'ready' : 'error');
    } catch (error) {
      try {
        await submitToolResult({
          call_id: call.call_id,
          name: call.name,
          ok: false,
          error: String(error.message || error)
        });
        if (aiSessionId && aiTaskActive && aiSessionId === activeAiSessionId) {
          lastToolResultAt = now();
          continuationRequired = true;
        }
      } catch (submitError) {
        if (aiSessionId && aiTaskActive && aiSessionId === activeAiSessionId) {
          finishAiTask('error', `Could not return ${call.name} result to ChatGPT Web: ${String(submitError && submitError.message || submitError)}`, 'error');
        }
      }
      setBadge('error');
    } finally {
      if (aiSessionId) activeToolRoundTrips = Math.max(0, activeToolRoundTrips - 1);
      if (aiTaskActive) scheduleCompletionCheck(900);
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

  function stripToolEnvelopes(text) {
    let out = String(text || '');
    while (true) {
      const start = out.indexOf(CALL_OPEN);
      if (start < 0) break;
      const end = out.indexOf(CALL_CLOSE, start + CALL_OPEN.length);
      if (end < 0) { out = out.slice(0, start); break; }
      out = out.slice(0, start) + out.slice(end + CALL_CLOSE.length);
    }
    return out.replace(/\n{3,}/g, '\n\n').trim();
  }

  function scanAssistantMessage(message) {
    if (!enabled || !(message instanceof Element)) return;
    const text = String(message.innerText || message.textContent || '');
    const calls = tools.length && text.includes(CALL_OPEN) && text.includes(CALL_CLOSE) ? extractCalls(text) : [];
    const visibleText = stripToolEnvelopes(text);
    if (visibleText && lastAssistantText.get(message) !== visibleText) {
      lastAssistantText.set(message, visibleText);
      if (aiTaskActive) {
        assistantSeen = true;
        lastAssistantUpdateAt = now();
        if (continuationRequired && lastAssistantUpdateAt >= lastToolResultAt) continuationRequired = false;
      }
      sendAiEvent('assistant', 'Assistant output updated', {
        messageId: String(message.getAttribute('data-message-id') || message.id || ''),
        text: visibleText.slice(0, 180000)
      });
    }
    if (calls.length) {
      for (const packet of calls) {
        callQueue = callQueue.then(() => executeCall(packet)).catch((error) => {
          setBadge('error');
          if (aiTaskActive) finishAiTask('error', `Rift tool pipeline failed: ${String(error && error.message || error)}`, 'error');
        });
      }
      return;
    }
    if (aiTaskActive && visibleText) scheduleCompletionCheck();
  }

  function compactInjectedUserMessage(message) {
    if (!(message instanceof Element)) return;
    const content = message.querySelector('.whitespace-pre-wrap');
    if (!content || content.childElementCount > 0) return;
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

  function collectMessage(container, role, targetSet) {
    if (!(container instanceof Element)) return;
    if (container.matches(`[data-message-author-role="${role}"]`)) targetSet.add(container);
    const closest = container.closest(`[data-message-author-role="${role}"]`);
    if (closest) targetSet.add(closest);
    for (const nested of container.querySelectorAll(`[data-message-author-role="${role}"]`)) targetSet.add(nested);
  }

  function collectMutation(mutation) {
    const base = mutation.target instanceof Element ? mutation.target : mutation.target.parentElement;
    if (base) {
      collectMessage(base, 'assistant', touchedAssistantMessages);
      collectMessage(base, 'user', touchedUserMessages);
    }
    for (const node of mutation.addedNodes || []) {
      if (!(node instanceof Element)) continue;
      collectMessage(node, 'assistant', touchedAssistantMessages);
      collectMessage(node, 'user', touchedUserMessages);
    }
  }

  function flushTouchedMessages() {
    processTimer = 0;
    const assistant = Array.from(touchedAssistantMessages);
    const users = Array.from(touchedUserMessages);
    touchedAssistantMessages.clear();
    touchedUserMessages.clear();
    for (const message of assistant) scanAssistantMessage(message);
    for (const message of users) compactInjectedUserMessage(message);
  }

  function scheduleTouchedMessages() {
    if (processTimer) return;
    processTimer = setTimeout(flushTouchedMessages, PROCESS_DELAY_MS);
  }

  function scanRecentMessagesOnce() {
    const assistant = document.querySelectorAll('[data-message-author-role="assistant"]');
    const users = document.querySelectorAll('[data-message-author-role="user"]');
    for (let i = Math.max(0, assistant.length - 4); i < assistant.length; i++) scanAssistantMessage(assistant[i]);
    for (let i = Math.max(0, users.length - 4); i < users.length; i++) compactInjectedUserMessage(users[i]);
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
      mcpReady = tools.length > 0;
      bootComplete = true;
      setBadge(mcpReady ? 'ready' : 'error');
      sendAiEvent(mcpReady ? 'transport' : 'error', mcpReady ? `Rift MCP ready · ${tools.length} local tools` : 'Rift MCP returned no tools');
    } catch (error) {
      tools = [];
      mcpReady = false;
      bootComplete = true;
      setBadge('error');
      sendAiEvent('error', `Rift MCP initialization failed: ${String(error && error.message || error)}`);
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

    // PERF_GUARD: mutations only enqueue the message elements they actually touched.
    // Never query every chat message for every streaming token.
    const observer = new MutationObserver((mutations) => {
      refreshRouteState();
      for (const mutation of mutations) collectMutation(mutation);
      if (touchedAssistantMessages.size || touchedUserMessages.size) scheduleTouchedMessages();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
    scanRecentMessagesOnce();
  }

  window.RiftMcpAppControl = Object.freeze({
    submitTask: (payload) => submitAiTask(payload).catch((error) => {
      const message = `ChatGPT Web task failed: ${String(error && error.message || error)}`;
      if (aiTaskActive) finishAiTask('error', message, 'error');
      else sendAiEvent('error', message, { phase: 'error' });
      return false;
    }),
    stop: stopAiTask,
    state: () => ({ version: VERSION, enabled, tools: tools.length, route: routeKey, aiTaskActive })
  });

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot, { once: true });
  else boot();
})();
