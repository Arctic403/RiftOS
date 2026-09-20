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

check(
  'retired Kotlin CLI sources are absent',
  retired.every(name => !exists(k + name))
);

check(
  'thin Kotlin host loads only the native RiftCLI library',
  host.includes('internal object RiftCliHost') &&
    host.includes('System.loadLibrary("riftcli")') &&
    host.includes('private external fun nativeExecute') &&
    host.includes('JSONObject(nativeExecute(args.toTypedArray(), cwd))')
);

check(
  'RiftShell routes rift-cli into the new host',
  shell.includes('"rift-cli" ->') &&
    shell.includes('RiftCliHost.executeShell(args, cwd)') &&
    !shell.includes('RiftExperimentalCli')
);

check(
  'Local Agent no longer routes through old CLI',
  services.includes('RiftOsLocalAgent.execute(context,request)') &&
    !services.includes('RiftAgentRouter')
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
  'bootstrap exposes zero engineering mutation/model/network/tool authority',
  core.includes('"modelBackend\\":false') &&
    core.includes('"networkAuthority\\":false') &&
    core.includes('"mutationAuthority\\":false') &&
    core.includes('"toolExecution\\":false') &&
    core.includes('"projectMemory\\":false') &&
    core.includes('"planner\\":false') &&
    core.includes('"verificationEngine\\":false')
);

check(
  'external driver dependency direction is one-way into CLI',
  core.includes('external-driver -> MCP/RiftShell -> RiftCLI') &&
    core.includes('"cliCallsDriver\\":false') &&
    docs.includes('RiftCLI **never calls a model or inference API**')
);

check(
  'JNI uses explicit UTF-16/UTF-8 transcoding',
  jni.includes('GetStringChars') &&
    jni.includes('ReleaseStringChars') &&
    jni.includes('NewString(') &&
    jni.includes('utf8ToUtf16') &&
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
  'native bootstrap has no process/network execution surface',
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
  'RiftCLI docs lock plan-global act-incremental and no-batch policy',
  docs.includes('plan globally but act incrementally') &&
    docs.includes('must not reintroduce the retired multi-operation batch-edit model')
);

if (failures.length) {
  console.error('RiftCLI native bootstrap validation failed:');
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log('RiftCLI native bootstrap source contract OK');
