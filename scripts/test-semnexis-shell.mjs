import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import '../src/semnexis-bootstrap.js';

const shell = readFileSync('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt', 'utf8');
const gradle = readFileSync('android/app/build.gradle.kts', 'utf8');
const compiler = readFileSync('src/semnexis-bootstrap.js', 'utf8');

// Semnexis stays as repository source with its dedicated compiler, ARM32,
// and self-hosting tests; its old in-APK headless shell is no longer shipped.
assert.doesNotMatch(shell, /"semx"\s*->|headlessJs\.|semx help\|version/);
assert.equal(existsSync('android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt'), false);
assert.doesNotMatch(gradle, /include\("src\/semnexis-bootstrap\.js"\)/);
for (const marker of ['globalThis.SemnexisBootstrap','SEMNEXIS_NATIVE_IR_V0','SNIRV7','verifyNativeIRControlFlow','SEMNEXIS_ARM32_RUNTIME_ELF_V0']) {
  assert.ok(compiler.includes(marker), 'Semnexis source regression: ' + marker);
}
assert.doesNotMatch(compiler, /\beval\s*\(|new Function|XMLHttpRequest|fetch\s*\(/);
console.log('ok - Semnexis shell host retired; compiler/IR/source remains independently testable');
