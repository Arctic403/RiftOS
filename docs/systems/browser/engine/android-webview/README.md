# Android WebView Browser Engine

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

`RiftBrowserAndroidWebViewEngine` is the current concrete implementation of `RiftBrowserEngine` using Android System WebView.

It owns WebView-specific rendering, browser security policy, auth popups, downloads, Desktop Site identity, bounded live-page inspection, exact-origin MCP bridge installation, and renderer-death reporting.

It does not own RiftDesktop geometry/visibility or native shell/filesystem authority.

## Source ownership

Primary:
- `RiftBrowserAndroidWebViewEngine.kt`

Direct collaborators:
- `RiftBrowserWindow.kt` — creates/destroys engines and performs one bounded replacement after main-renderer loss.
- `RiftBrowserMcpAppBridge.kt` — exact-origin MCP compatibility.
- `RiftBrowserRendererCrashGuard.kt` — privacy-limited renderer-loss diagnostics and dead-WebView destruction.

## Main WebView security configuration

Main WebView enables JavaScript, DOM storage, default caching, multiple windows, user-gesture media playback and hardware rendering.

It explicitly blocks mixed content; disables file/content/file-URL/universal-file-URL access and geolocation; enables Safe Browsing where supported; denies WebView permission requests; and cancels SSL-error navigation.

There is no generic native dispatcher exposed to the page.

## Navigation policy

For main-frame navigation:
- HTTPS is allowed;
- HTTP is upgraded to HTTPS;
- `mailto:`, `tel:`, and `geo:` are handed to Android external intents;
- other schemes are blocked.

The same HTTPS-upgrade/external-scheme rule applies to popup WebViews.

## Cookies and authentication

Global WebView cookie acceptance is enabled.

The main WebView allows third-party cookies only for recognized HTTPS authentication flows, including ChatGPT/OpenAI hosts and selected Google/Microsoft/Apple identity providers. Other main-page navigation disables third-party cookies.

Popup WebViews enable third-party cookies for their short-lived auth role and share Android WebView cookie storage. Page finish flushes CookieManager.

## Auth popups

A popup is created only when WebView requested a new window from an HTTPS opener and the request is user-gesture initiated.

The popup is a real child WebView in the owning renderer container, remains connected through `WebViewTransport`, has a native close button, inherits current Desktop Site identity, keeps file/content access disabled and mixed content blocked, denies geolocation and permission requests, cancels SSL errors, and is destroyed on close, engine destroy or Desktop Site mode change.

Popup renderer death is handled locally: the event is recorded, the popup host is removed and the dead popup WebView is destroyed. It does not trigger main-tab replacement.

## Desktop Site mode

At engine creation the backend captures Android WebView's default user agent and supported default UA metadata.

Desktop Site enabled uses a Windows desktop Chrome-style UA, sets non-mobile Windows UA metadata when supported, uses Desktop form factor when supported, enables overview/desktop viewport behavior and built-in zoom, applies the same identity to popups, and reloads an existing active page.

Desktop Site disabled restores the captured default Android WebView identity. UA metadata overrides are feature-gated.

## File chooser

The backend delegates `onShowFileChooser` to the parent BrowserWindow. Request/result ownership remains at the Browser coordinator/Activity boundary.

## Downloads

Only HTTPS download URLs are accepted.

Downloads use Android DownloadManager with a sanitized filename capped to 180 characters, MIME fallback, current user agent, current cookies when available, visible completed-download notification and destination inside RiftOS external-files Downloads.

The page receives no arbitrary raw filesystem access.

## Exact-origin MCP integration

The main WebView owns one `RiftBrowserMcpAppBridge`. It is installed at engine initialization and asked to `ensureInjected(url)` after main-page finish.

Origin/message/tool rules are audited separately in Browser MCP compatibility.

## Live-page inspector

Inspection is accepted only for an active HTTPS main page.

Actions:
- status;
- dom;
- inspect;
- focus;
- hide;
- show;
- text;
- attr;
- style;
- outline;
- reset.

Bounds:
- DOM result limit 1..100;
- selector <=512 characters;
- replacement text <=4096;
- attribute value <=1024;
- style value 1..512.

Selectors reject attribute/value selector syntax, quotes/equality selectors, `:has()`, and characters outside a narrow structural-selector allowlist.

