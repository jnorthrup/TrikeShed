package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.LineCas
import borg.trikeshed.cas.LineCasSourceRanges
import borg.trikeshed.cas.SourceLine
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * What the facet layer has done. Counters describe work; they never influence an answer. [linesScored] is the number of
 * distinct lines a question had to score: with a warm index it is the size of the union of the question's postings, not
 * the size of the corpus.
 */
data class FacetCounters(
    val documentsIndexed: Int,
    val documentHits: Int,
    val spinesComputed: Int,
    val spineHits: Int,
    val featuresComputed: Int,
    val featureHits: Int,
    val indexesBuilt: Int,
    val indexHits: Int,
    val evictions: Int,
    val linesScored: Long,
)

/** Lexical features of one distinct line: a pure function of its trimmed text under one policy revision. */
internal class LineFeatures(val tokens: List<String>, val action: Boolean, val constraint: Boolean)

/**
 * One document's line structure under LineCas, a pure function of its exact bytes: [lines] is
 * [LineCasSourceRanges.of] (spine ordinal to original range) and `contents[i]` is `LineCas.contentOf` of line `i`, the
 * identity LineCas gave that line. Keyed by the document's content id; nothing here can go stale.
 */
internal class DocumentFacet(val lines: Series<SourceLine>, val contents: Array<ContentId>) {
    val size: Int get() = contents.size

    companion object {
        fun of(text: String): DocumentFacet {
            val lines = LineCasSourceRanges.of(text)
            return DocumentFacet(lines, Array(lines.size) { i -> LineCas.contentOf(text.substring(lines[i].text.start, lines[i].text.end)) })
        }
    }
}

/** A growable list of ints without boxing. */
internal class IntBuf(capacity: Int = 4) {
    private var items = IntArray(maxOf(1, capacity))
    var size = 0
        private set

