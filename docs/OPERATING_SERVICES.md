# Host operating services: MCP, Local Agent, RiftFS and workspace

## MCP and relay

The APK includes `RiftMcpRuntime`, `RiftMcpServer`, `RiftMcpRelayClient`, `RiftMcpEventBus`, `RiftMcpOperationJournal`, `RiftToolHost`, `RiftToolSandbox`, and workspace/export/diff integrations. Remote relay implementation is owned independently by `/D:/Workspace/RiftMcpRelay`; the host owns its client and bounded JSON-RPC/tools boundary, not the Cloudflare Worker.

The Local Agent uses `RiftVortexAccessibilityService` and `RiftLocalAgentBatch` with explicit UI authority. Batch execution accepts up to 16 steps, validates plans, uses stop-on-error by default, supports result/status/cancel, and preserves request/job evidence. The workspace code-executor deliberately supports **one mutation operation per call**, not an unrestricted multi-operation batch. MCP journaling and reconcile functionality are host responsibilities.

## Filesystem and workspace

`RiftVolumePaths.kt` resolves Core-visible C:/D: virtual volume paths. `RiftWorkspaceRecords`, `RiftWorkspaceWatcher`, `RiftProjectExporter`, `RiftSourceIntelligenceV2`, `RiftDiffEngineV2`, `RiftFileIdentityV2` and `RiftNativeGit` support bounded workspace metadata, audit, source review, export and RiftGit.

Do not confuse a virtual C:/D: address with unrestricted Android storage. File access, tool grants, native process controls and Android Binder service access must remain behind their current permission and request limits. The graphical Shell's File Explorer is **external Shell code** consuming Core-owned file results, not an APK-owned explorer roadmap.

## Security boundary

MCP does not bypass Android permissions, Accessibility opt-in, private-process identity, bounded file operations, tool consent, artifact qualification or explicit user installation. Workspace secrets, signing keys, package state and relay credentials must not be shipped in docs, printed in logs or included in project export.
