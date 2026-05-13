package borg.trikeshed.bugzee

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.hazelnut.*
import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.DocRowVec

// ═══════════════════════════════════════════════════════════════════════════════
// BugzeeCouchGrounding.kt — CouchDB grounding layer for Bugzee
//
// Maps Bugzee platform entities to CouchDB query-server semantics:
//   • Bugs, threads, comments, votes, feeds → Couch documents
//   • Map/reduce views for HN-style feeds (hot, new, top, by_severity)
//   • All 9 hazelnut distributed object types → DocRowVec schema
// ═══════════════════════════════════════════════════════════════════════════════

// ── CouchDocument: base CouchDB document model ────────────────────────────────

data class CouchDocument(
    val id: CharSequence,
    val rev: CharSequence? = null,
    val fields: Map<CharSequence, Any?> = emptyMap(),
    val attachments: List<CouchAttachment> = emptyList(),
) {
    val docId: CharSequence get() = id
    val docType: CharSequence? 
        get() = when {
            fields.containsKey("_type") -> fields["_type"] as? CharSequence
            else -> null
        }
    
    fun get(field: CharSequence): Any? = fields[field]
    
    fun getString(field: CharSequence): CharSequence? = fields[field] as? CharSequence
    
    fun getInt(field: CharSequence): Int {
        val v = fields[field]
        return when (v) {
            is Int -> v
            is Number -> v.toInt()
            is CharSequence -> v.toString().toIntOrNull() ?: 0
            else -> 0
        }
    }
}

data class CouchAttachment(
    val name: CharSequence,
    val contentType: CharSequence = "application/octet-stream",
    val length: Long = 0L,
    val data: ByteArray = byteArrayOf(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CouchAttachment) return false
        return name == other.name && contentType == other.contentType &&
            length == other.length && data.contentEquals(other.data)
    }
    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + length.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

// ── BugzeeDoc: BugzeeEnvelope ↔ CouchDocument schema mapping ─────────────────

object BugzeeDoc {
    // Couch field keys for Bugzee documents
    private const val TYPE_KEY = "_type"
    private const val PRODUCT_KEY = "product"
    private const val BUG_ID_KEY = "bugId"
    private const val COMMENT_ID_KEY = "commentId"
    private const val SUMMARY_KEY = "summary"
    private const val DESCRIPTION_KEY = "description"
    private const val ASSIGNEE_KEY = "assignee"
    private const val SEVERITY_KEY = "severity"
    private const val METADATA_KEY = "metadata"
    private const val ATTACHMENT_COUNT_KEY = "attachmentCount"
    private const val BUGZEE_TYPE = "bugzee"
    
    // Document type variants
    enum class DocKind { BUG, COMMENT, VOTE, THREAD, FEED, LABEL, ATTACHMENT, WORKFLOW, ACTIVITY }

    fun toDocument(envelope: BugzeeEnvelope, kind: DocKind = DocKind.BUG): CouchDocument {
        val couchId = buildCouchId(kind, envelope)
        val fields = mutableMapOf<CharSequence, Any?>()
        fields[TYPE_KEY] = BUGZEE_TYPE
        fields["docKind"] = kind.name
        fields[PRODUCT_KEY] = envelope.product
        fields[BUG_ID_KEY] = envelope.bugId
        envelope.commentId?.let { fields[COMMENT_ID_KEY] = it }
        fields[SUMMARY_KEY] = envelope.summary
        fields[DESCRIPTION_KEY] = envelope.description
        envelope.assignee?.let { fields[ASSIGNEE_KEY] = it }
        fields[SEVERITY_KEY] = envelope.severity
        if (envelope.metadata.isNotEmpty()) {
            fields[METADATA_KEY] = envelope.metadata
        }
        fields[ATTACHMENT_COUNT_KEY] = envelope.attachments.size
        
        val couchAttachments = envelope.attachments.mapIndexedNotNull { idx, row ->
            val name = "attachment_$idx"
            CouchAttachment(
                name = name,
                contentType = row.getValue("mimeType")?.toString()
                    ?: "application/octet-stream",
                length = (row.getValue("size") as? Number)?.toLong() ?: 0L,
            )
        }

        return CouchDocument(
            id = couchId,
            fields = fields,
            attachments = couchAttachments,
        )
    }
    
