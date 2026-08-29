package borg.trikeshed.cas

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.cascade.Count
import borg.trikeshed.lib.cascade.Depth
import borg.trikeshed.lib.cascade.Emit
import borg.trikeshed.lib.cascade.Key
import borg.trikeshed.lib.cascade.Level
import borg.trikeshed.lib.cascade.Monoid
import borg.trikeshed.lib.cascade.Ticks
import borg.trikeshed.lib.cascade.Trie
import borg.trikeshed.lib.cascade.fibTicks
import borg.trikeshed.lib.cascade.groupLevel
import borg.trikeshed.lib.cascade.monoid
import borg.trikeshed.lib.cascade.view
import borg.trikeshed.job.ContentId

/**
 * CodeKeyedZoom — the zoom ladder over the coordinate minted at ingest (Step C).
 *
 * The CAS is not hierarchically sortable: content hashes carry zero semantic
 * locality. The [AngularCodec]-minted fragment code DOES — so the view key is
 * [code ring8, code, contentCid hex]: the code sorts and groups, the cid
 * disambiguates. `groupLevel(k)` over those keys IS the concentric zoom ring;
 * [fibTicks] supplies the depths worth asking for; [Trie.level] caches partial
 * reductions under the `cascadeWorthCaching` gates (the grouping axis is FIXED
 * per view — the sort key is always the code — so hard-no #2 passes).
 *
 * Keys are `Series<Char>` over hex nibbles of the three fields: prefix-ordered,
 * comparable, one symbol per nibble — the same opaque-string convention
 * `CursorCascade` uses for Char keys. Depth k = k hex nibbles:
 *   k=2  → one group per code ring8 (256 max) — the satellite ring
 *   k=4  → one group per full 16-bit code
 *   k≥6  → fragment-level: the cid tail separates distinct fragments
 */
object CodeKeyedZoom {

    /** The codeable view key: ring8 ‖ code ‖ cid-hex as hex-nibble Chars (2+4+64+... ). */
    fun keyOf(node: LineNode): Key<Char> {
        val code = node.code
        val sb = StringBuilder(4 + node.contentCid.hex.length)
        appendHex(sb, (code ushr 8) and 0xFF, 2)
        appendHex(sb, code and 0xFFFF, 4)
        sb.append(node.contentCid.hex)
        val s = sb.toString()
        return s.length j { i: Int -> s[i] }
    }

    /** Doc-grain key: the doc's own code over its spineCid — one emit per document. */
    fun keyOfDoc(spineCidHex: String, code: Int): Key<Char> {
        val sb = StringBuilder(4 + spineCidHex.length)
        appendHex(sb, (code ushr 8) and 0xFF, 2)
        appendHex(sb, code and 0xFFFF, 4)
        sb.append(spineCidHex)
        val s = sb.toString()
        return s.length j { i: Int -> s[i] }
    }

    /** Character-count monoid: bounded (one Int), associative — the ring rollup. */
    val Fragments: Monoid<Int> = Count

    /**
     * One zoom ring: `group_level=k` over code-prefixed keys. The collated emit
     * series IS the prefix tree; this is delivery-time reduction, uncached.
     */
    fun ring(spine: LineSpine, depth: Depth): Level<Char, Int> {
        val emits: Series<Emit<Char, Int>> = spine.size j { i: Int -> keyOf(spine[i]) j 1 }
        return emits.groupLevel(depth, Fragments)
    }

    /**
     * The zoom slider over one spine: depth → ring. [fibTicks] names the depths
     * worth asking — the satellite view is ring 2 (one nibble pair = the 8-bit
     * code), fragment level is ≥ 6.
     */
    fun view(spine: LineSpine): (Depth) -> Level<Char, Int> {
        val emits: Series<Emit<Char, Int>> = spine.size j { i: Int -> keyOf(spine[i]) j 1 }
        return { depth -> emits.groupLevel(depth, Fragments) }
    }

    /**
     * The cached-partial variant: one [Trie] per spine answers every depth from
     * cached prefix reductions. Worth it when queries repeat over a static spine —
     * the caller checks `cascadeWorthCaching(rows, depth, fanout, queriesPerWrite)`
     * and its four hard gates before paying the Trie.
     */
    fun trie(spine: LineSpine): Trie<Char, Int> =
        Trie<Char, Int>(Fragments).also { t ->
            for (i in 0 until spine.size) t.add(keyOf(spine[i]), 1)
        }

    /** The depths worth asking for a spine of [n] fragments. */
    fun ticks(n: Int): Ticks = fibTicks(n)

    private fun appendHex(sb: StringBuilder, v: Int, digits: Int) {
        for (shift in (digits - 1) * 4 downTo 4 step 4) sb.append("0123456789abcdef"[(v ushr shift) and 0xF])
        sb.append("0123456789abcdef"[v and 0xF])
    }

    /**
     * Persisted code index over a whole corpus: doc spineCid → doc code, rebuilt
     * per ingest, queried by ring. This is the [LineCasIndex] sibling for SEMANTIC
     * reuse (`overlap`/`linkSearch` answer structural reuse); grouped answers at
     * every depth come from the same collated-emit walk.
     */
    class CodeIndex {
        private val byDoc = linkedMapOf<String, Int>() // spineCid.hex → doc code

        val docCount: Int get() = byDoc.size

        /** Ingest a spine: records the doc's code (mean of member fragment codes — bounded rollup). */
        fun ingestSpine(spine: LineSpine): ContentId {
            val doc = LineCas.spineCid(spine)
            if (spine.size == 0) return doc
            var acc = 0
            for (i in 0 until spine.size) acc = Fragments.combine(acc, spine[i].code)
            byDoc[doc.hex] = acc / spine.size
            return doc
        }

        /** `group_level=k` over docs: one group per code-prefix at [nibbles] depth, counting docs. */
        fun ring(nibbles: Int): Level<Char, Int> {
            val emits = ArrayList<Emit<Char, Int>>(byDoc.size)
            for ((hex, code) in byDoc) emits += keyOfDoc(hex, code) j 1
            val emitSeries: Series<Emit<Char, Int>> = emits.size j { i: Int -> emits[i] }
            return emitSeries.groupLevel(nibbles, Fragments)
        }

        /** Docs under one ring8 value (the satellite neighborhood), by doc cid hex. */
        fun docsInRing(ring8: Int): List<String> {
            val want = buildString { appendHex(this, ring8 and 0xFF, 2) }
            return byDoc.entries.mapNotNull { (hex, code) ->
                val sb = StringBuilder(2)
                appendHex(sb, (code ushr 8) and 0xFF, 2)
                if (sb.toString() == want) hex else null
            }
        }
    }
}
