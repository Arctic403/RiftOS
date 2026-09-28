import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = path => fs.readFileSync(path, 'utf8');

const service = read('android/app/src/main/java/com/riftos/app/RiftppCompilerService.kt');
const native = read('android/app/src/main/cpp/riftpp/riftpp_compiler_host.cpp');
const cmake = read('android/app/src/main/cpp/CMakeLists.txt');
const manifest = read('android/app/src/main/AndroidManifest.xml');
const gradle = read('android/app/build.gradle.kts');
const shell = read('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt');

for (const required of [
  'android:name=".RiftppCompilerService"',
  'android:exported="false"',
  'android:process=":riftppCompiler"',
]) {
  assert.ok(manifest.includes(required), `Rift++ compiler service manifest contract missing: ${required}`);
}

assert.ok(service.includes('System.loadLibrary("riftpp_compiler_host")'));
assert.ok(service.includes('COMPILER_BYTES = 276'));
assert.ok(service.includes('MAX_SOURCE_BYTES = 4096'));
assert.ok(service.includes('MAX_OUTPUT_BYTES = 4096'));
assert.ok(service.includes('b1f33b940d2ac199f5e38c1c621cd8b27ed15dd3a60fcb85daad7b7154b2ee0c'));
assert.ok(service.includes('1725b5341e87a09943737130a945d8ee500492370da8ce648f696b331e118653'));
assert.ok(service.includes('Process.killProcess(workerPid)'), 'timeout path must kill the private worker process');
assert.ok(service.includes('compiler-process-died'), 'binder death must be classified as compiler crash');

assert.ok(native.includes('constexpr jsize kCompilerBytes = 276'));
assert.ok(native.includes('PROT_NONE'));
assert.ok(native.includes('PROT_READ | PROT_WRITE'));
assert.ok(native.includes('PROT_READ | PROT_EXEC'));
assert.ok(native.includes('__builtin___clear_cache'));
assert.ok(native.includes('using CompilerFn = uint32_t (*)('));
assert.ok(native.includes('compilerResult != 0xffffffffU'));
assert.ok(native.includes('outputRegion.pageSize - static_cast<size_t>(outputLength)'));

for (const forbidden of ['RPP0', '"ret ', 'QuickJS', 'runVm1', 'MOVI', 'BRNZ']) {
  assert.ok(!service.includes(forbidden), `Kotlin compiler host gained compiler semantics: ${forbidden}`);
  assert.ok(!native.includes(forbidden), `native compiler host gained compiler semantics: ${forbidden}`);
}

assert.ok(cmake.includes('riftpp_compiler_host'));
assert.ok(cmake.includes('riftpp/riftpp_compiler_host.cpp'));
assert.ok(gradle.includes('RiftppCompilerService.kt'));
assert.ok(gradle.includes('riftpp/riftpp_compiler_host.cpp'));

assert.ok(shell.includes('"riftpp-host" -> executeRiftppHostCommand(cwd, args)'));
assert.ok(shell.includes('compiler.arm64.hex'));
assert.ok(shell.includes('compiler.arm32.hex'));
assert.ok(shell.includes('RiftppCompilerClient.execute('));
assert.ok(shell.includes('text.endsWith("\\n")'), 'canonical compiler hex newline must be accepted');
assert.ok(!shell.includes('text.endsWith("\\\\n")'), 'literal backslash-n must not be treated as compiler-file newline');

console.log('Rift++ machine-code compiler host regression PASS');
