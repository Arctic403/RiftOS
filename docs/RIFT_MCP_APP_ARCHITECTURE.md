# RiftOS ChatGPT App / MCP Architecture

## Goal

Replace the DOM-scraped Rift Agent protocol with first-class model tools while
keeping RiftOS itself independent of MCP.

RiftOS owns the capability model. MCP is one adapter.

```text
ChatGPT custom app
       |
       | MCP over HTTPS
       v
Rift MCP Relay
       |
       | paired WSS, outbound from phone
       v
Rift MCP Device Client
       |
       v
RiftBrowserSandbox
       |
       v
filesDir/riftfs/browser-sandbox
```

## Why this exists

The legacy Rift Agent wraps user text with `[RIFT_AGENT_V3 ...]`, waits for a
Markdown `rift-tool` block, scrapes ChatGPT's rendered DOM, executes the tool,
and injects results back as another chat message. That works as a fallback but
is coupled to ChatGPT UI structure.

The MCP adapter makes tools part of the ChatGPT tool catalog. The model receives
structured tool definitions and arguments, and the relay returns structured
results without Markdown parsing or hidden tool-result messages.

## Android boundary

`RiftMcpRelayClient` intentionally creates its own `RiftBrowserSandbox` instance.
Both instances resolve to the same app-private root, but the relay never gets a
reference to `RiftNativeDispatcher` or its broader capabilities.

Allowed relay methods:

- `sandbox.info`
- `fs.stat`
- `fs.list`
- `fs.readText`
- `fs.writeText`
- `fs.mkdir`
- `fs.remove`
- `fs.move`

Not exposed:

- SAF/external mounts
- clipboard
- Android intents
- notifications
- device information beyond sandbox info
- secrets
- full RiftFS
- shell/native execution

## Pairing

The alpha uses one base64url pairing key. Android stores it through
`RiftSecretStore` (Android Keystore backed). The phone only makes a `wss://`
outbound connection. The same key is embedded in the private MCP endpoint path
for developer-mode setup.

This path-secret mechanism is an alpha convenience, not the final auth design.
A production/multi-user relay should use OAuth and per-device authorization.

## Runtime lifecycle

`RiftMcpInitProvider` starts with the RiftOS process and restores the saved relay
configuration. If MCP is enabled, it reconnects automatically. `RiftMcpRuntime`
keeps a single process-wide client.

`RiftMcpBridgeActivity` provides the current setup UI and can:

- set the WSS relay URL;
- generate/replace the pairing key;
- connect or disconnect;
- show connection state;
- copy the corresponding ChatGPT MCP endpoint.

## Fallback

`riftbrowser-chatgpt-agent.js` remains available during migration. It should be
treated as a compatibility adapter, not the long-term primary tool transport.
Once the MCP path is proven in normal RiftOS use, the DOM agent can default to
off and eventually move to a diagnostics-only fallback.

## Next hardening

1. OAuth / per-device authorization at the relay.
2. Multi-device routing instead of one-device replacement.
3. Explicit user approval policy for destructive tools.
4. Relay audit log containing tool names/status only, never file contents by default.
5. Optional read-only tool profile.
6. Remove the model-visible Rift Agent bootstrap from normal operation.
