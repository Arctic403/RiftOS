package com.codynex.editorapp

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import com.codynex.editor.CandidateState
import com.codynex.editor.CodynexEditorController
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Bounded development bridge from RiftOS into the installed Codynex Editor.
 *
 * Authority boundary:
 * - RiftOS transports bytes/commands only;
 * - all .cx compilation, preview/native proof, APK packing and signing happen here;
 * - all editor paths are confined to filesDir/codynex-workspace;
 * - only com.riftos.app may issue Binder transactions.
 */
class CodynexEditorBridgeService : Service() {
    companion object {
        const val DESCRIPTOR = "com.codynex.editor.bridge.v1"
        const val TRANSACTION_EXECUTE = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_OPEN_READ = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_OPEN_WRITE = IBinder.FIRST_CALL_TRANSACTION + 2

        private const val RIFTOS_PACKAGE = "com.riftos.app"
        private const val MAX_REQUEST_BYTES = 128 * 1024
        private const val MAX_TREE_ENTRIES = 4096
        private const val MAX_TREE_DEPTH = 32
    }

    private val workspaceRoot: File by lazy {
        File(filesDir, "codynex-workspace")
            .apply { mkdirs() }
            .canonicalFile
    }

    private val workspace: FileWorkspacePort by lazy {
        FileWorkspacePort(workspaceRoot)
    }

    private val toolchain: CodynexEditorToolchainPort by lazy {
        CodynexEditorToolchainPort(
            context = this,
            artifacts = BootstrapArtifactLoader.load(this),
            candidateDirectory = File(filesDir, "editor-candidates")
        )
    }

    private val compatibilityToolchain: CodynexEditorToolchainPort by lazy {
        CodynexEditorToolchainPort(
            context = this,
            artifacts = BootstrapArtifactLoader.load(this),
            candidateDirectory = File(filesDir, "editor-candidates-vm1-pack"),
            target = EditorVmTarget.VM1
        )
    }

