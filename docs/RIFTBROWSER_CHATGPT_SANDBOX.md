# RiftBrowser ChatGPT Sandbox and Rift Agent

RiftBrowser can open `https://chatgpt.com` using the Android System WebView content surface hosted inside the RiftOS browser window.

## Sandbox boundary

The native bridge is installed only for exact `https://chatgpt.com` and only accepts main-frame messages. Authentication hosts may receive normal cookie/navigation behavior but never receive the sandbox bridge.

Sandbox root:

```text
filesDir/riftfs/browser-sandbox/
  workspace/
  uploads/
  downloads/
```

Canonical path checks prevent traversal. The sandbox cannot use this bridge to read RiftOS secrets, SAF mounts, arbitrary Android files or other apps. Bridge file/payload operations are capped at 8 MiB.

## Page JavaScript API

`globalThis.RiftSandboxFS` provides:

- `info()`
- `stat(path)`
- `list(path, {recursive})`
- `readText(path)` / `writeText(path,text)`
- `readBase64(path)` / `writeBase64(path,data)`
- `mkdir(path)`
- `remove(path)`
- `move(from,to,{overwrite})`

## Rift Agent v2

Rift Agent is an opt-in browser adapter shown by the `Rift Agent ON/OFF` control on ChatGPT.

When ON:

1. normal user sends are intercepted,
2. RiftBrowser supplies a compact sandbox tool contract to the conversation,
3. ChatGPT may return a `rift-tool` JSON code block,
4. the adapter reads rendered DOM code blocks directly instead of depending on literal Markdown backticks,
5. only approved tool names are executed against `RiftSandboxFS`,
6. results are posted back into the same conversation,
7. ChatGPT continues until it returns a normal answer or the 12-round limit is reached.

Model-facing tools are intentionally narrower than the raw JavaScript API:

- `info`
- `stat`
- `list`
- `readText`
- `writeText`
- `mkdir`
- `remove`
- `move`

Binary Base64 operations are not exposed through the model-facing agent to avoid large chat payloads. There is no shell/process execution tool.

## Clean conversation UI

The protocol still exists inside the conversation because this is a browser adapter, but RiftBrowser masks the long agent wrapper so the user's visible message shows only the original task. Assistant tool-call turns and generated tool-result user turns are hidden from the visible ChatGPT page.

The ON state is persisted in the ChatGPT WebView profile so navigation/reload keeps the adapter enabled until the user turns it OFF.

## Security token

Each agent task receives a random token. Tool packets must return that exact token before execution. This prevents stale or unrelated tool-looking content from being executed accidentally; it is not a complete defense against every possible prompt-injection scenario, so file contents are treated as untrusted data and the tool allowlist remains narrow.

## Important limitation

Rift Agent is **not native ChatGPT MCP/tool registration**. ChatGPT Web is being orchestrated by RiftBrowser through user/assistant conversation turns. DOM selectors and rendered-message structure may change and require maintenance.
