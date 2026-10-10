package com.riftos.app

import android.os.Build
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Reusable, strictly bounded descriptor for one privileged external module.
 *
 * This parser grants NO execution or capability. It does not install RAPPs
 * and does not register external runtime-provider APKs.
 *
 * Gate 2-A accepts only explicitly trusted DEX service modules. Those run
 * in a *separate Android process under the same app UID*, not a security
 * sandbox. Additional module kinds require a separately reviewed adapter.
 */
internal data class RiftCoreModuleManifest(
    val id: String,
    val name: String,
    val version: String,
    val kind: String,
    val abi: String,
    val entrypoint: String,
    val payload: String,
    val sha256: String,
    val minSdk: Int,
    val maxSdk: Int
) {
    companion object {
        const val SCHEMA = "riftos.module/1"
        const val KIND_DEX_SERVICE = "dex-service-v1"
        const val ABI_BOOTSTRAP_ENTRY = "riftos.bootstrap-entry/1"
        const val MAX_MANIFEST_BYTES = 4096
        const val MAX_MODULE_BYTES = 32L * 1024 * 1024

        private val SAFE_ID = Regex("^[a-z][a-z0-9._-]{0,79}$")
        private val SAFE_NAME = Regex("^[^\u0000-\u001f\u007f]{1,100}$")
        private val SAFE_VERSION = Regex("^[0-9][A-Za-z0-9._+-]{0,63}$")
        private val SAFE_CLASS =
            Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
        private val SAFE_FILENAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.dex$")
        private val SAFE_SHA256 = Regex("^[0-9a-f]{64}$")
        private val KEYS = setOf(
            "schema", "id", "name", "version", "kind", "abi",
            "entrypoint", "payload", "sha256", "minSdk", "maxSdk",
            "requestedCapabilities", "dependencies"
        )

        fun parse(raw: ByteArray): RiftCoreModuleManifest {
            require(raw.size in 1..MAX_MANIFEST_BYTES) {
                "Module manifest must be 1..4096 bytes"
            }
            // Round-trip strict UTF-8 so malformed byte sequences cannot be
            // normalized into a different user-approved identity.
            val decoded = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(raw))
                .toString()
            val json = JSONObject(decoded)
            val keys = mutableSetOf<String>()
            val iter = json.keys()
            while (iter.hasNext()) keys.add(iter.next())
            require(keys == KEYS) { "Module manifest requires exact v1 fields" }
            require(json.getString("schema") == SCHEMA) { "Unknown module manifest schema" }

            for (key in listOf("schema", "id", "name", "version", "kind", "abi",
                "entrypoint", "payload", "sha256")) {
                require(json.get(key) is String) { "Module $key must be a JSON string" }
            }
            require(json.get("minSdk") is Int && json.get("maxSdk") is Int) {
                "Module SDK bounds must be JSON integers"
            }
            val permissions = json.getJSONArray("requestedCapabilities")
            val deps = json.getJSONArray("dependencies")
            require(permissions.length() == 0 && deps.length() == 0) {
                "Generic DEX proof does not admit capabilities or dependencies"
            }

            val item = RiftCoreModuleManifest(
                id = json.getString("id"),
                name = json.getString("name"),
                version = json.getString("version"),
                kind = json.getString("kind"),
                abi = json.getString("abi"),
                entrypoint = json.getString("entrypoint"),
                payload = json.getString("payload"),
                sha256 = json.getString("sha256"),
                minSdk = json.getInt("minSdk"),
                maxSdk = json.getInt("maxSdk")
            )
            item.validate()
            return item
        }
    }

    fun validate() {
        require(id.matches(SAFE_ID) && !id.startsWith("riftos.") &&
            id != "." && id != "..") { "Invalid/reserved module id" }
        require(name.matches(SAFE_NAME) && name == name.trim()) {
            "Invalid module display name"
        }
        require(version.matches(SAFE_VERSION)) { "Invalid module version" }
        require(kind == KIND_DEX_SERVICE && abi == ABI_BOOTSTRAP_ENTRY) {
            "Unsupported module execution kind/ABI"
        }
        require(entrypoint.matches(SAFE_CLASS)) { "Invalid module entrypoint" }
        require(payload.matches(SAFE_FILENAME) &&
            !payload.startsWith(".")) { "Invalid DEX payload name" }
        require(sha256.matches(SAFE_SHA256)) { "Invalid module digest" }
        require(minSdk in 26..1000 && maxSdk in minSdk..1000 &&
            Build.VERSION.SDK_INT in minSdk..maxSdk) {
            "Module Android API range is incompatible with this device"
        }
    }

    /** Canonical content identity: always includes both metadata AND DEX hash. */
    fun identityDigest(): String {
        validate()
        val fields = listOf(
            SCHEMA, id, name, version, kind, abi, entrypoint,
            payload, sha256, minSdk.toString(), maxSdk.toString(),
            "capabilities:none", "dependencies:none"
        )
        val binary = fields.joinToString("\u0000").toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(binary)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun receipt(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("id", id)
        .put("name", name)
        .put("version", version)
        .put("kind", kind)
        .put("abi", abi)
        .put("entrypoint", entrypoint)
        .put("payload", payload)
        .put("sha256", sha256)
        .put("minSdk", minSdk)
        .put("maxSdk", maxSdk)
        .put("requestedCapabilities", org.json.JSONArray())
        .put("dependencies", org.json.JSONArray())
        .put("manifestDigest", identityDigest())
}
