import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const read=(path)=>readFileSync(path,'utf8');
const core=read('android/app/src/main/cpp/riftcli/rift_cli_core.cpp');
const shell=read('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt');
const host=read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const localBatch=read('android/app/src/main/java/com/riftos/app/RiftLocalAgentBatch.kt');
const oldBatch=read('src/riftshell-batch.js');

assert.ok(core.includes(String.raw`\"batchV2\":false`));
assert.ok(core.includes(String.raw`\"batchV2MaxSteps\":0`));
assert.ok(core.includes(String.raw`\"batchOwner\":\"riftos-local-agent\"`));
assert.match(core,/RiftCLI-owned batching is retired/);

assert.match(
  shell,
  /"rift_cli_batch" -> throw IllegalStateException\([\s\S]{0,180}RiftCLI Batch V2 is retired/
);
assert.ok(
  !/"rift_cli_batch"\s*->\s*startCliBatch/.test(shell),
  'RiftCLI batch must not route into the legacy batch executor'
);

assert.match(host,/tool\(\s*"rift_local_agent_batch"/);
assert.match(host,/RiftLocalAgentBatch\.execute\(activity \?: appContext, args\)/);
assert.match(localBatch,/SCHEMA = "rift\.local-agent-batch\/1"/);
assert.match(localBatch,/MAX_STEPS = 16/);
assert.match(localBatch,/RiftLocalAgentExecutionGate/);
assert.match(localBatch,/RiftOsLocalAgent\.execute\(executionContext, request, job\.id\)/);

assert.match(oldBatch,/DISABLED: RiftShell batch commands are disabled/);
assert.match(oldBatch,/disabled:true/);
assert.ok(!/^\s*"batch"\s*->/m.test(shell),'native RiftShell must not resurrect the retired batch command');

console.log('ok - RiftCLI batching is retired and direct Local Agent batching is the only active batch authority');
