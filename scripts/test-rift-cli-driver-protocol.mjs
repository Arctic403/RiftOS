import fs from 'node:fs';

const read = path => fs.readFileSync(path, 'utf8');
const failures = [];
const check = (name, condition) => { if (!condition) failures.push(name); };

const core = read('android/app/src/main/cpp/riftcli/rift_cli_core.cpp');
const shell = read('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt');
const host = read('android/app/src/main/java/com/riftos/app/RiftCliHost.kt');
const toolHost = read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const sandbox = read('android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt');
const docs = read('docs/systems/riftcli/README.md');

check(
  'N1 schema and hardened protocol version are native-owned',
  core.includes('rift.cli-driver/1') &&
    core.includes('kDriverProtocolVersion = 1') &&
    core.includes('0.1.1-driver-live-poll')
);

check(
  'authority-bearing requests require replay-safe request ids',
  core.includes('--request-id') &&
    core.includes('kMaxDriverRequestIds = 4096') &&
    core.includes('RequestReservation::Duplicate') &&
    core.includes('RequestReservation::Capacity') &&
    core.includes('duplicate-request-id') &&
    core.includes('request-id-capacity') &&
    core.includes('g_recentRequestIds') &&
    !core.includes('g_recentRequestOrder') &&
    core.includes('resetDriverLoops()') &&
    core.includes(String.raw`\"driverReplayReset\":\"process-restart-only\"`)
);

check(
  'disable and re-enable preserve replay ids for the full process lifetime',
  core.includes('void resetDriverLoops()') &&
    !core.includes('g_recentRequestIds.clear()') &&
    core.includes('if (command == "disable")') &&
    core.includes('resetDriverLoops();') &&
    core.includes(String.raw`\"driverReplayReset\":\"process-restart-only\"`)
);

check(
  're-enabling preserves active replay and loop state',
  core.includes('RiftCLI is already enabled for this process; active loop/replay state was preserved.') &&
    !core.includes('resetDriverState();\n        g_enabled.store(true);\n        return {\n            "RiftCLI is already enabled')
);

check(
  'external continuation loop is hard-bounded and process-local',
  core.includes('kMaxDriverLoopSteps = 8') &&
    core.includes('kMaxActiveDriverLoops = 64') &&
    core.includes('g_driverLoops') &&
    core.includes('loop-step must be less than loop-max') &&
    core.includes('loop-max must be between 1 and 8')
);

check(
  'driver loop continuity binds identity and exact next step',
  core.includes('driver continuation identity does not match active loop') &&
    core.includes('driver continuation loop-max changed mid-loop') &&
    core.includes('driver continuation must advance exactly one loop step') &&
    core.includes('final step must dispatch an action or end') &&
    core.includes('request-more-info requires loop-max greater than 1')
);

check(
  'driver identity evidence and dispatch contract is present',
  [
    '--request-id',
    '--session',
    '--task',
    '--project',
    '--goal',
    '--assumption',
    '--evidence',
    '--capability',
    '--action',
    '--tool',
    '--tool-args',
    '--loop-id',
    '--loop-step',
    '--loop-max',
    '--request-more-info',
  ].every(marker => core.includes(marker))
);

check(
  'full RiftOS authority requires explicit enable',
  core.includes('full-riftos-when-enabled') &&
    core.includes('if (!on && !jobControl)') &&
    core.includes('cli-disabled') &&
    core.includes('"mutation\\":') &&
    core.includes('"toolExecution\\":') &&
    core.includes('"networkViaRiftOs\\":') &&
    core.includes('"directModelBackend\\":false') &&
    core.includes('"directNetworkClient\\":false')
);

check(
  'future Rift tools delegate dynamically while recursive and workspace-exec lanes stay denied',
  core.includes('name.rfind("rift_", 0)') &&
    core.includes('name != "rift_shell_exec"') &&
    core.includes('name != "rift_workspace_exec"') &&
    toolHost.includes('RiftCLI tool lane forbids')
);

check(
  'replay protection is fail-closed without eviction',
  core.includes('kMaxDriverRequestIds = 4096') &&
    core.includes('"driverReplayCapacity\\":') &&
    core.includes('"driverReplayEviction\\":false') &&
    core.includes('request-id-capacity') &&
    !core.includes('g_recentRequestOrder')
);

check(
  'job controls remain observable while CLI is disabled and do not consume replay capacity',
  core.includes('isDriverJobControl') &&
    core.includes('if (!on && !jobControl)') &&
    core.includes('jobControl ? RequestReservation::Accepted') &&
    core.includes('job-control requests must be single-step')
);

check(
  'exactly one CLI authority job may be outstanding across both lanes',
  toolHost.includes('outstandingJobId = AtomicReference<String?>(null)') &&
    toolHost.includes('fun tryReserve(jobId: String)') &&
    toolHost.includes('fun release(jobId: String)') &&
    shell.includes('RiftCliExecutionGate.tryReserve(jobId)') &&
    toolHost.includes('RiftCliExecutionGate.tryReserve(jobId)') &&
    shell.includes('RiftCliExecutionGate.release(jobId)') &&
    toolHost.includes('RiftCliExecutionGate.release(jobId)')
);

check(
  'job listing is metadata-only while explicit poll owns full results',
  shell.includes('cliShellJobSnapshot(it, includeResult = false)') &&
    toolHost.includes('cliJobSnapshot(it, includeResult = false)') &&
    !shell.includes('.put("command", job.command') &&
    shell.includes('.put("operation", job.operation')
);

check(
  'CLI never recursively dispatches itself',
  core.includes('internal RiftCLI recursion is forbidden') &&
    shell.includes('Internal RiftCLI recursion is forbidden') &&
    shell.includes('nestedCommand != "rift-cli"')
);

