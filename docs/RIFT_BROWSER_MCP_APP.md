# RiftBrowser MCP App Compatibility

`rift-mcp-app-v2` lets RiftBrowser teach ChatGPT Web about the local Rift tool manifest while keeping tool execution inside RiftOS.

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

This browser path remains a compatibility fallback. The optional native relay transport is separate and does not expose additional JavaScript authority to the page.

## Protocol

At page startup, the browser adapter performs MCP `initialize` and `tools/list`. The returned tool schemas are the source of truth for the compact context shown to ChatGPT. When `rift_workspace_exec` is present, the adapter also supplies the bounded Rift Code Mode operation contract so the model can collapse many project operations into one local batch.

When a tool is required, ChatGPT is instructed to emit one strict JSON request. For project work it should prefer one Code Mode call containing many local operations:

```json
{"protocol":"rift-tools-v2","request_id":"request-1","calls":[{"id":"call-1","tool":"rift_workspace_exec","arguments":{"operations":[{"op":"search","path":"workspace","query":"RiftKernel"},{"op":"read_range","path":"workspace/src/riftcore.js","startLine":1,"endLine":220}]}}]}
```

V2 accepts up to eight sequential calls and returns one correlated JSON result packet. Cross-call execution is intentionally not atomic, so related project edits should be consolidated into one transactional `rift_workspace_exec` call. Legacy `<rift_call>` envelopes and single-operation tools remain available for compatibility.

The browser adapter validates calls against the live manifest and invokes MCP `tools/call`. V2 results return together as a correlated `[RIFT_TOOL_RESULT_V2]` continuation; legacy calls still receive `[RIFT_MCP_RESULT_V1]`. The adapter requires native responses to echo the same call ID and, for Rift AI tasks, the same private session ID before accepting them.

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

`riftbrowser-mcp-app.js` also supports the local Rift AI HTML cockpit. Native code hands work to synchronous `window.RiftMcpAppControl.queueTask(...)` with a native-created session ID, task and compact project context. The page-owned async pump waits for MCP/composer readiness, retries rejected clicks, and reports `submitted` only after ChatGPT accepts the outgoing message. There is no model API request path.

The model never supplies the AI session ID. When the adapter parses a model `<rift_call>` during an active Rift AI task, it privately adds `_meta["riftos/aiSessionId"]` plus `_meta["riftos/callId"]` to the local MCP `tools/call`. The server echoes both values in its private result metadata; the browser rejects mismatches or stale-session results. This makes working-tree journaling and result delivery specific to the active call without changing the model-visible tool schema.

The adapter mirrors cleaned assistant text and transport lifecycle back to Android as exact-origin `rift/ai/event` messages over `RiftMcpNative`. Tool envelopes are stripped from the mirrored assistant pane; the actual tool loop still occurs in ChatGPT Web and MCP results still return through the composer. Completion is reported only after output stabilizes, the stop control is gone, active tool round-trips have drained and any tool-result continuation has produced another assistant update. Stop requests likewise wait for active tool work to drain before the session becomes reviewable.

For Rift AI chat routing, the adapter also exposes a read-only `targets()` control that inspects same-origin ChatGPT links already rendered in the page and classifies chats, projects and project chats. It does not call ChatGPT account/backend APIs. `openTargetSearch()` only activates ChatGPT Web's own visible search control; older-history selection therefore remains inside the authenticated ChatGPT interface. Native code validates every selected URL back to exact HTTPS ChatGPT origins before navigation.

## Permissions

Read/write policy comes exclusively from `RiftToolHost` and is configured in the local **Rift MCP** system app. Read defaults on. Write defaults off. A browser request cannot bypass those settings.

## Failure behavior

If ChatGPT changes its composer or semantic message attributes, compatibility mode can stop detecting calls. The local MCP server, sandbox and permissions remain intact. The badge reports local MCP/tool-list failures instead of granting broader access.

## Autonomous tool loop v2

RiftBrowser treats each AI task as a correlated local state machine rather than a one-shot DOM scrape. After a model tool envelope is accepted, the adapter executes the local MCP call, injects a result carrying a unique `result_id`, waits until that exact result appears as a ChatGPT user-message turn, and only then accepts a newly-created assistant message as the continuation. Mutations to older assistant DOM nodes, thinking UI changes, and stale historical tool envelopes cannot complete the round trip.

Malformed JSON envelopes, duplicate/misused call IDs, and tool validation failures are returned to ChatGPT Web as recoverable `RIFT_MCP_RESULT_V1` messages with instructions to correct the call and retry with a new `call_id`; the user does not need to resend the task or type `continue`. `rift_workspace_exec` operations use canonical flat objects such as `{"op":"stat","path":"workspace/project"}`. The native host defensively normalizes the unambiguous shorthand `{"stat":{"path":"workspace/project"}}` *before* write-permission classification, AI journaling, audit logging, and sandbox execution.
