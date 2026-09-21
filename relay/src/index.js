const PROTOCOL = "rift-mcp-relay-v1";

const MAX_BODY_BYTES = 1_000_000;
const REQUEST_TIMEOUT_MS = 75_000;
const MAX_PENDING_REQUESTS = 64;

const MAX_DRIVER_SOCKETS = 4;
const MAX_SSE_CLIENTS = 8;
const MAX_CLI_EVENT_BYTES = 128_000;

const MAX_SSE_BUFFER_BYTES = 512_000;
const SSE_HEARTBEAT_MS = 15_000;

const SSE_CLIENT_RETRY_MS = 2_000;
const SSE_RECONNECT_MIN_MS = 1_000;
const SSE_OPEN_WINDOW_MS = 10_000;
const MAX_SSE_OPEN_ATTEMPTS_PER_WINDOW = 24;
const SSE_FULL_RETRY_AFTER_SECONDS = 2;

const REPLAY_MIN_INTERVAL_MS = 1_000;
const REPLAY_WINDOW_MS = 10_000;
const MAX_REPLAY_REQUESTS_PER_WINDOW = 64;

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

async function proxySseResponse(clientRequest, upstreamResponse, ctx) {
  const contentType =
    upstreamResponse.headers.get("content-type") || "";

  if (
    !upstreamResponse.body ||
    !contentType.toLowerCase().includes("text/event-stream")
  ) {
    return upstreamResponse;
  }

  const reader = upstreamResponse.body.getReader();
  let finished = false;

  const detachAbortListener = () => {
    try {
      clientRequest.signal.removeEventListener(
        "abort",
        onClientAbort,
      );
    } catch {
      // Incoming request signals may already be detached.
    }
  };

  const cancelUpstream = async (reason) => {
    if (finished) {
      return;
    }

    finished = true;
    detachAbortListener();

    try {
      await reader.cancel(reason);
    } catch {
      // Upstream stream was already cancelled/closed.
    }
  };

  const onClientAbort = () => {
    const task = cancelUpstream(
      "RiftCLI SSE client disconnected",
    );

    try {
      ctx?.waitUntil?.(task);
    } catch {
      // Best-effort cleanup; stream cancellation is still attempted.
    }
  };

  if (clientRequest.signal.aborted) {
    await cancelUpstream(
      "RiftCLI SSE client already disconnected",
    );
  } else {
    clientRequest.signal.addEventListener(
      "abort",
      onClientAbort,
      { once: true },
    );
  }

  const stream = new ReadableStream({
    async pull(controller) {
      if (finished) {
        controller.close();
        return;
      }

      try {
        const { done, value } = await reader.read();

        if (done) {
          finished = true;
          detachAbortListener();
          controller.close();
          return;
        }

        controller.enqueue(value);
      } catch (error) {
        finished = true;
        detachAbortListener();

        try {
          controller.error(error);
        } catch {
          // Downstream was already closed.
        }
      }
    },

    async cancel(reason) {
      await cancelUpstream(
        reason || "RiftCLI SSE downstream cancelled",
      );
    },
  });

  return new Response(stream, {
    status: upstreamResponse.status,
    statusText: upstreamResponse.statusText,
    headers: new Headers(upstreamResponse.headers),
  });
}

