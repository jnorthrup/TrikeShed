package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.collections.associative.FunnelHashIndex

/**
 * Line CAS taxonomy.
 *
 * Every text line becomes:
 *   1. trim()
 *   2. ContentId = sha256(trimmed UTF-8)          — pure content identity
 *   3. NeighborStamp = prevHex‖nextHex             — 2 hex chars (1 byte) each side
 *   4. LinkedKey = stamp + ":" + contentHex        — context-bound identity
 *
 * A document is a linear [LineSpine] = Series&lt;LineNode&gt;. The spine itself has a
 * [spineCid] = CAS over the ordered linked keys — a taxonomy fingerprint of the
 * whole document under this line algebra.
 *
 * Why neighbor stamps cut false positives on link-matched CAS:
 *   - Content-only match: same boilerplate line anywhere (import, brace, blank-ish).
 *     Random FP rate ≈ 1 (any co-occurrence of identical text).
 *   - One-side neighbor (8 bits): FP ≈ 1/256 among content collisions.
 *   - Both-side [MatchGrade.LINKED] (16 bits): FP ≈ 1/65536 among content collisions.
 * Content SHA-256 collisions are negligible; the FP problem is *structural*
 * identity of reused lines, not cryptographic collision.
 *
 * Improvements over bare "hash each line":
 *   - Bidirectional stamp (prev+next), not one neighbor only
 *   - Edge sentinel [EDGE_HEX] for first/last lines (stable, not hash-of-empty)
 *   - Graded matches (LINKED / PARTIAL_* / CONTENT_ONLY) instead of boolean
 *   - Empty-after-trim lines dropped (noise)
 *   - Optional CasStore put so line bytes are recoverable from contentCid
 *   - Inverted [LineCasIndex] for O(1) candidate fetch by content hex
 *   - NEIGHBOR_HEX_LEN is a single constant — bump to 4 (2 bytes/side) if needed
 */
object LineCas {
    /** Hex chars taken from each neighbor's content CID. 2 = 1 byte = 8 bits. */
    const val NEIGHBOR_HEX_LEN: Int = 2

    /** Missing neighbor (document edge). Not a hash of empty string. */
    const val EDGE_HEX: String = "00"

    /**
     * Extract the neighbor hex prefix from a content CID.
     * Returns [EDGE_HEX] when [cid] is null (edge of spine).
     */
    fun neighborHex(cid: ContentId?): String =
        cid?.hex?.take(NEIGHBOR_HEX_LEN)?.padEnd(NEIGHBOR_HEX_LEN, '0') ?: EDGE_HEX

    /** Build a 2×NEIGHBOR_HEX_LEN stamp from prev/next content CIDs. */
    fun stamp(prev: ContentId?, next: ContentId?): NeighborStamp =
        NeighborStamp(neighborHex(prev) + neighborHex(next))

    /** Trim + content-address one line. Empty-after-trim is still addressable. */
    fun contentOf(line: String): ContentId =
        ContentId.of(line.trim().encodeToByteArray())

    /**
     * Project text into a linear spine of content-addressed, neighbor-stamped lines.
     * Empty-after-trim lines are dropped so the taxonomy stays dense.
     */
    fun spine(text: String): LineSpine {
        val trimmed = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (trimmed.isEmpty()) return emptySpine()

        val cids = Array(trimmed.size) { i -> ContentId.of(trimmed[i].encodeToByteArray()) }
        return trimmed.size j { i: Int ->
            val prev = if (i > 0) cids[i - 1] else null
            val next = if (i < cids.lastIndex) cids[i + 1] else null
            LineNode(
                contentCid = cids[i],
                stamp = stamp(prev, next),
                ordinal = i,
            )
        }
    }

    /**
     * Same as [spine], but CAS-puts each trimmed line so contentCid is recoverable.
     * Idempotent under content addressing (same bytes → same CID).
     */
    fun spineInto(cas: CasStore, text: String): LineSpine {
        val trimmed = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return ingestLines(cas, trimmed)
    }

