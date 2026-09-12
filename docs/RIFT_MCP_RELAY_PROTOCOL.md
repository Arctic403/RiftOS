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
{"type":"relay.ready"}
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

## Security requirements

- Reject non-TLS device endpoints.
- Never place the bearer token inside message bodies or logs.
- Bind each authenticated token to one device ID.
- Allow only one active socket per device; a newer authenticated socket replaces the older one.
- Generate unpredictable request IDs and enforce request timeouts.
- Limit messages to 1,000,000 UTF-16 characters on-device and apply tighter byte limits at the edge where practical.
- Never interpret tool arguments in the relay. Forward complete MCP JSON-RPC objects unchanged.
- Never persist MCP payloads or tool results in Durable Object storage; only socket and pending-response correlation live in memory.
- Coalesce identical retried `tools/call` requests inside the on-device `RiftMcpServer`, including requests already in flight.
- Keep `RiftToolHost` permissions and `RiftToolSandbox` as the final authority.
