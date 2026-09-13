# RiftMcpServer

## Purpose

`RiftMcpServer` is the in-process MCP JSON-RPC server. It accepts MCP requests from trusted RiftOS transports and delegates tool behavior to `RiftToolHost`.

## Source ownership

`android/app/src/main/java/com/riftos/app/RiftMcpServer.kt`.

Supported methods:
- `initialize`
- `ping`
- `tools/list`
- `tools/call`

There is no listening socket in this class.

## Request flow

`handleAsync()` treats `tools/call` specially for idempotency. A canonical JSON representation is hashed into a request key. Identical in-flight tool calls share waiters; recently completed identical calls can return a cached response for a short TTL. This protects local mutations from duplicate execution if an HTTP/relay response is lost and retried.

Other methods dispatch immediately.

`tools/list` returns `toolHost.tools()` plus RiftOS manifest count/hash metadata. `initialize` returns protocol/server identity, capabilities and the same manifest metadata. The tool catalog is static for a running process, so `tools.listChanged` is false.

## Tool call framing

`handleToolCall()` extracts `name`, JSON arguments and the private correlation metadata `riftos/callId`. It calls the tool host and wraps results into MCP `content`, `structuredContent`, `_meta`, and `isError` fields. Project-export responses are summarized in structured content so large raw source pages do not duplicate themselves unnecessarily.

## Critical invariants

- The server never owns filesystem permission policy; the host does.
- Duplicate retry coalescing must never merge non-identical canonical requests.
- Client-provided tool names/arguments are not trusted until host validation.
- Private call-correlation metadata is not part of model-visible tool schemas.
- `tools/list` must be generated from the same host registry used for execution.

## Failure signatures

- `tools/list` count differs from host manifest in the same process -> server regression.
- Duplicate mutation occurs after network retry -> idempotency key/cache path.
- Tool executed but MCP client sees malformed result -> result framing here.
- Method-not-found for valid MCP method -> dispatch table.

## Fix map

Patch this class for MCP protocol framing, request correlation, idempotency or server metadata. Do not implement a tool here; register/map it in `RiftToolHost` and implement its authority in the proper backend.

## Validation

`validate-rift-transport.mjs` checks idempotency and manifest metadata. For mutation changes, simulate duplicate identical `tools/call` requests and verify one underlying mutation with multiple matching replies.