    fun add(value: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    fun last(): Int = items[size - 1]
    operator fun get(index: Int): Int = items[index]
    fun toArray(): IntArray = items.copyOf(size)
}

/**
 * The inverted index of one snapshot, rebuildable from its documents. Lines are identified by their DISTINCT content
 * (LineCas's identity), numbered in order of first appearance; `lineId[d][i]` is the number of line `i` of document `d`.
 *
 * - [features] holds the features of each distinct line.
 * - [postings] maps a term to the ascending numbers of the distinct lines containing it, so its size is the term's
 *   document frequency in the sense the scorer has always used.
 * - Occurrences of distinct line `id` are `occDocument/occOrdinal[occStart[id] until occStart[id + 1]]`, ascending.
 */
internal class SnapshotIndex(
    val documents: List<DocumentFacet>,
    val lineId: Array<IntArray>,
    val features: Array<LineFeatures>,
    val occStart: IntArray,
    val occDocument: IntArray,
    val occOrdinal: IntArray,
    val postings: Map<String, IntArray>,
    val avgLen: Double,
    val totalLines: Int,
) {
    val distinct: Int get() = features.size
}

/**
 * Rebuildable, content-addressed memory for projections. Every entry is a pure function of its key, so losing or
 * evicting any of it costs time and never changes an answer: that is the contract the differential test against the plain
 * algorithm holds this layer to.
 *
 *  - document facets, by the document's exact content id (a [ContextDocument]'s `cid` names its `bytes`; acquisition
 *    establishes that, and this layer relies on it);
 *  - spine ids, by content id, so a document already verified is not normalized and hashed again on every acquire;
 *  - line features, by LineCas content id, shared by every document and revision that contains the line;
 *  - snapshot indexes, by the ordered content ids of a snapshot's documents.
 *
 * Bounds are in lines (documents, indexes) or entries (features, spines, interned terms). An entry heavier than its bound
 * is built for the request and not retained. Terms are interned so that a vocabulary is stored once, not once per line;
 * past [maxTerms] new terms are simply not shared. A given key is built by one caller; concurrent callers of the same key wait for it.
 * Instances are bound to one policy revision: the projector refuses a mismatch.
 */
class ProjectionFacets(
    val policy: String = BreadcrumbProjector.POLICY,
    maxDocumentLines: Int = 262_144,
    private val maxFeatureLines: Int = 131_072,
    maxIndexedLines: Int = 262_144,
    maxSpines: Int = 8_192,
    private val maxTerms: Int = 262_144,
) {
    init {
        require(maxDocumentLines >= 0 && maxFeatureLines >= 0 && maxIndexedLines >= 0 && maxSpines >= 0 && maxTerms >= 0) { "bounds cannot be negative" }
    }

    /** Test seam, called as a build starts ("document", "spine" or "index"). Never set in production. */
    internal var buildObserver: ((String) -> Unit)? = null

    private class Memo<V : Any>(private val capacity: Int, private val weigh: (V) -> Int, private val started: () -> Unit) {
        private val lock = SynchronizedObject()
        private val entries = LinkedHashMap<ContentId, V>()
        private val builders = HashMap<ContentId, SynchronizedObject>()
        private var weight = 0L
        private var hits = 0
        private var builds = 0
        private var evictions = 0

        fun hits(): Int = synchronized(lock) { hits }
        fun builds(): Int = synchronized(lock) { builds }
        fun evictions(): Int = synchronized(lock) { evictions }

        private fun touch(key: ContentId): V? {
            val held = entries.remove(key) ?: return null
            entries[key] = held
            hits++
            return held
        }

        fun get(key: ContentId, build: () -> V): V {
            synchronized(lock) { touch(key) }?.let { return it }
            val builder = synchronized(lock) { builders.getOrPut(key) { SynchronizedObject() } }
            return synchronized(builder) {
                val raced = synchronized(lock) { touch(key) }
                if (raced != null) raced else {
                    started()
                    val built = try {
                        build()
                    } catch (failure: Throwable) {
                        synchronized(lock) { builders.remove(key) }
                        throw failure
                    }
                    synchronized(lock) {
                        builds++
                        val w = weigh(built)
                        if (w <= capacity) {
                            entries[key] = built
                            weight += w
                            while (weight > capacity) {
                                val eldest = entries.entries.first()
                                weight -= weigh(eldest.value)
                                entries.remove(eldest.key)
                                evictions++
                            }
                        }
                        builders.remove(key)
                    }
                    built
                }
            }
        }
    }

    private val documents = Memo<DocumentFacet>(maxDocumentLines, { it.size }) { buildObserver?.invoke("document") }
    private val spines = Memo<ContentId>(maxSpines, { 1 }) { buildObserver?.invoke("spine") }
    private val indexes = Memo<SnapshotIndex>(maxIndexedLines, { it.totalLines }) { buildObserver?.invoke("index") }

    private val featureLock = SynchronizedObject()
    private val features = LinkedHashMap<ContentId, LineFeatures>()
    private val terms = HashMap<String, String>()
    private var featureHits = 0
    private var featuresComputed = 0
    private var featureEvictions = 0
    private var linesScored = 0L

    fun counters(): FacetCounters {
        val f = synchronized(featureLock) { listOf(featuresComputed.toLong(), featureHits.toLong(), featureEvictions.toLong(), linesScored) }
        return FacetCounters(
            documentsIndexed = documents.builds(), documentHits = documents.hits(),
            spinesComputed = spines.builds(), spineHits = spines.hits(),
            featuresComputed = f[0].toInt(), featureHits = f[1].toInt(),
            indexesBuilt = indexes.builds(), indexHits = indexes.hits(),
            evictions = documents.evictions() + spines.evictions() + indexes.evictions() + f[2].toInt(),
            linesScored = f[3],
        )
    }

    /** The spine id of verified [bytes] whose content id is [content]; computed once per content id while retained. */
    internal fun spineOf(content: ContentId, bytes: ByteArray): ContentId = spines.get(content) { ManifestDocument.spineOf(bytes) }

    internal fun document(content: ContentId, text: () -> String): DocumentFacet = documents.get(content) { DocumentFacet.of(text()) }

    internal fun noteScored(lines: Int) {
        synchronized(featureLock) { linesScored += lines }
    }

    /** The index of [snapshot], built from its documents' facets on first use and then reused for any question. */
    internal fun index(snapshot: ContextSnapshot): SnapshotIndex {
        val key = StringBuilder(policy).also { sb -> for (i in 0 until snapshot.documents.size) sb.append('\n').append(snapshot.documents[i].cid.value) }
        return indexes.get(ContentId.of(key.toString().encodeToByteArray())) {
            val facets = List(snapshot.documents.size) { d ->
                val doc = snapshot.documents[d]
                document(doc.cid) { doc.text }
            }
            build(facets) { d -> snapshot.documents[d].text }
        }
    }

    /** Features for distinct line contents, computed only for those not already held. [trimmed] reads line `i`'s text. */
    private fun featuresFor(contents: List<ContentId>, trimmed: (Int) -> String): Array<LineFeatures> {
        val out = arrayOfNulls<LineFeatures>(contents.size)
        var missing: IntBuf? = null
        synchronized(featureLock) {
            for (i in contents.indices) {
                val held = features.remove(contents[i])
                if (held != null) {
                    features[contents[i]] = held
                    out[i] = held
                    featureHits++
                } else (missing ?: IntBuf().also { missing = it }).add(i)
            }
        }
        val absent = missing
        if (absent != null) {
            val fresh = Array(absent.size) { k -> BreadcrumbProjector.featuresOf(trimmed(absent[k])) }
            synchronized(featureLock) {
                featuresComputed += fresh.size
                for (k in 0 until absent.size) {
                    val i = absent[k]
                    val use = features[contents[i]] ?: canonical(fresh[k]).also { features[contents[i]] = it }
                    out[i] = use
                }
                while (features.size > maxFeatureLines) {
                    features.remove(features.entries.first().key)
                    featureEvictions++
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return out as Array<LineFeatures>
    }

    /** The same features with every term replaced by the shared copy of it. Caller holds [featureLock]. */
    private fun canonical(line: LineFeatures): LineFeatures {
        val shared = ArrayList<String>(line.tokens.size)
        for (token in line.tokens) {
            val held = terms[token]
            if (held != null) shared.add(held) else {
                if (terms.size < maxTerms) terms[token] = token
                shared.add(token)
            }
        }
        return LineFeatures(shared, line.action, line.constraint)
    }

    private fun build(documents: List<DocumentFacet>, textOf: (Int) -> String): SnapshotIndex {
        val ids = HashMap<ContentId, Int>()
        val order = ArrayList<ContentId>()
        val firstDocument = IntBuf()
        val firstOrdinal = IntBuf()
        val lineId = Array(documents.size) { d -> IntArray(documents[d].size) }
        var totalLines = 0
        for (d in documents.indices) {
            val facet = documents[d]
            for (i in 0 until facet.size) {
                lineId[d][i] = ids.getOrPut(facet.contents[i]) {
                    order.add(facet.contents[i]); firstDocument.add(d); firstOrdinal.add(i)
                    order.size - 1
                }
            }
            totalLines += facet.size
        }

        // Documents are decoded only if a line of theirs is missing from the feature cache.
        val decoded = arrayOfNulls<String>(documents.size)
        val lineFeatures = featuresFor(order) { id ->
            val d = firstDocument[id]
            val text = decoded[d] ?: textOf(d).also { decoded[d] = it }
            val range = documents[d].lines[firstOrdinal[id]].text
            text.substring(range.start, range.end)
        }

        val occStart = IntArray(order.size + 1)
        for (d in documents.indices) for (i in lineId[d].indices) occStart[lineId[d][i] + 1]++
        for (id in order.indices) occStart[id + 1] += occStart[id]
        val cursor = occStart.copyOf()
        val occDocument = IntArray(totalLines)
        val occOrdinal = IntArray(totalLines)
        for (d in documents.indices) for (i in lineId[d].indices) {
            val k = cursor[lineId[d][i]]++
            occDocument[k] = d
            occOrdinal[k] = i
        }

        val building = HashMap<String, IntBuf>()
        var tokenSum = 0L
        for (id in lineFeatures.indices) {
            val tokens = lineFeatures[id].tokens
            tokenSum += tokens.size
            for (t in tokens) {
                val postings = building.getOrPut(t) { IntBuf() }
                if (postings.size == 0 || postings.last() != id) postings.add(id)
            }
        }
        val postings = HashMap<String, IntArray>(building.size * 2)
        for ((term, list) in building) postings[term] = list.toArray()

        val avgLen = if (order.isEmpty()) 1.0 else maxOf(1.0, tokenSum.toDouble() / order.size)
        return SnapshotIndex(documents, lineId, lineFeatures, occStart, occDocument, occOrdinal, postings, avgLen, totalLines)
    }
}
