import assert from 'node:assert/strict';
import { compileSemnexisV0, inspectSemnexisV0, encodeSemnexisNativeIRV0, decodeSemnexisNativeIRV0, encodeSemnexisNativeIRV1, decodeSemnexisNativeIRV1, encodeSemnexisNativeIRV2, decodeSemnexisNativeIRV2, encodeSemnexisNativeIRV3, decodeSemnexisNativeIRV3, encodeSemnexisNativeIRV4, decodeSemnexisNativeIRV4, encodeSemnexisNativeIRV5, decodeSemnexisNativeIRV5, encodeSemnexisNativeIRV6, decodeSemnexisNativeIRV6, encodeSemnexisNativeIRV7, decodeSemnexisNativeIRV7, encodeSemnexisNativeIR, decodeSemnexisNativeIR, emitSemnexisArm32ElfProofV0, verifySemnexisArm32ElfProofV0, emitSemnexisArm32RuntimeElfV0, verifySemnexisArm32RuntimeElfV0 } from '../src/semnexis-bootstrap.js';

const smokeSource = 'fn main() -> i32 {\n    return 40 + 2;\n}\n';
const expectedGraph = 'SEMNEXIS_PROGRAM_GRAPH_V0\n' +
  'node 0 Module root\n' +
  'node 1 Type i32 width=32 signed=true\n' +
  'node 2 Effect pure observable_effects=none\n' +
  'node 3 Effect time observable_effects=time\n' +
  'node 4 Capability time authority=clock\n' +
  'node 5 Intrinsic clock arity=0\n' +
  'node 6 Function main symbol=fn::main effect_proof=closed_pure_graph_v0 capability_proof=none_required_v0\n' +
  'node 7 Region main.local lifetime=function escape=false proof=no_reference_values_v0\n' +
  'node 8 Constant integer value=40\n' +
  'node 9 Constant integer value=2\n' +
  'node 10 Binary Plus operation=Plus\n' +
  'node 11 Return return\n' +
  'edge 5 -> 1 returns_type\n' +
  'edge 5 -> 3 has_effect\n' +
  'edge 5 -> 4 requires_capability\n' +
  'edge 0 -> 6 contains\n' +
  'edge 6 -> 1 returns_type\n' +
  'edge 6 -> 7 executes_in\n' +
  'edge 8 -> 1 has_type\n' +
  'edge 6 -> 8 contains_expr\n' +
  'edge 9 -> 1 has_type\n' +
  'edge 6 -> 9 contains_expr\n' +
  'edge 10 -> 8 lhs\n' +
  'edge 10 -> 9 rhs\n' +
  'edge 10 -> 1 has_type\n' +
  'edge 6 -> 10 contains_expr\n' +
  'edge 6 -> 11 contains\n' +
  'edge 11 -> 10 returns_value\n' +
  'edge 11 -> 1 has_type\n' +
  'edge 6 -> 2 has_effect\n';

const expectedPlan = 'SEMNEXIS_EXECUTION_PLAN_V0\n' +
  'step 0 enter_function main\n' +
  'step 1 create_region main.local\n' +
  'step 2 return main\n' +
  'step 3 destroy_region main.local\n' +
  'step 4 leave_function main\n';

const expectedIR = 'SEMNEXIS_NATIVE_IR_V0\n' +
  'function main graph=6 return=i32 effect=pure region=main.local requires=- grants=-\n' +
  'inst 0 region.begin region=main.local graph=7\n' +
  'inst 1 %v0:i32 = const.i32 40 graph=8\n' +
  'inst 2 %v1:i32 = const.i32 2 graph=9\n' +
  'inst 3 %v2:i32 = i32.add.checked %v0 %v1 graph=10\n' +
  'inst 4 region.end region=main.local graph=7\n' +
  'inst 5 ret.i32 %v2 graph=11\n' +
  'endfunction main\n';

const smoke = compileSemnexisV0(smokeSource);
assert.equal(smoke.graphText, expectedGraph);
assert.equal(smoke.planText, expectedPlan);
assert.equal(smoke.irText, expectedIR);
assert.equal(smoke.ir.functions.length, 1);
assert.equal(smoke.ir.functions[0].instructions.length, 6);
const smokeBinary = encodeSemnexisNativeIRV0(smoke.ir);
assert.equal(smokeBinary.length, 157);
assert.equal(decodeSemnexisNativeIRV0(smokeBinary).dump(), expectedIR);
assert.deepEqual(Array.from(encodeSemnexisNativeIRV0(smoke.ir)), Array.from(smokeBinary));
const corruptBinary = Uint8Array.from(smokeBinary);
corruptBinary[0] ^= 0xff;
assert.throws(() => decodeSemnexisNativeIRV0(corruptBinary), /magic mismatch/);

const symbols = 'fn add(a: i32, b: i32) -> i32 {\n' +
  '    return a + b;\n}\n\n' +
  'fn main() -> i32 {\n' +
  '    let base = 40;\n' +
  '    let answer = add(base, 2);\n' +
  '    return answer;\n}\n';
const symbolsInspect = inspectSemnexisV0(symbols);
assert.deepEqual(symbolsInspect.functions, ['add', 'main']);
assert.equal(symbolsInspect.irFunctions, 2);
assert.ok(symbolsInspect.irInstructions > 6);

const effectSource = 'fn sample_time() -> i32 {\n' +
  '    return clock();\n}\n\n' +
  'fn main() -> i32 with time {\n' +
  '    return sample_time();\n}\n';
const effect = compileSemnexisV0(effectSource);
assert.match(effect.graphText, /Function sample_time .*effect_proof=transitive_time_graph_v0 capability_proof=transitive_requirement_v0/);
assert.match(effect.graphText, /Function main .*effect_proof=transitive_time_graph_v0 capability_proof=satisfied_by_grant_v0/);

const rejects = [
  ['fn add(a: i32, b: i32) -> i32 { return a + b; }\nfn main() -> i32 { return add(1); }\n', /expects 2 argument/],
  ['fn main(a: i32) -> i32 { let a = 4; return a; }\n', /duplicate symbol/],
  ['fn sample_time() -> i32 { return clock(); }\nfn main() -> i32 { return sample_time(); }\n', /requires time/],
  ['fn main() -> i32 { return ; }\n', /expected expression/],
  ['fn main() -> i32 with network { return 0; }\n', /unknown capability/],
  ['fn main() -> i32 { return missing + 1; }\n', /unknown name/],
  ['fn main() -> i32 { return 2147483648; }\n', /signed i32 range/]
];
for (const [source, pattern] of rejects) assert.throws(() => compileSemnexisV0(source), pattern);

assert.throws(
  () => compileSemnexisV0('fn helper() -> i32 with time { return clock(); }\nfn main() -> i32 with time { return helper(); }\n'),
  /only entry function 'main' may grant capability/,
  'library functions must not mint ambient capability'
);

const nativeProgram = compileSemnexisV0(
  'fn add(a: i32, b: i32) -> i32 { return a + b; }\n' +
  'fn main() -> i32 { return add(40, 2); }\n'
);
const arm32 = emitSemnexisArm32ElfProofV0(nativeProgram.ir);
assert.equal(arm32.schema, 'SEMNEXIS_ARM32_ELF_PROOF_V0');
assert.equal(arm32.target, 'armv7a-linux-androideabi26');
assert.equal(arm32.byteLength, 100);
assert.equal(arm32.constantResult, 42);
assert.deepEqual(Array.from(arm32.bytes.slice(0, 4)), [0x7f, 0x45, 0x4c, 0x46]);
assert.equal(arm32.bytes[18] | (arm32.bytes[19] << 8), 40);
assert.equal(verifySemnexisArm32ElfProofV0(arm32), true);
const effectfulNativeProgram = compileSemnexisV0('fn main() -> i32 with time { return clock(); }\n');
assert.throws(
  () => emitSemnexisArm32ElfProofV0(effectfulNativeProgram.ir),
  /effectful\/capability function/,
  'proof backend must reject runtime effects until runtime lowering exists'
);

