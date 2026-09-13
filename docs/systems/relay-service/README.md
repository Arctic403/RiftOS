# Public Rift MCP Relay Service

## Purpose

The public relay service connects one authenticated RiftOS device WebSocket to HTTP MCP requests without ever receiving local filesystem authority. It is a transport rendezvous, not a tool implementation.

## Source ownership

- `relay/src/index.js` — Cloudflare Worker + Durable Object room.
- `relay/wrangler.jsonc` — worker/DO binding configuration.
- `relay/package.json` — relay package metadata.
- `relay/README.md` — deployment/configuration quick reference.

## Endpoints

- `/health` -> relay/device-connected status through the room.
- `/device` -> authenticated WebSocket upgrade for RiftOS device (`DEVICE_TOKEN`, protocol header, device ID).
- `/mcp/<secret>` -> HTTP MCP entry gated by `MCP_PATH_TOKEN`; POST forwards JSON-RPC to the connected device. OPTIONS/DELETE are handled for MCP client lifecycle/CORS expectations.

## Durable Object model

`RiftRelayRoom` keeps the current device socket and an in-memory `pending` request map. A newer device connection replaces the older socket. HTTP MCP requests receive generated relay request IDs and wait for matching `mcp.response`/`mcp.error`; timeouts/disconnects fail pending requests.

The room intentionally does not persist request payloads to Durable Object storage.

## Critical invariants

- Relay never implements or filters Rift tools; `tools/list` and calls are forwarded unchanged.
- Device authentication and MCP path secrecy are independent controls.
- Only the currently-owned socket may satisfy/close current pending requests; stale socket events are ignored.
- Message/body sizes and timeouts are bounded.
- Pending requests are cleared on completion, timeout or active-device disconnect.

## Failure signatures

- Device says connected but MCP endpoint returns offline -> wrong room/socket state or replacement race.
- Stale disconnect knocks out current device -> socket identity guard regression.
- Tools missing only through remote client but device `tools/list` has them -> client action cache/registration, not relay filtering.
- Requests time out -> device offline, on-device server not replying, socket send failure or timeout too short for operation.

## Fix map

Public auth/routing/DO socket correlation -> relay service.
Device reconnect/state -> `RiftMcpRelayClient`.
MCP methods/tools -> on-device server/host.

## Validation

Test unauthorized device, wrong protocol, wrong MCP path, device replacement, concurrent MCP requests, invalid/oversized JSON, timeout and disconnect. Confirm `tools/list` bytes are passed through without a local allowlist.
