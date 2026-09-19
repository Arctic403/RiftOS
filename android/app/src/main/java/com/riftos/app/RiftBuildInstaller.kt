package com.riftos.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File

/**
 * User-confirmed PackageInstaller owner for the fixed RiftBuild bootstrap proof package.
 */
class RiftBuildInstaller(context: Context) {
    companion object {
        const val TARGET_PACKAGE = "com.riftpp.nativeproof"
        const val TARGET_ACTIVITY = "android.app.NativeActivity"
        const val ACTION_INSTALL_STATUS = "com.riftos.app.RIFTBUILD_INSTALL_STATUS"

        private fun statusFile(context: Context): File =
            File(context.applicationContext.filesDir, "riftfs/system/riftbuild/v1/install-status.json")

        private fun readStatus(context: Context): JSONObject {
            val file = statusFile(context)
            return if (file.isFile && file.length() <= 1024L * 1024L) {
                runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrElse { JSONObject() }
            } else JSONObject()
        }

        private fun writeStatus(context: Context, value: JSONObject) {
            val target = statusFile(context)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "." + target.name + ".tmp")
            temp.writeText(value.toString(2), Charsets.UTF_8)
            if (target.exists()) require(target.delete()) { "could not replace RiftBuild install status" }
            require(temp.renameTo(target)) { "could not publish RiftBuild install status" }
        }

        private fun launchExact(context: Context) {
            val intent = Intent()
                .setClassName(TARGET_PACKAGE, TARGET_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.applicationContext.startActivity(intent)
        }

        fun handleStatus(context: Context, intent: Intent) {
            val appContext = context.applicationContext

            if (intent.action == Intent.ACTION_PACKAGE_FIRST_LAUNCH) {
                val packageName = intent.data?.schemeSpecificPart.orEmpty()
                if (packageName == TARGET_PACKAGE) {
                    val current = readStatus(appContext)
                    current
                        .put("schema", "riftbuild-install-status-v1")
                        .put("package", TARGET_PACKAGE)
                        .put("state", "launch-proven")
                        .put("launchProven", true)
                        .put("launchProvenAt", System.currentTimeMillis())
                    writeStatus(appContext, current)
                }
                return
            }

            if (intent.action != ACTION_INSTALL_STATUS) return
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
            val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()
            val current = readStatus(appContext)
                .put("schema", "riftbuild-install-status-v1")
                .put("package", TARGET_PACKAGE)
                .put("sessionId", sessionId)
                .put("platformStatus", status)
                .put("platformMessage", message)
                .put("reportedPackage", packageName)
                .put("updatedAt", System.currentTimeMillis())

            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    current.put("state", "pending-user-action")
                    val confirmIntent = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    }
                    current.put("confirmationIntentPresent", confirmIntent != null)
                    writeStatus(appContext, current)
                    if (confirmIntent != null) {
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        appContext.startActivity(confirmIntent)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    current
                        .put("state", "installed-launch-requested")
                        .put("installed", true)
                        .put("installedAt", System.currentTimeMillis())
                        .put("launchRequested", true)
                    val launchError = runCatching { launchExact(appContext) }.exceptionOrNull()
                    if (launchError != null) {
                        current
                            .put("state", "installed-launch-failed")
                            .put("launchRequested", false)
                            .put("launchError", launchError.message ?: launchError.javaClass.simpleName)
                    }
                    writeStatus(appContext, current)
                }
                else -> {
                    current
                        .put("state", "install-failed")
                        .put("installed", false)
                    writeStatus(appContext, current)
                }
            }
        }
    }

    private val appContext = context.applicationContext

    fun requestInstallPermissionIfNeeded(): JSONObject {
        val allowed = appContext.packageManager.canRequestPackageInstalls()
        val result = JSONObject()
            .put("schema", "riftbuild-install-permission-v1")
            .put("allowed", allowed)
            .put("package", appContext.packageName)
        if (!allowed) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + appContext.packageName)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            result
                .put("state", "settings-opened")
                .put("message", "Enable Allow from this source for RiftOS, then retry riftbuild install-proof")
        } else {
            result.put("state", "ready")
        }
        return result
    }

    fun installProof(apk: File, verified: RiftApkV2Signer.VerifyResult): JSONObject {
        require(apk.isFile) { "signed proof APK is missing" }
        require(verified.apkSha256.isNotBlank()) { "signed proof APK must pass RiftBuild v2 verification first" }

        val packageName = archivePackageName(apk)
        require(packageName == TARGET_PACKAGE) {
            "RiftBuild V0 installer accepts only " + TARGET_PACKAGE + ", got " + packageName
        }

        if (!appContext.packageManager.canRequestPackageInstalls()) {
            return requestInstallPermissionIfNeeded()
                .put("verifiedApkSha256", verified.apkSha256)
                .put("certificateSha256", verified.certificateSha256)
        }

        val packageInstaller = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(TARGET_PACKAGE)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 26) setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
            }
        }

        val sessionId = packageInstaller.createSession(params)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().buffered().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }

                val callbackIntent = Intent(appContext, RiftBuildInstallReceiver::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
                val callback = PendingIntent.getBroadcast(appContext, sessionId, callbackIntent, flags)

                val status = JSONObject()
                    .put("schema", "riftbuild-install-status-v1")
                    .put("state", "committed-awaiting-result")
                    .put("package", TARGET_PACKAGE)
                    .put("sessionId", sessionId)
                    .put("artifact", apk.absolutePath)
                    .put("artifactSha256", verified.apkSha256)
                    .put("certificateSha256", verified.certificateSha256)
                    .put("launchProven", false)
                    .put("createdAt", System.currentTimeMillis())
                writeStatus(appContext, status)

                session.commit(callback.intentSender)
                return status
            }
        } catch (error: Throwable) {
            runCatching { packageInstaller.abandonSession(sessionId) }
            throw error
        }
    }

    fun status(): JSONObject {
        val current = readStatus(appContext)
        return if (current.length() == 0) {
            JSONObject()
                .put("schema", "riftbuild-install-status-v1")
                .put("state", "none")
                .put("package", TARGET_PACKAGE)
        } else current
    }

    fun launchProof(): JSONObject {
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.packageManager.getPackageInfo(
                    TARGET_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(TARGET_PACKAGE, 0)
            }
        }.getOrNull()
        require(packageInfo != null) { "Rift++ native proof package is not installed" }

        launchExact(appContext)
        val current = status()
            .put("schema", "riftbuild-install-status-v1")
            .put("state", "launch-requested")
            .put("package", TARGET_PACKAGE)
            .put("launchRequested", true)
            .put("launchRequestedAt", System.currentTimeMillis())
        writeStatus(appContext, current)
        return current
    }

    private fun archivePackageName(apk: File): String {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            appContext.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        }
        return info?.packageName.orEmpty()
    }
}

class RiftBuildInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RiftBuildInstaller.handleStatus(context, intent)
    }
}
