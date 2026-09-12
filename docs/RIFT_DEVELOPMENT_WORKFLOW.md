# RiftOS Development Workflow

Default workflow for large changes:

Audit -> Snapshot -> Read -> Patch -> Dry Run -> Build -> Verify -> Commit

Large subsystem changes should be handled by subsystem ownership:

- RiftFS: filesystem and storage operations
- RiftNativeDispatcher: Android IO bridge
- RiftWorkspace: project layer
- RiftMCP: AI tooling layer

Each patch should identify the subsystem being changed and avoid cross-layer duplication.

## Publish the RiftOS workspace from RiftShell

Use `git auth` once per shell session to provide a GitHub token with Contents write access to `Arctic403/RiftOS`. The token stays in session storage. Run `workspace status` to compare `/workspace/RiftOS-main` with `Arctic403/RiftOS` `main`, then `workspace push "Describe the change"` to publish that folder as a single Git commit. `git workspace status` and `git workspace push "Describe the change"` are equivalent. No attach, copy to `/home`, or Git metadata in the project is required.

The push stops if the remote branch changes while files are uploading or the workspace changes during upload. Publishing source does not start the separate builder; builds remain manual. This workflow does not change the current signing policy.
