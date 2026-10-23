package borg.trikeshed.cas

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.intp.*

/** One ranked hit: the document's slot, the row key it was indexed under, and its score. */
data class PostingHit(val slot: Int, val key: Any?, val score: Double)

/**
 * One field's lexical postings and document directory, exact and in memory: Evoke's L0 shape.
 *
 * A slot is one document-version incarnation, not the row id: re-indexing a key retires its slot
 * and appends a new one (Evoke's UPSERT / RETIRE directory). Retired slots stay in the posting runs
 * until a fold reclaims them, but N, df and avgdl count live slots only, so every score equals a
 * fresh index over the visible rows. Postings carry `(slot, tf)` and no positions.
 */
class TermPostings(val analyzer: Analyzer = Analyzer(), val params: Bm25Params = Bm25Params()) {
    val lexicon = AtomLexicon()

    private var slotCount = 0
    private var docLen = IntArray(16)
    private var live = BooleanArray(16)
    private val slotKeys = ArrayList<Any?>()
    private val slotAtoms = ArrayList<IntArray>()
    private val postSlots = ArrayList<IntArrayList>()
    private val postTfs = ArrayList<IntArrayList>()
    private var df = IntArray(16)
    private val slotOfKey = HashMap<Any?, Int>()
    private var totalLength = 0L

    /** Live documents (N). */
    var documents = 0
        private set

    /** Every slot ever appended, live or retired. */
    val slots: Int get() = slotCount

    fun upsert(key: Any?, text: CharSequence): Int = upsertTokens(key, analyzer.tokens(text))

    /** Indexes [tokens] for [key], retiring the key's previous slot; answers the new slot. */
    fun upsertTokens(key: Any?, tokens: List<String>): Int {
        slotOfKey[key]?.let(::retire)
        val slot = slotCount++
        if (slot == docLen.size) { docLen = docLen.copyOf(slot * 2); live = live.copyOf(slot * 2) }
        val counts = LinkedHashMap<Int, Int>()
        for (token in tokens) lexicon.intern(token).id.let { counts[it] = (counts[it] ?: 0) + 1 }
        for ((atom, tf) in counts) {
            while (postSlots.size <= atom) { postSlots += IntArrayList(); postTfs += IntArrayList() }
            if (atom >= df.size) df = df.copyOf(maxOf(atom + 1, df.size * 2))
            postSlots[atom].add(slot)
            postTfs[atom].add(tf)
            df[atom]++
        }
        docLen[slot] = tokens.size
        live[slot] = true
        slotKeys += key
        slotAtoms += counts.keys.toIntArray()
        documents++
        totalLength += tokens.size
        slotOfKey[key] = slot
        return slot
    }

    /** RETIRE: the slot stops matching and counting; a later [upsertTokens] of its key appends anew. */
    fun retire(slot: Int) {
        if (!live[slot]) return
        live[slot] = false
        for (atom in slotAtoms[slot]) df[atom]--
        documents--
        totalLength -= docLen[slot]
        if (slotOfKey[slotKeys[slot]] == slot) slotOfKey.remove(slotKeys[slot])
    }

    fun remove(key: Any?): Boolean = slotOfKey[key]?.let { retire(it); true } ?: false

    fun topK(query: CharSequence, k: Int): List<PostingHit> = topKTokens(analyzer.tokens(query), k)

    /** Exact top-k by score descending, then slot ascending; a repeated query token adds its weight again. */
    fun topKTokens(query: List<String>, k: Int): List<PostingHit> {
        val (hits, count, acc) = accumulate(query) ?: return emptyList()
        hits.sort(0, count)
        val scores = DoubleArray(count) { acc[hits[it]] }
        return borg.trikeshed.lib.doublep.topK(scores, count, k, descending = true).map { i ->
            hits[i].let { slot -> PostingHit(slot, slotKeys[slot], scores[i]) }
        }
    }

    /** Every matching live document's score by key: what a per-row score marker reads. */
    fun scores(query: CharSequence): Map<Any?, Double> {
        val (hits, count, acc) = accumulate(analyzer.tokens(query)) ?: return emptyMap()
        return HashMap<Any?, Double>(count * 2).apply { for (i in 0 until count) put(slotKeys[hits[i]], acc[hits[i]]) }
    }

    private fun accumulate(query: List<String>): Triple<IntArray, Int, DoubleArray>? {
        if (documents == 0) return null
        val weights = LinkedHashMap<Int, Int>()
        for (token in query) lexicon.find(token)?.let { weights[it.id] = (weights[it.id] ?: 0) + 1 }
        val n = documents.toDouble()
        val lavg = totalLength.toDouble() / documents
        val acc = DoubleArray(slotCount)
        val touched = BooleanArray(slotCount)
        val hits = IntArray(slotCount)
        var count = 0
        for ((atom, weight) in weights) {
            if (atom >= df.size || df[atom] == 0) continue
            val idf = bm25Idf(params.idfMethod, df[atom].toDouble(), n)
            val run = postSlots[atom]
            count = bm25Accumulate(run.array, postTfs[atom].array, run.size, docLen, lavg, idf, weight.toDouble(), params, live, acc, touched, hits, count)
        }
        return Triple(hits, count, acc)
    }

    /** The live postings as a table: atom, term, slot, tf (Evoke's `lexical_postings`). */
    fun segment(): ColumnCursor {
        val atoms = IntArrayList(); val slots = IntArrayList(); val tfs = IntArrayList()
        for (atom in postSlots.indices) {
            val run = postSlots[atom]
            for (i in 0 until run.size) if (live[run[i]]) { atoms.add(atom); slots.add(run[i]); tfs.add(postTfs[atom][i]) }
        }
        val n = atoms.size
        return ColumnCursor.of(
            arrayOf(ColumnMeta("atom", IOMemento.IoLong), ColumnMeta("term", IOMemento.IoString), ColumnMeta("slot", IOMemento.IoLong), ColumnMeta("tf", IOMemento.IoLong)),
            arrayOf(
                DenseColumn(LongArray(n) { atoms[it].toLong() }),
                DenseColumn(DictColumn.of(Array(n) { lexicon.term(AtomId(atoms[it])) })),
                DenseColumn(LongArray(n) { slots[it].toLong() }),
                DenseColumn(LongArray(n) { tfs[it].toLong() }),
            ),
        )
    }
}
