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

At page startup, the browser adapter performs MCP `initialize` and `tools/list`. The returned tool schemas are the source of truth for the compact context shown to ChatGPT. When `rift_workspace_exec` is present, the adapter also supplies the bounded Rift Code Mode operation contract so the model can collapse many project operations into one local batch.

When a tool is required, ChatGPT is instructed to emit exactly one envelope. For project work it should prefer Code Mode:

```text
<rift_call>{"call_id":"unique-id","name":"rift_workspace_exec","args":{"operations":[{"op":"search","path":"workspace","query":"RiftKernel"},{"op":"read","path":"workspace/src/riftcore.js","startLine":1,"endLine":220}]}}</rift_call>
```

Legacy single-operation tools remain available for compatibility and very small tasks.

The browser adapter validates the call against the live manifest and invokes MCP `tools/call`. Every result is returned as a structured `[RIFT_MCP_RESULT_V1]` continuation message, including `finish:true` batches. The adapter requires the native response to echo the same model `call_id` and, for Rift AI tasks, the same private session ID before accepting it. A successful final batch therefore cannot leave ChatGPT waiting for a result that only RiftOS saw.

This protocol deliberately does not reuse any Agent V1/V2/V3 marker or fenced `rift-tool` packet.

## Browser behavior

The adapter:

- is injected only into ChatGPT main-frame HTTPS origins;
- never exposes a filesystem JavaScript API;
- never exposes `RiftNativeDispatcher`;
- limits automatic calls to 24 per minute in one page;
- deduplicates calls by scoped `call_id` and rejects conflicting reuse;
- arms tool execution only after a new user/AI prompt and only accepts calls from the newest assistant message;
- baselines already-rendered assistant messages so chat history can never replay old tool calls;
- serializes calls so tool-result continuations cannot race;
- shows a `Rift MCP` badge with the current local tool count;
- lets the badge disable compatibility behavior for the current tab;
- injects the compact tool manifest plus Rift Code Mode guide once per conversation route;
- processes only mutation-touched messages instead of rescanning the whole conversation while tokens stream.

Code Mode remains declarative: the adapter does not `eval` model-produced JavaScript in the ChatGPT origin. Filesystem/search/edit execution stays behind `RiftToolHost` in the local sandbox. Because ChatGPT Web has no supported local-tool registration hook on these plans, this compatibility layer still observes the composer and semantic assistant-message elements. That browser-facing dependency is isolated in one JavaScript asset. It is not part of the device capability boundary and does not own tool execution.

## Rift AI transport mode

`riftbrowser-mcp-app.js` also supports the local Rift AI HTML cockpit. Native code calls `window.RiftMcpAppControl.submitTask(...)` in the hidden ChatGPT WebView with a native-created session ID, task and compact project context. The adapter waits for MCP readiness and the normal ChatGPT composer, writes the task/context, and clicks the normal ChatGPT Web send control. There is no model API request path.

The model never supplies the AI session ID. When the adapter parses a model `<rift_call>` during an active Rift AI task, it privately adds `_meta["riftos/aiSessionId"]` plus `_meta["riftos/callId"]` to the local MCP `tools/call`. The server echoes both values in its private result metadata; the browser rejects mismatches or stale-session results. This makes working-tree journaling and result delivery specific to the active call without changing the model-visible tool schema.

The adapter mirrors cleaned assistant text and transport lifecycle back to Android as exact-origin `rift/ai/event` messages over `RiftMcpNative`. Tool envelopes are stripped from the mirrored assistant pane; the actual tool loop still occurs in ChatGPT Web and MCP results still return through the composer. Completion is reported only after output stabilizes, the stop control is gone, active tool round-trips have drained and any tool-result continuation has produced another assistant update. Stop requests likewise wait for active tool work to drain before the session becomes reviewable.

For Rift AI chat routing, the adapter also exposes a read-only `targets()` control that inspects same-origin ChatGPT links already rendered in the page and classifies chats, projects and project chats. It does not call ChatGPT account/backend APIs. `openTargetSearch()` only activates ChatGPT Web's own visible search control; older-history selection therefore remains inside the authenticated ChatGPT interface. Native code validates every selected URL back to exact HTTPS ChatGPT origins before navigation.

## Permissions

Read/write policy comes exclusively from `RiftToolHost` and is configured in the local **Rift MCP** system app. Read defaults on. Write defaults off. A browser request cannot bypass those settings.

## Failure behavior

If ChatGPT changes its composer or semantic message attributes, compatibility mode can stop detecting calls. The local MCP server, sandbox and permissions remain intact. The badge reports local MCP/tool-list failures instead of granting broader access.
