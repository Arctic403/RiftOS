import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { compileRiftPlusPlusCoreV1, inspectRiftPlusPlusCoreV1, parseRiftPlusPlusCoreV1, RiftCoreCompileError } from '../src/riftpp-core.js';
import { executeRiftExecutable, inspectRiftExecutable } from '../src/riftvm.js';

async function execute(source){
  const compiled=compileRiftPlusPlusCoreV1(source),output=[];
  const result=await executeRiftExecutable(compiled.executable,{write:value=>output.push(value)});
  return {compiled,output,result};
}

const source=readFileSync('examples/riftpp/core-v1-hello.riftpp','utf8');
const ast=parseRiftPlusPlusCoreV1(source);
assert.equal(ast.kind,'File');
assert.equal(ast.module,'demo.hello');
assert.deepEqual(ast.functions.map(fn=>fn.name),['multiply','main']);
const base=await execute(source),compiled=base.compiled;
assert.equal(compiled.schema,'riftpp-core-compile-result/1');
assert.equal(compiled.compiler,'0.2.0-bootstrap');
assert.equal(compiled.executable.format,'rift-exec-v1');
assert.equal(compiled.executable.abi,'riftvm-1');
assert.equal(compiled.executable.entry,'main');
assert.equal(compiled.executable.metadata.language,'riftpp/1');
assert.deepEqual(inspectRiftPlusPlusCoreV1(source).functions,['multiply','main']);
assert.equal(inspectRiftExecutable(compiled.executable).functions.length,2);
assert.deepEqual(base.output,['Hello from Rift++ Core V1','42','true']);
assert.equal(base.result.result.type,'unit');

const controlSource=readFileSync('examples/riftpp/core-v1-control-flow.riftpp','utf8');
const control=await execute(controlSource);
assert.deepEqual(control.output,['12','twelve','true','true','inner','12']);
const controlOps=Object.values(control.compiled.executable.functions).flatMap(fn=>fn.code.map(ins=>ins.op));
assert(controlOps.includes('store'),'control flow must lower mutable bindings/assignment to store');
assert(controlOps.includes('jump'),'control flow must lower loops/branches to jump');
assert(controlOps.includes('jump_if_false'),'control flow must lower bool conditions to jump_if_false');

const shortCircuit=`riftpp 1\nmodule proof.short_circuit\nfn main() {\n print(false and (1 / 0 == 0))\n print(true or (1 / 0 == 0))\n}\n`;
const shortResult=await execute(shortCircuit);
assert.deepEqual(shortResult.output,['false','true'],'and/or must skip a RHS that would trap');

const stringCompound=`riftpp 1\nmodule proof.string_compound\nfn main() {\n var text: string = "Rift"\n text += "++"\n print(text)\n}\n`;
assert.deepEqual((await execute(stringCompound)).output,['Rift++']);

const badVersion=source.replace('riftpp 1','riftpp 2');
assert.throws(()=>compileRiftPlusPlusCoreV1(badVersion),error=>error instanceof RiftCoreCompileError&&error.diagnostic.code==='E0103');
const missingModule=source.replace('module demo.hello','');
assert.throws(()=>compileRiftPlusPlusCoreV1(missingModule),error=>error instanceof RiftCoreCompileError&&error.diagnostic.rule==='grammar');
const duplicateLocal=`riftpp 1\nmodule bad.local\nfn main() {\n let x: u32 = 1\n let x: u32 = 2\n print(x)\n}\n`;
assert.throws(()=>compileRiftPlusPlusCoreV1(duplicateLocal),/duplicate local/);
const mismatch=`riftpp 1\nmodule bad.types\nfn main() {\n let x: string = 7\n print(x)\n}\n`;
assert.throws(()=>compileRiftPlusPlusCoreV1(mismatch),/type mismatch/);
const shadowPrelude=`riftpp 1\nmodule bad.prelude\nfn main() {\n let print: u32 = 1\n}\n`;
assert.throws(()=>compileRiftPlusPlusCoreV1(shadowPrelude),/reserved by the bootstrap prelude/);
const immutableAssignment=`riftpp 1\nmodule bad.immutable\nfn main() {\n let x: u32 = 1\n x = 2\n}\n`;
assert.throws(()=>compileRiftPlusPlusCoreV1(immutableAssignment),/cannot assign to immutable binding 'x'/);
const breakOutside=`riftpp 1\nmodule bad.break_case\nfn main() {\n break\n}\n`;
assert.throws(()=>compileRiftPlusPlusCoreV1(breakOutside),/break is only valid inside a loop/);
const continueOutside=`riftpp 1\nmodule bad.continue_case\nfn main() {\n continue\n}\n`;
assert.throws(()=>compileRiftPlusPlusCoreV1(continueOutside),/continue is only valid inside a loop/);
const incompleteReturn=`riftpp 1\nmodule bad.return_path\nfn choose(flag: bool) -> u32 {\n if flag { return 1 }\n}\nfn main() { print(choose(true)) }\n`;
assert.throws(()=>compileRiftPlusPlusCoreV1(incompleteReturn),/does not return on every reachable path/);
const unreachable=`riftpp 1\nmodule bad.unreachable\nfn value() -> u32 {\n return 1\n print("never")\n}\nfn main() { print(value()) }\n`;
assert.throws(()=>compileRiftPlusPlusCoreV1(unreachable),/unreachable statement/);
const unsupported=`riftpp 1\nmodule bad.match_case\nfn main() {\n match true { }\n}\n`;
assert.throws(()=>compileRiftPlusPlusCoreV1(unsupported),/not implemented in the bootstrap slice/);

const sourceCode=readFileSync('src/riftpp-core.js','utf8');
assert(!/\beval\s*\(/.test(sourceCode));
assert(!/new\s+Function\b/.test(sourceCode));
assert(!/ProcessBuilder|Runtime\.getRuntime|child_process/.test(sourceCode));
console.log('ok - Rift++ Core V1 source parses, type-checks, lowers to rift-exec-v1 and executes on RiftVM');
console.log('ok - Control Flow V1 executes var/assignment, scopes, if/else, while, break/continue and short-circuit and/or');
console.log('ok - mutability, loop-control, reachability and all-path return diagnostics fail closed');
console.log('ok - unsupported Core features remain explicit feature-gate failures');
