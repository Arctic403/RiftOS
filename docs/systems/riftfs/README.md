# RiftFS

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

RiftFS is RiftOS's app-private storage namespace rooted at Android `filesDir/riftfs`.

It provides stable RiftOS display paths and C:/D: aliases over that one app-private tree. It is not an Android block-device layer and the drive letters are not separate partitions.

## Source ownership

Primary mapping:
- `RiftVolumePaths.kt` — fixed native C:/D: display aliases.

Live consumers:
- `RiftNativeShell.kt` — broad app-private RiftFS shell access with canonical containment.
- `RiftToolSandbox.kt` — hard-scoped MCP/Code Mode access to physical `riftfs/workspace`.
- `RiftNativeWorkspaceApps.kt` — native Files/Editor plus separate SAF document-tree mounts.
- `RiftHeadlessJsRuntime.kt` — bounded headless Rift++ file access.
- `RiftNativeGit.kt`, `RiftNativeDevLab.kt`, `RiftNativeShellServices.kt` — bounded native consumers using the same alias resolver.
- `RiftBrowserAppHost.kt` — capability/path-restricted installed-app access.
- `MainActivity.kt` — reads installed program manifests from the C:/Programs alias.

Retained `src/riftcore.js` contains the older browser-hosted RiftFS model but is not Gradle-packaged as the current Android filesystem authority.

## Physical root

The native app-private root is:

`<Android app filesDir>/riftfs`

Consumers canonicalize paths and require resolved files to remain equal to or below that root.

## Fixed display volumes

### C: — RiftOS System

- `/C:/RiftOS` -> `riftfs/system/riftos`
- `/C:/Programs` -> `riftfs/system/programs`
- `/C:/ProgramData` -> `riftfs/system/program-data`
- `/C:/Toolchains` -> `riftfs/system/toolchains`

The backing namespace for other C: children is `riftfs/system/volumes/C`.

### D: — User Data

- `/D:/Users` -> `riftfs/home/users`
- `/D:/Workspace` -> `riftfs/workspace`
- `/D:/Projects` -> `riftfs/home/projects`
- `/D:/Packages` -> `riftfs/documents/packages`
- `/D:/Builds` -> `riftfs/documents/builds`
- `/D:/Documents` -> `riftfs/documents`
- `/D:/Downloads` -> `riftfs/downloads`
- `/D:/Vault` -> `riftfs/documents/vault`
- `/D:/Temp` -> `riftfs/home/temp`

Other D: children fall back under `riftfs/system/volumes/D`.

`/D:/Workspace` and physical `/workspace` therefore resolve to the same app-private directory.

## Path normalization

`RiftVolumePaths.normalizeDisplay()`:
- accepts slash or backslash input;
- removes empty and `.` segments;
- normalizes a leading drive token to uppercase;
- rejects `..`;
- rejects NUL-containing segments;
- returns a leading-slash display path.

Not every consumer uses identical display normalization.

RiftShell intentionally has a cwd-aware normalizer that collapses `..` for normal shell navigation. It still canonicalizes the resulting Android `File` and rejects anything outside `filesDir/riftfs`.

MCP/Code Mode is stricter: `.`, `..` and NUL segments are rejected and the first path segment must be `workspace`.

## Volume-root behavior

`/C:` and `/D:` are virtual volume roots.

Native consumers can synthesize the configured alias directories while also listing physical entries under each volume's backing directory.

Mutation APIs must not treat a volume root as an ordinary file.

## MCP / Code Mode boundary

`RiftToolSandbox` does not expose all of RiftFS.

Its canonical root is physical:

`riftfs/workspace`

Accepted tool paths are `workspace` or `workspace/...`.

It:
- rejects `.`, `..` and NUL path segments;
- rejects paths whose first segment is not `workspace`;
- canonicalizes every result under the workspace root;
- prevents direct mutation of the workspace root itself.

Therefore MCP cannot reach C:/Programs, D:/Documents, SAF mounts or arbitrary app-private state through workspace tools.

## Native Files and /Android

`/Android` is **not** part of physical RiftFS and is not a third RiftVolumePaths volume.

It is a native Files virtual root implemented by `RiftNativeWorkspaceApps`.

Mount flow:
1. user chooses a tree with `ACTION_OPEN_DOCUMENT_TREE`;
2. read/write/persistable/prefix flags are requested;
3. the returned grant must include read permission;
4. a `DocumentFile` directory must resolve;
5. the selected root must report writable;
6. the app persists URI authority;
7. a `mount:<id>` record is stored in the `rift-native` preferences namespace.

