package com.riftpp.editor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class RiftppApkReceipt(
    val packageName: String,
    val activityName: String,
    val signedApk: File,
    val apkSha256: String,
    val artifactSha256: String,
    val runtimeSha256: String,
    val certificateSha256: String,
    val contentDigestSha256: String,
    val entryCount: Int,
    val publishedUri: String?
)

/**
 * TEMP LIVE-PROOF Rift++-owned Android APK materializer/packer.
 *
 * Authority boundary:
 * - input is an already-compiled RPA2 artifact owned by the Rift++ editor;
 * - Android host/runtime bytes are harvested from this installed Rift++ editor APK;
 * - no RiftOS file, RiftBuild artifact, provider, or path is read during packaging;
 * - APK v2 signing and verification are owned by RiftppApkV2Signer.
 *
 * This remains temporary Android/Kotlin bootstrap scaffolding and MUST eventually
 * become native Rift++ packaging authority.
 */
class RiftppApkBuilder(private val context: Context) {
    companion object {
        private const val APP_ACTIVITY =
            "com.riftpp.apphost.RiftppAppActivity"
        private const val LIB_ENTRY =
            "lib/armeabi-v7a/libriftpp_editor_bridge.so"
        private const val PROGRAM_ASSET = "riftpp/app/program.rpa2"
        private const val RUNTIME_ASSET = "riftpp/app/runtime.arm32.native.bin"
        private const val VERSION_NAME = "0.2.0-app2-proof"

        private const val MAX_PROGRAM_BYTES = 1024 * 1024
        private const val MAX_DEX_FILES = 16
        private const val MAX_DEX_BYTES = 48 * 1024 * 1024
        private const val MAX_NATIVE_BYTES = 16 * 1024 * 1024
        private const val MAX_PACKAGE_INPUT_BYTES = 128L * 1024L * 1024L

        private val DEX_ENTRY =
            Regex("^classes(?:[2-9]|[1-9][0-9]+)?\\.dex$")

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

        private val MANIFEST_RESOURCE_IDS = intArrayOf(
            0x01010003,
            0x0101000c,
            0x01010010,
            0x01010024,
            0x0101020c,
            0x0101021b,
            0x0101021c,
            0x01010270
        )
    }

    private data class ManifestAttr(
        val namespace: Int,
        val name: Int,
        val rawValue: Int,
        val dataType: Int,
        val data: Int
    )

