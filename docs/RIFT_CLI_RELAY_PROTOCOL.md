# RiftCLI Relay Protocol v1

Status: source-defined; Builder, deployment and installed-device proof are still required before promotion.

## Purpose

`rift-cli-relay-v1` gives RiftCLI its own outbound Cloudflare connection so long-running CLI jobs and lifecycle events are not multiplexed through Rift MCP. MCP remains the independent transport for the 19-tool ToolHost surface and Local Agent.

## Device handshake

The Android client connects to `/device` with bearer `DEVICE_TOKEN`, `X-Rift-Device-Id` and `X-Rift-Protocol: rift-cli-relay-v1`.

The first device envelope is:

```json
{
  "type": "device.hello",
  "protocol": "rift-cli-relay-v1",
  "deviceId": "...",
  "ackSequence": 123,
  "client": {
    "name": "RiftCLI",
    "version": "...",
    "sourceSha": "...",
    "buildRunId": "..."
  }
}
```

The Worker responds with `relay.ready` and `resumeAfter`. The client replays retained device events after that sequence.

## Driver request

`POST /request` requires bearer `DRIVER_TOKEN`. The body contains a stable outer `requestId`, a command rooted at `rift-cli`, and optional `cwd`.

The Worker sends:

```json
{
  "type": "cli.request",
  "protocol": "rift-cli-relay-v1",
  "requestId": "...",
  "command": "rift-cli ...",
  "cwd": "/"
}
```

The device replies with `cli.response` or `cli.error`. Authority-bearing `rift-cli driver request` commands still pass through the native RiftCLI replay/authority gate and execute through existing bounded RiftOS owners.

Outer request IDs are fingerprint-bound. Reuse for another command is rejected. Completed responses are bounded and replayable for five minutes. Timeout/disconnect is tombstoned to avoid uncertain replay.

## Events

Device lifecycle events use schema `rift.cli-event/1` in `cli.event` envelopes. Accepted events receive `cli.ack`.

The Worker retains a bounded in-memory event window and exposes authenticated `GET /events?after=<sequence>&limit=<n>`. If Cloudflare has hibernated and lost event payloads while retaining the socket cursor, the Worker sends `cli.replay.request` to refill from the device-owned ring.

## Hard separation

`rift-mcp-relay-v1` and `rift-cli-relay-v1` do not share:
- Cloudflare Worker;
- Durable Object binding;
- device WebSocket;
- bearer token;
- device ID;
- ACK/replay cursor;
- pending-request table;
- request protocol;
- event lifecycle.

Sharing the passive `RiftDebugHub` and existing RiftOS execution owners does not merge transports or authority.