    /**
     * Ingest known trimmed lines: CAS-put each, return spine with neighbor stamps.
     * Prefer this when you already hold the line list and want recoverable blobs.
     */
    fun ingestLines(cas: CasStore, lines: List<String>): LineSpine {
        val trimmed = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmed.isEmpty()) return emptySpine()
        val cids = Array(trimmed.size) { i -> cas.put(trimmed[i].encodeToByteArray()) }
        return trimmed.size j { i: Int ->
            val prev = if (i > 0) cids[i - 1] else null
            val next = if (i < cids.lastIndex) cids[i + 1] else null
            LineNode(cids[i], stamp(prev, next), i)
        }
    }

    /**
     * Document-level taxonomy fingerprint: CAS over ordered linked keys.
     * Two documents with the same line sequence (content + neighborhoods)
     * share a spineCid even if source formatting differed before trim.
     */
    fun spineCid(spine: LineSpine): ContentId {
        if (spine.size == 0) return ContentId.of(ByteArray(0))
        val bytes = buildString(spine.size * 72) {
            for (i in 0 until spine.size) {
                if (i > 0) append('\n')
                append(spine[i].linkedKey)
            }
        }.encodeToByteArray()
        return ContentId.of(bytes)
    }

    /**
     * Grade a pairwise content match. Null when content CIDs differ.
     *
     * LINKED is the taxonomic win: same line text *and* same neighborhood
     * fingerprint — structural reuse, not just boilerplate coincidence.
     */
    fun matchGrade(a: LineNode, b: LineNode): MatchGrade? {
        if (a.contentCid != b.contentCid) return null
        val prevOk = a.stamp.prevHex == b.stamp.prevHex
        val nextOk = a.stamp.nextHex == b.stamp.nextHex
        return when {
            prevOk && nextOk -> MatchGrade.LINKED
            prevOk -> MatchGrade.PARTIAL_PREV
            nextOk -> MatchGrade.PARTIAL_NEXT
            else -> MatchGrade.CONTENT_ONLY
        }
    }

    /**
     * Count spine-to-spine overlap by match grade. Returns counts for
     * LINKED / PARTIAL / CONTENT_ONLY (partial = prev or next alone).
     *
     * O(n·m) naive; for large docs use [LineCasIndex].
     */
    fun overlapCounts(a: LineSpine, b: LineSpine): OverlapCounts {
        if (a.size == 0 || b.size == 0) return OverlapCounts.ZERO
        var linked = 0
        var partial = 0
        var content = 0
        for (i in 0 until a.size) {
            val na = a[i]
            for (j in 0 until b.size) {
                when (matchGrade(na, b[j])) {
                    MatchGrade.LINKED -> linked++
                    MatchGrade.PARTIAL_PREV, MatchGrade.PARTIAL_NEXT -> partial++
                    MatchGrade.CONTENT_ONLY -> content++
                    null -> Unit
                }
            }
        }
        return OverlapCounts(linked, partial, content)
    }

    /**
     * Score ∈ [0,1] blending match grades. LINKED weighs most — the taxonomic
     * signal the neighbor stamp exists to isolate.
     */
    fun proximity(a: LineSpine, b: LineSpine): Double {
        val o = overlapCounts(a, b)
        val denom = maxOf(a.size, b.size).toDouble().coerceAtLeast(1.0)
        // Weights: linked=1.0, partial=0.45, content-only=0.12
        val raw = o.linked * 1.0 + o.partial * 0.45 + o.contentOnly * 0.12
        return (raw / denom).coerceIn(0.0, 1.0)
    }

    private fun emptySpine(): LineSpine = 0 j { _: Int ->
        error("empty LineSpine has no elements")
    }
}

/**
 * 2×[LineCas.NEIGHBOR_HEX_LEN] lowercase hex stamp: prev prefix ‖ next prefix.
 * Length is always even; first half = prev, second half = next.
 */
@JvmInline
value class NeighborStamp(val hex: String) {
    init {
        require(hex.length == LineCas.NEIGHBOR_HEX_LEN * 2) {
            "NeighborStamp must be ${LineCas.NEIGHBOR_HEX_LEN * 2} hex chars, got ${hex.length}"
        }
        require(hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "NeighborStamp must be lowercase hex"
        }
    }

    val prevHex: String get() = hex.substring(0, LineCas.NEIGHBOR_HEX_LEN)
    val nextHex: String get() = hex.substring(LineCas.NEIGHBOR_HEX_LEN)

    override fun toString(): String = hex
}

/**
 * One line in a [LineSpine].
 *
 * [contentCid] — pure trim→CAS identity (dedup / stringpool).
 * [stamp] — neighbor context that binds the line into local structure.
 * [linkedKey] — stamp:contentHex compact identity for indexes and spineCid.
 */
data class LineNode(
    val contentCid: ContentId,
    val stamp: NeighborStamp,
    val ordinal: Int,
) {
    val linkedKey: String get() = "${stamp.hex}:${contentCid.hex}"
    val prevHex: String get() = stamp.prevHex
    val nextHex: String get() = stamp.nextHex
}

/** Linear sequence of line nodes — a document under the Line CAS taxonomy. */
typealias LineSpine = Series<LineNode>

