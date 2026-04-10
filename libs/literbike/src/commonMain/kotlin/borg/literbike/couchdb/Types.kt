package borg.literbike.couchdb

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement

/**
 * CouchDB document ID type
 */
typealias DocId = String

/**
 * CouchDB revision identifier
 */
typealias RevId = String

/**
 * CouchDB attachment digest
 */
typealias AttachmentDigest = String

/**
 * CouchDB document with metadata
 */
@Serializable
data class Document(
    val id: DocId,
    val rev: RevId,
    val deleted: Boolean? = null,
    val attachments: MutableMap<String, AttachmentInfo>? = null,
    val data: JsonObject = JsonObject(emptyMap())
)

/**
 * Attachment information
 */
@Serializable
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
@Serializable
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
@Serializable
data class ViewQuery(
    val conflicts: Boolean? = null,
    val descending: Boolean? = null,
    val endkey: JsonElement? = null,
    val endkeyDocid: String? = null,
    val group: Boolean? = null,
    val groupLevel: UInt? = null,
    val includeDocs: Boolean? = null,
    val inclusiveEnd: Boolean? = null,
    val key: JsonElement? = null,
    val keys: List<JsonElement>? = null,
    val limit: UInt? = null,
    val reduce: Boolean? = null,
    val skip: UInt? = null,
    val stale: String? = null,
    val startkey: JsonElement? = null,
    val startkeyDocid: String? = null,
    val updateSeq: Boolean? = null,
    val cursor: String? = null // For cursor-based pagination
) {
    companion object {
        fun default() = ViewQuery()
    }
}

/**
 * View row result
 */
@Serializable
data class ViewRow(
    val id: String? = null,
    val key: JsonElement,
    val value: JsonElement,
    val doc: Document? = null
)

/**
 * View query result
 */
@Serializable
data class ViewResult(
    val totalRows: ULong,
    val offset: UInt,
    val rows: List<ViewRow>,
    val updateSeq: ULong? = null,
    val nextCursor: String? = null // For cursor-based pagination
)

/**
 * Design document
 */
@Serializable
data class DesignDocument(
    val id: String,
    val rev: String,
    val language: String? = null,
    val views: MutableMap<String, ViewDefinition>? = null,
    val shows: MutableMap<String, String>? = null,
    val lists: MutableMap<String, String>? = null,
    val updates: MutableMap<String, String>? = null,
    val filters: MutableMap<String, String>? = null,
    val validateDocUpdate: String? = null
)

/**
 * View definition in design document
 */
@Serializable
data class ViewDefinition(
    val map: String,
    val reduce: String? = null
)

/**
 * Bulk document operation
 */
@Serializable
data class BulkDocs(
    val docs: List<Document>,
    val newEdits: Boolean? = null,
    val allOrNothing: Boolean? = null
)

/**
 * Bulk operation result
 */
@Serializable
data class BulkResult(
    val ok: Boolean? = null,
    val id: String,
    val rev: String? = null,
    val error: String? = null,
    val reason: String? = null
)

/**
 * Changes feed options
 */
@Serializable
data class ChangesQuery(
    val docIds: List<String>? = null,
    val conflicts: Boolean? = null,
    val descending: Boolean? = null,
    val feed: String? = null, // normal, longpoll, continuous, eventsource
    val filter: String? = null,
    val heartbeat: ULong? = null,
    val includeDocs: Boolean? = null,
    val attachments: Boolean? = null,
    val attEncodingInfo: Boolean? = null,
    val lastEventId: ULong? = null,
    val limit: UInt? = null,
    val since: String? = null,
    val style: String? = null, // all_docs, main_only
    val timeout: ULong? = null,
    val view: String? = null,
    val seqInterval: UInt? = null
)

/**
 * Change record
 */
@Serializable
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
@Serializable
data class ChangeRevision(
    val rev: String
)

/**
 * Changes feed response
 */
@Serializable
data class ChangesResponse(
    val results: List<Change>,
    val lastSeq: String,
    val pending: UInt? = null
)

/**
 * Server information
 */
@Serializable
data class ServerInfo(
    val couchdb: String,
    val uuid: String,
    val version: String,
    val vendor: ServerVendor,
    val features: List<String>,
    val gitSha: String
)

/**
 * Server vendor info
 */
@Serializable
data class ServerVendor(
    val name: String,
    val version: String
)

/**
 * All databases response
 */
@Serializable
data class AllDbsResponse(
    val databases: List<String>
)

/**
 * Replication document
 */
@Serializable
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
@Serializable
sealed class ReplicationEndpoint {
    @Serializable
    data class Database(val name: String) : ReplicationEndpoint()

    @Serializable
    data class Remote(
        val url: String,
        val headers: MutableMap<String, String>? = null,
        val auth: ReplicationAuth? = null
    ) : ReplicationEndpoint()
}

/**
 * Replication authentication
 */
@Serializable
data class ReplicationAuth(
    val username: String,
    val password: String
)

/**
 * User context for replication
 */
@Serializable
data class UserContext(
    val name: String,
    val roles: List<String>
)

/**
 * Cursor for pagination
 */
@Serializable
data class Cursor(
    val key: JsonElement,
    val docId: String? = null,
    val skip: UInt,
    val timestamp: kotlinx.datetime.Instant
) {
    constructor(key: JsonElement, docId: String? = null, skip: UInt) : this(
        key, docId, skip, Clock.System.now()
    )

    fun encode(): String {
        return okio.ByteString.encodeUtf8(Json.encodeToString(this)).base64()
    }

    companion object {
        fun decode(cursor: String): Cursor {
            val decoded = okio.ByteString.decodeBase64(cursor)
                ?: throw IllegalArgumentException("Invalid base64 cursor")
            return Json.decodeFromString(decoded.utf8())
        }
    }
}

/**
 * IPFS content identifier for distributed storage
 */
@Serializable
data class IpfsCid(
    val cid: String,
    val size: ULong,
    val contentType: String
)

/**
 * M2M communication message
 */
@Serializable
data class M2mMessage(
    val id: String, // UUID as String
    val sender: String,
    val recipient: String? = null, // None for broadcast
    val messageType: M2mMessageType,
    val payload: JsonObject,
    val timestamp: kotlinx.datetime.Instant,
    val ttl: ULong? = null // Time to live in seconds
)

/**
 * M2M message types
 */
@Serializable
enum class M2mMessageType {
    Replication,
    ViewUpdate,
    DocumentChange,
    DatabaseCreate,
    DatabaseDelete,
    AttachmentSync,
    HeartBeat,
    // UNKS: Custom would need sealed class with data variant
}

/**
 * Tensor operation definition
 */
@Serializable
data class TensorOperation(
    val operation: TensorOpType,
    val inputDocs: List<String>, // Document IDs
    val outputDoc: String? = null,
    val parameters: MutableMap<String, JsonElement> = mutableMapOf()
)

/**
 * Tensor operation types
 */
@Serializable
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
    // UNSK: Custom would need sealed class
}

/**
 * Key-value store entry for IPFS-backed attachments
 */
@Serializable
data class KvEntry(
    val key: String,
    val value: ByteArray,
    val contentType: String,
    val ipfsCid: String? = null,
    val createdAt: kotlinx.datetime.Instant,
    val updatedAt: kotlinx.datetime.Instant,
    val size: ULong,
    val metadata: MutableMap<String, String> = mutableMapOf()
)