    private fun buildCouchId(kind: DocKind, envelope: BugzeeEnvelope): CharSequence {
        val prefix = kind.name.lowercase()
        return "${prefix}_${envelope.product}_${envelope.bugId}${envelope.commentId?.let { "_$it" } ?: ""}"
    }

    fun fromDocument(doc: CouchDocument): BugzeeEnvelope {
        val product = doc.getString(PRODUCT_KEY) ?: error("CouchDocument missing 'product' field")
        val bugId = doc.getString(BUG_ID_KEY) ?: error("CouchDocument missing 'bugId' field")
        val commentId = doc.getString(COMMENT_ID_KEY)
        val summary = doc.getString(SUMMARY_KEY) ?: ""
        val description = doc.getString(DESCRIPTION_KEY) ?: ""
        val assignee = doc.getString(ASSIGNEE_KEY)
        val severity = doc.getInt(SEVERITY_KEY)
        val metadataRaw = doc.fields[METADATA_KEY]
        val metadata = if (metadataRaw is Map<*, *>) {
            metadataRaw.entries.associate { 
                it.key.toString() to (it.value?.toString() ?: "") 
            }
        } else {
            emptyMap<CharSequence, CharSequence>()
        }
        
        // Reconstruct attachments from document attachments list
        val attachments = doc.attachments.map { att ->
            DocRowVec(
                keys = listOf("name", "mimeType", "size" as CharSequence).map { it as CharSequence },
                cells = listOf(att.name, att.contentType, att.length),
            )
        }
        
        return BugzeeEnvelope(
            product = product,
            bugId = bugId,
            commentId = commentId,
            summary = summary,
            description = description,
            assignee = assignee,
            severity = severity,
            metadata = metadata,
            attachments = attachments.toSeries(),
        )
    }
}

// ── CouchView: map/reduce function interfaces for HN-style feeds ──────────────

/**
 * Map function interface — projects CouchDocument to (key, value) pairs.
 * Matches CouchDB map function semantics: `function(doc) { emit(key, value); }`
 */
fun interface CouchMapFn {
    fun map(doc: CouchDocument): Series<Pair<CharSequence, Any?>>
}

/**
 * Reduce function interface — aggregates map output values.
 * Matches CouchDB reduce semantics: `function(keys, values, rereduce) -> result`
 */
fun interface CouchReduceFn {
    fun reduce(
        keys: Series<CharSequence>,
        values: Series<Any?>,
        rereduce: Boolean,
    ): Any?
}

/**
 * View definition — pairs map with optional reduce, used for HN-style feeds.
 */
