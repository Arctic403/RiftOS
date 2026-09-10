# RiftBrowser MCP App Compatibility

`rift-mcp-app-v1` lets RiftBrowser teach ChatGPT Web about the local Rift tool manifest while keeping tool execution inside RiftOS.

## Purpose

Some ChatGPT plans do not expose custom MCP app registration. RiftBrowser therefore provides a compatibility adapter at the browser boundary while the device-side implementation remains normal in-process MCP JSON-RPC.

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
RiftToolSandbox
```

There is no remote relay, WSS connection, pairing key or public MCP endpoint in this path.

## Protocol

At page startup, the browser adapter performs MCP `initialize` and `tools/list`. The returned tool schemas are the source of truth for the compact context shown to ChatGPT.

When a tool is required, ChatGPT is instructed to emit exactly one envelope:

```text
<rift_call>{"call_id":"unique-id","name":"rift_list","args":{"path":"workspace"}}</rift_call>
```

The browser adapter validates the call against the live manifest and invokes MCP `tools/call`. Tool results are returned as a structured `[RIFT_MCP_RESULT_V1]` continuation message.

This protocol deliberately does not reuse any Agent V1/V2/V3 marker or fenced `rift-tool` packet.

## Browser behavior

The adapter:

- is injected only into ChatGPT main-frame HTTPS origins;
- never exposes a filesystem JavaScript API;
- never exposes `RiftNativeDispatcher`;
- limits automatic calls to 24 per minute in one page;
- deduplicates completed call envelopes;
- serializes calls so tool-result continuations cannot race;
- shows a `Rift MCP` badge with the current local tool count;
- lets the badge disable compatibility behavior for the current tab;
- injects the compact tool manifest once per conversation route;
- processes only mutation-touched messages instead of rescanning the whole conversation while tokens stream.

Because ChatGPT Web has no supported local-tool registration hook on these plans, this compatibility layer still observes the composer and semantic assistant-message elements. That browser-facing dependency is isolated in one JavaScript asset. It is not part of the device capability boundary and does not own tool execution.

## Rift AI transport mode

`riftbrowser-mcp-app.js` also supports the local Rift AI HTML cockpit. Native code calls `window.RiftMcpAppControl.submitTask(...)` in the hidden ChatGPT WebView with a native-created session ID, task and compact project context. The adapter waits for MCP readiness and the normal ChatGPT composer, writes the task/context, and clicks the normal ChatGPT Web send control. There is no model API request path.

The model never supplies the AI session ID. When the adapter parses a model `<rift_call>` during an active Rift AI task, it privately adds `_meta["riftos/aiSessionId"]` to the local MCP `tools/call`. This makes working-tree journaling specific to the AI task without changing the model-visible tool schema. Calls made outside the active Rift AI task are untagged.

The adapter mirrors cleaned assistant text and transport lifecycle back to Android as exact-origin `rift/ai/event` messages over `RiftMcpNative`. Tool envelopes are stripped from the mirrored assistant pane; the actual tool loop still occurs in ChatGPT Web and MCP results still return through the composer. Completion is reported only after output stabilizes, the stop control is gone, active tool round-trips have drained and any tool-result continuation has produced another assistant update. Stop requests likewise wait for active tool work to drain before the session becomes reviewable.

## Permissions

Read/write policy comes exclusively from `RiftToolHost` and is configured in the local **Rift MCP** system app. Read defaults on. Write defaults off. A browser request cannot bypass those settings.

## Failure behavior

If ChatGPT changes its composer or semantic message attributes, compatibility mode can stop detecting calls. The local MCP server, sandbox and permissions remain intact. The badge reports local MCP/tool-list failures instead of granting broader access.
