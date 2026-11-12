# Host operating services: MCP, Local Agent, RiftFS and workspace

## MCP and relay

The APK includes `RiftMcpRuntime`, `RiftMcpServer`, `RiftMcpRelayClient`, `RiftMcpEventBus`, `RiftMcpOperationJournal`, `RiftToolHost`, `RiftToolSandbox`, and workspace/export/diff integrations. Remote relay implementation is owned independently by `/D:/Workspace/RiftMcpRelay`; the host owns its client and bounded JSON-RPC/tools boundary, not the Cloudflare Worker.

The Local Agent uses `RiftVortexAccessibilityService` and `RiftLocalAgentBatch` with explicit UI authority. Batch execution accepts up to 16 steps, validates plans, uses stop-on-error by default, supports result/status/cancel, and preserves request/job evidence. The workspace code-executor deliberately supports **one mutation operation per call**, not an unrestricted multi-operation batch. MCP journaling and reconcile functionality are host responsibilities.

## Filesystem and workspace

`RiftVolumePaths.kt` resolves Core-visible C:/D: virtual volume paths. `RiftWorkspaceRecords`, `RiftWorkspaceWatcher`, `RiftProjectExporter`, `RiftSourceIntelligenceV2`, `RiftDiffEngineV2`, `RiftFileIdentityV2` and `RiftNativeGit` support bounded workspace metadata, audit, source review, export and RiftGit.

Do not confuse a virtual C:/D: address with unrestricted Android storage. File access, tool grants, native process controls and Android Binder service access must remain behind their current permission and request limits. The graphical Shell's File Explorer is **external Shell code** consuming Core-owned file results, not an APK-owned explorer roadmap.

## Native shell Generic Tools candidate (2026-11-11)

The host-owned `RiftNativeShell` now has source support for `tool list` and
`tool call <approved-id> <file.json|full-provider-json>` to expose the **existing selected
Core Generic Tool connection API** to native MCP/RiftShell calls. The command is
only a bounded, explicit front end: the independent Core retains signer pinning,
saved operation scopes, and per-call interactive consent. This is distinct from
the external graphical Shell Terminal's existing file-backed tool commands.

This is an **unbuilt host source candidate**. Updating an external Core/Shell
DEX does NOT activate these native commands. Manual host APK build/sign/install
is required. No changes are made to currently running processes by this
source update. See [native shell tool-call checkpoint](NATIVE_GENERIC_TOOL_SHELL_CALLS_20261111.md).

## Security boundary

MCP does not bypass Android permissions, Accessibility opt-in, private-process identity, bounded file operations, tool consent, artifact qualification or explicit user installation. Workspace secrets, signing keys, package state and relay credentials must not be shipped in docs, printed in logs or included in project export.
