import fs from 'node:fs';
import assert from 'node:assert/strict';

const read = file => fs.readFileSync(file, 'utf8');
const exists = file => fs.existsSync(file);

const nativeBuildPath = 'android/app/src/main/java/com/riftos/app/RiftBuildLocalExecutor.kt';
const nativeToolchainPath = 'android/app/src/main/java/com/riftos/app/RiftBuildNativeToolchain.kt';
const nativeAppPath = 'android/app/src/main/java/com/riftos/app/RiftBuildNativeApp.kt';
const signerPath = 'android/app/src/main/java/com/riftos/app/RiftApkV2Signer.kt';
const installerPath = 'android/app/src/main/java/com/riftos/app/RiftBuildInstaller.kt';
for (const file of [nativeBuildPath, nativeToolchainPath, nativeAppPath, signerPath, installerPath]) {
  assert.ok(exists(file), 'RiftBuild source owner is missing: ' + file);
}

const nativeBuild = read(nativeBuildPath);
const nativeToolchain = read(nativeToolchainPath);
const nativeApp = read(nativeAppPath);
const signer = read(signerPath);
const installer = read(installerPath);
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
const riftppDynamicCompilerService = read('android/app/src/main/java/com/riftos/app/RiftppDynamicCompilerService.kt');
const riftppDynamicCompilerHost = read('android/app/src/main/cpp/riftpp/riftpp_dynamic_compiler_host.cpp');
const managedJvmToolService = read('android/app/src/main/java/com/riftos/app/RiftManagedJvmToolService.kt');
const managedToolchains = read('android/app/src/main/java/com/riftos/app/RiftBuildManagedToolchains.kt');
const kotlinCompiler = read('android/app/src/main/java/com/riftos/app/RiftBuildKotlinCompiler.kt');
const managedKotlinTool = read('android/rift-managed-kotlin-tool/src/main/java/com/riftbuild/tools/kotlinc/KotlinCompilerTool.kt');
const managedKotlinToolGradle = read('android/rift-managed-kotlin-tool/build.gradle.kts');
const riftAppDiagnosticBridge = read('android/app/src/main/java/com/riftos/app/RiftAppDiagnosticBridge.kt');
const appHost = read('android/app/src/main/java/com/riftos/app/RiftBrowserAppHost.kt');
const gradle = read('android/app/build.gradle.kts');
const androidSettings = read('android/settings.gradle.kts');
const riftppAdapterBundleGradle = read('android/riftpp-adapter-runtime-bundle/build.gradle.kts');
const riftppAdapterActivity = read('android/riftpp-adapter-runtime-bundle/src/main/java/com/riftpp/android/RiftppActivity.kt');
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
  'RIFTPP_NATIVE_EDITOR_V1_TARGET_PACKAGE',
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
  riftAppDiagnosticBridge.includes('RIFTPP_ADAPTER_R1_TARGET_PACKAGE'),
  'Rift++ adapter R1 proof package must remain diagnostic-bridge allowlisted'
);

for (const required of [
  'RiftAppDiagnosticBridge.supports(packageName)',
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
assert.match(
  manifest,
  /<package android:name="com\.riftpp\.editor\.nativev1" \/>/
);
assert.match(
  manifest,
  /<package android:name="com\.riftpp\.editor\.adapterr1" \/>/
);
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
  'crossHostExpectedBundles',
  'prepareCodynexMc0',
  'prepare-codynex-mc0',
  'MC0_SEED_BYTES = 172',
  '3276dcbf29704b1ba7d9d331e7891ceff10d85b16bb7688c62273aeaa3ca311e',
  'libcodynex_mc0_host.so',
  'lib/armeabi-v7a/libcodynex_mc0_host.so',
  'readOwnApkEntry',
  'Codynex MC0 seed SHA-256 drift',
  'Codynex MC0 host materialization hash mismatch',
  'compilerAuthority", "assets/mc0_seed.bin',
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
]) assert.ok(nativeBuild.includes(required), 'native RiftBuild contract missing: ' + required);

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
  'TARGET_PACKAGE = "com.riftpp.nativeproof"',
  'RIFTPP_EDITOR_TARGET_PACKAGE = "com.riftpp.editor"',
  'RIFTPP_NATIVE_EDITOR_V1_TARGET_PACKAGE = "com.riftpp.editor.nativev1"',
  'RIFTPP_ADAPTER_R1_TARGET_PACKAGE = "com.riftpp.editor.adapterr1"',
  'getLaunchIntentForPackage',
  'MC0_TARGET_PACKAGE = "com.codynex.mc0proof"',
  'MC1A_TARGET_PACKAGE = "com.codynex.mc1aproof"',
  'MC1B_TARGET_PACKAGE = "com.codynex.mc1bproof"',
  'EDITOR_TARGET_PACKAGE = "com.codynex.editor"',
  'EDITOR_TARGET_ACTIVITY = "com.codynex.editorapp.MainActivity"',
  'CODYNEX_APP_TARGET_PACKAGE = "com.codynex.notepad"',
  'com.codynex.apphost.CodynexAppActivity',
  'ALLOWED_PROOF_PACKAGES',
  'PackageInstaller',
  'USER_ACTION_REQUIRED',
  'PendingIntent.FLAG_MUTABLE',
  'ACTION_PACKAGE_FIRST_LAUNCH',
  'launch-proven',
  'canRequestPackageInstalls',
  'ACTION_MANAGE_UNKNOWN_APP_SOURCES',
  'RiftBuild installer accepts only allowlisted proof packages',
  'class RiftBuildInstallActivity : Activity()',
  'PendingIntent.getBroadcast(',
  'retainPendingConfirmation(confirmIntent)',
  'resumePendingConfirmation',
  'confirmationLaunchState',
  'class RiftBuildInstallReceiver : BroadcastReceiver()',
]) assert.ok(installer.includes(required), 'RiftBuild installer contract missing: ' + required);
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
assert.match(nativeApp, /riftbuild-native-app-validation-v1/);
assert.ok(!nativeToolchain.includes('bundledMaterialized'), 'retired bundled-archive materialization must stay removed');
assert.ok(!nativeToolchain.includes('bundled-archive'), 'retired bundled-archive receipt entries must stay removed');

