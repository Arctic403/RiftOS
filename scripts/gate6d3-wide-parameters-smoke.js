const vmText=await core.fs.readText('/workspace/RiftOS-main/src/riftvm.js');
const coreText=await core.fs.readText('/workspace/RiftOS-main/src/riftpp-core.js');
const vmUrl=URL.createObjectURL(new Blob([vmText],{type:'text/javascript'}));
let coreUrl=null;
try{
  coreUrl=URL.createObjectURL(new Blob([coreText.replace("from './riftvm.js'","from '"+vmUrl+"'")],{type:'text/javascript'}));
  const vm=await import(vmUrl);
  const compiler=await import(coreUrl);
  lab.assert(compiler.RIFTPP_CORE_VERSION==='0.10.0-bootstrap','core version');
  const makeSource=n=>{const values=Array.from({length:n},()=> '0.0').join(',');return 'riftpp 1\\nmodule proof.capacity_'+n+'\\nfn main() { let weights: Vec<f64, '+n+'> = ['+values+'] print(weights.len()) print(value_sha256(weights)) }\\n';};
  const results=[];
  for(const n of [96,144]){
    const compiled=compiler.compileRiftPlusPlusCoreV1(makeSource(n));
    const output=[];
    await vm.executeRiftExecutable(compiled.executable,{write:v=>output.push(String(v))});
    lab.assert(output[0]===String(n),'capacity '+n+' length');
    lab.assert(/^[a-f0-9]{64}$/.test(output[1]),'capacity '+n+' hash');
    results.push({capacity:n,bytes:new TextEncoder().encode(JSON.stringify(compiled.executable)).byteLength,hash:output[1]});
  }
  let rejected=false;
  try{compiler.compileRiftPlusPlusCoreV1('riftpp 1\\nmodule bad.capacity\\nfn main() { let weights: Vec<f64, 257> = [] print(weights.len()) }\\n');}catch(e){rejected=String(e).includes('Vec capacity must be 1..256');}
  lab.assert(rejected,'257 must reject');
  lab.log('GATE6D3_WIDE_PARAMETERS_OK');
  lab.log(JSON.stringify(results));
  return {status:'GATE6D3_WIDE_PARAMETERS_OK',compiler:compiler.RIFTPP_CORE_VERSION,results,rejected257:rejected};
}finally{if(coreUrl)URL.revokeObjectURL(coreUrl);URL.revokeObjectURL(vmUrl);}