# RiftBrowser Engine Migration

RiftBrowser is moving away from making Android System WebView the long-term browser engine.

## Immediate performance guard

The ChatGPT Plus MCP compatibility layer must never rescan the complete conversation on every streaming DOM mutation. It uses mutation-scoped message tracking, a small delayed work queue, and a one-shot compact tool manifest per conversation route.

This is a stop-gap while the browser engine changes. It protects RiftOS from work caused by the compatibility adapter; it does not make Chromium WebView lightweight.

## Target: RiftEngine on Servo

The preferred experimental engine base is Servo, forked/integrated as **RiftEngine** rather than attempting to author HTML, CSS, JavaScript, networking, cookies, TLS, accessibility, and media engines from scratch.

Reasons:

- Rust-native browser engine intended for embedding.
- Android builds are available upstream.
- The embedder API supports per-webview rendering contexts, navigation, request interception, user scripts, and asynchronous JavaScript evaluation.
- RiftOS can own the browser chrome, lifecycle, permissions, memory policy, MCP adapter, and engine configuration.

## Compatibility gate

Servo/RiftEngine must not replace the current renderer until all of these pass on real hardware:

1. ChatGPT sign-in and persistent cookies.
2. ChatGPT conversation rendering and streaming.
3. Long-chat scrolling/input without RiftOS shell starvation.
4. Rift MCP App context injection and structured call/result round trips.
5. File chooser/download behavior needed by RiftBrowser.
6. Back/forward/reload and desktop resize behavior.
7. Memory-pressure recovery.

## Android floor

Current RiftOS supports API 26. Current Servo Android support is newer than that floor, so the migration needs an explicit compatibility policy. Until that is resolved, System WebView remains a compatibility backend rather than the design target.

## Architecture

```text
RiftDesktop
    |
RiftBrowser chrome
    |
RiftBrowserEngine interface
    |-- RiftEngine / Servo   (target)
    `-- Android WebView      (compatibility backend)

Rift MCP App
    |
RiftMcpServer
    |
RiftToolHost
```

The MCP/tool host is renderer-independent. Moving browser engines must not change the Rift capability boundary.

## Rift AI compatibility

A future renderer must preserve Rift AI transport-only mode: the authenticated ChatGPT page must be able to stay alive and stream while RiftOS shell HTML is the visible workspace. The renderer migration must not introduce a model API path or a second model transport.
