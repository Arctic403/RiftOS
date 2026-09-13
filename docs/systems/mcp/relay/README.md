# Rift MCP Relay Client

## Purpose

The relay client gives remote MCP clients a secure outbound path to the same on-device `RiftMcpServer` without opening a listener on the phone.

## Source ownership

- `RiftMcpRelayClient.kt` — WebSocket transport/state/reconnect/forwarding.
- `RiftRelaySettings.kt` — enabled flag, TLS endpoint, stable device ID and token retrieval/storage.
- `RiftSecretStore.kt` — encrypted bearer-token storage.
- `RiftMcpActivity.kt` — user configuration/reconnect/status UI.
- public counterpart: `relay/src/index.js` (documented under relay service).

## Runtime flow

```text
RiftMcpRelayClient
  -> outbound WSS /device with bearer token + device ID + protocol header
  -> relay.ready
  <- mcp.request envelopes
  -> RiftMcpServer.handleAsync(payload)
  -> mcp.response / mcp.error
```

The relay is disabled unless explicitly configured. It accepts secure `wss://` configuration and reconnects with bounded exponential backoff plus jitter. Socket identity checks ignore stale events from a replaced connection.

## Authority boundary

The relay is transport-only. It cannot call RiftFS directly, cannot override tool grants and cannot widen MCP's workspace sandbox. All received MCP JSON-RPC is forwarded to the existing local server.

## State

`status()` reports state/detail/enabled/configured/endpoint/deviceId/connect time/attempt count. The pairing token is not included. Settings persist endpoint/config state; token material is kept through `RiftSecretStore`.

## Failure signatures

- `needs-setup` -> missing endpoint/token.
- connecting/reconnecting forever -> endpoint/TLS/auth/network/service/device pairing.
- relay connected but tool denied -> local `RiftToolHost` grant.
- old socket close knocks down new connection -> stale socket identity regression.
- ChatGPT still shows old action count after relay reconnect -> client-side action catalog cache; relay reconnect does not force action rescan.

## Fix map

WebSocket lifecycle/backoff/envelope forwarding -> relay client.
Configuration validation -> relay settings.
Token encryption -> secret store.
Public HTTP/WebSocket routing -> relay service.
MCP semantics -> local server/host.

## Validation

Test disabled startup, missing token, invalid endpoint, successful connect, network loss/reconnect, replacement by a newer socket, oversized/invalid relay messages and local permission denial. Confirm no listening socket is created on-device.
