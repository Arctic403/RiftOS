package com.riftos.app

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Generic RiftOS application host for installed .rapp programs.
 *
 * Window/lifecycle/input ownership stays in RiftOS. Language/runtime protocol
 * details live behind RiftAppRuntimeAdapter and must not leak into this host.
 */
class RiftRappHost(
    private val activity: Activity,
    private val desktop: RiftNativeDesktop,
    private val refreshLauncher: () -> Unit
) {
    companion object {
        private const val OUTPUT_BYTES = 512 * 1024
        private const val EVENT_TIMEOUT_MS = 6500L

        @Volatile
        private var active:
            WeakReference<RiftRappHost>? = null

        fun notifyProgramsChanged() {
            active
                ?.get()
                ?.refreshLauncherAsync()
        }

        fun launchInstalled(
            id: String
        ): Boolean =
            active
                ?.get()
                ?.launchAsync(id)
                ?: false
    }

    private data class Session(
        val payload: RiftAppAbi.RuntimePayload,
        val adapter: RiftAppRuntimeAdapter
    ) {
        val id: String get() = payload.id
        val name: String get() = payload.name
    }

    private data class EventOutcome(
        val frame: RiftAppAbi.Frame? = null,
        val error: String? = null
    )

    private val manager by lazy(LazyThreadSafetyMode.NONE) {
        RiftRappManager(activity)
    }
    private val eventExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "rift-rapp-event").apply {
                isDaemon = true
            }
        }
    private val eventWatchdog =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "rift-rapp-watchdog").apply {
                isDaemon = true
            }
        }
    private val sessions =
        LinkedHashMap<String, Session>()

    init {
        active =
            WeakReference(this)
    }

    fun openFromLauncher(
        id: String
    ): Boolean {
        if (!manager.handlesInstalled(id)) {
            return false
        }
        runCatching {
            open(id)
        }.onFailure { error ->
            showLaunchFailure(
                id,
                error.message
                    ?: error.javaClass.simpleName
            )
        }
        return true
    }

    fun onDesktopClosed(
        id: String
    ): Boolean {
        val removed = sessions.remove(id) != null
        return removed || manager.handlesInstalled(id)
    }

    fun destroy() {
        sessions.clear()
        eventExecutor.shutdownNow()
        eventWatchdog.shutdownNow()
        val current =
            active
                ?.get()
        if (current === this) {
            active = null
        }
    }

    private fun open(
        id: String
    ) {
        val app =
            manager.loadInstalled(id)
        require(app.abi == RiftAppAbi.SCHEMA) {
            "Unsupported RiftOS app ABI: ${app.abi}"
        }
        val adapter = RiftAppAdapters.require(app.adapter)
        require(adapter.presentation == app.presentation) {
            "RiftOS app presentation does not match adapter"
        }

        val session =
            Session(
                payload =
                    RiftAppAbi.RuntimePayload(
                        id = app.id,
                        name = app.name,
                        abi = app.abi,
                        adapter = app.adapter,
                        presentation = app.presentation,
                        program = app.program,
                        runtime = app.runtime
                    ),
                adapter = adapter
            )
        sessions[id] = session

        desktop.handle(
            "desktop.window.open",
            JSONObject()
                .put("id", id)
                .put("title", app.name)
                .put(
                    "kicker",
                    "RIFTOS APP · ${app.adapter}"
                )
        )

        desktop.attachContent(
            id,
            TextView(activity).apply {
                text = "Launching RiftOS app…"
                textSize = 17f
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
        )

        runEventAsync(
            session,
            RiftAppAbi.Event(
                kind = RiftAppAbi.EventKind.BOOT
            )
        ) { outcome ->
            if (sessions[id] !== session) {
                return@runEventAsync
            }
            val frame = outcome.frame
            desktop.attachContent(
                id,
                if (frame != null) {
                    render(session, frame)
                } else {
                    failureView(
                        outcome.error
                            ?: "RAPP launch failed"
                    )
                }
            )
        }
    }

    private fun runEventAsync(
        session: Session,
        event: RiftAppAbi.Event,
        complete: (EventOutcome) -> Unit
    ) {
        RiftBoundedAsync.submit(
            executor = eventExecutor,
            watchdog = eventWatchdog,
            timeoutMs = EVENT_TIMEOUT_MS,
            timeoutValue = {
                EventOutcome(
                    error = "RAPP event timed out"
                )
            },
            failureValue = { error ->
                EventOutcome(
                    error = error.message
                        ?: error.javaClass.simpleName
                )
            },
            work = {
                val envelope =
                    session.adapter.encodeEvent(
                        session.payload,
                        event
                    )

                val result =
                    RiftNativeBufferCompilerService.compile(
                        activity,
                        session.payload.runtime,
                        envelope,
                        OUTPUT_BYTES
                    )
                val status =
                    result.getString("status")
                        ?: "host-reject"
                require(status == "success") {
                    buildString {
                        append("RAPP runtime ")
                        append(status)
                        append(" · host=")
                        append(result.getInt("hostStatus", -1))
                        append(" · return=")
                        append(result.getLong("returnValue", 0xffffffffL))
                        result.getString("detail")
                            ?.takeIf { it.isNotBlank() }
                            ?.let {
                                append(" · ")
                                append(it)
                            }
                    }
                }
                val output =
                    result.getByteArray("output")
                        ?: error(
                            "RAPP runtime succeeded without output"
                        )
                EventOutcome(
                    frame = session.adapter.decodeFrame(output)
                )
            },
            reply = { outcome ->
                activity.runOnUiThread {
                    if (
                        !activity.isFinishing &&
                        !activity.isDestroyed
                    ) {
                        complete(outcome)
                    }
                }
            }
        )
    }

    private fun render(
        session: Session,
        frame: RiftAppAbi.Frame
    ): View {
        require(frame.layout == RiftAppAbi.Layout.FLOW_COLUMN) {
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

        var textArea:
            EditText? = null

        frame.nodes.forEach { node ->
            when (node.kind) {
                RiftAppAbi.NodeKind.TEXT -> {
                    root.addView(
                        TextView(activity).apply {
                            text = node.text
                            textSize = 24f
                            setPadding(
                                0,
                                0,
                                0,
                                dp(12)
                            )
                        }
                    )
                }

                RiftAppAbi.NodeKind.TEXT_INPUT -> {
                    val field =
                        EditText(activity).apply {
                            setText(node.text)
                            gravity =
                                Gravity.TOP or
                                    Gravity.START
                            inputType =
                                InputType
                                    .TYPE_CLASS_TEXT or
                                    InputType
                                        .TYPE_TEXT_FLAG_MULTI_LINE or
                                    InputType
                                        .TYPE_TEXT_FLAG_CAP_SENTENCES
                            minLines = 8
                            maxLines = 24
                            setPadding(
                                dp(10),
                                dp(10),
                                dp(10),
                                dp(10)
                            )
                        }

                    textArea = field

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
                    root.addView(
                        Button(activity).apply {
                            text = node.text
                            setOnClickListener {
                                try {
                                    textArea
                                        ?.text
                                        ?.toString()

                                    isEnabled = false
                                    runEventAsync(
                                        session,
                                        RiftAppAbi.Event(
                                            kind = RiftAppAbi.EventKind.ACTION,
                                            targetId = node.id
                                        )
                                    ) { outcome ->
                                        if (
                                            sessions[session.id] !==
                                                session
                                        ) {
                                            return@runEventAsync
                                        }
                                        val next = outcome.frame
                                        desktop.attachContent(
                                            session.id,
                                            if (next != null) {
                                                render(
                                                    session,
                                                    next
                                                )
                                            } else {
                                                failureView(
                                                    outcome.error
                                                        ?: "RAPP event failed"
                                                )
                                            }
                                        )
                                    }
                                } catch (
                                    error: Throwable
                                ) {
                                    desktop.attachContent(
                                        session.id,
                                        failureView(
                                            error.message
                                                ?: error
                                                    .javaClass
                                                    .simpleName
                                        )
                                    )
                                }
                            }
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams
                                .MATCH_PARENT,
                            ViewGroup.LayoutParams
                                .WRAP_CONTENT
                        )
                    )
                }
            }
        }

        return root
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

    private fun refreshLauncherAsync() {
        activity.runOnUiThread {
            runCatching {
                refreshLauncher()
            }
        }
    }

    private fun launchAsync(
        id: String
    ): Boolean {
        if (!manager.handlesInstalled(id)) {
            return false
        }
        activity.runOnUiThread {
            runCatching {
                open(id)
            }.onFailure { error ->
                showLaunchFailure(
                    id,
                    error.message
                        ?: error.javaClass.simpleName
                )
            }
        }
        return true
    }

    private fun dp(
        value: Int
    ): Int =
        (
            value *
                activity.resources
                    .displayMetrics
                    .density
        ).toInt()
}
