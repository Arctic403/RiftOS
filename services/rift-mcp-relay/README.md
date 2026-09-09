# Rift Bridge MCP Relay

This service is the first remote adapter for the Rift Bridge system app. ChatGPT connects to the MCP endpoint; RiftOS keeps an outbound WebSocket open to `/device`; tool calls are forwarded to the phone and results are returned to the MCP client.

RiftOS itself is not MCP internally. MCP is only the external adapter protocol.

## Security model

- The Android side exposes only `riftfs/browser-sandbox`.
- Device-side Rift Bridge read/write grants are authoritative.
- No SAF mounts, Android intents, clipboard, secrets, notifications or wider RiftFS APIs are exposed.
- The phone initiates the WSS connection; no inbound port is opened on Android.
- A 24-128 character base64url pairing key gates both the MCP path and device socket.
- The pairing-key-in-URL design is an alpha/developer-mode mechanism. Use a private HTTPS deployment, rotate the key if exposed, and add OAuth before treating the relay as multi-user infrastructure.

## Run

```bash
export RIFT_PAIRING_KEY='generate-a-long-random-base64url-key'
export PORT=8787
npm install
npm start
```

Expose the service behind HTTPS/WSS. If the public host is `https://rift.example`, configure Rift Bridge with:

```text
wss://rift.example/device
```

and configure the ChatGPT custom app MCP endpoint as:

```text
https://rift.example/mcp/<same-pairing-key>
```

The **Rift Bridge** system app can generate a pairing key, shows the exact ChatGPT endpoint, controls read/write grants and displays recent tool activity.

## Exposed tools

- `rift_info`
- `rift_stat`
- `rift_list`
- `rift_read_text`
- `rift_write_text`
- `rift_mkdir`
- `rift_remove`
- `rift_move`

The relay is deliberately single-device for the alpha. A newer device connection replaces the previous one.
