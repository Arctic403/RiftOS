const PROTOCOL = "rift-mcp-relay-v1";
const MAX_BODY_BYTES = 1_000_000;
const REQUEST_TIMEOUT_MS = 75_000;
const MAX_PENDING_REQUESTS = 64;
const MAX_DRIVER_SOCKETS = 4;
const MAX_CLI_EVENT_BYTES = 128_000;
const encoder = new TextEncoder();

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

async function requestFingerprint(payload) {
  const normalized = payload && typeof payload === "object" && !Array.isArray(payload)
    ? { ...payload }
    : payload;
  if (normalized && typeof normalized === "object" && !Array.isArray(normalized)) delete normalized.id;
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(canonicalJson(normalized)));
  return [...new Uint8Array(digest)].slice(0, 12).map(byte => byte.toString(16).padStart(2, "0")).join("");
}

function json(value, status = 200, headers = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      ...headers,
    },
  });
}

function rpcError(id, code, message, status = 200) {
  return json({ jsonrpc: "2.0", id: id ?? null, error: { code, message } }, status);
}

function bearer(request) {
  const value = request.headers.get("authorization") || "";
  return value.startsWith("Bearer ") ? value.slice(7) : "";
}

function isValidMcpPath(pathname, secret) {
  return Boolean(secret) && pathname === `/mcp/${secret}`;
}

async function readBoundedText(request, maxBytes = MAX_BODY_BYTES) {
  const declaredRaw = request.headers.get("content-length");
  if (declaredRaw != null) {
    const declared = Number(declaredRaw);
    if (!Number.isFinite(declared) || declared < 0 || declared > maxBytes) {
      throw new RangeError("request body too large");
    }
  }
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) throw new RangeError("request body too large");
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      const room = env.RIFT_RELAY.getByName("primary");
      return room.fetch(new Request("https://relay.internal/health"));
    }

    if (url.pathname === "/device") {
      if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
        return json({ error: "WebSocket upgrade required" }, 426);
      }
      if (!env.DEVICE_TOKEN || bearer(request) !== env.DEVICE_TOKEN) {
        return json({ error: "Unauthorized device" }, 401);
      }
      if (request.headers.get("x-rift-protocol") !== PROTOCOL) {
        return json({ error: "Unsupported relay protocol" }, 400);
      }
      const deviceId = request.headers.get("x-rift-device-id")?.trim();
      if (!deviceId || !/^[A-Za-z0-9._:-]{1,160}$/.test(deviceId)) {
        return json({ error: "Invalid device ID" }, 400);
      }
      const room = env.RIFT_RELAY.getByName("primary");
      const forwarded = new Request("https://relay.internal/device", request);
      return room.fetch(forwarded);
    }

    if (isValidMcpPath(url.pathname, env.MCP_PATH_TOKEN)) {
      if (request.method === "OPTIONS") {
        return new Response(null, {
          status: 204,
          headers: {
            "access-control-allow-origin": "*",
            "access-control-allow-methods": "POST, GET, DELETE, OPTIONS",
            "access-control-allow-headers": "content-type, accept, mcp-protocol-version, mcp-session-id",
          },
        });
      }
      if (request.method === "GET") {
        const room = env.RIFT_RELAY.getByName("primary");
        const afterRaw = url.searchParams.get("after") || request.headers.get("last-event-id") || "0";
        const after = /^\d{1,20}$/.test(afterRaw) ? afterRaw : "0";
        if (request.headers.get("upgrade")?.toLowerCase() === "websocket") {
          const forwarded = new Request(`https://relay.internal/events-ws?after=${after}`, request);
          return room.fetch(forwarded);
        }
        return room.fetch(
          new Request(`https://relay.internal/events-sse?after=${after}`, {
            method: "GET",
            headers: { accept: "text/event-stream" },
          }),
        );
      }
      if (request.method === "DELETE") return new Response(null, { status: 204 });
      if (request.method !== "POST") {
        return new Response(null, { status: 405, headers: { allow: "POST, GET, DELETE, OPTIONS" } });
      }
      let text;
      try {
        text = await readBoundedText(request);
      } catch (error) {
        if (error instanceof RangeError) return rpcError(null, -32001, "MCP request too large", 413);
        return rpcError(null, -32700, "Invalid UTF-8 request body", 400);
      }
      let message;
      try {
        message = JSON.parse(text);
      } catch {
        return rpcError(null, -32700, "Invalid JSON", 400);
      }
      if (!message || Array.isArray(message) || typeof message !== "object") {
        return rpcError(null, -32600, "MCP request must be one JSON-RPC object", 400);
      }
      if (message.jsonrpc !== "2.0" || typeof message.method !== "string") {
        return rpcError(message.id, -32600, "Invalid JSON-RPC request", 400);
      }
      const room = env.RIFT_RELAY.getByName("primary");
      if (message.id === undefined && message.method.startsWith("notifications/")) {
        return room.fetch(
          new Request("https://relay.internal/notification", {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: text,
          }),
        );
      }
      return room.fetch(
        new Request("https://relay.internal/mcp", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: text,
        }),
      );
    }

    return json({ error: "Not found" }, 404);
  },
};

