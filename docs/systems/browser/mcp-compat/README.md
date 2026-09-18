# Browser MCP Compatibility Layer

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

This is a browser compatibility transport for supported AI web pages. It exposes **MCP JSON-RPC only** through an exact HTTPS-origin WebMessage bridge. Native tool schemas, grants, audit and execution remain owned by RiftMcpServer/RiftToolHost/RiftToolSandbox.

## Source ownership

- `RiftBrowserMcpAppBridge.kt` — native exact-origin WebMessage bridge.
- `android/app/src/main/assets/riftbrowser-mcp-app.js` — page-side manual-send protocol/state machine.
- `android/app/src/main/assets/adapters/ai-adapter-registry.js` — site DOM adapters.
- `RiftMcpServer.kt`, `RiftToolHost.kt`, `RiftToolSandbox.kt` — actual native authority.

## Allowed origins

The authoritative WebMessage/document-start origin set is:
- chatgpt.com / www.chatgpt.com
- github.com / www.github.com
- copilot.microsoft.com
- gemini.google.com
- google.com / www.google.com
- claude.ai / www.claude.ai

All require HTTPS and main-frame messages.

The Kotlin semantic origin check and WebView allowed-origin rule set are now aligned. The previous omission of the two `www` variants was repaired during this audit.

## Native bridge

Bridge name: `RiftMcpNative`.

Incoming page messages:
- must come from main frame;
- must come from an allowlisted HTTPS origin;
- are capped at 9 MiB UTF-8;
- must parse as a JSON object;
- are forwarded to `RiftMcpServer.handleAsync`.

The page receives no RiftFS object, shell object or general native dispatcher.

Responses are delivered only while the bridge/WebView/Activity is alive. Destroy marks the bridge dead and removes the listener, so late MCP responses cannot evaluate JavaScript into a destroyed renderer.

## Injection

The bridge concatenates:
1. `adapters/ai-adapter-registry.js`;
2. `riftbrowser-mcp-app.js`.

If WebView supports document-start injection, the script is installed only for the allowlisted origins.

Otherwise `ensureInjected(url)` performs fallback injection only for an allowlisted AI URL after page finish.

## Boot and tool catalog

Page boot performs:
- MCP `initialize` using protocol version `2025-06-18`;
- MCP `tools/list`.

The returned live tool list is the page-side validation source.

A status badge reports current local tool count and can disable compatibility behavior for the tab.

## Manual-send boundary

The compatibility layer **never clicks Send**.

A newly observed, non-historical user-message mutation:
- arms tool execution for the route;
- stages the compact Rift capability/tool context once for that route when needed.

The user remains responsible for sending that staged context.

Tool results are also staged into the composer and are not auto-sent.

Result/context user turns do not recursively stage another context block.

Route changes reset context/arming state, except the new-chat to conversation-route transition preserves an already-staged context as intended.

## Historical replay protection

Initial page boot marks recent existing assistant messages historical instead of executing them.

Only newly touched messages enter the live mutation queue.

A tool call may execute only when:
- compatibility is enabled;
- MCP/tool list is ready;
- execution has been armed by a new user turn;
- the message is the newest assistant message;
- the message is not historical;
- exactly one complete raw Rift call envelope is present.

## Raw call protocol

Envelope:
```
[RIFT_CALL]
call <unique-call-id> <tool-name>
set <path> <value>
[RIFT_END]
```

The parser supports:
- quoted scalar tokens;
- typed booleans/numbers/null;
- dotted object paths;
- numeric array indexes;
- bounded multiline heredocs.

Parser resource/security bounds:
- raw command block <=512000 characters;
- dotted path depth <=24;
- numeric array index <=4096;
- `__proto__`, `prototype` and `constructor` path segments are rejected;
- remembered processed call IDs are capped at 512.

Only one call block is accepted per assistant turn.

The requested tool name must exist in the live `tools/list` manifest.

## Call safety

- call id required and <=160 characters;
- route-scoped call-id deduplication;
- conflicting reuse rejected;
- 24 calls per rolling minute;
- calls serialized through one promise queue;
- native `tools/call` carries `_meta["riftos/callId"]`;
- returned result must echo that exact call id;
- tool execution permissions remain native.

For large project work, the injected guide prefers one transactional `rift_workspace_exec` batch.

## Result handling

Results are rendered as bounded `[RIFT_RESULT]` text.

Result body is capped at 48000 characters.

The staged result carries:
- result id;
- call id;
- tool name;
- status;
- final flag;
- bounded native structured value/error.

Failures are marked retryable and tell the AI to correct/retry with a new call id.

No hidden task controller, persistent AI journal or browser-owned patch authority exists here.

## Streaming and DOM performance

MutationObserver collects only assistant/user message nodes touched by DOM mutations.

Processing is delayed/batched and does not rescan the entire conversation for every token.

Incomplete command envelopes receive a short grace period while streaming before a recoverable parse result is staged.

## Non-ownership boundaries

This layer does not own:
- read/write permissions;
- tool schemas;
- workspace containment;
- shell semantics;
- Git;
- native relay;
- MCP server protocol behavior;
- AI-site selector definitions.

## Source fixes in this audit

- aligned `www.github.com` and `www.claude.ai` with the native allowed-origin rules;
- replaced stale state label `chatgpt.com` with allowlisted-AI origin description;
- added destroyed-bridge guard for late native responses;
- restored the missing new-user-turn `toolExecutionArmed = true` transition;
- restored one-context-per-route staging state;
- retained explicit manual-send behavior;
- removed two dead legacy helpers;
- added source-test assertions for arming/context/manual-send invariants.

## Critical invariants

- exact HTTPS origins only;
- main frame only;
- no generic native object;
- native tool host remains permission authority;
- historical assistant messages never replay calls;
- a new user turn is required to arm execution;
- exactly one call block per assistant turn;
- live tool manifest validates names;
- call ids are correlated end-to-end;
- browser never auto-clicks Send;
- late native responses do not target destroyed WebViews.

## Failure signatures

- supported www origin gets script but no bridge -> allowlist drift;
- tool envelope always returns NOT_ARMED -> missing user-turn arming;
- capability context is never staged -> route-state regression;
- old chat history runs tools on load -> historical-baseline regression;
- page calls unknown native method directly -> authority regression;
- result call id differs from request -> correlation failure;
- browser clicks Send -> explicit-user-boundary regression;
- native reply evaluates after destroy -> lifecycle race.

## Fix map

Origin/message lifecycle -> `RiftBrowserMcpAppBridge.kt`.

Raw protocol/arming/context/results/mutation queue -> `riftbrowser-mcp-app.js`.

DOM selectors -> AI adapter registry.

MCP method/tool/permission execution -> native MCP subsystems.

## Validation

Source validation:
- allowed-origin set equals semantic origin check;
- 9 MiB native message bound;
- destroyed response guard;
- initialize/tools-list/tool-call flow;
- new-user arming assignment exists;
- context staging assignment exists;
- no `.click()`;
- 24/min limit;
- live tool-name validation;
- route call-id dedupe;
- result correlation;
- historical/newest-message checks;
- result 48000-char cap.

Automated validation includes raw-protocol, AI-adapter and transport tests plus JS syntax checks.

Installed browser tests must still verify each supported site against real DOM changes and manual-send behavior.
