# RiftOS Fixed-Scope Local UI Agents

## Purpose

The local UI agent gives trusted RiftShell hands on two explicitly fixed Android UI scopes while keeping authority local to the phone. `vortex-agent` is hard-coded to exactly `com.vortex3d.app`; `riftos-agent` is hard-coded to exactly `com.riftos.app` for RiftOS self-acceptance testing. There is no package argument, arbitrary-app mode, ADB path, root path, raw Android shell path, network listener or system-server hook.

The agent complements, rather than replaces, the Vortex Binder/VTXScript bridge. Use the local agent for Android UI behavior and real gestures; use `vortex ...`/VTXScript for engine/JNI semantics, deterministic state inspection and validation.

## Source ownership

- `android/app/src/main/java/com/riftos/app/RiftVortexLocalAgent.kt` — shared fixed-scope helper plus Vortex-only and RiftOS-self-only launch/UI-tree/semantic-click/text/gesture/Back authorities.
- `android/app/src/main/res/xml/vortex_agent_accessibility.xml` — Android-enforced package filter and gesture/window-content capabilities.
- `android/app/src/main/AndroidManifest.xml` — declares the Accessibility service behind `android.permission.BIND_ACCESSIBILITY_SERVICE`.
- `RiftNativeDispatcher.kt` — finite `vortex.agent` native route.
- `src/riftos.js` — `vortex-agent` shell family.

## Authority boundary

`vortex-agent open` uses Android's normal launch intent for the hard-coded package `com.vortex3d.app`. It works whenever Vortex3D is installed, even when the Accessibility service is disabled.

All UI inspection and interaction requires the user to enable **RiftOS Local UI Agent** in Android Accessibility settings. Android's service metadata filters events to exactly `com.vortex3d.app` and `com.riftos.app`. Because ChatGPT can regain foreground focus between MCP calls, each UI operation may bring only its command's constructor-fixed package forward inside that same local call, wait up to three seconds for a visible active Accessibility root, require that root to stabilize across three consecutive polls, and then re-check the exact package before any inspection or action. Semantic actions only select visible, enabled nodes. Parent/child matches that resolve to one clickable/editable control are collapsed to that single action anchor; truly separate controls remain ambiguous. Password nodes are never returned with text and cannot be clicked or edited by semantic actions.

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

The `riftos-agent` command mirrors the same `status`, `open`, `tree`, `click`, `tap`, `swipe`, `type`, and `back` verbs but is fixed to `com.riftos.app`; it exists specifically for live RiftOS UI/function acceptance testing. It also owns a structured `riftos-agent devlab ...` controller. That controller does not use Accessibility and does not write `/system/devlab/stage` directly: native accepts only the finite Dev Lab action whitelist, tunnels a base64 JSON request through the active `RiftShellBridge` as `devlab rpc`, and the trusted WebView executes the real `RiftDevLab.executeAgentRequest()` API so baseline hashes, snapshots, evidence, guarded preview and publication stay authoritative. Arbitrary shell commands are not accepted by this tunnel. When the same label is exposed by both a non-actionable accessibility cell/text node and its real clickable/editable control, semantic resolution now prefers and deduplicates the actionable anchor instead of reporting false ambiguity; genuinely distinct actionable anchors remain an error. Because Android returned false-positive `ACTION_CLICK` success against RiftOS native self-controls during device acceptance, RiftOS semantic clicks use a short accessibility gesture at the resolved control center instead; Vortex retains its existing ACTION_CLICK behavior. Gesture bounds are validated in the same full real-display coordinate space used by `AccessibilityNodeInfo#getBoundsInScreen` and Android accessibility gestures, with resource metrics only as fallback; this keeps valid controls beside system bars and the native bottom taskbar from being rejected as off-display. RiftOS self-Back is dispatched through the actual foreground `MainActivity.onBackPressed()` path so Start/native-window handling reaches `RiftNativeDesktop.handleBack()` instead of trusting a false-positive global Accessibility Back result. Live agent commands are non-reversible and are rejected by RiftShell atomic batches. Dev Lab agent commands remain outside generic `batch` for the same reason: Dev Lab owns its own staged/snapshot/publication transaction semantics. Use `riftos-agent devlab stage-file` and `run-file` for exact multi-line/quoted source payloads.

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

Repository transport validation locks the hard-coded package, Android XML package filter, BIND_ACCESSIBILITY_SERVICE permission, absence of arbitrary package arguments/root/ADB execution, password rejection, native route, shell command and batch exclusion. An Android build remains the compile gate. Device smoke should enable the service manually once, then run `status`, `open`, `tree`, one semantic click, one coordinate tap/swipe, text entry into a harmless Vortex field, Back, and verify the agent rejects actions whenever another app is foreground.
