const PROTOCOL = "rift-cli-relay-v1";

const MAX_BODY_BYTES = 256_000;
const MAX_MESSAGE_BYTES = 1_000_000;
const MAX_PENDING_REQUESTS = 64;
const MAX_WAITERS_PER_REQUEST = 8;
const REQUEST_TIMEOUT_MS = 75_000;
const MAX_COMPLETED_REQUESTS = 64;
const MAX_COMPLETED_BYTES = 8 * 1024 * 1024;
const COMPLETED_TTL_MS = 5 * 60 * 1000;
const MAX_EVENTS = 256;
const MAX_EVENT_BYTES = 128_000;
const MAX_EVENT_RESPONSE_COUNT = 32;
const MAX_EVENT_RESPONSE_BYTES = 512_000;
const REPLAY_REQUEST_MIN_MS = 1_000;

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

function bearer(request) {
  const value = request.headers.get("authorization") || "";
  return value.startsWith("Bearer ") ? value.slice(7) : "";
}

function parseAfter(value) {
  const raw = String(value ?? "0");
  if (!/^\d{1,20}$/.test(raw)) return 0;
  const parsed = Number(raw);
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : 0;
}

function parseLimit(value) {
  const parsed = Number(value ?? MAX_EVENT_RESPONSE_COUNT);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) return MAX_EVENT_RESPONSE_COUNT;
  return Math.min(parsed, MAX_EVENT_RESPONSE_COUNT);
}

async function requestFingerprint(command, cwd) {
  const bytes = new TextEncoder().encode(JSON.stringify({ command, cwd }));
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)]
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

async function readBoundedJson(request) {
  const declaredRaw = request.headers.get("content-length");
  if (declaredRaw != null) {
    const declared = Number(declaredRaw);
    if (!Number.isFinite(declared) || declared < 0 || declared > MAX_BODY_BYTES) {
      throw new RangeError("request body too large");
    }
  }

  if (!request.body) return {};

  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_BODY_BYTES) {
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

  const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  if (!text) return {};
  return JSON.parse(text);
}

function validId(value, max = 256) {
  return typeof value === "string" &&
    value.length >= 1 &&
    value.length <= max &&
    /^[A-Za-z0-9._:-]+$/.test(value);
}

function validCliCommand(value) {
  return typeof value === "string" &&
    value.length >= 1 &&
    value.length <= 128 * 1024 &&
    (value === "rift-cli" || value.startsWith("rift-cli "));
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const room = env.RIFT_CLI_RELAY.getByName("primary");

    if (url.pathname === "/health") {
      if (!env.DRIVER_TOKEN || bearer(request) !== env.DRIVER_TOKEN) {
        return json({ error: "Unauthorized driver" }, 401);
      }
      return room.fetch(new Request("https://cli-relay.internal/health"));
    }

    if (url.pathname === "/device") {
      if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
        return json({ error: "WebSocket upgrade required" }, 426);
      }

      if (!env.DEVICE_TOKEN || bearer(request) !== env.DEVICE_TOKEN) {
        return json({ error: "Unauthorized device" }, 401);
      }

      if (request.headers.get("x-rift-protocol") !== PROTOCOL) {
        return json({ error: "Unsupported CLI relay protocol" }, 400);
      }

      const deviceId = request.headers.get("x-rift-device-id")?.trim() || "";
      if (!validId(deviceId, 160)) {
        return json({ error: "Invalid device ID" }, 400);
      }

      return room.fetch(new Request("https://cli-relay.internal/device", request));
    }

    if (url.pathname === "/request") {
      if (!env.DRIVER_TOKEN || bearer(request) !== env.DRIVER_TOKEN) {
        return json({ error: "Unauthorized driver" }, 401);
      }
      if (request.method !== "POST") {
        return new Response(null, { status: 405, headers: { allow: "POST" } });
      }

      let body;
      try {
        body = await readBoundedJson(request);
      } catch (error) {
        if (error instanceof RangeError) {
          return json({ error: error.message }, 413);
        }
        return json({ error: "Invalid request JSON" }, 400);
      }

      const requestId = String(body.requestId || crypto.randomUUID()).trim();
      const command = String(body.command || "").trim();
      const cwd = String(body.cwd || "/").trim() || "/";

      if (!validId(requestId, 256)) {
        return json({ error: "Invalid requestId" }, 400);
      }
      if (!validCliCommand(command)) {
        return json({ error: "Only commands rooted at rift-cli are allowed" }, 400);
      }
      if (cwd.length > 4096 || /[\r\n\0]/.test(cwd)) {
        return json({ error: "Invalid cwd" }, 400);
      }

      return room.fetch(
        new Request("https://cli-relay.internal/request", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ requestId, command, cwd }),
        }),
      );
    }

    if (url.pathname === "/events") {
      if (!env.DRIVER_TOKEN || bearer(request) !== env.DRIVER_TOKEN) {
        return json({ error: "Unauthorized driver" }, 401);
      }
      if (request.method !== "GET") {
        return new Response(null, { status: 405, headers: { allow: "GET" } });
      }
      const after = parseAfter(url.searchParams.get("after"));
      const limit = parseLimit(url.searchParams.get("limit"));
      return room.fetch(
        new Request("https://cli-relay.internal/events?after=" + after + "&limit=" + limit),
      );
    }

    return json({ error: "Not found" }, 404);
  },
};

