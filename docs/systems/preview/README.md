# Workspace File Preview

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

RiftBrowserPreviewActivity is RiftOS's explicit Chromium preview surface for workspace-local HTML/web content.

It is not:
- a general browser;
- a shell;
- an installed-app host;
- an MCP/native bridge.

It exists only to render content rooted beneath app-private RiftFS workspace.

## Source ownership

Primary:
- RiftBrowserPreviewActivity.kt
- MainActivity.kt launch helper
- AndroidManifest.xml
- RiftBrowserRendererCrashGuard.kt

Retired:
- RiftPreviewActivity.kt
- RiftNativeDispatcher preview routing

The retired preview Activity is absent.

## Manifest boundary

RiftBrowserPreviewActivity is:
- android:exported=false;
- resizeableActivity=true.

External apps cannot directly invoke the preview Activity through the manifest.

MainActivity is the current launcher.

## Launch path

MainActivity.openPreviewFromNativeShell(root, entry):
- launches RiftBrowserPreviewActivity on the UI thread;
- supplies root and entry extras.

The Preview Activity independently validates the root rather than trusting the caller.

## Workspace confinement

Backing workspace root:

<filesDir>/riftfs/workspace

The supplied root:
1. is normalized into segments;
2. is folded beneath that workspace root;
3. is canonicalized;
4. must equal workspace or remain beneath workspace;
5. must exist and be a directory.

Preview cannot select C:/, D:/ outside workspace, /Android SAF mounts or arbitrary Android files.

## Request-file confinement

For each local request:
- URI path is normalized;
- child path is resolved beneath previewRoot;
- canonical path must stay at/below previewRoot;
- directories map to index.html;
- missing extensionless routes may fall back to previewRoot/index.html;
- missing asset-like files return 404.

There is no file:// browsing.

WebView settings explicitly disable:
- allowFileAccess;
- allowContentAccess.

## Per-root origin isolation

Previous source used one shared riftpreview.local origin for every preview root.

That meant DOM storage/cookies from one project could share origin state with another preview project.

This audit replaced the shared host with a deterministic per-root host:

preview-<SHA-256-root-prefix>.riftos.local

The host suffix is derived from the canonical previewRoot path.

Therefore two different project roots receive different HTTPS origins while repeated preview of the same root retains a stable origin.

## Network isolation

Preview is workspace-local, not an alternate Internet browser.

Current controls:
- WebSettings.blockNetworkLoads=true;
- mixedContentMode=MIXED_CONTENT_NEVER_ALLOW;
- shouldOverrideUrlLoading allows only HTTPS to the exact per-root preview host;
- shouldInterceptRequest returns 403 for any request not using HTTPS + exact preview host.

The denial response states:

External preview networking is disabled

This closes the prior behavior where external HTTPS requests fell through to Chromium networking.

## JavaScript/storage

JavaScript remains enabled for local preview applications.

DOM storage remains enabled, but its origin is now isolated per preview root.

There is no:
- addJavascriptInterface;
- WebMessage native bridge;
- RiftShell injection;
- MCP injection;
- generic native dispatcher.

Preview JavaScript therefore has browser-page authority only within the isolated preview WebView.

## Local response serving

Approved preview-host requests are served directly from local files through shouldInterceptRequest.

MIME type:
- guessed from filename when possible;
- explicit fallback mappings for JS/MJS, JSON, SVG and WASM;
- otherwise application/octet-stream.

Text/JSON/JS/SVG responses receive UTF-8 encoding.

404 responses use no-store.

## Renderer failure

onRenderProcessGone:
- marks rendererGone;
- records browser renderer diagnostics through RiftBrowserRendererCrashGuard;
- destroys the dead WebView through the crash guard;
- finishes only the Preview Activity;
- returns true.

It does not request native shell/desktop/MCP restart.

## Back behavior

Back:
- goes through WebView history when possible;
- otherwise falls back to Activity back behavior.

## Destruction

Normal Activity destruction destroys the WebView.

If renderer loss already destroyed it, onDestroy does not destroy the dead renderer a second time.

## WebView ownership

Preview is one of the explicit RiftBrowser-named WebView owners allowed by the Gradle WebView ownership gate.

MainActivity remains renderer-free.

No generic preview WebView exists outside RiftBrowser ownership.

## Source fixes in this audit

- external HTTPS/navigation fall-through removed;
- non-preview requests now return 403;
- blockNetworkLoads enabled;
- shared preview origin replaced with deterministic per-root origin;
- source validator now locks workspace-local/per-root/network isolation.

## Critical invariants

- Activity remains non-exported;
- preview root stays beneath riftfs/workspace;
- requested files stay beneath previewRoot;
- no file/content access;
- network loads remain blocked;
- only exact per-root preview HTTPS origin is accepted;
- different roots do not share one preview origin;
- no shell/MCP/native bridge is injected;
- renderer crash closes preview only;
- WebView ownership remains RiftBrowser-only.

## Failure signatures

- Preview Activity becomes exported -> external activation regression;
- root canonicalization can leave workspace -> filesystem escape;
- request path can leave previewRoot -> content escape;
- external HTTPS request is allowed -> network isolation regression;
- all projects share one preview origin again -> cross-project storage regression;
- addJavascriptInterface/WebMessage shell/native bridge appears -> authority regression;
- preview renderer crash restarts/kills native desktop/MCP -> crash-scope regression;
- retired RiftPreviewActivity returns -> ownership regression.

## Fix map

Preview Activity/security/local serving -> RiftBrowserPreviewActivity.kt.

Launch -> MainActivity.kt.

Manifest exposure -> AndroidManifest.xml.

Renderer failure -> RiftBrowserRendererCrashGuard.kt.

WebView ownership gate -> android/app/build.gradle.kts.

## Validation

Second source audit must verify:
- non-exported manifest entry;
- retired preview class absent;
- only MainActivity launch caller;
- canonical workspace root check;
- request-level previewRoot containment;
- allowFileAccess/contentAccess false;
- blockNetworkLoads true;
- exact-origin navigation/request checks;
- per-root SHA-derived host;
- absence of JS/native bridges;
- renderer crash scope;
- Back/destroy behavior;
- source validator includes preview isolation.

Builder/device validation remains separate. The installed APK should later prove intercepted local content still loads correctly with blockNetworkLoads enabled.
