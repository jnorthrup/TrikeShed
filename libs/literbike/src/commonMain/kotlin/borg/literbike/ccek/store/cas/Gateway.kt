package borg.literbike.ccek.store.cas

import kotlin.concurrent.withLock
import kotlin.experimental.and

/**
 * Lazy N-way CAS projection gateway.
 *
 * The gateway stores canonical content once and materializes backend-specific
 * projections only when explicitly requested.
 */

/** Threshold in bytes below which objects are stored as a single inline blob. */
const val SMALL_OBJECT_THRESHOLD: Int = 4096

/** Backends supported by the lazy projection gateway. */
enum class ProjectionBackend {
    Git,
    Torrent,
    Ipfs,
    S3Blobs,
    Kv;

    fun asStr(): String = when (this) {
        Git -> "git"
        Torrent -> "torrent"
        Ipfs -> "ipfs"
        S3Blobs -> "s3-blobs"
        Kv -> "kv"
    }
}

/** Strategy for how objects are chunked before CAS storage. */
sealed class ChunkStrategy {
    /** Store as a single blob (used for objects <= SMALL_OBJECT_THRESHOLD bytes). */
    data object Inline : ChunkStrategy()

    /** Split into fixed-size chunks and store a manifest referencing each chunk hash. */
    data class FixedSize(val chunkBytes: Int) : ChunkStrategy()
}

/** Manifest for chunked objects: ordered list of chunk hashes. */
data class ChunkManifest(
    val strategy: ChunkStrategy,
    /** For Inline: single-element list with the object hash. For FixedSize: ordered chunk hashes. */
    val chunkHashes: List<ContentHash>,
    val totalSize: Long,
) {
    companion object {
        fun fromBytes(bytes: ByteArray, strategy: ChunkStrategy): ChunkManifest {
            return when (strategy) {
                is ChunkStrategy.Inline -> ChunkManifest(
                    chunkHashes = listOf(digest(bytes)),
                    totalSize = bytes.size.toLong(),
                    strategy = strategy,
                )
                is ChunkStrategy.FixedSize -> {
                    val chunkHashes = bytes
                        .chunked(strategy.chunkBytes)
                        .map { digest(it.toByteArray()) }
                    ChunkManifest(
                        chunkHashes = chunkHashes,
                        totalSize = bytes.size.toLong(),
                        strategy = strategy,
                    )
                }
            }
        }
    }
}

/** Canonical CAS metadata envelope. */
data class CasEnvelope(
    val algorithm: String,
    val size: Long,
    val mediaType: String,
)

/** Returned after storing canonical bytes. */
data class PutResult(
    val hash: ContentHash,
    val envelope: CasEnvelope,
)

/** Projection result for a backend materialization. */
data class ProjectionRecord(
    val backend: ProjectionBackend,
    val hash: ContentHash,
    val locator: String,
)

/** Adapter contract for individual backends. */
interface ProjectionAdapter {
    fun backend(): ProjectionBackend
    fun deterministicLocator(hash: ContentHash): String
    fun project(hash: ContentHash, bytes: ByteArray): Result<String>
    fun fetch(locator: String): Result<ByteArray?>
}

/** In-memory adapter used for deterministic tests and local simulation. */
class InMemoryProjectionAdapter(
    private val backendType: ProjectionBackend,
    private val namespace: String,
) : ProjectionAdapter {
    private val objects = mutableMapOf<String, ByteArray>()
    private var writeCountValue = 0

    fun writeCount(): Int = writeCountValue

    override fun backend(): ProjectionBackend = backendType

    override fun deterministicLocator(hash: ContentHash): String {
        return "$namespace/${hash.toHex()}"
    }

    override fun project(hash: ContentHash, bytes: ByteArray): Result<String> {
        val locator = deterministicLocator(hash)
        if (!objects.containsKey(locator)) {
            objects[locator] = bytes.copyOf()
            writeCountValue++
        }
        return Result.success(locator)
    }

    override fun fetch(locator: String): Result<ByteArray?> {
        return Result.success(objects[locator]?.copyOf())
    }
}

/** Policy controlling when projections are triggered and the fallback order. */
data class ProjectionPolicy(
    /** Backends to auto-project when `put` is called (eager mode). Empty means fully lazy. */
    val eagerBackends: List<ProjectionBackend> = emptyList(),
    /** Preferred fallback order for `get` when no backend is specified. */
    val fallbackOrder: List<ProjectionBackend> = listOf(
        ProjectionBackend.Kv,
        ProjectionBackend.Git,
        ProjectionBackend.S3Blobs,
        ProjectionBackend.Ipfs,
        ProjectionBackend.Torrent,
    ),
) {
    companion object {
        fun default(): ProjectionPolicy = ProjectionPolicy()
    }
}

