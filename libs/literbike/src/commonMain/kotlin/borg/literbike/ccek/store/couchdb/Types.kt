package borg.literbike.ccek.store.couchdb

import kotlinx.datetime.Instant

/**
 * CouchDB type definitions
 */

typealias DocId = String
typealias RevId = String
typealias AttachmentDigest = String

/**
 * CouchDB document with metadata
 */
data class Document(
    val id: DocId,
    val rev: RevId,
    val deleted: Boolean? = null,
    val attachments: Map<String, AttachmentInfo>? = null,
    val data: String = "{}" // JSON string
)

/**
 * Attachment information
 */
data class AttachmentInfo(
    val contentType: String,
    val length: ULong,
    val digest: AttachmentDigest,
    val stub: Boolean? = null,
    val revpos: UInt? = null,
    val data: String? = null // base64 encoded data for inline attachments
)

/**
 * Database information response
 */
data class DatabaseInfo(
    val dbName: String,
    val docCount: ULong,
    val docDelCount: ULong,
    val updateSeq: ULong,
    val purgeSeq: ULong,
    val compactRunning: Boolean,
    val diskSize: ULong,
    val dataSize: ULong,
    val instanceStartTime: String,
    val diskFormatVersion: UInt,
    val committedUpdateSeq: ULong
)

/**
 * View query parameters
 */
data class ViewQuery(
    val conflicts: Boolean? = null,
    val descending: Boolean? = null,
    val endkey: String? = null,
    val endkeyDocid: String? = null,
    val group: Boolean? = null,
    val groupLevel: UInt? = null,
    val includeDocs: Boolean? = null,
    val inclusiveEnd: Boolean? = null,
    val key: String? = null,
    val keys: List<String>? = null,
    val limit: UInt? = null,
    val reduce: Boolean? = null,
    val skip: UInt? = null,
    val stale: String? = null,
    val startkey: String? = null,
    val startkeyDocid: String? = null,
    val updateSeq: Boolean? = null,
    val cursor: String? = null // For cursor-based pagination
)

/**
 * View row result
 */
data class ViewRow(
    val id: String? = null,
    val key: String,
    val value: String,
    val doc: Document? = null
)

/**
 * View query result
 */
data class ViewResult(
    val totalRows: ULong,
    val offset: UInt,
    val rows: List<ViewRow>,
    val updateSeq: ULong? = null,
    val nextCursor: String? = null
)

/**
 * Design document
 */
data class DesignDocument(
    val id: String,
    val rev: String,
    val language: String? = null,
    val views: Map<String, ViewDefinition>? = null,
    val shows: Map<String, String>? = null,
    val lists: Map<String, String>? = null,
    val updates: Map<String, String>? = null,
    val filters: Map<String, String>? = null,
    val validateDocUpdate: String? = null
)

/**
 * View definition in design document
 */
data class ViewDefinition(
    val map: String,
    val reduce: String? = null
)

/**
 * Bulk document operation
 */
data class BulkDocs(
    val docs: List<Document>,
    val newEdits: Boolean? = null,
    val allOrNothing: Boolean? = null
)

/**
 * Bulk operation result
 */
data class BulkResult(
    val ok: Boolean? = null,
    val id: String,
    val rev: String? = null,
    val error: String? = null,
    val reason: String? = null
)

/**
 * Change record
 */
data class Change(
    val seq: String,
    val id: String,
    val changes: List<ChangeRevision>,
    val doc: Document? = null,
    val deleted: Boolean? = null
)

/**
 * Change revision info
 */
data class ChangeRevision(
    val rev: String
)

/**
 * Changes feed response
 */
data class ChangesResponse(
    val results: List<Change>,
    val lastSeq: String,
    val pending: UInt? = null
)

/**
 * Server information
 */
data class ServerInfo(
    val couchdb: String,
    val uuid: String,
    val version: String,
    val vendor: ServerVendor,
    val features: List<String> = emptyList(),
    val gitSha: String
)

/**
 * Server vendor info
 */
