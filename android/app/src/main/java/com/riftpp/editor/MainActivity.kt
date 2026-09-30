package com.riftpp.editor

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * TEMPORARY ANDROID/KOTLIN BOOTSTRAP ONLY.
 * This shell exists to prove the standalone Rift++ editor loop and must be
 * replaced by native Rift++ once the required Android-facing runtime slice is proven.
 * Kotlin must never parse .riftpp, emit RPA1, or interpret RPA1.
 */
class MainActivity : Activity() {
    private lateinit var source: EditText
    private lateinit var preview: TextView
    private lateinit var status: TextView
    private lateinit var pipeline: RiftppPipeline
    private lateinit var sourceFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            initializeEditor()
        } catch (error: Throwable) {
            setContentView(
                TextView(this).apply {
                    text =
                        "Rift++ editor bootstrap failed to start:\n\n" +
                            error.toString()
                    setTextIsSelectable(true)
                    setPadding(32, 32, 32, 32)
                }
            )
        }
    }

    private fun initializeEditor() {
        pipeline = RiftppPipeline(this)
        val projectDir = File(filesDir, "projects/default").apply { mkdirs() }
        sourceFile = File(projectDir, "main.riftpp")
        if (!sourceFile.exists()) {
            sourceFile.writeText("text Hello from Rift++\n")
            File(projectDir, "app.rift.json").writeText(
                """{"format":"rift.app/1","name":"Hello Rift++","package":"com.riftpp.hello","entry":"main.riftpp","target":"universal","presentation":"text"}"""
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        root.addView(TextView(this).apply {
            text = "Rift++ Editor"
            textSize = 24f
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun action(label: String, onClick: () -> Unit): Button =
            Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            }

        actions.addView(action("Load") { loadSource() })
        actions.addView(action("Save") { saveSource() })
        actions.addView(action("Compile") { compileOnly() })
        actions.addView(action("Preview") { compileAndPreview() })
        root.addView(actions)

        source = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            minLines = 10
            setHorizontallyScrolling(true)
        }

        val sourceScroll = ScrollView(this).apply {
            addView(
                source,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            sourceScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        status = TextView(this).apply {
            text = "Ready"
            textSize = 14f
        }
        root.addView(status)

        preview = TextView(this).apply {
            text = ""
            textSize = 18f
            setPadding(0, 20, 0, 20)
        }
        root.addView(preview)

        setContentView(root)
        loadSource()
    }

    private fun loadSource() {
        runCatching {
            source.setText(sourceFile.readText())
            status.text = "Loaded " + sourceFile.name
        }.onFailure {
            status.text = "Load failed: " + it.message
        }
    }

    private fun saveSource() {
        runCatching {
            sourceFile.writeText(source.text.toString())
            status.text = "Saved " + sourceFile.name
        }.onFailure {
            status.text = "Save failed: " + it.message
        }
    }

    private fun compileOnly() {
        runCatching {
            val artifact = pipeline.compile(source.text.toString())
            status.text = "Compile OK • " + artifact.size + " byte RPA1"
        }.onFailure {
            status.text = "Compile rejected: " + it.message
        }
    }

    private fun compileAndPreview() {
        runCatching {
            val artifact = pipeline.compile(source.text.toString())
            val shown = pipeline.preview(artifact)
            preview.text = shown.toString(Charsets.US_ASCII)
            status.text =
                "Preview OK • " + artifact.size + " byte RPA1 • " + shown.size + " output bytes"
        }.onFailure {
            preview.text = ""
            status.text = "Preview failed: " + it.message
        }
    }
}
