# RiftOS Source Documentation Ownership

This ledger assigns implementation/build/test/reference source files to the README responsible for documenting them. Ownership does **not** imply that a source is packaged, live, verified, trusted or device-proven. Runtime activation must be established from current build/source wiring.

For the current Android engine, Gradle packages only `src/riftpp-core.js` and `src/riftvm.js` from the `src/` tree. Other `src/` entries are retained reference/test/migration sources unless a later audit proves otherwise. `scripts/validate-rift-docs.mjs` checks that every maintained source has a documentation owner.

## Root/build sources

| Source | Owner |
| --- | --- |
| `index.html` | `docs/systems/boot/README.md` + `docs/systems/shell-ui/README.md` |
| `styles.css` | `docs/systems/shell-ui/README.md` + `docs/systems/desktop/README.md` |
| `package.json` | `docs/systems/build-validation/README.md` |
| `android/build.gradle.kts` | `docs/systems/build-validation/README.md` |
| `android/settings.gradle.kts` | `docs/systems/build-validation/README.md` |
| `android/gradle.properties` | `docs/systems/build-validation/README.md` |
| `android/app/build.gradle.kts` | `docs/systems/build-validation/README.md` + `docs/systems/engine/README.md` |
| `android/app/src/main/AndroidManifest.xml` | `docs/systems/android-host/README.md` + `docs/systems/riftllm-bridge/README.md` + `docs/systems/engine/README.md` |
| `android/app/src/main/res/values/styles.xml` | `docs/systems/android-host/README.md` + `docs/systems/shell-ui/README.md` |
| `android/app/src/main/res/xml/vortex_agent_accessibility.xml` | `docs/systems/android-host/README.md` + `docs/systems/vortex-agent/README.md` |
| `android/riftos-debug.keystore.b64` | `docs/systems/build-validation/README.md` (legacy signing input; policy-controlled) |

## JavaScript source (`src/`; ownership is separate from APK packaging)

| Source | Owner |
| --- | --- |
| `src/riftandroid-entry.js` | `docs/systems/boot/README.md` |
| `src/riftandroid-platform.js` | `docs/systems/android-host/README.md` |
| `src/riftandroid-preload.js` | `docs/systems/boot/README.md` |
| `src/riftapps-files.js` | `docs/systems/apps/README.md` |
| `src/riftapps.js` | `docs/systems/apps/README.md` |
| `src/riftcore.js` | `docs/systems/kernel/README.md` + `docs/systems/engine/README.md` + `docs/systems/riftfs/README.md` + `docs/systems/chat-handoff/README.md` + `docs/systems/dev-lab/README.md` |
| `src/riftdesktop-android-compat.js` | `docs/systems/desktop/README.md` |
| `src/riftdesktop-native-compat.js` | `docs/systems/desktop/README.md` |
| `src/riftdesktop-android.css` | `docs/systems/desktop/README.md` |
| `src/riftdesktop-android.js` | `docs/systems/desktop/README.md` |
| `src/riftdesktop-window-host.js` | `docs/systems/desktop/README.md` |
| `src/riftgit.js` | `docs/systems/git/README.md` |
| `src/riftvault.js` | `docs/systems/riftvault/README.md` |
| `src/riftrepo.js` | `docs/systems/riftrepo/README.md` |
| `src/riftmemory-control.js` | `docs/systems/riftmemory/README.md` |
| `src/riftbuild.js` | `docs/systems/riftbuild/README.md` |
| `src/riftlocal-platform.js` | `docs/systems/riftrepo/README.md` + `docs/systems/riftvault/README.md` + `docs/systems/riftbuild/README.md` + `docs/systems/riftmemory/README.md` |
| `src/riftllm-bridge.js` | `docs/systems/riftllm-bridge/README.md` |
| `src/riftdevlab.js` | `docs/systems/dev-lab/README.md` + `docs/systems/workspace/README.md` |
| `src/riftmcp-system.js` | `docs/systems/mcp/README.md` |
| `src/riftos.js` | `docs/systems/shell-ui/README.md`, `docs/systems/boot/README.md`, `docs/systems/riftllm-bridge/README.md`, `docs/systems/files-app/README.md`, `docs/systems/settings/README.md`, `docs/systems/shell/README.md`, `docs/systems/browser/README.md`, `docs/systems/chat-handoff/README.md`, `docs/systems/dev-lab/README.md`, `docs/systems/vortex-agent/README.md`, `docs/systems/riftrepo/README.md`, `docs/systems/riftvault/README.md`, `docs/systems/riftbuild/README.md`, `docs/systems/riftmemory/README.md` |
| `src/riftrt.js` | `docs/systems/riftrt/README.md` + `docs/systems/riftrt/engines/README.md` + engine-specific READMEs under `docs/systems/riftrt/engines/` |
| `src/riftvm.js` | `docs/systems/riftrt/engines/rift-vm/README.md` + `docs/systems/engine/README.md` |
| `src/riftpp-core.js` | `docs/systems/riftpp-core/README.md` + `docs/systems/engine/README.md` |
| `src/riftruntime.js` | `docs/systems/runtime-capabilities/README.md` + `docs/systems/engine/README.md` |
| `src/riftshell-batch.js` | `docs/systems/shell/README.md` + `docs/systems/chat-handoff/README.md` |
| `src/riftworkspace-android-adapter.js` | `docs/systems/workspace/README.md` |
| `src/riftworkspace-live-host.js` | `docs/systems/workspace/live/README.md` |
| `src/riftworkspace-web.js` | `docs/systems/workspace/README.md` |

