package borg.trikeshed.couch.pijul

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Pijul's fundamental unit of change — a named, invertible, commutative patch.
 *
 * Algebraic invariants:
 *   - P2 ∘ P1 is defined only when P1 and P2 are "compatible" (no conflicting edges)
 *   - P1.invert() ∘ P1 = identity on any pristine
 *   - P1 commute P2 is defined only when neither depends on the other
 *
 * Patch is the fundamental value type — immutable, hash-identified, timestamped.
 * All patches are local-first; remote sync happens via the SyncEngine.
 */

/** Line-level operations in a patch. */
enum class LineOperation { Add, Delete, Move }

/** A single line in a file. */
data class Line(val text: String)

/** A hash identifier for a patch (32 bytes). */
data class PatchHash(
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is PatchHash && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()

    fun display(): String = base58Encode(bytes)

    companion object {
        fun of(vararg bytes: Byte) = PatchHash(byteArrayOf(*bytes))
        fun parse(text: String) = PatchHash(text.encodeToByteArray())
    }
}

/** A tag — a named, immutable pointer to a patch hash. */
data class Tag(
    val name: String,
    val hash: PatchHash,
    val timestamp: Long,
    val message: String,
)

/** A named, ordered sequence of patches. Channels are branch-equivalent in pijul. */
data class Channel(
    val name: String,
    val head: PatchHash,
    val patches: Set<PatchHash>,
    val graph: DependencyGraph,
)

/**
 * Core patch interface. Implementations can be local (recorded) or remote (received).
 *
 * The key operation is apply(pristine) → ApplyResult.
 * compose(other) and commute(other) are partial — they return null on conflict.
 */
interface Patch {
    val name: String
    val hash: PatchHash
    val timestamp: Long
    val dependsOn: Set<PatchHash>
    val isConflicted: Boolean

    /** Apply this patch to a pristine file map, returning the new state. */
    fun apply(pristine: Pristine): ApplyResult

    /** Return the inverse of this patch (for undo/revert). */
    fun invert(): Patch

    /** Compose two patches into one (for incremental sync). May return null on conflict. */
    infix fun compose(other: Patch): Patch?

    /** Commute this patch past another — returns null if they conflict. */
    infix fun commute(other: Patch): Patch?
}

/** Result of applying a patch. */
sealed class ApplyResult {
    data class Success(val newState: Pristine, val changedLines: List<DeltaLineEdit>) : ApplyResult()
    data class Conflict(val message: String, val conflictedEdges: List<GraphEdge>) : ApplyResult()
    data class Failure(val message: String) : ApplyResult()
}

/** File → list of lines mapping. Pristine is the known-good state before a patch. */
typealias Pristine = Map<Int, List<Line>>  // inode → lines

/** A dependency edge in the patch graph. */
data class GraphEdge(
    val inode: Int,
    val patch: PatchHash,
    val startLine: Int,
    val endLine: Int,
    val isPositive: Boolean,
) {
    fun invert(): GraphEdge = copy(isPositive = !isPositive)
}

/** A patch dependency graph — edges define which patches depend on which lines. */
class DependencyGraph(
    private val edges: Map<Int, List<GraphEdge>>,  // inode → edges
) {
    /** Add an edge to the graph. */
    fun add(edge: GraphEdge): DependencyGraph {
        val existing = edges[edge.inode] ?: emptyList()
        return DependencyGraph(edges + (edge.inode to existing + edge))
    }

    /** Return all edges touching a given inode. */
    fun edgesFor(inode: Int): List<GraphEdge> = edges[inode] ?: emptyList()

    /** Return all conflicting edge pairs for a proposed edge. */
    fun conflictsWith(edge: GraphEdge): List<GraphEdge> =
        edgesFor(edge.inode).filter { existing ->
            existing.patch != edge.patch &&
            existing.startLine == edge.startLine &&
            existing.endLine == edge.endLine &&
            existing.isPositive && edge.isPositive
        }

    companion object {
        fun empty(): DependencyGraph = DependencyGraph(emptyMap())
    }
}

/**
 * Repository — the central state object in pijul.
 *
 * Contains:
 *   - pristine: the file contents (inode → lines)
 *   - channels: named patch sequences (like branches)
 *   - localPatches: unrecorded patches waiting to be integrated
 *   - tags: named patch pointers
 *   - nextInode: the next available inode number
 */
class Repository(
    val pristine: Pristine,
    val channels: Map<String, Channel>,
    val localPatches: Set<Patch>,
    val tags: Map<String, Tag>,
    val nextInode: Int,
) {
    fun channel(name: String): Channel? = channels[name]
    fun localPatch(hash: PatchHash): Patch? = localPatches.find { it.hash == hash }

    /** Create a new channel with the given name, starting from `from` channel. */
    fun createChannel(name: String, from: Channel): Repository {
        val newChannel = from.copy(name = name)
        return Repository(pristine, channels = channels + (name to newChannel), localPatches, tags, nextInode)
    }

    /** Record a new local patch (not yet synced to any channel). */
    fun recordPatch(patch: Patch): Repository =
        Repository(pristine, channels, localPatches = localPatches + patch, tags, nextInode)

    companion object {
        fun empty(): Repository = Repository(
            pristine = emptyMap(),
            channels = mapOf("main" to Channel(
                name = "main",
                head = PatchHash.of(),
                patches = emptySet(),
                graph = DependencyGraph.empty(),
            )),
            localPatches = emptySet(),
            tags = emptyMap(),
            nextInode = 1,
        )
    }
}

// --- Utility ---

private val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

private fun base58Encode(data: ByteArray): String {
    if (data.isEmpty()) return ""
    var carry = 0
    val digitCount = (data.size * 8 + 5) / 6
    val result = IntArray(digitCount)
    for (byte in data) {
        carry = carry * 256 + (byte.toInt() and 0xff)
        var i = digitCount - 1
        while (carry >= 58 || i >= 0) {
            if (i >= 0) {
                result[i] += carry % 58
                carry = carry / 58
                i--
            } else break
        }
        carry /= 58
    }
    val sb = StringBuilder()
    var i = 0
    while (i < digitCount && result[i] == 0) i++
    while (i < digitCount) sb.append(BASE58_ALPHABET[result[i++]])
    return sb.toString()
}