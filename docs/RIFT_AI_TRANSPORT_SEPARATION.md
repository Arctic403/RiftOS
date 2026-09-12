# RiftOS AI Transport Separation

RiftOS keeps AI entry points as independent transports.

## MCP transport

ChatGPT Web:

ChatGPT Web
→ MCP connection
→ Rift MCP app/bridge
→ RiftMcpServer
→ RiftToolHost
→ RiftOS native tools

## Browser injector transport

Other AI websites/browser integrations:

Injected JS
→ riftbrowser-mcp-app.js
→ Android bridge
→ RiftOS native tools

## Design rule

The injector is not an MCP replacement and does not route through the MCP server. MCP and injector remain sibling clients of RiftOS capabilities.

Shared layers:
- tool permissions
- sandbox
- audit logging
- filesystem APIs

Separated layers:
- transport protocol
- session state
- message routing
- client-specific adapters
