# RiftBrowser MCP App Compatibility

`rift-mcp-app-v1` lets RiftBrowser teach ChatGPT Web about the local Rift tool manifest while keeping tool execution inside RiftOS.

## Purpose

Some ChatGPT plans do not expose custom MCP app registration. RiftBrowser therefore provides a compatibility adapter at the browser boundary while keeping the device-side implementation standards-based MCP.

```text
ChatGPT Web
    |
    | structured compatibility context/result
    v
riftbrowser-mcp-app.js
    |
    | exact-origin WebMessage
    v
RiftBrowserMcpAppBridge
    |
    | MCP JSON-RPC in memory
    v
RiftMcpServer
    |
    v
RiftToolHost
    |
    v
RiftBridgeSandbox
```

## Protocol

At page startup, the browser adapter performs MCP `initialize` and `tools/list`. The returned tool schemas are the source of truth for the context shown to ChatGPT.

When a tool is required, ChatGPT is instructed to emit exactly one envelope:

```text
<rift_call>{"call_id":"unique-id","name":"rift_list","args":{"path":"workspace"}}</rift_call>
```

The browser adapter validates the call against the live manifest and invokes MCP `tools/call`. Tool results are returned as a structured `[RIFT_MCP_RESULT_V1]` continuation message.

This protocol deliberately does not reuse any Agent V1/V2/V3 marker or fenced `rift-tool` packet.

## Browser behavior

The adapter:

- is injected only into the ChatGPT main-frame HTTPS origins;
- never exposes a filesystem JavaScript API;
- never exposes `RiftNativeDispatcher`;
- limits automatic calls to 24 per minute in one page;
- deduplicates completed call envelopes;
- serializes calls so tool-result continuations cannot race;
- shows a `Rift MCP` badge with the current local tool count;
- lets the badge disable compatibility behavior for the current tab.

Because ChatGPT Web has no supported local-tool registration API on these plans, this compatibility layer must observe the ChatGPT composer and rendered assistant messages. That browser-facing dependency is isolated in one JavaScript asset. It is not part of the device capability boundary and does not own tool execution.

## Permissions

Read and write policy comes exclusively from `RiftToolHost` and is shared with the remote MCP adapter. Write tools remain disabled by default. A browser request cannot bypass those settings.

## Failure behavior

If ChatGPT changes its composer or semantic message attributes, compatibility mode can stop detecting calls, but the local MCP server, sandbox and permissions remain intact. The badge reports bridge/tool-list failures instead of silently granting broader access.
