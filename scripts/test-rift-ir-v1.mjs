import { readFileSync } from 'node:fs';

const ir = readFileSync('android/app/src/main/java/com/riftos/app/RiftIrV1.kt', 'utf8');
const adapter = readFileSync('android/app/src/main/java/com/riftos/app/RiftIrCliV1.kt', 'utf8');
const compiler = readFileSync('android/app/src/main/java/com/riftos/app/RiftPlusPlusV0.kt', 'utf8');
const cli = readFileSync('android/app/src/main/java/com/riftos/app/RiftExperimentalCli.kt', 'utf8');
const host = readFileSync('android/app/src/main/java/com/riftos/app/RiftToolHost.kt', 'utf8');
const gradle = readFileSync('android/app/build.gradle.kts', 'utf8');
const spec = readFileSync('docs/systems/experimental-cli/RIFT_IR_V1.md', 'utf8');

const failures = [];
const check = (name, condition) => { if (!condition) failures.push(name); };

check('Rift IR V1 has a versioned language-independent schema/profile', ir.includes('SCHEMA = "rift.ir/1"') && ir.includes('VERSION = 1') && ir.includes('PROFILE = "swarm-core"') && ir.includes('SOURCE_SWARM_SCHEMA = "rift.swarm-ir/0"'));
check('Rift IR V1 lowers rather than replacing V0 Swarm IR', ir.includes('fun lowerFromSwarmIr') && compiler.includes('IR_SCHEMA = "rift.swarm-ir/0"') && compiler.includes('fun compileWorkspace'));
check('Rift IR V1 carries identity actors graphs tasks resources policy execution validation', ['identity','actors','graphs','tasks','resources','policy','execution','validation'].every(key => ir.includes(`.put("${key}"`)));
check('Rift IR V1 recomputes deterministic graph schedules and rejects cycles/tampered schedules/duplicate edges', ir.includes('deterministicSchedule') && ir.includes('contains a cycle') && ir.includes('source schedule does not match its graph') && ir.includes('schedule is not canonical') && ir.includes('contains duplicate edge'));
check('Rift IR V1 independently validates roles memory and capability policy', ir.includes('private val roles = setOf(') && ir.includes('private val memoryScopes = setOf(') && ir.includes('validateCapabilityPolicy') && ir.includes('tool capability is not allowed') && ir.includes('allows and denies the same capability') && ir.includes("read-only actor '$id' has mutation authority") && ir.includes('declared capability set does not match actor policy'));
check('Rift IR V1 is non-executable and single-concurrency by default', ir.includes('.put("mode", "inspect-only")') && ir.includes('.put("defaultConcurrency", 1)') && ir.includes('.put("backendInvocation", false)') && ir.includes('.put("toolInvocation", false)') && ir.includes('.put("localAgentInvocation", false)') && ir.includes('.put("mutation", false)'));
check('Rift IR V1 forbids raw shell and arbitrary process authority', ir.includes('.put("rawShellAllowed", false)') && ir.includes('.put("arbitraryProcessAllowed", false)') && !ir.includes('ProcessBuilder') && !ir.includes('Runtime.getRuntime') && !ir.includes('Socket('));
check('Rift IR adapter has only compile validate inspect', adapter.includes('"compile", "validate", "inspect"') && !adapter.includes('run') && !adapter.includes('executeShell') && adapter.includes('RiftPlusPlusV0.compileWorkspace') && adapter.includes('RiftIrV1.lowerFromSwarmIr'));
check('Rift IR CLI remains behind the experimental process-local gate', cli.includes('"ir" ->') && cli.includes('RiftIrCliV1.execute(context, tail)') && cli.includes('Rift IR compile/validate/inspect requires explicit process-local enable'));
check('Rift IR adds no MCP tool family', !host.includes('rift_ir') && !host.includes('riftir') && !host.includes('rift_ir_v1'));
check('Android source verification includes Rift IR core and adapter', gradle.includes('RiftIrV1.kt') && gradle.includes('RiftIrCliV1.kt'));
check('Rift IR spec preserves no-execution and frontend separation', spec.includes('rift.ir/1') && spec.includes('language-independent') && spec.includes('inspect-only') && spec.includes('does **not** replace `rift.swarm-ir/0`'));

if (failures.length) {
  console.error('Rift IR V1 validation failed:');
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log('Rift IR V1 source contract OK');
