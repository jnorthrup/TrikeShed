package borg.literbike.couchdb

import kotlinx.serialization.json.*

/**
 * Document operations for CouchDB
 */
object DocumentOperations {
    /**
     * Validate document ID
     */
    fun validateDocumentId(id: String): CouchResult<Unit> {
        if (id.isEmpty()) {
            return Result.failure(CouchException(CouchError.badRequest("Document ID cannot be empty")))
        }

        if (id.startsWith("_") && !id.startsWith("_design/") && !id.startsWith("_local/")) {
            return Result.failure(CouchException(CouchError.badRequest("Invalid document ID: ${id.take(10)}...")))
        }

        return Result.success(Unit)
    }

    /**
     * Generate a new document ID
     */
    fun generateDocumentId(prefix: String = "doc"): String {
        val uuid = "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace("[xy]".toRegex()) { match ->
            val r = (Math.random() * 16).toInt()
            val v = if (match.value == "x") r else (r and 0x3 or 0x8)
            v.toString(16)
        }
        return "$prefix-$uuid"
    }

    /**
     * Validate document revision
     */
    fun validateRevision(rev: String): CouchResult<Unit> {
        if (rev.isEmpty()) {
            return Result.success(Unit) // New documents don't have a revision
        }

        // Revision format: N-hash (e.g., 1-abc123)
        val parts = rev.split("-", limit = 2)
        if (parts.size != 2) {
            return Result.failure(CouchException(CouchError.badRequest("Invalid revision format: $rev")))
        }

        parts[0].toIntOrNull()
            ?: return Result.failure(CouchException(CouchError.badRequest("Invalid revision number: ${parts[0]}")))

        return Result.success(Unit)
    }

    /**
     * Check if a document is a design document
     */
    fun isDesignDocument(id: String): Boolean {
        return id.startsWith("_design/")
    }

    /**
     * Check if a document is a local document
     */
    fun isLocalDocument(id: String): Boolean {
        return id.startsWith("_local/")
    }

    /**
     * Merge document fields
     */
    fun mergeDocumentFields(
        existing: JsonObject,
        new: JsonObject
    ): JsonObject {
        val merged = existing.toMutableMap()
        new.forEach { (key, value) ->
            merged[key] = value
        }
        return JsonObject(merged)
    }

    /**
     * Get document metadata
     */
    fun getDocumentMetadata(doc: Document): DocumentMetadata {
        return DocumentMetadata(
            id = doc.id,
            rev = doc.rev,
            deleted = doc.deleted ?: false,
            attachmentCount = doc.attachments?.size ?: 0
        )
    }
}

/**
 * Document metadata
 */
data class DocumentMetadata(
    val id: String,
    val rev: String,
    val deleted: Boolean,
    val attachmentCount: Int
)
