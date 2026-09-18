# RiftGit

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-18.**

## Purpose

RiftGit is RiftOS's Android-native GitHub repository synchronization layer for projects stored inside app-private RiftFS.

It uses the GitHub REST Git database API rather than invoking a local git process.

The retained src/riftgit.js implementation is historical/reference source and is not the current Android authority.

## Source ownership

Live:
- RiftNativeGit.kt — repository metadata, status, GitHub transport, atomic push/pull/switch/clone.
- RiftSecretStore.kt — GitHub token storage.
- RiftNativeShell.kt — git command routing.
- RiftNativeWorkspaceApps.kt — native Settings token entry/status.
- RiftWorkspaceRecords.kt — workspace checkpoint receiver after applicable successful push/pull.
- RiftPatchSessions.kt — provenance claims for workspace import/replacement and local Git metadata publication.

Focused source test:
- scripts/test-rift-shell-git.mjs

Retained:
- src/riftgit.js

During this audit the old CI test that executed retained src/riftgit.js was replaced by a native-source contract test under the same script name.

## No raw Git process

RiftNativeGit contains no:
- ProcessBuilder;
- Runtime.exec;
- shell git invocation.

Remote operations use fixed HTTPS requests to:

https://api.github.com

Supported HTTP methods are GET, POST and PATCH.

## Credential boundary

Token key:
github.token

Native Settings receives the token and calls RiftNativeGit.storeToken().

storeToken:
1. trims token;
2. requires length 20..512;
3. verifies it by calling GitHub /user;
4. only after successful verification stores it through RiftSecretStore.

RiftShell has no GitHub-token command/argument path.

Native Git source contains no localStorage/sessionStorage token storage.

logout removes the Keystore-backed secret.

## Commands

Current command families:
- auth / auth-status
- logout
- workspace status
- workspace push [message]
- clone owner/repo [branch] [destination]
- init/attach owner/repo [branch] [folder]
- use <folder|owner/repo>
- root
- repo
- status
- commit -m <message>
- push [message]
- pull
- sync [message]
- branches / branch
- switch / checkout <branch>

-C <folder> may select a repository cwd before the command.

Read/no-argument commands now reject extra arguments rather than silently ignoring them.

switch/checkout and use require exactly one argument.

attach/clone bound optional argument counts.

## Commit semantics

git commit -m <message> does not create a standalone local Git commit.

It stores a bounded pending commit message in RiftGit metadata for the next atomic push.

Commit message:
- nonblank;
- <=16 KiB UTF-8.

push may also receive its message directly.

## Repository metadata

Per-repository metadata file:
.riftgit.json

Current format:
riftgit-v3

Current-repository pointer:
 /home/.riftgit-current

Bounds:
- metadata <=8 MiB;
- pointer <=4 KiB.

Loaded metadata is not trusted blindly.

validateMeta() checks:
- format;
- owner/repo syntax and 1..100 lengths;
- exact full = owner/repo;
- branch syntax;
- head SHA shape;
- bounded pending commit message;
- tracked map exists;
- <=10000 tracked files;
- normalized safe relative paths;
- blob SHA format;
- each tracked size 0..48 MiB;
- aggregate tracked bytes <=256 MiB;
- six-digit Git modes.

The root stored inside the file is overwritten from the actual discovered filesystem location before validation, so metadata cannot redirect authority to another path.

## Branch names

