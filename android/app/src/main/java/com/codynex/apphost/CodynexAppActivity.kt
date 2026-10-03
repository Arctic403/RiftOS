package com.codynex.apphost

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.codynex.editorapp.Vm1Bridge

/**
 * TEMP LIVE-PROOF generic Codynex app host.
 * MUST be replaced by native Codynex/.cx application hosting.
 * This host renders only bounded CXUI primitives and contains no app-specific semantics.
 */
class CodynexAppActivity : Activity() {
    companion object {
        private const val MAX_PROGRAM_BYTES = 64 * 1024
        private const val MAX_VM_BYTES = 64 * 1024
        private const val OUTPUT_BYTES = 64 * 1024
        private const val MAX_EVENT_TEXT_BYTES = 240
        private const val MAX_NODES = 16
        private const val VM_ASSET = "vm2_seed.hex"
        private const val PROGRAM_ASSET = "program.vm2"
    }

    private data class Node(
        val kind: Int,
        val id: Int,
        val text: String
    )

    private data class Frame(
        val nodes: List<Node>
    )

    private lateinit var vm: ByteArray
    private lateinit var program: ByteArray

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            vm = decodeCanonicalHex(readAssetText(VM_ASSET))
            program = assets.open(PROGRAM_ASSET).use { it.readBytes() }

            require(vm.isNotEmpty() && vm.size <= MAX_VM_BYTES) {
                "Codynex VM asset is out of bounds"
            }
            require(program.isNotEmpty() && program.size <= MAX_PROGRAM_BYTES) {
                "Codynex program asset is out of bounds"
            }

            render(runCandidate(ByteArray(0)))
        } catch (error: Throwable) {
            showFailure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun runCandidate(input: ByteArray): Frame {
        val output = ByteArray(OUTPUT_BYTES)
        val raw =
            Vm1Bridge.run(
                vm = vm,
                program = program,
                source = input,
                output = output,
                stepBudget =
                    (program.size * 256 + 20_000)
                        .coerceIn(20_000, 5_000_000)
            )

        require(raw.size >= 2) {
            "Codynex VM bridge returned an invalid result"
        }
        require(raw[0] == 0) {
            "Codynex VM execution failed with status " + raw[0]
        }

        val emitted = raw[1]
        require(emitted in 0..output.size) {
            "Codynex app emitted an invalid CXUI byte count"
        }

        return parseFrame(output.copyOf(emitted))
    }

    private fun parseFrame(bytes: ByteArray): Frame {
        require(bytes.size >= 5) {
            "CXUI frame is too small"
        }
        require(
            (bytes[0].toInt() and 0xff) == 67 &&
                (bytes[1].toInt() and 0xff) == 88 &&
                (bytes[2].toInt() and 0xff) == 85 &&
                (bytes[3].toInt() and 0xff) == 49
        ) {
            "Unsupported CXUI frame"
        }

        val count = bytes[4].toInt() and 0xff
        require(count in 1..MAX_NODES) {
            "CXUI node count is out of bounds"
        }

        val ids = HashSet<Int>()
        val nodes = ArrayList<Node>(count)
        var cursor = 5

        repeat(count) {
            require(cursor + 3 <= bytes.size) {
                "Truncated CXUI node"
            }

            val kind = bytes[cursor].toInt() and 0xff
            val id = bytes[cursor + 1].toInt() and 0xff
            val length = bytes[cursor + 2].toInt() and 0xff
            cursor += 3

            require(kind in 1..3) {
                "Unsupported CXUI primitive"
            }
            require(id != 0 && ids.add(id)) {
                "Invalid or duplicate CXUI control id"
            }
            require(cursor + length <= bytes.size) {
                "Truncated CXUI text"
            }

            val text =
                bytes.copyOfRange(cursor, cursor + length)
                    .toString(Charsets.UTF_8)
            cursor += length
            nodes.add(Node(kind, id, text))
        }

        require(cursor == bytes.size) {
            "CXUI frame has trailing bytes"
        }
        require(nodes.count { it.kind == 2 } <= 1) {
            "CXUI v1 supports at most one editable text area"
        }

        return Frame(nodes)
    }

    private fun render(frame: Frame) {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(28), dp(20), dp(20))
            }

        var textArea: EditText? = null

        frame.nodes.forEach { node ->
            when (node.kind) {
                1 -> {
                    root.addView(
                        TextView(this).apply {
                            text = node.text
                            textSize = 28f
                            setPadding(0, 0, 0, dp(20))
                        }
                    )
                }

                2 -> {
                    val field =
                        EditText(this).apply {
                            setText(node.text)
                            gravity = Gravity.TOP or Gravity.START
                            inputType =
                                InputType.TYPE_CLASS_TEXT or
                                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                            minLines = 12
                            maxLines = 24
                            setPadding(dp(12), dp(12), dp(12), dp(12))
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
                                    render(
                                        runCandidate(
                                            encodeAction(
                                                node.id,
                                                textArea?.text?.toString().orEmpty()
                                            )
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

    private fun encodeAction(
        controlId: Int,
        currentText: String
    ): ByteArray {
        var bounded = currentText
        var bytes = bounded.toByteArray(Charsets.UTF_8)

        while (bytes.size > MAX_EVENT_TEXT_BYTES && bounded.isNotEmpty()) {
            bounded = bounded.dropLast(1)
            bytes = bounded.toByteArray(Charsets.UTF_8)
        }

        val input = ByteArray(bytes.size + 3)
        input[0] = 1
        input[1] = (controlId and 0xff).toByte()
        input[2] = bytes.size.toByte()
        bytes.copyInto(input, destinationOffset = 3)
        return input
    }

    private fun showFailure(message: String) {
        setContentView(
            TextView(this).apply {
                text = "Codynex app failed: " + message
                textSize = 18f
                setPadding(dp(24), dp(40), dp(24), dp(24))
            }
        )
    }

    private fun readAssetText(name: String): String =
        assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun decodeCanonicalHex(text: String): ByteArray {
        val raw = text.trim()
        require(raw.isNotEmpty() && raw.length % 2 == 0) {
            "Codynex VM hex asset is invalid"
        }
        require(raw.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Codynex VM hex asset must be lowercase canonical hex"
        }

        return ByteArray(raw.length / 2) { index ->
            val offset = index * 2
            raw.substring(offset, offset + 2).toInt(16).toByte()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
