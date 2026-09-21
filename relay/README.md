# Rift MCP Relay

For the full runtime ownership, failure map, invariants and repair guide, see [`../docs/systems/relay-service/README.md`](../docs/systems/relay-service/README.md).

Single-device development relay for RiftOS. A Durable Object keeps the Android WebSocket, correlates ChatGPT Streamable HTTP requests, and fans out bounded RiftCLI push events to driver WebSocket/SSE subscribers without storing tool or event payloads.

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
3. Keep the checked-in compatibility flags `enable_request_signal` and `request_signal_passthrough`; SSE disconnect cleanup depends on request cancellation reaching the Durable Object.
4. Deploy the Worker. Durable Object migration `v1` creates `RiftRelayRoom`.
5. In RiftOS → Rift MCP, enter the Android endpoint and `DEVICE_TOKEN`, enable the relay and connect.
6. Confirm `/health` reports `deviceConnected: true`.
7. In ChatGPT Developer mode, register the private HTTPS MCP URL.

Production SSE clients must send a stable `Mcp-Session-Id`. For manual browser diagnostics only, append `?subscriber=<id>` (plus `&after=<sequence>` when needed) using a 1-128 character identifier made from letters, digits, `.`, `_`, `:` or `-`. Anonymous SSE streams are rejected. SSE connections are lease-bounded and reconnect with `Last-Event-ID`.

This secret-path authentication is intended only for personal Developer mode testing. Worker invocation logs are disabled because the MCP secret is embedded in the URL path. Add standards-compliant OAuth 2.1 before sharing or publishing the MCP server.
