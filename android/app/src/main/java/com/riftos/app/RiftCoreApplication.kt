package com.riftos.app

import android.app.Application
import android.os.Build
import java.io.File

/**
 * Minimal Android entry for RiftOS Core; the desktop is a client and must not
 * be needed for Core initialization.
 */
class RiftCoreApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android runs Application.onCreate in every declared remote service
        // process. Only the main OS process owns the Core singleton.
        val currentProcess = if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            runCatching {
                File("/proc/self/cmdline").inputStream().use { stream ->
                    val bytes = ByteArray(256)
                    val size = stream.read(bytes)
                    if (size <= 0) "" else String(bytes, 0, size, Charsets.UTF_8)
                        .substringBefore('\u0000')
                }
            }.getOrDefault("")
        }
        if (currentProcess == applicationInfo.processName) {
            // C1.4-C1: reconcile any interrupted isolated C: canary before
            // accepting new Core admin effects. Failure leaves that effect
            // fail-closed but does not destroy unrelated Core/RAPP services.
            runCatching { RiftCoreAdminRollbackProof.recover(this) }
                .onFailure { android.util.Log.e("RiftCoreAdmin", "Rollback recovery failed", it) }
            RiftCoreRuntime.initialize(this)
            RiftCoreShellRecovery.initialize(this)
            // C1.3-D: MCP/relay belongs to the default Core Android process.
            RiftMcpRuntime.relayClient(this).start()
        } else if (currentProcess == packageName + ":riftShell" && Build.VERSION.SDK_INT >= 28) {
            // Android WebView requires its own browser data directory per
            // process; otherwise moving the real browser into RiftShell may
            // fail due to the Core/browser helper process holding the lock.
            RiftBrowserWindow.prepareRemoteShellWebViewDirectory()
        }
    }
}
