# Rift Bridge Architecture

Rift Bridge is the supported AI-tool integration layer for RiftOS Android.

## Design goal

RiftOS owns a native capability boundary. External AI protocols are adapters to that boundary, not the operating-system API itself.

```text
ChatGPT custom app
        |
        | MCP / HTTPS
        v
Remote Rift Bridge adapter
        |
        | paired WSS
        v
Rift Bridge system app
        |
        | device-side grants + audit
        v
Rift tool host
        |
        v
riftfs/browser-sandbox
```

## Device authority

The Android device decides which capability families are enabled. The current alpha exposes two grants:

- **Sandbox read**: `info`, `stat`, `list`, `readText`
- **Sandbox write**: `writeText`, `mkdir`, `remove`, `move`

Write access is disabled by default. A remote adapter cannot override these grants.

The bridge scope is deliberately limited to `filesDir/riftfs/browser-sandbox`. It does not expose SAF mounts, clipboard, Android intents, notifications, secrets or the wider RiftFS.

## Pairing

Rift Bridge stores its pairing key using Android Keystore-backed `RiftSecretStore`. The device opens an outbound `wss://` connection and automatically reconnects after network or process interruptions.

The alpha remote adapter uses the same pairing key in the MCP path. Treat the endpoint as a secret and rotate the key if exposed. OAuth/multi-device routing is future work.

## Audit

Rift Bridge keeps a bounded local activity log containing:

- timestamp,
- tool name,
- target path or move source/destination,
- success/failure,
- error text when applicable.

The audit log intentionally does not store file contents or write payloads.

## RiftBrowser separation

RiftBrowser no longer injects code into `chatgpt.com` for tool access. There is no prompt wrapper, fenced tool protocol, assistant-response parser or exact-origin filesystem WebMessage listener.

This removes ChatGPT DOM structure from the Rift tool trust boundary. Browser UI changes may affect browsing, but they cannot break Rift Bridge tool execution.

## External adapters

The first adapter is `services/rift-mcp-relay`, which presents the sandbox tools as first-class MCP tools to ChatGPT custom apps.

Future adapters can target other AI/tool protocols while reusing the same device-side policy and audit model.
