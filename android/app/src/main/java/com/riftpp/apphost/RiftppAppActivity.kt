package com.riftpp.apphost

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.riftpp.editor.RiftppNativeBridge
import com.riftpp.editor.RiftppUiCodec
import com.riftpp.editor.RiftppUiFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TEMPORARY generic Android host for standalone Rift++ App v2 packages.
 * It renders bounded RUI2 primitives and forwards control events only.
 * App-specific behavior remains in the packaged Rift++ artifact/runtime.
 */
class RiftppAppActivity : Activity() {
    companion object {
        private const val PROGRAM_ASSET = "riftpp/app/program.rpa2"
        private const val RUNTIME_ASSET =
            "riftpp/app/runtime.arm32.native.bin"
        private const val MAX_PROGRAM_BYTES = 1024 * 1024
        private const val MAX_RUNTIME_BYTES = 1024 * 1024
        private const val OUTPUT_BYTES = 1024 * 1024
    }

    private val bridge = RiftppNativeBridge()

    private lateinit var program: ByteArray
    private lateinit var runtime: ByteArray

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            program =
                assets.open(PROGRAM_ASSET).use { input ->
                    input.readBytes()
                }
            runtime =
                assets.open(RUNTIME_ASSET).use { input ->
                    input.readBytes()
                }

            require(program.size in 1..MAX_PROGRAM_BYTES) {
                "Rift++ app artifact is out of bounds"
            }
            require(runtime.size in 1..MAX_RUNTIME_BYTES) {
                "Rift++ runtime program is out of bounds"
            }

            render(runEvent(eventKind = 0, controlId = 0))
        } catch (error: Throwable) {
            showFailure(
                error.message
                    ?: error.javaClass.simpleName
            )
        }
    }

    private fun runEvent(
        eventKind: Int,
        controlId: Int
    ): RiftppUiFrame {
        val envelope =
            ByteBuffer
                .allocate(16 + program.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RPE2".toByteArray(Charsets.US_ASCII))
                .putInt(program.size)
                .putInt(eventKind)
                .putInt(controlId)
                .put(program)
                .array()

        val output =
            bridge.run(
                runtime,
                envelope,
                OUTPUT_BYTES
            )
                ?: error(
                    "Rift++ runtime rejected app event"
                )

        return RiftppUiCodec.parse(output)
    }

    private fun render(frame: RiftppUiFrame) {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dp(20),
                    dp(28),
                    dp(20),
                    dp(20)
                )
            }

        var textArea: EditText? = null

        frame.nodes.forEach { node ->
            when (node.kind) {
                1 -> {
                    root.addView(
                        TextView(this).apply {
                            text = node.text
                            textSize = 28f
                            setPadding(
                                0,
                                0,
                                0,
                                dp(20)
                            )
                        }
                    )
                }

                2 -> {
                    val field =
                        EditText(this).apply {
                            setText(node.text)
                            gravity =
                                Gravity.TOP or
                                    Gravity.START
                            inputType =
                                InputType.TYPE_CLASS_TEXT or
                                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                            minLines = 12
                            maxLines = 24
                            setPadding(
                                dp(12),
                                dp(12),
                                dp(12),
                                dp(12)
                            )
                        }

                    textArea = field

                    root.addView(
                        field,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            0,
                            1f
                        )
                    )
                }

                3 -> {
                    root.addView(
                        Button(this).apply {
                            text = node.text

                            setOnClickListener {
                                try {
                                    // Keep current editor text as Android view
                                    // state until the Rift++ runtime returns the
                                    // next canonical frame.
                                    textArea?.text?.toString()

                                    render(
                                        runEvent(
                                            eventKind = 1,
                                            controlId = node.id
                                        )
                                    )
                                } catch (error: Throwable) {
                                    showFailure(
                                        error.message
                                            ?: error.javaClass.simpleName
                                    )
                                }
                            }
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
            }
        }

        setContentView(root)
    }

    private fun showFailure(message: String) {
        setContentView(
            TextView(this).apply {
                text =
                    "Rift++ app failed: " +
                        message
                textSize = 18f
                setPadding(
                    dp(24),
                    dp(40),
                    dp(24),
                    dp(24)
                )
                setTextIsSelectable(true)
            }
        )
    }

    private fun dp(value: Int): Int =
        (
            value *
                resources.displayMetrics.density
        ).toInt()
}
