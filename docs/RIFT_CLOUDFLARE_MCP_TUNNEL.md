# Rift Cloud MCP Tunnel

Architecture:

```
ChatGPT MCP Connector
        |
        v
Cloudflare Worker Relay
        |
        | WebSocket
        v
Android RiftBrowser
        |
        v
RiftMcpServer
```

The Android client makes an outbound connection. No inbound ports are exposed.

Relay requirements:
- WebSocket upgrade
- device authentication
- MCP JSON-RPC forwarding
- session routing

ChatGPT connector points to the relay MCP endpoint.

Next step:
Implement Cloudflare Worker Durable Object relay.
