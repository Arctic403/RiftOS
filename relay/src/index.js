const PROTOCOL = "rift-mcp-relay-v1";

const MAX_BODY_BYTES = 1_000_000;
const REQUEST_TIMEOUT_MS = 75_000;
const MAX_PENDING_REQUESTS = 64;

const MAX_SSE_CLIENTS = 8;

const MAX_SSE_BUFFER_BYTES = 512_000;
const SSE_HEARTBEAT_MS = 15_000;
const MAX_SSE_NO_DRAIN_HEARTBEATS = 2;
const SSE_LEASE_MS = 180_000;
const SSE_LEASE_JITTER_MS = 30_000;

const SSE_CLIENT_RETRY_MS = 2_000;
const SSE_RECONNECT_MIN_MS = 1_000;
const SSE_OPEN_WINDOW_MS = 10_000;
const MAX_SSE_OPEN_ATTEMPTS_PER_WINDOW = 24;
const SSE_FULL_RETRY_AFTER_SECONDS = 2;

const encoder = new TextEncoder();

function canonicalJson(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(",")}]`;
  }

  if (value && typeof value === "object") {
    return `{${Object.keys(value)
      .sort()
      .map(
        (key) =>
          `${JSON.stringify(key)}:${canonicalJson(value[key])}`,
      )
      .join(",")}}`;
  }

  return JSON.stringify(value);
}

async function requestFingerprint(payload) {
  const normalized =
    payload && typeof payload === "object" && !Array.isArray(payload)
      ? { ...payload }
      : payload;

  if (
    normalized &&
    typeof normalized === "object" &&
    !Array.isArray(normalized)
  ) {
    delete normalized.id;
  }

  const digest = await crypto.subtle.digest(
    "SHA-256",
    encoder.encode(canonicalJson(normalized)),
  );

  return [...new Uint8Array(digest)]
    .slice(0, 12)
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
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
  return json(
    {
      jsonrpc: "2.0",
      id: id ?? null,
      error: {
        code,
        message,
      },
    },
    status,
  );
}

function bearer(request) {
  const value = request.headers.get("authorization") || "";
  return value.startsWith("Bearer ") ? value.slice(7) : "";
}

function isValidMcpPath(pathname, secret) {
  return Boolean(secret) && pathname === `/mcp/${secret}`;
}

function readSessionId(request) {
  const value = request.headers.get("mcp-session-id")?.trim() || "";

  if (!value || value.length > 256) {
    return "";
  }

  if (!/^[\x21-\x7E]+$/.test(value)) {
    return "";
  }

  return value;
}

function readDiagnosticSubscriberId(url) {
  const value = url.searchParams.get("subscriber")?.trim() || "";

  if (!value || !/^[A-Za-z0-9._:-]{1,128}$/.test(value)) {
    return "";
  }

  return `diag:${value}`;
}

function parseResumeAfter(value) {
  const raw = String(value ?? "0");

  if (!/^\d{1,20}$/.test(raw)) {
    return 0;
  }

  const number = Number(raw);

  return Number.isSafeInteger(number) && number >= 0 ? number : 0;
}

async function readBoundedText(request, maxBytes = MAX_BODY_BYTES) {
  const declaredRaw = request.headers.get("content-length");

  if (declaredRaw != null) {
    const declared = Number(declaredRaw);

    if (
      !Number.isFinite(declared) ||
      declared < 0 ||
      declared > maxBytes
    ) {
      throw new RangeError("request body too large");
    }
  }

  if (!request.body) {
    return "";
  }

  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;

  try {
    while (true) {
      const { done, value } = await reader.read();

      if (done) {
        break;
      }

      total += value.byteLength;

      if (total > maxBytes) {
        throw new RangeError("request body too large");
      }

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

      return room.fetch(
        new Request("https://relay.internal/health"),
      );
    }

    if (url.pathname === "/device") {
      if (
        request.headers.get("upgrade")?.toLowerCase() !== "websocket"
      ) {
        return json(
          { error: "WebSocket upgrade required" },
          426,
        );
      }

      if (
        !env.DEVICE_TOKEN ||
        bearer(request) !== env.DEVICE_TOKEN
      ) {
        return json(
          { error: "Unauthorized device" },
          401,
        );
      }

      if (
        request.headers.get("x-rift-protocol") !== PROTOCOL
      ) {
        return json(
          { error: "Unsupported relay protocol" },
          400,
        );
      }

      const deviceId =
        request.headers.get("x-rift-device-id")?.trim();

      if (
        !deviceId ||
        !/^[A-Za-z0-9._:-]{1,160}$/.test(deviceId)
      ) {
        return json(
          { error: "Invalid device ID" },
          400,
        );
      }

      const room = env.RIFT_RELAY.getByName("primary");

      return room.fetch(
        new Request(
          "https://relay.internal/device",
          request,
        ),
      );
    }

    if (isValidMcpPath(url.pathname, env.MCP_PATH_TOKEN)) {
      if (request.method === "OPTIONS") {
        return new Response(null, {
          status: 204,
          headers: {
            "access-control-allow-origin": "*",
            "access-control-allow-methods":
              "POST, GET, DELETE, OPTIONS",
            "access-control-allow-headers":
              "content-type, accept, last-event-id, mcp-protocol-version, mcp-session-id",
            "access-control-expose-headers":
              "mcp-session-id",
            "access-control-max-age": "86400",
          },
        });
      }

      if (request.method === "GET") {
        const room = env.RIFT_RELAY.getByName("primary");

        const after = parseResumeAfter(
          request.headers.get("last-event-id") ||
            url.searchParams.get("after") ||
            "0",
        );

        const headers = new Headers();
        headers.set("accept", "text/event-stream");

        const sessionId =
          readSessionId(request) ||
          readDiagnosticSubscriberId(url);

        if (sessionId) {
          headers.set("mcp-session-id", sessionId);
        }

        return room.fetch(
          new Request(
            `https://relay.internal/events-sse?after=${after}`,
            {
              method: "GET",
              headers,
              signal: request.signal,
            },
          ),
        );
      }

      if (request.method === "DELETE") {
        const room = env.RIFT_RELAY.getByName("primary");
        const headers = new Headers();
        const sessionId =
          readSessionId(request) ||
          readDiagnosticSubscriberId(url);

        if (sessionId) {
          headers.set("mcp-session-id", sessionId);
        }

        return room.fetch(
          new Request(
            "https://relay.internal/events-close",
            {
              method: "DELETE",
              headers,
            },
          ),
        );
      }

      if (request.method !== "POST") {
        return new Response(null, {
          status: 405,
          headers: {
            allow: "POST, GET, DELETE, OPTIONS",
          },
        });
      }

      let text;

      try {
        text = await readBoundedText(request);
      } catch (error) {
        if (error instanceof RangeError) {
          return rpcError(
            null,
            -32001,
            "MCP request too large",
            413,
          );
        }

        return rpcError(
          null,
          -32700,
          "Invalid UTF-8 request body",
          400,
        );
      }

      let message;

      try {
        message = JSON.parse(text);
      } catch {
        return rpcError(
          null,
          -32700,
          "Invalid JSON",
          400,
        );
      }

      if (
        !message ||
        Array.isArray(message) ||
        typeof message !== "object"
      ) {
        return rpcError(
          null,
          -32600,
          "MCP request must be one JSON-RPC object",
          400,
        );
      }

      if (
        message.jsonrpc !== "2.0" ||
        typeof message.method !== "string"
      ) {
        return rpcError(
          message.id,
          -32600,
          "Invalid JSON-RPC request",
          400,
        );
      }

      const room = env.RIFT_RELAY.getByName("primary");

      if (
        message.id === undefined &&
        message.method.startsWith("notifications/")
      ) {
        return room.fetch(
          new Request(
            "https://relay.internal/notification",
            {
              method: "POST",
              headers: {
                "content-type": "application/json",
              },
              body: text,
            },
          ),
        );
      }

      return room.fetch(
        new Request("https://relay.internal/mcp", {
          method: "POST",
          headers: {
            "content-type": "application/json",
          },
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

    this.socket =
      ctx.getWebSockets("device")[0] ||
      allSockets.find(
        (socket) =>
          socket.deserializeAttachment?.()?.role === "device",
      ) ||
      null;

    this.pending = new Map();
    this.sseClients = new Map();

    this.sseOpenAttempts = [];
    this.sseLastOpenBySession = new Map();

    this.sseStats = {
      opened: 0,
      disconnected: 0,
      explicitClosed: 0,
      sessionReplaced: 0,
      fullRejected: 0,
      reconnectThrottled: 0,
      backpressureDropped: 0,
      deliveryFailed: 0,
      heartbeatFailed: 0,
      leaseExpired: 0,
      anonymousRejected: 0,
    };
  }

  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return json({
        ok: true,
        deviceConnected: Boolean(this.socket),
        pendingRequests: this.pending.size,
        sseClients: this.sseClients.size,
        maxSseClients: MAX_SSE_CLIENTS,
        sseStats: {
          ...this.sseStats,
        },
      });
    }

    if (url.pathname === "/device") {
      return this.acceptDevice();
    }

    if (url.pathname === "/events-sse") {
      const after = parseResumeAfter(
        url.searchParams.get("after"),
      );

      const sessionId = readSessionId(request);

      return this.openSse(request, after, sessionId);
    }

    if (
      url.pathname === "/events-close" &&
      request.method === "DELETE"
    ) {
      const sessionId = readSessionId(request);

      if (!sessionId) {
        return json(
          {
            error:
              "Mcp-Session-Id is required to terminate an SSE session",
          },
          400,
          {
            "access-control-allow-origin": "*",
            "access-control-expose-headers":
              "mcp-session-id",
          },
        );
      }

      this.closeSseSession(
        sessionId,
        "MCP session closed",
        "explicitClosed",
      );
      this.sseLastOpenBySession.delete(sessionId);

      return new Response(null, {
        status: 204,
        headers: {
          "cache-control": "no-store",
          "access-control-allow-origin": "*",
          "access-control-expose-headers":
            "mcp-session-id",
        },
      });
    }

    if (
      url.pathname === "/mcp" &&
      request.method === "POST"
    ) {
      let payload;

      try {
        payload = await request.json();
      } catch {
        return rpcError(
          null,
          -32700,
          "Invalid internal MCP payload",
          400,
        );
      }

      return this.forwardMcp(payload);
    }

    if (
      url.pathname === "/notification" &&
      request.method === "POST"
    ) {
      let payload;

      try {
        payload = await request.json();
      } catch {
        return new Response(null, { status: 400 });
      }

      return this.forwardNotification(payload);
    }

    return json({ error: "Not found" }, 404);
  }

  acceptDevice() {
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    if (this.socket) {
      this.failPending(
        "RiftOS device connection was replaced",
      );

      try {
        this.socket.close(
          1012,
          "Replaced by a newer RiftOS connection",
        );
      } catch {
        // Existing device socket was already closed.
      }
    }

    this.ctx.acceptWebSocket(server, ["device"]);

    server.serializeAttachment({
      role: "device",
    });

    this.socket = server;

    return new Response(null, {
      status: 101,
      webSocket: client,
    });
  }

  openSse(request, after, sessionId) {
    const now = Date.now();
    this.pruneSseOpenState(now);

    if (!sessionId) {
      this.sseStats.anonymousRejected += 1;

      return json(
        {
          error:
            "Mcp-Session-Id or diagnostic subscriber is required for SSE",
        },
        400,
        {
          "access-control-allow-origin": "*",
          "access-control-expose-headers":
            "mcp-session-id",
        },
      );
    }

    const sameSessionIds = sessionId
      ? [...this.sseClients.entries()]
          .filter(([, client]) => client.sessionId === sessionId)
          .map(([id]) => id)
      : [];

    // Throttle a rapid duplicate open without touching the healthy stream
    // it is trying to replace.
    if (sessionId && sameSessionIds.length > 0) {
      const lastOpen = Number(
        this.sseLastOpenBySession.get(sessionId) ?? 0,
      );

      if (
        Number.isFinite(lastOpen) &&
        lastOpen > 0 &&
        now - lastOpen < SSE_RECONNECT_MIN_MS
      ) {
        this.sseStats.reconnectThrottled += 1;

        const retrySeconds = Math.max(
          1,
          Math.ceil(
            (SSE_RECONNECT_MIN_MS - (now - lastOpen)) / 1_000,
          ),
        );

        return this.sseTooMany(
          "MCP SSE reconnect is too fast",
          retrySeconds,
        );
      }
    }

    if (
      this.sseOpenAttempts.length >=
      MAX_SSE_OPEN_ATTEMPTS_PER_WINDOW
    ) {
      this.sseStats.reconnectThrottled += 1;

      const oldest = this.sseOpenAttempts[0] ?? now;
      const retrySeconds = Math.max(
        1,
        Math.ceil(
          (SSE_OPEN_WINDOW_MS - (now - oldest)) / 1_000,
        ),
      );

      return this.sseTooMany(
        "Too many MCP SSE reconnect attempts",
        retrySeconds,
      );
    }

    this.sseOpenAttempts.push(now);

    const effectiveAfter = after;

    // Same-session replacement does not consume an extra capacity slot.
    // Never evict an unrelated stream to create room.
    const effectiveClientCount =
      this.sseClients.size - sameSessionIds.length;

    if (effectiveClientCount >= MAX_SSE_CLIENTS) {
      this.sseStats.fullRejected += 1;

      return this.sseTooMany(
        "Too many MCP SSE subscribers",
        SSE_FULL_RETRY_AFTER_SECONDS,
      );
    }

    const id = crypto.randomUUID();
    const room = this;

    const stream = new ReadableStream(
      {
        start(controller) {
          const onAbort = () => {
            room.dropSseClient(
              id,
              "disconnect",
              "MCP SSE request aborted",
              "close",
            );
          };

          const clientState = {
            id,
            controller,

            // Connection-local delivery cursor only. It is deliberately not
            // reused on a later reconnect. The reconnect authority is the
            // client's Last-Event-ID (or explicit after fallback).
            after: effectiveAfter,

            sessionId,
            connectedAt: Date.now(),
            lastDeliveryAt: Date.now(),
            heartbeatTimer: null,
            leaseTimer: null,
            leaseExpiresAt: 0,
            heartbeatDesiredSize: controller.desiredSize,
            noDrainHeartbeats: 0,
            requestSignal: request.signal,
            onAbort,
          };

          room.sseClients.set(id, clientState);
          room.sseStats.opened += 1;

          const leaseMs =
            SSE_LEASE_MS +
            Math.floor(Math.random() * (SSE_LEASE_JITTER_MS + 1));
          clientState.leaseExpiresAt = Date.now() + leaseMs;
          clientState.leaseTimer = setTimeout(() => {
            room.dropSseClient(
              id,
              "leaseExpired",
              "MCP SSE lease expired; reconnect with Last-Event-ID",
              "close",
            );
          }, leaseMs);

          if (request.signal.aborted) {
            onAbort();
            return;
          }

          request.signal.addEventListener(
            "abort",
            onAbort,
            { once: true },
          );

          try {
            controller.enqueue(
              encoder.encode(
                `retry: ${SSE_CLIENT_RETRY_MS}\n: rift-mcp-ready\n\n`,
              ),
            );
            clientState.heartbeatDesiredSize =
              controller.desiredSize;
          } catch {
            room.dropSseClient(
              id,
              "delivery",
              "Initial MCP SSE delivery failed",
              "error",
            );
            return;
          }

          room.scheduleSseHeartbeat(id);

        },

        cancel(reason) {
          room.dropSseClient(
            id,
            "disconnect",
            typeof reason === "string"
              ? reason
              : "MCP SSE downstream cancelled",
            "none",
          );
        },
      },
      {
        highWaterMark: MAX_SSE_BUFFER_BYTES,
        size(chunk) {
          return chunk?.byteLength || 1;
        },
      },
    );

    // ReadableStream.start() runs synchronously. Replace the old stream only
    // after the new same-session stream has actually registered.
    if (this.sseClients.has(id)) {
      if (sessionId) {
        this.sseLastOpenBySession.set(sessionId, now);
      }

      for (const oldId of sameSessionIds) {
        this.closeSseClient(
          oldId,
          "Replaced by a newer MCP SSE connection",
          "sessionReplaced",
        );
      }
    }

    const headers = {
      "content-type":
        "text/event-stream; charset=utf-8",
      "cache-control":
        "no-store, no-cache, must-revalidate",
      "access-control-allow-origin": "*",
      "access-control-expose-headers":
        "mcp-session-id",
      "x-accel-buffering": "no",
    };

    if (sessionId) {
      headers["mcp-session-id"] = sessionId;
    }

    return new Response(stream, {
      status: 200,
      headers,
    });
  }

  sseTooMany(message, retryAfterSeconds) {
    return json(
      { error: message },
      429,
      {
        "retry-after": String(retryAfterSeconds),
        "access-control-allow-origin": "*",
        "access-control-expose-headers":
          "retry-after, mcp-session-id",
      },
    );
  }

  pruneSseOpenState(now = Date.now()) {
    this.sseOpenAttempts = this.sseOpenAttempts.filter(
      (at) => now - at < SSE_OPEN_WINDOW_MS,
    );

    for (const [sessionId, at] of this.sseLastOpenBySession) {
      if (now - at >= SSE_OPEN_WINDOW_MS) {
        this.sseLastOpenBySession.delete(sessionId);
      }
    }
  }

  scheduleSseHeartbeat(id) {
    const client = this.sseClients.get(id);

    if (!client) {
      return;
    }

    if (client.heartbeatTimer) {
      clearTimeout(client.heartbeatTimer);
    }

    client.heartbeatTimer = setTimeout(() => {
      const current = this.sseClients.get(id);

      if (!current) {
        return;
      }

      const heartbeat = encoder.encode(
        `: rift-heartbeat ${Date.now()}\n\n`,
      );

      const desiredSize =
        current.controller.desiredSize;
      const previousDesiredSize = Number(
        current.heartbeatDesiredSize,
      );

      if (desiredSize != null) {
        const madeDrainProgress =
          Number.isFinite(previousDesiredSize) &&
          desiredSize > previousDesiredSize;
        const hasQueuedBytes =
          desiredSize < MAX_SSE_BUFFER_BYTES;

        if (hasQueuedBytes && !madeDrainProgress) {
          current.noDrainHeartbeats += 1;
        } else {
          current.noDrainHeartbeats = 0;
        }

        if (
          current.noDrainHeartbeats >=
          MAX_SSE_NO_DRAIN_HEARTBEATS
        ) {
          this.dropSseClient(
            id,
            "backpressure",
            "MCP SSE subscriber stopped draining",
            "error",
          );
          return;
        }
      }

      if (
        desiredSize != null &&
        desiredSize < heartbeat.byteLength
      ) {
        this.dropSseClient(
          id,
          "backpressure",
          "MCP SSE subscriber is not keeping up",
          "error",
        );
        return;
      }

      try {
        current.controller.enqueue(heartbeat);
        current.lastDeliveryAt = Date.now();
        current.heartbeatDesiredSize =
          current.controller.desiredSize;
      } catch {
        this.dropSseClient(
          id,
          "heartbeat",
          "MCP SSE heartbeat failed",
          "error",
        );
        return;
      }

      this.scheduleSseHeartbeat(id);
    }, SSE_HEARTBEAT_MS);
  }

  dropSseClient(
    id,
    reasonKind,
    reasonMessage = "MCP SSE stream closed",
    streamAction = "close",
  ) {
    const client = this.sseClients.get(id);

    if (!client) {
      return false;
    }

    this.sseClients.delete(id);


    if (client.heartbeatTimer) {
      clearTimeout(client.heartbeatTimer);
    }

    if (client.leaseTimer) {
      clearTimeout(client.leaseTimer);
    }

    try {
      client.requestSignal?.removeEventListener(
        "abort",
        client.onAbort,
      );
    } catch {
      // Signal listener was already removed.
    }

    switch (reasonKind) {
      case "disconnect":
        this.sseStats.disconnected += 1;
        break;
      case "explicitClosed":
        this.sseStats.explicitClosed += 1;
        break;
      case "sessionReplaced":
        this.sseStats.sessionReplaced += 1;
        break;
      case "backpressure":
        this.sseStats.backpressureDropped += 1;
        break;
      case "delivery":
        this.sseStats.deliveryFailed += 1;
        break;
      case "heartbeat":
        this.sseStats.heartbeatFailed += 1;
        break;
      case "leaseExpired":
        this.sseStats.leaseExpired += 1;
        break;
    }

    if (streamAction === "close") {
      try {
        client.controller.close();
      } catch {
        // Stream was already closed/cancelled.
      }
    } else if (streamAction === "error") {
      try {
        client.controller.error(
          new Error(reasonMessage),
        );
      } catch {
        // Stream was already closed/cancelled.
      }
    }

    return true;
  }

  closeSseClient(
    id,
    reason = "MCP SSE stream closed",
    reasonKind = "explicitClosed",
  ) {
    return this.dropSseClient(
      id,
      reasonKind,
      reason,
      "close",
    );
  }

  closeSseSession(
    sessionId,
    reason = "MCP session closed",
    reasonKind = "explicitClosed",
  ) {
    if (!sessionId) {
      return 0;
    }

    const targets = [];

    for (const [id, client] of this.sseClients) {
      if (client.sessionId === sessionId) {
        targets.push(id);
      }
    }

    for (const id of targets) {
      this.closeSseClient(
        id,
        reason,
        reasonKind,
      );
    }

    return targets.length;
  }

  forwardNotification(payload) {
    const socket = this.socket;

    if (!socket) {
      return new Response(null, {
        status: 503,
      });
    }

    try {
      socket.send(
        JSON.stringify({
          type: "mcp.notification",
          payload,
        }),
      );

      return new Response(null, {
        status: 202,
      });
    } catch {
      return new Response(null, {
        status: 503,
      });
    }
  }

  async forwardMcp(payload) {
    const socket = this.socket;

    if (!socket) {
      return rpcError(
        payload?.id,
        -32002,
        "RiftOS device is offline",
        503,
      );
    }

    const modelCallId =
      payload?.params?._meta?.["riftos/callId"];

    const requestId =
      typeof modelCallId === "string" &&
      modelCallId.trim()
        ? `call-${modelCallId
            .trim()
            .slice(
              0,
              120,
            )}-${await requestFingerprint(payload)}`
        : crypto.randomUUID();

    const existing = this.pending.get(requestId);

    if (existing) {
      if (existing.waiters.length >= 8) {
        return rpcError(
          payload?.id,
          -32006,
          "Too many retries are waiting on the same RiftOS request",
          503,
        );
      }

      return new Promise((resolve) => {
        existing.waiters.push({
          resolve,
          rpcId: payload?.id,
        });
      });
    }

    if (
      this.pending.size >= MAX_PENDING_REQUESTS
    ) {
      return rpcError(
        payload?.id,
        -32005,
        "RiftOS relay is busy",
        503,
      );
    }

    return new Promise((resolve) => {
      const waiters = [
        {
          resolve,
          rpcId: payload?.id,
        },
      ];

      const timer = setTimeout(() => {
        this.pending.delete(requestId);

        for (const waiter of waiters) {
          waiter.resolve(
            rpcError(
              waiter.rpcId,
              -32003,
              "RiftOS device timed out",
              504,
            ),
          );
        }
      }, REQUEST_TIMEOUT_MS);

      this.pending.set(requestId, {
        waiters,
        timer,
        rpcId: payload?.id,
      });

      try {
        socket.send(
          JSON.stringify({
            type: "mcp.request",
            requestId,
            payload,
          }),
        );
      } catch {
        clearTimeout(timer);
        this.pending.delete(requestId);

        for (const waiter of waiters) {
          waiter.resolve(
            rpcError(
              waiter.rpcId,
              -32002,
              "RiftOS device disconnected",
              503,
            ),
          );
        }
      }
    });
  }

  webSocketMessage(socket, raw) {
    if (socket !== this.socket) {
      return;
    }

    if (
      typeof raw !== "string" ||
      encoder.encode(raw).byteLength >
        MAX_BODY_BYTES
    ) {
      socket.close(1009, "Message too large");
      return;
    }

    let message;

    try {
      message = JSON.parse(raw);
    } catch {
      socket.send(
        JSON.stringify({
          type: "relay.error",
          message: "Invalid device JSON",
        }),
      );

      return;
    }

    if (message.type === "device.hello") {
      if (message.protocol !== PROTOCOL) {
        socket.close(
          1002,
          "Unsupported protocol",
        );

        return;
      }

      socket.send(
        JSON.stringify({
          type: "relay.ready",
        }),
      );

      return;
    }

    if (message.type === "device.pong") {
      return;
    }

    if (
      message.type !== "mcp.response" &&
      message.type !== "mcp.error"
    ) {
      return;
    }

    const pending =
      this.pending.get(message.requestId);

    if (!pending) {
      return;
    }

    clearTimeout(pending.timer);
    this.pending.delete(message.requestId);

    if (
      message.type === "mcp.response" &&
      message.payload &&
      !Array.isArray(message.payload) &&
      typeof message.payload === "object"
    ) {
      const payload = message.payload;

      const idMatches =
        JSON.stringify(payload.id ?? null) ===
        JSON.stringify(pending.rpcId ?? null);

      if (
        payload.jsonrpc !== "2.0" ||
        !idMatches ||
        (payload.result === undefined &&
          payload.error === undefined)
      ) {
        for (const waiter of pending.waiters) {
          waiter.resolve(
            rpcError(
              waiter.rpcId,
              -32004,
              "Malformed RiftOS MCP response",
              502,
            ),
          );
        }

        return;
      }

      for (const waiter of pending.waiters) {
        waiter.resolve(
          json({
            ...payload,
            id: waiter.rpcId ?? null,
          }),
        );
      }

      return;
    }

    const errorMessage = String(
      message.message ||
        "RiftOS relay error",
    ).slice(0, 240);

    for (const waiter of pending.waiters) {
      waiter.resolve(
        rpcError(
          waiter.rpcId,
          -32004,
          errorMessage,
          502,
        ),
      );
    }
  }

  webSocketClose(socket) {
    if (socket !== this.socket) {
      return;
    }

    this.socket = null;

    this.failPending(
      "RiftOS device disconnected",
    );
  }

  webSocketError(socket) {
    if (socket !== this.socket) {
      return;
    }

    this.socket = null;

    this.failPending(
      "RiftOS device connection failed",
    );
  }

  failPending(message) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);

      for (const waiter of pending.waiters) {
        waiter.resolve(
          rpcError(
            waiter.rpcId,
            -32002,
            message,
            503,
          ),
        );
      }
    }

    this.pending.clear();
  }
}