/** How strongly two same-content lines share neighborhood context. */
enum class MatchGrade {
    /** Both neighbor prefixes match — structural link, lowest FP rate. */
    LINKED,

    /** Only previous-neighbor prefix matches. */
    PARTIAL_PREV,

    /** Only next-neighbor prefix matches. */
    PARTIAL_NEXT,

    /** Content CID match only — boilerplate / high FP. */
    CONTENT_ONLY,
    ;

    /** Strength for threshold filters: LINKED > PARTIAL_* > CONTENT_ONLY. */
    val strength: Int
        get() = when (this) {
            LINKED -> 3
            PARTIAL_PREV, PARTIAL_NEXT -> 2
            CONTENT_ONLY -> 1
        }

    /** True when this grade meets or exceeds [min] (PARTIAL sides are equal). */
    fun meets(min: MatchGrade): Boolean = strength >= min.strength
}

data class OverlapCounts(
    val linked: Int = 0,
    val partial: Int = 0,
    val contentOnly: Int = 0,
) {
    val total: Int get() = linked + partial + contentOnly

    companion object {
        val ZERO = OverlapCounts()
    }
}

/**
 * One hit from inverted-index link matching.
 * [docCid] is the spineCid of the document that owns [node].
 */
data class LinkHit(
    val grade: MatchGrade,
    val docCid: ContentId,
    val node: LineNode,
)

/**
 * Inverted index: contentCid.hex → locations (doc spineCid + LineNode).
 *
 * Lookup by content is O(1) to the candidate list; neighbor stamps grade
 * each candidate so callers can demand [MatchGrade.LINKED] and reject the
 * long tail of boilerplate collisions.
 */
class LineCasIndex {
    private val byContent = linkedMapOf<String, MutableList<Join<ContentId, LineNode>>>()
    private val docs = linkedMapOf<String, LineSpine>()
    internal var funnel: FunnelHashIndex<String>? = null

    val documentCount: Int get() = docs.size
    val contentKeyCount: Int get() = byContent.size

    /** Ingest text; returns document spineCid. */
    fun ingest(text: String): ContentId {
        val s = LineCas.spine(text)
        return ingestSpine(s)
    }

    /** Ingest a pre-built spine; returns spineCid. */
    fun ingestSpine(spine: LineSpine): ContentId {
        val doc = LineCas.spineCid(spine)
        docs[doc.hex] = spine
        for (i in 0 until spine.size) {
            val n = spine[i]
            byContent.getOrPut(n.contentCid.hex) { mutableListOf() }.add(doc j n)
        }
        funnel = FunnelHashIndex.build(byContent.keys.toList().toSeries(), 0L)
        return doc
    }

    /**
     * Find all indexed nodes matching [probe]'s content, graded by neighbor stamp.
     * [minGrade] filters: LINKED only returns full structural hits; CONTENT_ONLY
     * returns every content collision.
     */
    fun linkMatch(
        probe: LineNode,
        minGrade: MatchGrade = MatchGrade.LINKED,
    ): Series<LinkHit> {
        if (funnel?.contains(probe.contentCid.hex) == false) {
            return 0 j { _: Int -> error("empty") }
        }
        val candidates = byContent[probe.contentCid.hex].orEmpty()
        if (candidates.isEmpty()) return 0 j { _: Int -> error("empty") }

        val hits = ArrayList<LinkHit>(candidates.size)
        for (c in candidates) {
            val docCid = c.a
            val node = c.b
            val grade = LineCas.matchGrade(probe, node) ?: continue
            if (grade.meets(minGrade)) hits.add(LinkHit(grade, docCid, node))
        }
        return hits.size j { i: Int -> hits[i] }
    }

    /**
     * Cross-document link density for a probe spine against the whole index.
     * Returns Series of (docCid j OverlapCounts) for docs with any content hit.
     */
    fun linkDensity(probe: LineSpine): Series<Join<ContentId, OverlapCounts>> {
        var hasHits = false
        val f = funnel
        if (f != null) {
            for (i in 0 until probe.size) {
                if (f.contains(probe[i].contentCid.hex)) {
                    hasHits = true
                    break
                }
            }
            if (!hasHits) return 0 j { _: Int -> error("empty") }
        }

        val acc = linkedMapOf<String, IntArray>() // hex -> [linked, partial, content]
        for (i in 0 until probe.size) {
            val n = probe[i]
            val candidates = byContent[n.contentCid.hex] ?: continue
            for (c in candidates) {
                val docHex = c.a.hex
                val grade = LineCas.matchGrade(n, c.b) ?: continue
                val bucket = acc.getOrPut(docHex) { IntArray(3) }
                when (grade) {
                    MatchGrade.LINKED -> bucket[0]++
                    MatchGrade.PARTIAL_PREV, MatchGrade.PARTIAL_NEXT -> bucket[1]++
                    MatchGrade.CONTENT_ONLY -> bucket[2]++
                }
            }
        }
        val entries = acc.entries.toList()
        return entries.size j { i: Int ->
            val e = entries[i]
            val b = e.value
            ContentId("sha256:${e.key}") j OverlapCounts(b[0], b[1], b[2])
        }
    }

