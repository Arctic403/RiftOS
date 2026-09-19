const core=window.RiftOSCore;
if(!core)throw new Error("RiftOSCore must load before RiftShellBatch");

const DISABLED_MESSAGE="DISABLED: RiftShell batch commands are disabled because they can hang the agent/runtime. Do not use batch or batch --dry-run; use individual commands or MCP file operations instead.";

async function run(){
  throw new Error(DISABLED_MESSAGE);
}

function split(){
  throw new Error(DISABLED_MESSAGE);
}

window.RiftShellBatch=Object.freeze({
  disabled:true,
  status:"DISABLED",
  reason:"Known hang/glitch risk. Retained only as a fail-fast compatibility tombstone.",
  run,
  split
});
console.warn("[RiftShellBatch] DISABLED - fail-fast compatibility tombstone only");
