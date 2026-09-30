package com.riftpp.editor

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/**
 * TEMPORARY ANDROID/KOTLIN BOOTSTRAP ONLY.
 *
 * This shell is the Rift++ project editor proof environment. Kotlin owns only
 * Android widgets, project-file transport, generic RUI2 rendering and APK
 * bootstrap packaging/signing. It must not parse .riftpp, emit RPA2, or
 * implement application behavior.
 *
 * Native Rift++ replaces this shell before S4 promotion.
 */
class MainActivity : Activity() {
    private data class ProjectManifest(
        val name: String,
        val packageName: String,
        val entry: File
    )

    private lateinit var pipeline: RiftppPipeline
    private lateinit var projectRoot: File
    private lateinit var activeFile: File

    private lateinit var headerContainer: LinearLayout
    private lateinit var projectContainer: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var editorView: EditText
    private lateinit var projectList: ListView
    private lateinit var projectAdapter: ArrayAdapter<String>
    private lateinit var fullButton: Button

    private var projectFiles: List<File> = emptyList()
    private var cachedArtifact: ByteArray? = null
    private var dirty = false
    private var rendering = false
    private var fullScreen = false

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

        projectRoot =
            File(filesDir, "projects/default")
                .apply { mkdirs() }
                .canonicalFile

        require(projectRoot.isDirectory) {
            "Could not create Rift++ project root"
        }

        installSampleProjectIfMissing()
        buildUi()
        refreshProjectFiles()

