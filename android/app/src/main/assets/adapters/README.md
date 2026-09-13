# Browser AI Site Adapters

This directory owns site-specific DOM selector/semantic definitions used by the RiftBrowser compatibility transport. The full design, failure map and validation rules are in [`../../../../../../docs/systems/browser/ai-adapters/README.md`](../../../../../../docs/systems/browser/ai-adapters/README.md).

`ai-adapter-registry.js` is the single active registry and contains the supported ChatGPT, Gemini/Google, Claude and Copilot selector definitions. Do not add duplicate per-site marker files unless native injection actually loads them. Adapter code never owns RiftOS tool execution, filesystem access or native permissions; those remain behind MCP/native boundaries.
