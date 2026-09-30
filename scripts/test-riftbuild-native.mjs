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
const appHost = read('android/app/src/main/java/com/riftos/app/RiftBrowserAppHost.kt');
const gradle = read('android/app/build.gradle.kts');
const cmake = read('android/app/src/main/cpp/CMakeLists.txt');
const manifest = read('android/app/src/main/AndroidManifest.xml');
const retained = read('src/riftbuild.js');
const toolHost = read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const surfaces = read('docs/PUBLIC_SURFACES.md');
const riftppSeed0Arm64Proof = read('android/app/src/main/cpp/riftpp/riftpp_seed0_arm64_proof.cpp');
const riftppSeed0Arm64Gradle = read('proofs/riftpp-seed0-arm64/app/build.gradle.kts');
const riftppSeed0Arm64Manifest = read('proofs/riftpp-seed0-arm64/app/src/main/AndroidManifest.xml');

for (const required of [
  'class RiftBuildLocalExecutor',
  'system/riftbuild/v1/runs',
  'documents/builds',
  'workspaceRoot',
  'RiftBuildNativeToolchain',
  'toolchain-status',
  'toolchain-install-bundled',
  'compile-native',
  'RiftBuildNativeApp',
  'prepare-native-app',
  'structuredCompilerProcessExecution',
  'downloadedToolchainsAllowed',
  'preparedArtifactPackagerReady',
  'prepared-native-proof',
  'riftpp-direct-elf-shared-v0-bytes/1',
  'DIRECT-ELF-SHARED-V0-BYTES.json',
  'prepareRiftppV0',
  'prepareRiftppSeed0Arm64Proof',
  'prepare-riftpp-seed0-arm64',
  'RIFTPP_SEED0_ARM64_COMPILER_BYTES = 276',
  'b1f33b940d2ac199f5e38c1c621cd8b27ed15dd3a60fcb85daad7b7154b2ee0c',
  'lib/arm64-v8a/libriftpp_seed0_arm64_proof.so',
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
  'Rift++ V0 raw SHA-256 mismatch',
  'Rift++ V0 ELF machine mismatch',
  'libriftpp_nativeproof.so',
  'ByteArrayOutputStream',
  'RIFTPP_V0_BINARY_MANIFEST_BYTES = 1440',
  'ac035bb5bf89f55a3f34bae8eea980108324d2f36333f1e708f8a0b82af8e7c2',
  'eb0e8b7f3020499b50b135d1ef93c60af89f997c7c1984ec3d17d32c1595a6c1',
  '1d1739a07896c4d7f1e521fa154a0285c5c4eefe87eab718830fee37194c0765',
  'buildRiftppV0BinaryManifest',
  'RiftBuild V0 binary manifest SHA-256 oracle failed',
  'Materialized binary manifest SHA-256 drift',
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
  'rift-native.json',
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

for (const required of [
  'class RiftBuildNativeApp',
  'riftbuild-native-app/1',
  'rift-app.json',
  'android.app.NativeActivity',
  'android.app.lib_name',
  'build/riftbuild/prepared/AndroidManifest.xml',
  'assetFiles',
  'MAX_ASSET_FILES = 5_000',
  'MAX_ASSET_BYTES = 128L * 1024L * 1024L',
  'rift-app.json library must match rift-native.json library',
  'Native app assetsDir must not point inside build/riftbuild',
]) assert.ok(nativeApp.includes(required), 'native app preparer contract missing: ' + required);
assert.ok(!nativeApp.includes('ProcessBuilder'), 'native app preparer must not gain process authority');
assert.ok(!nativeApp.includes('Runtime.getRuntime().exec'), 'native app preparer must not gain raw exec authority');

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

const riftppApp0Host = read('android/app/src/main/cpp/riftpp/riftpp_app0_host.cpp');
assert.match(nativeBuild, /prepare-riftpp-app0/);
assert.match(nativeBuild, /RIFTPP_APP0_COMPILER_HEX_BYTES = 9576/);
assert.match(nativeBuild, /RIFTPP_APP0_COMPILER_BYTES = 4788/);
assert.match(nativeBuild, /RIFTPP_APP0_TARGET_PACKAGE/);
assert.match(nativeBuild, /RIFTPP_APP0_ARM64_HOST_APK_ENTRY = "lib\/arm64-v8a\/libriftpp_app0_host\.so"/);
assert.match(nativeBuild, /RIFTPP_APP0_ARM32_HOST_APK_ENTRY = "lib\/armeabi-v7a\/libriftpp_app0_host\.so"/);
assert.match(nativeBuild, /fun prepareRiftppApp0/);
assert.match(nativeBuild, /runVm1Bounded/);
assert.match(nativeBuild, /analyzeApp0Vm1/);
assert.match(nativeBuild, /target == "universal"/);
assert.match(nativeBuild, /hostParsesTig0\", false/);
assert.match(nativeBuild, /plannerParsesTig0\", false/);
assert.match(nativeBuild, /requirementAuthority\", \"compiled program\.bin\"/);
assert.match(nativeBuild, /applicationVmSeedAsset\", false/);
assert.match(nativeBuild, /core\.vm1\.arm64/);
assert.match(nativeBuild, /core\.vm1\.arm32/);
assert.match(nativeBuild, /verifyElfImage\(arm64Host, 2, 183\)/);
assert.match(nativeBuild, /verifyElfImage\(arm32Host, 1, 40\)/);
assert.match(nativeBuild, /lib\/arm64-v8a/);
assert.match(nativeBuild, /lib\/armeabi-v7a/);
assert.match(nativeBuild, /io\.output\.bytes/);
assert.ok(!nativeBuild.includes('RIFTPP_APP0_VM_HEX'), 'U0 must not package the historical ARM32 VM seed');
assert.ok(!nativeBuild.includes('RIFTPP_APP0_VM_BYTES'), 'U0 must not retain App0 VM-seed byte authority');
assert.ok(!nativeBuild.includes('RIFTPP_APP0_VM_SHA256'), 'U0 must not retain App0 VM-seed hash authority');
assert.match(riftppApp0Host, /kProgramAsset = "program\.bin"/);
assert.match(riftppApp0Host, /kMaxProgramBytes = 64U \* 1024U/);
assert.match(riftppApp0Host, /kOutputBytes = 1024U/);
assert.match(riftppApp0Host, /int32_t runVm1\(/);
assert.match(riftppApp0Host, /case 0x01:[\s\S]*?case 0x0a:/);
assert.ok(!riftppApp0Host.includes('vm1_seed.bin'), 'U0 native host must not load the ARM32 VM seed asset');
assert.ok(!riftppApp0Host.includes('mmap('), 'U0 native host must not map architecture-specific executable VM bytes');
assert.ok(!riftppApp0Host.includes('__arm__'), 'U0 runtime source must be shared by ARM64 and ARM32');
assert.ok(!riftppApp0Host.includes('program.tig0'), 'App0 host must not parse TIG0 source');
assert.ok(!riftppApp0Host.includes('Hello from Rift++'), 'App0 host must not embed Hello application behavior');
assert.ok(!riftppApp0Host.includes('#include <string>'), 'App0 host must not depend on std::string');
assert.match(cmake, /riftpp_app0_host[\s\S]*?riftpp\/riftpp_app0_host\.cpp/);
assert.match(cmake, /riftpp_app0_host[\s\S]*?-fno-exceptions/);
assert.match(cmake, /riftpp_app0_host[\s\S]*?-fno-rtti/);
assert.ok(gradle.includes('src/main/cpp/riftpp/riftpp_app0_host.cpp'), 'Gradle exact native source snapshot omitted Rift++ App0 host');
assert.match(gradle, /abiFilters \+= listOf\("arm64-v8a", "armeabi-v7a"\)/);
assert.match(installer, /RIFTPP_APP0_TARGET_PACKAGE = \"com\.riftpp\.hello\"/);
assert.ok(manifest.includes('com.riftpp.hello'), 'RiftOS manifest omitted Rift++ App0 package visibility');

assert.match(cmake, /if\(ANDROID_ABI STREQUAL "arm64-v8a"\)[\s\S]*?riftpp_seed0_arm64_proof/);
assert.match(cmake, /riftpp_seed0_arm64_proof[\s\S]*?riftpp\/riftpp_seed0_arm64_proof\.cpp/);
assert.ok(gradle.includes('src/main/cpp/riftpp/riftpp_seed0_arm64_proof.cpp'), 'Gradle exact native source snapshot omitted Rift++ seed0 ARM64 proof harness');
assert.match(riftppSeed0Arm64Gradle, /abiFilters \+= listOf\("arm64-v8a"\)/);
assert.ok(!riftppSeed0Arm64Gradle.includes('armeabi-v7a'), 'ARM64 proof APK must remain arm64-only');
assert.match(riftppSeed0Arm64Manifest, /package="com\.riftpp\.nativeproof"/);
assert.match(riftppSeed0Arm64Manifest, /android:value="riftpp_seed0_arm64_proof"/);
assert.match(riftppSeed0Arm64Proof, /#if !defined\(__aarch64__\)/);
assert.match(riftppSeed0Arm64Proof, /kCompilerBytes = 276U/);
assert.match(riftppSeed0Arm64Proof, /kPayloadOffset = 16U/);
assert.match(riftppSeed0Arm64Proof, /using CompilerFn = uint32_t \(\*\)\(/);
assert.match(riftppSeed0Arm64Proof, /using PayloadFn = uint32_t \(\*\)\(\)/);
assert.match(riftppSeed0Arm64Proof, /Rift\+\+ ARM64 seed0 PASS compiler=b1f33b94 vectors=5 payloads=5 rejects=17 crossHost=exact/);
assert.ok(!riftppSeed0Arm64Proof.includes('compiler.arm64.hex'), 'Proof harness must receive compiler bytes as an asset, not locate Rift++ source itself');
assert.ok(!riftppSeed0Arm64Proof.includes('parser'), 'Proof harness must not implement a Rift++ parser');
assert.ok(!riftppSeed0Arm64Proof.includes('emitArm'), 'Proof harness must not implement an ARM emitter');

const editorCoreModel = read('android/app/src/main/java/com/codynex/editor/EditorModel.kt');
const editorCorePorts = read('android/app/src/main/java/com/codynex/editor/EditorPorts.kt');
const editorCoreController = read('android/app/src/main/java/com/codynex/editor/CodynexEditorController.kt');
const editorActivity = read('android/app/src/main/java/com/codynex/editorapp/MainActivity.kt');
const editorApkBuilder = read('android/app/src/main/java/com/codynex/editorapp/CodynexApkBuilder.kt');
const editorApkSigner = read('android/app/src/main/java/com/codynex/editorapp/CodynexApkV2Signer.kt');
const codynexAppActivity = read('android/app/src/main/java/com/codynex/apphost/CodynexAppActivity.kt');
const editorWorkspace = read('android/app/src/main/java/com/codynex/editorapp/FileWorkspacePort.kt');
const editorBootstrap = read('android/app/src/main/java/com/codynex/editorapp/BootstrapArtifacts.kt');
const editorToolchain = read('android/app/src/main/java/com/codynex/editorapp/Source0SelfHostToolchainPort.kt');
const codynexProvider = read('android/app/src/main/java/com/riftos/app/CodynexCompilerProvider.kt');
const codynexHeadlessRuntime = read('android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt');
const nativeShellServices = read('android/app/src/main/java/com/riftos/app/RiftNativeShellServices.kt');
const editorVmBridgeKt = read('android/app/src/main/java/com/codynex/editorapp/Vm1Bridge.kt');
const editorVmBridgeCpp = read('android/app/src/main/cpp/editor/editor_vm_bridge.cpp');
assert.match(editorVmBridgeCpp, /uint8_t\* scratch;/);
assert.match(editorVmBridgeCpp, /uint32_t scratchCapacity;/);
assert.match(editorVmBridgeCpp, /sizeof\(VmContext\) == 28/);

assert.match(nativeBuild, /prepare-codynex-editor/);
assert.match(nativeBuild, /fun prepareCodynexEditor/);
assert.match(nativeBuild, /EDITOR_PACKAGE = "com\.codynex\.editor"/);
assert.match(nativeBuild, /EDITOR_ACTIVITY = "com\.codynex\.editorapp\.MainActivity"/);
assert.match(nativeBuild, /EDITOR_LIBRARY_NAME = "codynex_editor_vm"/);
assert.match(nativeBuild, /EDITOR_VM_HEX_SHA256 = "1f013e2592741895f511d1724ecd69ee156e24f771c289d848e1bab265d3655e"/);
assert.match(nativeBuild, /EDITOR_COMPILER_HEX_SHA256 = "a30e68e38600e25fc394c184b03c3e24f2775ffc2572c19a22426b3a0714581c"/);
assert.match(nativeBuild, /EDITOR_SOURCE0_SHA256 = "a30e68e38600e25fc394c184b03c3e24f2775ffc2572c19a22426b3a0714581c"/);
assert.match(nativeBuild, /buildEditorBinaryManifest/);
assert.match(nativeBuild, /readOwnDexEntries/);
assert.match(nativeBuild, /classes\.dex missing for code-bearing Activity package/);
assert.match(nativeBuild, /DEX_ENTRY/);
assert.match(nativeBuild, /editorCoreLanguageAgnostic", true/);
assert.match(nativeBuild, /remoteBuildRequired", false/);
assert.match(nativeBuild, /runtimeAuthority", "assets\/vm1_seed\.hex"/);
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
assert.match(gradle, /b89c30344a2b7a0aa48c5956030cdfeaae363bf02d3bd705051e9e88bb34f592/);
assert.match(gradle, /ab27d72241098fa6b09d2c26c48a7e1b129d95a500c386a13b96836b54209f28/);
assert.match(gradle, /8e231086c097ecc0bb8dbc60534509eaafa0c7cc6556def65f4fa12f7dc01f1c/);
assert.match(gradle, /3b564713851ad4e393519aee07301760993866875742a5bb5a273bf3dedd5f76/);
assert.match(nativeBuild, /8e231086c097ecc0bb8dbc60534509eaafa0c7cc6556def65f4fa12f7dc01f1c/);
assert.match(nativeBuild, /3b564713851ad4e393519aee07301760993866875742a5bb5a273bf3dedd5f76/);
assert.match(installer, /RIFTPP_EDITOR_TARGET_PACKAGE = "com\.riftpp\.editor"/);
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
assert.match(nativeBuild, /CODYNEX_APP_PROGRAM_ASSET = "program\.vm1"/);
assert.match(nativeBuild, /compileCodynexC0Project/);
assert.match(nativeBuild, /buildCodynexAppBinaryManifest/);
assert.match(nativeBuild, /hostContainsAppSemantics", false/);
assert.match(nativeBuild, /appSemantics", "assets\/program\.vm1"/);
assert.match(installer, /CODYNEX_APP_TARGET_PACKAGE = "com\.codynex\.notepad"/);
assert.match(installer, /com\.codynex\.apphost\.CodynexAppActivity/);
assert.match(codynexAppActivity, /TEMP LIVE-PROOF generic Codynex app host/);
assert.match(codynexAppActivity, /MUST be replaced by native Codynex\/\.cx/);
assert.match(codynexAppActivity, /Vm1Bridge\.run/);
assert.match(codynexAppActivity, /PROGRAM_ASSET = "program\.vm1"/);
assert.match(codynexAppActivity, /VM_ASSET = "vm1_seed\.hex"/);
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
assert.match(editorApkBuilder, /class CodynexApkBuilder/);
assert.match(editorApkBuilder, /context\.applicationInfo\.sourceDir/);
assert.match(editorApkBuilder, /CodynexApkV2Signer/);
assert.match(editorApkBuilder, /packAuthority", "Codynex"/);
assert.match(editorApkBuilder, /signAuthority", "Codynex"/);
assert.match(editorApkBuilder, /runtimeDependency", "none"/);
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
assert.match(editorToolchain, /compile-c0/);
assert.match(editorToolchain, /compile-c0-project/);
assert.match(editorToolchain, /MAX_PROJECT_BYTES = 1024 \* 1024/);
assert.match(editorToolchain, /MAX_PROJECT_MODULES = 64/);
assert.match(editorToolchain, /Preview passed: VM1 result/);
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
assert.match(nativeBuild, /codynex-c0-ref\/0\.12\.0/);
assert.match(nativeShellServices, /codynex-c0-ref\/0\.11\.0\|0\.12\.0 transition/);
assert.match(codynexProvider, /9874e844c24fe92c65908ce9b3cfb192f87774984a9e4fc600d883badcbe19b5/);
assert.match(codynexProvider, /MAX_SOURCE_BYTES = 256 \* 1024/);
assert.match(codynexProvider, /METHOD_COMPILE_PROJECT = "compile-c0-project"/);
assert.match(codynexProvider, /MAX_PROJECT_BYTES = 1024 \* 1024/);
assert.match(codynexProvider, /MAX_PROJECT_MODULES = 64/);
assert.match(codynexProvider, /compileCodynexC0Project/);
assert.match(codynexProvider, /MAX_VM1_BYTES = 64 \* 1024/);
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
assert.match(shell, /riftbuild doctor\|validate\|plan\|toolchain-status\|toolchain-install-bundled\|compile-native\|prepare-native-app\|prepare-riftpp-v0\|prepare-riftpp-seed0-arm64\|prepare-riftpp-app0\|prepare-codynex-mc0\|prepare-codynex-mc1a\|prepare-codynex-mc1b\|prepare-codynex-m2-vm0\|prepare-codynex-m2b\|prepare-codynex-mc2a\|prepare-codynex-editor\|prepare-codynex-app\|pack\|sign\|verify\|install-proof\|install-status\|launch-proof\|runs\|artifacts/);

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
