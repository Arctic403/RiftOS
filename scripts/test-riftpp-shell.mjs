import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const referenceShell = readFileSync('src/riftos.js','utf8');
const batch = readFileSync('src/riftshell-batch.js','utf8');
const nativeShell = readFileSync('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt','utf8');
const headless = readFileSync('android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt','utf8');
const stripCodeComments = text => text
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .split('\n').filter(line => !line.trimStart().startsWith('//')).join('\n');
const hasWebKitDependency = text => /(?:^|\n)\s*import\s+(?:android|androidx)\.webkit\.|(?:android|androidx)\.webkit\./m.test(stripCodeComments(text));

const start = referenceShell.indexOf('async function runRiftppShell(');
const end = referenceShell.indexOf('\nasync function runShell(', start);
assert(start >= 0 && end > start, 'reference runRiftppShell must remain available as a bootstrap oracle');
const slice = referenceShell.slice(start, end);
for (const command of ['help','version','self-test','check','compile','inspect','run','exec']) {
  assert(slice.includes(`sub==="${command}"`) || command === 'help', 'missing reference riftpp shell command: ' + command);
}
assert(slice.includes('await import("./riftpp-core.js")'));
assert(slice.includes('await import("./riftvm.js")'));
assert(slice.includes('riftpp shell execution denies host imports'));
assert(slice.includes('maxSteps:100000,maxStack:1024,maxCallDepth:32,yieldEvery:512'));
assert(slice.includes('riftpp-shell-self-test/1'));
assert(slice.includes('compileRiftPlusPlusCoreProgramV1'));
assert(batch.includes('"rift-cli","riftpp","rift-tool","chat"'), 'riftpp and fixed developer tools must stay outside atomic batch');

assert(nativeShell.includes('riftpp help|version|self-test|check|compile|inspect|run|exec|run-stateful|exec-stateful   [CORE V1 / HEADLESS QUICKJS]'));
assert(nativeShell.includes('rift-tool gate0-verify   [FIXED TRUSTED DEV TOOL / NO GENERIC JS]'));
assert(nativeShell.includes('"riftpp" -> {'));
assert(nativeShell.includes('headlessJs.executeRiftpp(args, cwd)'));
assert(nativeShell.includes('"rift-tool" -> {'));
assert(nativeShell.includes('headlessJs.executeDeveloperTool(args)'));
assert.equal((nativeShell.match(/private fun tokenize\(/g) || []).length, 1, 'native shell helper scope must remain structurally intact');
assert.equal((nativeShell.match(/private fun resolveFile\(/g) || []).length, 1, 'native shell file resolver must remain present exactly once');
assert(nativeShell.includes('private fun joinDisplay(base: String, child: String): String'));
assert(!nativeShell.includes('compatibilityFallback'));
assert(!nativeShell.includes('RiftShellBridge'));
assert(headless.includes('quickJs {'));
assert(headless.includes('const val POLYFILLS = \"\"\"'));
assert(headless.includes('const val RIFTPP_COMMAND_ENTRY = \"\"\"'));
assert(!headless.includes('private const val POLYFILLS'));
assert(!headless.includes('private const val RIFTPP_COMMAND_ENTRY'));
assert(headless.includes('preparedVmSource()'));
assert(headless.includes('preparedCoreSource()'));
assert(headless.includes('src/riftpp-core.js'));
assert(headless.includes('src/riftvm.js'));
assert(headless.includes("new Set(['state.load','state.save','state.remove'])"));
assert(headless.includes("if (sub === 'run-stateful')"));
assert(headless.includes("if (sub === 'exec-stateful')"));
assert(headless.includes('private fun stateSave(namespace: String, key: String, value: String): Boolean'));
assert(headless.includes('private fun stateLoad(namespace: String, key: String): String?'));
assert(headless.includes('private fun stateRemove(namespace: String, key: String): Boolean'));
assert(headless.includes('fun executeDeveloperTool(args: List<String>): CommandResult'));
assert(headless.includes('"gate0-verify" -> executeGate0Verifier()'));
assert(headless.includes('const val GATE0_VERIFY_ENTRY = """'));
assert(headless.includes('function("__rift_gate0_bundle")'));
assert(headless.includes('function("__rift_gate0_result")'));
assert(headless.includes('semantic-verifier-core.js'));
assert(headless.includes('reference-integrity-verifier-core.js'));
assert(headless.includes('RiftReferenceIntegrityVerifier'));
assert(headless.includes('compileRiftPlusPlusCoreV1: compiler.compile'));
assert(headless.includes('compileRiftPlusPlusCoreProgramV1: compiler.compileProgram'));
assert(headless.includes("schema: 'riftpp-gate0-device-verifier-suite/1'"));
assert(headless.includes('.put("installedSourceSha", BuildConfig.RIFT_SOURCE_SHA)'));
assert(headless.includes('Gate 0 verifier path is outside the fixed allowlist'));
const devStart = headless.indexOf('private fun executeGate0Verifier()');
const devEnd = headless.indexOf('\n    private fun gate0Bundle()', devStart);
assert(devStart >= 0 && devEnd > devStart, 'fixed Gate 0 verifier method must remain present');
const devSlice = headless.slice(devStart, devEnd);
for (const forbidden of ['__rift_read_text','__rift_write_text','__rift_state_load','__rift_state_save','__rift_state_remove','ProcessBuilder','Runtime.getRuntime().exec']) {
  assert(!devSlice.includes(forbidden), 'Gate 0 verifier must not expose authority: ' + forbidden);
}
assert(headless.includes('Rift++ state namespace exceeds $MAX_STATE_FILES records'));
assert.equal(hasWebKitDependency(headless), false);
assert(!headless.includes('ProcessBuilder'));

console.log('ok - reference Rift++ shell remains a deterministic bootstrap oracle');
console.log('ok - production native RiftShell routes Rift++ through headless QuickJS');
console.log('ok - Rift++ execution no longer depends on the trusted shell WebView');
