# RiftOS Development Workflow

## Current source ownership rule

Patch the narrow owner:
- desktop/window behavior -> `RiftNativeDesktop.kt`;
- built-ins -> `RiftNativeSystemApps.kt` / `RiftNativeWorkspaceApps.kt`;
- shell -> `RiftNativeShell.kt` / `RiftNativeShellServices.kt`;
- Git -> `RiftNativeGit.kt`;
- workspace MCP -> `RiftToolSandbox.kt`;
- MCP catalog/server -> `RiftToolHost.kt` / `RiftMcpServer.kt`;
- browser/app/preview rendering -> explicit `RiftBrowser*` source;
- Rift++ compiler/VM -> `src/riftpp-core.js`, `src/riftvm.js`, `RiftHeadlessJsRuntime.kt`.

Do not recreate the deleted broad native dispatcher or trusted shell WebView to shortcut ownership boundaries.

## Safe change sequence

1. inspect source/ownership/docs and identify the true owner;
2. preserve a backup for large architecture changes;
3. make a small guarded local patch;
4. update the owning README/validator in the same patch;
5. run static/source audits and diff review;
6. push only when explicitly authorized;
7. run the external Builder;
8. install the exact built APK;
9. live-abuse the changed subsystem and its boundaries;
10. rerun validators after fixes before promotion.

Dev Lab may stage/snapshot/publish source locally, but compiled Android changes still require Builder/install.

## Migration-specific rules

- no WebKit imports outside the explicit RiftBrowser allowlist;
- only Rift++ Core/VM JS assets are copied into the generated headless asset namespace;
- native shell/MCP must survive browser/Activity lifecycle changes;
- browser crash recovery is scoped to browser-owned renderers;
- source checks never count as a successful Android build;
- do not push local RiftOS changes without explicit project-owner instruction.
