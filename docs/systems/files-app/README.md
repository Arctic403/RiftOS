# Files App

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

## Purpose

Files is the Android-native RiftOS file browser for:
- app-private RiftFS;
- user-selected Android document-tree mounts exposed beneath the virtual /Android root.

It is owned by RiftNativeWorkspaceApps.kt and uses no WebView/DOM/JavaScript filesystem authority.

## Source ownership

Primary:
- RiftNativeWorkspaceApps.kt — Files UI, SAF mounts, native Editor integration.
- RiftVolumePaths.kt — C:/D: display path mapping.
- MainActivity.kt — Activity-result forwarding and owner lifecycle.
- app-private filesDir/riftfs — internal backing store.

Related but separate:
- RiftNativeShell.kt — shell copy/move/delete/archive commands.
- RiftToolSandbox.kt — MCP workspace filesystem.
- RiftNativeGit.kt — Git repository synchronization.
- RiftPatchSessions.kt — evidence-only provenance for internal Editor saves that resolve into D:/Workspace.

Those separate authorities do not become Files UI actions merely because they operate on files.

## Current Files UI

The native toolbar exposes:
- Up
- Refresh
- Android
- Mount
- current path display

Rows:
- directories navigate on tap;
- files open the native Editor;
- mounted Android roots expose an Unmount button.

The current Files UI does **not** expose historical Explorer actions for:
- copy;
- move;
- rename;
- delete;
- zip;
- unzip;
- multi-select.

Those capabilities exist elsewhere in RiftShell/MCP and must not be advertised as current Files UI behavior.

## Internal RiftFS navigation

Internal display paths are normalized through RiftVolumePaths.

Filesystem resolution:
- maps C:/ and D: display roots to app-private backing paths;
- canonicalizes the resulting java.io.File;
- requires the target remain equal to or beneath filesDir/riftfs.

Attempted internal path escape fails.

Volume-root browsing includes declared virtual roots plus any backing entries without duplicating names.

## Directory row bound

Native Files accepts at most 5000 discovered entries for one directory, but renders at most 400 row Views in one refresh. Larger valid listings show a truncation notice instead of constructing thousands of Android Views on the UI thread.

The limit is checked for:
- internal RiftFS directories;
- volume-root backing rows;
- Android DocumentFile directory rows.

Directories above this limit fail with:
directory exceeds native Files row limit

Directory enumeration and metadata collection run on the bounded native I/O worker with a 20-second deadline. SAF `DocumentFile.listFiles()`, `isDirectory` and `length()` are completed before returning to the UI; the UI only renders plain snapshot metadata. This prevents both unbounded row/view creation and a slow document provider from blocking the desktop thread.

## /Android virtual root

/Android is not a raw Android filesystem path.

It is a virtual namespace containing only persisted Storage Access Framework mounts that RiftOS was granted by the user.

A mount appears as:

/Android/<mount-id>

Mount children are resolved through DocumentFile, not direct java.io.File access.

## Mount flow

Mount launches ACTION_OPEN_DOCUMENT_TREE with:
- read URI permission;
- write URI permission;
- persistable URI permission;
- prefix URI permission.

The result must actually grant both:
- read;
- write.

RiftOS then:
1. persists the returned URI permission;
2. opens the URI through DocumentFile.fromTreeUri();
3. requires the selected item to be a directory;
4. requires it to be writable;
5. stores only mount metadata in private SharedPreferences.

Mounted roots are reconstructed only when Android still reports a persisted permission for the URI.

The current audit tightened reconstruction to require the persisted permission still has both read and write authority.

A stale preference record alone cannot create a live mount.

## Activity-result wiring

MainActivity.onActivityResult():
1. gives RiftBrowser its request codes first;
2. otherwise forwards the result to RiftNativeWorkspaceApps.

Files handles only its fixed ANDROID_FILES_REQUEST code.

The workspace-app owner is destroyed from MainActivity.onDestroy().

## Unmount

Unmount:
- resolves the stored mount URI;
- calls releasePersistableUriPermission for read+write;
- removes the private mount record only after release succeeds.

This audit removed the previous silent runCatching around permission release.

If Android rejects the release:
- the preference record remains;
- UI shows an Unmount failed toast;
- Files does not falsely claim authority was removed.

## Android document traversal

resolveDocument():
- requires the normalized path begin under /Android/<mount-id>;
- resolves the mount only from currently valid persisted grants;
- walks child names with DocumentFile.findFile().

There is no raw direct-path conversion from /Android into Android filesystem paths.

## Native Editor relationship

Clicking a file opens the native Editor.

Editor paths may target:
- internal RiftFS;
- a mounted /Android DocumentFile.

Maximum file/content size:
1 MiB UTF-8.