Branch names are bounded to 255 characters and reject:
- NUL/control/newline/CR;
- spaces;
- leading/trailing slash;
- duplicate slash;
- ..
- @ / @{
- trailing dot;
- .lock suffix;
- ~ ^ : ? * [ backslash;
- empty or dot-prefixed path components.

GitHub branch requests pass through this validation.

## Repository roots

Repository paths resolve canonically beneath app-private RiftFS.

A repository root cannot be:
- the RiftFS root itself;
- a C:/ or D: drive root.

attach requires an existing safe project directory.

clone requires a safe destination that does not already exist.

import/pull/switch recheck the same root boundary before replacement.

## Local status scan

status walks the repository without entering .git.

Before descending into each directory, its canonical path must remain beneath the repository root. This prevents a symlinked directory from causing walkTopDown() to leave the repository.

Every file is also canonicalized and checked.

Local status limits:
- <=10000 managed files;
- <=48 MiB/file;
- <=256 MiB aggregate.

.riftgit.json and .git are ignored as tracked project content.

Tracked files are compared using canonical Git blob SHA-1:

sha1("blob <length>\0" + bytes)

## Remote tree bounds

GitHub recursive tree response must not be truncated.

Only blob entries are synchronized.

Remote bounds:
- <=10000 files;
- each reported blob size 0..48 MiB;
- <=256 MiB aggregate.

Remote paths reject:
- blank components;
- absolute paths;
- dot/dot-dot;
- NUL;
- traversal.

## GitHub response bounds

All GitHub response bodies are streamed through readApiBody().

Hard response cap:
80 MiB.

The limit is enforced both:
- against Content-Length when known;
- while reading the response stream.

This prevents an unknown-length/chunked response from bypassing the cap.

## Downloaded blob integrity

Each downloaded blob:
- must use GitHub base64 encoding;
- decodes to <=48 MiB;
- is independently re-hashed with Git blob SHA-1;
- must equal the requested Git object SHA.

Remote tree metadata alone is not trusted as proof of the body received.

## Atomic push

push first computes the local status against the recorded metadata.

If no changes exist it returns clean.

Change-set limits:
- <=10000 changes;
- upload files <=48 MiB each;
- uploaded bytes <=256 MiB.

Before creating a commit:
1. fetch remote branch head;
2. require recorded head is blank or equals current remote head;
3. upload modified/untracked files as Git blobs;
4. verify GitHub returned each expected blob SHA;
5. represent deletions with null tree SHA;
6. verify the entire local modified/deleted/untracked set still matches the initial snapshot;
7. re-hash every uploaded local file and require it still matches the uploaded blob.

Then RiftGit creates:
- Git tree;
- Git commit whose parent is the fetched remote head.

Immediately before updating the branch ref, RiftGit performs the local-stability verification **again**.

The ref PATCH uses force=false.

If the remote branch advanced meanwhile, GitHub's non-force update must fail rather than overwrite the competing remote history.

Tracked sizes written to metadata come from the uploaded byte snapshot, not a later reread of a possibly changed file.

## Workspace push

git workspace push uses the fixed:
- Arctic403/RiftOS
- main
- /workspace/RiftOS-main

workspaceMeta() fetches the current remote tree rather than writing .riftgit.json into the workspace merely to push.

The same atomic push/stability rules apply.

After a successful push, local `.riftgit.json` metadata publication can register `origin=native-git` / `operation=push-metadata` provenance when that metadata lives under Workspace. This claim is committed before the Workspace Records checkpoint is advanced.

After a successful workspace push, Workspace Records checkpoint is advanced with:
- reason git:push
- Git root
- new head SHA.

## Pull/switch/clone import transaction

Remote replacement is a staged directory transaction.

Before publication:
1. fetch bounded remote tree;
2. download every blob into a unique sibling .riftgit-stage-* directory;
3. independently verify every blob SHA;
4. write validated next .riftgit.json into the stage.

Only after the entire stage is complete:
1. existing repository directory is renamed to unique .riftgit-backup-*;
2. completed stage is renamed into the canonical repository path;
3. current-repo pointer is written.

The old destructive delete-before-install design is retired.

## Import rollback

The replacement transaction explicitly tracks whether:
- old repository was backed up;
- staged project was published.

If a later publication step fails:
- a published replacement is moved back to the stage location where possible;
- original backup is restored to the repository location;
- every failed rollback step is collected.

If rollback is incomplete:
- source does not claim the original was preserved;
- stage/backup recovery evidence is retained;
- error includes both recovery paths.

If restoration fully succeeds:
- error explicitly says the previous destination state was restored.

The historical unchecked “Original project preserved” claim was removed.

If the operation fails before publication and stage cleanup itself fails, the stage path is reported.

## Backup cleanup after success

After successful publication the old backup is deleted.

If backup cleanup fails:
- the successful pull/import result reports backupCleanupPending=true;
- backupPath is returned.

Failure to remove a stale backup does not retroactively destroy a successfully published repository.

## Pull

pull requires:
- clean working tree;

or the special empty-checkout case where every tracked file is absent and no other local change exists.

If remote head already equals recorded head and checkout is not empty, pull reports up to date.

Otherwise it uses the staged import transaction.

A staged import opens one directory-scope `origin=native-git` provenance claim before publication. After the staged tree is atomically published, that claim is committed before the workspace checkpoint. Because a directory replacement covers many descendant writes, this evidence is deliberately labeled `confidence=scope-bound` rather than pretending every child was individually SHA-bound.

Successful workspace pull advances Workspace Records checkpoint with reason git:pull.

## Switch

switch requires a clean working tree.

Switching to a different branch uses the same complete staged import transaction rather than mutating files in place.

## Sync

sync compares current remote head to recorded metadata.

If remote changed:
- local tree must be clean;
- then sync pulls.

If remote did not change:
- clean tree reports synchronized;
- dirty tree performs atomic push.

It does not auto-merge simultaneous remote/local edits.

## Attach

init/attach does not download or overwrite files.

It:
- validates remote repo/branch;
- fetches bounded remote tree metadata;
- writes riftgit-v3 metadata into the existing safe project;
- status then shows actual local differences against that remote baseline.

## Clone

clone requires a nonexistent safe destination and uses the same staged import transaction.

## Current-repository pointer

use selects an existing attached repository and atomically updates /home/.riftgit-current.

A stale pointer fails explicitly if the referenced repository no longer exists.

writeMeta no longer creates a missing repository directory; a missing project is an error rather than an opportunity to recreate an empty shell of the repo.

## Atomic metadata writes

Metadata/pointer writes use:
- same-parent temporary file;
- backup rename of existing target;
- replacement rename.

Existing target must be a file.

A directory cannot be replaced by metadata.

If publication fails and previous-file restoration also fails, the error reports the backup path instead of silently claiming rollback.

## Branch listing limitation

branches currently calls GitHub with:

per_page=100

There is no pagination loop yet.

Therefore this command returns at most GitHub's first 100 branch rows. This is an explicit current limitation, not a complete branch-enumeration guarantee.

## Retained JavaScript implementation

src/riftgit.js is retained reference/history only.

It is not Gradle-packaged as current Git authority.

During this audit scripts/test-rift-shell-git.mjs was replaced so npm run check no longer executes the retained JavaScript Git implementation or validates sessionStorage-era token behavior.

## Source fixes in this audit

- bounded local repo scans with canonical directory/file confinement;
- bounded metadata, pointer, commit message and GitHub response sizes;
- metadata schema validation;
- stricter repository/branch syntax;
- attach/clone/import reject RiftFS/drive-root authority;
- downloaded blobs rehashed against requested Git SHA;
- all pushes verify local snapshot stability, not workspace push only;
- second local-stability check immediately before branch ref update;
- tracked file size comes from uploaded bytes;
- pull/switch/clone rollback rewritten to prove restoration or report incomplete recovery;
- successful backup cleanup failure is surfaced;
- metadata writes cannot replace directories;
- metadata writes report failed restoration;
- writeMeta cannot recreate a missing project;
- read commands reject ignored extra arguments;
- stale JS Git CI test replaced with native-source contract test.

## Critical invariants

- token never crosses RiftShell or JS storage;
- no raw git/subprocess execution;
- all local repository paths remain under RiftFS and outside volume roots;
- local/remote trees stay within 10k files, 48 MiB/file and 256 MiB total;
- remote response <=80 MiB;
- downloaded blob body must match requested Git SHA;
- push never force-updates remote ref;
- push checks remote head and local snapshot stability;
- import downloads completely before replacing local project;
- rollback success is never claimed without proof;
- incomplete recovery retains paths/evidence;
- metadata is bounded/validated before trust;
- Workspace Records checkpoint happens only after successful workspace Git operation;
- retained src/riftgit.js remains inactive.

## Failure signatures

- token appears in shell/localStorage/sessionStorage -> credential regression;
- ProcessBuilder/Runtime.exec appears -> authority regression;
- status follows symlink outside repo -> containment regression;
- oversized/unbounded GitHub body is read -> network bound regression;
- downloaded blob SHA mismatch is accepted -> integrity regression;
- pull deletes existing project before staging completes -> destructive-sync regression;
- rollback restore rename is unchecked -> recovery regression;
- failure message says original preserved while restore failed -> false-safety regression;
- push uses force=true -> remote history regression;
- only workspace pushes verify local stability -> generic push race regression;
- malformed .riftgit.json changes repo/remote authority -> metadata trust regression;
- old JS Git test becomes live CI authority again -> test/runtime drift.

## Fix map

Git/GitHub transport, metadata, transactions -> RiftNativeGit.kt.

Credential encryption -> RiftSecretStore.kt.

Credential UI -> RiftNativeWorkspaceApps.kt.

Shell routing -> RiftNativeShell.kt.

Workspace checkpoint -> RiftWorkspaceRecords.kt.

Focused validation -> scripts/test-rift-shell-git.mjs.

## Validation

Second source audit must recheck:
- no process execution;
- Settings/Keystore credential flow and no shell token route;
- metadata/pointer/message/API body bounds;
- metadata schema/path/branch validation;
- local canonical scan and resource limits;
- remote tree truncation/count/size limits;
- blob body SHA verification;
- generic + workspace push stability checks and force=false;
- staged import/backup/rollback state machine;
- missing-repo/drive-root rejection;
- metadata atomic restoration handling;
- Workspace Records push/pull checkpoints;
- retained src/riftgit.js not used by live CI test.

Builder/device/network validation remains separate. This source audit does not perform a GitHub push or pull.