data class CouchView(
    val name: CharSequence,
    val mapFn: CouchMapFn,
    val reduceFn: CouchReduceFn? = null,
    val description: CharSequence = "",
) {
    companion object {
        /** Hot feed — high severity + recent activity, sorted by composite score */
        fun hot(): CouchView = CouchView(
            name = "hot",
            mapFn = CouchMapFn { doc ->
                val severity = doc.getInt("severity")
                val hasAssignee = doc.getString("assignee") != null
                val score = severity + if (hasAssignee) 10 else 0
                val key = "${score}_${doc.id}"
                1 j { key to doc.fields }
            },
            reduceFn = CouchReduceFn { _, values, _ -> values.size },
            description = "High-activity bugs sorted by severity + assignment score",
        )
        
        /** New feed — sorted by document ID (creation order proxy) */
        fun new(): CouchView = CouchView(
            name = "new",
            mapFn = CouchMapFn { doc ->
                1 j { doc.id to doc.fields }
            },
            reduceFn = CouchReduceFn { _, values, _ ->
                values.filterNotNull().size
            },
            description = "Most recently created bugs",
        )
        
        /** Top feed — highest severity first */
        fun top(): CouchView = CouchView(
            name = "top",
            mapFn = CouchMapFn { doc ->
                val severity = doc.getInt("severity")
                val key = severity.toString()
                1 j { key to doc.fields }
            },
            reduceFn = CouchReduceFn { _, values, _ ->
                // Count bugs per severity level
                values.groupingBy { it }.eachCount()
            },
            description = "Bugs ranked by severity level",
        )
        
        /** By-severity feed — group bugs by their severity value */
        fun bySeverity(): CouchView = CouchView(
            name = "by_severity",
            mapFn = CouchMapFn { doc ->
                val severity = doc.getInt("severity")
                val key = when {
                    severity >= 8 -> "critical"
                    severity >= 5 -> "high"
                    severity >= 3 -> "medium"
                    severity >= 1 -> "low"
                    else -> "trivial"
                }
                1 j { key to doc.fields }
            },
            reduceFn = CouchReduceFn { keys, values, rereduce ->
                val grouped = mutableMapOf<CharSequence, MutableList<Any?>>()
                for (i in 0 until keys.size) {
                    grouped.getOrPut(keys[i]) { mutableListOf() }.add(values[i])
                }
                grouped
            },
            description = "Bugs grouped by severity category",
        )
    }
}

// ── CouchQuery: design doc references, view params, reduce options ───────────

data class CouchQuery(
    val designDoc: CharSequence = "_design/bugzee",
    val viewName: CharSequence,
    val reduce: Boolean = false,
    val startKey: CharSequence? = null,
    val endKey: CharSequence? = null,
    val key: CharSequence? = null,
    val keys: Series<CharSequence> = emptySeries(),
    val limit: Int = 25,
    val skip: Int = 0,
    val descending: Boolean = false,
    val includeDocs: Boolean = true,
    val staleOk: Boolean = false,
    val group: Boolean = false,
    val groupLevel: Int = 0,
)

data class CouchReplication(
    val source: CharSequence,
    val target: CharSequence,
    val continuous: Boolean = false,
    val filter: CharSequence? = null,
    val queryParams: Map<CharSequence, Any?>? = null,
)

data class CouchQueryResult(
    val rows: Series<CouchViewRow>,
    val totalRows: Int = 0,
    val offset: Int = 0,
)

data class CouchViewRow(
    val id: CharSequence? = null,
    val key: Any? = null,
    val value: Any? = null,
    val doc: CouchDocument? = null,
)

// ── CouchClient interface: CouchDB operations ───────────────────────────────

interface CouchClient {
    /**
     * Upsert a document. Returns the revision string of the saved document.
     * If rev is present in the document, this is an update; otherwise a create.
     */
    fun putDoc(doc: CouchDocument): CouchWriteResult
    
    /**
     * Retrieve a document by ID.
     */
    fun getDoc(id: CharSequence, rev: CharSequence? = null): CouchDocument?
    
    /**
     * Delete a document by ID and rev.
     */
    fun deleteDoc(id: CharSequence, rev: CharSequence): Boolean
    
    /**
     * Query a map/reduce view with parameters.
     */
    fun queryView(query: CouchQuery): CouchQueryResult
    
    /**
     * Bulk upsert/delete documents in a single batch.
     * Returns a list of write results matching the input order.
     */
    fun bulkDocs(docs: Series<CouchDocument>): Series<CouchWriteResult>
    
    /**
     * Trigger replication from source to target database.
     */
    fun replicate(replication: CouchReplication): CouchReplicationResult
}

data class CouchWriteResult(
    val id: CharSequence,
    val rev: CharSequence,
    val ok: Boolean = true,
    val error: CharSequence? = null,
)

data class CouchReplicationResult(
    val sessionId: CharSequence,
    val docsWritten: Long,
    val docsRead: Long,
    val ok: Boolean = true,
)

