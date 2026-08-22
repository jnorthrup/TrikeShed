@file:Suppress("NonAsciiCharacters", "NOTHING_TO_INLINE")

package borg.trikeshed.lib.cascade

import borg.trikeshed.lib.*

/*
 * ── Cascade taxonomy ─────────────────────────────────────────────────────────────────────────
 *
 * A Couch map/reduce view, re-expressed in the Join/Series algebra and nothing else.
 * Every alias below names ONE concept and carries its Couch analogue in the KDoc — these are
 * the guard-rails: if a change needs a type that is not here, it is probably not a cascade.
 *
 *   map      : document ─► Emit<S,V>           (key × value; key is prefix-ordered)
 *   reduce   : Monoid<V>                        (rereduce=true contract; bounded output)
 *   B-tree   : Trie<S,V> : MetaSeries<Prefix,V>   (trie[prefix] = cached reduction; ding ⇒ path rereduce)
 *   query    : View<S,V> = (Depth) -> Level     (group_level=k IS the zoom slider)
 *   range    : prefixRange(Prefix)              (startkey="abcd", endkey="abcd{" — by length)
 *   schedule : Ticks = fibTicks(n)              (which depths are worth asking for)
 *
 * The ingest signature is a Key: lines ─classify─► Series<S> ─rle─► Shape<S> ─key─► Key<S>.
 * Nothing derived from the key goes INTO the key (see Emit): derived facets are values, reduced.
 */

// ── Keys ─────────────────────────────────────────────────────────────────────────────────────

/** Key: prefix-ordered symbols. Couch: the JSON-array view key, or the opaque string with one Char per symbol. */
typealias Key<S> = Series<S>

/** Depth: Couch `group_level`. 0 = one group for everything; ≥ key.size = one group per distinct key. */
typealias Depth = Int

/** Prefix: a Key cut at a Depth. Equal prefixes ⇒ same group at that depth. */
typealias Prefix<S> = Key<S>

/** Ticks: the depths worth materialising or reporting — φ-spaced, dense where keys diverge early. */
typealias Ticks = Series<Depth>

// ── Runs (the facet ON the data) ─────────────────────────────────────────────────────────────

/** Span: half-open `[a, b)` over the source Series. Keeps the key addressable back into the data. */
typealias Span = Twin<Int>

/** Run: one maximal stretch of a single symbol, with its Span. */
typealias Run<S> = Join<S, Span>

/** Shape: the run-length facet. `shape.key` forgets the spans; `run.pull(source)` recovers them. */
typealias Shape<S> = Series<Run<S>>

// ── Reduction ────────────────────────────────────────────────────────────────────────────────

/**
 * Monoid: a Reducer whose element type IS its result type — exactly Couch's `rereduce=true` contract.
 * `combine` must be associative; output must not grow with input (Couch `reduce_limit`).
 * Anything that fails that test (a list, a set, an RLE) is a map emission, not a reduction.
 */
typealias Monoid<V> = Reducer<V, V>

/** Emit: what map() produced for one document — key × value. One per document. */
typealias Emit<S, V> = Join<Key<S>, V>

/** Node: one row of a group_level answer — prefix × cached reduction. */
typealias Node<S, V> = Join<Prefix<S>, V>

/** Level: the group_level=k answer, in key (collation) order. */
typealias Level<S, V> = Series<Node<S, V>>

/** View: the zoom slider. `view(k)` is Couch `?group_level=k`. */
typealias View<S, V> = (Depth) -> Level<S, V>

// ── Monoid constructors ──────────────────────────────────────────────────────────────────────

inline fun <V> monoid(zero: V, crossinline combine: (V, V) -> V): Monoid<V> = object : Reducer<V, V> {
    override val zero: V = zero
    override fun combine(acc: V, element: V): V = combine(acc, element)
}

/** Couch `_count`. */
val Count: Monoid<Int> = monoid(0) { a, b -> a + b }

/** Couch `_sum`. */
val Sum: Monoid<Double> = monoid(0.0) { a, b -> a + b }

