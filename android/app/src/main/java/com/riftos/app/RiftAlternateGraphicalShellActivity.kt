package com.riftos.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * C1.2-D2: an independent graphical shell consumer of Core surfaces.
 * This same-process Activity never owns an executable RAPP session,
 * calls RiftRappHost/RiftNativeDesktop, or sends Core input/focus events.
 * C1.3 is the separate-process IPC/restart gate.
 */
class RiftAlternateGraphicalShellActivity : Activity() {
    companion object { const val EXTRA_APP_ID = "riftos.core.alt-graphic-app-id" }
    private lateinit var surfaces: RiftCoreAppSurfaces
    private lateinit var appId: String
    private lateinit var subtitle: TextView
    private lateinit var frameContainer: FrameLayout
    private var subscription: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appId = intent.getStringExtra(EXTRA_APP_ID).orEmpty()
        require(appId.isNotBlank() && appId.length <= 128) {
            "Alternate graphical shell requires a bounded Core app ID"
        }
        surfaces = RiftCoreRuntime.surfaces(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff101722.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = "Alternate Core graphical client"
            textSize = 20f
            setTextColor(0xffffffff.toInt())
        })
        subtitle = TextView(this).apply {
            text = appId
            textSize = 12f
            setTextColor(0xffb4c5d7.toInt())
        }
        labels.addView(subtitle)
        header.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Read-only Core surface · No application input"
            textSize = 12f
            setTextColor(0xffb4c5d7.toInt())
            setPadding(0, dp(12), 0, dp(12))
        })
        frameContainer = FrameLayout(this)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(frameContainer)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        subscription = surfaces.subscribe { change ->
            if (change.appId == appId) runOnUiThread {
                if (!isFinishing && !isDestroyed) renderCurrent()
            }
        }
        renderCurrent()
    }

    override fun onStop() {
        subscription?.let(surfaces::unsubscribe)
        subscription = null
        super.onStop()
    }

    private fun renderCurrent() {
        val snapshot = surfaces.snapshot(appId)
        frameContainer.removeAllViews()
        if (snapshot == null) {
            subtitle.text = "$appId · Core surface unavailable"
            frameContainer.addView(textView("[Core surface removed]"))
            return
        }
        subtitle.text = "$appId · generation " + snapshot.attachmentGeneration +
            " · revision " + snapshot.revision
        val frame = snapshot.frame
        val nodes = frame.nodes
        require(nodes.size in 1..256) { "Core frame exceeds node bound" }
        val visible = nodes.filter {
            it.kind != RiftAppAbi.NodeKind.ROOT &&
                it.kind != RiftAppAbi.NodeKind.SURFACE
        }
        if (frame.layout == RiftAppAbi.Layout.ABSOLUTE) {
            for (node in visible) {
                val view = nodeView(node)
                val params = FrameLayout.LayoutParams(
                    if (node.width > 0) dp(node.width.coerceAtMost(1600))
                    else ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (node.height > 0) dp(node.height.coerceAtMost(900))
                    else ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.leftMargin = dp(node.x.coerceIn(0, 1600))
                params.topMargin = dp(node.y.coerceIn(0, 900))
                frameContainer.addView(view, params)
            }
        } else {
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val parents = nodes.associateBy { it.id }
            for (node in visible) {
                val view = nodeView(node)
                val indent = depthOf(node, parents).coerceAtMost(8)
                view.setPadding(dp(12 + indent * 8), dp(10), dp(12), dp(10))
                list.addView(view)
            }
            frameContainer.addView(list)
        }
    }

    private fun nodeView(node: RiftAppAbi.Node): TextView {
        val label = when (node.kind) {
            RiftAppAbi.NodeKind.TEXT -> node.text
            RiftAppAbi.NodeKind.TEXT_INPUT -> "TEXT INPUT (read-only): " + node.text
            RiftAppAbi.NodeKind.ACTION -> "ACTION (read-only): " + node.text
            RiftAppAbi.NodeKind.IMAGE -> "IMAGE (read-only) #" + node.id
            else -> node.text
        }
        return textView(label).apply {
            setBackgroundColor(when (node.kind) {
                RiftAppAbi.NodeKind.TEXT_INPUT -> 0xff283e51.toInt()
                RiftAppAbi.NodeKind.ACTION -> 0xff245678.toInt()
                else -> 0xff1c2b39.toInt()
            })
            isClickable = false
            isFocusable = false
        }
    }

    private fun textView(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 17f
        setTextColor(0xffffffff.toInt())
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun depthOf(node: RiftAppAbi.Node, ids: Map<Int, RiftAppAbi.Node>): Int {
        var parent = node.parentId
        var depth = 0
        val seen = HashSet<Int>()
        while (parent > 0 && depth < 16 && seen.add(parent)) {
            depth += 1
            parent = ids[parent]?.parentId ?: break
        }
        return depth
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
