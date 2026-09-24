import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(new URL("..", import.meta.url).pathname);
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");

const contracts = read("android/app/src/main/java/com/riftos/app/RiftCrossBoundaryContractsV1.kt");
const sandbox = read("android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt");
const gradle = read("android/app/build.gradle.kts");
const manifest = read("android/app/src/main/AndroidManifest.xml");
const cmake = read("android/app/src/main/cpp/CMakeLists.txt");
const toolHost = read("android/app/src/main/java/com/riftos/app/RiftToolHost.kt");
const relay = read("relay/src/index.js");
const relayClient = read("android/app/src/main/java/com/riftos/app/RiftMcpRelayClient.kt");
const cliEvents = read("android/app/src/main/java/com/riftos/app/RiftCliEventBus.kt");
const nativeShell = read("android/app/src/main/java/com/riftos/app/RiftNativeShell.kt");
const mcpServer = read("android/app/src/main/java/com/riftos/app/RiftMcpServer.kt");

for (const required of [
  'const val SCHEMA = "rift-cross-boundary-contracts-v1"',
  'const val PHASE = "N1.8.3"',
  'private const val MAX_FILES = 4_096',
  'private const val MAX_FILE_BYTES = 2L * 1024L * 1024L',
  'private const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L',
  'private const val MAX_FINDINGS = 1_024',
  'private const val MAX_PREVIEW_ROWS = 240',
  'orderedFindings.take(MAX_PREVIEW_ROWS)',
  'orderedEvidence.take(MAX_PREVIEW_ROWS)',
  '.put("maxPreviewRows", MAX_PREVIEW_ROWS)',
  '.put("findingsTruncated", orderedFindings.size > MAX_PREVIEW_ROWS)',
  '.put("evidenceTruncated", orderedEvidence.size > MAX_PREVIEW_ROWS)',
  'val scanIncomplete = incomplete.isNotEmpty()',
  'if (scanIncomplete) return',
  '.put("partialFindingsSuppressed", scanIncomplete)',
  '"contracts-file-bound"',
  '"contracts-file-size-bound"',
  '"contracts-byte-bound"',
  '"contracts-finding-bound"',
  '"android-namespace-application-id-mismatch"',
  '"android-cmake-path-missing"',
  '"android-manifest-component-missing"',
  '"native-library-producer-missing"',
  '"jni-native-symbol-missing"',
  '"jni-managed-declaration-missing"',
  'Regex("""\\bnative\\b[^;{}=\\n]*?\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(""")',
  '"mcp-tool-without-dispatch"',
  'val specialDispatched = linkedSetOf<String>()',
  'ch in \'a\'..\'z\' || ch in \'A\'..\'Z\' || ch in \'0\'..\'9\'',
  '"contract-mirror-mismatch"',
  '"async-timeout-order-mismatch"',
  '.put("authority", "evidence-only")',
  '.put("contractsSha256", sha256(shaInput))',
]) assert.ok(contracts.includes(required), "contracts oracle missing: " + required);

assert.ok(
  sandbox.includes('if (kind == "contracts") return RiftCrossBoundaryContractsV1(workspaceRoot).analyze(base)'),
  "project kind=contracts dispatch missing",
);
assert.ok(sandbox.includes('"propagation", "contracts"'), "contracts project view is not advertised");
assert.ok(
  gradle.includes('"src/main/java/com/riftos/app/RiftCrossBoundaryContractsV1.kt"'),
  "Gradle exact source snapshot does not declare the contracts oracle",
);


assert.ok(
  contracts.includes("Regex(\"\"\"System\\.loadLibrary") &&
  contracts.includes(".filter { codeMask.getOrNull(it.range.first) == true }"),
  "native-library contract must ignore string/comment self-matches",
);
assert.ok(
  contracts.includes("val braceDepth = IntArray(source.text.length + 1)") &&
  contracts.includes("it.depth < declarationDepth"),
  "JNI owner attribution must use lexical brace depth",
);

