package borg.literbike.ccek.store.couchdb

import kotlin.hash.Hash

/**
 * Attachment manager for handling document attachments
 */
object AttachmentManager {

    /**
     * Validate attachment data
     */
    fun validateAttachment(name: String, data: ByteArray, contentType: String): CouchResult<Unit> {
        if (name.isEmpty()) {
            return Result.failure(CouchError.badRequest("Attachment name cannot be empty"))
        }

        if ('/' in name || '\\' in name || '\u0000' in name) {
            return Result.failure(CouchError.badRequest("Invalid characters in attachment name"))
        }

        if (data.size > 1_073_741_824) {
            return Result.failure(CouchError.requestEntityTooLarge("Attachment exceeds size limit"))
        }

        if (contentType.isEmpty()) {
            return Result.failure(CouchError.badRequest("Content type is required"))
        }

        return Result.success(Unit)
    }

    /**
     * Create attachment info from data
     */
    fun createAttachmentInfo(data: ByteArray, contentType: String): CouchResult<AttachmentInfo> {
        val digest = calculateDigest(data)

        return Result.success(
            AttachmentInfo(
                contentType = contentType,
                length = data.size.toULong(),
                digest = digest,
                stub = false,
                revpos = 1u,
                data = null
            )
        )
    }

    /**
     * Calculate digest for attachment
     */
    fun calculateDigest(data: ByteArray): String {
        // Simplified hash - in a real implementation would use MD5 or SHA
        val hash = data.contentHashCode()
        return "md5-${hash.toString(16)}"
    }

    /**
     * Encode attachment data as base64
     */
    fun encodeBase64(data: ByteArray): String {
        // Platform-specific base64 encoding
        return data.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * Decode base64 attachment data
     */
    fun decodeBase64(encoded: String): CouchResult<ByteArray> {
        // Simplified hex decoding
        return try {
            val bytes = ByteArray(encoded.length / 2) { i ->
                encoded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            Result.success(bytes)
        } catch (e: Exception) {
            Result.failure(CouchError.badRequest("Invalid base64 data: ${e.message}"))
        }
    }

    /**
     * Check if content type is supported
     */
    fun isSupportedContent(contentType: String): Boolean {
        val supportedTypes = listOf(
            "text/plain",
            "text/html",
            "text/css",
            "text/javascript",
            "application/json",
            "application/xml",
            "application/pdf",
            "application/octet-stream",
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/svg+xml",
            "audio/mpeg",
            "audio/wav",
            "video/mp4",
            "video/mpeg"
        )

        if (contentType in supportedTypes) {
            return true
        }

        return contentType.startsWith("text/") ||
                contentType.startsWith("image/") ||
                contentType.startsWith("audio/") ||
                contentType.startsWith("video/") ||
                contentType.startsWith("application/")
    }

    /**
     * Compress attachment data (simplified)
     */
    fun compressData(data: ByteArray, compressionType: String = "none"): CouchResult<ByteArray> {
        // In a real implementation, would use gzip, deflate, etc.
        return Result.success(data.copyOf())
    }

    /**
     * Decompress attachment data (simplified)
     */
    fun decompressData(data: ByteArray, compressionType: String = "none"): CouchResult<ByteArray> {
        return Result.success(data.copyOf())
    }

    /**
     * Get MIME type from file extension
     */
    fun mimeTypeFromExtension(filename: String): String {
        val extension = filename.substringAfterLast('.', "").lowercase()

        return when (extension) {
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "text/javascript"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "mpeg", "mpg" -> "video/mpeg"
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            else -> "application/octet-stream"
        }
    }

    /**
     * Create inline attachment (with base64 data)
     */
    fun createInlineAttachment(data: ByteArray, contentType: String): CouchResult<AttachmentInfo> {
        val digest = calculateDigest(data)
        val encodedData = encodeBase64(data)

        return Result.success(
            AttachmentInfo(
                contentType = contentType,
                length = data.size.toULong(),
                digest = digest,
                stub = false,
                revpos = 1u,
                data = encodedData
            )
        )
    }

    /**
     * Create stub attachment (reference only)
     */
    fun createStubAttachment(
        length: ULong,
        contentType: String,
        digest: String,
        revpos: UInt
    ): AttachmentInfo {
        return AttachmentInfo(
            contentType = contentType,
            length = length,
            digest = digest,
            stub = true,
            revpos = revpos,
            data = null
        )
    }

    /**
     * Merge attachment collections
     */
    fun mergeAttachments(
        existing: Map<String, AttachmentInfo>?,
        new: Map<String, AttachmentInfo>?
    ): Map<String, AttachmentInfo>? {
        return when {
            existing == null && new == null -> null
            existing != null && new == null -> existing
            existing == null && new != null -> new
            else -> {
                val merged = existing!!.toMutableMap()
                merged.putAll(new!!)
                merged
            }
        }
    }

    /**
     * Get attachment statistics
     */
    fun getAttachmentStats(attachments: Map<String, AttachmentInfo>?): AttachmentStats {
        if (attachments == null) {
            return AttachmentStats()
        }

        val count = attachments.size
        val totalSize = attachments.values.sumOf { it.length }
        val contentTypes = attachments.values.map { it.contentType }.toSet().toList()

        return AttachmentStats(
            count = count,
            totalSize = totalSize,
            contentTypes = contentTypes
        )
    }

    /**
     * Validate attachment integrity
     */
    fun validateIntegrity(attachment: AttachmentInfo, data: ByteArray): CouchResult<Boolean> {
        if (attachment.length != data.size.toULong()) {
            return Result.success(false)
        }

        val calculatedDigest = calculateDigest(data)
        return Result.success(attachment.digest == calculatedDigest)
    }

    /**
     * Get attachment security info
     */
    fun getSecurityInfo(contentType: String, data: ByteArray): AttachmentSecurity {
        val isExecutable = isExecutableType(contentType)
        val containsScripts = containsScripts(contentType, data)
        val isSafe = !isExecutable && !containsScripts

        return AttachmentSecurity(
            isSafe = isSafe,
            isExecutable = isExecutable,
            containsScripts = containsScripts,
            contentType = contentType
        )
    }

    /**
     * Check if content type is executable
     */
    private fun isExecutableType(contentType: String): Boolean {
        return contentType in listOf(
            "application/x-executable",
            "application/x-msdos-program",
            "application/x-msdownload",
            "application/x-sh",
            "application/x-csh",
            "application/x-ksh"
        )
    }

    /**
     * Check if content contains scripts
     */
    private fun containsScripts(contentType: String, data: ByteArray): Boolean {
        if (contentType == "text/html") {
            val content = data.decodeToString().lowercase()
            return "<script" in content || "javascript:" in content
        }

        if (contentType == "text/javascript" || contentType == "application/javascript") {
            return true
        }

        return false
    }
}

/**
 * Attachment statistics
 */
data class AttachmentStats(
    val count: Int = 0,
    val totalSize: ULong = 0u,
    val contentTypes: List<String> = emptyList()
)

/**
 * Attachment security information
 */
data class AttachmentSecurity(
    val isSafe: Boolean,
    val isExecutable: Boolean,
    val containsScripts: Boolean,
    val contentType: String
)