Attribute writes are limited to class, title, aria-label, role and tabindex.

Style writes are limited to a fixed presentation/layout allowlist. Values reject URL/network/script/data/import constructs, semicolons and braces.

Password/sensitive form controls cannot be targeted for mutation, and form controls cannot be text-edited.

Inspector metadata exposes structural/layout information rather than form values, cookies, storage, headers, raw HTML or arbitrary page JavaScript.

## Renderer death

Popup loss is recorded/destroyed locally and returned handled to Android.

Main renderer loss:
1. captures previous URL;
2. marks the concrete engine crashed/unavailable;
3. records the event;
4. destroys the dead WebView;
5. calls `rendererGone(lastUrl)`;
6. returns handled.

`RiftBrowserRendererCrashGuard` does not restart Activity/process.

`RiftBrowserWindow` performs at most one automatic engine replacement per tab. A new engine is attached and the previous URL is reloaded only when HTTPS. A second renderer loss for that same tab is left failed rather than entering an unlimited recreate loop.

Closing/recreating a tab creates a fresh recovery budget.

## Crash diagnostics

Crash guard retains at most 16 privacy-limited events in app-private preferences: surface label, didCrash, renderer priority, timestamp and RiftOS process uptime. On supported Android versions it can also expose bounded historical process-exit metadata.

## State

Backend `state()` includes renderer id, URL/title, history capability, progress, crash flag, Desktop Site state, inspector-active state, popup-open state and MCP-bridge state.

Browser state is pull-based. The old no-op general `stateChanged` callback was removed during this audit.

The only backend-specific callback is main-renderer loss for bounded tab recovery.

## Destroy

Destroy removes/destroys popup and MCP bridge, stops/clears/destroys the main WebView and clears the renderer container.

## Non-ownership boundaries

This backend does not own Desktop geometry/minimize/maximize/taskbar, Browser tab registry, native browser controls, MCP tool execution, RiftFS/workspace permissions or generic Android capability dispatch. No general native dispatcher exists.

## Source cleanup/fixes in this audit

- removed dead/no-op `stateChanged` callback and page/progress/history notifications;
- corrected renderer-death architecture: crash guard records/destroys only;
- added bounded one-attempt same-tab engine replacement through BrowserWindow;
- popup renderer failure remains local cleanup only.

## Critical invariants

- mixed content blocked;
- SSL errors cancelled;
- file/content/file-URL authority disabled;
- geolocation and WebView permission requests denied;
- unknown schemes blocked;
- downloads HTTPS-only;
- popups require HTTPS + user gesture;
- exact-origin MCP remains separate;
- inspector cannot expose credential/form-value/raw-page secrets or arbitrary JS;
- renderer death is handled without killing native RiftOS;
- recovery is bounded;
- crash guard never claims to restart Activity/process.

## Failure signatures

- SSL handler proceeds -> TLS regression;
- page gains file/content native access -> WebView authority regression;
- camera/microphone/geolocation silently granted -> permission regression;
- HTTP remains plaintext -> navigation regression;
- unknown scheme launches externally -> intent regression;
- auth popup escapes renderer plane -> ownership regression;
- renderer death kills RiftOS -> unhandled renderer-loss regression;
- renderer auto-recreates without bound -> recovery-loop regression;
- docs say crash guard restarts Activity -> documentation regression;
- arbitrary selector/script/value extraction appears -> inspector regression.

## Fix map

WebView policy/navigation/auth/download/Desktop Site/inspector -> `RiftBrowserAndroidWebViewEngine.kt`.

Tab replacement/recovery budget -> `RiftBrowserWindow.kt`.

Crash storage/dead-WebView cleanup -> `RiftBrowserRendererCrashGuard.kt`.

MCP page bridge -> Browser MCP compatibility.

Outer visibility/geometry -> RiftBrowser/Desktop.

## Validation

Source verification must recheck all WebSettings flags; main/popup permission/geolocation/SSL handling; scheme routing; auth cookie rules; popup lifecycle; Desktop Site feature gates; downloads; inspector bounds; renderer-loss callback and one-recovery limit; absence of `stateChanged`; bridge injection; and destroy cleanup.

Installed-device validation must cover auth, cookies, Desktop Site UA/UA-CH, chooser/download, inspector and popup/main renderer loss with bounded recovery.