export class RiftRelayRoom {
  constructor(ctx) {
    this.ctx = ctx;
    const allSockets = ctx.getWebSockets();
    this.socket = ctx.getWebSockets("device")[0]
      || allSockets.find(socket => (socket.deserializeAttachment?.()?.role || "device") === "device")
      || null;
    this.pending = new Map();
    this.sseClients = new Map();
    const deviceAttachment = this.socket?.deserializeAttachment?.() || {};
    const attachedSequence = Number(deviceAttachment.lastCliSequence ?? 0);
    this.lastCliSequence = Number.isSafeInteger(attachedSequence) && attachedSequence >= 0
      ? attachedSequence
      : 0;
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/health") {
      return json({
        ok: true,
        deviceConnected: Boolean(this.socket),
        driverSockets: this.ctx.getWebSockets("driver").length,
        sseClients: this.sseClients.size,
        cliSequence: this.lastCliSequence,
      });
    }
    if (url.pathname === "/device") return this.acceptDevice();
    if (url.pathname === "/events-ws") {
      const after = Number(url.searchParams.get("after") || "0");
      return this.acceptDriver(Number.isSafeInteger(after) && after >= 0 ? after : 0);
    }
    if (url.pathname === "/events-sse") {
      const after = Number(url.searchParams.get("after") || "0");
      return this.openSse(Number.isSafeInteger(after) && after >= 0 ? after : 0);
    }
    if (url.pathname === "/mcp" && request.method === "POST") {
      const payload = await request.json();
      return this.forwardMcp(payload);
    }
    if (url.pathname === "/notification" && request.method === "POST") {
      const payload = await request.json();
      return this.forwardNotification(payload);
    }
    return json({ error: "Not found" }, 404);
  }

  acceptDevice() {
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    if (this.socket) {
      this.failPending("RiftOS device connection was replaced");
      this.socket.close(1012, "Replaced by a newer RiftOS connection");
    }
    this.ctx.acceptWebSocket(server, ["device"]);
    server.serializeAttachment({ role: "device", lastCliSequence: this.lastCliSequence });
    this.socket = server;
    return new Response(null, { status: 101, webSocket: client });
  }

  acceptDriver(after) {
    const existing = this.ctx.getWebSockets("driver");
    if (existing.length + this.sseClients.size >= MAX_DRIVER_SOCKETS) {
      return json({ error: "Too many RiftCLI event subscribers" }, 503);
    }
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server, ["driver"]);
    server.serializeAttachment({ role: "driver", after });
    server.send(JSON.stringify({
      type: "cli.events.ready",
      protocol: PROTOCOL,
      transport: "websocket",
      after,
    }));
    this.requestCliReplay(after);
    return new Response(null, { status: 101, webSocket: client });
  }

  openSse(after) {
    if (this.ctx.getWebSockets("driver").length + this.sseClients.size >= MAX_DRIVER_SOCKETS) {
      return json({ error: "Too many RiftCLI event subscribers" }, 503);
    }
    const stream = new TransformStream();
    const writer = stream.writable.getWriter();
    const id = crypto.randomUUID();
    this.sseClients.set(id, { writer, after });
    writer.closed.finally(() => this.sseClients.delete(id)).catch(() => this.sseClients.delete(id));
    writer.write(encoder.encode(
      `event: message\ndata: ${JSON.stringify({
        jsonrpc: "2.0",
        method: "notifications/riftcli/ready",
        params: { protocol: PROTOCOL, transport: "sse", after },
      })}\n\n`,
    )).catch(() => this.sseClients.delete(id));
    this.requestCliReplay(after);
    return new Response(stream.readable, {
      status: 200,
      headers: {
        "content-type": "text/event-stream; charset=utf-8",
        "cache-control": "no-store",
        "connection": "keep-alive",
      },
    });
  }

  requestCliReplay(after) {
    const socket = this.socket;
    if (!socket) return;
    try {
      socket.send(JSON.stringify({ type: "cli.replay.request", after }));
    } catch {
      // Device reconnect logic owns recovery.
    }
  }

  minimumCliResumeAfter() {
    let resume = Number.isSafeInteger(this.lastCliSequence) && this.lastCliSequence >= 0
      ? this.lastCliSequence
      : 0;
    for (const driver of this.ctx.getWebSockets("driver")) {
      const after = Number(driver.deserializeAttachment?.()?.after ?? 0);
      if (Number.isSafeInteger(after) && after >= 0) resume = Math.min(resume, after);
    }
    for (const client of this.sseClients.values()) {
      const after = Number(client?.after ?? 0);
      if (Number.isSafeInteger(after) && after >= 0) resume = Math.min(resume, after);
    }
    return resume;
  }

  broadcastCliEvent(event) {
    const sequence = Number(event.sequence);
    if (!Number.isSafeInteger(sequence) || sequence <= 0) return;
    const message = JSON.stringify({ type: "cli.event", event });
    for (const driver of this.ctx.getWebSockets("driver")) {
      const attachment = driver.deserializeAttachment?.() || {};
      const after = Number(attachment.after ?? 0);
      if (Number.isSafeInteger(after) && after >= sequence) continue;
      try {
        driver.send(message);
        driver.serializeAttachment({ role: "driver", after: sequence });
      } catch {
        try { driver.close(1011, "RiftCLI event delivery failed"); } catch {}
      }
    }
    const sse = encoder.encode(
      `id: ${event.sequence}\nevent: message\ndata: ${JSON.stringify({
        jsonrpc: "2.0",
        method: "notifications/riftcli/event",
        params: event,
      })}\n\n`,
    );
    for (const [id, client] of this.sseClients.entries()) {
      const after = Number(client?.after ?? 0);
      if (Number.isSafeInteger(after) && after >= sequence) continue;
      if (client.writer.desiredSize != null && client.writer.desiredSize <= 0) {
        this.sseClients.delete(id);
        client.writer.abort("RiftCLI SSE subscriber is not keeping up").catch(() => {});
        continue;
      }
      client.after = sequence;
      client.writer.write(sse).catch(() => {
        this.sseClients.delete(id);
        client.writer.abort("RiftCLI SSE delivery failed").catch(() => {});
      });
    }
  }

  forwardNotification(payload) {
    const socket = this.socket;
    if (!socket) return new Response(null, { status: 503 });
    try {
      socket.send(JSON.stringify({ type: "mcp.notification", payload }));
      return new Response(null, { status: 202 });
    } catch {
      return new Response(null, { status: 503 });
    }
  }

  async forwardMcp(payload) {
    const socket = this.socket;
    if (!socket) return rpcError(payload?.id, -32002, "RiftOS device is offline", 503);
    const modelCallId = payload?.params?._meta?.["riftos/callId"];
    const requestId = typeof modelCallId === "string" && modelCallId.trim()
      ? `call-${modelCallId.trim().slice(0, 120)}-${await requestFingerprint(payload)}`
      : crypto.randomUUID();

    const existing = this.pending.get(requestId);
    if (existing) {
      if (existing.waiters.length >= 8) {
        return rpcError(payload?.id, -32006, "Too many retries are waiting on the same RiftOS request", 503);
      }
      return new Promise((resolve) => existing.waiters.push({ resolve, rpcId: payload?.id }));
    }
    if (this.pending.size >= MAX_PENDING_REQUESTS) {
      return rpcError(payload?.id, -32005, "RiftOS relay is busy", 503);
    }

    return new Promise((resolve) => {
      const waiters = [{ resolve, rpcId: payload?.id }];
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        for (const waiter of waiters) {
          waiter.resolve(rpcError(waiter.rpcId, -32003, "RiftOS device timed out", 504));
        }
      }, REQUEST_TIMEOUT_MS);
      this.pending.set(requestId, { waiters, timer, rpcId: payload?.id });
      try {
        socket.send(JSON.stringify({ type: "mcp.request", requestId, payload }));
      } catch {
        clearTimeout(timer);
        this.pending.delete(requestId);
        for (const waiter of waiters) {
          waiter.resolve(rpcError(waiter.rpcId, -32002, "RiftOS device disconnected", 503));
        }
      }
    });
  }

  webSocketMessage(socket, raw) {
    const role = socket.deserializeAttachment?.()?.role || (socket === this.socket ? "device" : "unknown");
    if (role === "driver") {
      return this.handleDriverMessage(socket, raw);
    }
    if (socket !== this.socket) return;
    if (typeof raw !== "string" || encoder.encode(raw).byteLength > MAX_BODY_BYTES) {
      socket.close(1009, "Message too large");
      return;
    }
    let message;
    try {
      message = JSON.parse(raw);
    } catch {
      socket.send(JSON.stringify({ type: "relay.error", message: "Invalid device JSON" }));
      return;
    }
    if (message.type === "device.hello") {
      if (message.protocol !== PROTOCOL) {
        socket.close(1002, "Unsupported protocol");
        return;
      }
      socket.send(JSON.stringify({ type: "relay.ready", cliResumeAfter: this.minimumCliResumeAfter() }));
      return;
    }
    if (message.type === "device.pong") return;
    if (message.type === "cli.event") {
      const event = message.event;
      if (!event || Array.isArray(event) || typeof event !== "object") return;
      const serialized = JSON.stringify(event);
      if (encoder.encode(serialized).byteLength > MAX_CLI_EVENT_BYTES) return;
      if (event.schema !== "rift.cli-event/1") return;
      const sequence = Number(event.sequence);
      if (!Number.isSafeInteger(sequence) || sequence <= 0) return;
      this.lastCliSequence = Math.max(this.lastCliSequence, sequence);
      socket.serializeAttachment({ role: "device", lastCliSequence: this.lastCliSequence });
      this.broadcastCliEvent(event);
      try {
        socket.send(JSON.stringify({ type: "cli.ack", sequence }));
      } catch {}
      return;
    }
    if (message.type !== "mcp.response" && message.type !== "mcp.error") return;
    const pending = this.pending.get(message.requestId);
    if (!pending) return;
    clearTimeout(pending.timer);
    this.pending.delete(message.requestId);
    if (message.type === "mcp.response" && message.payload && !Array.isArray(message.payload) && typeof message.payload === "object") {
      const payload = message.payload;
      const idMatches = JSON.stringify(payload.id ?? null) === JSON.stringify(pending.rpcId ?? null);
      if (payload.jsonrpc !== "2.0" || !idMatches || (payload.result === undefined && payload.error === undefined)) {
        for (const waiter of pending.waiters) {
          waiter.resolve(rpcError(waiter.rpcId, -32004, "Malformed RiftOS MCP response", 502));
        }
        return;
      }
      for (const waiter of pending.waiters) {
        const response = { ...payload, id: waiter.rpcId ?? null };
        waiter.resolve(json(response));
      }
    } else {
      const errorMessage = String(message.message || "RiftOS relay error").slice(0, 240);
      for (const waiter of pending.waiters) {
        waiter.resolve(rpcError(waiter.rpcId, -32004, errorMessage, 502));
      }
    }
  }

  handleDriverMessage(socket, raw) {
    if (typeof raw !== "string" || encoder.encode(raw).byteLength > 32_000) {
      socket.close(1009, "Driver message too large");
      return;
    }
    let message;
    try {
      message = JSON.parse(raw);
    } catch {
      socket.send(JSON.stringify({ type: "cli.events.error", message: "Invalid driver JSON" }));
      return;
    }
    if (message.type === "cli.events.replay") {
      const after = Number(message.after || 0);
      if (Number.isSafeInteger(after) && after >= 0) {
        socket.serializeAttachment({ role: "driver", after });
        this.requestCliReplay(after);
      }
      return;
    }
    if (message.type === "cli.events.ack") {
      const sequence = Number(message.sequence || 0);
      const current = Number(socket.deserializeAttachment?.()?.after ?? 0);
      if (Number.isSafeInteger(sequence) && sequence >= 0 &&
          (!Number.isSafeInteger(current) || sequence > current)) {
        socket.serializeAttachment({ role: "driver", after: sequence });
      }
      return;
    }
    socket.send(JSON.stringify({ type: "cli.events.error", message: "Unsupported driver event message" }));
  }

  webSocketClose(socket) {
    const role = socket.deserializeAttachment?.()?.role;
    if (role === "driver") return;
    if (socket !== this.socket) return;
    this.socket = null;
    this.failPending("RiftOS device disconnected");
  }

  webSocketError(socket) {
    const role = socket.deserializeAttachment?.()?.role;
    if (role === "driver") return;
    if (socket !== this.socket) return;
    this.socket = null;
    this.failPending("RiftOS device connection failed");
  }

  failPending(message) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      for (const waiter of pending.waiters) {
        waiter.resolve(rpcError(waiter.rpcId, -32002, message, 503));
      }
    }
    this.pending.clear();
  }
}