const runtimeProgram = compileSemnexisV0(
  'fn add(a: i32, b: i32) -> i32 { return a + b; }\n' +
  'fn main() -> i32 { return add(40, 2); }\n'
);
const runtimeArm32 = emitSemnexisArm32RuntimeElfV0(runtimeProgram.ir);
assert.equal(runtimeArm32.schema, 'SEMNEXIS_ARM32_RUNTIME_ELF_V0');
assert.equal(runtimeArm32.target, 'armv7a-linux-androideabi26');
assert.equal(runtimeArm32.byteLength, 192);
assert.equal(runtimeArm32.constantEvaluated, false);
assert.equal(runtimeArm32.runtimeLowered, true);
assert.equal(runtimeArm32.functions.length, 2);
assert.equal(verifySemnexisArm32RuntimeElfV0(runtimeArm32, runtimeProgram.ir), true);
assert.ok(runtimeArm32.functions.every(fn => fn.frameBytes % 8 === 0));
assert.equal(runtimeArm32.allocator, 'linear-scan-r4-r7-v0');
assert.ok(runtimeArm32.functions.every(fn => fn.allocator === runtimeArm32.allocator));
assert.ok(runtimeArm32.functions.every(fn => fn.spillSlots === 0));
const readArmWord = (bytes, offset) => (
  bytes[offset] |
  (bytes[offset + 1] << 8) |
  (bytes[offset + 2] << 16) |
  (bytes[offset + 3] << 24)
) >>> 0;
const decodeArmBranchTarget = (word, fromAddress) => {
  let imm24 = word & 0x00ffffff;
  if (imm24 & 0x00800000) imm24 |= 0xff000000;
  return (fromAddress + 8 + (imm24 | 0) * 4) >>> 0;
};
const runtimeAdd = runtimeArm32.functions.find(fn => fn.name === 'add');
const runtimeMain = runtimeArm32.functions.find(fn => fn.name === 'main');
assert.ok(runtimeAdd && runtimeMain);
let sawRuntimeAdd = false;
let sawOverflowBranch = false;
let sawCallToAdd = false;
for (let offset = runtimeAdd.fileOffset; offset < runtimeAdd.fileOffset + runtimeAdd.bytes; offset += 4) {
  const word = readArmWord(runtimeArm32.bytes, offset);
  if ((word & 0x0FF00000) === 0x00900000) sawRuntimeAdd = true;
  if (((word & 0xFF000000) >>> 0) === 0x6A000000) {
    sawOverflowBranch = decodeArmBranchTarget(
      word,
      runtimeAdd.address + (offset - runtimeAdd.fileOffset)
    ) === runtimeArm32.trapAddress;
  }
}
for (let offset = runtimeMain.fileOffset; offset < runtimeMain.fileOffset + runtimeMain.bytes; offset += 4) {
  const word = readArmWord(runtimeArm32.bytes, offset);
  if (((word & 0xFF000000) >>> 0) === 0xEB000000 &&
      decodeArmBranchTarget(word, runtimeMain.address + (offset - runtimeMain.fileOffset)) === runtimeAdd.address) {
    sawCallToAdd = true;
  }
}
assert.equal(sawRuntimeAdd, true);
assert.equal(sawOverflowBranch, true);
assert.equal(sawCallToAdd, true);
const runtimeSub = emitSemnexisArm32RuntimeElfV0(compileSemnexisV0(
  'fn sub(a: i32, b: i32) -> i32 { return a - b; }\n' +
  'fn main() -> i32 { return sub(40, 2); }\n'
).ir);
const runtimeSubFn = runtimeSub.functions.find(fn => fn.name === 'sub');
let sawRuntimeSub = false;
for (let offset = runtimeSubFn.fileOffset; offset < runtimeSubFn.fileOffset + runtimeSubFn.bytes; offset += 4) {
  if ((readArmWord(runtimeSub.bytes, offset) & 0x0FF00000) === 0x00500000) sawRuntimeSub = true;
}
assert.equal(sawRuntimeSub, true);
const nestedRuntime = emitSemnexisArm32RuntimeElfV0(compileSemnexisV0(
  'fn add(a: i32, b: i32) -> i32 { return a + b; }\n' +
  'fn main() -> i32 { return add(add(20, 20), 2); }\n'
).ir);
const nestedMain = nestedRuntime.functions.find(fn => fn.name === 'main');
let nestedCalls = 0;
for (let offset = nestedMain.fileOffset; offset < nestedMain.fileOffset + nestedMain.bytes; offset += 4) {
  if (((readArmWord(nestedRuntime.bytes, offset) & 0xFF000000) >>> 0) === 0xEB000000) nestedCalls += 1;
}
assert.equal(nestedCalls, 2);
const runtimeMul = emitSemnexisArm32RuntimeElfV0(compileSemnexisV0(
  'fn mul(a: i32, b: i32) -> i32 { return a * b; }\n' +
  'fn main() -> i32 { return mul(6, 7); }\n'
).ir);
const runtimeMulFn = runtimeMul.functions.find(fn => fn.name === 'mul');
let sawSmull = false;
let sawMulOverflowTrap = false;
for (let offset = runtimeMulFn.fileOffset; offset < runtimeMulFn.fileOffset + runtimeMulFn.bytes; offset += 4) {
  const word = readArmWord(runtimeMul.bytes, offset);
  if (word === 0xE0C32190) sawSmull = true;
  if (((word & 0xFF000000) >>> 0) === 0x1A000000 &&
      decodeArmBranchTarget(word, runtimeMulFn.address + (offset - runtimeMulFn.fileOffset)) === runtimeMul.trapAddress) {
    sawMulOverflowTrap = true;
  }
}
assert.equal(sawSmull, true);
assert.equal(sawMulOverflowTrap, true);

const runtimeDiv = emitSemnexisArm32RuntimeElfV0(compileSemnexisV0(
  'fn divv(a: i32, b: i32) -> i32 { return a / b; }\n' +
  'fn main() -> i32 { return divv(84, 2); }\n'
).ir);
assert.equal(runtimeDiv.divisionHelperBytes, 716);
const runtimeDivFn = runtimeDiv.functions.find(fn => fn.name === 'divv');
let sawDivHelperCall = false;
for (let offset = runtimeDivFn.fileOffset; offset < runtimeDivFn.fileOffset + runtimeDivFn.bytes; offset += 4) {
  const word = readArmWord(runtimeDiv.bytes, offset);
  if (((word & 0xFF000000) >>> 0) === 0xEB000000 &&
      decodeArmBranchTarget(word, runtimeDivFn.address + (offset - runtimeDivFn.fileOffset)) === runtimeDiv.divisionHelperAddress) {
    sawDivHelperCall = true;
  }
}
assert.equal(sawDivHelperCall, true);
const divOffset = runtimeDiv.divisionHelperAddress - 0x00010000;
const divZeroBranch = readArmWord(runtimeDiv.bytes, divOffset + 4);
assert.equal(readArmWord(runtimeDiv.bytes, divOffset), 0xE3510000);
assert.equal(decodeArmBranchTarget(divZeroBranch, runtimeDiv.divisionHelperAddress + 4), runtimeDiv.trapAddress);

const arithmeticRuntime = emitSemnexisArm32RuntimeElfV0(compileSemnexisV0(
  'fn arithmetic(a: i32, b: i32) -> i32 {\n' +
  ' let sum = a + b;\n' +
  ' let difference = a - b;\n' +
  ' let product = sum * difference;\n' +
  ' return product / b;\n' +
  '}\n' +
  'fn main() -> i32 { return arithmetic(84, 2); }\n'
).ir);
assert.equal(arithmeticRuntime.byteLength, 1016);
assert.deepEqual(arithmeticRuntime.checkedArithmetic, ['add','sub','mul','div']);
assert.equal(arithmeticRuntime.divisionHelperBytes, 716);

const spillRuntime = emitSemnexisArm32RuntimeElfV0(compileSemnexisV0(
  'fn sum4(a: i32, b: i32, c: i32, d: i32) -> i32 { return a + b + c + d; }\n' +
  'fn main() -> i32 {\n' +
  ' let a = 1;\n let b = 2;\n let c = 3;\n let d = 4;\n let e = 5;\n' +
  ' return sum4(a, b, c, d) + e;\n}\n'
).ir);
const spillMain = spillRuntime.functions.find(fn => fn.name === 'main');
assert.ok(spillMain.spillSlots > 0);
assert.ok(spillMain.frameBytes >= 8);
assert.equal(spillMain.allocator, 'linear-scan-r4-r7-v0');

assert.throws(
  () => emitSemnexisArm32RuntimeElfV0(effectfulNativeProgram.ir),
  /unsupported effect\/capability function/,
  'runtime backend must reject time/capability functions while allowing explicit borrowed-state lowering'
);


const conditionalSource =
  'fn choose(a: i32, b: i32) -> i32 {\n' +
  ' return if a < b { a + 1 } else { b + 2 };\n' +
  '}\n' +
  'fn main() -> i32 { return choose(3, 5); }\n';
const conditional = compileSemnexisV0(conditionalSource);
const conditionalBinary = encodeSemnexisNativeIRV0(conditional.ir);
assert.equal(conditionalBinary.length, 674);
assert.equal(decodeSemnexisNativeIRV0(conditionalBinary).dump(), conditional.irText);
assert.match(conditional.planText, /branch_if comparison/);
assert.match(conditional.planText, /merge_phi i32/);
assert.match(conditional.irText, /br\.cmp\.lt .*then=if0\.then else=if0\.else/);
assert.match(conditional.irText, /phi\.i32 if0\.then:%v\d+ if0\.else:%v\d+/);

