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
        const val TARGET_PACKAGE = "com.riftpp.nativeproof"
        const val MC0_TARGET_PACKAGE = "com.codynex.mc0proof"
        const val MC1A_TARGET_PACKAGE = "com.codynex.mc1aproof"
        const val MC1B_TARGET_PACKAGE = "com.codynex.mc1bproof"
        const val M2_VM0_TARGET_PACKAGE = "com.codynex.m2vm0proof"
        const val M2_B_TARGET_PACKAGE = "com.codynex.m2bproof"
        const val MC2_A_TARGET_PACKAGE = "com.codynex.mc2aproof"
        const val EDITOR_TARGET_PACKAGE = "com.codynex.editor"
        const val TARGET_ACTIVITY = "android.app.NativeActivity"
        const val EDITOR_TARGET_ACTIVITY = "com.codynex.editorapp.MainActivity"
        const val ACTION_INSTALL_STATUS = "com.riftos.app.RIFTBUILD_INSTALL_STATUS"

        private val ALLOWED_PROOF_PACKAGES = setOf(
            TARGET_PACKAGE,
            MC0_TARGET_PACKAGE,
            MC1A_TARGET_PACKAGE,
            MC1B_TARGET_PACKAGE,
            M2_VM0_TARGET_PACKAGE,
            M2_B_TARGET_PACKAGE,
            MC2_A_TARGET_PACKAGE,
            EDITOR_TARGET_PACKAGE
        )

        private fun statusFile(context: Context): File =
            File(
                context.applicationContext.filesDir,
                "riftfs/system/riftbuild/v1/install-status.json"
            )

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
            require(packageName in ALLOWED_PROOF_PACKAGES) {
                "RiftBuild proof package is not allowlisted: " + packageName
            }
            val activity = if (packageName == EDITOR_TARGET_PACKAGE) {
                EDITOR_TARGET_ACTIVITY
            } else {
                TARGET_ACTIVITY
            }
            launchForeground(
                context,
                Intent().setClassName(packageName, activity)
            )
        }

        fun handleStatus(context: Context, intent: Intent) {
            val appContext = context.applicationContext

            if (intent.action == Intent.ACTION_PACKAGE_FIRST_LAUNCH) {
                val packageName = intent.data?.schemeSpecificPart.orEmpty()
                if (packageName in ALLOWED_PROOF_PACKAGES) {
                    val current = readStatus(appContext)
                    val recorded = current.optString("package")
                    if (recorded.isNotBlank() && recorded != packageName) return
                    current
                        .put("schema", "riftbuild-install-status-v1")
                        .put("package", packageName)
                        .put("state", "launch-proven")
                        .put("launchProven", true)
                        .put("launchProvenAt", System.currentTimeMillis())
                    writeStatus(appContext, current)
                }
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
            val expectedPackage = current.optString("package")
            val packageName = when {
                reportedPackage in ALLOWED_PROOF_PACKAGES -> reportedPackage
                expectedPackage in ALLOWED_PROOF_PACKAGES -> expectedPackage
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

            when (platformStatus) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    updated.put("state", "pending-user-action")
                    val confirmIntent = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            Intent.EXTRA_INTENT,
                            Intent::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    }
                    updated.put(
                        "confirmationIntentPresent",
                        confirmIntent != null
                    )
                    writeStatus(appContext, updated)
                    if (confirmIntent != null) {
                        launchForeground(context, confirmIntent)
                    }
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    require(packageName in ALLOWED_PROOF_PACKAGES) {
                        "PackageInstaller reported an unexpected proof package"
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

        val packageName = archivePackageName(apk)
        require(packageName in ALLOWED_PROOF_PACKAGES) {
            "RiftBuild installer accepts only allowlisted proof packages, got " +
                packageName
        }

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
                    RiftBuildInstallActivity::class.java
                ).setAction(ACTION_INSTALL_STATUS)

                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) {
                    flags = flags or PendingIntent.FLAG_MUTABLE
                }
                val callback = PendingIntent.getActivity(
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
                .put("package", TARGET_PACKAGE)
        } else current
    }

    fun launchProof(): JSONObject {
        val current = status()
        val packageName = current
            .optString("package")
            .ifBlank { TARGET_PACKAGE }

        require(packageName in ALLOWED_PROOF_PACKAGES) {
            "Latest RiftBuild proof package is not allowlisted"
        }

        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(packageName, 0)
            }
        }.getOrNull()

        require(packageInfo != null) {
            "RiftBuild proof package is not installed: " + packageName
        }

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
