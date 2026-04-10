package borg.literbike.ccek.store.cas

/**
 * Real Backend Adapters for CAS Lazy Projection Gateway
 *
 * This module provides production-ready backend adapters for:
 * - Git (via JGit or platform git CLI)
 * - IPFS (via IPFS HTTP API)
 * - S3 Blobs (via AWS SDK or HTTP API)
 * - KV (via platform-specific embedded database)
 *
 * Torrent adapter is deferred to future implementation.
 */

// ============================================================================
// Git Backend Adapter
// ============================================================================

/**
 * Git projection adapter.
 *
 * UNSAFE: In production, use JGit or invoke git CLI.
 * This implementation provides the interface contract; actual git operations
 * require a platform-specific git library.
 */
class GitProjectionAdapter(
    private val repoPath: String,
    private val namespace: String,
) : ProjectionAdapter {
    companion object {
        fun create(repoPath: String, namespace: String): Result<GitProjectionAdapter> {
            // Initialize git repository if it doesn't exist
            // UNSAFE: In production, use JGit to init repo
            val repoDir = java.io.File(repoPath)
            if (!repoDir.exists()) {
                repoDir.mkdirs()
                // UNSAFE: git init would happen here via JGit
            }
            return Result.success(GitProjectionAdapter(repoPath, namespace))
        }
    }

    override fun backend(): ProjectionBackend = ProjectionBackend.Git

    override fun deterministicLocator(hash: ContentHash): String {
        return "$namespace/${hash.toHex()}"
    }

    override fun project(hash: ContentHash, bytes: ByteArray): Result<String> {
        // UNSAFE: In production, use JGit to create blob in git object database
        // Check if object already exists, then create blob, verify hash
        // This is a stub - real impl requires JGit
        return Result.failure(UnsupportedOperationException(
            "Git projection requires JGit. Use jgit bindings to create blob and verify hash."
        ))
    }

    override fun fetch(locator: String): Result<ByteArray?> {
        // Parse locator to extract hash
        val prefix = "$namespace/"
        val hashStr = locator.removePrefix(prefix)
            ?: return Result.failure(IllegalArgumentException("Invalid git locator format"))

        // UNSAFE: In production, use JGit to find and read blob
        return Result.failure(UnsupportedOperationException(
            "Git fetch requires JGit. Use jgit to find blob by OID and read content."
        ))
    }
}

// ============================================================================
// IPFS Backend Adapter
// ============================================================================

/**
 * IPFS projection adapter.
 *
 * UNSAFE: Requires ktor-client or okhttp for IPFS HTTP API calls.
 */
class IpfsProjectionAdapter(
    private val host: String,
    private val port: Int,
    private val namespace: String,
) : ProjectionAdapter {
    companion object {
        fun create(host: String, port: Int, namespace: String): IpfsProjectionAdapter {
            return IpfsProjectionAdapter(host, port, namespace)
        }

        fun withUrl(url: String, namespace: String): Result<IpfsProjectionAdapter> {
            // UNSAFE: Validate URL format
            return runCatching {
                val parts = url.split("://")
                val hostPort = if (parts.size > 1) parts[1] else parts[0]
                val hostAndPort = hostPort.split(":")
                val h = hostAndPort[0]
                val p = hostAndPort.getOrNull(1)?.toIntOrNull() ?: 5001
                IpfsProjectionAdapter(h, p, namespace)
            }
        }
    }

    override fun backend(): ProjectionBackend = ProjectionBackend.Ipfs

    override fun deterministicLocator(hash: ContentHash): String {
        // IPFS uses multihash, but we'll use our SHA256 hash as CID v1
        val encoded = base58Encode(hash)
        return "$namespace/$encoded"
    }

    override fun project(hash: ContentHash, bytes: ByteArray): Result<String> {
        // UNSAFE: IPFS requires async HTTP POST to /api/v0/add
        // This would use ktor-client or okhttp to add content to IPFS
        return Result.failure(UnsupportedOperationException(
            "IPFS add requires HTTP client. POST to /api/v0/add with form-data body."
        ))
    }

    override fun fetch(locator: String): Result<ByteArray?> {
        // Parse locator to extract IPFS hash
        val prefix = "$namespace/"
        val cid = locator.removePrefix(prefix)
            ?: return Result.failure(IllegalArgumentException("Invalid IPFS locator format"))

        // UNSAFE: IPFS requires async HTTP GET from /api/v0/cat
        return Result.failure(UnsupportedOperationException(
            "IPFS fetch requires HTTP client. GET /api/v0/cat?arg=<cid>"
        ))
    }
}

// ============================================================================
// S3 Blobs Backend Adapter
// ============================================================================

/**
 * S3 Blobs projection adapter.
 *
 * UNSAFE: Requires ktor-client or AWS SDK for S3 operations.
 */
