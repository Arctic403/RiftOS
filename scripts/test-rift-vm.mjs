import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { executeRiftExecutable, inspectRiftExecutable, prepareRiftExecutable, RIFT_EXEC_FORMAT, RIFT_VM_ABI } from '../src/riftvm.js';

const program={
  format:RIFT_EXEC_FORMAT,
  abi:RIFT_VM_ABI,
  entry:'main',
  imports:['test.echo'],
  constants:[{type:'string',value:'Rift++ executable online'},{type:'u32',value:6},{type:'u32',value:7}],
  functions:{
    main:{params:0,locals:0,code:[{op:'const',index:0},{op:'print'},{op:'const',index:1},{op:'const',index:2},{op:'call',name:'multiply',argc:2},{op:'host',method:'test.echo',argc:1},{op:'print'},{op:'halt'}]},
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

const composite={
  format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',imports:[],
  constants:[{type:'u32',value:7},{type:'u32',value:9},{type:'u32',value:42}],
  functions:{main:{params:0,locals:2,code:[
    {op:'const',index:0},{op:'const',index:1},{op:'make_struct',name:'Point',fields:['x','y']},{op:'store',index:0},
    {op:'load',index:0},{op:'get_field',name:'Point',field:'x'},{op:'print'},
    {op:'const',index:2},{op:'make_enum',name:'Outcome',variant:'Promoted',argc:1},{op:'store',index:1},
    {op:'load',index:1},{op:'enum_is',name:'Outcome',variant:'Promoted'},{op:'print'},
    {op:'load',index:1},{op:'enum_get',name:'Outcome',variant:'Promoted',index:0},{op:'print'},
    {op:'load',index:0},{op:'print'},{op:'load',index:1},{op:'print'},{op:'halt'}
  ]}},limits:{maxSteps:100,maxStack:16,maxCallDepth:2}
};
const compositeOutput=[];
await executeRiftExecutable(composite,{write:value=>compositeOutput.push(value)});
assert.deepEqual(compositeOutput,['7','true','42','Point{x=7,y=9}','Outcome.Promoted(42)']);
assert.equal(inspectRiftExecutable(composite).instructionCount,21);

const vectors={
  format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',imports:[],
  constants:[{type:'u32',value:7},{type:'u32',value:9},{type:'u32',value:0},{type:'u32',value:42},{type:'u32',value:99}],
  functions:{main:{params:0,locals:2,code:[
    {op:'make_vec',capacity:2,count:0},{op:'store',index:0},
    {op:'load',index:0},{op:'const',index:0},{op:'vec_push'},{op:'enum_get',name:'Result',variant:'Ok',index:0},{op:'store',index:0},
    {op:'load',index:0},{op:'const',index:1},{op:'vec_push'},{op:'enum_get',name:'Result',variant:'Ok',index:0},{op:'store',index:0},
    {op:'load',index:0},{op:'vec_len'},{op:'print'},
    {op:'load',index:0},{op:'const',index:2},{op:'vec_get'},{op:'store',index:1},
    {op:'load',index:1},{op:'enum_is',name:'Option',variant:'Some'},{op:'print'},
    {op:'load',index:1},{op:'enum_get',name:'Option',variant:'Some',index:0},{op:'print'},
    {op:'load',index:0},{op:'const',index:1},{op:'const',index:3},{op:'vec_set'},{op:'enum_get',name:'Result',variant:'Ok',index:0},{op:'store',index:0},
    {op:'load',index:0},{op:'const',index:1},{op:'vec_get'},{op:'enum_get',name:'Option',variant:'Some',index:0},{op:'print'},
    {op:'load',index:0},{op:'const',index:4},{op:'vec_push'},{op:'enum_get',name:'Result',variant:'Err',index:0},{op:'print'},
    {op:'load',index:0},{op:'const',index:1},{op:'const',index:4},{op:'add'},{op:'vec_get'},{op:'enum_is',name:'Option',variant:'None'},{op:'print'},
    {op:'load',index:0},{op:'const',index:1},{op:'const',index:4},{op:'add'},{op:'const',index:4},{op:'vec_set'},{op:'enum_get',name:'Result',variant:'Err',index:0},{op:'print'},
    {op:'halt'}
  ]}},limits:{maxSteps:200,maxStack:16,maxCallDepth:2}
};
const vectorOutput=[];
await executeRiftExecutable(vectors,{write:value=>vectorOutput.push(value)});
assert.deepEqual(vectorOutput,['2','true','7','42','vec capacity exceeded','true','vec index out of range']);

const badOpcode=structuredClone(program);badOpcode.functions.main.code[0]={op:'eval'};
assert.throws(()=>prepareRiftExecutable(badOpcode),/unsupported opcode/);
const undeclaredHost=structuredClone(program);undeclaredHost.imports=[];
assert.throws(()=>prepareRiftExecutable(undeclaredHost),/not declared in imports/);
const fakePrepared=structuredClone(program);fakePrepared.instructionCount=1;fakePrepared.functions.main.code[0]={op:'eval'};
await assert.rejects(()=>executeRiftExecutable(fakePrepared),/unsupported opcode/);
const badJump=structuredClone(program);badJump.functions.main.code[0]={op:'jump',target:999};
assert.throws(()=>prepareRiftExecutable(badJump),/target must be an integer/);
const badComposite=structuredClone(composite);badComposite.functions.main.code[2]={op:'make_struct',name:'Point',fields:['x','x']};
assert.throws(()=>prepareRiftExecutable(badComposite),/duplicate x/);
const wrongEnumRead=structuredClone(composite);wrongEnumRead.functions.main.code[14]={op:'enum_get',name:'Outcome',variant:'Rejected',index:0};
await assert.rejects(()=>executeRiftExecutable(wrongEnumRead),/enum_get expected Outcome.Rejected/);
const badVecCapacity=structuredClone(vectors);badVecCapacity.functions.main.code[0]={op:'make_vec',capacity:65,count:0};
assert.throws(()=>prepareRiftExecutable(badVecCapacity),/capacity must be an integer in 1..64/);
const badVecCount=structuredClone(vectors);badVecCount.functions.main.code[0]={op:'make_vec',capacity:1,count:2};
assert.throws(()=>prepareRiftExecutable(badVecCount),/count exceeds vector capacity/);
const hostComposite={format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',imports:['test.echo'],constants:[{type:'u32',value:1}],functions:{main:{params:0,locals:0,code:[{op:'const',index:0},{op:'make_struct',name:'Box',fields:['value']},{op:'host',method:'test.echo',argc:1},{op:'halt'}]}},limits:{maxSteps:20,maxStack:8,maxCallDepth:2}};
await assert.rejects(()=>executeRiftExecutable(hostComposite,{invoke:async()=>null}),/composite values cannot cross the host import boundary/);
const hostVec={format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',imports:['test.echo'],constants:[],functions:{main:{params:0,locals:0,code:[{op:'make_vec',capacity:2,count:0},{op:'host',method:'test.echo',argc:1},{op:'halt'}]}},limits:{maxSteps:20,maxStack:8,maxCallDepth:2}};
await assert.rejects(()=>executeRiftExecutable(hostVec,{invoke:async()=>null}),/composite values cannot cross the host import boundary/);
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
console.log('ok - RiftVM nominal struct/enum and bounded Vec operations stay data-only and cannot cross the host boundary implicitly');
console.log('ok - .rift package can carry a main.rxe executable for the rift-vm RiftRT engine');