for (const required of [
  'class RiftBuildNativeApp',
  'riftbuild-native-app/1',
  'riftbuild-native-app/2',
  'RIFTPP_ADAPTER_PROFILE',
  'RIFTPP_ADAPTER_CLASS',
  'RIFTPP_ADAPTER_RUNTIME',
  'materializeManagedRuntime',
  'MAX_MANAGED_DEX_FILES = 8',
  'MAX_MANAGED_DEX_BYTES = 16L * 1024L * 1024L',
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
assert.ok(!nativeApp.includes('ProcessBuilder'), 'native app preparer must not gain process authority');
assert.ok(!nativeApp.includes('Runtime.getRuntime().exec'), 'native app preparer must not gain raw exec authority');
assert.match(nativeApp, /DEX_ENTRY\.matches/);
assert.ok(nativeApp.includes('build/riftbuild/hot-dex'), 'Rift++ adapter packaging must consume project-owned hot DEX output');
assert.ok(nativeApp.includes('run riftbuild kotlin-compile first'), 'Rift++ adapter packaging must require Kotlin hot compilation first');
assert.ok(!nativeApp.includes('riftbuild/managed-runtimes/riftpp-adapter-v1'), 'Rift++ adapter packaging must not depend on baked RiftOS adapter DEX assets');

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
  'class RiftppDynamicCompilerService',
  'riftpp_dynamic_compiler_host',
  'MAX_COMPILER_BYTES = 256 * 1024',
  'MAX_SOURCE_BYTES = 512 * 1024',
  'MAX_OUTPUT_BYTES = 512 * 1024',
  'Process.killProcess(remotePid)',
]) assert.ok(riftppDynamicCompilerService.includes(required), 'dynamic Rift++ compiler service contract missing: ' + required);

for (const required of [
  'Java_com_riftos_app_RiftppDynamicCompilerService_nativeCompileDynamic',
  'PROT_READ | PROT_EXEC',
  'kMaxCompilerBytes = 256 * 1024',
  'kCanary = 0xA5',
]) assert.ok(riftppDynamicCompilerHost.includes(required), 'dynamic Rift++ compiler host contract missing: ' + required);

