import fs from 'node:fs';
import assert from 'node:assert/strict';

const read = file => fs.readFileSync(file, 'utf8');
const exists = file => fs.existsSync(file);

const nativeBuildPath = 'android/app/src/main/java/com/riftos/app/RiftBuildLocalExecutor.kt';
const nativeToolchainPath = 'android/app/src/main/java/com/riftos/app/RiftBuildNativeToolchain.kt';
const nativeAppPath = 'android/app/src/main/java/com/riftos/app/RiftBuildNativeApp.kt';
const signerPath = 'android/app/src/main/java/com/riftos/app/RiftApkV2Signer.kt';
const installerPath = 'android/app/src/main/java/com/riftos/app/RiftBuildInstaller.kt';
const appAbiPath = 'android/app/src/main/java/com/riftos/app/RiftAppAbi.kt';
const rappAdapterPath = 'android/app/src/main/java/com/riftos/app/RiftRappRiftppAdapter.kt';
const rappWs15AdapterPath = 'android/app/src/main/java/com/riftos/app/RiftRappRiftppWs15Adapter.kt';
const rappAbsoluteViewPath = 'android/app/src/main/java/com/riftos/app/RiftRappAbsoluteView.kt';
const rappHostPath = 'android/app/src/main/java/com/riftos/app/RiftRappHost.kt';
const rappManagerPath = 'android/app/src/main/java/com/riftos/app/RiftRappManager.kt';
for (const file of [nativeBuildPath, nativeToolchainPath, nativeAppPath, signerPath, installerPath, appAbiPath, rappAdapterPath, rappWs15AdapterPath, rappAbsoluteViewPath, rappHostPath, rappManagerPath]) {
  assert.ok(exists(file), 'RiftBuild source owner is missing: ' + file);
}

const nativeBuild = read(nativeBuildPath);
const nativeToolchain = read(nativeToolchainPath);
const nativeApp = read(nativeAppPath);
const signer = read(signerPath);
const installer = read(installerPath);
const appAbi = read(appAbiPath);
const rappAdapter = read(rappAdapterPath);
const rappWs15Adapter = read(rappWs15AdapterPath);
const rappAbsoluteView = read(rappAbsoluteViewPath);
const rappHost = read(rappHostPath);
const rappManager = read(rappManagerPath);
assert.ok(nativeToolchain.includes("entry.name.replace('\\\\', '/')"), 'bundled toolchain ZIP paths must normalize a single escaped backslash char');
assert.ok(!nativeToolchain.includes("entry.name.replace('\\\\\\\\', '/')"), 'bundled toolchain ZIP path normalization must not use the invalid four-backslash Kotlin char literal');
const mainActivity = read('android/app/src/main/java/com/riftos/app/MainActivity.kt');
const shell = read('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt');
const riftppEditorBridgeClient = read('android/app/src/main/java/com/riftos/app/RiftppEditorBridgeClient.kt');
const riftppEditorBridgeService = read('android/app/src/main/java/com/riftpp/editor/RiftppEditorBridgeService.kt');
const riftppEditorMain = read('android/app/src/main/java/com/riftpp/editor/MainActivity.kt');
const riftppEditorApkBuilder = read('android/app/src/main/java/com/riftpp/editor/RiftppApkBuilder.kt');
const riftppEditorNativeElfPreflight = read('android/app/src/main/java/com/riftpp/editor/RiftppNativeElfPreflight.kt');
const riftppEditorRelocatableElfPreflight = read('android/app/src/main/java/com/riftpp/editor/RiftppRelocatableElfPreflight.kt');
const nativeBufferCompilerService = read('android/app/src/main/java/com/riftos/app/RiftNativeBufferCompilerService.kt');
const nativeBufferCompilerHost = read('android/app/src/main/cpp/compiler/rift_native_buffer_compiler_host.cpp');
const managedJvmToolService = read('android/app/src/main/java/com/riftos/app/RiftManagedJvmToolService.kt');
const managedToolchains = read('android/app/src/main/java/com/riftos/app/RiftBuildManagedToolchains.kt');
const kotlinCompiler = read('android/app/src/main/java/com/riftos/app/RiftBuildKotlinCompiler.kt');
const managedKotlinTool = read('android/rift-managed-kotlin-tool/src/main/java/com/riftbuild/tools/kotlinc/KotlinCompilerTool.kt');
const managedKotlinToolGradle = read('android/rift-managed-kotlin-tool/build.gradle.kts');
const riftAppDiagnosticBridge = read('android/app/src/main/java/com/riftos/app/RiftAppDiagnosticBridge.kt');
const appHost = read('android/app/src/main/java/com/riftos/app/RiftBrowserAppHost.kt');
const gradle = read('android/app/build.gradle.kts');
const androidSettings = read('android/settings.gradle.kts');
const cmake = read('android/app/src/main/cpp/CMakeLists.txt');
const manifest = read('android/app/src/main/AndroidManifest.xml');
const retained = read('src/riftbuild.js');
const toolHost = read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const surfaces = read('docs/PUBLIC_SURFACES.md');
for (const required of [
  'const val PORT = 39771',
  'const val PACKET_BYTES = 32',
  'DatagramSocket(PORT, InetAddress.getByName("127.0.0.1"))',
  'MAX_EVENTS = 64',
  'ROOT_RELATIVE = "system/volumes/D/Diagnostics/riftpp"',
  'processExitHistory',
  'getHistoricalProcessExitReasons',
  'ApplicationExitInfo.REASON_ANR',
  'RIFTPP_NATIVE_EDITOR_V1_PACKAGE',
  'packetSha256',
  'entry-begin',
  'before-return',
  'fatal-signal',
]) {
  assert.ok(
    riftAppDiagnosticBridge.includes(required),
    'Rift++ diagnostic bridge contract missing: ' + required
  );
}
for (const forbidden of [
  'ANativeActivity_onCreate',
  'ET_DYN',
  'DT_HASH',
  'DT_SYMTAB',
  'decodeRiftpp',
  'emitArm',
]) {
  assert.ok(
    !riftAppDiagnosticBridge.includes(forbidden),
    'Rift++ diagnostic bridge must remain transport-only: ' + forbidden
  );
}
assert.ok(
  riftAppDiagnosticBridge.includes('RIFTPP_ADAPTER_R1_PACKAGE = "com.riftpp.editor.adapterr1"'),
  'Rift++ adapter R1 identity must remain local to the diagnostic bridge'
);