// ── View row schema for DocRowVec projection ─────────────────────────────────

private val couchRowKeys = listOf("viewId", "viewKey", "viewValue", "docId", "docRev", "docType")

/**
 * Project a CouchViewRow into a DocRowVec for query result tabulation.
 */
fun CouchViewRow.toRowVec(): DocRowVec {
    val docTypeId = doc?.id
    val docRev = doc?.rev
    val docType = doc?.docType
    return DocRowVec(
        keys = couchRowKeys,
        cells = listOf(
            id,
            key,
            value,
            docTypeId,
            docRev,
            docType,
        ),
    )
}

// ── BugzeeCouchService: Couch-backed Bugzee operations ───────────────────────

/**
 * Service layer that bridges Bugzee entities to CouchDB query semantics.
 *
 * Provides indexing, full-text search, replication, and design document queries.
 * Every Bugzee entity becomes queryable via CouchDB view map/reduce semantics.
 */
class BugzeeCouchService(
    private val client: CouchClient,
    private val designDoc: CharSequence = "_design/bugzee",
) {
    // ── Core document operations ─────────────────────────────────────────

    fun save(envelope: BugzeeEnvelope, kind: BugzeeDoc.DocKind = BugzeeDoc.DocKind.BUG): CouchWriteResult {
        val doc = BugzeeDoc.toDocument(envelope, kind)
        return client.putDoc(doc)
    }

    fun load(id: CharSequence, rev: CharSequence? = null): BugzeeEnvelope? {
        val doc = client.getDoc(id, rev) ?: return null
        return try {
            BugzeeDoc.fromDocument(doc)
        } catch (e: Exception) {
            null
        }
    }

    // ── HN-style feed queries ────────────────────────────────────────────

    fun hotFeed(limit: Int = 25, skip: Int = 0): CouchQueryResult {
        return client.queryView(
            CouchQuery(
                designDoc = designDoc,
                viewName = "hot",
                reduce = false,
                limit = limit,
                skip = skip,
                descending = true,
                includeDocs = true,
            ),
        )
    }

    fun newFeed(limit: Int = 25, skip: Int = 0): CouchQueryResult {
        return client.queryView(
            CouchQuery(
                designDoc = designDoc,
                viewName = "new",
                reduce = false,
                limit = limit,
                skip = skip,
                descending = true,
                includeDocs = true,
            ),
        )
    }

    fun topFeed(limit: Int = 25, skip: Int = 0): CouchQueryResult {
        return client.queryView(
            CouchQuery(
                designDoc = designDoc,
                viewName = "top",
                reduce = false,
                limit = limit,
                skip = skip,
                descending = true,
                includeDocs = true,
            ),
        )
    }

    fun bySeverityFeed(severity: CharSequence, limit: Int = 25): CouchQueryResult {
        return client.queryView(
            CouchQuery(
                designDoc = designDoc,
                viewName = "by_severity",
                reduce = false,
                key = severity,
                limit = limit,
                includeDocs = true,
            ),
        )
    }

    // ── Custom view query ────────────────────────────────────────────────

    /**
     * Query a design document view with custom parameters.
     */
    fun queryDesign(
        viewName: CharSequence,
        reduce: Boolean = false,
        startKey: CharSequence? = null,
        endKey: CharSequence? = null,
        limit: Int = 25,
        descending: Boolean = false,
        staleOk: Boolean = false,
    ): CouchQueryResult {
        return client.queryView(
            CouchQuery(
                designDoc = designDoc,
                viewName = viewName,
                reduce = reduce,
                startKey = startKey,
                endKey = endKey,
                limit = limit,
                descending = descending,
                staleOk = staleOk,
                includeDocs = true,
            ),
        )
    }

    // ── Secondary index ──────────────────────────────────────────────────

    /**
     * Index Bugzee documents by a secondary key field.
     * Returns all documents matching the indexed key value.
     */
    fun indexBy(
        field: CharSequence,
        value: CharSequence,
        limit: Int = 100,
    ): Series<BugzeeEnvelope> {
        val result = client.queryView(
            CouchQuery(
                designDoc = designDoc,
                viewName = "by_${field}",
                reduce = false,
                key = value,
                limit = limit,
                staleOk = true,
                includeDocs = true,
            ),
        )
        return result.rows.mapNotNull { row ->
            row.doc?.let { BugzeeDoc.fromDocument(it) }
        }
    }

    // ── Full-text search ─────────────────────────────────────────────────

    /**
     * Search across Bugzee documents matching the given text in indexed fields.
     * Uses CouchDB's _text_search or a Lucene-backed FTS view.
     */
    fun fullTextSearch(
        query: CharSequence,
        fields: Series<CharSequence> = listOf("summary", "description"),
        limit: Int = 25,
    ): Series<BugzeeEnvelope> {
        val searchQuery = fields.mapIndexed { i, field ->
            val prefix = if (i > 0) " AND " else ""
            "${prefix}${field}:${query}"
        }.joinToString("")
        
        val result = client.queryView(
            CouchQuery(
                designDoc = designDoc,
                viewName = "_search/fts",
                reduce = false,
                key = searchQuery,
                limit = limit,
                includeDocs = true,
            ),
        )
        return result.rows.mapNotNull { row ->
            row.doc?.let { BugzeeDoc.fromDocument(it) }
        }
    }

    // ── Replication ──────────────────────────────────────────────────────

    /**
     * Replicate Bugzee documents to a remote CouchDB endpoint.
     */
    fun replicateTo(
        remoteUrl: CharSequence,
        continuous: Boolean = false,
        filter: CharSequence? = null,
    ): CouchReplicationResult {
        return client.replicate(
            CouchReplication(
                source = "bugzee_local",
                target = remoteUrl,
                continuous = continuous,
                filter = filter,
            ),
        )
    }

    // ── Bulk operations ──────────────────────────────────────────────────

    fun bulkSave(envelopes: Series<BugzeeEnvelope>): Series<CouchWriteResult> {
        val docs = envelopes.map { envelope ->
            BugzeeDoc.toDocument(envelope)
        }
        return client.bulkDocs(docs)
    }

    // ── Projection to DocRowVec ──────────────────────────────────────────

    fun projectResult(result: CouchQueryResult): Series<DocRowVec> {
        return result.rows.map { it.toRowVec() }
    }

    /**
     * Project a single envelope to its Couch view row representation.
     */
    fun project(envelope: BugzeeEnvelope): DocRowVec = envelope.toRowVec()
}