/** Couch `_stats` — count × sum × min × max; the canonical bounded reduction. */
data class Stats(val count: Int, val sum: Double, val min: Double, val max: Double) {
    companion object : Reducer<Stats, Stats> {
        override val zero = Stats(0, 0.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        override fun combine(acc: Stats, element: Stats) =
            Stats(acc.count + element.count, acc.sum + element.sum, minOf(acc.min, element.min), maxOf(acc.max, element.max))
        fun of(x: Double) = Stats(1, x, x, x)
    }
}

// ── When is the cached-partial option worth it? ──────────────────────────────────────────────

/**
 * Caching wins when  q · (r − g·d) > w · d · f  (read/write ratio exceeds depth × fanout / rows).
 * Hard gates — "no" regardless of the ratio:
 *  1. the reducer is not a bounded [Monoid];
 *  2. the grouping axis varies per query — no fixed collation;
 *  3. writes are bulk, not local dings;
 *  4. queries are not prefixes of the one key order.
 */
fun cascadeWorthCaching(rows: Int, depth: Int, fanout: Int, queriesPerWrite: Double, groups: Int = 0): Boolean =
    queriesPerWrite * (rows - groups * depth) > depth.toDouble() * fanout

// ── Forward: Series<S> ─► Shape ─► Key ───────────────────────────────────────────────────────

/** Run-length encode. Spans are absolute indices into the receiver. */
fun <S> Series<S>.rle(): Shape<S> {
    if (size == 0) return emptySeriesOf()
    val runs = ArrayList<Run<S>>()
    var sym = this[0]; var start = 0
    for (i in 1 until size) {
        val s = this[i]
        if (s != sym) { runs += sym j (start j i); sym = s; start = i }
    }
    runs += sym j (start j size)
    return runs.toSeries()
}

/** Classify then run-length: the ingest signature in one step. */
inline fun <T, S> Series<T>.shape(crossinline classify: (T) -> S): Shape<S> = (this α { classify(it) }).rle()

/** Forget the spans. */
val <S> Shape<S>.key: Key<S> get() = this α { it.a }

/** Reverse: pull a run back to its source slice. */
fun <S, T> Run<S>.pull(source: Series<T>): Series<T> = source[b.a until b.b]

/**
 * Shape is a monoid under seam-merge: `rle(a ++ b) = rle(a) ⊕ rle(b)`, joining the boundary runs
 * when they share a symbol. Spans must already be absolute. This is why the signature cascades
 * without co-location — but it is NOT bounded, so it is an Emit, never a reduce.
 */
fun <S> shapeMonoid(): Monoid<Shape<S>> = monoid<Shape<S>>(emptySeriesOf()) { l, r ->
    val seam = l.size > 0 && r.size > 0 && l.last().a == r[0].a
    if (!seam) (l.size + r.size) j { i -> if (i < l.size) l[i] else r[i - l.size] }
    else (l.size + r.size - 1) j { i -> when { i < l.size - 1 -> l[i]; i == l.size - 1 -> r[0].a j (l.last().b.a j r[0].b.b); else -> r[i - l.size + 1] } }
}

// ── Schedule ─────────────────────────────────────────────────────────────────────────────────

/**
 * The pure half of [borg.trikeshed.lib.FibonacciReporter]: indices 0, 1, 2, 4, 7, 12, 20, 33, …
 * below [n]. Use as the set of Depths to query, or of run boundaries to checkpoint.
 * No clock, no rate, no ETA — those are observers joined to these ticks, never folded into them.
 */
fun fibTicks(n: Int): Ticks {
    val out = ArrayList<Int>()
    var trigger = 0; var countdown = 1
    for (i in 0 until n) if (--countdown == 0) { out += i; countdown = fib(++trigger) }
    return out.toSeries()
}

// ── Collation, prefix, range ─────────────────────────────────────────────────────────────────

/** Lexicographic collation — Couch array-key order for same-typed symbols. Shorter prefix sorts first. */
fun <S : Comparable<S>> compareKeys(x: Key<S>, y: Key<S>): Int {
    val n = minOf(x.size, y.size)
    for (i in 0 until n) { val c = x[i].compareTo(y[i]); if (c != 0) return c }
    return x.size.compareTo(y.size)
}

fun <S> Key<S>.prefix(depth: Depth): Prefix<S> = this[0 until minOf(depth, size)]

/** `startkey=p, endkey=p{` — everything whose key begins with [p]. O(n) over an unsorted emit list. */
fun <S : Comparable<S>, V> Series<Emit<S, V>>.prefixRange(p: Prefix<S>): Series<Emit<S, V>> =
    filter { it.a.startsWith(p) }

// ── group_level over an emit list (delivery-time sum) ────────────────────────────────────────

/** Sort by collation once. */
fun <S : Comparable<S>, V> Series<Emit<S, V>>.collated(): Series<Emit<S, V>> =
    toList().sortedWith { x, y -> compareKeys(x.a, y.a) }.toSeries()

/**
 * Couch `?group_level=depth`: one walk over the collated emits, folding while the prefix is
 * unchanged. The collated Series IS the prefix tree — equal prefixes are contiguous — so no
 * second structure is needed; a dinged row is a re-collate.
 */
fun <S : Comparable<S>, V> Series<Emit<S, V>>.groupLevel(depth: Depth, m: Monoid<V>): Level<S, V> =
    collated().toList().groupBy { it.a.prefix(depth).toList() }.map { (p, es) -> p.toSeries() j es.fold(m.zero) { a, e -> m.combine(a, e.b) } }.toSeries()

fun <S : Comparable<S>, V> Series<Emit<S, V>>.view(m: Monoid<V>): View<S, V> = { groupLevel(it, m) }

// ── Trie: the prefix tree as a MetaSeries accessor ───────────────────────────────────────────

/**
 * A prefix tree read through the Series algebra: `MetaSeries<Prefix<S>, V>` whose bound `a` is the
 * root prefix and whose oracle `b` is *prefix → cached reduction of everything beneath it*. So
 * `trie[prefix]` is Couch `startkey=p&endkey=p{` with the reduce already done, and `trie[trie.a]`
 * is the grand total. `put`/`add`/`remove` of one key ("a dinged row") rereduces only the root path.
 *
 * One value per key (`put` replaces, `add` combines through the monoid). All prefixes of one
 * symbol order are free; another order is another Trie — the Couch constraint, unchanged.
 * Symbol type is free: `Trie<Char,_>` for opaque-string keys, `Trie<String,_>` for path segments.
 */
class Trie<S : Comparable<S>, V>(val m: Monoid<V>) : MetaSeries<Prefix<S>, V> {
    private inner class N {
        val kids = HashMap<S, N>()
        var leaf: V? = null; var isLeaf = false
        var cached: V = m.zero
        fun rereduce() {
            @Suppress("UNCHECKED_CAST")
            var acc = if (isLeaf) m.combine(m.zero, leaf as V) else m.zero
            for (k in kids.values) acc = m.combine(acc, k.cached)
            cached = acc
        }
    }

