import '../src/semnexis-bootstrap.js';

const compiler = globalThis.SemnexisBootstrap;
if (!compiler) throw new Error('Semnexis compiler global missing');

function check(value, message) {
  if (!value) throw new Error(message);
}
function equal(actual, expected, message) {
  if (actual !== expected) throw new Error((message || 'equality failed') + ': expected ' + expected + ', got ' + actual);
}
function u32(value) { return value >>> 0; }
function s32(value) { return value | 0; }
function ror32(value, amount) {
  const n = amount & 31;
  if (!n) return u32(value);
  return u32((value >>> n) | (value << (32 - n)));
}
function readImageWord(bytes, base, address) {
  const offset = address - base;
  if (offset < 0 || offset + 4 > bytes.length || (offset & 3)) {
    throw new Error('ARM executor fetch escaped image at 0x' + u32(address).toString(16));
  }
  return u32(bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16) | (bytes[offset + 3] << 24));
}
function conditionPasses(cond, flags) {
  switch (cond) {
    case 0x0: return flags.Z;
    case 0x1: return !flags.Z;
    case 0x2: return flags.C;
    case 0x6: return flags.V;
    case 0xA: return flags.N === flags.V;
    case 0xB: return flags.N !== flags.V;
    case 0xC: return !flags.Z && flags.N === flags.V;
    case 0xD: return flags.Z || flags.N !== flags.V;
    case 0xE: return true;
    default: throw new Error('ARM executor unsupported condition code ' + cond);
  }
}
function addFlags(a, b, carryIn) {
  const carry = carryIn ? 1 : 0;
  const sum = BigInt(u32(a)) + BigInt(u32(b)) + BigInt(carry);
  const result = Number(BigInt.asUintN(32, sum));
  const signedA = BigInt(s32(a));
  const signedB = BigInt(s32(b));
  const signedSum = signedA + signedB + BigInt(carry);
  return {
    result:u32(result),
    C:sum > 0xFFFFFFFFn,
    V:signedSum < -2147483648n || signedSum > 2147483647n
  };
}
function subFlags(a, b) {
  const ua = BigInt(u32(a)), ub = BigInt(u32(b));
  const result = u32(Number(BigInt.asUintN(32, ua - ub)));
  const signed = BigInt(s32(a)) - BigInt(s32(b));
  return {
    result:result,
    C:ua >= ub,
    V:signed < -2147483648n || signed > 2147483647n
  };
}
function decodeOperand2(word, regs, flags) {
  const immediate = ((word >>> 25) & 1) !== 0;
  if (immediate) {
    const imm8 = word & 0xFF;
    const rotate = ((word >>> 8) & 0xF) * 2;
    const value = ror32(imm8, rotate);
    const carry = rotate === 0 ? flags.C : ((value >>> 31) !== 0);
    return {value:value, carry:carry};
  }
  if ((word & 0x10) !== 0) throw new Error('ARM executor register-specified shifts are not allowed');
  const rm = word & 0xF;
  const shiftType = (word >>> 5) & 3;
  const shift = (word >>> 7) & 0x1F;
  const input = regs[rm];
  if (shiftType === 0) {
    if (shift === 0) return {value:input, carry:flags.C};
    return {value:u32(input << shift), carry:((input >>> (32 - shift)) & 1) !== 0};
  }
  if (shiftType === 2) {
    const amount = shift === 0 ? 32 : shift;
    const signed = s32(input);
    const value = amount >= 32 ? (signed < 0 ? 0xFFFFFFFF : 0) : u32(signed >> amount);
    const carry = amount >= 32 ? signed < 0 : ((input >>> (amount - 1)) & 1) !== 0;
    return {value:u32(value), carry:carry};
  }
  throw new Error('ARM executor unsupported shift type ' + shiftType);
}
function executeArm32(artifact, options = {}) {
  if (typeof options === 'number') options = {stepLimit:options};
  const stepLimit = options.stepLimit ?? 200000;
  const base = 0x00010000;
  const regs = new Uint32Array(16);
  const flags = {N:false,Z:false,C:false,V:false};
  const memory = new Map(options.words || []);
  const byteMemory = new Map(options.bytes || []);
  regs[13] = 0x80000000;
  regs[15] = u32(options.entry ?? artifact.entry);
  regs[14] = u32(options.linkRegister ?? 0);
  for (let i = 0; i < (options.args || []).length && i < 4; i += 1) regs[i] = u32(options.args[i]);

  const load = address => memory.get(u32(address)) ?? 0;
  const store = (address, value) => memory.set(u32(address), u32(value));
  const loadByte = address => {
    const key = u32(address);
    if (!byteMemory.has(key)) throw new Error('ARM executor byte read escaped mapped source at 0x' + key.toString(16));
    return byteMemory.get(key);
  };
  const returnAddress = options.returnAddress == null ? null : u32(options.returnAddress);
  const setNZ = value => {
    flags.N = (u32(value) & 0x80000000) !== 0;
    flags.Z = u32(value) === 0;
  };

  for (let step = 0; step < stepLimit; step += 1) {
    const pc = regs[15] >>> 0;
    if (returnAddress != null && pc === returnAddress) {
      return {result:s32(regs[0]), resultRegisters:[s32(regs[0]),s32(regs[1]),s32(regs[2]),s32(regs[3])], exitStatus:regs[0] & 0xFF, steps:step, memory:memory};
    }
    const word = readImageWord(artifact.bytes, base, pc);
    const cond = word >>> 28;
    if (!conditionPasses(cond, flags)) {
      regs[15] = u32(pc + 4);
      continue;
    }

    if (word === 0xEF000000) {
      if (regs[7] !== 1) throw new Error('ARM executor unsupported syscall ' + regs[7]);
      return {result:s32(regs[0]), resultRegisters:[s32(regs[0]),s32(regs[1]),s32(regs[2]),s32(regs[3])], exitStatus:regs[0] & 0xFF, steps:step + 1, memory:memory};
    }

    if (word === 0xE92D48F0) {
      const list = [4,5,6,7,11,14];
      const nextSp = u32(regs[13] - list.length * 4);
      for (let i = 0; i < list.length; i += 1) store(nextSp + i * 4, regs[list[i]]);
      regs[13] = nextSp;
      regs[15] = u32(pc + 4);
      continue;
    }
    if (word === 0xE8BD88F0) {
      const list = [4,5,6,7,11,15];
      const sp = regs[13];
      for (let i = 0; i < list.length; i += 1) regs[list[i]] = load(sp + i * 4);
      regs[13] = u32(sp + list.length * 4);
      continue;
    }

    if ((word & 0x0FFFFFF0) === 0x012FFF10) {
      regs[15] = regs[word & 0xF];
      continue;
    }

    if ((word & 0x0E000000) === 0x0A000000) {
      let imm24 = word & 0x00FFFFFF;
      if (imm24 & 0x00800000) imm24 |= 0xFF000000;
      if ((word & 0x01000000) !== 0) regs[14] = u32(pc + 4);
      regs[15] = u32(pc + 8 + (imm24 | 0) * 4);
      continue;
    }

    if ((word & 0x0FF00000) === 0x03000000) {
      const rd = (word >>> 12) & 0xF;
      const imm16 = (((word >>> 4) & 0xF000) | (word & 0x0FFF)) >>> 0;
      regs[rd] = imm16;
      regs[15] = u32(pc + 4);
      continue;
    }
    if ((word & 0x0FF00000) === 0x03400000) {
      const rd = (word >>> 12) & 0xF;
      const imm16 = (((word >>> 4) & 0xF000) | (word & 0x0FFF)) >>> 0;
      regs[rd] = u32((regs[rd] & 0xFFFF) | (imm16 << 16));
      regs[15] = u32(pc + 4);
      continue;
    }

    if ((word & 0x0FFFFFFF) === 0x00C32190) {
      const product = BigInt(s32(regs[0])) * BigInt(s32(regs[1]));
      const wide = BigInt.asUintN(64, product);
      regs[2] = Number(wide & 0xFFFFFFFFn) >>> 0;
      regs[3] = Number((wide >> 32n) & 0xFFFFFFFFn) >>> 0;
      regs[15] = u32(pc + 4);
      continue;
    }

    if ((word & 0x0C000000) === 0x04000000) {
      const immediateOffset = ((word >>> 25) & 1) === 0;
      const pre = ((word >>> 24) & 1) !== 0;
      const up = ((word >>> 23) & 1) !== 0;
      const byte = ((word >>> 22) & 1) !== 0;
      const writeBack = ((word >>> 21) & 1) !== 0;
      const loadBit = ((word >>> 20) & 1) !== 0;
      if (!pre || writeBack) throw new Error('ARM executor unsupported load/store addressing mode');
      const rn = (word >>> 16) & 0xF;
      const rd = (word >>> 12) & 0xF;
      let offset;
      if (immediateOffset) {
        offset = word & 0xFFF;
      } else {
        if ((word & 0x00000FF0) !== 0) throw new Error('ARM executor shifted register load/store offset is not supported');
        offset = regs[word & 0xF];
      }
      const address = u32(regs[rn] + (up ? offset : -offset));
      if (byte) {
        if (!loadBit) throw new Error('ARM executor byte stores are not supported');
        regs[rd] = loadByte(address);
      } else {
        if (!immediateOffset) throw new Error('ARM executor register-offset word load/store is not supported');
        if (loadBit) regs[rd] = load(address);
        else store(address, regs[rd]);
      }
      regs[15] = u32(pc + 4);
      continue;
    }

    if ((word & 0x0C000000) === 0x00000000) {
      const opcode = (word >>> 21) & 0xF;
      const setFlags = ((word >>> 20) & 1) !== 0;
      const rn = (word >>> 16) & 0xF;
      const rd = (word >>> 12) & 0xF;
      const op2 = decodeOperand2(word, regs, flags);
      let result;
      if (opcode === 0x1) {
        result = u32(regs[rn] ^ op2.value);
      } else if (opcode === 0x2) {
        const row = subFlags(regs[rn], op2.value);
        result = row.result;
        if (setFlags) { flags.C = row.C; flags.V = row.V; }
      } else if (opcode === 0x3) {
        const row = subFlags(op2.value, regs[rn]);
        result = row.result;
        if (setFlags) { flags.C = row.C; flags.V = row.V; }
      } else if (opcode === 0x4) {
        const row = addFlags(regs[rn], op2.value, false);
        result = row.result;
        if (setFlags) { flags.C = row.C; flags.V = row.V; }
      } else if (opcode === 0x5) {
        result = addFlags(regs[rn], op2.value, flags.C).result;
      } else if (opcode === 0x8) {
        result = u32(regs[rn] & op2.value);
        setNZ(result);
        flags.C = op2.carry;
        regs[15] = u32(pc + 4);
        continue;
      } else if (opcode === 0xA) {
        const row = subFlags(regs[rn], op2.value);
        setNZ(row.result);
        flags.C = row.C;
        flags.V = row.V;
        regs[15] = u32(pc + 4);
        continue;
      } else if (opcode === 0xD) {
        result = op2.value;
        if (setFlags) flags.C = op2.carry;
      } else {
        throw new Error('ARM executor unsupported data-processing opcode ' + opcode + ' word=0x' + word.toString(16));
      }

      regs[rd] = u32(result);
      if (setFlags) setNZ(result);
      regs[15] = u32(pc + 4);
      continue;
    }

    throw new Error('ARM executor unknown instruction 0x' + word.toString(16) + ' at 0x' + pc.toString(16));
  }
  throw new Error('ARM executor exceeded step limit');
}

