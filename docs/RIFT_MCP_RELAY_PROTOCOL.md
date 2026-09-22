# Rift MCP Relay Protocol

Current protocol: `rift-mcp-relay-v1`.

The Rift MCP relay is the independent Cloudflare transport for MCP JSON-RPC and MCP SSE lifecycle only. RiftCLI transport was removed from this protocol and now uses the separate `rift-cli-relay-v1` service documented in `docs/RIFT_CLI_RELAY_PROTOCOL.md`.

## Device connection

RiftOS opens an outbound authenticated WebSocket to the MCP Worker's `/device` endpoint with:

- `Authorization: Bearer <DEVICE_TOKEN>`;
- `X-Rift-Device-Id`;
- `X-Rift-Protocol: rift-mcp-relay-v1`.

The device sends:

```json
{
  "type": "device.hello",
  "protocol": "rift-mcp-relay-v1",
  "deviceId": "...",
  "client": {
    "name": "RiftOS",
    "version": "...",
    "sourceSha": "...",
    "buildRunId": "..."
  }
}
```

The Worker replies with:

```json
{"type":"relay.ready"}
```

No RiftCLI sequence, replay or ACK state is carried in this handshake.

## MCP forwarding

The public `/mcp/<secret>` path remains the MCP surface.

POST forwards one JSON-RPC request or notification. Request envelopes sent to the device use `mcp.request`; notifications use `mcp.notification`. Device terminal envelopes are `mcp.response` or `mcp.error`.

The relay preserves bounded pending-request correlation, request fingerprint dedupe, waiter limits and timeout behavior. MCP payloads are not persisted in Durable Object storage.

## MCP SSE

GET opens the MCP SSE lifecycle stream. Existing protections remain in force:

- stable `Mcp-Session-Id` or bounded diagnostic subscriber identity;
- eight-client ceiling;
- reconnect throttling;
- same-session replacement;
- bounded stream buffer;
- heartbeat drain detection;
- bounded lease with reconnect;
- explicit DELETE session close.

The initial stream record is an SSE comment (`: rift-mcp-ready`), not a RiftCLI JSON-RPC notification. The MCP Worker no longer exposes RiftCLI driver WebSockets, CLI replay requests, CLI event fan-out or CLI ACK handling.

## Separation invariant

The MCP relay must not contain `cli.event`, `cli.replay.request`, `cli.ack`, RiftCLI subscriber state or CLI pending requests.

RiftCLI uses a different Worker, protocol, device WebSocket, token, device identity, request table and replay cursor. Removing or restarting the CLI relay must not alter the MCP/local-agent transport, and vice versa.

## Bounds

- Relay/device message limit: 1,000,000 UTF-8 bytes.
- Pending MCP requests: 64.
- MCP relay request timeout: 75 seconds.
- MCP SSE clients: 8.
- SSE buffer: 512,000 bytes.
- SSE heartbeat: 15 seconds.
- No Durable Object payload persistence.

See `relay/src/index.js`, `RiftMcpRelayClient.kt`, and `docs/systems/mcp/relay/README.md` for the source-defined contract.
