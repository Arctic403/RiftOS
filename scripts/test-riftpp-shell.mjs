import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';

const reference = readFileSync('src/riftos.js', 'utf8');
const batch = readFileSync('src/riftshell-batch.js', 'utf8');
const shell = readFileSync('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt', 'utf8');
const gradle = readFileSync('android/app/build.gradle.kts', 'utf8');
const core = readFileSync('src/riftpp-core.js', 'utf8');
const vm = readFileSync('src/riftvm.js', 'utf8');

// Historical compiler/VM source remains an offline reference oracle.
// The permanent APK must not ship a second project-owned language CLI/VM.
assert.match(reference, /async function runRiftppShell\(/);
assert.match(batch, /DISABLED: RiftShell batch commands are disabled/);
assert.match(core, /compileRiftPlusPlusCoreProgramV1/);
assert.match(vm, /executeRiftExecutable/);
assert.doesNotMatch(shell, /"riftpp"\s*->|"rift-tool"\s*->|headlessJs\.|riftpp help\|version/);
assert.equal(existsSync('android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt'), false);
for (const asset of ['src/riftpp-core.js', 'src/riftvm.js', 'src/semnexis-bootstrap.js']) {
  assert.ok(!gradle.includes('include("' + asset + '")'), 'retired APK language asset reintroduced: ' + asset);
}
assert.match(shell, /RiftHostCoreComponents\.executionView\(\)/);
console.log('ok - Rift++ legacy language shell retired; reference source retained outside APK');
