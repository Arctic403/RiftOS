import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { executeRiftExecutable, inspectRiftExecutable, prepareRiftExecutable, RIFT_EXEC_FORMAT, RIFT_VM_ABI } from '../src/riftvm.js';

const program={
  format:RIFT_EXEC_FORMAT,
  abi:RIFT_VM_ABI,
  entry:'main',
  imports:['test.echo'],
  constants:[
    {type:'string',value:'Rift++ executable online'},
    {type:'u32',value:6},
    {type:'u32',value:7}
  ],
  functions:{
    main:{params:0,locals:0,code:[
      {op:'const',index:0},{op:'print'},
      {op:'const',index:1},{op:'const',index:2},{op:'call',name:'multiply',argc:2},{op:'host',method:'test.echo',argc:1},{op:'print'},
      {op:'halt'}
    ]},
    multiply:{params:2,locals:2,code:[{op:'load',index:0},{op:'load',index:1},{op:'mul'},{op:'ret'}]}
  },
  limits:{maxSteps:1000,maxStack:64,maxCallDepth:8}
};
const output=[];
const result=await executeRiftExecutable(program,{write:value=>output.push(value),invoke:async(method,args)=>{assert.equal(method,'test.echo');return args[0];}});
assert.deepEqual(output,['Rift++ executable online','42']);
assert.equal(result.result.type,'unit');
assert.equal(result.halted,true);
assert.equal(inspectRiftExecutable(program).instructionCount,12);
assert.equal(prepareRiftExecutable(JSON.stringify(program)).entry,'main');

const badOpcode=structuredClone(program);badOpcode.functions.main.code[0]={op:'eval'};
assert.throws(()=>prepareRiftExecutable(badOpcode),/unsupported opcode/);
const undeclaredHost=structuredClone(program);undeclaredHost.imports=[];
assert.throws(()=>prepareRiftExecutable(undeclaredHost),/not declared in imports/);
const fakePrepared=structuredClone(program);fakePrepared.instructionCount=1;fakePrepared.functions.main.code[0]={op:'eval'};
await assert.rejects(()=>executeRiftExecutable(fakePrepared),/unsupported opcode/);
const badJump=structuredClone(program);badJump.functions.main.code[0]={op:'jump',target:999};
assert.throws(()=>prepareRiftExecutable(badJump),/target must be an integer/);
const overflow={format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',constants:[{type:'u32',value:'4294967295'},{type:'u32',value:1}],functions:{main:{params:0,locals:0,code:[{op:'const',index:0},{op:'const',index:1},{op:'add'},{op:'halt'}]}},limits:{maxSteps:20,maxStack:8,maxCallDepth:2}};
await assert.rejects(()=>executeRiftExecutable(overflow),/u32 overflow/);
const loop={format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',constants:[],functions:{main:{params:0,locals:0,code:[{op:'jump',target:0}]}},limits:{maxSteps:25,maxStack:8,maxCallDepth:2}};
await assert.rejects(()=>executeRiftExecutable(loop),/step limit exceeded/);

const source=readFileSync('src/riftvm.js','utf8');
assert(!/\beval\s*\(/.test(source));
assert(!/new\s+Function\b/.test(source));
assert(!/ProcessBuilder|Runtime\.getRuntime|child_process/.test(source));
const packageFixture=JSON.parse(readFileSync('examples/riftpp/hello-rift-executable.rift','utf8'));
assert.equal(packageFixture.format,'rift-app-v1');
assert.equal(JSON.parse(packageFixture.files['riftrt.json']).engine,'rift-vm');
assert.equal(inspectRiftExecutable(packageFixture.files['main.rxe']).format,RIFT_EXEC_FORMAT);
console.log('ok - RiftVM validates and executes bounded rift-exec-v1 programs without eval/native shell');
console.log('ok - .rift package can carry a main.rxe executable for the rift-vm RiftRT engine');
