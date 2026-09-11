const PROTOCOL = "rift-mcp-relay-v1";
const MAX_BODY_BYTES = 1_000_000;
const REQUEST_TIMEOUT_MS = 30_000;

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
      if (!deviceId || deviceId.length > 160) {
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
      const declared = Number(request.headers.get("content-length") || "0");
      if (declared > MAX_BODY_BYTES) return rpcError(null, -32001, "MCP request too large", 413);
      const text = await request.text();
      if (new TextEncoder().encode(text).byteLength > MAX_BODY_BYTES) {
        return rpcError(null, -32001, "MCP request too large", 413);
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
      if (message.id === undefined && message.method.startsWith("notifications/")) {
        return new Response(null, { status: 202 });
      }
      const room = env.RIFT_RELAY.getByName("primary");
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
    return json({ error: "Not found" }, 404);
  }

  acceptDevice() {
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    if (this.socket) this.socket.close(1012, "Replaced by a newer RiftOS connection");
    this.ctx.acceptWebSocket(server);
    this.socket = server;
    return new Response(null, { status: 101, webSocket: client });
  }

  async forwardMcp(payload) {
    const socket = this.socket;
    if (!socket) return rpcError(payload?.id, -32002, "RiftOS device is offline", 503);
    const requestId = crypto.randomUUID();
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        resolve(rpcError(payload?.id, -32003, "RiftOS device timed out", 504));
      }, REQUEST_TIMEOUT_MS);
      this.pending.set(requestId, { resolve, timer, rpcId: payload?.id });
      try {
        socket.send(JSON.stringify({ type: "mcp.request", requestId, payload }));
      } catch {
        clearTimeout(timer);
        this.pending.delete(requestId);
        resolve(rpcError(payload?.id, -32002, "RiftOS device disconnected", 503));
      }
    });
  }

  webSocketMessage(socket, raw) {
    if (typeof raw !== "string" || raw.length > MAX_BODY_BYTES) {
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
    if (message.type === "mcp.response" && message.payload) {
      pending.resolve(json(message.payload));
    } else {
      pending.resolve(rpcError(pending.rpcId, -32004, message.message || "RiftOS relay error", 502));
    }
  }

  webSocketClose(socket) {
    if (this.socket === socket) this.socket = null;
    this.failPending("RiftOS device disconnected");
  }

  webSocketError(socket) {
    if (this.socket === socket) this.socket = null;
    this.failPending("RiftOS device connection failed");
  }

  failPending(message) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.resolve(rpcError(pending.rpcId, -32002, message, 503));
    }
    this.pending.clear();
  }
}
