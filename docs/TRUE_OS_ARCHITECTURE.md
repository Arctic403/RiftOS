# RiftOS True OS Architecture

RiftOS is an Android application that supplies its own desktop/runtime/tooling environment while using Android framework authority underneath. It is not an Android kernel replacement.

## Authority graph

```text
Android process
├─ MainActivity
│  ├─ RiftNativeDesktop
│  ├─ RiftNativeSystemApps
│  ├─ RiftNativeWorkspaceApps
│  └─ RiftBrowser* renderer surfaces
├─ RiftMcpRuntime
│  ├─ RiftMcpServer / RiftToolHost / RiftToolSandbox
│  ├─ RiftNativeShell
│  ├─ RiftNativeGit
│  └─ process-owned local bridge clients
└─ app-private RiftFS / preferences / Keystore
```

The native desktop and built-ins do not depend on Chromium. Chromium exists only behind explicit RiftBrowser owners. A browser renderer crash therefore must not take down native shell/MCP/desktop authority.

## Filesystem

RiftFS is app-private storage with fixed C:/D: display aliases. Workspace MCP/Project Intelligence remains hard-scoped to the canonical `/workspace` tree. User-granted Android folders are persisted SAF document-tree mounts surfaced by native Files under `/Android`; they are not general filesystem escape paths.

## MCP and shell

The 19-tool MCP catalog is device-owned by `RiftToolHost`; `rift_debug` is a passive read-only diagnostics query. Normal filesystem/Code Mode tools use `RiftToolSandbox`; `rift_shell_exec` uses process-owned `RiftNativeShell`. There is no WebView compatibility fallback and no raw Linux/Android shell.

## Runtime and apps

Native built-ins are Android Views. Installed/browser HTML surfaces use dedicated RiftBrowser-owned WebViews with fixed origins/capability APIs. Current production Rift++ compiles to `rift-exec-v1` and executes on RiftVM through bounded headless QuickJS, not through Chromium.

## Security rules

- WebKit outside explicit RiftBrowser sources is a build failure.
- Credentials remain in Android Keystore-backed paths.
- browser guests never receive shell/filesystem/general native authority.
- workspace MCP tools cannot escape workspace.
- local UI agents remain fixed-package, user-enabled Accessibility authorities.
- no broad native dispatcher is reintroduced.

## Promotion gate

A local source migration is not proven until source checks, Builder compilation/package/signing, install and live abuse all pass.
