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
assert.ok(service.includes('TRANSACTION_COMPILE_PROOF'));
assert.ok(service.includes('TRANSACTION_STAGE1_SELF_HOST'));
assert.ok(service.includes('STAGE1_EXECUTION_TIMEOUT_MS = 15_000L'));
assert.ok(service.includes('e3415740508a3db18f53744a2b8c900a1349b898eeb186e6159d21b38c9829f5'));
assert.ok(service.includes('ed2fb30fbd7819bd3adc3c427835ed70d7bd3167ae731357fe10697afdd3eb59'));
assert.ok(service.includes('1d5a87efb088e68ef1cec2b80c49c2a484d5e83d2c327d8131ef81e18ca9556b'));
assert.ok(service.includes('7b11fae1b5ad0314a6fcf1a310c57e2c10b90cb8b5b14b40c010e89aa3c3c431'));
assert.ok(service.includes('executeStage1SelfHost'));
assert.ok(service.includes('proveGeneratedPayload'));
assert.ok(service.includes('Process.killProcess(workerPid)'), 'timeout path must kill the private worker process');
assert.ok(service.includes('compiler-process-died'), 'binder death must be classified as compiler crash');

assert.ok(native.includes('constexpr jsize kCompilerBytes = 276'));
assert.ok(native.includes('PROT_NONE'));
assert.ok(native.includes('PROT_READ | PROT_WRITE'));
assert.ok(native.includes('PROT_READ | PROT_EXEC'));
assert.ok(native.includes('__builtin___clear_cache'));
assert.ok(native.includes('using CompilerFn = uint32_t (*)('));
assert.ok(native.includes('using GeneratedPayloadFn = uint32_t (*)()'));
assert.ok(native.includes('kHostPayloadOffset = 16U'));
assert.ok(native.includes('kHostPayloadOffset = 24U'));
assert.ok(native.includes('proveGeneratedPayload == JNI_TRUE'));
assert.ok(native.includes('compilerResult != 0xffffffffU'));
assert.ok(native.includes('bootstrapStage1Image'));
assert.ok(native.includes('runStage1Compiler'));
assert.ok(native.includes('nativeStage1SelfHost'));
assert.ok(native.includes('kStage1Arm32ImageBytes = 340'));
assert.ok(native.includes('kStage1Arm64ImageBytes = 336'));
assert.ok(native.includes("source[end] != static_cast<uint8_t>('\\n')"));
assert.ok(!native.includes('strtol('), 'Stage1 bootstrap host must not parse decimal values');
assert.ok(!native.includes('strtoul('), 'Stage1 bootstrap host must not parse decimal values');
assert.ok(!native.includes('nativeExecutePayload'), 'generic caller-supplied payload execution API appeared');
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
assert.ok(shell.includes('riftpp-host prove'));
assert.ok(shell.includes('riftpp-host stage1-selfhost <riftpp-root>'));
assert.ok(shell.includes('stage1/stage1.arm32.rpp'));
assert.ok(shell.includes('stage1/stage1.arm64.rpp'));
assert.ok(shell.includes('RiftppCompilerClient.executeStage1SelfHost('));
assert.ok(shell.includes('val proveGeneratedPayload = action == "prove"'));
assert.ok(!shell.includes('payload-file'), 'prove route must not accept caller-supplied executable payload files');
assert.ok(shell.includes('text.endsWith("\\n")'), 'canonical compiler hex newline must be accepted');
assert.ok(!shell.includes('text.endsWith("\\\\n")'), 'literal backslash-n must not be treated as compiler-file newline');


