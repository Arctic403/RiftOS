package com.riftos.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import org.json.JSONObject
import java.io.File

/**
 * User-confirmed PackageInstaller owner for bounded RiftBuild native proof packages.
 */
class RiftBuildInstaller(context: Context) {
    companion object {
        const val ACTION_INSTALL_STATUS = "com.riftos.app.RIFTBUILD_INSTALL_STATUS"

        private val SAFE_PACKAGE_NAME =
            Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val pendingConfirmationLock = Any()
        @Volatile private var pendingConfirmationIntent: Intent? = null

        private fun requireSafePackageName(packageName: String): String {
            val normalized = packageName.trim()
            require(normalized.length in 3..255 && SAFE_PACKAGE_NAME.matches(normalized)) {
                "RiftBuild package name is invalid: $packageName"
            }
            return normalized
        }

        private fun statusFile(context: Context): File =
            File(
                context.applicationContext.filesDir,
                "riftfs/system/riftbuild/v1/install-status.json"
            )

        private fun retainPendingConfirmation(intent: Intent?) {
            synchronized(pendingConfirmationLock) {
                pendingConfirmationIntent = intent?.let { Intent(it) }
            }
        }

        internal fun resumePendingConfirmation(activity: Activity): Boolean {
            val pending = synchronized(pendingConfirmationLock) {
                pendingConfirmationIntent?.let { Intent(it) }?.also {
                    pendingConfirmationIntent = null
                }
            } ?: return false

            return runCatching {
                activity.startActivity(pending)
                true
            }.getOrElse {
                synchronized(pendingConfirmationLock) {
                    if (pendingConfirmationIntent == null) {
                        pendingConfirmationIntent = Intent(pending)
                    }
                }
                false
            }
        }

        private fun readStatus(context: Context): JSONObject {
            val file = statusFile(context)
            return if (file.isFile && file.length() <= 1024L * 1024L) {
                runCatching {
                    JSONObject(file.readText(Charsets.UTF_8))
                }.getOrElse { JSONObject() }
            } else JSONObject()
        }

        private fun writeStatus(context: Context, value: JSONObject) {
            val target = statusFile(context)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "." + target.name + ".tmp")
            temp.writeText(value.toString(2), Charsets.UTF_8)
            if (target.exists()) {
                require(target.delete()) {
                    "could not replace RiftBuild install status"
                }
            }
            require(temp.renameTo(target)) {
                "could not publish RiftBuild install status"
            }
        }

        private fun launchForeground(context: Context, intent: Intent) {
            if (context is Activity) {
                context.startActivity(intent)
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.applicationContext.startActivity(intent)
            }
        }

        private fun launchExact(context: Context, packageName: String) {
            val safePackageName = requireSafePackageName(packageName)
            val current = readStatus(context.applicationContext)
            require(current.optString("package") == safePackageName) {
                "RiftBuild launch package is not bound to the latest verified install"
            }

            val artifactSha256 = current
                .optString("artifactSha256")
                .takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }

            if (RiftAppDiagnosticBridge.supports(safePackageName)) {
                RiftAppDiagnosticBridge.beginLaunch(
                    context.applicationContext,
                    safePackageName,
                    artifactSha256
                )
            }