const conditionOps = [
  ['<', 0xBA000000],
  ['<=', 0xDA000000],
  ['>', 0xCA000000],
  ['>=', 0xAA000000],
  ['==', 0x0A000000],
  ['!=', 0x1A000000]
];
for (const [operator, branchBase] of conditionOps) {
  const compiled = compileSemnexisV0(
    'fn choose(a: i32, b: i32) -> i32 { return if a ' + operator + ' b { a + 1 } else { b + 2 }; }\n' +
    'fn main() -> i32 { return choose(3, 5); }\n'
  );
  const bytes = encodeSemnexisNativeIRV0(compiled.ir);
  assert.equal(bytes.length, 674);
  assert.equal(decodeSemnexisNativeIRV0(bytes).dump(), compiled.irText);
  const artifact = emitSemnexisArm32RuntimeElfV0(compiled.ir);
  assert.equal(artifact.byteLength, 340);
  assert.equal(artifact.allocator, 'mixed-v0');
  assert.equal(artifact.controlFlowLowered, true);
  const choose = artifact.functions.find(fn => fn.name === 'choose');
  assert.equal(choose.allocator, 'cfg-spill-v0');
  assert.equal(choose.blockCount, 4);
  const blocks = Object.fromEntries(choose.blocks.map(block => [block.label, block]));
  const sortedBlocks = choose.blocks.slice().sort((a, b) => a.wordIndex - b.wordIndex);
  const rowsFor = (label) => {
    const index = sortedBlocks.findIndex(block => block.label === label);
    assert.ok(index >= 0);
    const block = sortedBlocks[index];
    const end = index + 1 < sortedBlocks.length
      ? sortedBlocks[index + 1].fileOffset
      : choose.fileOffset + choose.bytes;
    const rows = [];
    for (let offset = block.fileOffset; offset < end; offset += 4) {
      rows.push({
        word:readArmWord(artifact.bytes, offset),
        address:choose.address + (offset - choose.fileOffset)
      });
    }
    return rows;
  };
  const entryRows = rowsFor('entry');
  const predicate = entryRows.find(row => (((row.word & 0xFF000000) >>> 0) === branchBase));
  const fallback = entryRows.find(row => (((row.word & 0xFF000000) >>> 0) === 0xEA000000));
  assert.ok(predicate);
  assert.ok(fallback);
  assert.equal(decodeArmBranchTarget(predicate.word, predicate.address), blocks['if0.then'].address);
  assert.equal(decodeArmBranchTarget(fallback.word, fallback.address), blocks['if0.else'].address);
  for (const label of ['if0.then', 'if0.else']) {
    const rows = rowsFor(label);
    const mergeBranch = rows.filter(row => (((row.word & 0xFF000000) >>> 0) === 0xEA000000)).at(-1);
    assert.ok(mergeBranch);
    assert.equal(decodeArmBranchTarget(mergeBranch.word, mergeBranch.address), blocks['if0.merge'].address);
    const branchIndex = rows.indexOf(mergeBranch);
    assert.ok(branchIndex >= 2);
    assert.equal(((rows[branchIndex - 1].word & 0xFFFFF000) >>> 0), 0xE58D0000);
  }
}

const nestedConditional = compileSemnexisV0(
  'fn nested(a: i32, b: i32, c: i32) -> i32 {\n' +
  ' return if a < b { if b < c { a + b } else { b + c } } else { a + c };\n' +
  '}\n' +
  'fn main() -> i32 { return nested(1, 2, 3); }\n'
);
assert.equal(
  decodeSemnexisNativeIRV0(encodeSemnexisNativeIRV0(nestedConditional.ir)).dump(),
  nestedConditional.irText
);
const nestedConditionalArm32 = emitSemnexisArm32RuntimeElfV0(nestedConditional.ir);
const nestedConditionalFn = nestedConditionalArm32.functions.find(fn => fn.name === 'nested');
assert.equal(nestedConditionalFn.blockCount, 7);
assert.equal(nestedConditionalFn.allocator, 'cfg-spill-v0');
assert.equal(nestedConditionalArm32.byteLength, 444);

assert.throws(
  () => compileSemnexisV0('fn main() -> i32 { return if 1 { 2 } else { 3 }; }\n'),
  /condition must be a comparison yielding bool/
);
assert.throws(
  () => compileSemnexisV0('fn main() -> i32 { return if 1 < 2 < 3 { 2 } else { 3 }; }\n'),
  /chained comparisons/
);

const loopSource =
  'fn sum(n: i32) -> i32 {\n' +
  ' return loop (i = 0, acc = 0) while i < n { next (i + 1, acc + i); } yield acc;\n' +
  '}\n' +
  'fn main() -> i32 { return sum(5); }\n';
const loopProgram = compileSemnexisV0(loopSource);
const loopBinary = encodeSemnexisNativeIRV0(loopProgram.ir);
assert.equal(loopBinary.length, 756);
assert.equal(decodeSemnexisNativeIRV0(loopBinary).dump(), loopProgram.irText);
assert.match(loopProgram.planText, /loop_header 2_state/);
assert.match(loopProgram.planText, /loop_backedge simultaneous_next/);
assert.match(loopProgram.planText, /loop_yield i32/);
assert.match(loopProgram.irText, /phi\.i32 entry:%v0 loop0\.body:%v8/);
assert.match(loopProgram.irText, /phi\.i32 entry:%v1 loop0\.body:%v11/);
const loopArm32 = emitSemnexisArm32RuntimeElfV0(loopProgram.ir);
assert.equal(loopArm32.byteLength, 368);
assert.equal(loopArm32.allocator, 'mixed-v0');
assert.equal(loopArm32.controlFlowLowered, true);
const sumFn = loopArm32.functions.find(fn => fn.name === 'sum');
assert.equal(sumFn.allocator, 'cfg-spill-v0');
assert.equal(sumFn.blockCount, 4);
const loopBlocks = Object.fromEntries(sumFn.blocks.map(block => [block.label, block]));
const sortedLoopBlocks = sumFn.blocks.slice().sort((a, b) => a.wordIndex - b.wordIndex);
const loopRowsFor = (label) => {
  const index = sortedLoopBlocks.findIndex(block => block.label === label);
  assert.ok(index >= 0);
  const block = sortedLoopBlocks[index];
  const end = index + 1 < sortedLoopBlocks.length
    ? sortedLoopBlocks[index + 1].fileOffset
    : sumFn.fileOffset + sumFn.bytes;
  const rows = [];
  for (let offset = block.fileOffset; offset < end; offset += 4) {
    rows.push({
      word:readArmWord(loopArm32.bytes, offset),
      address:sumFn.address + (offset - sumFn.fileOffset)
    });
  }
  return rows;
};
const entryLoopRows = loopRowsFor('entry');
const entryToHeader = entryLoopRows.filter(row => (((row.word & 0xFF000000) >>> 0) === 0xEA000000)).at(-1);
assert.ok(entryToHeader);
assert.equal(decodeArmBranchTarget(entryToHeader.word, entryToHeader.address), loopBlocks['loop0.header'].address);
const entryBranchIndex = entryLoopRows.indexOf(entryToHeader);
assert.ok(entryLoopRows.slice(Math.max(0, entryBranchIndex - 4), entryBranchIndex)
  .filter(row => (((row.word & 0xFFFFF000) >>> 0) === 0xE58D0000)).length >= 2);

const headerRows = loopRowsFor('loop0.header');
const headerTrue = headerRows.find(row => (((row.word & 0xFF000000) >>> 0) === 0xBA000000));
const headerFalse = headerRows.find(row => (((row.word & 0xFF000000) >>> 0) === 0xEA000000));
assert.ok(headerTrue && headerFalse);
assert.equal(decodeArmBranchTarget(headerTrue.word, headerTrue.address), loopBlocks['loop0.body'].address);
assert.equal(decodeArmBranchTarget(headerFalse.word, headerFalse.address), loopBlocks['loop0.exit'].address);

const bodyRows = loopRowsFor('loop0.body');
const backedge = bodyRows.filter(row => (((row.word & 0xFF000000) >>> 0) === 0xEA000000)).at(-1);
assert.ok(backedge);
assert.equal(decodeArmBranchTarget(backedge.word, backedge.address), loopBlocks['loop0.header'].address);
assert.ok(backedge.address > loopBlocks['loop0.header'].address);
const backedgeIndex = bodyRows.indexOf(backedge);
assert.ok(bodyRows.slice(Math.max(0, backedgeIndex - 5), backedgeIndex)
  .filter(row => (((row.word & 0xFFFFF000) >>> 0) === 0xE58D0000)).length >= 2);

assert.throws(
  () => compileSemnexisV0(
    'fn main() -> i32 { return loop (i = 0, a = 0) while i < 2 { next (i + 1); } yield a; }\n'
  ),
  /next value count/
);
assert.throws(
  () => compileSemnexisV0(
    'fn main() -> i32 { let i = 0; return loop (i = 1) while i < 2 { next (i + 1); } yield i; }\n'
  ),
  /shadows an existing symbol/
);
assert.throws(
  () => compileSemnexisV0(
    'fn main() -> i32 { return loop (i = 0, i = 1) while i < 2 { next (i + 1, i + 1); } yield i; }\n'
  ),
  /duplicate carried state/
);


const malformedTarget = compileSemnexisV0(conditionalSource).ir;
malformedTarget.functions[0].instructions.find(inst => inst.op === 'br.cmp.lt').thenLabel = 'missing.block';
assert.throws(
  () => malformedTarget.verify(),
  /branch target 'missing\.block' does not exist/,
  'CFG verifier must reject missing branch targets'
);

