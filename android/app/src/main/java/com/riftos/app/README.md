# Native RiftOS Components

This package contains Android-side authorities and services. Keep class ownership narrow; do not solve a subsystem bug by duplicating its policy in another class.

| Component | Owner |
| --- | --- |
| `MainActivity.kt` | [`android-host`](../../../../../../../../docs/systems/android-host/README.md) |
| `RiftNativeDispatcher.kt` | [`native-dispatcher`](../../../../../../../../docs/systems/native-dispatcher/README.md) |
| `RiftTransferManifest.kt` + transfer logic in `RiftNativeDispatcher.kt` | [`transfers`](../../../../../../../../docs/systems/transfers/README.md) |
| `RiftBrowserWindow.kt` | [`browser`](../../../../../../../../docs/systems/browser/README.md) |
| `RiftBrowserEngine.kt`, `AndroidWebViewBrowserEngine.kt` | [`browser/engine`](../../../../../../../../docs/systems/browser/engine/README.md) |
| `RiftBrowserMcpAppBridge.kt` | [`browser/mcp-compat`](../../../../../../../../docs/systems/browser/mcp-compat/README.md) |
| `RiftMcpRuntime.kt`, `RiftMcpActivity.kt` | [`mcp`](../../../../../../../../docs/systems/mcp/README.md) |
| `RiftMcpServer.kt` | [`mcp/server`](../../../../../../../../docs/systems/mcp/server/README.md) |
| `RiftToolHost.kt` | [`mcp/tool-host`](../../../../../../../../docs/systems/mcp/tool-host/README.md) |
| `RiftToolSandbox.kt` | [`mcp/sandbox`](../../../../../../../../docs/systems/mcp/sandbox/README.md) |
| `RiftMcpRelayClient.kt`, `RiftRelaySettings.kt` | [`mcp/relay`](../../../../../../../../docs/systems/mcp/relay/README.md) |
| `RiftProjectExporter.kt` | [`mcp/project-exporter`](../../../../../../../../docs/systems/mcp/project-exporter/README.md) |
| `RiftShellBridge.kt` | [`shell`](../../../../../../../../docs/systems/shell/README.md) |
| `RiftWorkspaceRecords.kt`, `RiftWorkspaceWatcher.kt` | [`workspace/live`](../../../../../../../../docs/systems/workspace/live/README.md) |
| `RiftPreviewActivity.kt` | [`preview`](../../../../../../../../docs/systems/preview/README.md) |
| `RiftSystemDump.kt` | [`diagnostics`](../../../../../../../../docs/systems/diagnostics/README.md) |
| `RiftSecretStore.kt` | [`secrets`](../../../../../../../../docs/systems/secrets/README.md) |

`docs/SOURCE_OWNERSHIP.md` is the machine-validated complete ownership ledger.
