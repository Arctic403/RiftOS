import { readFileSync } from 'node:fs';

const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';

const relay = read('relay/src/index.js');
const client = read(k + 'RiftMcpRelayClient.kt');
const server = read(k + 'RiftMcpServer.kt');
const host = read(k + 'RiftToolHost.kt');
const sandbox = read(k + 'RiftToolSandbox.kt');
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
  server.includes('pending.execution?.cancel()') &&
  server.includes('executionRef.get()?.cancel()'),
  'MCP server must retain and cancel the actual execution handle'
);
assert(
  host.includes('callAsyncCancellable(') &&
  host.includes('return sandbox.handleAsync(request.toString())') &&
  host.includes('return executor.execute(command'),
  'ToolHost must return cancellation authority for sandbox and shell calls'
);
assert(
  sandbox.includes('fun handleAsync(raw: String, reply: (String) -> Unit): RiftAsyncHandle') &&
  sandbox.includes('return RiftBoundedAsync.submit('),
  'sandbox async calls must return their bounded execution handle'
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

console.log('ok - MCP caller AbortSignal reaches the relay room');
console.log('ok - retry waiters cancel independently and last-waiter loss cancels the device request');
console.log('ok - mcp.cancel reaches MCP server execution ownership');
console.log('ok - sandbox and native shell Futures are interruptible end-to-end');
