# RiftDebugHub

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-20.**

## Purpose

RiftDebugHub is the process-wide passive correlation and diagnostics core for RiftOS. It records bounded metadata beside normal execution paths so a debugger failure cannot become part of tool execution.

It is not a shell, executor, permission service, validator, cancellation service, filesystem owner, network client, model host, or mutation authority.

## Source ownership

- `RiftDebugHub.kt` — bounded hub, spans, event snapshots, secret-key redaction and the adapter contract.
- `RiftMcpRuntime.kt` — owns the single process-wide hub.
- `RiftMcpServer.kt` — creates the MCP tool-call parent span and returns `riftos/traceId`.
- `RiftToolHost.kt` — creates the child tool-host span and exposes the read-only `rift_debug` query.
- `scripts/test-rift-debug-hub.mjs` — source/wiring/authority regression lock.

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

For components that only need standalone events, `debugHub.sink("component.name")` supplies a lightweight sink without an adapter lifecycle.

Signals contain operation/phase/correlation/outcome/duration/message and bounded string attributes. They must not contain request bodies, file contents, tokens or credentials.

## Query surface

The single read-only MCP tool is `rift_debug`.

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
- RiftShell batch remains disabled.

## Failure signatures

- missing `riftos/traceId` -> MCP server integration regressed;
- MCP and Tool Host events have different trace IDs -> correlation context was not forwarded;
- an active span never ends -> producer missed a terminal callback or process work is hung;
- rising `evictedActiveSpans` -> active-span leak or sustained concurrency beyond the configured bound;
- rising `droppedEvents` -> event rate exceeds retained history;
- raw secret-like values appear -> adapter violated metadata rules or redaction regressed;
- debugger failure blocks a tool -> passive-boundary regression.

## Fix map

- event model, bounds, redaction or plug contract -> `RiftDebugHub.kt`;
- process lifetime -> `RiftMcpRuntime.kt`;
- MCP parent correlation/result metadata -> `RiftMcpServer.kt`;
- public query schema/read grant/child span -> `RiftToolHost.kt`;
- subsystem-specific emission -> that subsystem's adapter.

## Validation

Run:

```sh
node scripts/test-rift-debug-hub.mjs
npm run check
```

An Android build remains the Kotlin/compiler/package gate. Source validation is not installed-device proof.
