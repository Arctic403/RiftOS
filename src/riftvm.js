export const RIFT_EXEC_FORMAT='rift-exec-v1';
export const RIFT_VM_ABI='riftvm-1';

const NAME=/^[A-Za-z_][A-Za-z0-9_.:$-]{0,95}$/;
const HOST_METHOD=/^[A-Za-z][A-Za-z0-9_-]*(?:\.[A-Za-z][A-Za-z0-9_-]*){1,3}$/;
const POISON_NAMES=new Set(['__proto__','prototype','constructor']);
const OPS=new Set(['const','load','store','pop','dup','add','sub','mul','div','mod','neg','eq','ne','lt','le','gt','ge','not','concat','string_len','string_find','string_slice','string_replace','source_text','source_code_unit_len','source_utf8_byte_len','source_cursor','source_slice','source_to_string','cursor_code_unit_offset','cursor_line','cursor_column','cursor_eof','cursor_peek_code_unit','cursor_advance','make_string_builder','builder_len','builder_append','builder_append_source','builder_finish','u8_to_u32','u8_from_u32','parse_u32','parse_s32','parse_f64','format_u32','format_s32','format_f64','make_struct','get_field','make_enum','enum_is','enum_get','make_vec','vec_len','vec_get','vec_push','vec_set','make_buffer','buffer_len','buffer_get','buffer_push','buffer_set','buffer_slice','slice_len','slice_get','state_save','state_load','state_remove','value_sha256','jump','jump_if_false','call','host','print','ret','halt']);
const DEFAULT_LIMITS=Object.freeze({maxSteps:100000,maxStack:1024,maxCallDepth:32});
const HARD_LIMITS=Object.freeze({maxFunctions:256,maxImports:64,maxConstants:4096,maxInstructions:100000,maxInstructionsPerFunction:65536,maxParams:64,maxLocals:512,maxCompositeItems:64,maxVecCapacity:256,maxBufferCapacity:100000,maxSourceTextCodeUnits:4*1024*1024,maxStringBuilderUnits:4*1024*1024,maxStringBuilderParts:100000,maxCompositeDepth:32,maxPublicValues:4096,maxDisplayBytes:65536,maxPublicStringBytes:65536,maxExecutableBytes:8*1024*1024,maxConstantStringBytes:4*1024*1024,maxSteps:1000000,maxStack:4096,maxCallDepth:64,maxStringBytes:65536,maxStateBytes:65536,maxStateSchemaBytes:4096});
const INT_BOUNDS=Object.freeze({u8:[0n,255n],u32:[0n,4294967295n],s32:[-2147483648n,2147483647n]});
const UNIT=Object.freeze({type:'unit',value:null});
const PREPARED=Symbol('riftvm.prepared');
const encoder=new TextEncoder();

