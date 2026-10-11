import assert from 'node:assert/strict';
import fs from 'node:fs';

const root = new URL('../', import.meta.url);
const kotlin = name => fs.readFileSync(new URL('android/app/src/main/java/com/riftos/app/' + name, root), 'utf8');
const manifest = fs.readFileSync(new URL('android/app/src/main/AndroidManifest.xml', root), 'utf8');
const gradle = fs.readFileSync(new URL('android/app/build.gradle.kts', root), 'utf8');
const app = kotlin('RiftCoreApplication.kt');
const activity = kotlin('RiftManualRecoveryActivity.kt');
const store = kotlin('RiftManualRecoveryStore.kt');
const bootstrap = kotlin('RiftBootstrapComponentStore.kt');
const roadmap = fs.readFileSync(new URL('docs/systems/android-host/SIMPLE_BOOTSTRAP_RECOVERY_LOCK.md', root), 'utf8');

for (const file of ['RiftManualRecoveryActivity.kt', 'RiftManualRecoveryStore.kt']) {
  assert.ok(gradle.includes('"src/main/java/com/riftos/app/' + file + '"'),
    'Recovery source missing from exact Kotlin Gradle allowlist: ' + file);
}
assert.match(manifest, /android:name="\.RiftManualRecoveryActivity"\s+android:process=":riftRecovery"\s+android:exported="true"/);
assert.match(manifest, /android:label="RiftOS Recovery"/);
const recoveryActivity = manifest.match(/<activity\s+android:name="\.RiftManualRecoveryActivity"[\s\S]*?<\/activity>/)?.[0];
assert.ok(recoveryActivity);
assert.match(recoveryActivity, /android\.intent\.action\.MAIN/);
assert.match(recoveryActivity, /android\.intent\.category\.LAUNCHER/);
assert.ok(app.includes('if (currentProcess == packageName + ":riftRecovery") return') &&
  app.indexOf('if (currentProcess == packageName + ":riftRecovery") return') <
  app.indexOf('RiftBootstrapHost.startCore(this)'), 'Recovery must bypass Core bootstrap at Application startup');
assert.match(activity, /class RiftManualRecoveryActivity : Activity\(\)/);
assert.match(activity, /RiftManualRecoveryStore\.status\(this\)/);
assert.match(activity, /Refresh recovery status/);
assert.match(activity, /Close Recovery/);
for (const forbidden of [
  'RiftBootstrapHost.startCore(', 'RiftCoreRuntime.', 'RiftCoreCandidateSwitch.selectAtBoot(',
  'RiftShellCandidateSwitch.selectAtBoot(', 'RiftMcpRuntime.', 'writeProtectedPointer(',
  'resetCoreToEmbedded(', 'resetShellToEmbedded(', 'RiftProtectedComponentInstaller.activate('
]) {
  assert.ok(!activity.includes(forbidden) && !store.includes(forbidden),
    'Recovery Gate A must not select/load/alter Core or Shell: ' + forbidden);
}
for (const required of [
  'riftos.host.manual-recovery/1', 'riftos.host.working-backup/1',
  'firstInstallFallback', 'embedded-migration', '"restoreEnabled", false',
  'RiftBootstrapComponentStore.active(context, component)',
  'AtomicFile(file).openRead()', 'file.isFile', '!file.canWrite()',
  'MessageDigest.getInstance("SHA-256")',
  'RiftProtectedRevisionRecovery.verifiedReceipt(context, component, candidate)',
  'readBackup(context, component)?.let', 'backupError', 'activeError',
  'startupMarkerPresent'
]) assert.ok(store.includes(required), 'Backup integrity/first-install status missing: ' + required);
assert.match(bootstrap, /fun resetCoreToEmbedded\(context: Context\)/);
assert.match(roadmap, /Patch A/);
assert.match(roadmap, /Patch B/);
assert.match(roadmap, /Patch C/);
console.log('Patch A: independent Android recovery launcher, bootstrap skip, read-only bounded two-slot checks and embedded first-install compatibility SOURCE PASS (not signed APK proof)');
