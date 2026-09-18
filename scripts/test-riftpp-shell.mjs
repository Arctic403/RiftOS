import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const referenceShell = readFileSync('src/riftos.js','utf8');
const batch = readFileSync('src/riftshell-batch.js','utf8');
const nativeShell = readFileSync('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt','utf8');
const headless = readFileSync('android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt','utf8');

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
assert(batch.includes('"rift-cli","riftpp","chat"'), 'riftpp must stay outside atomic batch');

assert(nativeShell.includes('riftpp help|version|self-test|check|compile|inspect|run|exec   [CORE V1 / HEADLESS QUICKJS]'));
assert(nativeShell.includes('"riftpp" -> {'));
assert(nativeShell.includes('headlessJs.executeRiftpp(args, cwd)'));
assert(!nativeShell.includes('compatibilityFallback'));
assert(!nativeShell.includes('RiftShellBridge'));
assert(headless.includes('quickJs {'));
assert(headless.includes('preparedVmSource()'));
assert(headless.includes('preparedCoreSource()'));
assert(headless.includes('src/riftpp-core.js'));
assert(headless.includes('src/riftvm.js'));
assert(!/(?:^|\n)\s*import\s+(?:android|androidx)\.webkit\b|(?:android|androidx)\.webkit\./m.test(headless));
assert(!headless.includes('ProcessBuilder'));

console.log('ok - reference Rift++ shell remains a deterministic bootstrap oracle');
console.log('ok - production native RiftShell routes Rift++ through headless QuickJS');
console.log('ok - Rift++ execution no longer depends on the trusted shell WebView');
