import assert from 'node:assert/strict';
import fs from 'node:fs';

const root = new URL('../', import.meta.url);
const kotlin = name => fs.readFileSync(
  new URL('android/app/src/main/java/com/riftos/app/' + name, root), 'utf8');
const manifest = fs.readFileSync(new URL('android/app/src/main/AndroidManifest.xml', root), 'utf8');
const gradle = fs.readFileSync(new URL('android/app/build.gradle.kts', root), 'utf8');
const ownership = fs.readFileSync(new URL('docs/SOURCE_OWNERSHIP.md', root), 'utf8');
const app = kotlin('RiftCoreApplication.kt');
const activity = kotlin('RiftManualRecoveryActivity.kt');
const store = kotlin('RiftManualRecoveryStore.kt');
const installer = kotlin('RiftManualRecoveryInstaller.kt');
const protectedInstaller = kotlin('RiftProtectedComponentInstaller.kt');
const ledger = kotlin('RiftComponentReleaseLedger.kt');
const bootstrap = kotlin('RiftBootstrapComponentStore.kt');
const roadmap = fs.readFileSync(new URL('docs/systems/android-host/SIMPLE_BOOTSTRAP_RECOVERY_LOCK.md', root), 'utf8');

for (const file of ['RiftManualRecoveryActivity.kt', 'RiftManualRecoveryStore.kt',
                    'RiftManualRecoveryInstaller.kt']) {
  const source = 'android/app/src/main/java/com/riftos/app/' + file;
  assert.ok(gradle.includes('"src/main/java/com/riftos/app/' + file + '"'),
    'Recovery class missing from mandatory Gradle source inventory: ' + file);
  assert.equal(ownership.split('| `' + source + '` |').length - 1, 1,
    'Recovery source must have exactly one ownership entry: ' + source);
}
assert.match(manifest, /android:name="\.RiftManualRecoveryActivity"\s+android:process=":riftRecovery"\s+android:exported="true"/);
assert.match(manifest, /android:label="RiftOS Recovery"/);
const recoveryActivity = manifest.match(/<activity\s+android:name="\.RiftManualRecoveryActivity"[\s\S]*?<\/activity>/)?.[0];
assert.ok(recoveryActivity);
assert.match(recoveryActivity, /android\.intent\.action\.MAIN/);
assert.match(recoveryActivity, /android\.intent\.category\.LAUNCHER/);
const bypass = 'if (currentProcess == packageName + ":riftRecovery") return';
assert.ok(app.includes(bypass) && app.indexOf(bypass) < app.indexOf('RiftBootstrapHost.startCore(this)'),
  'Recovery must bypass Core/Shell bootstrap in its distinct Android process');

for (const code of [activity, store, installer]) for (const unsafe of [
  'RiftCoreRuntime.', 'RiftMcpRuntime.', 'RiftBootstrapHost.startCore(',
  'RiftCoreCandidateSwitch.selectAtBoot(', 'RiftShellCandidateSwitch.selectAtBoot('
]) assert.ok(!code.includes(unsafe), 'Recovery illegally loads Core or Shell: ' + unsafe);

for (const required of [
  'RiftManualRecoveryStore.status(this)', 'Refresh recovery status', 'Close Recovery',
  'AlertDialog.Builder(', 'Restore working Core backup', 'Restore working RiftShell backup',
  'Restore embedded Core', 'Restore embedded RiftShell', 'setNegativeButton("Cancel", null)',
  'RiftManualRecoveryStore.restore(this, component, selection)',
  'Intent.ACTION_OPEN_DOCUMENT', 'Intent.CATEGORY_OPENABLE',
  'Choose component.json', 'Choose matching component DEX', 'Install verified staged update',
  'RiftManualRecoveryInstaller.stage(this, manifest, it)',
  'RiftManualRecoveryInstaller.install(this@RiftManualRecoveryActivity, component, sha)',
  'ByteArrayOutputStream()', 'data.size() + count <= 32 * 1024'
]) assert.ok(activity.includes(required), 'Manual Recovery confirmation/install UI missing: ' + required);

for (const required of [
  'riftos.host.manual-recovery/1', 'riftos.host.working-backup/1', 'firstInstallFallback',
  'embedded-migration', 'RiftBootstrapComponentStore.active(context, component)',
  'AtomicFile(file).openRead()', 'file.isFile', '!file.canWrite()',
  'MessageDigest.getInstance("SHA-256")',
  'RiftProtectedRevisionRecovery.verifiedReceipt(context, component, candidate)',
  'RiftComponentReleaseLedger.checkManualRestoreTarget(context, component, sha)',
  'readBackup(context, component)?.let', 'backupError', 'activeError',
  'startupMarkerPresent', 'restoreWorkingBackupEnabled', 'restoreEmbeddedEnabled',
  'fun preserveAcceptedBeforeUpdate(context: Context, component: String)',
  'RiftComponentReleaseLedger.isAccepted(context, component, sha)',
  'fun restore(context: Context, component: String, selection: String)',
  'RiftBootstrapComponentStore.writeProtectedPointer(context, component, backup)',
  'RiftBootstrapComponentStore.resetCoreToEmbedded(context)',
  'RiftBootstrapComponentStore.resetShellToEmbedded(context)',
  'RiftComponentReleaseLedger.manualRestored(context, component, backup)',
  'RiftComponentReleaseLedger.manualRestored(context, component, null)'
]) assert.ok(store.includes(required), 'Recovery slot integrity or restore path missing: ' + required);

for (const required of [
  'Application.getProcessName() == context.packageName + ":riftRecovery"',
  'RiftProtectedComponentInstaller.stage(context, manifestBytes, dex)',
  'RiftProtectedComponentInstaller.activate(context, component, sha256)'
]) assert.ok(installer.includes(required), 'Independent APK installer bridge missing: ' + required);
assert.match(protectedInstaller, /RiftManualRecoveryStore\.preserveAcceptedBeforeUpdate\(context, component\)[\s\S]*?RiftComponentReleaseLedger\.recordPrepared/);
for (const required of [
  'fun checkManualRestoreTarget(context: Context, id: String, sha: String)',
  'state.optJSONObject("lastKnownGood")',
  'state.optJSONObject("previousKnownGood")',
  'fun manualRestored(context: Context, id: String, target: JSONObject?)',
  'target?.let { JSONObject(it.toString()) } ?: JSONObject.NULL',
  'current.optJSONObject("previousKnownGood")',
  'val retainedBackup = if (target == null)',
  'runCatching { read(context, id, verifyFiles = false) }.getOrNull()'
]) assert.ok(ledger.includes(required), 'Legacy ledger manual restore reconciliation missing: ' + required);

assert.match(bootstrap, /fun resetCoreToEmbedded\(context: Context\)/);
for (const step of ['Patch A', 'Patch B', 'Patch C']) assert.ok(roadmap.includes(step));
console.log('Manual Recovery Patch B source contracts PASS: independent APK process, trusted stage/activate, accepted backup snapshot and explicit manual restore. Physical restore/device acceptance NOT PROVEN.');
