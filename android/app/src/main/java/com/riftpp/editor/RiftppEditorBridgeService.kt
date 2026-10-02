package com.riftpp.editor

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Bounded development bridge for the legacy Rift++ editor.
 *
 * Authority boundary:
 * - every project path is resolved by RiftppWorkspace under filesDir/projects/default;
 * - only the com.riftos.app UID/package may issue Binder transactions;
 * - RiftOS transports bytes and requests operations but does not parse/compile Rift++ itself;
 * - compiler/runtime/APK authority remains inside the installed Rift++ editor.
 */
class RiftppEditorBridgeService : Service() {
    companion object {
        const val DESCRIPTOR =
            "com.riftpp.editor.bridge.v1"

        const val TRANSACTION_EXECUTE =
            IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_OPEN_READ =
            IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_OPEN_WRITE =
            IBinder.FIRST_CALL_TRANSACTION + 2

        private const val RIFTOS_PACKAGE =
            "com.riftos.app"
        private const val MAX_REQUEST_BYTES =
            128 * 1024
    }

    private val workspace: RiftppWorkspace by lazy {
        RiftppWorkspace(
            File(
                filesDir,
                "projects/default"
            )
        )
    }

    private val pipeline: RiftppPipeline by lazy {
        RiftppPipeline(this)
    }

    private val bridgeBinder =
        object : Binder() {
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
                            val requestText =
                                data.readString()
                                    ?: error(
                                        "bridge request is missing"
                                    )

                            require(
                                requestText
                                    .toByteArray(
                                        Charsets.UTF_8
                                    ).size <=
                                    MAX_REQUEST_BYTES
                            ) {
                                "bridge request exceeds $MAX_REQUEST_BYTES bytes"
                            }

                            val response =
                                execute(
                                    JSONObject(
                                        requestText
                                    )
                                )

                            reply?.writeNoException()
                            reply?.writeString(
                                response.toString()
                            )
                            true
                        }

