import fs from 'node:fs';
import assert from 'node:assert/strict';

const read = file => fs.readFileSync(file, 'utf8');
const batch = read('android/app/src/main/java/com/riftos/app/RiftLocalAgentBatch.kt');
const host = read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const localAgent = read('android/app/src/main/java/com/riftos/app/RiftVortexLocalAgent.kt');
const shell = read('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt');
const shellServices = read('android/app/src/main/java/com/riftos/app/RiftNativeShellServices.kt');
const retired = read('src/riftshell-batch.js');
const gradle = read('android/app/build.gradle.kts');

assert.match(batch, /SCHEMA = "rift\.local-agent-batch\/1"/);
assert.match(batch, /MAX_STEPS = 16/);
assert.match(batch, /MAX_PLAN_BYTES = 512 \* 1024/);
assert.match(batch, /MAX_STEP_BYTES = 256 \* 1024/);
assert.match(batch, /MAX_RETAINED_STEP_RESULT_BYTES = 96 \* 1024/);
assert.match(batch, /MAX_PERSISTED_STORE_BYTES = 32 \* 1024 \* 1024/);
assert.match(batch, /Executors\.newSingleThreadExecutor/);
assert.match(batch, /activeJobId = AtomicReference<String\?>\(null\)/);
for (const action of ['submit', 'status', 'result', 'cancel', 'list']) {
  assert.ok(batch.includes(`"${action}" ->`), 'missing action: ' + action);
}
assert.match(batch, /jobs\.values\.firstOrNull \{ it\.requestId == requestId \}/);
assert.match(batch, /planDigest\(failurePolicy, normalizedSteps\)/);
assert.match(batch, /existing\.planSha256 == planSha256/);
assert.match(batch, /already bound to a different plan/);
assert.match(batch, /validatePlan\(rawSteps\)/);
assert.match(batch, /rawSteps\.length\(\) in 1\.\.MAX_STEPS/);
assert.match(batch, /require\(ids\.add\(id\)\)/);
assert.match(batch, /op in allowedOps/);
assert.match(batch, /Nested Local Agent batches are forbidden/);
assert.match(batch, /failurePolicy == "stop" \|\| failurePolicy == "continue"/);
assert.match(batch, /cancelled_may_have_applied/);
assert.match(batch, /failed_may_have_applied/);
assert.match(batch, /completed_with_failures/);
assert.match(batch, /object RiftLocalAgentExecutionGate/);
assert.match(batch, /tryReserveBatch\(jobId\)/);
assert.match(batch, /releaseBatch\(job\.id\)/);
assert.match(batch, /Unfinished Local Agent batch was not replayed after process restart/);
assert.match(batch, /val recoveredJobs = LinkedHashMap<String, BatchJob>\(\)/);
assert.match(batch, /jobs\.putAll\(recoveredJobs\)/);
assert.match(batch, /loaded = true/);
assert.match(batch, /AtomicFile\(File\(directory, "jobs-v1\.json"\)\)/);
assert.match(batch, /output\.fd\.sync\(\)/);
assert.match(batch, /RESULT_PAGE_MAX = 4/);
assert.match(batch, /resultTooLarge/);
assert.match(batch, /stepManifest/);
assert.match(batch, /runJobInternal\(context, job\)/);
assert.match(batch, /runCatching \{ persistLocked\(context\) \}/);
assert.match(batch, /RiftOsLocalAgent\.execute\(executionContext, request, job\.id\)/);
for (const forbidden of ['RiftCliHost', 'RiftCliExecutionGate', 'RiftNativeShell', 'RiftMcpRelayClient', 'SSE']) {
  assert.ok(!batch.includes(forbidden), 'batch owner crossed forbidden boundary: ' + forbidden);
}

assert.match(host, /tool\(\s*"rift_local_agent_batch"/);
assert.match(host, /RiftLocalAgentBatch\.execute\(activity \?: appContext, args\)/);
assert.match(host, /action == "submit" \|\| action == "cancel"/);
assert.match(host, /if \(requiresWrite\) allowRead\(\) && allowWrite\(\) else allowRead\(\)/);
assert.match(host, /"rift_local_agent_batch" -> \{/);
assert.match(host, /"rift_local_agent_batch",\s*"rift_cli_batch"/);
assert.match(host, /unfinished jobs are never replayed after process restart/i);
assert.match(localAgent, /object RiftOsLocalAgent/);
assert.match(localAgent, /RiftLocalAgentExecutionGate\.withAccess\(batchOwnerId\)/);
assert.match(localAgent, /private fun executeUnlocked\(context: Context, args: JSONObject\)/);
assert.match(shellServices, /return RiftLocalAgentExecutionGate\.withAccess \{/);
assert.match(gradle, /RiftLocalAgentBatch\.kt/);

assert.ok(!/^\s*"batch"\s*->/m.test(shell), 'retired native RiftShell batch must remain absent');
assert.match(retired, /DISABLED: RiftShell batch commands are disabled/);

console.log('ok - Local Agent batch jobs are bounded, persistent, direct, cancellable and isolated');
