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

/** Fixed-scope local Android UI agents used only by trusted RiftShell commands. */
private class RiftScopedLocalAgent(
    private val targetPackage: String,
    private val displayName: String
) {
    companion object {
        private const val MAX_TREE_NODES = 1024
        private const val MAX_TEXT_CHARS = 4096
        private const val GESTURE_TIMEOUT_MS = 5_000L
        private const val ACTIVATION_TIMEOUT_MS = 3_000L
        private const val ACTIVATION_POLL_MS = 50L
        private const val ROOT_SETTLE_MS = 180L
        private const val ACTION_SETTLE_MS = 400L
        private const val TARGET_LOOKUP_TIMEOUT_MS = 1_000L
        private const val TARGET_LOOKUP_POLL_MS = 50L
        private const val REQUIRED_STABLE_ROOT_POLLS = 3
    }

    fun execute(context: Context, args: JSONObject): JSONObject {
        val op = args.optString("op").trim().lowercase()
        require(op.isNotBlank()) { "$displayName agent op is required" }
        return when (op) {
            "status" -> status(context)
            "open" -> open(context)
            "tree" -> tree(context, args)
            "click" -> click(context, args)
            "tap" -> tap(context, args)
            "swipe" -> swipe(context, args)
            "type" -> type(context, args)
            "back" -> back(context)
            else -> throw IllegalArgumentException("Unsupported $displayName local-agent operation: $op")
        }
    }

    fun ensureActive(context: Context) {
        requireTargetServiceAndRoot(context)
    }

    private fun status(context: Context): JSONObject {
        val installed = runCatching { context.packageManager.getApplicationInfo(targetPackage, 0) }.isSuccess
        val service = RiftVortexAccessibilityService.current()
        val activePackage = service?.rootInActiveWindow?.packageName?.toString().orEmpty()
        return JSONObject()
            .put("scope", targetPackage)
            .put("installed", installed)
            .put("accessibility_connected", service != null)
            .put("active_package", activePackage)
            .put("target_foreground", activePackage == targetPackage)
            .put("launch_available", installed)
            .put("ui_actions_available", installed && service != null)
            .put("actions_auto_activate", installed && service != null)
    }

    private fun open(context: Context): JSONObject {
        val launch = context.packageManager.getLaunchIntentForPackage(targetPackage)
            ?: throw IllegalStateException("$displayName is not installed: $targetPackage")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        context.startActivity(launch)
        return JSONObject()
            .put("scope", targetPackage)
            .put("launched", true)
            .put("accessibility_connected", RiftVortexAccessibilityService.current() != null)
    }

    private fun tree(context: Context, args: JSONObject): JSONObject {
        val root = requireTargetRoot(context)
        val limit = args.optInt("limit", 256).coerceIn(1, MAX_TREE_NODES)
        val rows = JSONArray()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var total = 0
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (node.packageName?.toString() != targetPackage) continue
            total++
            if (rows.length() < limit) rows.put(nodeJson(node, depth))
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> queue.add(child to depth + 1) }
            }
        }
        return JSONObject()
            .put("scope", targetPackage)
            .put("nodes", rows)
            .put("total_nodes", total)
            .put("truncated", total > limit)
    }

    private fun click(context: Context, args: JSONObject): JSONObject {
        val target = requiredTarget(args)
        val (service, root) = requireTargetServiceAndRoot(context)
        val node = findNodeWithRetry(service, root, target)
            ?: throw IllegalArgumentException("$displayName UI target not found: $target")
        rejectPassword(node)
        val actionNode = actionAnchor(node)
        require(actionNode.isClickable) { "$displayName UI target is not clickable: $target" }

        val inputMode = if (targetPackage == "com.riftos.app") {
            // Android can report ACTION_CLICK=true for RiftOS self-controls without dispatching the
            // View click listener. A short fixed-scope accessibility gesture matches physical input
            // and proved reliable against the native RiftDesktop during live acceptance testing.
            val bounds = Rect().also(actionNode::getBoundsInScreen)
            require(!bounds.isEmpty) { "$displayName UI target has no tappable bounds: $target" }
            val x = bounds.exactCenterX()
            val y = bounds.exactCenterY()
            requirePoint(service, x, y)
            dispatchGesture(service, Path().apply { moveTo(x, y) }, 80L)
            "gesture_tap"
        } else {
            val clicked = actionNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) throw IllegalStateException("$displayName UI target is not clickable: $target")
            "accessibility_click"
        }
        SystemClock.sleep(ACTION_SETTLE_MS)
        return JSONObject()
            .put("scope", targetPackage)
            .put("clicked", true)
            .put("target", target)
            .put("input", inputMode)
            .put("settled_ms", ACTION_SETTLE_MS)
            .put("node", nodeJson(actionNode, 0))
    }

    private fun tap(context: Context, args: JSONObject): JSONObject {
        val service = requireTargetServiceAndRoot(context).first
        val x = finite(args, "x")
        val y = finite(args, "y")
        requirePoint(service, x, y)
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(service, path, 80L)
        SystemClock.sleep(ACTION_SETTLE_MS)
        return JSONObject().put("scope", targetPackage).put("tapped", true).put("x", x).put("y", y).put("settled_ms", ACTION_SETTLE_MS)
    }

    private fun swipe(context: Context, args: JSONObject): JSONObject {
        val service = requireTargetServiceAndRoot(context).first
        val x1 = finite(args, "x1")
        val y1 = finite(args, "y1")
        val x2 = finite(args, "x2")
        val y2 = finite(args, "y2")
        requirePoint(service, x1, y1)
        requirePoint(service, x2, y2)
        val duration = args.optLong("durationMs", 350L).coerceIn(50L, 3_000L)
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(service, path, duration)
        SystemClock.sleep(ACTION_SETTLE_MS)
        return JSONObject()
            .put("scope", targetPackage)
            .put("swiped", true)
            .put("settled_ms", ACTION_SETTLE_MS)
            .put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2)
            .put("duration_ms", duration)
    }

    private fun type(context: Context, args: JSONObject): JSONObject {
        val target = requiredTarget(args)
        val text = args.optString("text")
        require(text.length <= MAX_TEXT_CHARS) { "$displayName agent text exceeds $MAX_TEXT_CHARS characters" }
        val (service, root) = requireTargetServiceAndRoot(context)
        val node = findNodeWithRetry(service, root, target)
            ?: throw IllegalArgumentException("$displayName text target not found: $target")
        rejectPassword(node)
        require(node.isEditable) { "$displayName target is not editable: $target" }
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        if (!ok) throw IllegalStateException("Android rejected text input for $displayName target: $target")
        SystemClock.sleep(ACTION_SETTLE_MS)
        return JSONObject().put("scope", targetPackage).put("typed", true).put("target", target).put("characters", text.length).put("settled_ms", ACTION_SETTLE_MS)
    }

    private fun back(context: Context): JSONObject {
        val (service, _) = requireTargetServiceAndRoot(context)
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        if (!ok) throw IllegalStateException("Android rejected Back while $displayName was foreground")
        SystemClock.sleep(ACTION_SETTLE_MS)
        return JSONObject()
            .put("scope", targetPackage)
            .put("back", true)
            .put("input", "accessibility_global_back")
            .put("settled_ms", ACTION_SETTLE_MS)
    }

    private fun requireTargetRoot(context: Context): AccessibilityNodeInfo = requireTargetServiceAndRoot(context).second

    private fun requireTargetServiceAndRoot(context: Context): Pair<RiftVortexAccessibilityService, AccessibilityNodeInfo> {
        val service = RiftVortexAccessibilityService.current()
            ?: throw IllegalStateException("RiftOS Local UI Agent accessibility service is not enabled")

        // Let Accessibility publish the real active window before trusting a cached root from the
        // previous MCP call. ChatGPT can retake foreground between calls.
        SystemClock.sleep(ROOT_SETTLE_MS)
        val initialRoot = service.rootInActiveWindow
        val initialReady = initialRoot != null && initialRoot.packageName?.toString() == targetPackage &&
            initialRoot.isVisibleToUser && (initialRoot.window?.isActive != false)
        if (!initialReady) {
            val launch = context.packageManager.getLaunchIntentForPackage(targetPackage)
                ?: throw IllegalStateException("$displayName is not installed: $targetPackage")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(launch)
        }

        val deadline = SystemClock.elapsedRealtime() + ACTIVATION_TIMEOUT_MS
        var lastPackage = initialRoot?.packageName?.toString().orEmpty()
        var lastSignature = ""
        var stablePolls = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = service.rootInActiveWindow
            lastPackage = root?.packageName?.toString().orEmpty()
            if (root != null && lastPackage == targetPackage && root.isVisibleToUser &&
                (root.window?.isActive != false)) {
                val signature = "${root.windowId}:${root.childCount}"
                stablePolls = if (signature == lastSignature) stablePolls + 1 else 1
                lastSignature = signature
                if (stablePolls >= REQUIRED_STABLE_ROOT_POLLS) return service to root
            } else {
                stablePolls = 0
                lastSignature = ""
            }
            SystemClock.sleep(ACTIVATION_POLL_MS)
        }
        throw IllegalStateException(
            "$displayName agent could not stabilize $targetPackage; last foreground package was ${lastPackage.ifBlank { "(none)" }}"
        )
    }

    private fun findNodeWithRetry(
        service: RiftVortexAccessibilityService,
        initialRoot: AccessibilityNodeInfo,
        target: String
    ): AccessibilityNodeInfo? {
        var root = initialRoot
        val deadline = SystemClock.elapsedRealtime() + TARGET_LOOKUP_TIMEOUT_MS
        while (true) {
            findNode(root, target)?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            SystemClock.sleep(TARGET_LOOKUP_POLL_MS)
            val fresh = service.rootInActiveWindow
            if (fresh != null && fresh.packageName?.toString() == targetPackage &&
                fresh.isVisibleToUser && (fresh.window?.isActive != false)) {
                root = fresh
            }
        }
    }

    private fun findNode(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val exactMatches = mutableListOf<AccessibilityNodeInfo>()
        val semanticMatches = mutableListOf<AccessibilityNodeInfo>()
        val semanticTarget = semanticLabel(target)
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.packageName?.toString() != targetPackage) continue
            val text = if (node.isPassword) "" else node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            if (node.isVisibleToUser && node.isEnabled) {
                if (target == text || target == description || target == viewId) {
                    exactMatches.add(node)
                } else if (semanticTarget.isNotEmpty() && (
                        semanticTarget.equals(semanticLabel(text), ignoreCase = true) ||
                        semanticTarget.equals(semanticLabel(description), ignoreCase = true)
                    )) {
                    semanticMatches.add(node)
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        val matches = if (exactMatches.isNotEmpty()) exactMatches else semanticMatches
        val anchored = LinkedHashMap<String, AccessibilityNodeInfo>()
        for (match in matches) {
            val anchor = actionAnchor(match)
            anchored.putIfAbsent(nodeIdentity(anchor), anchor)
        }
        if (anchored.size > 1) {
            throw IllegalArgumentException(
                "$displayName UI target is ambiguous: $target; use a unique content-description or view-id"
            )
        }
        return anchored.values.firstOrNull()
    }

    private fun actionAnchor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        while (current != null && current.packageName?.toString() == targetPackage) {
            if (current.isClickable || current.isEditable) return current
            current = current.parent
        }
        return node
    }

    private fun nodeIdentity(node: AccessibilityNodeInfo): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        return "${node.windowId}:${node.className}:${node.viewIdResourceName}:${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
    }

    private fun semanticLabel(value: String): String {
        val trimmed = value.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) return ""
        val parts = trimmed.split(" ", limit = 2)
        if (parts.size != 2) return trimmed
        val prefix = parts[0]
        val iconLike = prefix.length <= 3 && (
            prefix.any { !it.isLetterOrDigit() } ||
            (prefix.length <= 2 && prefix.any { it.isLetter() } && prefix.all { !it.isLetter() || it.isUpperCase() })
        )
        return if (iconLike) parts[1].trim() else trimmed
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
        require(!node.isPassword) { "Password fields are not available to the local UI agent" }
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

    @Suppress("DEPRECATION")
    private fun requirePoint(service: AccessibilityService, x: Float, y: Float) {
        // AccessibilityNodeInfo#getBoundsInScreen and dispatchGesture both use full-display screen
        // coordinates. Resource displayMetrics can describe only the app/content area on devices
        // with system bars, which falsely rejects valid controls near the native taskbar. Validate
        // against the real display bounds first and keep resource metrics only as a safe fallback.
        val realMetrics = android.util.DisplayMetrics()
        val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        runCatching { windowManager?.defaultDisplay?.getRealMetrics(realMetrics) }
        val fallback = service.resources.displayMetrics
        val width = realMetrics.widthPixels.takeIf { it > 0 } ?: fallback.widthPixels
        val height = realMetrics.heightPixels.takeIf { it > 0 } ?: fallback.heightPixels
        require(x >= 0f && y >= 0f && x < width && y < height) {
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
        require(accepted) { "Android rejected the $displayName gesture" }
        require(latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timed out waiting for the $displayName gesture" }
        require(completed && !cancelled) { "$displayName gesture was cancelled" }
    }
}

/** Vortex3D-only fixed local UI authority. */
object RiftVortexLocalAgent {
    private const val TARGET_PACKAGE = "com.vortex3d.app"
    private val delegate = RiftScopedLocalAgent(TARGET_PACKAGE, "Vortex3D")

    fun execute(context: Context, args: JSONObject): JSONObject {
        val out = delegate.execute(context, args)
        if (out.has("target_foreground")) out.put("vortex_foreground", out.optBoolean("target_foreground"))
        return out
    }

    /** Keep the one hard-coded Vortex target foreground for a native bridge session. */
    internal fun ensureActiveForSession(context: Context) = delegate.ensureActive(context)
}

/** RiftOS-self-only fixed local UI authority used for shell-driven UI acceptance testing. */
object RiftOsLocalAgent {
    private const val TARGET_PACKAGE = "com.riftos.app"
    private const val SELF_BACK_SETTLE_MS = 400L
    private val delegate = RiftScopedLocalAgent(TARGET_PACKAGE, "RiftOS")

    fun execute(context: Context, args: JSONObject): JSONObject {
        if (args.optString("op").trim().equals("back", ignoreCase = true) && context is MainActivity) {
            delegate.ensureActive(context)
            val latch = CountDownLatch(1)
            var failure: Throwable? = null
            context.runOnUiThread {
                try {
                    context.onBackPressed()
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    latch.countDown()
                }
            }
            require(latch.await(2_000L, TimeUnit.MILLISECONDS)) { "Timed out dispatching RiftOS self Back" }
            failure?.let { throw IllegalStateException("RiftOS self Back failed", it) }
            SystemClock.sleep(SELF_BACK_SETTLE_MS)
            return JSONObject()
                .put("scope", TARGET_PACKAGE)
                .put("back", true)
                .put("input", "riftos_activity_back")
                .put("settled_ms", SELF_BACK_SETTLE_MS)
        }
        val out = delegate.execute(context, args)
        if (out.has("target_foreground")) out.put("riftos_foreground", out.optBoolean("target_foreground"))
        return out
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