function compileAndRun(source) {
  const program = compiler.compile(source);
  const artifact = compiler.emitArm32Runtime(program.ir);
  check(compiler.verifyArm32Runtime(artifact, program.ir), 'canonical ARM verification failed');
  return {program, artifact, run:executeArm32(artifact)};
}

function executeFunction(artifact, name, args, words = [], bytes = []) {
  const fn = artifact.functions.find(candidate => candidate.name === name);
  if (!fn) throw new Error("ARM executor function not found: '" + name + "'");
  const returnAddress = 0xFFF00000;
  return executeArm32(artifact, {
    entry:fn.address,
    args:args,
    words:words,
    bytes:bytes,
    linkRegister:returnAddress,
    returnAddress:returnAddress
  });
}

const add = compileAndRun('fn main() -> i32 { return 40 + 2; }\n');
equal(add.run.result, 42, 'runtime checked add');

const arithmetic = compileAndRun(
  'fn arithmetic(a: i32, b: i32) -> i32 {\n' +
  ' let sum = a + b;\n let difference = a - b;\n let product = sum * difference;\n' +
  ' return product / b;\n}\n' +
  'fn main() -> i32 { return arithmetic(84, 2); }\n'
);
equal(arithmetic.run.result, 3526, 'runtime add/sub/mul/div');

const negativeDivision = compileAndRun(
  'fn main() -> i32 { return (0 - 7) / 3; }\n'
);
equal(negativeDivision.run.result, -2, 'signed division truncates toward zero');

const comparisonCases = [
  ['1 == 1', 11], ['1 != 2', 11], ['1 < 2', 11],
  ['1 <= 1', 11], ['2 > 1', 11], ['2 >= 2', 11],
  ['2 < 1', 22]
];
for (const [condition, expected] of comparisonCases) {
  const row = compileAndRun('fn main() -> i32 { return if ' + condition + ' { 11 } else { 22 }; }\n');
  equal(row.run.result, expected, 'comparison ' + condition);
}

const loop = compileAndRun(
  'fn sum(n: i32) -> i32 { return loop(i = 0, acc = 0) while i < n { next(i + 1, acc + i); } yield acc; }\n' +
  'fn main() -> i32 { return sum(5); }\n'
);
equal(loop.run.result, 10, 'loop backedge execution');

for (const [name, source] of [
  ['add overflow', 'fn main() -> i32 { return 2147483647 + 1; }\n'],
  ['multiply overflow', 'fn main() -> i32 { return 50000 * 50000; }\n'],
  ['divide by zero', 'fn main() -> i32 { return 1 / 0; }\n'],
  ['signed divide overflow', 'fn main() -> i32 { return ((0 - 2147483647) - 1) / (0 - 1); }\n']
]) {
  const row = compileAndRun(source);
  equal(row.run.result, 125, name + ' trap');
}

const swapProgram = compiler.compile(
  'fn swap_once() -> i32 {\n' +
  ' return loop(a = 1, b = 2, i = 0) while i < 1 { next(b, a, i + 1); } yield a;\n' +
  '}\nfn main() -> i32 { return swap_once(); }\n'
);
const swapFn = swapProgram.ir.functions.find(fn => fn.name === 'swap_once');
const swapPhis = swapFn.instructions.filter(inst => inst.op === 'phi.i32');
const aPhi = swapPhis[0], bPhi = swapPhis[1];
aPhi.incoming[1].value = bPhi.result; aPhi.args[1] = bPhi.result;
bPhi.incoming[1].value = aPhi.result; bPhi.args[1] = aPhi.result;
swapProgram.ir.verify();
const swapArtifact = compiler.emitArm32Runtime(swapProgram.ir);
check(compiler.verifyArm32Runtime(swapArtifact, swapProgram.ir), 'cyclic phi artifact failed canonical verify');
const swapRun = executeArm32(swapArtifact);
equal(swapRun.result, 2, 'parallel phi swap must preserve predecessor values');


const sliceProgram = compiler.compile(
  'fn byte_at(source: Slice<u8>, index: i32) -> u8 { return slice_get(source, index); }\n' +
  'fn source_len(source: Slice<u8>) -> i32 { return slice_len(source); }\n' +
  'fn main() -> i32 { return 0; }\n'
);
const sliceBinary = compiler.encodeIR(sliceProgram.ir);
equal(String.fromCharCode(...sliceBinary.slice(0, 6)), 'SNIRV2', 'slice program must use SNIRV2');
const sliceArtifact = compiler.emitArm32Runtime(sliceProgram.ir);
check(compiler.verifyArm32Runtime(sliceArtifact, sliceProgram.ir), 'slice artifact failed canonical verify');

const descriptorAddress = 0x20000000;
const dataAddress = 0x20000100;
const validWords = [
  [descriptorAddress, dataAddress],
  [descriptorAddress + 4, 3]
];
const validBytes = [
  [dataAddress, 65],
  [dataAddress + 1, 66],
  [dataAddress + 2, 67]
];

equal(
  executeFunction(sliceArtifact, 'byte_at', [descriptorAddress, 1], validWords, validBytes).result,
  66,
  'slice_get must read the requested byte'
);
equal(
  executeFunction(sliceArtifact, 'source_len', [descriptorAddress], validWords, validBytes).result,
  3,
  'slice_len must return descriptor length'
);

for (const [name, args, words, bytes] of [
  ['null descriptor', [0, 0], validWords, validBytes],
  ['negative index', [descriptorAddress, -1], validWords, validBytes],
  ['index equals length', [descriptorAddress, 3], validWords, validBytes],
  ['negative descriptor length', [descriptorAddress, 0], [[descriptorAddress, dataAddress], [descriptorAddress + 4, 0xFFFFFFFF]], validBytes],
  ['null data pointer', [descriptorAddress, 0], [[descriptorAddress, 0], [descriptorAddress + 4, 1]], []]
]) {
  equal(executeFunction(sliceArtifact, 'byte_at', args, words, bytes).result, 125, 'slice_get ' + name + ' trap');
}
equal(
  executeFunction(sliceArtifact, 'source_len', [0], validWords, validBytes).result,
  125,
  'slice_len null descriptor trap'
);
equal(
  executeFunction(sliceArtifact, 'source_len', [descriptorAddress], [[descriptorAddress, dataAddress], [descriptorAddress + 4, 0xFFFFFFFF]], validBytes).result,
  125,
  'slice_len negative length trap'
);

const byteAtFn = sliceArtifact.functions.find(fn => fn.name === 'byte_at');
let hasRegisterLdrb = false;
for (let offset = byteAtFn.fileOffset; offset < byteAtFn.fileOffset + byteAtFn.bytes; offset += 4) {
  const word = readImageWord(sliceArtifact.bytes, 0x00010000, byteAtFn.address + (offset - byteAtFn.fileOffset));
  if (word === 0xE7D22001) hasRegisterLdrb = true;
}
check(hasRegisterLdrb, 'slice_get must emit LDRB r2, [r2, r1]');


