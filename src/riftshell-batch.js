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
async function preflight(commands,state,resolve){
  const planned={cwd:state.cwd},overlay=new Map();
  const stat=async path=>overlay.has(path)?overlay.get(path):core.fs.stat(path);
  const parent=path=>path.slice(0,path.lastIndexOf("/"))||"/";
  const directory=async path=>{const entry=await stat(path);if(!entry||!["directory","mount"].includes(entry.kind))throw new Error(`not a directory: ${path}`);};
  const ensureParents=async path=>{const dir=parent(path);if(dir===path)return;const entry=await stat(dir);if(entry){await directory(dir);return;}await ensureParents(dir);overlay.set(dir,{kind:"directory"});};
  const permission=async capability=>{if(!core.permissions?.has||!await core.permissions.has("terminal",capability))throw new Error(`Batch permission denied: ${capability}`);};
  const shapes={help:[0,0],sysinfo:[0,0],df:[0,0],ps:[0,0],apps:[0,0],permissions:[0,0],native:[0,0],pwd:[0,0],home:[0,0],clear:[0,0],uptime:[0,0],version:[0,0],cd:[0,1],ls:[0,2],tree:[0,1],stat:[1,1],cat:[1,1],head:[1,2],tail:[1,2],write:[1,Infinity],touch:[1,1],mkdir:[1,1],rm:[1,1],cp:[2,3],mv:[2,3],zip:[2,2],unzip:[2,2],workspace:[0,2]};
  for(const command of commands){
    const args=tokenize(command),cmd=(args.shift()||"").toLowerCase();
    const targets=mutationTargets(command,planned,resolve),shape=shapes[cmd];
    if(!shape)throw new Error(`Unknown batch command: ${cmd}`);
    if(args.length<shape[0]||args.length>shape[1])throw new Error(`Invalid arguments for ${cmd}`);
    if(["cp","mv"].includes(cmd)&&args.length===3&&!['--force','-f'].includes(args[2]))throw new Error(`Invalid option: ${args[2]}`);
    if(cmd==="ls"&&(args.filter(a=>!a.startsWith("-")).length>1||args.some(a=>a.startsWith("-")&&!/^-[Rr]+$/.test(a))))throw new Error("Invalid ls arguments");
    if(["head","tail"].includes(cmd)&&args[1]&&!/^[1-9][0-9]*$/.test(args[1]))throw new Error("Line count must be a positive integer");
    if(cmd==="workspace"&&(!["info","cd","ls","history"].includes(args[0]||"info")||(args.length===2&&args[0]!=="ls")))throw new Error("Invalid workspace arguments");
    await permission("fs.read");
    if(targets.length)await permission("fs.write");
    for(const target of targets){
      if(PROTECTED_ROOTS.has(target))throw new Error(`Atomic batch cannot replace RiftFS root ${target}`);
      if(target==="/system/riftshell-batches"||target.startsWith("/system/riftshell-batches/"))throw new Error("Batch commands cannot target the rollback area");
      const route=core.fs.route?.(target);
      if(route?.mount||target.startsWith("/mounts/"))throw new Error("Mounted provider mutations are not eligible for batch rollback; run separately");
    }
    const path=resolve(planned.cwd,args[0]||planned.cwd);
    if(cmd==="cd"||cmd==="home"||cmd==="workspace"&&args[0]==="cd"){
      const next=cmd==="home"||cmd==="cd"&&!args[0]?"/home":cmd==="workspace"?"/workspace":path;
      await directory(next);planned.cwd=next;continue;
    }
    if(["cat","head","tail","stat","tree","ls","cp","mv","zip","unzip"].includes(cmd)){
      const source=cmd==="ls"?resolve(planned.cwd,args.find(a=>!a.startsWith("-"))||planned.cwd):path;
      const entry=await stat(source);if(!entry)throw new Error(`path not found: ${source}`);
      if(["cat","head","tail","unzip"].includes(cmd)&&entry.kind!=="file")throw new Error(`not a file: ${source}`);
    }
    if(["write","touch","mkdir"].includes(cmd)){
      const entry=await stat(path);
      if(entry&&((cmd==="mkdir")!==(entry.kind==="directory")))throw new Error(`Path kind conflict: ${path}`);
      if(cmd==="write"&&new TextEncoder().encode(args.slice(1).join(" ")).length>4*1024*1024)throw new Error("Text write exceeds the 4 MiB UTF-8 limit");
      await ensureParents(path);overlay.set(path,{kind:cmd==="mkdir"?"directory":"file"});
    }
    if(["cp","mv","zip","unzip"].includes(cmd)){
      const to=resolve(planned.cwd,args[1]),entry=await stat(to),source=await stat(path);
      if(to===path||to.startsWith(path+"/")||path.startsWith(to+"/"))throw new Error("Source and destination overlap");
      if(entry&&!(args.includes("--force")||args.includes("-f"))&&cmd!=="unzip")throw new Error(`Destination exists: ${to}`);
      if(cmd==="unzip"&&entry&&entry.kind!=="directory")throw new Error(`not a directory: ${to}`);
      await ensureParents(to);overlay.set(to,{kind:cmd==="zip"?"file":cmd==="unzip"?"directory":source.kind});
      if(["cp","mv"].includes(cmd)&&source.kind==="directory"){
        const rows=await core.fs.list(path,{recursive:true});
        for(const row of rows)overlay.set(to+row.path.slice(path.length),row);
        for(const [key,value] of [...overlay])if(key.startsWith(path+"/"))overlay.set(to+key.slice(path.length),value);
      }
    }
    if(cmd==="rm"||cmd==="mv"){
      for(const row of await core.fs.list(path,{recursive:true}).catch(()=>[]))overlay.set(row.path,null);
      for(const key of [...overlay.keys()])if(key.startsWith(path+"/"))overlay.set(key,null);
      overlay.set(path,null);
    }
  }
}
async function run(script,{state,execute,print=console.log,resolve,dryRun=false}={}){
  if(!state||typeof execute!=="function"||typeof resolve!=="function")throw new Error("RiftShell batch host is incomplete");
  const commands=split(script),plannedCwd=state.cwd;
  await preflight(commands,state,resolve);
  if(dryRun){
    for(let i=0;i<commands.length;i++)print(`[${i+1}/${commands.length}] ${commands[i]}`);
    print(`Dry run complete: ${commands.length} command(s); no files changed.`);return {dryRun:true,commands:commands.length};
  }
  const batchId=`batch-${Date.now()}-${Math.random().toString(16).slice(2)}`,backupRoot=`/system/riftshell-batches/${batchId}`,backups=new Map(),order=[];
  const backup=async target=>{
    if(PROTECTED_ROOTS.has(target))throw new Error(`Atomic batch cannot replace RiftFS root ${target}`);
    if(target==="/system/riftshell-batches"||target.startsWith("/system/riftshell-batches/"))throw new Error("Batch commands cannot target the rollback area");
    // Back up the first missing ancestor too, so implicit mkdirs roll back.
    let ancestor=target.slice(0,target.lastIndexOf("/"))||"/";
    while(ancestor!=="/"&&!await core.fs.stat(ancestor)){target=ancestor;ancestor=ancestor.slice(0,ancestor.lastIndexOf("/"))||"/";}
    if(backups.has(target))return;
    const stat=await core.fs.stat(target),record={target,existed:!!stat,backup:null};
    if(stat){record.backup=`${backupRoot}/${order.length}`;await core.fs.copy(target,record.backup,{overwrite:false});}
    backups.set(target,record);order.push(record);
  };
  const rollback=async()=>{
    const errors=[];
    for(const record of [...order].reverse()){
      try{if(await core.fs.stat(record.target))if(await core.fs.remove(record.target)===false)throw new Error("Could not clear rollback target");if(record.existed)await core.fs.copy(record.backup,record.target,{overwrite:false});}
      catch(error){errors.push(`${record.target}: ${error.message}`);}
    }
    state.cwd=plannedCwd;
    if(errors.length)throw new Error(`Batch failed and rollback was incomplete: ${errors.join("; ")}. Recovery files retained at ${backupRoot}`);
    await core.fs.remove(backupRoot).catch(()=>{});
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
