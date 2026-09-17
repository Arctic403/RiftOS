export const RIFT_EXEC_FORMAT='rift-exec-v1';
export const RIFT_VM_ABI='riftvm-1';

const NAME=/^[A-Za-z_][A-Za-z0-9_.:$-]{0,95}$/;
const HOST_METHOD=/^[A-Za-z][A-Za-z0-9_-]*(?:\.[A-Za-z][A-Za-z0-9_-]*){1,3}$/;
const POISON_NAMES=new Set(['__proto__','prototype','constructor']);
const OPS=new Set(['const','load','store','pop','dup','add','sub','mul','div','mod','neg','eq','ne','lt','le','gt','ge','not','concat','make_struct','get_field','make_enum','enum_is','enum_get','make_vec','vec_len','vec_get','vec_push','vec_set','jump','jump_if_false','call','host','print','ret','halt']);
const DEFAULT_LIMITS=Object.freeze({maxSteps:100000,maxStack:1024,maxCallDepth:32});
const HARD_LIMITS=Object.freeze({maxFunctions:256,maxImports:64,maxConstants:4096,maxInstructions:100000,maxInstructionsPerFunction:65536,maxParams:64,maxLocals:512,maxCompositeItems:64,maxVecCapacity:64,maxSteps:1000000,maxStack:4096,maxCallDepth:64,maxStringBytes:65536});
const INT_BOUNDS=Object.freeze({u32:[0n,4294967295n],s32:[-2147483648n,2147483647n]});
const UNIT=Object.freeze({type:'unit',value:null});
const PREPARED=Symbol('riftvm.prepared');
const encoder=new TextEncoder();

