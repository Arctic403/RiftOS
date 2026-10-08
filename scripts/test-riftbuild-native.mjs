import fs from 'node:fs';
import assert from 'node:assert/strict';

const read = file => fs.readFileSync(file, 'utf8');
const exists = file => fs.existsSync(file);

const paths = {
  capability: 'android/app/src/main/java/com/riftos/app/RiftLocalBuildCapability.kt',
  jvmDex: 'android/app/src/main/java/com/riftos/app/RiftJvmDexService.kt',
  platform: 'android/app/src/main/java/com/riftos/app/RiftBuildPlatformTools.kt',
  verifier: 'android/app/src/main/java/com/riftos/app/RiftApkV2Verifier.kt',
  installer: 'android/app/src/main/java/com/riftos/app/RiftBuildInstaller.kt',
  managed: 'android/app/src/main/java/com/riftos/app/RiftBuildManagedToolchains.kt',
  jvmTool: 'android/app/src/main/java/com/riftos/app/RiftManagedJvmToolService.kt',
  nativeBuffer: 'android/app/src/main/java/com/riftos/app/RiftNativeBufferCompilerService.kt',
  abi: 'android/app/src/main/java/com/riftos/app/RiftAppAbi.kt',
  broker: 'android/app/src/main/java/com/riftos/app/RiftRappCapabilityBroker.kt',
  host: 'android/app/src/main/java/com/riftos/app/RiftRappHost.kt',
  coreExecutor: 'android/app/src/main/java/com/riftos/app/RiftCoreAppExecutor.kt',
  manager: 'android/app/src/main/java/com/riftos/app/RiftRappManager.kt',
  jsonAdapter: 'android/app/src/main/java/com/riftos/app/RiftRappJsonAdapter.kt',
  quickjs: 'android/app/src/main/java/com/riftos/app/RiftRappQuickJsExecutor.kt',
  absolute: 'android/app/src/main/java/com/riftos/app/RiftRappAbsoluteView.kt',
  shell: 'android/app/src/main/java/com/riftos/app/RiftNativeShell.kt',
  toolHost: 'android/app/src/main/java/com/riftos/app/RiftToolHost.kt',
  browser: 'android/app/src/main/java/com/riftos/app/RiftBrowserAppHost.kt',
  gradle: 'android/app/build.gradle.kts',
};

for (const file of Object.values(paths)) {
  assert.ok(exists(file), 'required generic RiftOS platform source missing: ' + file);
}

const retired = [
  'android/app/src/main/java/com/riftos/app/RiftBuildLocalExecutor.kt',
  'android/app/src/main/java/com/riftos/app/RiftBuildKotlinCompiler.kt',
  'android/app/src/main/java/com/riftos/app/RiftBuildNativeToolchain.kt',
  'android/app/src/main/java/com/riftos/app/RiftBuildNativeApp.kt',
  'android/app/src/main/java/com/riftos/app/RiftApkV2Signer.kt',
  'apps/riftbuild-hosted/runtime.js',
  'apps/riftbuild-hosted/riftapp.json',
  'apps/riftbuild-hosted/program.json',
];
for (const file of retired) {
  assert.ok(!exists(file), 'retired embedded RiftBuild source resurfaced: ' + file);
}

const capability = read(paths.capability);
const jvmDex = read(paths.jvmDex);
const platform = read(paths.platform);
const verifier = read(paths.verifier);
const installer = read(paths.installer);
const managed = read(paths.managed);
const jvmTool = read(paths.jvmTool);
const nativeBuffer = read(paths.nativeBuffer);
const abi = read(paths.abi);
const broker = read(paths.broker);
const host = read(paths.host);
const coreExecutor = read(paths.coreExecutor);
const manager = read(paths.manager);
const jsonAdapter = read(paths.jsonAdapter);
const quickjs = read(paths.quickjs);
const absolute = read(paths.absolute);
const shell = read(paths.shell);
const toolHost = read(paths.toolHost);
const browser = read(paths.browser);
const gradle = read(paths.gradle);

for (const required of [
  'class RiftLocalBuildCapability',
  'RiftBuildManagedToolchains',
  'compilerStatus(',
  'compilerRun(',
  'compilerRunInline(',
  'RiftManagedJvmToolService.run(',
  'RiftNativeBufferCompilerService.compile(',
  'build/riftbuild/',
  'Managed compiler classpath escaped project/toolchains',
]) assert.ok(capability.includes(required), 'build.local capability contract missing: ' + required);
assert.doesNotMatch(
  capability,
  /PackageInstaller|RiftApkV2Verifier|packRapp|signArtifact|prepare-native-app|compile-native/,
  'build.local capability must not regain package/sign/install orchestration'
);

for (const required of [
  'class RiftJvmDexService',
  'riftbuild-kotlin-toolchain-status/2',
  'compilerAuthority',
  'managed-external',
  'rift-jvm-dex/1',
  'D8Command.builder()',
  'OutputMode.DexIndexed',
  'JVM classes directory must stay under build/riftbuild',
]) assert.ok(jvmDex.includes(required), 'JVM DEX service contract missing: ' + required);
assert.doesNotMatch(jvmDex, /K2JVMCompiler|PackageInstaller|AndroidKeyStore/);

