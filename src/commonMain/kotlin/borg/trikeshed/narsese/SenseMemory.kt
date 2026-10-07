package borg.trikeshed.narsese

import borg.trikeshed.collections.FunnelHashMap
import borg.trikeshed.lib.*
import kotlin.math.roundToLong

/**
 * NARS memory of word senses, held as categorical rows ([SenseRow]) rather than one belief per class. A row is one
 * word's judgments in one book under one menu: per class offered, the mass the judge's probabilities gave it, and the
 * sources judged (a source is one asking of the word: a window of a section). A source counts once in its book's rows of
 * the word; re-judging a book replaces its rows ([replace]), so a re-read in other windows never counts its text twice.
 *
 * Rows of the books crafted in one [Locality] read as that locality's row of the word, wherever the book is placed now.
 * Where a word is read, its sense is that row with every other locality's row that cannot be told apart from it
 * ([SenseRow.homogeneous]) added in ([sense]); a locality with no row of its own reads the word only when all of its
 * rows agree.
 *
 * Bounded (AIKR): past [capacity] class cells the least attended, least confident sixteenth of the rows is forgotten
 * at once. Observation renews a row's attention; [tick] decays it. Evidence never decays.
 */
class SenseMemory(val capacity: Int = CAPACITY) {
    private val lemmas = ArrayList<String>()
    private val lemmaIds = FunnelHashMap<String, Int>()
    private val classNames = ArrayList<String>()
    private val classIds = FunnelHashMap<String, Int>()
    private val bookNames = ArrayList<String>()
    private val bookIds = FunnelHashMap<String, Int>()
    private var bookAt = IntArray(16)

    /** Rows, one per (lemma, book, menu): parallel columns, a dropped row keeps its index with no classes. */
    private var rowLemma = IntArray(64)
    private var rowBook = IntArray(64)
    private var rowAttention = FloatArray(64)
    private val rowClasses = ArrayList<IntArray>()
    private val rowPositive = ArrayList<LongArray>()
    /** Per row its sources, ascending: each the first eight bytes of the source's SHA-256 content id. */
    private val rowSources = ArrayList<LongArray>()
    private var rowCount = 0

    /** Lemma id → its rows (dense: lemma ids are 0 until lemmas.size); a lemma's rows in one book are the few whose [rowBook] is that book. */
    private var byLemma = arrayOfNulls<IntArray>(64)

    /** Class cells held: Σ K over the live rows. */
    var size = 0
        private set

    /** Live rows. */
    var rows = 0
        private set

    /** Judgments held. */
    var judgments = 0L
        private set

    init { intern(classIds, classNames, NONE) }

    /** (lemma, locality) contexts holding a row. */
    val contexts: Int get() = heldKeys().size

    private fun intern(ids: FunnelHashMap<String, Int>, names: ArrayList<String>, s: String): Int =
        ids.get(s) ?: names.size.also { ids.put(s, it); names.add(s) }

    private fun place(book: Int, at: Locality) {
        if (book >= bookAt.size) bookAt = bookAt.copyOf(maxOf(book + 1, bookAt.size * 2))
        bookAt[book] = at.packed
    }

