# RiftOS MCP Relay

This service is the remote MCP adapter for the app-private RiftBrowser sandbox.
ChatGPT connects to the MCP endpoint; RiftOS keeps an outbound WebSocket open to
`/device`; tool calls are forwarded to the phone and results are returned to the
MCP client.

## Security model

- The Android side exposes only `riftfs/browser-sandbox`.
- No SAF mounts, Android intents, clipboard, secrets, or wider RiftFS APIs are exposed.
- The phone initiates the WSS connection; no inbound port is opened on Android.
- A 24-128 character base64url pairing key gates both the MCP path and device socket.
- The key-in-URL design is intentionally an alpha/developer-mode mechanism. Use a
  private HTTPS deployment, rotate the key if it is exposed, and add OAuth before
  treating the relay as multi-user infrastructure.

## Run

```bash
export RIFT_PAIRING_KEY='generate-a-long-random-base64url-key'
export PORT=8787
npm install
npm start
```

Expose the service behind HTTPS/WSS. If the public host is
`https://rift.example`, configure Android with:

```text
wss://rift.example/device
```

and configure the ChatGPT custom app MCP endpoint as:

```text
https://rift.example/mcp/<same-pairing-key>
```

The Android **Rift MCP Bridge** activity can generate a pairing key and shows the
exact ChatGPT endpoint after configuration.

## Exposed tools

- `rift_info`
- `rift_stat`
- `rift_list`
- `rift_read_text`
- `rift_write_text`
- `rift_mkdir`
- `rift_remove`
- `rift_move`

The relay is deliberately single-device for the alpha. A newer device connection
replaces the previous one.
