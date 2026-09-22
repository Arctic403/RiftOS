import fs from 'node:fs';

const read = path => fs.readFileSync(path, 'utf8');
const exists = path => fs.existsSync(path);
const failures = [];
const check = (name, condition) => { if (!condition) failures.push(name); };

const k = 'android/app/src/main/java/com/riftos/app/';
const cpp = 'android/app/src/main/cpp/';
const host = read(k + 'RiftCliHost.kt');
const shell = read(k + 'RiftNativeShell.kt');
const services = read(k + 'RiftNativeShellServices.kt');
const localAgent = read(k + 'RiftVortexLocalAgent.kt');
const localPackage = read(k + 'RiftLocalCliPackage.kt');
const gradle = read('android/app/build.gradle.kts');
const cmake = read(cpp + 'CMakeLists.txt');
const core = read(cpp + 'riftcli/rift_cli_core.cpp');
const header = read(cpp + 'riftcli/rift_cli_core.h');
const jni = read(cpp + 'riftcli/rift_cli_jni.cpp');
const docs = read('docs/systems/riftcli/README.md');

const retired = [
  'RiftExperimentalCli.kt',
  'RiftCliPatchLifecycleV1.kt',
  'RiftDocumentationParityV1.kt',
  'RiftVerificationPlannerV1.kt',
  'RiftResearchLedgerV1.kt',
  'RiftPlusPlusV0.kt',
  'RiftIrV1.kt',
  'RiftIrCliV1.kt',
  'RiftSwarmCoordinatorV0.kt',
  'RiftTextEncoderTaskRunner.kt',
  'RiftCliDriverProtocolV1.kt',
  'RiftEngineeringStateV1.kt',
];

check('retired Kotlin CLI sources are absent', retired.every(name => !exists(k + name)));

check(
  'thin Kotlin host loads native RiftCLI and fails closed on transport/envelope errors',
  host.includes('internal object RiftCliHost') &&
    host.includes('System.loadLibrary("riftcli")') &&
    host.includes('private external fun nativeExecute') &&
    host.includes('nativeLoadFailure') &&
    host.includes('rift.cli-host-error/1') &&
    host.includes('authorityState') &&
    host.includes('val raw = nativeExecute(args.toTypedArray(), cwd)') &&
    host.includes('envelope.optJSONObject("result")')
);

check(
  'RiftShell routes rift-cli through RiftOS Local Agent into the existing native supervisor',
  shell.includes('"rift-cli" -> executeHostedCliCommand(cwd, args)') &&
    shell.includes('RiftOsLocalAgent.execute(appContext, request)') &&
    shell.includes('internal fun executeCliForLocalAgent') &&
    shell.includes('RiftCliHost.executeShell(args, cwd)') &&
    shell.includes('origin = "rift-local-agent-cli"') &&
    localAgent.includes('private object RiftLocalAgentCliIntelligence') &&
    localAgent.includes('if (op == "intelligence") return RiftLocalAgentCliIntelligence.execute(context, args)') &&
    localAgent.includes('private val TRUST_KERNEL_COMMANDS = setOf("help", "status", "architecture", "enable", "disable", "driver")') &&
    localAgent.includes('if (command in TRUST_KERNEL_COMMANDS)') &&
    localAgent.includes('executeLocalCliForLocalAgent(cwd, argv)') &&
    localAgent.includes('RiftCLI is disabled; enable it through the native process gate before local intelligence execution') &&
    localAgent.includes('Local RiftCLI package may re-enter only the native driver protocol') &&
    !shell.includes('RiftExperimentalCli')
);

check(
  'Local Agent remains an independent RiftOS authority',
  services.includes('RiftOsLocalAgent.execute(context,request)') &&
    !services.includes('RiftAgentRouter')
);

check(
  'replaceable local RiftCLI package is sandboxed and cannot self-grant authority',
  localPackage.includes('private const val PACKAGE_RELATIVE = "workspace/.riftcli"') &&
    localPackage.includes('private const val MANIFEST_SCHEMA = "rift.cli-local-package/1"') &&
    localPackage.includes('runtime.executeQuickJs(') &&
    localPackage.includes('.put("riftFsWrite", false)') &&
    localPackage.includes('.put("processAuthority", false)') &&
    localPackage.includes('.put("networkAuthority", false)') &&
    localPackage.includes('.put("androidAuthority", false)') &&
    localPackage.includes('.put("gitAuthority", false)') &&
    localPackage.includes('.put("shellAuthority", false)') &&
    localPackage.includes('.put("toolHostAuthority", false)') &&
    localPackage.includes('responseLines.size == 1') &&
    localPackage.includes('val entrySnapshotName = "entry-$invocationId.js"') &&
    localPackage.includes('entrySnapshot.writeText(manifest.entryFile.readText(Charsets.UTF_8), Charsets.UTF_8)') &&
    localPackage.includes('runCatching { entrySnapshot.delete() }') &&
    localPackage.includes('values.first().trim().lowercase() == "driver"') &&
    localPackage.includes('RiftCLI local package may request only the native driver protocol') &&
    shell.includes('private val localCliPackage = RiftLocalCliPackage(appContext, headlessJs)') &&
    shell.includes('internal fun localCliPackageStatus()') &&
    shell.includes('internal fun executeLocalCliForLocalAgent')
);

