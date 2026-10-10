package com.riftos.app

import android.content.Context
import dalvik.system.DexClassLoader
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Bounded DEX reference/class-definition check for independently built
 * critical components. Links entrypoint but does not execute its constructor.
 * This is not a same-UID security sandbox or behavioral/device proof.
 */
internal object RiftProtectedDexVerifier {
    const val SCHEMA = "riftos.protected-dex-closure/1"
    private const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_CLASSES = 4096

    private fun integer(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 4 <= bytes.size) { "DEX integer out of bounds" }
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun section(bytes: ByteArray, offset: Int, count: Int, stride: Int) {
        require(offset >= 0 && count >= 0 && stride > 0 &&
            offset.toLong() + count.toLong() * stride <= bytes.size.toLong()) {
            "Protected DEX table out of bounds"
        }
    }

    private fun descriptor(bytes: ByteArray, dataOffset: Int): String {
        require(dataOffset in 0 until bytes.size) { "Invalid DEX string data offset" }
        var offset = dataOffset
        var shift = 0
        var charLength = 0
        while (true) {
            require(offset < bytes.size && shift < 35) { "Invalid DEX type string length" }
            val byte = bytes[offset++].toInt() and 255
            charLength = charLength or ((byte and 127) shl shift)
            if ((byte and 128) == 0) break
            shift += 7
        }
        val result = StringBuilder()
        var terminated = false
        while (offset < bytes.size && result.length <= 768) {
            val value = bytes[offset++].toInt() and 255
            if (value == 0) { terminated = true; break }
            require(value in 33..126) { "Non-ASCII DEX type descriptor" }
            result.append(value.toChar())
        }
        require(terminated && result.length in 1..768 &&
            result.length == charLength) { "Invalid DEX descriptor content/length" }
        return result.toString()
    }

    fun inspect(context: Context, manifest: RiftProtectedComponentManifest,
                dex: File): JSONObject {
        manifest.validate()
        require(dex.isFile && !Files.isSymbolicLink(dex.toPath()) &&
            !dex.canWrite() && dex.length() in 112L..MAX_BYTES.toLong()) {
            "Protected DEX must be an immutable regular file"
        }
        val bytes = dex.readBytes()
        require(bytes.size.toLong() == dex.length() &&
            bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10)) &&
            integer(bytes, 32) == bytes.size && integer(bytes, 36) == 112 &&
            integer(bytes, 40) == 0x12345678) {
            "Invalid protected Android DEX header"
        }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual == manifest.sha256) { "Protected DEX SHA-256 mismatch" }

        val stringCount = integer(bytes, 56)
        val stringOff = integer(bytes, 60)
        val typeCount = integer(bytes, 64)
        val typeOff = integer(bytes, 68)
        val classCount = integer(bytes, 96)
        val classOff = integer(bytes, 100)
        require(stringCount in 1..200000 && typeCount in 1..65535 &&
            classCount in 1..MAX_CLASSES) { "Protected DEX table count invalid" }
        section(bytes, stringOff, stringCount, 4)
        section(bytes, typeOff, typeCount, 4)
        section(bytes, classOff, classCount, 32)

        val strings = HashMap<Int, String>()
        fun lookup(index: Int): String {
            require(index in 0 until stringCount) { "DEX string index invalid" }
            return strings.getOrPut(index) {
                descriptor(bytes, integer(bytes, stringOff + index * 4))
            }
        }
        val prefix = "Lcom/riftos/external/" + manifest.component + "/"
        val stable = setOf(
            "Lcom/riftos/app/RiftCoreComponentV1;",
            "Lcom/riftos/app/RiftCoreExecutionViewV1;",
            "Lcom/riftos/app/RiftShellGraphicalComponentV1;",
            "Lcom/riftos/app/RiftShellPlatformServicesV1;",
            "Lcom/riftos/app/RiftAppAbi;"
        )
        val types = ArrayList<String>(typeCount)
        for (index in 0 until typeCount) {
            val raw = lookup(integer(bytes, typeOff + index * 4))
            var desc = raw
            while (desc.startsWith("[")) desc = desc.substring(1)
            if (desc.startsWith("Lcom/riftos/app/")) {
                require(desc in stable ||
                    desc.startsWith("Lcom/riftos/app/RiftAppAbi$")) {
                    "External component references APK-owned execution: $desc"
                }
            }
            require(!desc.startsWith("Lcom/riftos/external/") ||
                desc.startsWith(prefix)) {
                "External component references another independent implementation"
            }
            types.add(raw)
        }
        val entryDescriptor = "L" + manifest.entrypoint.replace('.', '/') + ";"
        var found = false
        for (index in 0 until classCount) {
            val id = integer(bytes, classOff + index * 32)
            require(id in 0 until typeCount) { "Protected class index invalid" }
            val name = types[id]
            require(name.startsWith(prefix) && name.endsWith(";")) {
                "Critical DEX defines class outside its independent namespace"
            }
            if (name == entryDescriptor) found = true
        }
        require(found) { "Critical DEX entrypoint missing from class definitions" }

        // No candidate constructor/static initializer or RAPP operation is
        // executed by this inactive linkage check.
        val loader = DexClassLoader(dex.absolutePath,
            context.codeCacheDir.absolutePath, null, context.classLoader)
        val type = loader.loadClass(manifest.entrypoint)
        val expected: Class<*> = if (manifest.component == "core") {
            RiftCoreComponentV1::class.java
        } else RiftShellGraphicalComponentV1::class.java
        require(type.classLoader === loader &&
            expected.isAssignableFrom(type) &&
            (manifest.component != "core" ||
                RiftCoreExecutionViewV1::class.java.isAssignableFrom(type)) &&
            type.getDeclaredConstructor() != null) {
            "Critical DEX entrypoint does not implement protected host ABI"
        }
        return JSONObject().put("schema", SCHEMA)
            .put("component", manifest.component)
            .put("entrypoint", manifest.entrypoint)
            .put("sha256", manifest.sha256)
            .put("classCount", classCount)
            .put("referencedTypeCount", typeCount)
            .put("hostExecutionClassReferences", 0)
            .put("inactiveLinkProven", true)
            .put("behavioralDeviceProof", false)
            .put("sameUidSandbox", false)
    }
}
