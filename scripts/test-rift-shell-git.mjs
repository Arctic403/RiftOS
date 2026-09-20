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
assert.match(git,/MAX_GRAPHQL_PUSH_REQUEST_BYTES = 16L \* 1024L \* 1024L/);
assert.match(git,/MAX_GRAPHQL_ERROR_CHARS = 2048/);
assert.match(git,/MAX_COMMIT_MESSAGE_BYTES = 16 \* 1024/);
assert.match(git,/DEFAULT_LOG_COMMITS = 20/);
assert.match(git,/MAX_LOG_COMMITS = 100/);
assert.match(git,/MAX_COMMIT_PARENTS = 32/);

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

assert.ok((git.match(/verifyWorkspaceStable\(initial, uploaded\)/g)||[]).length >= 2,'push must verify the local snapshot before constructing and sending the atomic mutation');
assert.match(git,/uploadedSizes\[path\] = bytes\.size\.toLong\(\)/);
assert.match(git,/\.put\("size", uploadedSizes\.getValue\(path\)\)/);
assert.ok(!git.includes('if (suppliedMeta != null) verifyWorkspaceStable'),'all pushes must verify a stable local snapshot');
assert.match(git,/Remote branch changed since the last clone\/pull/);
assert.match(git,/CREATE_COMMIT_ON_BRANCH_MUTATION/);
assert.ok(git.includes('createCommitOnBranch(input: \\$input)'), 'atomic GraphQL mutation must preserve the literal Kotlin-escaped $input variable');
assert.match(git,/repositoryNameWithOwner/);
assert.match(git,/branchName/);
assert.match(git,/expectedHeadOid/);
assert.match(git,/Base64\.encodeToString\(bytes, Base64\.NO_WRAP\)/);
assert.match(git,/graphqlObject\(/);
assert.match(git,/https:\/\/api\.github\.com\/graphql/);
assert.match(git,/GitHub atomic push request exceeds/);
assert.match(git,/one GitHub write request/);
assert.match(git,/writeRequests", 1/);
assert.match(git,/trackedMode == "100644"/);
assert.match(git,/native pack transport is required/);
const atomicPushBody = git.match(/private fun atomicPush[\s\S]*?private fun commitMessageInput/)?.[0] || '';
assert.ok(atomicPushBody,'atomicPush implementation must remain present');
assert.ok(!atomicPushBody.includes('/git/blobs'),'atomicPush must not POST one GitHub blob per changed file');
assert.ok(!atomicPushBody.includes('/git/trees'),'atomicPush must not construct the remote tree through a second REST write');
assert.ok(!atomicPushBody.includes('/git/commits'),'atomicPush must not create the commit through a third REST write');
assert.ok(!atomicPushBody.includes('/git/refs/heads/'),'atomicPush must not PATCH the branch ref separately');
assert.match(git,/\.put\("expectedHeadOid", remoteSha\)/);
assert.ok(!atomicPushBody.includes('.put("force"'),'atomic GraphQL push must rely on expectedHeadOid optimistic concurrency and expose no force override');

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
assert.match(git,/git log \[-n N\|-nN\|--max-count=N\] \[--oneline\]/);
assert.match(git,/"head" ->/);
assert.match(git,/"rev-parse" ->/);
assert.match(git,/usage: git head/);
assert.match(git,/usage: git rev-parse HEAD/);
assert.match(git,/private fun headIdentity/);
assert.match(git,/HEAD " \+ meta\.optString\("headSha"\)/);
assert.match(git,/"log" -> logHistory\(cwd, args, printer\)/);
assert.match(git,/private fun logHistory/);
assert.match(git,/private fun parseLogOptions/);
assert.match(git,/private fun checkedLogLimit/);
assert.match(git,/commits\?sha=/);
assert.match(git,/per_page=/);
assert.match(git,/GitHub returned more commits than requested/);
assert.match(git,/GitHub commit exceeds parent-count limit/);
assert.match(git,/private fun checkedGitSha/);
assert.match(git,/"GitHub " \+ label \+ " SHA is invalid"/);
assert.match(git,/git log commit limit must be 1\.\./);
assert.match(git,/git log commit limit was specified more than once/);
assert.match(git,/\.put\("commits", commits\)/);
assert.match(git,/\.put\("repository", owner \+ "\/" \+ repo\)/);
assert.match(git,/\.put\("branch", branch\)/);
assert.match(git,/\.put\("recordedHeadSha", recordedHead\)/);
assert.match(git,/\.put\("remoteHeadSha", remoteHead\)/);
assert.match(git,/\.put\(\s*"upToDate"/);
assert.match(git,/usage: git pull/);
assert.match(git,/usage: git branches/);
assert.match(git,/usage: git switch <branch>/);
assert.match(git,/usage: git use <folder\|owner\/repo>/);

assert.match(git,/checkpoint\(meta\.optString\("root"\), "git:push", commitSha\)/);
assert.match(git,/checkpoint\(rootDisplay, "git:pull", headSha\)/);
assert.match(git,/RiftWorkspaceRecords\.get\(appContext\)\.checkpoint/);
const logBody = git.match(/private fun logHistory[\s\S]*?private fun parseLogOptions/)?.[0] || '';
assert.ok(logBody,'git log implementation must remain present');
assert.ok(!logBody.includes('checkpoint('),'git log must remain read-only and must not checkpoint Workspace Records');
assert.ok(!logBody.includes('saveMeta('),'git log must not mutate RiftGit metadata');
assert.ok(!logBody.includes('writeMeta('),'git log must not mutate RiftGit metadata');

for (const forbidden of ['ProcessBuilder','Runtime.getRuntime().exec','sessionStorage','localStorage']) {
  assert.ok(!git.includes(forbidden), `forbidden native Git surface: ${forbidden}`);
}

assert.match(gradle,/RiftNativeGit\.kt/);
console.log('Rift native Git source/transaction/security contract OK');
