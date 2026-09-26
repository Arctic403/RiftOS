import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const read=(path)=>readFileSync(path,'utf8');
const core=read('android/app/src/main/cpp/riftcli/rift_cli_core.cpp');
const shell=read('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt');
const host=read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const sandbox=read('android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt');
const jobStore=read('android/app/src/main/java/com/riftos/app/RiftCliPersistentJobStore.kt');
const recoveryPolicy=read('android/app/src/main/java/com/riftos/app/RiftCliRecoveryPolicy.kt');
const localAgent=read('android/app/src/main/java/com/riftos/app/RiftVortexLocalAgent.kt');
const mcpServer=read('android/app/src/main/java/com/riftos/app/RiftMcpServer.kt');
const gradle=read('android/app/build.gradle.kts');
const oldBatch=read('src/riftshell-batch.js');
const transportValidator=read('scripts/validate-rift-transport.mjs');

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
const cancellationAfterStep = shell.indexOf('RiftCLI Batch V2 cancellation observed after step');
const cancellationGuardAfterStep = shell.lastIndexOf(
  'if (job.cancelRequested || Thread.currentThread().isInterrupted)',
  cancellationAfterStep
);
const stopOnErrorAfterCancellation = shell.indexOf(
  'if (!ok && plan.failurePolicy == "stop")',
  cancellationAfterStep
);
assert.ok(
  cancellationAfterStep >= 0 &&
  cancellationGuardAfterStep >= 0 &&
  cancellationGuardAfterStep < cancellationAfterStep &&
  stopOnErrorAfterCancellation > cancellationAfterStep,
  'cancellation must still be classified before ordinary stop-on-error after a step'
);
assert.match(shell,/plan\.rollbackPolicy == "on-failure"/,
  'rollback-enabled failed steps may transition into rollback before terminal cancellation classification');
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

assert.match(gradle,/RiftCliPersistentJobStore\.kt/);

assert.match(jobStore,/class RiftCliPersistentJobStore/);
assert.match(jobStore,/AtomicFile/);
assert.match(jobStore,/MAX_JOB_FILES = 32/);
assert.match(jobStore,/MAX_JOB_BYTES = 4 \* 1024 \* 1024L/);
assert.match(jobStore,/pruneLocked\(forceTerminalTrim = !target\.exists\(\)\)/);
assert.match(jobStore,/private fun pruneLocked\(forceTerminalTrim: Boolean = false\)/);
assert.match(jobStore,/forceTerminalTrim && readable\.size >= MAX_JOB_FILES/);
assert.match(jobStore,/indexOfFirst \{ it\.second\.optString\("status"\) in TERMINAL_STATUSES \}/);
assert.match(jobStore,/TERMINAL_RETENTION_MS = 24 \* 60 \* 60 \* 1000L/);
assert.match(jobStore,/payloadSha256/);
assert.match(jobStore,/recoverInterruptedJobs\(\)/);
assert.match(jobStore,/"recovery_required"/);
assert.match(jobStore,/"blindReplayAllowed", false/);
assert.match(jobStore,/"retrySafeResumeRequired", true/);
assert.match(jobStore,/"released_on_process_loss"/);


const driverJobControl=core.match(/bool isDriverJobControl\(const std::string& name\) \{([\s\S]*?)\n\}/)?.[1] ?? '';
assert.ok(driverJobControl.includes('rift_cli_job_list'));
assert.ok(driverJobControl.includes('rift_cli_job_poll'));
assert.ok(driverJobControl.includes('rift_cli_job_cancel'));
assert.ok(!driverJobControl.includes('rift_cli_job_recover'),
  'recovery/resume is authority-bearing and must require the explicit CLI enable gate');

