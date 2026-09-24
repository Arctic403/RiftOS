package com.riftos.app

import org.json.JSONObject

data class RiftMemoryStoreConfigV1(
    val databasePath: String,
    val createIfMissing: Boolean = true
)

data class RiftMemoryBoundsV1(
    val offset: Int = 0,
    val limit: Int = 100,
    val maxBlobBytes: Int = 1_048_576
) {
    init {
        require(offset in 0..1_000_000)
        require(limit in 1..1_000)
        require(maxBlobBytes in 1..8_388_608)
    }
}

data class RiftMemoryQueryV1(
    val id: String? = null,
    val recordId: String? = null,
    val evidenceId: String? = null,
    val namespace: String? = null,
    val projectId: String? = null,
    val kind: RiftMemoryRecordKindV1? = null,
    val branch: RiftMemoryBranchV1? = null
)

data class RiftMemoryTransactionV1(
    val id: String,
    val metadata: JSONObject
)

data class RiftMemoryCommitResultV1(
    val transactionId: String,
    val committedAt: Long,
    val eventSequence: Long
)

data class RiftMemoryRollbackResultV1(
    val transactionId: String,
    val rolledBack: Boolean
)

data class RiftMemoryRecordPageV1(
    val records: List<RiftCanonicalMemoryRecordV1>,
    val offset: Int,
    val limit: Int
)

data class RiftMemoryEvidencePageV1(
    val evidence: List<RiftMemoryEvidenceV1>,
    val offset: Int,
    val limit: Int
)

data class RiftMemoryEventRowV1(
    val sequence: Long,
    val event: JSONObject,
    val eventHash: String
)

data class RiftMemoryEventPageV1(
    val events: List<RiftMemoryEventRowV1>,
    val offset: Int,
    val limit: Int
)

data class RiftMemoryContentBlobV1(
    val hash: String,
    val mediaType: String,
    val bytes: ByteArray
)

data class RiftMemorySnapshotMetadataV1(
    val id: String,
    val createdAt: Long,
    val eventSequence: Long,
    val canonicalHash: String,
    val metadata: JSONObject
)

data class RiftMemoryIntegrityReportV1(
    val clean: Boolean,
    val sqliteIntegrity: String,
    val blobsChecked: Int,
    val evidenceChecked: Int,
    val recordsChecked: Int,
    val eventsChecked: Int,
    val findings: List<String>
)

data class RiftMemoryDirtyProjectionV1(
    val name: String,
    val reason: String,
    val updatedAt: Long
)

interface RiftMemoryStoreV1 {
    fun open(config: RiftMemoryStoreConfigV1): RiftMemoryStoreHandleV1
}

interface RiftMemoryStoreHandleV1 : AutoCloseable {
    fun beginTransaction(metadata: JSONObject = JSONObject()): RiftMemoryTransactionV1
    fun appendEvidence(transaction: RiftMemoryTransactionV1, evidence: RiftMemoryEvidenceV1): String
    fun appendEvent(transaction: RiftMemoryTransactionV1, event: RiftMemoryEventV1): String
    fun putCanonicalRecord(transaction: RiftMemoryTransactionV1, record: RiftCanonicalMemoryRecordV1): String
    fun putContentBlob(
        transaction: RiftMemoryTransactionV1,
        bytes: ByteArray,
        metadata: JSONObject = JSONObject()
    ): String
    fun commitTransaction(transaction: RiftMemoryTransactionV1): RiftMemoryCommitResultV1
    fun rollbackTransaction(transaction: RiftMemoryTransactionV1): RiftMemoryRollbackResultV1
    fun getCanonicalRecord(id: String): RiftCanonicalMemoryRecordV1?
    fun scanCanonicalRecords(query: RiftMemoryQueryV1, bounds: RiftMemoryBoundsV1): RiftMemoryRecordPageV1
    fun readEvents(query: RiftMemoryQueryV1, bounds: RiftMemoryBoundsV1): RiftMemoryEventPageV1
    fun readEvidence(query: RiftMemoryQueryV1, bounds: RiftMemoryBoundsV1): RiftMemoryEvidencePageV1
    fun getContentBlob(hash: String, bounds: RiftMemoryBoundsV1): RiftMemoryContentBlobV1?
    fun createSnapshot(scope: RiftMemoryQueryV1 = RiftMemoryQueryV1()): RiftMemorySnapshotMetadataV1
    fun verifyIntegrity(scope: RiftMemoryQueryV1 = RiftMemoryQueryV1(), bounds: RiftMemoryBoundsV1 = RiftMemoryBoundsV1(limit = 1_000)): RiftMemoryIntegrityReportV1
    fun markProjectionDirty(projection: String, reason: String)
    fun listDirtyProjections(scope: RiftMemoryQueryV1 = RiftMemoryQueryV1(), bounds: RiftMemoryBoundsV1 = RiftMemoryBoundsV1()): List<RiftMemoryDirtyProjectionV1>
    override fun close()
}
