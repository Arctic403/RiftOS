package com.riftos.app

import android.os.SystemClock
import org.json.JSONArray

/**
 * C1.3-D bounded Core-to-real-shell presentation requests.
 * Installed application execution has ALREADY begun in Core before enqueue.
 * Absence of a shell cannot prevent Core BOOT. Shell may consume only while
 * foreground; separate automatic shell start/recovery belongs to C1.3-E.
 */
object RiftCoreShellLaunchQueue {
    private const val CAPACITY = 32
    private const val TIMEOUT_MS = 60_000L
    private data class Request(val id: String, val issued: Long)
    private val queued = ArrayDeque<Request>()

    @Synchronized
    fun offer(id: String): Boolean {
        require(id.isNotBlank() && id.length <= 128) { "Shell launch ID invalid" }
        prune()
        if (queued.any { it.id == id }) return true
        if (queued.size >= CAPACITY) return false
        queued.addLast(Request(id, SystemClock.elapsedRealtime()))
        return true
    }

    @Synchronized
    fun drain(): JSONArray {
        prune()
        val result = JSONArray()
        while (queued.isNotEmpty() && result.length() < 8) {
            result.put(queued.removeFirst().id)
        }
        return result
    }

    private fun prune() {
        val now = SystemClock.elapsedRealtime()
        while (queued.isNotEmpty() &&
            now - queued.first().issued > TIMEOUT_MS) queued.removeFirst()
    }
}
