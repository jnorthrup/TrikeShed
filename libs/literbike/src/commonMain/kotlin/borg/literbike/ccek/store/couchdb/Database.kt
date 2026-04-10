package borg.literbike.ccek.store.couchdb

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.random.Random

/**
 * Core database manager for CouchDB emulation
 */
class DatabaseManager(
    private val dataDir: String
) {
    private val databases = mutableMapOf<String, DatabaseInstance>()
    private val dbLock = Mutex()
    val serverUuid: String = generateUuid()

    companion object {
        fun new(dataDir: String): DatabaseManager = DatabaseManager(dataDir)
    }

    /**
     * Create a new database
     */
    suspend fun createDatabase(name: String): CouchResult<DatabaseInfo> {
        if (!isValidDbName(name)) {
            return Result.failure(CouchError.badRequest("Invalid database name"))
        }

        dbLock.withLock {
            if (databases.contains(name)) {
                return Result.failure(CouchError.conflict("Database already exists"))
            }

            val dbInstance = DatabaseInstance(
                name = name,
            )

            databases[name] = dbInstance
        }

        return getDatabaseInfo(name)
    }

    /**
     * Delete a database
     */
    suspend fun deleteDatabase(name: String): CouchResult<Map<String, Boolean>> {
        dbLock.withLock {
            if (!databases.containsKey(name)) {
                return Result.failure(CouchError.notFound("Database does not exist"))
            }

            databases.remove(name)
        }

        return Result.success(mapOf("ok" to true))
    }

    /**
     * Get database information
     */
    suspend fun getDatabaseInfo(name: String): CouchResult<DatabaseInfo> {
        val dbInstance = dbLock.withLock { databases[name] }
            ?: return Result.failure(CouchError.notFound("Database does not exist"))

        val docCount = dbInstance.docCount
        val deletedCount = dbInstance.deletedCount
        val sequence = dbInstance.sequenceCounter
        val dataSize = dbInstance.dataSize.toULong()

        return Result.success(
            DatabaseInfo(
                dbName = name,
                docCount = docCount.toULong(),
                docDelCount = deletedCount.toULong(),
                updateSeq = sequence.toULong(),
                purgeSeq = 0u,
                compactRunning = false,
                diskSize = dataSize,
                dataSize = dataSize,
                instanceStartTime = "1970-01-01T00:00:00.000000Z",
                diskFormatVersion = 8u,
                committedUpdateSeq = sequence.toULong()
            )
        )
    }

    /**
     * List all databases
     */
    suspend fun listDatabases(): CouchResult<List<String>> {
        val dbNames = dbLock.withLock { databases.keys.toList() }.sorted()
        return Result.success(dbNames)
    }

    /**
     * Check if database exists
     */
    suspend fun databaseExists(name: String): Boolean {
        return dbLock.withLock { databases.containsKey(name) }
    }

    /**
     * Get database instance (returns a cloned copy for safety)
     */
    suspend fun getDatabaseClone(name: String): CouchResult<DatabaseInstance> {
        return dbLock.withLock {
            databases[name]?.let { Result.success(it.clone()) }
                ?: Result.failure(CouchError.notFound("Database does not exist"))
        }
    }

    /**
     * Get server information
     */
    fun getServerInfo(): ServerInfo {
        return ServerInfo(
            couchdb = "Welcome",
            uuid = serverUuid,
            version = "1.7.2",
            vendor = ServerVendor(
                name = "LiterBike CouchDB Emulator",
                version = "0.1.0"
            ),
            features = listOf(
                "attachments",
                "httpd",
                "ipfs",
                "m2m",
                "tensor",
                "cursor_pagination"
            ),
            gitSha = "unknown"
        )
    }

    /**
     * Initialize with default databases
     */
    suspend fun initializeDefaults(): CouchResult<Unit> {
        val systemDbs = listOf("_users", "_replicator")

        for (dbName in systemDbs) {
            if (!databaseExists(dbName)) {
                createDatabase(dbName)
            }
        }

        return Result.success(Unit)
    }

    /**
     * Compact database (placeholder implementation)
     */
    suspend fun compactDatabase(name: String): CouchResult<Map<String, Boolean>> {
        if (!databaseExists(name)) {
            return Result.failure(CouchError.notFound("Database does not exist"))
        }

        return Result.success(mapOf("ok" to true))
    }
}

/**
 * Individual database instance
 */