const scannerProgram = compiler.compile(
  'fn is_digit(c: u8) -> i32 {\n' +
  ' return if c >= 48 { if c <= 57 { 1 } else { 0 } } else { 0 };\n' +
  '}\n' +
  'fn count_digits(source: Slice<u8>) -> i32 {\n' +
  ' return loop(i = 0, count = 0) while i < slice_len(source) {\n' +
  '  next(i + 1, count + is_digit(slice_get(source, i)));\n' +
  ' } yield count;\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
const scannerBinary = compiler.encodeIR(scannerProgram.ir);
equal(String.fromCharCode(...scannerBinary.slice(0, 6)), 'SNIRV2', 'scanner kernel must use SNIRV2');
const scannerArtifact = compiler.emitArm32Runtime(scannerProgram.ir);
check(compiler.verifyArm32Runtime(scannerArtifact, scannerProgram.ir), 'scanner artifact failed canonical verify');
equal(scannerArtifact.byteLength, 688, 'scanner kernel ELF size');

const scannerDescriptor = 0x20001000;
const scannerData = 0x20001100;
const scannerBytesRaw = [97, 49, 98, 50, 51, 33];
const scannerWords = [
  [scannerDescriptor, scannerData],
  [scannerDescriptor + 4, scannerBytesRaw.length]
];
const scannerBytes = scannerBytesRaw.map((value, index) => [scannerData + index, value]);
equal(
  executeFunction(scannerArtifact, 'count_digits', [scannerDescriptor], scannerWords, scannerBytes).result,
  3,
  'native Slice<u8> scan must classify all source bytes'
);
equal(
  executeFunction(
    scannerArtifact,
    'count_digits',
    [scannerDescriptor],
    [[scannerDescriptor, 0], [scannerDescriptor + 4, 0]],
    []
  ).result,
  0,
  'empty Slice<u8> may use a null data pointer when no byte is read'
);


const tokenProgram = compiler.compile(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn first_token(source: Slice<u8>) -> Token {\n' +
  ' return Token { kind: 1, start: 0, end: slice_len(source) };\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
const tokenBinary = compiler.encodeIR(tokenProgram.ir);
equal(String.fromCharCode(...tokenBinary.slice(0, 6)), 'SNIRV3', 'Token record program must use SNIRV3');
const tokenArtifact = compiler.emitArm32Runtime(tokenProgram.ir);
check(compiler.verifyArm32Runtime(tokenArtifact, tokenProgram.ir), 'Token record artifact failed canonical verify');
equal(tokenArtifact.byteLength, 248, 'Token record ELF size');
const tokenDescriptor = 0x20002000;
const tokenData = 0x20002100;
const tokenRun = executeFunction(
  tokenArtifact,
  'first_token',
  [tokenDescriptor],
  [[tokenDescriptor, tokenData], [tokenDescriptor + 4, 5]],
  []
);
equal(tokenRun.resultRegisters[0], 1, 'Token.kind record return register');
equal(tokenRun.resultRegisters[1], 0, 'Token.start record return register');
equal(tokenRun.resultRegisters[2], 5, 'Token.end record return register');


const tokenUseProgram = compiler.compile(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn first_token(source: Slice<u8>) -> Token { return Token { kind: 1, start: 0, end: slice_len(source) }; }\n' +
  'fn token_kind(source: Slice<u8>) -> i32 { let token = first_token(source); return token.kind; }\n' +
  'fn token_end(source: Slice<u8>) -> i32 { let token = first_token(source); return token.end; }\n' +
  'fn main() -> i32 { return 0; }\n'
);
const tokenUseBinary = compiler.encodeIR(tokenUseProgram.ir);
equal(String.fromCharCode(...tokenUseBinary.slice(0, 6)), 'SNIRV4', 'record field projection must use SNIRV4');
equal(tokenUseBinary.length, 802, 'record field projection SNIRV4 size');
const tokenUseArtifact = compiler.emitArm32Runtime(tokenUseProgram.ir);
check(compiler.verifyArm32Runtime(tokenUseArtifact, tokenUseProgram.ir), 'record-call projection artifact failed canonical verify');
equal(tokenUseArtifact.byteLength, 440, 'record-call projection ELF size');
equal(
  executeFunction(tokenUseArtifact, 'token_kind', [tokenDescriptor], [[tokenDescriptor, tokenData], [tokenDescriptor + 4, 5]], []).result,
  1,
  'record-returning call must preserve Token.kind through r0-r3 ABI'
);
equal(
  executeFunction(tokenUseArtifact, 'token_end', [tokenDescriptor], [[tokenDescriptor, tokenData], [tokenDescriptor + 4, 5]], []).result,
  5,
  'record.get must project nonzero field offsets after record-returning calls'
);

const tokenBranchProgram = compiler.compile(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn lex_first(source: Slice<u8>) -> Token {\n' +
  ' let c = slice_get(source, 0);\n' +
  ' return if c >= 48 { if c <= 57 { Token { kind: 2, start: 0, end: 1 } } else { Token { kind: 1, start: 0, end: 1 } } } else { Token { kind: 1, start: 0, end: 1 } };\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
const tokenBranchBinary = compiler.encodeIR(tokenBranchProgram.ir);
equal(String.fromCharCode(...tokenBranchBinary.slice(0, 6)), 'SNIRV5', 'record-valued conditional must use SNIRV5');
equal(tokenBranchBinary.length, 1125, 'record-valued conditional SNIRV5 size');
const tokenBranchArtifact = compiler.emitArm32Runtime(tokenBranchProgram.ir);
check(compiler.verifyArm32Runtime(tokenBranchArtifact, tokenBranchProgram.ir), 'record-valued conditional artifact failed canonical verify');
equal(tokenBranchArtifact.byteLength, 644, 'record-valued conditional ELF size');

const branchDescriptor = 0x20002000;
const branchData = 0x20002100;
for (const [byte, expectedKind] of [[53, 2], [65, 1], [47, 1]]) {
  const run = executeFunction(
    tokenBranchArtifact,
    'lex_first',
    [branchDescriptor],
    [[branchDescriptor, branchData], [branchDescriptor + 4, 1]],
    [[branchData, byte]]
  );
  equal(run.resultRegisters[0], expectedKind, 'record phi Token.kind for byte ' + byte);
  equal(run.resultRegisters[1], 0, 'record phi Token.start for byte ' + byte);
  equal(run.resultRegisters[2], 1, 'record phi Token.end for byte ' + byte);
}


const lexAtProgram = compiler.compile(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn is_digit(c: u8) -> i32 { return if c >= 48 { if c <= 57 { 1 } else { 0 } } else { 0 }; }\n' +
  'fn scan_number_end(source: Slice<u8>, start: i32) -> i32 {\n' +
  ' return loop(i = start, done = 0) while done == 0 {\n' +
  '  next(\n' +
  '   if i < slice_len(source) { if is_digit(slice_get(source, i)) == 1 { i + 1 } else { i } } else { i },\n' +
  '   if i < slice_len(source) { if is_digit(slice_get(source, i)) == 1 { 0 } else { 1 } } else { 1 }\n' +
  '  );\n' +
  ' } yield i;\n' +
  '}\n' +
  'fn lex_at(source: Slice<u8>, start: i32) -> Token {\n' +
  ' return if start < slice_len(source) {\n' +
  '  if is_digit(slice_get(source, start)) == 1 {\n' +
  '   Token { kind: 2, start: start, end: scan_number_end(source, start) }\n' +
  '  } else { Token { kind: 1, start: start, end: start + 1 } }\n' +
  ' } else { Token { kind: 0, start: start, end: start } };\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
const lexAtBinary = compiler.encodeIR(lexAtProgram.ir);
equal(String.fromCharCode(...lexAtBinary.slice(0, 6)), 'SNIRV5', 'real lex_at kernel must use SNIRV5');
equal(lexAtBinary.length, 3859, 'real lex_at kernel SNIRV5 size');
const lexAtArtifact = compiler.emitArm32Runtime(lexAtProgram.ir);
check(compiler.verifyArm32Runtime(lexAtArtifact, lexAtProgram.ir), 'real lex_at artifact failed canonical verify');
equal(lexAtArtifact.byteLength, 1664, 'real lex_at kernel ELF size');

const lexAtDescriptor = 0x20003000;
const lexAtData = 0x20003100;
const lexAtRaw = [97, 49, 50, 51, 98];
const lexAtWords = [
  [lexAtDescriptor, lexAtData],
  [lexAtDescriptor + 4, lexAtRaw.length]
];
const lexAtBytes = lexAtRaw.map((value, index) => [lexAtData + index, value]);

const numberToken = executeFunction(lexAtArtifact, 'lex_at', [lexAtDescriptor, 1], lexAtWords, lexAtBytes);
equal(numberToken.resultRegisters[0], 2, 'lex_at decimal token kind');
equal(numberToken.resultRegisters[1], 1, 'lex_at decimal token start');
equal(numberToken.resultRegisters[2], 4, 'lex_at decimal token end');

const fallbackToken = executeFunction(lexAtArtifact, 'lex_at', [lexAtDescriptor, 0], lexAtWords, lexAtBytes);
equal(fallbackToken.resultRegisters[0], 1, 'lex_at fallback token kind');
equal(fallbackToken.resultRegisters[1], 0, 'lex_at fallback token start');
equal(fallbackToken.resultRegisters[2], 1, 'lex_at fallback token end');

const eofToken = executeFunction(lexAtArtifact, 'lex_at', [lexAtDescriptor, lexAtRaw.length], lexAtWords, lexAtBytes);
equal(eofToken.resultRegisters[0], 0, 'lex_at EOF token kind');
equal(eofToken.resultRegisters[1], lexAtRaw.length, 'lex_at EOF token start');
equal(eofToken.resultRegisters[2], lexAtRaw.length, 'lex_at EOF token end');


const tokenStreamProgram = compiler.compile(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn lex_at(source: Slice<u8>, start: i32) -> Token {\n' +
  ' return if start < slice_len(source) {\n' +
  '  Token { kind: 1, start: start, end: start + 1 }\n' +
  ' } else { Token { kind: 0, start: start, end: start } };\n' +
  '}\n' +
  'fn count_tokens(source: Slice<u8>) -> i32 {\n' +
  ' let first = lex_at(source, 0);\n' +
  ' return loop(token = first, count = 0) while token.kind != 0 {\n' +
  '  next(lex_at(source, token.end), count + 1);\n' +
  ' } yield count;\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
const tokenStreamBinary = compiler.encodeIR(tokenStreamProgram.ir);
equal(String.fromCharCode(...tokenStreamBinary.slice(0, 6)), 'SNIRV5', 'record loop state must remain SNIRV5');
equal(tokenStreamBinary.length, 1554, 'record loop state SNIRV5 size');
const tokenStreamArtifact = compiler.emitArm32Runtime(tokenStreamProgram.ir);
check(compiler.verifyArm32Runtime(tokenStreamArtifact, tokenStreamProgram.ir), 'record loop-state artifact failed canonical verify');
equal(tokenStreamArtifact.byteLength, 816, 'record loop-state ELF size');

const tokenStreamDescriptor = 0x20004000;
const tokenStreamData = 0x20004100;
const tokenStreamRaw = [97, 98, 99, 100];
const tokenStreamWords = [
  [tokenStreamDescriptor, tokenStreamData],
  [tokenStreamDescriptor + 4, tokenStreamRaw.length]
];
const tokenStreamBytes = tokenStreamRaw.map((value, index) => [tokenStreamData + index, value]);
equal(
  executeFunction(tokenStreamArtifact, 'count_tokens', [tokenStreamDescriptor], tokenStreamWords, tokenStreamBytes).result,
  tokenStreamRaw.length,
  'record loop state must stream one token per byte until EOF'
);
equal(
  executeFunction(
    tokenStreamArtifact,
    'count_tokens',
    [tokenStreamDescriptor],
    [[tokenStreamDescriptor, 0], [tokenStreamDescriptor + 4, 0]],
    []
  ).result,
  0,
  'record loop state must terminate immediately on empty input'
);



const numberSpanProgram = compiler.compile(
  'fn is_digit(c: u8) -> i32 { return if c >= 48 { if c <= 57 { 1 } else { 0 } } else { 0 }; }\n' +
  'fn scan_digits(source: Slice<u8>, start: i32) -> i32 {\n' +
  ' return loop(i = start, active = 1) while active != 0 {\n' +
  '  next(\n' +
  '   if i < slice_len(source) { if is_digit(slice_get(source, i)) == 1 { i + 1 } else { i } } else { i },\n' +
  '   if i < slice_len(source) { if is_digit(slice_get(source, i)) == 1 { 1 } else { 0 } } else { 0 }\n' +
  '  );\n' +
  ' } yield i;\n' +
  '}\n' +
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn lex_at(source: Slice<u8>, start: i32) -> Token {\n' +
  ' return if start < slice_len(source) {\n' +
  '  if is_digit(slice_get(source, start)) == 1 { Token { kind: 2, start: start, end: scan_digits(source, start) } }\n' +
  '  else { Token { kind: 1, start: start, end: start + 1 } }\n' +
  ' } else { Token { kind: 0, start: start, end: start } };\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
const numberSpanBinary = compiler.encodeIR(numberSpanProgram.ir);
equal(String.fromCharCode(...numberSpanBinary.slice(0, 6)), 'SNIRV5', 'multi-byte token span must use SNIRV5');
equal(numberSpanBinary.length, 3847, 'multi-byte token span SNIRV5 size');
const numberSpanArtifact = compiler.emitArm32Runtime(numberSpanProgram.ir);
check(compiler.verifyArm32Runtime(numberSpanArtifact, numberSpanProgram.ir), 'multi-byte token span artifact failed canonical verify');
equal(numberSpanArtifact.byteLength, 1664, 'multi-byte token span ELF size');

const numberSpanDescriptor = 0x20006000;
const numberSpanData = 0x20006100;
const numberSpanRaw = [49, 50, 51, 43, 52];
const numberSpanWords = [
  [numberSpanDescriptor, numberSpanData],
  [numberSpanDescriptor + 4, numberSpanRaw.length]
];
const numberSpanBytes = numberSpanRaw.map((value, index) => [numberSpanData + index, value]);

const number0 = executeFunction(numberSpanArtifact, 'lex_at', [numberSpanDescriptor, 0], numberSpanWords, numberSpanBytes);
equal(number0.resultRegisters[0], 2, 'first token must be number');
equal(number0.resultRegisters[1], 0, 'first number start');
equal(number0.resultRegisters[2], 3, 'first number must span three digits');

const plusSpan = executeFunction(numberSpanArtifact, 'lex_at', [numberSpanDescriptor, 3], numberSpanWords, numberSpanBytes);
equal(plusSpan.resultRegisters[0], 1, 'plus must use fallback token kind');
equal(plusSpan.resultRegisters[1], 3, 'plus start');
equal(plusSpan.resultRegisters[2], 4, 'plus end');

const number1 = executeFunction(numberSpanArtifact, 'lex_at', [numberSpanDescriptor, 4], numberSpanWords, numberSpanBytes);
equal(number1.resultRegisters[0], 2, 'last token must be number');
equal(number1.resultRegisters[1], 4, 'last number start');
equal(number1.resultRegisters[2], 5, 'last number end');

const numberEof = executeFunction(numberSpanArtifact, 'lex_at', [numberSpanDescriptor, 5], numberSpanWords, numberSpanBytes);
equal(numberEof.resultRegisters[0], 0, 'number-span EOF kind');
equal(numberEof.resultRegisters[1], 5, 'number-span EOF start');
equal(numberEof.resultRegisters[2], 5, 'number-span EOF end');


const decimalValueProgram = compiler.compile(
  'fn digit_value(c: u8) -> i32 { return c - 48; }\n' +
  'fn parse_decimal(source: Slice<u8>, start: i32, end: i32) -> i32 {\n' +
  ' return loop(i = start, value = 0) while i < end {\n' +
  '  next(i + 1, value * 10 + digit_value(slice_get(source, i)));\n' +
  ' } yield value;\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
const decimalValueBinary = compiler.encodeIR(decimalValueProgram.ir);
equal(String.fromCharCode(...decimalValueBinary.slice(0, 6)), 'SNIRV6', 'decimal byte widening must use SNIRV6');
equal(decimalValueBinary.length, 1085, 'decimal widening SNIRV6 size');
const decimalValueArtifact = compiler.emitArm32Runtime(decimalValueProgram.ir);
check(compiler.verifyArm32Runtime(decimalValueArtifact, decimalValueProgram.ir), 'decimal widening artifact failed canonical verify');
equal(decimalValueArtifact.byteLength, 532, 'decimal widening ELF size');
equal(
  executeFunction(decimalValueArtifact, 'digit_value', [55]).result,
  7,
  'u8-to-i32 widening must preserve ASCII digit value before subtraction'
);

const decimalDescriptor = 0x20007000;
const decimalData = 0x20007100;
const decimalRaw = [49, 50, 51, 52];
const decimalWords = [[decimalDescriptor, decimalData], [decimalDescriptor + 4, decimalRaw.length]];
const decimalBytes = decimalRaw.map((value, index) => [decimalData + index, value]);
equal(
  executeFunction(decimalValueArtifact, 'parse_decimal', [decimalDescriptor, 0, 4], decimalWords, decimalBytes).result,
  1234,
  'native decimal parser must accumulate 1234'
);
equal(
  executeFunction(decimalValueArtifact, 'parse_decimal', [decimalDescriptor, 1, 3], decimalWords, decimalBytes).result,
  23,
  'native decimal parser must honor token subspan bounds'
);

const parserStateProgram = compiler.compile(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn classify(c: u8) -> i32 {\n' +
  ' return if c >= 48 { if c <= 57 { 2 } else { 1 } } else { if c == 43 { 3 } else { 1 } };\n' +
  '}\n' +
  'fn lex_at(source: Slice<u8>, start: i32) -> Token {\n' +
  ' return if start < slice_len(source) { Token { kind: classify(slice_get(source, start)), start: start, end: start + 1 } } else { Token { kind: 0, start: start, end: start } };\n' +
  '}\n' +
  'fn accepts_digit_plus_digit(source: Slice<u8>) -> i32 {\n' +
  ' let first = lex_at(source, 0);\n' +
  ' return loop(token = first, phase = 0, valid = 1) while token.kind != 0 {\n' +
  '  next(\n' +
  '   lex_at(source, token.end),\n' +
  '   if phase < 3 { phase + 1 } else { phase },\n' +
  '   if valid == 1 {\n' +
  '    if phase == 0 { if token.kind == 2 { 1 } else { 0 } } else {\n' +
  '     if phase == 1 { if token.kind == 3 { 1 } else { 0 } } else {\n' +
  '      if phase == 2 { if token.kind == 2 { 1 } else { 0 } } else { 0 }\n' +
  '     }\n' +
  '    }\n' +
  '   } else { 0 }\n' +
  '  );\n' +
  ' } yield if valid == 1 { if phase == 3 { 1 } else { 0 } } else { 0 };\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
const parserStateBinary = compiler.encodeIR(parserStateProgram.ir);
equal(String.fromCharCode(...parserStateBinary.slice(0, 6)), 'SNIRV5', 'parser-state kernel must use SNIRV5');
equal(parserStateBinary.length, 5574, 'parser-state SNIRV5 size');
const parserStateArtifact = compiler.emitArm32Runtime(parserStateProgram.ir);
check(compiler.verifyArm32Runtime(parserStateArtifact, parserStateProgram.ir), 'parser-state artifact failed canonical verify');
equal(parserStateArtifact.byteLength, 2056, 'parser-state ELF size');

function runParserBytes(raw) {
  const descriptor = 0x20005000;
  const data = 0x20005100;
  const words = [[descriptor, data], [descriptor + 4, raw.length]];
  const bytes = raw.map((value, index) => [data + index, value]);
  if (raw.length === 0) words[0] = [descriptor, 0];
  return executeFunction(parserStateArtifact, 'accepts_digit_plus_digit', [descriptor], words, bytes).result;
}

equal(runParserBytes([49,43,50]), 1, 'parser must accept 1+2');
equal(runParserBytes([57,43,48]), 1, 'parser must accept 9+0');
equal(runParserBytes([49,45,50]), 0, 'parser must reject 1-2');
equal(runParserBytes([49,43]), 0, 'parser must reject incomplete 1+');
equal(runParserBytes([49,43,50,43,51]), 0, 'parser must reject extra +3');
equal(runParserBytes([]), 0, 'parser must reject empty input');


const astRecordProgram = compiler.compile(
  'struct Expr { kind: i32, left: i32, right: i32, value: i32 }\n' +
  'fn digit_value(c: u8) -> i32 { return c - 48; }\n' +
  'fn parse_add(source: Slice<u8>) -> Expr {\n' +
  ' let left = digit_value(slice_get(source, 0));\n' +
  ' let right = digit_value(slice_get(source, 2));\n' +
  ' return Expr { kind: 1, left: left, right: right, value: left + right };\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
const astRecordBinary = compiler.encodeIR(astRecordProgram.ir);
equal(String.fromCharCode(...astRecordBinary.slice(0, 6)), 'SNIRV6', 'AST record parser must use SNIRV6');
equal(astRecordBinary.length, 829, 'AST record parser SNIRV6 size');
const astRecordArtifact = compiler.emitArm32Runtime(astRecordProgram.ir);
check(compiler.verifyArm32Runtime(astRecordArtifact, astRecordProgram.ir), 'AST record parser artifact failed canonical verify');
equal(astRecordArtifact.byteLength, 472, 'AST record parser ELF size');

const astDescriptor = 0x20008000;
const astData = 0x20008100;
const astBytesRaw = [49, 43, 50];
const astWords = [[astDescriptor, astData], [astDescriptor + 4, astBytesRaw.length]];
const astBytes = astBytesRaw.map((value, index) => [astData + index, value]);
const astRun = executeFunction(astRecordArtifact, 'parse_add', [astDescriptor], astWords, astBytes);
equal(astRun.resultRegisters[0], 1, 'AST record kind');
equal(astRun.resultRegisters[1], 1, 'AST record left value');
equal(astRun.resultRegisters[2], 2, 'AST record right value');
equal(astRun.resultRegisters[3], 3, 'AST record computed value');


const arenaWriteProgram = compiler.compile(
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
const arenaWriteBinary = compiler.encodeIR(arenaWriteProgram.ir);
equal(String.fromCharCode(...arenaWriteBinary.slice(0, 6)), 'SNIRV7', 'Arena state program must use SNIRV7');
equal(arenaWriteBinary.length, 1018, 'Arena state SNIRV7 size');
const arenaWriteArtifact = compiler.emitArm32Runtime(arenaWriteProgram.ir);
check(compiler.verifyArm32Runtime(arenaWriteArtifact, arenaWriteProgram.ir), 'Arena writer artifact failed canonical verify');
equal(arenaWriteArtifact.byteLength, 808, 'Arena writer ELF size');

const arenaDescriptor = 0x20009000;
const arenaData = 0x20009100;
const arenaWords = [[arenaDescriptor, arenaData], [arenaDescriptor + 4, 3]];
const arenaRun = executeFunction(arenaWriteArtifact, 'build_add', [0, arenaDescriptor], arenaWords, []);
equal(arenaRun.result, 2, 'Arena AST writer root handle');

const expectedArenaCells = [
  [0, 0, 0, 1],
  [0, 0, 0, 2],
  [1, 0, 1, 3]
];
for (let cell = 0; cell < expectedArenaCells.length; cell += 1) {
  for (let field = 0; field < 4; field += 1) {
    equal(
      arenaRun.memory.get(arenaData + cell * 16 + field * 4),
      expectedArenaCells[cell][field],
      'Arena cell ' + cell + ' field ' + field
    );
  }
}

equal(
  executeFunction(arenaWriteArtifact, 'build_add', [0, 0], arenaWords, []).result,
  125,
  'Arena null descriptor trap'
);
equal(
  executeFunction(
    arenaWriteArtifact,
    'build_add',
    [0, arenaDescriptor],
    [[arenaDescriptor, arenaData], [arenaDescriptor + 4, 0xFFFFFFFF]],
    []
  ).result,
  125,
  'Arena negative length trap'
);
equal(
  executeFunction(
    arenaWriteArtifact,
    'build_add',
    [0, arenaDescriptor],
    [[arenaDescriptor, 0], [arenaDescriptor + 4, 3]],
    []
  ).result,
  125,
  'Arena null data trap'
);
equal(
  executeFunction(
    arenaWriteArtifact,
    'build_add',
    [0, arenaDescriptor],
    [[arenaDescriptor, arenaData], [arenaDescriptor + 4, 2]],
    []
  ).result,
  125,
  'Arena out-of-range third node trap'
);

const arenaLenProgram = compiler.compile(
  'fn capacity(arena: Arena) -> i32 { return arena_len(arena); }\n' +
  'fn main() -> i32 { return 0; }\n'
);
const arenaLenBinary = compiler.encodeIR(arenaLenProgram.ir);
equal(String.fromCharCode(...arenaLenBinary.slice(0, 6)), 'SNIRV7', 'Arena length program must use SNIRV7');
const arenaLenArtifact = compiler.emitArm32Runtime(arenaLenProgram.ir);
check(compiler.verifyArm32Runtime(arenaLenArtifact, arenaLenProgram.ir), 'Arena length artifact failed canonical verify');
equal(
  executeFunction(arenaLenArtifact, 'capacity', [arenaDescriptor], arenaWords, []).result,
  3,
  'arena_len must return cell capacity'
);
equal(
  executeFunction(arenaLenArtifact, 'capacity', [0], arenaWords, []).result,
  125,
  'arena_len null descriptor trap'
);

const arenaRoundTripProgram = compiler.compile(
  'struct Expr { kind: i32, a: i32, b: i32, value: i32 }\n' +
  'fn roundtrip(arena: Arena) -> i32 {\n' +
  ' let node = Expr { kind: 1, a: 2, b: 3, value: 42 };\n' +
  ' let stored = arena_store(arena, 0, node);\n' +
  ' let loaded = arena_load<Expr>(arena, 0);\n' +
  ' return loaded.value;\n' +
  '}\n' +
  'fn read_value(arena: Arena, index: i32) -> i32 {\n' +
  ' let loaded = arena_load<Expr>(arena, index);\n' +
  ' return loaded.value;\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
const arenaRoundTripBinary = compiler.encodeIR(arenaRoundTripProgram.ir);
equal(String.fromCharCode(...arenaRoundTripBinary.slice(0, 6)), 'SNIRV7', 'Arena read/write program must use SNIRV7');
const arenaRoundTripDecoded = compiler.decodeIR(arenaRoundTripBinary);
equal(arenaRoundTripDecoded.dump(), arenaRoundTripProgram.irText, 'Arena read/write SNIRV7 round trip');
let arenaV6Rejects = false;
try { compiler.encodeIRV6(arenaRoundTripProgram.ir); } catch (_) { arenaV6Rejects = true; }
check(arenaV6Rejects, 'SNIRV6 must reject Arena read/write IR');
check(
  arenaRoundTripProgram.ir.functions.some(fn => fn.instructions.some(inst => inst.op === 'arena.load.record')),
  'Arena read/write IR must contain arena.load.record'
);
const arenaRoundTripArtifact = compiler.emitArm32Runtime(arenaRoundTripProgram.ir);
check(compiler.verifyArm32Runtime(arenaRoundTripArtifact, arenaRoundTripProgram.ir), 'Arena read/write artifact failed canonical verify');

const arenaRoundTripDescriptor = 0x2000A000;
const arenaRoundTripData = 0x2000A100;
const arenaRoundTripWords = [[arenaRoundTripDescriptor, arenaRoundTripData], [arenaRoundTripDescriptor + 4, 1]];
const arenaRoundTripRun = executeFunction(arenaRoundTripArtifact, 'roundtrip', [arenaRoundTripDescriptor], arenaRoundTripWords, []);
equal(arenaRoundTripRun.result, 42, 'Arena store then load must recover AST value');
for (const [field, expected] of [[0,1],[1,2],[2,3],[3,42]]) {
  equal(arenaRoundTripRun.memory.get(arenaRoundTripData + field * 4), expected, 'Arena round-trip stored field ' + field);
}

const seededArenaWords = [
  [arenaRoundTripDescriptor, arenaRoundTripData],
  [arenaRoundTripDescriptor + 4, 1],
  [arenaRoundTripData, 7],
  [arenaRoundTripData + 4, 8],
  [arenaRoundTripData + 8, 9],
  [arenaRoundTripData + 12, 1234]
];
equal(
  executeFunction(arenaRoundTripArtifact, 'read_value', [arenaRoundTripDescriptor, 0], seededArenaWords, []).result,
  1234,
  'Arena load must read an existing AST cell'
);
for (const [name, args, words] of [
  ['null descriptor', [0, 0], seededArenaWords],
  ['negative index', [arenaRoundTripDescriptor, -1], seededArenaWords],
  ['index equals capacity', [arenaRoundTripDescriptor, 1], seededArenaWords],
  ['negative capacity', [arenaRoundTripDescriptor, 0], [[arenaRoundTripDescriptor, arenaRoundTripData], [arenaRoundTripDescriptor + 4, 0xFFFFFFFF]]],
  ['null data', [arenaRoundTripDescriptor, 0], [[arenaRoundTripDescriptor, 0], [arenaRoundTripDescriptor + 4, 1]]]
]) {
  equal(executeFunction(arenaRoundTripArtifact, 'read_value', args, words, []).result, 125, 'Arena load ' + name + ' trap');
}

let arenaScalarTypeRejects = false;
try {
  compiler.compile('fn bad(arena: Arena) -> i32 { return arena_load<i32>(arena, 0); }\nfn main() -> i32 { return 0; }\n');
} catch (_) { arenaScalarTypeRejects = true; }
check(arenaScalarTypeRejects, 'arena_load type argument must be a flat record');

const arenaTreeProgram = compiler.compile(
  'struct Expr { kind: i32, a: i32, b: i32, value: i32 }\n' +
  'fn digit_value(c: u8) -> i32 { return c - 48; }\n' +
  'fn store_leaf(arena: Arena, index: i32, value: i32) -> i32 {\n' +
  ' let node = Expr { kind: 0, a: 0, b: 0, value: value };\n' +
  ' return arena_store(arena, index, node);\n' +
  '}\n' +
  'fn build_add(source: Slice<u8>, arena: Arena) -> i32 {\n' +
  ' let left = store_leaf(arena, 0, digit_value(slice_get(source, 0)));\n' +
  ' let right = store_leaf(arena, 1, digit_value(slice_get(source, 2)));\n' +
  ' let root = Expr { kind: 1, a: left, b: right, value: 0 };\n' +
  ' return arena_store(arena, 2, root);\n' +
  '}\n' +
  'fn load_value(arena: Arena, index: i32) -> i32 {\n' +
  ' let node = arena_load<Expr>(arena, index);\n' +
  ' return node.value;\n' +
  '}\n' +
  'fn eval_add(arena: Arena, root_index: i32) -> i32 {\n' +
  ' let root = arena_load<Expr>(arena, root_index);\n' +
  ' return load_value(arena, root.a) + load_value(arena, root.b);\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
for (const name of ['store_leaf','build_add','load_value','eval_add']) {
  equal(arenaTreeProgram.ir.functions.find(fn => fn.name === name).effect, 'state', 'Arena tree derived state effect for ' + name);
}
const arenaTreeBinary = compiler.encodeIR(arenaTreeProgram.ir);
equal(String.fromCharCode(...arenaTreeBinary.slice(0, 6)), 'SNIRV7', 'Arena tree must use SNIRV7');
equal(compiler.decodeIR(arenaTreeBinary).dump(), arenaTreeProgram.irText, 'Arena tree SNIRV7 round trip');
const arenaTreeArtifact = compiler.emitArm32Runtime(arenaTreeProgram.ir);
check(compiler.verifyArm32Runtime(arenaTreeArtifact, arenaTreeProgram.ir), 'Arena tree artifact failed canonical verify');

const arenaTreeSourceDescriptor = 0x2000B000;
const arenaTreeSourceData = 0x2000B100;
const arenaTreeDescriptor = 0x2000B200;
const arenaTreeData = 0x2000B300;
const arenaTreeWords = [
  [arenaTreeSourceDescriptor, arenaTreeSourceData],
  [arenaTreeSourceDescriptor + 4, 3],
  [arenaTreeDescriptor, arenaTreeData],
  [arenaTreeDescriptor + 4, 3]
];
const arenaTreeBytes = [[arenaTreeSourceData,49],[arenaTreeSourceData + 1,43],[arenaTreeSourceData + 2,50]];
const arenaTreeBuild = executeFunction(
  arenaTreeArtifact,
  'build_add',
  [arenaTreeSourceDescriptor, arenaTreeDescriptor],
  arenaTreeWords,
  arenaTreeBytes
);
equal(arenaTreeBuild.result, 2, 'Arena tree root handle');
equal(arenaTreeBuild.memory.get(arenaTreeData + 12), 1, 'Arena tree left leaf value');
equal(arenaTreeBuild.memory.get(arenaTreeData + 16 + 12), 2, 'Arena tree right leaf value');
equal(arenaTreeBuild.memory.get(arenaTreeData + 32), 1, 'Arena tree root kind');
equal(arenaTreeBuild.memory.get(arenaTreeData + 36), 0, 'Arena tree root left handle');
equal(arenaTreeBuild.memory.get(arenaTreeData + 40), 1, 'Arena tree root right handle');
const arenaTreeEval = executeFunction(
  arenaTreeArtifact,
  'eval_add',
  [arenaTreeDescriptor, arenaTreeBuild.result],
  Array.from(arenaTreeBuild.memory.entries()),
  []
);
equal(arenaTreeEval.result, 3, 'Arena tree traversal must follow child handles and evaluate 1+2');

const recordParameterProgram = compiler.compile(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn token_end(token: Token) -> i32 { return token.end; }\n' +
  'fn shifted(prefix: i32, token: Token) -> i32 { return prefix + token.end; }\n' +
  'fn call_token_end() -> i32 { let token = Token { kind: 2, start: 4, end: 9 }; return token_end(token); }\n' +
  'fn call_shifted() -> i32 { let token = Token { kind: 2, start: 4, end: 9 }; return shifted(10, token); }\n' +
  'fn main() -> i32 { return call_token_end(); }\n'
);
const recordParameterArtifact = compiler.emitArm32Runtime(recordParameterProgram.ir);
check(compiler.verifyArm32Runtime(recordParameterArtifact, recordParameterProgram.ir), 'record-parameter artifact failed canonical verify');
equal(executeFunction(recordParameterArtifact, 'token_end', [2,4,9]).result, 9, 'record parameter direct r0-r2 ABI');
equal(executeFunction(recordParameterArtifact, 'shifted', [10,2,4,9]).result, 19, 'mixed scalar+record r0-r3 ABI');
equal(executeFunction(recordParameterArtifact, 'call_token_end', []).result, 9, 'record argument call flattening');
equal(executeFunction(recordParameterArtifact, 'call_shifted', []).result, 19, 'mixed scalar+record call flattening');

const stackedRecordParameterProgram = compiler.compile(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'fn stacked(prefix: i32, token: Token, suffix: i32) -> i32 { return prefix + token.end + suffix; }\n' +
  'fn call_stacked() -> i32 { let token = Token { kind: 2, start: 4, end: 9 }; return stacked(10, token, 7); }\n' +
  'fn five_scalars(a: i32, b: i32, c: i32, d: i32, e: i32) -> i32 { return a + b + c + d + e; }\n' +
  'fn call_five_scalars() -> i32 { return five_scalars(1, 2, 3, 4, 5); }\n' +
  'fn main() -> i32 { return call_stacked(); }\n'
);
const stackedRecordParameterArtifact = compiler.emitArm32Runtime(stackedRecordParameterProgram.ir);
check(compiler.verifyArm32Runtime(stackedRecordParameterArtifact, stackedRecordParameterProgram.ir), 'stack-argument artifact failed canonical verify');
equal(executeFunction(stackedRecordParameterArtifact, 'call_stacked', []).result, 26, 'fifth flattened record/scalar word must travel on aligned caller stack');
equal(executeFunction(stackedRecordParameterArtifact, 'call_five_scalars', []).result, 15, 'fifth scalar argument must travel on aligned caller stack');

const overBudgetParameterList = Array.from({length:33}, (_, index) => 'p' + index + ': i32').join(', ');
const overBudgetProgram = compiler.compile(
  'fn too_many(' + overBudgetParameterList + ') -> i32 { return p0; }\n' +
  'fn main() -> i32 { return 0; }\n'
);
let overBudgetRejects = false;
try { compiler.emitArm32Runtime(overBudgetProgram.ir); } catch (error) {
  overBudgetRejects = /exceeds 32 argument words/.test(String(error && error.message ? error.message : error));
}
check(overBudgetRejects, 'stack argument ABI must reject functions beyond the 32-word budget');

const arenaParserProgram = compiler.compile(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'struct Expr { kind: i32, a: i32, b: i32, value: i32 }\n' +
  'fn classify(c: u8) -> i32 { return if c >= 48 { if c <= 57 { 2 } else { 1 } } else { if c == 43 { 3 } else { 1 } }; }\n' +
  'fn lex_at(source: Slice<u8>, start: i32) -> Token { return if start < slice_len(source) { Token { kind: classify(slice_get(source, start)), start: start, end: start + 1 } } else { Token { kind: 0, start: start, end: start } }; }\n' +
  'fn token_value(source: Slice<u8>, token: Token) -> i32 { return slice_get(source, token.start) - 48; }\n' +
  'fn parse_add(source: Slice<u8>, arena: Arena) -> i32 {\n' +
  ' let first = lex_at(source, 0);\n' +
  ' let plus = lex_at(source, first.end);\n' +
  ' let second = lex_at(source, plus.end);\n' +
  ' let left = Expr { kind: 0, a: 0, b: 0, value: token_value(source, first) };\n' +
  ' let right = Expr { kind: 0, a: 0, b: 0, value: token_value(source, second) };\n' +
  ' let left_handle = arena_store(arena, 0, left);\n' +
  ' let right_handle = arena_store(arena, 1, right);\n' +
  ' let root = Expr { kind: 1, a: left_handle, b: right_handle, value: 0 };\n' +
  ' return arena_store(arena, 2, root);\n' +
  '}\n' +
  'fn load_value(arena: Arena, index: i32) -> i32 { let node = arena_load<Expr>(arena, index); return node.value; }\n' +
  'fn eval_add(arena: Arena, root_index: i32) -> i32 { let root = arena_load<Expr>(arena, root_index); return load_value(arena, root.a) + load_value(arena, root.b); }\n' +
  'fn main() -> i32 { return 0; }\n'
);
const arenaParserBinary = compiler.encodeIR(arenaParserProgram.ir);
equal(String.fromCharCode(...arenaParserBinary.slice(0, 6)), 'SNIRV7', 'Arena parser pipeline must use SNIRV7');
equal(compiler.decodeIR(arenaParserBinary).dump(), arenaParserProgram.irText, 'Arena parser pipeline SNIRV7 round trip');
const arenaParserArtifact = compiler.emitArm32Runtime(arenaParserProgram.ir);
check(compiler.verifyArm32Runtime(arenaParserArtifact, arenaParserProgram.ir), 'Arena parser pipeline artifact failed canonical verify');
equal(arenaParserProgram.ir.functions.find(fn => fn.name === 'token_value').effect, 'pure', 'Token helper remains pure');
equal(arenaParserProgram.ir.functions.find(fn => fn.name === 'parse_add').effect, 'state', 'Arena parser derives state effect');
equal(arenaParserProgram.ir.functions.find(fn => fn.name === 'eval_add').effect, 'state', 'Arena evaluator derives state effect');

const arenaParserSourceDescriptor = 0x2000C000;
const arenaParserSourceData = 0x2000C100;
const arenaParserDescriptor = 0x2000C200;
const arenaParserData = 0x2000C300;
const arenaParserWords = [
  [arenaParserSourceDescriptor, arenaParserSourceData],
  [arenaParserSourceDescriptor + 4, 3],
  [arenaParserDescriptor, arenaParserData],
  [arenaParserDescriptor + 4, 3]
];
const arenaParserBytes = [[arenaParserSourceData,49],[arenaParserSourceData + 1,43],[arenaParserSourceData + 2,50]];
const arenaParserBuild = executeFunction(
  arenaParserArtifact,
  'parse_add',
  [arenaParserSourceDescriptor, arenaParserDescriptor],
  arenaParserWords,
  arenaParserBytes
);
equal(arenaParserBuild.result, 2, 'Arena parser root handle');
const arenaParserEval = executeFunction(
  arenaParserArtifact,
  'eval_add',
  [arenaParserDescriptor, arenaParserBuild.result],
  Array.from(arenaParserBuild.memory.entries()),
  []
);
equal(arenaParserEval.result, 3, 'lexer→Token→Arena parser→Arena traversal must evaluate 1+2');

const parserStateStackProgram = compiler.compile(
  'struct Expr { kind: i32, a: i32, b: i32, value: i32 }\n' +
  'struct ParserState { pos: i32, root: i32, slot: i32 }\n' +
  'fn step(source: Slice<u8>, arena: Arena, state: ParserState) -> i32 {\n' +
  ' let node = arena_load<Expr>(arena, state.root);\n' +
  ' return state.pos + state.slot + node.value + slice_len(source);\n' +
  '}\n' +
  'fn call_step(source: Slice<u8>, arena: Arena) -> i32 {\n' +
  ' let state = ParserState { pos: 2, root: 0, slot: 7 };\n' +
  ' return step(source, arena, state);\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
const parserStateStackArtifact = compiler.emitArm32Runtime(parserStateStackProgram.ir);
check(compiler.verifyArm32Runtime(parserStateStackArtifact, parserStateStackProgram.ir), 'parser-state stack-argument artifact failed canonical verify');
const parserStateSourceDescriptor = 0x2000D000;
const parserStateSourceData = 0x2000D100;
const parserStateArenaDescriptor = 0x2000D200;
const parserStateArenaData = 0x2000D300;
const parserStateWords = [
  [parserStateSourceDescriptor, parserStateSourceData],
  [parserStateSourceDescriptor + 4, 3],
  [parserStateArenaDescriptor, parserStateArenaData],
  [parserStateArenaDescriptor + 4, 1],
  [parserStateArenaData, 0],
  [parserStateArenaData + 4, 0],
  [parserStateArenaData + 8, 0],
  [parserStateArenaData + 12, 11]
];
equal(
  executeFunction(
    parserStateStackArtifact,
    'call_step',
    [parserStateSourceDescriptor, parserStateArenaDescriptor],
    parserStateWords,
    []
  ).result,
  23,
  'Slice+Arena+ParserState five-word parser helper ABI'
);

const parserStateReturnProgram = compiler.compile(
  'struct Expr { kind: i32, a: i32, b: i32, value: i32 }\n' +
  'struct ParserState { pos: i32, root: i32, slot: i32 }\n' +
  'fn parse_leaf(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState {\n' +
  ' let node = Expr { kind: 0, a: 0, b: 0, value: slice_get(source, state.pos) - 48 };\n' +
  ' let handle = arena_store(arena, state.slot, node);\n' +
  ' return ParserState { pos: state.pos + 1, root: handle, slot: state.slot + 1 };\n' +
  '}\n' +
  'fn run(source: Slice<u8>, arena: Arena) -> i32 {\n' +
  ' let initial = ParserState { pos: 0, root: 0, slot: 0 };\n' +
  ' let parsed = parse_leaf(source, arena, initial);\n' +
  ' let node = arena_load<Expr>(arena, parsed.root);\n' +
  ' return node.value + parsed.pos + parsed.slot;\n' +
  '}\n' +
  'fn main() -> i32 { return 0; }\n'
);
const parserStateReturnArtifact = compiler.emitArm32Runtime(parserStateReturnProgram.ir);
check(compiler.verifyArm32Runtime(parserStateReturnArtifact, parserStateReturnProgram.ir), 'parser-state record-return artifact failed canonical verify');
const parserStateReturnSourceDescriptor = 0x2000E000;
const parserStateReturnSourceData = 0x2000E100;
const parserStateReturnArenaDescriptor = 0x2000E200;
const parserStateReturnArenaData = 0x2000E300;
const parserStateReturnWords = [
  [parserStateReturnSourceDescriptor, parserStateReturnSourceData],
  [parserStateReturnSourceDescriptor + 4, 1],
  [parserStateReturnArenaDescriptor, parserStateReturnArenaData],
  [parserStateReturnArenaDescriptor + 4, 1]
];
equal(
  executeFunction(
    parserStateReturnArtifact,
    'run',
    [parserStateReturnSourceDescriptor, parserStateReturnArenaDescriptor],
    parserStateReturnWords,
    [[parserStateReturnSourceData,53]]
  ).result,
  7,
  'parser-state helper must return updated record through r0-r2 after five-word call ABI'
);

const parserChainProgram = compiler.compile(
  'struct Expr { kind: i32, a: i32, b: i32, value: i32 }\n' +
  'struct ParserState { pos: i32, root: i32, slot: i32 }\n' +
  'fn parse_leaf(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState {\n' +
  ' let node = Expr { kind: 0, a: 0, b: 0, value: slice_get(source, state.pos) - 48 };\n' +
  ' let handle = arena_store(arena, state.slot, node);\n' +
  ' return ParserState { pos: state.pos + 1, root: handle, slot: state.slot + 1 };\n' +
  '}\n' +
  'fn parse_plus(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState {\n' +
  ' let right_start = ParserState { pos: state.pos + 1, root: state.root, slot: state.slot };\n' +
  ' let right = parse_leaf(source, arena, right_start);\n' +
  ' let root = Expr { kind: 1, a: state.root, b: right.root, value: 0 };\n' +
  ' let handle = arena_store(arena, right.slot, root);\n' +
  ' return ParserState { pos: right.pos, root: handle, slot: right.slot + 1 };\n' +
  '}\n' +
  'fn parse_chain(source: Slice<u8>, arena: Arena) -> ParserState {\n' +
  ' let initial = parse_leaf(source, arena, ParserState { pos: 0, root: 0, slot: 0 });\n' +
  ' return loop(state = initial) while state.pos < slice_len(source) {\n' +
  '  next(parse_plus(source, arena, state));\n' +
  ' } yield state;\n' +
  '}\n' +
  'fn run(source: Slice<u8>, arena: Arena) -> i32 { let parsed = parse_chain(source, arena); return parsed.root + parsed.slot + parsed.pos; }\n' +
  'fn main() -> i32 { return 0; }\n'
);
const parserChainArtifact = compiler.emitArm32Runtime(parserChainProgram.ir);
check(compiler.verifyArm32Runtime(parserChainArtifact, parserChainProgram.ir), 'parser-chain artifact failed canonical verify');
const parserChainSourceDescriptor = 0x2000F000;
const parserChainSourceData = 0x2000F100;
const parserChainArenaDescriptor = 0x2000F200;
const parserChainArenaData = 0x2000F300;
const parserChainWords = [
  [parserChainSourceDescriptor, parserChainSourceData],
  [parserChainSourceDescriptor + 4, 5],
  [parserChainArenaDescriptor, parserChainArenaData],
  [parserChainArenaDescriptor + 4, 5]
];
const parserChainBytes = [
  [parserChainSourceData,49],
  [parserChainSourceData + 1,43],
  [parserChainSourceData + 2,50],
  [parserChainSourceData + 3,43],
  [parserChainSourceData + 4,51]
];
const parserChainRun = executeFunction(
  parserChainArtifact,
  'run',
  [parserChainSourceDescriptor, parserChainArenaDescriptor],
  parserChainWords,
  parserChainBytes
);
equal(parserChainRun.result, 14, 'record-yield parser loop must return pos=5 root=4 slot=5');
const expectedParserChainCells = [
  [0,0,0,1],
  [0,0,0,2],
  [1,0,1,0],
  [0,0,0,3],
  [1,2,3,0]
];
for (let cell = 0; cell < expectedParserChainCells.length; cell += 1) {
  for (let field = 0; field < 4; field += 1) {
    equal(
      parserChainRun.memory.get(parserChainArenaData + cell * 16 + field * 4),
      expectedParserChainCells[cell][field],
      'parser-chain Arena cell ' + cell + ' field ' + field
    );
  }
}

const multiDigitParserProgram = compiler.compile(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'struct Expr { kind: i32, a: i32, b: i32, value: i32 }\n' +
  'struct ParserState { pos: i32, root: i32, slot: i32 }\n' +
  'fn is_digit(c: u8) -> i32 { return if c >= 48 { if c <= 57 { 1 } else { 0 } } else { 0 }; }\n' +
  'fn scan_digits(source: Slice<u8>, start: i32) -> i32 { return loop(i = start, active = 1) while active != 0 { next(if i < slice_len(source) { if is_digit(slice_get(source, i)) == 1 { i + 1 } else { i } } else { i }, if i < slice_len(source) { if is_digit(slice_get(source, i)) == 1 { 1 } else { 0 } } else { 0 }); } yield i; }\n' +
  'fn lex_at(source: Slice<u8>, start: i32) -> Token { return if start < slice_len(source) { if is_digit(slice_get(source, start)) == 1 { Token { kind: 2, start: start, end: scan_digits(source, start) } } else { if slice_get(source, start) == 43 { Token { kind: 3, start: start, end: start + 1 } } else { Token { kind: 1, start: start, end: start + 1 } } } } else { Token { kind: 0, start: start, end: start } }; }\n' +
  'fn digit_value(c: u8) -> i32 { return c - 48; }\n' +
  'fn parse_decimal(source: Slice<u8>, start: i32, end: i32) -> i32 { return loop(i = start, value = 0) while i < end { next(i + 1, value * 10 + digit_value(slice_get(source, i))); } yield value; }\n' +
  'fn parse_number(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState { let token = lex_at(source, state.pos); let node = Expr { kind: 0, a: 0, b: 0, value: parse_decimal(source, token.start, token.end) }; let handle = arena_store(arena, state.slot, node); return ParserState { pos: token.end, root: handle, slot: state.slot + 1 }; }\n' +
  'fn parse_plus(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState { let plus = lex_at(source, state.pos); let right = parse_number(source, arena, ParserState { pos: plus.end, root: state.root, slot: state.slot }); let root = Expr { kind: 1, a: state.root, b: right.root, value: 0 }; let handle = arena_store(arena, right.slot, root); return ParserState { pos: right.pos, root: handle, slot: right.slot + 1 }; }\n' +
  'fn parse_chain(source: Slice<u8>, arena: Arena) -> ParserState { let initial = parse_number(source, arena, ParserState { pos: 0, root: 0, slot: 0 }); return loop(state = initial) while state.pos < slice_len(source) { next(parse_plus(source, arena, state)); } yield state; }\n' +
  'fn run(source: Slice<u8>, arena: Arena) -> i32 { let parsed = parse_chain(source, arena); return parsed.pos + parsed.root + parsed.slot; }\n' +
  'fn main() -> i32 { return 0; }\n'
);
const multiDigitParserArtifact = compiler.emitArm32Runtime(multiDigitParserProgram.ir);
check(compiler.verifyArm32Runtime(multiDigitParserArtifact, multiDigitParserProgram.ir), 'multi-digit parser artifact failed canonical verify');
const multiDigitSourceDescriptor = 0x20010000;
const multiDigitSourceData = 0x20010100;
const multiDigitArenaDescriptor = 0x20010200;
const multiDigitArenaData = 0x20010300;
const multiDigitWords = [
  [multiDigitSourceDescriptor, multiDigitSourceData],
  [multiDigitSourceDescriptor + 4, 7],
  [multiDigitArenaDescriptor, multiDigitArenaData],
  [multiDigitArenaDescriptor + 4, 5]
];
const multiDigitRaw = [49,50,43,51,52,43,53];
const multiDigitBytes = multiDigitRaw.map((value, index) => [multiDigitSourceData + index, value]);
const multiDigitRun = executeFunction(
  multiDigitParserArtifact,
  'run',
  [multiDigitSourceDescriptor, multiDigitArenaDescriptor],
  multiDigitWords,
  multiDigitBytes
);
equal(multiDigitRun.result, 16, 'multi-digit parser must finish pos=7 root=4 slot=5');
const expectedMultiDigitCells = [
  [0,0,0,12],
  [0,0,0,34],
  [1,0,1,0],
  [0,0,0,5],
  [1,2,3,0]
];
for (let cell = 0; cell < expectedMultiDigitCells.length; cell += 1) {
  for (let field = 0; field < 4; field += 1) {
    equal(
      multiDigitRun.memory.get(multiDigitArenaData + cell * 16 + field * 4),
      expectedMultiDigitCells[cell][field],
      'multi-digit parser Arena cell ' + cell + ' field ' + field
    );
  }
}

const validatingParserProgram = compiler.compile(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'struct Expr { kind: i32, a: i32, b: i32, value: i32 }\n' +
  'struct ParserState { pos: i32, root: i32, slot: i32, ok: i32 }\n' +
  'fn is_digit(c: u8) -> i32 { return if c >= 48 { if c <= 57 { 1 } else { 0 } } else { 0 }; }\n' +
  'fn scan_digits(source: Slice<u8>, start: i32) -> i32 { return loop(i = start, active = 1) while active != 0 { next(if i < slice_len(source) { if is_digit(slice_get(source, i)) == 1 { i + 1 } else { i } } else { i }, if i < slice_len(source) { if is_digit(slice_get(source, i)) == 1 { 1 } else { 0 } } else { 0 }); } yield i; }\n' +
  'fn lex_at(source: Slice<u8>, start: i32) -> Token { return if start < slice_len(source) { if is_digit(slice_get(source, start)) == 1 { Token { kind: 2, start: start, end: scan_digits(source, start) } } else { if slice_get(source, start) == 43 { Token { kind: 3, start: start, end: start + 1 } } else { Token { kind: 1, start: start, end: start + 1 } } } } else { Token { kind: 0, start: start, end: start } }; }\n' +
  'fn digit_value(c: u8) -> i32 { return c - 48; }\n' +
  'fn parse_decimal(source: Slice<u8>, start: i32, end: i32) -> i32 { return loop(i = start, value = 0) while i < end { next(i + 1, value * 10 + digit_value(slice_get(source, i))); } yield value; }\n' +
  'fn store_number(source: Slice<u8>, arena: Arena, state: ParserState, token: Token) -> ParserState { let node = Expr { kind: 0, a: 0, b: 0, value: parse_decimal(source, token.start, token.end) }; let handle = arena_store(arena, state.slot, node); return ParserState { pos: token.end, root: handle, slot: state.slot + 1, ok: state.ok }; }\n' +
  'fn parse_number(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState { let token = lex_at(source, state.pos); return if token.kind == 2 { store_number(source, arena, state, token) } else { ParserState { pos: slice_len(source), root: state.root, slot: state.slot, ok: 0 } }; }\n' +
  'fn make_add(arena: Arena, left: ParserState, right: ParserState) -> ParserState { let root = Expr { kind: 1, a: left.root, b: right.root, value: 0 }; let handle = arena_store(arena, right.slot, root); return ParserState { pos: right.pos, root: handle, slot: right.slot + 1, ok: 1 }; }\n' +
  'fn parse_plus_valid(source: Slice<u8>, arena: Arena, state: ParserState, plus: Token) -> ParserState { let right = parse_number(source, arena, ParserState { pos: plus.end, root: state.root, slot: state.slot, ok: state.ok }); return if right.ok == 1 { make_add(arena, state, right) } else { right }; }\n' +
  'fn parse_plus(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState { let plus = lex_at(source, state.pos); return if plus.kind == 3 { parse_plus_valid(source, arena, state, plus) } else { ParserState { pos: slice_len(source), root: state.root, slot: state.slot, ok: 0 } }; }\n' +
  'fn parse_chain(source: Slice<u8>, arena: Arena) -> ParserState { let initial = parse_number(source, arena, ParserState { pos: 0, root: 0, slot: 0, ok: 1 }); return loop(state = initial) while state.pos < slice_len(source) { next(parse_plus(source, arena, state)); } yield state; }\n' +
  'fn run(source: Slice<u8>, arena: Arena) -> i32 { let parsed = parse_chain(source, arena); return parsed.ok * 100 + parsed.root * 10 + parsed.slot; }\n' +
  'fn main() -> i32 { return 0; }\n'
);
const validatingParserArtifact = compiler.emitArm32Runtime(validatingParserProgram.ir);
check(compiler.verifyArm32Runtime(validatingParserArtifact, validatingParserProgram.ir), 'validating parser artifact failed canonical verify');

function runValidatingParser(rawBytes, arenaBase, sourceBase) {
  const sourceDescriptor = sourceBase;
  const sourceData = sourceBase + 0x100;
  const arenaDescriptor = arenaBase;
  const arenaData = arenaBase + 0x100;
  const words = [
    [sourceDescriptor, sourceData],
    [sourceDescriptor + 4, rawBytes.length],
    [arenaDescriptor, arenaData],
    [arenaDescriptor + 4, 8]
  ];
  return {
    run:executeFunction(
      validatingParserArtifact,
      'run',
      [sourceDescriptor, arenaDescriptor],
      words,
      rawBytes.map((value, index) => [sourceData + index, value])
    ),
    arenaData:arenaData
  };
}

const validOperatorRun = runValidatingParser([49,50,43,51,52,43,53], 0x20011200, 0x20011000);
equal(validOperatorRun.run.result, 145, 'validating parser must return ok=1 root=4 slot=5 for 12+34+5');
equal(validOperatorRun.run.memory.get(validOperatorRun.arenaData + 12), 12, 'validating parser first number');
equal(validOperatorRun.run.memory.get(validOperatorRun.arenaData + 16 + 12), 34, 'validating parser second number');
equal(validOperatorRun.run.memory.get(validOperatorRun.arenaData + 48 + 12), 5, 'validating parser third number');

const invalidOperatorRun = runValidatingParser([49,50,42,51,52], 0x20012200, 0x20012000);
equal(invalidOperatorRun.run.result, 1, 'invalid operator must return ok=0 root=0 slot=1');
equal(invalidOperatorRun.run.memory.get(invalidOperatorRun.arenaData + 12), 12, 'invalid operator keeps the valid first leaf');
check(!invalidOperatorRun.run.memory.has(invalidOperatorRun.arenaData + 16), 'invalid operator must not create a bogus second AST node');

const precedenceParserProgram = compiler.compile(
  'struct Token { kind: i32, start: i32, end: i32 }\n' +
  'struct Expr { kind: i32, a: i32, b: i32, value: i32 }\n' +
  'struct ParserState { pos: i32, root: i32, slot: i32, ok: i32 }\n' +
  'fn is_digit(c: u8) -> i32 { return if c >= 48 { if c <= 57 { 1 } else { 0 } } else { 0 }; }\n' +
  'fn scan_digits(source: Slice<u8>, start: i32) -> i32 { return loop(i = start, active = 1) while active != 0 { next(if i < slice_len(source) { if is_digit(slice_get(source, i)) == 1 { i + 1 } else { i } } else { i }, if i < slice_len(source) { if is_digit(slice_get(source, i)) == 1 { 1 } else { 0 } } else { 0 }); } yield i; }\n' +
  'fn lex_at(source: Slice<u8>, start: i32) -> Token { return if start < slice_len(source) { if is_digit(slice_get(source, start)) == 1 { Token { kind: 2, start: start, end: scan_digits(source, start) } } else { if slice_get(source, start) == 43 { Token { kind: 3, start: start, end: start + 1 } } else { if slice_get(source, start) == 42 { Token { kind: 4, start: start, end: start + 1 } } else { Token { kind: 1, start: start, end: start + 1 } } } } } else { Token { kind: 0, start: start, end: start } }; }\n' +
  'fn digit_value(c: u8) -> i32 { return c - 48; }\n' +
  'fn parse_decimal(source: Slice<u8>, start: i32, end: i32) -> i32 { return loop(i = start, value = 0) while i < end { next(i + 1, value * 10 + digit_value(slice_get(source, i))); } yield value; }\n' +
  'fn store_number(source: Slice<u8>, arena: Arena, state: ParserState, token: Token) -> ParserState { let node = Expr { kind: 0, a: 0, b: 0, value: parse_decimal(source, token.start, token.end) }; let handle = arena_store(arena, state.slot, node); return ParserState { pos: token.end, root: handle, slot: state.slot + 1, ok: state.ok }; }\n' +
  'fn parse_number(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState { let token = lex_at(source, state.pos); return if token.kind == 2 { store_number(source, arena, state, token) } else { ParserState { pos: slice_len(source), root: state.root, slot: state.slot, ok: 0 } }; }\n' +
  'fn make_binary(arena: Arena, left: ParserState, right: ParserState, kind: i32) -> ParserState { let left_node = arena_load<Expr>(arena, left.root); let right_node = arena_load<Expr>(arena, right.root); let value = if kind == 1 { left_node.value + right_node.value } else { left_node.value * right_node.value }; let root = Expr { kind: kind, a: left.root, b: right.root, value: value }; let handle = arena_store(arena, right.slot, root); return ParserState { pos: right.pos, root: handle, slot: right.slot + 1, ok: 1 }; }\n' +
  'fn should_mul(source: Slice<u8>, state: ParserState) -> i32 { let token = lex_at(source, state.pos); return if state.ok == 1 { if state.pos < slice_len(source) { if token.kind == 4 { 1 } else { 0 } } else { 0 } } else { 0 }; }\n' +
  'fn mul_step(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState { let op = lex_at(source, state.pos); let right = parse_number(source, arena, ParserState { pos: op.end, root: state.root, slot: state.slot, ok: state.ok }); return if right.ok == 1 { make_binary(arena, state, right, 2) } else { right }; }\n' +
  'fn parse_term(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState { let initial = parse_number(source, arena, state); return loop(current = initial) while should_mul(source, current) == 1 { next(mul_step(source, arena, current)); } yield current; }\n' +
  'fn should_plus(source: Slice<u8>, state: ParserState) -> i32 { let token = lex_at(source, state.pos); return if state.ok == 1 { if state.pos < slice_len(source) { if token.kind == 3 { 1 } else { 0 } } else { 0 } } else { 0 }; }\n' +
  'fn plus_step(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState { let op = lex_at(source, state.pos); let right = parse_term(source, arena, ParserState { pos: op.end, root: state.root, slot: state.slot, ok: state.ok }); return if right.ok == 1 { make_binary(arena, state, right, 1) } else { right }; }\n' +
  'fn parse_expr(source: Slice<u8>, arena: Arena) -> ParserState { let initial = parse_term(source, arena, ParserState { pos: 0, root: 0, slot: 0, ok: 1 }); return loop(current = initial) while should_plus(source, current) == 1 { next(plus_step(source, arena, current)); } yield current; }\n' +
  'fn run(source: Slice<u8>, arena: Arena) -> i32 { let parsed = parse_expr(source, arena); let root = arena_load<Expr>(arena, parsed.root); return if parsed.ok == 1 { root.value } else { 0 }; }\n' +
  'fn main() -> i32 { return 0; }\n'
);
const precedenceParserArtifact = compiler.emitArm32Runtime(precedenceParserProgram.ir);
check(compiler.verifyArm32Runtime(precedenceParserArtifact, precedenceParserProgram.ir), 'precedence parser artifact failed canonical verify');

function runPrecedenceParser(rawBytes, arenaBase, sourceBase) {
  const sourceDescriptor = sourceBase;
  const sourceData = sourceBase + 0x100;
  const arenaDescriptor = arenaBase;
  const arenaData = arenaBase + 0x100;
  const words = [
    [sourceDescriptor, sourceData],
    [sourceDescriptor + 4, rawBytes.length],
    [arenaDescriptor, arenaData],
    [arenaDescriptor + 4, 8]
  ];
  return {
    run:executeFunction(
      precedenceParserArtifact,
      'run',
      [sourceDescriptor, arenaDescriptor],
      words,
      rawBytes.map((value, index) => [sourceData + index, value])
    ),
    arenaData:arenaData
  };
}

const plusThenMul = runPrecedenceParser([50,43,51,42,52], 0x20013200, 0x20013000);
equal(plusThenMul.run.result, 14, 'precedence parser must evaluate 2+3*4 as 14');
equal(plusThenMul.run.memory.get(plusThenMul.arenaData + 3 * 16), 2, '2+3*4 multiplication node kind');
equal(plusThenMul.run.memory.get(plusThenMul.arenaData + 3 * 16 + 12), 12, '2+3*4 multiplication node value');
equal(plusThenMul.run.memory.get(plusThenMul.arenaData + 4 * 16), 1, '2+3*4 addition root kind');
equal(plusThenMul.run.memory.get(plusThenMul.arenaData + 4 * 16 + 12), 14, '2+3*4 addition root value');

const mulThenPlus = runPrecedenceParser([50,42,51,43,52], 0x20014200, 0x20014000);
equal(mulThenPlus.run.result, 10, 'precedence parser must evaluate 2*3+4 as 10');
equal(mulThenPlus.run.memory.get(mulThenPlus.arenaData + 2 * 16), 2, '2*3+4 multiplication node kind');
equal(mulThenPlus.run.memory.get(mulThenPlus.arenaData + 2 * 16 + 12), 6, '2*3+4 multiplication node value');
equal(mulThenPlus.run.memory.get(mulThenPlus.arenaData + 4 * 16), 1, '2*3+4 addition root kind');
equal(mulThenPlus.run.memory.get(mulThenPlus.arenaData + 4 * 16 + 12), 10, '2*3+4 addition root value');

const recursiveFactorialProgram = compiler.compile(
  'fn fact(n: i32) -> i32 { return if n <= 1 { 1 } else { n * fact(n - 1) }; }\n' +
  'fn main() -> i32 { return fact(5); }\n'
);
const recursiveFactorialArtifact = compiler.emitArm32Runtime(recursiveFactorialProgram.ir);
check(compiler.verifyArm32Runtime(recursiveFactorialArtifact, recursiveFactorialProgram.ir), 'recursive factorial artifact failed canonical verify');
equal(recursiveFactorialArtifact.maxRecursiveCallDepth, 256, 'recursive call depth limit');
equal(recursiveFactorialArtifact.recursiveFunctions.join(','), 'fact', 'recursive function classification');
equal(executeFunction(recursiveFactorialArtifact, 'fact', [5]).result, 120, 'bounded recursive factorial');

const recursiveDepthProgram = compiler.compile(
  'fn dive(n: i32) -> i32 { return if n == 0 { 0 } else { dive(n - 1) }; }\n' +
  'fn main() -> i32 { return dive(1); }\n'
);
const recursiveDepthArtifact = compiler.emitArm32Runtime(recursiveDepthProgram.ir);
equal(executeFunction(recursiveDepthArtifact, 'dive', [255]).result, 0, 'recursive frame 256 must be allowed');
equal(executeFunction(recursiveDepthArtifact, 'dive', [256]).result, 125, 'recursive frame 257 must trap');

const mutualRecursionProgram = compiler.compile(
  'fn even(n: i32) -> i32 { return if n == 0 { 1 } else { odd(n - 1) }; }\n' +
  'fn odd(n: i32) -> i32 { return if n == 0 { 0 } else { even(n - 1) }; }\n' +
  'fn main() -> i32 { return even(10); }\n'
);
const mutualRecursionArtifact = compiler.emitArm32Runtime(mutualRecursionProgram.ir);
equal(mutualRecursionArtifact.recursiveFunctions.join(','), 'even,odd', 'mutual recursion classification');
equal(executeFunction(mutualRecursionArtifact, 'even', [10]).result, 1, 'bounded mutual recursion');

const recursiveParserProgram = compiler.compile(
  'struct Expr { kind: i32, a: i32, b: i32, value: i32 }\n' +
  'struct ParserState { pos: i32, root: i32, slot: i32 }\n' +
  'fn digit_value(c: u8) -> i32 { return c - 48; }\n' +
  'fn parse_expr(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState { return parse_add(source, arena, state); }\n' +
  'fn parse_digit(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState {\n' +
  ' let node = Expr { kind: 0, a: 0, b: 0, value: digit_value(slice_get(source, state.pos)) };\n' +
  ' let handle = arena_store(arena, state.slot, node);\n' +
  ' return ParserState { pos: state.pos + 1, root: handle, slot: state.slot + 1 };\n' +
  '}\n' +
  'fn parse_group(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState {\n' +
  ' let inner_start = ParserState { pos: state.pos + 1, root: state.root, slot: state.slot };\n' +
  ' let inner = parse_expr(source, arena, inner_start);\n' +
  ' return ParserState { pos: inner.pos + 1, root: inner.root, slot: inner.slot };\n' +
  '}\n' +
  'fn parse_primary(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState {\n' +
  ' return if slice_get(source, state.pos) == 40 { parse_group(source, arena, state) } else { parse_digit(source, arena, state) };\n' +
  '}\n' +
  'fn parse_plus(source: Slice<u8>, arena: Arena, left: ParserState) -> ParserState {\n' +
  ' let right_start = ParserState { pos: left.pos + 1, root: left.root, slot: left.slot };\n' +
  ' let right = parse_primary(source, arena, right_start);\n' +
  ' let root = Expr { kind: 1, a: left.root, b: right.root, value: 0 };\n' +
  ' let handle = arena_store(arena, right.slot, root);\n' +
  ' return ParserState { pos: right.pos, root: handle, slot: right.slot + 1 };\n' +
  '}\n' +
  'fn parse_add(source: Slice<u8>, arena: Arena, state: ParserState) -> ParserState {\n' +
  ' let left = parse_primary(source, arena, state);\n' +
  ' return if left.pos < slice_len(source) { if slice_get(source, left.pos) == 43 { parse_plus(source, arena, left) } else { left } } else { left };\n' +
  '}\n' +
  'fn eval_expr(arena: Arena, index: i32) -> i32 {\n' +
  ' let node = arena_load<Expr>(arena, index);\n' +
  ' return if node.kind == 0 { node.value } else { eval_expr(arena, node.a) + eval_expr(arena, node.b) };\n' +
  '}\n' +
  'fn parse(source: Slice<u8>, arena: Arena) -> ParserState { return parse_expr(source, arena, ParserState { pos: 0, root: 0, slot: 0 }); }\n' +
  'fn main() -> i32 { return 0; }\n'
);
const recursiveParserArtifact = compiler.emitArm32Runtime(recursiveParserProgram.ir);
check(compiler.verifyArm32Runtime(recursiveParserArtifact, recursiveParserProgram.ir), 'recursive parser artifact failed canonical verify');
for (const name of ['parse_expr','parse_group','parse_primary','parse_add','eval_expr']) {
  check(recursiveParserArtifact.recursiveFunctions.includes(name), 'recursive parser classification for ' + name);
}
const recursiveParserSourceDescriptor = 0x21000000;
const recursiveParserSourceData = 0x21000100;
const recursiveParserArenaDescriptor = 0x21000200;
const recursiveParserArenaData = 0x21000300;
const recursiveParserRaw = [49,43,40,50,43,51,41];
const recursiveParserWords = [
  [recursiveParserSourceDescriptor, recursiveParserSourceData],
  [recursiveParserSourceDescriptor + 4, recursiveParserRaw.length],
  [recursiveParserArenaDescriptor, recursiveParserArenaData],
  [recursiveParserArenaDescriptor + 4, 5]
];
const recursiveParserBytes = recursiveParserRaw.map((value,index) => [recursiveParserSourceData + index,value]);
const parsedRecursive = executeFunction(
  recursiveParserArtifact,
  'parse',
  [recursiveParserSourceDescriptor, recursiveParserArenaDescriptor],
  recursiveParserWords,
  recursiveParserBytes
);
equal(parsedRecursive.resultRegisters[0], 7, 'recursive parser consumed source');
equal(parsedRecursive.resultRegisters[1], 4, 'recursive parser root handle');
equal(parsedRecursive.resultRegisters[2], 5, 'recursive parser Arena slot count');
const evaluatedRecursive = executeFunction(
  recursiveParserArtifact,
  'eval_expr',
  [recursiveParserArenaDescriptor, parsedRecursive.resultRegisters[1]],
  Array.from(parsedRecursive.memory.entries()),
  []
);
equal(evaluatedRecursive.result, 6, 'recursive parser/evaluator must compute 1+(2+3)');

console.log('ok - Semnexis ARM32 machine execution');