for (const required of [
  'class RiftBuildManagedToolchains',
  'riftbuild-hot.json',
  'riftbuild-managed-payloads/1',
  'riftbuild-compiler-json/1',
  'native-buffer-v1',
  'dex-json-v1',
]) assert.ok(managed.includes(required), 'managed compiler registry contract missing: ' + required);

for (const required of [
  'class RiftBuildPlatformTools',
  '"compiler-status"',
  '"compiler-run"',
  '"jvm-status"',
  '"jvm-dex"',
  '"pack-rapp"',
  '"install-rapp"',
  '"launch-rapp"',
  '"rapp-list"',
  '"verify"',
  '"install-proof"',
  '"install-status"',
  '"launch-proof"',
  'RiftApkV2Verifier()',
  'RiftCoreRuntime.packages(appContext)',
  'RiftCoreRuntime.runtimes(appContext)',
]) assert.ok(platform.includes(required), 'platform tool contract missing: ' + required);
assert.doesNotMatch(platform, /RiftRappManager\(appContext\)|RiftExternalRuntimeProviders\(appContext\)/, 'platform tools must delegate package/runtime ownership to Core');
for (const forbidden of [
  '"kotlin-compile"',
  '"compile-native"',
  '"compile-object"',
  '"extract-object-text"',
  '"prepare-native-app"',
  '"toolchain-install-bundled"',
  '"sign" ->',
  '"pack" ->',
  'signArtifact(',
]) assert.ok(!platform.includes(forbidden), 'retired platform command resurfaced: ' + forbidden);

