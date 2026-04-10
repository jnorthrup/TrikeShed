package borg.literbike.ccek.store.couchdb

/**
 * Document operations and utilities
 */
object DocumentManager {

    /**
     * Validate document structure
     */
    fun validateDocument(doc: Document): CouchResult<Unit> {
        if (doc.id.isEmpty()) {
            return Result.failure(CouchError.badRequest("Document ID cannot be empty"))
        }

        if ('\u0000' in doc.id || (doc.id.startsWith('_') && !doc.id.startsWith("_design/"))) {
            return Result.failure(CouchError.badRequest("Invalid document ID"))
        }

        if (doc.rev.isNotEmpty() && !isValidRevision(doc.rev)) {
            return Result.failure(CouchError.badRequest("Invalid revision format"))
        }

        // Validate document size (CouchDB limit is typically 4MB)
        val docSize = doc.data.length + doc.id.length + doc.rev.length
        if (docSize > 4 * 1024 * 1024) {
            return Result.failure(CouchError.requestEntityTooLarge("Document exceeds size limit"))
        }

        return Result.success(Unit)
    }

    /**
     * Validate revision format (N-hash)
     */
    fun isValidRevision(rev: String): Boolean {
        val parts = rev.split('-')
        if (parts.size != 2) {
            return false
        }

        if (parts[0].toUIntOrNull() == null) {
            return false
        }

        return parts[1].length >= 16 && parts[1].all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    }

    /**
     * Merge document with existing data (for updates)
     */
    fun mergeDocument(existing: Document, newDoc: Document): CouchResult<Document> {
        val merged = newDoc.copy(
            rev = if (newDoc.rev.isEmpty()) existing.rev else newDoc.rev
        )

        // Merge attachments
        val mergedAttachments = if (existing.attachments != null) {
            val existingAtts = existing.attachments!!
            val newAtts = newDoc.attachments ?: emptyMap()
            existingAtts + newAtts
        } else {
            newDoc.attachments
        }

        return Result.success(merged.copy(attachments = mergedAttachments))
    }

    /**
     * Extract attachments from document data
     */
    fun extractInlineAttachments(doc: Document): CouchResult<Map<String, ByteArray>> {
        val extracted = mutableMapOf<String, ByteArray>()

        doc.attachments?.forEach { (name, attachment) ->
            attachment.data?.let { dataStr ->
                val decoded = decodeBase64(dataStr).getOrThrow()

                // Update attachment info
                val updatedAttachment = attachment.copy(
                    length = decoded.size.toULong(),
                    stub = true,
                    data = null
                )
                // Note: In a real impl, we'd mutate the doc; here we just extract
                extracted[name] = decoded
            }
        }

        return Result.success(extracted)
    }

    /**
     * Filter document for output (remove internal fields)
     */
    fun filterForOutput(doc: Document, includeAttachments: Boolean): Document {
        if (!includeAttachments) {
            val filteredAttachments = doc.attachments?.mapValues { (_, att) ->
                att.copy(data = null)
            }
            return doc.copy(attachments = filteredAttachments)
        }
        return doc
    }

    /**
     * Check if document is a design document
     */
    fun isDesignDocument(doc: Document): Boolean {
        return doc.id.startsWith("_design/")
    }

    /**
     * Check if document is a local document
     */
    fun isLocalDocument(doc: Document): Boolean {
        return doc.id.startsWith("_local/")
    }

    /**
     * Get document conflicts (simplified implementation)
     */
    fun getConflicts(doc: Document): List<String> {
        // In a real implementation, this would check for conflicting revisions
        return emptyList()
    }

    /**
     * Generate document revision
     */
    fun generateRevision(currentRev: String, docData: String): String {
        val revNum = if (currentRev.isEmpty()) {
            1u
        } else {
            val parts = currentRev.split('-')
            if (parts.size >= 2) {
                (parts[0].toUIntOrNull() ?: 0u) + 1u
            } else {
                1u
            }
        }

        // Generate hash from document data
        val hash = simpleHash(docData)

        return "$revNum-${hash.toString(16)}"
    }

    /**
     * Simple hash function for revision generation
     */
    private fun simpleHash(input: String): Long {
        var hash: Long = 0
        for (char in input) {
            hash = 31 * hash + char.code
        }
        return hash
    }

    /**
     * Check if document has conflicts
     */
    fun hasConflicts(doc: Document): Boolean {
        return getConflicts(doc).isNotEmpty()
    }

    /**
     * Get document size in bytes
     */
    fun getDocumentSize(doc: Document): Int {
        return doc.data.length + doc.id.length + doc.rev.length
    }

    /**
     * Compare documents for equality (ignoring revision)
     */
    fun documentsEqual(doc1: Document, doc2: Document): Boolean {
        return doc1.id == doc2.id &&
                doc1.data == doc2.data &&
                doc1.attachments == doc2.attachments
    }

    /**
     * Create a tombstone document for deletion
     */
    fun createTombstone(doc: Document): Document {
        return Document(
            id = doc.id,
            rev = doc.rev,
            deleted = true,
            attachments = null,
            data = "{}"
        )
    }

    /**
     * Decode base64/hex string to bytes
     */
    private fun decodeBase64(encoded: String): CouchResult<ByteArray> {
        return try {
            // Handle hex-encoded strings
            val bytes = ByteArray(encoded.length / 2) { i ->
                encoded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            Result.success(bytes)
        } catch (e: Exception) {
            Result.failure(CouchError.badRequest("Invalid base64 data: ${e.message}"))
        }
    }
}
