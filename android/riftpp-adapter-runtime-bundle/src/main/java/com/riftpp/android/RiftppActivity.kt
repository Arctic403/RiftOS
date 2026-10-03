package com.riftpp.android

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

        private const val DIAGNOSTIC_PORT = 39771
        private const val DIAGNOSTIC_PACKET_BYTES = 32
        private const val DIAGNOSTIC_VERSION = 1
    }

    private lateinit var surfaceView: SurfaceView
    private var nativeLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        emitDiagnostic(100)

        val info = packageManager.getActivityInfo(
            componentName,
            PackageManager.GET_META_DATA
        )
        val library = info.metaData
            ?.getString(LIBRARY_META)
            ?.trim()
            .orEmpty()

        emitDiagnostic(110)
        require(library.matches(Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$"))) {
            "Rift++ native library metadata is missing or invalid"
        }

        emitDiagnostic(120)
        System.loadLibrary(library)
        nativeLoaded = true
        emitDiagnostic(121)

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        setContentView(surfaceView)
        emitDiagnostic(130)

        emitDiagnostic(140)
        nativeLifecycle(LIFECYCLE_CREATE)
        emitDiagnostic(141)
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
        emitDiagnostic(200)
        if (nativeLoaded) {
            emitDiagnostic(201)
            nativeSurface(
                holder.surface,
                surfaceView.width,
                surfaceView.height,
                SURFACE_CREATED
            )
            emitDiagnostic(202)
        }
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        emitDiagnostic(210)
        if (nativeLoaded) {
            emitDiagnostic(211)
            nativeSurface(
                holder.surface,
                width,
                height,
                SURFACE_CHANGED
            )
            emitDiagnostic(212)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        emitDiagnostic(220)
        if (nativeLoaded) {
            emitDiagnostic(221)
            nativeSurface(
                null,
                0,
                0,
                SURFACE_DESTROYED
            )
            emitDiagnostic(222)
        }
    }

    private fun emitDiagnostic(stage: Int) {
        runCatching {
            val payload = ByteBuffer
                .allocate(DIAGNOSTIC_PACKET_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put('R'.code.toByte())
                    put('D'.code.toByte())
                    put('B'.code.toByte())
                    put('G'.code.toByte())
                    putInt(DIAGNOSTIC_VERSION)
                    putInt(stage)
                    putInt(Process.myPid())
                    putInt(0)
                    putInt(0)
                    putInt(0)
                    putInt(0)
                }
                .array()

            DatagramSocket().use { socket ->
                socket.send(
                    DatagramPacket(
                        payload,
                        payload.size,
                        InetAddress.getByName("127.0.0.1"),
                        DIAGNOSTIC_PORT
                    )
                )
            }
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
