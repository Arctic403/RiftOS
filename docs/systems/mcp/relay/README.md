# Rift MCP Relay Client

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-20.**

## Purpose

The Relay client provides an optional outbound-only WSS transport from RiftOS to the public relay while keeping all MCP execution/grants on device.

## Source ownership

- `RiftMcpRelayClient.kt` — socket lifecycle, protocol envelopes, reconnect.
- `RiftRelaySettings.kt` — persisted enablement/endpoint/device id and secret-token lookup.
- `RiftSecretStore.kt` — AES-GCM token storage backed by Android Keystore.
- `RiftMcpActivity.kt` — local configuration/status UI.
- `RiftMcpServer.kt` — actual MCP request execution/retry dedupe.

## Configuration

Default relay state is disabled.

A configured enabled relay requires:
- valid `wss://` URI;
- nonblank host;
- no URI user-info;
- no fragment;
- saved pairing token.

New/replacement token:
- trimmed;
- <=4096 characters;
- rejects CR/LF and other ASCII control characters before it can become an Authorization header.

Stable device id is a generated UUID stored in relay preferences.

Endpoint/enabled/device id are ordinary private preferences. Pairing token plaintext is not stored there; it is encrypted through `RiftSecretStore`.

Clearing token also disables relay.

## Connection

Client constructs an OkHttp WebSocket request with:
- Authorization: Bearer <token>;
- X-Rift-Device-Id;
- X-Rift-Protocol: rift-mcp-relay-v1.

It sends `device.hello` after socket open, including device id plus RiftOS version/source/build diagnostics.

No local listening socket is created.

## Relay protocol

Inbound recognized types:
- relay.ready;
- relay.ping;
- mcp.request;
- mcp.notification;
- cli.replay.request;
- cli.ack;
- relay.error.

Unknown types receive `mcp.error`.

Incoming text messages are capped at 1,000,000 UTF-8 bytes. Oversize messages close the socket with code 1009.

Outgoing `mcp.response` envelopes are also capped at 1,000,000 UTF-8 bytes before WebSocket send; an oversized local response is converted to a bounded `mcp.error` instead of being transmitted.

Invalid JSON returns a protocol error.

`mcp.request` requires requestId + object payload.

The payload is passed unchanged to:
`RiftMcpServer.handleAsync(payload, requestId)`.

`mcp.notification` carries an id-less `notifications/*` payload and is passed to the same server without a retry key or response callback, preserving JSON-RPC notification semantics.

That relay request id is the only stable retry key supplied to server idempotency.

## RiftCLI push events

The client subscribes to the process-wide `RiftCliEventBus` owned by `RiftMcpRuntime`. New events are wrapped as `cli.event` and sent over the already-open device WSS; no second device connection or rapid poll loop is created.

On `relay.ready`, the client reads `cliResumeAfter` and replays retained device events after that sequence. The relay may later request another replay with `cli.replay.request`. `cli.ack` advances the client's observed acknowledgement high-water mark. The event ring itself remains on-device.

The event bus is bounded to 256 events, 96 KiB per event and 48 KiB inline results. Large results are represented by metadata and remain available through the explicit job-control fallback when retained by the owning job lane.

Responses are sent only if the WebSocket is still the current socket. Each forwarded request also gets a 70-second Android-side forwarding watchdog; if the local MCP callback never terminates, the client emits one bounded `mcp.error` instead of leaving the relay request open forever.

## Stale-socket protection

Every WebSocket callback checks object identity against the currently owned socket.

Close/failure from an older replaced socket cannot clear or reconnect over a newer socket.

Reload/disconnect cancels scheduled reconnect and nulls current socket before starting or stopping as requested.

## Reconnect

On current-socket close/failure while desiredRunning:
- connectedAt resets;
- attempt counter increments;
- retry delay doubles from 1s through a capped base of 60s;
- random jitter 0..<750 ms is added;
- only one scheduled reconnect is retained.

`relay.ready` resets attempts to zero.

## Status

Status exposes:
- state;
- bounded detail (<=240 chars);
- enabled/configured;
- endpoint;
- device id;
- connectedAt;
- attempts;
- last RiftCLI acknowledgement sequence;
- bounded event-bus status/capacity.

It never returns the pairing token.

## Authority boundary

Relay owns transport only.

It cannot:
- alter ToolHost grants;
- access workspace directly;
- invoke RiftShell directly;
- bypass Sandbox;
- add tools.

Browser compatibility and relay converge on the same process-owned MCP server/host.

## Source hardening in this audit

Relay settings were tightened from a prefix-only WSS check to actual URI validation, and pairing tokens gained length/control-character validation before HTTP-header use. The client message bound is byte-accurate rather than character-counted, local MCP responses are bounded before WebSocket transmission, and request forwarding has a 70-second terminal watchdog ordered inside the public relay's 75-second timeout. N1.5 additionally reuses this persistent WSS for bounded RiftCLI push events, reconnect replay and acknowledgements.

## Critical invariants

- outbound WSS only;
- no plaintext token preference;
- no token in status;
- invalid endpoint/token rejected before connect;
- incoming/outgoing relay envelopes <=1,000,000 UTF-8 bytes;
- stale socket events ignored;
- one bounded reconnect schedule;
- relay request id forwarded for server retry dedupe;
- local forwarding terminates within 70 seconds, before the public relay's 75-second timeout;
- RiftCLI events use the process-wide bounded device ring and the existing WSS;
- reconnect replay is sequence-based and does not give the relay execution authority;
- local ToolHost remains authority.

## Failure signatures

- malformed WSS config survives save -> settings validation regression;
- token with CR/LF reaches header construction -> input-validation regression;
- old socket close drops new socket -> identity regression;
- retry loop schedules multiple concurrent reconnects -> lifecycle regression;
- relay changes read/write permissions -> authority regression;
- status exposes token -> secret leak;
- same relay request duplicates mutation -> Server/relay request-id regression;
- RiftCLI progress requires rapid polling despite an active relay WSS -> push-channel regression;
- replay request/ACK handling regresses or event replay escapes the bounded device ring -> event-recovery regression.

## Fix map

Socket/protocol/backoff -> `RiftMcpRelayClient.kt`.

Configuration/device id -> `RiftRelaySettings.kt`.

Token cryptography -> `RiftSecretStore.kt`.

MCP semantics/retry cache -> `RiftMcpServer.kt`.

## Validation

Second source audit must recheck URI/token validation, encrypted token storage, outbound headers, 1M input bound, current-socket checks, reconnect cap/jitter, 70-second forwarding watchdog, request-id forwarding, token-free status, process-wide event-bus subscription, replay/ACK handling and bounded event status.

Public relay-service behavior is a separate subsystem audit.
