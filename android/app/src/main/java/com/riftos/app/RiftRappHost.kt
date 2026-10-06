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
import com.riftpp.editor.RiftppNativeBridge
import com.riftpp.editor.RiftppUiCodec
import com.riftpp.editor.RiftppUiFrame
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * In-process RiftOS application host for installed .rapp programs.
 *
 * Window/lifecycle ownership stays in RiftOS. The first engine driver reuses
 * the existing bounded Rift++ RPE2/RUI2 runtime path. Additional compiler
 * targets can add engines without changing APK packaging.
 */
class RiftRappHost(
    private val activity: Activity,
    private val desktop: RiftNativeDesktop,
    private val refreshLauncher: () -> Unit
) {
    companion object {
        private const val OUTPUT_BYTES = 1024 * 1024

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
        val id: String,
        val name: String,
        val program: ByteArray,
        val runtime: ByteArray
    )

    private val manager by lazy(LazyThreadSafetyMode.NONE) {
        RiftRappManager(activity)
    }
    private val bridge by lazy(LazyThreadSafetyMode.NONE) {
        RiftppNativeBridge()
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
        require(
            app.engine ==
                RiftRappManager.ENGINE_RIFTPP_RUI2
        ) {
            "Unsupported RAPP engine"
        }
        require(
            app.presentation == "rui2"
        ) {
            "Unsupported RAPP presentation"
        }

        val session =
            Session(
                id = app.id,
                name = app.name,
                program = app.program,
                runtime = app.runtime
            )
        sessions[id] = session

        desktop.handle(
            "desktop.window.open",
            JSONObject()
                .put("id", id)
                .put("title", app.name)
                .put(
                    "kicker",
                    "RIFT APP · RIFTPP"
                )
        )

        val initial =
            runCatching {
                runEvent(
                    session,
                    eventKind = 0,
                    controlId = 0
                )
            }

        if (initial.isSuccess) {
            desktop.attachContent(
                id,
                render(
                    session,
                    initial.getOrThrow()
                )
            )
        } else {
            desktop.attachContent(
                id,
                failureView(
                    initial.exceptionOrNull()
                        ?.message
                        ?: "RAPP launch failed"
                )
            )
        }
    }

    private fun runEvent(
        session: Session,
        eventKind: Int,
        controlId: Int
    ): RiftppUiFrame {
        val envelope =
            ByteBuffer
                .allocate(
                    16 +
                        session.program.size
                )
                .order(
                    ByteOrder.LITTLE_ENDIAN
                )
                .put(
                    "RPE2".toByteArray(
                        Charsets.US_ASCII
                    )
                )
                .putInt(
                    session.program.size
                )
                .putInt(eventKind)
                .putInt(controlId)
                .put(session.program)
                .array()

        val output =
            bridge.run(
                session.runtime,
                envelope,
                OUTPUT_BYTES
            )
                ?: error(
                    "Rift++ runtime rejected RAPP event"
                )

        return RiftppUiCodec.parse(
            output
        )
    }

    private fun render(
        session: Session,
        frame: RiftppUiFrame
    ): View {
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
                1 -> {
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

                2 -> {
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

                3 -> {
                    root.addView(
                        Button(activity).apply {
                            text = node.text
                            setOnClickListener {
                                try {
                                    textArea
                                        ?.text
                                        ?.toString()

                                    val next =
                                        runEvent(
                                            session,
                                            eventKind = 1,
                                            controlId = node.id
                                        )

                                    desktop.attachContent(
                                        session.id,
                                        render(
                                            session,
                                            next
                                        )
                                    )
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