    /**
     * One judgment of [lemma]'s use in [book], crafted in [at], asked as [source] (a content id): offered class →
     * probability. False when that source is already counted in the book's rows of the word. A book is read where it was
     * last observed: observing it in another locality moves every row it holds there.
     */
    fun observe(lemma: String, at: Locality, book: String, source: String, judged: Map<String, Double>): Boolean {
        if (judged.isEmpty()) return false
        val l = intern(lemmaIds, lemmas, lemma)
        val b = intern(bookIds, bookNames, book)
        place(b, at)
        val s = sourceKey(source)
        val mine = if (l < byLemma.size) byLemma[l] else null
        if (mine != null) for (r in mine) if (rowBook[r] == b && rowSources[r].seek(s) >= 0) return false
        val n = judged.size
        val ids = IntArray(n); val ps = LongArray(n)
        var i = 0
        for ((name, p) in judged) { ids[i] = intern(classIds, classNames, name); ps[i] = (p.coerceIn(0.0, 1.0) * Nal.UNIT).roundToLong(); i++ }
        val order = ascending(ids, n)
        val menu = IntArray(n) { ids[order[it]] }
        var r = -1
        if (mine != null) for (x in mine) if (rowBook[x] == b && rowClasses[x].contentEquals(menu)) { r = x; break }
        if (r < 0) r = newRow(l, b, menu)
        val acc = rowPositive[r]
        for (x in 0 until n) acc[x] += ps[order[x]]
        rowSources[r] = rowSources[r].inserted(s)
        rowAttention[r] = 1f
        judgments++
        if (size > capacity) forget()
        return true
    }

    private fun newRow(l: Int, b: Int, menu: IntArray): Int {
        val r = rowCount++
        if (r == rowLemma.size) {
            rowLemma = rowLemma.copyOf(r * 2); rowBook = rowBook.copyOf(r * 2); rowAttention = rowAttention.copyOf(r * 2)
        }
        rowLemma[r] = l; rowBook[r] = b; rowAttention[r] = 1f
        rowClasses.add(menu); rowPositive.add(LongArray(menu.size)); rowSources.add(NO_SOURCES)
        if (l >= byLemma.size) byLemma = byLemma.copyOf(maxOf(l + 1, byLemma.size * 2))
        byLemma[l] = (byLemma[l] ?: NO_ROWS) + r
        size += menu.size; rows++
        return r
    }

    /** Every row [book] holds, dropped: its judgments are about to be observed again from its tables. */
    fun replace(book: String) {
        val b = bookIds.get(book) ?: return
        for (r in 0 until rowCount) if (rowBook[r] == b && rowClasses[r].isNotEmpty()) drop(r)
    }

    private fun drop(r: Int) {
        val l = rowLemma[r]
        size -= rowClasses[r].size; rows--; judgments -= rowSources[r].size
        rowClasses[r] = NO_ROWS; rowPositive[r] = NO_SOURCES; rowSources[r] = NO_SOURCES
        byLemma[l]?.let { v -> val w = v.without(r); byLemma[l] = if (w.isEmpty()) null else w }
    }

    /** Attention decays by [DECAY]; evidence does not. */
    fun tick() {
        for (r in 0 until rowCount) if (rowClasses[r].isNotEmpty()) rowAttention[r] *= DECAY
    }

    /** The least attended and least confident [FORGET_SHARE]th of the rows, forgotten together. */
    private fun forget() {
        val live = (0 until rowCount).filter { rowClasses[it].isNotEmpty() }
        val n = maxOf(1, live.size / FORGET_SHARE)
        val score = DoubleArray(live.size) { i -> val r = live[i]; rowAttention[r] * row(r).confidence }
        val order = live.indices.sortedBy { score[it] }
        for (x in 0 until minOf(n, order.size)) drop(live[order[x]])
    }

    private fun row(r: Int): SenseRow = SenseRow.of(rowClasses[r], rowPositive[r], rowSources[r].size, NONE_ID)

    /** [l]'s rows by locality, each locality's rows summed, in locality order. */
    private fun localRows(l: Int): Series<Join<Locality, SenseRow>> {
        val mine = (if (l < byLemma.size) byLemma[l] else null) ?: return emptySeriesOf()
        val at = IntArray(mine.size) { bookAt[rowBook[mine[it]]] }
        val places = at.sortedUnique()
        val sums = arrayOfNulls<SenseRow>(places.size)
        for (i in mine.indices) { val x = places.seek(at[i]); val r = row(mine[i]); sums[x] = sums[x]?.plus(r) ?: r }
        return places.size j { x: Int -> Locality(places[x]) j sums[x]!! }
    }

