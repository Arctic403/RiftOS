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
assert.throws(()=>prepareRiftExecutable(' '.repeat(8*1024*1024+1)),/executable JSON exceeds 8388608 UTF-8 bytes/);
const constantBudgetBomb={format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',imports:[],constants:Array.from({length:65},()=>({type:'string',value:'x'.repeat(65536)})),functions:{main:{params:0,locals:0,code:[{op:'halt'}]}}};
assert.throws(()=>prepareRiftExecutable(constantBudgetBomb),/constant strings exceed 4194304 UTF-8 bytes/);
const dottedShare={format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',imports:['share.text'],constants:[],functions:{main:{params:0,locals:0,code:[{op:'halt'}]}}};
assert.deepEqual(prepareRiftExecutable(dottedShare).imports,['share.text']);
const bareShare={...dottedShare,imports:['share']};
assert.throws(()=>prepareRiftExecutable(bareShare),/invalid import: share/);

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
  constants:[{type:'u32',value:7},{type:'u32',value:9},{type:'u32',value:0},{type:'u32',value:42},{type:'u32',value:99},{type:'u32',value:1}],
  functions:{main:{params:0,locals:2,code:[
    {op:'make_vec',capacity:2,count:0},{op:'store',index:0},
    {op:'load',index:0},{op:'const',index:0},{op:'vec_push'},{op:'enum_get',name:'Result',variant:'Ok',index:0},{op:'store',index:0},
    {op:'load',index:0},{op:'const',index:1},{op:'vec_push'},{op:'enum_get',name:'Result',variant:'Ok',index:0},{op:'store',index:0},
    {op:'load',index:0},{op:'vec_len'},{op:'print'},
    {op:'load',index:0},{op:'const',index:2},{op:'vec_get'},{op:'store',index:1},
    {op:'load',index:1},{op:'enum_is',name:'Option',variant:'Some'},{op:'print'},
    {op:'load',index:1},{op:'enum_get',name:'Option',variant:'Some',index:0},{op:'print'},
    {op:'load',index:0},{op:'const',index:5},{op:'const',index:3},{op:'vec_set'},{op:'enum_get',name:'Result',variant:'Ok',index:0},{op:'store',index:0},
    {op:'load',index:0},{op:'const',index:5},{op:'vec_get'},{op:'enum_get',name:'Option',variant:'Some',index:0},{op:'print'},
    {op:'load',index:0},{op:'const',index:4},{op:'vec_push'},{op:'enum_get',name:'Result',variant:'Err',index:0},{op:'print'},
    {op:'load',index:0},{op:'const',index:1},{op:'const',index:4},{op:'add'},{op:'vec_get'},{op:'enum_is',name:'Option',variant:'None'},{op:'print'},
    {op:'load',index:0},{op:'const',index:1},{op:'const',index:4},{op:'add'},{op:'const',index:4},{op:'vec_set'},{op:'enum_get',name:'Result',variant:'Err',index:0},{op:'print'},
    {op:'halt'}
  ]}},limits:{maxSteps:200,maxStack:16,maxCallDepth:2}
};
const vectorOutput=[];
await executeRiftExecutable(vectors,{write:value=>vectorOutput.push(value)});
assert.deepEqual(vectorOutput,['2','true','7','42','vec capacity exceeded','true','vec index out of range']);

const stringOps={format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',imports:[],constants:[{type:'string',value:'retrun 7'},{type:'string',value:'retrun'},{type:'u32',value:0},{type:'u32',value:6},{type:'string',value:'return'}],functions:{main:{params:0,locals:0,code:[
  {op:'const',index:0},{op:'string_len'},{op:'print'},
  {op:'const',index:0},{op:'const',index:1},{op:'const',index:2},{op:'string_find'},{op:'enum_get',name:'Option',variant:'Some',index:0},{op:'print'},
  {op:'const',index:0},{op:'const',index:2},{op:'const',index:3},{op:'string_slice'},{op:'print'},
  {op:'const',index:0},{op:'const',index:2},{op:'const',index:3},{op:'const',index:4},{op:'string_replace'},{op:'print'},{op:'halt'}
]}},limits:{maxSteps:50,maxStack:8,maxCallDepth:2}};
const stringOutput=[];await executeRiftExecutable(stringOps,{write:value=>stringOutput.push(value)});assert.deepEqual(stringOutput,['8','0','retrun','return 7']);
const badStringRange=structuredClone(stringOps);badStringRange.constants[3]={type:'u32',value:99};await assert.rejects(()=>executeRiftExecutable(badStringRange,{write:()=>{}}),/string_slice range is out of bounds/);