const malformedPhi = compileSemnexisV0(conditionalSource).ir;
const malformedPhiInst = malformedPhi.functions[0].instructions.find(inst => inst.op === 'phi.i32');
malformedPhiInst.incoming.pop();
malformedPhiInst.args.pop();
assert.throws(
  () => malformedPhi.verify(),
  /malformed phi|phi predecessors do not match/,
  'CFG verifier must reject incomplete phi predecessor sets'
);

const dominanceLeak = compileSemnexisV0(conditionalSource).ir;
const dominanceFn = dominanceLeak.functions[0];
const branchOnlyValue = dominanceFn.instructions.find(inst => inst.op === 'i32.add.checked').result;
dominanceFn.instructions.at(-1).args = [branchOnlyValue];
assert.throws(
  () => dominanceLeak.verify(),
  /does not dominate use/,
  'CFG verifier must reject branch-local values used after merge without phi'
);

const malformedLoopPhi = compileSemnexisV0(loopSource).ir;
const malformedLoopPhiInst = malformedLoopPhi.functions[0].instructions.find(inst => inst.op === 'phi.i32');
malformedLoopPhiInst.incoming[1].label = 'loop0.exit';
assert.throws(
  () => malformedLoopPhi.verify(),
  /phi predecessors do not match/,
  'CFG verifier must reject incorrect loop backedge predecessor labels'
);


const forgedDirectEffect = compileSemnexisV0('fn main() -> i32 with time { return clock(); }\n').ir;
forgedDirectEffect.functions[0].effect = 'pure';
forgedDirectEffect.functions[0].requiresCapabilities = [];
forgedDirectEffect.functions[0].grantsCapabilities = [];
assert.throws(
  () => forgedDirectEffect.verify(),
  /effect metadata mismatch/,
  'IR verifier must derive direct intrinsic effects rather than trust stored metadata'
);

const forgedTransitiveEffect = compileSemnexisV0(
  'fn helper() -> i32 { return clock(); }\n' +
  'fn main() -> i32 with time { return helper(); }\n'
).ir;
const forgedHelper = forgedTransitiveEffect.functions.find(fn => fn.name === 'helper');
forgedHelper.effect = 'pure';
forgedHelper.requiresCapabilities = [];
assert.throws(
  () => forgedTransitiveEffect.verify(),
  /effect metadata mismatch/,
  'IR verifier must derive transitive call effects rather than trust stored metadata'
);

assert.throws(
  () => verifySemnexisArm32RuntimeElfV0(runtimeArm32),
  /source IR is required/,
  'runtime machine verifier must require the originating verified IR'
);

const tamperProgram = compileSemnexisV0(
  'fn add(a: i32, b: i32) -> i32 { return a + b; }\n' +
  'fn main() -> i32 { return add(40, 2); }\n'
);
const tamperedArithmetic = emitSemnexisArm32RuntimeElfV0(tamperProgram.ir);
const tamperedAdd = tamperedArithmetic.functions.find(fn => fn.name === 'add');
let tamperedAddOffset = -1;
for (let offset = tamperedAdd.fileOffset; offset < tamperedAdd.fileOffset + tamperedAdd.bytes; offset += 4) {
  const word = readArmWord(tamperedArithmetic.bytes, offset);
  if ((word & 0x0FF00000) === 0x00900000) { tamperedAddOffset = offset; break; }
}
assert.ok(tamperedAddOffset >= 0);
tamperedArithmetic.bytes[tamperedAddOffset] = 0x00;
tamperedArithmetic.bytes[tamperedAddOffset + 1] = 0x00;
tamperedArithmetic.bytes[tamperedAddOffset + 2] = 0xA0;
tamperedArithmetic.bytes[tamperedAddOffset + 3] = 0xE1;
assert.throws(
  () => verifySemnexisArm32RuntimeElfV0(tamperedArithmetic, tamperProgram.ir),
  /machine image differs from canonical IR lowering/,
  'runtime verifier must reject tampered arithmetic machine code'
);

const tamperedBranchProgram = compileSemnexisV0(
  'fn choose(a: i32, b: i32) -> i32 { return if a < b { 1 } else { 2 }; }\n' +
  'fn main() -> i32 { return choose(1, 2); }\n'
);
const tamperedBranch = emitSemnexisArm32RuntimeElfV0(tamperedBranchProgram.ir);
const tamperedChoose = tamperedBranch.functions.find(fn => fn.name === 'choose');
let conditionalBranchOffset = -1;
for (let offset = tamperedChoose.fileOffset; offset < tamperedChoose.fileOffset + tamperedChoose.bytes; offset += 4) {
  const word = readArmWord(tamperedBranch.bytes, offset);
  if (((word & 0xFF000000) >>> 0) === 0xBA000000) { conditionalBranchOffset = offset; break; }
}
assert.ok(conditionalBranchOffset >= 0);
tamperedBranch.bytes[conditionalBranchOffset] = 0x00;
tamperedBranch.bytes[conditionalBranchOffset + 1] = 0x00;
tamperedBranch.bytes[conditionalBranchOffset + 2] = 0x00;
tamperedBranch.bytes[conditionalBranchOffset + 3] = 0xEA;
assert.throws(
  () => verifySemnexisArm32RuntimeElfV0(tamperedBranch, tamperedBranchProgram.ir),
  /machine image differs from canonical IR lowering/,
  'runtime verifier must reject tampered branch machine code'
);

const cyclicPhiProgram = compileSemnexisV0(
  'fn swap_once() -> i32 {\n' +
  ' return loop (a = 1, b = 2, i = 0) while i < 1 { next (b, a, i + 1); } yield a;\n' +
  '}\n' +
  'fn main() -> i32 { return swap_once(); }\n'
);
const cyclicFn = cyclicPhiProgram.ir.functions.find(fn => fn.name === 'swap_once');
const cyclicPhis = cyclicFn.instructions.filter(inst => inst.op === 'phi.i32');
const cyclicA = cyclicPhis[0];
const cyclicB = cyclicPhis[1];
const cyclicBackedge = cyclicA.incoming[1].label;
cyclicA.incoming[1].value = cyclicB.result;
cyclicA.args[1] = cyclicB.result;
cyclicB.incoming[1].value = cyclicA.result;
cyclicB.args[1] = cyclicA.result;
cyclicPhiProgram.ir.verify();
const cyclicArtifact = emitSemnexisArm32RuntimeElfV0(cyclicPhiProgram.ir);
const cyclicMeta = cyclicArtifact.functions.find(fn => fn.name === 'swap_once');
const cyclicBlocks = cyclicMeta.blocks.slice().sort((a, b) => a.wordIndex - b.wordIndex);
const cyclicBodyIndex = cyclicBlocks.findIndex(block => block.label === cyclicBackedge);
assert.ok(cyclicBodyIndex >= 0);
const cyclicBody = cyclicBlocks[cyclicBodyIndex];
const cyclicBodyEnd = cyclicBodyIndex + 1 < cyclicBlocks.length
  ? cyclicBlocks[cyclicBodyIndex + 1].fileOffset
  : cyclicMeta.fileOffset + cyclicMeta.bytes;
let phiScratchLoad = false;
let phiScratchStore = false;
for (let offset = cyclicBody.fileOffset; offset < cyclicBodyEnd; offset += 4) {
  const word = readArmWord(cyclicArtifact.bytes, offset);
  if (((word & 0xFFFFF000) >>> 0) === 0xE59DC000) phiScratchLoad = true;
  if (((word & 0xFFFFF000) >>> 0) === 0xE58DC000) phiScratchStore = true;
}
assert.equal(phiScratchLoad, true, 'cyclic phi copies must preserve one source through r12');
assert.equal(phiScratchStore, true, 'cyclic phi copies must restore the preserved source from r12');
assert.equal(verifySemnexisArm32RuntimeElfV0(cyclicArtifact, cyclicPhiProgram.ir), true);

let deeplyNested = '1';
for (let i = 0; i < 300; i += 1) deeplyNested = 'if 0 < 1 { ' + deeplyNested + ' } else { 2 }';
assert.throws(
  () => compileSemnexisV0('fn main() -> i32 { return ' + deeplyNested + '; }\n'),
  /expression nesting exceeds compiler budget/,
  'frontend must bound recursive expression depth'
);

assert.throws(
  () => compileSemnexisV0(' '.repeat((512 * 1024) + 1)),
  /source exceeds compiler budget/,
  'frontend must reject source larger than its compiler budget before lexing'
);

const tokenBomb = 'fn main() -> i32 { return ' + Array(40000).fill('1').join(' + ') + '; }\n';
assert.throws(
  () => compileSemnexisV0(tokenBomb),
  /token count exceeds compiler budget/,
  'frontend must cap token count independently of source size'
);

assert.throws(
  () => compileSemnexisV0('fn ' + 'a'.repeat(256) + '() -> i32 { return 1; }\nfn main() -> i32 { return 1; }\n'),
  /identifier exceeds compiler budget/,
  'frontend must reject overlong identifiers before semantic construction'
);
assert.throws(
  () => compileSemnexisV0('fn main() -> i32 { return 00000000001; }\n'),
  /integer literal exceeds compiler budget/,
  'frontend must reject overlong integer literal text before numeric conversion'
);

