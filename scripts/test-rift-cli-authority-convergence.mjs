import assert from "node:assert/strict";
import fs from "node:fs";

const read = path => fs.readFileSync(path, "utf8");
const host = read("android/app/src/main/java/com/riftos/app/RiftToolHost.kt");
const shell = read("android/app/src/main/java/com/riftos/app/RiftNativeShell.kt");
const core = read("android/app/src/main/cpp/riftcli/rift_cli_core.cpp");
const git = read("android/app/src/main/java/com/riftos/app/RiftNativeGit.kt");
const observer = read("android/app/src/main/java/com/riftos/app/RiftRepositoryConsistencyObserver.kt");

assert.match(host, /internal object RiftCliPayloadStore/);
assert.match(host, /MAX_ENTRIES = 4/);
assert.match(host, /MAX_ENTRY_BYTES = 10 \* 1024 \* 1024/);
assert.match(host, /MAX_TOTAL_BYTES = 20 \* 1024 \* 1024/);
assert.match(host, /RETENTION_MS = 2 \* 60 \* 1000L/);
assert.match(host, /private fun routePublicMcpThroughCli\(/);
assert.match(host, /private fun submitMcpCliAuthority\(/);
assert.match(host, /private fun executeMcpCliAuthority\(/);
assert.match(host, /RiftOsLocalAgent\.execute\(/);
assert.match(host, /\.put\("op", "intelligence"\)/);
assert.match(host, /if \(name == "rift_info" \|\| name == "rift_debug" \|\| name in MCP_BATCH_TOOLS\) return null/);
assert.match(host, /DIRECT_CLI_CONTROL_COMMANDS = setOf\("help", "status", "architecture", "enable", "disable"\)/);
assert.match(host, /Public MCP may call only RiftCLI trust-kernel controls directly/);

assert.match(host, /private fun isDirectRiftGitReleaseCommand\(command: String\): Boolean/);
assert.match(host, /"push", "sync" -> true/);
assert.match(host, /"workspace" -> tokens\.getOrNull\(2\) == "push"/);
assert.match(host, /shellCommandName == "git" && isDirectRiftGitReleaseCommand\(command\)\) return null/);
assert.match(git, /private fun atomicPush\(/);
assert.ok(!git.includes("routePublicMcpThroughCli"), "RiftGit release implementation must remain independent of MCP CLI routing");

assert.match(host, /private fun isWorkspaceSidecarProject\(args: JSONObject\): Boolean/);
assert.match(host, /operation\.optString\("op"\)\.trim\(\)\.lowercase\(\) == "project"/);
assert.match(host, /if \(isWorkspaceSidecarProject\(normalizedArgs\)\) return null/);
assert.match(host, /Public Project Intelligence remains an independent sidecar and is not owned by RiftCLI/);
assert.match(host, /RiftCLI Batch V2 does not own public Project Intelligence sidecar execution/);
assert.ok(!observer.includes("routePublicMcpThroughCli"), "Observer must remain structurally independent from MCP CLI routing");

const batchValidator = host.slice(
  host.indexOf("internal fun validateCliBatchTool"),
  host.indexOf("internal fun executeCliBatchTool")
);
assert.ok(!batchValidator.includes('"rift_workspace_exec",\n            "rift_cli_batch"'), "workspace.exec must not remain in the Batch deny list");
assert.match(batchValidator, /RiftCLI Batch V2 workspace step requires exactly one operation/);
assert.match(batchValidator, /RiftCLI Batch V2 does not own public Project Intelligence sidecar execution/);
assert.ok(batchValidator.includes('"rift_shell_exec"'), "recursive shell tool dispatch must remain forbidden");

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

const allowedDriverTool = core.slice(
  core.indexOf("bool isAllowedDriverTool"),
  core.indexOf("bool isDriverJobControl")
);
assert.ok(allowedDriverTool.includes('name != "rift_shell_exec"'));
assert.ok(!allowedDriverTool.includes('name != "rift_workspace_exec"'));
assert.match(core, /--tool-payload-id/);
assert.match(core, /std::string toolPayloadId/);
assert.match(core, /tool-payload-id and tool-args are mutually exclusive/);
assert.match(core, /public Project Intelligence sidecars remain outside the CLI tool lane/);

console.log("ok - RiftCLI owns execution tools while Project Intelligence and Git release remain independent");
