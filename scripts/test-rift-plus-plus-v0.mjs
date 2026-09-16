import { readFileSync } from 'node:fs';

const compiler = readFileSync('android/app/src/main/java/com/riftos/app/RiftPlusPlusV0.kt', 'utf8');
const coordinator = readFileSync('android/app/src/main/java/com/riftos/app/RiftSwarmCoordinatorV0.kt', 'utf8');
const cli = readFileSync('android/app/src/main/java/com/riftos/app/RiftExperimentalCli.kt', 'utf8');
const shell = readFileSync('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt', 'utf8');
const host = readFileSync('android/app/src/main/java/com/riftos/app/RiftToolHost.kt', 'utf8');
const gradle = readFileSync('android/app/build.gradle.kts', 'utf8');
const sample = readFileSync('examples/riftpp/riftos-dev-team.riftpp', 'utf8');
const spec = readFileSync('docs/systems/experimental-cli/RIFT_PLUS_PLUS_V0.md', 'utf8');

const failures = [];
const check = (name, condition) => { if (!condition) failures.push(name); };

check('Rift++ V0 compiler pins non-executable Swarm IR schema', compiler.includes('IR_SCHEMA = "rift.swarm-ir/0"') && compiler.includes('.put("executable", false)'));
check('Rift++ source is bounded and workspace-confined', compiler.includes('MAX_SOURCE_BYTES = 128 * 1024L') && compiler.includes('riftfs/workspace') && compiler.includes('path traversal is not allowed') && compiler.includes('.riftpp') && compiler.includes('.rift++'));
check('Rift++ grammar is declarative and finite', compiler.includes('setOf("backend", "brain", "agent", "swarm", "task")') && compiler.includes('flow') && compiler.includes('topologicalOrder') && compiler.includes('swarm flow contains a cycle'));
check('Rift++ has finite role and capability vocabularies', compiler.includes('private val roles = setOf(') && compiler.includes('private val capabilities = setOf(') && compiler.includes('workspace.read') && compiler.includes('git.push') && compiler.includes('device.control'));
check('review and security are compile-time read-only roles', compiler.includes('readOnlyRoles = setOf("review", "security")') && compiler.includes('mutatingCapabilities') && compiler.includes('is read-only and cannot allow'));
check('tools require explicit allow and cannot be denied', compiler.includes('every tool capability must also appear in allow') && compiler.includes('denied capability cannot be used as a tool'));
check('task requirements resolve against real worker roles', compiler.includes("requirement '$needed' has no matching worker role"));
check('swarm lead/reviewer cardinality is compile-time enforced', compiler.includes("must declare role lead") && compiler.includes('review-role worker(s)'));
check('V0 has no generic process or network execution path', !compiler.includes('ProcessBuilder') && !compiler.includes('Runtime.getRuntime') && !compiler.includes('Socket(') && !compiler.includes('exec('));
check('BrainBackend interface exists but V0 coordinator is preview-only', coordinator.includes('interface RiftBrainBackend') && coordinator.includes('fun respond(request: RiftBrainRequest): RiftBrainResponse') && coordinator.includes('object RiftSwarmCoordinatorV0') && coordinator.includes('backendInvoked", false') && coordinator.includes('execution", false'));
check('coordinator never invokes BrainBackend respond', !coordinator.includes('.respond('));
check('RiftCLI exposes Rift++ behind experimental gate', cli.includes('"riftpp" ->') && cli.includes('RiftPlusPlusV0.execute(context, tail)') && cli.includes('Rift++ compile/preview requires explicit process-local enable'));
check('native shell advertises Rift++ without a new MCP tool', shell.includes('riftpp') && !host.includes('riftpp') && !host.includes('rift_plus_plus'));
check('Android source verification includes Rift++ compiler/coordinator', gradle.includes('RiftPlusPlusV0.kt') && gradle.includes('RiftSwarmCoordinatorV0.kt'));
check('sample is a complete V0 swarm', sample.startsWith('riftpp 0') && /backend\s+RulesBrain\s*\{/.test(sample) && /brain\s+MainBrain\s*\{/.test(sample) && /agent\s+Reviewer\s*\{/.test(sample) && /swarm\s+DevTeam\s*\{/.test(sample) && /task\s+RepairRiftOS\s*\{/.test(sample));
check('sample keeps reviewer/security non-mutating', sample.includes('role security') && sample.includes('role review') && sample.includes('deny [workspace.write, git.push, device.control]'));
check('spec states the no-execution and RiftLLM boundaries', spec.includes('not executable') && spec.includes('no autonomous execution') && spec.includes('no changes to RiftLLM or its tokenizer/training stack'));

if (failures.length) {
  console.error('Rift++ V0 validation failed:');
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log('Rift++ V0 source contract OK');