export default {
  async fetch(request, env, ctx) {
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

        if (
          request.headers.get("upgrade")?.toLowerCase() ===
          "websocket"
        ) {
          return room.fetch(
            new Request(
              `https://relay.internal/events-ws?after=${after}`,
              request,
            ),
          );
        }

        const headers = new Headers();
        headers.set("accept", "text/event-stream");

        const sessionId = readSessionId(request);

        if (sessionId) {
          headers.set("mcp-session-id", sessionId);
        }

        const upstream = await room.fetch(
          new Request(
            `https://relay.internal/events-sse?after=${after}`,
            {
              method: "GET",
              headers,
              signal: request.signal,
            },
          ),
        );

        return proxySseResponse(request, upstream, ctx);
      }

      if (request.method === "DELETE") {
        const room = env.RIFT_RELAY.getByName("primary");
        const headers = new Headers();
        const sessionId = readSessionId(request);

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

    this.replayRequestTimes = [];
    this.replayLastRequestBySource = new Map();
    this.deferredReplay = null;

    this.sseStats = {
      opened: 0,
      disconnected: 0,
      explicitClosed: 0,
      sessionReplaced: 0,
      fullRejected: 0,
      reconnectThrottled: 0,
      replayThrottled: 0,
      replayDeferred: 0,
      backpressureDropped: 0,
      deliveryFailed: 0,
      heartbeatFailed: 0,
    };

    const deviceAttachment =
      this.socket?.deserializeAttachment?.() || {};

    const attachedSequence = Number(
      deviceAttachment.lastCliSequence ?? 0,
    );

    this.lastCliSequence =
      Number.isSafeInteger(attachedSequence) &&
      attachedSequence >= 0
        ? attachedSequence
        : 0;
  }

  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return json({
        ok: true,
        deviceConnected: Boolean(this.socket),
        pendingRequests: this.pending.size,
        driverSockets:
          this.ctx.getWebSockets("driver").length,
        sseClients: this.sseClients.size,
        maxDriverSockets: MAX_DRIVER_SOCKETS,
        maxSseClients: MAX_SSE_CLIENTS,
        cliSequence: this.lastCliSequence,
        deferredReplayPending: Boolean(this.deferredReplay),
        sseStats: {
          ...this.sseStats,
        },
      });
    }

    if (url.pathname === "/device") {
      return this.acceptDevice();
    }

    if (url.pathname === "/events-ws") {
      const after = parseResumeAfter(
        url.searchParams.get("after"),
      );

      return this.acceptDriver(after);
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
      this.cancelDeferredReplayIfIdle();

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
      lastCliSequence: this.lastCliSequence,
    });

    this.socket = server;

    return new Response(null, {
      status: 101,
      webSocket: client,
    });
  }

  acceptDriver(after) {
    const existing =
      this.ctx.getWebSockets("driver");

    if (existing.length >= MAX_DRIVER_SOCKETS) {
      return json(
        {
          error:
            "Too many RiftCLI WebSocket subscribers",
        },
        429,
        {
          "retry-after": String(SSE_FULL_RETRY_AFTER_SECONDS),
        },
      );
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    this.ctx.acceptWebSocket(server, ["driver"]);

    const subscriberId = crypto.randomUUID();

    server.serializeAttachment({
      role: "driver",
      subscriberId,
      after,
      sentThrough: after,
    });

    server.send(
      JSON.stringify({
        type: "cli.events.ready",
        protocol: PROTOCOL,
        transport: "websocket",
        after,
      }),
    );

    this.requestCliReplay(
      after,
      `driver:${subscriberId}`,
    );

    return new Response(null, {
      status: 101,
      webSocket: client,
    });
  }

  openSse(request, after, sessionId) {
    const now = Date.now();
    this.pruneSseOpenState(now);

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

    let effectiveAfter = after;

    if (
      this.lastCliSequence > 0 &&
      effectiveAfter > this.lastCliSequence
    ) {
      effectiveAfter = this.lastCliSequence;
    }

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
    const sessionKey = sessionId || `client:${id}`;
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
            sessionKey,
            connectedAt: Date.now(),
            lastDeliveryAt: Date.now(),
            heartbeatTimer: null,
            requestSignal: request.signal,
            onAbort,
          };

          room.sseClients.set(id, clientState);
          room.sseStats.opened += 1;

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
                `retry: ${SSE_CLIENT_RETRY_MS}\nevent: message\ndata: ${JSON.stringify({
                  jsonrpc: "2.0",
                  method: "notifications/riftcli/ready",
                  params: {
                    protocol: PROTOCOL,
                    transport: "sse",
                    after: effectiveAfter,
                  },
                })}\n\n`,
              ),
            );
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

          // Replay recovery is coalesced when throttled. It is not silently
          // discarded while a live subscriber still requires recovery.
          room.requestCliReplay(
            effectiveAfter,
            `sse:${sessionKey}`,
            { defer: true },
          );
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

      if (
        current.controller.desiredSize != null &&
        current.controller.desiredSize < heartbeat.byteLength
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

    this.cancelDeferredReplayIfIdle();

    if (client.heartbeatTimer) {
      clearTimeout(client.heartbeatTimer);
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

    this.cancelDeferredReplayIfIdle();
    return targets.length;
  }

  pruneReplayState(now = Date.now()) {
    this.replayRequestTimes = this.replayRequestTimes.filter(
      (at) => now - at < REPLAY_WINDOW_MS,
    );

    for (const [key, at] of this.replayLastRequestBySource) {
      if (now - at >= REPLAY_WINDOW_MS) {
        this.replayLastRequestBySource.delete(key);
      }
    }
  }

  hasReplayConsumers() {
    return (
      this.sseClients.size > 0 ||
      this.ctx.getWebSockets("driver").length > 0
    );
  }

  normalizeReplayAfter(after) {
    let normalizedAfter = Number(after);

    if (
      !Number.isSafeInteger(normalizedAfter) ||
      normalizedAfter < 0
    ) {
      normalizedAfter = 0;
    }

    if (
      this.lastCliSequence > 0 &&
      normalizedAfter > this.lastCliSequence
    ) {
      normalizedAfter = this.lastCliSequence;
    }

    return normalizedAfter;
  }

  requestCliReplay(
    after,
    sourceKey = "relay",
    options = {},
  ) {
    const socket = this.socket;

    if (!socket) {
      // Device reconnect uses minimumCliResumeAfter(), so no in-memory retry
      // timer is needed while the device is offline.
      return "offline";
    }

    const defer = options.defer !== false;
    const now = Date.now();
    const normalizedAfter =
      this.normalizeReplayAfter(after);

    this.pruneReplayState(now);

    const lastSourceRequest = Number(
      this.replayLastRequestBySource.get(sourceKey) ?? 0,
    );

    if (
      lastSourceRequest > 0 &&
      now - lastSourceRequest < REPLAY_MIN_INTERVAL_MS
    ) {
      this.sseStats.replayThrottled += 1;

      if (defer) {
        this.deferCliReplay(
          REPLAY_MIN_INTERVAL_MS - (now - lastSourceRequest),
        );
        return "deferred";
      }

      return "throttled";
    }

    if (
      this.replayRequestTimes.length >=
      MAX_REPLAY_REQUESTS_PER_WINDOW
    ) {
      this.sseStats.replayThrottled += 1;

      const oldest = this.replayRequestTimes[0] ?? now;
      const retryMs = Math.max(
        25,
        REPLAY_WINDOW_MS - (now - oldest),
      );

      if (defer) {
        this.deferCliReplay(retryMs);
        return "deferred";
      }

      return "throttled";
    }

    return this.sendCliReplayNow(
      normalizedAfter,
      sourceKey,
      now,
    )
      ? "sent"
      : "failed";
  }

  sendCliReplayNow(after, sourceKey, now = Date.now()) {
    const socket = this.socket;

    if (!socket) {
      return false;
    }

    this.replayRequestTimes.push(now);
    this.replayLastRequestBySource.set(sourceKey, now);

    try {
      socket.send(
        JSON.stringify({
          type: "cli.replay.request",
          after,
        }),
      );
      return true;
    } catch {
      // Device reconnect logic owns recovery.
      return false;
    }
  }

  deferCliReplay(delayMs) {
    if (!this.hasReplayConsumers()) {
      return false;
    }

    const dueAt =
      Date.now() + Math.max(25, Number(delayMs) || 25);
    const existing = this.deferredReplay;

    // One room-wide deferred replay is enough: replay from the oldest active
    // cursor covers every subscriber with a newer cursor and cannot overflow
    // a per-source deferred queue.
    if (existing && existing.dueAt <= dueAt) {
      return true;
    }

    if (existing?.timer) {
      clearTimeout(existing.timer);
    }

    const timer = setTimeout(() => {
      this.flushDeferredReplay();
    }, Math.max(25, dueAt - Date.now()));

    this.deferredReplay = {
      timer,
      dueAt,
    };
    this.sseStats.replayDeferred += 1;
    return true;
  }

  flushDeferredReplay() {
    const pending = this.deferredReplay;
    this.deferredReplay = null;

    if (pending?.timer) {
      clearTimeout(pending.timer);
    }

    if (!this.socket || !this.hasReplayConsumers()) {
      return;
    }

    const now = Date.now();
    this.pruneReplayState(now);

    if (
      this.replayRequestTimes.length >=
      MAX_REPLAY_REQUESTS_PER_WINDOW
    ) {
      const oldest = this.replayRequestTimes[0] ?? now;

      this.deferCliReplay(
        Math.max(
          25,
          REPLAY_WINDOW_MS - (now - oldest),
        ),
      );
      return;
    }

    this.sendCliReplayNow(
      this.minimumCliResumeAfter(),
      "deferred",
      now,
    );
  }

  cancelDeferredReplayIfIdle() {
    if (this.hasReplayConsumers()) {
      return false;
    }

    const pending = this.deferredReplay;

    if (!pending) {
      return false;
    }

    this.deferredReplay = null;

    if (pending.timer) {
      clearTimeout(pending.timer);
    }

    return true;
  }

  minimumCliResumeAfter() {
    let resume =
      Number.isSafeInteger(this.lastCliSequence) &&
      this.lastCliSequence >= 0
        ? this.lastCliSequence
        : 0;

    for (
      const driver of
      this.ctx.getWebSockets("driver")
    ) {
      const after = Number(
        driver.deserializeAttachment?.()?.after ?? 0,
      );

      if (
        Number.isSafeInteger(after) &&
        after >= 0
      ) {
        resume = Math.min(resume, after);
      }
    }

    for (const client of this.sseClients.values()) {
      const after = Number(client?.after ?? 0);

      if (
        Number.isSafeInteger(after) &&
        after >= 0
      ) {
        resume = Math.min(resume, after);
      }
    }

    return resume;
  }

  broadcastCliEvent(event) {
    const sequence = Number(event.sequence);

    if (
      !Number.isSafeInteger(sequence) ||
      sequence <= 0
    ) {
      return;
    }

    const message = JSON.stringify({
      type: "cli.event",
      event,
    });

    for (
      const driver of
      this.ctx.getWebSockets("driver")
    ) {
      const attachment =
        driver.deserializeAttachment?.() || {};

      const after = Number(
        attachment.after ?? 0,
      );

      const sentThrough = Number(
        attachment.sentThrough ?? after,
      );

      const deliveredThrough = Math.max(
        Number.isSafeInteger(after) && after >= 0
          ? after
          : 0,
        Number.isSafeInteger(sentThrough) && sentThrough >= 0
          ? sentThrough
          : 0,
      );

      if (deliveredThrough >= sequence) {
        continue;
      }

      try {
        driver.send(message);

        driver.serializeAttachment({
          role: "driver",
          subscriberId:
            typeof attachment.subscriberId === "string"
              ? attachment.subscriberId
              : crypto.randomUUID(),
          after:
            Number.isSafeInteger(after) && after >= 0
              ? after
              : 0,
          sentThrough:
            Number.isSafeInteger(sentThrough) && sentThrough >= 0
              ? Math.max(sentThrough, sequence)
              : sequence,
        });
      } catch {
        try {
          driver.close(
            1011,
            "RiftCLI event delivery failed",
          );
        } catch {
          // Driver socket was already closed.
        }
      }
    }

    const sse = encoder.encode(
      `id: ${sequence}\nevent: message\ndata: ${JSON.stringify(
        {
          jsonrpc: "2.0",
          method: "notifications/riftcli/event",
          params: event,
        },
      )}\n\n`,
    );

    const deliveredSessions = new Set();

    for (
      const [id, client] of
      [...this.sseClients.entries()]
    ) {
      const sessionKey = client.sessionKey || `client:${id}`;

      if (deliveredSessions.has(sessionKey)) {
        continue;
      }

      const after = Number(client?.after ?? 0);

      if (
        Number.isSafeInteger(after) &&
        after >= sequence
      ) {
        continue;
      }

      if (
        client.controller.desiredSize != null &&
        client.controller.desiredSize < sse.byteLength
      ) {
        this.dropSseClient(
          id,
          "backpressure",
          "MCP SSE subscriber is not keeping up",
          "error",
        );
        continue;
      }

      try {
        client.controller.enqueue(sse);
        client.after = sequence;
        client.lastDeliveryAt = Date.now();
        deliveredSessions.add(sessionKey);
      } catch {
        this.dropSseClient(
          id,
          "delivery",
          "MCP SSE delivery failed",
          "error",
        );
      }
    }
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
    const role =
      socket.deserializeAttachment?.()?.role ||
      (socket === this.socket
        ? "device"
        : "unknown");

    if (role === "driver") {
      this.handleDriverMessage(socket, raw);
      return;
    }

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

      const deviceAckSequence = Number(
        message.cliAckSequence ?? 0,
      );

      if (
        Number.isSafeInteger(deviceAckSequence) &&
        deviceAckSequence >= 0
      ) {
        this.lastCliSequence = Math.max(
          this.lastCliSequence,
          deviceAckSequence,
        );

        socket.serializeAttachment({
          role: "device",
          lastCliSequence:
            this.lastCliSequence,
        });
      }

      socket.send(
        JSON.stringify({
          type: "relay.ready",
          cliResumeAfter:
            this.minimumCliResumeAfter(),
        }),
      );

      return;
    }

    if (message.type === "device.pong") {
      return;
    }

    if (message.type === "cli.event") {
      const event = message.event;

      if (
        !event ||
        Array.isArray(event) ||
        typeof event !== "object"
      ) {
        return;
      }

      const serialized = JSON.stringify(event);

      if (
        encoder.encode(serialized).byteLength >
        MAX_CLI_EVENT_BYTES
      ) {
        return;
      }

      if (event.schema !== "rift.cli-event/1") {
        return;
      }

      const sequence = Number(event.sequence);

      if (
        !Number.isSafeInteger(sequence) ||
        sequence <= 0
      ) {
        return;
      }

      this.lastCliSequence = Math.max(
        this.lastCliSequence,
        sequence,
      );

      socket.serializeAttachment({
        role: "device",
        lastCliSequence:
          this.lastCliSequence,
      });

      this.broadcastCliEvent(event);

      try {
        socket.send(
          JSON.stringify({
            type: "cli.ack",
            sequence,
          }),
        );
      } catch {
        // Device will reconnect and replay.
      }

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

  handleDriverMessage(socket, raw) {
    if (
      typeof raw !== "string" ||
      encoder.encode(raw).byteLength > 32_000
    ) {
      socket.close(
        1009,
        "Driver message too large",
      );

      return;
    }

    let message;

    try {
      message = JSON.parse(raw);
    } catch {
      socket.send(
        JSON.stringify({
          type: "cli.events.error",
          message: "Invalid driver JSON",
        }),
      );

      return;
    }

    if (message.type === "cli.events.replay") {
      const after = parseResumeAfter(
        message.after,
      );

      const attachment =
        socket.deserializeAttachment?.() || {};

      const subscriberId =
        typeof attachment.subscriberId === "string" &&
        attachment.subscriberId
          ? attachment.subscriberId
          : crypto.randomUUID();

      const replayState = this.requestCliReplay(
        after,
        `driver:${subscriberId}`,
        { defer: false },
      );

      if (replayState !== "sent") {
        try {
          socket.send(
            JSON.stringify({
              type: "cli.events.error",
              message:
                replayState === "offline"
                  ? "RiftOS device is offline"
                  : replayState === "failed"
                    ? "Replay request failed"
                    : "Replay request throttled",
              retryAfterMs:
                replayState === "throttled"
                  ? REPLAY_MIN_INTERVAL_MS
                  : undefined,
            }),
          );
        } catch {
          // Driver socket closed while reporting replay failure.
        }

        return;
      }

      socket.serializeAttachment({
        role: "driver",
        subscriberId,
        after,
        sentThrough: after,
      });

      return;
    }

    if (message.type === "cli.events.ack") {
      const sequence = Number(
        message.sequence ?? 0,
      );

      const attachment =
        socket.deserializeAttachment?.() || {};

      const current = Number(
        attachment.after ?? 0,
      );

      const sentThrough = Number(
        attachment.sentThrough ?? current,
      );

      if (
        Number.isSafeInteger(sequence) &&
        sequence >= 0 &&
        Number.isSafeInteger(sentThrough) &&
        sequence <= sentThrough &&
        (!Number.isSafeInteger(current) ||
          sequence > current)
      ) {
        socket.serializeAttachment({
          role: "driver",
          subscriberId:
            typeof attachment.subscriberId === "string"
              ? attachment.subscriberId
              : crypto.randomUUID(),
          after: sequence,
          sentThrough,
        });
      }

      return;
    }

    socket.send(
      JSON.stringify({
        type: "cli.events.error",
        message:
          "Unsupported driver event message",
      }),
    );
  }

  webSocketClose(socket) {
    const attachment =
      socket.deserializeAttachment?.() || {};
    const role = attachment.role;

    if (role === "driver") {
      this.cancelDeferredReplayIfIdle();
      return;
    }

    if (socket !== this.socket) {
      return;
    }

    this.socket = null;

    this.failPending(
      "RiftOS device disconnected",
    );
  }

  webSocketError(socket) {
    const attachment =
      socket.deserializeAttachment?.() || {};
    const role = attachment.role;

    if (role === "driver") {
      this.cancelDeferredReplayIfIdle();
      return;
    }

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