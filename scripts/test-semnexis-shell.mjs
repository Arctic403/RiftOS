import fs from 'node:fs';
import assert from 'node:assert/strict';

const shell = fs.readFileSync(new URL('../android/app/src/main/java/com/riftos/app/RiftNativeShell.kt', import.meta.url), 'utf8');
const runtime = fs.readFileSync(new URL('../android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt', import.meta.url), 'utf8');
const gradle = fs.readFileSync(new URL('../android/app/build.gradle.kts', import.meta.url), 'utf8');
const compiler = fs.readFileSync(new URL('../src/semnexis-bootstrap.js', import.meta.url), 'utf8');
const nativeToolchain = new URL('../android/app/src/main/java/com/riftos/app/RiftNativeToolchain.kt', import.meta.url);

assert.equal(fs.existsSync(nativeToolchain), false, 'retired RiftNativeToolchain.kt must stay removed');
assert.match(shell, /"semx"\s*->\s*\{\s*val value = headlessJs\.executeSemnexis\(args, cwd\)/s);
assert.doesNotMatch(shell, /riftclang|RiftNativeToolchain|nativeToolchain/);
assert.match(runtime, /fun executeSemnexis\(args: List<String>, cwd: String\): CommandResult/);
assert.match(runtime, /preparedSemnexisSource\(\)/);
assert.match(runtime, /www\/src\/semnexis-bootstrap\.js/);
assert.doesNotMatch(runtime, /ProcessBuilder|\/system\/bin\/sh/);
assert.match(gradle, /include\("src\/semnexis-bootstrap\.js"\)/);
assert.doesNotMatch(gradle, /RiftNativeToolchain\.kt/);
assert.match(compiler, /globalThis\.SemnexisBootstrap/);
assert.match(compiler, /only entry function 'main' may grant capability/);
assert.match(compiler, /SEMNEXIS_NATIVE_IR_V0/);
assert.match(compiler, /i32\.add\.checked/);
assert.match(runtime, /semx dump-ir <source\.snx>/);
assert.match(runtime, /format:compiler\.irSchema/);
assert.match(runtime, /semx emit-arm32-proof <source\.snx>/);
assert.match(runtime, /\/documents\/builds\/Semnexis\/semx-arm32-proof\.elf/);
assert.match(runtime, /__rift_write_semnexis_binary/);
assert.match(compiler, /SEMNEXIS_ARM32_ELF_PROOF_V0/);
assert.match(compiler, /generated-artifact-not-executed-from-riftfs/);
assert.doesNotMatch(compiler, /\beval\s*\(|new Function|\bprocess\b|XMLHttpRequest|fetch\s*\(/);

console.log('ok - Semnexis QuickJS shell bootstrap boundary');