        val manifest = readManifest()
        openFile(manifest.entry)
    }

    private fun installSampleProjectIfMissing() {
        val manifest = File(projectRoot, "app.rift.json")
        val source = File(projectRoot, "main.riftpp")

        if (!manifest.exists()) {
            copyAssetText(
                "riftpp/examples/notepad/app.rift.json",
                manifest
            )
        }

        if (!source.exists()) {
            copyAssetText(
                "riftpp/examples/notepad/main.riftpp",
                source
            )
        } else {
            val legacyProofSource =
                "title Rift++ Notepad\n" +
                    "textarea Type something here...\n" +
                    "button clear Clear\n"

            if (
                source.readText(Charsets.UTF_8) ==
                    legacyProofSource
            ) {
                copyAssetText(
                    "riftpp/examples/notepad/main.riftpp",
                    source
                )
            }
        }
    }

    private fun copyAssetText(
        assetPath: String,
        output: File
    ) {
        output.writeText(
            assets.open(assetPath)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() },
            Charsets.UTF_8
        )
    }

    private fun buildUi() {
        headerContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL

                addView(
                    TextView(this@MainActivity).apply {
                        text = "Rift++ Editor • App v2"
                        textSize = 23f
                        setPadding(16, 10, 16, 2)
                    }
                )

                addView(
                    TextView(this@MainActivity).apply {
                        text =
                            "TEMP Kotlin platform shell • " +
                                "Rift++ owns compile/runtime semantics"
                        textSize = 12f
                        setPadding(16, 0, 16, 6)
                    }
                )
            }

        statusView =
            TextView(this).apply {
                text = "Starting..."
                setPadding(16, 6, 16, 8)
                setTextIsSelectable(true)
            }

        projectAdapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_activated_1,
                mutableListOf()
            )

        projectList =
            ListView(this).apply {
                adapter = projectAdapter
                choiceMode = ListView.CHOICE_MODE_SINGLE

                onItemClickListener =
                    android.widget.AdapterView.OnItemClickListener {
                            _,
                            _,
                            position,
                            _ ->
                        val file =
                            projectFiles.getOrNull(position)
                                ?: return@OnItemClickListener

                        runCatching {
                            saveIfDirty()
                            openFile(file)
                        }.onFailure {
                            statusView.text =
                                "Open failed: " +
                                    (
                                        it.message
                                            ?: it.javaClass.simpleName
                                    )
                        }
                    }
            }

        projectContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL

                addView(
                    TextView(this@MainActivity).apply {
                        text = "Project"
                        setPadding(16, 4, 16, 2)
                    }
                )

                addView(
                    projectList,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(120)
                    )
                )

                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL

                        addView(
                            actionButton("New File") {
                                promptNewFile()
                            },
                            weightedButtonParams()
                        )

                        addView(
                            actionButton("Refresh") {
                                runCatching {
                                    saveIfDirty()
                                    refreshProjectFiles()
                                    statusView.text =
                                        "Project refreshed"
                                }.onFailure {
                                    statusView.text =
                                        "Refresh failed: " +
                                            it.message
                                }
                            },
                            weightedButtonParams()
                        )
                    }
                )
            }

        editorView =
            EditText(this).apply {
                gravity =
                    Gravity.TOP or
                        Gravity.START
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setHorizontallyScrolling(true)
                minLines = 12
                setPadding(16, 12, 16, 12)

                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int
                        ) = Unit

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                        ) {
                            if (!rendering) {
                                dirty = true
                                cachedArtifact = null
                                renderStatus()
                            }
                        }

                        override fun afterTextChanged(
                            s: Editable?
                        ) = Unit
                    }
                )
            }

        val actionRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL

                addView(
                    actionButton("Save") {
                        saveActiveFile()
                    }
                )
                addView(
                    actionButton("Reload") {
                        openFile(activeFile)
                    }
                )
                addView(
                    actionButton("Compile") {
                        compileProject()
                    }
                )
                addView(
                    actionButton("Preview") {
                        previewProject()
                    }
                )
                addView(
                    actionButton("Pack APK") {
                        packStandaloneApk()
                    }
                )

                fullButton =
                    actionButton("Full") {
                        toggleFullScreen()
                    }
                addView(fullButton)
            }

        val actionScroll =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = true
                addView(actionRow)
            }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(headerContainer)
                addView(statusView)
                addView(projectContainer)
                addView(actionScroll)
                addView(
                    editorView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                )
            }

        setContentView(root)
    }

    private fun actionButton(
        label: String,
        action: () -> Unit
    ): Button =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

    private fun weightedButtonParams():
        LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )

    private fun refreshProjectFiles() {
        projectFiles =
            projectRoot
                .walkTopDown()
                .filter { file ->
                    file.isFile &&
                        file.canonicalFile
                            .toPath()
                            .startsWith(
                                projectRoot.toPath()
                            )
                }
                .sortedBy { file ->
                    relativePath(file)
                }
                .toList()

        projectAdapter.clear()
        projectAdapter.addAll(
            projectFiles.map(::relativePath)
        )
        projectAdapter.notifyDataSetChanged()

        if (::activeFile.isInitialized) {
            val index =
                projectFiles.indexOfFirst {
                    it.canonicalFile ==
                        activeFile.canonicalFile
                }

            if (index >= 0) {
                projectList.setItemChecked(
                    index,
                    true
                )
            }
        }
    }

    private fun openFile(file: File) {
        val canonical = file.canonicalFile

        require(
            canonical.toPath()
                .startsWith(projectRoot.toPath())
        ) {
            "Project file escaped project root"
        }
        require(canonical.isFile) {
            "Project file is missing"
        }
        require(canonical.length() <= 256 * 1024) {
            "Project file is too large for editor"
        }

        activeFile = canonical
        rendering = true

        try {
            editorView.setText(
                canonical.readText(
                    Charsets.UTF_8
                )
            )
            editorView.setSelection(
                editorView.text.length
            )
        } finally {
            rendering = false
        }

        dirty = false
        cachedArtifact = null
        refreshProjectFiles()

        renderStatus(
            "Loaded " +
                relativePath(canonical)
        )
    }

    private fun saveIfDirty() {
        if (dirty) {
            saveActiveFile(
                updateStatus = false
            )
            require(!dirty) {
                "active file could not be saved"
            }
        }
    }

    private fun saveActiveFile(
        updateStatus: Boolean = true
    ) {
        runCatching {
            require(::activeFile.isInitialized) {
                "No project file is open"
            }

            val canonical =
                activeFile.canonicalFile

            require(
                canonical.toPath()
                    .startsWith(
                        projectRoot.toPath()
                    )
            ) {
                "Save path escaped project root"
            }

            canonical.writeText(
                editorView.text.toString(),
                Charsets.UTF_8
            )

            dirty = false
            cachedArtifact = null

            if (updateStatus) {
                renderStatus(
                    "Saved " +
                        relativePath(canonical)
                )
            }
        }.onFailure {
            renderStatus(
                "Save failed: " +
                    (
                        it.message
                            ?: it.javaClass.simpleName
                    )
            )
        }
    }

    private fun compileProject(): ByteArray? {
        var artifact: ByteArray? = null

        runCatching {
            saveIfDirty()

            val manifest = readManifest()
            val source =
                manifest.entry.readText(
                    Charsets.UTF_8
                )

            artifact =
                pipeline.compile(source)

            cachedArtifact =
                artifact?.copyOf()

            renderStatus(
                "Compile OK • " +
                    manifest.name +
                    " • " +
                    (artifact?.size ?: 0) +
                    " byte RPA2"
            )
        }.onFailure {
            cachedArtifact = null

            renderStatus(
                "Compile rejected: " +
                    (
                        it.message
                            ?: it.javaClass.simpleName
                    )
            )
        }

        return artifact
    }

    private fun previewProject() {
        val artifact =
            compileProject()
                ?: return

        runCatching {
            val frame =
                RiftppUiCodec.parse(
                    pipeline.render(
                        artifact = artifact,
                        eventKind = 0,
                        controlId = 0
                    )
                )

            renderStatus(
                "Preview OK • " +
                    artifact.size +
                    " byte RPA2 • " +
                    frame.nodes.size +
                    " RUI2 nodes"
            )

            showPreviewDialog(
                artifact,
                frame
            )
        }.onFailure {
            renderStatus(
                "Preview failed: " +
                    (
                        it.message
                            ?: it.javaClass.simpleName
                    )
            )
        }
    }

    private fun showPreviewDialog(
        artifact: ByteArray,
        frame: RiftppUiFrame
    ) {
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dp(20),
                    dp(12),
                    dp(20),
                    dp(12)
                )
            }

        var textArea: EditText? = null
        val buttons =
            mutableListOf<
                Pair<Button, RiftppUiNode>
            >()

        frame.nodes.forEach { node ->
            when (node.kind) {
                1 -> {
                    content.addView(
                        TextView(this).apply {
                            text = node.text
                            textSize = 22f
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
                        EditText(this).apply {
                            setText(node.text)
                            gravity =
                                Gravity.TOP or
                                    Gravity.START
                            inputType =
                                InputType.TYPE_CLASS_TEXT or
                                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                            minLines = 10
                            maxLines = 18
                            setPadding(
                                dp(12),
                                dp(12),
                                dp(12),
                                dp(12)
                            )
                        }

                    textArea = field

                    content.addView(
                        field,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(320)
                        )
                    )
                }

                3 -> {
                    val button =
                        Button(this).apply {
                            text = node.text
                        }

                    buttons +=
                        button to node

                    content.addView(button)
                }
            }
        }

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(
                    "Live Rift++ preview"
                )
                .setView(content)
                .setNegativeButton(
                    "Close",
                    null
                )
                .create()

        buttons.forEach { pair ->
            val button = pair.first
            val node = pair.second

            button.setOnClickListener {
                button.isEnabled = false

                renderStatus(
                    "Running Rift++ action " +
                        node.id +
                        "..."
                )

                Thread {
                    val result =
                        runCatching {
                            RiftppUiCodec.parse(
                                pipeline.render(
                                    artifact =
                                        artifact,
                                    eventKind = 1,
                                    controlId =
                                        node.id
                                )
                            )
                        }

                    runOnUiThread {
                        button.isEnabled = true

                        result.onSuccess {
                            dialog.dismiss()

                            renderStatus(
                                "Rift++ action " +
                                    node.id +
                                    " passed"
                            )

                            showPreviewDialog(
                                artifact,
                                it
                            )
                        }.onFailure {
                            renderStatus(
                                "Action failed: " +
                                    (
                                        it.message
                                            ?: it.javaClass.simpleName
                                    )
                            )
                        }
                    }
                }.start()
            }
        }

        dialog.show()
    }

    private fun packStandaloneApk() {
        runCatching {
            saveIfDirty()
            readManifest()
        }.onFailure {
            renderStatus(
                "Pack APK failed: " +
                    (
                        it.message
                            ?: it.javaClass.simpleName
                    )
            )
        }.onSuccess { manifest ->
            renderStatus(
                "Compiling and packaging standalone APK..."
            )

            Thread {
                val message =
                    runCatching {
                        require(
                            !android.os.Process
                                .is64Bit()
                        ) {
                            "App v2 APK proof is ARM32-only for now"
                        }

                        val source =
                            manifest.entry
                                .readText(
                                    Charsets.UTF_8
                                )
                        val artifact =
                            pipeline.compile(
                                source
                            )
                        val runtime =
                            pipeline
                                .runtimeProgramForPackaging()

                        val receipt =
                            RiftppApkBuilder(this)
                                .build(
                                    artifact =
                                        artifact,
                                    runtime =
                                        runtime,
                                    packageName =
                                        manifest.packageName,
                                    sourcePath =
                                        relativePath(
                                            manifest.entry
                                        )
                                )

                        buildString {
                            append(
                                "Standalone APK ready"
                            )
                            append(
                                "\nPackage: "
                            )
                            append(
                                receipt.packageName
                            )
                            append(
                                "\nActivity: "
                            )
                            append(
                                receipt.activityName
                            )
                            append(
                                "\nSigned + verified: "
                            )
                            append(
                                receipt.signedApk
                                    .absolutePath
                            )
                            append(
                                "\nDownloads: "
                            )
                            append(
                                receipt.publishedUri
                                    ?: "private-only fallback"
                            )
                            append(
                                "\nAPK SHA-256: "
                            )
                            append(
                                receipt.apkSha256
                            )
                            append(
                                "\nRPA2 SHA-256: "
                            )
                            append(
                                receipt.artifactSha256
                            )
                            append(
                                "\nRuntime SHA-256: "
                            )
                            append(
                                receipt.runtimeSha256
                            )
                        }
                    }.fold(
                        onSuccess = { it },
                        onFailure = {
                            "Pack APK failed: " +
                                (
                                    it.message
                                        ?: it.javaClass.simpleName
                                )
                        }
                    )

                runOnUiThread {
                    renderStatus(message)
                }
            }.start()
        }
    }

    private fun readManifest(): ProjectManifest {
        val manifestFile =
            File(
                projectRoot,
                "app.rift.json"
            ).canonicalFile

        require(
            manifestFile.isFile &&
                manifestFile.toPath()
                    .startsWith(
                        projectRoot.toPath()
                    )
        ) {
            "app.rift.json is missing"
        }

        val json =
            JSONObject(
                manifestFile.readText(
                    Charsets.UTF_8
                )
            )

        require(
            json.getString("format") ==
                "rift.app/2"
        ) {
            "unsupported Rift++ project format"
        }

        val name =
            json.getString("name")
                .trim()

        require(name.isNotEmpty()) {
            "project name is empty"
        }

        val packageName =
            json.getString("package")
                .trim()

        require(
            Regex(
                "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"
            ).matches(packageName)
        ) {
            "project package is invalid"
        }

        require(
            json.optString(
                "presentation"
            ) == "rui2"
        ) {
            "App v2 requires RUI2 presentation"
        }

        val entryName =
            json.getString("entry")

        require(
            entryName.isNotBlank() &&
                !entryName.contains("..")
        ) {
            "project entry is invalid"
        }

        val entry =
            File(
                projectRoot,
                entryName
            ).canonicalFile

        require(
            entry.toPath()
                .startsWith(
                    projectRoot.toPath()
                ) &&
                entry.isFile
        ) {
            "project entry is missing or escaped root"
        }

        return ProjectManifest(
            name = name,
            packageName =
                packageName,
            entry = entry
        )
    }

    private fun promptNewFile() {
        val input =
            EditText(this).apply {
                hint = "example.riftpp"
                isSingleLine = true
            }

        AlertDialog.Builder(this)
            .setTitle("New project file")
            .setView(input)
            .setNegativeButton(
                "Cancel",
                null
            )
            .setPositiveButton(
                "Create"
            ) { _, _ ->
                runCatching {
                    saveIfDirty()

                    val name =
                        input.text
                            .toString()
                            .trim()

                    require(
                        Regex(
                            "^[A-Za-z0-9._-]{1,80}$"
                        ).matches(name)
                    ) {
                        "invalid file name"
                    }

                    require(
                        name != "." &&
                            name != ".."
                    ) {
                        "invalid file name"
                    }

                    val file =
                        File(
                            projectRoot,
                            name
                        ).canonicalFile

                    require(
                        file.parentFile ==
                            projectRoot
                    ) {
                        "new file escaped project root"
                    }

                    require(
                        !file.exists()
                    ) {
                        "file already exists"
                    }

                    file.writeText(
                        "",
                        Charsets.UTF_8
                    )

                    refreshProjectFiles()
                    openFile(file)
                }.onFailure {
                    renderStatus(
                        "New file failed: " +
                            (
                                it.message
                                    ?: it.javaClass.simpleName
                            )
                    )
                }
            }
            .show()
    }

    private fun toggleFullScreen() {
        fullScreen = !fullScreen

        val visibility =
            if (fullScreen) {
                View.GONE
            } else {
                View.VISIBLE
            }

        headerContainer.visibility =
            visibility
        statusView.visibility =
            visibility
        projectContainer.visibility =
            visibility
        fullButton.text =
            if (fullScreen) {
                "Exit Full"
            } else {
                "Full"
            }
    }

    private fun renderStatus(
        message: String? = null
    ) {
        if (!::statusView.isInitialized) {
            return
        }

        val file =
            if (::activeFile.isInitialized) {
                relativePath(activeFile)
            } else {
                "none"
            }

        statusView.text =
            buildString {
                if (message != null) {
                    append(message)
                    append("\n")
                }

                append("File: ")
                append(file)

                if (dirty) {
                    append(" • modified")
                }

                if (cachedArtifact != null) {
                    append(" • compiled ")
                    append(
                        cachedArtifact?.size
                    )
                    append(" bytes")
                }
            }
    }

    private fun relativePath(
        file: File
    ): String =
        projectRoot
            .toPath()
            .relativize(
                file.canonicalFile
                    .toPath()
            )
            .toString()
            .replace(
                File.separatorChar,
                '/'
            )

    private fun dp(value: Int): Int =
        (
            value *
                resources.displayMetrics.density
        ).toInt()
}