const flatDepthBomb = 'fn main() -> i32 { return ' + Array(2000).fill('1').join(' + ') + '; }\n';
assert.throws(
  () => compileSemnexisV0(flatDepthBomb),
  /AST structural depth exceeds compiler budget/,
  'frontend must reject deeply left-nested ASTs even when parser recursion stays shallow'
);


const compatibilityBinary = encodeSemnexisNativeIRV0(smoke.ir);
const badVersion = compatibilityBinary.slice();
badVersion[8] = 1;
badVersion[9] = 0;
assert.throws(
  () => decodeSemnexisNativeIRV0(badVersion),
  /unsupported version/,
  'SNIRV0 must reject unknown binary versions'
);
const badReservedFlags = compatibilityBinary.slice();
badReservedFlags[10] = 1;
assert.throws(
  () => decodeSemnexisNativeIRV0(badReservedFlags),
  /nonzero reserved flags/,
  'SNIRV0 must reject unknown reserved flags'
);
const badOpcode = compatibilityBinary.slice();
assert.equal(badOpcode[51], 1, 'frozen smoke first opcode offset must remain region.begin');
badOpcode[51] = 0xFF;
assert.throws(
  () => decodeSemnexisNativeIRV0(badOpcode),
  /unknown opcode/,
  'SNIRV0 must reject unknown opcodes'
);

const staleIndexGraph = compileSemnexisV0('fn main() -> i32 { return 1; }\n').graph;
staleIndexGraph.edges.push({...staleIndexGraph.edges[0]});
assert.throws(
  () => staleIndexGraph.verify(),
  /duplicate semantic edge/,
  'Program Graph verification must rebuild its edge index from authoritative edges'
);

assert.throws(
  () => smoke.graph.dump(10),
  /graph dump exceeds output budget/,
  'Program Graph dumps must enforce construction-time output budgets'
);
assert.throws(
  () => smoke.plan.dump(10),
  /plan dump exceeds output budget/,
  'Execution Plan dumps must enforce construction-time output budgets'
);
assert.throws(
  () => smoke.ir.dump(10),
  /IR dump exceeds output budget/,
  'Native IR dumps must enforce construction-time output budgets'
);

let longCallSource = '';
for (let i = 0; i < 200; i += 1) {
  longCallSource += 'fn f' + i + '() -> i32 { return ' + (i === 199 ? '1' : ('f' + (i + 1) + '()')) + '; }\n';
}
longCallSource += 'fn main() -> i32 { return f0(); }\n';
const longCallProgram = compileSemnexisV0(longCallSource);
const longCallArtifact = emitSemnexisArm32RuntimeElfV0(longCallProgram.ir);
assert.equal(verifySemnexisArm32RuntimeElfV0(longCallArtifact, longCallProgram.ir), true);
assert.equal(longCallArtifact.functions.length, 201);

const recursiveProgram = compileSemnexisV0(
  'fn a() -> i32 { return b(); }\n' +
  'fn b() -> i32 { return a(); }\n' +
  'fn main() -> i32 { return a(); }\n'
);
const recursiveArtifact = emitSemnexisArm32RuntimeElfV0(recursiveProgram.ir);
assert.equal(verifySemnexisArm32RuntimeElfV0(recursiveArtifact, recursiveProgram.ir), true);
assert.deepEqual(recursiveArtifact.recursiveFunctions, ['a','b']);
assert.equal(recursiveArtifact.maxRecursiveCallDepth, 256);
assert.equal(recursiveArtifact.functions.find(fn => fn.name === 'a').boundedRecursion, true);
assert.equal(recursiveArtifact.functions.find(fn => fn.name === 'b').boundedRecursion, true);
assert.equal(recursiveArtifact.functions.find(fn => fn.name === 'main').boundedRecursion, false);


const autoSmokeBinary = encodeSemnexisNativeIR(smoke.ir);
assert.deepEqual(Array.from(autoSmokeBinary), Array.from(smokeBinary), 'auto binary encoder must preserve frozen SNIRV0 for i32-only IR');

const u8ProbeSource =
  'fn classify_byte(c: u8) -> i32 {\n' +
  ' return if c >= 48 { if c <= 57 { 3 } else { 4 } } else { 4 };\n' +
  '}\n' +
  'fn main() -> i32 { return classify_byte(65); }\n';
const u8Probe = compileSemnexisV0(u8ProbeSource);
assert.match(u8Probe.irText, /param 0 %arg0:u8 name=c/);
assert.match(u8Probe.irText, /copy\.u8/);
assert.match(u8Probe.irText, /const\.u8 48/);
const u8BinaryV1 = encodeSemnexisNativeIRV1(u8Probe.ir);
const u8BinaryAuto = encodeSemnexisNativeIR(u8Probe.ir);
assert.equal(String.fromCharCode(...u8BinaryV1.slice(0, 6)), 'SNIRV1');
assert.deepEqual(Array.from(u8BinaryAuto), Array.from(u8BinaryV1));
assert.equal(decodeSemnexisNativeIRV1(u8BinaryV1).dump(), u8Probe.irText);
assert.equal(decodeSemnexisNativeIR(u8BinaryAuto).dump(), u8Probe.irText);
assert.throws(
  () => encodeSemnexisNativeIRV0(u8Probe.ir),
  /SNIRV0 cannot encode u8 values/,
  'frozen SNIRV0 must reject the additive u8 IR surface'
);
const u8Runtime = emitSemnexisArm32RuntimeElfV0(u8Probe.ir);
assert.equal(verifySemnexisArm32RuntimeElfV0(u8Runtime, u8Probe.ir), true);
assert.equal(u8Runtime.byteLength, 348);

const u8Return = compileSemnexisV0(
  'fn id_byte(x: u8) -> u8 { return x; }\n' +
  'fn main() -> i32 { return 42; }\n'
);
assert.match(u8Return.irText, /ret\.u8/);
assert.equal(decodeSemnexisNativeIRV1(encodeSemnexisNativeIRV1(u8Return.ir)).dump(), u8Return.irText);

assert.throws(
  () => compileSemnexisV0('fn take(x: u8) -> i32 { return 1; }\nfn main() -> i32 { return take(300); }\n'),
  /integer literal is outside u8 range/,
  'u8 contextual literals must reject values above 255'
);
assert.throws(
  () => compileSemnexisV0('fn take(x: u8) -> i32 { return 1; }\nfn main() -> i32 { let x = 300; return take(x); }\n'),
  /requires u8, got i32/,
  'runtime i32 values must not narrow implicitly to u8'
);


const sliceProbeSource =
  'fn first(source: Slice<u8>) -> u8 { return slice_get(source, 0); }\n' +
  'fn main() -> i32 { return 0; }\n';
const sliceProbe = compileSemnexisV0(sliceProbeSource);
assert.match(sliceProbe.graphText, /Type Slice<u8> kind=borrowed_slice element=u8 mutability=read_only abi=descriptor_ptr_v0 descriptor_alignment=4 descriptor_layout=data_ptr@0,length_i32@4 escape=parameter_borrow_only/);
assert.match(sliceProbe.graphText, /Region first\.local lifetime=function escape=false proof=borrowed_slice_parameter_v0/);
assert.match(sliceProbe.irText, /param 0 %arg0:Slice<u8> name=source/);
assert.match(sliceProbe.irText, /copy\.slice\.u8/);
assert.match(sliceProbe.irText, /slice\.get\.u8/);
const sliceBinaryV2 = encodeSemnexisNativeIRV2(sliceProbe.ir);
const sliceBinaryAuto = encodeSemnexisNativeIR(sliceProbe.ir);
assert.equal(String.fromCharCode(...sliceBinaryV2.slice(0, 6)), 'SNIRV2');
assert.equal(sliceBinaryV2.length, 273);
assert.deepEqual(Array.from(sliceBinaryAuto), Array.from(sliceBinaryV2));
assert.equal(decodeSemnexisNativeIRV2(sliceBinaryV2).dump(), sliceProbe.irText);
assert.equal(decodeSemnexisNativeIR(sliceBinaryAuto).dump(), sliceProbe.irText);
assert.throws(
  () => encodeSemnexisNativeIRV1(sliceProbe.ir),
  /SNIRV1 cannot encode Slice<u8> values/,
  'frozen SNIRV1 must reject borrowed slice IR'
);
assert.throws(
  () => encodeSemnexisNativeIRV0(sliceProbe.ir),
  /SNIRV0 cannot encode Slice<u8> values/,
  'frozen SNIRV0 must reject borrowed slice IR'
);
const sliceRuntime = emitSemnexisArm32RuntimeElfV0(sliceProbe.ir);
assert.equal(verifySemnexisArm32RuntimeElfV0(sliceRuntime, sliceProbe.ir), true);
assert.equal(sliceRuntime.byteLength, 232);

