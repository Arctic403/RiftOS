# Rift MCP Relay Client

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-20.**

## Purpose

The Relay client provides an optional outbound-only WSS transport from RiftOS to the public relay while keeping all MCP execution/grants on device.

## Source ownership

- `RiftMcpRelayClient.kt` — socket lifecycle, protocol envelopes, reconnect and passive RiftDebugHub transport diagnostics.
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

### Passive N1.5 debugger observability

The process-wide RiftDebugHub observes this transport beside the execution path.

`RiftCliEventBus` emits `event.created` metadata under component `riftcli.event-bus`.

`RiftMcpRelayClient` emits bounded metadata under component `mcp.relay` for:
- `socket.connect`;
- `socket.open`;
- `relay.ready` and its resume cursor;
- `cli.event.send` with sequence/type/status/terminal and queue outcome;
- `cli.replay.request` and `cli.replay.send`;
- `cli.ack` with sequence and whether the local acknowledgement high-water advanced;
- socket close/failure/reconnect scheduling.

These signals contain no MCP payload body, CLI result body, relay endpoint URL, Authorization header or pairing token. They grant no transport or execution authority.

This gives the installed-device promotion test three separate evidence points:
1. `event.created` proves the local CLI event exists;
2. `cli.event.send: queued` proves OkHttp accepted it for the current device WebSocket;
3. matching `cli.ack` proves the Cloudflare relay received and acknowledged that sequence.

A separate external subscriber proof is still required to prove Cloudflare -> driver SSE/WebSocket delivery.

Responses are sent only if the WebSocket is still the current socket. Each forwarded request also gets a 110-second Android-side forwarding watchdog; if the local MCP callback never terminates, the client emits one bounded `mcp.error` instead of leaving the relay request open forever.

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
- `mcp.cancel` is accepted from the relay and cancels the matching server-owned execution; socket loss cancels every request owned by that socket;
- local forwarding terminates within 110 seconds, before the public relay's 120-second request timeout;
- RiftCLI events use the process-wide bounded device ring and the existing WSS;
- reconnect replay is sequence-based and does not give the relay execution authority;
- debugger instrumentation remains passive and metadata-only;
- local event creation, WSS queueing and relay acknowledgement remain separately observable;
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
- replay request/ACK handling regresses or event replay escapes the bounded device ring -> event-recovery regression;
- a connected relay queues a CLI event but no matching ACK is observed -> device-to-relay delivery failure/unproven state;
- debugger signals expose endpoint URLs, Authorization values, pairing tokens or CLI/MCP payload bodies -> diagnostics privacy regression.

## Fix map

Socket/protocol/backoff -> `RiftMcpRelayClient.kt`.

Configuration/device id -> `RiftRelaySettings.kt`.

Token cryptography -> `RiftSecretStore.kt`.

MCP semantics/retry cache -> `RiftMcpServer.kt`.

## Validation

Second source audit must recheck URI/token validation, encrypted token storage, outbound headers, 1M input bound, current-socket checks, reconnect cap/jitter, 110-second forwarding watchdog, request-id forwarding, token-free status, process-wide event-bus subscription, replay/ACK handling and bounded event status.

Public relay-service behavior is a separate subsystem audit.