            val launchIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(safePackageName)
            launchForeground(context, launchIntent)
        }

        fun handleStatus(context: Context, intent: Intent) {
            val appContext = context.applicationContext

            if (intent.action == Intent.ACTION_PACKAGE_FIRST_LAUNCH) {
                val packageName = runCatching {
                    requireSafePackageName(intent.data?.schemeSpecificPart.orEmpty())
                }.getOrNull() ?: return
                val current = readStatus(appContext)
                if (current.optString("package") != packageName) return
                current
                    .put("schema", "riftbuild-install-status-v1")
                    .put("package", packageName)
                    .put("state", "launch-proven")
                    .put("launchProven", true)
                    .put("launchProvenAt", System.currentTimeMillis())
                writeStatus(appContext, current)
                return
            }

            if (intent.action != ACTION_INSTALL_STATUS) return

            val platformStatus = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
            )
            val message = intent
                .getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                .orEmpty()
            val sessionId = intent.getIntExtra(
                PackageInstaller.EXTRA_SESSION_ID,
                -1
            )
            val reportedPackage = intent
                .getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                .orEmpty()

            val current = readStatus(appContext)
            val expectedPackage = runCatching {
                requireSafePackageName(current.optString("package"))
            }.getOrNull().orEmpty()
            val reportedSafe = if (reportedPackage.isBlank()) {
                ""
            } else {
                runCatching { requireSafePackageName(reportedPackage) }
                    .getOrNull()
                    .orEmpty()
            }
            val packageName = when {
                expectedPackage.isBlank() -> ""
                reportedSafe.isBlank() -> expectedPackage
                reportedSafe == expectedPackage -> expectedPackage
                else -> ""
            }

            val updated = current
                .put("schema", "riftbuild-install-status-v1")
                .put("package", packageName)
                .put("sessionId", sessionId)
                .put("platformStatus", platformStatus)
                .put("platformMessage", message)
                .put("reportedPackage", reportedPackage)
                .put("updatedAt", System.currentTimeMillis())

            if (platformStatus != PackageInstaller.STATUS_PENDING_USER_ACTION) {
                retainPendingConfirmation(null)
            }

            when (platformStatus) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirmIntent = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            Intent.EXTRA_INTENT,
                            Intent::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    }
                    retainPendingConfirmation(confirmIntent)

                    val activeActivity = RiftMcpRuntime.activeActivity()
                        ?.takeIf { it.hasWindowFocus() }
                    val launchedFromForeground = if (activeActivity != null) {
                        resumePendingConfirmation(activeActivity)
                    } else {
                        false
                    }

                    updated
                        .put("state", "pending-user-action")
                        .put("confirmationIntentPresent", confirmIntent != null)
                        .put("confirmationRetainedInProcess", confirmIntent != null)
                        .put(
                            "confirmationLaunchState",
                            if (launchedFromForeground) {
                                "foreground-activity"
                            } else {
                                "awaiting-riftos-foreground"
                            }
                        )
                    writeStatus(appContext, updated)
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    require(packageName.isNotBlank() && packageName == expectedPackage) {
                        "PackageInstaller reported an unexpected package identity"
                    }
                    updated
                        .put("state", "installed-launch-requested")
                        .put("installed", true)
                        .put("installedAt", System.currentTimeMillis())
                        .put("launchRequested", true)

                    val launchError = runCatching {
                        launchExact(context, packageName)
                    }.exceptionOrNull()

                    if (launchError != null) {
                        updated
                            .put("state", "installed-launch-failed")
                            .put("launchRequested", false)
                            .put(
                                "launchError",
                                launchError.message
                                    ?: launchError.javaClass.simpleName
                            )
                    }
                    writeStatus(appContext, updated)
                }

                else -> {
                    updated
                        .put("state", "install-failed")
                        .put("installed", false)
                    writeStatus(appContext, updated)
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
                .put(
                    "message",
                    "Enable Allow from this source for RiftOS, then retry riftbuild install-proof"
                )
        } else {
            result.put("state", "ready")
        }
        return result
    }

    fun installProof(
        apk: File,
        verified: RiftApkV2Signer.VerifyResult
    ): JSONObject {
        require(apk.isFile) { "signed proof APK is missing" }
        require(verified.apkSha256.isNotBlank()) {
            "signed proof APK must pass RiftBuild v2 verification first"
        }

        val packageName = requireSafePackageName(archivePackageName(apk))

        if (!appContext.packageManager.canRequestPackageInstalls()) {
            return requestInstallPermissionIfNeeded()
                .put("proofPackage", packageName)
                .put("verifiedApkSha256", verified.apkSha256)
                .put("certificateSha256", verified.certificateSha256)
        }

        val packageInstaller = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 26) {
                setInstallReason(PackageManager.INSTALL_REASON_USER)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                )
            }
            if (Build.VERSION.SDK_INT >= 33) {
                setPackageSource(
                    PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
                )
            }
        }

        val sessionId = packageInstaller.createSession(params)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().buffered().use { input ->
                        input.copyTo(output)
                    }
                    session.fsync(output)
                }

                val callbackIntent = Intent(
                    appContext,
                    RiftBuildInstallReceiver::class.java
                ).setAction(ACTION_INSTALL_STATUS)

                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) {
                    flags = flags or PendingIntent.FLAG_MUTABLE
                }
                val callback = PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    callbackIntent,
                    flags
                )

                val status = JSONObject()
                    .put("schema", "riftbuild-install-status-v1")
                    .put("state", "committed-awaiting-result")
                    .put("package", packageName)
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
                .put("package", "")
        } else current
    }

    fun launchProof(): JSONObject {
        val current = status()
        val packageName = requireSafePackageName(current.optString("package"))
        launchExact(appContext, packageName)
        val updated = current
            .put("schema", "riftbuild-install-status-v1")
            .put("state", "launch-requested")
            .put("package", packageName)
            .put("launchRequested", true)
            .put("launchRequestedAt", System.currentTimeMillis())
        writeStatus(appContext, updated)
        return updated
    }

    private fun archivePackageName(apk: File): String {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            appContext.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                0
            )
        }
        return info?.packageName.orEmpty()
    }
}

class RiftBuildInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        RiftBuildInstaller.handleStatus(this, intent)
        finish()
    }
}

class RiftBuildInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RiftBuildInstaller.handleStatus(context, intent)
    }
}