assert.throws(
  () => compileSemnexisV0(
    'fn bad(source: Slice<u8>) -> u8 { return slice_get(source, source); }\n' +
    'fn main() -> i32 { return 0; }\n'
  ),
  /intrinsic 'slice_get' requires i32, got Slice<u8>/,
  'slice_get index must remain i32'
);
assert.throws(
  () => compileSemnexisV0(
    'fn bad(source: Slice<u8>) -> i32 { return slice_len(1); }\n' +
    'fn main() -> i32 { return 0; }\n'
  ),
  /intrinsic 'slice_len' requires Slice<u8>, got i32/,
  'slice_len must reject non-slice values'
);
assert.throws(
  () => compileSemnexisV0(
    'fn bad(source: Slice<u8>) -> Slice<u8> { return source; }\n' +
    'fn main() -> i32 { return 0; }\n'
  ),
  /must return i32, u8 or a flat record/,
  'borrowed Slice<u8> must not escape through function returns'
);
assert.throws(
  () => compileSemnexisV0(
    'fn bad(source: Vec<Slice<u8>>) -> i32 { return 0; }\n' +
    'fn main() -> i32 { return 0; }\n'
  ),
  /must be i32, u8, Slice<u8> or a flat record/,
  'generic type syntax may parse while unsupported generic semantics remain fail-closed'
);
const sliceMain = compileSemnexisV0('fn main(source: Slice<u8>) -> i32 { return slice_len(source); }\n');
assert.throws(
  () => emitSemnexisArm32RuntimeElfV0(sliceMain.ir),
  /entry function 'main' must have zero parameters/,
  'native process entry must not consume undefined Slice<u8> registers'
);


