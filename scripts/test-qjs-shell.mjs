import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';

const shell = readFileSync('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt', 'utf8');
const gradle = readFileSync('android/app/build.gradle.kts', 'utf8');
const rappEngine = readFileSync('android/app/src/main/java/com/riftos/app/RiftRappQuickJsExecutor.kt', 'utf8');

// The developer-only qjs command was retired; real installed RAPPs still need
// the generic QuickJS execution bridge until an external provider is device-proven.
assert.doesNotMatch(shell, /"qjs"\s*->|headlessJs\.|qjs help\|version\|eval\|run/);
assert.equal(existsSync('android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt'), false);
assert.doesNotMatch(gradle, /RiftHeadlessJsRuntime\.kt|include\("src\/riftpp-core\.js"\)|include\("src\/riftvm\.js"\)|include\("src\/semnexis-bootstrap\.js"\)/);
assert.match(gradle, /io\.github\.dokar3:quickjs-kt:1\.0\.14/);
assert.match(rappEngine, /quickJs|QuickJs|quickjs/);
console.log('ok - retired shell qjs host absent; live RAPP QuickJS bridge retained');
