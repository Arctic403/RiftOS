import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const app = path.join(root, "android/app/src/main/java/com/riftos/app");
const read = (name) => fs.readFileSync(path.join(app, name), "utf8");

const hub = read("RiftDebugHub.kt");
const runtime = read("RiftMcpRuntime.kt");
const cliRuntime = read("RiftCliRuntime.kt");
const server = read("RiftMcpServer.kt");
const host = read("RiftToolHost.kt");
const relayClient = read("RiftMcpRelayClient.kt");
const cliRelayClient = read("RiftCliRelayClient.kt");
const cliEvents = read("RiftCliEventBus.kt");
const gradle = fs.readFileSync(path.join(root, "android/app/build.gradle.kts"), "utf8");
const ownership = fs.readFileSync(path.join(root, "docs/SOURCE_OWNERSHIP.md"), "utf8");
const debuggerDocs = fs.readFileSync(path.join(root, "docs/systems/debugger/README.md"), "utf8");

assert.match(hub, /interface RiftDebugAdapter/);
assert.match(hub, /fun attachDebugSink\(sink: RiftDebugSink\): AutoCloseable/);
assert.match(hub, /fun interface RiftDebugSink/);
assert.match(hub, /class RiftDebugHub/);
assert.match(hub, /private val maxEvents: Int = 1_024/);
assert.match(hub, /private val maxActiveSpans: Int = 128/);
assert.match(hub, /sensitiveKeys/);
assert.match(hub, /"\[REDACTED\]"/);
assert.match(hub, /Bearer \[REDACTED\]/);
assert.match(hub, /attach_failed/);
assert.match(hub, /"status" -> status\(\)/);
assert.match(hub, /"events" -> eventSnapshot/);
assert.match(hub, /"active" -> activeSnapshot/);
assert.match(hub, /"components" -> componentSnapshot\(\)/);
assert.doesNotMatch(hub, /Runtime\.getRuntime|ProcessBuilder|java\.io\.File|java\.net\.|android\.webkit/);

assert.match(runtime, /@Volatile private var debugHub: RiftDebugHub\? = null/);
assert.match(runtime, /fun debugHub\(\): RiftDebugHub/);
assert.match(runtime, /RiftToolHost\([\s\S]*debugHub\(\)/);
assert.match(runtime, /RiftMcpServer\(toolHost\(context\), debugHub\(\)\)/);
assert.doesNotMatch(runtime, /RiftCliEventBus|cliEvents/);
assert.match(cliRuntime, /RiftCliEventBus\(RiftMcpRuntime\.debugHub\(\)\)/);
assert.match(cliRuntime, /RiftCliRelayClient\([\s\S]*RiftMcpRuntime\.debugHub\(\)/);

assert.match(server, /component = "mcp\.server"/);
assert.match(server, /operation = "tools\.call"/);
assert.match(server, /toolHost\.callAsync\(name, args, mcpSpan\.context\)/);
assert.match(server, /"riftos\/traceId"/);
assert.match(server, /mcpSpan\.success/);
assert.match(server, /mcpSpan\.failure/);

assert.match(host, /"rift_debug"/);
assert.match(host, /component = "tool\.host"/);
assert.match(host, /parent = debugContext/);
assert.match(host, /debugHub\.query\(args\)/);
assert.match(host, /Actions: status, events, active, components/);
assert.doesNotMatch(host, /"rift_debug"[\s\S]{0,800}"cancel"/);

assert.match(cliEvents, /debugHub\?\.sink\("riftcli\.event-bus"\)/);
assert.match(cliEvents, /operation = "event\.created"/);
assert.match(cliEvents, /runCatching \{[\s\S]{0,1200}debugSink\?\.emit\(/, "event-bus diagnostics must fail isolated before relay listeners");
assert.match(cliEvents, /"eventSequence" to frozen\.optLong\("sequence"\)\.toString\(\)/);
assert.doesNotMatch(cliEvents, /RiftDebugSignal\([\s\S]{0,1000}resultInline/);

assert.match(relayClient, /debugHub\?\.sink\("mcp\.relay"\)/);
assert.match(relayClient, /private fun debug\([\s\S]*runCatching \{[\s\S]{0,500}debugSink\?\.emit\(/, "MCP relay diagnostics must be fail isolated");
for (const operation of [
  "socket.connect",
  "socket.open",
  "relay.ready",
  "socket.closed",
  "socket.failure",
  "socket.reconnect",
]) {
  assert.ok(relayClient.includes(`operation = "${operation}"`), `missing MCP relay debug operation ${operation}`);
}
assert.doesNotMatch(relayClient, /operation = "cli\./, "MCP relay must not emit CLI transport diagnostics");

assert.match(cliRelayClient, /debugHub\?\.sink\("cli\.relay"\)/);
assert.match(cliRelayClient, /private fun debug\([\s\S]*runCatching \{[\s\S]{0,500}debugSink\?\.emit\(/, "CLI relay diagnostics must be fail isolated");
for (const operation of [
  "socket.connect",
  "socket.open",
  "relay.ready",
  "cli.replay.request",
  "cli.replay.send",
  "cli.event.send",
  "cli.ack",
  "socket.closed",
  "socket.failure",
  "socket.reconnect",
]) {
  assert.ok(cliRelayClient.includes(`"${operation}"`) || cliRelayClient.includes(`operation = "${operation}"`), `missing CLI relay debug operation ${operation}`);
}
assert.match(cliRelayClient, /"eventSequence" to event\.optLong\("sequence", 0L\)\.toString\(\)/);
assert.doesNotMatch(relayClient, /"(endpoint|authorization|token|secret|cookie)"\s+to\s+/, "MCP relay debugger attributes must not expose endpoint or credential fields");
assert.doesNotMatch(cliRelayClient, /"(endpoint|authorization|token|secret|cookie)"\s+to\s+/, "CLI relay debugger attributes must not expose endpoint or credential fields");

assert.ok(gradle.includes("src/main/java/com/riftos/app/RiftDebugHub.kt"));
assert.ok(ownership.includes("RiftDebugHub.kt") && ownership.includes("docs/systems/debugger/README.md"));
assert.ok(ownership.includes("test-rift-debug-hub.mjs"));
assert.match(debuggerDocs, /RiftDebugAdapter/);
assert.match(debuggerDocs, /no execution path is routed through the hub/);

const kotlinFiles = fs.readdirSync(app)
  .filter((name) => name.endsWith(".kt"))
  .map((name) => [name, read(name)]);

const toolHostConstructors = kotlinFiles.flatMap(([name, text]) =>
  [...text.matchAll(/RiftToolHost\s*\(/g)].map(() => name)
);
const serverConstructors = kotlinFiles.flatMap(([name, text]) =>
  [...text.matchAll(/RiftMcpServer\s*\(/g)].map(() => name)
);
assert.deepEqual(toolHostConstructors.sort(), ["RiftMcpRuntime.kt", "RiftToolHost.kt"]);
assert.deepEqual(serverConstructors.sort(), ["RiftMcpRuntime.kt", "RiftMcpServer.kt"]);

console.log("RiftDebugHub passive core, MCP/ToolHost correlation, RiftCLI event/relay diagnostics, bounds, privacy, and authority checks passed");
