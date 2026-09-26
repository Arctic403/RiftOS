import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';
const sessions = read(k + 'RiftPatchSessions.kt');
const fence = read(k + 'RiftMutationFence.kt');
const records = read(k + 'RiftWorkspaceRecords.kt');
const sandbox = read(k + 'RiftToolSandbox.kt');
const shell = read(k + 'RiftNativeShell.kt');
const apps = read(k + 'RiftNativeWorkspaceApps.kt');
const devlab = read(k + 'RiftNativeDevLab.kt');
const git = read(k + 'RiftNativeGit.kt');
const host = read(k + 'RiftToolHost.kt');
const gradle = read('android/app/build.gradle.kts');
const ownership = read('docs/SOURCE_OWNERSHIP.md');

assert.match(sessions, /internal object RiftPatchSessions/);
assert.match(sessions, /const val VERSION = 2/);
assert.match(sessions, /CLAIM_TTL_MS = 15_000L/);
assert.match(sessions, /MAX_PATHS = 512/);
assert.match(sessions, /stateMatches/);
assert.match(sessions, /origin", "unattributed-local"/);
assert.match(sessions, /transportRequestId/);
assert.match(sessions, /modelCallId/);
assert.match(sessions, /traceId/);
assert.match(sessions, /!value\.startsWith\("workspace\/", ignoreCase = true\)\) return null/);

assert.match(fence, /internal object RiftMutationFence/);
assert.match(fence, /CANCEL_RETENTION_MS = 10 \* 60 \* 1000L/);
assert.match(fence, /MAX_REPO_KEYS = 16/);
assert.match(fence, /return "workspace\/\$first"/);
assert.match(fence, /Workspace writer lease is already held/);
assert.match(fence, /cancelTransport/);
assert.match(fence, /requireActive/);
assert.match(fence, /originating request was cancelled/);
assert.match(fence, /leases\[key\]/);

assert.match(records, /\.put\("patchId", provenance\.getString\("patchId"\)\)/);
assert.match(records, /\.put\("provenance", provenance\)/);
assert.match(records, /"transportRequestId"/);
assert.match(records, /"modelCallId"/);
assert.match(records, /"traceId"/);
assert.match(records, /RiftPatchSessions\.resolve/);
assert.match(records, /RiftPatchSessions\.unattributed/);

assert.match(sandbox, /provenanceMutationPaths/);
assert.match(sandbox, /RiftMutationFence\.begin/);
assert.match(sandbox, /RiftMutationFence\.requireActive/);
assert.match(sandbox, /transaction\.rollback\(\)/);
assert.match(sandbox, /origin = origin/);
assert.match(sandbox, /executeRequest\(raw, "mcp"\)/);
assert.match(sandbox, /executeRequest\(raw, "rift-cli"\)/);

assert.match(shell, /origin = "native-shell"/);
assert.match(apps, /origin = "native-editor"/);
assert.match(devlab, /origin = "devlab"/);
assert.match(git, /origin = "native-git"/);
assert.match(host, /"intent"/);
assert.match(host, /"transportRequestId"/);
assert.match(host, /"modelCallId"/);
assert.match(host, /"traceId"/);

assert.match(gradle, /RiftPatchSessions\.kt/);
assert.match(gradle, /RiftMutationFence\.kt/);
assert.match(ownership, /RiftPatchSessions\.kt/);
assert.match(ownership, /RiftMutationFence\.kt/);
assert.match(ownership, /scripts\/test-rift-patch-sessions\.mjs/);

console.log('ok - patch sessions persist model/transport/trace identity and mutation fencing is source-owned');