    private val bridgeBinder = object : Binder() {
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int
        ): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(DESCRIPTOR)
                return true
            }

            return try {
                data.enforceInterface(DESCRIPTOR)
                enforceRiftOsCaller()

                when (code) {
                    TRANSACTION_EXECUTE -> {
                        val requestText = data.readString()
                            ?: error("bridge request is missing")
                        require(
                            requestText.toByteArray(Charsets.UTF_8).size <=
                                MAX_REQUEST_BYTES
                        ) {
                            "bridge request exceeds $MAX_REQUEST_BYTES bytes"
                        }

                        val response = execute(JSONObject(requestText))
                        reply?.writeNoException()
                        reply?.writeString(response.toString())
                        true
                    }

                    TRANSACTION_OPEN_READ -> {
                        val path = data.readString()
                            ?: error("read path is missing")
                        val file = editorFile(path)
                        require(file.isFile) {
                            "editor file does not exist: $path"
                        }

                        val descriptor = ParcelFileDescriptor.open(
                            file,
                            ParcelFileDescriptor.MODE_READ_ONLY
                        )
                        try {
                            reply?.writeNoException()
                            reply?.writeInt(1)
                            descriptor.writeToParcel(
                                requireNotNull(reply),
                                0
                            )
                        } finally {
                            descriptor.close()
                        }
                        true
                    }

                    TRANSACTION_OPEN_WRITE -> {
                        val path = data.readString()
                            ?: error("write path is missing")
                        val file = editorFile(path)
                        require(file != workspaceRoot) {
                            "cannot write workspace root"
                        }

                        val parent = file.parentFile
                            ?: error("editor file has no parent")
                        require(parent.mkdirs() || parent.isDirectory) {
                            "could not create editor file parent"
                        }

                        val descriptor = ParcelFileDescriptor.open(
                            file,
                            ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_TRUNCATE or
                                ParcelFileDescriptor.MODE_WRITE_ONLY
                        )
                        try {
                            reply?.writeNoException()
                            reply?.writeInt(1)
                            descriptor.writeToParcel(
                                requireNotNull(reply),
                                Parcelable.PARCELABLE_WRITE_RETURN_VALUE
                            )
                        } finally {
                            descriptor.close()
                        }
                        true
                    }

                    else -> super.onTransact(code, data, reply, flags)
                }
            } catch (error: Throwable) {
                reply?.writeException(
                    IllegalArgumentException(
                        error.message ?: error.javaClass.simpleName
                    )
                )
                true
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = bridgeBinder

    private fun enforceRiftOsCaller() {
        val uid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        require(RIFTOS_PACKAGE in packages) {
            "Codynex editor bridge caller is not authorized"
        }
    }

    private fun execute(request: JSONObject): JSONObject {
        val op = request.getString("op").trim().lowercase()

        return when (op) {
            "status" -> {
                val entries = workspace.listRecursive(workspace.rootPath())
                JSONObject()
                    .put("schema", "codynex-editor-bridge-status/1")
                    .put("state", "ready")
                    .put("workspace", "codynex-workspace")
                    .put("entries", entries.size)
                    .put("compilerAuthority", "Codynex Editor CodynexEditorToolchainPort")
                    .put("apkAuthority", "CodynexApkBuilder + CodynexApkV2Signer")
                    .put("folderTransport", true)
            }

            "list" -> {
                val entries = JSONArray()
                workspace.listRecursive(workspace.rootPath()).forEach { entry ->
                    val depth = entry.relativePath
                        .split('/')
                        .count { it.isNotBlank() }
                    require(depth <= MAX_TREE_DEPTH) {
                        "workspace tree exceeds depth $MAX_TREE_DEPTH"
                    }
                    entries.put(
                        JSONObject()
                            .put("path", entry.relativePath)
                            .put("name", entry.name)
                            .put("directory", entry.directory)
                            .put("depth", depth)
                    )
                }
                require(entries.length() <= MAX_TREE_ENTRIES) {
                    "workspace tree exceeds $MAX_TREE_ENTRIES entries"
                }
                JSONObject()
                    .put("schema", "codynex-editor-bridge-list/1")
                    .put("entries", entries)
            }

            "stat" -> {
                val path = request.getString("path")
                val file = editorFile(path)
                require(file.exists()) {
                    "editor path does not exist: $path"
                }
                JSONObject()
                    .put("schema", "codynex-editor-bridge-stat/1")
                    .put("path", normalizeEditorPath(path))
                    .put("directory", file.isDirectory)
                    .put("bytes", if (file.isFile) file.length() else 0L)
                    .put(
                        "sha256",
                        if (file.isFile) sha256(file)
                        else JSONObject.NULL
                    )
            }

            "write" -> {
                val path = request.getString("path")
                val text = request.getString("text")
                workspace.writeText(editorFile(path).absolutePath, text)
                val file = editorFile(path)
                JSONObject()
                    .put("schema", "codynex-editor-bridge-write/1")
                    .put("path", normalizeEditorPath(path))
                    .put("bytes", file.length())
                    .put("sha256", sha256(file))
            }

            "mkdir" -> {
                val path = request.getString("path")
                val file = editorFile(path)
                val created = if (file.exists()) {
                    require(file.isDirectory) {
                        "editor path exists and is not a directory: $path"
                    }
                    false
                } else {
                    workspace.createDirectory(file.absolutePath)
                    true
                }
                JSONObject()
                    .put("schema", "codynex-editor-bridge-mkdir/1")
                    .put("path", normalizeEditorPath(path))
                    .put("created", created)
            }

            "move" -> {
                val from = request.getString("from")
                val to = request.getString("to")
                workspace.move(
                    editorFile(from).absolutePath,
                    editorFile(to).absolutePath
                )
                JSONObject()
                    .put("schema", "codynex-editor-bridge-move/1")
                    .put("from", normalizeEditorPath(from))
                    .put("to", normalizeEditorPath(to))
            }

            "delete" -> {
                val path = request.getString("path")
                workspace.delete(editorFile(path).absolutePath)
                JSONObject()
                    .put("schema", "codynex-editor-bridge-delete/1")
                    .put("path", normalizeEditorPath(path))
                    .put("deleted", true)
            }

            "compile" -> {
                val entry = request.getString("entry")
                val compiled = compileFresh(entry)
                val candidate = compiled.candidate
                JSONObject()
                    .put("schema", "codynex-editor-bridge-compile/1")
                    .put("entry", normalizeEditorPath(entry))
                    .put("state", compiled.state.candidateState.name.lowercase())
                    .put("summary", compiled.state.status)
                    .put("candidateBytes", candidate.length())
                    .put("candidateSha256", sha256(candidate))
            }

            "preview" -> {
                val entry = request.getString("entry")
                val compiled = compileFresh(entry)
                val previewed = compiled.controller.preview()
                JSONObject()
                    .put("schema", "codynex-editor-bridge-preview/1")
                    .put("entry", normalizeEditorPath(entry))
                    .put("state", previewed.candidateState.name.lowercase())
                    .put("summary", previewed.status)
                    .put(
                        "success",
                        previewed.candidateState == CandidateState.PREVIEWED
                    )
            }

            "native-proof" -> {
                val run = toolchain.runNativeProof()
                JSONObject()
                    .put("schema", "codynex-editor-bridge-native-proof/1")
                    .put("success", run.success)
                    .put("result", Integer.toUnsignedLong(run.result))
                    .put("outputBytes", run.output.size)
                    .put("outputSha256", sha256(run.output))
                    .put("error", run.error ?: JSONObject.NULL)
            }

            "build-apk" -> {
                val entry = request.getString("entry")
                val compiled = compileFresh(entry)
                val receipt = CodynexApkBuilder(this).build(
                    candidateFile = compiled.candidate,
                    sourcePath = editorFile(entry).absolutePath
                )
                JSONObject()
                    .put("schema", "codynex-editor-bridge-build-apk/1")
                    .put("entry", normalizeEditorPath(entry))
                    .put(
                        "compileState",
                        compiled.state.candidateState.name.lowercase()
                    )
                    .put("package", receipt.packageName)
                    .put("activity", receipt.activityName)
                    .put("signedApk", receipt.signedApk.absolutePath)
                    .put("apkSha256", receipt.apkSha256)
                    .put("programSha256", receipt.programSha256)
                    .put("certificateSha256", receipt.certificateSha256)
                    .put("contentDigestSha256", receipt.contentDigestSha256)
                    .put("entryCount", receipt.entryCount)
                    .put(
                        "publishedUri",
                        receipt.publishedUri ?: JSONObject.NULL
                    )
            }

            else -> error("unsupported Codynex editor bridge op: $op")
        }
    }

    private data class CompileFreshResult(
        val state: com.codynex.editor.EditorState,
        val candidate: File,
        val controller: CodynexEditorController
    )

    private fun compileFresh(
        entry: String,
        activeToolchain: CodynexEditorToolchainPort = toolchain
    ): CompileFreshResult {
        val entryFile = editorFile(entry)
        require(entryFile.isFile) {
            "project entry does not exist: $entry"
        }
        require(entryFile.extension.equals("cx", ignoreCase = true)) {
            "project entry must be a .cx file"
        }

        val controller = CodynexEditorController(
            workspace = workspace,
            compiler = activeToolchain,
            preview = activeToolchain
        )
        controller.openWorkspace(workspace.rootPath())
        controller.openFile(entryFile.absolutePath)
        controller.setProjectEntry(entryFile.absolutePath)

        val state = controller.compile()
        val candidate = state.candidate?.id?.let(::File)
            ?: error("Codynex compile did not produce a candidate")
        require(
            state.candidateState == CandidateState.COMPILED &&
                candidate.isFile
        ) {
            "Codynex compile failed: ${state.status}"
        }

        return CompileFreshResult(
            state = state,
            candidate = candidate.canonicalFile,
            controller = controller
        )
    }

    private fun editorFile(path: String): File {
        val clean = normalizeEditorPath(path)
        val file = File(workspaceRoot, clean).canonicalFile
        val prefix = workspaceRoot.path + File.separator
        require(file.path.startsWith(prefix)) {
            "editor path escaped workspace"
        }
        return file
    }

    private fun normalizeEditorPath(path: String): String {
        val clean = path.trim().replace('\\', '/').trim('/')
        require(clean.isNotBlank()) {
            "editor path must not be blank"
        }
        require(!Regex("^[A-Za-z]:").containsMatchIn(clean)) {
            "editor path must be workspace-relative"
        }
        val segments = clean.split('/')
        require(
            segments.none { it.isBlank() || it == "." || it == ".." }
        ) {
            "editor path is not canonical"
        }
        require(segments.size <= MAX_TREE_DEPTH) {
            "editor path exceeds depth $MAX_TREE_DEPTH"
        }
        return segments.joinToString("/")
    }

    private fun sha256(file: File): String =
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
}
