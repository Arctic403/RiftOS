import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const read=(path)=>readFileSync(path,'utf8');

const gradle=read('android/app/build.gradle.kts');
const main=read('android/app/src/main/java/com/riftos/app/MainActivity.kt');
const manifest=read('android/app/src/main/AndroidManifest.xml');

const mcpRuntime=read('android/app/src/main/java/com/riftos/app/RiftMcpRuntime.kt');
const mcpClient=read('android/app/src/main/java/com/riftos/app/RiftMcpRelayClient.kt');
const mcpSettings=read('android/app/src/main/java/com/riftos/app/RiftRelaySettings.kt');

const cliRuntime=read('android/app/src/main/java/com/riftos/app/RiftCliRuntime.kt');
const cliEvents=read('android/app/src/main/java/com/riftos/app/RiftCliEventBus.kt');
const cliClient=read('android/app/src/main/java/com/riftos/app/RiftCliRelayClient.kt');
const cliSettings=read('android/app/src/main/java/com/riftos/app/RiftCliRelaySettings.kt');
const cliActivity=read('android/app/src/main/java/com/riftos/app/RiftCliRelayActivity.kt');

const toolHost=read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const shell=read('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt');

const mcpRelay=read('relay/src/index.js');
const mcpWrangler=read('relay/wrangler.jsonc');
const cliRelay=read('cli-relay/src/index.js');
const cliWrangler=read('cli-relay/wrangler.jsonc');

for (const file of [
  'RiftCliEventBus.kt',
  'RiftCliRelayActivity.kt',
  'RiftCliRelayClient.kt',
  'RiftCliRelaySettings.kt',
  'RiftCliRuntime.kt',
]) {
  assert.match(gradle,new RegExp(file.replace('.','\\.')),'exact Android source snapshot must include '+file);
}

assert.match(main,/RiftMcpRuntime\.relayClient\(this\)\.start\(\)/,'MCP relay must still start independently');
assert.match(main,/RiftCliRuntime\.relayClient\(this\)\.start\(\)/,'CLI relay must start independently');
assert.match(manifest,/android:name="\.RiftCliRelayActivity"/);
assert.match(manifest,/android:host="cli"/);

