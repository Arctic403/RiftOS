package com.riftos.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android graphical envelope only. Desktop, icons, taskbar, windows and file
 * explorer are exclusively owned by the qualified external Shell component.
 * A missing or invalid component fails closed to user-manual Recovery.
 */
class RiftShellActivity : Activity() {
    companion object {
        const val EXTRA_OPEN_APP_ID = "riftos.shell.open-app-id"
        const val EXTRA_RECOVERY_RESTART = "riftos.shell.recovery-restart"
    }

    private var externalShell: RiftShellGraphicalComponentV1? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        require(android.os.Build.VERSION.SDK_INT < 28 ||
            android.app.Application.getProcessName() == packageName + ":riftShell") {
            "Graphical shell must run in :riftShell Android process"
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = 0xff0a0d12.toInt()
        window.navigationBarColor = 0xff0a0d12.toInt()
        val host = FrameLayout(this).apply { clipChildren = true; clipToPadding = true }
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            if (view.paddingLeft != safe.left || view.paddingTop != safe.top ||
                view.paddingRight != safe.right || view.paddingBottom != safe.bottom) {
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
            insets
        }

        // Verification and selected-version lookup never trigger automatic
        // rollback or instantiate an APK-owned embedded graphical desktop.
        val candidate = try {
            RiftShellCandidateSwitch.selectAtBoot(application)
                ?: error("No qualified external Shell selected; use manual Recovery")
        } catch (failure: Throwable) {
            RiftShellCrashDiagnostics.recordFailure(
                application, "external-shell-selection", failure)
            throw failure
        }
        try {
            val core = RiftShellCoreClient(applicationContext)
            val restore = runCatching { core.claimRecovery() }.getOrNull()
            setContentView(host)
            candidate.attach(
                this, host, RiftShellPlatformServicesAdapter(core, this), restore)
            RiftShellCandidateSwitch.reportAttached(application)
            externalShell = candidate
            ViewCompat.requestApplyInsets(host)
        } catch (failure: Throwable) {
            RiftShellCrashDiagnostics.recordFailure(
                application, "external-shell-attach", failure)
            runCatching { RiftShellCandidateSwitch.reportStopped(application) }
            runCatching { candidate.onDestroy() }
            // Neither automatically load another revision nor construct a
            // private embedded desktop. Manual Recovery owns the decision.
            throw failure
        }
    }

    override fun onResume() {
        super.onResume()
        externalShell?.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        externalShell?.onWindowFocusChanged(hasFocus)
    }

    override fun onPause() {
        externalShell?.onPause()
        super.onPause()
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        externalShell?.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Back navigation compatibility")
    override fun onBackPressed() {
        if (externalShell?.onBackPressed() == true) return
        super.onBackPressed()
    }

    override fun onDestroy() {
        val selected = externalShell
        externalShell = null
        if (selected != null) {
            runCatching { RiftShellCandidateSwitch.reportStopped(application) }
            runCatching { selected.onDestroy() }
        }
        super.onDestroy()
    }
}
