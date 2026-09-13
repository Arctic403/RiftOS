package com.riftos.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Vortex3D-only local Android UI agent.
 *
 * Launching uses Android's normal package launch intent and does not require Accessibility.
 * UI inspection/actions require the user-enabled AccessibilityService, which is package-filtered
 * by Android to com.vortex3d.app and re-checks the foreground package before every action.
 */
object RiftVortexLocalAgent {
    private const val TARGET_PACKAGE = "com.vortex3d.app"
    private const val MAX_TREE_NODES = 1024
    private const val MAX_TEXT_CHARS = 4096
    private const val GESTURE_TIMEOUT_MS = 5_000L
    private const val ACTIVATION_TIMEOUT_MS = 3_000L
    private const val ACTIVATION_POLL_MS = 50L

    fun execute(context: Context, args: JSONObject): JSONObject {
        val op = args.optString("op").trim().lowercase()
        require(op.isNotBlank()) { "vortex.agent op is required" }
        return when (op) {
            "status" -> status(context)
            "open" -> open(context)
            "tree" -> tree(context, args)
            "click" -> click(context, args)
            "tap" -> tap(context, args)
            "swipe" -> swipe(context, args)
            "type" -> type(context, args)
            "back" -> back(context)
            else -> throw IllegalArgumentException("Unsupported Vortex local-agent operation: $op")
        }
    }

    private fun status(context: Context): JSONObject {
        val installed = runCatching { context.packageManager.getApplicationInfo(TARGET_PACKAGE, 0) }.isSuccess
        val service = RiftVortexAccessibilityService.current()
        val activePackage = service?.rootInActiveWindow?.packageName?.toString().orEmpty()
        return JSONObject()
            .put("scope", TARGET_PACKAGE)
            .put("installed", installed)
            .put("accessibility_connected", service != null)
            .put("active_package", activePackage)
            .put("vortex_foreground", activePackage == TARGET_PACKAGE)
            .put("launch_available", installed)
            .put("ui_actions_available", installed && service != null)
            .put("actions_auto_activate", installed && service != null)
    }

    private fun open(context: Context): JSONObject {
        val launch = context.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
            ?: throw IllegalStateException("Vortex3D is not installed: $TARGET_PACKAGE")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        context.startActivity(launch)
        return JSONObject()
            .put("scope", TARGET_PACKAGE)
            .put("launched", true)
            .put("accessibility_connected", RiftVortexAccessibilityService.current() != null)
    }

