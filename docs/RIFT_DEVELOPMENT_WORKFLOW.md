# RiftOS Development Workflow

Default workflow for large changes:

Audit -> Snapshot -> Read -> Patch -> Dry Run -> Build -> Verify -> Commit

Large subsystem changes should be handled by subsystem ownership:

- RiftFS: filesystem and storage operations
- RiftNativeDispatcher: Android IO bridge
- RiftWorkspace: project layer
- RiftMCP: AI tooling layer

Each patch should identify the subsystem being changed and avoid cross-layer duplication.
