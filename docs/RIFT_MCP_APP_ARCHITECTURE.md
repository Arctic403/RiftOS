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

The browser adapter obtains tool definitions through `tools/list`; schemas are not duplicated in the page asset. The server fingerprints the live tool manifest into `serverInfo.version` and returns the manifest hash/count in MCP metadata. The tool catalog is static for a running RiftOS process, so the server correctly advertises `tools.listChanged=false`. `rift_info` also returns the authoritative MCP manifest so a stale client scan can be diagnosed through a tool that has existed since the original connector. Remote clients may cache their own action catalog; after a RiftOS upgrade, refresh/rescan the client app actions when its exposed count differs from the device manifest count.

## Tool host

`RiftToolHost` is the canonical capability authority. It owns:

- tool names and JSON schemas,
- canonical mapping to sandbox operations,
- local read/write grants,
- bounded audit metadata,
- dispatch into `RiftToolSandbox`.

Current tools (18 total):

```text
rift_shell_exec
rift_info
rift_stat
rift_hash
rift_list
rift_read_text
rift_write_text
rift_mkdir
rift_remove
rift_move
rift_copy
rift_archive
rift_extract
rift_audit
rift_scan
rift_project_export
rift_workspace_diff
rift_workspace_exec
```

Read defaults enabled. Write defaults disabled. `rift_workspace_diff` is read-only and exposes bounded private workspace records/checkpoint diffs. `rift_workspace_exec` is always read-gated and becomes write-gated only when its operation list contains a mutation. Grants are changed through the local **Rift MCP** system app.

## Sandbox

The logical and physical MCP sandbox is the single canonical `riftfs/workspace` tree. A fresh workspace starts empty; RiftOS housekeeping and patch-history metadata are stored outside it. All filesystem tools, including the legacy one-operation tools, require paths under `workspace/`; an empty list path resolves to that root. RiftOS system roots, downloads, documents, SAF mounts and legacy transfer directories are outside the capability.

Historical `tool-sandbox/workspace` and `browser-sandbox/workspace` directories are migration inputs only. Unique entries are merged forward into `riftfs/workspace`; the old roots are never addressable by MCP.

`RiftToolSandbox` enforces canonical-path containment, an 8 MiB tool payload/file limit and bounded listings. Rift Code Mode caps each batch at 192 operations, bounds returned context, and skips oversized/binary files during local text search.


## Rift Code Mode

`rift_workspace_exec` is the large-project path. One model-visible MCP call carries an ordered declarative program that can perform many local operations:

```text
project  snapshot  stat  hash  list  search  symbols  references
read  read_range  read_symbol  write  replace  patch  patch_range
apply_hunks  mkdir  remove  move  rename  copy  archive  extract
```

This is intentionally not arbitrary JavaScript evaluated inside the `chatgpt.com` origin. The ChatGPT page receives only the declarative operation envelope; execution stays in the device-side sandbox. That avoids giving model-produced code access to ChatGPT DOM/session state while still collapsing many local filesystem actions into one ChatGPT↔Rift round trip.

Mutating batches use a lazy copy-on-write transaction in app cache. Only paths actually touched by the batch are copied. If any operation fails, the batch restores its mutations before returning an error. On success, the committed workspace state remains in place; there is no separate persistent AI-session review journal.

A caller may set `finish:true` only on a mutating Code Mode batch intended to complete the requested change. `finish` is result metadata, not a hidden task/session lifecycle. In browser compatibility mode the correlated `[RIFT_RESULT]` is staged in the visible composer for explicit user send; relay/registered MCP clients receive normal MCP results directly. Native metadata carries only the private call ID used for result correlation.

The upfront project handoff is `RIFT_PROJECT_V2`: a bounded top-level descriptor that states full workspace reachability. For a complete offline audit, `rift_project_export` streams a deterministic `RIFT_PROJECT_EXPORT_V2` snapshot in pages capped below the relay limit. Each page contains UTF-8 source content, paths, full-file hashes and byte ranges. Callers continue with `nextCursor` and the first page's `snapshotId`; continuation fails if the project changes mid-export. Build outputs, binary assets and sensitive credential files are excluded, while large source files are split across pages.

After the audit, the model returns complete ordinary files or guarded patches as operations in one `rift_workspace_exec` call. The device applies that batch under one copy-on-write transaction: every operation commits together, or every touched path is restored. This is a local atomic change set, not an automatic Git commit.

## Mutation safety

Persistent AI-session journaling has been removed. Mutation safety is provided by the actual active layers: local read/write grants in `RiftToolHost`, workspace containment and limits in `RiftToolSandbox`, copy-on-write rollback for one transactional `rift_workspace_exec` batch, expected snapshot/hash guards, bounded audit metadata, and explicit Git/source-control workflows when durable history is required.

## Browser compatibility boundary

On ChatGPT plans without official custom MCP registration, `riftbrowser-mcp-app.js` provides a browser compatibility adapter. It:

1. calls MCP `initialize` and `tools/list` locally;
2. injects a compact tool manifest plus the local Rift Code Mode operation contract once per conversation route;
3. asks the model for one bounded `[RIFT_CALL]` / `[RIFT_END]` block when a tool is required, preferring `rift_workspace_exec` for project work;
4. parses dotted-path arguments and validates the call against the live manifest;
5. performs local `tools/call` over private MCP JSON-RPC;
6. returns a bounded `[RIFT_RESULT]` continuation through the normal ChatGPT composer.

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
- raw Android/Linux shell execution.

`rift_shell_exec` is intentionally narrower: it only dispatches commands through the existing RiftShell runtime and does not expose a raw Android shell.

MCP is a protocol adapter over `RiftToolHost`; it is not the internal RiftOS kernel API.