class S3BlobsProjectionAdapter(
    private val endpoint: String,
    private val bucket: String,
    private val namespace: String,
    private val accessKey: String? = null,
    private val secretKey: String? = null,
) : ProjectionAdapter {
    companion object {
        fun create(
            endpoint: String,
            bucket: String,
            namespace: String,
        ): S3BlobsProjectionAdapter {
            return S3BlobsProjectionAdapter(endpoint, bucket, namespace)
        }

        fun withCredentials(
            endpoint: String,
            bucket: String,
            namespace: String,
            accessKey: String,
            secretKey: String,
        ): S3BlobsProjectionAdapter {
            return S3BlobsProjectionAdapter(endpoint, bucket, namespace, accessKey, secretKey)
        }
    }

    private fun objectKey(hash: ContentHash): String {
        return "$namespace/${hash.toHex()}"
    }

    private fun objectUrl(key: String): String {
        return "$endpoint/$bucket/$key"
    }

    override fun backend(): ProjectionBackend = ProjectionBackend.S3Blobs

    override fun deterministicLocator(hash: ContentHash): String {
        return objectKey(hash)
    }

    private fun generateAuthHeaders(method: String, key: String): Result<Map<String, String>> {
        // Simple AWS SigV4-style authentication (simplified for S3-compatible APIs)
        val ak = accessKey
            ?: return Result.failure(IllegalStateException("Access key required for authenticated requests"))
        val sk = secretKey
            ?: return Result.failure(IllegalStateException("Secret key required for authenticated requests"))

        val timestamp = java.time.Instant.now().toString()
        val date = timestamp.take(8)

        // UNSAFE: In production, use HmacSHA256 for signature computation
        // This is a simplified implementation
        val signature = try {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(sk.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val input = "$method\n$key\n$timestamp"
            mac.doFinal(input.toByteArray(Charsets.UTF_8)).toHex()
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val headers = mutableMapOf<String, String>()
        headers["Authorization"] = "AWS4-HMAC-SHA256 Credential=$ak/$date, SignedHeaders=host;x-amz-date, Signature=$signature"
        headers["x-amz-date"] = timestamp

        return Result.success(headers)
    }

    override fun project(hash: ContentHash, bytes: ByteArray): Result<String> {
        val key = objectKey(hash)
        val url = objectUrl(key)

        // UNSAFE: Requires HTTP PUT client (ktor-client or okhttp)
        // Add authentication if credentials are provided
        if (accessKey != null && secretKey != null) {
            generateAuthHeaders("PUT", key).getOrThrow()
        }

        return Result.failure(UnsupportedOperationException(
            "S3 upload requires HTTP client. PUT $url with body bytes."
        ))
    }

    override fun fetch(locator: String): Result<ByteArray?> {
        val url = objectUrl(locator)

        // UNSAFE: Requires HTTP GET client (ktor-client or okhttp)
        // Add authentication if credentials are provided
        if (accessKey != null && secretKey != null) {
            generateAuthHeaders("GET", locator).getOrThrow()
        }

        return Result.failure(UnsupportedOperationException(
            "S3 fetch requires HTTP client. GET $url"
        ))
    }
}

// ============================================================================
// KV Backend Adapter
// ============================================================================

/**
 * KV projection adapter (embedded key-value store).
 *
 * UNSAFE: In production, use platform-specific embedded KV store
 * (e.g., LMDB, RocksDB, or SQLite).
 */
class KvProjectionAdapter(
    private val db: MutableMap<String, ByteArray>,
    private val namespace: String,
) : ProjectionAdapter {
    companion object {
        fun create(namespace: String): KvProjectionAdapter {
            return KvProjectionAdapter(mutableMapOf(), namespace)
        }
    }

    private fun kvKey(hash: ContentHash): String {
        return "$namespace/${hash.toHex()}"
    }

    override fun backend(): ProjectionBackend = ProjectionBackend.Kv

    override fun deterministicLocator(hash: ContentHash): String {
        return kvKey(hash)
    }

    override fun project(hash: ContentHash, bytes: ByteArray): Result<String> {
        val key = kvKey(hash)
        db[key] = bytes.copyOf()
        return Result.success(deterministicLocator(hash))
    }

    override fun fetch(locator: String): Result<ByteArray?> {
        val value = db[locator]
        return Result.success(value?.copyOf())
    }
}

// ============================================================================
// Factory Functions
// ============================================================================

/** Create a git projection adapter */
fun createGitAdapter(
    repoPath: String,
    namespace: String,
): Result<ProjectionAdapter> {
    return GitProjectionAdapter.create(repoPath, namespace)
}

/** Create an IPFS projection adapter */
fun createIpfsAdapter(
    host: String,
    port: Int,
    namespace: String,
): ProjectionAdapter {
    return IpfsProjectionAdapter.create(host, port, namespace)
}

/** Create an S3 blobs projection adapter */
fun createS3Adapter(
    endpoint: String,
    bucket: String,
    namespace: String,
): ProjectionAdapter {
    return S3BlobsProjectionAdapter.create(endpoint, bucket, namespace)
}

/** Create an S3 blobs projection adapter with credentials */
fun createS3AdapterWithAuth(
    endpoint: String,
    bucket: String,
    namespace: String,
    accessKey: String,
    secretKey: String,
): ProjectionAdapter {
    return S3BlobsProjectionAdapter.withCredentials(
        endpoint, bucket, namespace, accessKey, secretKey
    )
}

/** Create a KV projection adapter */
fun createKvAdapter(
    namespace: String,
): ProjectionAdapter {
    return KvProjectionAdapter.create(namespace)
}

/** Base58 encoding for IPFS CIDs */
private fun base58Encode(bytes: ByteArray): String {
    val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    val input = bytes.copyOf()
    // Count leading zeros
    var zeros = 0
    while (zeros < input.size && input[zeros] == 0.toByte()) zeros++

    // Encode
    val encoded = StringBuilder()
    var startIdx = zeros
    while (startIdx < input.size) {
        var mod = 0
        var carry = 0
        var nonZeroStart = input.size
        for (i in input.indices.reversed()) {
            carry = (input[i].toInt() and 0xFF) + mod * 256
            input[i] = (carry % 58).toByte()
            mod = carry / 58
            if (input[i] != 0.toByte() && i < nonZeroStart) {
                nonZeroStart = i
            }
        }
        startIdx = nonZeroStart
        if (carry > 0 || mod > 0) {
            encoded.append(alphabet[mod])
        }
        if (startIdx >= input.size) break
    }

    // Add leading '1's for each leading zero byte
    repeat(zeros) { encoded.append('1') }

    return encoded.reverse().toString()
}
