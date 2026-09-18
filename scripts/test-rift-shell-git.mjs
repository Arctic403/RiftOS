import fs from 'node:fs';
import assert from 'node:assert/strict';

const read = file => fs.readFileSync(file, 'utf8');
const git = read('android/app/src/main/java/com/riftos/app/RiftNativeGit.kt');
const shell = read('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt');
const settings = read('android/app/src/main/java/com/riftos/app/RiftNativeWorkspaceApps.kt');
const secrets = read('android/app/src/main/java/com/riftos/app/RiftSecretStore.kt');
const gradle = read('android/app/build.gradle.kts');

assert.match(git,/class RiftNativeGit/);
assert.match(git,/META_NAME = "\.riftgit\.json"/);
assert.match(git,/format", "riftgit-v3"/);
assert.match(git,/MAX_FILE = 48L \* 1024L \* 1024L/);
assert.match(git,/MAX_TOTAL = 256L \* 1024L \* 1024L/);
assert.match(git,/MAX_FILES = 10000/);
assert.match(git,/MAX_META_BYTES = 8L \* 1024L \* 1024L/);
assert.match(git,/MAX_API_RESPONSE_BYTES = 80L \* 1024L \* 1024L/);
assert.match(git,/MAX_COMMIT_MESSAGE_BYTES = 16 \* 1024/);

assert.match(git,/RiftSecretStore\(appContext\)/);
assert.match(git,/TOKEN_KEY = "github\.token"/);
assert.match(git,/apiObject\("\/user", tokenOverride = token\)/);
assert.match(settings,/nativeGit\.storeToken\(token\)/);
assert.match(secrets,/AndroidKeyStore/);
assert.ok(!shell.includes('github.token'),'native shell must not transport GitHub credentials');

assert.match(git,/validateMeta\(meta\)/);
assert.match(git,/Unsupported RiftGit metadata format/);
assert.match(git,/RiftGit tracked map is missing/);
assert.match(git,/RiftGit metadata exceeds tracked-file limit/);
assert.match(git,/RiftGit metadata exceeds tracked-byte limit/);
assert.match(git,/checkedBranch\(meta\.optString\("branch"\)\)/);
assert.match(git,/Invalid Git branch name/);

assert.match(git,/GitHub returned a truncated tree; sync stopped/);
assert.match(git,/has invalid or oversized Git size/);
assert.match(git,/GitHub blob SHA mismatch/);
assert.match(git,/GitHub response exceeds \$MAX_API_RESPONSE_BYTES bytes/);
assert.match(git,/blobSha\(bytes\) == sha/);

assert.ok((git.match(/verifyWorkspaceStable\(initial, uploaded\)/g)||[]).length >= 2,'push must verify the local snapshot before commit construction and again before ref update');
assert.match(git,/uploadedSizes\[path\] = bytes\.size\.toLong\(\)/);
assert.match(git,/\.put\("size", uploadedSizes\.getValue\(path\)\)/);
assert.ok(!git.includes('if (suppliedMeta != null) verifyWorkspaceStable'),'all pushes must verify a stable local snapshot');
assert.match(git,/Remote branch changed since the last clone\/pull/);
assert.match(git,/put\("force", false\)/);

assert.match(git,/\.riftgit-stage-/);
assert.match(git,/\.riftgit-backup-/);
assert.match(git,/Could not move current project into recovery backup/);
assert.match(git,/Could not publish staged project/);
assert.match(git,/rollback incomplete/);
assert.match(git,/could not restore original project backup/);
assert.match(git,/Previous destination state restored/);
assert.ok(!git.includes('Original project preserved'),'unchecked historical rollback claim must stay retired');
assert.match(git,/backupCleanupPending/);
assert.match(git,/staging cleanup failed/);

assert.match(git,/Repository root cannot be the RiftFS root or a drive root/);
assert.match(git,/Project folder is missing or unsafe/);
assert.match(git,/Clone destination is unsafe/);
assert.match(git,/Repository directory escaped root/);
assert.match(git,/Repository entry escaped root/);
assert.match(git,/Repository path escaped root/);
assert.match(git,/Git metadata target is not a file/);
assert.match(git,/Git metadata write failed and previous file could not be restored/);

assert.match(git,/usage: git auth-status/);
assert.match(git,/usage: git logout/);
assert.match(git,/usage: git status/);
assert.match(git,/usage: git pull/);
assert.match(git,/usage: git branches/);
assert.match(git,/usage: git switch <branch>/);
assert.match(git,/usage: git use <folder\|owner\/repo>/);

assert.match(git,/checkpoint\(meta\.optString\("root"\), "git:push", commitSha\)/);
assert.match(git,/checkpoint\(rootDisplay, "git:pull", headSha\)/);
assert.match(git,/RiftWorkspaceRecords\.get\(appContext\)\.checkpoint/);

for (const forbidden of ['ProcessBuilder','Runtime.getRuntime().exec','sessionStorage','localStorage']) {
  assert.ok(!git.includes(forbidden), `forbidden native Git surface: ${forbidden}`);
}

assert.match(gradle,/RiftNativeGit\.kt/);
console.log('Rift native Git source/transaction/security contract OK');