for (const required of [
  'RiftAppDiagnosticBridge.supports(safePackageName)',
  'RiftAppDiagnosticBridge.beginLaunch',
  'artifactSha256',
]) {
  assert.ok(
    installer.includes(required),
    'RiftBuild installer diagnostic launch hook missing: ' + required
  );
}
for (const required of [
  'ALLOWED_PERMISSIONS',
  '"android.permission.INTERNET"',
  'Native app permission is not allowed',
  'uses-permission',
]) {
  assert.ok(
    nativeApp.includes(required),
    'Native app bounded permission contract missing: ' + required
  );
}
assert.ok(
  manifest.includes('<package android:name="com.riftpp.editor" />'),
  'RiftOS manifest must expose Rift++ editor bridge package visibility'
);
for (const forbiddenRiftppVisibility of [
  '<package android:name="com.riftpp.editor.nativev1" />',
  '<package android:name="com.riftpp.editor.adapterr1" />',
  '<package android:name="com.riftpp.nativeproof" />',
]) {
  assert.ok(
    !manifest.includes(forbiddenRiftppVisibility),
    'RiftOS manifest must not pin Rift++ proof package visibility: ' + forbiddenRiftppVisibility
  );
}
assert.ok(
  shell.includes('riftcrash help|status|start|capture|latest|reset [package]'),
  'RiftShell diagnostic bridge control surface is missing'
);

for (const forbidden of [
  'ANativeActivity_onCreate',
  'ET_DYN',
  'DT_HASH',
  'DT_SYMTAB',
]) {
  assert.ok(
    !shell.includes(forbidden),
    'Rift++ shell must not own ELF/Android symbol semantics: ' + forbidden
  );
}

for (const required of [
  'class RiftBuildLocalExecutor',
  'system/riftbuild/v1/runs',
  'documents/builds',
  'workspaceRoot',
  'RiftBuildNativeToolchain',
  'toolchain-status',
  'toolchain-install-bundled',
  'compile-native',
  'compile-object',
  'nativeToolchain.compileAssemblyObject(',
  'extract-object-text',
  'nativeToolchain.extractRelocationFreeText(',
  'RiftBuildNativeApp',
  'prepare-native-app',
  'manifestNativeProject',
  'nativeToolchain.validateProject(ref.file)',
  'nativeApp.validateProject(ref.file)',
  'structuredCompilerProcessExecution',
  'downloadedToolchainsAllowed',
  'preparedArtifactPackagerReady',
  'prepared-native-proof',
  'ByteArrayOutputStream',
  'ZipOutputStream',
  'AndroidManifest.xml must be compiled Android binary XML',
  'arm64-v8a',
  'armeabi-v7a',
  'signArtifact',
  'verifyArtifact',
  'installProof',
  'resolveArtifact',
  'RiftBuild artifact must live under D:/Builds',
  'RiftBuild sign accepts only *-unsigned.apk artifacts',
  'RiftBuild verify accepts only *-signed.apk artifacts',
  'RiftBuild install-proof accepts only *-signed.apk artifacts',
  'installableClaimed',
  'RiftBuild does not accept raw commands',
  'RiftRappManager',
  'pack-rapp',
  'install-rapp',
  'launch-rapp',
  'rapp-list',
  'private val rappManager by lazy',
]) assert.ok(nativeBuild.includes(required), 'native RiftBuild contract missing: ' + required);