const nestedOwnerFixture = [
  "internal object RiftCliHost {",
  "  data class CommandResult(val output: String)",
  "  private external fun nativeExecute(args: Array<String>): String",
  "}",
].join("\n");
const fixtureDepth = new Int32Array(nestedOwnerFixture.length + 1);
let fixtureBraceDepth = 0;
for (let i = 0; i < nestedOwnerFixture.length; i++) {
  fixtureDepth[i] = fixtureBraceDepth;
  if (nestedOwnerFixture[i] === "{") fixtureBraceDepth++;
  else if (nestedOwnerFixture[i] === "}") fixtureBraceDepth = Math.max(0, fixtureBraceDepth - 1);
}
fixtureDepth[nestedOwnerFixture.length] = fixtureBraceDepth;
const fixtureOwners = [...nestedOwnerFixture.matchAll(/\b(?:class|object)\s+([A-Za-z_][A-Za-z0-9_]*)\b/g)]
  .map((m) => ({ name: m[1], offset: m.index, depth: fixtureDepth[m.index] }));
const nativeOffset = nestedOwnerFixture.indexOf("nativeExecute");
const nativeDepth = fixtureDepth[nativeOffset];
const fixtureOwner = fixtureOwners
  .filter((owner) => owner.offset < nativeOffset && owner.depth < nativeDepth)
  .sort((a, b) => a.depth - b.depth || a.offset - b.offset)
  .at(-1)?.name;
assert.equal(fixtureOwner, "RiftCliHost", "nested helper type stole outer JNI declaration ownership");

const namespace = gradle.match(/\bnamespace\s*=\s*"([^"]+)"/)?.[1];
const applicationId = gradle.match(/\bapplicationId\s*=\s*"([^"]+)"/)?.[1];
assert.equal(namespace, applicationId, "current Android namespace/applicationId mirror drifted");

const cmakePath = gradle.match(/path\s*=\s*file\("([^"]+)"\)/)?.[1];
assert.ok(cmakePath, "Gradle CMake path missing");
assert.ok(fs.existsSync(path.join(root, "android/app", cmakePath)), "Gradle CMake path does not exist");

const cmakeLibraries = new Set(
  [...cmake.matchAll(/add_library\s*\(\s*([A-Za-z0-9_.+\-]+)\s+(?:SHARED|STATIC|MODULE|OBJECT)\b/gi)]
    .map((m) => m[1]),
);
for (const sourcePath of [
  "android/app/src/main/java/com/riftos/app/RiftCliHost.kt",
  "android/app/src/main/java/com/codynex/editorapp/Vm1Bridge.kt",
]) {
  const source = read(sourcePath);
  for (const match of source.matchAll(/System\.loadLibrary\("([^"]+)"\)/g)) {
    assert.ok(cmakeLibraries.has(match[1]), sourcePath + " loads undeclared CMake library " + match[1]);
  }
}

const managedSources = [];
function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(p);
    else if (/\.(kt|java)$/.test(entry.name)) managedSources.push(p);
  }
}
walk(path.join(root, "android/app/src/main/java"));
const managedText = managedSources.map((p) => fs.readFileSync(p, "utf8"));

for (const match of manifest.matchAll(/<(activity|service|receiver|provider)\b[^>]*android:name\s*=\s*"([^"]+)"/g)) {
  const raw = match[2];
  const fqcn = raw.startsWith(".") ? namespace + raw : raw.includes(".") ? raw : namespace + "." + raw;
  const pkg = fqcn.slice(0, fqcn.lastIndexOf("."));
  const simple = fqcn.slice(fqcn.lastIndexOf(".") + 1);
  const pkgPattern = "^\\s*package\\s+" + pkg.replaceAll(".", "\\.");
  assert.ok(managedText.some((src) =>
    new RegExp(pkgPattern, "m").test(src) &&
    new RegExp("\\b(class|object)\\s+" + simple + "\\b").test(src)
  ), "manifest component has no managed class: " + fqcn);
}