data class ServerVendor(
    val name: String,
    val version: String
)

/**
 * Replication document
 */
data class ReplicationDoc(
    val id: String? = null,
    val rev: String? = null,
    val source: ReplicationEndpoint,
    val target: ReplicationEndpoint,
    val continuous: Boolean? = null,
    val createTarget: Boolean? = null,
    val docIds: List<String>? = null,
    val filter: String? = null,
    val proxy: String? = null,
    val sinceSeq: ULong? = null,
    val userCtx: UserContext? = null
)

/**
 * Replication endpoint
 */
sealed class ReplicationEndpoint {
    data class Database(val name: String) : ReplicationEndpoint()
    data class Remote(
        val url: String,
        val headers: Map<String, String>? = null,
        val auth: ReplicationAuth? = null
    ) : ReplicationEndpoint()
}

/**
 * Replication authentication
 */
data class ReplicationAuth(
    val username: String,
    val password: String
)

/**
 * User context for replication
 */
data class UserContext(
    val name: String,
    val roles: List<String> = emptyList()
)

/**
 * Cursor for pagination
 */
data class Cursor(
    val key: String,
    val docId: String? = null,
    val skip: UInt = 0u,
    val timestamp: Instant = Instant.fromEpochMilliseconds(0)
) {
    companion object {
        fun new(key: String, docId: String? = null, skip: UInt = 0u): Cursor =
            Cursor(key, docId, skip, Instant.fromEpochMilliseconds(Clocks.System.now()))
    }

    fun encode(): String {
        val json = "{\"key\":$key,\"docId\":${docId?.let { "\"$it\"" } ?: "null"},\"skip\":$skip,\"timestamp\":\"$timestamp\"}"
        return json.encodeToByteArray().toBase64()
    }

    companion object {
        fun decode(cursor: String): Cursor? {
            // Simplified - in real impl would use proper JSON parsing
            return null
        }
    }
}

private fun ByteArray.toBase64(): String {
    // Platform-specific base64 encoding
    return this.joinToString("") { b -> "%02x".format(b) } // Hex for simplicity
}

/**
 * IPFS content identifier for distributed storage
 */
data class IpfsCid(
    val cid: String,
    val size: ULong,
    val contentType: String
)

/**
 * M2M message types
 */
enum class M2mMessageType {
    Replication,
    ViewUpdate,
    DocumentChange,
    DatabaseCreate,
    DatabaseDelete,
    AttachmentSync,
    HeartBeat,
    Custom;
}

/**
 * M2M communication message
 */
data class M2mMessage(
    val id: String, // UUID string
    val sender: String,
    val recipient: String? = null, // null for broadcast
    val messageType: M2mMessageType,
    val payload: String, // JSON string
    val timestamp: Instant,
    val ttl: ULong? = null // Time to live in seconds
)

/**
 * Tensor operation types
 */
enum class TensorOpType {
    MatrixMultiply,
    VectorAdd,
    VectorSubtract,
    DotProduct,
    CrossProduct,
    Transpose,
    Inverse,
    Eigenvalues,
    Svd, // Singular Value Decomposition
    Qr,  // QR Decomposition
    Custom;
}

/**
 * Tensor operation definition
 */
data class TensorOperation(
    val operation: TensorOpType,
    val inputDocs: List<String>, // Document IDs
    val outputDoc: String? = null,
    val parameters: Map<String, String> = emptyMap() // JSON values as strings
)

/**
 * Key-value store entry for IPFS-backed attachments
 */
data class KvEntry(
    val key: String,
    val value: ByteArray = ByteArray(0),
    val contentType: String,
    val ipfsCid: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val size: ULong = value.size.toULong(),
    val metadata: Map<String, String> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KvEntry) return false
        return key == other.key && value.contentEquals(other.value) &&
                contentType == other.contentType && ipfsCid == other.ipfsCid &&
                createdAt == other.createdAt && updatedAt == other.updatedAt &&
                size == other.size && metadata == other.metadata
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + value.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + (ipfsCid?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }
}
