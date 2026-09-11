# Rift AI / RiftBrowser Transport Audit

## Root causes found

1. The transport WebView was switched to Android `INVISIBLE`/`GONE` whenever ChatGPT was not shown. Android WebView can throttle or suspend the exact timers, layout, MutationObserver and composer state used by the compatibility adapter. This explains why opening RiftBrowser made stuck tasks resume.
2. Native dispatch treated a synchronous JavaScript invocation as a successful send. `submitTask()` started asynchronous work, but Kotlin immediately discarded its pending payload before ChatGPT had accepted the message.
3. Composer clicks were not acknowledged. A missed first click could be reported as `submitted`, while result continuations treated an uncertain DOM acknowledgement as success.
4. The model-facing envelope mixed XML-like tags and JSON and allowed only one call. It worked, but malformed streaming output was unnecessarily easy to produce.
5. RiftDev's `npm run check` referenced removed files, so its advertised local validation failed before checking active Android assets.

## Fixes applied

- Warm ChatGPT Web when RiftBrowser is created.
- Keep the transport WebView rendered and `VISIBLE` behind the trusted shell; bring the same view to the front only for normal browsing/sign-in.
- Keep the native pending payload until the page emits a matching `submitted` event.
- Add a synchronous, deduplicating `queueTask()` handoff and a page-owned asynchronous readiness/send pump.
- Verify outgoing user-message acceptance and retry a rejected click up to three times without retrying after the composer clears, generation begins or a new user turn appears.
- Replace the chat-facing JSON request/result protocol with a bounded raw-text command channel using `[RIFT_CALL]` / `[RIFT_END]` and `[RIFT_RESULT]`.
- Keep JSON-RPC only inside the trusted page/native MCP bridge; it is no longer injected into normal ChatGPT conversation turns.
- Add dotted-path argument assignment and heredocs so nested `rift_workspace_exec` batches and multiline source edits remain fully supported without chat JSON.
- Cap raw result bodies before composer injection so a large local read cannot exceed the ChatGPT message submission limit.
- Preserve exact-origin, read/write permission, active-session, duplicate-ID, rate-limit, workspace scope, rollback and result-correlation gates.
- Add local `archive` Code Mode operation so ChatGPT can request a ZIP without transferring binary data through chat.
- Repair RiftDev's JavaScript check command and add a focused transport validator.

## Intended data flow

ChatGPT receives a compact plain-text tool manifest, emits one raw Rift command block when local work is needed, and receives a bounded plain-text result. RiftOS translates that command into its internal MCP JSON-RPC call without exposing the JSON transport in the conversation. Whole projects and generated ZIP bytes do not travel through the ChatGPT composer.

## Remaining runtime dependency

The adapter still depends on ChatGPT Web's composer, send/stop controls and semantic message-role attributes. The validation script catches source-level regressions, but an on-device signed APK test is still required after major ChatGPT UI or Android System WebView updates.