export class RiftCliRelayRoom {
  constructor(ctx) {
    this.ctx = ctx;
    this.device =
      ctx.getWebSockets("device")[0] ||
      ctx.getWebSockets().find(
        (socket) => socket.deserializeAttachment?.()?.role === "device",
      ) ||
      null;

    this.pending = new Map();
    this.completed = new Map();
    this.completedBytes = 0;
    this.events = [];
    this.lastReplayRequestAt = 0;
    this.lastReplayAfter = 0;

    const attachment = this.device?.deserializeAttachment?.() || {};
    const attachedSequence = Number(attachment.lastSequence ?? 0);
    this.lastSequence =
      Number.isSafeInteger(attachedSequence) && attachedSequence >= 0
        ? attachedSequence
        : 0;
    this.deviceId =
      typeof attachment.deviceId === "string"
        ? attachment.deviceId.slice(0, 160)
        : "";
    const attachedConnectedAt = Number(attachment.connectedAt ?? 0);
    this.connectedAt =
      Number.isSafeInteger(attachedConnectedAt) && attachedConnectedAt >= 0
        ? attachedConnectedAt
        : 0;
  }

  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return json({
        ok: true,
        protocol: PROTOCOL,
        deviceConnected: Boolean(this.device),
        deviceId: this.deviceId || null,
        connectedAt: this.connectedAt || null,
        pendingRequests: this.pending.size,
        eventSequence: this.lastSequence,
        eventsRetained: this.events.length,
        maxEvents: MAX_EVENTS,
      });
    }

    if (url.pathname === "/device") {
      return this.acceptDevice();
    }

    if (url.pathname === "/request") {
      return this.forwardRequest(request);
    }

    if (url.pathname === "/events") {
      const after = parseAfter(url.searchParams.get("after"));
      const limit = parseLimit(url.searchParams.get("limit"));
      return this.eventPage(after, limit);
    }

    return json({ error: "Not found" }, 404);
  }

  eventPage(after, limit) {
    const eligible = this.events.filter((event) => Number(event.sequence) > after);
    const out = [];
    let bytes = 0;

    for (const event of eligible) {
      if (out.length >= limit) break;
      const encodedBytes = new TextEncoder().encode(JSON.stringify(event)).byteLength;
      if (bytes + encodedBytes > MAX_EVENT_RESPONSE_BYTES) break;
      out.push(event);
      bytes += encodedBytes;
    }

    const earliest = this.events.length > 0 ? Number(this.events[0].sequence) : 0;
    const replayNeeded =
      Boolean(this.device) &&
      this.lastSequence > after &&
      (
        this.events.length === 0 ||
        (after > 0 && Number.isSafeInteger(earliest) && earliest > after)
      );
    const replayRequested = replayNeeded ? this.requestReplay(after) : false;
    const nextAfter = out.length > 0 ? Number(out[out.length - 1].sequence) : after;

    return json({
      ok: true,
      protocol: PROTOCOL,
      after,
      nextAfter,
      eventSequence: this.lastSequence,
      eventsRetained: this.events.length,
      replayRequested,
      truncated: eligible.length > out.length,
      events: out,
    });
  }

  requestReplay(after) {
    if (!this.device) return false;
    const now = Date.now();
    if (
      this.lastReplayAfter === after &&
      now - this.lastReplayRequestAt < REPLAY_REQUEST_MIN_MS
    ) {
      return false;
    }

    try {
      this.device.send(JSON.stringify({
        type: "cli.replay.request",
        protocol: PROTOCOL,
        after,
      }));
      this.lastReplayAfter = after;
      this.lastReplayRequestAt = now;
      return true;
    } catch {
      return false;
    }
  }

  pruneCompleted(now = Date.now()) {
    for (const [requestId, entry] of this.completed.entries()) {
      if (now - entry.at >= COMPLETED_TTL_MS) {
        this.completed.delete(requestId);
        this.completedBytes = Math.max(0, this.completedBytes - entry.bytes);
      }
    }

    while (
      this.completed.size > MAX_COMPLETED_REQUESTS ||
      this.completedBytes > MAX_COMPLETED_BYTES
    ) {
      const oldest = this.completed.entries().next().value;
      if (!oldest) break;
      const [requestId, entry] = oldest;
      this.completed.delete(requestId);
      this.completedBytes = Math.max(0, this.completedBytes - entry.bytes);
    }
  }

  rememberCompleted(requestId, fingerprint, status, body) {
    const serialized = JSON.stringify(body);
    const bytes = new TextEncoder().encode(serialized).byteLength;
    const previous = this.completed.get(requestId);
    if (previous) {
      this.completedBytes = Math.max(0, this.completedBytes - previous.bytes);
      this.completed.delete(requestId);
    }

    this.completed.set(requestId, {
      fingerprint,
      status,
      body,
      bytes,
      at: Date.now(),
    });
    this.completedBytes += bytes;
    this.pruneCompleted();
  }

  acceptDevice() {
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    if (this.device) {
      this.failPending("RiftCLI device connection was replaced");
      try {
        this.device.close(1012, "Replaced by a newer RiftCLI connection");
      } catch {
        // Existing socket already closed.
      }
    }

    this.ctx.acceptWebSocket(server, ["device"]);
    this.connectedAt = Date.now();
    server.serializeAttachment({
      role: "device",
      lastSequence: this.lastSequence,
      deviceId: this.deviceId,
      connectedAt: this.connectedAt,
    });
    this.device = server;

    return new Response(null, {
      status: 101,
      webSocket: client,
    });
  }

  async forwardRequest(request) {
    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: "Invalid internal request JSON" }, 400);
    }

    const requestId = String(body.requestId || "").trim();
    const command = String(body.command || "").trim();
    const cwd = String(body.cwd || "/").trim() || "/";

    if (!validId(requestId, 256) || !validCliCommand(command)) {
      return json({ error: "Invalid CLI request" }, 400);
    }

    const fingerprint = await requestFingerprint(command, cwd);
    this.pruneCompleted();

    const completed = this.completed.get(requestId);
    if (completed) {
      if (completed.fingerprint !== fingerprint) {
        return json({ error: "requestId was already used for a different CLI request" }, 409);
      }
      return json(completed.body, completed.status);
    }

    const pendingExisting = this.pending.get(requestId);
    if (pendingExisting) {
      if (pendingExisting.fingerprint !== fingerprint) {
        return json({ error: "requestId is pending for a different CLI request" }, 409);
      }
      if (pendingExisting.waiters.length >= MAX_WAITERS_PER_REQUEST) {
        return json({ error: "Too many retries are waiting on this RiftCLI request", requestId }, 429);
      }
      const result = await new Promise((resolve) => {
        pendingExisting.waiters.push(resolve);
      });
      return json(result.body, result.status);
    }

    if (!this.device) {
      return json({ error: "RiftCLI device is not connected" }, 503);
    }

    if (this.pending.size >= MAX_PENDING_REQUESTS) {
      return json({ error: "Too many pending RiftCLI requests" }, 429);
    }

    const envelope = JSON.stringify({
      type: "cli.request",
      protocol: PROTOCOL,
      requestId,
      command,
      cwd,
    });

    if (new TextEncoder().encode(envelope).byteLength > MAX_MESSAGE_BYTES) {
      return json({ error: "CLI request exceeds relay message limit" }, 413);
    }

    let resolvePending;
    const terminal = new Promise((resolve) => {
      resolvePending = resolve;
    });

    const timeout = setTimeout(() => {
      const pending = this.pending.get(requestId);
      if (!pending) return;
      this.pending.delete(requestId);
      const body = {
        error: "RiftCLI device response timed out; requestId is tombstoned to prevent uncertain replay",
        requestId,
      };
      this.rememberCompleted(requestId, pending.fingerprint, 504, body);
      const result = { status: 504, body };
      for (const resolve of pending.waiters) resolve(result);
    }, REQUEST_TIMEOUT_MS);

    this.pending.set(requestId, {
      waiters: [resolvePending],
      timeout,
      fingerprint,
    });

    try {
      this.device.send(envelope);
    } catch {
      const pending = this.pending.get(requestId);
      if (pending) {
        clearTimeout(pending.timeout);
        this.pending.delete(requestId);
        const body = {
          error: "RiftCLI device disconnected before request delivery was proven; requestId is tombstoned",
          requestId,
        };
        this.rememberCompleted(requestId, fingerprint, 503, body);
        const result = { status: 503, body };
        for (const resolve of pending.waiters) resolve(result);
      }
    }

    const result = await terminal;
    return json(result.body, result.status);
  }

  webSocketMessage(socket, message) {
    if (socket !== this.device) return;

    const text =
      typeof message === "string"
        ? message
        : new TextDecoder().decode(message);

    if (new TextEncoder().encode(text).byteLength > MAX_MESSAGE_BYTES) {
      socket.close(1009, "Message too large");
      return;
    }

    let envelope;
    try {
      envelope = JSON.parse(text);
    } catch {
      this.sendDeviceError("Invalid JSON");
      return;
    }

    if (!envelope || typeof envelope !== "object" || Array.isArray(envelope)) {
      this.sendDeviceError("Invalid CLI relay envelope");
      return;
    }

    if (envelope.type === "device.hello") {
      if (envelope.protocol !== PROTOCOL) {
        this.sendDeviceError("Unsupported CLI relay protocol");
        return;
      }
      this.deviceId = String(envelope.deviceId || "").slice(0, 160);
      const deviceAckSequence = Number(envelope.ackSequence ?? 0);
      if (Number.isSafeInteger(deviceAckSequence) && deviceAckSequence >= 0) {
        this.lastSequence = Math.max(this.lastSequence, deviceAckSequence);
      }
      this.connectedAt = Date.now();
      socket.serializeAttachment({
        role: "device",
        lastSequence: this.lastSequence,
        deviceId: this.deviceId,
        connectedAt: this.connectedAt,
      });
      socket.send(JSON.stringify({
        type: "relay.ready",
        protocol: PROTOCOL,
        resumeAfter: this.lastSequence,
      }));
      return;
    }

    if (envelope.type === "device.pong") {
      return;
    }

    if (envelope.type === "cli.response") {
      this.finishPending(envelope.requestId, 200, {
        ok: true,
        requestId: envelope.requestId,
        payload: envelope.payload ?? null,
      });
      return;
    }

    if (envelope.type === "cli.error") {
      this.finishPending(envelope.requestId, 400, {
        ok: false,
        requestId: envelope.requestId ?? null,
        error: String(envelope.message || "RiftCLI device error").slice(0, 512),
      });
      return;
    }

    if (envelope.type === "cli.event") {
      this.acceptEvent(socket, envelope.event);
      return;
    }

    this.sendDeviceError("Unknown CLI device message");
  }

  webSocketClose(socket) {
    if (socket !== this.device) return;
    this.device = null;
    this.connectedAt = 0;
    this.failPending("RiftCLI device disconnected");
  }

  webSocketError(socket) {
    if (socket !== this.device) return;
    this.device = null;
    this.connectedAt = 0;
    this.failPending("RiftCLI device socket failed");
  }

  finishPending(requestIdValue, status, body) {
    const requestId = String(requestIdValue || "").trim();
    const pending = this.pending.get(requestId);
    if (!pending) return;

    clearTimeout(pending.timeout);
    this.pending.delete(requestId);
    this.rememberCompleted(requestId, pending.fingerprint, status, body);
    const result = { status, body };
    for (const resolve of pending.waiters) resolve(result);
  }

  failPending(message) {
    for (const [requestId, pending] of this.pending.entries()) {
      clearTimeout(pending.timeout);
      const body = {
        error: message + "; requestId is tombstoned because execution state is uncertain",
        requestId,
      };
      this.rememberCompleted(requestId, pending.fingerprint, 503, body);
      const result = { status: 503, body };
      for (const resolve of pending.waiters) resolve(result);
    }
    this.pending.clear();
  }

  acceptEvent(socket, event) {
    if (!event || typeof event !== "object" || Array.isArray(event)) return;
    if (event.schema !== "rift.cli-event/1") return;

    const encoded = JSON.stringify(event);
    if (new TextEncoder().encode(encoded).byteLength > MAX_EVENT_BYTES) return;

    const sequence = Number(event.sequence);
    if (!Number.isSafeInteger(sequence) || sequence <= 0) return;

    if (!this.events.some((existing) => Number(existing.sequence) === sequence)) {
      this.events.push(event);
      this.events.sort((a, b) => Number(a.sequence) - Number(b.sequence));
      while (this.events.length > MAX_EVENTS) this.events.shift();
    }

    if (sequence > this.lastSequence) {
      this.lastSequence = sequence;
    }

    socket.serializeAttachment({
      role: "device",
      lastSequence: this.lastSequence,
      deviceId: this.deviceId,
      connectedAt: this.connectedAt,
    });

    socket.send(JSON.stringify({
      type: "cli.ack",
      protocol: PROTOCOL,
      sequence,
    }));
  }

  sendDeviceError(message) {
    if (!this.device) return;
    this.device.send(JSON.stringify({
      type: "relay.error",
      protocol: PROTOCOL,
      message: String(message).slice(0, 240),
    }));
  }
}
