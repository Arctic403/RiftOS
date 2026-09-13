# Chat Handoff Bundles

## Purpose

Chat handoff bundles let a long ChatGPT development session move into a new chat without depending on ChatGPT Memory or on reloading the entire transcript. ChatGPT owns summarization/continuation content; RiftOS owns local validation, packaging, hashing, storage and bounded reads.

The format is `.riftchat`, a validated ZIP container stored in app-private RiftFS under `/workspace/chat-handoffs/`. It is a development-session handoff format, not a hidden AI journal and not a replacement for ChatGPT account export.

## RiftShell surface

The feature stays behind the existing `rift_shell_exec` MCP tool. No new MCP tool family is added.

- `chat handoff <payload.json> [name]` — package a staged payload into `.riftchat`.
- `chat export <payload.json> [name]` — alias of `handoff`.
- `chat list` — list local handoff bundles.
- `chat inspect <bundle.riftchat>` — return manifest + bundle hash.
- `chat resume <bundle.riftchat>` — verify and return `handoff.md` + `state.json` for a new chat.
- `chat transcript <bundle.riftchat> [offset-chars] [max-chars]` — bounded optional transcript reads.

ChatGPT should normally stage a payload with the existing workspace write tool, then invoke `chat handoff` through `rift_shell_exec`. A new chat can immediately invoke `chat resume` on the returned path. Transcript chunks are only needed when the compact handoff does not contain enough detail.

## Payload v1

Payload schema: `rift.chat-handoff.payload/1`.

Required: `handoff_markdown` (or legacy alias `handoff`). Recommended: `title`, `source: "chatgpt"`, and a structured `state` object containing repo SHAs, installed/runtime state, decisions, known-good paths, failures and exact next actions.

Optional transcript input can be either `transcript` (string/array) or `transcript_path` pointing to a local RiftFS JSONL file. Full transcript inclusion is best-effort; the continuation contract is the compact handoff + state. RiftOS does not scrape the ChatGPT UI, browser DOM, credentials or account storage to manufacture missing history.

## Bundle v1

Schema: `rift.chat-handoff/1`.

Fixed entries only: `manifest.json`, `handoff.md`, `state.json`, and optional `transcript.jsonl`. `manifest.json` records title/time/source plus entry sizes and SHA-256 digests. Unknown, duplicate or archive-traversal entries are rejected. Resume verifies the hashes of the handoff/state it returns. Transcript reads are bounded and opt-in so a new chat does not flood its context window.

## Authority and privacy

This subsystem is local and app-private. It never opens a network listener, never widens Android Accessibility, never reads arbitrary Android app data and never receives a new MCP schema. It can only read RiftFS paths supplied through the trusted RiftShell/native path, and all resolved paths are canonicalized under RiftFS. `.riftchat` files may contain private conversation/project content, so they should be treated as private workspace artifacts.

## Source ownership

- `RiftChatHandoff.kt` — bundle schema, path safety, ZIP/hash/read/write limits.
- `src/riftos.js` — RiftShell command parsing and payload/bundle path routing.
- `RiftNativeDispatcher.kt` — narrow `chat.handoff` native route.
- `src/riftcore.js` — bounded native-call timeout/capability declaration.
- `src/riftshell-batch.js` — prevents chat packaging from pretending to participate in reversible shell batches.

## Failure signatures

- `handoff_markdown is required` -> ChatGPT staged an incomplete payload.
- payload or transcript size rejected -> use a compact handoff and keep large history in separate transcript chunks/files.
- `.riftchat bundle not found` -> use `chat list` and the exact returned `/workspace/chat-handoffs/...` path.
- hash/size mismatch -> bundle is corrupted or was modified; regenerate rather than trusting it.
- new chat lacks an old detail -> use `chat transcript` for the relevant chunk rather than loading the whole transcript.

## Fix map

Payload/schema/hash/path/ZIP behavior -> `RiftChatHandoff.kt`.
Shell syntax/path resolution/help -> `src/riftos.js`.
Native route wiring -> `RiftNativeDispatcher.kt` and `src/riftcore.js`.
Batch classification -> `src/riftshell-batch.js`.
MCP surface accidentally grows -> `RiftToolHost.kt` plus `scripts/validate-rift-transport.mjs`; the correct design keeps the existing shell tool.

## Validation

Repository validation locks the command behind existing RiftShell authority, confirms the native route and source inclusion, confirms `chat` is excluded from supposedly reversible shell batches, confirms RiftFS-only canonical paths/hash limits, and confirms no `rift_chat*` MCP tool appears. Android build remains the compile gate for Kotlin/ZIP behavior.