                        TRANSACTION_OPEN_READ -> {
                            val path =
                                data.readString()
                                    ?: error(
                                        "read path is missing"
                                    )
                            val file =
                                workspace.file(path)

                            require(file.isFile) {
                                "editor file does not exist: $path"
                            }

                            val descriptor =
                                ParcelFileDescriptor.open(
                                    file,
                                    ParcelFileDescriptor
                                        .MODE_READ_ONLY
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
                            val path =
                                data.readString()
                                    ?: error(
                                        "write path is missing"
                                    )
                            val file =
                                workspace.file(path)

                            require(
                                file != workspace.root
                            ) {
                                "cannot write workspace root"
                            }

                            val parent =
                                file.parentFile
                                    ?: error(
                                        "editor file has no parent"
                                    )

                            require(
                                parent.mkdirs() ||
                                    parent.isDirectory
                            ) {
                                "could not create editor file parent"
                            }

                            val descriptor =
                                ParcelFileDescriptor.open(
                                    file,
                                    ParcelFileDescriptor
                                        .MODE_CREATE or
                                        ParcelFileDescriptor
                                            .MODE_TRUNCATE or
                                        ParcelFileDescriptor
                                            .MODE_WRITE_ONLY
                                )

                            try {
                                reply?.writeNoException()
                                reply?.writeInt(1)
                                descriptor.writeToParcel(
                                    requireNotNull(reply),
                                    Parcelable
                                        .PARCELABLE_WRITE_RETURN_VALUE
                                )
                            } finally {
                                descriptor.close()
                            }
                            true
                        }

                        else ->
                            super.onTransact(
                                code,
                                data,
                                reply,
                                flags
                            )
                    }
                } catch (error: Throwable) {
                    reply?.writeException(
                        IllegalArgumentException(
                            error.message
                                ?: error.javaClass
                                    .simpleName
                        )
                    )
                    true
                }
            }
        }

    override fun onBind(intent: Intent?): IBinder =
        bridgeBinder

    private fun enforceRiftOsCaller() {
        val uid = Binder.getCallingUid()
        val packages =
            packageManager
                .getPackagesForUid(uid)
                .orEmpty()

        require(
            RIFTOS_PACKAGE in packages
        ) {
            "Rift++ editor bridge caller is not authorized"
        }
    }

    private fun execute(
        request: JSONObject
    ): JSONObject {
        val op =
            request
                .getString("op")
                .trim()
                .lowercase()

        return when (op) {
            "status" ->
                JSONObject()
                    .put(
                        "schema",
                        "riftpp-editor-bridge-status/1"
                    )
                    .put("state", "ready")
                    .put(
                        "workspace",
                        "projects/default"
                    )
                    .put(
                        "manifestPresent",
                        workspace.exists(
                            "app.rift.json"
                        )
                    )
                    .put(
                        "entries",
                        workspace
                            .listRecursive()
                            .size
                    )
                    .put(
                        "compilerAuthority",
                        "Rift++ frozen S3"
                    )
                    .put(
                        "runtimeAuthority",
                        "Rift++ App v2 runtime"
                    )
                    .put(
                        "filesystemAuthority",
                        "RiftppWorkspace"
                    )

            "list" -> {
                val entries =
                    JSONArray()

                workspace
                    .listRecursive()
                    .forEach { entry ->
                        entries.put(
                            JSONObject()
                                .put(
                                    "path",
                                    entry.relativePath
                                )
                                .put(
                                    "name",
                                    entry.name
                                )
                                .put(
                                    "directory",
                                    entry.directory
                                )
                                .put(
                                    "depth",
                                    entry.depth
                                )
                        )
                    }

                JSONObject()
                    .put(
                        "schema",
                        "riftpp-editor-bridge-list/1"
                    )
                    .put(
                        "entries",
                        entries
                    )
            }

            "stat" -> {
                val path =
                    request.getString("path")
                val file =
                    workspace.file(path)

                require(file.exists()) {
                    "editor path does not exist: $path"
                }

                JSONObject()
                    .put(
                        "schema",
                        "riftpp-editor-bridge-stat/1"
                    )
                    .put("path", path)
                    .put(
                        "directory",
                        file.isDirectory
                    )
                    .put(
                        "bytes",
                        if (file.isFile) {
                            file.length()
                        } else {
                            0L
                        }
                    )
                    .put(
                        "sha256",
                        if (file.isFile) {
                            sha256(file)
                        } else {
                            JSONObject.NULL
                        }
                    )
            }

            "write" -> {
                val path =
                    request.getString("path")
                val text =
                    request.getString("text")

                workspace.writeText(
                    path,
                    text
                )

                JSONObject()
                    .put(
                        "schema",
                        "riftpp-editor-bridge-write/1"
                    )
                    .put("path", path)
                    .put(
                        "bytes",
                        text.toByteArray(
                            Charsets.UTF_8
                        ).size
                    )
                    .put(
                        "sha256",
                        sha256(
                            workspace.file(path)
                        )
                    )
            }

            "mkdir" -> {
                val path =
                    request.getString("path")
                workspace.createDirectory(path)

                JSONObject()
                    .put(
                        "schema",
                        "riftpp-editor-bridge-mkdir/1"
                    )
                    .put("path", path)
                    .put("created", true)
            }

            "move" -> {
                val from =
                    request.getString("from")
                val to =
                    request.getString("to")

                workspace.move(
                    from,
                    to
                )

                JSONObject()
                    .put(
                        "schema",
                        "riftpp-editor-bridge-move/1"
                    )
                    .put("from", from)
                    .put("to", to)
            }

            "delete" -> {
                val path =
                    request.getString("path")
                workspace.delete(path)

                JSONObject()
                    .put(
                        "schema",
                        "riftpp-editor-bridge-delete/1"
                    )
                    .put("path", path)
                    .put("deleted", true)
            }

            "compile" -> {
                val manifest =
                    RiftppProjectModel.read(
                        workspace
                    )
                val artifact =
                    compileManifest(
                        manifest
                    )

                val outputPath =
                    ".riftpp/build/compile-latest.rpa2"

                writeBinaryAtomic(
                    workspace.file(
                        outputPath
                    ),
                    artifact
                )

                JSONObject()
                    .put(
                        "schema",
                        "riftpp-editor-bridge-compile/1"
                    )
                    .put(
                        "package",
                        manifest.packageName
                    )
                    .put(
                        "sources",
                        manifest.sources.size
                    )
                    .put(
                        "artifactPath",
                        outputPath
                    )
                    .put(
                        "artifactBytes",
                        artifact.size
                    )
                    .put(
                        "artifactSha256",
                        sha256(artifact)
                    )
            }

            "preflight" -> {
                val manifest =
                    RiftppProjectModel.read(
                        workspace
                    )
                val artifact =
                    compileManifest(
                        manifest
                    )
                val runtime =
                    pipeline
                        .runtimeProgramForPackaging()
                val preview =
                    pipeline.render(artifact)

                RiftppUiCodec.parse(preview)

                JSONObject()
                    .put(
                        "schema",
                        "riftpp-editor-preflight/1"
                    )
                    .put("state", "passed")
                    .put(
                        "productionPackage",
                        manifest.packageName
                    )
                    .put(
                        "debugPackage",
                        debugPackage(
                            manifest.packageName
                        )
                    )
                    .put(
                        "sources",
                        manifest.sources.size
                    )
                    .put(
                        "artifactBytes",
                        artifact.size
                    )
                    .put(
                        "artifactSha256",
                        sha256(artifact)
                    )
                    .put(
                        "runtimeBytes",
                        runtime.size
                    )
                    .put(
                        "runtimeSha256",
                        sha256(runtime)
                    )
                    .put(
                        "previewBytes",
                        preview.size
                    )
                    .put(
                        "previewSha256",
                        sha256(preview)
                    )
                    .put(
                        "uiProtocol",
                        "RUI2"
                    )
            }

            "build-debug" ->
                buildCurrent(
                    debug = true
                )

            "build-production" ->
                buildCurrent(
                    debug = false
                )

            "native-compile" ->
                nativeCompile(
                    request
                )

            "native-run" ->
                nativeRun(
                    request
                )

            "native-preflight" ->
                nativePreflight(
                    request
                )

            "native-build-debug" ->
                nativeBuildDebug(
                    request
                )

            else ->
                error(
                    "unsupported editor bridge op: $op"
                )
        }
    }

    private fun nativeCompile(
        request: JSONObject
    ): JSONObject {
        val sourcePath =
            request.getString("sourcePath")
        val outputPath =
            request.optString(
                "outputPath",
                ".riftpp/native/compiled.bin"
            )
        val compilerPath =
            request.optString(
                "compilerPath",
                ""
            ).trim()
                .takeIf {
                    it.isNotEmpty()
                }

        val compilerEncoding =
            when {
                compilerPath == null ->
                    "bootstrap"
                compilerPath.endsWith(
                    ".hex",
                    ignoreCase = true
                ) ->
                    "continuous-hex"
                else ->
                    "binary"
            }

        val compiler =
            compilerPath?.let { path ->
                if (
                    compilerEncoding ==
                        "continuous-hex"
                ) {
                    HexAssets.decodeContinuousHex(
                        workspace.readText(
                            path
                        )
                    )
                } else {
                    readBinaryBounded(
                        path,
                        1024 * 1024
                    )
                }
            }

        val compiled =
            pipeline.compileRecordHex(
                workspace.readText(
                    sourcePath
                ),
                compiler
            )

        writeBinaryAtomic(
            workspace.file(
                outputPath
            ),
            compiled
        )

        return JSONObject()
            .put(
                "schema",
                "riftpp-editor-native-compile/1"
            )
            .put(
                "state",
                "compiled"
            )
            .put(
                "sourcePath",
                sourcePath
            )
            .put(
                "compilerPath",
                compilerPath
                    ?: "bootstrap"
            )
            .put(
                "compilerEncoding",
                compilerEncoding
            )
            .put(
                "outputPath",
                outputPath
            )
            .put(
                "bytes",
                compiled.size
            )
            .put(
                "sha256",
                sha256(compiled)
            )
    }

    private fun nativeRun(
        request: JSONObject
    ): JSONObject {
        val programPath =
            request.getString(
                "programPath"
            )
        val inputPath =
            request.getString(
                "inputPath"
            )
        val outputPath =
            request.optString(
                "outputPath",
                ".riftpp/native/output.bin"
            )
        val outputCapacity =
            request.optInt(
                "outputCapacity",
                4 * 1024 * 1024
            )

        val output =
            pipeline.runNativeProgram(
                readBinaryBounded(
                    programPath,
                    1024 * 1024
                ),
                readBinaryBounded(
                    inputPath,
                    4 * 1024 * 1024
                ),
                outputCapacity
            )

        writeBinaryAtomic(
            workspace.file(
                outputPath
            ),
            output
        )

        return JSONObject()
            .put(
                "schema",
                "riftpp-editor-native-run/1"
            )
            .put(
                "state",
                "executed"
            )
            .put(
                "programPath",
                programPath
            )
            .put(
                "inputPath",
                inputPath
            )
            .put(
                "outputPath",
                outputPath
            )
            .put(
                "bytes",
                output.size
            )
            .put(
                "sha256",
                sha256(output)
            )
    }

    private fun nativePreflight(
        request: JSONObject
    ): JSONObject {
        val elfPath =
            request.getString(
                "elfPath"
            )
        val elf =
            readBinaryBounded(
                elfPath,
                16 * 1024 * 1024
            )
        val elfType =
            (elf[16].toInt() and 0xff) or
                ((elf[17].toInt() and 0xff) shl 8)

        if (elfType == 1) {
            val receipt =
                RiftppRelocatableElfPreflight
                    .inspect(
                        elf
                    )

            return JSONObject()
                .put(
                    "schema",
                    "riftpp-editor-native-preflight/1"
                )
                .put(
                    "state",
                    "passed"
                )
                .put(
                    "elfType",
                    "ET_REL"
                )
                .put(
                    "elfPath",
                    elfPath
                )
                .put(
                    "bytes",
                    receipt.bytes
                )
                .put(
                    "sha256",
                    sha256(elf)
                )
                .put(
                    "sectionHeaders",
                    receipt.sectionHeaders
                )
                .put(
                    "sectionTableOffset",
                    receipt.sectionTableOffset
                )
                .put(
                    "executableSections",
                    receipt.executableSections
                )
                .put(
                    "symbolCount",
                    receipt.symbolCount
                )
                .put(
                    "requiredSymbol",
                    receipt.requiredSymbol
                )
        }

        val receipt =
            RiftppNativeElfPreflight
                .inspect(
                    elf
                )

        return JSONObject()
            .put(
                "schema",
                "riftpp-editor-native-preflight/1"
            )
            .put(
                "state",
                "passed"
            )
            .put(
                "elfPath",
                elfPath
            )
            .put(
                "bytes",
                receipt.bytes
            )
            .put(
                "sha256",
                sha256(elf)
            )
            .put(
                "entry",
                receipt.entry
            )
            .put(
                "programHeaders",
                receipt.programHeaders
            )
            .put(
                "sectionHeaders",
                receipt.sectionHeaders
            )
            .put(
                "sectionTableOffset",
                receipt.sectionTableOffset
            )
            .put(
                "loadSegments",
                receipt.loadSegments
            )
            .put(
                "executableLoads",
                receipt.executableLoads
            )
            .put(
                "writableLoads",
                receipt.writableLoads
            )
            .put(
                "rxEnd",
                receipt.rxEnd
            )
            .put(
                "rwStart",
                receipt.rwStart
            )
    }

    private fun readBinaryBounded(
        path: String,
        maxBytes: Int
    ): ByteArray {
        val file =
            workspace.file(
                path
            )

        require(
            file.isFile
        ) {
            "binary file does not exist: $path"
        }
        require(
            file.length() in
                0L..maxBytes.toLong()
        ) {
            "binary file exceeds $maxBytes bytes: $path"
        }

        return file.readBytes()
    }

    private fun nativeBuildDebug(
        request: JSONObject
    ): JSONObject {
        val elfPath =
            request.getString(
                "elfPath"
            )
        val packageName =
            request.optString(
                "package",
                "com.riftpp.editor.nativev1.debug"
            )
        val libraryName =
            request.optString(
                "library",
                "riftpp_editor_native_r1"
            )

        require(
            packageName.endsWith(
                ".debug"
            )
        ) {
            "native debug package must end with .debug"
        }

        val elf =
            readBinaryBounded(
                elfPath,
                16 * 1024 * 1024
            )

        RiftppNativeElfPreflight
            .inspect(
                elf
            )

        val receipt =
            RiftppApkBuilder(
                this
            )
                .buildNativeDebug(
                    elf = elf,
                    packageName =
                        packageName,
                    libraryName =
                        libraryName,
                    sourcePath =
                        elfPath
                )

        val apkPath =
            ".riftpp/native/debug-latest.apk"
        val receiptPath =
            ".riftpp/native/debug-latest.json"

        copyBinaryAtomic(
            receipt.signedApk,
            workspace.file(
                apkPath
            )
        )

        require(
            sha256(
                workspace.file(
                    apkPath
                )
            ) ==
                receipt.apkSha256
        ) {
            "exported native APK hash drift"
        }

        val result =
            JSONObject()
                .put(
                    "schema",
                    "riftpp-editor-native-build/1"
                )
                .put(
                    "state",
                    "signed-verified"
                )
                .put(
                    "mode",
                    "debug"
                )
                .put(
                    "package",
                    receipt.packageName
                )
                .put(
                    "activity",
                    receipt.activityName
                )
                .put(
                    "library",
                    receipt.libraryName
                )
                .put(
                    "elfPath",
                    elfPath
                )
                .put(
                    "elfSha256",
                    receipt.elfSha256
                )
                .put(
                    "apkSha256",
                    receipt.apkSha256
                )
                .put(
                    "certificateSha256",
                    receipt.certificateSha256
                )
                .put(
                    "contentDigestSha256",
                    receipt.contentDigestSha256
                )
                .put(
                    "entryCount",
                    receipt.entryCount
                )
                .put(
                    "apkWorkspacePath",
                    apkPath
                )
                .put(
                    "publishedUri",
                    receipt.publishedUri
                )

        workspace.writeText(
            receiptPath,
            result.toString(2)
        )

        return result.put(
            "receiptWorkspacePath",
            receiptPath
        )
    }

    private fun buildCurrent(
        debug: Boolean
    ): JSONObject {
        require(
            !android.os.Process.is64Bit()
        ) {
            "Rift++ editor APK proof is ARM32-only for now"
        }

        val manifest =
            RiftppProjectModel.read(
                workspace
            )
        val artifact =
            compileManifest(
                manifest
            )
        val runtime =
            pipeline
                .runtimeProgramForPackaging()

        val packageName =
            if (debug) {
                debugPackage(
                    manifest.packageName
                )
            } else {
                manifest.packageName
            }

        val receipt =
            RiftppApkBuilder(this)
                .build(
                    artifact = artifact,
                    runtime = runtime,
                    packageName = packageName,
                    sourcePath =
                        manifest.entry
                )

        val mode =
            if (debug) {
                "debug"
            } else {
                "production"
            }
        val apkPath =
            ".riftpp/build/" +
                mode +
                "-latest.apk"
        val receiptPath =
            ".riftpp/build/" +
                mode +
                "-latest.json"

        copyBinaryAtomic(
            receipt.signedApk,
            workspace.file(
                apkPath
            )
        )

        require(
            sha256(
                workspace.file(
                    apkPath
                )
            ) ==
                receipt.apkSha256
        ) {
            "exported Rift++ APK hash drift"
        }

        val result =
            JSONObject()
                .put(
                    "schema",
                    "riftpp-editor-build/1"
                )
                .put("state", "signed-verified")
                .put("mode", mode)
                .put(
                    "productionPackage",
                    manifest.packageName
                )
                .put(
                    "package",
                    receipt.packageName
                )
                .put(
                    "sideBySide",
                    receipt.packageName !=
                        manifest.packageName
                )
                .put(
                    "artifactSha256",
                    receipt.artifactSha256
                )
                .put(
                    "runtimeSha256",
                    receipt.runtimeSha256
                )
                .put(
                    "apkSha256",
                    receipt.apkSha256
                )
                .put(
                    "certificateSha256",
                    receipt.certificateSha256
                )
                .put(
                    "contentDigestSha256",
                    receipt.contentDigestSha256
                )
                .put(
                    "apkWorkspacePath",
                    apkPath
                )
                .put(
                    "publishedUri",
                    receipt.publishedUri
                )

        workspace.writeText(
            receiptPath,
            result.toString(2)
        )

        return result.put(
            "receiptWorkspacePath",
            receiptPath
        )
    }

    private fun compileManifest(
        manifest: RiftppProjectManifest
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

    private fun debugPackage(
        productionPackage: String
    ): String {
        val value =
            "$productionPackage.debug"

        require(
            value != productionPackage
        ) {
            "debug package must differ from production package"
        }

        return value
    }

    private fun writeBinaryAtomic(
        target: File,
        bytes: ByteArray
    ) {
        val parent =
            target.parentFile
                ?: error(
                    "binary output has no parent"
                )

        require(
            parent.mkdirs() ||
                parent.isDirectory
        ) {
            "could not create binary output parent"
        }

        val temp =
            File(
                parent,
                "." +
                    target.name +
                    ".riftpp-editor.tmp"
            )

        temp.writeBytes(bytes)
        moveReplace(
            temp,
            target
        )
    }

    private fun copyBinaryAtomic(
        source: File,
        target: File
    ) {
        require(source.isFile) {
            "source APK is missing"
        }

        val parent =
            target.parentFile
                ?: error(
                    "binary output has no parent"
                )

        require(
            parent.mkdirs() ||
                parent.isDirectory
        ) {
            "could not create binary output parent"
        }

        val temp =
            File(
                parent,
                "." +
                    target.name +
                    ".riftpp-editor.tmp"
            )

        source.copyTo(
            temp,
            overwrite = true
        )
        moveReplace(
            temp,
            target
        )
    }

    private fun moveReplace(
        from: File,
        to: File
    ) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption
                    .ATOMIC_MOVE,
                StandardCopyOption
                    .REPLACE_EXISTING
            )
        } catch (
            _: AtomicMoveNotSupportedException
        ) {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption
                    .REPLACE_EXISTING
            )
        }
    }

    private fun sha256(
        bytes: ByteArray
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it)
            }

    private fun sha256(
        file: File
    ): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")

        file.inputStream()
            .buffered()
            .use { input ->
                val buffer =
                    ByteArray(
                        64 * 1024
                    )

                while (true) {
                    val count =
                        input.read(buffer)

                    if (count < 0) {
                        break
                    }
                    if (count > 0) {
                        digest.update(
                            buffer,
                            0,
                            count
                        )
                    }
                }
            }

        return digest
            .digest()
            .joinToString("") {
                "%02x".format(it)
            }
    }
}
