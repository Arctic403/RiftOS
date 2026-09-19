import assert from 'node:assert/strict';
import { compileSemnexisV0, inspectSemnexisV0, encodeSemnexisNativeIRV0, decodeSemnexisNativeIRV0, emitSemnexisArm32ElfProofV0, verifySemnexisArm32ElfProofV0, emitSemnexisArm32RuntimeElfV0, verifySemnexisArm32RuntimeElfV0 } from '../src/semnexis-bootstrap.js';

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
assert.match(effect.graphText, /Function sample_time .*effect_proof=transitive_call_graph_v0 capability_proof=transitive_requirement_v0/);
assert.match(effect.graphText, /Function main .*effect_proof=transitive_call_graph_v0 capability_proof=satisfied_by_grant_v0/);

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
assert.equal(verifySemnexisArm32RuntimeElfV0(runtimeArm32), true);
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
  /effectful\/capability function/,
  'runtime backend must reject capabilities until runtime capability lowering exists'
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

console.log('ok - Semnexis QuickJS bootstrap compiler');