// ═════════════════════════════════════════════════════════════════════════════
// Distributed Object Couch Mapping — 9 hazelnut types → Bugzee DocRowVec schema
//
// Matches the pattern in HazelnutService.kt::toCouchRowVec()
// Base keys: objectId, objectType, revision, ttl, originNode
// + type-specific keys for each distributed object type
// ═════════════════════════════════════════════════════════════════════════════

private val distributedObjBaseKeys = listOf("objectId", "objectType", "revision", "ttl", "originNode")

/**
 * Map a DistributedObject to a DocRowVec using Bugzee Couch schema.
 * Mirrors HazelnutService.toCouchRowVec() pattern with Bugzee-specific keys.
 *
 * All 9 distributed object types are grounded:
 *   1. DString          → value, valueLength
 *   2. DList            → elementCount
 *   3. DHash            → fieldCount
 *   4. DSet             → memberCount
 *   5. DSortedSet       → entryCount
 *   6. DBitmap          → byteSize, populateCount
 *   7. DGeo             → pointCount
 *   8. DStream          → entryCount, maxLen
 *   9. DHyperLogLog     → cardinality, byteSize
 */
fun DistributedObject.toCouchRowVec(): DocRowVec {
    val (keys, cells) = when (this) {
        is DString -> {
            listOf("value", "valueLength") to listOf(value, value.length)
        }
        is DList -> {
            listOf("elementCount") to listOf(elements.size)
        }
        is DHash -> {
            listOf("fieldCount") to listOf(fields.size)
        }
        is DSet -> {
            listOf("memberCount") to listOf(members.size)
        }
        is DSortedSet -> {
            listOf("entryCount") to listOf(entries.size)
        }
        is DBitmap -> {
            listOf("byteSize", "populateCount") to listOf(bytes.size, populateCount())
        }
        is DGeo -> {
            listOf("pointCount") to listOf(points.size)
        }
        is DStream -> {
            listOf("entryCount", "maxLen") to listOf(entries.size, maxLen?.toInt() ?: -1)
        }
        is DHyperLogLog -> {
            listOf("cardinality", "byteSize") to listOf(cardinality, bytes.size)
        }
        else -> error("Unknown distributed object type: ${this::class}")
    }
    
    return DocRowVec(
        keys = distributedObjBaseKeys + keys,
        cells = listOf(
            id,
            type.name,
            revision,
            ttl?.let { it.toString() } ?: "-1",
            originNode,
        ) + cells,
    )
}