    /** [lemma]'s row per locality it was read in, each joined to its locality, in locality order. */
    fun contexts(lemma: String): Series<Join<Locality, SenseRow>> = lemmaIds.get(lemma)?.let(::localRows) ?: emptySeriesOf()

    /**
     * [lemma]'s row where it is read in [at]: that locality's row with every other locality's row that cannot be told
     * apart from it added in. Where [at] holds no row of the word: its rows summed when every pair of them agrees, else
     * null, as a word whose sense moved between localities says nothing of one it was never read in.
     */
    fun sense(lemma: String, at: Locality): SenseRow? {
        val local = contexts(lemma)
        val n = local.size
        if (n == 0) return null
        var own = -1
        for (x in 0 until n) if (local[x].a == at) { own = x; break }
        if (own >= 0) {
            val mine = local[own].b
            var out = mine
            for (x in 0 until n) if (x != own) { val r = local[x].b; if (mine.homogeneous(r)) out += r }
            return out
        }
        for (x in 0 until n) for (y in x + 1 until n) if (!local[x].b.homogeneous(local[y].b)) return null
        var out = local[0].b
        for (x in 1 until n) out += local[x].b
        return out
    }

    /** The class a row's class id names. */
    fun className(id: Int): String = classNames[id]

    /** Every (lemma, locality) holding a row as packInts(lemma id, locality), ascending: by lemma as interned, then locality. */
    private fun heldKeys(): LongArray {
        var keys = LongArray(64); var n = 0
        for (l in byLemma.indices) for (r in byLemma[l] ?: continue) {
            if (n == keys.size) keys = keys.copyOf(n * 2)
            keys[n++] = packInts(l, bookAt[rowBook[r]])
        }
        return keys.copyOf(n).sortedUnique()
    }

    /** Every (lemma, locality) holding a row, by lemma as interned, then locality. */
    fun held(): Series<Join<String, Locality>> {
        val keys = heldKeys()
        return keys.size j { i: Int -> keys[i].let { k -> lemmas[(k ushr 32).toInt()] j Locality(k.toInt()) } }
    }

    /**
     * The memory as text, what [read] restores: per book a `k` line (its locality), per row an `r` line (lemma, book,
     * attention, sources in hex) followed by a `c` line per class offered (class, mass).
     */
    fun write(): String = buildString {
        val live = (0 until rowCount).filter { rowClasses[it].isNotEmpty() }
        for (b in live.map { rowBook[it] }.distinct().sorted()) append("k\t").append(bookNames[b]).append('\t').append(bookAt[b]).append('\n')
        for (r in live) {
            append("r\t").append(lemmas[rowLemma[r]]).append('\t').append(bookNames[rowBook[r]]).append('\t').append(rowAttention[r]).append('\t')
            rowSources[r].forEachIndexed { i, s -> if (i > 0) append(','); append(s.toULong().toString(16)) }
            append('\n')
            val ids = rowClasses[r]; val ps = rowPositive[r]
            for (i in ids.indices) append("c\t").append(classNames[ids[i]]).append('\t').append(ps[i]).append('\n')
        }
    }

