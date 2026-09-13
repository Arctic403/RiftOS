# RiftOS Active Source Ownership

This ledger assigns every active implementation/build/test source file to the README that owns its behavior. `scripts/validate-rift-docs.mjs` checks this file so newly added active source cannot silently become undocumented.

## Root/build sources

| Source | Owner |
| --- | --- |
| `index.html` | `docs/systems/boot/README.md` + `docs/systems/shell-ui/README.md` |
| `styles.css` | `docs/systems/shell-ui/README.md` + `docs/systems/desktop/README.md` |
| `package.json` | `docs/systems/build-validation/README.md` |
| `android/build.gradle.kts` | `docs/systems/build-validation/README.md` |
| `android/settings.gradle.kts` | `docs/systems/build-validation/README.md` |
| `android/gradle.properties` | `docs/systems/build-validation/README.md` |
| `android/app/build.gradle.kts` | `docs/systems/build-validation/README.md` |
| `android/app/src/main/AndroidManifest.xml` | `docs/systems/android-host/README.md` |
| `android/app/src/main/res/values/styles.xml` | `docs/systems/android-host/README.md` + `docs/systems/shell-ui/README.md` |
| `android/riftos-debug.keystore.b64` | `docs/systems/build-validation/README.md` (legacy signing input; policy-controlled) |

## Web runtime (`src/`)

| Source | Owner |
| --- | --- |
| `src/riftandroid-entry.js` | `docs/systems/boot/README.md` |
| `src/riftandroid-platform.js` | `docs/systems/android-host/README.md` |
| `src/riftandroid-preload.js` | `docs/systems/boot/README.md` |
| `src/riftapps-files.js` | `docs/systems/apps/README.md` |
| `src/riftapps.js` | `docs/systems/apps/README.md` |
| `src/riftcore.js` | `docs/systems/kernel/README.md` + `docs/systems/riftfs/README.md` |
| `src/riftdesktop-android-compat.js` | `docs/systems/desktop/README.md` |
| `src/riftdesktop-android.css` | `docs/systems/desktop/README.md` |
| `src/riftdesktop-android.js` | `docs/systems/desktop/README.md` |
| `src/riftdesktop-window-host.js` | `docs/systems/desktop/README.md` |
| `src/riftgit.js` | `docs/systems/git/README.md` |
| `src/riftmcp-system.js` | `docs/systems/mcp/README.md` |
| `src/riftos.js` | `docs/systems/shell-ui/README.md`, `docs/systems/boot/README.md`, `docs/systems/files-app/README.md`, `docs/systems/settings/README.md`, `docs/systems/shell/README.md`, `docs/systems/browser/README.md` |
| `src/riftrt.js` | `docs/systems/riftrt/README.md` + `docs/systems/riftrt/engines/README.md` + engine-specific READMEs under `docs/systems/riftrt/engines/` |
| `src/riftruntime.js` | `docs/systems/runtime-capabilities/README.md` |
| `src/riftshell-batch.js` | `docs/systems/shell/README.md` |
| `src/riftworkspace-android-adapter.js` | `docs/systems/workspace/README.md` |
| `src/riftworkspace-live-host.js` | `docs/systems/workspace/live/README.md` |
| `src/riftworkspace-web.js` | `docs/systems/workspace/README.md` |

## Android Kotlin

| Source | Owner |
| --- | --- |
| `android/app/src/main/java/com/riftos/app/AndroidWebViewBrowserEngine.kt` | `docs/systems/browser/engine/README.md` + `docs/systems/browser/engine/android-webview/README.md` |
| `android/app/src/main/java/com/riftos/app/MainActivity.kt` | `docs/systems/android-host/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftBrowserEngine.kt` | `docs/systems/browser/engine/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftBrowserMcpAppBridge.kt` | `docs/systems/browser/mcp-compat/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftBrowserWindow.kt` | `docs/systems/browser/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftMcpActivity.kt` | `docs/systems/mcp/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftMcpRelayClient.kt` | `docs/systems/mcp/relay/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftMcpRuntime.kt` | `docs/systems/mcp/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftMcpServer.kt` | `docs/systems/mcp/server/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftNativeDispatcher.kt` | `docs/systems/native-dispatcher/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftPreviewActivity.kt` | `docs/systems/preview/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftProjectExporter.kt` | `docs/systems/mcp/project-exporter/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftRelaySettings.kt` | `docs/systems/mcp/relay/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftSecretStore.kt` | `docs/systems/secrets/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftShellBridge.kt` | `docs/systems/shell/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftSystemDump.kt` | `docs/systems/diagnostics/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftToolHost.kt` | `docs/systems/mcp/tool-host/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt` | `docs/systems/mcp/sandbox/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftTransferManifest.kt` | `docs/systems/transfers/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftWorkspaceRecords.kt` | `docs/systems/workspace/live/README.md` + `docs/systems/mcp/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftWorkspaceWatcher.kt` | `docs/systems/workspace/live/README.md` |

## Browser-injected assets

| Source | Owner |
| --- | --- |
| `android/app/src/main/assets/riftbrowser-mcp-app.js` | `docs/systems/browser/mcp-compat/README.md` |
| `android/app/src/main/assets/adapters/ai-adapter-registry.js` | `docs/systems/browser/ai-adapters/README.md` |

## Workspace Records

| Source | Owner |
| --- | --- |
| `workspace-live/index.html` | `docs/systems/workspace/live/README.md` |
| `workspace-live/style.css` | `docs/systems/workspace/live/README.md` |
| `workspace-live/app.js` | `docs/systems/workspace/live/README.md` |

## Relay service

| Source | Owner |
| --- | --- |
| `relay/src/index.js` | `docs/systems/relay-service/README.md` |
| `relay/package.json` | `docs/systems/relay-service/README.md` |
| `relay/wrangler.jsonc` | `docs/systems/relay-service/README.md` |

## Validation/test sources

| Source | Owner |
| --- | --- |
| `scripts/test-rift-ai-adapters.mjs` | `docs/systems/build-validation/README.md` + AI adapters |
| `scripts/test-rift-app-import.mjs` | `docs/systems/build-validation/README.md` + apps |
| `scripts/test-rift-raw-protocol.mjs` | `docs/systems/build-validation/README.md` + browser MCP compatibility |
| `scripts/test-rift-shell-batch.mjs` | `docs/systems/build-validation/README.md` + shell |
| `scripts/test-rift-shell-git.mjs` | `docs/systems/build-validation/README.md` + Git |
| `scripts/validate-rift-wiring.mjs` | `docs/systems/build-validation/README.md` |
| `scripts/validate-rift-transport.mjs` | `docs/systems/build-validation/README.md` |
| `scripts/validate-rift-docs.mjs` | `docs/systems/build-validation/README.md` |

## Rule for new source

When a new active implementation, browser asset, relay source, Workspace Records source or validation script is added, add its exact repository-relative path here and either assign it to an existing system README or create a new system/subsystem README. Documentation validation is intentionally strict so ownership cannot silently decay.
