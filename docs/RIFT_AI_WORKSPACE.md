# Rift AI Workspace

## Purpose

Rift AI is the visible HTML workspace for AI-assisted project work inside RiftOS. It does **not** call the OpenAI API or any other model API. The only model transport is the authenticated `chatgpt.com` page already hosted by RiftBrowser.

The design separates the user interface from the model transport:

```text
RiftOS shell WebView
  |
  `-- Rift AI HTML workspace
       |-- project tree
       |-- assistant output
       |-- live structured logs
       `-- local changes / diff / accept / revert

hidden native RiftBrowser WebView
  |
  `-- authenticated ChatGPT Web
       |
       `-- riftbrowser-mcp-app.js
            |
            `-- exact-origin RiftMcpNative WebMessage
                 |
                 `-- RiftMcpServer -> RiftToolHost -> RiftToolSandbox
```

There is one ChatGPT WebView. Rift AI does not create a second browser renderer. In normal AI mode that existing ChatGPT WebView stays laid out and alive but Android marks it `INVISIBLE`, allowing the ChatGPT composer, streaming response DOM and local MCP loop to keep working while RiftOS HTML remains the only visible interface.

## ChatGPT Web-only invariant

Rift AI has no model endpoint or credential path. Active source must not contain an OpenAI API URL, API-key field, bearer-token model request or alternate model transport. CI checks the packaged Rift AI module for this invariant.

The **Show ChatGPT** action only reveals the same authenticated WebView for sign-in, account state or debugging. Closing that browser window returns an active AI session to hidden transport mode rather than destroying the WebView.

## Session flow

Starting a task:

1. creates a local Rift AI session under app-private storage;
2. builds a compact project tree for `tool-sandbox/workspace`;
3. opens a fresh `https://chatgpt.com/` conversation in the existing hidden RiftBrowser WebView;
4. submits the user's task plus compact project context and the live MCP tool manifest;
5. mirrors assistant output into the Rift AI HTML pane;
6. records structured transport/tool events locally;
7. journals every MCP mutation before it changes a project path.

The project tree is context, not a project dump. ChatGPT is instructed to use `rift_list` and `rift_read_text` selectively, so large projects do not need to be copied into the prompt.

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

This directory is outside `filesDir/riftfs/tool-sandbox`, so MCP tools cannot rewrite their own rollback history.

Before `rift_write_text`, `rift_mkdir`, `rift_remove` or `rift_move`, `RiftToolHost` asks the journal to lazily snapshot the affected path. Reads are logged but do not create snapshots. Only changed targets are copied; the full project is never cloned for a normal session.

A snapshot failure blocks the mutation rather than allowing an unreviewable edit. Snapshot size is bounded. Diff generation is text-oriented and bounded; large/binary files are reported without forcing a large text diff.

## Review

The Changes panel exposes the local working tree. Selecting a changed path requests a unified-style diff from Android. The current checkpoint supports session-wide:

- **Accept all** — discard rollback snapshots and keep current project files as the new baseline;
- **Revert all** — restore captured originals and remove paths that did not exist before the session mutation.

The journal is persistent across process restarts because session metadata, event logs and originals are stored on disk.

## Logs and assistant output

The ChatGPT compatibility asset emits exact-origin `rift/ai/event` messages over the existing `RiftMcpNative` WebMessage channel. Android accepts this method as telemetry only; it is not forwarded to MCP.

Assistant streaming text is stored as the latest output snapshot instead of appending every token to the event log. Tool start/finish events come from `RiftToolHost`, which records tool name, target and status without writing file contents into the log.

The Rift AI HTML workspace polls these local state files through `ai.*` native commands. Normal polling reads state/events frequently, while project-tree and change scans run less often to keep the shell lightweight.

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

These commands are local MainActivity capabilities. They do not expose a remote listener and are not MCP tools available to ChatGPT.
