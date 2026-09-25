import assert from 'node:assert/strict';
import fs from 'node:fs';
import { mountWorkspaceRecords } from '../workspace-live/app.js';

const gradle=fs.readFileSync('android/app/build.gradle.kts','utf8');
const nativeApps=fs.readFileSync('android/app/src/main/java/com/riftos/app/RiftNativeWorkspaceApps.kt','utf8');
const records=fs.readFileSync('android/app/src/main/java/com/riftos/app/RiftWorkspaceRecords.kt','utf8');
const watcher=fs.readFileSync('android/app/src/main/java/com/riftos/app/RiftWorkspaceWatcher.kt','utf8');
const nativeGit=fs.readFileSync('android/app/src/main/java/com/riftos/app/RiftNativeGit.kt','utf8');
assert.match(records,/MAX_RECORDS = 256/,'Workspace Records history must stay compact');
assert.match(records,/RECORD_OPERATION_TIMEOUT_MS = 60_000L/,'Workspace Records bounded operation budget drifted');
assert.match(records,/TRACKING_POLICY_VERSION = 2/,'Workspace Records tracking policy migration missing');
assert.match(records,/candidateSessionsByPath/,'active candidate session evidence must be persisted separately');
assert.match(records,/candidateStateSha256/,'stable candidate-state identity must be separate from the full evidence manifest');
assert.match(records,/sealedManifest\.put\("candidateId", "candidate-\$\{candidateStateSha\.take\(24\)\}"\)/,'candidateId must derive from candidate-state evidence');
const candidateManifestBody = records.slice(
  records.indexOf('private fun buildCandidateManifest('),
  records.indexOf('private fun manifestEntryJson(')
);
const candidateStateBody = candidateManifestBody.slice(
  candidateManifestBody.indexOf('val candidateState = JSONObject()'),
  candidateManifestBody.indexOf('val candidateStateSha =')
);
assert.ok(!candidateStateBody.includes('recordChain'),'record-chain diagnostics must not affect candidate identity');
assert.ok(!candidateStateBody.includes('oldestRetainedAt'),'retained-history age must not affect candidate identity');
assert.ok(!candidateStateBody.includes('prunedThroughSequence'),'history pruning must not affect candidate identity');
assert.ok(!candidateStateBody.includes('sessionEvidence'),'patch/session provenance must not affect candidate identity');
assert.match(records,/prepareForRead\("semantic-impact-seed", requireCurrent = true\)/,'semantic impact must use watcher-aware currentness');

const checkpointBody = records.slice(
  records.indexOf('private fun createCheckpoint('),
  records.indexOf('private fun queryInternal(')
);
assert.ok(nativeGit.includes('.put("gitRoot", gitRoot ?: "")'),'RiftGit must pass repo scope into Workspace Records checkpoints');
assert.ok(checkpointBody.includes('val prefix = gitRoot'),'repo checkpoint scope must be derived from gitRoot');
assert.ok(checkpointBody.includes('if (prefix == null) {'),'global checkpoint behavior must be explicit');
const scopedCheckpointBody = checkpointBody.slice(
  checkpointBody.indexOf('} else {'),
  checkpointBody.indexOf('checkpointAt =')
);
assert.ok(scopedCheckpointBody.includes('.filter { path -> matchesPrefix(path, prefix) }'),'scoped checkpoint must remove only paths inside the pushed repo');
assert.ok(scopedCheckpointBody.includes('resetSnapshotPrefix(checkpointRoot, prefix)'),'scoped checkpoint must reset only the pushed repo snapshot subtree');
assert.ok(scopedCheckpointBody.includes('if (!matchesPrefix(path, prefix)) continue'),'scoped checkpoint must copy only observed paths inside the pushed repo');
assert.ok(scopedCheckpointBody.includes('candidateSessionsByPath.keys'),'scoped checkpoint must prune candidate sessions by repo path');
assert.ok(!scopedCheckpointBody.includes('checkpoint.clear()'),'repo push must not clear the whole workspace checkpoint');
assert.ok(!scopedCheckpointBody.includes('resetSnapshotRoot(checkpointRoot)'),'repo push must not reset the whole workspace checkpoint snapshot');
assert.ok(!scopedCheckpointBody.includes('candidateSessionsByPath.clear()'),'repo push must not erase unrelated candidate session evidence');
assert.ok(!scopedCheckpointBody.includes('candidateSessionEvidenceComplete = true'),'repo push must not globally upgrade incomplete session evidence');
assert.ok(records.includes('private fun resetSnapshotPrefix(root: File, prefix: String)'),'scoped snapshot cleanup helper missing');

assert.match(records,/IGNORED_DIRECTORY_NAMES/,'generated/cache tracking policy missing');
assert.match(watcher,/records\.shouldTrackDirectory/,'watcher must share Workspace Records tracking policy');
assert.match(watcher,/records\.updateWatcherCoverage/,'watcher completeness must feed Workspace Records currentness');
assert.ok(!gradle.includes('workspace-live'),'retained Workspace Records HTML adapter must not be packaged');
assert.ok(nativeApps.includes('WORKSPACE RECORDS · LOCAL ONLY'),'native Workspace Records owner marker missing');

function fakeRoot() {
  const elements = new Map();
  return {
    elements,
    querySelector(selector) {
      if (!elements.has(selector)) {
        elements.set(selector, {
          textContent: '', innerHTML: '', value: '', disabled: false,
          classList: { toggle() {} }, addEventListener() {}
        });
      }
      return elements.get(selector);
    }
  };
}
const tick = () => new Promise(resolve => setImmediate(resolve));

const root = fakeRoot();
const calls = [];
let listener = null, subscriptionsRemoved = 0;
const invoke = async method => {
  calls.push(method);
  if (method === 'info') return { watch: { active: true } };
  if (method === 'records') return {
    summary: { records: 2, changedFiles: 1 }, checkpoint: { at: 1, reason: 'git:push' },
    files: [{ path: 'project/main.js', status: 'modified', diff: '+updated' }],
    records: [{ id: 'rec-2', path: 'project/main.js', action: 'modified', at: 2, diff: '+updated' }]
  };
  throw new Error(`Unexpected startup call: ${method}`);
};
const mounted = mountWorkspaceRecords(root, invoke, callback => {
  listener = callback;
  return () => { listener = null; subscriptionsRemoved++; };
});
await tick();
assert.equal(root.querySelector('#recordCount').textContent, '2');
assert.equal(root.querySelector('#localCount').textContent, '1');
assert.equal(root.querySelector('#liveText').textContent, 'Local recording on');
assert.match(root.querySelector('#recordList').innerHTML, /project\/main\.js/);
assert.deepEqual(calls.sort(), ['info', 'records']);
assert.equal(typeof listener, 'function');
mounted.destroy();
assert.equal(listener, null);
assert.equal(subscriptionsRemoved, 1);

const failingRoot = fakeRoot();
const failing = mountWorkspaceRecords(failingRoot, async method => {
  if (method === 'info') return { watch: { active: true } };
  throw new Error('Native records unavailable');
}, () => () => {});
await tick();
assert.match(failingRoot.querySelector('#recordList').innerHTML, /Tap Refresh to retry/);
assert.match(failingRoot.querySelector('#checkpointText').textContent, /Native records unavailable/);
failing.destroy();
console.log('ok - retained Workspace Records UI adapter regression remains un-packaged; native owner is present');
