package com.riftos.app

import android.os.Build
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Strict metadata for a real, separately compiled Core or graphical Shell.
 * It has no RAPP/runtime-provider interpretation and grants no execution.
 */
internal data class RiftProtectedComponentManifest(
    val component: String,
    val version: String,
    val entrypoint: String,
    val sha256: String,
    val buildSha256: String,
    val payload: String,
    val abi: Int,
    val minSdk: Int,
    val maxSdk: Int
) {
    companion object {
        const val SCHEMA = "riftos.protected-component/1"
        const val MAX_BYTES = 4096
        private val DIGEST = Regex("^[0-9a-f]{64}$")
        private val VERSION = Regex("^[0-9][A-Za-z0-9._+-]{0,63}$")
        private val JAVA_NAME =
            Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
        private val FIELDS = setOf("schema", "component", "version", "entrypoint",
            "sha256", "buildSha256", "payload", "abi", "minSdk", "maxSdk")

        fun parse(bytes: ByteArray): RiftProtectedComponentManifest {
            require(bytes.size in 1..MAX_BYTES) { "Protected manifest size invalid" }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val value = JSONObject(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString())
            val seen = mutableSetOf<String>()
            val names = value.keys()
            while (names.hasNext()) seen.add(names.next())
            require(seen == FIELDS && value.getString("schema") == SCHEMA) {
                "Protected component manifest fields/schema mismatch"
            }
            for (field in listOf("schema", "component", "version", "entrypoint",
                "sha256", "buildSha256", "payload")) {
                require(value.get(field) is String) {
                    "Protected manifest $field must be a string"
                }
            }
            for (field in listOf("abi", "minSdk", "maxSdk")) {
                require(value.get(field) is Int) {
                    "Protected manifest $field must be an integer"
                }
            }
            return RiftProtectedComponentManifest(
                component = value.getString("component"),
                version = value.getString("version"),
                entrypoint = value.getString("entrypoint"),
                sha256 = value.getString("sha256"),
                buildSha256 = value.getString("buildSha256"),
                payload = value.getString("payload"),
                abi = value.getInt("abi"),
                minSdk = value.getInt("minSdk"),
                maxSdk = value.getInt("maxSdk")
            ).also { it.validate() }
        }
    }

    fun validate() {
        require(component == "core" || component == "shell") {
            "Protected component kind invalid"
        }
        require(version.matches(VERSION) && sha256.matches(DIGEST) &&
            buildSha256.matches(DIGEST)) { "Protected component version/build digest invalid" }
        require(payload == "$component.dex" &&
            entrypoint.matches(JAVA_NAME) &&
            entrypoint.startsWith("com.riftos.external.$component.")) {
            "Protected component entrypoint/payload must be external-only"
        }
        require(abi == RiftHostCoreComponents.ABI_VERSION &&
            minSdk in 26..1000 && maxSdk in minSdk..1000 &&
            Build.VERSION.SDK_INT in minSdk..maxSdk) {
            "Protected component host ABI or Android SDK incompatible"
        }
    }

    fun identityDigest(): String {
        validate()
        val fields = listOf(SCHEMA, component, version, entrypoint,
            sha256, buildSha256, payload, abi.toString(),
            minSdk.toString(), maxSdk.toString())
        return MessageDigest.getInstance("SHA-256")
            .digest(fields.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun activationRecord(): JSONObject = JSONObject()
        .put("schema", RiftBootstrapComponentStore.SCHEMA)
        .put("component", component).put("api", abi)
        .put("entrypoint", entrypoint).put("sha256", sha256)

    fun receipt(): JSONObject = JSONObject()
        .put("schema", SCHEMA).put("component", component)
        .put("version", version).put("entrypoint", entrypoint)
        .put("sha256", sha256).put("buildSha256", buildSha256)
        .put("payload", payload).put("abi", abi)
        .put("minSdk", minSdk).put("maxSdk", maxSdk)
        .put("manifestDigest", identityDigest())
}