function fail(message){throw new Error(`RiftVM: ${message}`);}
function plain(value){return !!value&&typeof value==='object'&&!Array.isArray(value);}
function safeName(value,label){const name=String(value||'');if(!NAME.test(name)||POISON_NAMES.has(name))fail(`${label} is invalid: ${name||'(empty)'}`);return name;}
function integer(value,label,min,max){const n=Number(value);if(!Number.isInteger(n)||n<min||n>max)fail(`${label} must be an integer in ${min}..${max}`);return n;}
function stringBytes(value,label,max=HARD_LIMITS.maxStringBytes){const text=String(value);if(encoder.encode(text).byteLength>max)fail(`${label} exceeds ${max} UTF-8 bytes`);return text;}
function parseIntegerConstant(value,label){
  try{
    if(typeof value==='bigint')return value;
    if(typeof value==='number'){if(!Number.isSafeInteger(value))fail(`${label} numeric literal is not a safe integer; encode it as a decimal string`);return BigInt(value);}
    if(typeof value==='string'&&/^-?(0|[1-9][0-9]*)$/.test(value))return BigInt(value);
  }catch(_){ }
  fail(`${label} must be an integer or decimal integer string`);
}
function normalizeConstant(raw,index){
  if(!plain(raw))fail(`constant ${index} must be an object`);
  const type=String(raw.type||'');
  if(type==='unit')return UNIT;
  if(type==='bool'){if(typeof raw.value!=='boolean')fail(`constant ${index} bool value is invalid`);return Object.freeze({type,value:raw.value});}
  if(type==='string'){if(typeof raw.value!=='string')fail(`constant ${index} string value is invalid`);return Object.freeze({type,value:stringBytes(raw.value,`constant ${index}`)});}
  if(type==='f64'){const value=Number(raw.value);if(!Number.isFinite(value))fail(`constant ${index} f64 must be finite`);return Object.freeze({type,value});}
  if(type==='u32'||type==='s32'){
    const value=parseIntegerConstant(raw.value,`constant ${index} ${type}`),bounds=INT_BOUNDS[type];
    if(value<bounds[0]||value>bounds[1])fail(`constant ${index} ${type} is out of range`);
    return Object.freeze({type,value});
  }
  fail(`constant ${index} uses unsupported type: ${type}`);
}
function normalizeLimits(raw){
  const limits=plain(raw)?raw:{};
  return Object.freeze({
    maxSteps:integer(limits.maxSteps??DEFAULT_LIMITS.maxSteps,'limits.maxSteps',1,HARD_LIMITS.maxSteps),
    maxStack:integer(limits.maxStack??DEFAULT_LIMITS.maxStack,'limits.maxStack',1,HARD_LIMITS.maxStack),
    maxCallDepth:integer(limits.maxCallDepth??DEFAULT_LIMITS.maxCallDepth,'limits.maxCallDepth',1,HARD_LIMITS.maxCallDepth)
  });
}
function normalizeFieldList(raw,where){
  if(!Array.isArray(raw)||raw.length<1||raw.length>HARD_LIMITS.maxCompositeItems)fail(`${where}.fields must contain 1..${HARD_LIMITS.maxCompositeItems} names`);
  const seen=new Set(),fields=[];
  for(let i=0;i<raw.length;i++){const name=safeName(raw[i],`${where}.fields[${i}]`);if(seen.has(name))fail(`${where}.fields contains duplicate ${name}`);seen.add(name);fields.push(name);}
  return Object.freeze(fields);
}
function normalizeInstruction(raw,where,ctx){
  if(!plain(raw))fail(`${where} instruction must be an object`);
  const op=String(raw.op||'');if(!OPS.has(op))fail(`${where} has unsupported opcode: ${op||'(empty)'}`);
  if(op==='const')return Object.freeze({op,index:integer(raw.index,`${where}.index`,0,ctx.constantCount-1)});
  if(op==='load'||op==='store')return Object.freeze({op,index:integer(raw.index,`${where}.index`,0,ctx.locals-1)});
  if(op==='make_struct')return Object.freeze({op,name:safeName(raw.name,`${where}.name`),fields:normalizeFieldList(raw.fields,where)});
  if(op==='get_field')return Object.freeze({op,name:safeName(raw.name,`${where}.name`),field:safeName(raw.field,`${where}.field`)});
  if(op==='make_enum')return Object.freeze({op,name:safeName(raw.name,`${where}.name`),variant:safeName(raw.variant,`${where}.variant`),argc:integer(raw.argc??0,`${where}.argc`,0,HARD_LIMITS.maxCompositeItems)});
  if(op==='enum_is')return Object.freeze({op,name:safeName(raw.name,`${where}.name`),variant:safeName(raw.variant,`${where}.variant`)});
  if(op==='enum_get')return Object.freeze({op,name:safeName(raw.name,`${where}.name`),variant:safeName(raw.variant,`${where}.variant`),index:integer(raw.index,`${where}.index`,0,HARD_LIMITS.maxCompositeItems-1)});
  if(op==='make_vec'){const capacity=integer(raw.capacity,`${where}.capacity`,1,HARD_LIMITS.maxVecCapacity),count=integer(raw.count??0,`${where}.count`,0,HARD_LIMITS.maxVecCapacity);if(count>capacity)fail(`${where}.count exceeds vector capacity`);return Object.freeze({op,capacity,count});}
  if(op==='vec_len'||op==='vec_get'||op==='vec_push'||op==='vec_set')return Object.freeze({op});
  if(op==='jump'||op==='jump_if_false')return Object.freeze({op,target:integer(raw.target,`${where}.target`,0,ctx.codeLength-1)});
  if(op==='call'){
    const name=safeName(raw.name,`${where}.name`),argc=integer(raw.argc??0,`${where}.argc`,0,HARD_LIMITS.maxParams),target=ctx.functions[name];
    if(!target)fail(`${where} calls unknown function: ${name}`);if(argc!==target.params)fail(`${where} argc ${argc} does not match ${name} params ${target.params}`);
    return Object.freeze({op,name,argc});
  }
  if(op==='host'){
    const method=String(raw.method||'');if(!HOST_METHOD.test(method)||!ctx.imports.has(method))fail(`${where} host method is not declared in imports: ${method||'(empty)'}`);
    return Object.freeze({op,method,argc:integer(raw.argc??0,`${where}.argc`,0,16)});
  }
  return Object.freeze({op});
}
function parseInput(raw){
  if(typeof raw==='string'){
    const text=raw.charCodeAt(0)===0xfeff?raw.slice(1):raw;
    try{return JSON.parse(text);}catch(error){fail(`invalid executable JSON: ${error.message}`);}
  }
  return raw;
}

