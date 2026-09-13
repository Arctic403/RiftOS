# Workspace Records UI Assets

This historical `workspace-live/` folder now contains the trusted-shell **Workspace Records** dashboard. The product surface is observational: persistent local records, affected-file lists, local checkpoint diffs, and optional RiftGit comparison. The HTML is mounted into a shadow root with direct access to the shell's local records API; it is not a sandboxed guest app. Its UI does not offer direct workspace mutation or approve/deny controls.

The full architecture, persistence model, MCP `rift_workspace_diff` contract, security boundary and debugging map live in [`../docs/systems/workspace/live/README.md`](../docs/systems/workspace/live/README.md).
