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
        // This separate APK Recovery process must never initialize either
        // selected Core or graphical Shell. It can open after a bad Core update.
        if (currentProcess == packageName + ":riftRecovery") return
        if (currentProcess == applicationInfo.processName) {
            // The APK enters the selected Core bootstrap component. The
            // Only the qualified external Core is selected; host Recovery ordering stays native.
            RiftBootstrapHost.startCore(this)
        } else if (currentProcess == packageName + ":riftShell" && Build.VERSION.SDK_INT >= 28) {
            // Install the durable Shell fatal observer before WebView setup,
            // candidate DEX loading, or graphical Activity lifecycle.
            RiftShellCrashDiagnostics.install(this)
            // Android WebView requires its own browser data directory per
            // process; otherwise moving the real browser into RiftShell may
            // fail due to the Core/browser helper process holding the lock.
            RiftBootstrapHost.prepareShell(this)
        }
    }
}