Stored mounts are surfaced only while the URI still appears in Android's persisted URI permission set and resolves to a directory.

Unmount releases persisted read/write permission when possible and removes the mount record.

SAF entries are traversed through `DocumentFile`, not converted into arbitrary raw filesystem paths.

## Native Editor SAF boundary

The native Editor bounds both internal and SAF text editing to 1 MiB.

SAF reads stream through ContentResolver and abort when the bound would be exceeded.

SAF writes try provider modes `rwt`, `wt`, then `w`. The surrounding Editor save path preserves prior bytes and attempts rollback on failure.

Provider behavior still requires installed-device testing.

## Native Shell boundary

RiftShell can operate across app-private RiftFS display paths.

It:
- supports C:/D: aliases;
- canonicalizes resolved Android files under `riftfs`;
- refuses destructive operations against the RiftFS root and virtual volume roots;
- does not expose SAF `/Android` mounts.

Shell navigation permits `..` as cwd navigation but canonical containment prevents escaping RiftFS.

## Headless Rift++ boundary

`RiftHeadlessJsRuntime` resolves display paths through RiftVolumePaths and canonical containment.

Its text input/output paths are bounded to 8 MiB.

It has no SAF authority.

## Installed-app boundary

`RiftBrowserAppHost` resolves files through RiftVolumePaths but applies per-program policy.

Reads are restricted to:
- the app's own `/C:/Programs/<appId>`;
- the app's own AppData;
- approved public D: data roots.

Writes are restricted to the app's own AppData and approved D: user/project data roots.

Text operations are bounded to 8 MiB.

This installed-app capability layer is narrower than native Shell RiftFS authority.

## Persistence model

RiftFS files are ordinary app-private Android files and survive Activity recreation/process restart subject to Android app-data lifetime.

This does not imply every in-memory subsystem state is persistent.

External SAF authority has a separate Android persisted-URI permission lifecycle.

## Non-ownership boundaries

RiftFS does not own:
- SAF picker/UI policy -> Files;
- MCP permission/grant policy -> MCP/tool host;
- Git transactions -> RiftGit;
- installed-app capability grants -> Apps host;
- workspace change records -> Workspace Records;
- archive transaction policy -> consumer-specific transfer/sandbox owners.

## Critical invariants

- app-private native root remains `filesDir/riftfs`;
- C:/D: aliases are fixed code, not user-remappable mounts;
- D:/Workspace resolves to physical `riftfs/workspace`;
- canonical file access cannot escape `riftfs`;
- MCP remains confined to physical `riftfs/workspace`;
- SAF `/Android` never becomes raw RiftFS authority;
- stored SAF mounts require a still-persisted Android URI grant;
- installed apps receive narrower path access than native Shell;
- retained JS RiftFS code is not treated as the current Android filesystem owner.

## Failure signatures

- C:/ or D:/ alias points to a different physical tree -> mapping regression;
- D:/Workspace differs from MCP workspace -> workspace alias regression;
- canonical target escapes `filesDir/riftfs` -> containment failure;
- MCP accepts C:/, D:/Documents or /Android -> sandbox escape;
- SAF URI is surfaced without persisted authority -> Files/SAF regression;
- external URI is converted to an arbitrary raw path -> authority escape;
- installed app can write its C:/Programs files or unrelated AppData -> app capability regression;
- docs claim all consumers handle `..` identically -> path-semantics documentation error.

## Fix map

C:/D: alias table and strict display normalization -> `RiftVolumePaths.kt`.

Native shell resolution/destructive-root guards -> `RiftNativeShell.kt`.

MCP workspace containment -> `RiftToolSandbox.kt`.

Files/Editor/SAF mounts -> `RiftNativeWorkspaceApps.kt`.

Headless Rift++ file access -> `RiftHeadlessJsRuntime.kt`.

Installed-app filesystem policy -> `RiftBrowserAppHost.kt`.

Git/Dev Lab/shell-service path consumers -> their subsystem owners.

## Validation

Source verification must recheck:
- exact C:/D: map;
- fallback backing roots;
- display normalization;
- consumer-specific `..` handling;
- canonical containment;
- D:/Workspace identity;
- MCP workspace root enforcement;
- SAF mount permission/storage/unmount flow;
- 1 MiB native Editor bound;
- 8 MiB headless/app-host text bounds;
- installed-app read/write path restrictions;
- retained versus packaged JS boundary.

Installed-device validation must additionally test SAF provider behavior, persisted mount restore/unmount, internal and external editor writes, Android app-data persistence, and real path behavior.

Source verification is not Android Builder/device proof.
