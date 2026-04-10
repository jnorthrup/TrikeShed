package borg.literbike.couchdb

import kotlin.collections.HashSet
import kotlin.math.min

/**
 * Attachment manager for handling document attachments
 */
object AttachmentManager {
    /**
     * Validate attachment data
     */
    fun validateAttachment(name: String, data: ByteArray, contentType: String): CouchResult<Unit> {
        if (name.isEmpty()) {
            return Result.failure(CouchException(CouchError.badRequest("Attachment name cannot be empty")))
        }

        if ('/' in name || '\\' in name || '\u0000' in name) {
            return Result.failure(CouchException(CouchError.badRequest("Invalid characters in attachment name")))
        }

        // Check size limits (CouchDB typically allows up to 1GB attachments)
        if (data.size > 1_073_741_824) {
            return Result.failure(CouchException(CouchError.requestEntityTooLarge("Attachment exceeds size limit")))
        }

        if (contentType.isEmpty()) {
            return Result.failure(CouchException(CouchError.badRequest("Content type is required")))
        }

        return Result.success(Unit)
    }

    /**
     * Create attachment info from data
     */
    fun createAttachmentInfo(data: ByteArray, contentType: String): CouchResult<AttachmentInfo> {
        val digest = calculateDigest(data)
        return Result.success(AttachmentInfo(
            contentType = contentType,
            length = data.size.toULong(),
            digest = digest,
            stub = false,
            revpos = 1u,
            data = null
        ))
    }

    /**
     * Calculate hash digest for attachment
     */
    fun calculateDigest(data: ByteArray): AttachmentDigest {
        // In a real implementation, we'd use MD5 or SHA
        val hash = data.contentHashCode()
        return "md5-${hash.toString(16)}"
    }

    /**
     * Encode attachment data as base64
     */
    fun encodeBase64(data: ByteArray): String {
        return okio.ByteString.of(*data).base64()
    }

    /**
     * Decode base64 attachment data
     */
    fun decodeBase64(encoded: String): CouchResult<ByteArray> {
        return runCatching {
            okio.ByteString.decodeBase64(encoded)?.toByteArray()
                ?: throw IllegalArgumentException("Invalid base64 data")
        }
    }

    /**
     * Check if content type is supported
     */
    fun isSupportedContentType(contentType: String): Boolean {
        val supportedTypes = setOf(
            "text/plain", "text/html", "text/css", "text/javascript",
            "application/json", "application/xml", "application/pdf",
            "application/octet-stream", "image/jpeg", "image/png",
            "image/gif", "image/svg+xml", "audio/mpeg", "audio/wav",
            "video/mp4", "video/mpeg"
        )

        return contentType in supportedTypes ||
            contentType.startsWith("text/") ||
            contentType.startsWith("image/") ||
            contentType.startsWith("audio/") ||
            contentType.startsWith("video/") ||
            contentType.startsWith("application/")
    }

    /**
     * Compress attachment data (simplified)
     */
    fun compressData(data: ByteArray, compressionType: String): CouchResult<ByteArray> {
        // In a real implementation, we'd use gzip, deflate, etc.
        // For simplicity, just return the original data
        return Result.success(data.copyOf())
    }

    /**
     * Decompress attachment data (simplified)
     */
    fun decompressData(data: ByteArray, compressionType: String): CouchResult<ByteArray> {
        // In a real implementation, we'd decompress based on type
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

        return Result.success(AttachmentInfo(
            contentType = contentType,
            length = data.size.toULong(),
            digest = digest,
            stub = false,
            revpos = 1u,
            data = encodedData
        ))
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
        existing: MutableMap<String, AttachmentInfo>?,
        new: MutableMap<String, AttachmentInfo>?
    ): MutableMap<String, AttachmentInfo>? {
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
    fun getAttachmentStats(attachments: MutableMap<String, AttachmentInfo>?): AttachmentStats {
        if (attachments == null) return AttachmentStats()

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
        // Check length
        if (attachment.length != data.size.toULong()) {
            return Result.success(false)
        }

        // Check digest
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
        return contentType in setOf(
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
    val totalSize: ULong = 0uL,
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