// ── Bugzee-specific distributed object doc type mapping ──────────────────────

/**
 * Bugzee entity types that can be backed by distributed objects.
 * Each maps to one of the 9 distributed object types.
 */
enum class BugzeeEntityType {
    BUG_SUMMARY,       // DString  — bug description text
    BUG_LABELS,         // DSet     — tag/label membership
    BUG_COMMENTS,       // DList    — ordered comment thread
    BUG_VOTES,          // DSortedSet — scored/upvote rankings
    BUG_WORKFLOW,       // DHash    — state machine fields (status, assignee, etc)
    BUG_ACTIVITY_LOG,   // DStream  — timestamped activity entries
    BUG_FEATURE_FLAGS,  // DBitmap  — feature enable bits per bug
    BUG_DEDUP_IDS,      // DHyperLogLog — cardinality tracking for dedup
    BUG_GEO,            // DGeo     — location-based bug reports
}

/**
 * Map a Bugzee entity type to its corresponding distributed object type.
 */
fun BugzeeEntityType.toDistributedObjectType(): DistributedObjectType = when (this) {
    BugzeeEntityType.BUG_SUMMARY -> DistributedObjectType.STRING
    BugzeeEntityType.BUG_LABELS -> DistributedObjectType.SET
    BugzeeEntityType.BUG_COMMENTS -> DistributedObjectType.LIST
    BugzeeEntityType.BUG_VOTES -> DistributedObjectType.SORTED_SET
    BugzeeEntityType.BUG_WORKFLOW -> DistributedObjectType.HASH
    BugzeeEntityType.BUG_ACTIVITY_LOG -> DistributedObjectType.STREAM
    BugzeeEntityType.BUG_FEATURE_FLAGS -> DistributedObjectType.BITMAP
    BugzeeEntityType.BUG_DEDUP_IDS -> DistributedObjectType.HYPERLOGLOG
    BugzeeEntityType.BUG_GEO -> DistributedObjectType.GEO
}

/**
 * Build a DistributedObject for a Bugzee entity, grounding it in the
 * hazelnut distributed object taxonomy.
 */
