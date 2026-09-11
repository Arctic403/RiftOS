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

## Chat target picker

Rift AI chooses where a task runs before starting the local review session. The picker supports:

- **New chat** — loads `https://chatgpt.com/` and starts a clean conversation.
- **Current** — uses the ChatGPT page already open in RiftBrowser.
- **Chat** — navigates to an existing conversation discovered from the currently rendered ChatGPT Web DOM.
- **Project** — navigates to a discovered ChatGPT Project and starts a new chat in that project.
- **Project chat** — continues a discovered existing conversation inside a project.

Target discovery is intentionally browser-native: `riftbrowser-mcp-app.js` inspects same-origin ChatGPT links already present in the rendered page and returns a bounded, ephemeral list to the RiftOS shell. RiftOS does not call an undocumented/private ChatGPT backend endpoint to enumerate account history. Chat titles and conversation/project URLs are routing data only and are not persisted in `RiftAiJournal`.

ChatGPT can virtualize or omit older destinations from the sidebar DOM. **Browse all…** therefore reveals the same authenticated ChatGPT WebView and asks ChatGPT's own search control to open. The user can select any older chat/project there, return to Rift AI, refresh targets, and choose **Current**. This preserves the ChatGPT-Web-only model/identity boundary instead of cloning ChatGPT account history into RiftOS.

## Session flow

Starting a task:

1. creates a persistent Rift AI session under app-private storage;
2. refuses to start if the previous task is still running;
3. refuses to start if the previous session still has unreviewed changes;
4. builds a compact top-level `RIFT_PROJECT_V2` descriptor for `RiftFS/workspace` without recursively dumping a large project;
5. resolves and validates the selected ChatGPT Web target, rejecting any target outside `https://chatgpt.com` / `https://www.chatgpt.com`;
6. waits for local MCP initialization and a usable ChatGPT composer before submitting anything;
7. submits the task plus compact project context, the live MCP tool manifest and the Rift Code Mode operation contract;
8. mirrors cleaned assistant output and structured transport/tool events into RiftOS;
9. tracks only Rift-AI-owned MCP mutations in the local working-tree journal;
10. ends in review, stopped or error state and releases hidden transport rendering when appropriate.

The project descriptor is capability context, not a project dump. The full `workspace/` remains reachable through `rift_workspace_exec`. ChatGPT can combine `project`, `stat`, `list`, `search`, `read`, `write`, `replace`, `patch`, `mkdir`, `remove`, `move`, `rename` and `copy` operations into one model-visible tool call. All operations execute locally in `RiftToolSandbox`; its logical `workspace/` is mapped directly onto the same canonical `filesDir/riftfs/workspace` tree used by Files and RiftDev. Only the bounded batch result is returned to ChatGPT Web.

A Code Mode batch is transactionally protected inside the sandbox. Mutating paths are copied lazily into an app-cache rollback set immediately before their first batch mutation. If any operation fails, all mutations made by that batch are restored before the error reaches ChatGPT. Successful AI-scoped mutations remain covered by the persistent `RiftAiJournal` review baseline until the user accepts or reverts the session.

For tasks where the model already knows the exact final edits, `rift_workspace_exec` also supports `finish:true`. RiftBrowser honors it only after a successful AI-scoped mutating batch with confirmed mutation targets. That fast path ends hidden ChatGPT transport locally and moves directly to review instead of spending another model turn returning a success packet. Inspection batches, read-only tasks and failures continue through the normal result/continuation loop.

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

Before an AI-scoped `rift_write_text`, `rift_mkdir`, `rift_remove`, `rift_move`, or mutating `rift_workspace_exec` batch, `RiftToolHost` asks the journal to lazily snapshot the affected path(s). Read-only Code Mode batches require only the read grant and do not create rollback copies; a batch containing `write`, `replace`, `patch`, `mkdir`, `remove`, or `move` additionally requires the local write grant. An existing ancestor snapshot suppresses redundant descendant copies; if a parent is captured after an earlier child change, the earlier child snapshot is retained so revert still reconstructs the pre-session baseline.

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
ai.targets
ai.targetSearch
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
