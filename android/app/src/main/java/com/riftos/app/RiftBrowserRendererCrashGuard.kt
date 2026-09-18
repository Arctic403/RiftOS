package com.riftos.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-safe renderer crash containment and diagnostics for every RiftOS WebView surface.
 *
 * A Chromium renderer may be shared by several WebView instances. Every affected WebView
 * must report the renderer loss as handled or Android may terminate the RiftOS app process.
 * This helper records a privacy-limited ring buffer and destroys only the dead browser-owned
 * renderer surface. Native desktop/activity lifecycle is never tied to Chromium recovery.
 */
object RiftBrowserRendererCrashGuard {
    private const val PREFS = "rift-renderer-crash-guard"
    private const val EVENTS_KEY = "renderer-events"
    private const val MAX_EVENTS = 16
    private const val MAX_EVENT_STORE_BYTES = 32 * 1024
    fun record(context: Context, surface: String, detail: RenderProcessGoneDetail): JSONObject {
        val event = JSONObject()
            .put("surface", surface.take(48))
            .put("didCrash", detail.didCrash())
            .put("rendererPriorityAtExit", detail.rendererPriorityAtExit())
            .put("atEpochMs", System.currentTimeMillis())
            .put("processUptimeMs", currentProcessUptimeMs())

        synchronized(this) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(EVENTS_KEY, "[]") ?: "[]"
            val previous = if (raw.toByteArray(Charsets.UTF_8).size <= MAX_EVENT_STORE_BYTES) {
                runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
            } else JSONArray()
            val next = JSONArray()
            val start = (previous.length() - (MAX_EVENTS - 1)).coerceAtLeast(0)
            for (index in start until previous.length()) next.put(previous.opt(index))
            next.put(event)
            prefs.edit().putString(EVENTS_KEY, next.toString()).apply()
        }
        return event
    }

    fun recent(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EVENTS_KEY, "[]") ?: "[]"
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_EVENT_STORE_BYTES) return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    fun destroyDeadWebView(view: WebView) {
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.removeAllViews() }
        runCatching { view.destroy() }
    }

    fun currentProcessUptimeMs(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()).coerceAtLeast(0L)
        } else 0L

    fun historicalProcessExits(context: Context, limit: Int = 8): JSONArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return JSONArray()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val rows = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, limit.coerceIn(1, 16))
        }.getOrElse { emptyList() }
        return JSONArray().apply {
            for (row in rows) put(JSONObject()
                .put("timestamp", row.timestamp)
                .put("reason", row.reason)
                .put("reasonName", reasonName(row.reason))
                .put("status", row.status)
                .put("importance", row.importance)
                .put("pssKb", row.pss)
                .put("rssKb", row.rss))
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_CRASH -> "java-crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization-failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resource-usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        else -> "reason-$reason"
    }
}
