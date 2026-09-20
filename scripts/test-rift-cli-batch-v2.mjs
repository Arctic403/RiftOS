import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const read=(path)=>readFileSync(path,'utf8');
const core=read('android/app/src/main/cpp/riftcli/rift_cli_core.cpp');
const shell=read('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt');
const host=read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const sandbox=read('android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt');
const oldBatch=read('src/riftshell-batch.js');

assert.ok(core.includes(String.raw`\"batchV2\":true`));
assert.ok(core.includes(String.raw`\"batchV2MaxSteps\":16`));
assert.ok(core.includes(String.raw`\"driverToolExecution\":\"push-first-jobs-with-poll-fallback\"`));

assert.match(shell,/MAX_CLI_BATCH_STEPS = 16/);
assert.match(shell,/MAX_CLI_BATCH_BYTES = 128 \* 1024/);
assert.match(shell,/MAX_CLI_BATCH_STEP_BYTES = 64 \* 1024/);
assert.match(shell,/MAX_CLI_BATCH_STEP_RESULT_BYTES = 128 \* 1024/);
assert.match(shell,/private fun parseCliBatchPlan/);
assert.match(shell,/mode == "execute" \|\| mode == "validate"/);
assert.match(shell,/failurePolicy == "stop" \|\| failurePolicy == "continue"/);
assert.match(shell,/Regex\("\[A-Za-z0-9\._-\]\{1,64\}"\)/);
assert.match(shell,/seen\.add\(id\)/);
assert.match(shell,/validateCliBatchTool\(name, toolArgs\)/);
assert.match(shell,/rawToolArgs == null \|\| rawToolArgs === JSONObject\.NULL \|\| rawToolArgs is JSONObject/,'tool args must fail prevalidation when present but not an object');
assert.match(shell,/CLI_BATCH_ALLOWED_SHELL_COMMANDS/);
assert.match(shell,/Unsupported RiftCLI Batch V2 shell command/);
assert.match(shell,/commandName != "rift-cli"/);
assert.match(shell,/commandName != "batch"/);
assert.match(shell,/origin = "rift-cli-batch"/);
assert.match(shell,/RiftCliExecutionGate\.tryReserve\(jobId\)/);
assert.match(shell,/RiftCliExecutionGate\.release\(jobId\)/);
assert.match(shell,/emitCliShellJob\(job, "batch\.submitted"/);
assert.match(shell,/emitCliShellJob\(job, "batch\.started"/);
assert.match(shell,/"batch\.step\.started"/);
assert.match(shell,/"batch\.step\.completed"/);
assert.match(shell,/"batch\.step\.failed"/);
assert.match(shell,/"batch\.cancelled"/);
assert.match(shell,/"completed_with_failures"/);
assert.match(shell,/val terminal = job\.status in setOf\([\s\S]{0,220}"completed_with_failures"/,'continue-on-error terminal events must be marked terminal');
assert.match(shell,/"rift_cli_batch" -> startCliBatch/);
assert.match(shell,/kind", if \(job\.lane == "batch"\) "rift-cli-batch" else "rift-shell"/);
assert.match(shell,/RiftCLI Batch V2 cancellation observed after step/);
assert.match(shell,/catch \(error: InterruptedException\) \{\s*throw error/,'batch steps must not swallow cancellation interrupts');
assert.ok(
  shell.indexOf('if (job.cancelRequested || Thread.currentThread().isInterrupted) {\n                                throw InterruptedException("RiftCLI Batch V2 cancellation observed after step') <
    shell.indexOf('if (!ok && plan.failurePolicy == "stop")'),
  'cancellation must be classified before stop-on-error after a step'
);

assert.match(host,/MAX_CLI_BATCH_TOOL_ARGS_BYTES = 64 \* 1024/);
assert.match(host,/internal fun validateCliBatchTool/);
assert.match(host,/internal fun executeCliBatchTool/);
assert.match(host,/"rift_workspace_exec"/);
assert.match(host,/"rift_cli_batch"/);
assert.match(host,/"rift_cli_job_list"/);
assert.match(host,/"rift_cli_job_poll"/);
assert.match(host,/"rift_cli_job_cancel"/);
assert.match(host,/sandbox\.executeCliBatchRequest\(request\.toString\(\)\)/);
assert.match(sandbox,/internal fun executeCliBatchRequest/);
assert.match(sandbox,/executeRequest\(raw, "rift-cli-batch"\)/);

assert.match(oldBatch,/DISABLED: RiftShell batch commands are disabled/);
assert.match(oldBatch,/disabled:true/);
assert.ok(!shell.includes('"batch" ->'),'native RiftShell must not resurrect the retired batch command');

console.log('ok - RiftCLI N1.6 Batch V2 is bounded, prevalidated, single-authority and separate from retired batch paths');