assert.ok(service.includes('TRANSACTION_S2_BOOTSTRAP'));
assert.ok(service.includes('S2_BOOTSTRAP_TIMEOUT_MS = 15_000L'));
assert.ok(service.includes('S2_GENA_ARM32_SOURCE_BYTES = 22809'));
assert.ok(service.includes('S2_GENA_ARM64_SOURCE_BYTES = 20230'));
assert.ok(service.includes('S2_GENA_ARM32_IMAGE_BYTES = 3128'));
assert.ok(service.includes('S2_GENA_ARM64_IMAGE_BYTES = 2884'));
assert.ok(service.includes('602ea5053ad483a3a27e6239812e26afc6f641dd1affcabf54d92999f17665b9'));
assert.ok(service.includes('d96060c42ffa7b1eec2cd01efbc046368d1f738a43e95f36ef5813394f5f805d'));
assert.ok(service.includes('d8a725107677188fdde1b6926139eb0b2da0719afe4c0f717d2c7a880237f49c'));
assert.ok(service.includes('f0e3c871b4765bd94d69d71a26ffdfbcc3eabe3e114de492681f9999892cdfa5'));
assert.ok(service.includes('executeS2Bootstrap'));
assert.ok(service.includes('nativeS2Bootstrap'));
assert.ok(native.includes('Java_com_riftos_app_RiftppCompilerService_nativeS2Bootstrap'));
assert.ok(native.includes('kS2GenAArm32SourceBytes = 22809'));
assert.ok(native.includes('kS2GenAArm64SourceBytes = 20230'));
assert.ok(native.includes('kS2GenAArm32ImageBytes = 3128'));
assert.ok(native.includes('kS2GenAArm64ImageBytes = 2884'));
assert.ok(native.includes('proof(nullptr, 0U, nullptr, 0U)'));
assert.ok(shell.includes('riftpp-host s2-bootstrap <riftpp-root>'));
assert.ok(shell.includes('s2/bootstrap/compiler.gena.arm32.rpp'));
assert.ok(shell.includes('s2/bootstrap/compiler.gena.arm64.rpp'));
assert.ok(shell.includes('s2/ret42.arm32.r2.hex'));
assert.ok(shell.includes('s2/ret42.arm64.r2.hex'));
assert.ok(shell.includes('RiftppCompilerClient.executeS2Bootstrap('));
assert.ok(shell.includes('decodeRiftppFixedRecordHex'));
assert.ok(shell.includes('genAArm32SourceFile.length() <= 32768L'));
assert.ok(shell.includes('genAArm64SourceFile.length() <= 32768L'));
assert.ok(shell.includes('sourceFile.length() <= 4096L'), 'generic compile source bound must remain 4096');
assert.ok(!shell.includes('payload-file'), 'S2 bootstrap must not expose arbitrary executable payload input');

assert.ok(service.includes('TRANSACTION_S2_VECTORS'));
assert.ok(service.includes('S2_VECTOR_SOURCE_BYTES = 560'));
assert.ok(service.includes('S2_VECTOR_OUTPUT_BYTES = 2240'));
assert.ok(service.includes('cf33c59d52c504e3464e6e23e527ea22dc0e64117b82d3f4b5dcd3a0541ea412'));
assert.ok(service.includes('3075cb2d91a3bf1411d1a0b63c1c38dd2f3482ae3f3b2ea146d35699156aa287'));
assert.ok(service.includes('executeS2Vectors'));
assert.ok(service.includes('nativeS2Vectors'));
assert.ok(service.includes('outputsExecuted'));
assert.ok(native.includes('Java_com_riftos_app_RiftppCompilerService_nativeS2Vectors'));
assert.ok(native.includes('kS2VectorSourceBytes = 560'));
assert.ok(native.includes('kS2VectorOutputBytes = 2240'));
assert.ok(native.includes('runStage1Compiler('));
assert.ok(!native.includes('reinterpret_cast<CompilerFn>(output32)'), 'S2 vector output must never execute');
assert.ok(!native.includes('reinterpret_cast<CompilerFn>(output64)'), 'S2 vector output must never execute');
assert.ok(shell.includes('riftpp-host s2-vectors <riftpp-root>'));
assert.ok(shell.includes('s2/vectors/emitter-corpus.arm32.r2.hex'));
assert.ok(shell.includes('s2/vectors/emitter-corpus.arm64.r2.hex'));
assert.ok(shell.includes('s2/bootstrap/$genAName'));
assert.ok(shell.includes('decodeRiftppExactRawHex'));
assert.ok(shell.includes('RiftppCompilerClient.executeS2Vectors('));
assert.ok(shell.includes('sourceFile.length() <= 4096L'), 'generic compile source bound must remain 4096');

console.log('Rift++ machine-code compiler host regression PASS');
