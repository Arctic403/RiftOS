package com.riftos.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * C1.3-D: production :riftShell process graphical RAPP renderer.
 * All executable BOOT, session, focus and input authority is in the default
 * Core Android process. This class holds only presentation/viewport state.
 */
class RiftShellRappHost(
    private val activity: Activity,
    private val desktop: RiftNativeDesktop,
    private val core: RiftShellCoreClient,
    private val refreshLauncher: () -> Unit
) {
    private class Session(
        val id: String,
        val name: String,
        val adapter: RiftAppRuntimeAdapter,
        val generation: Long
    ) {
        var revision = -1L
        var onFrame: ((RiftAppAbi.Frame) -> Unit)? = null
    }
    private data class EventOutcome(
        val frame: RiftAppAbi.Frame? = null,
        val error: String? = null
    )
    private val sessions = LinkedHashMap<String, Session>()
    private val poller = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "rift-shell-surface-observer").apply { isDaemon = true }
    }
    @Volatile private var tracked = emptyList<Pair<String, Long>>()
    @Volatile private var closed = false

    init {
        poller.scheduleWithFixedDelay({
            if (closed) return@scheduleWithFixedDelay
            for ((id, generation) in tracked) {
                if (closed) break
                val read = runCatching { core.snapshot(id) }
                // IPC failure is NOT Core app death. Retain the presentation
                // through transient Binder errors rather than closing a valid
                // window; only a confirmed absent Core surface may close it.
                if (read.isFailure) continue
                val published = read.getOrNull()
                activity.runOnUiThread {
                    if (closed || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    val session = sessions[id] ?: return@runOnUiThread
                    if (session.generation != generation) return@runOnUiThread
                    if (published == null) {
                        // When Core stops a RAPP, its surface is removed. A
                        // local close must NOT stop a new generation.
                        if (session.revision >= 0) {
                            desktop.handle("desktop.window.close",
                                JSONObject().put("id", id))
                        }
                    } else if (published.generation == generation &&
                        published.revision > session.revision) {
                        session.revision = published.revision
                        val renderer = session.onFrame
                        if (renderer == null) {
                            desktop.attachContent(id, render(session, published.frame))
                        } else renderer(published.frame)
                    }
                }
            }
        }, 400L, 500L, TimeUnit.MILLISECONDS)
    }

    private fun refreshTracked() {
        tracked = sessions.values.map { it.id to it.generation }
    }

    fun handlesInstalled(id: String): Boolean =
        runCatching {
            val list = core.installed()
            (0 until list.length()).any { list.getJSONObject(it).optString("id") == id }
        }.getOrDefault(false)

    fun openFromLauncher(id: String): Boolean {
        if (!handlesInstalled(id)) return false
        runCatching { open(id) }.onFailure { error ->
            showLaunchFailure(id, error.message ?: error.javaClass.simpleName)
        }
        return true
    }

    fun onDesktopClosed(id: String): Boolean {
        val session = sessions.remove(id) ?: return false
        session.onFrame = null
        refreshTracked()
        runCatching { core.stop(id, session.generation) }
        return true
    }

    fun destroy() {
        closed = true
        poller.shutdownNow()
        sessions.values.forEach { it.onFrame = null }
        sessions.clear()
        tracked = emptyList()
        // Losing the shell Activity must NEVER stop Core execution.
    }

    private fun open(id: String) {
        val packages = core.installed()
        val app = (0 until packages.length()).map { packages.getJSONObject(it) }
            .firstOrNull { it.optString("id") == id }
            ?: error("Core RAPP is not installed: $id")
        require(app.getString("abi") == RiftAppAbi.SCHEMA) { "Unsupported RAPP ABI" }
        val adapterId = app.getString("adapter")
        val adapter = RiftAppAdapters.require(adapterId)
        require(adapter.presentation == app.getString("presentation")) {
            "RAPP adapter presentation mismatch"
        }
        val state = core.start(id)
        val session = Session(id, app.getString("name"), adapter,
            state.getLong("attachmentGeneration"))
        sessions[id]?.onFrame = null
        sessions[id] = session
        refreshTracked()
        desktop.handle("desktop.window.open", JSONObject()
            .put("id", id).put("title", session.name)
            .put("kicker", "RIFTOS APP · $adapterId · CORE IPC"))
        val published = runCatching { core.snapshot(id) }.getOrNull()?.takeIf {
            it.generation == session.generation
        }
        desktop.attachContent(id, when {
            published != null -> {
                session.revision = published.revision
                render(session, published.frame)
            }
            state.optString("state") == "failed" ->
                failureView(state.optString("error", "Core BOOT failed"))
            else -> TextView(activity).apply {
                text = "Launching Core RAPP…"
                textSize = 17f
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
        })
    }

    private fun runEventAsync(
        session: Session,
        event: RiftAppAbi.Event,
        complete: (EventOutcome) -> Unit
    ) {
        if (closed || sessions[session.id] !== session) return
        val rejection = runCatching {
            core.offerEvent(session.id, session.generation, event)
        }.exceptionOrNull() ?: return
        val snapshot = runCatching { core.snapshot(session.id) }.getOrNull()
            ?.takeIf { it.generation == session.generation }
        complete(EventOutcome(snapshot?.frame,
            rejection.message ?: "Core IPC rejected RAPP input"))
    }

    private fun render(
        session: Session,
        frame: RiftAppAbi.Frame
    ): View {
        if (
            frame.layout ==
                RiftAppAbi.Layout.ABSOLUTE
        ) {
            return renderAbsolute(
                session,
                frame
            )
        }

        require(
            frame.layout ==
                RiftAppAbi.Layout.FLOW_COLUMN
        ) {
            "RiftOS app layout is not supported by this renderer yet"
        }

        val root =
            LinearLayout(activity).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(16),
                    dp(16),
                    dp(16),
                    dp(16)
                )
            }

        val scroll =
            ScrollView(activity).apply {
                isFillViewport =
                    true
                addView(
                    root,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams
                            .MATCH_PARENT,
                        ViewGroup.LayoutParams
                            .WRAP_CONTENT
                    )
                )
            }

        val textNodes =
            LinkedHashMap<Int, TextView>()
        val inputNodes =
            LinkedHashMap<Int, EditText>()
        val actionNodes =
            LinkedHashMap<Int, Button>()
        val suppressInput =
            HashSet<Int>()
        var currentFrame =
            frame

        fun applyFrame(
            next: RiftAppAbi.Frame
        ) {
            val structureChanged =
                next.layout !=
                    RiftAppAbi.Layout.FLOW_COLUMN ||
                    next.nodes.map {
                        it.id to it.kind
                    } !=
                    currentFrame.nodes.map {
                        it.id to it.kind
                    }

            if (
                structureChanged
            ) {
                desktop.attachContent(
                    session.id,
                    render(
                        session,
                        next
                    )
                )
                return
            }

            next.nodes.forEach {
                node ->
                when (
                    node.kind
                ) {
                    RiftAppAbi.NodeKind.TEXT ->
                        textNodes[
                            node.id
                        ]?.text =
                            node.text

                    RiftAppAbi.NodeKind.TEXT_INPUT ->
                        inputNodes[
                            node.id
                        ]?.let {
                            field ->
                            val canonical =
                                node.text

                            if (
                                !field.hasFocus() &&
                                field.text
                                    .toString() !=
                                    canonical
                            ) {
                                val selection =
                                    field.selectionStart
                                        .coerceAtLeast(
                                            0
                                        )

                                suppressInput.add(
                                    node.id
                                )
                                field.setText(
                                    canonical
                                )
                                field.setSelection(
                                    minOf(
                                        selection,
                                        canonical.length
                                    )
                                )
                                suppressInput.remove(
                                    node.id
                                )
                            }
                        }

                    RiftAppAbi.NodeKind.ACTION ->
                        actionNodes[
                            node.id
                        ]?.text =
                            node.text
                }
            }

            currentFrame =
                next
        }

        fun acceptOutcome(
            outcome: EventOutcome
        ) {
            val next =
                outcome.frame

            if (
                next !=
                    null
            ) {
                applyFrame(
                    next
                )
            } else {
                desktop.attachContent(
                    session.id,
                    failureView(
                        outcome.error
                            ?: "RAPP event failed"
                    )
                )
            }
        }

        if (
            session.adapter
                .supportsEventKind(
                    RiftAppAbi.EventKind
                        .DISPLAY_RESIZE
                )
        ) {
            root.addOnLayoutChangeListener {
                _,
                left,
                top,
                right,
                bottom,
                oldLeft,
                oldTop,
                oldRight,
                oldBottom ->

                val nextWidth =
                    right -
                        left
                val nextHeight =
                    bottom -
                        top
                val oldWidth =
                    oldRight -
                        oldLeft
                val oldHeight =
                    oldBottom -
                        oldTop

                if (
                    nextWidth >
                        0 &&
                    nextHeight >
                        0 &&
                    (
                        nextWidth !=
                            oldWidth ||
                        nextHeight !=
                            oldHeight
                        )
                ) {
                    runEventAsync(
                        session,
                        RiftAppAbi.Event(
                            kind =
                                RiftAppAbi.EventKind
                                    .DISPLAY_RESIZE,
                            arg0 =
                                nextWidth,
                            arg1 =
                                nextHeight
                        )
                    ) {
                        outcome ->
                        if (
                            sessions[
                                session.id
                            ] ===
                                session
                        ) {
                            acceptOutcome(
                                outcome
                            )
                        }
                    }
                }
            }
        }

        frame.nodes.forEach {
            node ->
            when (
                node.kind
            ) {
                RiftAppAbi.NodeKind.TEXT -> {
                    val label =
                        TextView(activity).apply {
                            text =
                                node.text
                            textSize =
                                24f
                            setPadding(
                                0,
                                0,
                                0,
                                dp(12)
                            )
                        }

                    textNodes[
                        node.id
                    ] =
                        label
                    root.addView(
                        label
                    )
                }

                RiftAppAbi.NodeKind.TEXT_INPUT -> {
                    val field =
                        EditText(activity).apply {
                            setText(
                                node.text
                            )
                            gravity =
                                Gravity.TOP or
                                    Gravity.START
                            inputType =
                                InputType
                                    .TYPE_CLASS_TEXT or
                                    InputType
                                        .TYPE_TEXT_FLAG_MULTI_LINE or
                                    InputType
                                        .TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            minLines =
                                8
                            maxLines =
                                24
                            setPadding(
                                dp(10),
                                dp(10),
                                dp(10),
                                dp(10)
                            )
                        }

                    inputNodes[
                        node.id
                    ] =
                        field

                    field.setOnKeyListener {
                        _,
                        keyCode,
                        keyEvent ->
                        val kind =
                            when (
                                keyEvent.action
                            ) {
                                KeyEvent.ACTION_DOWN ->
                                    RiftAppAbi.EventKind
                                        .KEY_DOWN

                                KeyEvent.ACTION_UP ->
                                    RiftAppAbi.EventKind
                                        .KEY_UP

                                else ->
                                    null
                            }

                        if (
                            kind !=
                                null &&
                            session.adapter
                                .supportsEventKind(
                                    kind
                                )
                        ) {
                            runEventAsync(
                                session,
                                RiftAppAbi.Event(
                                    kind =
                                        kind,
                                    targetId =
                                        node.id,
                                    arg0 =
                                        keyCode,
                                    arg1 =
                                        keyEvent.repeatCount,
                                    arg2 =
                                        keyEvent.metaState
                                )
                            ) {
                                outcome ->
                                if (
                                    sessions[
                                        session.id
                                    ] ===
                                        session
                                ) {
                                    acceptOutcome(
                                        outcome
                                    )
                                }
                            }
                        }

                        false
                    }

                    field.addTextChangedListener(
                        object :
                            TextWatcher {
                            override fun beforeTextChanged(
                                value: CharSequence?,
                                start: Int,
                                count: Int,
                                after: Int
                            ) = Unit

                            override fun onTextChanged(
                                value: CharSequence?,
                                start: Int,
                                before: Int,
                                count: Int
                            ) = Unit

                            override fun afterTextChanged(
                                value: Editable?
                            ) {
                                if (
                                    node.id in
                                        suppressInput ||
                                    !session.adapter
                                        .supportsEventKind(
                                            RiftAppAbi.EventKind
                                                .TEXT_INPUT
                                        )
                                ) {
                                    return
                                }

                                runEventAsync(
                                    session,
                                    RiftAppAbi.Event(
                                        kind =
                                            RiftAppAbi.EventKind
                                                .TEXT_INPUT,
                                        targetId =
                                            node.id,
                                        arg0 =
                                            field.selectionStart
                                                .coerceAtLeast(
                                                    0
                                                ),
                                        arg1 =
                                            field.selectionEnd
                                                .coerceAtLeast(
                                                    0
                                                ),
                                        text =
                                            value
                                                ?.toString()
                                                .orEmpty()
                                    )
                                ) {
                                    outcome ->
                                    if (
                                        sessions[
                                            session.id
                                        ] ===
                                            session
                                    ) {
                                        acceptOutcome(
                                            outcome
                                        )
                                    }
                                }
                            }
                        }
                    )

                    root.addView(
                        field,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams
                                .MATCH_PARENT,
                            0,
                            1f
                        )
                    )
                }

                RiftAppAbi.NodeKind.ACTION -> {
                    val button =
                        Button(activity).apply {
                            text =
                                node.text
                            setOnClickListener {
                                runEventAsync(
                                    session,
                                    RiftAppAbi.Event(
                                        kind =
                                            RiftAppAbi.EventKind
                                                .ACTION,
                                        targetId =
                                            node.id
                                    )
                                ) {
                                    outcome ->
                                    if (
                                        sessions[
                                            session.id
                                        ] ===
                                            session
                                    ) {
                                        acceptOutcome(
                                            outcome
                                        )
                                    }
                                }
                            }
                        }

                    actionNodes[
                        node.id
                    ] =
                        button

                    root.addView(
                        button,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams
                                .MATCH_PARENT,
                            ViewGroup.LayoutParams
                                .WRAP_CONTENT
                        )
                    )
                }

                RiftAppAbi.NodeKind.IMAGE -> {
                    root.addView(
                        TextView(activity).apply {
                            text =
                                node.text
                            textSize =
                                16f
                            gravity =
                                Gravity.CENTER
                        }
                    )
                }
            }
        }

        session.onFrame = { next -> applyFrame(next) }
        return scroll
    }

    private fun renderAbsolute(
        session: Session,
        frame: RiftAppAbi.Frame
    ): View {
        lateinit var view:
            RiftRappAbsoluteView

        view =
            RiftRappAbsoluteView(
                activity,
                frame,
                supportsEvent = {
                    kind ->
                    session.adapter
                        .supportsEventKind(
                            kind
                        )
                }
            ) { event ->
                runEventAsync(
                    session,
                    event
                ) { outcome ->
                    if (
                        sessions[session.id] !==
                            session
                    ) {
                        return@runEventAsync
                    }

                    val next =
                        outcome.frame
                    if (
                        next != null &&
                        next.layout ==
                            RiftAppAbi.Layout.ABSOLUTE
                    ) {
                        view.updateFrame(
                            next
                        )
                    } else if (
                        next != null
                    ) {
                        desktop.attachContent(
                            session.id,
                            render(
                                session,
                                next
                            )
                        )
                    } else {
                        desktop.attachContent(
                            session.id,
                            failureView(
                                outcome.error
                                    ?: "RAPP event failed"
                            )
                        )
                    }
                }
            }

        session.onFrame = { next ->
            if (next.layout == RiftAppAbi.Layout.ABSOLUTE) view.updateFrame(next)
            else desktop.attachContent(session.id, render(session, next))
        }
        return view
    }

    private fun showLaunchFailure(
        id: String,
        message: String
    ) {
        runCatching {
            desktop.handle(
                "desktop.window.open",
                JSONObject()
                    .put("id", id)
                    .put("title", id)
                    .put("kicker", "RIFT APP ERROR")
            )
            desktop.attachContent(
                id,
                failureView(message)
            )
        }
    }

    private fun failureView(
        message: String
    ): View =
        TextView(activity).apply {
            text =
                "RiftOS app failed: $message"
            textSize = 17f
            setPadding(
                dp(18),
                dp(18),
                dp(18),
                dp(18)
            )
            setTextIsSelectable(true)
        }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