Editor load and save are part of RiftNativeWorkspaceApps, so Files and Editor share the same mount/path authority.

## Internal Editor save

Internal saves:
1. resolve canonically under RiftFS;
2. reject / and volume roots as file targets;
3. enforce <=1 MiB;
4. write a same-parent temporary file;
5. if a target exists, require it is a file;
6. rename existing file to backup;
7. publish temp by rename;
8. remove backup after success.

This audit added the existing-target file check so Editor cannot replace a directory with a file.

If publication fails and restoration of the previous file also fails, the error explicitly reports the backup path.

For an internal save whose normalized path is inside D:/Workspace, Editor opens an `origin=native-editor` patch-session claim before writing, commits it after successful publication, and aborts it on failure. Internal RiftFS paths outside Workspace are not attributed as workspace patches.

## Android Editor save

External Android saves:
1. require a live mounted DocumentFile;
2. require document.isFile and canWrite();
3. read and retain the original bytes;
4. attempt provider write using rwt, then wt, then w modes;
5. read the document back;
6. require byte-for-byte equality with requested content.

If write or verification fails:
- original bytes are written back;
- rollback is also read back and verified.

If rollback itself fails, source reports:

Android save failed and rollback was incomplete

instead of swallowing the restoration failure.

## Android reads

Document reads stream through ContentResolver.

Read accumulation is capped at 1 MiB before bytes are returned.

The UI does not trust DocumentFile.length() as the enforcement mechanism.

## Mount persistence boundary

SharedPreferences contains mount metadata only.

Actual authority comes from Android's persistedUriPermissions.

mountedAndroidRoots() drops records from the live view if:
- the URI is no longer persisted;
- read/write permission is missing;
- DocumentFile cannot be reconstructed;
- the root is no longer a directory.

## Separation from MCP

Files SAF mounts do not widen MCP workspace access.

MCP workspace tools remain owned by RiftToolSandbox and restricted to workspace.

No /Android mount mapping is injected into MCP path normalization.

## Separation from shell transfers

Files UI currently has no native transfer context menu.

RiftShell owns its independently audited:
- cp;
- mv;
- rm;
- zip;
- unzip.

Do not infer that the Files window has those controls.

## Source fixes in this audit

- mount results now require actual read + write grant flags;
- reconstructed mounts require persisted read + write permission;
- directory rendering capped at 5000 rows;
- internal Editor rejects replacing directories;
- internal Editor reports failed previous-file restoration;
- Android Editor verifies written bytes;
- Android Editor verifies rollback bytes;
- failed Android rollback is reported explicitly;
- unmount no longer silently removes UI metadata when permission release fails;
- source validators now lock these Files/SAF/editor boundaries.

## Critical invariants

- no WebView/DOM filesystem owner;
- all internal paths remain beneath RiftFS;
- /Android authority exists only through persisted SAF grants;
- mount requires read+write;
- one directory cannot create unbounded native rows;
- Editor content remains <=1 MiB;
- internal atomic save never replaces directories;
- external save verifies publication and rollback;
- unmount does not claim success if permission release failed;
- Files mounts never widen MCP workspace authority;
- unsupported historical Explorer actions are not documented as live.

## Failure signatures

- Files reads a raw Android path outside SAF -> authority regression;
- preference record creates mount without Android persisted grant -> mount regression;
- read-only mount is treated as writable -> grant regression;
- directory >5000 rows renders without refusal -> resource regression;
- internal save replaces directory -> type-safety regression;
- Android provider truncates/changes output and save reports success -> verification regression;
- rollback failure is swallowed -> data-safety regression;
- unmount hides mount while persisted grant remains because release failed -> authority-reporting regression;
- docs claim copy/move/zip/multi-select exists in Files UI -> stale UI contract.

## Fix map

Files UI / Editor / SAF -> RiftNativeWorkspaceApps.kt.

Display-path mapping -> RiftVolumePaths.kt.

Activity result/lifecycle -> MainActivity.kt.

Shell transfers -> RiftNativeShell.kt.

MCP workspace filesystem -> RiftToolSandbox.kt.

## Validation

Second source audit must verify:
- exact toolbar/row actions;
- absence of copy/move/zip/rename UI;
- internal canonical confinement;
- 5000-row limits;
- picker flags and read+write grant checks;
- persisted permission reconstruction;
- DocumentFile-only Android traversal;
- unmount release-before-record-removal;
- 1 MiB read/write bounds;
- internal file-type/rollback handling;
- external write and rollback verification;
- MainActivity result forwarding and destroy wiring;
- MCP remains separate.

Builder/device validation is still separate. Device proof should later exercise real SAF mount/browse/edit/unmount behavior.
