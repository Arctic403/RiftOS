# Rift MCP Relay Protocol v1

The relay connects ChatGPT's public Streamable HTTP MCP endpoint to a RiftOS device without exposing a listener on Android. The device always initiates an encrypted WebSocket connection.

## Device handshake

The device connects to its configured `wss://` URL with:

- `Authorization: Bearer <pairing token>`
- `X-Rift-Device-Id: <stable device UUID>`
- `X-Rift-Protocol: rift-mcp-relay-v1`

After the WebSocket opens, RiftOS sends:

```json
{"type":"device.hello","protocol":"rift-mcp-relay-v1","deviceId":"...","client":{"name":"RiftOS","version":"0.11.0"}}
```

The relay confirms authentication and device registration with:

```json
{"type":"relay.ready","cliResumeAfter":123}
```

## MCP exchange

The relay sends one MCP JSON-RPC object per request:

```json
{"type":"mcp.request","requestId":"unique-relay-id","payload":{"jsonrpc":"2.0","id":1,"method":"tools/list"}}
```

RiftOS answers with the correlated result:

```json
{"type":"mcp.response","requestId":"unique-relay-id","payload":{"jsonrpc":"2.0","id":1,"result":{}}}
```

Protocol-level failures use `mcp.error` with the same `requestId`. Relay keepalives use `relay.ping` and `device.pong`.

## RiftCLI persistent event channel

The same device WebSocket also carries bounded RiftCLI lifecycle events:

```json
{"type":"cli.event","protocol":"rift-mcp-relay-v1","event":{"schema":"rift.cli-event/1","sequence":123,"type":"job.completed","jobId":"...","terminal":true}}
```

The device owns a process-local replay ring of 256 events. Events are capped at 96 KiB and small results may be inlined up to 48 KiB; larger results advertise result metadata and remain recoverable through explicit job polling.

A driver may subscribe with `GET /mcp/<secret>?after=<sequence>` as a WebSocket upgrade or as SSE. `Last-Event-ID` is accepted as the SSE resume cursor. At most four event subscribers are retained across WebSocket and SSE combined. Slow SSE consumers are dropped instead of allowing an unbounded write queue.

The relay requests device replay with `cli.replay.request`, acknowledges accepted device events with `cli.ack`, and tracks per-subscriber cursors so replayed events are not rebroadcast to subscribers that already consumed them. On device reconnect, `relay.ready.cliResumeAfter` is the oldest active subscriber cursor the relay still needs. Replay payloads remain device-owned; the Durable Object does not write each event to storage.

SSE events are valid JSON-RPC notifications using `notifications/riftcli/event`. Job list/poll/cancel remain recovery/debug fallbacks rather than the normal progress loop.

## Security requirements

- Reject non-TLS device endpoints.
- Never place the bearer token inside message bodies or logs.
- Bind each authenticated token to one device ID.
- Allow only one active socket per device; a newer authenticated socket replaces the older one.
- Generate unpredictable request IDs and enforce request timeouts.
- Limit relay envelopes to 1,000,000 UTF-8 bytes on-device and at the Worker boundary; apply the tighter RiftCLI event bounds before relay send.
- Never interpret tool arguments in the relay. Forward complete MCP JSON-RPC objects unchanged.
- Never persist MCP payloads, tool results, or RiftCLI event payloads in Durable Object storage; socket/correlation/subscriber cursors remain ephemeral.
- Coalesce identical retried `tools/call` requests inside the on-device `RiftMcpServer`, including requests already in flight.
- Keep `RiftToolHost` permissions and `RiftToolSandbox` as the final authority.
