import assert from "node:assert/strict";
import fs from "node:fs";

const read = (path) => fs.readFileSync(path, "utf8");
const host = read("android/app/src/main/java/com/riftos/app/RiftToolHost.kt");
const shell = read("android/app/src/main/java/com/riftos/app/RiftNativeShell.kt");
const server = read("android/app/src/main/java/com/riftos/app/RiftMcpServer.kt");
const core = read("android/app/src/main/cpp/riftcli/rift_cli_core.cpp");
const localPackage = read("android/app/src/main/java/com/riftos/app/RiftLocalCliPackage.kt");

assert.match(host, /private fun routePublicMcpThroughCli\(/);
assert.match(host, /private fun submitMcpCliAuthority\(/);
assert.match(host, /private fun executeMcpCliAuthority\(/);
assert.match(host, /if \(name == "rift_info" \|\| name == "rift_debug" \|\| name in MCP_BATCH_TOOLS\) return null/);
assert.match(host, /DIRECT_CLI_CONTROL_COMMANDS = setOf\("help", "status", "architecture", "enable", "disable"\)/);
assert.match(host, /Public MCP may call only RiftCLI trust-kernel controls directly/);
assert.match(host, /RiftOsLocalAgent\.execute\(/);
assert.match(host, /\.put\("op", "intelligence"\)/);
assert.match(host, /MCP_CLI_ROUTE_TIMEOUT_MS = 95_000L/);
assert.match(server, /REQUEST_TIMEOUT_MS = 100_000L/);
assert.match(host, /transportRequestId\?\.let\(RiftMutationFence::cancelTransport\)/);

assert.match(host, /internal object RiftCliPayloadStore/);
assert.match(host, /MAX_ENTRIES = 4/);
assert.match(host, /MAX_ENTRY_BYTES = 20 \* 1024 \* 1024/);
assert.match(host, /MAX_TOTAL_BYTES = 40 \* 1024 \* 1024/);
assert.match(host, /RETENTION_MS = 2 \* 60 \* 1000L/);
assert.match(host, /fun stage\(name: String, args: JSONObject\): String/);
assert.match(host, /fun take\(id: String\): Entry/);
assert.match(host, /fun discard\(id: String\)/);
assert.match(host, /fun clear\(\)/);

assert.match(host, /"--action", normalizedArgs\.optString\("command"\)/);
assert.match(host, /"--tool", name/);
assert.match(host, /"--tool-payload-id", payloadId/);
assert.doesNotMatch(host, /rift_cli_mcp_authority/);

assert.match(shell, /val payloadId = dispatch\.optString\("payloadId"\)\.trim\(\)/);
assert.match(shell, /RiftCliPayloadStore\.take\(payloadId\)/);
assert.match(shell, /entry\.name == toolName/);
assert.match(shell, /transportRequestId = cliResult\.optString\("requestId"\)/);
assert.match(shell, /modelCallId = cliResult\.optString\("sessionId"\)/);
assert.match(shell, /traceId = cliResult\.optString\("taskId"\)/);
assert.match(shell, /RiftCliPayloadStore\.clear\(\)/);

assert.match(host, /if \(name == "rift_workspace_exec"\)/);
assert.match(host, /operationCount > 1/);
const batchValidator = host.slice(
  host.indexOf("internal fun validateCliBatchTool"),
  host.indexOf("internal fun executeCliBatchTool")
);
assert.ok(!batchValidator.includes('"rift_workspace_exec"'), "one-op workspace.exec must be CLI Batch-capable");
assert.ok(batchValidator.includes('"rift_shell_exec"'), "recursive shell tool lane must remain forbidden");

const allowedDriverTool = core.slice(
  core.indexOf("bool isAllowedDriverTool"),
  core.indexOf("bool isDriverJobControl")
);
assert.ok(allowedDriverTool.includes('name != "rift_shell_exec"'));
assert.ok(!allowedDriverTool.includes('name != "rift_workspace_exec"'));
assert.match(core, /--tool-payload-id/);
assert.match(core, /std::string toolPayloadId/);
assert.match(core, /tool-payload-id and tool-args are mutually exclusive/);
assert.match(core, /\\\"payloadId\\\"/);

assert.match(localPackage, /values\.first\(\)\.trim\(\)\.lowercase\(\) == "driver"/);
assert.match(localPackage, /MAX_NATIVE_ARGS = 128/);
assert.match(localPackage, /MAX_NATIVE_ARG_BYTES = 128 \* 1024/);
assert.match(localPackage, /MAX_NATIVE_TOTAL_BYTES = 256 \* 1024/);

console.log("RiftCLI universal authority convergence contract passed");