for (const required of [
  'class RiftApkV2Verifier',
  'fun verify(',
  'APK Sig Block 42',
  'V2_BLOCK_ID = 0x7109871a',
  'SIGNATURE_ALGORITHM_ID = 0x0103',
  'SHA256withRSA',
  'v2 signed-data RSA signature verification failed',
  'v2 protected APK content digest mismatch',
]) assert.ok(verifier.includes(required), 'APK verifier contract missing: ' + required);
assert.doesNotMatch(
  verifier,
  /AndroidKeyStore|KeyGenParameterSpec|KeyPairGenerator|KeyStore|fun sign\(/,
  'APK verifier must remain keyless and verification-only'
);
assert.match(installer, /verified: RiftApkV2Verifier\.VerifyResult/);
assert.match(installer, /PackageInstaller\.SessionParams/);
assert.match(installer, /USER_ACTION_REQUIRED/);

for (const required of [
  'Capability.BUILD_LOCAL',
  '"toolchainStatus"',
  '"compilerRun"',
  '"jvmDex"',
  'RiftLocalBuildCapability(activity.applicationContext)',
  'Capability.SIGNING_IDENTITY',
  '"signSha256RsaPkcs1"',
  '"verifySha256RsaPkcs1"',
  'AndroidKeyStore',
  'SHA256withRSA',
  'riftbuild-apk-v2-rsa-v1',
]) assert.ok(broker.includes(required), 'generic capability broker contract missing: ' + required);
assert.match(
  broker,
  /\bBUILD_OPERATION_TIMEOUT_MS\s*=\s*180_000L\b/,
  'generic build operation must retain its 180-second bounded timeout'
);
assert.doesNotMatch(broker, /RiftBuildLocalExecutor|RiftBuildNativeToolchain|RiftBuildNativeApp/);

assert.match(abi, /BUILD_LOCAL\s*=\s*"build\.local"/);
assert.match(abi, /SIGNING_IDENTITY\s*=\s*"signing\.identity"/);
assert.match(abi, /QUICKJS\s*=\s*"quickjs-v1"/);
assert.match(abi, /NATIVE_BUFFER\s*=\s*"native-buffer-v1"/);
assert.match(abi, /HOST_EFFECT_RESULT/);

for (const required of [
  'json-generic-v1',
  'json-frame-v1',
  'riftos-app-event-json/1',
  'riftos-app-output-json/1',
]) assert.ok(jsonAdapter.includes(required), 'generic JSON adapter contract missing: ' + required);
assert.match(quickjs, /class RiftRappQuickJsExecutor/);
assert.doesNotMatch(
  quickjs,
  /RiftVolumePaths|ProcessBuilder|Runtime\.getRuntime|PackageInstaller|RiftApkV2Verifier/,
  'QuickJS executor must remain authority-free'
);

for (const required of [
  'MAX_EFFECT_DEPTH = 1024',
  'ScrollView(activity)',
  '!field.hasFocus()',
  'coreExecutor.execute(',
  'HOST_EFFECT_RESULT',
]) assert.ok(host.includes(required), 'generic RAPP host contract missing: ' + required);
for (const required of [
  'RiftRappQuickJsExecutor()', 'RiftNativeBufferCompilerService.compile(',
  'RiftBoundedAsync.submit(', 'sessions.commitFromExecution(',
  'adapter.encodeEvent(', 'adapter.decodeOutput(',
]) assert.ok(coreExecutor.includes(required), 'Core RAPP executor contract missing: ' + required);
assert.doesNotMatch(
  host, /RiftRappQuickJsExecutor\(|RiftBoundedAsync\.submit\(|RiftNativeBufferCompilerService\.compile\(/,
  'UI host must not execute language runtimes directly'
);
assert.match(host, /\bisFillViewport\s*=\s*true\b/, 'generic RAPP host scroll view must fill viewport');
assert.doesNotMatch(host, /Riftpp|RPE2|RUI2/, 'generic RAPP host must not own language protocol semantics');

for (const required of [
  'riftos.rapp-project/1',
  'riftos.rapp/1',
  '/C:/Programs',
  'state.bin',
  'persistState',
  'RiftAppAdapters.find',
]) assert.ok(manager.includes(required), 'RAPP manager contract missing: ' + required);
assert.doesNotMatch(manager, /RiftApkV2Verifier|PackageInstaller/);

assert.match(absolute, /Layout\s*\.\s*ABSOLUTE/);
assert.match(absolute, /NodeKind\s*\.\s*TEXT_INPUT/);
assert.match(absolute, /EventKind\s*\.\s*TEXT_INPUT/);

assert.match(shell, /private val riftBuild = RiftCoreRuntime\.buildPlatform\(appContext\)/);
assert.match(shell, /riftbuild compiler-status\|compiler-run\|jvm-status\|jvm-dex\|runtime-status\|pack-rapp\|install-rapp\|launch-rapp\|rapp-list\|verify\|install-proof\|install-status\|launch-proof/);
for (const retiredCommand of [
  'riftbuild doctor|validate|plan',
  'kotlin-compile',
  'compile-native',
  'prepare-native-app',
  '|pack|sign|',
]) assert.ok(!shell.includes(retiredCommand), 'retired RiftShell build surface resurfaced: ' + retiredCommand);

for (const required of [
  '"compiler-run"',
  '"jvm-dex"',
  '"pack-rapp"',
  '"install-rapp"',
  '"verify"',
  '"install-proof"',
]) assert.ok(toolHost.includes(required), 'persistent job classifier missing surviving operation: ' + required);
for (const retiredCommand of [
  '"kotlin-compile"',
  '"compile-native"',
  '"compile-object"',
  '"extract-object-text"',
  '"prepare-native-app"',
  '"toolchain-install-bundled"',
]) assert.ok(!toolHost.includes(retiredCommand), 'job classifier retained retired operation: ' + retiredCommand);

for (const retiredBrowserSurface of [
  'build.doctor',
  'build.plan',
  'build.prepare',
  'build.submit',
  'build.runs',
  'build.artifacts',
  'build:Object.freeze',
  'RiftBuildLocalExecutor',
]) assert.ok(!browser.includes(retiredBrowserSurface), 'browser retained retired build orchestration: ' + retiredBrowserSurface);

for (const required of [
  '"src/main/java/com/riftos/app/RiftLocalBuildCapability.kt"',
  '"src/main/java/com/riftos/app/RiftJvmDexService.kt"',
  '"src/main/java/com/riftos/app/RiftBuildPlatformTools.kt"',
  '"src/main/java/com/riftos/app/RiftApkV2Verifier.kt"',
  '"src/main/java/com/riftos/app/RiftBuildManagedToolchains.kt"',
]) assert.ok(gradle.includes(required), 'Gradle generic platform source contract missing: ' + required);
for (const retiredSource of [
  'RiftBuildLocalExecutor.kt',
  'RiftBuildKotlinCompiler.kt',
  'RiftBuildNativeToolchain.kt',
  'RiftBuildNativeApp.kt',
  'RiftApkV2Signer.kt',
]) assert.ok(!gradle.includes(retiredSource), 'Gradle retained retired RiftBuild source: ' + retiredSource);

for (const required of [
  'class RiftManagedJvmToolService : Service()',
  'DESCRIPTOR = "com.riftos.app.RiftManagedJvmToolService"',
  'MAX_PAYLOAD_BYTES = 128L * 1024L * 1024L',
  'RUN_TIMEOUT_SECONDS = 10 * 60L',
  'Process.killProcess(remotePid)',
]) assert.ok(jvmTool.includes(required), 'managed JVM execution service contract missing: ' + required);
for (const required of [
  'class RiftNativeBufferCompilerService : Service()',
  'DESCRIPTOR = "com.riftos.app.RiftNativeBufferCompilerService"',
  'MAX_SOURCE_BYTES = 512 * 1024',
  'MAX_OUTPUT_BYTES = 512 * 1024',
  'COMPILE_TIMEOUT_SECONDS = 3L',
]) assert.ok(nativeBuffer.includes(required), 'native-buffer engine contract missing: ' + required);

console.log('ok - RiftOS generic build provider boundary and legacy purge');
