package com.riftos.app

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Inert until explicitly started by future Core-authorized native proof UI.
 * The probe executes in its own Android process, never in production Core
 * or graphical :riftShell. Its startup cannot run RAPPs or create UI.
 */
class RiftBootstrapProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        require(Build.VERSION.SDK_INT < 28 ||
            Application.getProcessName() == packageName + ":riftBootstrapProbe") {
            "External bootstrap probe must run in isolated Android process"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Thread({
            try {
                RiftBootstrapHost.startProbe(application as Application)
            } catch (failure: Throwable) {
                Log.e("RiftBootstrapProbe", "Isolated probe failed; recovery on next explicit launch", failure)
            } finally {
                stopSelf(startId)
            }
        }, "rift-bootstrap-probe").start()
        return START_NOT_STICKY
    }
}
