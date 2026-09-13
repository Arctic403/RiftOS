# RiftBrowser Native Assets

These assets are injected into browser renderer pages by native RiftBrowser code; they are not part of the normal RiftOS `www` shell asset tree.

- `riftbrowser-mcp-app.js` -> [`../../../../../docs/systems/browser/mcp-compat/README.md`](../../../../../docs/systems/browser/mcp-compat/README.md)
- `adapters/ai-adapter-registry.js` and site adapters -> [`../../../../../docs/systems/browser/ai-adapters/README.md`](../../../../../docs/systems/browser/ai-adapters/README.md)

Keep page-side code transport-only. Native tool authority remains in `RiftMcpServer`/`RiftToolHost`/`RiftToolSandbox`.