    private fun tree(context: Context, args: JSONObject): JSONObject {
        val root = requireVortexRoot(context)
        val limit = args.optInt("limit", 256).coerceIn(1, MAX_TREE_NODES)
        val rows = JSONArray()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var total = 0
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (node.packageName?.toString() != TARGET_PACKAGE) continue
            total++
            if (rows.length() < limit) rows.put(nodeJson(node, depth))
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> queue.add(child to depth + 1) }
            }
        }
        return JSONObject()
            .put("scope", TARGET_PACKAGE)
            .put("nodes", rows)
            .put("total_nodes", total)
            .put("truncated", total > limit)
    }

    private fun click(context: Context, args: JSONObject): JSONObject {
        val target = requiredTarget(args)
        val node = findNode(requireVortexRoot(context), target)
            ?: throw IllegalArgumentException("Vortex UI target not found: $target")
        rejectPassword(node)
        var actionNode: AccessibilityNodeInfo? = node
        while (actionNode != null && !actionNode.isClickable) {
            val parent = actionNode.parent
            if (parent?.packageName?.toString() != TARGET_PACKAGE) break
            actionNode = parent
        }
        val clicked = actionNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (!clicked) throw IllegalStateException("Vortex UI target is not clickable: $target")
        return JSONObject()
            .put("scope", TARGET_PACKAGE)
            .put("clicked", true)
            .put("target", target)
            .put("node", nodeJson(node, 0))
    }

    private fun tap(context: Context, args: JSONObject): JSONObject {
        val service = requireVortexServiceAndRoot(context).first
        val x = finite(args, "x")
        val y = finite(args, "y")
        requirePoint(service, x, y)
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(service, path, 80L)
        return JSONObject().put("scope", TARGET_PACKAGE).put("tapped", true).put("x", x).put("y", y)
    }

    private fun swipe(context: Context, args: JSONObject): JSONObject {
        val service = requireVortexServiceAndRoot(context).first
        val x1 = finite(args, "x1")
        val y1 = finite(args, "y1")
        val x2 = finite(args, "x2")
        val y2 = finite(args, "y2")
        requirePoint(service, x1, y1)
        requirePoint(service, x2, y2)
        val duration = args.optLong("durationMs", 350L).coerceIn(50L, 3_000L)
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(service, path, duration)
        return JSONObject()
            .put("scope", TARGET_PACKAGE)
            .put("swiped", true)
            .put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2)
            .put("duration_ms", duration)
    }

    private fun type(context: Context, args: JSONObject): JSONObject {
        val target = requiredTarget(args)
        val text = args.optString("text")
        require(text.length <= MAX_TEXT_CHARS) { "Vortex agent text exceeds $MAX_TEXT_CHARS characters" }
        val node = findNode(requireVortexRoot(context), target)
            ?: throw IllegalArgumentException("Vortex text target not found: $target")
        rejectPassword(node)
        require(node.isEditable) { "Vortex target is not editable: $target" }
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        if (!ok) throw IllegalStateException("Android rejected text input for Vortex target: $target")
        return JSONObject().put("scope", TARGET_PACKAGE).put("typed", true).put("target", target).put("characters", text.length)
    }

    private fun back(context: Context): JSONObject {
        val (service, _) = requireVortexServiceAndRoot(context)
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        if (!ok) throw IllegalStateException("Android rejected Back while Vortex3D was foreground")
        return JSONObject().put("scope", TARGET_PACKAGE).put("back", true)
    }

    /** Keep the one hard-coded Vortex target foreground for a native bridge session. */
    internal fun ensureActiveForSession(context: Context) {
        requireVortexServiceAndRoot(context)
    }

    private fun requireVortexRoot(context: Context): AccessibilityNodeInfo = requireVortexServiceAndRoot(context).second

    private fun requireVortexServiceAndRoot(context: Context): Pair<RiftVortexAccessibilityService, AccessibilityNodeInfo> {
        val service = RiftVortexAccessibilityService.current()
            ?: throw IllegalStateException("RiftOS Vortex Agent accessibility service is not enabled")
        val current = service.rootInActiveWindow
        if (current?.packageName?.toString() == TARGET_PACKAGE) return service to current

        // MCP/ChatGPT can become foreground again between separate tool calls. Activate the one hard-coded
        // target and finish the UI operation inside this same native call, then re-check the package before touch.
        val launch = context.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
            ?: throw IllegalStateException("Vortex3D is not installed: $TARGET_PACKAGE")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        context.startActivity(launch)

        val deadline = SystemClock.elapsedRealtime() + ACTIVATION_TIMEOUT_MS
        var lastPackage = current?.packageName?.toString().orEmpty()
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(ACTIVATION_POLL_MS)
            val root = service.rootInActiveWindow
            lastPackage = root?.packageName?.toString().orEmpty()
            if (lastPackage == TARGET_PACKAGE && root != null) return service to root
        }
        throw IllegalStateException(
            "Vortex Agent could not activate $TARGET_PACKAGE; last foreground package was ${lastPackage.ifBlank { "(none)" }}"
        )
    }

    private fun findNode(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.packageName?.toString() != TARGET_PACKAGE) continue
            val text = if (node.isPassword) "" else node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            if (target == text || target == description || target == viewId) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return null
    }

    private fun nodeJson(node: AccessibilityNodeInfo, depth: Int): JSONObject {
        val bounds = Rect().also(node::getBoundsInScreen)
        val out = JSONObject()
            .put("depth", depth)
            .put("class", node.className?.toString().orEmpty())
            .put("view_id", node.viewIdResourceName.orEmpty())
            .put("content_description", node.contentDescription?.toString().orEmpty())
            .put("password", node.isPassword)
            .put("clickable", node.isClickable)
            .put("editable", node.isEditable)
            .put("enabled", node.isEnabled)
            .put("visible", node.isVisibleToUser)
            .put("bounds", JSONObject().put("left", bounds.left).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom))
        out.put("text", if (node.isPassword) JSONObject.NULL else node.text?.toString().orEmpty())
        return out
    }

    private fun rejectPassword(node: AccessibilityNodeInfo) {
        require(!node.isPassword) { "Password fields are not available to the Vortex local agent" }
    }

    private fun requiredTarget(args: JSONObject): String {
        val target = args.optString("target").trim()
        require(target.isNotEmpty()) { "target is required" }
        require(target.length <= 256) { "target is too long" }
        return target
    }

    private fun finite(args: JSONObject, key: String): Float {
        require(args.has(key)) { "$key is required" }
        val value = args.getDouble(key)
        require(value.isFinite()) { "$key must be finite" }
        return value.toFloat()
    }

    private fun requirePoint(service: AccessibilityService, x: Float, y: Float) {
        val metrics = service.resources.displayMetrics
        require(x >= 0f && y >= 0f && x < metrics.widthPixels && y < metrics.heightPixels) {
            "gesture point is outside the local display"
        }
    }

    private fun dispatchGesture(service: AccessibilityService, path: Path, durationMs: Long) {
        val latch = CountDownLatch(1)
        var completed = false
        var cancelled = false
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                cancelled = true
                latch.countDown()
            }
        }, null)
        require(accepted) { "Android rejected the Vortex gesture" }
        require(latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timed out waiting for the Vortex gesture" }
        require(completed && !cancelled) { "Vortex gesture was cancelled" }
    }
}

class RiftVortexAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var live: RiftVortexAccessibilityService? = null
        fun current(): RiftVortexAccessibilityService? = live
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        live = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (live === this) live = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (live === this) live = null
        super.onDestroy()
    }
}
