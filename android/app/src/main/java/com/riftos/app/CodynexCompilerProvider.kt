package com.riftos.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * TEMPORARY Codynex editor live-proof transport only.
 * This is not compiler authority and MUST disappear when native Codynex/.cx owns the editor/toolchain path.
 */
class CodynexCompilerProvider : ContentProvider() {
    companion object {
        private const val METHOD_COMPILE = "compile-c0"
        private const val METHOD_COMPILE_PROJECT = "compile-c0-project"
        private const val METHOD_COMPILE_VM2 = "compile-c0-vm2"
        private const val METHOD_COMPILE_PROJECT_VM2 = "compile-c0-project-vm2"
        private const val EDITOR_PACKAGE = "com.codynex.editor"
        private const val EDITOR_CERT_SHA256 =
            "9874e844c24fe92c65908ce9b3cfb192f87774984a9e4fc600d883badcbe19b5"
        private const val COMPILER_PATH =
            "/workspace/Codynex/external/language/l0/compiler/c0_reference.js"
        private const val COMPILER_VERSION_PREVIOUS =
            "codynex-c0-ref/0.11.0"
        private const val COMPILER_VERSION_CURRENT =
            "codynex-c0-ref/0.12.0"
        private val SUPPORTED_COMPILER_VERSIONS =
            setOf(
                COMPILER_VERSION_PREVIOUS,
                COMPILER_VERSION_CURRENT
            )
        private const val MAX_SOURCE_BYTES = 256 * 1024
        private const val MAX_PROJECT_BYTES = 1024 * 1024
        private const val MAX_PROJECT_MODULES = 64
        private const val MAX_VM1_BYTES = 64 * 1024
        private const val MAX_VM2_BYTES = 64 * 1024
    }

    private lateinit var runtime: RiftHeadlessJsRuntime
    private lateinit var requestRoot: File
    private val lock = Any()
    private val requestIds = AtomicLong()

    override fun onCreate(): Boolean {
        val ctx = requireNotNull(context)
        runtime = RiftHeadlessJsRuntime(ctx)
        requestRoot =
            File(
                ctx.filesDir,
                "riftfs/system/codynex-editor-bridge"
            ).apply { mkdirs() }.canonicalFile
        return true
    }

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle {
        verifyCaller()

        if (
            method != METHOD_COMPILE &&
            method != METHOD_COMPILE_PROJECT &&
            method != METHOD_COMPILE_VM2 &&
            method != METHOD_COMPILE_PROJECT_VM2
        ) {
            return Bundle().apply {
                putBoolean("success", false)
                putString("error", "unsupported Codynex compiler method")
            }
        }

        val source =
            extras?.getString("source")
                ?: return Bundle().apply {
                    putBoolean("success", false)
                    putString("error", "missing .cx source")
                }

        val sourceBytes = source.toByteArray(Charsets.UTF_8)
        if (sourceBytes.isEmpty() || sourceBytes.size > MAX_SOURCE_BYTES) {
            return Bundle().apply {
                putBoolean("success", false)
                putString(
                    "error",
                    "source must be 1.." + MAX_SOURCE_BYTES + " bytes"
                )
            }
        }

        return synchronized(lock) {
            when (method) {
                METHOD_COMPILE_PROJECT ->
                    compileProject(
                        rootSource = source,
                        modulesJson = extras?.getString("modulesJson") ?: "{}"
                    )
                METHOD_COMPILE_PROJECT_VM2 ->
                    compileProjectVM2(
                        rootSource = source,
                        modulesJson = extras?.getString("modulesJson") ?: "{}"
                    )
                METHOD_COMPILE_VM2 -> compileSourceVM2(source)
                else -> compileSource(source)
            }
        }
    }

