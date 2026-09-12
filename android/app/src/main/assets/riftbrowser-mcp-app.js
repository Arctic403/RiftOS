(() => {
  'use strict';
  if (window.__RIFT_MCP_APP_V1__) return;
  window.__RIFT_MCP_APP_V1__ = true;

  const VERSION = 'rift-mcp-app-v2.1.0-raw-chat';
  const CONTEXT_MARKER = '[RIFT_MCP_RAW_V1]';
  const RESULT_MARKER = '[RIFT_RESULT]';
  const CALL_OPEN = '[RIFT_CALL]';
  const CALL_CLOSE = '[RIFT_END]';
  const MAX_CONTEXT_CHARS = 5000;
  const MAX_RESULT_CHARS = 48000;
  const MAX_CALLS_PER_MINUTE = 24;
  const PROCESS_DELAY_MS = 180;
  const RESULT_ACK_TIMEOUT_MS = 12000;
  const INCOMPLETE_CALL_GRACE_MS = 1400;
  const MAX_CHAT_TARGETS = 180;

  const pending = new Map();
  const processedCalls = new Map();
  const recentCallTimes = [];
  const touchedAssistantMessages = new Set();
  const touchedUserMessages = new Set();
  const lastAssistantText = new WeakMap();
  const historicalAssistantMessages = new WeakSet();
  const protocolIssueFingerprints = new WeakMap();
  const incompleteCallTimers = new WeakMap();
  const acknowledgedResultIds = new Set();
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
  let toolExecutionArmed = false;
  let toolLoopState = 'idle';
  let continuationBaseline = new WeakSet();
  let continuationBaselineKeys = new Set();
  let pendingResultId = '';
  let consumedContinuationResultId = '';
  let continuationWaitStartedAt = 0;
  let resultCounter = 0;
  let recoveryAttempt = 0;
  let queuedAiPayload = null;
  let taskPumpRunning = false;
  // AI website adapters may prepare messages, but user approval is required before submission.
  let manualSendApprovalRequired = true;
  let pendingApprovedSubmission = null;

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
    sendAiEvent(type, message, { phase, sessionId, toolLoopState });
    aiTaskActive = false;
    aiStopRequested = false;
    activeToolRoundTrips = 0;
    continuationRequired = false;
    activeAiSessionId = '';
    toolExecutionArmed = false;
    toolLoopState = 'idle';
    continuationBaseline = new WeakSet();
    continuationBaselineKeys = new Set();
    pendingResultId = '';
    consumedContinuationResultId = '';
    continuationWaitStartedAt = 0;
    recoveryAttempt = 0;
  }

  function toolLoopBusy() {
    return toolLoopState === 'executing-tool' ||
      toolLoopState === 'delivering-result' ||
      toolLoopState === 'waiting-result-ack' ||
      toolLoopState === 'waiting-continuation';
  }

  function assistantMessageKey(message) {
    if (!(message instanceof Element)) return '';
    return String(message.getAttribute('data-message-id') || message.id || '').trim();
  }

  function markContinuationBaseline() {
    continuationBaseline = new WeakSet();
    continuationBaselineKeys = new Set();
    for (const message of document.querySelectorAll('[data-message-author-role="assistant"]')) {
      continuationBaseline.add(message);
      const key = assistantMessageKey(message);
      if (key) continuationBaselineKeys.add(key);
    }
  }

  function isContinuationBaseline(message) {
    if (continuationBaseline.has(message)) return true;
    const key = assistantMessageKey(message);
    return Boolean(key && continuationBaselineKeys.has(key));
  }

  function resultIdForPayload() {
    return `result-${now()}-${++resultCounter}`;
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
      if (toolLoopState === 'waiting-continuation' && continuationRequired && continuationWaitStartedAt &&
          now() - continuationWaitStartedAt >= 45000) {
        finishAiTask('error', 'ChatGPT continuation was not observed after Rift result delivery', 'error');
        return;
      }
      if (!assistantSeen || continuationRequired || toolLoopBusy() || !latestAssistantHasCompletableOutput()) {
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
    const lines = tools.map((tool) => {
      const schema = compactSchema(tool.inputSchema || {});
      const args = Object.entries(schema.args).map(([name, type]) => `${name}:${type}${schema.required.includes(name) ? '*' : ''}`).join(', ');
      return `- ${tool.name}${args ? ` (${args})` : ''}`;
    });
    return lines.join('\n').slice(0, MAX_CONTEXT_CHARS);
  }

  function codeModeGuide() {
    if (!tools.some((tool) => tool.name === 'rift_workspace_exec')) return '';
    return `\nRift Code Mode + Project Intelligence v1: prefer rift_workspace_exec so project inspection and edits run locally in one model-visible round trip. ` +
      `Use one [RIFT_CALL] block. First line: call <unique-call-id> rift_workspace_exec. Add arguments with set <path> <value>. ` +
      `Nested values use dotted paths and numeric array indexes, for example: set operations.0.op list ; set operations.0.path workspace/RiftOS-main ; set operations.0.recursive true. ` +
      `For multiline text use a heredoc: set operations.1.text <<RIFT_TEXT, then the exact text, then a line containing only RIFT_TEXT. ` +
      `Operations: project, snapshot, stat, list, search, symbols, references, read, read_range, read_symbol, write, replace, patch, patch_range, apply_hunks, mkdir, remove, move, rename, copy, archive. ` +
      `For nested patch edits use paths such as operations.0.edits.0.find and operations.0.edits.0.replace. For hunks use operations.0.hunks.0.startLine and operations.0.hunks.0.text. ` +
      `For large codebases, search symbols/references first, read only the exact symbol/range needed, then patch exact ranges/hunks using returned hashes instead of resending old source. ` +
      `Every batch is transactional: if any operation fails, all mutations are rolled back. The AI can access only workspace/. ` +
      `Set finish true only when a mutating batch fully completes the task.`;
  }

  function contextBlock() {
    return `${CONTEXT_MARKER}\nLocal Rift MCP tools:\n${toolManifest()}${codeModeGuide()}\n` +
      `When a local tool is needed, output exactly one raw Rift command block and no prose around it:\n` +
      `${CALL_OPEN}\ncall unique-call-id tool_name\nset argName value\n${CALL_CLOSE}\n` +
      `Values may be unquoted single tokens or quoted strings. Booleans and numbers are typed automatically. Use dotted paths for nested objects/arrays and heredocs for multiline strings. ` +
      `Prefer one rift_workspace_exec call containing all related local operations. Wait for ${RESULT_MARKER} before continuing. ` +
      `If a Rift call fails, correct it and retry automatically with a new call id when another tool is still required. Never claim success without a successful result.`;
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
    toolExecutionArmed = false;
  }

  function normalizedChatGptTargetUrl(raw) {
    try {
      const url = new URL(String(raw || ''), location.href);
      const host = url.hostname.toLowerCase();
      if (url.protocol !== 'https:' || (host !== 'chatgpt.com' && host !== 'www.chatgpt.com')) return null;
      url.hash = '';
      return url;
    } catch (_) {
      return null;
    }
  }

  function hasProjectContext(url, anchor) {
    const route = `${url.pathname}${url.search}`.toLowerCase();
    if (route.includes('g-p-') || route.includes('/project') || url.searchParams.has('project')) return true;
    if (!(anchor instanceof Element)) return false;
    return Boolean(anchor.closest('[data-testid*="project"],[aria-label*="Project"],[aria-label*="project"]'));
  }

  function classifyChatTarget(url, anchor) {
    if (!url) return '';
    const isChat = /\/c\/[^/?#]+/.test(url.pathname);
    const isProject = hasProjectContext(url, anchor);
    if (isChat && isProject) return 'project-chat';
    if (isChat) return 'chat';
    if (isProject) return 'project';
    return '';
  }

  function chatTargetLabel(anchor, kind, url) {
    const candidates = anchor instanceof Element ? [
      anchor.getAttribute('aria-label'),
      anchor.getAttribute('title'),
      anchor.innerText,
      anchor.textContent
    ] : [];
    for (const candidate of candidates) {
      const label = String(candidate || '').replace(/\s+/g, ' ').trim();
      if (label && label.toLowerCase() !== 'more' && label.toLowerCase() !== 'options') return label.slice(0, 140);
    }
    const tail = decodeURIComponent(url.pathname.split('/').filter(Boolean).pop() || '').replace(/[-_]+/g, ' ').trim();
    if (tail && !/^c$/i.test(tail)) return tail.slice(0, 140);
    return kind === 'project' ? 'Project' : kind === 'project-chat' ? 'Project chat' : 'Chat';
  }

  function collectChatTargets() {
    refreshRouteState();
    const byUrl = new Map();
    for (const anchor of document.querySelectorAll('a[href]')) {
      const url = normalizedChatGptTargetUrl(anchor.getAttribute('href'));
      if (!url) continue;
      const kind = classifyChatTarget(url, anchor);
      if (!kind) continue;
      const href = url.toString();
      const label = chatTargetLabel(anchor, kind, url);
      if (kind === 'project' && /^(new|create)\s+project$/i.test(label)) continue;
      if (!byUrl.has(href)) {
        byUrl.set(href, {
          mode: 'url',
          kind,
          label,
          url: href
        });
      }
      if (byUrl.size >= MAX_CHAT_TARGETS) break;
    }

    const currentUrl = normalizedChatGptTargetUrl(location.href);
    let current = null;
    if (currentUrl) {
      const currentHref = currentUrl.toString();
      const known = byUrl.get(currentHref);
      const kind = known?.kind || classifyChatTarget(currentUrl, null) || 'current';
      const title = String(document.title || '').replace(/\s*[|\-–—]\s*ChatGPT\s*$/i, '').trim();
      current = {
        mode: 'current',
        kind,
        label: known?.label || title || 'Current ChatGPT page',
        url: currentHref
      };
    }

    return {
      version: 1,
      route: routeKey,
      current,
      targets: Array.from(byUrl.values())
    };
  }

  function openTargetSearch() {
    let best = null;
    let bestScore = 0;
    for (const element of document.querySelectorAll('button,a,[role="button"]')) {
      if (!(element instanceof Element) || !isVisible(element)) continue;
      const testId = String(element.getAttribute('data-testid') || '').toLowerCase();
      const aria = String(element.getAttribute('aria-label') || '').toLowerCase();
      const title = String(element.getAttribute('title') || '').toLowerCase();
      const text = String(element.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
      let score = 0;
      if (testId.includes('search')) score += 100;
      if (aria === 'search' || aria.includes('search chats')) score += 90;
      else if (aria.includes('search')) score += 65;
      if (title.includes('search')) score += 45;
      if (text === 'search') score += 60;
      if (score > bestScore) { best = element; bestScore = score; }
    }
    if (best instanceof HTMLElement) {
      best.click();
      return true;
    }
    return false;
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

  function snapshotUserMessages() {
    return new Set(Array.from(document.querySelectorAll('[data-message-author-role="user"]')));
  }

  async function waitForOutgoingAcceptance(baseline, composer, message, timeoutMs = 6000) {
    const deadline = now() + timeoutMs;
    const signature = String(message || '').trim().slice(0, 160);
    while (now() < deadline) {
      const users = document.querySelectorAll('[data-message-author-role="user"]');
      for (const row of users) {
        if (baseline.has(row)) continue;
        const text = String(row.innerText || row.textContent || '').trim();
        if (!signature || text.includes(signature) || signature.includes(text.slice(0, 80))) return true;
      }
      if (stopButtonVisible()) return true;
      if (!readComposer(composer).trim()) return true;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    return false;
  }

  async function sendComposerMessage(message, timeoutMs = 12000) {
    if (manualSendApprovalRequired) {
      pendingApprovedSubmission = { message, timeoutMs };
      sendAiEvent('waiting', 'Message prepared. Waiting for user send approval.', { phase: 'awaiting-user-send' });
      return false;
    }
    for (let attempt = 0; attempt < 3; attempt += 1) {
      const composer = await waitForComposer(timeoutMs);
      if (!composer) throw new Error('ChatGPT composer unavailable');
      const baseline = snapshotUserMessages();
      if (readComposer(composer).trim() !== String(message).trim()) writeComposer(composer, message);
      const button = await waitForSendButton(timeoutMs);
      if (!button) throw new Error('ChatGPT send button unavailable');
      button.click();
      if (await waitForOutgoingAcceptance(baseline, composer, message)) return true;
      sendAiPhase('waiting', `ChatGPT did not accept the message; retrying (${attempt + 2}/3)`);
      await new Promise((resolve) => setTimeout(resolve, 350));
    }
    throw new Error('ChatGPT Web did not acknowledge message submission');
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

  function markHistoricalAssistantMessage(message) {
    if (message instanceof Element) historicalAssistantMessages.add(message);
  }

  function rememberExistingToolCalls() {
    for (const message of document.querySelectorAll('[data-message-author-role="assistant"]')) markHistoricalAssistantMessage(message);
  }

  function decorateOutgoingPrompt() {
    refreshRouteState();
    if (!enabled || suppressDecoration || tools.length === 0) return;
    const composer = findComposer();
    if (!composer) return;
    const text = readComposer(composer).trim();
    if (!text || text.startsWith(RESULT_MARKER)) return;
    rememberExistingToolCalls();
    toolExecutionArmed = true;
    if (text.includes(CONTEXT_MARKER) || contextSentForRoute) return;
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

  async function prepareProjectTarget(target) {
    if (!target || target.kind !== 'project' || findComposer()) return;
    const deadline = now() + 5000;
    while (now() < deadline && !findComposer()) {
      const root = document.querySelector('main');
      const candidates = root ? Array.from(root.querySelectorAll('button,a,[role="button"]')) : [];
      let best = null;
      let bestScore = 0;
      for (const element of candidates) {
        if (!isVisible(element)) continue;
        const testId = String(element.getAttribute('data-testid') || '').toLowerCase();
        const aria = String(element.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().toLowerCase();
        const text = String(element.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
        let score = 0;
        if (testId.includes('new-chat')) score += 100;
        if (aria === 'new chat' || text === 'new chat') score += 90;
        if (aria.includes('start chat') || text.includes('start chat')) score += 75;
        if (aria === 'chat' || text === 'chat') score += 40;
        if (score > bestScore) { best = element; bestScore = score; }
      }
      if (best instanceof HTMLElement && bestScore > 0) {
        best.click();
        await new Promise((resolve) => setTimeout(resolve, 450));
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, 180));
    }
  }

  async function submitAiTask(payload) {
    const sessionId = String(payload && payload.sessionId || '').trim();
    const task = String(payload && payload.task || '').trim();
    const projectContext = String(payload && payload.projectContext || '').trim();
    const target = payload && payload.target && typeof payload.target === 'object' ? payload.target : {};
    if (!sessionId) { sendAiEvent('error', 'Rift AI session id is missing', { phase: 'error' }); return false; }
    if (aiTaskActive) {
      if (sessionId === activeAiSessionId) {
        sendAiEvent('transport', 'Duplicate Rift AI task dispatch ignored', { phase: 'duplicate-submit', sessionId });
        return true;
      }
      sendAiEvent('error', 'A Rift AI task is already active', { phase: 'error', sessionId });
      return false;
    }

    activeAiSessionId = sessionId;
    aiTaskActive = true;
    aiStopRequested = false;
    activeToolRoundTrips = 0;
    assistantSeen = false;
    lastAssistantUpdateAt = 0;
    lastToolResultAt = 0;
    continuationRequired = false;
    toolLoopState = 'waiting-assistant';
    continuationBaseline = new WeakSet();
    continuationBaselineKeys = new Set();
    pendingResultId = '';
    consumedContinuationResultId = '';
    continuationWaitStartedAt = 0;
    recoveryAttempt = 0;
    clearCompletionTimer();

    if (!task) { finishAiTask('error', 'Rift AI task is empty', 'error'); return false; }
    sendAiPhase('waiting', 'Waiting for local MCP manifest');
    if (!await waitForMcpReady(12000)) {
      finishAiTask('error', 'Local Rift MCP did not initialize; task was not submitted', 'error');
      return false;
    }
    if (target.kind === 'project') {
      sendAiPhase('waiting', `Preparing project chat${target.label ? ` · ${String(target.label).slice(0, 120)}` : ''}`);
      await prepareProjectTarget(target);
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
    rememberExistingToolCalls();
    toolExecutionArmed = true;
    try {
      await sendComposerMessage(message);
    } catch (error) {
      suppressDecoration = false;
      finishAiTask('error', String(error && error.message || error), 'error');
      return false;
    }
    sendAiPhase('submitted', 'Task submitted to ChatGPT Web');
    sendAiPhase('running', 'ChatGPT Web is working');
    setTimeout(() => { suppressDecoration = false; }, 800);
    scheduleCompletionCheck(1800);
    return true;
  }

  function queueAiTask(payload) {
    const sessionId = String(payload && payload.sessionId || '').trim();
    if (!enabled) return { accepted: false, reason: 'disabled' };
    if (!sessionId) return { accepted: false, reason: 'missing-session' };
    if (aiTaskActive) {
      return activeAiSessionId === sessionId
        ? { accepted: true, duplicate: true, state: toolLoopState }
        : { accepted: false, reason: 'busy', activeSessionId: activeAiSessionId };
    }
    if (queuedAiPayload) {
      const queuedId = String(queuedAiPayload.sessionId || '').trim();
      return queuedId === sessionId
        ? { accepted: true, duplicate: true, state: 'queued' }
        : { accepted: false, reason: 'busy', activeSessionId: queuedId };
    }
    queuedAiPayload = payload;
    queueMicrotask(pumpAiTaskQueue);
    return { accepted: true, state: 'queued' };
  }

  async function pumpAiTaskQueue() {
    if (taskPumpRunning || !queuedAiPayload) return;
    taskPumpRunning = true;
    const payload = queuedAiPayload;
    queuedAiPayload = null;
    try {
      await submitAiTask(payload);
    } catch (error) {
      const message = `ChatGPT Web task failed: ${String(error && error.message || error)}`;
      if (aiTaskActive) finishAiTask('error', message, 'error');
      else sendAiEvent('error', message, { phase: 'error', sessionId: String(payload && payload.sessionId || '') });
    } finally {
      taskPumpRunning = false;
      if (queuedAiPayload) queueMicrotask(pumpAiTaskQueue);
    }
  }

  function approvePendingSend() {
    manualSendApprovalRequired = false;
    if (!pendingApprovedSubmission) return false;
    const pending = pendingApprovedSubmission;
    pendingApprovedSubmission = null;
    sendComposerMessage(pending.message, pending.timeoutMs).catch((error) => sendAiEvent('error', String(error && error.message || error), { phase: 'send-error' }));
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

  function readResultPayloadFromMessage(message) {
    if (!(message instanceof Element)) return null;
    const text = String(message.innerText || message.textContent || '');
    const markerAt = text.indexOf(RESULT_MARKER);
    if (markerAt < 0) return null;
    const tail = text.slice(markerAt + RESULT_MARKER.length).replace(/^\s+/, '');
    const out = {};
    for (const line of tail.split('\n')) {
      const trimmed = line.trim();
      if (!trimmed || trimmed === 'result:') continue;
      const match = trimmed.match(/^([a-z_]+):\s*(.*)$/i);
      if (!match) break;
      const key = match[1].toLowerCase();
      const value = match[2].trim();
      if (key === 'result_id') out.result_id = value;
      else if (key === 'call_id') out.call_id = value;
      else if (key === 'tool') out.name = value;
      else if (key === 'status') out.ok = value.toLowerCase() === 'ok';
      else if (key === 'final') out.final = value.toLowerCase() === 'true';
      else if (key === 'error_code') out.error_code = value;
      else if (key === 'error') out.error = value;
    }
    return Object.keys(out).length ? out : null;
  }

  function markInjectedResultMessage(message) {
    const parsed = readResultPayloadFromMessage(message);
    const resultId = String(parsed && parsed.result_id || '').trim();
    if (resultId) {
      acknowledgedResultIds.add(resultId);
      if (message instanceof HTMLElement) message.dataset.riftResultId = resultId;
    }
    return resultId;
  }

  function acknowledgeResultFromAssistantContinuation(message = null) {
    if (!aiTaskActive || toolLoopState !== 'waiting-result-ack' || !pendingResultId) return false;

    const assistantMessages = document.querySelectorAll('[data-message-author-role="assistant"]');
    const candidate = message instanceof Element
      ? message
      : (assistantMessages.length ? assistantMessages[assistantMessages.length - 1] : null);
    if (!(candidate instanceof Element)) return false;

    const isLatestAssistant = assistantMessages.length > 0 &&
      assistantMessages[assistantMessages.length - 1] === candidate;
    if (!isLatestAssistant || isContinuationBaseline(candidate)) return false;

    const raw = String(candidate.innerText || candidate.textContent || '');
    const visible = stripToolEnvelopes(raw);
    const protocolSignal = hasToolProtocolSignal(raw);
    const meaningful = protocolSignal || (Boolean(visible) && !isTransientAssistantStatus(visible));
    if (!meaningful) return false;

    const resultId = pendingResultId;
    const firstAck = !acknowledgedResultIds.has(resultId);
    acknowledgedResultIds.add(resultId);
    if (firstAck) {
      sendAiPhase('running', 'ChatGPT continuation acknowledged Rift result', 'transport', {
        resultId,
        ackSource: 'assistant-continuation'
      });
    }
    return true;
  }

  async function waitForResultAck(resultId, timeoutMs = RESULT_ACK_TIMEOUT_MS) {
    const deadline = now() + timeoutMs;
    while (now() < deadline) {
      if (acknowledgedResultIds.has(resultId)) return true;
      for (const message of document.querySelectorAll('[data-message-author-role="user"]')) {
        if (!(message instanceof Element)) continue;
        const known = message instanceof HTMLElement ? String(message.dataset.riftResultId || '') : '';
        if (known === resultId) {
          acknowledgedResultIds.add(resultId);
          return true;
        }
        if (markInjectedResultMessage(message) === resultId) return true;
      }
      if (pendingResultId === resultId && acknowledgeResultFromAssistantContinuation()) return true;
      await new Promise((resolve) => setTimeout(resolve, 80));
    }
    return false;
  }

  function scanForContinuation() {
    const assistant = document.querySelectorAll('[data-message-author-role="assistant"]');
    for (let i = Math.max(0, assistant.length - 6); i < assistant.length; i++) {
      const message = assistant[i];
      if (!isContinuationBaseline(message)) scanAssistantMessage(message);
    }
  }

  function formatRawValue(value, depth = 0) {
    const pad = '  '.repeat(depth);
    if (value === null || value === undefined) return 'null';
    if (typeof value === 'string') {
      if (!value.includes('\n')) return value;
      return `|\n${value.split('\n').map((line) => `${pad}  ${line}`).join('\n')}`;
    }
    if (typeof value === 'number' || typeof value === 'boolean') return String(value);
    if (Array.isArray(value)) {
      if (!value.length) return '[]';
      return value.map((item) => {
        const rendered = formatRawValue(item, depth + 1);
        const lines = rendered.split('\n');
        return `${pad}- ${lines[0]}${lines.length > 1 ? `\n${lines.slice(1).map((line) => `${pad}  ${line}`).join('\n')}` : ''}`;
      }).join('\n');
    }
    if (typeof value === 'object') {
      const entries = Object.entries(value);
      if (!entries.length) return '{}';
      return entries.map(([key, item]) => {
        const rendered = formatRawValue(item, depth + 1);
        if (!rendered.includes('\n') && (item === null || typeof item !== 'object')) return `${pad}${key}: ${rendered}`;
        return `${pad}${key}:\n${rendered}`;
      }).join('\n');
    }
    return String(value);
  }

  function rawResultMessage(resultPayload) {
    const header = [
      RESULT_MARKER,
      `result_id: ${resultPayload.result_id || ''}`,
      `call_id: ${resultPayload.call_id || ''}`,
      `tool: ${resultPayload.name || ''}`,
      `status: ${resultPayload.ok === false ? 'error' : 'ok'}`,
      `final: ${resultPayload.final === true ? 'true' : 'false'}`
    ];
    if (resultPayload.error_code) header.push(`error_code: ${resultPayload.error_code}`);
    if (resultPayload.error) header.push(`error: ${String(resultPayload.error).replace(/\s+/g, ' ').slice(0, 1200)}`);
    let body = resultPayload.result === undefined || resultPayload.result === null ? 'null' : formatRawValue(resultPayload.result);
    let truncated = false;
    if (body.length > MAX_RESULT_CHARS) {
      body = body.slice(0, MAX_RESULT_CHARS);
      truncated = true;
    }
    header.push('result:');
    header.push(body);
    if (truncated) header.push('... [Rift result truncated; request a narrower range or continue with another targeted read]');
    return header.join('\n');
  }

  async function submitToolResult(payload, expectedSessionId = '') {
    if (expectedSessionId && (!aiTaskActive || activeAiSessionId !== expectedSessionId)) {
      throw new Error(`Stale Rift result ignored for inactive session ${expectedSessionId}`);
    }
    const resultId = resultIdForPayload();
    const resultPayload = { ...(payload && typeof payload === 'object' ? payload : {}), result_id: resultId };
    const manageSession = Boolean(expectedSessionId && aiTaskActive && activeAiSessionId === expectedSessionId);
    if (resultPayload.ok === false) {
      recoveryAttempt += 1;
      resultPayload.retryable = true;
      resultPayload.recovery_attempt = recoveryAttempt;
    } else if (resultPayload.ok === true) {
      recoveryAttempt = 0;
    }
    const finalInstruction = resultPayload.final === true && resultPayload.ok === true
      ? `The requested final batch is confirmed. Briefly confirm completion unless the result itself shows unfinished work.`
      : resultPayload.ok === false
        ? `The Rift call failed. Correct the raw Rift command and retry automatically with a new call id if the task still requires a tool. Do not ask the user to resend or type continue.`
        : `Continue immediately using this result. If another tool is required, return one new ${CALL_OPEN}...${CALL_CLOSE} block with a new call id. Do not wait for the user.`;
    const message = `${rawResultMessage(resultPayload)}\n${finalInstruction}`;

    if (manageSession) {
      markContinuationBaseline();
      lastToolResultAt = now();
      continuationRequired = true;
      assistantSeen = false;
      pendingResultId = resultId;
      consumedContinuationResultId = '';
      continuationWaitStartedAt = 0;
      toolLoopState = 'delivering-result';
      sendAiPhase('running', 'Returning Rift result to ChatGPT Web', 'transport', {
        resultId,
        callId: resultPayload.call_id || null,
        tool: resultPayload.name || null,
        ok: resultPayload.ok !== false
      });
    }

    suppressDecoration = true;
    if (manageSession) toolLoopState = 'waiting-result-ack';
    try {
      await sendComposerMessage(message);
    } catch (error) {
      suppressDecoration = false;
      throw error;
    }
    setTimeout(() => { suppressDecoration = false; }, 800);

    if (manageSession) {
      const acknowledged = await waitForResultAck(resultId);
      if (!acknowledged) {
        sendAiPhase('running', 'Rift result sent · ACK observer uncertain, continuing recovery', 'transport', {
          resultId,
          ackSource: 'recovery-timeout'
        });
        acknowledgedResultIds.add(resultId);
      }
      if (!aiTaskActive || activeAiSessionId !== expectedSessionId) {
        throw new Error(`Stale Rift result ignored for inactive session ${expectedSessionId}`);
      }
      pendingResultId = '';
      if (consumedContinuationResultId === resultId) {
        consumedContinuationResultId = '';
        continuationWaitStartedAt = 0;
        sendAiPhase('running', 'Rift result delivered · ChatGPT continuation already received', 'transport', {
          resultId,
          callId: resultPayload.call_id || null,
          tool: resultPayload.name || null,
          ok: resultPayload.ok !== false,
          ackSource: 'assistant-continuation'
        });
      } else {
        toolLoopState = 'waiting-continuation';
        continuationWaitStartedAt = now();
        sendAiPhase('running', 'Rift result delivered · waiting for ChatGPT continuation', 'transport', {
          resultId,
          callId: resultPayload.call_id || null,
          tool: resultPayload.name || null,
          ok: resultPayload.ok !== false
        });
        queueMicrotask(scanForContinuation);
      }
    }
    return resultId;
  }

  function rateLimitAllowsCall() {
    const cutoff = now() - 60000;
    while (recentCallTimes.length && recentCallTimes[0] < cutoff) recentCallTimes.shift();
    if (recentCallTimes.length >= MAX_CALLS_PER_MINUTE) return false;
    recentCallTimes.push(now());
    return true;
  }

  function normalizeCall(packet) {
    if (!packet || typeof packet !== 'object' || Array.isArray(packet)) throw new Error('Rift call must be a command object');
    const callId = String(packet.call_id || packet.id || '').trim();
    const name = String(packet.name || packet.tool || '').trim();
    const suppliedArgs = Object.prototype.hasOwnProperty.call(packet, 'arguments') ? packet.arguments : packet.args;
    if (suppliedArgs !== undefined && (!suppliedArgs || typeof suppliedArgs !== 'object' || Array.isArray(suppliedArgs))) {
      throw new Error('Rift call arguments are invalid');
    }
    const args = suppliedArgs && typeof suppliedArgs === 'object' ? suppliedArgs : {};
    if (!callId || callId.length > 160) throw new Error('Invalid call_id');
    if (!tools.some((tool) => tool.name === name)) throw new Error(`Unknown Rift tool: ${name}`);
    return { call_id: callId, name, args };
  }

  async function performCall(packet, aiSessionId) {
    let call;
    try {
      call = normalizeCall(packet);
    } catch (error) {
      return {
        call_id: String(packet && (packet.call_id || packet.id) || '').trim() || null,
        name: String(packet && (packet.name || packet.tool) || '').trim() || null,
        ok: false,
        final: false,
        error_code: 'CALL_VALIDATION_ERROR',
        error: String(error && error.message || error)
      };
    }
    if (!toolExecutionArmed) return { call_id: call.call_id, name: call.name, ok: false, final: false, error_code: 'NOT_ARMED', error: 'Rift tool execution is not armed for this chat turn' };

    const scope = aiSessionId || routeKey;
    const callKey = `${scope}:${call.call_id}`;
    const signature = `${call.name}:${JSON.stringify(call.args)}`;
    const previousSignature = processedCalls.get(callKey);
    if (previousSignature) {
      if (previousSignature === signature) return { call_id: call.call_id, name: call.name, skip: true };
      return { call_id: call.call_id, name: call.name, ok: false, final: false, error_code: 'DUPLICATE_CALL_ID', error: 'Duplicate Rift call id was reused with different arguments; retry with a new id.' };
    }

    if (!rateLimitAllowsCall()) {
      processedCalls.set(callKey, signature);
      return { call_id: call.call_id, name: call.name, ok: false, final: false, error_code: 'RATE_LIMIT', error: 'Rift MCP browser rate limit reached; consolidate work into a larger rift_workspace_exec batch.' };
    }

    // Only mark a model call processed after it has actually been accepted for execution.
    processedCalls.set(callKey, signature);
    if (aiSessionId && aiTaskActive && activeAiSessionId === aiSessionId) toolLoopState = 'executing-tool';
    if (aiSessionId) activeToolRoundTrips += 1;
    setBadge('busy');
    try {
      const meta = { 'riftos/callId': call.call_id };
      if (aiSessionId) meta['riftos/aiSessionId'] = aiSessionId;
      const result = await postRpc('tools/call', { name: call.name, arguments: call.args, _meta: meta });
      const resultMeta = result && result._meta && typeof result._meta === 'object' ? result._meta : {};
      if (String(resultMeta['riftos/callId'] || '') !== call.call_id) {
        throw new Error(`Rift MCP result correlation failed for ${call.call_id}`);
      }
      if (aiSessionId && String(resultMeta['riftos/aiSessionId'] || '') !== aiSessionId) {
        throw new Error(`Rift MCP session correlation failed for ${call.call_id}`);
      }
      if (aiSessionId && (!aiTaskActive || activeAiSessionId !== aiSessionId)) {
        throw new Error(`Stale Rift result ignored for inactive session ${aiSessionId}`);
      }

      const structured = result.structuredContent || {};
      const ok = !result.isError && structured.ok !== false;
      const value = structured.value !== undefined ? structured.value : null;
      const mutationCount = Array.isArray(value && value.mutationTargets) ? value.mutationTargets.length : 0;
      const requestedFinal = Boolean(call.name === 'rift_workspace_exec' && call.args && call.args.finish === true && call.args.dryRun !== true && value && value.committed !== false && mutationCount > 0);

      return {
        call_id: call.call_id,
        name: call.name,
        ok,
        final: Boolean(ok && requestedFinal),
        result: value,
        error: structured.error || null
      };
    } catch (error) {
      const message = String(error && error.message || error);
      if (message.startsWith('Stale Rift result ignored')) {
        sendAiEvent('transport', message, { phase: 'stale-result', sessionId: aiSessionId, callId: call.call_id });
        throw error;
      }
      return { call_id: call.call_id, name: call.name, ok: false, final: false, error_code: 'TOOL_TRANSPORT_ERROR', error: message };
    } finally {
      if (aiSessionId) activeToolRoundTrips = Math.max(0, activeToolRoundTrips - 1);
    }
  }

  async function executeCall(packet) {
    const aiSessionId = aiTaskActive ? activeAiSessionId : '';
    const payload = await performCall(packet, aiSessionId);
    if (payload.skip) return;
    await submitToolResult({ ...payload, session_id: aiSessionId || null }, aiSessionId);
    setBadge(payload.ok === false ? 'error' : 'ready');
    if (aiTaskActive) scheduleCompletionCheck(900);
  }

  function splitRawTokens(line) {
    const tokens = [];
    let token = '';
    let quote = '';
    let escaping = false;
    for (const ch of String(line || '')) {
      if (escaping) { token += ch; escaping = false; continue; }
      if (ch === '\\') { escaping = true; continue; }
      if (quote) {
        if (ch === quote) quote = '';
        else token += ch;
        continue;
      }
      if (ch === '"' || ch === "'") { quote = ch; continue; }
      if (/\s/.test(ch)) {
        if (token) { tokens.push(token); token = ''; }
        continue;
      }
      token += ch;
    }
    if (escaping) token += '\\';
    if (quote) throw new Error('Unclosed quote in Rift command');
    if (token) tokens.push(token);
    return tokens;
  }

  function parseRawScalar(value) {
    const text = String(value ?? '');
    if (/^(true|false)$/i.test(text)) return text.toLowerCase() === 'true';
    if (/^null$/i.test(text)) return null;
    if (/^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(text)) return Number(text);
    return text;
  }

  function setRawPath(root, path, value) {
    const parts = String(path || '').split('.').filter(Boolean);
    if (!parts.length) throw new Error('set requires a dotted argument path');
    let cursor = root;
    for (let i = 0; i < parts.length; i += 1) {
      const part = parts[i];
      const isIndex = /^\d+$/.test(part);
      const last = i === parts.length - 1;
      if (Array.isArray(cursor)) {
        if (!isIndex) throw new Error(`Expected numeric array index at ${part}`);
        const index = Number(part);
        if (last) { cursor[index] = value; return; }
        const nextIsIndex = /^\d+$/.test(parts[i + 1]);
        if (!cursor[index] || typeof cursor[index] !== 'object') cursor[index] = nextIsIndex ? [] : {};
        cursor = cursor[index];
      } else {
        if (last) { cursor[part] = value; return; }
        const nextIsIndex = /^\d+$/.test(parts[i + 1]);
        if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = nextIsIndex ? [] : {};
        cursor = cursor[part];
      }
    }
  }

  function parseRawCallBlock(rawBlock) {
    const lines = String(rawBlock || '').replace(/\r\n?/g, '\n').split('\n');
    let callId = '';
    let name = '';
    const args = {};
    for (let i = 0; i < lines.length; i += 1) {
      const rawLine = lines[i];
      const trimmed = rawLine.trim();
      if (!trimmed || trimmed.startsWith('#')) continue;
      const tokens = splitRawTokens(trimmed);
      const command = String(tokens.shift() || '').toLowerCase();
      if (command === 'call') {
        if (callId || name) throw new Error('Rift command block may contain only one call line');
        callId = String(tokens.shift() || '').trim();
        name = String(tokens.shift() || '').trim();
        if (!callId || !name || tokens.length) throw new Error('call syntax is: call <unique-call-id> <tool-name>');
        continue;
      }
      if (command !== 'set') throw new Error(`Unknown Rift command: ${command || trimmed}`);
      const path = String(tokens.shift() || '').trim();
      if (!path) throw new Error('set syntax is: set <path> <value>');
      let valueText = tokens.join(' ');
      if (valueText.startsWith('<<')) {
        const delimiter = valueText.slice(2).trim();
        if (!delimiter || /\s/.test(delimiter)) throw new Error('Heredoc delimiter must be one non-space token');
        const body = [];
        let closed = false;
        for (i += 1; i < lines.length; i += 1) {
          if (lines[i] === delimiter) { closed = true; break; }
          body.push(lines[i]);
        }
        if (!closed) return { incomplete: true };
        setRawPath(args, path, body.join('\n'));
      } else {
        setRawPath(args, path, parseRawScalar(valueText));
      }
    }
    if (!callId || !name) throw new Error('Rift command block requires one call line');
    return { packet: { call_id: callId, name, args }, incomplete: false };
  }

  function parseRawCallEnvelopes(text) {
    const calls = [];
    const errors = [];
    let incomplete = false;
    let cursor = 0;
    const raw = String(text || '');
    while (true) {
      const start = raw.indexOf(CALL_OPEN, cursor);
      if (start < 0) break;
      const end = raw.indexOf(CALL_CLOSE, start + CALL_OPEN.length);
      if (end < 0) { incomplete = true; break; }
      const body = raw.slice(start + CALL_OPEN.length, end).trim();
      try {
        const parsed = parseRawCallBlock(body);
        if (parsed.incomplete) incomplete = true;
        else calls.push(parsed.packet);
      } catch (error) {
        errors.push(String(error && error.message || error));
      }
      cursor = end + CALL_CLOSE.length;
    }
    return { calls, errors, incomplete };
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
    for (let length = Math.min(CALL_OPEN.length - 1, out.length); length > 0; length -= 1) {
      if (out.endsWith(CALL_OPEN.slice(0, length))) {
        out = out.slice(0, -length);
        break;
      }
    }
    return out.replace(/\n{3,}/g, '\n\n').trim();
  }

  function hasToolProtocolSignal(text) {
    const raw = String(text || '');
    if (raw.includes(CALL_OPEN)) return true;
    const trimmed = raw.trimEnd();
    for (let length = Math.min(CALL_OPEN.length - 1, trimmed.length); length > 0; length -= 1) {
      if (trimmed.endsWith(CALL_OPEN.slice(0, length))) return true;
    }
    return false;
  }

  function isTransientAssistantStatus(text) {
    const normalized = String(text || '')
      .replace(/[\u2026.]+$/g, '')
      .replace(/\s+/g, ' ')
      .trim()
      .toLowerCase();
    if (!normalized) return false;
    return normalized === 'thinking' ||
      normalized === 'working' ||
      normalized === 'reasoning' ||
      normalized === 'generating' ||
      normalized === 'searching' ||
      normalized === 'browsing' ||
      normalized === 'analyzing' ||
      normalized === 'analysing';
  }

  function latestAssistantHasCompletableOutput() {
    const assistant = document.querySelectorAll('[data-message-author-role="assistant"]');
    if (!assistant.length) return false;
    const message = assistant[assistant.length - 1];
    const raw = String(message.innerText || message.textContent || '');
    if (hasToolProtocolSignal(raw)) return false;
    const visible = stripToolEnvelopes(raw);
    return Boolean(visible) && !isTransientAssistantStatus(visible);
  }

  function queueProtocolRecovery(message, errorText) {
    if (!(message instanceof Element)) return;
    const fingerprint = `${String(errorText)}\n${String(message.innerText || message.textContent || '')}`;
    if (protocolIssueFingerprints.get(message) === fingerprint) return;
    protocolIssueFingerprints.set(message, fingerprint);
    const aiSessionId = aiTaskActive ? activeAiSessionId : '';
    if (aiSessionId) toolLoopState = 'delivering-result';
    callQueue = callQueue.then(() => submitToolResult({
      call_id: null,
      name: null,
      session_id: aiSessionId || null,
      ok: false,
      final: false,
      error_code: 'CALL_PARSE_ERROR',
      error: String(errorText)
    }, aiSessionId)).catch((error) => {
      setBadge('error');
      if (aiTaskActive) finishAiTask('error', `Rift tool recovery failed: ${String(error && error.message || error)}`, 'error');
    });
  }

  function scheduleIncompleteCallCheck(message, text) {
    const previous = incompleteCallTimers.get(message);
    if (previous && previous.text === text) return;
    if (previous && previous.timer) clearTimeout(previous.timer);
    const timer = setTimeout(() => {
      incompleteCallTimers.delete(message);
      if (!(message instanceof Element) || !message.isConnected) return;
      const currentText = String(message.innerText || message.textContent || '');
      if (currentText !== text) {
        scanAssistantMessage(message);
        return;
      }
      const parsed = parseRawCallEnvelopes(currentText);
      if (parsed.incomplete && !stopButtonVisible()) {
        queueProtocolRecovery(message, `Rift command block was incomplete. Return one complete ${CALL_OPEN}...${CALL_CLOSE} block.`);
      }
    }, INCOMPLETE_CALL_GRACE_MS);
    incompleteCallTimers.set(message, { text, timer });
  }

  function scanAssistantMessage(message) {
    if (!enabled || !(message instanceof Element)) return;
    const text = String(message.innerText || message.textContent || '');
    const visibleText = stripToolEnvelopes(text);
    const protocolSignal = hasToolProtocolSignal(text);
    const meaningfulAssistantContent = protocolSignal || (Boolean(visibleText) && !isTransientAssistantStatus(visibleText));
    const assistantMessages = document.querySelectorAll('[data-message-author-role="assistant"]');
    const isLatestAssistant = assistantMessages.length > 0 && assistantMessages[assistantMessages.length - 1] === message;
    const isHistorical = historicalAssistantMessages.has(message);

    if (!meaningfulAssistantContent) return;

    if (aiTaskActive && toolLoopState === 'waiting-continuation') {
      if (isContinuationBaseline(message)) return;
      continuationRequired = false;
      continuationWaitStartedAt = 0;
      toolLoopState = 'waiting-assistant';
      lastAssistantUpdateAt = now();
      sendAiPhase('running', 'ChatGPT continuation received', 'transport');
    } else if (aiTaskActive && toolLoopState === 'waiting-result-ack') {
      const resultId = pendingResultId;
      if (!acknowledgeResultFromAssistantContinuation(message)) return;
      consumedContinuationResultId = resultId;
      continuationRequired = false;
      continuationWaitStartedAt = 0;
      toolLoopState = 'waiting-assistant';
      lastAssistantUpdateAt = now();
      sendAiPhase('running', 'ChatGPT continuation received with Rift result acknowledgement', 'transport', {
        resultId,
        ackSource: 'assistant-continuation'
      });
      // Intentionally fall through: this exact assistant update must also be
      // processed as a next tool call or final assistant response.
    } else if (aiTaskActive && (toolLoopState === 'executing-tool' || toolLoopState === 'delivering-result')) {
      return;
    }

    const mayExecute = toolExecutionArmed && isLatestAssistant && !isHistorical && tools.length > 0;
    const parsed = mayExecute && text.includes(CALL_OPEN) ? parseRawCallEnvelopes(text) : { calls: [], errors: [], incomplete: false };

    if (mayExecute && parsed.errors.length) {
      queueProtocolRecovery(message, parsed.errors.join('; '));
      return;
    }
    if (mayExecute && parsed.incomplete) {
      scheduleIncompleteCallCheck(message, text);
      return;
    }
    if (mayExecute && parsed.calls.length > 1) {
      queueProtocolRecovery(message, `Only one ${CALL_OPEN} block is allowed per assistant turn. Consolidate work into one rift_workspace_exec batch or issue calls sequentially.`);
      return;
    }
    if (mayExecute && parsed.calls.length === 1) {
      if (aiTaskActive) toolLoopState = 'executing-tool';
      const packet = parsed.calls[0];
      callQueue = callQueue.then(() => executeCall(packet)).catch((error) => {
        setBadge('error');
        if (aiTaskActive) finishAiTask('error', `Rift tool pipeline failed: ${String(error && error.message || error)}`, 'error');
      });
      return;
    }

    if (visibleText && lastAssistantText.get(message) !== visibleText) {
      lastAssistantText.set(message, visibleText);
      if (aiTaskActive) {
        assistantSeen = true;
        lastAssistantUpdateAt = now();
      }
      sendAiEvent('assistant', 'Assistant output updated', {
        messageId: String(message.getAttribute('data-message-id') || message.id || ''),
        text: visibleText.slice(0, 180000)
      });
    }
    if (aiTaskActive && visibleText) scheduleCompletionCheck();
  }

  function compactInjectedUserMessage(message) {
    if (!(message instanceof Element)) return;
    markInjectedResultMessage(message);
    const content = message.querySelector('.whitespace-pre-wrap');
    if (!content || content.childElementCount > 0) return;
    const text = String(content.textContent || '');
    if (text.includes(CONTEXT_MARKER)) {
      content.textContent = text.split(`\n\n${CONTEXT_MARKER}`)[0];
    } else if (text.startsWith(RESULT_MARKER)) {
      let label = '↔ Rift tool result';
      const parsed = readResultPayloadFromMessage(message);
      if (parsed && parsed.name) label += ` · ${parsed.name}`;
      label += parsed && parsed.ok === false ? ' · blocked/error' : ' · ok';
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
    for (let i = Math.max(0, assistant.length - 8); i < assistant.length; i++) markHistoricalAssistantMessage(assistant[i]);
    for (let i = Math.max(0, users.length - 8); i < users.length; i++) compactInjectedUserMessage(users[i]);
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
    queueTask: queueAiTask,
    submitTask: queueAiTask,
    targets: collectChatTargets,
    openTargetSearch,
    stop: stopAiTask,
    approveSend: approvePendingSend,
    setManualSendApproval: (value) => { manualSendApprovalRequired = Boolean(value); },
    state: () => ({ version: VERSION, enabled, ready: bootComplete && mcpReady, tools: tools.length, route: routeKey, aiTaskActive, queued: Boolean(queuedAiPayload), toolLoopState, pendingResultId, manualSendApprovalRequired })
  });

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot, { once: true });
  else boot();
})();
