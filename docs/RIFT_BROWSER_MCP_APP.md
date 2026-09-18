# RiftBrowser MCP App Compatibility

## Status

The native/page compatibility boundary is source-audited in `docs/systems/browser/mcp-compat/README.md`. Native MCP server/tool/sandbox subsystems remain independently audited.

## Flow

```text
allowlisted AI HTTPS main frame
 -> riftbrowser-mcp-app.js
 -> exact-origin RiftMcpNative WebMessage
 -> RiftBrowserMcpAppBridge
 -> RiftMcpServer
 -> RiftToolHost
 -> RiftToolSandbox / fixed native tool owners
```

The browser adapter performs MCP initialize + tools/list, stages a compact capability context after a new user turn, parses one raw `[RIFT_CALL]` block from the newest non-historical assistant message, validates the tool against the live manifest, then invokes native `tools/call`.

Tool results are correlated with `riftos/callId`, bounded, and staged as `[RIFT_RESULT]` text.

## User boundary

The compatibility layer never clicks Send. Capability context and tool-result continuations are staged into the visible composer for explicit user submission.

## Security

The page gets only MCP JSON-RPC, never raw RiftFS/shell/native-dispatcher authority. Read/write policy is enforced by native RiftToolHost and sandbox.

Supported origin rules cover the audited ChatGPT, GitHub/Copilot, Gemini/Google and Claude HTTPS origins listed in the subsystem README.
