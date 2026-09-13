# RiftOS Vortex Local Agent

## Purpose

The Vortex local agent gives the trusted RiftOS shell hands on the real Vortex3D Android UI while keeping authority local to the phone. It is intentionally scoped to exactly `com.vortex3d.app`; there is no package argument, arbitrary-app mode, ADB path, root path, raw Android shell path, network listener or system-server hook.

The agent complements, rather than replaces, the Vortex Binder/VTXScript bridge. Use the local agent for Android UI behavior and real gestures; use `vortex ...`/VTXScript for engine/JNI semantics, deterministic state inspection and validation.

## Source ownership

- `android/app/src/main/java/com/riftos/app/RiftVortexLocalAgent.kt` — launch helper, Vortex-only AccessibilityService registration, UI-tree inspection, semantic click/text, gestures and Back.
- `android/app/src/main/res/xml/vortex_agent_accessibility.xml` — Android-enforced package filter and gesture/window-content capabilities.
- `android/app/src/main/AndroidManifest.xml` — declares the Accessibility service behind `android.permission.BIND_ACCESSIBILITY_SERVICE`.
- `RiftNativeDispatcher.kt` — finite `vortex.agent` native route.
- `src/riftos.js` — `vortex-agent` shell family.

## Authority boundary

`vortex-agent open` uses Android's normal launch intent for the hard-coded package `com.vortex3d.app`. It works whenever Vortex3D is installed, even when the Accessibility service is disabled.

All UI inspection and interaction requires the user to enable **RiftOS Vortex Agent** in Android Accessibility settings. Android's service metadata filters events to `com.vortex3d.app`, and every action additionally rejects any active root whose package is not exactly `com.vortex3d.app`. Password nodes are never returned with text and cannot be clicked or edited by semantic actions.

The service does not request root, ADB, shell execution, screen-overlay authority, unrestricted package control or remote/network control. ChatGPT reaches it only through the already-existing `rift_shell_exec` -> trusted RiftShell path.

## Commands

- `vortex-agent status` — installed/service/foreground/action readiness.
- `vortex-agent open` — launch or bring Vortex3D forward using its normal Android package intent.
- `vortex-agent tree [limit]` — bounded Vortex-only Accessibility tree.
- `vortex-agent click <text|content-description|view-id>` — exact semantic match, with clickable-parent fallback inside Vortex only.
- `vortex-agent tap <x> <y>` — real Android accessibility gesture within display bounds while Vortex is foreground.
- `vortex-agent swipe <x1> <y1> <x2> <y2> [ms]` — bounded real gesture while Vortex is foreground.
- `vortex-agent type <target> <text>` — ACTION_SET_TEXT on an exact editable, non-password Vortex node.
- `vortex-agent back` — Android Back only when Vortex is currently foreground.

Live agent commands are non-reversible and are rejected by RiftShell atomic batches.

## Failure signatures

- `open` says Vortex is not installed -> package `com.vortex3d.app` is absent or build/install failed.
- `accessibility_connected=false` -> enable RiftOS Vortex Agent in Android Accessibility settings.
- action says foreground package must be Vortex -> Vortex is not the active accessibility window; run `vortex-agent open` and retry after it appears.
- semantic target not found -> inspect `vortex-agent tree`; the view may lack text/content-description/resource id, in which case use a bounded coordinate gesture.
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