for (const required of [
  'riftos.rapp-project/1',
  'riftos.rapp/1',
  'RAPP install artifact must live under D:/Builds',
  'Refusing to replace non-RAPP program',
  '/C:/Programs',
  'programSha256',
  'runtimeSha256',
]) assert.ok(rappManager.includes(required), 'RAPP manager contract missing: ' + required);
assert.doesNotMatch(rappManager, /RiftApkV2Signer|PackageInstaller/, 'RAPP manager must remain independent of APK signing/install');
assert.match(appAbi, /riftos-app-abi\/1/);
assert.match(appAbi, /interface RiftAppRuntimeAdapter/);
assert.match(appAbi, /object RiftAppAdapters/);
assert.match(rappAdapter, /RiftppUiCodec\.parse/);
assert.match(rappAdapter, /RPE2/);
assert.match(rappAdapter, /riftpp-rpa2-v1/);
assert.match(appAbi, /RiftRappRiftppWs15Adapter/);
assert.match(appAbi, /nextProgram/);
assert.match(rappWs15Adapter, /riftpp-rws2-rui3-v1/);
assert.match(rappWs15Adapter, /RPE3/);
assert.match(rappWs15Adapter, /RUI3/);
assert.match(rappWs15Adapter, /RWS2/);
assert.match(rappAbsoluteView, /Layout\.ABSOLUTE/);
assert.match(rappAbsoluteView, /POINTER_MOVE/);
assert.match(rappHost, /session\.program/);
assert.match(rappHost, /nextProgram\(output\)/);
assert.match(rappHost, /RiftBoundedAsync\.submit/);
assert.match(rappHost, /RiftNativeBufferCompilerService\.compile/);
assert.match(rappHost, /RiftAppAbi\.Event/);
assert.match(rappHost, /RiftAppAdapters\.require/);
assert.doesNotMatch(rappHost, /Riftpp|RPE2|RUI2/, 'generic RAPP host must not own language/runtime protocol semantics');
assert.match(rappHost, /showLaunchFailure/);
assert.match(rappHost, /runCatching \{[\s\S]*?open\(id\)/);
assert.match(mainActivity, /rappHost = RiftRappHost\(this, nativeDesktop, ::populateNativeLauncher\)/);
assert.match(mainActivity, /rappHost\.openFromLauncher\(id\)/);

for (const required of [
  'class RiftApkV2Signer',
  'AndroidKeyStore',
  'KEY_SIZE = 2048',
  'SIGNATURE_ALGORITHM_ID = 0x0103',
  'V2_BLOCK_ID = 0x7109871a',
  'APK Sig Block 42',
  'SHA256withRSA',
  'SIGNATURE_PADDING_RSA_PKCS1',
  '0xa5.toByte()',
  'top.write(0x5a)',
  'ZIP64 APK is unsupported',
  'APK already contains an APK Signing Block',
  'v2 protected APK content digest mismatch',
  'v2 signed-data RSA signature verification failed',
]) assert.ok(signer.includes(required), 'APK v2 signer contract missing: ' + required);

for (const required of [
  'class RiftBuildInstaller',
  'SAFE_PACKAGE_NAME',
  'requireSafePackageName',
  'Intent(Intent.ACTION_MAIN)',
  'Intent.CATEGORY_LAUNCHER',
  '.setPackage(safePackageName)',
  'launch package is not bound to the latest verified install',
  'PackageInstaller',
  'USER_ACTION_REQUIRED',
  'PendingIntent.FLAG_MUTABLE',
  'ACTION_PACKAGE_FIRST_LAUNCH',
  'launch-proven',
  'canRequestPackageInstalls',
  'ACTION_MANAGE_UNKNOWN_APP_SOURCES',
  'PackageInstaller reported an unexpected package identity',
  'class RiftBuildInstallActivity : Activity()',
  'PendingIntent.getBroadcast(',
  'retainPendingConfirmation(confirmIntent)',
  'resumePendingConfirmation',
  'confirmationLaunchState',
  'class RiftBuildInstallReceiver : BroadcastReceiver()',
]) assert.ok(installer.includes(required), 'RiftBuild installer contract missing: ' + required);
for (const forbidden of [
  'ALLOWED_PROOF_PACKAGES',
  'RIFTPP_EDITOR_TARGET_PACKAGE',
  'RIFTPP_NATIVE_EDITOR_V1_TARGET_PACKAGE',
  'RIFTPP_ADAPTER_R1_TARGET_PACKAGE',
  'TARGET_PACKAGE = "com.riftpp.nativeproof"',
  'EDITOR_TARGET_PACKAGE = "com.codynex.editor"',
  'getLaunchIntentForPackage',
]) assert.ok(!installer.includes(forbidden), 'generic RiftBuild installer regained project identity: ' + forbidden);
assert.ok(!installer.includes('PendingIntent.getActivity('), 'PackageInstaller status callback regressed to Activity-only delivery');
assert.ok(mainActivity.includes('RiftBuildInstaller.resumePendingConfirmation(this)'), 'MainActivity must resume retained PackageInstaller confirmation from a foreground Activity');

for (const required of [
  'class RiftBuildNativeToolchain',
  'riftbuild-android-clang-toolchain/1',
  'riftbuild-native-project/1',
  'riftbuild-native-project/2',
  'rift-native.json',
  'objects',
  'archives',
  'verifyRelocatableObject',
  'compileAssemblyObject',
  'riftbuild-native-object-compile-v1',
  'Assembly object source must end in .S or .s',
  'build/riftbuild/objects/',
  'argv += "-c"',
  'verifyRelocatableObject(output, abi.abi)',
  'extractRelocationFreeText',
  'riftbuild-native-object-text-v1',
  'extracted-native-object-text',
  'Native object contains relocation sections; raw text extraction is forbidden',
  'Native object must contain exactly one .text section',
  'build/riftbuild/blobs/',
  'verifyStaticArchive',
  '-Wl,--whole-archive',
  '-Wl,--no-whole-archive',
  'ProcessBuilder(argv)',
  'structured-argv',
  '--target=',
  '--sysroot=',
  'MAX_TOOLCHAIN_ARGS = 128',
  'BUNDLED_TOOLCHAIN_ASSET = "riftbuild/android-clang-v1.zip"',
  'riftbuild-native-toolchain-install-v1',
  'ZipInputStream',
  'bundledToolchainAvailable',
  'LD_LIBRARY_PATH',
  '.replace("%COMPILER_DIR%", compiler.parentFile?.absolutePath.orEmpty())',
  'toolchainArgCount',
  '.replace("%TOOLCHAIN%", toolchainRoot.absolutePath)',
  '.replace("%SYSROOT%", sysroot.absolutePath)',
  'libraries',
  'MAX_LIBRARIES = 64',
  'Native link-library name is invalid',
  'build/riftbuild/prepared',
  'armeabi-v7a',
  'arm64-v8a',
  'verifyElf',
  'downloadedToolchainsAllowed',
]) assert.ok(nativeToolchain.includes(required), 'native toolchain contract missing: ' + required);
assert.ok(!nativeToolchain.includes('/system/bin/sh'), 'native toolchain must not route compilation through a shell');
assert.ok(!nativeToolchain.includes('Runtime.getRuntime().exec'), 'native toolchain must use structured ProcessBuilder argv only');
assert.ok(!nativeToolchain.includes('linkerArgs'), 'native project must not gain arbitrary linker-argument authority');
assert.ok(nativeToolchain.includes('SUPPORTED_INPUT_ABIS'), 'native v2 link inputs must remain ABI-scoped');
assert.match(nativeToolchain, /fun validateProject\(projectRoot: File\)/);
assert.match(nativeApp, /fun validateProject\(projectRoot: File\)/);
assert.match(nativeApp, /riftbuild-native-app-validation-v2/);
assert.match(nativeApp, /fun validateProject\(projectRoot: File\)/);
assert.match(nativeApp, /riftbuild-native-app-validation-v2/);
assert.ok(!nativeToolchain.includes('bundledMaterialized'), 'retired bundled-archive materialization must stay removed');
assert.ok(!nativeToolchain.includes('bundled-archive'), 'retired bundled-archive receipt entries must stay removed');

for (const required of [
  'class RiftBuildNativeApp',
  'riftbuild-native-app/1',
  'riftbuild-native-app/2',
  'riftbuild-native-app/3',
  'riftbuild-runtime-profile/1',
  'riftbuild-native-app-prepare-v3',
  'readRuntimeProfile',
  'materializeRuntimeProfile',
  'MAX_RUNTIME_DEX_FILES = 8',
  'MAX_RUNTIME_DEX_BYTES = 16L * 1024L * 1024L',
  'runtimeProfile',
  'activityClass',
  'hasCode',
  'dexDir',
  'clearPreparedDex',
  'rift-app.json',
  'android.app.NativeActivity',
  'android.app.lib_name',
  'build/riftbuild/prepared/AndroidManifest.xml',
  'assetFiles',
  'MAX_ASSET_FILES = 5_000',
  'MAX_ASSET_BYTES = 128L * 1024L * 1024L',
  'riftbuild-native-project/2',
  'rift-app.json library must match rift-native.json library',
  'Native app assetsDir must not point inside build/riftbuild',
]) assert.ok(nativeApp.includes(required), 'native app preparer contract missing: ' + required);
for (const forbidden of [
  'RIFTPP_ADAPTER_PROFILE',
  'RIFTPP_ADAPTER_CLASS',
  'RIFTPP_ADAPTER_RUNTIME',
  'riftpp-adapter',
  'riftpp-android-adapter/1',
  'com.riftpp.android.RiftppActivity',
  'materializeManagedRuntime',
  'riftbuild/managed-runtimes/riftpp-adapter-v1',
]) assert.ok(!nativeApp.includes(forbidden), 'generic native app materializer regained project identity: ' + forbidden);
assert.ok(!nativeApp.includes('ProcessBuilder'), 'native app preparer must not gain process authority');
assert.ok(!nativeApp.includes('Runtime.getRuntime().exec'), 'native app preparer must not gain raw exec authority');
assert.match(nativeApp, /DEX_ENTRY\.matches/);

for (const required of [
  'class RiftBuildManagedToolchains',
  'riftbuild-hot.json',
  'riftbuild-managed-payloads/1',
  'Managed payload SHA-256 mismatch',
  'MAX_PAYLOADS = 64',
  'COMPILER_PROTOCOL = "riftbuild-compiler-json/1"',
  'ENGINE_NATIVE_BUFFER = "native-buffer-v1"',
  'ENGINE_DEX_JSON = "dex-json-v1"',
  'resolveCompiler',
]) assert.ok(managedToolchains.includes(required), 'managed payload/compiler contract missing: ' + required);
for (const required of [
  '"managed-copy" -> managedCopy(',
  '"compiler-status" -> compilerStatus(',
  '"compiler-run" -> compilerRun(',
]) assert.ok(nativeBuild.includes(required), 'generic managed compiler command missing: ' + required);

for (const required of [
  'class RiftBuildKotlinCompiler',
  'riftbuild-kotlin-project/1',
  'compilerAuthority',
  'managed-external',
  'compilerInvoker',
  'D8Command',
  'OutputMode.DexIndexed',
  'build/riftbuild/hot-dex',
  'kotlin-stdlib.jar',
  'android.jar',
]) assert.ok(kotlinCompiler.includes(required), 'Kotlin hot compiler contract missing: ' + required);
assert.ok(!kotlinCompiler.includes('K2JVMCompiler'), 'RiftOS Kotlin wrapper must not embed compiler implementation authority');

for (const required of [
  'class RiftManagedJvmToolService',
  'DexClassLoader',
  'rift-managed-jvm',
  'public static String run(String requestJson)',
  'applicationContext.classLoader.parent',
  'Process.killProcess(remotePid)',
]) assert.ok(managedJvmToolService.includes(required), 'managed JVM tool host contract missing: ' + required);

for (const required of [
  'object KotlinCompilerTool',
  '@JvmStatic',
  'riftbuild-compiler-json/1',
  'riftbuild-compiler-response/1',
  'K2JVMCompiler',
]) assert.ok(managedKotlinTool.includes(required), 'managed Kotlin compiler payload contract missing: ' + required);
assert.ok(managedKotlinToolGradle.includes('com.github.PranavPurwar:kotlinc-android:2.4.0'), 'managed Kotlin payload must pin Android compiler port');
assert.ok(managedKotlinToolGradle.includes('org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0'), 'managed Kotlin payload must package its coroutines runtime');

for (const required of [
  'class RiftNativeBufferCompilerService',
  'rift_native_buffer_compiler_host',
  'MAX_COMPILER_BYTES = 256 * 1024',
  'MAX_SOURCE_BYTES = 512 * 1024',
  'MAX_OUTPUT_BYTES = 512 * 1024',
  'Process.killProcess(remotePid)',
]) assert.ok(nativeBufferCompilerService.includes(required), 'generic native-buffer compiler service contract missing: ' + required);

for (const required of [
  'Java_com_riftos_app_RiftNativeBufferCompilerService_nativeCompileDynamic',
  'PROT_READ | PROT_EXEC',
  'kMaxCompilerBytes = 256 * 1024',
  'kCanary = 0xA5',
]) assert.ok(nativeBufferCompilerHost.includes(required), 'generic native-buffer compiler host contract missing: ' + required);

assert.ok(!exists('android/app/src/main/java/com/riftos/app/RiftppCompilerService.kt'), 'retired legacy Rift++ compiler service must stay absent');
assert.ok(!exists('android/app/src/main/cpp/riftpp/riftpp_compiler_host.cpp'), 'retired legacy Rift++ compiler host must stay absent');
assert.ok(!exists('android/app/src/main/java/com/riftos/app/RiftppDynamicCompilerService.kt'), 'Rift++-named native-buffer service must stay retired');
assert.ok(!exists('android/app/src/main/cpp/riftpp/riftpp_dynamic_compiler_host.cpp'), 'Rift++-named native-buffer host must stay retired');
assert.ok(!exists('android/riftpp-adapter-runtime-bundle'), 'Rift++ adapter bundle must be project-owned, not RiftOS-owned');
assert.ok(!manifest.includes('.RiftppCompilerService'), 'retired legacy Rift++ compiler service must stay out of the manifest');
assert.ok(!manifest.includes('.RiftppDynamicCompilerService'), 'Rift++-named compiler service must stay out of the manifest');
assert.ok(!manifest.includes(':riftppCompiler') && !manifest.includes(':riftppCompilerHot'), 'Rift++-named compiler processes must stay absent');
assert.ok(!cmake.includes('riftpp_compiler_host'), 'retired legacy Rift++ compiler target must stay absent');
assert.ok(!cmake.includes('riftpp_dynamic_compiler_host'), 'Rift++-named native-buffer target must stay absent');
assert.ok(!shell.includes('riftpp-host'), 'retired legacy riftpp-host shell surface must stay absent');
for (const retired of ['prepare-riftpp-v0', 'prepare-riftpp-seed0-arm64', 'prepare-riftpp-app0', 'prepare-riftpp-editor']) {
  assert.ok(!nativeBuild.includes(retired), 'retired Rift++ special-case RiftBuild route resurfaced: ' + retired);
}
assert.ok(nativeBuild.includes('args.optString("kind", "native-app")'), 'build.prepare must default to generic native-app');
assert.ok(nativeBuild.includes('"native-app" -> prepareNativeApp(project, cwd)'), 'build.prepare native-app must use generic prepareNativeApp');
assert.ok(!nativeBuild.includes('"riftpp-v0"') && !nativeBuild.includes('"riftpp-app0"'), 'retired Rift++ programmatic prepare kinds must stay absent');
assert.ok(cmake.includes('rift_native_buffer_compiler_host'), 'generic native-buffer compiler host target must stay present');
assert.ok(manifest.includes('.RiftNativeBufferCompilerService'), 'generic native-buffer compiler service must stay crash-contained');
assert.ok(manifest.includes(':riftNativeBufferCompiler'), 'generic native-buffer compiler service must stay process-isolated');
assert.ok(manifest.includes('.RiftManagedJvmToolService'), 'generic managed JVM tool service must stay declared');
assert.ok(manifest.includes(':riftJvmToolHot'), 'generic managed JVM tool service must stay process-isolated');
assert.ok(!androidSettings.includes('riftpp-adapter-runtime-bundle'), 'RiftOS settings must not include a project-specific runtime bundle');
assert.ok(androidSettings.includes('include(":rift-managed-kotlin-tool")'), 'managed Kotlin compiler payload module must stay included');
assert.ok(androidSettings.includes('https://jitpack.io'), 'managed compiler payload repository must stay available');
assert.ok(!gradle.includes('syncRiftBuildRiftppAdapterRuntime'), 'RiftOS Gradle must not own project-specific runtime sync');
assert.ok(!gradle.includes('managed-runtimes/riftpp-adapter-v1'), 'RiftOS Gradle must not own project-specific runtime assets');
assert.ok(gradle.includes('syncRiftBuildKotlinToolchain'), 'RiftBuild Kotlin toolchain asset sync must be wired');
assert.ok(gradle.includes('dependsOn(syncRiftBuildKotlinToolchain)'), 'RiftBuild Kotlin toolchain must be active preBuild infrastructure');
assert.ok(gradle.includes('syncRiftBuildCompilerSeeds'), 'managed compiler seed sync must stay wired');
assert.ok(gradle.includes('dependsOn(syncRiftBuildCompilerSeeds)'), 'managed compiler seeds must be active preBuild infrastructure');
assert.ok(gradle.includes('kotlin-android-2.4.0.apk'), 'managed Kotlin compiler seed asset name must stay pinned');
assert.ok(!gradle.includes('kotlin-compiler-embeddable:2.4.10'), 'RiftOS app must not embed the desktop Kotlin compiler implementation');
assert.ok(gradle.includes('com.android.tools:r8:8.13.23'), 'RiftBuild D8/R8 engine version must stay pinned');
const combinedAuthority = nativeBuild + '\n' + signer + '\n' + installer;
for (const forbidden of [
  'ProcessBuilder',
  'Runtime.getRuntime().exec',
  'rift-cli enable',
  'localBuildExecutor',
  'pm install',
]) assert.ok(!combinedAuthority.includes(forbidden), 'RiftBuild gained forbidden authority: ' + forbidden);

assert.match(nativeBuild, /display == "\/D:\/Workspace"/);
assert.match(nativeBuild, /confinedTo\(workspaceRoot, file\)/);
assert.match(nativeBuild, /confinedTo\(artifactRoot, outDir\)/);
assert.match(nativeBuild, /confinedTo\(artifactRoot, file\)/);
assert.match(nativeBuild, /type == XML_TYPE && headerSize == 8 && declaredSize == file\.length\(\)\.toInt\(\)/);
assert.match(nativeApp, /u32\(out, 1\)/);
assert.match(nativeApp, /u32\(out, XML_NO_INDEX\)/);
for (const retiredRoute of [
  'prepare-codynex-mc0',
  'prepare-codynex-mc1a',
  'prepare-codynex-mc1b',
  'prepare-codynex-m2-vm0',
  'prepare-codynex-m2b',
  'prepare-codynex-mc2a',
  'prepare-codynex-editor',
  'prepare-codynex-app',
]) {
  assert.ok(!nativeBuild.includes(retiredRoute), 'retired Codynex special-case RiftBuild route resurfaced: ' + retiredRoute);
}
for (const retiredHost of [
  'android/app/src/main/cpp/mc0/codynex_mc0_host.cpp',
  'android/app/src/main/cpp/mc1/codynex_mc1a_host.cpp',
  'android/app/src/main/cpp/mc1/codynex_mc1b_host.cpp',
  'android/app/src/main/cpp/m2/codynex_m2_vm0_host.cpp',
  'android/app/src/main/cpp/m2/codynex_m2b_host.cpp',
  'android/app/src/main/cpp/m2/codynex_mc2a_host.cpp',
]) {
  assert.equal(exists(retiredHost), false, 'retired Codynex proof host resurfaced: ' + retiredHost);
}
for (const retiredTarget of [
  'codynex_mc0_host',
  'codynex_mc1a_host',
  'codynex_mc1b_host',
  'codynex_m2_vm0_host',
  'codynex_m2b_host',
  'codynex_mc2a_host',
]) {
  assert.ok(!cmake.includes(retiredTarget), 'retired Codynex CMake target resurfaced: ' + retiredTarget);
}
assert.match(riftAppDiagnosticBridge, /RIFTPP_EDITOR_PACKAGE = "com\.riftpp\.editor"/);
assert.match(riftppEditorApkBuilder, /libriftpp_editor_bridge\.so/);
assert.match(riftppEditorBridgeService, /class RiftppEditorBridgeService : Service\(\)/);
assert.match(riftppEditorApkBuilder, /private fun buildBinaryManifest\(/);
assert.match(shell, /"riftpp-editor" -> executeRiftppEditorCommand/);
assert.match(shell, /riftpp-editor build-debug/);
assert.match(shell, /riftpp-editor native-compile/);
assert.match(shell, /riftpp-editor native-run/);
assert.match(shell, /riftpp-editor native-preflight/);
assert.match(shell, /native-preflight <elf-path> \[required-symbol\]/);
assert.match(shell, /args\.size in 1\.\.2/);
assert.match(shell, /requiredSymbol/);
assert.match(shell, /riftpp-editor native-build-debug/);
assert.match(riftppEditorBridgeClient, /RiftppEditorBridgeService/);
assert.match(riftppEditorBridgeService, /RIFTOS_PACKAGE[\s\S]*?"com\.riftos\.app"/);
assert.match(riftppEditorBridgeService, /filesDir[\s\S]*?"projects\/default"/);
assert.match(riftppEditorBridgeService, /"build-debug" ->/);
assert.match(riftppEditorBridgeService, /"native-compile" ->/);
assert.match(riftppEditorBridgeService, /"native-run" ->/);
assert.match(riftppEditorBridgeService, /"native-preflight" ->/);
assert.match(riftppEditorBridgeService, /"native-build-debug" ->/);
assert.match(riftppEditorBridgeService, /compilerEncoding/);
assert.match(riftppEditorBridgeService, /continuous-hex/);
assert.match(riftppEditorBridgeService, /HexAssets\.decodeContinuousHex/);
assert.match(riftppEditorBridgeService, /debugPackage\(/);
assert.match(riftppEditorMain, /"Debug APK"/);
assert.match(riftppEditorApkBuilder, /debug: Boolean = false/);
assert.match(riftppEditorApkBuilder, /packageName \+ "\.debug"/);
assert.match(riftppEditorApkBuilder, /fun buildNativeDebug\(/);
assert.match(riftppEditorApkBuilder, /android\.app\.NativeActivity/);
assert.match(riftppEditorApkBuilder, /android\.app\.lib_name/);
assert.match(riftppEditorNativeElfPreflight, /EM_ARM = 40/);
assert.match(riftppEditorNativeElfPreflight, /PT_LOAD/);
assert.match(riftppEditorNativeElfPreflight, /writable and executable/);
assert.match(riftppEditorBridgeService, /elfType == 1/);
assert.match(riftppEditorBridgeService, /RiftppRelocatableElfPreflight/);
assert.match(riftppEditorBridgeService, /requiredSymbol/);
assert.match(riftppEditorBridgeService, /inspect\([\s\S]*?elf,[\s\S]*?requiredSymbol/);
assert.match(riftppEditorBridgeService, /"ET_REL"/);
assert.match(riftppEditorRelocatableElfPreflight, /ET_REL = 1/);
assert.match(riftppEditorRelocatableElfPreflight, /EM_ARM = 40/);
assert.match(riftppEditorRelocatableElfPreflight, /android_main/);
assert.match(riftppEditorRelocatableElfPreflight, /must not contain program headers/);
assert.ok(
    !/Rift\+\+|Riftpp|riftpp/.test(nativeBuild),
    'generic RiftBuildLocalExecutor regained Rift++ project identity'
);
assert.match(cmake, /riftpp_editor_bridge[\s\S]*?-fno-exceptions/);
assert.match(cmake, /riftpp_editor_bridge[\s\S]*?-fno-rtti/);
assert.match(gradle, /verifyRiftppEditorPayload/);
assert.ok(
    gradle.includes('src/main/cpp/editor/riftpp_editor_bridge.cpp'),
    'RiftOS exact native source snapshot omitted Rift++ editor bridge'
);
assert.ok(
    gradle.includes('src/main/java/com/riftpp/editor/MainActivity.kt'),
    'Rift++ editor payload hash gate omitted MainActivity'
);
assert.ok(
    gradle.includes('src/main/java/com/riftpp/editor/RiftppEditorBridgeService.kt'),
    'Rift++ editor payload hash gate omitted editor bridge service'
);
assert.ok(
    gradle.includes('src/main/java/com/riftpp/editor/RiftppRelocatableElfPreflight.kt'),
    'Rift++ editor payload hash gate omitted relocatable ELF preflight'
);
assert.ok(
    gradle.includes('src/main/java/com/riftpp/editor/RiftppWorkspace.kt'),
    'Rift++ editor payload hash gate omitted workspace layer'
);
assert.ok(
    gradle.includes('src/main/java/com/riftpp/editor/RiftppProject.kt'),
    'Rift++ editor payload hash gate omitted project model'
);
assert.ok(
    gradle.includes('src/main/java/com/riftpp/editor/RiftppUi.kt'),
    'Rift++ editor payload hash gate omitted RUI2 codec'
);
assert.ok(
    gradle.includes('src/main/java/com/riftpp/editor/RiftppApkBuilder.kt'),
    'Rift++ editor payload hash gate omitted APK builder'
);
assert.ok(
    gradle.includes('src/main/java/com/riftpp/editor/RiftppApkV2Signer.kt'),
    'Rift++ editor payload hash gate omitted APK v2 signer'
);
assert.ok(
    gradle.includes('src/main/java/com/riftpp/apphost/RiftppAppActivity.kt'),
    'Rift++ editor payload hash gate omitted standalone app host'
);
const editorCoreModel = read('android/app/src/main/java/com/codynex/editor/EditorModel.kt');
const editorCorePorts = read('android/app/src/main/java/com/codynex/editor/EditorPorts.kt');
const editorCoreController = read('android/app/src/main/java/com/codynex/editor/CodynexEditorController.kt');
const editorActivity = read('android/app/src/main/java/com/codynex/editorapp/MainActivity.kt');
const editorBridgeService = read('android/app/src/main/java/com/codynex/editorapp/CodynexEditorBridgeService.kt');
const editorApkBuilder = read('android/app/src/main/java/com/codynex/editorapp/CodynexApkBuilder.kt');
const editorApkSigner = read('android/app/src/main/java/com/codynex/editorapp/CodynexApkV2Signer.kt');
const codynexAppActivity = read('android/app/src/main/java/com/codynex/apphost/CodynexAppActivity.kt');
const editorWorkspace = read('android/app/src/main/java/com/codynex/editorapp/FileWorkspacePort.kt');
const editorBootstrap = read('android/app/src/main/java/com/codynex/editorapp/BootstrapArtifacts.kt');
const editorToolchain = read('android/app/src/main/java/com/codynex/editorapp/CodynexEditorToolchainPort.kt');
const editorCompilerRuntime = read('android/app/src/main/java/com/codynex/editorapp/CodynexCompilerRuntime.kt');
const editorVmBridgeKt = read('android/app/src/main/java/com/codynex/editorapp/CodynexRuntimeBridge.kt');
const editorVmBridgeCpp = read('android/app/src/main/cpp/editor/editor_vm_bridge.cpp');
assert.match(editorVmBridgeCpp, /uint8_t\* scratch;/);
assert.match(editorVmBridgeCpp, /uint32_t scratchCapacity;/);
assert.match(editorVmBridgeCpp, /sizeof\(VmContext\) == 28/);
assert.match(editorVmBridgeCpp, /CodynexRuntimeBridge_run/);

for (const retired of [
  'prepare-codynex-editor',
  'prepare-codynex-app',
  'fun prepareCodynexEditor',
  'fun prepareCodynexApp',
  'compileCodynexC0ProjectVM2',
  'assets/selfhost_compiler.hex',
  'assets/selfhost_compiler.cx0',
]) {
  assert.ok(!nativeBuild.includes(retired), 'retired RiftBuild Codynex authority resurfaced: ' + retired);
  assert.ok(!shell.includes(retired), 'retired RiftShell Codynex build route resurfaced: ' + retired);
}
assert.match(cmake, /codynex_editor_vm[\s\S]*?editor\/editor_vm_bridge\.cpp/);
assert.match(cmake, /codynex_editor_vm[\s\S]*?-fno-exceptions/);
assert.match(cmake, /codynex_editor_vm[\s\S]*?-fno-rtti/);
assert.ok(gradle.includes('src/main/cpp/editor/editor_vm_bridge.cpp'), 'Gradle exact native source snapshot omitted editor VM bridge');
assert.match(gradle, /verifyCodynexEditorPayload/);
assert.ok(gradle.includes('src/main/java/com/codynex/editorapp/CodynexCompilerRuntime.kt'), 'Codynex editor payload gate omitted generic compiler host');
assert.ok(gradle.includes('src/main/java/com/codynex/editorapp/CodynexEditorToolchainPort.kt'), 'Codynex editor payload gate omitted editor toolchain adapter');
assert.ok(!gradle.includes('validateCodynexCompilerTransition'), 'retired RiftOS Codynex compiler transition gate resurfaced');
assert.ok(!gradle.includes('CodynexCompilerProvider.kt'), 'retired RiftOS Codynex compiler provider resurfaced in Gradle sources');
assert.ok(!installer.includes('com.riftpp.'), 'generic RiftBuild installer must not own Rift++ package identity');
assert.ok(!installer.includes('com.codynex.editor'), 'generic RiftBuild installer must not own Codynex editor package identity');
assert.match(manifest, /com\.codynex\.editor/);
assert.match(codynexAppActivity, /Thin Android bootstrap for Codynex applications/);
assert.match(codynexAppActivity, /platform glue, not part of the Codynex runtime/);
assert.match(codynexAppActivity, /CodynexRuntimeBridge\.run/);
assert.match(codynexAppActivity, /parseFrame/);
assert.match(codynexAppActivity, /encodeAction/);
assert.ok(
  !/Codynex Notepad|write_clear|LETS GOOOOO/i.test(codynexAppActivity),
  'Generic Codynex app host gained Notepad-specific semantics'
);

for (const core of [editorCoreModel, editorCorePorts, editorCoreController]) {
  assert.ok(!/android\./.test(core), 'Reusable editor core gained Android coupling');
  assert.ok(!/Source0/i.test(core), 'Reusable editor core gained Source0 coupling');
  assert.ok(!/VM1|Vm1/.test(core), 'Reusable editor core gained VM1 coupling');
  assert.ok(!/LR0|CXE1/i.test(core), 'Reusable editor core gained LR0/CXE1 coupling');
}
assert.match(editorActivity, /CodynexEditorController/);
assert.match(editorActivity, /controller\.compile\(\)/);
assert.match(editorActivity, /controller\.preview\(\)/);
assert.match(editorActivity, /actionButton\("Pack APK"\)/);
assert.match(editorActivity, /CodynexApkBuilder/);
assert.match(editorActivity, /compatibilityToolchain/);
assert.match(editorActivity, /target = EditorVmTarget\.VM1/);
assert.match(editorActivity, /candidate\.kind == "vm2-program"/);
assert.match(editorApkBuilder, /class CodynexApkBuilder/);
assert.match(editorApkBuilder, /VM_ASSET = "vm2_seed\.hex"/);
assert.match(editorApkBuilder, /PROGRAM_ASSET = "program\.vm2"/);
assert.match(editorApkBuilder, /3f746727e18a55933a20dc63fc7f84544566f982aa5cab0803573c456926c916/);
assert.match(editorApkBuilder, /context\.applicationInfo\.sourceDir/);
assert.match(codynexAppActivity, /VM_ASSET = "vm2_seed\.hex"/);
assert.match(codynexAppActivity, /PROGRAM_ASSET = "program\.vm2"/);
assert.match(editorApkBuilder, /CodynexApkV2Signer/);
assert.match(editorApkBuilder, /packAuthority", "Codynex"/);
assert.match(editorApkBuilder, /signAuthority", "Codynex"/);
assert.match(editorApkBuilder, /runtimeDependency", "none"/);
assert.match(editorApkBuilder, /androidBootstrap", "thin-platform-shim"/);
assert.match(editorApkBuilder, /bootstrapPartOfRuntime", false/);
assert.match(editorApkBuilder, /android-lifecycle,ui-rendering,touch-ime,platform-handoff/);
assert.match(editorApkBuilder, /language,compiler,optimizer,vm-semantics,app-semantics/);
assert.match(editorApkBuilder, /MediaStore\.Downloads\.EXTERNAL_CONTENT_URI/);
assert.match(editorApkBuilder, /Environment\.DIRECTORY_DOWNLOADS \+ "\/Codynex"/);
assert.match(editorApkBuilder, /publishedUri/);
assert.ok(
  !/com\.riftos\.app|RiftBuildLocalExecutor|prepare-codynex-app/.test(editorApkBuilder),
  'Codynex-owned APK builder gained an executable RiftOS/RiftBuild packaging dependency'
);
assert.ok(
  !/Codynex Notepad|write_clear|LETS GOOOOO/i.test(editorApkBuilder),
  'Codynex-owned APK builder gained Notepad-specific semantics'
);
assert.match(editorApkSigner, /class CodynexApkV2Signer/);
assert.match(editorApkSigner, /KEY_ALIAS = "codynex-apk-v2-rsa-v1"/);
assert.match(editorApkSigner, /CN=Codynex Local APK V2/);
assert.ok(
  !/RiftBuild|com\.riftos\.app/.test(editorApkSigner),
  'Codynex-owned APK signer gained a RiftOS identity/dependency'
);
assert.match(editorWorkspace, /StandardCopyOption\.ATOMIC_MOVE/);
assert.match(editorWorkspace, /path escapes editor workspace/);
assert.match(editorBootstrap, /VM1_SHA256/);
assert.match(editorBootstrap, /VM2_SHA256/);
assert.match(editorBootstrap, /vm2_seed\.hex/);
assert.match(editorBootstrap, /starterSource/);
assert.match(editorBootstrap, /notepadSource/);
assert.match(editorBootstrap, /module app\.notepad/);
assert.match(editorBootstrap, /sink_write/);
assert.match(editorBootstrap, /fn smaller\(requested: u32, available: u32\) -> u32/);
assert.match(editorBootstrap, /fn clamp_text_length\(value: u32\) -> u32/);
assert.match(editorBootstrap, /fn current_text_length\(\) -> u32/);
assert.match(editorBootstrap, /fn current_text_start\(\) -> u32/);
assert.match(editorBootstrap, /\/\/ CXUI v1 header \+ three generic controls\./);
assert.ok(!editorBootstrap.includes('# CXUI v1 header + three generic controls.'), 'Bundled Notepad uses unsupported # comment syntax');
assert.ok(!/compilerA|selfhost_compiler|SOURCE_SHA256/.test(editorBootstrap), 'Editor bootstrap still depends on obsolete MC2-A compiler/source assets');
assert.match(editorActivity, /main\.cx/);
assert.match(editorActivity, /notepad\.cx/);
assert.match(editorActivity, /CXUI app structure and behavior are emitted by compiled \.cx code/);
assert.match(editorActivity, /Live \.cx preview/);
assert.match(editorActivity, /parseCxUiFrame/);
assert.match(editorActivity, /replayLivePreview/);
assert.match(editorActivity, /TEMP LIVE-PROOF SCAFFOLD/);
assert.match(editorBridgeService, /compatibilityToolchain/);
assert.match(editorBridgeService, /target = EditorVmTarget\.VM1/);
assert.match(editorActivity, /New Folder/);
assert.match(editorActivity, /Set Entry/);
assert.match(editorActivity, /Save All/);
assert.match(editorCoreController, /fun search\(/);
assert.match(editorCoreController, /fun setProjectEntry\(/);
assert.match(editorCoreController, /buildProject\(/);
assert.match(editorWorkspace, /fun listRecursive\(/);
assert.match(editorToolchain, /class CodynexEditorToolchainPort/);
assert.match(editorToolchain, /CodynexCompilerRuntime\(context\.applicationContext\)\.compile/);
assert.match(editorToolchain, /MAX_PROJECT_BYTES = 1024 \* 1024/);
assert.match(editorToolchain, /MAX_PROJECT_MODULES = 64/);
assert.match(editorToolchain, /MAX_CANDIDATE_BYTES = 64 \* 1024/);
assert.match(editorToolchain, /EditorVmTarget\.VM2/);
assert.match(editorToolchain, /Preview passed: \$targetLabel result/);
assert.match(editorToolchain, /artifacts\.vm2/);
assert.match(editorToolchain, /artifacts\.vm1/);
assert.match(editorToolchain, /data class LivePreviewRun/);
assert.match(editorToolchain, /MAX_LIVE_INPUT_BYTES = 1024/);
assert.match(editorToolchain, /replayLivePreview/);
assert.match(editorToolchain, /executePreview/);
assert.doesNotMatch(editorToolchain, /contentResolver\.call|com\.riftos\.app\.codynexcompiler|COMPILE_METHOD_VM/);

assert.match(editorCompilerRuntime, /class CodynexCompilerRuntime/);
assert.match(editorCompilerRuntime, /HOT_COMPILER_WORKSPACE_PATH/);
assert.match(editorCompilerRuntime, /\.codynex\/toolchains\/compiler\.js/);
assert.match(editorCompilerRuntime, /BUNDLED_COMPILER_ASSET/);
assert.match(editorCompilerRuntime, /codynex_compiler\.js/);
assert.match(editorCompilerRuntime, /quickJs \{/);
assert.match(editorCompilerRuntime, /globalThis\.CodynexC0/);
assert.match(editorCompilerRuntime, /compiler\.compileProjectVM2/);
assert.match(editorCompilerRuntime, /compiler\.compileProject/);
assert.doesNotMatch(editorCompilerRuntime, /codynex-c0-ref\/0\.11\.0|codynex-c0-ref\/0\.12\.0/);

assert.doesNotMatch(nativeBuild, /CodynexCompilerProvider|com\.riftos\.app\.codynexcompiler|compileCodynexC0Project/);
assert.doesNotMatch(manifest, /CodynexCompilerProvider|com\.riftos\.app\.codynexcompiler/);
assert.ok(!gradle.includes('src/main/java/com/riftos/app/CodynexCompilerProvider.kt'), 'retired Codynex compiler provider resurfaced in Gradle exact source snapshot');
assert.match(editorVmBridgeKt, /System\.loadLibrary\("codynex_editor_vm"\)/);
assert.ok(!/Source0|selfhost_compiler|hex character/i.test(editorVmBridgeCpp), 'Generic editor VM bridge gained Source0/compiler parsing semantics');
assert.match(editorVmBridgeCpp, /VmContext/);
assert.match(editorVmBridgeCpp, /stepBudget/);
assert.match(nativeBuild, /\.put\("signed", false\)/);
assert.match(nativeBuild, /\.put\("installableClaimed", false\)/);

assert.match(shell, /private val riftBuild = RiftBuildLocalExecutor\(appContext\)/);
assert.match(shell, /"riftbuild" ->/);
assert.match(shell, /prepare-native-app/);
assert.doesNotMatch(shell, /riftpp-compile-hot/);
assert.doesNotMatch(nativeBuild, /riftpp-compile-hot|riftppCompileHot|riftbuild-riftpp-hot-compile/);
assert.match(shell, /compiler-run/);
assert.doesNotMatch(shell, /prepare-codynex-/);

assert.match(appHost, /"build\.doctor" -> withCapability\(instance, id, "build\.local"\)/);
assert.match(appHost, /"build\.prepare" -> withCapability\(instance, id, "build\.local"\) \{ riftBuild\.prepare\(args\) \}/);
assert.match(appHost, /"build\.submit" -> withCapability\(instance, id, "build\.local"\) \{ riftBuild\.submit\(args\) \}/);
assert.match(appHost, /private val riftBuild = RiftBuildLocalExecutor\(activity\.applicationContext\)/);

for (const source of ['RiftBoundedAsync.kt', 'RiftBuildLocalExecutor.kt', 'RiftBuildNativeToolchain.kt', 'RiftBuildNativeApp.kt', 'RiftApkV2Signer.kt', 'RiftBuildInstaller.kt', 'RiftAppAbi.kt', 'RiftRappRiftppAdapter.kt', 'RiftRappHost.kt', 'RiftRappManager.kt']) {
  assert.ok(gradle.includes('src/main/java/com/riftos/app/' + source), 'Gradle exact source snapshot omitted ' + source);
}
assert.ok(gradle.includes('sourceSets["main"].assets.directories.add("build/generated/riftosAssets")'), 'Gradle must package generated RiftOS assets');
assert.ok(gradle.includes('sourceSets["main"].jniLibs.directories.add("build/generated/riftosJniLibs")'), 'Gradle must package generated RiftBuild host compiler JNI payloads');
assert.ok(gradle.includes('jniLibs.useLegacyPackaging = true'), 'Gradle must request extracted/legacy JNI packaging for host compiler execution');
assert.ok(manifest.includes('android:extractNativeLibs="true"'), 'RiftOS manifest must extract host compiler native libraries');
assert.ok(manifest.includes('android.permission.REQUEST_INSTALL_PACKAGES'), 'RiftOS manifest omitted REQUEST_INSTALL_PACKAGES');
assert.ok(manifest.includes('.RiftBuildInstallActivity'), 'RiftOS manifest omitted foreground RiftBuild install callback activity');
assert.ok(manifest.includes('.RiftBuildInstallReceiver'), 'RiftOS manifest omitted private RiftBuild install receiver');
assert.ok(manifest.includes('android.intent.action.PACKAGE_FIRST_LAUNCH'), 'RiftOS manifest omitted first-launch proof action');
assert.ok(manifest.includes('<package android:name="com.riftpp.editor" />'), 'RiftOS manifest must retain Rift++ editor bridge visibility');
for (const retiredRiftppVisibility of [
  'com.riftpp.editor.nativev1',
  'com.riftpp.editor.adapterr1',
  'com.riftpp.nativeproof',
]) {
  assert.ok(!manifest.includes(retiredRiftppVisibility), 'RiftOS manifest must not pin Rift++ proof package visibility: ' + retiredRiftppVisibility);
}
for (const retiredPackage of [
  'com.codynex.mc0proof',
  'com.codynex.mc1aproof',
  'com.codynex.mc1bproof',
  'com.codynex.m2vm0proof',
  'com.codynex.m2bproof',
  'com.codynex.mc2aproof',
]) {
  assert.ok(!manifest.includes(retiredPackage), 'retired Codynex proof package visibility resurfaced: ' + retiredPackage);
}
assert.ok(manifest.includes('com.codynex.editor'), 'RiftOS manifest omitted Codynex editor package visibility');
for (const retiredSource of [
  'src/main/cpp/mc0/codynex_mc0_host.cpp',
  'src/main/cpp/mc1/codynex_mc1a_host.cpp',
  'src/main/cpp/mc1/codynex_mc1b_host.cpp',
  'src/main/cpp/m2/codynex_m2_vm0_host.cpp',
  'src/main/cpp/m2/codynex_m2b_host.cpp',
  'src/main/cpp/m2/codynex_mc2a_host.cpp',
]) {
  assert.ok(!gradle.includes(retiredSource), 'retired Codynex proof source resurfaced in Gradle snapshot: ' + retiredSource);
}
assert.match(manifest, /android:name="\.RiftBuildInstallReceiver"[\s\S]*?android:exported="false"/);

assert.match(retained, /RiftBuild doctor blocked local execution/);
assert.ok(!gradle.includes('src/riftbuild.js'), 'retained JavaScript RiftBuild must remain unpackaged');
assert.ok(!toolHost.includes('rift_build'), 'RiftBuild must not expand the MCP catalog');
assert.match(surfaces, /Native RiftBuild/);
assert.match(surfaces, /RiftApkV2Signer\.kt/);
assert.match(surfaces, /RiftBuildInstaller\.kt/);

console.log('Native RiftBuild bounded prepare/package/v2-sign/verify/install-proof contract OK');