/** Canonical CAS + lazy backend projection gateway. */
class LazyProjectionGateway {
    private val canonical: ContentAddressedStore = ContentAddressedStore()
    private val envelopes = mutableMapOf<ContentHashWrapper, CasEnvelope>()
    private val adapters = mutableMapOf<ProjectionBackend, ProjectionAdapter>()
    private val projectionIndex = mutableMapOf<Pair<ContentHashWrapper, ProjectionBackend>, String>()
    private val manifests = mutableMapOf<ContentHashWrapper, ChunkManifest>()
    private var policy: ProjectionPolicy = ProjectionPolicy.default()

    companion object {
        fun create(): LazyProjectionGateway = LazyProjectionGateway()
    }

    /** Register or replace a backend adapter. */
    fun registerAdapter(adapter: ProjectionAdapter) {
        adapters[adapter.backend()] = adapter
    }

    /** Replace the projection policy. */
    fun setPolicy(newPolicy: ProjectionPolicy) {
        policy = newPolicy
    }

    /** Return the chunk manifest for a stored object, if present. */
    fun manifest(hash: ContentHash): ChunkManifest? {
        return manifests[ContentHashWrapper(hash)]
    }

    /** Store bytes once in canonical CAS. */
    fun put(bytes: ByteArray, mediaType: String): Result<PutResult> {
        val strategy = if (bytes.size <= SMALL_OBJECT_THRESHOLD) {
            ChunkStrategy.Inline
        } else {
            ChunkStrategy.FixedSize(chunkBytes = SMALL_OBJECT_THRESHOLD)
        }
        val manifest = ChunkManifest.fromBytes(bytes, strategy)

        val blob = ContentBlob.create(bytes)
        canonical.store(blob)

        manifests[ContentHashWrapper(blob.hash)] = manifest

        val envelope = CasEnvelope(
            algorithm = "sha2-256",
            size = blob.size.toLong(),
            mediaType = mediaType,
        )
        envelopes[ContentHashWrapper(blob.hash)] = envelope

        // Eager projection for registered backends in policy.
        for (backend in policy.eagerBackends) {
            // Best-effort: ignore errors from eager projection (adapter may not be registered).
            runCatching { project(blob.hash, backend) }
        }

        return Result.success(PutResult(hash = blob.hash, envelope = envelope))
    }

    /** Lazily project canonical bytes into a selected backend. */
    fun project(hash: ContentHash, backend: ProjectionBackend): Result<ProjectionRecord> {
        val hashWrapper = ContentHashWrapper(hash)
        projectionIndex[hashWrapper to backend]?.let { existing ->
            return Result.success(ProjectionRecord(
                backend = backend,
                hash = hash,
                locator = existing,
            ))
        }

        val blob = canonical.retrieve(hash)
            ?: return Result.failure(IllegalStateException("canonical object not found for hash ${hash.toHex()}"))

        val adapter = adapters[backend]
            ?: return Result.failure(IllegalStateException("projection adapter not registered: ${backend.asStr()}"))

        val locatorResult = adapter.project(hash, blob.data)
        return locatorResult.mapCatching { locator ->
            projectionIndex[hashWrapper to backend] = locator
            ProjectionRecord(
                backend = backend,
                hash = hash,
                locator = locator,
            )
        }
    }

    /** Resolve bytes from canonical storage first, then fall back to projected backends. */
    fun get(hash: ContentHash, fallbackOrder: List<ProjectionBackend>): Result<ByteArray?> {
        canonical.retrieve(hash)?.let { blob ->
            return Result.success(blob.data)
        }

        for (backend in fallbackOrder) {
            val hashWrapper = ContentHashWrapper(hash)
            val locator = projectionIndex[hashWrapper to backend] ?: continue

            val adapter = adapters[backend] ?: continue

            val fetchResult = adapter.fetch(locator)
            val bytes = fetchResult.getOrNull() ?: continue

            if (digest(bytes).contentEquals(hash)) {
                return Result.success(bytes)
            }
        }

        return Result.success(null)
    }

    fun envelope(hash: ContentHash): CasEnvelope? {
        return envelopes[ContentHashWrapper(hash)]
    }
}

/** Convert ByteArray to hex string */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
