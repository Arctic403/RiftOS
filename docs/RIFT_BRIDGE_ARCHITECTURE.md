# Rift Bridge Architecture

Rift Bridge is the supported AI-tool integration layer for RiftOS Android.

## Design goal

RiftOS owns one native capability boundary. MCP, browser compatibility and future tunnel protocols are adapters to that boundary; they are not the operating-system API itself.

```text
                         Rift Tool Host
                      /        |        \
                     /         |         \
          RiftBrowser MCP   Remote MCP   future adapters
             App adapter      relay
                 |              |
          ChatGPT Web      ChatGPT custom app
```

All adapters share the same tool schemas, device-side permissions, sandbox implementation and local audit log.

## Local MCP core

`RiftMcpServer` is an in-process MCP JSON-RPC server. It intentionally opens no listening socket. An adapter sends `initialize`, `tools/list` and `tools/call` requests to it in memory.

The canonical tools are:

- `rift_info`
- `rift_stat`
- `rift_list`
- `rift_read_text`
- `rift_write_text`
- `rift_mkdir`
- `rift_remove`
- `rift_move`

`RiftToolHost` maps those tools to the app-private `RiftBridgeSandbox` after applying policy.

## Device authority

The Android device decides which capability families are enabled. The current alpha exposes two grants:

- **Sandbox read**: `rift_info`, `rift_stat`, `rift_list`, `rift_read_text`
- **Sandbox write**: `rift_write_text`, `rift_mkdir`, `rift_remove`, `rift_move`

Write access is disabled by default. No adapter can override these grants.

The bridge scope is deliberately limited to `filesDir/riftfs/browser-sandbox`. It does not expose SAF mounts, clipboard, Android intents, notifications, secrets or the wider `RiftNativeDispatcher` surface.

## RiftBrowser MCP App compatibility

On `https://chatgpt.com` and `https://www.chatgpt.com` only, RiftBrowser installs `rift-mcp-app-v1`.

The native page bridge exposes only MCP JSON-RPC to `RiftMcpServer`; it does not expose the filesystem or the general Rift Android API directly. The browser adapter obtains the live manifest with `tools/list`, adds that manifest to outgoing ChatGPT context, recognizes one strict `<rift_call>...</rift_call>` envelope, invokes `tools/call`, and feeds a structured result back into the conversation.

This is a compatibility adapter for ChatGPT plans that cannot register a custom MCP app. It is not an official server-side ChatGPT MCP registration. Because ChatGPT Web does not expose a supported local-tool API on those plans, the compatibility boundary still depends on the rendered ChatGPT composer/message surface. Unlike the removed Agent V1/V2/V3 design, it does not use fenced `rift-tool` blocks, response-node identity, hidden native filesystem globals or the old Agent protocol.

A visible `Rift MCP` badge reports the local manifest count and can disable the compatibility adapter for the current tab.

## Remote adapter

`services/rift-mcp-relay` remains an optional adapter for ChatGPT accounts/workspaces that can register a real remote MCP app. The Android relay client now owns transport only; it routes every incoming tool request through the same `RiftToolHost` used by RiftBrowser.

The remote adapter still uses an outbound paired `wss://` device connection in the current alpha. Its pairing key is stored using Android Keystore-backed `RiftSecretStore`.

## Audit

Rift Tool Host keeps a bounded local activity log containing timestamp, canonical tool name, target path or move source/destination, success/failure and error text when applicable.

The audit log intentionally does not store file contents or write payloads.

## Security boundary

RiftBrowser page code never receives `RiftNativeDispatcher`, SAF mounts, secrets or arbitrary Android intents. The only ChatGPT-origin native object is the exact-origin `RiftMcpNative` message endpoint, and its requests terminate at the MCP server and Tool Host policy layer.

The removed DOM Agent asset must not be restored as a fallback.
