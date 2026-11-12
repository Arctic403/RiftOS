# RiftOS Host — Blockbench Mobile Accessibility controller (2026-11-12)

## Why this is needed
The installed RiftOS Local Agent's UI functions are scoped to com.riftos.app. An attempted local-agent open with args.package=net.blockbench.androidcommunity actually launched RiftOS Recovery because the argument was ignored by the existing fixed-scope agent. No Blockbench UI screenshot or project opening was verified.

## Host-only source patch
- Add an explicit second fixed Accessibility target called blockbench-mobile, package net.blockbench.androidcommunity.
- The Local Agent request uses args.targetApp=blockbench-mobile for status/open/tree/click/tap/swipe/type/type-focused/back/screenshot. An omitted targetApp retains existing RiftOS behavior. Other target IDs and raw package strings are rejected.
- The existing Accessibility service XML now includes Blockbench Mobile in its packageNames allowlist.
- AndroidManifest queries includes only that exact installed package; no QUERY_ALL_PACKAGES permission added.
- Add Android AccessibilityService.takeScreenshot API30+ support, gated on Blockbench's active app window. The screenshot is resized and JPEG compressed to <=56 KiB, base64 returned inside the existing bounded Local Agent result. This is a genuine Android display screenshot (not a WebView.draw composite).
- Add android:canTakeScreenshot=true in the AccessibilityService metadata. Android may require turning the service off and back on after installation to adopt the capability.
- Screenshot op added to local-agent-batch operation validation and MCP tool schema. No new MCP tool name or external Core change.

## Manual Host APK build/install (user-owned)
Push is source-only. User triggers the existing RiftOS GitHub APK builder, then updates the RiftOS Host APK using the same signing key. Existing external Core v0.6.24 and selected external Shell are separate and should be preserved. Do not auto-stage, auto-select or overwrite external Core/Shell.

## Actual live test after updated Host installed
Run one bounded Local Agent batch with these steps (one per row or together):

  op=status, args={"targetApp":"blockbench-mobile"}
  op=open, args={"targetApp":"blockbench-mobile"}
  op=tree, args={"targetApp":"blockbench-mobile","limit":250}
  op=screenshot, args={"targetApp":"blockbench-mobile"}

Expected: status scope net.blockbench.androidcommunity, installed=true, accessibility_connected=true; open launches Blockbench, tree contains UI nodes under net.blockbench.androidcommunity, screenshot returns mimeType=image/jpeg and imageBase64.

Use the screenshot to inspect Edit/Paint/Animate tabs and the small floating R chip. Note that screenshot includes the Android display and UI; it should not capture content outside Blockbench because the target root is checked before taking it.

Then use the already-live signer-pinned Blockbench Binder tool (no new tool approval scope) to call:

  tool call blockbench-mobile /D:/Workspace/BlockbenchAndroid/android/bridge-tests/project-summary.json

Obtain the actual returned requestId and fetch its result. For a QA import, first ensure Blockbench app is foreground, and use the PRIVATE RiftOS request:

  tool call blockbench-mobile /D:/Workspace/BlockbenchQAResults/import-back-into-blockbench.json

Recheck project summary, list cubes, capture screenshot through Accessibility again, capture a Blockbench 3D preview, export to private RiftOS workspace, and compare the round-tripped geometry. Original user projects must remain unmodified.

## Source / device distinction
This patch was not installed or compiled by the assistant. Device validation of the exact new flow remains pending. The original hardcoded RiftOS agent has already been verified live, which is why this Host patch is necessary.

## Android caveats
- Android can deny startActivity while RiftOS is in the background even when Accessibility service exists; the live open step must confirm target_foreground rather than trusting launched=true alone.
- Android's screenshot API may fail with an OS error code. Errors are returned, not silently treated as successful images.
- A screenshot is real-time only while Blockbench is actually visible; this flow intentionally foregrounds the app and differs from headless editing.
- Accessibility permission should be explicitly enabled for RiftOS; if screenshots are denied after Host update, toggle RiftOS Local UI Agent off/on in Android Settings and try again.