    private fun compileProject(
        rootSource: String,
        modulesJson: String
    ): Bundle {
        return try {
            val payload = JSONObject(modulesJson)
            require(payload.length() <= MAX_PROJECT_MODULES - 1) {
                "project exceeds $MAX_PROJECT_MODULES modules"
            }

            val moduleId = Regex(
                "^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*$"
            )
            val modules = linkedMapOf<String, String>()
            var totalBytes = rootSource.toByteArray(Charsets.UTF_8).size

            val names = mutableListOf<String>()
            val keys = payload.keys()
            while (keys.hasNext()) names += keys.next()

            for (name in names.sorted()) {
                require(moduleId.matches(name)) {
                    "invalid Codynex module identity: $name"
                }
                val source = payload.getString(name)
                val bytes = source.toByteArray(Charsets.UTF_8)
                require(bytes.isNotEmpty() && bytes.size <= MAX_SOURCE_BYTES) {
                    "module exceeds per-source bounds: $name"
                }
                totalBytes += bytes.size
                require(totalBytes <= MAX_PROJECT_BYTES) {
                    "project exceeds $MAX_PROJECT_BYTES bytes"
                }
                modules[name] = source
            }

            val compiled =
                runtime.compileCodynexC0Project(
                    rootSource = rootSource,
                    moduleSources = modules
                )

            require(
                compiled.compiler in SUPPORTED_COMPILER_VERSIONS
            ) {
                "compiler identity drift"
            }
            require(compiled.vm1.isNotEmpty()) {
                "compiler returned empty VM1"
            }
            require(compiled.vm1.size <= MAX_VM1_BYTES) {
                "compiler VM1 output exceeds $MAX_VM1_BYTES bytes"
            }

            Bundle().apply {
                putBoolean("success", true)
                putString("compiler", compiled.compiler)
                putByteArray("vm1", compiled.vm1)
                putInt("moduleCount", compiled.moduleCount)
            }
        } catch (error: Throwable) {
            Bundle().apply {
                putBoolean("success", false)
                putString(
                    "error",
                    error.message ?: error.javaClass.simpleName
                )
            }
        }
    }

    private fun compileProjectVM2(
        rootSource: String,
        modulesJson: String
    ): Bundle {
        return try {
            val payload = JSONObject(modulesJson)
            require(payload.length() <= MAX_PROJECT_MODULES - 1) {
                "project exceeds $MAX_PROJECT_MODULES modules"
            }

            val moduleId = Regex(
                "^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*$"
            )
            val modules = linkedMapOf<String, String>()
            var totalBytes = rootSource.toByteArray(Charsets.UTF_8).size

            val names = mutableListOf<String>()
            val keys = payload.keys()
            while (keys.hasNext()) names += keys.next()

            for (name in names.sorted()) {
                require(moduleId.matches(name)) {
                    "invalid Codynex module identity: $name"
                }
                val source = payload.getString(name)
                val bytes = source.toByteArray(Charsets.UTF_8)
                require(bytes.isNotEmpty() && bytes.size <= MAX_SOURCE_BYTES) {
                    "module exceeds per-source bounds: $name"
                }
                totalBytes += bytes.size
                require(totalBytes <= MAX_PROJECT_BYTES) {
                    "project exceeds $MAX_PROJECT_BYTES bytes"
                }
                modules[name] = source
            }

            val compiled =
                runtime.compileCodynexC0ProjectVM2(
                    rootSource = rootSource,
                    moduleSources = modules
                )

            require(
                compiled.compiler in SUPPORTED_COMPILER_VERSIONS
            ) {
                "compiler identity drift"
            }
            require(compiled.vm2.isNotEmpty()) {
                "compiler returned empty VM2"
            }
            require(compiled.vm2.size <= MAX_VM2_BYTES) {
                "compiler VM2 output exceeds $MAX_VM2_BYTES bytes"
            }

            Bundle().apply {
                putBoolean("success", true)
                putString("compiler", compiled.compiler)
                putByteArray("vm2", compiled.vm2)
                putInt("moduleCount", compiled.moduleCount)
            }
        } catch (error: Throwable) {
            Bundle().apply {
                putBoolean("success", false)
                putString(
                    "error",
                    error.message ?: error.javaClass.simpleName
                )
            }
        }
    }