assert.match(gradle,/RiftCliRecoveryPolicy\.kt/);
assert.match(recoveryPolicy,/rift\.cli-recovery-policy\/1/);
assert.match(recoveryPolicy,/policyOwner", "riftos"/);
assert.match(recoveryPolicy,/callerMayOverride", false/);
assert.match(recoveryPolicy,/wholeJobReplayAllowed", false/);
assert.match(recoveryPolicy,/automaticRetryAllowed", false/);
assert.match(recoveryPolicy,/ROLLBACK_CAPABLE_SHELL_COMMANDS = setOf\("write", "touch", "mkdir"\)/);
assert.match(recoveryPolicy,/rollbackSupported = operation in ROLLBACK_CAPABLE_SHELL_COMMANDS/);
assert.match(recoveryPolicy,/RETRY_SAFE_TOOLS/);
assert.match(recoveryPolicy,/"rift_read_text"/);
assert.match(recoveryPolicy,/MUTATING_TOOLS/);
assert.match(recoveryPolicy,/"rift_write_text"/);
assert.match(recoveryPolicy,/RETRY_SAFE_SHELL_COMMANDS/);
assert.match(recoveryPolicy,/"tree"/);

assert.match(jobStore,/JSONObject\(row\.optJSONObject\("recoveryMetadata"\)\?\.toString\(\) \?: "\{\}"\)/,
  'process-loss recovery must preserve the pre-crash step journal');

assert.match(shell,/executionCwd/);
assert.match(shell,/"executionCwd", job\.executionCwd/);
assert.match(shell,/"currentStepCwd", currentCwd/);
assert.match(shell,/private fun submitCliBatchExecution/);
assert.match(shell,/private fun recoverCliPersistedJob/);
assert.match(shell,/action in setOf\("resume", "fail", "rollback"\)/);
assert.match(shell,/Recovered RiftCLI job failed closed by explicit recovery resolution/);
assert.match(shell,/private fun submitRecoveredCliRollback/);
assert.match(shell,/validateRecoveredRollbackJournal\(plan, currentStep, journal\)/);
assert.match(shell,/parseCliBatchPlan\(persistedPlan, toolHost\)/,
  'recovery must revalidate the normalized full plan under current authority rules');
assert.match(shell,/recomputedPlanHash != storedPlanHash/);
assert.match(shell,/stepRows\.length\(\) != completedSteps/);
assert.match(shell,/currentStep == completedSteps \+ 1/);
assert.match(shell,/RiftCliRecoveryPolicy\.forTool/);
assert.match(shell,/RiftCliRecoveryPolicy\.forShell/);
assert.match(shell,/policy\.optBoolean\("retrySafe", false\) && policy\.optBoolean\("idempotent", false\)/);
assert.match(shell,/RiftCliExecutionGate\.tryReserve\(jobId\)/);
assert.match(shell,/state", "reserved_after_recovery"/);
assert.match(shell,/initialStepRows = stepRows/);
assert.match(shell,/recovered = true/);
assert.match(shell,/"wholeJobReplayAllowed", false/);
assert.match(shell,/"rift_cli_job_recover" ->/);
assert.match(host,/"rift_cli_job_recover"/);

assert.match(shell,/private val cliJobStore = RiftCliPersistentJobStore/);
assert.match(shell,/private fun persistCliShellJob/);
assert.match(shell,/private fun cliBatchPersistentPlan/);
assert.match(shell,/private fun sha256Utf8/);
assert.match(shell,/planHash = sha256Utf8\(persistentPlan\.toString\(\)\)/);
assert.match(shell,/"rift\.cli-authority-lease\/1"/);
assert.match(shell,/"authorizationBypass", false/);
assert.match(shell,/"perOperationAuthorizationRequired", true/);
assert.match(shell,/"observerValidatorBypass", false/);
assert.match(shell,/job\.currentStep = index/);
assert.match(shell,/job\.completedSteps = executedSteps/);
assert.match(shell,/job\.stepResults = JSONArray\(stepRows\.toString\(\)\)/);
assert.match(shell,/"currentStepState", "started"/);
assert.match(shell,/"currentStepState", "completed"/);
const batchSubmittedEvent = shell.indexOf('emitCliShellJob(job, "batch.submitted"');
const batchSubmittedPersist = shell.lastIndexOf('persistCliShellJob(job)', batchSubmittedEvent);
assert.ok(batchSubmittedEvent >= 0 && batchSubmittedPersist >= 0 && batchSubmittedPersist < batchSubmittedEvent,
  'Batch V2 must persist the submitted job before emitting batch.submitted');

const batchStartedEvent = shell.indexOf('emitCliShellJob(job, "batch.started"');
const batchStartedPersist = shell.lastIndexOf('persistCliShellJob(job)', batchStartedEvent);
assert.ok(batchStartedEvent >= 0 && batchStartedPersist >= 0 && batchStartedPersist < batchStartedEvent,
  'Batch V2 must persist running state before emitting batch.started');

const batchStepStartedEvent = shell.indexOf('"batch.step.started"', batchStartedEvent);
const batchStepStartedPersist = shell.lastIndexOf('persistCliShellJob(job)', batchStepStartedEvent);
assert.ok(batchStepStartedEvent >= 0 && batchStepStartedPersist >= 0 && batchStepStartedPersist < batchStepStartedEvent,
  'Batch V2 must persist current-step state before emitting batch.step.started');
assert.match(shell,/jobPersistenceRequired/);
assert.match(shell,/"cancelled_after_recovery"/);

assert.match(shell,/MAX_CLI_ROLLBACK_SNAPSHOT_BYTES = 128 \* 1024/);
assert.match(shell,/MAX_CLI_ROLLBACK_TOTAL_BYTES = 512 \* 1024/);
assert.match(shell,/rollbackPolicy == "none" \|\| rollbackPolicy == "on-failure"/);
assert.match(shell,/rollbackPolicy != "on-failure" \|\| failurePolicy == "stop"/);
assert.match(shell,/supportedMutation = policy\.optBoolean\("rollbackSupported", false\)/,
  'rollback-enabled plan validation must consume the system-owned rollback registry');
assert.match(shell,/rollbackPolicy=on-failure forbids tool mutation without an explicit rollback contract/);
assert.match(shell,/rollbackPolicy=on-failure forbids shell mutation without an explicit rollback contract/);
assert.match(shell,/\.put\("rollbackPolicy", plan\.rollbackPolicy\)/);

assert.match(shell,/private fun prepareCliBatchRollbackEntry/);
assert.match(shell,/private fun rollbackFileMatchesState/);
assert.match(shell,/private fun applyCliRollbackEntry/);
assert.match(shell,/private fun executeCliRollback/);
assert.match(shell,/rollbackPreparationFailed/);
assert.match(shell,/rollbackPostStateMismatch/);
assert.match(shell,/rollbackFileMatchesState\(/);
assert.match(shell,/executeCliRollback\(job, "failure"\)/,
  'stop-on-error rollback plans must enter the bounded rollback engine before terminalization');
assert.match(shell,/rift\.cli-rollback-entry\/1/);
assert.match(shell,/RiftCLI rollback contract requires an existing parent directory/);
assert.match(shell,/rollback snapshot exceeds/);
assert.match(shell,/rollback journal exceeds/);
assert.match(shell,/Rollback target no longer matches the batch-produced post-state/);
assert.match(shell,/delete-created-empty-directory/);
assert.match(shell,/target\.listFiles\(\)\?\.isEmpty\(\) == true/);
assert.match(shell,/for \(index in job\.rollbackJournal\.length\(\) - 1 downTo 0\)/);
assert.match(shell,/"rolling_back"/);
assert.match(shell,/"rolled_back"/);
assert.match(shell,/"rollback_failed"/);
assert.match(shell,/"cancelled_during_rollback"/);
assert.match(shell,/"released_after_rollback"/);
assert.match(shell,/"released_after_rollback_failure"/);
assert.match(shell,/"released_after_rollback_cancel"/);
assert.match(shell,/"batch\.rollback\.started"/);
assert.match(shell,/"batch\.rolled_back"/);
assert.match(shell,/"batch\.rollback\.failed"/);
assert.match(shell,/"batch\.rollback\.cancelled"/);

assert.match(shell,/private fun publicRollbackJournal/);
assert.match(shell,/pre\.remove\("bytesBase64"\)/);
assert.match(shell,/"snapshotBytesRetainedPrivately", true/);
assert.match(shell,/private fun publicPersistedCliJob/);
assert.match(shell,/\.put\("rollbackJournal", JSONArray\(job\.rollbackJournal\.toString\(\)\)\)/,
  'private persistence must retain bounded rollback bytes');
assert.match(shell,/\.put\("rollbackJournal", publicRollbackJournal\(job\.rollbackJournal\)\)/,
  'public live job snapshots must redact rollback bytes');

assert.match(shell,/private fun validateRecoveredRollbackJournal/);
assert.match(shell,/Recovered rollback journal refers to a future\/unstarted step/);
assert.match(shell,/Recovered uncertain write is missing its pre-step rollback journal/);
assert.match(shell,/private fun submitRecoveredCliRollback/);
assert.match(shell,/state", "reserved_for_recovery_rollback"/);
assert.match(shell,/state", "active_recovery_rollback"/);
assert.match(shell,/"released_after_recovery_rollback"/);
assert.match(shell,/action == "rollback"/);
assert.match(shell,/rollbackJournal = persisted\.optJSONArray\("rollbackJournal"\)/,
  'safe resume must carry forward private rollback evidence');

assert.match(jobStore,/"rolled_back"/);
assert.match(jobStore,/"rollback_failed"/);
assert.match(jobStore,/"cancelled_during_rollback"/);

assert.match(host,/private val cliJobStore = RiftCliPersistentJobStore/);
assert.match(host,/private fun persistCliJob/);
assert.match(host,/"persistentJobs", true/);
assert.match(host,/"persistedOnly", true/);
assert.match(host,/"authorizationBypass", false/);
assert.match(host,/"perOperationAuthorizationRequired", true/);

assert.match(localAgent,/private object RiftLocalAgentBatch/);
assert.match(localAgent,/SCHEMA = "rift\.local-agent-batch\/1"/);
assert.match(localAgent,/MAX_PLAN_BYTES = 128 \* 1024/);
assert.match(localAgent,/ACTIONS = setOf\("submit", "list", "poll", "cancel", "recover"\)/);
assert.match(localAgent,/RECOVERY_ACTIONS = setOf\("resume", "fail", "rollback"\)/);
assert.match(localAgent,/if \(op == "batch"\) return RiftLocalAgentBatch\.execute\(context, args\)/);
assert.match(localAgent,/toolName = "rift_cli_batch"/);
assert.match(localAgent,/toolName = "rift_cli_job_list"/);
assert.match(localAgent,/toolName = if \(action == "poll"\) "rift_cli_job_poll" else "rift_cli_job_cancel"/);
assert.match(localAgent,/toolName = "rift_cli_job_recover"/);
assert.match(localAgent,/nativeShell\.executeCliForLocalAgent\(cwd, argv\)/,
  'Local Agent Batch must re-enter the existing native RiftCLI driver instead of executing jobs itself');
assert.match(localAgent,/executionOwner", "riftcli"/);
assert.match(localAgent,/private fun compactJob\(job: JSONObject\): JSONObject/);
assert.match(localAgent,/private fun compactDispatch\(action: String, raw: Any\?\): Any/);
assert.match(localAgent,/val limit = minOf\(jobs\.length\(\), 32\)/);
assert.match(localAgent,/\.put\("returnedJobs", rows\.length\(\)\)/);
assert.match(localAgent,/\.put\("totalJobs", jobs\.length\(\)\)/);
assert.match(localAgent,/\.put\("truncated", jobs\.length\(\) > rows\.length\(\)\)/);
assert.match(localAgent,/\.put\("dispatchResult", compactDispatch\(action, dispatchResult\)\)/);
assert.ok(!localAgent.includes('.put("driver", JSONObject(driver.toString()))'),
  'Local Agent Batch must not duplicate the full native driver body in its response');
assert.ok(transportValidator.includes('services.includes(\'"poll","cancel"->{\')'));
assert.ok(transportValidator.includes('services.includes(\'require(args.size==1){"usage: riftos-agent batch $action <job-id>"}\')'));
assert.ok(!transportValidator.includes('usage: riftos-agent batch poll <job-id>'));
assert.ok(!transportValidator.includes('usage: riftos-agent batch cancel <job-id>'));
for (const name of ['rift_batch_submit','rift_batch_list','rift_batch_poll','rift_batch_cancel','rift_batch_recover']) {
  assert.ok(host.includes(`"${name}"`), `first-class MCP Batch control must be declared: ${name}`);
}
assert.match(host,/MCP_BATCH_TOOLS = setOf\("rift_batch_submit", "rift_batch_list", "rift_batch_poll", "rift_batch_cancel", "rift_batch_recover"\)/);
assert.match(host,/if \(name in MCP_BATCH_TOOLS\)/);
assert.match(host,/mcpBatchLocalAgentRequest\(name, args\)/);
assert.match(host,/RiftOsLocalAgent\.execute\(appContext, request\)/,
  'MCP Batch controls must enter the Local Agent instead of dispatching directly to RiftCLI or RiftToolSandbox');
assert.match(host,/MCP_BATCH_READ_ONLY_TOOLS = setOf\("rift_batch_list", "rift_batch_poll"\)/);
assert.match(host,/"rift_batch_cancel", "rift_batch_recover" -> true/);
assert.match(host,/\.put\("steps", JSONObject\(\)\.put\("type", "array"\)\.put\("minItems", 1\)\.put\("maxItems", 16\)/);
assert.ok(!mcpServer.includes('rift_cli_batch'),'internal RiftCLI batch control must not become a direct MCP server tool');
assert.ok(!mcpServer.includes('rift_cli_job_recover'),'internal RiftCLI recovery control must not become a direct MCP server tool');
assert.match(host,/"rift_batch_submit"/);
assert.match(host,/"rift_batch_recover"/);

assert.match(oldBatch,/DISABLED: RiftShell batch commands are disabled/);
assert.match(oldBatch,/disabled:true/);
assert.ok(!shell.includes('"batch" ->'),'native RiftShell must not resurrect the retired batch command');

console.log('ok - RiftCLI Batch V2 B1+B2A+B2B contract is bounded, sealed, retry-safe, rollback-journaled, exposed through bounded Local Agent translation, and published as five bounded first-class MCP Batch controls');
