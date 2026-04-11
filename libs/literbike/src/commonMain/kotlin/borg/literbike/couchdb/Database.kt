package borg.literbike.couchdb

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Individual database instance
 */
data class DatabaseInstance(
    val name: String,
    val tree: MutableMap<String, ByteArray> = mutableMapOf(),
    var sequenceCounter: Long = 0,
    var docCount: Long = 0,
    var deletedCount: Long = 0,
    val designDocs: MutableMap<String, DesignDocument> = mutableMapOf()
) {
    /**
     * Put a document
     */
    fun putDocument(doc: Document): CouchResult<Pair<String, String>> {
        val docKey = "doc:${doc.id}"
        val serialized = Json.encodeToString(DocumentSerializer, doc).toByteArray(Charsets.UTF_8)

        // Check if document exists for conflict detection
        tree[docKey]?.let { existingData ->
            runCatching {
                val existingDoc = Json.decodeFromString(DocumentSerializer, existingData.decodeToString())
                if (existingDoc.rev != doc.rev) {
                    return Result.failure(CouchException(CouchError.conflict("Document update conflict")))
                }
            }.onFailure {
                return Result.failure(CouchException(CouchError.internalServerError(it.message ?: "JSON parse error")))
            }
        }

        // Generate new revision
        val newRev = generateRevision(doc.rev)
        val updatedDoc = doc.copy(rev = newRev)

        val updatedSerialized = Json.encodeToString(DocumentSerializer, updatedDoc).toByteArray(Charsets.UTF_8)
        tree[docKey] = updatedSerialized

        sequenceCounter++
        if (doc.deleted == true) {
            deletedCount++
        } else {
            docCount++
        }

        return Result.success(doc.id to newRev)
    }

    /**
     * Get a document
     */
    fun getDocument(id: String): CouchResult<Document> {
        val docKey = "doc:$id"
        val data = tree[docKey] ?: return Result.failure(
            CouchException(CouchError.notFound("Document not found"))
        )

        return runCatching {
            val doc = Json.decodeFromString(DocumentSerializer, data.decodeToString())
            if (doc.deleted == true) {
                throw CouchException(CouchError.notFound("Document is deleted"))
            }
            doc
        }
    }

    /**
     * Delete a document
     */
    fun deleteDocument(id: String, rev: String): CouchResult<Pair<String, String>> {
        val doc = getDocument(id).getOrThrow()
        if (doc.rev != rev) {
            return Result.failure(CouchException(CouchError.conflict("Document revision conflict")))
        }
        return putDocument(doc.copy(deleted = true))
    }

    /**
     * Check if document exists
     */
    fun documentExists(id: String): Boolean {
        return "doc:$id" in tree
    }

    /**
     * Get all documents with pagination
     */
    fun getAllDocuments(query: ViewQuery): CouchResult<ViewResult> {
        val results = mutableListOf<ViewRow>()
        val limit = query.limit?.toInt() ?: 25
        val skip = query.skip?.toInt() ?: 0
        val includeDocs = query.includeDocs ?: false

        var totalCount = 0
        var currentSkip = 0

        for ((key, value) in tree.entries) {
            if (!key.startsWith("doc:")) continue
            totalCount++

            if (currentSkip < skip) {
                currentSkip++
                continue
            }

            if (results.size >= limit) break

            val docId = key.removePrefix("doc:")
            val doc = runCatching {
                Json.decodeFromString(DocumentSerializer, value.decodeToString())
            }.getOrElse { return Result.failure(CouchException(CouchError.internalServerError(it.message ?: "JSON parse error"))) }

            // Skip deleted documents unless explicitly requested
            if (doc.deleted == true && query.conflicts != true) continue

            val row = ViewRow(
                id = docId,
                key = kotlinx.serialization.json.JsonPrimitive(docId),
                value = kotlinx.serialization.json.buildJsonObject { put("rev", kotlinx.serialization.json.JsonPrimitive(doc.rev)) },
                doc = if (includeDocs) doc else null
            )
            results.add(row)
        }

        return Result.success(ViewResult(
            totalRows = totalCount.toULong(),
            offset = skip.toUInt(),
            rows = results,
            updateSeq = sequenceCounter.toULong(),
            nextCursor = null
        ))
    }

    /**
     * Store design document
     */
    fun putDesignDocument(ddoc: DesignDocument): CouchResult<Pair<String, String>> {
        designDocs[ddoc.id] = ddoc
        val ddocKey = "design:${ddoc.id}"
        tree[ddocKey] = Json.encodeToString(DesignDocumentSerializer, ddoc).toByteArray(Charsets.UTF_8)
        return Result.success(ddoc.id to ddoc.rev)
    }

    /**
     * Get design document
     */
    fun getDesignDocument(id: String): CouchResult<DesignDocument> {
        return designDocs[id]?.let { Result.success(it) }
            ?: Result.failure(CouchException(CouchError.notFound("Design document not found")))
    }
}

/**
 * Core database manager for CouchDB emulation
 */