check(
  'docs lock compiled trust kernel plus local replaceable intelligence without activating blocked cognition',
  docs.includes('compiled RiftCLI trust kernel') &&
    docs.includes('/workspace/.riftcli/') &&
    docs.includes('may propose/request work but can never grant itself authority') &&
    docs.includes('Planner, project memory, skills, command policy, Observer interpretation') &&
    docs.includes('no new CLI intelligence capability is added until N1.8.0 Repository Consistency Observer is fully torture-tested and promoted')
);

check(
  'RiftOS Local Agent exposes a bounded CLI compatibility namespace without exposing raw intelligence',
  services.includes('if(args.firstOrNull()?.lowercase()=="cli")') &&
    services.includes('.put("op","intelligence")') &&
    services.includes('.put("cwd",cwd)') &&
    services.includes('.put("argv",JSONArray(args))') &&
    services.includes('$name cli [help|status|architecture|enable|disable|driver ...]') &&
    services.includes('RiftOsLocalAgent.execute(context,request)') &&
    !services.includes('if(args.firstOrNull()?.lowercase()=="intelligence")')
);

check(
  'CMake builds a dedicated shared native core',
  cmake.includes('add_library(') &&
    cmake.includes('riftcli') &&
    cmake.includes('SHARED') &&
    cmake.includes('riftcli/rift_cli_core.cpp') &&
    cmake.includes('riftcli/rift_cli_jni.cpp') &&
    cmake.includes('cxx_std_17')
);

check(
  'Android builds both primary ARM64 and required ARM32 ABI',
  gradle.includes('ndkVersion = "28.2.13676358"') &&
    gradle.includes('abiFilters += listOf("arm64-v8a", "armeabi-v7a")') &&
    gradle.includes('path = file("src/main/cpp/CMakeLists.txt")') &&
    gradle.includes('version = "3.22.1"')
);

for (const required of [
  'src/main/cpp/CMakeLists.txt',
  'src/main/cpp/riftcli/rift_cli_core.cpp',
  'src/main/cpp/riftcli/rift_cli_core.h',
  'src/main/cpp/riftcli/rift_cli_jni.cpp',
  'src/main/java/com/riftos/app/RiftCliHost.kt',
  'src/main/java/com/riftos/app/RiftLocalCliPackage.kt',
]) {
  check(`Android exact source snapshot includes ${required}`, gradle.includes(required));
}

check(
  'native core defaults off and requires explicit process-local confirmation',
  core.includes('std::atomic<bool> g_enabled{false}') &&
    core.includes('CONFIRM-EXPERIMENTAL') &&
    core.includes('"persistentEnable\\":false') &&
    core.includes('"defaultEnabled\\":false')
);

check(
  'N1 authority is full RiftOS only when explicitly enabled',
  core.includes('full-riftos-when-enabled') &&
    core.includes('"mutation\\":') &&
    core.includes('"toolExecution\\":') &&
    core.includes('"networkViaRiftOs\\":') &&
    core.includes('"directModelBackend\\":false') &&
    core.includes('"directNetworkClient\\":false') &&
    core.includes('bounded-riftos-authorities')
);

check(
  'RiftOS Local Agent owns the CLI host boundary while the native core remains gated',
  core.includes('MCP/RiftShell -> RiftOS Local Agent -> RiftCLI') &&
    core.includes(String.raw`\"hostOwner\":\"riftos-local-agent`) &&
    core.includes(String.raw`\"hostedByLocalAgent\":true`) &&
    core.includes(String.raw`\"directExternalHost\":false`) &&
    core.includes(String.raw`\"cliCallsDriver\":false`) &&
    docs.includes('RiftCLI **never calls a model or inference API**')
);

check(
  'JNI uses explicit UTF-16/UTF-8 transcoding and bounded ingress',
  jni.includes('GetStringChars') &&
    jni.includes('ReleaseStringChars') &&
    jni.includes('NewString(') &&
    jni.includes('utf8ToUtf16') &&
    jni.includes('kMaxNativeArgs = 512') &&
    jni.includes('kMaxNativeArgBytes = 128 * 1024') &&
    jni.includes('kMaxNativeTotalArgBytes = 512 * 1024') &&
    jni.includes('kMaxNativeCwdBytes = 4096') &&
    jni.includes(String.raw`\"authorityState\":\"unknown\"`) &&
    !jni.includes('GetStringUTFChars') &&
    !jni.includes('NewStringUTF')
);

const forbiddenNativeCalls = [
  /\bsystem\s*\(/,
  /\bpopen\s*\(/,
  /\bfork\s*\(/,
  /\bsocket\s*\(/,
  /\bconnect\s*\(/,
  /\bcurl_easy_/,
  /https?:\/\//,
];
check(
  'native core delegates authority without gaining raw process/network clients',
  forbiddenNativeCalls.every(pattern => !pattern.test(core) && !pattern.test(jni))
);

check(
  'native core interface stays narrow',
  header.includes('struct CommandResponse') &&
    header.includes('CommandResponse execute(') &&
    !header.includes('filesystem') &&
    !header.includes('network')
);

check(
  'RiftCLI docs lock plan-global act-incremental and retired-batch isolation',
  docs.includes('plan globally but act incrementally') &&
    docs.includes('N1.6 adds a **new** bounded batch mechanism') &&
    docs.includes('retired RiftShell batch implementation and multi-operation `rift_workspace_exec` remain fail-fast disabled')
);

if (failures.length) {
  console.error('RiftCLI native foundation validation failed:');
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log('RiftCLI native foundation source contract OK');