    private fun compileSource(source: String): Bundle {
        val requestId =
            java.lang.Long.toUnsignedString(
                requestIds.incrementAndGet(),
                16
            )
        val sourceFile =
            confinedFile("request-" + requestId + ".cx")
        val wrapperFile =
            confinedFile("request-" + requestId + ".js")
        val sourceDisplay =
            "/system/codynex-editor-bridge/" +
                sourceFile.name
        val wrapperDisplay =
            "/system/codynex-editor-bridge/" +
                wrapperFile.name

        try {
            sourceFile.writeText(source, Charsets.UTF_8)

            val wrapper =
                buildString {
                    append("this.CODYNEX_AUTORUN=false;\n")
                    append("try {\n")
                    append("  eval(rift.readText(")
                    append(JSONObject.quote(COMPILER_PATH))
                    append("));\n")
                    append("  if (CodynexC0.VERSION !== ")
                    append(JSONObject.quote(COMPILER_VERSION_PREVIOUS))
                    append(" && CodynexC0.VERSION !== ")
                    append(JSONObject.quote(COMPILER_VERSION_CURRENT))
                    append(") throw new Error('unexpected compiler '+CodynexC0.VERSION);\n")
                    append("  const source=rift.readText(")
                    append(JSONObject.quote(sourceDisplay))
                    append(");\n")
                    append("  const c=CodynexC0.compile(source);\n")
                    append("  const bytes=Array.from(c.vm1.bytes);\n")
                    append("  const hex=bytes.map(v=>(v&255).toString(16).padStart(2,'0')).join('');\n")
                    append("  print(JSON.stringify({ok:true,compiler:CodynexC0.VERSION,vm1Bytes:bytes.length,hex:hex}));\n")
                    append("} catch(e) {\n")
                    append("  print(JSON.stringify({ok:false,error:String(e),code:e&&e.code?String(e.code):''}));\n")
                    append("}\n")
                }

            wrapperFile.writeText(wrapper, Charsets.UTF_8)

            val execution =
                runtime.executeQuickJs(
                    listOf("run", wrapperDisplay),
                    "/"
                )

            val line =
                execution.output
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .lastOrNull()
                    ?: error("C0 compiler returned no result")

            val payload = JSONObject(line)
            if (!payload.optBoolean("ok", false)) {
                val code = payload.optString("code")
                val message = payload.optString("error")
                return Bundle().apply {
                    putBoolean("success", false)
                    putString(
                        "error",
                        listOf(code, message)
                            .filter { it.isNotBlank() }
                            .joinToString(": ")
                            .ifBlank { "C0 compiler rejected source" }
                    )
                }
            }

            val compiler = payload.optString("compiler")
            require(
                compiler in SUPPORTED_COMPILER_VERSIONS
            ) {
                "compiler identity drift"
            }

            val vm1 = decodeHex(payload.getString("hex"))
            require(vm1.isNotEmpty()) {
                "compiler returned empty VM1"
            }
            require(vm1.size <= MAX_VM1_BYTES) {
                "compiler VM1 output exceeds " +
                    MAX_VM1_BYTES +
                    " bytes"
            }

            return Bundle().apply {
                putBoolean("success", true)
                putString("compiler", compiler)
                putByteArray("vm1", vm1)
            }
        } catch (error: Throwable) {
            return Bundle().apply {
                putBoolean("success", false)
                putString(
                    "error",
                    error.message ?: error.javaClass.simpleName
                )
            }
        } finally {
            sourceFile.delete()
            wrapperFile.delete()
        }
    }

    private fun compileSourceVM2(source: String): Bundle {
        val requestId =
            java.lang.Long.toUnsignedString(
                requestIds.incrementAndGet(),
                16
            )
        val sourceFile =
            confinedFile("request-" + requestId + ".cx")
        val wrapperFile =
            confinedFile("request-" + requestId + ".js")
        val sourceDisplay =
            "/system/codynex-editor-bridge/" +
                sourceFile.name
        val wrapperDisplay =
            "/system/codynex-editor-bridge/" +
                wrapperFile.name

        try {
            sourceFile.writeText(source, Charsets.UTF_8)

            val wrapper =
                buildString {
                    append("this.CODYNEX_AUTORUN=false;\n")
                    append("try {\n")
                    append("  eval(rift.readText(")
                    append(JSONObject.quote(COMPILER_PATH))
                    append("));\n")
                    append("  if (CodynexC0.VERSION !== ")
                    append(JSONObject.quote(COMPILER_VERSION_PREVIOUS))
                    append(" && CodynexC0.VERSION !== ")
                    append(JSONObject.quote(COMPILER_VERSION_CURRENT))
                    append(") throw new Error('unexpected compiler '+CodynexC0.VERSION);\n")
                    append("  const source=rift.readText(")
                    append(JSONObject.quote(sourceDisplay))
                    append(");\n")
                    append("  const c=CodynexC0.compileVM2(source);\n")
                    append("  const bytes=Array.from(c.vm2.bytes);\n")
                    append("  const hex=bytes.map(v=>(v&255).toString(16).padStart(2,'0')).join('');\n")
                    append("  print(JSON.stringify({ok:true,compiler:CodynexC0.VERSION,vm2Bytes:bytes.length,hex:hex}));\n")
                    append("} catch(e) {\n")
                    append("  print(JSON.stringify({ok:false,error:String(e),code:e&&e.code?String(e.code):''}));\n")
                    append("}\n")
                }

            wrapperFile.writeText(wrapper, Charsets.UTF_8)

            val execution =
                runtime.executeQuickJs(
                    listOf("run", wrapperDisplay),
                    "/"
                )

            val line =
                execution.output
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .lastOrNull()
                    ?: error("C0 VM2 compiler returned no result")

            val payload = JSONObject(line)
            if (!payload.optBoolean("ok", false)) {
                val code = payload.optString("code")
                val message = payload.optString("error")
                return Bundle().apply {
                    putBoolean("success", false)
                    putString(
                        "error",
                        listOf(code, message)
                            .filter { it.isNotBlank() }
                            .joinToString(": ")
                            .ifBlank { "C0 VM2 compiler rejected source" }
                    )
                }
            }

            val compiler = payload.optString("compiler")
            require(
                compiler in SUPPORTED_COMPILER_VERSIONS
            ) {
                "compiler identity drift"
            }

            val vm2 = decodeVm2Hex(payload.getString("hex"))
            require(vm2.isNotEmpty()) {
                "compiler returned empty VM2"
            }
            require(vm2.size <= MAX_VM2_BYTES) {
                "compiler VM2 output exceeds " +
                    MAX_VM2_BYTES +
                    " bytes"
            }

            return Bundle().apply {
                putBoolean("success", true)
                putString("compiler", compiler)
                putByteArray("vm2", vm2)
            }
        } catch (error: Throwable) {
            return Bundle().apply {
                putBoolean("success", false)
                putString(
                    "error",
                    error.message ?: error.javaClass.simpleName
                )
            }
        } finally {
            sourceFile.delete()
            wrapperFile.delete()
        }
    }