class DatabaseManager(
    private val data: MutableMap<String, MutableMap<String, ByteArray>> = mutableMapOf(),
    private val databases: MutableMap<String, DatabaseInstance> = mutableMapOf(),
    private val serverUuid: String = generateUuid()
) {
    companion object {
        fun new(): DatabaseManager = DatabaseManager()

        private fun generateUuid(): String {
            return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace("[xy]".toRegex()) { match ->
                val r = (Math.random() * 16).toInt()
                val v = if (match.value == "x") r else (r and 0x3 or 0x8)
                v.toString(16)
            }
        }
    }

    /**
     * Create a new database
     */
    fun createDatabase(name: String): CouchResult<DatabaseInfo> {
        if (!isValidDbName(name)) {
            return Result.failure(CouchException(CouchError.badRequest("Invalid database name")))
        }

        if (name in databases) {
            return Result.failure(CouchException(CouchError.conflict("Database already exists")))
        }

        val dbInstance = DatabaseInstance(
            name = name,
            tree = mutableMapOf()
        )

        databases[name] = dbInstance
        return getDatabaseInfo(name)
    }

    /**
     * Delete a database
     */
    fun deleteDatabase(name: String): CouchResult<kotlinx.serialization.json.JsonObject> {
        if (name !in databases) {
            return Result.failure(CouchException(CouchError.notFound("Database does not exist")))
        }

        databases.remove(name)
        return Result.success(kotlinx.serialization.json.buildJsonObject {
            put("ok", kotlinx.serialization.json.JsonPrimitive(true))
        })
    }

    /**
     * Get database information
     */
    fun getDatabaseInfo(name: String): CouchResult<DatabaseInfo> {
        val dbInstance = databases[name]
            ?: return Result.failure(CouchException(CouchError.notFound("Database does not exist")))

        val dataSize = dbInstance.tree.size.toULong()

        return Result.success(DatabaseInfo(
            dbName = name,
            docCount = dbInstance.docCount.toULong(),
            docDelCount = dbInstance.deletedCount.toULong(),
            updateSeq = dbInstance.sequenceCounter.toULong(),
            purgeSeq = 0uL,
            compactRunning = false,
            diskSize = dataSize,
            dataSize = dataSize,
            instanceStartTime = "1970-01-01T00:00:00.000000Z",
            diskFormatVersion = 8u,
            committedUpdateSeq = dbInstance.sequenceCounter.toULong()
        ))
    }

    /**
     * List all databases
     */
    fun listDatabases(): CouchResult<AllDbsResponse> {
        val dbNames = databases.keys.sorted()
        return Result.success(AllDbsResponse(dbNames))
    }

    /**
     * Check if database exists
     */
    fun databaseExists(name: String): Boolean = name in databases

    /**
     * Get cloned database instance
     */
    fun getDatabaseClone(name: String): CouchResult<DatabaseInstance> {
        val db = databases[name]
            ?: return Result.failure(CouchException(CouchError.notFound("Database does not exist")))

        return Result.success(DatabaseInstance(
            name = db.name,
            tree = db.tree.toMutableMap(),
            sequenceCounter = db.sequenceCounter,
            docCount = db.docCount,
            deletedCount = db.deletedCount,
            designDocs = db.designDocs.toMutableMap()
        ))
    }

    /**
     * Get server information
     */
    fun getServerInfo(): ServerInfo = ServerInfo(
        couchdb = "Welcome",
        uuid = serverUuid,
        version = "1.7.2",
        vendor = ServerVendor(
            name = "Literbike CouchDB Emulator",
            version = "0.1.0"
        ),
        features = listOf("attachments", "httpd", "ipfs", "m2m", "tensor", "cursor_pagination"),
        gitSha = "unknown"
    )

    /**
     * Initialize with default databases
     */
    fun initializeDefaults(): CouchResult<Unit> {
        val systemDbs = listOf("_users", "_replicator")
        for (dbName in systemDbs) {
            if (!databaseExists(dbName)) {
                createDatabase(dbName)
            }
        }
        return Result.success(Unit)
    }

    /**
     * Compact database (placeholder)
     */
    fun compactDatabase(name: String): CouchResult<kotlinx.serialization.json.JsonObject> {
        if (!databaseExists(name)) {
            return Result.failure(CouchException(CouchError.notFound("Database does not exist")))
        }
        return Result.success(kotlinx.serialization.json.buildJsonObject {
            put("ok", kotlinx.serialization.json.JsonPrimitive(true))
        })
    }
}

/**
 * Validate database name according to CouchDB rules
 */
private fun isValidDbName(name: String): Boolean {
    if (name.isEmpty() || name.length > 127) return false
    if (!name[0].isLowerCase()) return false
    return name.all { it.isLowerCase() || it.isDigit() || "_\$()+-/" .contains(it) }
}

/**
 * Generate new document revision
 */
private fun generateRevision(currentRev: String): String {
    val revNum = if (currentRev.isEmpty()) {
        1
    } else {
        val parts = currentRev.split("-")
        if (parts.size >= 2) parts[0].toUIntOrNull()?.plus(1u) ?: 1u else 1u
    }
    val hash = Clocks.System.now().toString(16).take(16)
    return "$revNum-$hash"
}