    fun build(
        artifact: ByteArray,
        runtime: ByteArray,
        packageName: String,
        sourcePath: String,
        debug: Boolean = false
    ): RiftppApkReceipt {
        require(artifact.size in 1..MAX_PROGRAM_BYTES) {
            "RPA2 artifact is out of bounds"
        }
        require(runtime.size in 1..MAX_PROGRAM_BYTES) {
            "Rift++ App v2 runtime is out of bounds"
        }
        val outputPackageName =
            if (debug) {
                packageName + ".debug"
            } else {
                packageName
            }
        val versionName =
            if (debug) {
                VERSION_NAME + "-debug"
            } else {
                VERSION_NAME
            }

        require(outputPackageName.length <= 180) {
            "Android package name is too long"
        }
        require(
            Regex(
                "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"
            ).matches(outputPackageName)
        ) {
            "Android package name is invalid"
        }

        val artifactSha = sha256(artifact)
        val runtimeSha = sha256(runtime)
        val packageSegment = outputPackageName.substringAfterLast('.')

        val ownApk =
            File(context.applicationInfo.sourceDir)
                .canonicalFile
        require(ownApk.isFile) {
            "Installed Rift++ editor APK is unavailable"
        }

        val outputRoot =
            File(
                context.filesDir,
                "riftpp-apk-builds"
            )
                .apply { mkdirs() }
                .canonicalFile

        val buildId =
            packageSegment +
                "-" +
                artifactSha.take(16) +
                "-" +
                System.currentTimeMillis().toString()

        val buildDir =
            File(outputRoot, buildId)
                .canonicalFile
        require(buildDir.parentFile == outputRoot) {
            "Rift++ APK build path escaped output root"
        }
        require(buildDir.mkdirs()) {
            "Could not create Rift++ APK build directory"
        }

        val unsignedApk =
            File(
                buildDir,
                "$packageSegment-arm32-unsigned.apk"
            ).canonicalFile
        val signedApk =
            File(
                buildDir,
                "$packageSegment-arm32-signed.apk"
            ).canonicalFile

        var entryCount = 0
        var totalInputBytes = 0L

        ZipFile(ownApk).use { sourceZip ->
            val dexEntries = ArrayList<ZipEntry>()
            val enumeration = sourceZip.entries()

            while (enumeration.hasMoreElements()) {
                val sourceEntry = enumeration.nextElement()
                if (
                    !sourceEntry.isDirectory &&
                    DEX_ENTRY.matches(sourceEntry.name)
                ) {
                    dexEntries += sourceEntry
                }
            }

            dexEntries.sortBy { dexOrder(it.name) }

            require(dexEntries.isNotEmpty()) {
                "Installed Rift++ editor APK contains no DEX payload"
            }
            require(dexEntries.size <= MAX_DEX_FILES) {
                "Installed Rift++ editor APK exceeds DEX-count bound"
            }

            val nativeEntry =
                sourceZip.getEntry(LIB_ENTRY)
                    ?: error(
                        "Installed Rift++ editor APK is missing $LIB_ENTRY"
                    )

            unsignedApk
                .outputStream()
                .buffered()
                .use { fileOutput ->
                    ZipOutputStream(fileOutput).use { output ->
                        fun putBytes(
                            name: String,
                            bytes: ByteArray
                        ) {
                            totalInputBytes +=
                                bytes.size.toLong()
                            require(
                                totalInputBytes <=
                                    MAX_PACKAGE_INPUT_BYTES
                            ) {
                                "Rift++ APK package input exceeds bound"
                            }

                            val zipEntry =
                                ZipEntry(name).apply {
                                    time = 0L
                                }

                            output.putNextEntry(zipEntry)
                            output.write(bytes)
                            output.closeEntry()
                            entryCount += 1
                        }

                        putBytes(
                            "AndroidManifest.xml",
                            buildBinaryManifest(
                                outputPackageName,
                                versionName
                            )
                        )

                        for (dexEntry in dexEntries) {
                            putBytes(
                                dexEntry.name,
                                readZipEntry(
                                    sourceZip,
                                    dexEntry,
                                    MAX_DEX_BYTES
                                )
                            )
                        }

                        putBytes(
                            LIB_ENTRY,
                            readZipEntry(
                                sourceZip,
                                nativeEntry,
                                MAX_NATIVE_BYTES
                            )
                        )

                        putBytes(
                            "assets/$PROGRAM_ASSET",
                            artifact
                        )
                        putBytes(
                            "assets/$RUNTIME_ASSET",
                            runtime
                        )
                    }
                }
        }

        require(
            unsignedApk.isFile &&
                unsignedApk.length() > 0L
        ) {
            "Rift++ unsigned APK was not produced"
        }

        val signer = RiftppApkV2Signer(context)
        val signed =
            signer.sign(
                unsignedApk,
                signedApk
            )
        val verified =
            signer.verify(signedApk)

        require(
            signed.apkSha256 ==
                verified.apkSha256
        ) {
            "Rift++ post-sign APK hash verification drift"
        }
        require(
            signed.certificateSha256 ==
                verified.certificateSha256
        ) {
            "Rift++ post-sign certificate verification drift"
        }
        require(
            signed.contentDigestSha256 ==
                verified.contentDigestSha256
        ) {
            "Rift++ post-sign content digest verification drift"
        }

        val publishedUri =
            publishToDownloads(
                signedApk,
                "$packageSegment-arm32-signed.apk"
            )

        val receipt =
            JSONObject()
                .put(
                    "format",
                    "riftpp-editor-bootstrap-apk-v2"
                )
                .put(
                    "state",
                    "signed-verified"
                )
                .put(
                    "package",
                    outputPackageName
                )
                .put(
                    "buildMode",
                    if (debug) "debug" else "production"
                )
                .put(
                    "activity",
                    APP_ACTIVITY
                )
                .put(
                    "sourcePath",
                    sourcePath
                )
                .put(
                    "artifactBytes",
                    artifact.size
                )
                .put(
                    "artifactSha256",
                    artifactSha
                )
                .put(
                    "runtimeBytes",
                    runtime.size
                )
                .put(
                    "runtimeSha256",
                    runtimeSha
                )
                .put(
                    "runtimeDependency",
                    "none"
                )
                .put(
                    "packAuthority",
                    "temporary Rift++ Kotlin bootstrap"
                )
                .put(
                    "signAuthority",
                    "temporary Rift++ Kotlin bootstrap"
                )
                .put(
                    "signatureScheme",
                    2
                )
                .put(
                    "entryCount",
                    entryCount
                )
                .put(
                    "unsignedApk",
                    unsignedApk.absolutePath
                )
                .put(
                    "signedApk",
                    signedApk.absolutePath
                )
                .put(
                    "publishedUri",
                    publishedUri?.toString()
                )
                .put(
                    "apkSha256",
                    verified.apkSha256
                )
                .put(
                    "certificateSha256",
                    verified.certificateSha256
                )
                .put(
                    "contentDigestSha256",
                    verified.contentDigestSha256
                )
                .put(
                    "hostContainsAppSemantics",
                    false
                )
                .put(
                    "appSemantics",
                    "assets/$PROGRAM_ASSET"
                )
                .put(
                    "runtimeProgram",
                    "assets/$RUNTIME_ASSET"
                )
                .put(
                    "uiProtocol",
                    "RUI2"
                )
                .put(
                    "bootstrapNote",
                    "TEMPORARY: APK pack/sign/host scaffolding must be replaced by native Rift++ before S4 promotion."
                )
                .put(
                    "createdAt",
                    System.currentTimeMillis()
                )

        File(
            buildDir,
            "receipt.json"
        )
            .writeText(
                receipt.toString(2),
                Charsets.UTF_8
            )

        return RiftppApkReceipt(
            packageName = outputPackageName,
            activityName = APP_ACTIVITY,
            signedApk = signedApk,
            apkSha256 = verified.apkSha256,
            artifactSha256 = artifactSha,
            runtimeSha256 = runtimeSha,
            certificateSha256 =
                verified.certificateSha256,
            contentDigestSha256 =
                verified.contentDigestSha256,
            entryCount = entryCount,
            publishedUri =
                publishedUri?.toString()
        )
    }

