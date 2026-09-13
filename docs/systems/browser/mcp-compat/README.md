# Browser MCP Compatibility Layer

## Purpose

The browser compatibility layer lets ChatGPT Web discover and call RiftOS local MCP tools when the client does not provide a native custom-MCP registration path. It is a transport/compatibility layer only; all filesystem authority, permissions and execution remain native.

## Source ownership

- `android/app/src/main/assets/riftbrowser-mcp-app.js` — page-side compatibility state machine.
- `RiftBrowserMcpAppBridge.kt` — exact-origin WebMessage bridge to the in-process MCP server.
- `assets/adapters/ai-adapter-registry.js` — site-specific selectors used by the compatibility UI logic.
- `RiftMcpServer.kt`, `RiftToolHost.kt`, `RiftToolSandbox.kt` — actual MCP authority/execution, documented separately.

## Runtime flow

```text
ChatGPT Web page
  -> riftbrowser-mcp-app.js
  -> postRpc(initialize/tools/list/tools/call)
  -> exact-origin RiftMcpNative WebMessage
  -> RiftBrowserMcpAppBridge
  -> RiftMcpServer
  -> RiftToolHost / RiftToolSandbox
  -> bounded result
  -> page compatibility continuation
```

At boot the asset runs MCP `initialize` and `tools/list`, builds a compact manifest and exposes a small status badge. Tool calls parsed from the newest assistant turn are validated against that live manifest before `tools/call`.

## Tool-loop behavior

The adapter tracks assistant/user message baselines so historical tool envelopes cannot replay. It rate-limits calls, deduplicates call IDs, serializes execution, injects bounded results, waits for the exact result turn to be acknowledged, and then accepts only a newly-created assistant continuation. Streaming mutation handling is scoped to touched messages instead of repeatedly rescanning the whole conversation.

`rift_workspace_exec` is preferred for large project work so many local operations can execute in one transactional native batch.

## Important boundary

This layer must never implement filesystem or RiftShell operations itself. The page receives neither `RiftAndroid`, `RiftNativeDispatcher`, `RiftShellMcp` nor a raw RiftFS object. The only native page capability is structured MCP JSON-RPC through an allowed HTTPS origin. `rift_shell_exec` is executed by the native tool host through the trusted RiftOS shell WebView, not by guest-page JavaScript.

## Current manual-send behavior

The adapter can prepare task/result text for explicit user copy/paste where automatic submission is intentionally not used. Do not reintroduce silent composer mutation just to make a transport failure look successful.

## Failure signatures

- Badge says fewer tools than native Rift MCP screen -> client/action/catalog cache or stale `tools/list` scan, not missing sandbox capabilities.
- Tool envelope appears but no call happens -> parsing/arming/newest-message validation.
- Tool runs but continuation never resumes -> result correlation/acknowledgement logic.
- ChatGPT UI update breaks detection -> site adapter selectors or compatibility DOM assumptions.
- Native call fails with permission error -> `RiftToolHost` grant, not page JavaScript.

## Fix map

Parsing, page lifecycle, composer/message detection, call correlation -> `riftbrowser-mcp-app.js`.
Origin/WebMessage installation -> `RiftBrowserMcpAppBridge.kt`.
Selectors -> AI adapter registry.
Tool schema/permissions/execution -> native MCP subsystems.

## Validation

Run `scripts/test-rift-raw-protocol.mjs`, `scripts/test-rift-ai-adapters.mjs`, `scripts/validate-rift-transport.mjs` and the JS syntax checks in `npm run check`. Manually verify historical messages do not replay, malformed calls recover, a result continuation correlates correctly, and page streaming does not trigger full-chat rescans.

## Safe extension points

Add protocol syntax or page-state handling only when it stays transport-only. New tool capability belongs in `RiftToolHost`/sandbox. New supported AI sites belong in the adapter registry and origin allowlist after explicit review.
