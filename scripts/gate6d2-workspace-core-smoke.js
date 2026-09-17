const vmText=await core.fs.readText('/workspace/RiftOS-main/src/riftvm.js');
const coreText=await core.fs.readText('/workspace/RiftOS-main/src/riftpp-core.js');
lab.assert(vmText&&coreText,'missing patched source');
const vmUrl=URL.createObjectURL(new Blob([vmText],{type:'text/javascript'}));
let coreUrl=null;
try{
  const patchedCore=coreText.replace("from './riftvm.js'",`from '${vmUrl}'`);
  coreUrl=URL.createObjectURL(new Blob([patchedCore],{type:'text/javascript'}));
  const vm=await import(vmUrl),compiler=await import(coreUrl);
  lab.assert(compiler.RIFTPP_CORE_VERSION==='0.7.2-bootstrap','core version');
  const source=`riftpp 1\nmodule proof.gate6d2_workspace\nfn main() allow [repair_eval] {\n let source: string = repair_input_source()\n print(string_len(source))\n let found: Option<u32> = string_find(source, "retrun", 0)\n match found { Option.Some(index) => { print(index) } Option.None => { print(999) } }\n let fixed: string = string_replace(source, 0, 6, "return")\n print(string_slice(fixed, 0, 6))\n print(repair_compile_test(fixed, repair_expected_output()))\n print(repair_case_id())\n}\n`;
  const compiled=compiler.compileRiftPlusPlusCoreV1(source);
  lab.assert(JSON.stringify(compiled.executable.imports)===JSON.stringify(['repair.caseId','repair.compileTest','repair.expected','repair.source']),'imports');
  const output=[];
  const result=await vm.executeRiftExecutable(compiled.executable,{write:v=>output.push(String(v)),invoke:async(method,args)=>{if(method==='repair.source')return 'retrun 7';if(method==='repair.expected')return '7';if(method==='repair.caseId')return 'workspace-smoke';if(method==='repair.compileTest'){lab.assert(args[0]==='return 7'&&args[1]==='7','compileTest args');return 'correct';}throw new Error('unexpected '+method);}});
  lab.assert(JSON.stringify(output)===JSON.stringify(['8','0','return','correct','workspace-smoke']),'output '+JSON.stringify(output));
  let missingEffect=false;try{compiler.compileRiftPlusPlusCoreV1(source.replace('fn main() allow [repair_eval] {','fn main() {'));}catch(e){missingEffect=String(e).includes('missing required capability repair_eval');}
  lab.assert(missingEffect,'repair_eval fail closed');
  const ops=Object.values(compiled.executable.functions).flatMap(fn=>fn.code.map(x=>x.op));
  const resultRow={compiler:compiler.RIFTPP_CORE_VERSION,format:compiled.executable.format,abi:compiled.executable.abi,imports:compiled.executable.imports,output,steps:result.steps,stringOps:['string_len','string_find','string_slice','string_replace'].every(x=>ops.includes(x)),missingEffectRejected:missingEffect};
  lab.log('GATE6D2_WORKSPACE_CORE_SMOKE_OK');lab.log(JSON.stringify(resultRow));return resultRow;
} finally {if(coreUrl)URL.revokeObjectURL(coreUrl);URL.revokeObjectURL(vmUrl);}