assert.match(cliRuntime,/object RiftCliRuntime/);
assert.match(cliRuntime,/fun events\(\): RiftCliEventBus/);
assert.match(cliRuntime,/RiftCliEventBus\(RiftMcpRuntime\.debugHub\(\)\)/,'CLI events may share only the passive process debugger');
assert.match(cliRuntime,/fun relayClient\(context: Context\): RiftCliRelayClient/);
assert.match(cliRuntime,/RiftCliRelayClient\([\s\S]*RiftMcpRuntime\.nativeShell\(context\),[\s\S]*events\(\),[\s\S]*RiftMcpRuntime\.debugHub\(\)/);

assert.ok(!mcpRuntime.includes('RiftCliEventBus'),'MCP runtime must not own CLI event state');
assert.ok(!mcpRuntime.includes('cliEvents'),'MCP runtime must not expose CLI event state');
assert.ok(!mcpClient.includes('RiftCli'),'MCP relay client must not depend on CLI runtime/types');
assert.ok(!mcpClient.includes('"cli.'),'MCP relay client must not carry CLI protocol messages');
assert.ok(!mcpClient.includes('cliAck'),'MCP relay client must not carry CLI ACK state');
assert.ok(!mcpSettings.includes('cliAck'),'MCP settings must not persist CLI ACK state');
assert.ok(!mcpSettings.includes('rift.cli'),'MCP settings must not reuse CLI secrets');

assert.match(mcpClient,/PROTOCOL = "rift-mcp-relay-v1"/);
assert.match(mcpClient,/"mcp\.request" -> handleMcpRequest/);
assert.match(mcpClient,/"mcp\.notification" -> handleMcpNotification/);
assert.match(mcpClient,/debugHub\?\.sink\("mcp\.relay"\)/);

assert.match(cliSettings,/PREFS = "rift-cli-relay"/);
assert.match(cliSettings,/TOKEN_SECRET = "rift\.cli\.relay\.token"/);
assert.match(cliSettings,/KEY_ACK_SEQUENCE = "ackSequence"/);
assert.match(cliSettings,/fun loadAckSequence\(\): Long/);
assert.match(cliSettings,/fun saveAckSequence\(sequence: Long\): Boolean/);
assert.match(cliSettings,/\.putLong\(KEY_ACK_SEQUENCE, sequence\)[\s\S]{0,80}\.commit\(\)/,'CLI ACK cursor must be synchronously persisted');
assert.ok(!cliSettings.includes('rift-mcp-relay'),'CLI settings must not reuse MCP preferences');
assert.ok(!cliSettings.includes('rift.relay.token'),'CLI settings must not reuse MCP token');

assert.match(cliActivity,/title = "RiftCLI Relay"/);
assert.match(cliActivity,/RiftCliRuntime\.relayClient\(this\)/);
assert.match(cliActivity,/RiftCliRelaySettings\(this\)/);
assert.ok(!cliActivity.includes('RiftMcpActivity'),'CLI settings surface must be independent from MCP activity');

assert.match(cliEvents,/SCHEMA = "rift\.cli-event\/1"/);
assert.match(cliEvents,/MAX_EVENTS = 256/);
assert.match(cliEvents,/MAX_EVENT_BYTES = 96 \* 1024/);
assert.match(cliEvents,/MAX_INLINE_RESULT_BYTES = 48 \* 1024/);
assert.match(cliEvents,/AtomicLong\(System\.currentTimeMillis\(\) \* 1000L\)/);
assert.match(cliEvents,/fun replayAfter\(afterSequence: Long\)/);
assert.match(cliEvents,/events\.addLast\(frozen\)/);
assert.match(cliEvents,/while \(events\.size > MAX_EVENTS\) events\.removeFirst\(\)/);
assert.match(cliEvents,/debugHub\?\.sink\("riftcli\.event-bus"\)/);

assert.match(toolHost,/RiftCliRuntime\.events\(\)\.emitJob\(/,'ToolHost CLI jobs must publish to CLI runtime, not MCP runtime');
assert.match(shell,/RiftCliRuntime\.events\(\)\.emitJob\(/,'RiftShell CLI jobs must publish to CLI runtime');
assert.match(shell,/RiftCliRuntime\.events\(\)\.emit\(/,'CLI lifecycle events must publish to CLI runtime');
assert.ok(!toolHost.includes('RiftMcpRuntime.cliEvents()'));
assert.ok(!shell.includes('RiftMcpRuntime.cliEvents()'));

assert.match(cliClient,/PROTOCOL = "rift-cli-relay-v1"/);
assert.match(cliClient,/debugHub\?\.sink\("cli\.relay"\)/);
assert.match(cliClient,/private val settings = RiftCliRelaySettings/);
assert.match(cliClient,/cliEvents\.addListener\(eventListener\)/);
assert.match(cliClient,/"relay\.ready" ->/);
assert.match(cliClient,/"cli\.request" -> handleCliRequest/);
assert.match(cliClient,/"cli\.replay\.request" ->/);
assert.match(cliClient,/"cli\.ack" ->/);
assert.match(cliClient,/settings\.saveAckSequence\(sequence\)/);
assert.match(cliClient,/cliEvents\.replayAfter\(afterSequence\)/);
assert.match(cliClient,/command == "rift-cli" \|\| command\.startsWith\("rift-cli "\)/,'device CLI transport must reject non-CLI shell commands');
assert.ok(!cliClient.includes('mcp.request'),'CLI relay client must not carry MCP request envelopes');
assert.ok(!cliClient.includes('mcp.notification'),'CLI relay client must not carry MCP notification envelopes');
assert.ok(!cliClient.includes('rift-mcp-relay-v1'),'CLI client must not reuse MCP protocol');

assert.match(mcpRelay,/const PROTOCOL = "rift-mcp-relay-v1"/);
assert.match(mcpRelay,/type: "mcp\.request"/);
assert.match(mcpRelay,/type: "mcp\.notification"/);
assert.match(mcpRelay,/MAX_SSE_CLIENTS = 8/);
assert.match(mcpRelay,/MAX_SSE_NO_DRAIN_HEARTBEATS = 2/);
assert.match(mcpRelay,/SSE_LEASE_MS = 180_000/);
assert.match(mcpRelay,/MCP SSE subscriber stopped draining/);
assert.match(mcpRelay,/MCP SSE lease expired; reconnect with Last-Event-ID/);
assert.ok(!mcpRelay.includes('cli.event'),'MCP Worker must not carry CLI events');
assert.ok(!mcpRelay.includes('cli.events'),'MCP Worker must not expose CLI event subscribers');
assert.ok(!mcpRelay.includes('notifications/riftcli'),'MCP SSE must not carry CLI notifications');
assert.ok(!mcpRelay.includes('MAX_DRIVER_SOCKETS'),'MCP Worker must not own CLI driver sockets');
assert.ok(!mcpRelay.includes('MAX_CLI_EVENT_BYTES'),'MCP Worker must not own CLI event bounds');
assert.ok(!mcpRelay.includes('lastCliSequence'),'MCP Worker must not own CLI replay cursors');
assert.ok(!mcpRelay.includes('requestCliReplay'),'MCP Worker must not request CLI replay');
assert.ok(!mcpRelay.includes('broadcastCliEvent'),'MCP Worker must not fan out CLI events');
assert.ok(!mcpRelay.includes('getWebSockets("driver")'),'MCP Worker must not own CLI driver sockets');
assert.match(mcpRelay,/: rift-mcp-ready/,'MCP SSE may establish the stream without emitting a CLI notification');

assert.match(cliRelay,/const PROTOCOL = "rift-cli-relay-v1"/);
assert.match(cliRelay,/const MAX_PENDING_REQUESTS = 64/);
assert.match(cliRelay,/const MAX_EVENTS = 256/);
assert.match(cliRelay,/const MAX_EVENT_BYTES = 128_000/);
assert.match(cliRelay,/url\.pathname === "\/device"/);
assert.match(cliRelay,/url\.pathname === "\/request"/);
assert.match(cliRelay,/url\.pathname === "\/events"/);
assert.match(cliRelay,/env\.DEVICE_TOKEN/);
assert.match(cliRelay,/env\.DRIVER_TOKEN/);
assert.match(cliRelay,/Only commands rooted at rift-cli are allowed/);
assert.match(cliRelay,/type: "cli\.request"/);
assert.match(cliRelay,/envelope\.type === "cli\.response"/);
assert.match(cliRelay,/envelope\.type === "cli\.error"/);
assert.match(cliRelay,/envelope\.type === "cli\.event"/);
assert.match(cliRelay,/event\.schema !== "rift\.cli-event\/1"/);
assert.match(cliRelay,/type: "cli\.ack"/);
assert.match(cliRelay,/resumeAfter: this\.lastSequence/);
assert.match(cliRelay,/while \(this\.events\.length > MAX_EVENTS\) this\.events\.shift\(\)/);
assert.ok(!cliRelay.includes('mcp.request'),'CLI Worker must not carry MCP requests');
assert.ok(!cliRelay.includes('mcp.notification'),'CLI Worker must not carry MCP notifications');
assert.ok(!cliRelay.includes('rift-mcp-relay-v1'),'CLI Worker must not reuse MCP protocol');
assert.ok(!cliRelay.includes('ctx.storage'),'CLI event replay remains bounded and in-memory/device-owned');

assert.match(mcpWrangler,/"name": "rift-mcp-relay"/);
assert.match(mcpWrangler,/"name": "RIFT_RELAY"/);
assert.match(cliWrangler,/"name": "rift-cli-relay"/);
assert.match(cliWrangler,/"name": "RIFT_CLI_RELAY"/);
assert.match(cliWrangler,/"class_name": "RiftCliRelayRoom"/);

console.log('ok - RiftCLI transport is independent from MCP and both relay boundaries fail closed');
