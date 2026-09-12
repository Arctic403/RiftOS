package com.riftos.app

import android.util.Log

/**
 * Local transport diagnostics only.
 * Does not connect to MCP or the Cloudflare relay.
 */
object RiftWebSocketDiagnostics {
    private const val TAG = "RiftWebSocket"

    fun started(name: String, host: String, port: Int) {
        Log.i(TAG, "STARTED name=$name host=$host port=$port")
    }

    fun stopped(name: String) {
        Log.i(TAG, "STOPPED name=$name")
    }

    fun error(name: String, error: Throwable) {
        Log.e(TAG, "ERROR name=$name message=${error.message}", error)
    }
}