    companion object {
        /** Class cells held at once: enough for a library straddling legal and medical texts. */
        const val CAPACITY = 1 shl 20
        const val DECAY = 0.9f
        const val FORGET_SHARE = 16

        /** The option every sense question offers for a use none of the listed senses names. */
        const val NONE = "none"

        /** [NONE]'s class id, interned first in every memory. */
        const val NONE_ID = 0

        private val NO_ROWS = IntArray(0)
        private val NO_SOURCES = LongArray(0)

        /** A source's key: the first eight bytes of its hex content id; a name that is not one is folded by FNV-1a. */
        fun sourceKey(source: String): Long {
            if (source.length >= 16 && (0 until 16).all { source[it] in '0'..'9' || source[it] in 'a'..'f' })
                return source.substring(0, 16).toULong(16).toLong()
            var h = -0x340d631b7bdddcdbL
            for (c in source) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
            return h
        }

        /** A memory from [write]'s text. */
        fun read(lines: Sequence<String>, capacity: Int = CAPACITY): SenseMemory {
            val m = SenseMemory(capacity)
            var lemma = -1; var book = -1; var attention = 1f; var sources = NO_SOURCES
            var ids = IntArray(16); var ps = LongArray(16); var k = 0
            fun flush() {
                if (lemma < 0 || k == 0) { k = 0; return }
                val order = ascending(ids, k)
                val menu = IntArray(k) { ids[order[it]] }
                val r = m.newRow(lemma, book, menu)
                val acc = m.rowPositive[r]
                for (x in 0 until k) acc[x] = ps[order[x]]
                m.rowSources[r] = sources; m.rowAttention[r] = attention; m.judgments += sources.size
                k = 0
            }
            for (line in lines) {
                val f = line.split('\t')
                when (f[0]) {
                    "k" -> m.place(m.intern(m.bookIds, m.bookNames, f[1]), Locality(f[2].toInt()))
                    "r" -> {
                        flush()
                        lemma = m.intern(m.lemmaIds, m.lemmas, f[1]); book = m.intern(m.bookIds, m.bookNames, f[2])
                        attention = f[3].toFloat()
                        sources = if (f[4].isEmpty()) NO_SOURCES else f[4].split(',').map { it.toULong(16).toLong() }.toLongArray().also { it.sort() }
                    }
                    "c" -> {
                        if (k == ids.size) { ids = ids.copyOf(k * 2); ps = ps.copyOf(k * 2) }
                        ids[k] = m.intern(m.classIds, m.classNames, f[1]); ps[k] = f[2].toLong(); k++
                    }
                }
            }
            flush()
            return m
        }
    }
}

/** The index of [x] in this ascending array, or -1. */
private fun LongArray.seek(x: Long): Int {
    var lo = 0; var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val v = this[mid]
        if (v < x) lo = mid + 1 else if (v > x) hi = mid - 1 else return mid
    }
    return -1
}

/** This ascending array with [x] in its place. */
private fun LongArray.inserted(x: Long): LongArray {
    var at = 0
    while (at < size && this[at] < x) at++
    val out = LongArray(size + 1)
    copyInto(out, 0, 0, at); out[at] = x; copyInto(out, at + 1, at, size)
    return out
}

/** The positions of the first [n] of [ids] in ascending order of id (ids distinct): sorted as packInts(id, position). */
private fun ascending(ids: IntArray, n: Int): IntArray {
    val keys = LongArray(n) { packInts(ids[it], it) }
    keys.sort()
    return IntArray(n) { keys[it].toInt() }
}

/** This array ascending with duplicates removed. */
private fun IntArray.sortedUnique(): IntArray {
    val a = copyOf(); a.sort()
    var n = 0
    for (i in a.indices) if (n == 0 || a[i] != a[n - 1]) a[n++] = a[i]
    return a.copyOf(n)
}

/** This array ascending with duplicates removed. */
private fun LongArray.sortedUnique(): LongArray {
    val a = copyOf(); a.sort()
    var n = 0
    for (i in a.indices) if (n == 0 || a[i] != a[n - 1]) a[n++] = a[i]
    return a.copyOf(n)
}

/** The index of [x] in this ascending array, or -1. */
private fun IntArray.seek(x: Int): Int {
    var lo = 0; var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val v = this[mid]
        if (v < x) lo = mid + 1 else if (v > x) hi = mid - 1 else return mid
    }
    return -1
}

/** This array without [x]. */
private fun IntArray.without(x: Int): IntArray {
    var n = 0
    for (v in this) if (v != x) n++
    val out = IntArray(n); var i = 0
    for (v in this) if (v != x) out[i++] = v
    return out
}
