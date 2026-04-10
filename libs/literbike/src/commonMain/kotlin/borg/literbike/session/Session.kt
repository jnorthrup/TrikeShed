/**
 * Port of /Users/jim/work/literbike/src/session/mod.rs
 *
 * Session management with Pijul-backed patch recording.
 *
 * NOTE: The Rust version uses `libpijul` — a Rust-native version control library
 * with in-memory working copies, change stores, and pristine databases.
 * There is no direct Kotlin equivalent. The structure, function signatures,
 * and error-handling flow are translated faithfully. The actual libpijul
 * operations are abstracted behind the `PijulBackend` interface so that a
 * Kotlin implementation can be provided.
 */
package borg.literbike.session

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// ============================================================================
// Pijul Backend Abstraction
// ============================================================================

/**
 * Abstracts the libpijul operations used by the session module.
 * Implement this interface to provide a Kotlin-native or FFI-backed
 * version control backend.
 */
interface PijulBackend {
    /** Create the "main" channel if it does not exist. */
    fun ensureMainChannel(): Result<Unit>

    /**
     * Add file contents to the in-memory working copy.
     * Returns the path that was added.
     */
    fun addFile(path: String, content: ByteArray): Result<Unit>

    /**
     * Track a file in the pristine DB.
     */
    fun trackFile(path: String, offset: Long): Result<Unit>

    /**
     * Record all changes and produce a change with the given message.
     * Returns the base-32 encoded hash of the resulting patch.
     */
    fun recordChange(message: String): Result<String>

    /**
     * Get all patch hashes in the channel, optionally starting after [fromHash].
     */
    fun getPatchLog(fromSerial: Long): Result<List<HashEntry>>

    /**
     * Unrecord (unapply) the patch with the given hash.
     */
    fun unrecord(hash: String): Result<Unit>

    /**
     * Parse a hash string into its internal representation.
     */
    fun parseHash(hashStr: String): Result<HashRepresentation>

    /**
     * Get the serial number of a given hash in the channel.
     */
    fun getSerialForHash(hash: HashRepresentation): Result<Long?>
}

/**
 * Mirrors Rust `Hash` type from libpijul.
 * FFI handle — opaque representation of a Pijul change hash.
 */