    fun spineOf(docCid: ContentId): LineSpine? = docs[docCid.hex]

    /**
     * Regional top-k density per aperture band.
     * Computes the residual density (novel content hits and overlap) grouped by
     * chunks matching the provided aperture zoom level, avoiding full probe loads.
     */
    fun residualDensity(probe: LineSpine, aperture: borg.trikeshed.collections.LineAperture): Series<Join<Int, Series<Join<ContentId, OverlapCounts>>>> {
        if (probe.size == 0) return 0 j { _: Int -> error("empty") }

        val regions = when (aperture) {
            borg.trikeshed.collections.LineAperture.L0 -> 1
            borg.trikeshed.collections.LineAperture.L1 -> 4
            borg.trikeshed.collections.LineAperture.L2 -> 16
            borg.trikeshed.collections.LineAperture.L3 -> 64
        }
        val chunks = minOf(regions, probe.size)

        val result: Series<Join<Int, Series<Join<ContentId, OverlapCounts>>>> = chunks j { i: Int ->
            val start = i * probe.size / chunks
            val end = (i + 1) * probe.size / chunks
            val chunk: Series<LineNode> = end - start j { j: Int -> probe[start + j] }
            val res = residualsOf(chunk)
            val density: Series<Join<ContentId, OverlapCounts>> = if (chunk.size > 0) {
                try { linkDensity(chunk) } catch (e: Exception) { 0 j { _: Int -> error("empty") } }
            } else {
                0 j { _: Int -> error("empty") }
            }
            res.size j density
        }
        return result as Series<Join<Int, Series<Join<ContentId, OverlapCounts>>>>
    }

    /**
     * Residual extraction for the Funnel N-way merge.
     * Returns nodes from the probe whose contentCid.hex are MISSES in the index.
     */
    fun residualsOf(probe: LineSpine): Series<LineNode> {
        val f = funnel ?: return probe

        var missCount = 0
        var missIndices = IntArray(minOf(probe.size, 16))
        for (i in 0 until probe.size) {
            if (!f.contains(probe[i].contentCid.hex)) {
                if (missCount == missIndices.size) {
                    missIndices = missIndices.copyOf(missIndices.size * 2)
                }
                missIndices[missCount++] = i
            }
        }

        val finalIndices = if (missCount == missIndices.size) missIndices else missIndices.copyOf(missCount)
        return missCount j { i: Int -> probe[finalIndices[i]] }
    }
}

enum class LineCasNeighbor {
    NEIGHBOR_PREV,
    NEIGHBOR_NEXT,
    NEIGHBOR_SIBLING,
    NEIGHBOR_PARENT,
    NEIGHBOR_CHILD,
    NEIGHBOR_UNKNOWN
}

data class LineCasEdge(
    val sourceCid: ContentId,
    val targetCid: ContentId,
    val relationship: LineCasNeighbor,
    val confidence: Double = 1.0
)

data class LineCasSpine(
    val contentCid: ContentId,
    val ordinal: Int,
    val linkedKey: String? = null
)

enum class LinkConfidence {
    CANDIDATE,
    PROVISIONAL,
    CONFIRMED
}

fun confidenceOf(grade: MatchGrade): LinkConfidence = when (grade) {
    MatchGrade.CONTENT_ONLY -> LinkConfidence.CANDIDATE
    MatchGrade.PARTIAL_PREV, MatchGrade.PARTIAL_NEXT -> LinkConfidence.PROVISIONAL
    MatchGrade.LINKED -> LinkConfidence.CONFIRMED
}

/**
 * Returns a log-ish spaced confidence score based on match grade.
 * Note: This score is a prior confidence, not a probability proof.
 */
fun rampScore(grade: MatchGrade): Double = when (grade) {
    MatchGrade.CONTENT_ONLY -> 0.12
    MatchGrade.PARTIAL_PREV, MatchGrade.PARTIAL_NEXT -> 0.45
    MatchGrade.LINKED -> 1.0
}
