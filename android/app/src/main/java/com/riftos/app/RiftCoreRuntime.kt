package com.riftos.app

import android.content.Context
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject

/**
 * RiftOS Core process authority. Does not depend on any Activity, desktop,
 * RiftShell window, app renderer, or installed application presentation.
 *
 * Core owns generic package installation/registry, runtime-provider discovery,
 * and build/execution capabilities. The current RAPP rendering/lifecycle host
 * is still Activity-owned; moving its execution sessions out is a separate gate.
 *
 * This object survives Activity recreation within the Android app process.
 * It is NOT a separate protected OS process: Android process death still resets
 * in-memory authority. Never claim cross-process/shell-crash survival from this
 * first ownership gate alone.
 */
object RiftCoreRuntime {
    private const val STATUS_SCHEMA = "riftos.core.status/1"

    @Volatile private var application: Context? = null
    @Volatile private var initializedAt: Long = 0L
    @Volatile private var installedPackages: RiftRappManager? = null
    @Volatile private var runtimeRegistry: RiftExternalRuntimeProviders? = null
    @Volatile private var buildPlatform: RiftBuildPlatformTools? = null
    @Volatile private var appSessions: RiftCoreAppSessions? = null
    @Volatile private var appSurfaces: RiftCoreAppSurfaces? = null
    @Volatile private var coreExecutor: RiftCoreAppExecutor? = null
    @Volatile private var coreAppLifecycle: RiftCoreAppLifecycle? = null

    fun initialize(context: Context) {
        if (application != null) return
        synchronized(this) {
            if (application == null) {
                application = context.applicationContext
                initializedAt = SystemClock.elapsedRealtime()
            }
        }
    }

    fun packages(context: Context): RiftRappManager {
        initialize(context)
        installedPackages?.let { return it }
        return synchronized(this) {
            installedPackages ?: RiftRappManager(requireApplication()).also {
                installedPackages = it
            }
        }
    }

    fun runtimes(context: Context): RiftExternalRuntimeProviders {
        initialize(context)
        runtimeRegistry?.let { return it }
        return synchronized(this) {
            runtimeRegistry ?: RiftExternalRuntimeProviders(requireApplication()).also {
                runtimeRegistry = it
            }
        }
    }

    fun buildPlatform(context: Context): RiftBuildPlatformTools {
        initialize(context)
        buildPlatform?.let { return it }
        return synchronized(this) {
            buildPlatform ?: RiftBuildPlatformTools(requireApplication()).also {
                buildPlatform = it
            }
        }
    }

    fun surfaces(context: Context): RiftCoreAppSurfaces {
        initialize(context)
        appSurfaces?.let { return it }
        return synchronized(this) {
            appSurfaces ?: RiftCoreAppSurfaces().also { appSurfaces = it }
        }
    }

    fun sessions(context: Context): RiftCoreAppSessions {
        initialize(context)
        appSessions?.let { return it }
        return synchronized(this) {
            appSessions ?: RiftCoreAppSessions(surfaces(context)).also { appSessions = it }
        }
    }

    fun appExecutor(context: Context): RiftCoreAppExecutor {
        initialize(context)
        coreExecutor?.let { return it }
        return synchronized(this) {
            coreExecutor ?: RiftCoreAppExecutor(requireApplication()).also {
                coreExecutor = it
            }
        }
    }

    /** Core-owned app bootstrap; it does not construct or require a desktop. */
    fun lifecycle(context: Context): RiftCoreAppLifecycle {
        initialize(context)
        coreAppLifecycle?.let { return it }
        return synchronized(this) {
            coreAppLifecycle ?: RiftCoreAppLifecycle(requireApplication()).also {
                coreAppLifecycle = it
            }
        }
    }

    /** Read-only Core health probe; never opens an Activity or a shell window. */
    fun status(context: Context): JSONObject {
        initialize(context)
        val providers = runtimes(context).status()
        return JSONObject()
            .put("schema", STATUS_SCHEMA)
            .put("scope", "android-app-process")
            .put("pid", Process.myPid())
            .put("initializedAtElapsedMs", initializedAt)
            .put("uptimeMs", (SystemClock.elapsedRealtime() - initializedAt).coerceAtLeast(0L))
            .put("installedRappCount", packages(context).listInstalled().length())
            .put("runtimeProviderCount", providers.optInt("registered"))
            .put("runtimeProviderState", providers.optString("state"))
            .put("appSessions", sessions(context).summary())
            .put("inputFocus", sessions(context).focusStatus())
            .put("appSurfaces", surfaces(context).list())
            .put("coreApps", lifecycle(context).status())
            .put("desktopRequired", false)
            .put("shellRequired", false)
            .put("appExecutionIndependentOfDesktop", false)
            .put("separateCoreProcess", false)
    }

    private fun requireApplication(): Context =
        application ?: error("RiftOS Core application context unavailable")
}
