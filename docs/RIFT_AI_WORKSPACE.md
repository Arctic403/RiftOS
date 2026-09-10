# Rift AI Workspace

## Purpose

Rift AI is the visible HTML workspace for AI-assisted project work inside RiftOS. The only model transport is the authenticated `chatgpt.com` page already hosted by RiftBrowser. Rift AI has no OpenAI API endpoint, API-key flow, bearer-token model request, alternate model client, remote MCP relay or second AI WebView.

The visible workspace and the model transport are deliberately separate:

```text
RiftOS shell WebView
  |
  `-- Rift AI HTML workspace
       |-- project tree
       |-- assistant output
       |-- live structured logs
       `-- local changes / diff / accept / revert

native RiftBrowser WebView
  |
  `-- authenticated ChatGPT Web (INVISIBLE while AI transport is active)
       |
       `-- riftbrowser-mcp-app.js
            |
            `-- exact-origin RiftMcpNative WebMessage
                 |
                 `-- RiftMcpServer -> RiftToolHost -> RiftToolSandbox
```

There is one ChatGPT WebView. During an AI task it remains attached and fully laid out, but Android marks it `INVISIBLE`. The composer, response stream and MCP loop therefore continue running while the RiftOS shell remains the only visible AI interface.

## ChatGPT Web-only invariant

Rift AI never talks to a model endpoint directly. Task submission, tool-result continuations and assistant responses all pass through the normal authenticated ChatGPT Web page.

**Show ChatGPT** reveals that same WebView for sign-in, account state or debugging. It is not a second transport. If the user closes the visible browser while a task is still active, RiftBrowser returns the same WebView to invisible transport mode. Once the task reaches a terminal state and the WebView is not being shown manually, the hidden renderer is released to `GONE` instead of being kept alive indefinitely.

CI checks the active and packaged Rift AI/browser sources for model-API endpoint/key patterns and rejects the discarded second-WebView design.

## Session flow

Starting a task:

1. creates a persistent Rift AI session under app-private storage;
2. refuses to start if the previous task is still running;
3. refuses to start if the previous session still has unreviewed changes;
4. builds a compact recursive project tree for `tool-sandbox/workspace`;
5. opens a fresh `https://chatgpt.com/` conversation in the existing RiftBrowser WebView;
6. waits for local MCP initialization and a usable ChatGPT composer before submitting anything;
7. submits the task plus compact project context and the live MCP tool manifest;
8. mirrors cleaned assistant output and structured transport/tool events into RiftOS;
9. tracks only Rift-AI-owned MCP mutations in the local working-tree journal;
10. ends in review, stopped or error state and releases hidden transport rendering when appropriate.

The project tree is context, not a project dump. ChatGPT uses the ordinary `rift_list`, `rift_stat` and `rift_read_text` tools to inspect only the paths required for the task.

## Session-scoped MCP writes

Rift AI does not trust the model to identify an AI session. The browser compatibility adapter owns the current session ID internally. After parsing a model-produced `<rift_call>`, the adapter adds:

```text
_meta["riftos/aiSessionId"]
```

to the local MCP `tools/call` request. The session ID is not part of the model-visible tool schema and is not taken from model output.

`RiftMcpServer` passes that internal metadata to `RiftToolHost`. `RiftAiJournal` accepts tool events and mutation snapshots only when the ID matches the currently active Rift AI transport session. Normal MCP calls made from an ordinary visible ChatGPT conversation remain valid, but they are not folded into an old Rift AI rollback session.

## Working-tree journal

`RiftAiJournal` stores rollback state under:

```text
filesDir/rift-ai/sessions/<session-id>/
  meta.json
  events.jsonl
  assistant.json
  snapshots.json
  originals/
```

This directory is outside `filesDir/riftfs/tool-sandbox`, so ChatGPT cannot rewrite its own rollback history through MCP.

Before an AI-scoped `rift_write_text`, `rift_mkdir`, `rift_remove` or `rift_move`, `RiftToolHost` asks the journal to lazily snapshot the affected path. Reads are logged but do not create rollback copies. An existing ancestor snapshot suppresses redundant descendant copies; if a parent is captured after an earlier child change, the earlier child snapshot is retained so revert still reconstructs the pre-session baseline.

A snapshot failure blocks the mutation rather than allowing an unreviewable edit. Snapshot size is bounded. Text diff generation is also bounded, and large/binary files are reported without forcing their contents into a huge diff.

## Review and lifecycle locking

The Changes panel is a local Git-style working-tree view. Changed text files report additions/deletions and expose a bounded unified-style diff.

Current review actions are session-wide:

- **Accept all** keeps the current project files and discards rollback snapshots.
- **Revert all** restores captured originals and removes paths that did not exist before the AI mutation.

Accept and Revert are blocked while ChatGPT transport is still active. This prevents the review baseline from being cleared while a later tool call could still mutate the project. A new task is also blocked until the current task is terminal and any pending changes have been accepted or reverted.

Session metadata, event logs, latest assistant output and rollback originals persist across Android process restarts. Because a killed process cannot preserve a live ChatGPT transport, startup converts any persisted `transportActive=true` session to `interrupted`/inactive while preserving its pending changes for review.

## Completion detection

The ChatGPT Web adapter tracks the active AI transport lifecycle independently from the persistent session record. It does not mark a task complete merely because a session exists.

A terminal completion is emitted only after assistant output has been observed, the response has stabilized, ChatGPT is no longer showing its stop control, no local tool round-trip is active, and any tool-result continuation has produced a subsequent assistant update. Stop requests remain in a stopping state until active tool work has drained.

Terminal phases are `complete`, `stopped` and `error`. Android records the terminal state and can release the invisible ChatGPT WebView while retaining the session for review.

## Logs and assistant output

The ChatGPT compatibility asset emits exact-origin `rift/ai/event` messages over the existing `RiftMcpNative` WebMessage channel. Android accepts that method as local telemetry only; it is not forwarded as an MCP method.

Assistant streaming text is stored as the latest output snapshot instead of appending every token to `events.jsonl`. `RiftToolHost` records tool name, target, phase and status without storing file contents in the activity log.

The Rift AI HTML workspace polls lightweight state/events. Project-tree and Changes scans refresh on relevant mutation/review/session events or explicit Refresh, rather than rescanning the project continuously.

## Native commands

The shell-facing command surface is:

```text
ai.start
ai.state
ai.events
ai.tree
ai.changes
ai.diff
ai.accept
ai.revert
ai.showWeb
ai.hideWeb
ai.stop
```

These are local `MainActivity` capabilities for the trusted RiftOS shell. They are not MCP tools exposed to ChatGPT and they do not create a remote listener.
