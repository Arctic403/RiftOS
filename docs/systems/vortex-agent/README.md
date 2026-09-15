# RiftOS Fixed-Scope Local UI Agents

## Purpose

The local UI agent gives trusted RiftShell hands on two explicitly fixed Android app scopes while keeping authority local to the phone. `vortex-agent` is hard-coded to exactly `com.vortex3d.app`; `riftos-agent` is hard-coded to exactly `com.riftos.app` for RiftOS self-acceptance testing. RiftOS additionally has a bounded Samsung Keyboard companion fixed to `com.samsung.android.honeyboard`; it can only report readiness or press one exact key while a visible, focused, non-password RiftOS editable field owns the input session. There is no package argument, arbitrary-app mode, keyboard tree/suggestion dump, ADB path, root path, raw Android shell path, network listener or system-server hook.

The agent complements, rather than replaces, the Vortex Binder/VTXScript bridge. Use the local agent for Android UI behavior and real gestures; use `vortex ...`/VTXScript for engine/JNI semantics, deterministic state inspection and validation.

## Source ownership

- `android/app/src/main/java/com/riftos/app/RiftVortexLocalAgent.kt` — shared fixed-scope helper plus Vortex-only and RiftOS-self-only launch/UI-tree/semantic-click/text/gesture/Back authorities.
- `android/app/src/main/res/xml/vortex_agent_accessibility.xml` — Android-enforced package filter and gesture/window-content capabilities.
- `android/app/src/main/AndroidManifest.xml` — declares the Accessibility service behind `android.permission.BIND_ACCESSIBILITY_SERVICE`.
- `RiftNativeDispatcher.kt` — finite `vortex.agent` native route.
- `src/riftos.js` — `vortex-agent` shell family.

## Authority boundary

`vortex-agent open` uses Android's normal launch intent for the hard-coded package `com.vortex3d.app`. It works whenever Vortex3D is installed, even when the Accessibility service is disabled.

All UI inspection and interaction requires the user to enable **RiftOS Local UI Agent** in Android Accessibility settings. Android's service metadata filters events to exactly `com.vortex3d.app`, `com.riftos.app`, and the Samsung Keyboard package `com.samsung.android.honeyboard`; interactive-window retrieval is enabled solely so the RiftOS keyboard companion can see the active input-method window while RiftOS itself remains visible. Because ChatGPT can regain foreground focus between MCP calls, each UI operation may bring only its command's constructor-fixed package forward inside that same local call, wait up to three seconds for a visible Accessibility application root, require that root to stabilize across three consecutive polls, and then re-check the exact package before any inspection or action. When an IME owns Android's active Accessibility window, the shared resolver searches only visible `TYPE_APPLICATION` windows for that same constructor-fixed package instead of relaunching/reordering the app; this preserves the focused EditText while keeping package authority unchanged. Semantic actions only select visible, enabled nodes. Parent/child matches that resolve to one clickable/editable control are collapsed to that single action anchor; truly separate controls remain ambiguous. Password nodes are never returned with text and cannot be clicked or edited by semantic actions.

The service does not request root, ADB, shell execution, screen-overlay authority, unrestricted package control or remote/network control. ChatGPT reaches it only through the already-existing `rift_shell_exec` -> trusted RiftShell path. The Vortex bridge may call the same hard-coded activation guard internally during `test-wait`/`script-wait`; this does not add package-selection authority or broaden the Accessibility scope.

## Commands

- `vortex-agent status` — installed/service/foreground/action readiness.
- `vortex-agent open` — launch or bring Vortex3D forward using its normal Android package intent.
- `vortex-agent tree [limit]` — bounded Vortex-only Accessibility tree; self-activates Vortex when another app regained foreground.
- `vortex-agent click <text|content-description|view-id>` — exact visible/enabled match first, then an unambiguous icon-prefix-normalized semantic match, with clickable-parent fallback inside Vortex only; ambiguous matches are rejected. If a just-transitioned fixed-package UI has not published its deeper Accessibility children yet, lookup briefly re-queries fresh roots for the same requested target/package before failing, then waits for UI settlement after success.
- `vortex-agent tap <x> <y>` — real Android accessibility gesture within display bounds after same-call Vortex activation/package verification.
- `vortex-agent swipe <x1> <y1> <x2> <y2> [ms]` — bounded real gesture after same-call Vortex activation/package verification.
- `vortex-agent type <target> <text>` — ACTION_SET_TEXT on a visible, enabled, editable, non-password Vortex node after same-call activation and root stabilization; the same exact-then-unambiguous semantic matcher is used.
- `vortex-agent back` — Android Back only after the agent has activated and verified Vortex in the same call.

