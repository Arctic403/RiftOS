# RiftOS MCP Architecture

## Goal

Provide ChatGPT Web with structured Rift tools while keeping tool execution, permissions, audit and storage local to RiftOS.

The native server has a local browser compatibility transport and an optional secure relay transport:

```text
ChatGPT Web
       |
       | Rift MCP compatibility context/result
       v
riftbrowser-mcp-app.js
       |
       | exact-origin WebMessage
       v
RiftBrowserMcpAppBridge
       |
       | in-process MCP JSON-RPC
       v
RiftMcpServer
       |
       v
RiftToolHost
       |
       v
RiftToolSandbox
       |
       v
filesDir/riftfs/workspace
```

## MCP server

`RiftMcpServer` is a small in-process JSON-RPC server. It has no listening socket or public endpoint. The current methods are:

- `initialize`
- `ping`
- `tools/list`
- `tools/call`

The browser adapter obtains tool definitions through `tools/list`; schemas are not duplicated in the page asset.

## Tool host

`RiftToolHost` is the canonical capability authority. It owns:

- tool names and JSON schemas,
- canonical mapping to sandbox operations,
- local read/write grants,
- bounded audit metadata,
- dispatch into `RiftToolSandbox`.

Current tools:

```text
rift_info
rift_stat
rift_list
rift_read_text
rift_write_text
rift_mkdir
rift_remove
rift_move
rift_copy
rift_audit
rift_scan
rift_project_export
rift_workspace_exec
```

Read defaults enabled. Write defaults disabled. `rift_workspace_exec` is always read-gated and becomes write-gated only when its operation list contains a mutation. Grants are changed through the local **Rift MCP** system app.

## Sandbox

The logical and physical MCP sandbox is the single canonical `riftfs/workspace` tree. A fresh workspace starts empty; RiftOS housekeeping and patch-history metadata are stored outside it. All filesystem tools, including the legacy one-operation tools, require paths under `workspace/`; an empty list path resolves to that root. RiftOS system roots, downloads, documents, SAF mounts and legacy transfer directories are outside the capability.

Historical `tool-sandbox/workspace` and `browser-sandbox/workspace` directories are migration inputs only. Unique entries are merged forward into `riftfs/workspace`; the old roots are never addressable by MCP.

`RiftToolSandbox` enforces canonical-path containment, an 8 MiB tool payload/file limit and bounded listings. Rift Code Mode caps each batch at 192 operations, bounds returned context, and skips oversized/binary files during local text search.


## Rift Code Mode

`rift_workspace_exec` is the large-project path. One model-visible MCP call carries an ordered declarative program that can perform many local operations:

```text
project  stat  list  search  read
write    replace  patch  mkdir  remove  move  rename  copy
```

This is intentionally not arbitrary JavaScript evaluated inside the `chatgpt.com` origin. The ChatGPT page receives only the declarative operation envelope; execution stays in the device-side sandbox. That avoids giving model-produced code access to ChatGPT DOM/session state while still collapsing many local filesystem actions into one ChatGPT↔Rift round trip.

Mutating batches use a lazy copy-on-write transaction in app cache. Only paths actually touched by the batch are copied. If any operation fails, the batch restores its mutations before returning an error. On success, the resulting working-tree edits remain subject to the normal Rift AI session journal and Accept all / Revert all review flow.

A model may set `finish:true` only on a mutating Code Mode batch that fully completes the current Rift AI task. Final batches are **not** silently swallowed: after local execution, RiftBrowser sends the correlated `[RIFT_MCP_RESULT_V1]` result back through ChatGPT Web. The result carries the model call ID plus the private AI session ID internally, and the task becomes reviewable only after the final assistant continuation completes. Failed, stale or mismatched results cannot terminate the active session as success.

The upfront project handoff is `RIFT_PROJECT_V2`: a bounded top-level descriptor that states full workspace reachability. For a complete offline audit, `rift_project_export` streams a deterministic `RIFT_PROJECT_EXPORT_V2` snapshot in pages capped below the relay limit. Each page contains UTF-8 source content, paths, full-file hashes and byte ranges. Callers continue with `nextCursor` and the first page's `snapshotId`; continuation fails if the project changes mid-export. Build outputs, binary assets and sensitive credential files are excluded, while large source files are split across pages.

After the audit, the model returns complete ordinary files or guarded patches as operations in one `rift_workspace_exec` call. The device applies that batch under one copy-on-write transaction: every operation commits together, or every touched path is restored. This is a local atomic change set, not an automatic Git commit.

## Rift AI working-tree journal

Rift AI journaling is session-scoped, not a global side effect of MCP. The model emits the same ordinary `<rift_call>` envelope as normal. While a Rift AI task is active, the browser adapter privately adds `_meta["riftos/aiSessionId"]` to the resulting local `tools/call`. `RiftMcpServer` passes that value to `RiftToolHost`, and `RiftAiJournal` tracks the call only when it matches the current active transport session. The session ID is not a model argument or tool-schema field.

Before each matching mutating tool (`rift_write_text`, `rift_mkdir`, `rift_remove`, `rift_move`) and each mutating `rift_workspace_exec` batch, the journal captures the original affected path(s). A capture failure blocks the mutation. Matching reads/list/stat calls are logged but do not create rollback copies. Normal MCP calls outside the active Rift AI transport remain fully usable and are not added to the AI rollback set.

Journal state is stored under `filesDir/rift-ai`, outside the MCP-visible `riftfs/workspace`. The shell can inspect additions/deletions, request a bounded unified-style text diff, accept the current files as a new baseline or revert the captured mutations. Accept/revert are rejected while transport is active, and a new AI session cannot replace unreviewed changes. This review layer does not add MCP authority and is not visible as a ChatGPT tool.

## Browser compatibility boundary

On ChatGPT plans without official custom MCP registration, `riftbrowser-mcp-app.js` provides a browser compatibility adapter. It:

1. calls MCP `initialize` and `tools/list` locally;
2. injects a compact tool manifest plus the local Rift Code Mode operation contract once per conversation route;
3. asks the model for one strict `<rift_call>...</rift_call>` envelope when a tool is required, preferring `rift_workspace_exec` for project work;
4. validates the call against the live manifest;
5. performs local `tools/call`;
6. returns `[RIFT_MCP_RESULT_V1]` through the normal ChatGPT composer.

The page never gets a general native object or direct sandbox API.

## Performance rule

Streaming responses generate many DOM mutations. The compatibility asset therefore processes only touched message nodes through a small delayed queue. It must not rescan all historical messages on every mutation. CI rejects the removed full-chat scanner.

## Secure relay transport

`RiftMcpRelayClient` is an optional outbound-only WSS transport. It is disabled by default, accepts only TLS endpoints, stores its bearer token through `RiftSecretStore`, reconnects with bounded backoff and passes received JSON-RPC to the existing `RiftMcpServer`. It does not implement tools or bypass `RiftToolHost` permissions.

The public relay service is deployed separately. Its contract is documented in `RIFT_MCP_RELAY_PROTOCOL.md`.

Legacy read/write/audit preferences are migrated into `rift-mcp-tools`, then the old preference file and pairing key are cleared.

## Security boundary

Not exposed to MCP tools:

- SAF/external mounts,
- clipboard,
- Android intents,
- notifications,
- secrets,
- general `RiftNativeDispatcher`,
- full RiftFS,
- shell/native execution.

MCP is a protocol adapter over `RiftToolHost`; it is not the internal RiftOS kernel API.
