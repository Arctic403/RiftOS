# APK-owned Kotlin surface

This directory contains the Android host, protected external component ABI/loading/Recovery, IPC and runtime adapters, MCP and Local Agent, RiftFS/workspace/RiftGit, browser/WebView and generic Android tool/installer services.

Stable host: `RiftCoreApplication`, `RiftShellActivity`, `RiftHostComponentAbiV1`, `RiftShellComponentAbiV1`, `RiftCoreCandidateSwitch`, `RiftShellCandidateSwitch`, protected manifest/digest/activation classes and manual Recovery.

Operating services: `RiftMcp*`, `RiftToolHost`, `RiftLocalAgentBatch`, `RiftVortexLocalAgent`, `RiftWorkspaceRecords`, `RiftWorkspaceWatcher`, `RiftVolumePaths`, `RiftNativeGit`.

Embedded Core/Shell implementations such as `RiftNativeDesktop`, `RiftCoreRuntime`, and `MainActivity` were deleted from this source candidate. `RiftNativeShell` remains the **MCP command executor**, not a graphical desktop. No embedded implementation TODOs or per-milestone test validations belong here.

For exact behavior use current Kotlin source and the repository-root `docs/README.md`, not retired embedded milestone reports.
