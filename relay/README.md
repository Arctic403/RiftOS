# Rift MCP Relay

For the full runtime ownership, failure map, invariants and repair guide, see [`../docs/systems/relay-service/README.md`](../docs/systems/relay-service/README.md).

Single-device development relay for RiftOS. A Durable Object keeps the Android WebSocket and correlates ChatGPT Streamable HTTP requests without storing tool payloads.

## Required secrets

- `DEVICE_TOKEN`: random token used only by the RiftOS Android client.
- `MCP_PATH_TOKEN`: separate random token placed in the private ChatGPT MCP URL.

Never reuse either token and never commit their values.

## Endpoints

- Android: `wss://<worker-host>/device`
- ChatGPT: `https://<worker-host>/mcp/<MCP_PATH_TOKEN>`
- Health: `https://<worker-host>/health`

## Cloudflare setup

1. Create a Worker connected to this repository with root directory `relay`.
2. Add both required values as encrypted secrets under Worker settings.
3. Deploy the Worker. Durable Object migration `v1` creates `RiftRelayRoom`.
4. In RiftOS → Rift MCP, enter the Android endpoint and `DEVICE_TOKEN`, enable the relay and connect.
5. Confirm `/health` reports `deviceConnected: true`.
6. In ChatGPT Developer mode, register the private HTTPS MCP URL.

This secret-path authentication is intended only for personal Developer mode testing. Add standards-compliant OAuth 2.1 before sharing or publishing the MCP server.
