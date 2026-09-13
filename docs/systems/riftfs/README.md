# RiftFS

## Purpose

RiftFS is RiftOS's logical filesystem namespace. It gives the shell, apps and workspace a consistent path model while Android supplies actual app-private storage and Storage Access Framework mounts.

## Source ownership

- `RiftFS` in `src/riftcore.js` — logical path API, mount-aware operations, JSON helpers and transfer queue integration.
- `RiftNativeDispatcher.kt` — native internal-file and SAF implementations.
- `src/riftworkspace-android-adapter.js` — maps workspace operations to native storage.
- `MainActivity.kt` — picker/mount acquisition lifecycle.

## Namespace

The Android app initializes RiftFS under `filesDir/riftfs`, including logical roots such as `/home`, `/apps`, `/system`, `/workspace`, `/downloads` and `/documents`. External folders appear under mount paths backed by persisted SAF URI permissions.

The canonical MCP/project workspace is `/workspace`, physically `filesDir/riftfs/workspace`. MCP is intentionally restricted to that tree; the general RiftFS API is broader.

## Why this boundary exists

Every consumer should reason in RiftOS paths rather than raw Android `File`, URI, or `DocumentFile` semantics. Native storage implementations can therefore change without rewriting the shell/app APIs.

## Data flow

```text
consumer
  -> RiftFS normalized logical path
  -> internal path or mount routing
  -> RiftNativeBridge
  -> RiftNativeDispatcher
      -> java.io.File for app-private RiftFS
      -> DocumentFile/ContentResolver for SAF mount
```

Large copy/move work is queued and progress-aware. Provider-native copy/move is attempted where possible before streaming fallback.

## Critical invariants

- Normalize path segments; reject traversal rather than silently escaping a root.
- Do not mix MCP's narrower workspace authority with general RiftFS authority.
- Preserve internal-vs-mount routing semantics.
- Writes should not report success before native commit completes.
- Move/copy must preserve verification and progress semantics for large trees.
- SAF access depends on persisted URI permission; a provider can disappear or revoke access independently.

## Failure signatures

- Internal files fail everywhere -> RiftFS/native dispatcher path or bridge.
- Only mounted folder fails -> SAF mount record/provider permission/path traversal.
- Workspace works in Files but not MCP -> MCP sandbox boundary, not RiftFS.
- Copy/move freezes UI -> transfer scheduling/progress path.
- A move duplicates or loses data -> native move/copy verification/commit path.

## Fix map

- Logical path normalization/routing/API -> `RiftFS`.
- Android internal/SAF read-write-list-copy-move -> `RiftNativeDispatcher`.
- Project-only abstractions -> RiftWorkspace.
- MCP-only workspace operations -> `RiftToolSandbox`.
- Transfer cancellation/progress/jobs -> transfer subsystem.

## Validation

Exercise internal file create/read/write/delete, recursive list, directory tree copy/move, mount-to-internal and internal-to-mount transfers, ZIP/unzip, and provider cancellation/revocation. For destructive fixes use a workspace snapshot first.

## Safe extension points

New filesystem features should enter through a stable RiftFS method and a narrow native method if Android authority is required. Avoid creating parallel storage roots or bypass APIs inside individual apps.
