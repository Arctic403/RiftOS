package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject

enum class RiftMemoryBranchV1 {
    REALITY,
    HYPOTHESIS,
    SIMULATION,
    COUNTERFACTUAL
}

enum class RiftMemoryTrustStateV1 {
    VERIFIED,
    TRUSTED,
    PROVISIONAL,
    UNVERIFIED,
    CONFLICTED,
    QUARANTINED,
    SUPERSEDED,
    INVALIDATED,
    HISTORICAL,
    ARCHIVED
}

enum class RiftMemoryRecordKindV1 {
    CLAIM,
    BELIEF,
    POLICY,
    CONFIG
}

data class RiftMemoryScopeV1(
    val namespace: String,
    val workspaceId: String? = null,
    val projectId: String? = null
) {
    init {
        require(namespace.isNotBlank() && namespace.length <= 256)
        require(workspaceId == null || workspaceId.length <= 256)
        require(projectId == null || projectId.length <= 256)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("namespace", namespace)
        .put("workspaceId", workspaceId ?: JSONObject.NULL)
        .put("projectId", projectId ?: JSONObject.NULL)

    companion object {
        fun fromJson(value: JSONObject): RiftMemoryScopeV1 = RiftMemoryScopeV1(
            namespace = value.getString("namespace"),
            workspaceId = value.opt("workspaceId").takeUnless { it == null || it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() },
            projectId = value.opt("projectId").takeUnless { it == null || it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
        )
    }
}

data class RiftMemoryBiTemporalV1(
    val validFrom: Long,
    val validTo: Long? = null,
    val recordedAt: Long
) {
    init {
        require(validFrom >= 0L)
        require(recordedAt >= 0L)
        require(validTo == null || validTo >= validFrom)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("validFrom", validFrom)
        .put("validTo", validTo ?: JSONObject.NULL)
        .put("recordedAt", recordedAt)

    companion object {
        fun fromJson(value: JSONObject): RiftMemoryBiTemporalV1 = RiftMemoryBiTemporalV1(
            validFrom = value.getLong("validFrom"),
            validTo = value.opt("validTo").takeUnless { it == null || it == JSONObject.NULL }?.let {
                value.getLong("validTo")
            },
            recordedAt = value.getLong("recordedAt")
        )
    }
}

data class RiftMemoryEvidenceV1(
    val id: String,
    val contentSha256: String,
    val mediaType: String,
    val sourceType: String,
    val sourceRef: String,
    val scope: RiftMemoryScopeV1,
    val branch: RiftMemoryBranchV1,
    val observedAt: Long,
    val recordedAt: Long,
    val trustState: RiftMemoryTrustStateV1,
    val metadata: JSONObject = JSONObject()
) {
    init {
        RiftMemoryModelV1.requireId(id)
        require(RiftMemoryModelV1.SHA256.matches(contentSha256))
        require(mediaType.isNotBlank() && mediaType.length <= 128)
        require(sourceType.isNotBlank() && sourceType.length <= 128)
        require(sourceRef.isNotBlank() && sourceRef.length <= 4096)
        require(observedAt >= 0L && recordedAt >= 0L)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schema", RiftMemoryModelV1.EVIDENCE_SCHEMA)
        .put("id", id)
        .put("contentSha256", contentSha256)
        .put("mediaType", mediaType)
        .put("sourceType", sourceType)
        .put("sourceRef", sourceRef)
        .put("scope", scope.toJson())
        .put("branch", branch.name)
        .put("observedAt", observedAt)
        .put("recordedAt", recordedAt)
        .put("trustState", trustState.name)
        .put("metadata", JSONObject(metadata.toString()))

    companion object {
        fun fromJson(value: JSONObject): RiftMemoryEvidenceV1 {
            require(value.getString("schema") == RiftMemoryModelV1.EVIDENCE_SCHEMA)
            return RiftMemoryEvidenceV1(
                id = value.getString("id"),
                contentSha256 = value.getString("contentSha256"),
                mediaType = value.getString("mediaType"),
                sourceType = value.getString("sourceType"),
                sourceRef = value.getString("sourceRef"),
                scope = RiftMemoryScopeV1.fromJson(value.getJSONObject("scope")),
                branch = RiftMemoryBranchV1.valueOf(value.getString("branch")),
                observedAt = value.getLong("observedAt"),
                recordedAt = value.getLong("recordedAt"),
                trustState = RiftMemoryTrustStateV1.valueOf(value.getString("trustState")),
                metadata = JSONObject(value.optJSONObject("metadata")?.toString() ?: "{}")
            )
        }
    }
}

data class RiftCanonicalMemoryRecordV1(
    val id: String,
    val kind: RiftMemoryRecordKindV1,
    val scope: RiftMemoryScopeV1,
    val branch: RiftMemoryBranchV1,
    val trustState: RiftMemoryTrustStateV1,
    val time: RiftMemoryBiTemporalV1,
    val payload: JSONObject,
    val evidenceRefs: List<String>,
    val authorityNamespace: String? = null
) {
    init {
        RiftMemoryModelV1.requireId(id)
        require(evidenceRefs.size <= RiftMemoryModelV1.MAX_EVIDENCE_REFS)
        require(evidenceRefs.distinct().size == evidenceRefs.size)
        evidenceRefs.forEach(RiftMemoryModelV1::requireId)
        require(authorityNamespace == null || authorityNamespace.length <= 256)
        if (kind == RiftMemoryRecordKindV1.POLICY || kind == RiftMemoryRecordKindV1.CONFIG) {
            require(authorityNamespace != null && RiftMemoryModelV1.isProtectedNamespace(authorityNamespace))
            require(branch == RiftMemoryBranchV1.REALITY)
        }
        if (trustState == RiftMemoryTrustStateV1.VERIFIED || trustState == RiftMemoryTrustStateV1.TRUSTED) {
            require(evidenceRefs.isNotEmpty()) {
                "Trusted/verified canonical memory requires evidence provenance."
            }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schema", RiftMemoryModelV1.RECORD_SCHEMA)
        .put("schemaVersion", RiftMemoryModelV1.SCHEMA_VERSION)
        .put("id", id)
        .put("kind", kind.name)
        .put("scope", scope.toJson())
        .put("branch", branch.name)
        .put("trustState", trustState.name)
        .put("time", time.toJson())
        .put("payload", JSONObject(payload.toString()))
        .put("evidenceRefs", JSONArray(evidenceRefs))
        .put("authorityNamespace", authorityNamespace ?: JSONObject.NULL)

    companion object {
        fun fromJson(value: JSONObject): RiftCanonicalMemoryRecordV1 {
            val migrated = RiftMemoryModelV1.migrateRecord(value)
            val refs = migrated.getJSONArray("evidenceRefs")
            return RiftCanonicalMemoryRecordV1(
                id = migrated.getString("id"),
                kind = RiftMemoryRecordKindV1.valueOf(migrated.getString("kind")),
                scope = RiftMemoryScopeV1.fromJson(migrated.getJSONObject("scope")),
                branch = RiftMemoryBranchV1.valueOf(migrated.getString("branch")),
                trustState = RiftMemoryTrustStateV1.valueOf(migrated.getString("trustState")),
                time = RiftMemoryBiTemporalV1.fromJson(migrated.getJSONObject("time")),
                payload = JSONObject(migrated.getJSONObject("payload").toString()),
                evidenceRefs = List(refs.length()) { refs.getString(it) },
                authorityNamespace = migrated.opt("authorityNamespace").takeUnless { it == null || it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
            )
        }
    }
}

data class RiftMemoryEventV1(
    val id: String,
    val type: String,
    val recordId: String? = null,
    val evidenceId: String? = null,
    val scope: RiftMemoryScopeV1,
    val branch: RiftMemoryBranchV1,
    val at: Long,
    val validFrom: Long,
    val validTo: Long? = null,
    val payload: JSONObject = JSONObject()
) {
    init {
        RiftMemoryModelV1.requireId(id)
        require(type.isNotBlank() && type.length <= 128)
        recordId?.let(RiftMemoryModelV1::requireId)
        evidenceId?.let(RiftMemoryModelV1::requireId)
        require(at >= 0L && validFrom >= 0L)
        require(validTo == null || validTo >= validFrom)
    }

    fun toJson(previousEventHash: String?): JSONObject = JSONObject()
        .put("schema", RiftMemoryModelV1.EVENT_SCHEMA)
        .put("id", id)
        .put("type", type)
        .put("recordId", recordId ?: JSONObject.NULL)
        .put("evidenceId", evidenceId ?: JSONObject.NULL)
        .put("scope", scope.toJson())
        .put("branch", branch.name)
        .put("at", at)
        .put("validFrom", validFrom)
        .put("validTo", validTo ?: JSONObject.NULL)
        .put("payload", JSONObject(payload.toString()))
        .put("previousEventHash", previousEventHash ?: JSONObject.NULL)
}

object RiftMemoryModelV1 {
    const val SCHEMA_VERSION = 1
    const val RECORD_SCHEMA = "rift-memory-record-v1"
    const val EVIDENCE_SCHEMA = "rift-memory-evidence-v1"
    const val EVENT_SCHEMA = "rift-memory-event-v1"
    const val POLICY_NAMESPACE_PREFIX = "policy/"
    const val CONFIG_NAMESPACE_PREFIX = "config/"
    const val MAX_EVIDENCE_REFS = 256
    val SHA256 = Regex("^[0-9a-f]{64}$")

    fun requireId(value: String) {
        require(value.isNotBlank() && value.length <= 256)
        require(value.none { it == '\u0000' || it == '\n' || it == '\r' })
    }

    fun isProtectedNamespace(value: String): Boolean =
        value.startsWith(POLICY_NAMESPACE_PREFIX) || value.startsWith(CONFIG_NAMESPACE_PREFIX)

    fun migrateRecord(value: JSONObject): JSONObject {
        require(value.getString("schema") == RECORD_SCHEMA)
        val version = value.optInt("schemaVersion", 0)
        return when (version) {
            SCHEMA_VERSION -> JSONObject(value.toString())
            0 -> {
                val migrated = JSONObject(value.toString())
                val recordedAt = migrated.getLong("recordedAt")
                val validFrom = migrated.optLong("validFrom", recordedAt)
                migrated.remove("recordedAt")
                migrated.remove("validFrom")
                migrated.put("schemaVersion", SCHEMA_VERSION)
                migrated.put(
                    "time",
                    JSONObject()
                        .put("validFrom", validFrom)
                        .put("validTo", JSONObject.NULL)
                        .put("recordedAt", recordedAt)
                )
                migrated
            }
            else -> error("Unsupported Rift memory record schemaVersion=$version")
        }
    }

    fun canonicalSha256(value: JSONObject): String =
        RiftPatchManifestV1.sha256Canonical(value)
}
