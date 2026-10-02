package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Generic native Android package preparer for RiftBuild applications.
 *
 * Kept separate from frozen proof-manifest encoders so historical proof byte identities remain stable.
 */
class RiftBuildNativeApp(
    context: Context,
    private val workspaceRoot: File
) {
    private val appContext = context.applicationContext
    private data class AppSpec(
        val schema: String,
        val packageName: String,
        val library: String,
        val versionCode: Int,
        val versionName: String,
        val minSdk: Int,
        val targetSdk: Int,
        val assetsDir: String,
        val permissions: List<String>,
        val activityProfile: String,
        val managedRuntime: String
    )

    private data class Attr(
        val namespace: Int,
        val name: Int,
        val rawValue: Int,
        val dataType: Int,
        val data: Int
    )

    private data class CopyStats(val files: Int, val bytes: Long)

    companion object {
        private const val APP_SCHEMA_V1 = "riftbuild-native-app/1"
        private const val APP_SCHEMA_V2 = "riftbuild-native-app/2"
        private const val NATIVE_PROJECT_SCHEMA_V1 = "riftbuild-native-project/1"
        private const val NATIVE_PROJECT_SCHEMA_V2 = "riftbuild-native-project/2"
        private const val APP_MANIFEST = "rift-app.json"
        private const val NATIVE_MANIFEST = "rift-native.json"
        private const val MAX_JSON_BYTES = 256L * 1024L
        private const val MAX_ASSET_FILES = 5_000
        private const val MAX_ASSET_BYTES = 128L * 1024L * 1024L
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_MANAGED_DEX_FILES = 8
        private const val MAX_MANAGED_DEX_BYTES = 16L * 1024L * 1024L
        private const val NATIVE_ACTIVITY_PROFILE = "native-activity"
        private const val RIFTPP_ADAPTER_PROFILE = "riftpp-adapter"
        private const val RIFTPP_ADAPTER_CLASS = "com.riftpp.android.RiftppActivity"
        private const val RIFTPP_ADAPTER_RUNTIME = "riftpp-android-adapter/1"
        private val SAFE_LIBRARY = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
        private val DEX_ENTRY = Regex("^classes(?:[2-9]|[1-9][0-9]+)?\\.dex$")
        private val SAFE_PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val ALLOWED_PERMISSIONS = setOf(
            "android.permission.INTERNET"
        )

        private const val XML_NO_INDEX = -1
        private const val XML_STRING_POOL_TYPE = 0x0001
        private const val XML_TYPE = 0x0003
        private const val XML_START_NAMESPACE_TYPE = 0x0100
        private const val XML_END_NAMESPACE_TYPE = 0x0101
        private const val XML_START_ELEMENT_TYPE = 0x0102
        private const val XML_END_ELEMENT_TYPE = 0x0103
        private const val XML_RESOURCE_MAP_TYPE = 0x0180
        private const val XML_UTF8_FLAG = 0x00000100
        private const val XML_VALUE_STRING = 0x03
        private const val XML_VALUE_INT_DEC = 0x10
        private const val XML_VALUE_INT_BOOLEAN = 0x12

        private val RESOURCE_ATTR_NAMES = listOf(
            "name", "hasCode", "exported", "value",
            "minSdkVersion", "versionCode", "versionName", "targetSdkVersion"
        )
        private val RESOURCE_IDS = intArrayOf(
            0x01010003, 0x0101000c, 0x01010010, 0x01010024,
            0x0101020c, 0x0101021b, 0x0101021c, 0x01010270
        )
    }

    fun prepare(projectRoot: File): JSONObject {
        val project = projectRoot.canonicalFile
        require(confinedTo(workspaceRoot, project)) { "Native app project escaped D:/Workspace" }
        require(project.isDirectory) { "Native app project is not a directory" }

        val spec = readAppSpec(project)
        crossCheckNativeLibrary(project, spec.library)

        val prepared = File(project, "build/riftbuild/prepared").canonicalFile
        require(confinedTo(project, prepared)) { "Native app prepared root escaped project" }
        require(prepared.mkdirs() || prepared.isDirectory) { "Could not create native app prepared root" }
        clearPreparedDex(prepared)

        val manifestBytes = buildManifest(spec)
        require(manifestBytes.size in 8..MAX_MANIFEST_BYTES) { "Generated native app manifest size is invalid" }
        val manifest = File(prepared, "AndroidManifest.xml").canonicalFile
        require(confinedTo(prepared, manifest)) { "Native app manifest output escaped prepared root" }
        writeAtomic(manifest, manifestBytes)

        val preparedAssets = File(prepared, "assets").canonicalFile
        require(confinedTo(prepared, preparedAssets)) { "Native app assets output escaped prepared root" }
        if (preparedAssets.exists()) deleteTreeBounded(preparedAssets)

        val sourceAssets = if (spec.assetsDir.isBlank()) null else resolveProjectPath(project, spec.assetsDir)
        if (sourceAssets != null) {
            val buildRoot = File(project, "build/riftbuild").canonicalFile
            require(!confinedTo(buildRoot, sourceAssets)) { "Native app assetsDir must not point inside build/riftbuild" }
        }
        val stats = if (sourceAssets == null || !sourceAssets.exists()) {
            CopyStats(0, 0L)
        } else {
            require(sourceAssets.isDirectory) { "Native app assetsDir is not a directory" }
            copyAssets(sourceAssets, preparedAssets)
        }
        val managedDex = materializeManagedRuntime(spec, prepared)

        return JSONObject()
            .put("schema", "riftbuild-native-app-prepare-v2")
            .put("appSchema", spec.schema)
            .put("package", spec.packageName)
            .put("library", spec.library)
            .put("versionCode", spec.versionCode)
            .put("versionName", spec.versionName)
            .put("minSdk", spec.minSdk)
            .put("targetSdk", spec.targetSdk)
            .put("manifestPath", "build/riftbuild/prepared/AndroidManifest.xml")
            .put("manifestBytes", manifest.length())
            .put("manifestSha256", sha256(manifestBytes))
            .put("assetsDir", spec.assetsDir)
            .put("assetFiles", stats.files)
            .put("assetBytes", stats.bytes)
            .put("permissions", JSONArray(spec.permissions))
            .put("activityProfile", spec.activityProfile)
            .put("activityClass", activityClass(spec))
            .put("managedRuntime", spec.managedRuntime)
            .put("dexFiles", managedDex)
            .put("state", "prepared-native-app")
    }

    private fun readAppSpec(project: File): AppSpec {
        val file = File(project, APP_MANIFEST).canonicalFile
        require(confinedTo(project, file) && file.isFile) { "Native app manifest missing: " + APP_MANIFEST }
        val json = readJson(file)
        val schema = json.optString("schema")
        require(schema == APP_SCHEMA_V1 || schema == APP_SCHEMA_V2) {
            "Unsupported native app schema"
        }

        val packageName = json.optString("package").trim()
        require(SAFE_PACKAGE.matches(packageName) && packageName.length <= 240) { "Native app package name is invalid" }
        val library = json.optString("library").trim()
        require(SAFE_LIBRARY.matches(library)) { "Native app library name is invalid" }
        val versionCode = json.optInt("versionCode", 1)
        require(versionCode > 0) { "Native app versionCode must be positive" }
        val versionName = json.optString("versionName", "0.1.0").trim()
        require(versionName.isNotBlank() && versionName.length <= 120 && !versionName.contains('\u0000')) {
            "Native app versionName is invalid"
        }
        val minSdk = json.optInt("minSdk", 26)
        val targetSdk = json.optInt("targetSdk", 36)
        require(minSdk in 21..100 && targetSdk in minSdk..100) { "Native app SDK range is invalid" }
        val assetsDir = json.optString("assetsDir", "assets").trim()
        if (assetsDir.isNotBlank()) validateRelativePath(assetsDir)

        val permissionArray = json.optJSONArray("permissions")
        val permissions = ArrayList<String>()
        if (permissionArray != null) {
            require(permissionArray.length() <= 8) {
                "Native app permission count exceeds 8"
            }
            for (index in 0 until permissionArray.length()) {
                val permission = permissionArray.optString(index).trim()
                require(permission in ALLOWED_PERMISSIONS) {
                    "Native app permission is not allowed: $permission"
                }
                require(permission !in permissions) {
                    "Duplicate native app permission: $permission"
                }
                permissions += permission
            }
        }

        val activityProfile = if (schema == APP_SCHEMA_V1) {
            NATIVE_ACTIVITY_PROFILE
        } else {
            json.optString("activity", NATIVE_ACTIVITY_PROFILE).trim()
        }
        require(
            activityProfile == NATIVE_ACTIVITY_PROFILE ||
                activityProfile == RIFTPP_ADAPTER_PROFILE
        ) {
            "Native app activity must be native-activity or riftpp-adapter"
        }
        val managedRuntime =
            if (schema == APP_SCHEMA_V1) "" else json.optString("managedRuntime").trim()
        if (activityProfile == RIFTPP_ADAPTER_PROFILE) {
            require(managedRuntime == RIFTPP_ADAPTER_RUNTIME) {
                "Rift++ adapter app requires managedRuntime: $RIFTPP_ADAPTER_RUNTIME"
            }
        } else {
            require(managedRuntime.isBlank()) {
                "NativeActivity app must not declare a managed runtime"
            }
        }

        return AppSpec(
            schema,
            packageName,
            library,
            versionCode,
            versionName,
            minSdk,
            targetSdk,
            assetsDir,
            permissions,
            activityProfile,
            managedRuntime
        )
    }

    private fun crossCheckNativeLibrary(project: File, library: String) {
        val nativeFile = File(project, NATIVE_MANIFEST).canonicalFile
        if (!nativeFile.isFile) return
        require(confinedTo(project, nativeFile)) { "Native project manifest escaped project" }
        val native = readJson(nativeFile)
        val schema = native.optString("schema")
        require(schema == NATIVE_PROJECT_SCHEMA_V1 || schema == NATIVE_PROJECT_SCHEMA_V2) {
            "Unsupported native project schema"
        }
        require(native.optString("library").trim() == library) {
            "rift-app.json library must match rift-native.json library"
        }
    }

    private fun buildManifest(spec: AppSpec): ByteArray {
        val strings = buildStringPoolValues(spec)
        val body = ByteArrayOutputStream()
        body.write(stringPool(strings))
        body.write(resourceMap())
        body.write(namespace(strings, XML_START_NAMESPACE_TYPE))

        body.write(startElement(strings, "manifest", listOf(
            stringAttr(strings, "package", spec.packageName, XML_NO_INDEX),
            intAttr(strings, "versionCode", spec.versionCode.toString(), spec.versionCode),
            stringAttr(strings, "versionName", spec.versionName)
        )))
        body.write(startElement(strings, "uses-sdk", listOf(
            intAttr(strings, "minSdkVersion", spec.minSdk.toString(), spec.minSdk),
            intAttr(strings, "targetSdkVersion", spec.targetSdk.toString(), spec.targetSdk)
        )))
        body.write(endElement(strings, "uses-sdk"))
        for (permission in spec.permissions) {
            body.write(startElement(strings, "uses-permission", listOf(
                stringAttr(strings, "name", permission)
            )))
            body.write(endElement(strings, "uses-permission"))
        }
        val hasCode = spec.activityProfile == RIFTPP_ADAPTER_PROFILE
        body.write(startElement(strings, "application", listOf(
            boolAttr(strings, "hasCode", hasCode.toString(), hasCode)
        )))
        body.write(startElement(strings, "activity", listOf(
            stringAttr(strings, "name", activityClass(spec)),
            boolAttr(strings, "exported", "true", true)
        )))
        body.write(startElement(strings, "meta-data", listOf(
            stringAttr(strings, "name", "android.app.lib_name"),
            stringAttr(strings, "value", spec.library)
        )))
        body.write(endElement(strings, "meta-data"))
        body.write(startElement(strings, "intent-filter", emptyList()))
        body.write(startElement(strings, "action", listOf(
            stringAttr(strings, "name", "android.intent.action.MAIN")
        )))
        body.write(endElement(strings, "action"))
        body.write(startElement(strings, "category", listOf(
            stringAttr(strings, "name", "android.intent.category.LAUNCHER")
        )))
        body.write(endElement(strings, "category"))
        body.write(endElement(strings, "intent-filter"))
        body.write(endElement(strings, "activity"))
        body.write(endElement(strings, "application"))
        body.write(endElement(strings, "manifest"))
        body.write(namespace(strings, XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        chunkHeader(out, XML_TYPE, 8, 8 + bodyBytes.size)
        out.write(bodyBytes)
        return out.toByteArray()
    }

    private fun buildStringPoolValues(spec: AppSpec): List<String> {
        val values = ArrayList<String>()
        values += RESOURCE_ATTR_NAMES
        values += listOf(
            "android",
            "http://schemas.android.com/apk/res/android",
            "manifest",
            "package",
            spec.packageName,
            spec.versionCode.toString(),
            spec.versionName,
            "uses-sdk",
            spec.minSdk.toString(),
            spec.targetSdk.toString(),
            "uses-permission",
            *spec.permissions.toTypedArray(),
            "application",
            (spec.activityProfile == RIFTPP_ADAPTER_PROFILE).toString(),
            "activity",
            activityClass(spec),
            "true",
            "meta-data",
            "android.app.lib_name",
            spec.library,
            "intent-filter",
            "action",
            "android.intent.action.MAIN",
            "category",
            "android.intent.category.LAUNCHER"
        )
        return values
    }

    private fun activityClass(spec: AppSpec): String =
        if (spec.activityProfile == RIFTPP_ADAPTER_PROFILE) {
            RIFTPP_ADAPTER_CLASS
        } else {
            "android.app.NativeActivity"
        }

    private fun clearPreparedDex(prepared: File) {
        prepared.listFiles()
            ?.filter { it.isFile && DEX_ENTRY.matches(it.name) }
            ?.forEach { file ->
                require(file.delete()) { "Could not remove stale prepared DEX: ${file.name}" }
            }
    }

    private fun materializeManagedRuntime(spec: AppSpec, prepared: File): JSONArray {
        val out = JSONArray()
        if (spec.managedRuntime.isBlank()) return out

        require(
            spec.activityProfile == RIFTPP_ADAPTER_PROFILE &&
                spec.managedRuntime == RIFTPP_ADAPTER_RUNTIME
        ) {
            "Managed runtime is not allowlisted: ${spec.managedRuntime}"
        }

        val assetRoot = "riftbuild/managed-runtimes/riftpp-adapter-v1"
        val names = appContext.assets.list(assetRoot)
            ?.filter { DEX_ENTRY.matches(it) }
            ?.sortedWith(compareBy<String> {
                if (it == "classes.dex") 1
                else it.removePrefix("classes").removeSuffix(".dex").toIntOrNull()
                    ?: Int.MAX_VALUE
            })
            .orEmpty()

        require(names.isNotEmpty() && names.first() == "classes.dex") {
            "Pinned Rift++ Android adapter classes.dex is missing"
        }
        require(names.size <= MAX_MANAGED_DEX_FILES) {
            "Managed runtime DEX file count exceeds limit"
        }

        var total = 0L
        for (name in names) {
            val bytes = appContext.assets.open("$assetRoot/$name").use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    require(total <= MAX_MANAGED_DEX_BYTES) {
                        "Managed runtime DEX bytes exceed limit"
                    }
                    buffer.write(chunk, 0, count)
                }
                buffer.toByteArray()
            }

            require(bytes.size >= 8) {
                "Managed runtime DEX is truncated: $name"
            }
            require(
                bytes[0] == 'd'.code.toByte() &&
                    bytes[1] == 'e'.code.toByte() &&
                    bytes[2] == 'x'.code.toByte() &&
                    bytes[3] == '\n'.code.toByte() &&
                    bytes[7] == 0.toByte()
            ) {
                "Managed runtime DEX magic is invalid: $name"
            }

            val output = File(prepared, name).canonicalFile
            require(confinedTo(prepared, output)) {
                "Managed runtime DEX escaped prepared root"
            }
            writeAtomic(output, bytes)
            out.put(
                JSONObject()
                    .put("name", name)
                    .put("bytes", bytes.size)
                    .put("sha256", sha256(bytes))
            )
        }

        return out
    }

    private fun stringPool(strings: List<String>): ByteArray {
        val offsets = ArrayList<Int>(strings.size)
        val data = ByteArrayOutputStream()
        for (value in strings) {
            val utf8 = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x8000 && utf8.size < 0x8000) { "Native manifest string is too long" }
            offsets += data.size()
            length8(data, value.length)
            length8(data, utf8.size)
            data.write(utf8)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val stringsStart = 28 + strings.size * 4
        val bytes = data.toByteArray()
        val out = ByteArrayOutputStream()
        chunkHeader(out, XML_STRING_POOL_TYPE, 28, stringsStart + bytes.size)
        u32(out, strings.size)
        u32(out, 0)
        u32(out, XML_UTF8_FLAG)
        u32(out, stringsStart)
        u32(out, 0)
        for (offset in offsets) u32(out, offset)
        out.write(bytes)
        return out.toByteArray()
    }

    private fun resourceMap(): ByteArray {
        val out = ByteArrayOutputStream()
        chunkHeader(out, XML_RESOURCE_MAP_TYPE, 8, 8 + RESOURCE_IDS.size * 4)
        for (id in RESOURCE_IDS) u32(out, id)
        return out.toByteArray()
    }

    private fun namespace(strings: List<String>, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        nodeHeader(out, type, 24)
        u32(out, index(strings, "android"))
        u32(out, index(strings, "http://schemas.android.com/apk/res/android"))
        return out.toByteArray()
    }

    private fun startElement(strings: List<String>, name: String, attrs: List<Attr>): ByteArray {
        val out = ByteArrayOutputStream()
        nodeHeader(out, XML_START_ELEMENT_TYPE, 36 + attrs.size * 20)
        u32(out, XML_NO_INDEX)
        u32(out, index(strings, name))
        u16(out, 20)
        u16(out, 20)
        u16(out, attrs.size)
        u16(out, 0)
        u16(out, 0)
        u16(out, 0)
        for (attr in attrs) {
            u32(out, attr.namespace)
            u32(out, attr.name)
            u32(out, attr.rawValue)
            u16(out, 8)
            out.write(0)
            out.write(attr.dataType)
            u32(out, attr.data)
        }
        return out.toByteArray()
    }

    private fun endElement(strings: List<String>, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        nodeHeader(out, XML_END_ELEMENT_TYPE, 24)
        u32(out, XML_NO_INDEX)
        u32(out, index(strings, name))
        return out.toByteArray()
    }

    private fun stringAttr(
        strings: List<String>,
        name: String,
        value: String,
        namespace: Int = index(strings, "http://schemas.android.com/apk/res/android")
    ): Attr = Attr(namespace, index(strings, name), index(strings, value), XML_VALUE_STRING, index(strings, value))

    private fun intAttr(strings: List<String>, name: String, raw: String, value: Int): Attr =
        Attr(index(strings, "http://schemas.android.com/apk/res/android"), index(strings, name), index(strings, raw), XML_VALUE_INT_DEC, value)

    private fun boolAttr(strings: List<String>, name: String, raw: String, value: Boolean): Attr =
        Attr(index(strings, "http://schemas.android.com/apk/res/android"), index(strings, name), index(strings, raw), XML_VALUE_INT_BOOLEAN, if (value) -1 else 0)

    private fun index(strings: List<String>, value: String): Int {
        val result = strings.indexOf(value)
        require(result >= 0) { "Native manifest string missing from pool: " + value }
        return result
    }

    private fun nodeHeader(out: ByteArrayOutputStream, type: Int, size: Int) {
        chunkHeader(out, type, 16, size)
        u32(out, 1)
        u32(out, XML_NO_INDEX)
    }

    private fun chunkHeader(out: ByteArrayOutputStream, type: Int, headerSize: Int, size: Int) {
        u16(out, type)
        u16(out, headerSize)
        u32(out, size)
    }

    private fun length8(out: ByteArrayOutputStream, value: Int) {
        require(value in 0 until 0x8000)
        if (value < 0x80) {
            out.write(value)
        } else {
            out.write((value ushr 8) or 0x80)
            out.write(value and 0xff)
        }
    }

    private fun u16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }

    private fun u32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }

    private fun copyAssets(source: File, destination: File): CopyStats {
        var files = 0
        var bytes = 0L
        source.walkTopDown().forEach { entry ->
            if (!entry.isFile) return@forEach
            val canonical = entry.canonicalFile
            require(confinedTo(source, canonical)) { "Native asset escaped asset root" }
            files += 1
            require(files <= MAX_ASSET_FILES) { "Native app asset file-count limit exceeded" }
            bytes += canonical.length()
            require(bytes <= MAX_ASSET_BYTES) { "Native app asset byte limit exceeded" }
            val relative = canonical.relativeTo(source.canonicalFile).invariantSeparatorsPath
            validateRelativePath(relative)
            val output = File(destination, relative).canonicalFile
            require(confinedTo(destination, output)) { "Native app asset output escaped prepared root" }
            output.parentFile?.let { require(it.mkdirs() || it.isDirectory) { "Could not create native asset directory" } }
            canonical.inputStream().use { input ->
                output.outputStream().use { out -> input.copyTo(out, 8192) }
            }
        }
        return CopyStats(files, bytes)
    }

    private fun deleteTreeBounded(root: File) {
        var count = 0
        root.walkBottomUp().forEach { entry ->
            count += 1
            require(count <= MAX_ASSET_FILES + 256) { "Prepared asset tree exceeds deletion bound" }
            require(entry.delete()) { "Could not clear stale prepared asset: " + entry.name }
        }
    }

    private fun resolveProjectPath(project: File, raw: String): File {
        validateRelativePath(raw)
        val file = File(project, raw.replace('\\', '/')).canonicalFile
        require(confinedTo(project, file)) { "Native app path escaped project root" }
        return file
    }

    private fun validateRelativePath(raw: String) {
        require(raw.isNotBlank() && !raw.contains('\u0000')) { "Native app relative path is invalid" }
        val value = raw.replace('\\', '/')
        require(!value.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(value)) { "Native app path must be relative" }
        require(value.split('/').all { it.isNotBlank() && it != "." && it != ".." }) { "Native app path traversal is forbidden" }
    }

    private fun readJson(file: File): JSONObject {
        require(file.length() in 1L..MAX_JSON_BYTES) { "Native app JSON manifest exceeds limit" }
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.let { require(it.mkdirs() || it.isDirectory) { "Could not create native app output directory" } }
        val parent = file.parentFile.canonicalFile
        val temp = File(parent, file.name + ".tmp").canonicalFile
        require(confinedTo(parent, temp)) { "Native app temporary output escaped directory" }
        temp.outputStream().use { it.write(bytes) }
        if (file.exists()) require(file.delete()) { "Could not replace native app output" }
        require(temp.renameTo(file)) { "Could not commit native app output" }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun confinedTo(root: File, child: File): Boolean {
        val rootPath = root.canonicalFile.path
        val childPath = child.canonicalFile.path
        return childPath == rootPath || childPath.startsWith(rootPath + File.separator)
    }
}
