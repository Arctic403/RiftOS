import fs from 'node:fs';
import assert from 'node:assert/strict';

const read = file => fs.readFileSync(file, 'utf8');
const exists = file => fs.existsSync(file);

const nativeBuildPath = 'android/app/src/main/java/com/riftos/app/RiftBuildLocalExecutor.kt';
assert.ok(exists(nativeBuildPath), 'native RiftBuild owner is missing');

const nativeBuild = read(nativeBuildPath);
const shell = read('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt');
const appHost = read('android/app/src/main/java/com/riftos/app/RiftBrowserAppHost.kt');
const gradle = read('android/app/build.gradle.kts');
const retained = read('src/riftbuild.js');
const toolHost = read('android/app/src/main/java/com/riftos/app/RiftToolHost.kt');
const surfaces = read('docs/PUBLIC_SURFACES.md');

for (const required of [
  'class RiftBuildLocalExecutor',
  'system/riftbuild/v1/runs',
  'documents/builds',
  'workspaceRoot',
  'preparedArtifactPackagerReady',
  'direct-elf-shared-object',
  'riftpp-direct-elf-shared-v0-bytes/1',
  'DIRECT-ELF-SHARED-V0-BYTES.json',
  'prepareRiftppV0',
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
  'installableClaimed',
  'RiftBuild does not accept raw commands',
]) assert.ok(nativeBuild.includes(required), 'native RiftBuild contract missing: ' + required);

for (const forbidden of [
  'ProcessBuilder',
  'Runtime.getRuntime().exec',
  '.exec(',
  'rift-cli enable',
  'localBuildExecutor',
]) assert.ok(!nativeBuild.includes(forbidden), 'native RiftBuild gained forbidden authority: ' + forbidden);

assert.match(nativeBuild, /display == "\/D:\/Workspace"/);
assert.match(nativeBuild, /confinedTo\(workspaceRoot, file\)/);
assert.match(nativeBuild, /confinedTo\(artifactRoot, outDir\)/);
assert.match(nativeBuild, /type == XML_TYPE && headerSize == 8 && declaredSize == file\.length\(\)\.toInt\(\)/);
assert.match(nativeBuild, /writeManifestU32\(output, 1\)/);
assert.match(nativeBuild, /writeManifestU32\(output, XML_NO_INDEX\)/);
assert.match(nativeBuild, /\.put\("signed", false\)/);
assert.match(nativeBuild, /\.put\("installableClaimed", false\)/);

assert.match(shell, /private val riftBuild = RiftBuildLocalExecutor\(appContext\)/);
assert.match(shell, /"riftbuild" ->/);
assert.match(shell, /riftbuild doctor\|validate\|plan\|prepare-riftpp-v0\|pack\|runs\|artifacts/);

assert.match(appHost, /"build\.doctor" -> withCapability\(instance, id, "build\.local"\)/);
assert.match(appHost, /"build\.prepare" -> withCapability\(instance, id, "build\.local"\) \{ riftBuild\.prepare\(args\) \}/);
assert.match(appHost, /"build\.submit" -> withCapability\(instance, id, "build\.local"\) \{ riftBuild\.submit\(args\) \}/);
assert.match(appHost, /private val riftBuild = RiftBuildLocalExecutor\(activity\.applicationContext\)/);

assert.ok(gradle.includes('src/main/java/com/riftos/app/RiftBuildLocalExecutor.kt'), 'Gradle exact source snapshot omitted RiftBuild');
assert.match(retained, /RiftBuild doctor blocked local execution/);
assert.ok(!gradle.includes('src/riftbuild.js'), 'retained JavaScript RiftBuild must remain unpackaged');
assert.ok(!toolHost.includes('rift_build'), 'RiftBuild must not expand the MCP catalog');
assert.match(surfaces, /Native RiftBuild/);

console.log('Native RiftBuild bounded controller + prepared APK packager contract OK');
