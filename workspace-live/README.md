# Workspace Records UI Assets

This historical `workspace-live/` folder now contains the sandboxed **Workspace Records** dashboard. The product surface is observational: persistent local records, affected-file lists, local checkpoint diffs, and remote RiftGit comparison. It does not expose direct workspace mutation or approve/deny controls.

The full architecture, persistence model, MCP `rift_workspace_diff` contract, security boundary and debugging map live in [`../docs/systems/workspace/live/README.md`](../docs/systems/workspace/live/README.md).
