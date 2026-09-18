import assert from 'node:assert/strict';
import fs from 'node:fs';
import { mountWorkspaceRecords } from '../workspace-live/app.js';

const gradle=fs.readFileSync('android/app/build.gradle.kts','utf8');
const nativeApps=fs.readFileSync('android/app/src/main/java/com/riftos/app/RiftNativeWorkspaceApps.kt','utf8');
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
