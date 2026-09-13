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

When a tool is required, ChatGPT is instructed to emit one bounded raw command block. For project work it should prefer one Code Mode call containing many local operations:

```text
[RIFT_CALL]
call call-1 rift_workspace_exec
set operations.0.op search
set operations.0.path workspace
set operations.0.query RiftKernel
set operations.1.op read_range
set operations.1.path workspace/src/riftcore.js
set operations.1.startLine 1
set operations.1.endLine 220
[RIFT_END]
```

`set` supports dotted object paths, numeric array indexes and heredocs for multiline source. Related edits should be consolidated into one transactional `rift_workspace_exec` call because one Code Mode batch provides the atomic mutation boundary.

The browser adapter validates the parsed call against the live manifest and invokes native MCP `tools/call`. Native MCP carries the unique `riftos/callId` correlation value and the browser rejects a mismatched result. Results are formatted as bounded `[RIFT_RESULT]` text and staged into the visible composer; the adapter does not click Send.

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

## Result delivery boundary

The browser compatibility asset has no hidden Rift AI task controller, target picker, session lifecycle, AI event channel or persistent mutation journal. Those retired paths are not part of current RiftOS.

When a tool finishes, the adapter formats one bounded `[RIFT_RESULT]` message and writes it into the currently visible AI-site composer. It deliberately does **not** click the site's send control. The user remains the explicit send boundary for browser-compatibility result continuations. The optional native MCP relay does not use this composer path at all.

## Permissions

Read/write policy comes exclusively from `RiftToolHost` and is configured in the local **Rift MCP** system app. Read defaults on. Write defaults off. A browser request cannot bypass those settings.

## Failure behavior

If ChatGPT changes its composer or semantic message attributes, compatibility mode can stop detecting calls. The local MCP server, sandbox and permissions remain intact. The badge reports local MCP/tool-list failures instead of granting broader access.

## Autonomous tool loop v2

RiftBrowser treats each AI task as a correlated local state machine rather than a one-shot DOM scrape. After a model tool envelope is accepted, the adapter executes the local MCP call, injects a result carrying a unique `result_id`, waits until that exact result appears as a ChatGPT user-message turn, and only then accepts a newly-created assistant message as the continuation. Mutations to older assistant DOM nodes, thinking UI changes, and stale historical tool envelopes cannot complete the round trip.

Malformed raw command blocks, duplicate/misused call IDs, and tool validation failures are returned to ChatGPT Web as recoverable `[RIFT_RESULT]` messages with instructions to correct the call and retry with a new call ID; the user does not need to resend the task or type `continue`. Inside `rift_workspace_exec`, operations remain canonical flat objects such as `{"op":"stat","path":"workspace/project"}` after raw dotted-path parsing. The native host defensively normalizes the unambiguous shorthand `{"stat":{"path":"workspace/project"}}` *before* write-permission classification, AI journaling, audit logging, and sandbox execution.
