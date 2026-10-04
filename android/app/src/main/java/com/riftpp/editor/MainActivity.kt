package com.riftpp.editor

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
 * Android/Kotlin host for the Rift++ editor.
 *
 * Kotlin owns Android lifecycle, widgets, IME/platform transport and packaging
 * glue. Rift++ owns language/runtime semantics, rendering and product-facing UI
 * behavior across the narrow host boundary.
 */
class MainActivity : Activity() {
    private lateinit var pipeline: RiftppPipeline
    private lateinit var projectRoot: File
    private lateinit var workspace: RiftppWorkspace

    private lateinit var activePath: String
    private var selectedPath: String? = null
    private var projectEntries: List<RiftppWorkspaceEntry> =
        emptyList()
    private val openTabs =
        mutableListOf<String>()

    private lateinit var headerContainer: LinearLayout
    private lateinit var projectContainer: LinearLayout
    private lateinit var tabsContainer: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var editorView: EditText
    private lateinit var projectList: ListView
    private lateinit var projectAdapter: ArrayAdapter<String>
    private lateinit var fullButton: Button

    private var cachedArtifact: ByteArray? = null
    private var dirty = false
    private var rendering = false
    private var fullScreen = false
    private var projectPanelVisible = false

    private val uiBackground = Color.rgb(18, 18, 20)
    private val uiSurface = Color.rgb(27, 28, 31)
    private val uiSurfaceRaised = Color.rgb(36, 38, 42)
    private val uiBorder = Color.rgb(58, 61, 67)
    private val uiText = Color.rgb(232, 234, 238)
    private val uiMuted = Color.rgb(151, 156, 166)
    private val uiAccent = Color.rgb(241, 137, 46)
    private val uiAccentSoft = Color.rgb(74, 48, 28)

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
            File(
                filesDir,
                "projects/default"
            )
                .apply { mkdirs() }
                .canonicalFile

        workspace =
            RiftppWorkspace(
                projectRoot
            )

        installSampleProjectIfMissing()
        buildUi()
        refreshProjectTree()

        val manifest =
            RiftppProjectModel.read(
                workspace
            )