@Suppress("RESERVED_MEMBER_INSIDE_VALUE_CLASS")
@JvmInline
value class HashRepresentation(val raw: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is HashRepresentation && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()

    @OptIn(ExperimentalEncodingApi::class)
    fun toBase32(): String = Base64.encode(raw)

    companion object {
        @OptIn(ExperimentalEncodingApi::class)
        fun fromBase32(bytes: ByteArray): HashRepresentation? {
            // Attempt to decode; if input is not valid base64-like, return null
            return try {
                val decoded = Base64.decode(String(bytes))
                HashRepresentation(decoded)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Mirrors Rust log entry: `(_n, (serialized_hash, _merkle))`
 */
data class HashEntry(
    val serial: Long,
    val hash: HashRepresentation,
    val merkle: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is HashEntry && serial == other.serial && hash == other.hash && merkle.contentEquals(other.merkle)

    override fun hashCode(): Int {
        var result = serial.hashCode()
        result = 31 * result + hash.hashCode()
        result = 31 * result + merkle.contentHashCode()
        return result
    }
}

// ============================================================================
// SessionStore and SessionChannel
// ============================================================================

/**
 * Mirrors Rust struct: `pub struct SessionStore`
 *
 * Holds the in-memory working copy, change store, environment, and metadata.
 */
class SessionStore(
    internal val pijul: PijulBackend,
    val sessionId: String,
    var turnCount: Int = 0,
) {
    /**
     * Initialize the store by ensuring the "main" channel exists.
     * Mirrors the channel creation block in Rust `open_channel`.
     */
    fun initialize(): Result<Unit> = pijul.ensureMainChannel()
}

/**
 * Mirrors Rust struct: `pub struct SessionChannel` with manual Clone impl.
 *
 * Thread-safe session channel wrapping a shared SessionStore.
 */
class SessionChannel(
    val sessionId: String,
    private val store: SessionStore,
    private val lock: ReentrantLock = ReentrantLock(),
) {

    /**
     * Mirrors Rust `impl Clone for SessionChannel` — shares the same store reference.
     */
    fun copy(): SessionChannel = SessionChannel(sessionId, store, lock)

    /**
     * Execute a block with exclusive access to the store.
     */
    internal fun <T> withStore(block: (SessionStore) -> T): T = lock.withLock {
        block(store)
    }
}

// ============================================================================
// Public Functions
// ============================================================================

/**
 * Mirrors Rust fn: `pub fn open_channel(session_id: &str) -> Result<SessionChannel, String>`
 *
 * Open or create a pijul channel for a session (in-memory).
 */
fun openChannel(sessionId: String, pijul: PijulBackend): Result<SessionChannel> {
    val store = SessionStore(
        pijul = pijul,
        sessionId = sessionId,
        turnCount = 0,
    )

    // Create the "main" channel so it exists before any operations.
    store.initialize().onFailure { e ->
        return Result.failure(Exception("PijulBackend.ensureMainChannel failed: ${e.message}"))
    }

    return Result.success(SessionChannel(sessionId, store))
}

/**
 * Mirrors Rust fn: `pub fn record_turn(channel: &SessionChannel, role: &str, content: &str) -> Result<String, String>`
 *
 * Record a turn (user or assistant message) as a patch.
 * Returns the base-32 encoded hash of the patch.
 */
fun recordTurn(channel: SessionChannel, role: String, content: String): Result<String> {
    return channel.withStore { store ->
        // Write the file contents into the in-memory working copy.
        val path = "turns/turn_${store.turnCount}"
        val fileContent = "$role: $content".encodeToByteArray()
        store.pijul.addFile(path, fileContent).onFailure { e ->
            return@withStore Result.failure(Exception("addFile failed: ${e.message}"))
        }

        // Begin tracking the file in the pristine DB.
        store.pijul.trackFile(path, 0).onFailure { e ->
            return@withStore Result.failure(Exception("trackFile failed: ${e.message}"))
        }

        // Record the change using the record pattern from libpijul.
        val message = "turn ${store.turnCount}: $role"
        store.pijul.recordChange(message).onFailure { e ->
            return@withStore Result.failure(Exception("recordChange failed: ${e.message}"))
        }.onSuccess { hash ->
            store.turnCount += 1
            return@withStore Result.success(hash)
        }

        Result.failure(Exception("recordChange returned neither success nor failure"))
    }
}

/**
 * Mirrors Rust fn: `pub fn patch_feed(channel: &SessionChannel, from_hash: Option<&str>) -> Result<Vec<String>, String>`
 *
 * Get all patch hashes in the channel, optionally starting after [fromHash].
 */
fun patchFeed(channel: SessionChannel, fromHash: String?): Result<List<String>> {
    return channel.withStore { store ->
        // Determine the starting serial number (0 = from beginning).
        val fromN: Long = if (fromHash != null) {
            val hash = store.pijul.parseHash(fromHash)
                .getOrElse { return@withStore Result.failure(Exception("invalid hash: $fromHash")) }

            store.pijul.getSerialForHash(hash).getOrElse { e ->
                return@withStore Result.failure(Exception("getSerialForHash failed: ${e.message}"))
            }?.let { serial -> serial + 1 }
                ?: return@withStore Result.failure(Exception("hash not found in channel: $fromHash"))
        } else {
            0L
        }

        val logResult = store.pijul.getPatchLog(fromN).getOrElse { e ->
            return@withStore Result.failure(Exception("getPatchLog failed: ${e.message}"))
        }

        val hashes = logResult.map { entry -> entry.hash.toBase32() }
        Result.success(hashes)
    }
}

/**
 * Mirrors Rust fn: `pub fn revert_turn(channel: &SessionChannel, hash_str: &str) -> Result<(), String>`
 *
 * Revert a turn by unapplying its patch.
 */
fun revertTurn(channel: SessionChannel, hashStr: String): Result<Unit> {
    return channel.withStore { store ->
        val hash = store.pijul.parseHash(hashStr)
            .getOrElse { return@withStore Result.failure(Exception("invalid hash: $hashStr")) }

        // In the Rust code the hash is passed as a reference to unrecord.
        store.pijul.unrecord(hash.toBase32()).getOrElse { e ->
            return@withStore Result.failure(Exception("unrecord failed: ${e.message}"))
        }

        Result.success(Unit)
    }
}
