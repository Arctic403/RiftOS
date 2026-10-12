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

// Graphical Shell fatal dumps are owned by APK host, not external Shell/Core,
// and must remain readable when the selected desktop never starts.
const shellCrash = kotlin('RiftShellCrashDiagnostics.kt');
const shellActivity = kotlin('RiftShellActivity.kt');
const shellRecovery = kotlin('RiftCoreShellRecovery.kt');
const coreRuntime = kotlin('RiftCoreRuntime.kt');
const nativeShell = kotlin('RiftNativeShell.kt');
assert.ok(gradle.includes('"src/main/java/com/riftos/app/RiftShellCrashDiagnostics.kt"'),
  'Crash recorder must be compiled into the permanent signed APK');
for (const required of [
  'riftos.shell.crash-diagnostics/1',
  'last-crash.json', 'last-exit.json',
  'Thread.setDefaultUncaughtExceptionHandler',
  'previous.uncaughtException(thread, error)',
  'lastCapturedFailure !== error',
  'Thread.getDefaultUncaughtExceptionHandler() ?: return',
  'traceReachedEof = true',
  'getHistoricalProcessExitReasons(',
  'it.processName == context.packageName + ":riftShell"',
  'fullNativeTombstoneGuaranteed", false',
  'automaticRollbackEnabled", false'
]) assert.ok(shellCrash.includes(required), 'Shell failure capture missing: ' + required);
assert.ok(app.includes('RiftShellCrashDiagnostics.install(this)'),
  'Crash observer must install in Shell Application before DEX boot');
assert.ok(shellActivity.includes('RiftShellCrashDiagnostics.recordFailure(application, "selected-dex-load", failure)') &&
          shellActivity.includes('RiftShellCrashDiagnostics.recordFailure(application, "external-shell-attach", failure)'),
  'Shell loader and attach exceptions must preserve their exact startup stage');
assert.ok(shellRecovery.includes('crashPollWorker.execute {') &&
          shellRecovery.includes('crashPollInFlight.compareAndSet(false, true)') &&
          shellRecovery.includes('RiftShellCrashDiagnostics.refreshHistoricalExit(context)'),
  'Independent Core must poll Android exit reports off heartbeat main thread');
assert.ok(coreRuntime.includes('.put("shellCrashDiagnostics", RiftShellCrashDiagnostics.status(context))') &&
          nativeShell.includes('"shell-crash" -> RiftShellCrashDiagnostics.report(appContext)'),
  'Headless Core must expose graphical Shell dump after desktop failure');
for (const forbidden of [
  'RiftManualRecoveryStore.restore(', 'writeProtectedPointer(',
  'resetShellToEmbedded(', 'RiftComponentReleaseLedger.acceptProven('
]) assert.ok(!shellCrash.includes(forbidden),
  'Crash recorder cannot automatically change user-owned component selection: ' + forbidden);

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
  'AlertDialog.Builder(', 'Restore saved Core backup', 'Restore saved RiftShell backup',
  'Save selected Core as recovery backup', 'Save selected RiftShell as recovery backup',
  'setPositiveButton("Save backup")', 'Restore embedded Core', 'Restore embedded RiftShell', 'setNegativeButton("Cancel", null)',
  'RiftManualRecoveryStore.restore(this, component, selection)',
  'Intent.ACTION_OPEN_DOCUMENT', 'Intent.CATEGORY_OPENABLE',
  'Choose component.json', 'Choose matching component DEX', 'Install verified staged update',
  'Stage verified LOCAL component pair',
  'RiftManualRecoveryInstaller.stageLocal(',
  '/D:/Builds/Components/core/trial',
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
  'readBackup(context, component)?.let', 'backupError', 'activeError',
  'startupMarkerPresent', 'restoreWorkingBackupEnabled', 'restoreEmbeddedEnabled',
  'saveBackupEnabled',
  'fun saveSelectedBackup(context: Context, component: String): JSONObject',
  'requireManualRecoveryProcess(context)',
  'verifyExternal(context, component, selected)',
  'writeBackup(context, component, selected)',
  'fun restore(context: Context, component: String, selection: String)',
  'RiftBootstrapComponentStore.writeProtectedPointer(context, component, backup)',
  'RiftBootstrapComponentStore.resetCoreToEmbedded(context)',
  'RiftBootstrapComponentStore.resetShellToEmbedded(context)',
]) assert.ok(store.includes(required), 'User-owned Recovery backup/restore integrity missing: ' + required);
for (const forbidden of [
  'preserveAcceptedBeforeUpdate(',
  'RiftComponentReleaseLedger.isAccepted(context, component, sha)',
  'RiftComponentReleaseLedger.checkManualRestoreTarget(context, component, sha)',
  'RiftComponentReleaseLedger.manualRestored(context, component, backup)',
  'RiftComponentReleaseLedger.manualRestored(context, component, null)',
]) assert.ok(!store.includes(forbidden),
  'User-owned Recovery must not depend on legacy accepted SHA journal: ' + forbidden);

for (const required of [
  'Application.getProcessName() == context.packageName + ":riftRecovery"',
  'RiftProtectedComponentInstaller.stage(context, manifestBytes, dex)',
  'RiftProtectedComponentInstaller.activate(context, component, sha256)',
  'fun stageLocal(context: Context, virtualDirectory: String)',
  'RiftVolumePaths.normalizeDisplay(virtualDirectory)',
  'val prefix = "/D:/Builds/Components/"',
  'RiftProtectedComponentManifest.parse(manifestBytes)',
  'RiftVolumePaths.resolveRelative(display)',
  'java.nio.file.Files.isSymbolicLink(candidate.toPath())',
  'dex.inputStream().use { stage(context, manifestBytes, it) }'
]) assert.ok(installer.includes(required), 'Independent APK installer bridge missing: ' + required);
assert.ok(protectedInstaller.includes(
  'RiftBootstrapComponentStore.writeProtectedPointer(context, component, pointer)'),
  'User-confirmed install must select only the protected next-boot pointer');
for (const forbidden of [
  'RiftManualRecoveryStore.preserveAcceptedBeforeUpdate(',
  'RiftComponentReleaseLedger.recordPrepared(',
  'RiftProtectedRevisionRecovery.rollback(',
]) assert.ok(!protectedInstaller.includes(forbidden),
  'Installer must not automatically replace backup, approve or roll back a selected version: ' + forbidden);
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
console.log('Manual Recovery source contracts PASS: independent APK process, SHA-verified user-saved backup, protected install and explicit restore; no automatic version changes. Physical Recovery/device behavior NOT YET PROVEN.');
