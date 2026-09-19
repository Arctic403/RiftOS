const PROTOCOL = "rift-mcp-relay-v1";
const MAX_BODY_BYTES = 1_000_000;
const REQUEST_TIMEOUT_MS = 75_000;
const MAX_PENDING_REQUESTS = 64;
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
        return new Response(null, { status: 405, headers: { allow: "POST, DELETE, OPTIONS" } });
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
    this.socket = ctx.getWebSockets()[0] || null;
    this.pending = new Map();
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/health") {
      return json({ ok: true, deviceConnected: Boolean(this.socket) });
    }
    if (url.pathname === "/device") return this.acceptDevice();
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
    this.ctx.acceptWebSocket(server);
    this.socket = server;
    return new Response(null, { status: 101, webSocket: client });
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
      socket.send(JSON.stringify({ type: "relay.ready" }));
      return;
    }
    if (message.type === "device.pong") return;
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

  webSocketClose(socket) {
    if (socket !== this.socket) return;
    this.socket = null;
    this.failPending("RiftOS device disconnected");
  }

  webSocketError(socket) {
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