const tokenRecordSource =
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn first_token(source: Slice<u8>) -> Token {\n' +
  ' return Token { kind: 1, start: 0, end: slice_len(source) };\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n';
const tokenRecordProgram = compileSemnexisV0(tokenRecordSource);
assert.match(tokenRecordProgram.graphText, /Type Token kind=record abi=flat_words_v0 field_count=3/);
assert.match(tokenRecordProgram.irText, /record Token fields=kind:i32,start:i32,end:i32 abi=flat_words_v0/);
assert.match(tokenRecordProgram.irText, /record\.make Token/);
assert.match(tokenRecordProgram.irText, /ret\.record/);
const tokenRecordV3 = encodeSemnexisNativeIRV3(tokenRecordProgram.ir);
const tokenRecordAuto = encodeSemnexisNativeIR(tokenRecordProgram.ir);
assert.equal(String.fromCharCode(...tokenRecordV3.slice(0, 6)), 'SNIRV3');
assert.equal(tokenRecordV3.length, 358);
assert.deepEqual(Array.from(tokenRecordAuto), Array.from(tokenRecordV3));
assert.equal(decodeSemnexisNativeIRV3(tokenRecordV3).dump(), tokenRecordProgram.irText);
assert.equal(decodeSemnexisNativeIR(tokenRecordAuto).dump(), tokenRecordProgram.irText);
assert.throws(
  () => encodeSemnexisNativeIRV2(tokenRecordProgram.ir),
  /SNIRV2 cannot encode record values/,
  'frozen SNIRV2 must reject record IR'
);
const tokenRecordRuntime = emitSemnexisArm32RuntimeElfV0(tokenRecordProgram.ir);
assert.equal(verifySemnexisArm32RuntimeElfV0(tokenRecordRuntime, tokenRecordProgram.ir), true);
assert.equal(tokenRecordRuntime.byteLength, 248);
const firstTokenMeta = tokenRecordRuntime.functions.find(fn => fn.name === 'first_token');
assert.equal(firstTokenMeta.frameBytes, 16);
assert.equal(firstTokenMeta.slotCount, 3);
assert.equal(firstTokenMeta.spillSlots, 0);

const copiedRecordProgram = compileSemnexisV0(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn copy_token() -> Token {\n' +
  ' let t = Token { kind: 7, start: 8, end: 9 };\n' +
  ' return t;\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
assert.match(copiedRecordProgram.irText, /copy\.record/);
assert.equal(
  verifySemnexisArm32RuntimeElfV0(emitSemnexisArm32RuntimeElfV0(copiedRecordProgram.ir), copiedRecordProgram.ir),
  true
);

assert.throws(
  () => compileSemnexisV0(
    'struct Token { kind: i32, start: i32, end: i32 }\n' +
    'fn bad() -> Token { return Token { kind: 1, start: 0 }; }\n' +
    'fn main() -> i32 { return 0; }\n'
  ),
  /must initialize every field exactly once/,
  'record literals must initialize every field'
);
assert.throws(
  () => compileSemnexisV0(
    'struct TooWide { a: i32, b: i32, c: i32, d: i32, e: i32 }\n' +
    'fn main() -> i32 { return 0; }\n'
  ),
  /exceeds flat record ABI limit of 4 fields/,
  'flat record ABI must remain bounded to four words'
);
assert.throws(
  () => compileSemnexisV0(
    'struct Token { kind: i32, kind: i32 }\n' +
    'fn main() -> i32 { return 0; }\n'
  ),
  /duplicate struct field 'kind'/,
  'record field names must be unique'
);

const braceAmbiguityProgram = compileSemnexisV0(
  'fn sum(n: i32) -> i32 { return loop(i = 0, acc = 0) while i < n { next(i + 1, acc + i); } yield acc; }\n' +
  'fn main() -> i32 { return sum(5); }\n'
);
assert.equal(emitSemnexisArm32RuntimeElfV0(braceAmbiguityProgram.ir).byteLength, 368);

const recordParameterProgram = compileSemnexisV0(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn consume(token: Token) -> i32 { return token.end; }\n' +
  'fn relay() -> i32 { let token = Token { kind: 2, start: 4, end: 9 }; return consume(token); }\n' +
  'fn main() -> i32 { return 0; }\n'
);
const recordParameterRuntime = emitSemnexisArm32RuntimeElfV0(recordParameterProgram.ir);
assert.equal(
  verifySemnexisArm32RuntimeElfV0(recordParameterRuntime, recordParameterProgram.ir),
  true,
  'record parameters must lower through the r0-r3 flat-record ABI'
);
const mixedRecordParameterProgram = compileSemnexisV0(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn shifted(prefix: i32, token: Token) -> i32 { return prefix + token.end; }\n' +
  'fn main() -> i32 { return 0; }\n'
);
assert.equal(
  verifySemnexisArm32RuntimeElfV0(emitSemnexisArm32RuntimeElfV0(mixedRecordParameterProgram.ir), mixedRecordParameterProgram.ir),
  true,
  'one scalar plus a three-word record must exactly fill r0-r3'
);
const stackedRecordParameterProgram = compileSemnexisV0(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn stacked(prefix: i32, token: Token, suffix: i32) -> i32 { return prefix + token.end + suffix; }\n' +
  'fn relay_stacked() -> i32 { let token = Token { kind: 2, start: 4, end: 9 }; return stacked(10, token, 7); }\n' +
  'fn main() -> i32 { return 0; }\n'
);
assert.equal(
  verifySemnexisArm32RuntimeElfV0(emitSemnexisArm32RuntimeElfV0(stackedRecordParameterProgram.ir), stackedRecordParameterProgram.ir),
  true,
  'record parameters beyond r0-r3 must use the bounded aligned stack-argument ABI'
);
const overBudgetParameterList = Array.from({length:33}, (_, index) => 'p' + index + ': i32').join(', ');
const overBudgetParameterProgram = compileSemnexisV0(
  'fn too_many(' + overBudgetParameterList + ') -> i32 { return p0; }\n' +
  'fn main() -> i32 { return 0; }\n'
);
assert.throws(
  () => emitSemnexisArm32RuntimeElfV0(overBudgetParameterProgram.ir),
  /exceeds 32 argument words/,
  'native stack-argument ABI must remain bounded to 32 flattened words'
);

const recordCallProgram = compileSemnexisV0(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn make() -> Token { return Token { kind: 1, start: 0, end: 1 }; }\n' +
  'fn relay() -> Token { return make(); }\n' +
  'fn main() -> i32 { return 0; }\n'
);
assert.equal(
  verifySemnexisArm32RuntimeElfV0(emitSemnexisArm32RuntimeElfV0(recordCallProgram.ir), recordCallProgram.ir),
  true,
  'record-returning calls must lower through the r0-r3 flat-record ABI'
);

const recordProjectionProgram = compileSemnexisV0(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn first_token(source: Slice<u8>) -> Token { return Token { kind: 1, start: 0, end: slice_len(source) }; }\n' +
  'fn token_kind(source: Slice<u8>) -> i32 { let token = first_token(source); return token.kind; }\n' +
  'fn token_end(source: Slice<u8>) -> i32 { let token = first_token(source); return token.end; }\n' +
  'fn main() -> i32 { return 0; }\n'
);
assert.match(recordProjectionProgram.graphText, /Field kind record_type=Token field_index=0 field_name=kind/);
assert.match(recordProjectionProgram.graphText, /Field end record_type=Token field_index=2 field_name=end/);
assert.match(recordProjectionProgram.irText, /record\.get %v2 field=0/);
assert.match(recordProjectionProgram.irText, /record\.get %v2 field=2/);
const recordProjectionV4 = encodeSemnexisNativeIRV4(recordProjectionProgram.ir);
const recordProjectionAuto = encodeSemnexisNativeIR(recordProjectionProgram.ir);
assert.equal(String.fromCharCode(...recordProjectionV4.slice(0, 6)), 'SNIRV4');
assert.equal(recordProjectionV4.length, 802);
assert.deepEqual(Array.from(recordProjectionAuto), Array.from(recordProjectionV4));
assert.equal(decodeSemnexisNativeIRV4(recordProjectionV4).dump(), recordProjectionProgram.irText);
assert.equal(decodeSemnexisNativeIR(recordProjectionAuto).dump(), recordProjectionProgram.irText);
assert.throws(
  () => encodeSemnexisNativeIRV3(recordProjectionProgram.ir),
  /SNIRV3 cannot encode record field projection/,
  'frozen SNIRV3 must reject record field projection'
);
const recordProjectionRuntime = emitSemnexisArm32RuntimeElfV0(recordProjectionProgram.ir);
assert.equal(verifySemnexisArm32RuntimeElfV0(recordProjectionRuntime, recordProjectionProgram.ir), true);
assert.equal(recordProjectionRuntime.byteLength, 440);

const forgedProjection = recordProjectionProgram.ir.functions.find(fn => fn.name === 'token_end').instructions.find(inst => inst.op === 'record.get');
const savedFieldIndex = forgedProjection.fieldIndex;
forgedProjection.fieldIndex = 9;
assert.throws(
  () => recordProjectionProgram.ir.verify(),
  /record\.get field index out of range/,
  'IR verifier must reject forged record field indexes'
);
forgedProjection.fieldIndex = savedFieldIndex;
recordProjectionProgram.ir.verify();

assert.throws(
  () => compileSemnexisV0(
    'struct Token { kind: i32, start: i32, end: i32 }\n' +
    'fn bad() -> i32 { let token = Token { kind: 1, start: 0, end: 1 }; return token.nope; }\n' +
    'fn main() -> i32 { return 0; }\n'
  ),
  /has no field 'nope'/,
  'record field projection must reject unknown field names'
);
assert.throws(
  () => compileSemnexisV0(
    'fn bad() -> i32 { return 1.kind; }\n' +
    'fn main() -> i32 { return 0; }\n'
  ),
  /field access base must be a flat record/,
  'field projection must reject non-record bases'
);


const recordConditionalProgram = compileSemnexisV0(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn lex_first(source: Slice<u8>) -> Token {\n' +
  ' let c = slice_get(source, 0);\n' +
  ' return if c >= 48 { if c <= 57 { Token { kind: 2, start: 0, end: 1 } } else { Token { kind: 1, start: 0, end: 1 } } } else { Token { kind: 1, start: 0, end: 1 } };\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
assert.equal(recordConditionalProgram.ir.functions.find(fn => fn.name === 'lex_first').instructions.filter(inst => inst.op === 'phi.record').length, 2);
const recordConditionalV5 = encodeSemnexisNativeIRV5(recordConditionalProgram.ir);
const recordConditionalAuto = encodeSemnexisNativeIR(recordConditionalProgram.ir);
assert.equal(String.fromCharCode(...recordConditionalV5.slice(0, 6)), 'SNIRV5');
assert.equal(recordConditionalV5.length, 1125);
assert.deepEqual(Array.from(recordConditionalAuto), Array.from(recordConditionalV5));
assert.equal(decodeSemnexisNativeIRV5(recordConditionalV5).dump(), recordConditionalProgram.irText);
assert.equal(decodeSemnexisNativeIR(recordConditionalAuto).dump(), recordConditionalProgram.irText);
assert.throws(
  () => encodeSemnexisNativeIRV4(recordConditionalProgram.ir),
  /SNIRV4 cannot encode record phi values/,
  'frozen SNIRV4 must reject aggregate phi control flow'
);
const recordConditionalRuntime = emitSemnexisArm32RuntimeElfV0(recordConditionalProgram.ir);
assert.equal(verifySemnexisArm32RuntimeElfV0(recordConditionalRuntime, recordConditionalProgram.ir), true);
assert.equal(recordConditionalRuntime.byteLength, 644);

const recordConditionalFn = recordConditionalProgram.ir.functions.find(fn => fn.name === 'lex_first');
const forgedRecordPhi = recordConditionalFn.instructions.find(inst => inst.op === 'phi.record');
const savedIncomingValue = forgedRecordPhi.incoming[0].value;
const savedArgValue = forgedRecordPhi.args[0];
forgedRecordPhi.incoming[0].value = '%v9';
forgedRecordPhi.args[0] = '%v9';
assert.throws(
  () => recordConditionalProgram.ir.verify(),
  /phi\.record incoming value type mismatch/,
  'IR verifier must reject scalar values forged into record phi inputs'
);
forgedRecordPhi.incoming[0].value = savedIncomingValue;
forgedRecordPhi.args[0] = savedArgValue;
recordConditionalProgram.ir.verify();

const savedIncomingLabel = forgedRecordPhi.incoming[0].label;
forgedRecordPhi.incoming[0].label = 'entry';
assert.throws(
  () => recordConditionalProgram.ir.verify(),
  /phi predecessors do not match CFG predecessors/,
  'record phi predecessors must exactly match CFG predecessors'
);
forgedRecordPhi.incoming[0].label = savedIncomingLabel;
recordConditionalProgram.ir.verify();


const recordLoopStateProgram = compileSemnexisV0(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn lex_at(source: Slice<u8>, start: i32) -> Token {\n' +
  ' return if start < slice_len(source) { Token { kind: 1, start: start, end: start + 1 } } else { Token { kind: 0, start: start, end: start } };\n' +
  '}\n' +
  'fn count_tokens(source: Slice<u8>) -> i32 {\n' +
  ' let first = lex_at(source, 0);\n' +
  ' return loop(token = first, count = 0) while token.kind != 0 {\n' +
  '  next(lex_at(source, token.end), count + 1);\n' +
  ' } yield count;\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
const countTokensIr = recordLoopStateProgram.ir.functions.find(fn => fn.name === 'count_tokens');
assert.equal(countTokensIr.instructions.filter(inst => inst.op === 'phi.record').length, 1);
assert.equal(countTokensIr.instructions.filter(inst => inst.op === 'phi.i32').length, 1);
const recordLoopStateV5 = encodeSemnexisNativeIRV5(recordLoopStateProgram.ir);
assert.equal(String.fromCharCode(...recordLoopStateV5.slice(0, 6)), 'SNIRV5');
assert.equal(recordLoopStateV5.length, 1554);
assert.equal(decodeSemnexisNativeIRV5(recordLoopStateV5).dump(), recordLoopStateProgram.irText);
assert.equal(decodeSemnexisNativeIR(recordLoopStateV5).dump(), recordLoopStateProgram.irText);
const recordLoopRuntime = emitSemnexisArm32RuntimeElfV0(recordLoopStateProgram.ir);
assert.equal(verifySemnexisArm32RuntimeElfV0(recordLoopRuntime, recordLoopStateProgram.ir), true);
assert.equal(recordLoopRuntime.byteLength, 816);

assert.throws(
  () => compileSemnexisV0(
    'struct Token { kind: i32, start: i32, end: i32 }\n' +
    'fn bad() -> i32 {\n' +
    ' let first = Token { kind: 1, start: 0, end: 1 };\n' +
    ' return loop(token = first, count = 0) while count < 1 { next(count, count + 1); } yield count;\n' +
    '}\n' +
    'fn main() -> i32 { return 0; }\n'
  ),
  /next value for 'token' must remain Token, got i32/,
  'record loop state must preserve its exact type across next()'
);


const recordLoopYieldProgram = compileSemnexisV0(
  'struct ParserState { pos: i32, root: i32, slot: i32 }\n' +
  'fn advance(n: i32) -> ParserState {\n' +
  ' return loop(state = ParserState { pos: 0, root: 0, slot: 0 }) while state.pos < n {\n' +
  '  next(ParserState { pos: state.pos + 1, root: state.root, slot: state.slot + 1 });\n' +
  ' } yield state;\n' +
  '}\n' +
  'fn run() -> i32 { let state = advance(5); return state.pos + state.slot; }\n' +
  'fn main() -> i32 { return run(); }\n'
);
const recordLoopYieldV5 = encodeSemnexisNativeIRV5(recordLoopYieldProgram.ir);
assert.equal(String.fromCharCode(...recordLoopYieldV5.slice(0, 6)), 'SNIRV5');
assert.equal(recordLoopYieldV5.length, 1150);
assert.equal(decodeSemnexisNativeIRV5(recordLoopYieldV5).dump(), recordLoopYieldProgram.irText);
const recordLoopYieldRuntime = emitSemnexisArm32RuntimeElfV0(recordLoopYieldProgram.ir);
assert.equal(verifySemnexisArm32RuntimeElfV0(recordLoopYieldRuntime, recordLoopYieldProgram.ir), true);
assert.equal(recordLoopYieldRuntime.byteLength, 700);
assert.equal(
  recordLoopYieldProgram.ir.functions.find(fn => fn.name === 'advance').instructions.filter(inst => inst.op === 'phi.record').length,
  1,
  'record-valued loop result must use the loop-carried record phi'
);

const decimalValueProgram = compileSemnexisV0(
  'fn digit_value(c: u8) -> i32 { return c - 48; }\n' +
  'fn parse_decimal(source: Slice<u8>, start: i32, end: i32) -> i32 {\n' +
  ' return loop(i = start, value = 0) while i < end {\n' +
  '  next(i + 1, value * 10 + digit_value(slice_get(source, i)));\n' +
  ' } yield value;\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
assert.match(decimalValueProgram.irText, /zext\.u8\.i32/);
const decimalValueV6 = encodeSemnexisNativeIRV6(decimalValueProgram.ir);
const decimalValueAuto = encodeSemnexisNativeIR(decimalValueProgram.ir);
assert.equal(String.fromCharCode(...decimalValueV6.slice(0, 6)), 'SNIRV6');
assert.equal(decimalValueV6.length, 1085);
assert.deepEqual(Array.from(decimalValueAuto), Array.from(decimalValueV6));
assert.equal(decodeSemnexisNativeIRV6(decimalValueV6).dump(), decimalValueProgram.irText);
assert.equal(decodeSemnexisNativeIR(decimalValueAuto).dump(), decimalValueProgram.irText);
assert.throws(
  () => encodeSemnexisNativeIRV5(decimalValueProgram.ir),
  /SNIRV5 cannot encode u8-to-i32 widening/,
  'frozen SNIRV5 must reject u8-to-i32 widening'
);
const decimalValueRuntime = emitSemnexisArm32RuntimeElfV0(decimalValueProgram.ir);
assert.equal(verifySemnexisArm32RuntimeElfV0(decimalValueRuntime, decimalValueProgram.ir), true);
assert.equal(decimalValueRuntime.byteLength, 532);

const decimalValueFn = decimalValueProgram.ir.functions.find(fn => fn.name === 'digit_value');
const forgedZext = decimalValueFn.instructions.find(inst => inst.op === 'zext.u8.i32');
const savedZextType = forgedZext.type;
forgedZext.type = 'u8';
assert.throws(
  () => decimalValueProgram.ir.verify(),
  /malformed zext\.u8\.i32/,
  'IR verifier must reject malformed zext result types'
);
forgedZext.type = savedZextType;
decimalValueProgram.ir.verify();


const arenaStateProgram = compileSemnexisV0(
  'struct Expr { kind: i32, a: i32, b: i32, value: i32 }\n' +
  'fn build_add(source: Slice<u8>, arena: Arena) -> i32 {\n' +
  ' let left = Expr { kind: 0, a: 0, b: 0, value: 1 };\n' +
  ' let right = Expr { kind: 0, a: 0, b: 0, value: 2 };\n' +
  ' let root = Expr { kind: 1, a: 0, b: 1, value: 3 };\n' +
  ' let write0 = arena_store(arena, 0, left);\n' +
  ' let write1 = arena_store(arena, 1, right);\n' +
  ' let write2 = arena_store(arena, 2, root);\n' +
  ' return 2;\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
const arenaBuildFn = arenaStateProgram.ir.functions.find(fn => fn.name === 'build_add');
assert.equal(arenaBuildFn.effect, 'state');
assert.match(arenaStateProgram.irText, /%v[0-9]+:Arena = copy\.arena %arg1/);
assert.match(arenaStateProgram.irText, /arena\.store\.record .* record=Expr effect=state/);
const arenaStateV7 = encodeSemnexisNativeIRV7(arenaStateProgram.ir);
const arenaStateAuto = encodeSemnexisNativeIR(arenaStateProgram.ir);
assert.equal(String.fromCharCode(...arenaStateV7.slice(0, 6)), 'SNIRV7');
assert.equal(arenaStateV7.length, 1018);
assert.deepEqual(Array.from(arenaStateAuto), Array.from(arenaStateV7));
assert.equal(decodeSemnexisNativeIRV7(arenaStateV7).dump(), arenaStateProgram.irText);
assert.equal(decodeSemnexisNativeIR(arenaStateAuto).dump(), arenaStateProgram.irText);
assert.throws(
  () => encodeSemnexisNativeIRV6(arenaStateProgram.ir),
  /SNIRV6 cannot encode Arena\/state values/,
  'frozen SNIRV6 must reject Arena/state IR'
);
const arenaStateRuntime = emitSemnexisArm32RuntimeElfV0(arenaStateProgram.ir);
assert.equal(verifySemnexisArm32RuntimeElfV0(arenaStateRuntime, arenaStateProgram.ir), true);
assert.equal(arenaStateRuntime.byteLength, 808);
const arenaStateRuntimeFn = arenaStateRuntime.functions.find(fn => fn.name === 'build_add');
assert.equal(arenaStateRuntimeFn.bytes, 676);
assert.equal(arenaStateRuntimeFn.frameBytes, 112);
assert.equal(arenaStateRuntimeFn.slotCount, 27);
assert.equal(arenaStateRuntimeFn.spillSlots, 3);

const arenaStoreInst = arenaBuildFn.instructions.find(inst => inst.op === 'arena.store.record');
const savedArenaRecordType = arenaStoreInst.recordType;
arenaStoreInst.recordType = 'MissingRecord';
assert.throws(
  () => arenaStateProgram.ir.verify(),
  /malformed arena\.store\.record/,
  'IR verifier must reject forged Arena record type metadata'
);
arenaStoreInst.recordType = savedArenaRecordType;
const savedArenaEffect = arenaBuildFn.effect;
arenaBuildFn.effect = 'pure';
assert.throws(
  () => arenaStateProgram.ir.verify(),
  /effect metadata mismatch; expected state, got pure/,
  'IR verifier must re-derive borrowed-state effects'
);
arenaBuildFn.effect = savedArenaEffect;
arenaStateProgram.ir.verify();

const arenaReadProgram = compileSemnexisV0(
  'struct Expr { kind: i32, a: i32, b: i32, value: i32 }\n' +
  'fn read_value(arena: Arena, index: i32) -> i32 {\n' +
  ' let node = arena_load<Expr>(arena, index);\n' +
  ' return node.value;\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
const arenaReadFn = arenaReadProgram.ir.functions.find(fn => fn.name === 'read_value');
assert.equal(arenaReadFn.effect, 'state');
assert.match(arenaReadProgram.irText, /%v[0-9]+:Expr = arena\.load\.record .* record=Expr effect=state/);
const arenaReadV7 = encodeSemnexisNativeIRV7(arenaReadProgram.ir);
assert.equal(String.fromCharCode(...arenaReadV7.slice(0, 6)), 'SNIRV7');
assert.equal(decodeSemnexisNativeIRV7(arenaReadV7).dump(), arenaReadProgram.irText);
assert.equal(decodeSemnexisNativeIR(arenaReadV7).dump(), arenaReadProgram.irText);
assert.throws(
  () => encodeSemnexisNativeIRV6(arenaReadProgram.ir),
  /SNIRV6 cannot encode Arena\/state values/,
  'frozen SNIRV6 must reject Arena record loads'
);
const arenaReadRuntime = emitSemnexisArm32RuntimeElfV0(arenaReadProgram.ir);
assert.equal(verifySemnexisArm32RuntimeElfV0(arenaReadRuntime, arenaReadProgram.ir), true);

const arenaLoadInst = arenaReadFn.instructions.find(inst => inst.op === 'arena.load.record');
const savedArenaLoadType = arenaLoadInst.type;
arenaLoadInst.type = 'i32';
assert.throws(
  () => arenaReadProgram.ir.verify(),
  /malformed arena\.load\.record/,
  'IR verifier must reject non-record Arena loads'
);
arenaLoadInst.type = savedArenaLoadType;
const savedArenaLoadEffect = arenaLoadInst.effect;
arenaLoadInst.effect = 'pure';
assert.throws(
  () => arenaReadProgram.ir.verify(),
  /malformed arena\.load\.record/,
  'IR verifier must reject Arena loads without state effect'
);
arenaLoadInst.effect = savedArenaLoadEffect;
arenaReadProgram.ir.verify();
assert.throws(
  () => compileSemnexisV0('fn bad(arena: Arena) -> i32 { return arena_load<i32>(arena, 0); }\nfn main() -> i32 { return 0; }\n'),
  /type argument must be a flat record/,
  'arena_load must reject scalar type arguments'
);

const arenaTimeStateProgram = compileSemnexisV0(
  'struct Cell { value: i32 }\n' +
  'fn main(arena: Arena) -> i32 with time {\n' +
  ' let cell = Cell { value: 1 };\n' +
  ' let written = arena_store(arena, 0, cell);\n' +
  ' return clock();\n' +
  '}\n'
);
assert.equal(arenaTimeStateProgram.ir.functions.find(fn => fn.name === 'main').effect, 'time_state');
const arenaTimeStateV7 = encodeSemnexisNativeIRV7(arenaTimeStateProgram.ir);
assert.equal(decodeSemnexisNativeIRV7(arenaTimeStateV7).dump(), arenaTimeStateProgram.irText);
assert.throws(
  () => emitSemnexisArm32RuntimeElfV0(arenaTimeStateProgram.ir),
  /entry function 'main' must have zero parameters|unsupported effect\/capability function/,
  'native runtime must keep time_state fail-closed at the process-entry or effect boundary'
);

console.log('ok - Semnexis QuickJS bootstrap compiler');