    private fun publishToDownloads(
        signedApk: File,
        displayName: String
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }

        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    displayName
                )
                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    "application/vnd.android.package-archive"
                )
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Rift++"
                )
                put(
                    MediaStore.MediaColumns.IS_PENDING,
                    1
                )
            }

        val uri =
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            )
                ?: error(
                    "Could not publish Rift++ APK to Downloads"
                )

        try {
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) {
                    "Could not open Rift++ Downloads APK output"
                }

                signedApk.inputStream().buffered().use { input ->
                    input.copyTo(output)
                }
            }

            values.clear()
            values.put(
                MediaStore.MediaColumns.IS_PENDING,
                0
            )
            require(
                resolver.update(
                    uri,
                    values,
                    null,
                    null
                ) == 1
            ) {
                "Could not finalize Rift++ APK in Downloads"
            }

            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun packageNameFor(sourcePath: String): String {
        val stem =
            File(sourcePath)
                .nameWithoutExtension
                .lowercase(Locale.US)

        var segment =
            stem.map { value ->
                if (
                    value in 'a'..'z' ||
                    value in '0'..'9' ||
                    value == '_'
                ) {
                    value
                } else {
                    '_'
                }
            }.joinToString("")

        segment = segment.trim('_').take(50)

        if (segment.isEmpty()) {
            segment = "app"
        }
        if (segment.first() !in 'a'..'z') {
            segment = "app_$segment"
        }

        require(
            Regex("^[a-z][a-z0-9_]{0,53}$")
                .matches(segment)
        ) {
            "Could not derive a valid Android package segment"
        }

        return "com.riftpp.$segment"
    }

    private fun dexOrder(name: String): Int =
        if (name == "classes.dex") {
            1
        } else {
            name
                .removePrefix("classes")
                .removeSuffix(".dex")
                .toIntOrNull()
                ?: Int.MAX_VALUE
        }

    private fun readZipEntry(
        zip: ZipFile,
        entry: ZipEntry,
        maxBytes: Int
    ): ByteArray {
        if (entry.size >= 0L) {
            require(entry.size <= maxBytes.toLong()) {
                "APK entry exceeds bound: " + entry.name
            }
        }

        val output = ByteArrayOutputStream()

        zip.getInputStream(entry).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0

            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue

                total += count
                require(total <= maxBytes) {
                    "APK entry exceeds bound while reading: " +
                        entry.name
                }

                output.write(buffer, 0, count)
            }
        }

        return output.toByteArray()
    }

    private fun buildBinaryManifest(
        packageName: String,
        versionName: String
    ): ByteArray {
        val strings =
            listOf(
                "name",
                "hasCode",
                "exported",
                "value",
                "minSdkVersion",
                "versionCode",
                "versionName",
                "targetSdkVersion",
                "android",
                "http://schemas.android.com/apk/res/android",
                "manifest",
                "package",
                packageName,
                "1",
                versionName,
                "uses-sdk",
                "26",
                "36",
                "application",
                "true",
                "activity",
                APP_ACTIVITY,
                "intent-filter",
                "action",
                "android.intent.action.MAIN",
                "category",
                "android.intent.category.LAUNCHER"
            )

        fun index(value: String): Int {
            val found = strings.indexOf(value)
            require(found >= 0) {
                "Manifest string is missing: $value"
            }
            return found
        }

        fun stringAttr(
            name: String,
            value: String,
            namespace: Int =
                index(
                    "http://schemas.android.com/apk/res/android"
                )
        ): ManifestAttr =
            ManifestAttr(
                namespace = namespace,
                name = index(name),
                rawValue = index(value),
                dataType = XML_VALUE_STRING,
                data = index(value)
            )

        fun intAttr(
            name: String,
            rawValue: String,
            value: Int
        ): ManifestAttr =
            ManifestAttr(
                namespace =
                    index(
                        "http://schemas.android.com/apk/res/android"
                    ),
                name = index(name),
                rawValue = index(rawValue),
                dataType = XML_VALUE_INT_DEC,
                data = value
            )

        fun boolAttr(
            name: String,
            rawValue: String,
            value: Boolean
        ): ManifestAttr =
            ManifestAttr(
                namespace =
                    index(
                        "http://schemas.android.com/apk/res/android"
                    ),
                name = index(name),
                rawValue = index(rawValue),
                dataType = XML_VALUE_INT_BOOLEAN,
                data = if (value) -1 else 0
            )

        fun stringPool(): ByteArray {
            val offsets = ArrayList<Int>(strings.size)
            val data = ByteArrayOutputStream()

            for (value in strings) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                require(
                    value.length < 0x80 &&
                        bytes.size < 0x80
                ) {
                    "Manifest string exceeds bootstrap UTF-8 bound"
                }

                offsets += data.size()
                writeManifestLength8(
                    data,
                    value.length
                )
                writeManifestLength8(
                    data,
                    bytes.size
                )
                data.write(bytes)
                data.write(0)
            }

            while (data.size() % 4 != 0) {
                data.write(0)
            }

            val stringsStart =
                28 + (strings.size * 4)
            val bytes = data.toByteArray()
            val output = ByteArrayOutputStream()

            writeManifestChunkHeader(
                output,
                XML_STRING_POOL_TYPE,
                28,
                stringsStart + bytes.size
            )
            writeManifestU32(
                output,
                strings.size
            )
            writeManifestU32(output, 0)
            writeManifestU32(
                output,
                XML_UTF8_FLAG
            )
            writeManifestU32(
                output,
                stringsStart
            )
            writeManifestU32(output, 0)

            for (offset in offsets) {
                writeManifestU32(
                    output,
                    offset
                )
            }

            output.write(bytes)
            return output.toByteArray()
        }

        fun resourceMap(): ByteArray {
            val output = ByteArrayOutputStream()
            writeManifestChunkHeader(
                output,
                XML_RESOURCE_MAP_TYPE,
                8,
                8 + (MANIFEST_RESOURCE_IDS.size * 4)
            )
            for (id in MANIFEST_RESOURCE_IDS) {
                writeManifestU32(output, id)
            }
            return output.toByteArray()
        }

        fun namespace(type: Int): ByteArray {
            val output = ByteArrayOutputStream()
            writeManifestNodeHeader(
                output,
                type,
                24
            )
            writeManifestU32(
                output,
                index("android")
            )
            writeManifestU32(
                output,
                index(
                    "http://schemas.android.com/apk/res/android"
                )
            )
            return output.toByteArray()
        }

        fun startElement(
            name: String,
            attrs: List<ManifestAttr>
        ): ByteArray {
            val output = ByteArrayOutputStream()

            writeManifestNodeHeader(
                output,
                XML_START_ELEMENT_TYPE,
                36 + (attrs.size * 20)
            )
            writeManifestU32(
                output,
                XML_NO_INDEX
            )
            writeManifestU32(
                output,
                index(name)
            )
            writeManifestU16(output, 20)
            writeManifestU16(output, 20)
            writeManifestU16(
                output,
                attrs.size
            )
            writeManifestU16(output, 0)
            writeManifestU16(output, 0)
            writeManifestU16(output, 0)

            for (attr in attrs) {
                writeManifestU32(
                    output,
                    attr.namespace
                )
                writeManifestU32(
                    output,
                    attr.name
                )
                writeManifestU32(
                    output,
                    attr.rawValue
                )
                writeManifestU16(output, 8)
                output.write(0)
                output.write(attr.dataType)
                writeManifestU32(
                    output,
                    attr.data
                )
            }

            return output.toByteArray()
        }

        fun endElement(name: String): ByteArray {
            val output = ByteArrayOutputStream()
            writeManifestNodeHeader(
                output,
                XML_END_ELEMENT_TYPE,
                24
            )
            writeManifestU32(
                output,
                XML_NO_INDEX
            )
            writeManifestU32(
                output,
                index(name)
            )
            return output.toByteArray()
        }

        val body = ByteArrayOutputStream()
        body.write(stringPool())
        body.write(resourceMap())
        body.write(
            namespace(
                XML_START_NAMESPACE_TYPE
            )
        )

        body.write(
            startElement(
                "manifest",
                listOf(
                    stringAttr(
                        "package",
                        packageName,
                        XML_NO_INDEX
                    ),
                    intAttr(
                        "versionCode",
                        "1",
                        1
                    ),
                    stringAttr(
                        "versionName",
                        versionName
                    )
                )
            )
        )

        body.write(
            startElement(
                "uses-sdk",
                listOf(
                    intAttr(
                        "minSdkVersion",
                        "26",
                        26
                    ),
                    intAttr(
                        "targetSdkVersion",
                        "36",
                        36
                    )
                )
            )
        )
        body.write(
            endElement("uses-sdk")
        )

        body.write(
            startElement(
                "application",
                listOf(
                    boolAttr(
                        "hasCode",
                        "true",
                        true
                    )
                )
            )
        )

        body.write(
            startElement(
                "activity",
                listOf(
                    stringAttr(
                        "name",
                        APP_ACTIVITY
                    ),
                    boolAttr(
                        "exported",
                        "true",
                        true
                    )
                )
            )
        )

        body.write(
            startElement(
                "intent-filter",
                emptyList()
            )
        )
        body.write(
            startElement(
                "action",
                listOf(
                    stringAttr(
                        "name",
                        "android.intent.action.MAIN"
                    )
                )
            )
        )
        body.write(endElement("action"))

        body.write(
            startElement(
                "category",
                listOf(
                    stringAttr(
                        "name",
                        "android.intent.category.LAUNCHER"
                    )
                )
            )
        )
        body.write(endElement("category"))
        body.write(endElement("intent-filter"))
        body.write(endElement("activity"))
        body.write(endElement("application"))
        body.write(endElement("manifest"))

        body.write(
            namespace(
                XML_END_NAMESPACE_TYPE
            )
        )

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()

        writeManifestChunkHeader(
            output,
            XML_TYPE,
            8,
            8 + bodyBytes.size
        )
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun writeManifestNodeHeader(
        output: ByteArrayOutputStream,
        type: Int,
        size: Int
    ) {
        writeManifestChunkHeader(
            output,
            type,
            16,
            size
        )
        writeManifestU32(output, 1)
        writeManifestU32(
            output,
            XML_NO_INDEX
        )
    }

    private fun writeManifestChunkHeader(
        output: ByteArrayOutputStream,
        type: Int,
        headerSize: Int,
        size: Int
    ) {
        writeManifestU16(output, type)
        writeManifestU16(
            output,
            headerSize
        )
        writeManifestU32(output, size)
    }

    private fun writeManifestLength8(
        output: ByteArrayOutputStream,
        value: Int
    ) {
        require(value in 0..0x7f) {
            "Manifest UTF-8 length overflow"
        }
        output.write(value)
    }

    private fun writeManifestU16(
        output: ByteArrayOutputStream,
        value: Int
    ) {
        output.write(value and 0xff)
        output.write(
            (value ushr 8) and 0xff
        )
    }

    private fun writeManifestU32(
        output: ByteArrayOutputStream,
        value: Int
    ) {
        output.write(value and 0xff)
        output.write(
            (value ushr 8) and 0xff
        )
        output.write(
            (value ushr 16) and 0xff
        )
        output.write(
            (value ushr 24) and 0xff
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(
                    it.toInt() and 0xff
                )
            }
}
