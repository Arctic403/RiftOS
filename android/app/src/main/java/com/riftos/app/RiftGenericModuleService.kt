package com.riftos.app

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import dalvik.system.DexClassLoader
import java.nio.file.Files

/**
 * One generic, nonexported Android host for a Core-approved trusted DEX.
 * THIS IS NOT AN UNTRUSTED-CODE SANDBOX: separate process, SAME APK UID.
 *
 * It reads Core's private activated slot (not caller-supplied intents/paths)
 * and always rechecks sealed DEX/manifest before calling the module entry.
 */
class RiftGenericModuleService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 28) {
            require(Application.getProcessName() ==
                packageName + ":riftModuleHost") {
                "Trusted module must not run inside Core or graphical Shell"
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Thread({
            try {
                val app = application as Application
                val record = RiftCoreModuleActivation.pendingHost(app)
                val id = record.getString("id")
                val revision = record.getString("manifestDigest")
                val manifest = RiftCoreModuleStore.staged(app, id, revision)
                val dex = RiftCoreModuleStore.file(app, id, revision)
                require(dex.isFile && !Files.isSymbolicLink(dex.toPath()) &&
                    !dex.canWrite()) { "Module DEX must be sealed" }
                val classname = manifest.entrypoint
                require(runCatching {
                    app.classLoader.loadClass(classname)
                }.isFailure) { "External module must not shadow APK entrypoints" }
                val loader = DexClassLoader(
                    dex.absolutePath,
                    app.codeCacheDir.absolutePath,
                    null,
                    app.classLoader
                )
                val entry = loader.loadClass(classname)
                require(RiftBootstrapEntry::class.java.isAssignableFrom(entry)) {
                    "Module does not implement the generic host ABI"
                }
                val module = entry.getDeclaredConstructor().newInstance()
                    as RiftBootstrapEntry
                module.start(app)
                val process = if (Build.VERSION.SDK_INT >= 28) {
                    Application.getProcessName()
                } else packageName + ":riftModuleHost"
                RiftCoreModuleActivation.complete(app, record, process)
                Log.i("RiftModuleHost", "Core-approved external module completed")
            } catch (failure: Throwable) {
                Log.e("RiftModuleHost", "External module startup failed", failure)
                // Fail closed: rollback active pointer if marker remains.
                runCatching { RiftCoreModuleActivation.rollback(application) }
                    .onFailure { Log.e("RiftModuleHost", "Recovery unavailable", it) }
            } finally {
                stopSelf(startId)
            }
        }, "rift-generic-module-host").start()
        return START_NOT_STICKY
    }
}
