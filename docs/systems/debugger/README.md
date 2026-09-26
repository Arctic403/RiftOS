# RiftDebugHub

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-20.**

## Purpose

RiftDebugHub is the process-wide passive correlation and diagnostics core for RiftOS. It records bounded metadata beside normal execution paths so a debugger failure cannot become part of tool execution.

It is not a shell, executor, permission service, validator, cancellation service, filesystem owner, network client, model host, or mutation authority.

## Source ownership

- `RiftDebugHub.kt` — bounded hub, spans, event snapshots, secret-key redaction and the adapter contract.
- `RiftMcpRuntime.kt` — owns the single process-wide hub and injects it into the CLI event/relay path.
- `RiftMcpServer.kt` — creates the MCP tool-call parent span and returns `riftos/traceId`.
- `RiftToolHost.kt` — creates the child tool-host span and exposes the read-only `rift_debug` query.
- `RiftCliEventBus.kt` — emits bounded event-creation metadata into component `riftcli.event-bus`.
- `RiftMcpRelayClient.kt` — emits bounded socket/push/replay/ACK metadata into component `mcp.relay`.
- `scripts/test-rift-debug-hub.mjs` — source/wiring/privacy/authority regression lock.

## Runtime flow

```text
MCP tools/call
  -> mcp.server span
  -> RiftToolHost child span
  -> existing sandbox/shell/tool implementation
  -> terminal span outcome
  -> structured MCP result with riftos/traceId
```

Debug events travel beside that path. Normal execution never waits for a debugger consumer.

RiftCLI N1.5 adds a second passive diagnostic flow:

```text
RiftCLI state transition
  -> RiftCliEventBus event.created
  -> RiftMcpRelayClient cli.event.send
  -> existing device WSS
  -> Cloudflare relay
  -> cli.ack received by device
```

The debugger records only bounded metadata such as event sequence, event type, lane, status, terminal flag, queue result, replay cursor/count and socket/HTTP status. It does not retain CLI result bodies, MCP payloads, endpoint URLs, Authorization headers, pairing tokens or SSE/WebSocket subscriber payloads.

## Universal plug

A subsystem implements `RiftDebugAdapter`:

```kotlin
class ExampleAdapter : RiftDebugAdapter {
    override val debugComponent = "example"

    override fun attachDebugSink(sink: RiftDebugSink): AutoCloseable {
        // Retain the sink, emit RiftDebugSignal metadata, and detach on close.
        return AutoCloseable { /* release retained sink */ }
    }
}
```

Register it with the process hub:

```kotlin
val connection = RiftMcpRuntime.debugHub().plug(exampleAdapter)
```

For components that only need standalone events, `debugHub.sink("component.name")` supplies a lightweight sink without an adapter lifecycle. The N1.5 event bus and relay client use this lightweight path because both are process-owned singletons with the same lifetime as the hub.

Signals contain operation/phase/correlation/outcome/duration/message and bounded string attributes. They must not contain request bodies, file contents, tokens or credentials.

## Query surface

The single read-only MCP tool is `rift_debug`.

The replaceable `/workspace/.riftcli/` package also exposes a local `rift-cli debug <request-id> [status|events|active|components] [component] [traceId] [sinceSequence] [limit]` command. That command has no direct debugger authority: it returns a native driver request for `rift_debug`, so the process-local CLI enable gate, one-action driver protocol and passive DebugHub authority declaration remain authoritative. Package version 0.2.1 live-proved this path on installed run 393 with request `debughub-cli-001`.

Actions:
- `status` — capacity, counters, authority declaration and known components;
- `events` — bounded event timeline, optionally filtered by exact trace or component;
- `active` — currently open spans and their age;
- `components` — known components, attached adapter counts and active-span counts.

`events` and `active` clamp `limit` to 1–200. Event history is process-memory only and disappears on process death.

## Bounds and privacy

- retained events: 1,024;
- simultaneously active spans: 128;
- query return limit: 200;
- attributes per event: 16;
- attribute value: 256 characters;
- message: 512 characters;
- identifiers: 128 characters;
- sensitive attribute names are stored as `[REDACTED]`;
- Bearer credentials and common secret assignments are redacted from retained messages;
- oldest events are dropped at capacity;
- oldest active spans are closed as `evicted` at capacity.

The hub stores no payload body and persists nothing to disk.

## Invariants

- passive observation only;
- no execution path is routed through the hub;
- one process-wide instance;
- MCP and Tool Host spans share a trace;
- terminal span completion is exactly once;
- all retained collections are bounded;
- adapter detach is explicit;
- debug queries require the existing MCP read grant;
- RiftCLI is not enabled or made persistent by this subsystem;
- relay/event diagnostics are metadata-only and never include event payload bodies, URLs or credentials;
- `event.created`, `cli.event.send` and `cli.ack` remain distinguishable so local creation, local queueing and Cloudflare receipt cannot be conflated;
- debugger absence/failure must not change event delivery, replay, ACK or reconnect behavior;
- RiftShell batch remains disabled.

## Failure signatures

- missing `riftos/traceId` -> MCP server integration regressed;
- MCP and Tool Host events have different trace IDs -> correlation context was not forwarded;
- an active span never ends -> producer missed a terminal callback or process work is hung;
- rising `evictedActiveSpans` -> active-span leak or sustained concurrency beyond the configured bound;
- rising `droppedEvents` -> event rate exceeds retained history;
- raw secret-like values appear -> adapter violated metadata rules or redaction regressed;
- debugger failure blocks a tool -> passive-boundary regression;
- a CLI event is created but no `cli.event.send` appears while the relay is connected -> event-to-relay wiring regression;
- `cli.event.send` is queued but no matching `cli.ack` arrives -> device-to-Cloudflare delivery is unproven or broken;
- raw CLI results, MCP payloads, endpoint URLs or credentials appear in relay/event diagnostics -> privacy-boundary regression.

## Fix map

- event model, bounds, redaction or plug contract -> `RiftDebugHub.kt`;
- process lifetime -> `RiftMcpRuntime.kt`;
- MCP parent correlation/result metadata -> `RiftMcpServer.kt`;
- public query schema/read grant/child span -> `RiftToolHost.kt`;
- subsystem-specific emission -> that subsystem's adapter/lightweight debug sink;
- RiftCLI event creation -> `RiftCliEventBus.kt`;
- relay socket/push/replay/ACK diagnostics -> `RiftMcpRelayClient.kt`.

## Validation

Run:

```sh
node scripts/test-rift-debug-hub.mjs
npm run check
```

An Android build remains the Kotlin/compiler/package gate. Source validation is not installed-device proof.
