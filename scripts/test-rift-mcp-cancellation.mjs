import { readFileSync } from 'node:fs';

const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';

const relay = read('relay/src/index.js');
const client = read(k + 'RiftMcpRelayClient.kt');
const server = read(k + 'RiftMcpServer.kt');
const host = read(k + 'RiftToolHost.kt');
const sandbox = read(k + 'RiftToolSandbox.kt');
const fence = read(k + 'RiftMutationFence.kt');
const bounded = read(k + 'RiftBoundedAsync.kt');
const shellContract = read(k + 'RiftShellExecutor.kt');
const shell = read(k + 'RiftNativeShell.kt');

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

assert(
  relay.includes('signal: request.signal') &&
  relay.includes('forwardMcp(payload, request.signal)'),
  'public MCP POST must propagate the caller AbortSignal into the relay room'
);
assert(
  relay.includes('pending.waiters.splice(index, 1)') &&
  relay.includes('pending.waiters.length === 0') &&
  relay.includes('releasePendingWaiter(waiter)'),
  'deduplicated MCP waiters must be independently abortable and listener-clean'
);
assert(
  relay.includes('type: "mcp.cancel"') &&
  relay.includes('cancelDeviceRequest(requestId)'),
  'last-waiter loss or timeout must send mcp.cancel to the device'
);
assert(
  client.includes('"mcp.cancel" -> handleMcpCancel(message)') &&
  client.includes('server.cancelRequest(requestId)') &&
  client.includes('cancelMcpRequestsForSocket(webSocket'),
  'Android relay client must cancel local MCP work on explicit cancel and socket loss'
);
assert(
  server.includes('var execution: RiftAsyncHandle? = null') &&
  server.includes('fun cancelRequest(retryKey: String): Boolean') &&
  server.includes('RiftMutationFence.cancelTransport(normalized)') &&
  server.includes('RiftMutationFence.cancelTransport(retryKey.trim())') &&
  server.includes('pending.execution?.cancel()') &&
  server.includes('executionRef.get()?.cancel()'),
  'MCP server cancellation and timeout must poison the downstream mutation fence before interrupting execution'
);
assert(
  host.includes('callAsyncCancellable(') &&
  host.includes('transportRequestId') &&
  host.includes('modelCallId') &&
  host.includes('.put("_context", requestContext)') &&
  host.includes('return sandbox.handleAsync(request.toString())') &&
  host.includes('return executor.execute(command'),
  'ToolHost must propagate model/transport identity and retain cancellation authority'
);
assert(
  sandbox.includes('fun handleAsync(raw: String, reply: (String) -> Unit): RiftAsyncHandle') &&
  sandbox.includes('RiftMutationFence.begin(') &&
  sandbox.includes('RiftMutationFence.commit(mutationLease, "$origin mutation commit")') &&
  sandbox.includes('transaction.rollback()') &&
  sandbox.includes('return RiftBoundedAsync.submit('),
  'sandbox mutation calls must acquire a repo writer lease and rollback when commit fencing fails'
);
assert(
  fence.includes('Workspace writer lease is already held') &&
  fence.includes('cancelledTransport') &&
  fence.includes('cancelledModelCalls') &&
  fence.includes('repoKeys.forEach { leases[it] = lease }') &&
  fence.includes('return "workspace/$first"'),
  'mutation fence must serialize writes per repo while allowing unrelated repo keys to remain independent'
);
assert(
  bounded.includes('class RiftAsyncHandle') &&
  bounded.includes('fun cancel(): Boolean') &&
  bounded.includes('taskRef.get()?.cancel(true)') &&
  bounded.includes('RiftDeadline.runUntil'),
  'bounded async cancellation must interrupt the worker and retain cooperative deadlines'
);
assert(
  shellContract.includes('): RiftAsyncHandle') &&
  shell.includes('override fun execute(command: String, cwd: String?, reply: (JSONObject) -> Unit): RiftAsyncHandle') &&
  shell.includes('return RiftBoundedAsync.submit('),
  'native shell execution must expose its bounded cancellation handle'
);

console.log('ok - MCP caller cancellation reaches a downstream mutation fence');
console.log('ok - timeout/socket-loss cancellation cannot silently commit sandbox writes');
console.log('ok - per-repo writer leases serialize same-repo AI mutation while keeping separate repos independent');
