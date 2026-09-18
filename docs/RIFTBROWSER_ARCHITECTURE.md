# RiftBrowser Architecture

## Verification status

**BROWSER WINDOW COORDINATOR SOURCE-VERIFIED 2026-09-17; CHILD ENGINE/BRIDGE SUBSYSTEMS ARE AUDITED SEPARATELY.**

## Current path

```text
MainActivity
 -> RiftNativeDesktop WindowRecord("browser")
 -> attach RiftBrowserWindow.nativeWindowView()
 -> RiftBrowserWindow active tab
 -> RiftBrowserEngine
 -> RiftBrowserAndroidWebViewEngine
```

Desktop owns outer bounds/visibility. RiftBrowserWindow owns tab selection, renderer child visibility/lifecycle and file chooser routing. The backend owns WebView policy.

## Current caller reachability

Live external calls are open/close/state/back/inspect/Activity-result/lifecycle plus nativeWindowView attachment.

Navigate, forward, reload, desktop mode and tab-management functions exist but currently have no native UI caller.

The old BrowserWindow parallel `setVisible`/`setBounds` and no-op state-push path were removed because they duplicated Desktop authority and could make a minimized browser reappear during Activity resume.

## Visibility rule

Renderer lifecycle follows actual attached View visibility:
- hidden/detached -> all paused;
- visible + Activity resumed -> active tab resumed, others paused.

Activity resume cannot make a Desktop-minimized browser visible.

## Security/renderer rule

All WebKit imports remain confined to explicit RiftBrowser-owned backend/bridge/preview/crash classes as enforced by Gradle validation.

Exact-origin MCP, WebView security/auth/download handling and AI adapters belong to their own child subsystem audits and are not implicitly trusted by this coordinator verification.