export function prepareRiftExecutable(raw){
  const source=parseInput(raw);if(!plain(source))fail('executable root must be an object');
  if(source.format!==RIFT_EXEC_FORMAT)fail(`unsupported format: ${source.format||'(missing)'}`);
  if(source.abi!==RIFT_VM_ABI)fail(`unsupported ABI: ${source.abi||'(missing)'}`);
  const entry=safeName(source.entry,'entry');
  const rawImports=Array.isArray(source.imports)?source.imports:[];if(rawImports.length>HARD_LIMITS.maxImports)fail(`too many imports; max ${HARD_LIMITS.maxImports}`);
  const imports=new Set();for(const item of rawImports){const method=String(item||'');if(!HOST_METHOD.test(method))fail(`invalid import: ${method||'(empty)'}`);if(imports.has(method))fail(`duplicate import: ${method}`);imports.add(method);}
  const rawConstants=Array.isArray(source.constants)?source.constants:[];if(rawConstants.length>HARD_LIMITS.maxConstants)fail(`too many constants; max ${HARD_LIMITS.maxConstants}`);
  const constants=Object.freeze(rawConstants.map(normalizeConstant));
  const rawFunctions=source.functions;if(!plain(rawFunctions))fail('functions must be an object');
  const names=Object.keys(rawFunctions);if(!names.length||names.length>HARD_LIMITS.maxFunctions)fail(`function count must be 1..${HARD_LIMITS.maxFunctions}`);
  const functionMeta=Object.create(null);let totalInstructions=0;
  for(const name of names){safeName(name,'function name');const rawFn=rawFunctions[name];if(!plain(rawFn))fail(`function ${name} must be an object`);const params=integer(rawFn.params??0,`${name}.params`,0,HARD_LIMITS.maxParams),locals=integer(rawFn.locals??params,`${name}.locals`,params,HARD_LIMITS.maxLocals);if(!Array.isArray(rawFn.code)||!rawFn.code.length)fail(`function ${name} code must be a non-empty array`);if(rawFn.code.length>HARD_LIMITS.maxInstructionsPerFunction)fail(`function ${name} has too many instructions`);totalInstructions+=rawFn.code.length;if(totalInstructions>HARD_LIMITS.maxInstructions)fail(`program exceeds ${HARD_LIMITS.maxInstructions} instructions`);functionMeta[name]={params,locals,rawCode:rawFn.code};}
  if(!functionMeta[entry])fail(`entry function not found: ${entry}`);if(functionMeta[entry].params!==0)fail('entry function must have zero parameters');
  const functions=Object.create(null);
  for(const name of names){const meta=functionMeta[name],ctx={constantCount:constants.length,locals:meta.locals,codeLength:meta.rawCode.length,functions:functionMeta,imports};const code=Object.freeze(meta.rawCode.map((instruction,index)=>normalizeInstruction(instruction,`${name}[${index}]`,ctx)));functions[name]=Object.freeze({params:meta.params,locals:meta.locals,code});}
  return Object.freeze({[PREPARED]:true,format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry,imports:Object.freeze([...imports]),constants,functions:Object.freeze(functions),limits:normalizeLimits(source.limits),instructionCount:totalInstructions});
}

