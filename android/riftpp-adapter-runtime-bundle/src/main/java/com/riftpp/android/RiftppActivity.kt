package com.riftpp.android

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Frozen Android platform adapter for Rift++ applications.
 *
 * Android owns only Activity/Surface lifecycle and managed platform delivery.
 * Rift++ owns runtime state, rendering, input semantics and application logic.
 */
class RiftppActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        private const val LIBRARY_META = "android.app.lib_name"

        private const val LIFECYCLE_CREATE = 1
        private const val LIFECYCLE_START = 2
        private const val LIFECYCLE_RESUME = 3
        private const val LIFECYCLE_PAUSE = 4
        private const val LIFECYCLE_STOP = 5
        private const val LIFECYCLE_DESTROY = 6

        private const val SURFACE_CREATED = 1
        private const val SURFACE_CHANGED = 2
        private const val SURFACE_DESTROYED = 3
    }

    private lateinit var surfaceView: SurfaceView
    private var nativeLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val info = packageManager.getActivityInfo(
            componentName,
            PackageManager.GET_META_DATA
        )
        val library = info.metaData
            ?.getString(LIBRARY_META)
            ?.trim()
            .orEmpty()

        require(library.matches(Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$"))) {
            "Rift++ native library metadata is missing or invalid"
        }

        System.loadLibrary(library)
        nativeLoaded = true

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        setContentView(surfaceView)

        nativeLifecycle(LIFECYCLE_CREATE)
    }

    override fun onStart() {
        super.onStart()
        if (nativeLoaded) nativeLifecycle(LIFECYCLE_START)
    }

    override fun onResume() {
        super.onResume()
        if (nativeLoaded) nativeLifecycle(LIFECYCLE_RESUME)
    }

    override fun onPause() {
        if (nativeLoaded) nativeLifecycle(LIFECYCLE_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        if (nativeLoaded) nativeLifecycle(LIFECYCLE_STOP)
        super.onStop()
    }

    override fun onDestroy() {
        if (nativeLoaded) nativeLifecycle(LIFECYCLE_DESTROY)
        if (::surfaceView.isInitialized) {
            surfaceView.holder.removeCallback(this)
        }
        nativeLoaded = false
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (nativeLoaded) {
            nativeSurface(
                holder.surface,
                surfaceView.width,
                surfaceView.height,
                SURFACE_CREATED
            )
        }
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        if (nativeLoaded) {
            nativeSurface(
                holder.surface,
                width,
                height,
                SURFACE_CHANGED
            )
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (nativeLoaded) {
            nativeSurface(
                null,
                0,
                0,
                SURFACE_DESTROYED
            )
        }
    }

    private external fun nativeLifecycle(event: Int)

    private external fun nativeSurface(
        surface: Surface?,
        width: Int,
        height: Int,
        event: Int
    )
}
