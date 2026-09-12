const core=window.RiftOSCore;
if(!core)throw new Error("RiftOSCore must load before RiftShellBatch");

const NON_REVERSIBLE=new Set(["git","gh","github","mount","umount","kill","open","browser","batch"]);
const PROTECTED_ROOTS=new Set(["/","/home","/apps","/system","/workspace","/downloads","/documents","/mounts"]);

function tokenize(raw){const out=[];String(raw||"").replace(/"([^"]*)"|'([^']*)'|([^\s]+)/g,(_,a,b,c)=>{out.push(a??b??c);return "";});return out;}
function split(script){
  const commands=[];let current="",quote="",escaped=false;
  const flush=()=>{const value=current.trim();if(value)commands.push(value);current="";};
  for(let i=0;i<String(script||"").length;i++){
    const char=script[i];
    if(escaped){current+=char;escaped=false;continue;}
    if(char==="\\"){current+=char;escaped=true;continue;}
    if(quote){current+=char;if(char===quote)quote="";continue;}
    if(char==='"'||char==="'"){quote=char;current+=char;continue;}
    if(char===";"||char==="\n"){flush();continue;}
    if(char==="&"&&script[i+1]==="&"){flush();i++;continue;}
    current+=char;
  }
  if(quote)throw new Error("Unterminated quote in batch command");flush();
  if(!commands.length)throw new Error("usage: batch [--dry-run] <command> ; <command> ; ...");
  return commands;
}
function mutationTargets(command,state,resolve){
  const args=tokenize(command),cmd=(args.shift()||"").toLowerCase();
  if(NON_REVERSIBLE.has(cmd))throw new Error(`${cmd} cannot run inside an atomic batch; run it separately`);
  if(cmd==="workspace"&&(args[0]||"").toLowerCase()==="rollback")throw new Error("workspace rollback cannot run inside another atomic batch");
  const path=value=>resolve(state.cwd,value);
  if(["write","touch","mkdir","rm"].includes(cmd))return args[0]?[path(args[0])]:[];
  if(cmd==="cp")return args[1]?[path(args[1])]:[];
  if(cmd==="mv")return args.length>1?[path(args[0]),path(args[1])]:[];
  if(cmd==="zip")return args[1]?[path(args[1])]:[];
  if(cmd==="unzip")return args[1]?[path(args[1])]:[];
  return [];
}
async function run(script,{state,execute,print=console.log,resolve,dryRun=false}={}){
  if(!state||typeof execute!=="function"||typeof resolve!=="function")throw new Error("RiftShell batch host is incomplete");
  const commands=split(script),plannedCwd=state.cwd;
  if(dryRun){
    for(let i=0;i<commands.length;i++)print(`[${i+1}/${commands.length}] ${commands[i]}`);
    print(`Dry run complete: ${commands.length} command(s); no files changed.`);return {dryRun:true,commands:commands.length};
  }
  const batchId=`batch-${Date.now()}-${Math.random().toString(16).slice(2)}`,backupRoot=`/system/riftshell-batches/${batchId}`,backups=new Map(),order=[];
  const backup=async target=>{
    if(PROTECTED_ROOTS.has(target))throw new Error(`Atomic batch cannot replace RiftFS root ${target}`);
    if(target.startsWith("/system/riftshell-batches/"))throw new Error("Batch commands cannot target the rollback area");
    if(backups.has(target))return;
    const stat=await core.fs.stat(target),record={target,existed:!!stat,backup:null};
    if(stat){record.backup=`${backupRoot}/${order.length}`;await core.fs.copy(target,record.backup,{overwrite:false});}
    backups.set(target,record);order.push(record);
  };
  const rollback=async()=>{
    const errors=[];
    for(const record of [...order].reverse()){
      try{if(await core.fs.stat(record.target))await core.fs.remove(record.target);if(record.existed)await core.fs.copy(record.backup,record.target,{overwrite:false});}
      catch(error){errors.push(`${record.target}: ${error.message}`);}
    }
    await core.fs.remove(backupRoot).catch(()=>{});state.cwd=plannedCwd;
    if(errors.length)throw new Error(`Batch failed and rollback was incomplete: ${errors.join("; ")}`);
  };
  try{
    await core.fs.mkdir(backupRoot);
    for(let i=0;i<commands.length;i++){
      const command=commands[i],targets=mutationTargets(command,state,resolve);for(const target of targets)await backup(target);
      print(`[${i+1}/${commands.length}] ${command}`);await execute(command);
    }
    await core.fs.remove(backupRoot).catch(()=>{});print(`Batch complete: ${commands.length} command(s).`);return {commands:commands.length};
  }catch(error){
    try{await rollback();}catch(rollbackError){throw new Error(`${error.message}. ${rollbackError.message}`);}
    throw new Error(`${error.message}. All batch filesystem changes were rolled back.`);
  }
}

window.RiftShellBatch=Object.freeze({run,split});
console.info("[RiftShellBatch] atomic local command batches ready");
