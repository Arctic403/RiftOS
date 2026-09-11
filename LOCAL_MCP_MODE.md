# RiftOS MCP Transports

RiftOS keeps the MCP runtime, permissions, tool implementations, and workspace access in
the native Android layer.

The ChatGPT Web page receives only a narrow JavaScript connector. It:

- initializes the native MCP endpoint;
- reads the native tool manifest;
- detects Rift tool calls in the chat;
- forwards calls through `RiftMcpNative`;
- writes bounded tool results back to the chat.

The connector does not implement filesystem tools, store credentials or call a model API.
For compatibility it can still use the existing browser composer path. When the user enables
the secure relay, `RiftMcpRelayClient` opens an outbound WSS connection and carries the same
MCP JSON-RPC messages without page automation. `RiftMcpServer`, `RiftToolHost`, and
`RiftToolSandbox` remain the capability and permission boundary for both transports.