function jniMangle(value) {
  return [...value].map((ch) => ch === "." || ch === "/" ? "_" : ch === "_" ? "_1" : ch).join("");
}
const nativeText = [
  "android/app/src/main/cpp/editor/editor_vm_bridge.cpp",
  "android/app/src/main/cpp/riftcli/rift_cli_jni.cpp",
].map(read).join("\n");
for (const sourcePath of [
  "android/app/src/main/java/com/riftos/app/RiftCliHost.kt",
  "android/app/src/main/java/com/codynex/editorapp/Vm1Bridge.kt",
]) {
  const src = read(sourcePath);
  const pkg = src.match(/^\s*package\s+([A-Za-z0-9_.]+)/m)?.[1];
  const owner = src.match(/\b(?:class|object)\s+([A-Za-z_][A-Za-z0-9_]*)\b/)?.[1];
  assert.ok(pkg && owner, "could not identify JNI owner in " + sourcePath);
  for (const match of src.matchAll(/\bexternal\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(/g)) {
    const symbol = "Java_" + jniMangle(pkg) + "_" + jniMangle(owner) + "_" + jniMangle(match[1]);
    assert.ok(nativeText.includes(symbol), "missing JNI producer " + symbol);
  }
}

const protocolJs = relay.match(/const PROTOCOL = "([^"]+)"/)?.[1];
const protocolKt = relayClient.match(/private const val PROTOCOL = "([^"]+)"/)?.[1];
assert.equal(protocolJs, protocolKt, "MCP relay protocol mirror drifted");

const eventSchemaKt = cliEvents.match(/const val SCHEMA = "([^"]+)"/)?.[1];
const eventSchemaJs = relay.match(/event\.schema !== "([^"]+)"/)?.[1];
assert.equal(eventSchemaKt, eventSchemaJs, "CLI event schema mirror drifted");

const numeric = (text, re) => Number(text.match(re)?.[1]?.replaceAll("_", "").replace(/L$/, ""));
const timeouts = [
  numeric(sandbox, /private const val REQUEST_TIMEOUT_MS = ([0-9_]+L?)/),
  numeric(nativeShell, /private const val SHELL_TIMEOUT_MS = ([0-9_]+L?)/),
  numeric(mcpServer, /private const val REQUEST_TIMEOUT_MS = ([0-9_]+L?)/),
  numeric(relayClient, /private const val REQUEST_FORWARD_TIMEOUT_MS = ([0-9_]+L?)/),
  numeric(relay, /const REQUEST_TIMEOUT_MS = ([0-9_]+)/),
];
assert.ok(timeouts.every(Number.isFinite), "timeout chain extraction failed");
for (let i = 1; i < timeouts.length; i++) {
  assert.ok(timeouts[i - 1] < timeouts[i], "timeout chain is not strictly increasing: " + timeouts.join(","));
}

const toolDefs = new Set([...toolHost.matchAll(/\.put\(tool\(\s*"(rift_[a-z0-9_]+)"/g)].map((m) => m[1]));
const methodBlock = toolHost.split("private fun methodFor(name: String): String? = when (name) {")[1]?.split("\n    }")[0] ?? "";
const dispatch = new Set([...methodBlock.matchAll(/"(rift_[a-z0-9_]+)"\s*->/g)].map((m) => m[1]));
if (toolHost.includes('if (name == "rift_debug")') && toolHost.includes("debugHub.query(")) dispatch.add("rift_debug");
assert.equal(toolDefs.size, 19, "unexpected MCP tool definition count");
for (const tool of toolDefs) assert.ok(dispatch.has(tool), "advertised MCP tool lacks dispatch: " + tool);

assert.notEqual("rift-mcp-relay-v2", protocolKt, "protocol mutation fixture failed");
const brokenTimeouts = [...timeouts];
brokenTimeouts[2] = brokenTimeouts[1];
assert.ok(brokenTimeouts.some((v, i) => i > 0 && brokenTimeouts[i - 1] >= v),
  "timeout mutation fixture failed to create ordering violation");

console.log("N1.8.3 cross-boundary contracts source contract OK");