    private fun confinedFile(name: String): File {
        require(
            Regex("^request-[0-9a-f]+\\.(cx|js)$")
                .matches(name)
        ) {
            "invalid Codynex bridge request name"
        }
        val file = File(requestRoot, name).canonicalFile
        require(file.parentFile == requestRoot) {
            "Codynex bridge request escaped directory"
        }
        return file
    }

    private fun verifyCaller() {
        val ctx = requireNotNull(context)
        val uid = Binder.getCallingUid()

        if (uid == Process.myUid()) {
            return
        }

        val packages =
            ctx.packageManager.getPackagesForUid(uid)
                ?.toSet()
                .orEmpty()

        if (EDITOR_PACKAGE !in packages) {
            throw SecurityException(
                "Codynex compiler bridge caller is not editor"
            )
        }

        @Suppress("DEPRECATION")
        val info =
            ctx.packageManager.getPackageInfo(
                EDITOR_PACKAGE,
                PackageManager.GET_SIGNING_CERTIFICATES
            )

        @Suppress("DEPRECATION")
        val signatures =
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners.orEmpty()
            } else {
                info.signatures.orEmpty()
            }

        val allowed =
            signatures.any { signature ->
                sha256(signature.toByteArray()) ==
                    EDITOR_CERT_SHA256
            }

        if (!allowed) {
            throw SecurityException(
                "Codynex editor signing certificate rejected"
            )
        }
    }

    private fun decodeHex(raw: String): ByteArray {
        require(raw.length % 2 == 0) {
            "compiler returned odd-length VM1 hex"
        }
        require(raw.length / 2 <= MAX_VM1_BYTES) {
            "compiler returned oversized VM1 hex"
        }

        val output = ByteArray(raw.length / 2)
        var source = 0
        var target = 0

        while (source < raw.length) {
            val high = Character.digit(raw[source], 16)
            val low = Character.digit(raw[source + 1], 16)
            require(high >= 0 && low >= 0) {
                "compiler returned non-hex VM1 data"
            }
            output[target] =
                ((high shl 4) or low).toByte()
            source += 2
            target += 1
        }

        return output
    }

    private fun decodeVm2Hex(raw: String): ByteArray {
        require(raw.length % 2 == 0) {
            "compiler returned odd-length VM2 hex"
        }
        require(raw.length / 2 <= MAX_VM2_BYTES) {
            "compiler returned oversized VM2 hex"
        }

        val output = ByteArray(raw.length / 2)
        var source = 0
        var target = 0

        while (source < raw.length) {
            val high = Character.digit(raw[source], 16)
            val low = Character.digit(raw[source + 1], 16)
            require(high >= 0 && low >= 0) {
                "compiler returned non-hex VM2 data"
            }
            output[target] =
                ((high shl 4) or low).toByte()
            source += 2
            target += 1
        }

        return output
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