function publicValue(value){
  if(!value||value.type==='unit')return{type:'unit'};
  if(value.type==='u32'||value.type==='s32')return{type:value.type,value:value.value.toString()};
  if(value.type==='struct'){const fields={};for(const [key,item] of Object.entries(value.fields))fields[key]=publicValue(item);return{type:'struct',name:value.name,fields};}
  if(value.type==='enum')return{type:'enum',name:value.name,variant:value.variant,values:value.values.map(publicValue)};
  if(value.type==='vec')return{type:'vec',capacity:value.capacity,items:value.items.map(publicValue)};
  return{type:value.type,value:value.value};
}
function valueToPublic(value){return publicValue(value);}
function isCompositeValue(value){return value?.type==='struct'||value?.type==='enum'||value?.type==='vec';}
function valueToHost(value){if(!value||value.type==='unit')return null;if(isCompositeValue(value))fail('composite values cannot cross the host import boundary');if(value.type==='u32'||value.type==='s32')return Number(value.value);return value.value;}
function hostToValue(raw){if(raw===null||raw===undefined)return UNIT;if(typeof raw==='boolean')return Object.freeze({type:'bool',value:raw});if(typeof raw==='string')return Object.freeze({type:'string',value:stringBytes(raw,'host string')});if(typeof raw==='number'){if(!Number.isFinite(raw))fail('host returned a non-finite number');return Object.freeze({type:'f64',value:raw});}const text=JSON.stringify(raw);return Object.freeze({type:'string',value:stringBytes(text,'host JSON result')});}
function displayValue(value){
  if(!value||value.type==='unit')return'unit';
  if(value.type==='u32'||value.type==='s32')return value.value.toString();
  if(value.type==='struct')return `${value.name}{${Object.entries(value.fields).map(([key,item])=>`${key}=${displayValue(item)}`).join(',')}}`;
  if(value.type==='enum')return `${value.name}.${value.variant}${value.values.length?`(${value.values.map(displayValue).join(',')})`:''}`;
  if(value.type==='vec')return `[${value.items.map(displayValue).join(',')}]`;
  return String(value.value);
}
function sameType(a,b,op){if(!a||!b||a.type!==b.type)fail(`${op} requires operands of the same type`);if(isCompositeValue(a)||isCompositeValue(b))fail(`${op} does not support composite values`);}
function checkedInteger(type,value,op){const bounds=INT_BOUNDS[type];if(value<bounds[0]||value>bounds[1])fail(`${op} ${type} overflow`);return Object.freeze({type,value});}
function numericBinary(op,a,b){
  sameType(a,b,op);if(a.type==='f64'){let value;if(op==='add')value=a.value+b.value;else if(op==='sub')value=a.value-b.value;else if(op==='mul')value=a.value*b.value;else if(op==='div'){if(b.value===0)fail('division by zero');value=a.value/b.value;}else if(op==='mod'){if(b.value===0)fail('modulo by zero');value=a.value%b.value;}else fail(`${op} is not numeric`);if(!Number.isFinite(value))fail(`${op} produced non-finite f64`);return Object.freeze({type:'f64',value});}
  if(a.type!=='u32'&&a.type!=='s32')fail(`${op} requires numeric operands`);let value;if(op==='add')value=a.value+b.value;else if(op==='sub')value=a.value-b.value;else if(op==='mul')value=a.value*b.value;else if(op==='div'){if(b.value===0n)fail('division by zero');value=a.value/b.value;}else if(op==='mod'){if(b.value===0n)fail('modulo by zero');value=a.value%b.value;}else fail(`${op} is not numeric`);return checkedInteger(a.type,value,op);
}
function compare(op,a,b){
  if(isCompositeValue(a)||isCompositeValue(b))fail(`${op} does not support composite values`);
  if(op==='eq'||op==='ne'){const equal=a?.type===b?.type&&(a?.type==='unit'||a?.value===b?.value);return Object.freeze({type:'bool',value:op==='eq'?equal:!equal});}
  sameType(a,b,op);if(!['u32','s32','f64','string'].includes(a.type))fail(`${op} requires ordered operands`);let value;if(op==='lt')value=a.value<b.value;else if(op==='le')value=a.value<=b.value;else if(op==='gt')value=a.value>b.value;else value=a.value>=b.value;return Object.freeze({type:'bool',value});
}

