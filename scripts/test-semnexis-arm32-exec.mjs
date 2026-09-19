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
function executeArm32(artifact, stepLimit = 200000) {
  const base = 0x00010000;
  const regs = new Uint32Array(16);
  const flags = {N:false,Z:false,C:false,V:false};
  const memory = new Map();
  regs[13] = 0x80000000;
  regs[15] = artifact.entry >>> 0;

  const load = address => memory.get(u32(address)) ?? 0;
  const store = (address, value) => memory.set(u32(address), u32(value));
  const setNZ = value => {
    flags.N = (u32(value) & 0x80000000) !== 0;
    flags.Z = u32(value) === 0;
  };

  for (let step = 0; step < stepLimit; step += 1) {
    const pc = regs[15] >>> 0;
    const word = readImageWord(artifact.bytes, base, pc);
    const cond = word >>> 28;
    if (!conditionPasses(cond, flags)) {
      regs[15] = u32(pc + 4);
      continue;
    }

    if (word === 0xEF000000) {
      if (regs[7] !== 1) throw new Error('ARM executor unsupported syscall ' + regs[7]);
      return {result:s32(regs[0]), exitStatus:regs[0] & 0xFF, steps:step + 1};
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
      if (!immediateOffset || !pre || byte || writeBack) throw new Error('ARM executor unsupported load/store form');
      const rn = (word >>> 16) & 0xF;
      const rd = (word >>> 12) & 0xF;
      const offset = word & 0xFFF;
      const address = u32(regs[rn] + (up ? offset : -offset));
      if (loadBit) regs[rd] = load(address);
      else store(address, regs[rd]);
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

console.log('ok - Semnexis ARM32 machine execution');
