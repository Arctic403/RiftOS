import fs from 'node:fs';
import assert from 'node:assert/strict';
import '../src/semnexis-bootstrap.js';

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
assert.match(compiler, /SNIRV7/);
assert.match(compiler, /NATIVE_IR_BINARY_VERSION_V7/);
for (const symbol of [
  'encodeNativeIRV2','decodeNativeIRV2',
  'encodeNativeIRV3','decodeNativeIRV3',
  'encodeNativeIRV4','decodeNativeIRV4',
  'encodeNativeIRV5','decodeNativeIRV5',
  'encodeNativeIRV6','decodeNativeIRV6',
  'encodeNativeIRV7','decodeNativeIRV7'
]) assert.ok(compiler.includes(symbol), 'Semnexis compiler is missing frozen IR compatibility symbol ' + symbol);
assert.match(compiler, /zext\.u8\.i32/);
assert.match(compiler, /Slice<u8>/);
assert.match(compiler, /slice\.len/);
assert.match(compiler, /slice\.get\.u8/);
assert.match(compiler, /record\.make/);
assert.match(compiler, /record\.get/);
assert.match(compiler, /phi\.record/);
assert.match(compiler, /ret\.record/);
assert.match(compiler, /arm32LdrbReg/);
assert.match(compiler, /i32\.add\.checked/);
assert.match(runtime, /semx dump-ir <source\.snx>/);
assert.match(runtime, /format:compiler\.irSchema/);
assert.match(runtime, /semx emit-arm32-proof <source\.snx>/);
assert.match(runtime, /semx emit-arm32-runtime <source\.snx>/);
assert.match(runtime, /\/documents\/builds\/Semnexis\/semx-arm32-proof\.elf/);
assert.match(runtime, /\/documents\/builds\/Semnexis\/semx-arm32-runtime\.elf/);
assert.match(runtime, /__rift_write_semnexis_binary/);
assert.match(compiler, /SEMNEXIS_ARM32_ELF_PROOF_V0/);
assert.match(compiler, /SEMNEXIS_ARM32_RUNTIME_ELF_V0/);
assert.match(compiler, /constantEvaluated:false/);
assert.match(compiler, /runtimeLowered:true/);
assert.match(compiler, /linear-scan-r4-r7-v0/);
assert.match(compiler, /buildArm32RuntimeDivHelperV0/);
assert.match(compiler, /checkedArithmetic:\['add','sub','mul','div'\]/);
assert.match(compiler, /verifyNativeIRControlFlow/);
assert.match(compiler, /phi\.i32/);
assert.match(compiler, /br\.cmp\.lt/);
assert.match(compiler, /cfg-spill-v0/);
assert.match(compiler, /loop_backedge/);
assert.match(runtime, /semnexis-bootstrap-self-test\/13/);
assert.match(runtime, /hardeningDerivedEffects/);
assert.match(runtime, /hardeningCanonicalMachineVerify/);
assert.match(runtime, /hardeningExpressionBudget/);
assert.match(runtime, /numeric\.isFinite\(\)/);
assert.match(runtime, /MAX_SEMNEXIS_SOURCE_BYTES/);
assert.match(runtime, /arm32LoopBackedge/);
assert.match(compiler, /generated-artifact-not-executed-from-riftfs/);
assert.doesNotMatch(compiler, /\beval\s*\(|new Function|\bprocess\b|XMLHttpRequest|fetch\s*\(/);

const commandMatch = runtime.match(/const val SEMNEXIS_COMMAND_ENTRY = \"\"\"([\s\S]*?)\"\"\"\s*\n\s*const val RIFTPP_COMMAND_ENTRY/);
assert.ok(commandMatch, 'embedded Semnexis command entry must be extractable');
let embeddedResult = null;
globalThis.__rift_request = () => JSON.stringify({args:['self-test'], cwd:'/workspace/Semnexis'});
globalThis.__rift_read_text = () => { throw new Error('embedded self-test must not read workspace source'); };
globalThis.__rift_write_semnexis_binary = () => { throw new Error('embedded self-test must not write artifacts'); };
globalThis.__rift_result = value => { embeddedResult = JSON.parse(String(value)); };
(0, eval)(commandMatch[1]);
assert.equal(embeddedResult?.result?.ok, true);
assert.equal(embeddedResult.result.schema, 'semnexis-bootstrap-self-test/17');
assert.equal(embeddedResult.result.compiler, '0.7.0-quickjs-bootstrap');
assert.equal(embeddedResult.result.irBinaryVersion, 0);
assert.equal(embeddedResult.result.irBinaryLatestFormat, 'SNIRV7');
assert.equal(embeddedResult.result.irBinaryLatestVersion, 7);
assert.equal(
  embeddedResult.result.irBinaryCompatibility,
  'frozen-v0-v1-v2-v3-v4-v5-v6-plus-v7-arena-state-reject-unknown-version-flags-opcodes'
);
assert.equal(embeddedResult.result.irGraphNodeSemantics, 'advisory-correlation-id-v0');

assert.equal(embeddedResult.result.sliceIrBinaryFormat, 'SNIRV2');
assert.equal(embeddedResult.result.sliceV1Rejects, true);
assert.ok(embeddedResult.result.sliceIrBinaryBytes > 0);
assert.ok(embeddedResult.result.arm32SliceBytes > 0);

assert.equal(embeddedResult.result.recordIrBinaryFormat, 'SNIRV3');
assert.equal(embeddedResult.result.recordV2Rejects, true);
assert.ok(embeddedResult.result.recordIrBinaryBytes > 0);
assert.ok(embeddedResult.result.arm32RecordBytes > 0);

assert.equal(embeddedResult.result.projectionIrBinaryFormat, 'SNIRV4');
assert.equal(embeddedResult.result.projectionV3Rejects, true);
assert.ok(embeddedResult.result.projectionIrBinaryBytes > 0);
assert.ok(embeddedResult.result.arm32ProjectionBytes > 0);

assert.equal(embeddedResult.result.recordConditionalIrBinaryFormat, 'SNIRV5');
assert.equal(embeddedResult.result.recordConditionalV4Rejects, true);
assert.equal(embeddedResult.result.recordConditionalPhiCount, 2);
assert.equal(embeddedResult.result.arm32RecordConditionalAllocator, 'cfg-spill-v0');

assert.equal(embeddedResult.result.recordLoopIrBinaryFormat, 'SNIRV5');
assert.equal(embeddedResult.result.recordLoopV4Rejects, true);
assert.equal(embeddedResult.result.recordLoopPhiRecordCount, 1);
assert.equal(embeddedResult.result.recordLoopPhiI32Count, 1);
assert.equal(embeddedResult.result.arm32RecordLoopAllocator, 'cfg-spill-v0');

assert.equal(embeddedResult.result.decimalIrBinaryFormat, 'SNIRV6');
assert.equal(embeddedResult.result.decimalV5Rejects, true);
assert.equal(embeddedResult.result.decimalZextCount, 1);
assert.equal(embeddedResult.result.arm32DecimalAllocator, 'cfg-spill-v0');

assert.equal(embeddedResult.result.arenaReadIrBinaryFormat, 'SNIRV7');
assert.equal(embeddedResult.result.arenaReadV6Rejects, true);
assert.equal(embeddedResult.result.arenaReadLoadCount, 1);
assert.equal(embeddedResult.result.arm32ArenaReadAllocator, 'linear-scan-r4-r7-v0');

assert.equal(embeddedResult.result.parserStateStackIrBinaryFormat, 'SNIRV7');
assert.ok(embeddedResult.result.parserStateStackIrBinaryBytes > 0);
assert.ok(embeddedResult.result.arm32ParserStateStackBytes > 0);

assert.equal(embeddedResult.result.recordLoopYieldIrBinaryFormat, 'SNIRV5');
assert.ok(embeddedResult.result.recordLoopYieldIrBinaryBytes > 0);
assert.ok(embeddedResult.result.arm32RecordLoopYieldBytes > 0);

assert.equal(embeddedResult.result.boundedRecursionFunctions, 1);
assert.equal(embeddedResult.result.boundedRecursionMaxDepth, 256);
assert.ok(embeddedResult.result.arm32BoundedRecursionBytes > 0);

assert.equal(embeddedResult.result.hardeningDerivedEffects, true);
assert.equal(embeddedResult.result.hardeningCanonicalMachineVerify, true);
assert.equal(embeddedResult.result.hardeningExpressionBudget, true);
delete globalThis.__rift_request;
delete globalThis.__rift_read_text;
delete globalThis.__rift_write_semnexis_binary;
delete globalThis.__rift_result;

console.log('ok - Semnexis QuickJS shell bootstrap boundary');
