# Public Rift MCP Relay Service

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

## Purpose

The public Rift MCP relay is a Cloudflare Worker plus Durable Object transport rendezvous between one authenticated RiftOS Android WebSocket and HTTP MCP requests. It does not implement Rift tools or receive direct filesystem, shell, Android or workspace authority. MCP execution remains on-device in RiftMcpServer and RiftToolHost.

## Source ownership

- relay/src/index.js — Worker routing and RiftRelayRoom.
- relay/wrangler.jsonc — Worker/Durable Object binding, migration and observability.
- relay/package.json — relay package metadata/check/deploy commands.
- relay/README.md — deployment quick reference.
- RiftMcpRelayClient.kt — Android WebSocket client.
- RiftRelaySettings.kt and RiftMcpActivity.kt — Android relay configuration.

## Deployment model

Worker name: rift-mcp-relay.

Main module: src/index.js.

Durable Object binding: RIFT_RELAY -> RiftRelayRoom.

Migration v1 creates RiftRelayRoom as a SQLite-class Durable Object. Current relay code does not use Durable Object storage for MCP request or response payloads.

## Required secrets

Two independent Worker secrets are required:
- DEVICE_TOKEN authenticates the Android device WebSocket.
- MCP_PATH_TOKEN is the secret segment in the private HTTP MCP URL.

Their values are not present in repository configuration and must not be reused.

## Development-only path authentication

The MCP URL is /mcp/<MCP_PATH_TOKEN>. This secret-path scheme is for personal Developer-mode use, not a public multi-user authentication design. OAuth 2.1 remains required before publishing/sharing the server as a general service.

Because the secret is embedded in the pathname, normal invocation logging can expose it. This audit changed relay/wrangler.jsonc to invocation_logs=false. The relay source itself contains no console logging of request URLs or secret values.

## Public endpoints

/health returns only ok=true and deviceConnected.

/device requires:
- WebSocket upgrade;
- DEVICE_TOKEN as Authorization Bearer;
- exact X-Rift-Protocol = rift-mcp-relay-v1;
- X-Rift-Device-Id matching [A-Za-z0-9._:-]{1,160}.

/mcp/<secret> supports OPTIONS, POST and DELETE. GET and unsupported methods return 405. DELETE is a stateless lifecycle acknowledgement; the relay owns no HTTP session object.

## Request body bound

MAX_BODY_BYTES = 1,000,000 UTF-8 bytes.

Previous source used request.text() before a reliable bound when Content-Length was absent. This audit added readBoundedText(), which validates a declared length when present, streams body chunks, stops as soon as cumulative bytes exceed the cap, and decodes UTF-8 with fatal decoding only after the bounded byte collection.

Oversized input returns HTTP 413. Invalid UTF-8 returns HTTP 400. Unknown/chunked length cannot force an unbounded request.text() allocation.

## JSON-RPC ingress

POST must decode to one JSON object with jsonrpc="2.0" and a string method. Arrays/batches and malformed JSON are rejected.

The relay has no tool allowlist. Valid tools/list and tools/call payloads are forwarded to the device after transport-level JSON-RPC validation.

## MCP notifications

Id-less methods beginning with notifications/ are now forwarded as the fire-and-forget WebSocket envelope mcp.notification instead of being silently dropped.

RiftRelayRoom allocates no pending correlation state for a notification. RiftMcpRelayClient accepts only an object payload with no id and a notifications/ method, then passes it to RiftMcpServer without a retry key or response transport. This preserves JSON-RPC notification no-response semantics.

## Durable Object room

RiftRelayRoom owns only:
- the current device socket;
- an in-memory pending HTTP-to-device request map.

The constructor can recover an accepted socket through ctx.getWebSockets(). Current source contains no ctx.storage access and no payload database writes.

## Device replacement

A newer device connection replaces the old socket.

Before closing the old socket, this audit now calls failPending("RiftOS device connection was replaced"), so current HTTP callers fail immediately instead of waiting for the normal timeout. The old socket is then closed with code 1012.

## Stale-socket protection

webSocketMessage, webSocketClose and webSocketError each first require socket === this.socket. Late events from a replaced socket therefore cannot satisfy a current request, clear the new socket or fail its pending work.

## Pending request bound

MAX_PENDING_REQUESTS = 64.

When the active room already has 64 distinct correlated requests, a new request receives relay-busy JSON-RPC error with HTTP 503. A duplicate logical request may join the existing pending entry instead of consuming another slot, with at most 8 HTTP waiters per logical request.

## Request correlation

If `_meta["riftos/callId"]` is present, the Worker derives the relay requestId from that stable model-call id **plus a SHA-256 fingerprint of canonical request JSON with the transport JSON-RPC `id` removed**; otherwise it uses `crypto.randomUUID()`. A repeated logical call therefore joins only when both the model call id and logical request payload match, while accidental call-id reuse with different arguments remains distinct. The pending entry stores one timer plus a bounded waiter list, and each waiter keeps its own JSON-RPC id.

The Android device receives one `mcp.request` envelope containing requestId plus the original payload. `RiftMcpRelayClient` calls `RiftMcpServer.handleAsync(payload, requestId)`. The phone removes the transport JSON-RPC `id` from the request hash, executes the logical request once, then rewrites the response id separately for each waiter.

## Response validation

Only the current socket can reply.

mcp.response must contain:
- an object payload;
- jsonrpc="2.0";
- payload id equal to the original pending JSON-RPC id;
- either result or error.

Malformed or mismatched responses resolve as "Malformed RiftOS MCP response" rather than being returned to the HTTP caller as trusted JSON-RPC.