## Android Kotlin

| Source | Owner |
| --- | --- |
| `android/app/src/main/java/com/riftos/app/RiftBrowserAndroidWebViewEngine.kt` | `docs/systems/browser/engine/README.md` + `docs/systems/browser/engine/android-webview/README.md` |
| `android/app/src/main/java/com/riftos/app/MainActivity.kt` | `docs/systems/android-host/README.md` + `docs/systems/engine/README.md` + `docs/systems/boot/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftBrowserEngine.kt` | `docs/systems/browser/engine/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftBrowserMcpAppBridge.kt` | `docs/systems/browser/mcp-compat/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftBrowserWindow.kt` | `docs/systems/browser/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftMcpActivity.kt` | `docs/systems/mcp/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftMcpRelayClient.kt` | `docs/systems/mcp/relay/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftMcpRuntime.kt` | `docs/systems/mcp/README.md` + `docs/systems/engine/README.md` + `docs/systems/boot/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftMcpServer.kt` | `docs/systems/mcp/server/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftNativeDesktop.kt` | `docs/systems/desktop/README.md` + `docs/systems/android-host/README.md` + `docs/systems/engine/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftNativeSystemApps.kt` | `docs/systems/shell-ui/README.md` + `docs/systems/shell/README.md` + `docs/systems/desktop/README.md` + `docs/systems/engine/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftBrowserAppHost.kt` | `docs/systems/riftrt/README.md` + `docs/systems/apps/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftBrowserRendererCrashGuard.kt` | `docs/systems/android-host/README.md` + `docs/systems/diagnostics/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftVolumePaths.kt` | `docs/systems/riftfs/README.md` + `docs/systems/engine/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftLlmDevClient.kt` | `docs/systems/riftllm-bridge/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftBrowserPreviewActivity.kt` | `docs/systems/preview/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftProjectExporter.kt` | `docs/systems/mcp/project-exporter/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftRelaySettings.kt` | `docs/systems/mcp/relay/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftSecretStore.kt` | `docs/systems/secrets/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt` | `docs/systems/shell/README.md` + `docs/systems/mcp/README.md` + `docs/systems/engine/README.md` + `docs/systems/kernel/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftShellExecutor.kt` | `docs/systems/shell/README.md` + `docs/systems/mcp/README.md` + `docs/systems/engine/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt` | `docs/systems/shell/README.md` + `docs/systems/riftpp-core/README.md` + `docs/systems/engine/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftNativeShellServices.kt` | `docs/systems/shell/README.md` + `docs/systems/riftllm-bridge/README.md` + `docs/systems/dev-lab/README.md` + `docs/systems/vortex-agent/README.md` + `docs/systems/engine/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftNativeGit.kt` | `docs/systems/git/README.md` + `docs/systems/secrets/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftNativeWorkspaceApps.kt` | `docs/systems/files-app/README.md` + `docs/systems/settings/README.md` + `docs/systems/dev-lab/README.md` + `docs/systems/workspace/live/README.md` + `docs/systems/engine/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftNativeDevLab.kt` | `docs/systems/dev-lab/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftExperimentalCli.kt` | `docs/systems/experimental-cli/README.md` + `docs/systems/experimental-cli/PATCH_LIFECYCLE_V1.md` + `docs/systems/shell/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftDocumentationParityV1.kt` | `docs/systems/experimental-cli/PATCH8_DOCUMENTATION_PARITY.md` + `docs/systems/experimental-cli/README.md` + `docs/systems/build-validation/README.md` + `docs/PATCH_HISTORY.md` |
| `android/app/src/main/java/com/riftos/app/RiftCliPatchLifecycleV1.kt` | `docs/systems/experimental-cli/PATCH_LIFECYCLE_V1.md` + `docs/systems/experimental-cli/README.md` + `docs/RIFT_AI_PATCH_PIPELINE.md` + `docs/RIFT_DEVELOPMENT_WORKFLOW.md` + `docs/systems/build-validation/README.md` + `docs/PATCH_HISTORY.md` |
| `android/app/src/main/java/com/riftos/app/RiftResearchLedgerV1.kt` | `docs/systems/experimental-cli/PATCH_LIFECYCLE_V1.md` + `docs/RIFT_AI_PATCH_PIPELINE.md` + `docs/systems/build-validation/README.md` + `docs/PATCH_HISTORY.md` |
| `android/app/src/main/java/com/riftos/app/RiftPlusPlusV0.kt` | `docs/systems/experimental-cli/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftIrV1.kt` | `docs/systems/experimental-cli/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftIrCliV1.kt` | `docs/systems/experimental-cli/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftSwarmCoordinatorV0.kt` | `docs/systems/experimental-cli/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftTextEncoderTaskRunner.kt` | `docs/systems/experimental-cli/README.md` + `docs/systems/shell/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftTrainDataTaskRunner.kt` | `docs/systems/riftllm-bridge/README.md` + `docs/systems/shell/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftToolHost.kt` | `docs/systems/mcp/tool-host/README.md` + `docs/systems/engine/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt` | `docs/systems/mcp/sandbox/README.md` + `docs/systems/engine/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftSourceIntelligenceV2.kt` | `docs/systems/mcp/sandbox/README.md` + `docs/systems/workspace/live/README.md` + `docs/systems/workspace/README.md` + `docs/systems/engine/README.md` + `docs/systems/build-validation/README.md` + `docs/PATCH_HISTORY.md` |
| `android/app/src/main/java/com/riftos/app/RiftVortexBridgeClient.kt` | `docs/systems/vortex-bridge/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftVortexLocalAgent.kt` | `docs/systems/vortex-agent/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftChatHandoff.kt` | `docs/systems/chat-handoff/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftDiffEngineV2.kt` | `docs/systems/workspace/live/README.md` + `docs/systems/workspace/README.md` + `docs/systems/build-validation/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftFileIdentityV2.kt` | `docs/systems/workspace/live/README.md` + `docs/systems/workspace/README.md` + `docs/systems/build-validation/README.md` + `docs/systems/mcp/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftPatchManifestV1.kt` | `docs/systems/workspace/live/README.md` + `docs/systems/workspace/README.md` + `docs/systems/build-validation/README.md` + `docs/PATCH_HISTORY.md` |
| `android/app/src/main/java/com/riftos/app/RiftPatchSessions.kt` | `docs/systems/workspace/live/README.md` + `docs/systems/workspace/README.md` + `docs/systems/mcp/sandbox/README.md` + `docs/systems/shell/README.md` + `docs/systems/files-app/README.md` + `docs/systems/dev-lab/README.md` + `docs/systems/git/README.md` + `docs/systems/build-validation/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftWorkspaceRecords.kt` | `docs/systems/workspace/live/README.md` + `docs/systems/mcp/README.md` + `docs/systems/engine/README.md` |
| `android/app/src/main/java/com/riftos/app/RiftWorkspaceWatcher.kt` | `docs/systems/workspace/live/README.md` + `docs/systems/engine/README.md` + `docs/systems/boot/README.md` |

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
| `scripts/test-rift-dev-lab.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/dev-lab/README.md` |
| `scripts/test-rift-raw-protocol.mjs` | `docs/systems/build-validation/README.md` + browser MCP compatibility |
| `scripts/test-rift-workspace-records.mjs` | `docs/systems/build-validation/README.md` + workspace records |
| `scripts/test-rift-diff-engine-v2.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/workspace/live/README.md` |
| `scripts/test-rift-file-identity-v2.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/workspace/live/README.md` |
| `scripts/test-rift-patch-sessions.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/workspace/live/README.md` + `docs/systems/mcp/sandbox/README.md` |
| `scripts/test-rift-patch-manifest-v1.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/workspace/live/README.md` + `docs/PATCH_HISTORY.md` |
| `scripts/test-rift-semantic-impact-v1.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/mcp/sandbox/README.md` + `docs/systems/workspace/live/README.md` + `docs/PATCH_HISTORY.md` |
| `scripts/test-rift-cli-patch-lifecycle-v1.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/experimental-cli/PATCH_LIFECYCLE_V1.md` + `docs/PATCH_HISTORY.md` |
| `scripts/test-rift-documentation-parity-v1.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/experimental-cli/PATCH8_DOCUMENTATION_PARITY.md` + `docs/PATCH_HISTORY.md` |
| `scripts/test-rift-cli-stress-foundation.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/experimental-cli/PATCH_LIFECYCLE_V1.md` + `docs/PATCH_HISTORY.md` |
| `scripts/test-rift-shell-batch.mjs` | `docs/systems/build-validation/README.md` + shell |
| `scripts/test-rift-shell-git.mjs` | `docs/systems/build-validation/README.md` + Git |
| `scripts/test-rift-path-compat.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/riftfs/README.md` + shell/build/repo/Git/Vault/Memory/Dev Lab/RiftLLM/Files owners |
| `scripts/test-rift-local-platform.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/riftrepo/README.md` + `docs/systems/riftvault/README.md` + `docs/systems/riftbuild/README.md` + `docs/systems/riftmemory/README.md` |
| `scripts/test-riftllm-bridge.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/riftllm-bridge/README.md` |
| `scripts/test-riftllm-text-encoding-bridge.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/riftllm-bridge/README.md` |
| `scripts/test-riftllm-training-bridge.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/riftllm-bridge/README.md` |
| `scripts/validate-rift-wiring.mjs` | `docs/systems/build-validation/README.md` |
| `scripts/validate-rift-transport.mjs` | `docs/systems/build-validation/README.md` |
| `scripts/validate-rift-docs.mjs` | `docs/systems/build-validation/README.md` |

| `scripts/test-rift-shell-bridge.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/shell/README.md` |
| `scripts/test-riftllm-corpus.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/riftllm-bridge/README.md` |
| `scripts/test-rift-text-encoder-task.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/experimental-cli/README.md` |
| `scripts/test-rift-plus-plus-v0.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/experimental-cli/README.md` |
| `scripts/test-rift-ir-v1.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/experimental-cli/README.md` |
| `scripts/test-rift-plus-plus-core-v1.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/riftpp-core/README.md` |
| `scripts/test-riftpp-shell.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/shell/README.md` + `docs/systems/riftpp-core/README.md` |
| `scripts/test-rift-vm.mjs` | `docs/systems/build-validation/README.md` + `docs/systems/riftrt/engines/rift-vm/README.md` |

## Rule for new source

When a new maintained implementation/reference source, browser asset, relay source, Workspace Records source or validation script is added, add its exact repository-relative path here and either assign it to an existing system README or create a new system/subsystem README. Runtime activation is a separate question. Documentation validation is intentionally strict so ownership cannot silently decay.