function fail(message){throw new Error(`RiftVM: ${message}`);}
function plain(value){return !!value&&typeof value==='object'&&!Array.isArray(value);}
function safeName(value,label){const name=String(value||'');if(!NAME.test(name)||POISON_NAMES.has(name))fail(`${label} is invalid: ${name||'(empty)'}`);return name;}
function integer(value,label,min,max){const n=Number(value);if(!Number.isInteger(n)||n<min||n>max)fail(`${label} must be an integer in ${min}..${max}`);return n;}
function stringBytes(value,label,max=HARD_LIMITS.maxStringBytes){const text=String(value);if(encoder.encode(text).byteLength>max)fail(`${label} exceeds ${max} UTF-8 bytes`);return text;}
function okValue(value){return Object.freeze({type:'enum',name:'Result',variant:'Ok',values:Object.freeze([value]),depth:checkedCompositeDepth([value],'Result.Ok')});}
function errValue(message){const value=Object.freeze({type:'string',value:stringBytes(message,'Result.Err')});return Object.freeze({type:'enum',name:'Result',variant:'Err',values:Object.freeze([value]),depth:checkedCompositeDepth([value],'Result.Err')});}
function optionValue(value){return value===null?Object.freeze({type:'enum',name:'Option',variant:'None',values:Object.freeze([]),depth:1}):Object.freeze({type:'enum',name:'Option',variant:'Some',values:Object.freeze([value]),depth:checkedCompositeDepth([value],'Option.Some')});}
function makeSourceTextValue(text,start=0,length=String(text).length,label='SourceText'){
  const value=String(text);
  if(!Number.isInteger(start)||!Number.isInteger(length)||start<0||length<0||start+length>value.length)fail(`${label} range is invalid`);
  if(length>HARD_LIMITS.maxSourceTextCodeUnits)fail(`${label} exceeds ${HARD_LIMITS.maxSourceTextCodeUnits} UTF-16 code units`);
  return Object.freeze({type:'source_text',text:value,start,length,depth:1});
}
function sourceString(value){if(value.type!=='source_text')fail('source string access expected SourceText');return value.text.slice(value.start,value.start+value.length);}
function sourceCodeUnit(value,index){if(value.type!=='source_text')fail('source code-unit access expected SourceText');if(index<0||index>=value.length)return null;return value.text.charCodeAt(value.start+index);}
function canonicalUtf8ByteLength(text){
  const value=String(text);let bytes=0;
  for(let i=0;i<value.length;i++){
    const unit=value.charCodeAt(i);
    if(unit>=0xd800&&unit<=0xdbff){
      const next=i+1<value.length?value.charCodeAt(i+1):-1;
      if(next>=0xdc00&&next<=0xdfff){bytes+=4;i++;}else bytes+=3;
    }else if(unit>=0xdc00&&unit<=0xdfff)bytes+=3;
    else if(unit<=0x7f)bytes+=1;
    else if(unit<=0x7ff)bytes+=2;
    else bytes+=3;
  }
  return bytes;
}
function sourceUtf8ByteLength(value){return canonicalUtf8ByteLength(sourceString(value));}
function makeTextCursorValue(source,codeUnitOffset=0,line=1,column=1){
  if(source.type!=='source_text')fail('TextCursor source must be SourceText');if(!Number.isInteger(codeUnitOffset)||codeUnitOffset<0||codeUnitOffset>source.length)fail('TextCursor code-unit offset is invalid');
  return Object.freeze({type:'text_cursor',source,codeUnitOffset,line,column,depth:2});
}
function advanceTextCursor(value){
  if(value.type!=='text_cursor')fail('cursor_advance expected TextCursor');if(value.codeUnitOffset>=value.source.length)return value;
  const codeUnit=sourceCodeUnit(value.source,value.codeUnitOffset);let line=value.line,column=value.column;if(codeUnit===0x0a){line++;column=1;}else column++;
  return makeTextCursorValue(value.source,value.codeUnitOffset+1,line,column);
}
function makeStringBuilderValue(capacity,tail=null,unitLength=0,parts=0){
  if(!Number.isInteger(capacity)||capacity<1||capacity>HARD_LIMITS.maxStringBuilderUnits)fail(`StringBuilder capacity must be 1..${HARD_LIMITS.maxStringBuilderUnits}`);
  if(!Number.isInteger(unitLength)||unitLength<0||unitLength>capacity)fail('StringBuilder code-unit length is invalid');if(!Number.isInteger(parts)||parts<0||parts>HARD_LIMITS.maxStringBuilderParts)fail('StringBuilder part count is invalid');
  return Object.freeze({type:'string_builder',capacity,tail,unitLength,parts,depth:1});
}
function builderAppendSource(builder,source){
  if(builder.type!=='string_builder'||source.type!=='source_text')fail('StringBuilder append type mismatch');if(builder.parts>=HARD_LIMITS.maxStringBuilderParts)return errValue('StringBuilder part limit exceeded');
  if(builder.unitLength+source.length>builder.capacity)return errValue('StringBuilder capacity exceeded');
  const tail=Object.freeze({previous:builder.tail,source});return okValue(makeStringBuilderValue(builder.capacity,tail,builder.unitLength+source.length,builder.parts+1));
}
function finishStringBuilder(builder){
  if(builder.type!=='string_builder')fail('builder_finish expected StringBuilder');const parts=[];let node=builder.tail;while(node){parts.push(sourceString(node.source));node=node.previous;}
  parts.reverse();return makeSourceTextValue(parts.join(''),0,builder.unitLength,'StringBuilder.finish');
}
function parseIntegerText(text,type){
  const raw=String(text),signed=type==='s32',match=signed?/^-?(?:0[xX][0-9a-fA-F](?:[0-9a-fA-F_]*[0-9a-fA-F])?|[0-9](?:[0-9_]*[0-9])?)$/:/^(?:0[xX][0-9a-fA-F](?:[0-9a-fA-F_]*[0-9a-fA-F])?|[0-9](?:[0-9_]*[0-9])?)$/;
  if(!match.test(raw)||raw.includes('__'))return errValue(`invalid ${type} text`);let negative=false,body=raw;if(signed&&body.startsWith('-')){negative=true;body=body.slice(1);}body=body.replaceAll('_','');let value;try{value=BigInt(body);}catch{return errValue(`invalid ${type} text`);}if(negative)value=-value;const bounds=INT_BOUNDS[type];if(value<bounds[0]||value>bounds[1])return errValue(`${type} text is out of range`);return okValue(Object.freeze({type,value}));
}
function parseF64Text(text){
  const raw=String(text);if(!/^[+-]?[0-9](?:[0-9_]*[0-9])?\.[0-9](?:[0-9_]*[0-9])?(?:[eE][+-]?[0-9](?:[0-9_]*[0-9])?)?$/.test(raw)||raw.includes('__'))return errValue('invalid f64 text');
  const value=Number(raw.replaceAll('_',''));if(!Number.isFinite(value))return errValue('f64 text is non-finite or out of range');return okValue(Object.freeze({type:'f64',value:Object.is(value,-0)?0:value}));
}
function canonicalF64Text(value){
  const finite=finiteF64(value,'format_f64'),normalized=Object.is(finite,-0)?0:finite;let text=String(normalized),exp='';
  const e=text.search(/[eE]/);if(e>=0){exp=text.slice(e+1);text=text.slice(0,e);if(!text.includes('.'))text+='.0';const expNumber=Number(exp);if(!Number.isInteger(expNumber))fail('format_f64 exponent normalization failed');return `${text}e${expNumber}`;}
  if(!text.includes('.'))text+='.0';return text;
}
function valueDepth(value){return value&&Number.isInteger(value.depth)?value.depth:0;}
function checkedCompositeDepth(values,label){let depth=1;for(const value of values)depth=Math.max(depth,valueDepth(value)+1);if(depth>HARD_LIMITS.maxCompositeDepth)fail(`${label} exceeds composite depth limit ${HARD_LIMITS.maxCompositeDepth}`);return depth;}
function finiteF64(value,label){if(typeof value!=='number'||!Number.isFinite(value))fail(`${label} must be a finite JSON number`);return Object.is(value,-0)?0:value;}
function makeVecValue(capacity,items,label='vec'){return Object.freeze({type:'vec',capacity,items:Object.freeze(items),depth:checkedCompositeDepth(items,label)});}
const BUFFER_BRANCH_BITS=5;
const BUFFER_BRANCH=1<<BUFFER_BRANCH_BITS;
const BUFFER_ROOT_LEVEL=3;
function makeBufferNode(children,level){
  let maxDepth=0;
  for(const child of children){if(child===undefined)continue;const depth=level===0?valueDepth(child):child.maxDepth;if(depth>maxDepth)maxDepth=depth;}
  return Object.freeze({children:Object.freeze(children),maxDepth});
}
function bufferNodeSet(node,index,value,level){
  const children=node?node.children.slice():[],slot=(index>>>(level*BUFFER_BRANCH_BITS))&(BUFFER_BRANCH-1);
  children[slot]=level===0?value:bufferNodeSet(children[slot]||null,index,value,level-1);
  return makeBufferNode(children,level);
}
function bufferNodeGet(node,index,level){
  if(!node)return null;const slot=(index>>>(level*BUFFER_BRANCH_BITS))&(BUFFER_BRANCH-1),child=node.children[slot];
  if(level===0)return child??null;return child?bufferNodeGet(child,index,level-1):null;
}
function makeBufferValue(capacity,items,label='buffer'){
  if(!Number.isInteger(capacity)||capacity<1||capacity>HARD_LIMITS.maxBufferCapacity)fail(`${label} capacity is invalid`);
  if(!Array.isArray(items)||items.length>capacity)fail(`${label} items exceed capacity`);
  let root=null;for(let i=0;i<items.length;i++)root=bufferNodeSet(root,i,items[i],BUFFER_ROOT_LEVEL);
  const depth=1+(root?.maxDepth??0);if(depth>HARD_LIMITS.maxCompositeDepth)fail(`${label} exceeds composite depth limit ${HARD_LIMITS.maxCompositeDepth}`);
  return Object.freeze({type:'buffer',capacity,length:items.length,root,depth});
}
function bufferGetValue(value,index){
  if(index<0||index>=value.length)return null;const item=bufferNodeGet(value.root,index,BUFFER_ROOT_LEVEL);if(!item)fail('buffer storage invariant failed');return item;
}
function bufferWithValue(value,index,item,nextLength=value.length,label='buffer'){
  const root=bufferNodeSet(value.root,index,item,BUFFER_ROOT_LEVEL),depth=1+(root?.maxDepth??0);if(depth>HARD_LIMITS.maxCompositeDepth)fail(`${label} exceeds composite depth limit ${HARD_LIMITS.maxCompositeDepth}`);
  return Object.freeze({type:'buffer',capacity:value.capacity,length:nextLength,root,depth});
}
function bufferItems(value,mapper){const out=Array(value.length);for(let i=0;i<value.length;i++)out[i]=mapper(bufferGetValue(value,i),i);return out;}
function makeSliceValue(buffer,start,end,label='buffer_slice'){
  if(buffer.type!=='buffer')fail(`${label} expected buffer`);if(!Number.isInteger(start)||!Number.isInteger(end)||start<0||start>end||end>buffer.length)fail(`${label} range is invalid`);
  const depth=valueDepth(buffer)+1;if(depth>HARD_LIMITS.maxCompositeDepth)fail(`${label} exceeds composite depth limit ${HARD_LIMITS.maxCompositeDepth}`);
  return Object.freeze({type:'slice',buffer,start,length:end-start,depth});
}
function sliceGetValue(value,index){if(index<0||index>=value.length)return null;return bufferGetValue(value.buffer,value.start+index);}
function sliceItems(value,mapper){const out=Array(value.length);for(let i=0;i<value.length;i++)out[i]=mapper(sliceGetValue(value,i),i);return out;}
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
  if(type==='f64')return Object.freeze({type,value:finiteF64(raw.value,`constant ${index} f64`)});
  if(type==='u8'||type==='u32'||type==='s32'){
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
function normalizeStateDescriptor(raw,where,depth=0){
  if(depth>HARD_LIMITS.maxCompositeDepth)fail(`${where} exceeds state descriptor depth ${HARD_LIMITS.maxCompositeDepth}`);if(!plain(raw))fail(`${where} must be an object`);const kind=String(raw.k||'');
  if(kind==='p'){const type=String(raw.t||'');if(!['unit','bool','u8','u32','s32','f64','string'].includes(type))fail(`${where} primitive type is invalid: ${type||'(empty)'}`);return Object.freeze({k:'p',t:type});}
  if(kind==='v')return Object.freeze({k:'v',c:integer(raw.c,`${where}.c`,1,HARD_LIMITS.maxVecCapacity),i:normalizeStateDescriptor(raw.i,`${where}.i`,depth+1)});
  if(kind==='o')return Object.freeze({k:'o',i:normalizeStateDescriptor(raw.i,`${where}.i`,depth+1)});
  if(kind==='r')return Object.freeze({k:'r',o:normalizeStateDescriptor(raw.o,`${where}.o`,depth+1),e:normalizeStateDescriptor(raw.e,`${where}.e`,depth+1)});
  if(kind==='s'){
    const name=safeName(raw.n,`${where}.n`);if(!Array.isArray(raw.f)||raw.f.length<1||raw.f.length>HARD_LIMITS.maxCompositeItems)fail(`${where}.f must contain 1..${HARD_LIMITS.maxCompositeItems} fields`);const seen=new Set(),fields=[];
    for(let i=0;i<raw.f.length;i++){const pair=raw.f[i];if(!Array.isArray(pair)||pair.length!==2)fail(`${where}.f[${i}] must be [name,descriptor]`);const field=safeName(pair[0],`${where}.f[${i}][0]`);if(seen.has(field))fail(`${where} has duplicate field ${field}`);seen.add(field);fields.push(Object.freeze([field,normalizeStateDescriptor(pair[1],`${where}.f[${i}][1]`,depth+1)]));}return Object.freeze({k:'s',n:name,f:Object.freeze(fields)});
  }
  if(kind==='e'){
    const name=safeName(raw.n,`${where}.n`);if(!Array.isArray(raw.c)||raw.c.length<1||raw.c.length>HARD_LIMITS.maxCompositeItems)fail(`${where}.c must contain 1..${HARD_LIMITS.maxCompositeItems} cases`);const seen=new Set(),cases=[];
    for(let i=0;i<raw.c.length;i++){const pair=raw.c[i];if(!Array.isArray(pair)||pair.length!==2||!Array.isArray(pair[1])||pair[1].length>HARD_LIMITS.maxCompositeItems)fail(`${where}.c[${i}] is invalid`);const variant=safeName(pair[0],`${where}.c[${i}][0]`);if(seen.has(variant))fail(`${where} has duplicate case ${variant}`);seen.add(variant);cases.push(Object.freeze([variant,Object.freeze(pair[1].map((item,index)=>normalizeStateDescriptor(item,`${where}.c[${i}][1][${index}]`,depth+1)))]));}return Object.freeze({k:'e',n:name,c:Object.freeze(cases)});
  }
  fail(`${where} has unsupported descriptor kind: ${kind||'(empty)'}`);
}
function validateStateValue(value,descriptor,where='state'){
  if(descriptor.k==='p'){if(!value||value.type!==descriptor.t)fail(`${where} expected ${descriptor.t}, got ${value?.type||'(missing)'}`);return value;}
  if(descriptor.k==='v'){if(!value||value.type!=='vec'||value.capacity!==descriptor.c)fail(`${where} expected Vec capacity ${descriptor.c}`);for(let i=0;i<value.items.length;i++)validateStateValue(value.items[i],descriptor.i,`${where}[${i}]`);return value;}
  if(descriptor.k==='o'){if(!value||value.type!=='enum'||value.name!=='Option')fail(`${where} expected Option`);if(value.variant==='None'){if(value.values.length)fail(`${where} Option.None payload is invalid`);return value;}if(value.variant==='Some'){if(value.values.length!==1)fail(`${where} Option.Some payload is invalid`);validateStateValue(value.values[0],descriptor.i,`${where}.Some`);return value;}fail(`${where} Option variant is invalid: ${value.variant}`);}
  if(descriptor.k==='r'){if(!value||value.type!=='enum'||value.name!=='Result')fail(`${where} expected Result`);if(value.variant==='Ok'){if(value.values.length!==1)fail(`${where} Result.Ok payload is invalid`);validateStateValue(value.values[0],descriptor.o,`${where}.Ok`);return value;}if(value.variant==='Err'){if(value.values.length!==1)fail(`${where} Result.Err payload is invalid`);validateStateValue(value.values[0],descriptor.e,`${where}.Err`);return value;}fail(`${where} Result variant is invalid: ${value.variant}`);}
  if(descriptor.k==='s'){if(!value||value.type!=='struct'||value.name!==descriptor.n)fail(`${where} expected struct ${descriptor.n}`);const keys=Object.keys(value.fields);if(keys.length!==descriptor.f.length)fail(`${where} struct ${descriptor.n} field count mismatch`);for(const [name,child] of descriptor.f){if(!Object.prototype.hasOwnProperty.call(value.fields,name))fail(`${where} struct ${descriptor.n} is missing field ${name}`);validateStateValue(value.fields[name],child,`${where}.${name}`);}return value;}
  if(descriptor.k==='e'){if(!value||value.type!=='enum'||value.name!==descriptor.n)fail(`${where} expected enum ${descriptor.n}`);const item=descriptor.c.find(([variant])=>variant===value.variant);if(!item)fail(`${where} enum ${descriptor.n} variant is invalid: ${value.variant}`);if(value.values.length!==item[1].length)fail(`${where} enum ${descriptor.n}.${value.variant} payload count mismatch`);for(let i=0;i<item[1].length;i++)validateStateValue(value.values[i],item[1][i],`${where}.${value.variant}[${i}]`);return value;}
  fail(`${where} descriptor is invalid`);
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
  if(op==='make_buffer'){const capacity=integer(raw.capacity,`${where}.capacity`,1,HARD_LIMITS.maxBufferCapacity),count=integer(raw.count??0,`${where}.count`,0,HARD_LIMITS.maxBufferCapacity);if(count>capacity)fail(`${where}.count exceeds buffer capacity`);return Object.freeze({op,capacity,count});}
  if(op==='buffer_len'||op==='buffer_get'||op==='buffer_push'||op==='buffer_set'||op==='buffer_slice'||op==='slice_len'||op==='slice_get')return Object.freeze({op});
  if(op==='make_string_builder')return Object.freeze({op,capacity:integer(raw.capacity,`${where}.capacity`,1,HARD_LIMITS.maxStringBuilderUnits)});
  if(['source_text','source_code_unit_len','source_utf8_byte_len','source_cursor','source_slice','source_to_string','cursor_code_unit_offset','cursor_line','cursor_column','cursor_eof','cursor_peek_code_unit','cursor_advance','builder_len','builder_append','builder_append_source','builder_finish','u8_to_u32','u8_from_u32','parse_u32','parse_s32','parse_f64','format_u32','format_s32','format_f64'].includes(op))return Object.freeze({op});
  if(op==='jump'||op==='jump_if_false')return Object.freeze({op,target:integer(raw.target,`${where}.target`,0,ctx.codeLength-1)});
  if(op==='call'){
    const name=safeName(raw.name,`${where}.name`),argc=integer(raw.argc??0,`${where}.argc`,0,HARD_LIMITS.maxParams),target=ctx.functions[name];
    if(!target)fail(`${where} calls unknown function: ${name}`);if(argc!==target.params)fail(`${where} argc ${argc} does not match ${name} params ${target.params}`);
    return Object.freeze({op,name,argc});
  }
  if(op==='state_save'||op==='state_load'){
    const method=op==='state_save'?'state.save':'state.load';if(!ctx.imports.has(method))fail(`${where} ${op} requires declared import ${method}`);const schema=stringBytes(raw.schema,`${where}.schema`,HARD_LIMITS.maxStateSchemaBytes);if(!schema)fail(`${where}.schema must not be empty`);let parsed;try{parsed=JSON.parse(schema);}catch(error){fail(`${where}.schema is invalid JSON: ${error.message}`);}const descriptor=normalizeStateDescriptor(parsed,`${where}.schema`);if(JSON.stringify(descriptor)!==schema)fail(`${where}.schema must use canonical descriptor JSON`);return Object.freeze({op,schema,descriptor});
  }
  if(op==='state_remove'){if(!ctx.imports.has('state.remove'))fail(`${where} state_remove requires declared import state.remove`);return Object.freeze({op});}
  if(op==='host'){
    const method=String(raw.method||'');if(!HOST_METHOD.test(method)||!ctx.imports.has(method))fail(`${where} host method is not declared in imports: ${method||'(empty)'}`);
    return Object.freeze({op,method,argc:integer(raw.argc??0,`${where}.argc`,0,16)});
  }
  return Object.freeze({op});
}
function parseInput(raw){
  if(typeof raw==='string'){
    const text=raw.charCodeAt(0)===0xfeff?raw.slice(1):raw;
    if(text.length>HARD_LIMITS.maxExecutableBytes)fail(`executable JSON exceeds ${HARD_LIMITS.maxExecutableBytes} UTF-8 bytes`);
    const bytes=encoder.encode(text).byteLength;if(bytes>HARD_LIMITS.maxExecutableBytes)fail(`executable JSON exceeds ${HARD_LIMITS.maxExecutableBytes} UTF-8 bytes`);
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
  const normalizedConstants=[];let constantStringBytes=0;for(let i=0;i<rawConstants.length;i++){const item=normalizeConstant(rawConstants[i],i);if(item.type==='string'){constantStringBytes+=encoder.encode(item.value).byteLength;if(constantStringBytes>HARD_LIMITS.maxConstantStringBytes)fail(`constant strings exceed ${HARD_LIMITS.maxConstantStringBytes} UTF-8 bytes`);}normalizedConstants.push(item);}const constants=Object.freeze(normalizedConstants);
  const rawFunctions=source.functions;if(!plain(rawFunctions))fail('functions must be an object');
  const names=Object.keys(rawFunctions);if(!names.length||names.length>HARD_LIMITS.maxFunctions)fail(`function count must be 1..${HARD_LIMITS.maxFunctions}`);
  const functionMeta=Object.create(null);let totalInstructions=0;
  for(const name of names){safeName(name,'function name');const rawFn=rawFunctions[name];if(!plain(rawFn))fail(`function ${name} must be an object`);const params=integer(rawFn.params??0,`${name}.params`,0,HARD_LIMITS.maxParams),locals=integer(rawFn.locals??params,`${name}.locals`,params,HARD_LIMITS.maxLocals);if(!Array.isArray(rawFn.code)||!rawFn.code.length)fail(`function ${name} code must be a non-empty array`);if(rawFn.code.length>HARD_LIMITS.maxInstructionsPerFunction)fail(`function ${name} has too many instructions`);totalInstructions+=rawFn.code.length;if(totalInstructions>HARD_LIMITS.maxInstructions)fail(`program exceeds ${HARD_LIMITS.maxInstructions} instructions`);functionMeta[name]={params,locals,rawCode:rawFn.code};}
  if(!functionMeta[entry])fail(`entry function not found: ${entry}`);if(functionMeta[entry].params!==0)fail('entry function must have zero parameters');
  const functions=Object.create(null);
  for(const name of names){const meta=functionMeta[name],ctx={constantCount:constants.length,locals:meta.locals,codeLength:meta.rawCode.length,functions:functionMeta,imports};const code=Object.freeze(meta.rawCode.map((instruction,index)=>normalizeInstruction(instruction,`${name}[${index}]`,ctx)));functions[name]=Object.freeze({params:meta.params,locals:meta.locals,code});}
  return Object.freeze({[PREPARED]:true,format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry,imports:Object.freeze([...imports]),constants,functions:Object.freeze(functions),limits:normalizeLimits(source.limits),instructionCount:totalInstructions});
}

function publicValue(value,state={values:0,stringBytes:0}){
  if(++state.values>HARD_LIMITS.maxPublicValues)fail(`public result exceeds ${HARD_LIMITS.maxPublicValues} values`);
  if(!value||value.type==='unit')return{type:'unit'};
  if(value.type==='u8'||value.type==='u32'||value.type==='s32')return{type:value.type,value:value.value.toString()};
  if(value.type==='string'){state.stringBytes+=encoder.encode(value.value).byteLength;if(state.stringBytes>HARD_LIMITS.maxPublicStringBytes)fail(`public result strings exceed ${HARD_LIMITS.maxPublicStringBytes} UTF-8 bytes`);return{type:'string',value:value.value};}
  if(value.type==='struct'){const fields={};for(const [key,item] of Object.entries(value.fields))fields[key]=publicValue(item,state);return{type:'struct',name:value.name,fields};}
  if(value.type==='enum')return{type:'enum',name:value.name,variant:value.variant,values:value.values.map(item=>publicValue(item,state))};
  if(value.type==='vec')return{type:'vec',capacity:value.capacity,items:value.items.map(item=>publicValue(item,state))};
  if(value.type==='buffer')return{type:'buffer',capacity:value.capacity,items:bufferItems(value,item=>publicValue(item,state))};
  if(value.type==='slice')return{type:'slice',items:sliceItems(value,item=>publicValue(item,state))};
  if(value.type==='source_text')return{type:'source_text',codeUnitLength:value.length};
  if(value.type==='text_cursor')return{type:'text_cursor',codeUnitOffset:value.codeUnitOffset,line:value.line,column:value.column};
  if(value.type==='string_builder')return{type:'string_builder',capacity:value.capacity,codeUnitLength:value.unitLength,parts:value.parts};
  return{type:value.type,value:value.value};
}
function valueToPublic(value){return publicValue(value);}
function hashPublicValue(value,state={values:0,stringBytes:0}){
  if(++state.values>HARD_LIMITS.maxPublicValues)fail(`hash input exceeds ${HARD_LIMITS.maxPublicValues} values`);
  if(!value||value.type==='unit')return{type:'unit'};
  if(value.type==='u8'||value.type==='u32'||value.type==='s32')return{type:value.type,value:value.value.toString()};
  if(value.type==='f64')return{type:'f64',value:finiteF64(value.value,'hash input f64')};
  if(value.type==='bool')return{type:'bool',value:value.value===true};
  if(value.type==='string'){state.stringBytes+=encoder.encode(value.value).byteLength;if(state.stringBytes>HARD_LIMITS.maxPublicStringBytes)fail(`hash input strings exceed ${HARD_LIMITS.maxPublicStringBytes} UTF-8 bytes`);return{type:'string',value:value.value};}
  if(value.type==='struct'){const fields={};for(const key of Object.keys(value.fields).sort())fields[key]=hashPublicValue(value.fields[key],state);return{type:'struct',name:value.name,fields};}
  if(value.type==='enum')return{type:'enum',name:value.name,variant:value.variant,values:value.values.map(item=>hashPublicValue(item,state))};
  if(value.type==='vec')return{type:'vec',capacity:value.capacity,items:value.items.map(item=>hashPublicValue(item,state))};
  if(value.type==='buffer')return{type:'buffer',capacity:value.capacity,items:bufferItems(value,item=>hashPublicValue(item,state))};
  if(value.type==='slice')return{type:'slice',items:sliceItems(value,item=>hashPublicValue(item,state))};
  fail(`hash input uses unsupported type: ${value.type||'(missing)'}`);
}
async function valueSha256(value){const text=JSON.stringify(hashPublicValue(value)),bytes=encoder.encode(text);if(bytes.byteLength>HARD_LIMITS.maxStateBytes)fail(`hash input exceeds ${HARD_LIMITS.maxStateBytes} UTF-8 bytes`);const subtle=globalThis.crypto?.subtle;if(!subtle)fail('SHA-256 is unavailable in this runtime');const digest=new Uint8Array(await subtle.digest('SHA-256',bytes));return Object.freeze({type:'string',value:[...digest].map(byte=>byte.toString(16).padStart(2,'0')).join('')});}
function isCompositeValue(value){return value?.type==='struct'||value?.type==='enum'||value?.type==='vec'||value?.type==='buffer'||value?.type==='slice'||value?.type==='source_text'||value?.type==='text_cursor'||value?.type==='string_builder';}
function valueToHost(value){if(!value||value.type==='unit')return null;if(isCompositeValue(value))fail('composite values cannot cross the host import boundary');if(value.type==='u8'||value.type==='u32'||value.type==='s32')return Number(value.value);return value.value;}
function hostToValue(raw){if(raw===null||raw===undefined)return UNIT;if(typeof raw==='boolean')return Object.freeze({type:'bool',value:raw});if(typeof raw==='string')return Object.freeze({type:'string',value:stringBytes(raw,'host string')});if(typeof raw==='number')return Object.freeze({type:'f64',value:finiteF64(raw,'host f64')});const text=JSON.stringify(raw);return Object.freeze({type:'string',value:stringBytes(text,'host JSON result')});}
function statePublicToValue(raw,state={values:0,stringBytes:0}){
  if(++state.values>HARD_LIMITS.maxPublicValues)fail(`state payload exceeds ${HARD_LIMITS.maxPublicValues} values`);if(!plain(raw))fail('state payload value must be an object');const type=String(raw.type||'');
  if(type==='unit')return UNIT;
  if(type==='bool'){if(typeof raw.value!=='boolean')fail('state bool is invalid');return Object.freeze({type,value:raw.value});}
  if(type==='string'){if(typeof raw.value!=='string')fail('state string is invalid');state.stringBytes+=encoder.encode(raw.value).byteLength;if(state.stringBytes>HARD_LIMITS.maxPublicStringBytes)fail(`state strings exceed ${HARD_LIMITS.maxPublicStringBytes} UTF-8 bytes`);return Object.freeze({type,value:stringBytes(raw.value,'state string')});}
  if(type==='u8'||type==='u32'||type==='s32'){const value=parseIntegerConstant(raw.value,`state ${type}`),bounds=INT_BOUNDS[type];if(value<bounds[0]||value>bounds[1])fail(`state ${type} is out of range`);return Object.freeze({type,value});}
  if(type==='f64')return Object.freeze({type,value:finiteF64(raw.value,'state f64')});
  if(type==='struct'){const name=safeName(raw.name,'state struct name');if(!plain(raw.fields))fail(`state struct ${name} fields must be an object`);const entries=Object.entries(raw.fields);if(!entries.length||entries.length>HARD_LIMITS.maxCompositeItems)fail(`state struct ${name} field count is invalid`);const values=[],fields=Object.create(null);for(const [key,item] of entries){safeName(key,`state struct ${name} field`);const value=statePublicToValue(item,state);fields[key]=value;values.push(value);}return Object.freeze({type:'struct',name,fields:Object.freeze(fields),depth:checkedCompositeDepth(values,`state struct ${name}`)});}
  if(type==='enum'){const name=safeName(raw.name,'state enum name'),variant=safeName(raw.variant,'state enum variant');if(!Array.isArray(raw.values)||raw.values.length>HARD_LIMITS.maxCompositeItems)fail(`state enum ${name}.${variant} values are invalid`);const values=raw.values.map(item=>statePublicToValue(item,state));return Object.freeze({type:'enum',name,variant,values:Object.freeze(values),depth:checkedCompositeDepth(values,`state enum ${name}.${variant}`)});}
  if(type==='vec'){const capacity=integer(raw.capacity,'state vec capacity',1,HARD_LIMITS.maxVecCapacity);if(!Array.isArray(raw.items)||raw.items.length>capacity)fail('state vec items exceed capacity');return makeVecValue(capacity,raw.items.map(item=>statePublicToValue(item,state)),'state vec');}
  fail(`state payload uses unsupported type: ${type||'(empty)'}`);
}
function serializeStateValue(value,schema,descriptor){validateStateValue(value,descriptor,'state.save');const envelope={format:'riftvm-state-v1',schema:String(schema),value:valueToPublic(value)};const text=JSON.stringify(envelope),bytes=encoder.encode(text).byteLength;if(bytes>HARD_LIMITS.maxStateBytes)fail(`state payload exceeds ${HARD_LIMITS.maxStateBytes} UTF-8 bytes`);return text;}
function deserializeStateValue(text,schema,descriptor){const raw=stringBytes(text,'state payload',HARD_LIMITS.maxStateBytes);let envelope;try{envelope=JSON.parse(raw);}catch(error){fail(`invalid state payload JSON: ${error.message}`);}if(!plain(envelope)||envelope.format!=='riftvm-state-v1')fail('invalid state payload format');if(envelope.schema!==schema)fail(`state schema mismatch: expected ${schema}`);const value=statePublicToValue(envelope.value);validateStateValue(value,descriptor,'state.load');return value;}
function displayValue(value){
  const state={bytes:0,values:0,parts:[]};
  const append=text=>{const part=String(text),bytes=encoder.encode(part).byteLength;state.bytes+=bytes;if(state.bytes>HARD_LIMITS.maxDisplayBytes)fail(`display value exceeds ${HARD_LIMITS.maxDisplayBytes} UTF-8 bytes`);state.parts.push(part);};
  const visit=item=>{if(++state.values>HARD_LIMITS.maxPublicValues)fail(`display value exceeds ${HARD_LIMITS.maxPublicValues} values`);if(!item||item.type==='unit'){append('unit');return;}if(item.type==='u8'||item.type==='u32'||item.type==='s32'){append(item.value.toString());return;}if(item.type==='struct'){append(`${item.name}{`);let first=true;for(const [key,child] of Object.entries(item.fields)){if(!first)append(',');first=false;append(`${key}=`);visit(child);}append('}');return;}if(item.type==='enum'){append(`${item.name}.${item.variant}`);if(item.values.length){append('(');for(let i=0;i<item.values.length;i++){if(i)append(',');visit(item.values[i]);}append(')');}return;}if(item.type==='vec'){append('[');for(let i=0;i<item.items.length;i++){if(i)append(',');visit(item.items[i]);}append(']');return;}if(item.type==='buffer'){append('Buffer[');for(let i=0;i<item.length;i++){if(i)append(',');visit(bufferGetValue(item,i));}append(']');return;}if(item.type==='slice'){append('Slice[');for(let i=0;i<item.length;i++){if(i)append(',');visit(sliceGetValue(item,i));}append(']');return;}if(item.type==='source_text'){append(`SourceText(codeUnits=${item.length})`);return;}if(item.type==='text_cursor'){append(`TextCursor(codeUnit=${item.codeUnitOffset},line=${item.line},column=${item.column})`);return;}if(item.type==='string_builder'){append(`StringBuilder(codeUnits=${item.unitLength},capacity=${item.capacity})`);return;}append(item.value);};
  visit(value);return state.parts.join('');
}
function sameType(a,b,op){if(!a||!b||a.type!==b.type)fail(`${op} requires operands of the same type`);if(isCompositeValue(a)||isCompositeValue(b))fail(`${op} does not support composite values`);}
function checkedInteger(type,value,op){const bounds=INT_BOUNDS[type];if(value<bounds[0]||value>bounds[1])fail(`${op} ${type} overflow`);return Object.freeze({type,value});}
function numericBinary(op,a,b){
  sameType(a,b,op);if(a.type==='f64'){let value;if(op==='add')value=a.value+b.value;else if(op==='sub')value=a.value-b.value;else if(op==='mul')value=a.value*b.value;else if(op==='div'){if(b.value===0)fail('division by zero');value=a.value/b.value;}else if(op==='mod'){if(b.value===0)fail('modulo by zero');value=a.value%b.value;}else fail(`${op} is not numeric`);return Object.freeze({type:'f64',value:finiteF64(value,`${op} f64 result`)});}
  if(a.type!=='u8'&&a.type!=='u32'&&a.type!=='s32')fail(`${op} requires numeric operands`);let value;if(op==='add')value=a.value+b.value;else if(op==='sub')value=a.value-b.value;else if(op==='mul')value=a.value*b.value;else if(op==='div'){if(b.value===0n)fail('division by zero');value=a.value/b.value;}else if(op==='mod'){if(b.value===0n)fail('modulo by zero');value=a.value%b.value;}else fail(`${op} is not numeric`);return checkedInteger(a.type,value,op);
}
function compare(op,a,b){
  if(isCompositeValue(a)||isCompositeValue(b))fail(`${op} does not support composite values`);
  if(op==='eq'||op==='ne'){const equal=a?.type===b?.type&&(a?.type==='unit'||a?.value===b?.value);return Object.freeze({type:'bool',value:op==='eq'?equal:!equal});}
  sameType(a,b,op);if(!['u8','u32','s32','f64','string'].includes(a.type))fail(`${op} requires ordered operands`);let value;if(op==='lt')value=a.value<b.value;else if(op==='le')value=a.value<=b.value;else if(op==='gt')value=a.value>b.value;else value=a.value>=b.value;return Object.freeze({type:'bool',value});
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
      case'neg':{const a=pop(frame,'neg');if(a.type==='f64')push(frame,Object.freeze({type:'f64',value:finiteF64(-a.value,'neg f64 result')}));else if(a.type==='s32')push(frame,checkedInteger('s32',-a.value,'neg'));else fail('neg requires s32 or f64');break;}
      case'eq':case'ne':case'lt':case'le':case'gt':case'ge':{const b=pop(frame,ins.op),a=pop(frame,ins.op);push(frame,compare(ins.op,a,b));break;}
      case'not':{const a=pop(frame,'not');if(a.type!=='bool')fail('not requires bool');push(frame,Object.freeze({type:'bool',value:!a.value}));break;}
      case'concat':{const b=pop(frame,'concat'),a=pop(frame,'concat');if(a.type!=='string'||b.type!=='string')fail('concat requires strings');push(frame,Object.freeze({type:'string',value:stringBytes(a.value+b.value,'concat result')}));break;}
      case'string_len':{const value=pop(frame,'string_len');if(value.type!=='string')fail('string_len requires string');push(frame,Object.freeze({type:'u32',value:BigInt(value.value.length)}));break;}
      case'string_find':{const startValue=pop(frame,'string_find'),needle=pop(frame,'string_find'),text=pop(frame,'string_find');if(text.type!=='string'||needle.type!=='string'||startValue.type!=='u32')fail('string_find requires string, string, u32');const start=Number(startValue.value);if(start>text.value.length)fail('string_find start is out of range');const index=text.value.indexOf(needle.value,start);if(index<0)push(frame,Object.freeze({type:'enum',name:'Option',variant:'None',values:Object.freeze([]),depth:1}));else{const item=Object.freeze({type:'u32',value:BigInt(index)});push(frame,Object.freeze({type:'enum',name:'Option',variant:'Some',values:Object.freeze([item]),depth:checkedCompositeDepth([item],'Option.Some')}));}break;}
      case'string_slice':{const endValue=pop(frame,'string_slice'),startValue=pop(frame,'string_slice'),text=pop(frame,'string_slice');if(text.type!=='string'||startValue.type!=='u32'||endValue.type!=='u32')fail('string_slice requires string, u32, u32');const start=Number(startValue.value),end=Number(endValue.value);if(start>end||end>text.value.length)fail('string_slice range is out of bounds');push(frame,Object.freeze({type:'string',value:stringBytes(text.value.slice(start,end),'string_slice result')}));break;}
      case'string_replace':{const replacement=pop(frame,'string_replace'),endValue=pop(frame,'string_replace'),startValue=pop(frame,'string_replace'),text=pop(frame,'string_replace');if(text.type!=='string'||startValue.type!=='u32'||endValue.type!=='u32'||replacement.type!=='string')fail('string_replace requires string, u32, u32, string');const start=Number(startValue.value),end=Number(endValue.value);if(start>end||end>text.value.length)fail('string_replace range is out of bounds');const next=text.value.slice(0,start)+replacement.value+text.value.slice(end);push(frame,Object.freeze({type:'string',value:stringBytes(next,'string_replace result')}));break;}
      case'source_text':{const value=pop(frame,'source_text');if(value.type!=='string')fail('source_text requires string');push(frame,makeSourceTextValue(value.value,0,value.value.length,'source_text'));break;}
      case'source_code_unit_len':{const value=pop(frame,'source_code_unit_len');if(value.type!=='source_text')fail('source_code_unit_len expected SourceText');push(frame,Object.freeze({type:'u32',value:BigInt(value.length)}));break;}
      case'source_utf8_byte_len':{const value=pop(frame,'source_utf8_byte_len');if(value.type!=='source_text')fail('source_utf8_byte_len expected SourceText');push(frame,Object.freeze({type:'u32',value:BigInt(sourceUtf8ByteLength(value))}));break;}
      case'source_cursor':{const value=pop(frame,'source_cursor');if(value.type!=='source_text')fail('source_cursor expected SourceText');push(frame,makeTextCursorValue(value));break;}
      case'source_slice':{const endValue=pop(frame,'source_slice'),startValue=pop(frame,'source_slice'),value=pop(frame,'source_slice');if(value.type!=='source_text'||startValue.type!=='u32'||endValue.type!=='u32')fail('source_slice requires SourceText, u32, u32');const start=Number(startValue.value),end=Number(endValue.value);if(start>end||end>value.length){push(frame,errValue('SourceText slice out of range'));break;}push(frame,okValue(makeSourceTextValue(value.text,value.start+start,end-start,'SourceText.slice')));break;}
      case'source_to_string':{const value=pop(frame,'source_to_string');if(value.type!=='source_text')fail('source_to_string expected SourceText');const text=sourceString(value);if(encoder.encode(text).byteLength>HARD_LIMITS.maxStringBytes){push(frame,errValue('SourceText exceeds string byte limit'));break;}push(frame,okValue(Object.freeze({type:'string',value:stringBytes(text,'SourceText.to_string')})));break;}
      case'cursor_code_unit_offset':{const value=pop(frame,'cursor_code_unit_offset');if(value.type!=='text_cursor')fail('cursor_code_unit_offset expected TextCursor');push(frame,Object.freeze({type:'u32',value:BigInt(value.codeUnitOffset)}));break;}
      case'cursor_line':{const value=pop(frame,'cursor_line');if(value.type!=='text_cursor')fail('cursor_line expected TextCursor');push(frame,Object.freeze({type:'u32',value:BigInt(value.line)}));break;}
      case'cursor_column':{const value=pop(frame,'cursor_column');if(value.type!=='text_cursor')fail('cursor_column expected TextCursor');push(frame,Object.freeze({type:'u32',value:BigInt(value.column)}));break;}
      case'cursor_eof':{const value=pop(frame,'cursor_eof');if(value.type!=='text_cursor')fail('cursor_eof expected TextCursor');push(frame,Object.freeze({type:'bool',value:value.codeUnitOffset>=value.source.length}));break;}
      case'cursor_peek_code_unit':{const offsetValue=pop(frame,'cursor_peek_code_unit'),value=pop(frame,'cursor_peek_code_unit');if(value.type!=='text_cursor'||offsetValue.type!=='u32')fail('cursor_peek_code_unit requires TextCursor, u32');const index=value.codeUnitOffset+Number(offsetValue.value),codeUnit=sourceCodeUnit(value.source,index);push(frame,optionValue(codeUnit===null?null:Object.freeze({type:'u32',value:BigInt(codeUnit)})));break;}
      case'cursor_advance':{const value=pop(frame,'cursor_advance');push(frame,advanceTextCursor(value));break;}
      case'make_string_builder':{push(frame,makeStringBuilderValue(ins.capacity));break;}
      case'builder_len':{const value=pop(frame,'builder_len');if(value.type!=='string_builder')fail('builder_len expected StringBuilder');push(frame,Object.freeze({type:'u32',value:BigInt(value.unitLength)}));break;}
      case'builder_append':{const text=pop(frame,'builder_append'),builder=pop(frame,'builder_append');if(builder.type!=='string_builder'||text.type!=='string')fail('builder_append requires StringBuilder, string');push(frame,builderAppendSource(builder,makeSourceTextValue(text.value,0,text.value.length,'StringBuilder.append')));break;}
      case'builder_append_source':{const source=pop(frame,'builder_append_source'),builder=pop(frame,'builder_append_source');push(frame,builderAppendSource(builder,source));break;}
      case'builder_finish':{const builder=pop(frame,'builder_finish');push(frame,finishStringBuilder(builder));break;}
      case'u8_to_u32':{const value=pop(frame,'u8_to_u32');if(value.type!=='u8')fail('u8_to_u32 requires u8');push(frame,Object.freeze({type:'u32',value:value.value}));break;}
      case'u8_from_u32':{const value=pop(frame,'u8_from_u32');if(value.type!=='u32')fail('u8_from_u32 requires u32');if(value.value>255n){push(frame,errValue('u32 value is out of u8 range'));break;}push(frame,okValue(Object.freeze({type:'u8',value:value.value})));break;}
      case'parse_u32':{const value=pop(frame,'parse_u32');if(value.type!=='string')fail('parse_u32 requires string');push(frame,parseIntegerText(value.value,'u32'));break;}
      case'parse_s32':{const value=pop(frame,'parse_s32');if(value.type!=='string')fail('parse_s32 requires string');push(frame,parseIntegerText(value.value,'s32'));break;}
      case'parse_f64':{const value=pop(frame,'parse_f64');if(value.type!=='string')fail('parse_f64 requires string');push(frame,parseF64Text(value.value));break;}
      case'format_u32':{const value=pop(frame,'format_u32');if(value.type!=='u32')fail('format_u32 requires u32');push(frame,Object.freeze({type:'string',value:value.value.toString()}));break;}
      case'format_s32':{const value=pop(frame,'format_s32');if(value.type!=='s32')fail('format_s32 requires s32');push(frame,Object.freeze({type:'string',value:value.value.toString()}));break;}
      case'format_f64':{const value=pop(frame,'format_f64');if(value.type!=='f64')fail('format_f64 requires f64');push(frame,Object.freeze({type:'string',value:canonicalF64Text(value.value)}));break;}
      case'make_struct':{const values=Array(ins.fields.length);for(let i=ins.fields.length-1;i>=0;i--)values[i]=pop(frame,'make_struct');const fields=Object.create(null);for(let i=0;i<ins.fields.length;i++)fields[ins.fields[i]]=values[i];push(frame,Object.freeze({type:'struct',name:ins.name,fields:Object.freeze(fields),depth:checkedCompositeDepth(values,`struct ${ins.name}`)}));break;}
      case'get_field':{const value=pop(frame,'get_field');if(value.type!=='struct'||value.name!==ins.name)fail(`get_field expected struct ${ins.name}`);if(!Object.prototype.hasOwnProperty.call(value.fields,ins.field))fail(`struct ${ins.name} has no field ${ins.field}`);push(frame,value.fields[ins.field]);break;}
      case'make_enum':{const values=Array(ins.argc);for(let i=ins.argc-1;i>=0;i--)values[i]=pop(frame,'make_enum');push(frame,Object.freeze({type:'enum',name:ins.name,variant:ins.variant,values:Object.freeze(values),depth:checkedCompositeDepth(values,`enum ${ins.name}.${ins.variant}`)}));break;}
      case'enum_is':{const value=pop(frame,'enum_is');if(value.type!=='enum'||value.name!==ins.name)fail(`enum_is expected enum ${ins.name}`);push(frame,Object.freeze({type:'bool',value:value.variant===ins.variant}));break;}
      case'enum_get':{const value=pop(frame,'enum_get');if(value.type!=='enum'||value.name!==ins.name)fail(`enum_get expected enum ${ins.name}`);if(value.variant!==ins.variant)fail(`enum_get expected ${ins.name}.${ins.variant}, got ${value.name}.${value.variant}`);if(ins.index>=value.values.length)fail(`enum_get payload index ${ins.index} is out of range`);push(frame,value.values[ins.index]);break;}
      case'make_vec':{const items=Array(ins.count);for(let i=ins.count-1;i>=0;i--)items[i]=pop(frame,'make_vec');push(frame,makeVecValue(ins.capacity,items,'make_vec'));break;}
      case'vec_len':{const value=pop(frame,'vec_len');if(value.type!=='vec')fail('vec_len expected vec');push(frame,Object.freeze({type:'u32',value:BigInt(value.items.length)}));break;}
      case'vec_get':{const indexValue=pop(frame,'vec_get'),value=pop(frame,'vec_get');if(value.type!=='vec')fail('vec_get expected vec');if(indexValue.type!=='u32')fail('vec_get index must be u32');const index=Number(indexValue.value);if(index<0||index>=value.items.length)push(frame,Object.freeze({type:'enum',name:'Option',variant:'None',values:Object.freeze([]),depth:1}));else{const item=value.items[index];push(frame,Object.freeze({type:'enum',name:'Option',variant:'Some',values:Object.freeze([item]),depth:checkedCompositeDepth([item],'Option.Some')}));}break;}
      case'vec_push':{const item=pop(frame,'vec_push'),value=pop(frame,'vec_push');if(value.type!=='vec')fail('vec_push expected vec');if(value.items.length>=value.capacity){const err=Object.freeze({type:'string',value:'vec capacity exceeded'});push(frame,Object.freeze({type:'enum',name:'Result',variant:'Err',values:Object.freeze([err]),depth:checkedCompositeDepth([err],'Result.Err')}));break;}const next=makeVecValue(value.capacity,[...value.items,item],'vec_push');push(frame,Object.freeze({type:'enum',name:'Result',variant:'Ok',values:Object.freeze([next]),depth:checkedCompositeDepth([next],'Result.Ok')}));break;}
      case'vec_set':{const item=pop(frame,'vec_set'),indexValue=pop(frame,'vec_set'),value=pop(frame,'vec_set');if(value.type!=='vec')fail('vec_set expected vec');if(indexValue.type!=='u32')fail('vec_set index must be u32');const index=Number(indexValue.value);if(index<0||index>=value.items.length){const err=Object.freeze({type:'string',value:'vec index out of range'});push(frame,Object.freeze({type:'enum',name:'Result',variant:'Err',values:Object.freeze([err]),depth:checkedCompositeDepth([err],'Result.Err')}));break;}const items=value.items.slice();items[index]=item;const next=makeVecValue(value.capacity,items,'vec_set');push(frame,Object.freeze({type:'enum',name:'Result',variant:'Ok',values:Object.freeze([next]),depth:checkedCompositeDepth([next],'Result.Ok')}));break;}
      case'make_buffer':{const items=Array(ins.count);for(let i=ins.count-1;i>=0;i--)items[i]=pop(frame,'make_buffer');push(frame,makeBufferValue(ins.capacity,items,'make_buffer'));break;}
      case'buffer_len':{const value=pop(frame,'buffer_len');if(value.type!=='buffer')fail('buffer_len expected buffer');push(frame,Object.freeze({type:'u32',value:BigInt(value.length)}));break;}
      case'buffer_get':{const indexValue=pop(frame,'buffer_get'),value=pop(frame,'buffer_get');if(value.type!=='buffer')fail('buffer_get expected buffer');if(indexValue.type!=='u32')fail('buffer_get index must be u32');const index=Number(indexValue.value),item=bufferGetValue(value,index);if(item===null)push(frame,Object.freeze({type:'enum',name:'Option',variant:'None',values:Object.freeze([]),depth:1}));else push(frame,Object.freeze({type:'enum',name:'Option',variant:'Some',values:Object.freeze([item]),depth:checkedCompositeDepth([item],'Option.Some')}));break;}
      case'buffer_push':{const item=pop(frame,'buffer_push'),value=pop(frame,'buffer_push');if(value.type!=='buffer')fail('buffer_push expected buffer');if(value.length>=value.capacity){const err=Object.freeze({type:'string',value:'buffer capacity exceeded'});push(frame,Object.freeze({type:'enum',name:'Result',variant:'Err',values:Object.freeze([err]),depth:checkedCompositeDepth([err],'Result.Err')}));break;}const next=bufferWithValue(value,value.length,item,value.length+1,'buffer_push');push(frame,Object.freeze({type:'enum',name:'Result',variant:'Ok',values:Object.freeze([next]),depth:checkedCompositeDepth([next],'Result.Ok')}));break;}
      case'buffer_set':{const item=pop(frame,'buffer_set'),indexValue=pop(frame,'buffer_set'),value=pop(frame,'buffer_set');if(value.type!=='buffer')fail('buffer_set expected buffer');if(indexValue.type!=='u32')fail('buffer_set index must be u32');const index=Number(indexValue.value);if(index<0||index>=value.length){const err=Object.freeze({type:'string',value:'buffer index out of range'});push(frame,Object.freeze({type:'enum',name:'Result',variant:'Err',values:Object.freeze([err]),depth:checkedCompositeDepth([err],'Result.Err')}));break;}const next=bufferWithValue(value,index,item,value.length,'buffer_set');push(frame,Object.freeze({type:'enum',name:'Result',variant:'Ok',values:Object.freeze([next]),depth:checkedCompositeDepth([next],'Result.Ok')}));break;}
      case'buffer_slice':{const endValue=pop(frame,'buffer_slice'),startValue=pop(frame,'buffer_slice'),value=pop(frame,'buffer_slice');if(value.type!=='buffer')fail('buffer_slice expected buffer');if(startValue.type!=='u32'||endValue.type!=='u32')fail('buffer_slice indices must be u32');const start=Number(startValue.value),end=Number(endValue.value);if(start>end||end>value.length){const err=Object.freeze({type:'string',value:'buffer slice out of range'});push(frame,Object.freeze({type:'enum',name:'Result',variant:'Err',values:Object.freeze([err]),depth:checkedCompositeDepth([err],'Result.Err')}));break;}const next=makeSliceValue(value,start,end,'buffer_slice');push(frame,Object.freeze({type:'enum',name:'Result',variant:'Ok',values:Object.freeze([next]),depth:checkedCompositeDepth([next],'Result.Ok')}));break;}
      case'slice_len':{const value=pop(frame,'slice_len');if(value.type!=='slice')fail('slice_len expected slice');push(frame,Object.freeze({type:'u32',value:BigInt(value.length)}));break;}
      case'slice_get':{const indexValue=pop(frame,'slice_get'),value=pop(frame,'slice_get');if(value.type!=='slice')fail('slice_get expected slice');if(indexValue.type!=='u32')fail('slice_get index must be u32');const index=Number(indexValue.value),item=sliceGetValue(value,index);if(item===null)push(frame,Object.freeze({type:'enum',name:'Option',variant:'None',values:Object.freeze([]),depth:1}));else push(frame,Object.freeze({type:'enum',name:'Option',variant:'Some',values:Object.freeze([item]),depth:checkedCompositeDepth([item],'Option.Some')}));break;}
      case'jump':frame.ip=ins.target;break;
      case'jump_if_false':{const condition=pop(frame,'jump_if_false');if(condition.type!=='bool')fail('jump_if_false requires bool');if(!condition.value)frame.ip=ins.target;break;}
      case'call':{const args=Array(ins.argc);for(let i=ins.argc-1;i>=0;i--)args[i]=pop(frame,'call');frames.push(makeFrame(ins.name,args));break;}
      case'state_save':{if(typeof host.invoke!=='function')fail('host import unavailable: state.save');const value=pop(frame,'state_save'),key=pop(frame,'state_save');if(key.type!=='string')fail('state_save key must be string');const ok=await host.invoke('state.save',[key.value,serializeStateValue(value,ins.schema,ins.descriptor)]);push(frame,Object.freeze({type:'bool',value:ok===true}));break;}
      case'state_load':{if(typeof host.invoke!=='function')fail('host import unavailable: state.load');const fallback=pop(frame,'state_load'),key=pop(frame,'state_load');if(key.type!=='string')fail('state_load key must be string');validateStateValue(fallback,ins.descriptor,'state.load fallback');const raw=await host.invoke('state.load',[key.value]);push(frame,raw===null||raw===undefined?fallback:deserializeStateValue(raw,ins.schema,ins.descriptor));break;}
      case'state_remove':{if(typeof host.invoke!=='function')fail('host import unavailable: state.remove');const key=pop(frame,'state_remove');if(key.type!=='string')fail('state_remove key must be string');const ok=await host.invoke('state.remove',[key.value]);push(frame,Object.freeze({type:'bool',value:ok===true}));break;}
      case'value_sha256':{push(frame,await valueSha256(pop(frame,'value_sha256')));break;}
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
