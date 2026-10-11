import assert from 'node:assert/strict';
import fs from 'node:fs';
const base = new URL('../', import.meta.url);
const read = name => fs.readFileSync(new URL(name, base), 'utf8');
const manager = read('android/app/src/main/java/com/riftos/app/RiftRappManager.kt');
const quickjs = read('android/app/src/main/java/com/riftos/app/RiftRappQuickJsExecutor.kt');
const sessions = read('android/app/src/main/java/com/riftos/app/RiftCoreAppSessions.kt');
const externalCore = read('external-components/IndependentCoreV1.kt');
const externalSessions = read('external-components/IndependentRappSessionsV1.kt');

for (const [name, source, expression] of [
  ['RAPP package runtime', manager, /MAX_RUNTIME_BYTES = 8 \* 1024 \* 1024/],
  ['RAPP whole ZIP', manager, /MAX_PACKAGE_BYTES = 16L \* 1024L \* 1024L/],
  ['QuickJS runtime source', quickjs, /MAX_RUNTIME_BYTES =\s*8 \* 1024 \* 1024/],
  ['Independent Core runtime', externalCore, /MAX_RUNTIME_BYTES = 8L \* 1024 \* 1024/],
  ['Independent Core JS session', externalSessions, /MAX_RUNTIME = 8 \* 1024 \* 1024/],
]) assert.match(source, expression, name + ' cap drift');
assert.match(manager, /MAX_PROGRAM_BYTES = 1024 \* 1024/);
assert.match(sessions, /MAX_PROGRAM_BYTES = 1024 \* 1024/);
assert.match(externalCore, /MAX_PROGRAM_BYTES = 1024 \* 1024L/);
assert.match(manager, /PROGRAM_ENTRY -> MAX_PROGRAM_BYTES/);
assert.match(manager, /RUNTIME_ENTRY -> MAX_RUNTIME_BYTES/);
assert.match(manager, /totalBytes <= MAX_PACKAGE_BYTES/);
assert.match(manager, /readBounded\(runtimeFile, MAX_RUNTIME_BYTES\)/);
assert.match(externalCore, /if \(name == "runtime.bin"\) MAX_RUNTIME_BYTES else MAX_PROGRAM_BYTES/);
assert.match(quickjs, /input\.size <=\s*MAX_INPUT_BYTES/);
assert.match(quickjs, /outputCapacity in\s*1\.\.MAX_OUTPUT_BYTES/);
const runtimeMax = 8 * 1024 * 1024;
const stateMax = 1024 * 1024;
const zipMax = 16 * 1024 * 1024;
assert.ok(runtimeMax > 1024 * 1024 && stateMax + runtimeMax + 64 * 1024 < zipMax);
console.log('RAPP 8 MiB executable/16 MiB ZIP aligned across installer, QuickJS and independent Core; mutable state and event I/O remain separately bounded (static contract).');
