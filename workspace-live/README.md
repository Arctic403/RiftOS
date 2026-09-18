# Workspace Records UI Assets — Retained

## Runtime status

This `workspace-live/` directory is retained historical/reference UI source.

It is **not packaged as the current Android Workspace Records built-in**.

The live Workspace Records UI is Android-native in `RiftNativeWorkspaceApps.kt`, backed by `RiftWorkspaceRecords.kt` and `RiftWorkspaceWatcher.kt`.

Historical descriptions of a trusted-shell shadow-root dashboard, direct shell API access, or HTML-mounted Workspace Records surface are not current runtime behavior.

See:
- `docs/systems/workspace/README.md` for the canonical workspace boundary;
- `docs/systems/workspace/live/README.md` for the current native Workspace Records subsystem.

Do not patch this retained folder to fix the live Android Workspace Records UI unless it is explicitly being reactivated by a separate migration.
