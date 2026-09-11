# RiftOS Local MCP Mode

RiftOS keeps the MCP runtime, permissions, tool implementations, and workspace access in
the native Android layer.

The ChatGPT Web page receives only a narrow JavaScript connector. It:

- initializes the native MCP endpoint;
- reads the native tool manifest;
- detects Rift tool calls in the chat;
- forwards calls through `RiftMcpNative`;
- writes bounded tool results back to the chat.

The connector does not implement filesystem tools, store credentials, call a model API,
or connect to a remote relay. `RiftMcpServer`, `RiftToolHost`, and `RiftToolSandbox`
remain the capability and permission boundary.