    private val root = N()
    var size = 0; private set

    override val a: Prefix<S> = emptySeriesOf()
    override val b: (Prefix<S>) -> V = { p -> node(p)?.cached ?: m.zero }

    private fun node(p: Prefix<S>): N? { var n = root; for (i in 0 until p.size) n = n.kids[p[i]] ?: return null; return n }
    private fun path(key: Key<S>, create: Boolean): List<N>? {
        val out = ArrayList<N>(key.size + 1); var n = root; out += n
        for (i in 0 until key.size) { n = if (create) n.kids.getOrPut(key[i]) { N() } else (n.kids[key[i]] ?: return null); out += n }
        return out
    }
    private fun rereduce(path: List<N>, key: Key<S>) {
        for (i in path.indices.reversed()) {
            path[i].rereduce()
            if (i > 0 && !path[i].isLeaf && path[i].kids.isEmpty()) path[i - 1].kids.remove(key[i - 1])
        }
    }

    /** Set the value at [key], replacing any previous one. */
    fun put(key: Key<S>, v: V) {
        val p = path(key, create = true)!!; val n = p.last()
        if (!n.isLeaf) size++
        n.leaf = v; n.isLeaf = true; rereduce(p, key)
    }

    /** Combine [v] into the value at [key] through the monoid (many documents, one key). */
    fun add(key: Key<S>, v: V) = put(key, m.combine(leaf(key) ?: m.zero, v))

    /** Remove the value at [key]; null if absent. Prunes emptied branches. */
    fun remove(key: Key<S>): V? {
        val p = path(key, create = false) ?: return null; val n = p.last()
        if (!n.isLeaf) return null
        val old = n.leaf; n.leaf = null; n.isLeaf = false; size--
        rereduce(p, key); return old
    }

    /** The value stored exactly at [key] (not a prefix reduction). */
    fun leaf(key: Key<S>): V? = node(key)?.takeIf { it.isLeaf }?.leaf
    operator fun contains(key: Key<S>): Boolean = node(key)?.isLeaf == true
    /** Nothing under this prefix — a first sighting. */
    fun unseen(p: Prefix<S>): Boolean = node(p) == null

    /** Couch `?group_level=depth` from cached nodes; keys shorter than depth are their own groups. */
    fun level(depth: Depth): Level<S, V> {
        val out = ArrayList<Node<S, V>>()
        fun walk(n: N, prefix: List<S>) {
            if (prefix.size == depth || n.kids.isEmpty()) { out += prefix.toSeries() j n.cached; return }
            @Suppress("UNCHECKED_CAST")
            if (n.isLeaf) out += prefix.toSeries() j m.combine(m.zero, n.leaf as V)
            for ((s, k) in n.kids.entries.sortedBy { it.key }) walk(k, prefix + s)
        }
        walk(root, emptyList()); return out.toSeries()
    }

    /** Every stored key beneath [p], in collation order. */
    fun keys(p: Prefix<S> = a): List<Key<S>> {
        val out = ArrayList<Key<S>>()
        fun walk(n: N, prefix: List<S>) {
            if (n.isLeaf) out += prefix.toSeries()
            for ((s, k) in n.kids.entries.sortedBy { it.key }) walk(k, prefix + s)
        }
        node(p)?.let { walk(it, p.toList()) }; return out
    }
}
