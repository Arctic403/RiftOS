const core=globalThis.RiftOSCore;
const repo=globalThis.RiftRepo;
const vault=globalThis.RiftVault;
const memory=globalThis.RiftMemory;
if(!core?.fs||!repo||!vault||!memory)throw new Error("RiftBuild requires RiftOSCore, RiftRepo, RiftVault and RiftMemory");

const ROOT="/system/riftbuild/v1";
const RUN_ROOT=`${ROOT}/runs`;
const ARTIFACT_ROOT="/documents/builds";
const nowISO=()=>new Date().toISOString();
const runId=()=>`build-${Date.now()}-${crypto.randomUUID?.()||Math.random().toString(36).slice(2)}`;
async function ensure(){await core.ready;await core.fs.mkdir(ROOT);await core.fs.mkdir(RUN_ROOT);await core.fs.mkdir(ARTIFACT_ROOT);}
function normalizeProject(value,cwd="/workspace"){const raw=String(value||cwd||"/workspace").trim(),path=core.path.normalize(raw.startsWith("/")?raw:core.path.join(cwd||"/workspace",raw));if(path!=="/workspace"&&!path.startsWith("/workspace/"))throw new Error("RiftBuild projects must live under /workspace");return path;}

async function detectProject(path){
  const root=normalizeProject(path),stat=await core.fs.stat(root);if(!stat||stat.kind!=="directory")throw new Error(`Build project is not a directory: ${root}`);const exists=async child=>!!(await core.fs.stat(core.path.join(root,child)).catch(()=>null));
  return {root,name:core.path.basename(root)||"workspace",gradle:await exists("gradlew")||await exists("build.gradle")||await exists("build.gradle.kts")||await exists("settings.gradle")||await exists("settings.gradle.kts"),gradleWrapper:await exists("gradlew"),androidApp:await exists("app/src/main/AndroidManifest.xml")||await exists("android/app/src/main/AndroidManifest.xml"),packageJson:await exists("package.json")};
}
async function doctor(project=null,cwd="/workspace"){
  await ensure();const capabilities=core.native.capabilities?.()||{},storage=await core.fs.estimate(),target=project?await detectProject(normalizeProject(project,cwd)).catch(error=>({root:normalizeProject(project,cwd),error:error.message})):null,blockers=[],warnings=[];
  const nativeExecutor=capabilities.localBuildExecutor===true;if(!nativeExecutor)blockers.push("This APK does not expose a trusted local build executor yet; RiftBuild will not fake Java/Gradle/SDK/NDK execution.");if(target?.error)blockers.push(target.error);else if(target&&!target.gradle)warnings.push("No Gradle project markers were detected; build planning remains available but Android APK execution is not implied.");if(Number(storage?.free||0)<512*1024*1024)warnings.push("Less than 512 MiB free local storage is available.");
  return {available:true,version:1,ready:blockers.length===0,nativeExecutor,project:target,storage,blockers,warnings,checkedAt:nowISO(),requiredChecks:["Java runtime","Gradle wrapper/runtime","Android SDK/aapt2","D8/R8","NDK/Clang when native","linker","signing tools","free storage","ABI targets"]};
}
async function plan(project,target="universal",cwd="/workspace"){
  await ensure();const detected=await detectProject(normalizeProject(project,cwd)),rows=await core.fs.list(detected.root,{recursive:true}),files=rows.filter(row=>row.kind==="file"),bytes=files.reduce((sum,row)=>sum+Number(row.size||0),0),sourceFiles=files.filter(row=>/\.(?:kt|java|cpp|c|cc|h|hpp|js|mjs|ts|tsx|css|html|xml|gradle|kts|json)$/i.test(row.path));const normalizedTarget=String(target||"universal").toLowerCase();if(!["arm32","arm64","universal"].includes(normalizedTarget))throw new Error("Build target must be arm32, arm64 or universal");return {format:"riftbuild-plan-v1",project:detected,target:normalizedTarget,createdAt:nowISO(),files:files.length,sourceFiles:sourceFiles.length,workingSetBytes:bytes,stages:["planning","hydrating","compiling","linking","packaging","signing","verifying","artifact backup"],artifactRoot:core.path.join(ARTIFACT_ROOT,detected.name),memory:await memory.status()};
}
async function build(project,target="universal",cwd="/workspace",options={}){
  const projectPath=normalizeProject(project,cwd),health=await doctor(projectPath,cwd);if(!health.ready)throw new Error(`RiftBuild doctor blocked local execution: ${health.blockers.join(" ")}`);const planValue=await plan(projectPath,target,cwd),id=runId(),record={format:"riftbuild-run-v1",id,state:"running",startedAt:nowISO(),plan:planValue,source:options.checkpoint?{checkpoint:options.checkpoint}:{workingTree:true}};await core.fs.writeJSON(`${RUN_ROOT}/${id}.json`,record);
  try{const result=await core.native.call("build.execute",{project:projectPath,target:planValue.target,checkpoint:options.checkpoint||null,runId:id,artifactRoot:planValue.artifactRoot});Object.assign(record,{state:"complete",completedAt:nowISO(),result});await core.fs.writeJSON(`${RUN_ROOT}/${id}.json`,record);return record;}catch(error){Object.assign(record,{state:"failed",failedAt:nowISO(),error:error.message});await core.fs.writeJSON(`${RUN_ROOT}/${id}.json`,record).catch(()=>{});throw error;}
}
async function runs(limit=20){await ensure();const rows=await core.fs.list(RUN_ROOT,{recursive:false}).catch(()=>[]),out=[];for(const row of rows.filter(r=>r.kind==="file").sort((a,b)=>Number(b.modified||0)-Number(a.modified||0)).slice(0,Math.max(1,Math.min(100,Number(limit)||20)))){const value=await core.fs.readJSON(row.path,null);if(value)out.push(value);}return out;}
async function artifacts(project=null){await ensure();const root=project?core.path.join(ARTIFACT_ROOT,core.path.basename(normalizeProject(project))):ARTIFACT_ROOT;return await core.fs.list(root,{recursive:true}).catch(()=>[]);}

