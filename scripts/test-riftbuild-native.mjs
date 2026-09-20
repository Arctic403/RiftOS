import fs from 'node:fs';
import assert from 'node:assert/strict';

const read = file => fs.readFileSync(file, 'utf8');
const exists = file => fs.existsSync(file);

const nativeBuildPath = 'android/app/src/main/java/com/riftos/app/RiftBuildLocalExecutor.kt';
const signerPath = 'android/app/src/main/java/com/riftos/app/RiftApkV2Signer.kt';
const installerPath = 'android/app/src/main/java/com/riftos/app/RiftBuildInstaller.kt';
for (const file of [nativeBuildPath, signerPath, installerPath]) {
  assert.ok(exists(file), 'RiftBuild source owner is missing: ' + file);
}

const nativeBuild = read(nativeBuildPath);
const signer = read(signerPath);
const installer = read(installerPath);
const shell = read('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt');
const appHost = read('android/app/src/main/java/com/riftos/app/RiftBrowserAppHost.kt');
const gradle = read('android/app/build.gradle.kts');
const cmake = read('android/app/src/main/cpp/CMakeLists.txt');
const manifest = read('android/app/src/main/AndroidManifest.xml');
const retained = read('src/riftbuild.js');
const toolHost = read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const surfaces = read('docs/PUBLIC_SURFACES.md');

for (const required of [
  'class RiftBuildLocalExecutor',
  'system/riftbuild/v1/runs',
  'documents/builds',
  'workspaceRoot',
  'preparedArtifactPackagerReady',
  'prepared-native-proof',
  'riftpp-direct-elf-shared-v0-bytes/1',
  'DIRECT-ELF-SHARED-V0-BYTES.json',
  'prepareRiftppV0',
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
  'MC0_TARGET_PACKAGE = "com.codynex.mc0proof"',
  'MC1A_TARGET_PACKAGE = "com.codynex.mc1aproof"',
  'MC1B_TARGET_PACKAGE = "com.codynex.mc1bproof"',
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
  'PendingIntent.getActivity(',
  'launchForeground(context, confirmIntent)',
  'class RiftBuildInstallReceiver : BroadcastReceiver()',
]) assert.ok(installer.includes(required), 'RiftBuild installer contract missing: ' + required);

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
assert.match(nativeBuild, /\.put\("signed", false\)/);
assert.match(nativeBuild, /\.put\("installableClaimed", false\)/);

assert.match(shell, /private val riftBuild = RiftBuildLocalExecutor\(appContext\)/);
assert.match(shell, /"riftbuild" ->/);
assert.match(shell, /riftbuild doctor\|validate\|plan\|prepare-riftpp-v0\|prepare-codynex-mc0\|prepare-codynex-mc1a\|prepare-codynex-mc1b\|prepare-codynex-m2-vm0\|pack\|sign\|verify\|install-proof\|install-status\|launch-proof\|runs\|artifacts/);

assert.match(appHost, /"build\.doctor" -> withCapability\(instance, id, "build\.local"\)/);
assert.match(appHost, /"build\.prepare" -> withCapability\(instance, id, "build\.local"\) \{ riftBuild\.prepare\(args\) \}/);
assert.match(appHost, /"build\.submit" -> withCapability\(instance, id, "build\.local"\) \{ riftBuild\.submit\(args\) \}/);
assert.match(appHost, /private val riftBuild = RiftBuildLocalExecutor\(activity\.applicationContext\)/);

for (const source of ['RiftBoundedAsync.kt', 'RiftBuildLocalExecutor.kt', 'RiftApkV2Signer.kt', 'RiftBuildInstaller.kt']) {
  assert.ok(gradle.includes('src/main/java/com/riftos/app/' + source), 'Gradle exact source snapshot omitted ' + source);
}
assert.ok(manifest.includes('android.permission.REQUEST_INSTALL_PACKAGES'), 'RiftOS manifest omitted REQUEST_INSTALL_PACKAGES');
assert.ok(manifest.includes('.RiftBuildInstallActivity'), 'RiftOS manifest omitted foreground RiftBuild install callback activity');
assert.ok(manifest.includes('.RiftBuildInstallReceiver'), 'RiftOS manifest omitted private RiftBuild install receiver');
assert.ok(manifest.includes('android.intent.action.PACKAGE_FIRST_LAUNCH'), 'RiftOS manifest omitted first-launch proof action');
assert.ok(manifest.includes('com.riftpp.nativeproof'), 'RiftOS manifest omitted Rift++ proof-package visibility');
assert.ok(manifest.includes('com.codynex.mc0proof'), 'RiftOS manifest omitted Codynex MC0 proof-package visibility');
assert.ok(manifest.includes('com.codynex.mc1aproof'), 'RiftOS manifest omitted Codynex MC1-A proof-package visibility');
assert.ok(manifest.includes('com.codynex.mc1bproof'), 'RiftOS manifest omitted Codynex MC1-B proof-package visibility');
assert.ok(manifest.includes('com.codynex.m2vm0proof'), 'RiftOS manifest omitted Codynex M2 VM0 proof-package visibility');
assert.ok(gradle.includes('src/main/cpp/mc0/codynex_mc0_host.cpp'), 'Gradle exact native source snapshot omitted MC0 host');
assert.ok(gradle.includes('src/main/cpp/mc1/codynex_mc1a_host.cpp'), 'Gradle exact native source snapshot omitted MC1-A host');
assert.ok(gradle.includes('src/main/cpp/mc1/codynex_mc1b_host.cpp'), 'Gradle exact native source snapshot omitted MC1-B host');
assert.ok(gradle.includes('src/main/cpp/m2/codynex_m2_vm0_host.cpp'), 'Gradle exact native source snapshot omitted M2 VM0 host');
assert.match(manifest, /android:name="\.RiftBuildInstallReceiver"[\s\S]*?android:exported="false"/);

assert.match(retained, /RiftBuild doctor blocked local execution/);
assert.ok(!gradle.includes('src/riftbuild.js'), 'retained JavaScript RiftBuild must remain unpackaged');
assert.ok(!toolHost.includes('rift_build'), 'RiftBuild must not expand the MCP catalog');
assert.match(surfaces, /Native RiftBuild/);
assert.match(surfaces, /RiftApkV2Signer\.kt/);
assert.match(surfaces, /RiftBuildInstaller\.kt/);

console.log('Native RiftBuild bounded prepare/package/v2-sign/verify/install-proof contract OK');