fun BugzeeEnvelope.toDistributedObject(
    entityType: BugzeeEntityType,
    originNode: CharSequence = "bugzee-local",
    ttl: Long? = null,
): DistributedObject {
    val baseId = "${entityType}_${product}_${bugId}"
    return when (entityType) {
        BugzeeEntityType.BUG_SUMMARY -> DString(
            id = baseId,
            value = description,
            ttl = ttl,
            originNode = originNode,
        )
        BugzeeEntityType.BUG_LABELS -> DSet(
            id = baseId,
            members = metadata.keys.toSet(),
            ttl = ttl,
            originNode = originNode,
        )
        BugzeeEntityType.BUG_COMMENTS -> DList(
            id = baseId,
            elements = listOfNotNull(commentId).map { it.toString() },
            ttl = ttl,
            originNode = originNode,
        )
        BugzeeEntityType.BUG_VOTES -> DSortedSet(
            id = baseId,
            entries = emptyList(),
            ttl = ttl,
            originNode = originNode,
        )
        BugzeeEntityType.BUG_WORKFLOW -> DHash(
            id = baseId,
            fields = metadata +
                ("severity" to severity.toString()) +
                (assignee?.let { "assignee" to it.toString() } ?: emptyList()),
            ttl = ttl,
            originNode = originNode,
        )
        BugzeeEntityType.BUG_ACTIVITY_LOG -> DStream(
            id = baseId,
            entries = emptyList(),
            ttl = ttl,
            originNode = originNode,
        )
        BugzeeEntityType.BUG_FEATURE_FLAGS -> DBitmap(
            id = baseId,
            ttl = ttl,
            originNode = originNode,
        )
        BugzeeEntityType.BUG_DEDUP_IDS -> DHyperLogLog(
            id = baseId,
            ttl = ttl,
            originNode = originNode,
        )
        BugzeeEntityType.BUG_GEO -> DGeo(
            id = baseId,
            ttl = ttl,
            originNode = originNode,
        )
    }
}

/**
 * Ground a BugzeeEnvelope into a DocRowVec using the distributed object schema.
 * Combines the Bugzee envelope fields with the Couch base keys pattern.
 */
fun BugzeeEnvelope.toCouchRowVec(): DocRowVec {
    return DocRowVec(
        keys = listOf("product", "bugId", "commentId", "summary", "description", "assignee", "severity", "attachmentCount"),
        cells = listOf(
            product,
            bugId,
            commentId,
            summary,
            description,
            assignee,
            severity,
            attachments.size,
        ),
        child = attachments,
    )
}

// ── Couch design document template generator ────────────────────────────────

/**
 * Generate a CouchDB design document with all Bugzee views.
 * Returns the design document as a structured map that can be upserted.
 */
fun generateBugzeeDesignDoc(dbName: CharSequence = "bugzee"): Map<CharSequence, Any?> {
    val views = mapOf(
        "hot" to mapOf(
            "map" to """function(doc) {
                if (doc._type !== 'bugzee') return;
                var severity = doc.severity || 0;
                var score = severity + (doc.assignee ? 10 : 0);
                emit(score + '_' + doc._id, doc);
            }""".trimIndent(),
            "reduce" to "_count",
        ),
        "new" to mapOf(
            "map" to """function(doc) {
                if (doc._type !== 'bugzee') return;
                emit(doc._id, doc);
            }""".trimIndent(),
            "reduce" to "_count",
        ),
        "top" to mapOf(
            "map" to """function(doc) {
                if (doc._type !== 'bugzee') return;
                emit(doc.severity || 0, doc);
            }""".trimIndent(),
            "reduce" to "_count",
        ),
        "by_severity" to mapOf(
            "map" to """function(doc) {
                if (doc._type !== 'bugzee') return;
                var sev = doc.severity || 0;
                var cat = sev >= 8 ? 'critical' : sev >= 5 ? 'high' : sev >= 3 ? 'medium' : sev >= 1 ? 'low' : 'trivial';
                emit(cat, doc);
            }""".trimIndent(),
        ),
        "by_product" to mapOf(
            "map" to """function(doc) {
                if (doc._type !== 'bugzee') return;
                emit(doc.product, doc);
            }""".trimIndent(),
        ),
        "by_assignee" to mapOf(
            "map" to """function(doc) {
                if (doc._type !== 'bugzee') return;
                if (doc.assignee) emit(doc.assignee, doc);
            }""".trimIndent(),
        ),
    )
    
    return mapOf(
        "_id" to "_design/bugzee",
        "language" to "javascript",
        "views" to views,
    )
}