Valid result contents are not tool-filtered by the relay.

## Worker and Android message bound

Worker WebSocket text is limited to 1,000,000 UTF-8 bytes. Previous code used JavaScript character length, which was not a byte-accurate Unicode resource bound.

RiftMcpRelayClient now uses the same MAX_MESSAGE_BYTES = 1,000,000 limit for incoming text.

Outgoing Android mcp.response envelopes are serialized and byte-checked before send. An oversized local result is replaced by a bounded mcp.error saying "Local MCP response exceeds relay message limit". Protocol error strings are truncated to 240 characters.

## Timeout and cleanup

REQUEST_TIMEOUT_MS = 75,000 ms.

Timeout deletes the logical pending request and resolves every joined waiter with its own 504 relay-timeout response. The Worker timeout intentionally sits outside the 70-second Android forwarding watchdog, 65-second MCP server watchdog, 60-second shell deadline and 45-second sandbox deadline so transport failure cannot normally race ahead of an inner mutation.

Active-device disconnect/error clears the current socket, clears every timer, resolves every pending request with 503 and empties the map.

Device replacement performs the same immediate pending failure before switching ownership.

## Notification delivery

Notification delivery is best-effort and uncorrelated:
- offline device -> 503;
- WebSocket send failure -> 503;
- successful queueing -> 202.

No response is expected.

## Android reconnect ownership

The Worker does not implement Android reconnect policy. RiftMcpRelayClient owns desired-running state, WSS connection, bounded exponential reconnect, ping/hello handling and stale client socket identity checks.

## Relay settings boundary

RiftRelaySettings validates a wss:// endpoint, rejects userinfo/fragments, and bounds/control-validates the pairing token before Authorization-header use. The token is stored through RiftSecretStore.

## Privacy and persistence

Current relay source:
- has no ctx.storage use;
- performs no database write;
- has no console logging;
- stores correlation only in live object memory;
- clears pending entries on reply, send failure, timeout, replacement or disconnect.

Durable Object configuration does not imply payload persistence.

## Source fixes in this audit

- HTTP request body changed from post-allocation request.text() checking to bounded streaming.
- Invalid UTF-8 fails closed.
- WebSocket size checks changed from character length to UTF-8 byte length.
- Distinct pending requests capped at 64, with at most 8 waiters per logical request.
- Device replacement immediately fails pending work.
- Device ID syntax narrowed.
- Device MCP responses validate JSON-RPC id and shape.
- Android incoming/outgoing relay envelopes aligned to 1,000,000 UTF-8 bytes.
- Oversized local MCP responses fail before send.
- MCP notifications are forwarded fire-and-forget instead of dropped.
- Worker invocation logs disabled because path authentication contains a secret.
- The already-verified MCP relay-client README and transport validator were re-synchronized.

## Current limitations

- one named Durable Object room: primary;
- one active device;
- secret-path auth is development-only;
- no OAuth/user accounts/multi-tenant isolation;
- /health publicly reveals only device connectivity;
- no persistent offline queue;
- no request resumption across room/process restart;
- no HTTP MCP session state beyond stateless DELETE acknowledgement.

## Critical invariants

- relay stays transport-only;
- DEVICE_TOKEN and MCP_PATH_TOKEN remain independent;
- secret values stay out of source/logs;
- invocation logs stay disabled while path-token auth is used;
- request/message/pending/timeout resources stay bounded;
- only the current socket can satisfy/fail correlated requests;
- replacement cannot strand pending work;
- response JSON-RPC id must match the pending request;
- notifications preserve no-response semantics;
- no MCP payload persistence;
- no relay tool allowlist;
- on-device ToolHost remains authority.

## Failure signatures

- request.text() is used before bounding an unknown-length body -> allocation regression;
- raw string length is used as a byte limit -> Unicode/resource regression;
- pending map loses its cap -> concurrency regression;
- stale socket event clears/fails the current connection -> ownership regression;
- device replacement leaves pending work until timeout -> cleanup regression;
- mismatched JSON-RPC id is accepted -> correlation regression;
- notification returns 202 without device forwarding -> lifecycle transport regression;
- ctx.storage starts persisting MCP payloads -> persistence/privacy expansion;
- invocation_logs becomes enabled while URL-path secret auth remains -> credential exposure risk;
- relay gains a tool allowlist/filter -> transport/authority drift.

## Fix map

Worker/DO routing, auth, bounds and correlation -> relay/src/index.js.

Worker binding/logging -> relay/wrangler.jsonc.

Android WSS client/reconnect/envelope bounds -> RiftMcpRelayClient.kt.

Android endpoint/token configuration -> RiftRelaySettings.kt and RiftSecretStore.kt.

On-device JSON-RPC -> RiftMcpServer.kt.

Tool authority -> RiftToolHost.kt.

## Validation

Second source audit must verify:
- source/binding/migration wiring;
- no repository secret values;
- invocation_logs=false;
- /device auth/protocol/device-id checks;
- /mcp secret-path gate;
- bounded streaming request read;
- JSON-RPC ingress validation;
- notification forwarding;
- 64 distinct pending limit plus 8 retry waiters per logical request;
- 75-second outer timeout cleanup ordered after Android/local deadlines;
- replacement/disconnect cleanup;
- three stale-socket guards;
- response id/shape correlation;
- matching 1,000,000-byte Worker and Android bounds;
- no ctx.storage/payload persistence;
- no relay tool filter;
- Android response pre-send bound.

relay/package.json syntax/deployment and live Cloudflare abuse remain later runtime validation.
