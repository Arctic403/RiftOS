(() => {
  'use strict';
  if (window.__RIFT_MCP_APP_V1__) return;
  window.__RIFT_MCP_APP_V1__ = true;

  const VERSION = 'rift-mcp-app-v2.2.0-multi-ai';
  const CONTEXT_MARKER = '[RIFT_MCP_RAW_V1]';
  const RESULT_MARKER = '[RIFT_RESULT]';
  const CALL_OPEN = '[RIFT_CALL]';
  const CALL_CLOSE = '[RIFT_END]';
  const MAX_CONTEXT_CHARS = 5000;
  const MAX_RESULT_CHARS = 48000;
  const MAX_CALLS_PER_MINUTE = 24;
  const PROCESS_DELAY_MS = 180;
  const INCOMPLETE_CALL_GRACE_MS = 1400;

  const pending = new Map();
  const processedCalls = new Map();
  const recentCallTimes = [];
  const touchedAssistantMessages = new Set();
  const touchedUserMessages = new Set();
  const historicalAssistantMessages = new WeakSet();
  const protocolIssueFingerprints = new WeakMap();
  const incompleteCallTimers = new WeakMap();
  let requestCounter = 0;
  let tools = [];
  let mcpReady = false;
  let enabled = true;
  let callQueue = Promise.resolve();
  let badge = null;
  let processTimer = 0;
  let routeKey = location.pathname + location.search;
  let contextSentForRoute = false;
  let toolExecutionArmed = false;
  let resultCounter = 0;
  let recoveryAttempt = 0;
  const siteAdapter = window.RiftAIAdapters?.current?.() || {
    name: 'generic', label: 'AI chat', composer: ['textarea', '[contenteditable="true"]'],
    send: ['button[aria-label*="Send" i]', 'button[type="submit"]'],
    stop: ['button[aria-label^="Stop" i]'], assistant: ['[data-role="assistant"]'], user: ['[data-role="user"]']
  };
  function now() { return Date.now(); }

  function stopButtonVisible() {
    const stop = queryFirst(siteAdapter.stop);
    return stop instanceof Element && isVisible(stop) && !stop.disabled;
  }

  function selectorText(selectors) {
    return Array.isArray(selectors) ? selectors.filter(Boolean).join(',') : String(selectors || '');
  }

  function queryAll(selectors, root = document) {
    const query = selectorText(selectors);
    if (!query || !root?.querySelectorAll) return [];
    try {
      const matches = Array.from(root.querySelectorAll(query));
      return matches.filter((element) => !matches.some((other) => other !== element && other.contains(element)));
    } catch (_) { return []; }
  }

  function queryFirst(selectors, root = document) {
    for (const selector of Array.isArray(selectors) ? selectors : [selectors]) {
      if (!selector) continue;
      try {
        for (const element of root.querySelectorAll(selector)) if (isVisible(element)) return element;
      } catch (_) {}
    }
    return null;
  }

  function listAssistantMessages() { return queryAll(siteAdapter.assistant); }
  function listUserMessages() { return queryAll(siteAdapter.user); }

  function resultIdForPayload() {
    return `result-${now()}-${++resultCounter}`;
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

  function riftCapabilityManifest() {
    return {
      provider: 'RiftOS',
      version: 'rift-tools-v1',
      tools,
      count: tools.length
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
    const capabilities = JSON.stringify(riftCapabilityManifest());
    return `${CONTEXT_MARKER}\nRiftOS capabilities: ${capabilities}\nLocal Rift MCP tools:\n${toolManifest()}${codeModeGuide()}\n` +
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

  function isVisible(element) {
    if (!(element instanceof Element)) return false;
    const rect = element.getBoundingClientRect();
    const style = getComputedStyle(element);
    return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
  }

  function findComposer() {
    const preferred = queryFirst(siteAdapter.composer);
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

  function markHistoricalAssistantMessage(message) {
    if (message instanceof Element) historicalAssistantMessages.add(message);
  }

  function rememberExistingToolCalls() {
    for (const message of listAssistantMessages()) markHistoricalAssistantMessage(message);
  }

  async function stageComposerMessage(message) {
    const composer = findComposer();
    if (!composer) throw new Error('AI composer unavailable; open the web view before returning a Rift result');
    if (!writeComposer(composer, String(message ?? ''))) throw new Error('Could not stage Rift result in the AI composer');
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
    if (resultId && message instanceof HTMLElement) message.dataset.riftResultId = resultId;
    return resultId;
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

  async function submitToolResult(payload) {
    const resultId = resultIdForPayload();
    const resultPayload = { ...(payload && typeof payload === 'object' ? payload : {}), result_id: resultId };
    if (resultPayload.ok === false) {
      recoveryAttempt += 1;
      resultPayload.retryable = true;
      resultPayload.recovery_attempt = recoveryAttempt;
    } else if (resultPayload.ok === true) {
      recoveryAttempt = 0;
    }
    const finalInstruction = resultPayload.final === true && resultPayload.ok === true
      ? 'The requested final batch is confirmed. Briefly confirm completion unless the result itself shows unfinished work.'
      : resultPayload.ok === false
        ? 'The Rift call failed. Correct the raw Rift command and retry with a new call id if the task still requires a tool.'
        : `Continue using this result. If another tool is required, return one new ${CALL_OPEN}...${CALL_CLOSE} block with a new call id.`;
    const message = `${rawResultMessage(resultPayload)}\n${finalInstruction}`;
    await stageComposerMessage(message);
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

  async function performCall(packet) {
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

    const callKey = `${routeKey}:${call.call_id}`;
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

    processedCalls.set(callKey, signature);
    setBadge('busy');
    try {
      const result = await postRpc('tools/call', {
        name: call.name,
        arguments: call.args,
        _meta: { 'riftos/callId': call.call_id }
      });
      const resultMeta = result && result._meta && typeof result._meta === 'object' ? result._meta : {};
      if (String(resultMeta['riftos/callId'] || '') !== call.call_id) throw new Error(`Rift MCP result correlation failed for ${call.call_id}`);
      const structured = result.structuredContent || {};
      const ok = !result.isError && structured.ok !== false;
      const value = structured.value !== undefined ? structured.value : null;
      const mutationCount = Array.isArray(value && value.mutationTargets) ? value.mutationTargets.length : 0;
      const requestedFinal = Boolean(call.name === 'rift_workspace_exec' && call.args && call.args.finish === true && call.args.dryRun !== true && value && value.committed !== false && mutationCount > 0);
      return { call_id: call.call_id, name: call.name, ok, final: Boolean(ok && requestedFinal), result: value, error: structured.error || null };
    } catch (error) {
      return { call_id: call.call_id, name: call.name, ok: false, final: false, error_code: 'TOOL_TRANSPORT_ERROR', error: String(error && error.message || error) };
    }
  }

  async function executeCall(packet) {
    const payload = await performCall(packet);
    if (payload.skip) return;
    await submitToolResult(payload);
    setBadge(payload.ok === false ? 'error' : 'ready');
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
    const assistant = listAssistantMessages();
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
    callQueue = callQueue.then(() => submitToolResult({
      call_id: null,
      name: null,
      ok: false,
      final: false,
      error_code: 'CALL_PARSE_ERROR',
      error: String(errorText)
    })).catch(() => setBadge('error'));
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
    const messages = listAssistantMessages();
    const isLatestAssistant = messages.length > 0 && messages[messages.length - 1] === message;
    const isHistorical = historicalAssistantMessages.has(message);
    if (!meaningfulAssistantContent) return;

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
      const packet = parsed.calls[0];
      callQueue = callQueue.then(() => executeCall(packet)).catch(() => setBadge('error'));
    }
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
    const selectors = role === 'assistant' ? siteAdapter.assistant : siteAdapter.user;
    const query = selectorText(selectors);
    if (!query) return;
    try {
      if (container.matches(query)) targetSet.add(container);
      const closest = container.closest(query);
      if (closest) targetSet.add(closest);
      for (const nested of queryAll(selectors, container)) targetSet.add(nested);
    } catch (_) {}
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
    const assistant = listAssistantMessages();
    const users = listUserMessages();
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
      setBadge(mcpReady ? 'ready' : 'error');
    } catch (error) {
      tools = [];
      mcpReady = false;
      setBadge('error');
    }


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

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot, { once: true });
  else boot();
})();