export async function executeRiftExecutable(raw,host={},options={}){
  const program=raw?.[PREPARED]===true?raw:prepareRiftExecutable(raw);
  const limits=Object.freeze({maxSteps:Math.min(program.limits.maxSteps,Number.isInteger(options.maxSteps)?options.maxSteps:program.limits.maxSteps),maxStack:Math.min(program.limits.maxStack,Number.isInteger(options.maxStack)?options.maxStack:program.limits.maxStack),maxCallDepth:Math.min(program.limits.maxCallDepth,Number.isInteger(options.maxCallDepth)?options.maxCallDepth:program.limits.maxCallDepth)});
  const frames=[];let steps=0,prints=0,halted=false,result=UNIT;
  const push=(frame,value)=>{if(frame.stack.length>=limits.maxStack)fail(`stack limit exceeded in ${frame.name}`);frame.stack.push(value);};
  const pop=(frame,op)=>{if(!frame.stack.length)fail(`${op} stack underflow in ${frame.name}`);return frame.stack.pop();};
  const makeFrame=(name,args=[])=>{if(frames.length>=limits.maxCallDepth)fail('call depth limit exceeded');const fn=program.functions[name],locals=Array.from({length:fn.locals},()=>UNIT);for(let i=0;i<args.length;i++)locals[i]=args[i];return{name,fn,locals,stack:[],ip:0};};
  frames.push(makeFrame(program.entry));
  const yieldEvery=Math.max(32,Math.min(8192,Number(options.yieldEvery)||1024));
  while(frames.length&&!halted){
    if(options.shouldCancel?.())fail('execution cancelled');if(++steps>limits.maxSteps)fail(`step limit exceeded (${limits.maxSteps})`);if(steps%yieldEvery===0){if(host.yield)await host.yield();else await Promise.resolve();}
    const frame=frames[frames.length-1];if(frame.ip<0||frame.ip>=frame.fn.code.length)fail(`instruction pointer escaped ${frame.name}`);const ins=frame.fn.code[frame.ip++];
    switch(ins.op){
      case'const':push(frame,program.constants[ins.index]);break;
      case'load':push(frame,frame.locals[ins.index]);break;
      case'store':frame.locals[ins.index]=pop(frame,'store');break;
      case'pop':pop(frame,'pop');break;
      case'dup':{const value=pop(frame,'dup');push(frame,value);push(frame,value);break;}
      case'add':case'sub':case'mul':case'div':case'mod':{const b=pop(frame,ins.op),a=pop(frame,ins.op);push(frame,numericBinary(ins.op,a,b));break;}
      case'neg':{const a=pop(frame,'neg');if(a.type==='f64'){const value=-a.value;if(!Number.isFinite(value))fail('neg produced non-finite f64');push(frame,Object.freeze({type:'f64',value}));}else if(a.type==='s32')push(frame,checkedInteger('s32',-a.value,'neg'));else fail('neg requires s32 or f64');break;}
      case'eq':case'ne':case'lt':case'le':case'gt':case'ge':{const b=pop(frame,ins.op),a=pop(frame,ins.op);push(frame,compare(ins.op,a,b));break;}
      case'not':{const a=pop(frame,'not');if(a.type!=='bool')fail('not requires bool');push(frame,Object.freeze({type:'bool',value:!a.value}));break;}
      case'concat':{const b=pop(frame,'concat'),a=pop(frame,'concat');if(a.type!=='string'||b.type!=='string')fail('concat requires strings');push(frame,Object.freeze({type:'string',value:stringBytes(a.value+b.value,'concat result')}));break;}
      case'make_struct':{const values=Array(ins.fields.length);for(let i=ins.fields.length-1;i>=0;i--)values[i]=pop(frame,'make_struct');const fields=Object.create(null);for(let i=0;i<ins.fields.length;i++)fields[ins.fields[i]]=values[i];push(frame,Object.freeze({type:'struct',name:ins.name,fields:Object.freeze(fields)}));break;}
      case'get_field':{const value=pop(frame,'get_field');if(value.type!=='struct'||value.name!==ins.name)fail(`get_field expected struct ${ins.name}`);if(!Object.prototype.hasOwnProperty.call(value.fields,ins.field))fail(`struct ${ins.name} has no field ${ins.field}`);push(frame,value.fields[ins.field]);break;}
      case'make_enum':{const values=Array(ins.argc);for(let i=ins.argc-1;i>=0;i--)values[i]=pop(frame,'make_enum');push(frame,Object.freeze({type:'enum',name:ins.name,variant:ins.variant,values:Object.freeze(values)}));break;}
      case'enum_is':{const value=pop(frame,'enum_is');if(value.type!=='enum'||value.name!==ins.name)fail(`enum_is expected enum ${ins.name}`);push(frame,Object.freeze({type:'bool',value:value.variant===ins.variant}));break;}
      case'enum_get':{const value=pop(frame,'enum_get');if(value.type!=='enum'||value.name!==ins.name)fail(`enum_get expected enum ${ins.name}`);if(value.variant!==ins.variant)fail(`enum_get expected ${ins.name}.${ins.variant}, got ${value.name}.${value.variant}`);if(ins.index>=value.values.length)fail(`enum_get payload index ${ins.index} is out of range`);push(frame,value.values[ins.index]);break;}
      case'make_vec':{const items=Array(ins.count);for(let i=ins.count-1;i>=0;i--)items[i]=pop(frame,'make_vec');push(frame,Object.freeze({type:'vec',capacity:ins.capacity,items:Object.freeze(items)}));break;}
      case'vec_len':{const value=pop(frame,'vec_len');if(value.type!=='vec')fail('vec_len expected vec');push(frame,Object.freeze({type:'u32',value:BigInt(value.items.length)}));break;}
      case'vec_get':{const indexValue=pop(frame,'vec_get'),value=pop(frame,'vec_get');if(value.type!=='vec')fail('vec_get expected vec');if(indexValue.type!=='u32')fail('vec_get index must be u32');const index=Number(indexValue.value);if(index<0||index>=value.items.length)push(frame,Object.freeze({type:'enum',name:'Option',variant:'None',values:Object.freeze([])}));else push(frame,Object.freeze({type:'enum',name:'Option',variant:'Some',values:Object.freeze([value.items[index]])}));break;}
      case'vec_push':{const item=pop(frame,'vec_push'),value=pop(frame,'vec_push');if(value.type!=='vec')fail('vec_push expected vec');if(value.items.length>=value.capacity){push(frame,Object.freeze({type:'enum',name:'Result',variant:'Err',values:Object.freeze([Object.freeze({type:'string',value:'vec capacity exceeded'})])}));break;}const items=Object.freeze([...value.items,item]);push(frame,Object.freeze({type:'enum',name:'Result',variant:'Ok',values:Object.freeze([Object.freeze({type:'vec',capacity:value.capacity,items})])}));break;}
      case'vec_set':{const item=pop(frame,'vec_set'),indexValue=pop(frame,'vec_set'),value=pop(frame,'vec_set');if(value.type!=='vec')fail('vec_set expected vec');if(indexValue.type!=='u32')fail('vec_set index must be u32');const index=Number(indexValue.value);if(index<0||index>=value.items.length){push(frame,Object.freeze({type:'enum',name:'Result',variant:'Err',values:Object.freeze([Object.freeze({type:'string',value:'vec index out of range'})])}));break;}const items=value.items.slice();items[index]=item;push(frame,Object.freeze({type:'enum',name:'Result',variant:'Ok',values:Object.freeze([Object.freeze({type:'vec',capacity:value.capacity,items:Object.freeze(items)})])}));break;}
      case'jump':frame.ip=ins.target;break;
      case'jump_if_false':{const condition=pop(frame,'jump_if_false');if(condition.type!=='bool')fail('jump_if_false requires bool');if(!condition.value)frame.ip=ins.target;break;}
      case'call':{const args=Array(ins.argc);for(let i=ins.argc-1;i>=0;i--)args[i]=pop(frame,'call');frames.push(makeFrame(ins.name,args));break;}
      case'host':{if(typeof host.invoke!=='function')fail(`host import unavailable: ${ins.method}`);const args=Array(ins.argc);for(let i=ins.argc-1;i>=0;i--)args[i]=valueToHost(pop(frame,'host'));push(frame,hostToValue(await host.invoke(ins.method,args)));break;}
      case'print':{const value=pop(frame,'print');prints++;if(host.write)await host.write(displayValue(value));break;}
      case'ret':{const returned=frame.stack.length?pop(frame,'ret'):UNIT;frames.pop();if(frames.length)push(frames[frames.length-1],returned);else result=returned;break;}
      case'halt':result=frame.stack.length?pop(frame,'halt'):UNIT;halted=true;frames.length=0;break;
      default:fail(`unreachable opcode: ${ins.op}`);
    }
  }
  return Object.freeze({format:'rift-exec-result-v1',abi:RIFT_VM_ABI,entry:program.entry,steps,prints,result:valueToPublic(result),halted});
}

export function inspectRiftExecutable(raw){const program=prepareRiftExecutable(raw);return Object.freeze({format:program.format,abi:program.abi,entry:program.entry,imports:[...program.imports],functions:Object.keys(program.functions),constantCount:program.constants.length,instructionCount:program.instructionCount,limits:{...program.limits}});}
