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

`handleAsync()` has two modes. Direct/local MCP calls dispatch immediately and always execute fresh, even when tool name and arguments are identical to a recent call. The remote relay uses the overload that supplies its transport `requestId`; only that path enables retry coalescing/completed-response replay. The retry key combines the relay request ID with a canonical hash of the JSON-RPC request, so a lost relay response can be retried without executing the same mutation twice while a genuinely new repeated command is never mistaken for a retry.

Other methods dispatch immediately.

`tools/list` returns `toolHost.tools()` plus RiftOS manifest count/hash metadata. `initialize` returns protocol/server identity, capabilities, the same manifest metadata and the generated source/build fingerprint (`sourceSha`, build run id/number). The tool catalog is static for a running process, so `tools.listChanged` is false.

## Tool call framing

`handleToolCall()` extracts `name`, JSON arguments and the private correlation metadata `riftos/callId`. It calls the tool host and wraps results into MCP `content`, `structuredContent`, `_meta`, and `isError` fields. Project-export responses are summarized in structured content so large raw source pages do not duplicate themselves unnecessarily. A successful `rift_shell_exec` result may carry a private `_riftImage` produced by the Vortex local bridge; the server extracts its bounded Base64 once into MCP `content[type=image]` and replaces the structured/text copy with compact attachment metadata so the relay payload is not duplicated. This does not add or alter any MCP tool schema.

## Critical invariants

- The server never owns filesystem permission policy; the host does.
- Duplicate retry coalescing is relay-scoped and must never merge non-identical canonical requests or distinct fresh invocations.
- Client-provided tool names/arguments are not trusted until host validation.
- Private call-correlation metadata is not part of model-visible tool schemas.
- `tools/list` must be generated from the same host registry used for execution.

## Failure signatures

- `tools/list` count differs from host manifest in the same process -> server regression.
- Duplicate mutation occurs after relay retry -> relay request-id/cache path.
- Repeating the same successful tool call returns old live state -> local/direct call accidentally entered the completed retry cache.
- Tool executed but MCP client sees malformed result -> result framing here.
- Method-not-found for valid MCP method -> dispatch table.

## Fix map

Patch this class for MCP protocol framing, request correlation, idempotency or server metadata. Do not implement a tool here; register/map it in `RiftToolHost` and implement its authority in the proper backend.

## Validation

`validate-rift-transport.mjs` checks relay-scoped retry idempotency and manifest metadata. For runtime validation, repeat an identical direct/local live command and verify it executes fresh, then retry one relay envelope with the same relay request ID and verify one underlying mutation with multiple matching replies.
