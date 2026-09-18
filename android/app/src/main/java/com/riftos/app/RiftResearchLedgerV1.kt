package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Bounded external-research evidence schema for the CLI patch lifecycle.
 *
 * This ledger records what was researched and which claims each source supports. It does not turn
 * AI-supplied prose into trusted fact: final independent evaluation must re-check critical claims.
 */
internal object RiftResearchLedgerV1 {
    const val SCHEMA = "rift.research-ledger/1"
    const val VERSION = 1
    const val MAX_ENTRIES = 128
    const val MAX_ASSUMPTIONS = 64
    private const val MAX_ID = 120
    private const val MAX_URI = 2_048
    private const val MAX_PUBLISHER = 180
    private const val MAX_TITLE = 300
    private const val MAX_CLAIM = 2_000
    private const val MAX_VERSION = 240
    private const val MAX_REASON = 2_000

    private val SOURCE_TYPES = setOf(
        "official",
        "specification",
        "primary",
        "standards-body",
        "secondary",
        "community"
    )

    fun validate(raw: JSONObject): JSONObject {
        require(raw.optString("schema") == SCHEMA) { "Research ledger schema must be $SCHEMA" }
        val required = raw.optBoolean("required", true)
        val reason = raw.optString("notRequiredReason").trim().take(MAX_REASON)
        if (!required) require(reason.isNotBlank()) { "Research-not-required needs a reason" }

        val entriesRaw = raw.optJSONArray("entries") ?: JSONArray()
        val assumptionsRaw = raw.optJSONArray("assumptions") ?: JSONArray()
        require(entriesRaw.length() <= MAX_ENTRIES) { "Research ledger exceeds $MAX_ENTRIES entries" }
        require(assumptionsRaw.length() <= MAX_ASSUMPTIONS) { "Research ledger exceeds $MAX_ASSUMPTIONS assumptions" }

        val entries = JSONArray()
        val entryIds = LinkedHashSet<String>()
        val authoritativeIds = LinkedHashSet<String>()
        for (index in 0 until entriesRaw.length()) {
            val source = entriesRaw.optJSONObject(index)
                ?: throw IllegalArgumentException("Research entry $index must be an object")
            val id = bounded(source.optString("id"), MAX_ID, "Research entry id")
            require(entryIds.add(id)) { "Duplicate research entry id: $id" }
            val sourceType = source.optString("sourceType").trim().lowercase()
            require(sourceType in SOURCE_TYPES) { "Unsupported research sourceType: $sourceType" }
            val uri = bounded(source.optString("uri"), MAX_URI, "Research source uri")
            require(uri.startsWith("https://")) { "Research source must use https://: $id" }
            val publisher = bounded(source.optString("publisher"), MAX_PUBLISHER, "Research publisher")
            val title = bounded(source.optString("title"), MAX_TITLE, "Research title")
            val retrievedAt = bounded(source.optString("retrievedAt"), 64, "Research retrievedAt")
            val instant = runCatching { Instant.parse(retrievedAt) }
                .getOrElse { throw IllegalArgumentException("Research retrievedAt must be ISO-8601 UTC: $id") }
            require(!instant.isAfter(Instant.now())) { "Research source is future-dated: $id" }
            val claim = bounded(source.optString("claim"), MAX_CLAIM, "Research claim")
            val version = source.optString("versionOrDate").trim().take(MAX_VERSION)
            val contentSha = source.optString("contentSha256").trim().lowercase()
            require(contentSha.isBlank() || contentSha.matches(Regex("^[0-9a-f]{64}$"))) {
                "Research contentSha256 must be empty or 64 lowercase hex: $id"
            }
            val supportsRaw = source.optJSONArray("supports") ?: JSONArray()
            require(supportsRaw.length() <= MAX_ASSUMPTIONS) { "Research supports list is too large: $id" }
            val supports = JSONArray()
            for (supportIndex in 0 until supportsRaw.length()) {
                supports.put(bounded(supportsRaw.optString(supportIndex), MAX_ID, "Supported assumption id"))
            }

            if (sourceType in setOf("official", "specification", "primary", "standards-body")) {
                authoritativeIds += id
            }
            entries.put(JSONObject()
                .put("id", id)
                .put("sourceType", sourceType)
                .put("uri", uri)
                .put("publisher", publisher)
                .put("title", title)
                .put("retrievedAt", retrievedAt)
                .put("claim", claim)
                .put("versionOrDate", version.ifBlank { JSONObject.NULL })
                .put("contentSha256", contentSha.ifBlank { JSONObject.NULL })
                .put("supports", supports))
        }

        val assumptions = JSONArray()
        var unresolved = 0
        var criticalWithoutAuthoritativeSource = 0
        val assumptionIds = LinkedHashSet<String>()
        for (index in 0 until assumptionsRaw.length()) {
            val assumption = assumptionsRaw.optJSONObject(index)
                ?: throw IllegalArgumentException("Research assumption $index must be an object")
            val id = bounded(assumption.optString("id"), MAX_ID, "Research assumption id")
            require(assumptionIds.add(id)) { "Duplicate research assumption id: $id" }
            val claim = bounded(assumption.optString("claim"), MAX_CLAIM, "Research assumption claim")
            val critical = assumption.optBoolean("critical", false)
            val status = assumption.optString("status", "unresolved").trim().lowercase()
            require(status in setOf("supported", "disputed", "unresolved")) {
                "Unsupported research assumption status: $status"
            }
            val sourceIdsRaw = assumption.optJSONArray("sourceIds") ?: JSONArray()
            require(sourceIdsRaw.length() <= MAX_ENTRIES) { "Research assumption source list too large: $id" }
            val sourceIds = JSONArray()
            var hasAuthoritative = false
            for (sourceIndex in 0 until sourceIdsRaw.length()) {
                val sourceId = bounded(sourceIdsRaw.optString(sourceIndex), MAX_ID, "Research source id")
                require(sourceId in entryIds) { "Unknown research source id $sourceId for assumption $id" }
                if (sourceId in authoritativeIds) hasAuthoritative = true
                sourceIds.put(sourceId)
            }
            if (status == "supported") {
                require(sourceIds.length() > 0) { "Supported research assumption has no source: $id" }
            }
            if (status == "unresolved" || status == "disputed") unresolved++
            if (critical && !hasAuthoritative) criticalWithoutAuthoritativeSource++
            assumptions.put(JSONObject()
                .put("id", id)
                .put("claim", claim)
                .put("critical", critical)
                .put("status", status)
                .put("sourceIds", sourceIds)
                .put("authoritativeSourcePresent", hasAuthoritative))
        }

        for (entryIndex in 0 until entries.length()) {
            val entry = entries.getJSONObject(entryIndex)
            val supports = entry.getJSONArray("supports")
            for (supportIndex in 0 until supports.length()) {
                val assumptionId = supports.getString(supportIndex)
                require(assumptionId in assumptionIds) {
                    "Research source references unknown supported assumption: " + assumptionId
                }
            }
        }

        if (required) {
            require(entries.length() > 0) { "Required research ledger has no sources" }
            require(assumptions.length() > 0) { "Required research ledger has no assumptions/claims" }
        }

        val complete = !required || (unresolved == 0 && criticalWithoutAuthoritativeSource == 0)
        val normalized = JSONObject()
            .put("schema", SCHEMA)
            .put("version", VERSION)
            .put("required", required)
            .put("notRequiredReason", if (required) JSONObject.NULL else reason)
            .put("entries", entries)
            .put("assumptions", assumptions)
            .put("complete", complete)
            .put("unresolvedAssumptions", unresolved)
            .put("criticalWithoutAuthoritativeSource", criticalWithoutAuthoritativeSource)
            .put("independentVerificationRequired", required)

        normalized.put("ledgerSha256", RiftPatchManifestV1.sha256Canonical(normalized))
        return normalized
    }

    fun contract(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("version", VERSION)
        .put("maxEntries", MAX_ENTRIES)
        .put("maxAssumptions", MAX_ASSUMPTIONS)
        .put("sourceTypes", JSONArray(SOURCE_TYPES.sorted()))
        .put("criticalRule", "Critical supported claims require at least one official/specification/primary/standards-body source.")
        .put("trustRule", "Collection completeness is not trust. Final evaluator must independently re-check critical external claims.")
        .put("networkAuthority", false)

    private fun bounded(value: String, max: Int, label: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "$label is required" }
        require(trimmed.length <= max) { "$label exceeds $max characters" }
        return trimmed
    }
}