        openDocument(
            manifest.entry
        )
    }

    private fun installSampleProjectIfMissing() {
        val manifestFile =
            File(
                projectRoot,
                "app.rift.json"
            )

        if (!manifestFile.exists()) {
            installProject3Sample()
            return
        }

        val manifestText =
            manifestFile.readText(
                Charsets.UTF_8
            )

        val json =
            runCatching {
                JSONObject(
                    manifestText
                )
            }.getOrNull()
                ?: return

        if (
            json.optString(
                "format"
            ) != "rift.app/2"
        ) {
            return
        }

        val legacySource =
            File(
                projectRoot,
                "main.riftpp"
            )

        if (!legacySource.isFile) {
            return
        }

        val knownStructuredV2 =
            "app Notepad {\n" +
                "    title(\"Rift++ Notepad\");\n" +
                "    let note = textarea(\"Type something here...\");\n" +
                "    button(\"Clear\") {\n" +
                "        note.clear();\n" +
                "    }\n" +
                "}\n"

        if (
            legacySource.readText(
                Charsets.UTF_8
            ) != knownStructuredV2
        ) {
            return
        }

        installProject3Sample()

        require(
            legacySource.delete()
        ) {
            "could not remove migrated legacy source"
        }
    }

    private fun installProject3Sample() {
        val src =
            File(
                projectRoot,
                "src"
            )

        require(
            src.mkdirs() ||
                src.isDirectory
        ) {
            "could not create sample src directory"
        }

        copyAssetText(
            "riftpp/examples/notepad/app.rift.json",
            File(
                projectRoot,
                "app.rift.json"
            )
        )
        copyAssetText(
            "riftpp/examples/notepad/src/main.riftpp",
            File(
                src,
                "main.riftpp"
            )
        )
        copyAssetText(
            "riftpp/examples/notepad/src/ui.riftpp",
            File(
                src,
                "ui.riftpp"
            )
        )
    }

    private fun copyAssetText(
        assetPath: String,
        output: File
    ) {
        output.parentFile?.let { parent ->
            require(
                parent.mkdirs() ||
                    parent.isDirectory
            ) {
                "could not create asset destination"
            }
        }

        output.writeText(
            assets.open(assetPath)
                .bufferedReader(
                    Charsets.UTF_8
                )
                .use { it.readText() },
            Charsets.UTF_8
        )
    }

    private fun buildUi() {
        window.statusBarColor = uiBackground
        window.navigationBarColor = uiBackground

        headerContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dp(12),
                    dp(8),
                    dp(8),
                    dp(8)
                )
                background =
                    roundedBackground(
                        uiSurface,
                        uiBorder,
                        0
                    )

                addView(
                    LinearLayout(
                        this@MainActivity
                    ).apply {
                        orientation = LinearLayout.VERTICAL

                        addView(
                            TextView(
                                this@MainActivity
                            ).apply {
                                text = "RIFT++"
                                textSize = 18f
                                typeface =
                                    Typeface.create(
                                        Typeface.DEFAULT,
                                        Typeface.BOLD
                                    )
                                setTextColor(uiText)
                            }
                        )

                        addView(
                            TextView(
                                this@MainActivity
                            ).apply {
                                text = "default"
                                textSize = 11f
                                setTextColor(uiMuted)
                            }
                        )
                    },
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )

                addView(
                    actionButton(
                        "Files"
                    ) {
                        projectPanelVisible =
                            !projectPanelVisible
                        projectContainer.visibility =
                            if (
                                projectPanelVisible &&
                                !fullScreen
                            ) {
                                View.VISIBLE
                            } else {
                                View.GONE
                            }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(40)
                    ).apply {
                        setMargins(
                            0,
                            0,
                            dp(6),
                            0
                        )
                    }
                )

                fullButton =
                    actionButton(
                        "Focus"
                    ) {
                        toggleFullScreen()
                    }

                addView(
                    fullButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(40)
                    )
                )
            }

        statusView =
            TextView(this).apply {
                text = "Ready"
                textSize = 11f
                setTextColor(uiMuted)
                setPadding(
                    dp(12),
                    dp(6),
                    dp(12),
                    dp(6)
                )
                maxLines = 2
                setTextIsSelectable(true)
                setBackgroundColor(uiSurface)
            }

        projectAdapter =
            ArrayAdapter(
                this,
                android.R.layout
                    .simple_list_item_activated_1,
                mutableListOf()
            )

        projectList =
            ListView(this).apply {
                adapter = projectAdapter
                choiceMode =
                    ListView.CHOICE_MODE_SINGLE
                dividerHeight = 0
                setBackgroundColor(uiSurface)

                onItemClickListener =
                    android.widget.AdapterView
                        .OnItemClickListener {
                                _,
                                _,
                                position,
                                _ ->
                            val entry =
                                projectEntries
                                    .getOrNull(
                                        position
                                    )
                                    ?: return@OnItemClickListener

                            selectedPath =
                                entry.relativePath

                            if (!entry.directory) {
                                runCatching {
                                    saveIfDirty()
                                    openDocument(
                                        entry.relativePath
                                    )
                                }.onFailure {
                                    renderStatus(
                                        "Open failed: " +
                                            errorText(it)
                                    )
                                }
                            } else {
                                renderStatus(
                                    "Selected folder: " +
                                        entry.relativePath
                                )
                            }
                        }
            }

        val projectActions =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL

                addView(
                    actionButton(
                        "+ File"
                    ) {
                        promptNewFile()
                    },
                    weightedButtonParams()
                )
                addView(
                    actionButton(
                        "+ Folder"
                    ) {
                        promptNewFolder()
                    },
                    weightedButtonParams()
                )
                addView(
                    actionButton(
                        "Rename"
                    ) {
                        promptRename()
                    },
                    weightedButtonParams()
                )
                addView(
                    actionButton(
                        "Delete"
                    ) {
                        confirmDelete()
                    },
                    weightedButtonParams()
                )
                addView(
                    actionButton(
                        "Set Entry"
                    ) {
                        setSelectedAsEntry()
                    },
                    weightedButtonParams()
                )
                addView(
                    actionButton(
                        "Add Source"
                    ) {
                        addSelectedSource()
                    },
                    weightedButtonParams()
                )
                addView(
                    actionButton(
                        "Refresh"
                    ) {
                        runCatching {
                            saveIfDirty()
                            refreshProjectTree()
                            renderStatus(
                                "Project refreshed"
                            )
                        }.onFailure {
                            renderStatus(
                                "Refresh failed: " +
                                    errorText(it)
                            )
                        }
                    },
                    weightedButtonParams()
                )
            }

        projectContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(
                    dp(8),
                    dp(8),
                    dp(8),
                    dp(8)
                )
                background =
                    roundedBackground(
                        uiSurface,
                        uiBorder,
                        0
                    )

                addView(
                    LinearLayout(
                        this@MainActivity
                    ).apply {
                        orientation =
                            LinearLayout.HORIZONTAL
                        gravity =
                            Gravity.CENTER_VERTICAL

                        addView(
                            TextView(
                                this@MainActivity
                            ).apply {
                                text = "EXPLORER"
                                textSize = 12f
                                typeface =
                                    Typeface.create(
                                        Typeface.DEFAULT,
                                        Typeface.BOLD
                                    )
                                setTextColor(uiText)
                            },
                            LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams
                                    .WRAP_CONTENT,
                                1f
                            )
                        )

                        addView(
                            TextView(
                                this@MainActivity
                            ).apply {
                                text = "projects/default"
                                textSize = 10f
                                setTextColor(uiMuted)
                            }
                        )
                    }
                )

                addView(
                    projectList,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams
                            .MATCH_PARENT,
                        dp(190)
                    ).apply {
                        setMargins(
                            0,
                            dp(6),
                            0,
                            dp(6)
                        )
                    }
                )

                addView(
                    HorizontalScrollView(
                        this@MainActivity
                    ).apply {
                        isHorizontalScrollBarEnabled =
                            false
                        addView(projectActions)
                    }
                )
            }

        tabsContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(
                    dp(6),
                    dp(4),
                    dp(6),
                    dp(4)
                )
            }

        val tabScroll =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(uiSurface)
                addView(tabsContainer)
            }

        editorView =
            EditText(this).apply {
                gravity =
                    Gravity.TOP or
                        Gravity.START
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType
                            .TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType
                            .TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setHorizontallyScrolling(true)
                minLines = 12
                textSize = 14f
                typeface =
                    Typeface.create(
                        Typeface.MONOSPACE,
                        Typeface.NORMAL
                    )
                setTextColor(uiText)
                setHintTextColor(uiMuted)
                setBackgroundColor(uiBackground)
                setPadding(
                    dp(14),
                    dp(12),
                    dp(14),
                    dp(12)
                )

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
                                renderTabs()
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
                setPadding(
                    dp(6),
                    dp(5),
                    dp(6),
                    dp(5)
                )

                addView(
                    actionButton(
                        "Save"
                    ) {
                        saveActiveFile()
                    }
                )
                addView(
                    actionButton(
                        "Reload"
                    ) {
                        if (
                            ::activePath
                                .isInitialized
                        ) {
                            openDocument(
                                activePath
                            )
                        }
                    }
                )
                addView(
                    actionButton(
                        "Close"
                    ) {
                        closeActiveTab()
                    }
                )
                addView(
                    actionButton(
                        "Compile",
                        primary = true
                    ) {
                        compileProject()
                    }
                )
                addView(
                    actionButton(
                        "Preview"
                    ) {
                        previewProject()
                    }
                )
                addView(
                    actionButton(
                        "Debug APK"
                    ) {
                        packStandaloneApk(
                            debug = true
                        )
                    }
                )
                addView(
                    actionButton(
                        "Pack APK"
                    ) {
                        packStandaloneApk()
                    }
                )
            }

        val actionScroll =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(uiSurface)
                addView(actionRow)
            }

        val editorShell =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(uiBackground)

                addView(tabScroll)
                addView(actionScroll)
                addView(
                    editorView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams
                            .MATCH_PARENT,
                        0,
                        1f
                    )
                )
            }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(uiBackground)

                addView(headerContainer)
                addView(projectContainer)
                addView(
                    editorShell,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams
                            .MATCH_PARENT,
                        0,
                        1f
                    )
                )
                addView(statusView)
            }

        setContentView(root)
    }

    private fun actionButton(
        label: String,
        primary: Boolean = false,
        action: () -> Unit
    ): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(
                if (primary) Color.BLACK else uiText
            )
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(
                dp(12),
                dp(7),
                dp(12),
                dp(7)
            )
            background =
                roundedBackground(
                    if (primary) uiAccent else uiSurfaceRaised,
                    if (primary) uiAccent else uiBorder,
                    7
                )
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(40)
                ).apply {
                    setMargins(
                        0,
                        0,
                        dp(6),
                        0
                    )
                }
            setOnClickListener {
                action()
            }
        }

    private fun roundedBackground(
        fillColor: Int,
        strokeColor: Int = fillColor,
        radiusDp: Int = 8
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun weightedButtonParams():
        LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            dp(118),
            dp(42)
        ).apply {
            setMargins(
                0,
                0,
                dp(6),
                0
            )
        }

    private fun refreshProjectTree() {
        projectEntries =
            workspace.listRecursive()

        projectAdapter.clear()
        projectAdapter.addAll(
            projectEntries.map { entry ->
                buildString {
                    repeat(
                        entry.depth
                    ) {
                        append("    ")
                    }

                    append(
                        if (
                            entry.directory
                        ) {
                            "[DIR] "
                        } else {
                            "[FILE] "
                        }
                    )

                    append(
                        entry.name
                    )
                }
            }
        )
        projectAdapter
            .notifyDataSetChanged()

        selectedPath?.let { selected ->
            val index =
                projectEntries
                    .indexOfFirst {
                        it.relativePath ==
                            selected
                    }

            if (index >= 0) {
                projectList
                    .setItemChecked(
                        index,
                        true
                    )
            }
        }

        renderTabs()
    }

    private fun openDocument(path: String) {
        val file =
            workspace.file(path)

        require(file.isFile) {
            "project file is missing"
        }

        val text =
            workspace.readText(
                path
            )

        activePath = path
        selectedPath = path

        if (
            path !in openTabs
        ) {
            openTabs += path
        }

        rendering = true

        try {
            editorView.setText(
                text
            )
            editorView.setSelection(
                editorView.text.length
            )
        } finally {
            rendering = false
        }

        dirty = false
        cachedArtifact = null

        refreshProjectTree()

        renderStatus(
            "Loaded " +
                path
        )
    }

    private fun switchTab(path: String) {
        if (
            ::activePath
                .isInitialized &&
            activePath == path
        ) {
            return
        }

        runCatching {
            saveIfDirty()
            openDocument(path)
        }.onFailure {
            renderStatus(
                "Tab switch failed: " +
                    errorText(
                        it
                    )
            )
        }
    }

    private fun closeActiveTab() {
        if (
            !::activePath
                .isInitialized
        ) {
            return
        }

        if (openTabs.size <= 1) {
            renderStatus(
                "Keep at least one document tab open"
            )
            return
        }

        runCatching {
            saveIfDirty()

            val closing =
                activePath

            openTabs.remove(
                closing
            )

            openDocument(
                openTabs.last()
            )
        }.onFailure {
            renderStatus(
                "Close tab failed: " +
                    errorText(
                        it
                    )
            )
        }
    }

    private fun renderTabs() {
        if (
            !::tabsContainer
                .isInitialized
        ) {
            return
        }

        tabsContainer
            .removeAllViews()

        openTabs.forEach { path ->
            val active =
                ::activePath.isInitialized &&
                    activePath == path

            tabsContainer.addView(
                TextView(this).apply {
                    text =
                        buildString {
                            append(
                                File(path)
                                    .name
                            )

                            if (active && dirty) {
                                append("  •")
                            }
                        }
                    textSize = 12f
                    typeface =
                        Typeface.create(
                            Typeface.DEFAULT,
                            if (active) {
                                Typeface.BOLD
                            } else {
                                Typeface.NORMAL
                            }
                        )
                    setTextColor(
                        if (active) {
                            uiText
                        } else {
                            uiMuted
                        }
                    )
                    setPadding(
                        dp(14),
                        dp(8),
                        dp(14),
                        dp(8)
                    )
                    background =
                        roundedBackground(
                            if (active) {
                                uiAccentSoft
                            } else {
                                uiSurface
                            },
                            if (active) {
                                uiAccent
                            } else {
                                uiSurface
                            },
                            6
                        )
                    setOnClickListener {
                        switchTab(path)
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(
                        0,
                        0,
                        dp(5),
                        0
                    )
                }
            )
        }
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
            require(
                ::activePath
                    .isInitialized
            ) {
                "No project file is open"
            }

            workspace.writeText(
                activePath,
                editorView.text
                    .toString()
            )

            dirty = false
            cachedArtifact = null
            renderTabs()

            if (updateStatus) {
                renderStatus(
                    "Saved " +
                        activePath
                )
            }
        }.onFailure {
            renderStatus(
                "Save failed: " +
                    errorText(
                        it
                    )
            )
        }
    }

    private fun compileProject():
        ByteArray? {
        var artifact:
            ByteArray? = null

        runCatching {
            saveIfDirty()

            val manifest =
                RiftppProjectModel.read(
                    workspace
                )

            artifact =
                compileManifest(
                    manifest
                )

            cachedArtifact =
                artifact?.copyOf()

            renderStatus(
                "Compile OK • " +
                    manifest.name +
                    " • " +
                    manifest.sources.size +
                    " source file(s) • " +
                    (
                        artifact
                            ?.size
                            ?: 0
                        ) +
                    " byte RPA2"
            )
        }.onFailure {
            cachedArtifact = null

            renderStatus(
                "Compile rejected: " +
                    errorText(
                        it
                    )
            )
        }

        return artifact
    }

    private fun compileManifest(
        manifest:
            RiftppProjectManifest
    ): ByteArray =
        if (
            manifest.format ==
                "rift.app/3"
        ) {
            pipeline.compileProject(
                RiftppProjectModel
                    .buildCompileInput(
                        workspace,
                        manifest
                    )
            )
        } else {
            pipeline.compile(
                workspace.readText(
                    manifest.entry
                )
            )
        }

    private fun previewProject() {
        val artifact =
            compileProject()
                ?: return

        runCatching {
            val frame =
                RiftppUiCodec.parse(
                    pipeline.render(
                        artifact =
                            artifact,
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
                    errorText(
                        it
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
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(20),
                    dp(12),
                    dp(20),
                    dp(12)
                )
            }

        val buttons =
            mutableListOf<
                Pair<
                    Button,
                    RiftppUiNode
                >
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
                    content.addView(
                        EditText(this).apply {
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
                                        .TYPE_TEXT_FLAG_CAP_SENTENCES
                            minLines = 10
                            maxLines = 18
                            setPadding(
                                dp(12),
                                dp(12),
                                dp(12),
                                dp(12)
                            )
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams
                                .MATCH_PARENT,
                            dp(320)
                        )
                    )
                }

                3 -> {
                    val button =
                        Button(this).apply {
                            text =
                                node.text
                        }

                    buttons +=
                        button to node

                    content.addView(
                        button
                    )
                }
            }
        }

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(
                    "Live Rift++ preview"
                )
                .setView(
                    content
                )
                .setNegativeButton(
                    "Close",
                    null
                )
                .create()

        buttons.forEach { pair ->
            val button =
                pair.first
            val node =
                pair.second

            button.setOnClickListener {
                button.isEnabled =
                    false

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
                        button.isEnabled =
                            true

                        result.onSuccess {
                            dialog.dismiss()
                            showPreviewDialog(
                                artifact,
                                it
                            )
                        }.onFailure {
                            renderStatus(
                                "Action failed: " +
                                    errorText(
                                        it
                                    )
                            )
                        }
                    }
                }.start()
            }
        }

        dialog.show()
    }

    private fun packStandaloneApk(
        debug: Boolean = false
    ) {
        runCatching {
            saveIfDirty()
            RiftppProjectModel
                .read(
                    workspace
                )
        }.onFailure {
            renderStatus(
                "Pack APK failed: " +
                    errorText(
                        it
                    )
            )
        }.onSuccess { manifest ->
            renderStatus(
                if (debug) {
                    "Compiling project and packaging side-by-side debug APK..."
                } else {
                    "Compiling project and packaging standalone APK..."
                }
            )

            Thread {
                val message =
                    runCatching {
                        require(
                            !android.os.Process
                                .is64Bit()
                        ) {
                            "Project APK proof is ARM32-only for now"
                        }

                        val artifact =
                            compileManifest(
                                manifest
                            )
                        val runtime =
                            pipeline
                                .runtimeProgramForPackaging()

                        val packageName =
                            if (debug) {
                                manifest.packageName +
                                    ".debug"
                            } else {
                                manifest.packageName
                            }

                        val receipt =
                            RiftppApkBuilder(
                                this
                            ).build(
                                artifact =
                                    artifact,
                                runtime =
                                    runtime,
                                packageName =
                                    packageName,
                                sourcePath =
                                    manifest.entry
                            )

                        (
                            if (debug) {
                                "Debug APK ready"
                            } else {
                                "Standalone APK ready"
                            }
                        ) +
                            "\nPackage: " +
                            receipt.packageName +
                            "\nSources: " +
                            manifest.sources.size +
                            "\nSigned + verified: " +
                            receipt.signedApk
                                .absolutePath +
                            "\nDownloads: " +
                            (
                                receipt
                                    .publishedUri
                                    ?: "private-only fallback"
                                ) +
                            "\nAPK SHA-256: " +
                            receipt.apkSha256 +
                            "\nRPA2 SHA-256: " +
                            receipt.artifactSha256
                    }.fold(
                        onSuccess = {
                            it
                        },
                        onFailure = {
                            "Pack APK failed: " +
                                errorText(
                                    it
                                )
                        }
                    )

                runOnUiThread {
                    renderStatus(
                        message
                    )
                }
            }.start()
        }
    }

    private fun promptNewFile() {
        promptPath(
            title =
                "New project file",
            hint =
                "src/example.riftpp"
        ) { path ->
            runCatching {
                saveIfDirty()

                workspace
                    .createTextFile(
                        path,
                        ""
                    )

                refreshProjectTree()
                openDocument(path)
            }.onFailure {
                renderStatus(
                    "New file failed: " +
                        errorText(
                            it
                        )
                )
            }
        }
    }

    private fun promptNewFolder() {
        promptPath(
            title =
                "New project folder",
            hint =
                "src/components"
        ) { path ->
            runCatching {
                workspace
                    .createDirectory(
                        path
                    )
                refreshProjectTree()
                renderStatus(
                    "Created folder " +
                        path
                )
            }.onFailure {
                renderStatus(
                    "New folder failed: " +
                        errorText(
                            it
                        )
                )
            }
        }
    }

    private fun promptRename() {
        val entry =
            selectedEntry()
                ?: run {
                    renderStatus(
                        "Select a file or folder first"
                    )
                    return
                }

        val input =
            EditText(this).apply {
                setText(
                    entry.relativePath
                )
                isSingleLine =
                    true
            }

        AlertDialog.Builder(this)
            .setTitle(
                "Rename / move"
            )
            .setView(
                input
            )
            .setNegativeButton(
                "Cancel",
                null
            )
            .setPositiveButton(
                "Move"
            ) { _, _ ->
                val target =
                    input.text
                        .toString()
                        .trim()

                runCatching {
                    saveIfDirty()

                    require(
                        entry.relativePath !=
                            "app.rift.json"
                    ) {
                        "manifest cannot be renamed"
                    }

                    workspace.move(
                        entry.relativePath,
                        target
                    )

                    RiftppProjectModel
                        .renamePathReferences(
                            workspace =
                                workspace,
                            fromPath =
                                entry.relativePath,
                            toPath =
                                target,
                            directory =
                                entry.directory
                        )

                    for (
                        index in
                            openTabs.indices
                    ) {
                        val tab =
                            openTabs[index]

                        if (
                            tab ==
                                entry.relativePath ||
                            (
                                entry.directory &&
                                    tab.startsWith(
                                        entry.relativePath +
                                            "/"
                                    )
                                )
                        ) {
                            openTabs[index] =
                                target +
                                    tab.removePrefix(
                                        entry.relativePath
                                    )
                        }
                    }

                    if (
                        ::activePath
                            .isInitialized
                    ) {
                        if (
                            activePath ==
                                entry.relativePath ||
                            (
                                entry.directory &&
                                    activePath
                                        .startsWith(
                                            entry.relativePath +
                                                "/"
                                        )
                                )
                        ) {
                            activePath =
                                target +
                                    activePath
                                        .removePrefix(
                                            entry.relativePath
                                        )
                        }
                    }

                    selectedPath =
                        target

                    refreshProjectTree()
                    renderStatus(
                        "Moved to " +
                            target
                    )
                }.onFailure {
                    renderStatus(
                        "Rename failed: " +
                            errorText(
                                it
                            )
                    )
                }
            }
            .show()
    }

    private fun confirmDelete() {
        val entry =
            selectedEntry()
                ?: run {
                    renderStatus(
                        "Select a file or folder first"
                    )
                    return
                }

        AlertDialog.Builder(this)
            .setTitle(
                "Delete " +
                    entry.name +
                    "?"
            )
            .setMessage(
                "This permanently removes the selected project path."
            )
            .setNegativeButton(
                "Cancel",
                null
            )
            .setPositiveButton(
                "Delete"
            ) { _, _ ->
                runCatching {
                    saveIfDirty()

                    require(
                        entry.relativePath !=
                            "app.rift.json"
                    ) {
                        "project manifest cannot be deleted"
                    }

                    RiftppProjectModel
                        .removePathReferences(
                            workspace =
                                workspace,
                            path =
                                entry.relativePath,
                            directory =
                                entry.directory
                        )

                    workspace.delete(
                        entry.relativePath
                    )

                    openTabs.removeAll { tab ->
                        tab ==
                            entry.relativePath ||
                            (
                                entry.directory &&
                                    tab.startsWith(
                                        entry.relativePath +
                                            "/"
                                    )
                                )
                    }

                    selectedPath = null

                    if (
                        ::activePath
                            .isInitialized &&
                        activePath !in
                            openTabs
                    ) {
                        val manifest =
                            RiftppProjectModel
                                .read(
                                    workspace
                                )

                        openDocument(
                            manifest.entry
                        )
                    } else {
                        refreshProjectTree()
                    }

                    renderStatus(
                        "Deleted " +
                            entry.relativePath
                    )
                }.onFailure {
                    renderStatus(
                        "Delete failed: " +
                            errorText(
                                it
                            )
                    )
                }
            }
            .show()
    }

    private fun setSelectedAsEntry() {
        val entry =
            selectedEntry()
                ?: run {
                    renderStatus(
                        "Select a .riftpp file first"
                    )
                    return
                }

        runCatching {
            require(
                !entry.directory &&
                    entry.relativePath
                        .endsWith(
                            ".riftpp"
                        )
            ) {
                "entry must be a .riftpp file"
            }

            saveIfDirty()

            RiftppProjectModel
                .setEntry(
                    workspace,
                    entry.relativePath
                )

            renderStatus(
                "Entry set to " +
                    entry.relativePath
            )
        }.onFailure {
            renderStatus(
                "Set Entry failed: " +
                    errorText(
                        it
                    )
            )
        }
    }

    private fun addSelectedSource() {
        val entry =
            selectedEntry()
                ?: run {
                    renderStatus(
                        "Select a .riftpp file first"
                    )
                    return
                }

        runCatching {
            require(
                !entry.directory &&
                    entry.relativePath
                        .endsWith(
                            ".riftpp"
                        )
            ) {
                "source must be a .riftpp file"
            }

            saveIfDirty()

            RiftppProjectModel
                .addSource(
                    workspace,
                    entry.relativePath
                )

            renderStatus(
                "Added project source " +
                    entry.relativePath
            )
        }.onFailure {
            renderStatus(
                "Add Source failed: " +
                    errorText(
                        it
                    )
            )
        }
    }

    private fun selectedEntry():
        RiftppWorkspaceEntry? {
        val path =
            selectedPath
                ?: return null

        return projectEntries
            .firstOrNull {
                it.relativePath ==
                    path
            }
    }

    private fun promptPath(
        title: String,
        hint: String,
        action: (String) -> Unit
    ) {
        val input =
            EditText(this).apply {
                this.hint =
                    hint
                isSingleLine =
                    true
            }

        AlertDialog.Builder(this)
            .setTitle(
                title
            )
            .setView(
                input
            )
            .setNegativeButton(
                "Cancel",
                null
            )
            .setPositiveButton(
                "Create"
            ) { _, _ ->
                val path =
                    input.text
                        .toString()
                        .trim()

                runCatching {
                    require(
                        path.isNotEmpty()
                    ) {
                        "path is empty"
                    }

                    require(
                        Regex(
                            "^[A-Za-z0-9._/-]{1,180}$"
                        ).matches(
                            path
                        )
                    ) {
                        "path contains unsupported characters"
                    }

                    require(
                        !path.contains(
                            ".."
                        )
                    ) {
                        "path traversal is not allowed"
                    }

                    action(path)
                }.onFailure {
                    renderStatus(
                        "Path rejected: " +
                            errorText(
                                it
                            )
                    )
                }
            }
            .show()
    }

    private fun toggleFullScreen() {
        fullScreen =
            !fullScreen

        headerContainer.visibility =
            View.VISIBLE

        statusView.visibility =
            if (fullScreen) {
                View.GONE
            } else {
                View.VISIBLE
            }

        projectContainer.visibility =
            if (
                !fullScreen &&
                projectPanelVisible
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        fullButton.text =
            if (fullScreen) {
                "Exit Focus"
            } else {
                "Focus"
            }
    }

    private fun renderStatus(
        message: String? = null
    ) {
        if (
            !::statusView
                .isInitialized
        ) {
            return
        }

        statusView.text =
            buildString {
                if (
                    message != null
                ) {
                    append(
                        message
                    )
                    append(
                        "\n"
                    )
                }

                append(
                    "Project: default"
                )

                if (
                    ::activePath
                        .isInitialized
                ) {
                    append(
                        " • File: "
                    )
                    append(
                        activePath
                    )
                }

                if (dirty) {
                    append(
                        " • modified"
                    )
                }

                if (
                    cachedArtifact != null
                ) {
                    append(
                        " • compiled "
                    )
                    append(
                        cachedArtifact
                            ?.size
                    )
                    append(
                        " bytes"
                    )
                }
            }
    }

    private fun errorText(
        error: Throwable
    ): String =
        error.message
            ?: error.javaClass
                .simpleName

    private fun dp(value: Int): Int =
        (
            value *
                resources
                    .displayMetrics
                    .density
        ).toInt()
}
