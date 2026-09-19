# Native RiftOS Components

This package contains Android-side authorities and services. Keep class ownership narrow; do not solve a subsystem bug by duplicating its policy in another class.

| Component | Owner |
| --- | --- |
| `MainActivity.kt` | [`android-host`](../../../../../../../../docs/systems/android-host/README.md) |
| `RiftNativeDesktop.kt` | [`desktop`](../../../../../../../../docs/systems/desktop/README.md) |
| `RiftNativeSystemApps.kt` | [`shell-ui`](../../../../../../../../docs/systems/shell-ui/README.md) + [`shell`](../../../../../../../../docs/systems/shell/README.md) |
| `RiftBrowserAppHost.kt` | [`riftrt`](../../../../../../../../docs/systems/riftrt/README.md) + [`apps`](../../../../../../../../docs/systems/apps/README.md) |
| `RiftBrowserRendererCrashGuard.kt` | [`browser`](../../../../../../../../docs/systems/browser/README.md) + [`diagnostics`](../../../../../../../../docs/systems/diagnostics/README.md) |
| `RiftVolumePaths.kt` | [`riftfs`](../../../../../../../../docs/systems/riftfs/README.md) |
| `RiftNativeWorkspaceApps.kt`, `RiftNativeDevLab.kt` | [`files-app`](../../../../../../../../docs/systems/files-app/README.md) + [`dev-lab`](../../../../../../../../docs/systems/dev-lab/README.md) + [`settings`](../../../../../../../../docs/systems/settings/README.md) + [`workspace/live`](../../../../../../../../docs/systems/workspace/live/README.md) |
| `RiftNativeGit.kt` | [`git`](../../../../../../../../docs/systems/git/README.md) + [`secrets`](../../../../../../../../docs/systems/secrets/README.md) |
| `RiftBrowserWindow.kt` | [`browser`](../../../../../../../../docs/systems/browser/README.md) |\n| `RiftBuildLocalExecutor.kt` | [`riftbuild`](../../../../../../../../docs/systems/riftbuild/README.md) + [`build-validation`](../../../../../../../../docs/systems/build-validation/README.md) |
| `RiftBrowserEngine.kt`, `RiftBrowserAndroidWebViewEngine.kt` | [`browser/engine`](../../../../../../../../docs/systems/browser/engine/README.md) |
| `RiftBrowserMcpAppBridge.kt` | [`browser/mcp-compat`](../../../../../../../../docs/systems/browser/mcp-compat/README.md) |
| `RiftMcpRuntime.kt`, `RiftMcpActivity.kt` | [`mcp`](../../../../../../../../docs/systems/mcp/README.md) |
| `RiftMcpServer.kt`, `RiftBoundedAsync.kt` | [`mcp/server`](../../../../../../../../docs/systems/mcp/server/README.md) + [`mcp/sandbox`](../../../../../../../../docs/systems/mcp/sandbox/README.md) |
| `RiftToolHost.kt` | [`mcp/tool-host`](../../../../../../../../docs/systems/mcp/tool-host/README.md) |
| `RiftToolSandbox.kt`, `RiftSourceIntelligenceV2.kt` | [`mcp/sandbox`](../../../../../../../../docs/systems/mcp/sandbox/README.md) + [`workspace/live`](../../../../../../../../docs/systems/workspace/live/README.md) |
| `RiftMcpRelayClient.kt`, `RiftRelaySettings.kt` | [`mcp/relay`](../../../../../../../../docs/systems/mcp/relay/README.md) |
| `RiftProjectExporter.kt` | [`mcp/project-exporter`](../../../../../../../../docs/systems/mcp/project-exporter/README.md) |
| `RiftNativeShell.kt`, `RiftShellExecutor.kt`, `RiftHeadlessJsRuntime.kt`, `RiftNativeShellServices.kt` | [`shell`](../../../../../../../../docs/systems/shell/README.md) + [`mcp`](../../../../../../../../docs/systems/mcp/README.md) + [`riftpp-core`](../../../../../../../../docs/systems/riftpp-core/README.md) + [`semnexis-bootstrap`](../../../../../../../../docs/systems/semnexis-bootstrap/README.md) |
| `RiftExperimentalCli.kt`, `RiftCliPatchLifecycleV1.kt`, `RiftDocumentationParityV1.kt`, `RiftVerificationPlannerV1.kt`, `RiftResearchLedgerV1.kt`, `RiftPlusPlusV0.kt`, `RiftIrV1.kt`, `RiftIrCliV1.kt`, `RiftSwarmCoordinatorV0.kt`, `RiftTextEncoderTaskRunner.kt` | [`experimental-cli`](../../../../../../../../docs/systems/experimental-cli/README.md) + [`patch lifecycle`](../../../../../../../../docs/systems/experimental-cli/PATCH_LIFECYCLE_V1.md) + [`shell`](../../../../../../../../docs/systems/shell/README.md) |
| `RiftDiffEngineV2.kt`, `RiftFileIdentityV2.kt`, `RiftPatchManifestV1.kt`, `RiftPatchSessions.kt`, `RiftWorkspaceRecords.kt`, `RiftWorkspaceWatcher.kt` | [`workspace/live`](../../../../../../../../docs/systems/workspace/live/README.md) + [`workspace`](../../../../../../../../docs/systems/workspace/README.md) |
| `RiftBrowserPreviewActivity.kt` | [`preview`](../../../../../../../../docs/systems/preview/README.md) |
| `RiftSecretStore.kt` | [`secrets`](../../../../../../../../docs/systems/secrets/README.md) |
| `RiftVortexBridgeClient.kt` | [`vortex-bridge`](../../../../../../../../docs/systems/vortex-bridge/README.md) |
| `RiftVortexLocalAgent.kt` | [`vortex-agent` + `riftos-agent`](../../../../../../../../docs/systems/vortex-agent/README.md) |
| `RiftLlmDevClient.kt` | [`riftllm-bridge`](../../../../../../../../docs/systems/riftllm-bridge/README.md) |
| `RiftChatHandoff.kt` | [`chat-handoff`](../../../../../../../../docs/systems/chat-handoff/README.md) |

`docs/SOURCE_OWNERSHIP.md` is the machine-validated complete ownership ledger.
