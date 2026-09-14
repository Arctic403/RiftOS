const repo=globalThis.RiftRepo;
const vault=globalThis.RiftVault;
const build=globalThis.RiftBuild;
const memory=globalThis.RiftMemory;
if(!repo||!vault||!build||!memory)throw new Error("RiftLocalPlatform requires RiftRepo, RiftVault, RiftBuild and RiftMemory");

async function status(cwd="/workspace"){
  let repoState=null;try{repoState=await repo.status(null,cwd);}catch(error){repoState={available:false,reason:error.message};}
  return {format:"rift-local-platform-v1",repo:repoState,vault:await vault.status(),build:await build.doctor(null,cwd),memory:await memory.status()};
}
async function run(args,print=console.log,context={}){
  const list=[...args],family=(list.shift()||"status").toLowerCase();
  if(family==="help")return print("rift status | repo ... | vault ... | build ... | memory ...\nUse `rift <family> help` for detailed commands.");
  if(family==="status")return print(JSON.stringify(await status(context.cwd||"/workspace"),null,2));
  if(family==="repo")return repo.run(list,print,context);
  if(family==="vault")return vault.run(list,print,context);
  if(family==="build")return build.run(list,print,context);
  if(family==="memory")return memory.run(list,print,context);
  throw new Error(`unknown rift subsystem: ${family}`);
}

globalThis.RiftLocalPlatform=Object.freeze({version:1,status,run});
