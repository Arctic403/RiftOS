# RiftMcpServer

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-20.**

## Purpose

`RiftMcpServer` is RiftOS's in-process MCP JSON-RPC framing layer. It has no listening socket and owns no filesystem/tool permission policy.

## Source ownership

- `RiftMcpServer.kt`
- `RiftDebugHub.kt` — passive trace/span owner shared with Tool Host
- `RiftBoundedAsync.kt` — shared exactly-once deadline/cancellation primitive
- callers: Browser MCP bridge and outbound Relay client
- execution authority: `RiftToolHost`

## Supported protocol

Requests:
- `initialize`
- `ping`
- `tools/list`
- `tools/call`

Notification:
- `notifications/initialized` — accepted with no response.

Unknown methods return JSON-RPC -32601.

## Initialization

Protocol version: `2025-06-18`.

Initialize reports:
- server name/version;
- static tools capability (`listChanged=false`);
- tool count/manifest SHA;
- source/build identifiers;
- workspace/Code Mode operating instructions.

## Tool listing

`tools/list` returns the exact `RiftToolHost.tools()` definitions plus count/hash metadata from that same host manifest.

## Tool calls

Server extracts:
- tool name;
- object arguments;
- optional private `_meta["riftos/callId"]`.

ToolHost executes the call.

Server frames:
- MCP text content;
- structuredContent with ok/value or error;
- `isError`;
- echoed private call-id metadata when supplied.

Project-export pages are summarized in structured content to avoid duplicating large source payloads.

A private bounded Vortex image attachment from `rift_shell_exec` is emitted once as MCP image content while structured shell output is replaced with compact image metadata.

## Relay retry idempotency

Direct/browser calls use `handleAsync(request, reply)` and always execute fresh.

Only Relay supplies a stable retry key.

For Relay `tools/call`:
- retry key is combined with SHA-256 of canonical request JSON with the transport JSON-RPC `id` removed;
- identical in-flight retries join one execution and each waiter receives its own JSON-RPC id on return;
- each logical request accepts at most 8 retry waiters;
- at most 64 relay-scoped requests may be in flight;
- completed response can replay for 2 minutes;
- completed cache is capped at 128 entries **and 8 MiB total serialized response bytes**;
- distinct request JSON or retry id executes separately.

Canonical JSON sorts object keys recursively; array order remains significant.

## Bounded request lifecycle

Every request carrying an id has a server-side terminal deadline of 65 seconds. Relay-scoped requests also remove their in-flight entry when that deadline wins, so later retries cannot join an immortal request.

The server deadline is intentionally outside the local execution deadlines and inside the transport deadlines:

`RiftToolSandbox 45s -> RiftNativeShell 60s -> RiftMcpServer 65s -> RiftMcpRelayClient 70s -> relay Worker 75s`.

The ordering is an invariant: an outer transport must not report failure while an inner mutation is still expected to keep running.

`RiftBoundedAsync` guarantees exactly-once terminal callbacks for local worker owners, cancels the submitted Future on timeout, and carries a cooperative monotonic `RiftDeadline` through nested filesystem/runtime work.

## Error behavior

Missing tool name -> -32602.

Unknown method -> -32601.

Unexpected relay-deduped execution exception -> -32603.

Tool-level failures remain successful JSON-RPC envelopes whose MCP result has `isError=true` and structured error content.

## Non-ownership boundaries

Server does not own:
- read/write grants;
- tool schemas/implementation;
- workspace containment;
- socket transport;
- browser origin policy.

## Source change in this audit

Added standard `notifications/initialized` handling as a no-response notification.

## Critical invariants

- direct calls never enter completed-response retry cache;
- only relay tools/call uses retry dedupe;
- retry cache max 128 entries / 8 MiB / TTL 2 minutes;
- request hash is canonical;
- tools/list and execution use same ToolHost;
- private call correlation is echoed, not published as a tool schema;
- initialized notification gets no response;
- request deadlines remain ordered 45s < 60s < 65s < 70s < 75s;
- one logical request has at most 8 retry waiters and the server has at most 64 in-flight relay requests.

## Failure signatures

- identical direct read returns stale result -> retry-scope regression;
- relay retry duplicates mutation -> idempotency regression;
- different requests collapse together -> canonical key regression;
- tools/list differs from execution registry -> host/server drift;
- client gets method-not-found for initialized notification -> lifecycle regression;
- image Base64 duplicated in structured text and image content -> payload regression.

## Debug correlation

Every valid `tools/call` opens an `mcp.server` parent span. The same context is forwarded to Tool Host, and the MCP result metadata includes `riftos/traceId` for a later `rift_debug` query.

The debugger remains outside request execution: it cannot dispatch, authorize, mutate or cancel the call.

## Fix map

Protocol framing/idempotency/metadata -> `RiftMcpServer.kt`.

Trace storage/bounds/redaction/adapter contract -> `RiftDebugHub.kt`.

Tool behavior/grants -> Tool Host.

Transport request ids -> Relay.

## Validation

Second source audit must verify dispatch table, notification behavior, TTL/cache bound, relay-only retry path, canonical hashing, call-id echo, export/image sanitization and JSON-RPC error codes.