check(
  'shell authority runs as a separate live-poll job lane',
  core.includes(String.raw`\"kind\":\"rift-shell\"`) &&
    shell.includes('private val cliWorker = ThreadPoolExecutor(') &&
    shell.includes('cliWorker.submit {') &&
    shell.includes('MAX_CLI_SHELL_JOBS = 16') &&
    shell.includes('cli-shell-job-') &&
    shell.includes('dispatchSubmitted') &&
    shell.includes('dispatchExecuted') &&
    shell.includes('dispatchAsync') &&
    !shell.includes('CountDownLatch')
);

check(
  'direct ToolHost authority runs without a fixed CLI wall-clock timeout',
  core.includes(String.raw`\"kind\":\"rift-tool\"`) &&
    toolHost.includes('internal fun startCliJob') &&
    toolHost.includes('internal fun listCliJobs') &&
    toolHost.includes('internal fun pollCliJob') &&
    toolHost.includes('internal fun cancelCliJob') &&
    toolHost.includes('MAX_CLI_JOBS = 16') &&
    sandbox.includes('internal fun submitCliJob(') &&
    sandbox.includes('executor.submit {') &&
    sandbox.includes('executeRequest(raw, "rift-cli")')
);

check(
  'live-poll retention is bounded for 32-bit heap safety',
  shell.includes('MAX_CLI_SHELL_RETAINED_RESULT_BYTES = 2 * 1024 * 1024') &&
    shell.includes('CLI_SHELL_JOB_RETENTION_MS = 5 * 60 * 1000L') &&
    shell.includes('"completed_result_too_large"') &&
    toolHost.includes('MAX_CLI_RETAINED_RESULT_BYTES = 2 * 1024 * 1024') &&
    toolHost.includes('CLI_JOB_RETENTION_MS = 5 * 60 * 1000L') &&
    toolHost.includes('"completed_result_too_large"')
);

check(
  'live-poll recovery surface covers list poll and cancel',
  core.includes(String.raw`\"driverToolExecution\":\"live-poll-jobs\"`) &&
    core.includes('rift_cli_job_list') &&
    core.includes('rift_cli_job_poll') &&
    core.includes('rift_cli_job_cancel') &&
    shell.includes('"rift_cli_job_list" ->') &&
    shell.includes('"rift_cli_job_poll" ->') &&
    shell.includes('"rift_cli_job_cancel" ->') &&
    shell.includes('requestId') &&
    toolHost.includes('.put("requestId", job.requestId)')
);

check(
  'cancellation never claims success before the worker resolves',
  shell.includes('"cancelling"') &&
    shell.includes('"completed_after_cancel_request"') &&
    shell.includes('"cancelled_may_have_applied"') &&
    shell.includes('cancelRequested') &&
    toolHost.includes('"cancelling"') &&
    toolHost.includes('"completed_after_cancel_request"') &&
    toolHost.includes('"cancelled_may_have_applied"') &&
    toolHost.includes('cancelRequested') &&
    toolHost.includes('finally {') &&
    toolHost.includes('RiftCliExecutionGate.release(jobId)')
);

check(
  'all CLI execution is globally serialized while polling stays separate',
  toolHost.includes('internal object RiftCliExecutionGate') &&
    toolHost.includes('ReentrantLock(true)') &&
    shell.includes('RiftCliExecutionGate.run {') &&
    sandbox.includes('RiftCliExecutionGate.run {')
);

check(
  'completed CLI mutations commit provenance even if cancellation arrived late',
  !shell.includes('RiftDeadline.check("rift-cli shell job")') &&
    sandbox.includes('if (origin != "rift-cli") RiftDeadline.check("$origin sandbox request")')
);

check(
  'disable revokes both live job lanes',
  shell.includes('cancelAllCliShellJobs(reason)') &&
    shell.includes('cancelAllCliJobs(reason)') &&
    shell.includes('cliShellJobCancellationsRequested') &&
    shell.includes('cliToolJobCancellationsRequested')
);

check(
  'CLI shell mutations retain request-bound patch provenance',
  shell.includes('origin = "rift-cli-driver"') &&
    shell.includes('requestId = cliResult.optString("requestId")') &&
    shell.includes('RiftPatchSessions.begin(') &&
    shell.includes('RiftPatchSessions.commit') &&
    shell.includes('RiftPatchSessions::abort')
);

check(
  'direct CLI tool mutations retain sandbox provenance',
  sandbox.includes('executeRequest(raw, "rift-cli")') &&
    sandbox.includes('origin = origin') &&
    sandbox.includes('RiftPatchSessions.begin(') &&
    sandbox.includes('RiftPatchSessions.commit') &&
    sandbox.includes('RiftPatchSessions::abort')
);

check(
  'Kotlin JNI host remains transport-only',
  host.includes('private external fun nativeExecute') &&
    !host.includes('loopStep') &&
    !host.includes('capability') &&
    !host.includes('RiftPatchSessions')
);

check(
  'driver requests more information only through explicit external continuation',
  core.includes('need_more_info') &&
    core.includes('requestMoreInfo') &&
    core.includes('continuationRequired') &&
    core.includes('external driver must send exactly the next loop step') &&
    core.includes('"cliCallsDriver\\":false')
);

check(
  'docs describe full authority live polling replay safety and bounded looping',
  docs.includes('full RiftOS authority') &&
    docs.includes('live-poll') &&
    docs.includes('request-id') &&
    docs.includes('external continuation') &&
    docs.includes('8')
);

if (failures.length) {
  console.error('RiftCLI N1 driver protocol validation failed:');
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log('RiftCLI N1 driver protocol source contract OK');
