# RiftOS Local MCP Architecture

## Goal

Provide ChatGPT Web with structured Rift tools while keeping tool execution, permissions, audit and storage local to RiftOS.

The active architecture has no remote Rift relay:

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
filesDir/riftfs/tool-sandbox
```

## MCP server

`RiftMcpServer` is a small in-process JSON-RPC server. It has no HTTP listener, WebSocket listener or public endpoint. The current methods are:

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
```

Read defaults enabled. Write defaults disabled. Grants are changed through the local **Rift MCP** system app.

## Sandbox

The logical sandbox is `riftfs/tool-sandbox` with standard `workspace`, `uploads` and `downloads` directories.

The first local-MCP build migrates existing alpha data from the historical `riftfs/browser-sandbox` directory. That old directory name is migration input only, not the active namespace.

`RiftToolSandbox` enforces canonical-path containment, an 8 MiB tool payload/file limit and a 5000-entry listing limit.

## Browser compatibility boundary

On ChatGPT plans without official custom MCP registration, `riftbrowser-mcp-app.js` provides a browser compatibility adapter. It:

1. calls MCP `initialize` and `tools/list` locally;
2. injects a compact tool manifest once per conversation route;
3. asks the model for one strict `<rift_call>...</rift_call>` envelope when a tool is required;
4. validates the call against the live manifest;
5. performs local `tools/call`;
6. returns `[RIFT_MCP_RESULT_V1]` through the normal ChatGPT composer.

The page never gets a general native object or direct sandbox API.

## Performance rule

Streaming responses generate many DOM mutations. The compatibility asset therefore processes only touched message nodes through a small delayed queue. It must not rescan all historical messages on every mutation. CI rejects the removed full-chat scanner.

## Removed remote architecture

The following have been deleted from the active source/build:

- `services/rift-mcp-relay`,
- `.github/workflows/rift-mcp-relay.yml`,
- `RiftMcpRelayClient`,
- `RiftMcpInitProvider`,
- `RiftMcpBridgeActivity`,
- `riftbridge-system.js`,
- WSS device protocol/pairing configuration,
- pairing-key MCP endpoint URLs.

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
