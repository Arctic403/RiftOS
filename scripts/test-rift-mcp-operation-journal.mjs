import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const app = path.join(root, "android/app/src/main/java/com/riftos/app");
const read = (name) => fs.readFileSync(path.join(app, name), "utf8");

const journal = read("RiftMcpOperationJournal.kt");
const runtime = read("RiftMcpRuntime.kt");
const server = read("RiftMcpServer.kt");
const host = read("RiftToolHost.kt");
const relay = read("RiftMcpRelayClient.kt");
const shellExecutor = read("RiftShellExecutor.kt");
const nativeShell = read("RiftNativeShell.kt");
const records = read("RiftWorkspaceRecords.kt");
const gradle = fs.readFileSync(path.join(root, "android/app/build.gradle.kts"), "utf8");

assert.match(journal, /class RiftMcpOperationJournal/);
assert.match(journal, /AtomicFile/);
assert.match(journal, /MAX_ENTRIES = 256/);
assert.match(journal, /rift\.mcp-operation-journal\/1/);
assert.match(journal, /interrupted_on_restart/);
assert.match(journal, /Effects may have applied\. Reconcile before retrying/);
assert.match(journal, /queued_to_relay/);
assert.match(journal, /response_not_delivered/);
assert.match(journal, /requestHash/);
assert.match(journal, /latestSequence/);
assert.match(journal, /sinceSequence/);
assert.match(journal, /persistLocked\(\)/);
assert.doesNotMatch(journal, /"arguments"/);
assert.doesNotMatch(journal, /"payload"/);

assert.match(runtime, /operationJournal: RiftMcpOperationJournal\?/);
assert.match(runtime, /fun operationJournal\(context: Context\): RiftMcpOperationJournal/);
assert.match(runtime, /RiftToolHost\([\s\S]*operationJournal\(context\)/);
assert.match(runtime, /RiftMcpServer\([\s\S]*operationJournal\(context\)/);
assert.match(runtime, /RiftMcpRelayClient\([\s\S]*operationJournal\(context\)/);

assert.match(server, /prepareOperation\(/);
assert.match(server, /operationJournal\.begin\(/);
assert.match(server, /operationJournal\.complete\(/);
assert.match(server, /recoveredToolResponse/);
assert.match(server, /alreadyExecuted/);
assert.match(server, /did not replay it/);
assert.match(server, /riftos\/operationId/);
assert.match(server, /riftos\/journalSequence/);
assert.match(server, /riftos\/recoveredReplay/);
assert.match(server, /toolHost\.callAsync\(name, args, mcpSpan\.context, operationContext\)/);

assert.match(host, /"rift_mcp_reconcile"/);
assert.match(host, /operationJournal\.query\(args\)/);
assert.match(host, /evidenceForRequestIds\(operationIds\)/);
assert.match(host, /operationContext\?\.operationId/);
assert.match(host, /shellExecutor\?\.execute\(command, args\.optString\("cwd", "\/"\), operationContext\?\.operationId\)/);
assert.match(host, /internal fun isMutatingCall/);

assert.match(shellExecutor, /requestId: String\? = null/);
assert.match(nativeShell, /requestId = requestId,/);

assert.match(records, /internal fun evidenceForRequestIds/);
assert.match(records, /provenance\.optString\("requestId"\)/);
assert.match(records, /"recordSequence"/);
assert.match(records, /"patchId"/);

assert.match(relay, /operationJournal\.markDelivery\(operationId, "response_not_delivered"\)/);
assert.match(relay, /"queued_to_relay"/);
assert.match(relay, /riftos\/recoveredReplay/);
assert.match(relay, /if \(operationId != null && !recoveredReplay\)/);

assert.ok(gradle.includes("src/main/java/com/riftos/app/RiftMcpOperationJournal.kt"));

console.log("Persistent MCP operation journal, replay protection, reconciliation evidence, and transport-delivery semantics checks passed");