assert.ok(!exists('android/app/src/main/java/com/riftos/app/RiftppCompilerService.kt'), 'retired legacy Rift++ compiler service must stay absent');
assert.ok(!exists('android/app/src/main/cpp/riftpp/riftpp_compiler_host.cpp'), 'retired legacy Rift++ compiler host must stay absent');
assert.ok(!manifest.includes('.RiftppCompilerService'), 'retired legacy Rift++ compiler service must stay out of the manifest');
assert.ok(!manifest.includes('android:process=":riftppCompiler"'), 'retired legacy Rift++ compiler process must stay absent');
assert.ok(!cmake.includes('riftpp_compiler_host'), 'retired legacy Rift++ compiler target must stay absent');
assert.ok(!shell.includes('riftpp-host'), 'retired legacy riftpp-host shell surface must stay absent');
for (const retired of ['prepare-riftpp-v0', 'prepare-riftpp-seed0-arm64', 'prepare-riftpp-app0', 'prepare-riftpp-editor']) {
  assert.ok(!nativeBuild.includes(retired), 'retired Rift++ special-case RiftBuild route resurfaced: ' + retired);
}
assert.ok(nativeBuild.includes('args.optString("kind", "native-app")'), 'build.prepare must default to generic native-app');
assert.ok(nativeBuild.includes('"native-app" -> prepareNativeApp(project, cwd)'), 'build.prepare native-app must use generic prepareNativeApp');
assert.ok(!nativeBuild.includes('"riftpp-v0"') && !nativeBuild.includes('"riftpp-app0"'), 'retired Rift++ programmatic prepare kinds must stay absent');
assert.ok(cmake.includes('riftpp_dynamic_compiler_host'), 'dynamic Rift++ compiler host target must stay separate');
assert.ok(manifest.includes('.RiftppDynamicCompilerService'), 'dynamic Rift++ compiler service must stay crash-contained in the manifest');
assert.ok(manifest.includes('.RiftManagedJvmToolService'), 'generic managed JVM tool service must stay declared');
assert.ok(manifest.includes(':riftJvmToolHot'), 'generic managed JVM tool service must stay process-isolated');
assert.ok(androidSettings.includes('include(":riftpp-adapter-runtime-bundle")'), 'Rift++ adapter bundle module must stay included');
assert.ok(androidSettings.includes('include(":rift-managed-kotlin-tool")'), 'managed Kotlin compiler payload module must stay included');
assert.ok(androidSettings.includes('https://jitpack.io'), 'managed compiler payload repository must stay available');
assert.ok(gradle.includes('syncRiftBuildRiftppAdapterRuntime'), 'legacy Rift++ adapter DEX sync task must remain available as fallback');
assert.ok(!gradle.includes('dependsOn(syncRiftBuildRiftppAdapterRuntime)'), 'legacy Rift++ adapter DEX sync must not remain active preBuild authority');
assert.ok(gradle.includes('generated/riftosAssets/riftbuild/managed-runtimes/riftpp-adapter-v1'), 'legacy Rift++ adapter generated runtime path must stay pinned');
assert.ok(gradle.includes('syncRiftBuildKotlinToolchain'), 'RiftBuild Kotlin toolchain asset sync must be wired');
assert.ok(gradle.includes('dependsOn(syncRiftBuildKotlinToolchain)'), 'RiftBuild Kotlin toolchain must be active preBuild infrastructure');
assert.ok(gradle.includes('syncRiftBuildCompilerSeeds'), 'managed compiler seed sync must stay wired');
assert.ok(gradle.includes('dependsOn(syncRiftBuildCompilerSeeds)'), 'managed compiler seeds must be active preBuild infrastructure');
assert.ok(gradle.includes('kotlin-android-2.4.0.apk'), 'managed Kotlin compiler seed asset name must stay pinned');
assert.ok(!gradle.includes('kotlin-compiler-embeddable:2.4.10'), 'RiftOS app must not embed the desktop Kotlin compiler implementation');
assert.ok(gradle.includes('com.android.tools:r8:8.13.23'), 'RiftBuild D8/R8 engine version must stay pinned');
assert.ok(riftppAdapterBundleGradle.includes('namespace = "com.riftpp.android"'), 'Rift++ adapter namespace must stay fixed');
for (const required of [
  'class RiftppActivity : Activity(), SurfaceHolder.Callback',
  'System.loadLibrary(library)',
  'nativeLifecycle',
  'nativeSurface',
  'SurfaceView(this)',
]) assert.ok(riftppAdapterActivity.includes(required), 'Rift++ adapter boundary missing: ' + required);
for (const forbidden of ['ProcessBuilder', 'Runtime.getRuntime().exec', 'RiftppCompilerService', 'RiftppEditorBridgeService']) {
  assert.ok(!riftppAdapterActivity.includes(forbidden), 'Rift++ adapter gained forbidden app/runtime authority: ' + forbidden);
}

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
assert.match(nativeBuild, /writeManifestU32\(output, 1\)/);
assert.match(nativeBuild, /writeManifestU32\(output, XML_NO_INDEX\)/);
assert.match(nativeBuild, /buildMc0BinaryManifest/);
assert.match(nativeBuild, /verifyElfImage\(host, 1, 40\)/);
assert.match(nativeBuild, /target", "arm32"/);
assert.match(nativeBuild, /hostParsesSource", false/);
assert.match(nativeBuild, /hostEmitsInstructions", false/);
const mc0Host = read('android/app/src/main/cpp/mc0/codynex_mc0_host.cpp');
assert.match(mc0Host, /while \(total < kSeedBytes\)/);
assert.match(mc0Host, /AAsset_read\(/);
assert.match(mc0Host, /mprotect\(memory, rounded, PROT_READ \| PROT_EXEC\)/);
assert.match(mc0Host, /mprotect\(generated, pageSize, PROT_READ \| PROT_EXEC\)/);
assert.match(mc0Host, /const int validDigits\[\] = \{0, 1, 7, 9\}/);
assert.match(mc0Host, /reject-capacity-0/);
assert.match(mc0Host, /reject-capacity-7/);
assert.match(mc0Host, /kCanary = 0xA5/);
assert.match(mc0Host, /reject-output-unchanged/);
assert.ok(!mc0Host.includes('#include <string>'), 'MC0 host must not depend on std::string');
assert.ok(!mc0Host.includes('std::string'), 'MC0 host must remain C-style test glue');
assert.match(cmake, /codynex_mc0_host[\s\S]*?-fno-exceptions/);
assert.match(cmake, /codynex_mc0_host[\s\S]*?-fno-rtti/);
const mc1aHost = read('android/app/src/main/cpp/mc1/codynex_mc1a_host.cpp');
assert.match(nativeBuild, /prepare-codynex-mc1a/);
assert.match(nativeBuild, /MC1A_SEED_BYTES = 236/);
assert.match(nativeBuild, /MC1A_SEED_SHA256 = "2ef7054e533bfafaefb0fcc14b9cd41cd05aceeec58eeeb335fc6aef4e88ba1a"/);
assert.match(nativeBuild, /MC1A_PACKAGE = "com\.codynex\.mc1aproof"/);
assert.match(nativeBuild, /buildMc1aBinaryManifest/);
assert.match(nativeBuild, /compilerAuthority", "assets\/mc1a_seed\.bin"/);
assert.match(mc1aHost, /kSeedBytes = 236/);
assert.match(mc1aHost, /kSeedAsset = "mc1a_seed\.bin"/);
assert.match(mc1aHost, /while \(total < kSeedBytes\)/);
assert.match(mc1aHost, /const ValidCase validCases\[\]/);
assert.match(mc1aHost, /"ret 255", 7, 255/);
assert.match(mc1aHost, /reject-overflow-256/);
assert.match(mc1aHost, /reject-leading-zero-2/);
assert.match(mc1aHost, /reject-capacity-7/);
assert.match(mc1aHost, /kCanary = 0xA5/);
assert.match(mc1aHost, /reject-output-unchanged/);
assert.ok(!mc1aHost.includes('#include <string>'), 'MC1-A host must not depend on std::string');
assert.ok(!mc1aHost.includes('std::string'), 'MC1-A host must remain C-style test glue');
assert.match(cmake, /codynex_mc1a_host[\s\S]*?-fno-exceptions/);
assert.match(cmake, /codynex_mc1a_host[\s\S]*?-fno-rtti/);
const mc1bHost = read('android/app/src/main/cpp/mc1/codynex_mc1b_host.cpp');
assert.match(nativeBuild, /prepare-codynex-mc1b/);
assert.match(nativeBuild, /MC1B_SEED_BYTES = 552/);
assert.match(nativeBuild, /MC1B_SEED_SHA256 = "4f4a7305900547d949831fc4cfc6c6c0f747edd7ab525adfb8a1488a6ca304be"/);
assert.match(nativeBuild, /MC1B_PACKAGE = "com\.codynex\.mc1bproof"/);
assert.match(nativeBuild, /buildMc1bBinaryManifest/);
assert.match(nativeBuild, /compilerAuthority", "assets\/mc1b_seed\.bin"/);
assert.match(mc1bHost, /kSeedBytes = 552/);
assert.match(mc1bHost, /kSeedAsset = "mc1b_seed\.bin"/);
assert.match(mc1bHost, /kGeneratedBytes = 12/);
assert.match(mc1bHost, /"ret 255\+255", 11, 255, 255, 510/);
assert.match(mc1bHost, /runtime-add-emission/);
assert.match(mc1bHost, /generated-runtime-add-result/);
assert.match(mc1bHost, /reject-left-leading-zero/);
assert.match(mc1bHost, /reject-right-leading-zero/);
assert.match(mc1bHost, /reject-capacity-11/);
assert.match(mc1bHost, /kCanary = 0xA5/);
assert.match(mc1bHost, /reject-output-unchanged/);
assert.ok(!mc1bHost.includes('#include <string>'), 'MC1-B host must not depend on std::string');
assert.ok(!mc1bHost.includes('std::string'), 'MC1-B host must remain C-style test glue');
assert.match(cmake, /codynex_mc1b_host[\s\S]*?-fno-exceptions/);
assert.match(cmake, /codynex_mc1b_host[\s\S]*?-fno-rtti/);
const m2Vm0Host = read('android/app/src/main/cpp/m2/codynex_m2_vm0_host.cpp');
assert.match(nativeBuild, /prepare-codynex-m2-vm0/);
assert.match(nativeBuild, /M2_VM0_SEED_BYTES = 332/);
assert.match(nativeBuild, /M2_VM0_SEED_SHA256 = "0577161c8cad09541a998ba44cacd823ce0b0c3a6a5b607960b855483af1dba6"/);
assert.match(nativeBuild, /M2_VM0_PACKAGE = "com\.codynex\.m2vm0proof"/);
assert.match(nativeBuild, /buildM2Vm0BinaryManifest/);
assert.match(nativeBuild, /fun prepareCodynexM2Vm0[\s\S]*?val manifestBytes = buildM2Vm0BinaryManifest\(\)/);
assert.match(nativeBuild, /vmAuthority", "assets\/vm0_seed\.bin"/);
assert.match(m2Vm0Host, /kSeedBytes = 332/);
assert.match(m2Vm0Host, /kSeedAsset = "vm0_seed\.bin"/);
assert.match(m2Vm0Host, /M2-A VM0 PASS/);
assert.match(m2Vm0Host, /reject-step-limit/);
assert.match(m2Vm0Host, /failure-result-unchanged/);
assert.ok(!m2Vm0Host.includes('#include <string>'), 'M2 VM0 host must not depend on std::string');
assert.ok(!m2Vm0Host.includes('std::string'), 'M2 VM0 host must remain C-style test glue');
assert.match(cmake, /codynex_m2_vm0_host[\s\S]*?-fno-exceptions/);
assert.match(cmake, /codynex_m2_vm0_host[\s\S]*?-fno-rtti/);
const m2bHost = read('android/app/src/main/cpp/m2/codynex_m2b_host.cpp');
assert.match(nativeBuild, /prepare-codynex-m2b/);
assert.match(nativeBuild, /M2_B_VM_BYTES = 812/);
assert.match(nativeBuild, /M2_B_VM_SHA256 = "7d7b33d2796ab2ddbca1519e00f254c2e6c8417af3ee9317ab45929a593b7df5"/);
assert.match(nativeBuild, /M2_B_COMPILER_BYTES = 704/);
assert.match(nativeBuild, /M2_B_COMPILER_SHA256 = "4a3bd4867de5cf76604e5810f2ae92a2af029f694891017bf0e073833510f575"/);
assert.match(nativeBuild, /M2_B_PACKAGE = "com\.codynex\.m2bproof"/);
assert.match(nativeBuild, /buildM2BBinaryManifest/);
assert.match(nativeBuild, /fun prepareCodynexM2B[\s\S]*?val manifestBytes = buildM2BBinaryManifest\(\)/);
assert.match(nativeBuild, /vmAuthority", "assets\/vm1_seed\.bin"/);
assert.match(nativeBuild, /compilerAuthority", "assets\/mc1b_compiler\.bin"/);
assert.match(m2bHost, /kVmBytes = 812/);
assert.match(m2bHost, /kCompilerBytes = 704/);
assert.match(m2bHost, /M2-B PASS/);
assert.match(m2bHost, /vm-compile-valid/);
assert.match(m2bHost, /generated-runtime-add-result/);
assert.match(m2bHost, /reject-capacity-11/);
assert.ok(!m2bHost.includes('#include <string>'), 'M2-B host must not depend on std::string');
assert.ok(!m2bHost.includes('std::string'), 'M2-B host must remain C-style test glue');
assert.match(cmake, /codynex_m2b_host[\s\S]*?-fno-exceptions/);
assert.match(cmake, /codynex_m2b_host[\s\S]*?-fno-rtti/);
const mc2aHost = read('android/app/src/main/cpp/m2/codynex_mc2a_host.cpp');
assert.match(nativeBuild, /prepare-codynex-mc2a/);
assert.match(nativeBuild, /MC2_A_VM_BYTES = 812/);
assert.match(nativeBuild, /MC2_A_VM_SHA256 = "7d7b33d2796ab2ddbca1519e00f254c2e6c8417af3ee9317ab45929a593b7df5"/);
assert.match(nativeBuild, /MC2_A_COMPILER_BYTES = 292/);
assert.match(nativeBuild, /MC2_A_COMPILER_SHA256 = "b00cc99ef0cf122d47cff54123e1e1ec19f83a44dfe949f5358428e45f47fb2e"/);
assert.match(nativeBuild, /MC2_A_SOURCE_BYTES = 584/);
assert.match(nativeBuild, /MC2_A_SOURCE_SHA256 = "a30e68e38600e25fc394c184b03c3e24f2775ffc2572c19a22426b3a0714581c"/);
assert.match(nativeBuild, /MC2_A_PACKAGE = "com\.codynex\.mc2aproof"/);
assert.match(nativeBuild, /buildMc2ABinaryManifest/);
assert.match(nativeBuild, /fun prepareCodynexMc2A[\s\S]*?val manifestBytes = buildMc2ABinaryManifest\(\)/);
assert.match(nativeBuild, /vmAuthority", "assets\/vm1_seed\.bin"/);
assert.match(nativeBuild, /compilerAuthority", "assets\/selfhost_compiler\.bin"/);
assert.match(nativeBuild, /sourceAuthority", "assets\/selfhost_compiler\.cx0"/);
assert.match(mc2aHost, /kVmBytes = 812/);
assert.match(mc2aHost, /kCompilerBytes = 292/);
assert.match(mc2aHost, /kSourceBytes = 584/);
assert.match(mc2aHost, /MC2-A PASS/);
assert.match(mc2aHost, /B-equals-A/);
assert.match(mc2aHost, /C-equals-B/);
assert.match(mc2aHost, /C-equals-A/);
assert.ok(!mc2aHost.includes('#include <string>'), 'MC2-A host must not depend on std::string');
assert.ok(!mc2aHost.includes('std::string'), 'MC2-A host must remain C-style proof glue');
assert.match(cmake, /codynex_mc2a_host[\s\S]*?-fno-exceptions/);
assert.match(cmake, /codynex_mc2a_host[\s\S]*?-fno-rtti/);

assert.match(nativeBuild, /RIFTPP_EDITOR_PACKAGE = "com\.riftpp\.editor"/);
assert.match(nativeBuild, /RIFTPP_EDITOR_LIBRARY_NAME = "riftpp_editor_bridge"/);
assert.match(nativeBuild, /RIFTPP_EDITOR_BRIDGE_SERVICE/);
assert.match(nativeBuild, /buildRiftppEditorBinaryManifest[\s\S]*?"service"/);
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
assert.match(nativeBuild, /temporaryPlatformShell", "Kotlin\/Android Activity"/);
assert.match(nativeBuild, /uiProtocol", "RUI2"/);
assert.match(nativeBuild, /kotlinEmitsRpa2", false/);
assert.match(nativeBuild, /kotlinInterpretsRpa2", false/);
assert.match(nativeBuild, /temporaryApkPackSign", true/);
assert.match(nativeBuild, /bootstrapCompilerAuthority", "frozen Rift\+\+ S3 ARM32 recovery root"/);
assert.match(nativeBuild, /s3NextBootstrapAuthority", "workspace-supplied promoted S2 Generation-C native compiler"/);
assert.match(nativeBuild, /s3NextOpcodeSurface", "full 21-op 00\.\.14"/);
assert.match(nativeBuild, /developmentCompilerAuthority", "workspace-supplied Rift\+\+ S3 Next"/);
const riftppReplacementTargetKey = nativeBuild.indexOf('"replacementTarget"');
assert.ok(
    riftppReplacementTargetKey >= 0,
    'Rift++ editor materializer omitted replacementTarget'
);
const riftppReplacementTargetWindow = nativeBuild.slice(
    riftppReplacementTargetKey,
    riftppReplacementTargetKey + 220
);
assert.ok(
    riftppReplacementTargetWindow.includes(
        '"native Rift++ editor/filesystem/compiler/runtime/packer/signer"'
    ),
    'Rift++ editor replacementTarget drift'
);
assert.match(nativeBuild, /frontend\.app2\.arm32\.r4\.hex/);
assert.match(nativeBuild, /frontend\.project1\.arm32\.r4\.hex/);
assert.match(nativeBuild, /runtime\.app2\.arm32\.r4\.hex/);
assert.match(nativeBuild, /standalone\/app\/examples\/notepad\/src\/main\.riftpp/);
assert.match(nativeBuild, /standalone\/app\/examples\/notepad\/src\/ui\.riftpp/);
assert.match(nativeBuild, /RiftppApkBuilder\.kt/);
assert.match(nativeBuild, /RiftppApkV2Signer\.kt/);
assert.match(nativeBuild, /RiftppAppActivity\.kt/);
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
assert.match(nativeBuild, /RiftppRelocatableElfPreflight\.kt/);
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
const editorToolchain = read('android/app/src/main/java/com/codynex/editorapp/Source0SelfHostToolchainPort.kt');
const codynexProvider = read('android/app/src/main/java/com/riftos/app/CodynexCompilerProvider.kt');
const codynexHeadlessRuntime = read('android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt');
const nativeShellServices = read('android/app/src/main/java/com/riftos/app/RiftNativeShellServices.kt');
const editorVmBridgeKt = read('android/app/src/main/java/com/codynex/editorapp/CodynexRuntimeBridge.kt');
const editorVmBridgeCpp = read('android/app/src/main/cpp/editor/editor_vm_bridge.cpp');
assert.match(editorVmBridgeCpp, /uint8_t\* scratch;/);
assert.match(editorVmBridgeCpp, /uint32_t scratchCapacity;/);
assert.match(editorVmBridgeCpp, /sizeof\(VmContext\) == 28/);
assert.match(editorVmBridgeCpp, /CodynexRuntimeBridge_run/);

assert.match(nativeBuild, /prepare-codynex-editor/);
assert.match(nativeBuild, /fun prepareCodynexEditor/);
assert.match(nativeBuild, /EDITOR_PACKAGE = "com\.codynex\.editor"/);
assert.match(nativeBuild, /EDITOR_ACTIVITY = "com\.codynex\.editorapp\.MainActivity"/);
assert.match(nativeBuild, /EDITOR_LIBRARY_NAME = "codynex_editor_vm"/);
assert.match(nativeBuild, /EDITOR_VM_HEX_SHA256 = "1f013e2592741895f511d1724ecd69ee156e24f771c289d848e1bab265d3655e"/);
assert.match(nativeBuild, /EDITOR_VM2_HEX_SHA256 = "3f746727e18a55933a20dc63fc7f84544566f982aa5cab0803573c456926c916"/);
assert.match(nativeBuild, /EDITOR_COMPILER_HEX_SHA256 = "a30e68e38600e25fc394c184b03c3e24f2775ffc2572c19a22426b3a0714581c"/);
assert.match(nativeBuild, /EDITOR_SOURCE0_SHA256 = "a30e68e38600e25fc394c184b03c3e24f2775ffc2572c19a22426b3a0714581c"/);
assert.match(nativeBuild, /buildEditorBinaryManifest/);
assert.match(nativeBuild, /readOwnDexEntries/);
assert.match(nativeBuild, /classes\.dex missing for code-bearing Activity package/);
assert.match(nativeBuild, /DEX_ENTRY/);
assert.match(nativeBuild, /editorCoreLanguageAgnostic", true/);
assert.match(nativeBuild, /remoteBuildRequired", false/);
assert.match(nativeBuild, /runtimeAuthority", "assets\/vm2_seed\.hex"/);
assert.match(nativeBuild, /compatibilityRuntimeAuthority", "assets\/vm1_seed\.hex"/);
assert.match(nativeBuild, /compilerAuthority", "assets\/selfhost_compiler\.hex"/);
assert.match(nativeBuild, /sourceAuthority", "assets\/selfhost_compiler\.cx0"/);
assert.match(cmake, /codynex_editor_vm[\s\S]*?editor\/editor_vm_bridge\.cpp/);
assert.match(cmake, /codynex_editor_vm[\s\S]*?-fno-exceptions/);
assert.match(cmake, /codynex_editor_vm[\s\S]*?-fno-rtti/);
assert.ok(gradle.includes('src/main/cpp/editor/editor_vm_bridge.cpp'), 'Gradle exact native source snapshot omitted editor VM bridge');
assert.match(gradle, /verifyCodynexEditorPayload/);
assert.match(gradle, /val validateCodynexCompilerTransition by tasks\.registering/);
assert.match(gradle, /COMPILER_VERSION_PREVIOUS/);
assert.match(gradle, /COMPILER_VERSION_CURRENT/);
assert.match(gradle, /SUPPORTED_COMPILER_VERSIONS/);
assert.match(gradle, /codynex-c0-ref\/0\.11\.0/);
assert.match(gradle, /codynex-c0-ref\/0\.12\.0/);
assert.match(gradle, /dependsOn\(validateCodynexCompilerTransition\)/);
assert.match(gradle, /d545e3802b300af446bbce56948b10a0ac7b5c00c04c118caa84a93f38e11b45/);
assert.match(gradle, /d2d4fc035f8e024549f2c3232520cdd40f277966328df75f5c942134017395cc/);
assert.match(gradle, /481d8b0306bbf02bc173a28c14b359f9867153611b7272435404a4730d786bea/);
assert.match(gradle, /bb16b80adec43339f08aac19054f684d16b72700d07813043760feac78c45f26/);
assert.match(gradle, /3b564713851ad4e393519aee07301760993866875742a5bb5a273bf3dedd5f76/);
assert.match(nativeBuild, /bb16b80adec43339f08aac19054f684d16b72700d07813043760feac78c45f26/);
assert.match(nativeBuild, /3b564713851ad4e393519aee07301760993866875742a5bb5a273bf3dedd5f76/);
assert.match(installer, /RIFTPP_EDITOR_TARGET_PACKAGE = "com\.riftpp\.editor"/);
assert.match(installer, /RIFTPP_NATIVE_EDITOR_V1_TARGET_PACKAGE = "com\.riftpp\.editor\.nativev1"/);
assert.match(installer, /EDITOR_TARGET_PACKAGE = "com\.codynex\.editor"/);
assert.match(installer, /EDITOR_TARGET_ACTIVITY = "com\.codynex\.editorapp\.MainActivity"/);
assert.match(manifest, /com\.codynex\.editor/);
assert.match(shell, /prepare-codynex-editor/);
assert.match(shell, /prepare-codynex-app/);

assert.match(nativeBuild, /fun prepareCodynexApp/);
assert.match(nativeBuild, /CODYNEX_APPHOST_PROJECT = "external\/apphost"/);
assert.match(nativeBuild, /CODYNEX_APP_PACKAGE = "com\.codynex\.notepad"/);
assert.match(nativeBuild, /CODYNEX_APP_ACTIVITY/);
assert.match(nativeBuild, /com\.codynex\.apphost\.CodynexAppActivity/);
assert.match(nativeBuild, /CODYNEX_APP_PROGRAM_ASSET = "program\.vm2"/);
assert.match(nativeBuild, /compileCodynexC0ProjectVM2/);
assert.match(nativeBuild, /EDITOR_VM2_HEX/);
assert.match(nativeBuild, /vm2_seed\.hex/);
assert.match(nativeBuild, /compiled\.vm2/);
assert.match(nativeBuild, /buildCodynexAppBinaryManifest/);
assert.match(nativeBuild, /hostContainsAppSemantics", false/);
assert.match(nativeBuild, /appSemantics", "assets\/program\.vm2"/);
assert.match(installer, /CODYNEX_APP_TARGET_PACKAGE = "com\.codynex\.notepad"/);
assert.match(installer, /com\.codynex\.apphost\.CodynexAppActivity/);
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
assert.match(editorToolchain, /class Source0SelfHostToolchainPort/);
assert.match(editorToolchain, /COMPILER_AUTHORITY/);
assert.match(editorToolchain, /com\.riftos\.app\.codynexcompiler/);
assert.match(editorToolchain, /contentResolver\.call/);
assert.match(editorToolchain, /COMPILE_METHOD_VM1 = "compile-c0"/);
assert.match(editorToolchain, /COMPILE_PROJECT_METHOD_VM1 = "compile-c0-project"/);
assert.match(editorToolchain, /COMPILE_METHOD_VM2 = "compile-c0-vm2"/);
assert.match(editorToolchain, /COMPILE_PROJECT_METHOD_VM2 = "compile-c0-project-vm2"/);
assert.match(editorToolchain, /MAX_PROJECT_BYTES = 1024 \* 1024/);
assert.match(editorToolchain, /MAX_PROJECT_MODULES = 64/);
assert.match(editorToolchain, /EditorVmTarget\.VM2/);
assert.match(editorToolchain, /Preview passed: \$targetLabel result/);
assert.match(editorToolchain, /artifacts\.vm2/);
assert.match(editorToolchain, /artifacts\.vm1/);
assert.match(editorToolchain, /data class LivePreviewRun/);
assert.match(editorToolchain, /MAX_LIVE_INPUT_BYTES = 1024/);
assert.match(editorToolchain, /replayLivePreview/);
assert.match(editorToolchain, /executePreview/);
assert.match(codynexProvider, /class CodynexCompilerProvider/);
assert.match(codynexProvider, /codynex-c0-ref\/0\.11\.0/);
assert.match(codynexProvider, /codynex-c0-ref\/0\.12\.0/);
assert.match(codynexProvider, /SUPPORTED_COMPILER_VERSIONS/);
assert.match(codynexHeadlessRuntime, /codynex-c0-ref\/0\.11\.0/);
assert.match(codynexHeadlessRuntime, /codynex-c0-ref\/0\.12\.0/);
assert.match(codynexHeadlessRuntime, /CODYNEX_C0_COMPILER_VERSIONS/);
assert.match(codynexHeadlessRuntime, /compileCodynexC0ProjectVM2/);
assert.match(codynexHeadlessRuntime, /CODYNEX_C0_PROJECT_VM2_ENTRY/);
assert.match(nativeBuild, /codynex-c0-ref\/0\.12\.0/);
assert.match(nativeShellServices, /codynex-c0-ref\/0\.11\.0\|0\.12\.0 transition/);
assert.match(codynexProvider, /9874e844c24fe92c65908ce9b3cfb192f87774984a9e4fc600d883badcbe19b5/);
assert.match(codynexProvider, /MAX_SOURCE_BYTES = 256 \* 1024/);
assert.match(codynexProvider, /METHOD_COMPILE_PROJECT = "compile-c0-project"/);
assert.match(codynexProvider, /METHOD_COMPILE_VM2 = "compile-c0-vm2"/);
assert.match(codynexProvider, /METHOD_COMPILE_PROJECT_VM2 = "compile-c0-project-vm2"/);
assert.match(codynexProvider, /MAX_PROJECT_BYTES = 1024 \* 1024/);
assert.match(codynexProvider, /MAX_PROJECT_MODULES = 64/);
assert.match(codynexProvider, /compileCodynexC0Project/);
assert.match(codynexProvider, /compileCodynexC0ProjectVM2/);
assert.match(codynexProvider, /MAX_VM1_BYTES = 64 \* 1024/);
assert.match(codynexProvider, /MAX_VM2_BYTES = 64 \* 1024/);
assert.match(codynexProvider, /runtime\.executeQuickJs/);
assert.match(manifest, /CodynexCompilerProvider/);
assert.match(manifest, /com\.riftos\.app\.codynexcompiler/);
assert.ok(gradle.includes('src/main/java/com/riftos/app/CodynexCompilerProvider.kt'), 'Gradle exact source snapshot omitted Codynex compiler provider');
assert.match(editorVmBridgeKt, /System\.loadLibrary\("codynex_editor_vm"\)/);
assert.ok(!/Source0|selfhost_compiler|hex character/i.test(editorVmBridgeCpp), 'Generic editor VM bridge gained Source0/compiler parsing semantics');
assert.match(editorVmBridgeCpp, /VmContext/);
assert.match(editorVmBridgeCpp, /stepBudget/);
assert.match(nativeBuild, /\.put\("signed", false\)/);
assert.match(nativeBuild, /\.put\("installableClaimed", false\)/);

assert.match(shell, /private val riftBuild = RiftBuildLocalExecutor\(appContext\)/);
assert.match(shell, /"riftbuild" ->/);
assert.match(shell, /riftbuild doctor\|validate\|plan\|toolchain-status\|toolchain-install-bundled\|managed-status\|managed-payload\|managed-copy\|compiler-status\|compiler-run\|kotlin-status\|kotlin-compile\|riftpp-compile-hot\|compile-native\|compile-object\|extract-object-text\|prepare-native-app\|prepare-codynex-mc0\|prepare-codynex-mc1a\|prepare-codynex-mc1b\|prepare-codynex-m2-vm0\|prepare-codynex-m2b\|prepare-codynex-mc2a\|prepare-codynex-editor\|prepare-codynex-app\|pack\|sign\|verify\|install-proof\|install-status\|launch-proof\|runs\|artifacts/);

assert.match(appHost, /"build\.doctor" -> withCapability\(instance, id, "build\.local"\)/);
assert.match(appHost, /"build\.prepare" -> withCapability\(instance, id, "build\.local"\) \{ riftBuild\.prepare\(args\) \}/);
assert.match(appHost, /"build\.submit" -> withCapability\(instance, id, "build\.local"\) \{ riftBuild\.submit\(args\) \}/);
assert.match(appHost, /private val riftBuild = RiftBuildLocalExecutor\(activity\.applicationContext\)/);

for (const source of ['RiftBoundedAsync.kt', 'RiftBuildLocalExecutor.kt', 'RiftBuildNativeToolchain.kt', 'RiftBuildNativeApp.kt', 'RiftApkV2Signer.kt', 'RiftBuildInstaller.kt']) {
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
assert.ok(manifest.includes('com.riftpp.nativeproof'), 'RiftOS manifest omitted Rift++ proof-package visibility');
assert.ok(manifest.includes('com.codynex.mc0proof'), 'RiftOS manifest omitted Codynex MC0 proof-package visibility');
assert.ok(manifest.includes('com.codynex.mc1aproof'), 'RiftOS manifest omitted Codynex MC1-A proof-package visibility');
assert.ok(manifest.includes('com.codynex.mc1bproof'), 'RiftOS manifest omitted Codynex MC1-B proof-package visibility');
assert.ok(manifest.includes('com.codynex.m2vm0proof'), 'RiftOS manifest omitted Codynex M2 VM0 proof-package visibility');
assert.ok(manifest.includes('com.codynex.m2bproof'), 'RiftOS manifest omitted Codynex M2-B proof-package visibility');
assert.ok(manifest.includes('com.codynex.mc2aproof'), 'RiftOS manifest omitted Codynex MC2-A proof-package visibility');
assert.ok(manifest.includes('com.codynex.editor'), 'RiftOS manifest omitted Codynex editor package visibility');
assert.ok(gradle.includes('src/main/cpp/mc0/codynex_mc0_host.cpp'), 'Gradle exact native source snapshot omitted MC0 host');
assert.ok(gradle.includes('src/main/cpp/mc1/codynex_mc1a_host.cpp'), 'Gradle exact native source snapshot omitted MC1-A host');
assert.ok(gradle.includes('src/main/cpp/mc1/codynex_mc1b_host.cpp'), 'Gradle exact native source snapshot omitted MC1-B host');
assert.ok(gradle.includes('src/main/cpp/m2/codynex_m2_vm0_host.cpp'), 'Gradle exact native source snapshot omitted M2 VM0 host');
assert.ok(gradle.includes('src/main/cpp/m2/codynex_m2b_host.cpp'), 'Gradle exact native source snapshot omitted M2-B host');
assert.ok(gradle.includes('src/main/cpp/m2/codynex_mc2a_host.cpp'), 'Gradle exact native source snapshot omitted MC2-A host');
assert.match(manifest, /android:name="\.RiftBuildInstallReceiver"[\s\S]*?android:exported="false"/);

assert.match(retained, /RiftBuild doctor blocked local execution/);
assert.ok(!gradle.includes('src/riftbuild.js'), 'retained JavaScript RiftBuild must remain unpackaged');
assert.ok(!toolHost.includes('rift_build'), 'RiftBuild must not expand the MCP catalog');
assert.match(surfaces, /Native RiftBuild/);
assert.match(surfaces, /RiftApkV2Signer\.kt/);
assert.match(surfaces, /RiftBuildInstaller\.kt/);

console.log('Native RiftBuild bounded prepare/package/v2-sign/verify/install-proof contract OK');