The `riftos-agent` command mirrors the same `status`, `open`, `tree`, `click`, `tap`, `swipe`, `type`, and `back` verbs but is fixed to `com.riftos.app`; it exists specifically for live RiftOS UI/function acceptance testing. It also exposes the RiftBrowser-only `browser-inspect` family for bounded temporary maintenance of the active HTTPS page. `riftos-agent type-focused <text>` targets the one currently focused editable RiftOS node and therefore works on unlabeled/blank WebView or native form fields; it still rejects password fields. If the Samsung Keyboard owns the active Accessibility window, the agent resolves the still-visible fixed RiftOS application window without relaunching RiftOS, so the input focus is preserved across the tool call. `riftos-agent keyboard status` reports only bounded readiness flags, and `riftos-agent keyboard key <label>` can press one exact Samsung Keyboard key only when RiftOS has a focused non-password editable field. The keyboard companion never exposes a keyboard tree, suggestion text, clipboard contents, or an arbitrary package selector. It also owns a structured `riftos-agent devlab ...` controller. That controller does not use Accessibility and does not write `/system/devlab/stage` directly: native accepts only the finite Dev Lab action whitelist, submits a base64 JSON `devlab rpc` request to process-owned `RiftNativeShell`, and that not-yet-native family delegates to the active `RiftShellBridge` compatibility executor so the trusted WebView executes the real `RiftDevLab.executeAgentRequest()` API so baseline hashes, snapshots, evidence, guarded preview and publication stay authoritative. Arbitrary shell commands are not accepted by this tunnel. When the same label is exposed by both a non-actionable accessibility cell/text node and its real clickable/editable control, semantic resolution now prefers and deduplicates the actionable anchor instead of reporting false ambiguity; genuinely distinct actionable anchors remain an error. Because Android returned false-positive `ACTION_CLICK` success against RiftOS native self-controls during device acceptance, RiftOS semantic clicks use a short accessibility gesture at the resolved control center instead; Vortex retains its existing ACTION_CLICK behavior. Gesture bounds are validated in the same full real-display coordinate space used by `AccessibilityNodeInfo#getBoundsInScreen` and Android accessibility gestures, with resource metrics only as fallback; this keeps valid controls beside system bars and the native bottom taskbar from being rejected as off-display. RiftOS self-Back is dispatched through the actual foreground `MainActivity.onBackPressed()` path so Start/native-window handling reaches `RiftNativeDesktop.handleBack()` instead of trusting a false-positive global Accessibility Back result. Live agent commands are non-reversible and are rejected by RiftShell atomic batches. Dev Lab agent commands remain outside generic `batch` for the same reason: Dev Lab owns its own staged/snapshot/publication transaction semantics. Use `riftos-agent devlab stage-file` and `run-file` for exact multi-line/quoted source payloads.

### RiftBrowser live inspector

`riftos-agent browser-inspect` is not a generic DevTools or JavaScript tunnel. The fixed action set is `status`, `dom`, `inspect`, `focus`, `hide`, `show`, `text`, `attr`, `style`, `outline`, and `reset`. DOM results contain structural metadata only and intentionally omit page text/HTML, input values, cookies, storage, headers and hidden credentials. Selectors reject attribute/value probing and `:has()`. `style` accepts only a bounded property whitelist and a restricted value grammar; there is no raw stylesheet injection. Sensitive password-containing targets are rejected for mutations. Every change is active-tab/page-local and disappears on navigation/reload or explicit reset.

## Failure signatures

- `open` says Vortex is not installed -> package `com.vortex3d.app` is absent or build/install failed.
- `accessibility_connected=false` -> enable **RiftOS Local UI Agent** in Android Accessibility settings.
- action cannot activate Vortex -> package launch failed, Android denied the foreground transition, or Vortex did not expose an Accessibility root within the bounded activation timeout.
- semantic target not found after the bounded fresh-root retry -> inspect `vortex-agent tree`; the view may truly be hidden, disabled or lack text/content-description/resource id, in which case use a unique semantic label/view id or a bounded coordinate gesture.
- semantic target ambiguous -> multiple distinct visible actionable controls still share the same label after parent/child collapsing and non-actionable semantic duplicates are discarded; use an app-specific content description such as `Taskbar Files` / `Start Files` or a resource id instead of letting the agent guess.
- gesture rejected/cancelled -> Android accessibility service/lifecycle or invalid foreground transition.
- JNI/engine state is wrong after a successful UI gesture -> inspect the separate Vortex bridge/VTXScript/engine owner rather than widening agent authority.

## Fix map

Package scope, node filtering, gesture bounds and semantic actions -> `RiftVortexLocalAgent.kt`.
Android package filtering/capabilities -> `vortex_agent_accessibility.xml`.
Service declaration -> Android manifest.
Shell parsing -> `src/riftos.js`.
Native route -> `RiftNativeDispatcher.kt`.
Engine/JNI semantics -> Vortex3D bridge/VTXScript, not this subsystem.

## Validation

Repository transport validation locks the hard-coded app packages plus the single Samsung Keyboard companion package, interactive-window flag, BIND_ACCESSIBILITY_SERVICE permission, absence of arbitrary package arguments/root/ADB execution, password rejection, focused-field text path, IME-safe fixed-application root recovery, bounded keyboard status/key-only surface, native route, shell command and batch exclusion. An Android build remains the compile gate. Device smoke should enable the service manually once, then run `status`, `open`, `tree`, one semantic click, one coordinate tap/swipe, text entry into a harmless Vortex field, Back, and verify the agent rejects actions whenever another app is foreground.
