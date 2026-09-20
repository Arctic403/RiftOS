# RiftOS Android-Native Architecture

## Current architecture

RiftOS is an Android-native desktop/runtime with Chromium restricted to explicit RiftBrowser-owned surfaces.

```text
MainActivity
 -> RiftNativeDesktop
    -> RiftNativeSystemApps
    -> RiftNativeWorkspaceApps
 -> RiftMcpRuntime
    -> RiftNativeShell
    -> RiftNativeGit / fixed native services
 -> RiftBrowserWindow / RiftBrowserAppHost / RiftBrowserPreviewActivity
```

There is no trusted shell WebView, `RiftShellBridge`, or general `RiftNativeDispatcher`.

## Native authority

Android-native code owns desktop/window state, Terminal, Task Manager, Files, Editor, Dev Lab, Workspace Records, Settings, Git, MCP, native shell, local agents and fixed RiftLLM/Vortex service routes.

`RiftNativeShell` is process-owned by `RiftMcpRuntime` while the Android process is alive; Activity recreation does not explicitly destroy it, but Android process death does. `RiftHeadlessJsRuntime` hosts bounded QuickJS over exactly three packaged headless assets: `riftpp-core.js`, `riftvm.js`, and `semnexis-bootstrap.js`. Rift++ uses the first two; `semx` uses the Semnexis bootstrap asset.

Native Files owns user-granted Android document-tree access through persisted SAF permissions. The legacy shell mount entry point is retired.

## Chromium boundary

Only explicit `RiftBrowser*` classes may import WebKit APIs. The Gradle `validateRiftBrowserWebViewOwnership` preBuild task enforces this and fails a build if WebKit ownership escapes the allowlist.

RiftBrowser owns:
- main browser tabs/window;
- installed HTML application surfaces;
- preview Activity;
- exact-origin browser MCP compatibility;
- renderer crash containment.

Browser content does not receive native desktop, filesystem, shell, Keystore or general Android authority.

## Validation order

Source validators -> explicit push -> external Builder -> install -> live abuse. Source validation is not a substitute for Kotlin/Gradle compilation or device lifecycle testing.
