# RiftOS Local MCP Mode

This build removes the ChatGPT Web injection path.

Kept:
- RiftMcpServer
- RiftToolHost
- RiftToolSandbox
- local workspace tools

Removed:
- ChatGPT page injection
- composer automation
- browser chat relay

Target architecture:

RiftBrowser -> local MCP runtime -> RiftSandbox -> tools

Future AI clients should connect through a real MCP transport instead of controlling the browser page.
