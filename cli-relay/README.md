# RiftCLI Relay

`cli-relay/` is the standalone Cloudflare transport for RiftCLI.

It is intentionally independent from `relay/`, the existing Rift MCP relay. The two Workers use different protocols, settings, authentication secrets, device sockets, Durable Object bindings, request state and lifecycle.

## Contract

Protocol: `rift-cli-relay-v1`

Cloudflare secrets:

- `DEVICE_TOKEN` authenticates the RiftOS phone's outbound WebSocket.
- `DRIVER_TOKEN` authenticates the external driver API.

Device endpoint:

```
wss://<rift-cli-relay-worker>/device
```

The Android `RiftCliRelayClient` sends `X-Rift-Protocol: rift-cli-relay-v1`, an independent CLI device ID and `Authorization: Bearer <DEVICE_TOKEN>`.

Driver endpoints:

- `GET /health` — authenticated CLI transport health.
- `POST /request` — submit exactly one command rooted at `rift-cli`.
- `GET /events?after=<sequence>&limit=<n>` — bounded lifecycle-event page and replay recovery.

`POST /request` accepts:

```json
{
  "requestId": "optional-stable-id",
  "command": "rift-cli status",
  "cwd": "/"
}
```

The Worker rejects commands that are not exactly `rift-cli` or do not begin with `rift-cli `. RiftCLI itself remains the authority gate; the Worker does not implement RiftOS mutation policy.

## Reliability

The Worker keeps at most 64 pending requests. Completed request IDs are fingerprint-bound and retained in a bounded, five-minute in-memory cache so a lost HTTP response can be retried without re-executing the same request. Timeout or disconnect produces a tombstone because execution state may be uncertain.

CLI events remain primarily device-owned in the bounded `RiftCliEventBus`. The Worker retains at most 256 events in memory, caps each event at 128 KiB and caps each `/events` page to 32 events / 512 KiB. Cloudflare hibernation restores the sequence high-water from the device WebSocket attachment; when event payloads are absent, `/events` requests bounded replay from the phone.

The Worker deliberately does not use Durable Object storage for request payloads, CLI results or event payloads.

## Separation invariant

The MCP relay must never accept, forward, replay or acknowledge `cli.*` messages. The CLI relay must never accept MCP JSON-RPC or `mcp.*` envelopes. Regression tests fail if either boundary is crossed.
