# RiftBrowser ChatGPT Sandbox and Rift Agent

RiftBrowser opens `https://chatgpt.com` inside the Android System WebView content surface hosted by a normal RiftOS desktop window.

## Sandbox boundary

The native bridge is installed only for exact `https://chatgpt.com` and only accepts main-frame messages. Authentication hosts can use normal cookie/navigation behavior but never receive the Rift sandbox bridge.

Sandbox root:

```text
filesDir/riftfs/browser-sandbox/
  workspace/
  uploads/
  downloads/
```

Canonical path checks prevent traversal. The bridge cannot read RiftOS secrets, SAF mounts, arbitrary Android files, or other apps. Bridge file/payload operations are capped at 8 MiB.

## Page JavaScript API

`globalThis.RiftSandboxFS` provides `info`, `stat`, `list`, text/base64 read-write, `mkdir`, `remove`, and `move` over the sandbox root.

## Rift Agent v3 — Persistent Tool Runtime

Rift Agent remains an opt-in browser-side adapter; it is **not MCP** and does not make separate OpenAI API calls.

When ON, RiftBrowser persists the Agent state in the ChatGPT WebView profile. The first task in a ChatGPT conversation carries a compact bootstrap describing the model-facing filesystem tools. Later tasks carry only a tiny `RIFT_AGENT_V3 fs1` marker, so the large per-message V1/V2 wrapper is gone.

Model-facing tools remain intentionally narrow:

- `info`
- `stat`
- `list`
- `readText`
- `writeText`
- `mkdir`
- `remove`
- `move`

Binary Base64 operations remain available to page JavaScript but are not model-facing. There is no shell or process-execution tool.

### Automatic pipeline

```text
user task
  -> Rift Agent intercepts send
  -> compact one-time bootstrap or tiny V3 marker
  -> ChatGPT emits a rendered rift-tool JSON block when needed
  -> RiftBrowser validates and executes approved calls locally
  -> hidden tool result turn is returned to the same conversation
  -> ChatGPT continues until it answers normally
```

The adapter reads rendered `pre/code` blocks directly, with Markdown-text parsing only as a fallback. Tool-call assistant turns and generated result user turns are hidden from the visible ChatGPT page, while the original user message is visually preserved.

## No model-visible security token

V3 removes the UUID/token from user prompts, tool packets, and result messages. Security enforcement lives in RiftBrowser instead:

- only assistant output from the active task is considered,
- tool names are allowlisted,
- call objects and unique call IDs are validated,
- maximum 8 calls per round and 12 rounds per task,
- exact-origin sandbox and canonical-path enforcement remain native,
- repeated identical tool packets are blocked,
- file contents are treated as untrusted data.

Internal task/session identifiers stay inside RiftBrowser and are not sent to the model.

## Conversation bootstrap

The full compact tool schema is injected once per ChatGPT conversation and recorded in `sessionStorage`. Normal turns then use only the tiny V3 marker. Starting a new conversation automatically causes a new bootstrap. `RiftSandboxAgent.rebootstrap()` can clear the current conversation bootstrap if ChatGPT behavior changes and a fresh tool reminder is needed.

The ON/OFF state migrates from the V2 preference and persists across reload/navigation.

## Important limitation

ChatGPT Web does not expose native custom-tool registration to RiftBrowser. Rift Agent therefore orchestrates normal conversation turns rather than attaching first-class OpenAI/MCP tools. It is designed to feel persistent while remaining a custom zero-extra-API browser adapter. ChatGPT DOM structure/selectors can change and may require maintenance.
