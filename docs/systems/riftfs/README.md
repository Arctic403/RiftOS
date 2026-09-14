# RiftFS

## Purpose

RiftFS is RiftOS's filesystem and volume namespace. Android app-private storage remains the physical backing store, while RiftFS now presents first-class OS-style volumes so system/program files and user/project data are separated by policy and by stable paths.

## Volume model

RiftOS V1 exposes two permanent virtual volumes:

```text
C:/  RiftOS System
  RiftOS/       -> /system/riftos
  Programs/     -> /system/programs
  ProgramData/  -> /system/program-data
  Toolchains/   -> /system/toolchains

D:/  User Data
  Users/        -> /home/users
  Workspace/    -> /workspace
  Projects/     -> /home/projects
  Packages/     -> /documents/packages
  Builds/       -> /documents/builds
  Documents/    -> /documents
  Downloads/    -> /downloads
  Vault/        -> /documents/vault
  Temp/         -> /home/temp
```

The drive letters are RiftOS namespaces, not Android partitions. `C:/Programs` and `/system/programs`, for example, resolve to the same canonical physical RiftFS directory. This lets existing Workspace/MCP/build code keep using its canonical compatibility root while new OS/application code uses the drive-oriented namespace.

## Source ownership

- `src/riftcore.js` — JS RiftFS API, virtual-volume resolver, drive listing and compatibility routing.
- `android/app/src/main/java/com/riftos/app/RiftVolumePaths.kt` — Android-side copy of the fixed C:/D: mapping for native program surfaces.
- `android/app/src/main/java/com/riftos/app/RiftNativeDispatcher.kt` — physical Android internal/SAF storage implementation.

The JS and Kotlin volume maps are deliberately fixed code, not package-controlled metadata. Installed apps cannot invent a system-drive alias or remap `C:/Programs` to another directory.

## Compatibility and migration

Legacy roots (`/workspace`, `/documents`, `/downloads`, `/home`, `/system`, `/apps`) remain readable during the migration. The new drive names do not bulk-move existing projects. `D:/Workspace` resolves to `/workspace`, preserving MCP's canonical workspace and existing Git/project metadata. Old `/apps/packages` and `/apps/data` are migration inputs only for the program installer; new installs live under C:/Programs and user state under D:/Users/Default/AppData.

## Protection rules

Volume roots and major mapped roots are protected from ordinary root-level remove/move operations. This prevents a generic file operation from deleting `C:/Programs`, `D:/Workspace`, or another OS namespace root. Children remain manageable through the owning subsystem and normal permission checks.

SAF mounts remain under `/mounts/*`. Future removable/cloud volumes may receive additional drive letters, but C: and D: are permanent OS-owned volumes.

## Invariants

- `C:/` contains OS/program/toolchain state; ordinary app data does not belong there.
- `D:/` contains user data, projects, packages and build outputs.
- `D:/Workspace` and `/workspace` are the same canonical data, not copies.
- Display drive paths must be normalized before resolving to a physical RiftFS path.
- Path traversal must fail before touching Android storage.
- Volume roots cannot be overwritten as files or used as archive endpoints.
- External SAF mount semantics remain independent from the C:/D: mapping.

## Validation

Verify root listing exposes C:, D: and mounts; `stat`, `list`, read/write, copy/move and archive operations resolve children correctly; protected roots reject destructive root operations; `/workspace/foo` and `D:/Workspace/foo` address the same data; and Android `RiftVolumePaths` resolves the same fixed mappings as JS RiftFS.