const numericParameters={
  format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',imports:[],
  constants:[{type:'f64',value:0.5},{type:'f64',value:-1.0},{type:'f64',value:2.0},{type:'f64',value:0.25}],
  functions:{main:{params:0,locals:0,code:[
    {op:'const',index:0},{op:'const',index:1},{op:'const',index:2},{op:'const',index:3},{op:'make_vec',capacity:4,count:4},
    {op:'dup'},{op:'value_sha256'},{op:'print'},{op:'value_sha256'},{op:'print'},{op:'halt'}
  ]}},limits:{maxSteps:50,maxStack:8,maxCallDepth:2}
};
const numericParameterOutput=[];await executeRiftExecutable(numericParameters,{write:value=>numericParameterOutput.push(value)});
assert.equal(numericParameterOutput.length,2);assert.match(numericParameterOutput[0],/^[a-f0-9]{64}$/);assert.equal(numericParameterOutput[0],numericParameterOutput[1],'value_sha256 must be deterministic for the same bounded VM value');
const badF64Null=structuredClone(numericParameters);badF64Null.constants[0]={type:'f64',value:null};assert.throws(()=>prepareRiftExecutable(badF64Null),/f64 must be a finite JSON number/);
const badF64Infinity=structuredClone(numericParameters);badF64Infinity.constants[0]={type:'f64',value:Infinity};assert.throws(()=>prepareRiftExecutable(badF64Infinity),/f64 must be a finite JSON number/);
const negativeZero=structuredClone(numericParameters);negativeZero.constants[0]={type:'f64',value:-0};const preparedNegativeZero=prepareRiftExecutable(negativeZero);assert.equal(Object.is(preparedNegativeZero.constants[0].value,-0),false,'Gate 6A must canonicalize f64 negative zero before hashing/checkpointing');assert.equal(preparedNegativeZero.constants[0].value,0);
const f64StateSchema=JSON.stringify({k:'p',t:'f64'}),f64StateProgram={format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',imports:['state.load'],constants:[{type:'string',value:'parameter'},{type:'f64',value:0.0}],functions:{main:{params:0,locals:0,code:[{op:'const',index:0},{op:'const',index:1},{op:'state_load',schema:f64StateSchema},{op:'print'},{op:'halt'}]}},limits:{maxSteps:20,maxStack:4,maxCallDepth:2}};
const f64StateOutput=[];await executeRiftExecutable(f64StateProgram,{write:value=>f64StateOutput.push(value),invoke:async()=>JSON.stringify({format:'riftvm-state-v1',schema:f64StateSchema,value:{type:'f64',value:1.5}})});assert.deepEqual(f64StateOutput,['1.5']);
await assert.rejects(()=>executeRiftExecutable(f64StateProgram,{invoke:async()=>JSON.stringify({format:'riftvm-state-v1',schema:f64StateSchema,value:{type:'f64',value:null}})}),/state f64 must be a finite JSON number/);

const stateSchema=JSON.stringify({k:'s',n:'BrainState',f:[['value',{k:'p',t:'u32'}]]});
const stateProgram={format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',imports:['state.load','state.save','state.remove'],constants:[{type:'string',value:'brain'},{type:'u32',value:42},{type:'u32',value:0}],functions:{main:{params:0,locals:2,code:[{op:'const',index:0},{op:'const',index:1},{op:'make_struct',name:'BrainState',fields:['value']},{op:'state_save',schema:stateSchema},{op:'print'},{op:'const',index:0},{op:'const',index:2},{op:'make_struct',name:'BrainState',fields:['value']},{op:'state_load',schema:stateSchema},{op:'store',index:0},{op:'load',index:0},{op:'get_field',name:'BrainState',field:'value'},{op:'print'},{op:'const',index:0},{op:'state_remove'},{op:'print'},{op:'const',index:0},{op:'const',index:2},{op:'make_struct',name:'BrainState',fields:['value']},{op:'state_load',schema:stateSchema},{op:'store',index:1},{op:'load',index:1},{op:'get_field',name:'BrainState',field:'value'},{op:'print'},{op:'halt'}]}},limits:{maxSteps:100,maxStack:16,maxCallDepth:2}};
const stateStore=new Map(),stateOutput=[];await executeRiftExecutable(stateProgram,{write:value=>stateOutput.push(value),invoke:async(method,args)=>{if(method==='state.save'){assert.equal(typeof args[1],'string');stateStore.set(args[0],args[1]);return true;}if(method==='state.load')return stateStore.get(args[0])??null;if(method==='state.remove')return stateStore.delete(args[0]);throw new Error(`unexpected ${method}`);}});assert.deepEqual(stateOutput,['true','42','true','0']);
const missingStateImport=structuredClone(stateProgram);missingStateImport.imports=missingStateImport.imports.filter(item=>item!=='state.save');assert.throws(()=>prepareRiftExecutable(missingStateImport),/state_save requires declared import state.save/);
const corruptState=structuredClone(stateProgram);corruptState.functions.main.code=stateProgram.functions.main.code.slice(5,14);corruptState.functions.main.code.push({op:'halt'});await assert.rejects(()=>executeRiftExecutable(corruptState,{invoke:async method=>method==='state.load'?'not-json':null}),/invalid state payload JSON/);
const wrongSchema=JSON.stringify({k:'s',n:'Wrong',f:[['value',{k:'p',t:'u32'}]]}),wrongSchemaPayload=JSON.stringify({format:'riftvm-state-v1',schema:wrongSchema,value:{type:'struct',name:'BrainState',fields:{value:{type:'u32',value:'42'}}}});await assert.rejects(()=>executeRiftExecutable(corruptState,{invoke:async method=>method==='state.load'?wrongSchemaPayload:null}),/state schema mismatch/);
const wrongShapePayload=JSON.stringify({format:'riftvm-state-v1',schema:stateSchema,value:{type:'struct',name:'BrainState',fields:{other:{type:'u32',value:'42'}}}});await assert.rejects(()=>executeRiftExecutable(corruptState,{invoke:async method=>method==='state.load'?wrongShapePayload:null}),/missing field value|field count mismatch/);
const nonCanonicalStateProgram=structuredClone(stateProgram);nonCanonicalStateProgram.functions.main.code[3].schema=JSON.stringify(JSON.parse(stateSchema),null,2);assert.throws(()=>prepareRiftExecutable(nonCanonicalStateProgram),/schema must use canonical descriptor JSON/);
await assert.rejects(()=>executeRiftExecutable(corruptState,{invoke:async method=>method==='state.load'?'x'.repeat(65537):null}),/state payload exceeds 65536 UTF-8 bytes/);

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
const deepCompositeCode=[{op:'const',index:0}];for(let i=0;i<33;i++)deepCompositeCode.push({op:'make_vec',capacity:1,count:1});deepCompositeCode.push({op:'halt'});
const deepComposite={format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',imports:[],constants:[{type:'u32',value:1}],functions:{main:{params:0,locals:0,code:deepCompositeCode}},limits:{maxSteps:100,maxStack:4,maxCallDepth:2}};
await assert.rejects(()=>executeRiftExecutable(deepComposite),/composite depth limit 32/);
const displayBombCode=Array.from({length:32},()=>({op:'const',index:0}));displayBombCode.push({op:'make_vec',capacity:32,count:32},{op:'print'},{op:'halt'});
const displayBomb={format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',imports:[],constants:[{type:'string',value:'x'.repeat(4096)}],functions:{main:{params:0,locals:0,code:displayBombCode}},limits:{maxSteps:100,maxStack:64,maxCallDepth:2}};
await assert.rejects(()=>executeRiftExecutable(displayBomb,{write:()=>{}}),/display value exceeds 65536 UTF-8 bytes/);
const publicBombCode=Array.from({length:64},()=>({op:'const',index:0}));publicBombCode.push({op:'make_vec',capacity:64,count:64});for(let i=0;i<63;i++)publicBombCode.push({op:'dup'});publicBombCode.push({op:'make_vec',capacity:64,count:64},{op:'halt'});
const publicBomb={format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',imports:[],constants:[{type:'unit'}],functions:{main:{params:0,locals:0,code:publicBombCode}},limits:{maxSteps:200,maxStack:128,maxCallDepth:2}};
await assert.rejects(()=>executeRiftExecutable(publicBomb),/public result exceeds 4096 values/);
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
console.log('ok - RiftVM Gate 6A accepts only finite JSON f64 values and computes deterministic bounded value_sha256 identities without host imports');
console.log('ok - RiftVM Gate 6D.2 bounded string locate/slice/replace primitives execute without host authority');
console.log('ok - RiftVM Gate 5 state opcodes validate canonical type descriptors and reject undeclared/corrupt/schema/shape-mismatched state');
console.log('ok - .rift package can carry a main.rxe executable for the rift-vm RiftRT engine');
