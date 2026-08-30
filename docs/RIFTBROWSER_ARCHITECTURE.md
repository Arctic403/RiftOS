# RiftBrowser Architecture

## Contract first

RiftBrowser is a RiftKernel service with replaceable renderers. The browser must remain usable while renderer technology changes underneath it.

```text
Launcher / RiftShell / future APIs
              |
       RiftKernel.browser
     _________|___________
    |         |           |
 WebKit   RiftEngine   Web transport
 native    WebCore      fallback
```

The browser service is implemented in `src/riftbrowser-kernel.js`. The current desktop adapter is `src/riftbrowser-ui.js`.

## Service ownership

The service is responsible for renderer-independent state and policy:

- logical tabs and selected tab;
- normalized URLs/search queries;
- navigation requests;
- logical history and bookmarks;
- renderer registry and priority;
- renderer capability/status reporting;
- persistent browser metadata.

Renderer code is responsible for drawing/executing a page and reporting a result. It must not create a second RiftOS kernel, filesystem or permission broker.

## Renderer registration

```js
window.RiftBrowserRendererContract.register({
  id: "renderer-id",
  name: "Renderer name",
  priority: 150,
  available: () => true,
  capabilities: {
    fullWeb: true,
    localEngine: true
  },
  open: async ({ url, tab, newTab, service }) => {
    return { mode: "custom", url, title: "Optional title" };
  }
});
```

Required fields are `id`, `available()` and `open()`. Higher priority wins when selection is `auto`.

A renderer should return enough metadata for the browser service/UI to understand what happened without handing the renderer unrelated OS privileges.

## Current backends

### Native WebKit

`native-webkit` delegates to the Swift `RiftBrowserStore` via `browser.open`. It is the current full-web backend when RiftNative is connected. Swift browser tabs intentionally omit the privileged native script-message handler.

### RiftEngine

`riftengine` is an adapter slot. A usable engine publishes `window.RiftEngineBrowserBackend` with `open()` or `navigate()` and optionally an `available()` readiness check. The kernel then promotes it automatically ahead of the PWA fallback.

RiftEngine should focus on page execution/rendering: JSC, WebCore, networking, painting, input and page storage. Browser chrome and OS policy remain above it.

### Web transport fallback

`web-transport` is always available. It can display CORS-readable HTML/text after sanitization and return an external-open result otherwise. This is useful for testing BrowserService behavior on GitHub Pages before a full custom renderer is ready.

It cannot make `chatgpt.com` or another site ignore CORS/CSP/frame policy.

## UI and shell

The existing desktop is deliberately not duplicated. `src/riftbrowser-ui.js` captures normal Browser launcher actions and routes them to `RiftKernel.browser` while using the existing RiftOS window template/process model for web-mode chrome.

RiftShell commands:

```text
browser [url]
browserctl status
browserctl tabs
browserctl renderers
browserctl renderer <auto|native-webkit|riftengine|web-transport>
browserctl new [url]
browserctl back
browserctl forward
browserctl reload
browserctl bookmark [url]
browserctl bookmarks
browserctl close [tabId]
```

## Security invariants

1. A page renderer does not receive RiftWorkspace by default.
2. Native web pages never receive the shell's `riftNative` message handler.
3. Web fallback content is sandboxed and active markup is stripped before `srcdoc` rendering.
4. Renderer registration is a trusted RiftOS extension point, not an API exposed to arbitrary page content.
5. Future downloads/filesystem integration must go through explicit kernel capability checks.

## Engine promotion gates

RiftEngine may become the preferred non-native backend only after it can satisfy the browser renderer contract and pass its own engine gates: stable JSC context, WebCore document rendering, input/event loop, networking, persistent profile and iPhone memory/stability testing.

The browser service itself should not wait on those expensive gates. Tabs, shell commands, history/bookmarks, renderer selection and UI behavior can be tested continuously on normal Pages deployments.