class DatabaseInstance(
    val name: String
) {
    private val documents = mutableMapOf<String, Document>()
    private val designDocs = mutableMapOf<String, DesignDocument>()
    private var _sequenceCounter: Long = 0
    private var _docCount: Long = 0
    private var _deletedCount: Long = 0
    private val lock = Mutex()

    val sequenceCounter: Long get() = _sequenceCounter
    val docCount: Long get() = _docCount
    val deletedCount: Long get() = _deletedCount
    val dataSize: Int get() = documents.size + designDocs.size

    fun clone(): DatabaseInstance {
        return DatabaseInstance(name).also { clone ->
            clone._sequenceCounter = this._sequenceCounter
            clone._docCount = this._docCount
            clone._deletedCount = this._deletedCount
            // Copy documents and design docs
            this.documents.forEach { (k, v) -> clone.documents[k] = v }
            this.designDocs.forEach { (k, v) -> clone.designDocs[k] = v }
        }
    }

    /**
     * Put a document
     */
    suspend fun putDocument(doc: Document): CouchResult<Pair<String, String>> {
        lock.withLock {
            // Check if document exists for conflict detection
            documents[doc.id]?.let { existing ->
                if (existing.rev != doc.rev) {
                    return Result.failure(CouchError.conflict("Document update conflict"))
                }
            }

            // Generate new revision
            val newRev = generateRevision(doc.rev)
            val updatedDoc = doc.copy(rev = newRev)

            documents[doc.id] = updatedDoc

            _sequenceCounter++

            if (doc.deleted == true) {
                _deletedCount++
            } else {
                _docCount++
            }

            return Result.success(doc.id to newRev)
        }
    }

    /**
     * Get a document
     */
    suspend fun getDocument(id: String): CouchResult<Document> {
        return lock.withLock {
            documents[id]?.let { doc ->
                if (doc.deleted == true) {
                    Result.failure(CouchError.notFound("Document is deleted"))
                } else {
                    Result.success(doc)
                }
            } ?: Result.failure(CouchError.notFound("Document not found"))
        }
    }

    /**
     * Delete a document
     */
    suspend fun deleteDocument(id: String, rev: String): CouchResult<Pair<String, String>> {
        val doc = getDocument(id).getOrThrow()

        if (doc.rev != rev) {
            return Result.failure(CouchError.conflict("Document revision conflict"))
        }

        val tombstone = doc.copy(deleted = true, data = "{}")
        return putDocument(tombstone)
    }

    /**
     * Check if document exists
     */
    suspend fun documentExists(id: String): Boolean {
        return lock.withLock { documents.containsKey(id) }
    }

    /**
     * Get all documents with pagination
     */
    suspend fun getAllDocuments(query: ViewQuery): CouchResult<ViewResult> {
        return lock.withLock {
            val results = mutableListOf<ViewRow>()
            val limit = (query.limit ?: 25u).toInt()
            val skip = (query.skip ?: 0u).toInt()
            val includeDocs = query.includeDocs ?: false
            val showConflicts = query.conflicts ?: false

            var totalCount = 0
            var currentSkip = 0

            for ((docId, doc) in documents) {
                totalCount++

                if (currentSkip < skip) {
                    currentSkip++
                    continue
                }

                if (results.size >= limit) {
                    break
                }

                // Skip deleted documents unless explicitly requested
                if (doc.deleted == true && !showConflicts) {
                    continue
                }

                val row = ViewRow(
                    id = docId,
                    key = docId,
                    value = """{"rev":"${doc.rev}"}""",
                    doc = if (includeDocs) doc else null
                )

                results.add(row)
            }

            Result.success(
                ViewResult(
                    totalRows = totalCount.toULong(),
                    offset = skip.toUInt(),
                    rows = results,
                    updateSeq = _sequenceCounter.toULong(),
                    nextCursor = null
                )
            )
        }
    }

    /**
     * Store design document
     */
    suspend fun putDesignDocument(ddoc: DesignDocument): CouchResult<Pair<String, String>> {
        return lock.withLock {
            designDocs[ddoc.id] = ddoc
            Result.success(ddoc.id to ddoc.rev)
        }
    }

    /**
     * Get design document
     */
    suspend fun getDesignDocument(id: String): CouchResult<DesignDocument> {
        return lock.withLock {
            designDocs[id]?.let { Result.success(it) }
                ?: Result.failure(CouchError.notFound("Design document not found"))
        }
    }
}

/**
 * Validate database name according to CouchDB rules
 */
fun isValidDbName(name: String): Boolean {
    if (name.isEmpty() || name.length > 127) {
        return false
    }

    // Must start with lowercase letter
    if (!name.first().isLowerCase()) {
        return false
    }

    // Only lowercase letters, digits, and special chars
    return name.all { c -> c.isLowerCase() || c.isDigit() || "_\$()+-/".contains(c) }
}

/**
 * Generate new document revision
 */
fun generateRevision(currentRev: String): String {
    val revNum = if (currentRev.isEmpty()) {
        1
    } else {
        val parts = currentRev.split("-")
        if (parts.size >= 2) {
            parts[0].toUIntOrNull() ?: 0u + 1u
        } else {
            1u
        }
    }

    val hash = Random.nextLong().toString(16).take(16)
    return "$revNum-$hash"
}

/**
 * Generate a simple UUID-like string
 */
fun generateUuid(): String {
    val bytes = ByteArray(16) { Random.nextInt(256).toByte() }
    return bytes.joinToString("") { "%02x".format(it) }
}