async function run(args,print=console.log,context={}){
  const list=[...args],cmd=(list.shift()||"doctor").toLowerCase(),cwd=context.cwd||"/workspace";if(cmd==="help")return print("rift build doctor [project] | plan <project> [arm32|arm64|universal] | run <project> [target] | runs [limit] | artifacts [project] | cache status | cache prune [max-bytes]");if(cmd==="doctor")return print(JSON.stringify(await doctor(list[0]||null,cwd),null,2));if(cmd==="plan"){if(!list[0])throw new Error("usage: rift build plan <project> [target]");return print(JSON.stringify(await plan(list[0],list[1]||"universal",cwd),null,2));}if(cmd==="run"||cmd==="build"){if(!list[0])throw new Error("usage: rift build run <project> [target]");return print(JSON.stringify(await build(list[0],list[1]||"universal",cwd),null,2));}if(cmd==="runs")return print(JSON.stringify(await runs(Number(list[0])||20),null,2));if(cmd==="artifacts")return print(JSON.stringify(await artifacts(list[0]||null),null,2));if(cmd==="cache"){const sub=(list.shift()||"status").toLowerCase();if(sub==="status")return print(JSON.stringify(await memory.status(),null,2));if(sub==="prune")return print(JSON.stringify(await memory.prune(Number(list[0])||512*1024*1024),null,2));throw new Error(`unknown rift build cache command: ${sub}`);}throw new Error(`unknown rift build command: ${cmd}`);
}

await ensure();
globalThis.RiftBuild=Object.freeze({version:1,root:ROOT,doctor,plan,build,runs,artifacts,run});
