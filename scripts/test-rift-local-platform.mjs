import fs from 'node:fs';
import assert from 'node:assert/strict';

const read = file => fs.readFileSync(file, 'utf8');
const vault = read('src/riftvault.js');
const repo = read('src/riftrepo.js');
const memory = read('src/riftmemory-control.js');
const build = read('src/riftbuild.js');
const platform = read('src/riftlocal-platform.js');
const nativeShell = read('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt');
const nativeGit = read('android/app/src/main/java/com/riftos/app/RiftNativeGit.kt');
const devLab = read('android/app/src/main/java/com/riftos/app/RiftNativeDevLab.kt');
const surfaces = read('docs/PUBLIC_SURFACES.md');

assert.match(vault,/\/system\/riftvault\/v1/);
assert.match(vault,/objects\/sha256/);
assert.match(repo,/\/system\/riftrepo\/v1/);
assert.match(repo,/Working tree has local RiftRepo changes/);
assert.match(memory,/\/system\/riftmemory\/v1/);
assert.match(build,/RiftBuild doctor blocked local execution/);
assert.match(platform,/family==="repo"/);
assert.match(platform,/family==="vault"/);
assert.match(platform,/family==="build"/);
assert.match(platform,/family==="memory"/);

assert.match(nativeShell,/Legacy RiftLocalPlatform shell wrapper is retired/);
assert.match(nativeShell,/"git" -> nativeGit\.execute/);
assert.match(nativeShell,/"devlab" -> services\.devLab/);
assert.match(nativeGit,/class RiftNativeGit/);
assert.match(nativeGit,/GitHub tokens are entered only in native Settings/);
assert.match(devLab,/object RiftNativeDevLab/);
assert.equal(fs.existsSync('android/app/src/main/java/com/riftos/app/RiftNativeDispatcher.kt'), false, 'dead dispatcher must not return');

for (const retained of ['riftrepo.js','riftvault.js','riftbuild.js','riftmemory-control.js','riftlocal-platform.js']) {
  assert.ok(surfaces.includes(retained), `retained local-first source missing from public-surface classification: ${retained}`);
}
for (const liveName of ['RiftRepo','RiftVault','RiftBuild','RiftMemory','RiftLocalPlatform']) {
  assert.ok(!surfaces.includes(`| \`${liveName}\``), `retained local-first module must not be listed as a live public surface: ${liveName}`);
}
assert.equal(fs.existsSync('docs/RIFT_LOCAL_FIRST_REPO_VAULT_BUILD_ARCHITECTURE.md'), false, 'local-first architecture planning document must remain outside the RiftOS repo');
console.log('Rift local-first reference modules + native authority migration contract OK');
