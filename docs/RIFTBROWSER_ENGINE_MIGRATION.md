# RiftBrowser Engine Migration

RiftBrowser no longer treats Android System WebView as the browser architecture. RiftOS owns browser chrome, window lifecycle and a dedicated renderer surface; rendering is behind `RiftBrowserEngine`.

## Current backend

`AndroidWebViewBrowserEngine` is the compatibility backend because it currently satisfies ChatGPT Web, cookies/auth, file chooser/downloads and the exact-origin MCP bridge on the existing Android floor.

The backend is **never parked as a full-host visible view**. When the RiftBrowser window is not the active visible window, its owned native surface is removed from layout (`View.GONE`) while the engine object remains alive.

## Target renderers

A future engine can replace the compatibility backend without changing RiftDesktop or MCP. Candidates include a RiftEngine/Servo embedder or another embeddable engine with sufficient ChatGPT compatibility.

```text
RiftDesktop
    |
RiftBrowser chrome/window
    |
RiftBrowserWindow
    |
RiftBrowserEngine
    |-- AndroidWebViewBrowserEngine (compatibility)
    `-- future RiftEngine/other backend

Rift MCP App
    |
RiftMcpServer
    |
RiftToolHost
```

## Compatibility gate

Do not replace the current backend until the candidate passes on real hardware:

1. ChatGPT sign-in and persistent cookies.
2. Conversation rendering and streaming.
3. Long-chat scrolling/input without shell starvation.
4. Exact-origin MCP call/result round trips.
5. File chooser/download behavior.
6. Back/forward/reload and desktop resize/minimize/restore.
7. No renderer surface escape outside the RiftBrowser window.
8. Memory-pressure recovery.

## Android floor

RiftOS currently supports API 26. Any replacement engine must either support that floor or come with an explicit platform-policy change.

## Invariant

Renderer migration must not weaken the local capability boundary. Tool schemas, grants, audit, sandbox containment and the ChatGPT exact-origin boundary remain independent of the renderer choice.
