package borg.trikeshed.narsese

import borg.trikeshed.cas.LaneTrie
import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.lib.cascade.Monoid
import borg.trikeshed.lib.cascade.Trie

/**
 * A curated book's lanes in the CursorTrie: `book.sections`, one row per section (ordinal, the SHA-256 of its text as
 * four longs, its length), and `law.cites`, one row per citation of codified law a section makes (ordinal, the key path
 * [CitationLinks.keys] reads, joined by `/`). The law's hierarchy is read back as a Cascade [Trie] over the key paths:
 * `trie[15, U.S.C., 1693f]` is every citation of §1693f and of its subsections. The monoid carries the citing sections
 * as a Roaring basis (`document shl 20 or section`, the constellation's global section id, each the name of one section
 * URN), so a section citing (a) and (b) counts once at the section above them.
 */
object LawCites {
    class Evidence(val citations: Long, val basis: RoaringSeries)

    val EVIDENCE: Monoid<Evidence> = object : Monoid<Evidence> {
        override val zero = Evidence(0L, RoaringSeries.EMPTY)
        override fun combine(acc: Evidence, element: Evidence) = Evidence(acc.citations + element.citations, acc.basis or element.basis)
    }

    const val CITES = "law.cites"
    const val SECTIONS = "book.sections"

    /** The widest key path a row holds, in bytes. */
    const val KEY = 64

    private val CITE_COLUMNS = listOf("section" to IOMemento.IoInt, "key" to IOMemento.IoString)
    private val SECTION_COLUMNS = listOf("section" to IOMemento.IoInt, "c0" to IOMemento.IoLong, "c1" to IOMemento.IoLong,
        "c2" to IOMemento.IoLong, "c3" to IOMemento.IoLong, "length" to IOMemento.IoInt)

    /** [document]'s `book.sections` and `law.cites` lanes, filled from its [sections] texts; durable at the trie's next commit. */
    fun land(trie: LaneTrie, document: Int, sections: List<String>): Pair<LaneTrie.Lane, LaneTrie.Lane> {
        val sectionLane = trie.lane(document, SECTIONS, SECTION_COLUMNS)
        val citeLane = trie.lane(document, CITES, CITE_COLUMNS, mapOf("key" to KEY))
        // A document node names one source text: its lanes, once filled, hold its rows already.
        if (sectionLane.size > 0) return sectionLane to citeLane
        trie.appender(sectionLane).use { rows ->
            for ((k, text) in sections.withIndex()) {
                val h = ContentId.of(text.encodeToByteArray()).hex
                rows.append(k, h.substring(0, 16).toULong(16).toLong(), h.substring(16, 32).toULong(16).toLong(),
                    h.substring(32, 48).toULong(16).toLong(), h.substring(48, 64).toULong(16).toLong(), text.length)
            }
        }
        trie.appender(citeLane).use { rows ->
            for ((k, text) in sections.withIndex()) for (key in CitationLinks.keys(text)) {
                val joined = key.joinToString("/")
                if (joined.encodeToByteArray().size <= KEY) rows.append(k, joined)
            }
        }
        return sectionLane to citeLane
    }

    /** The citation trie over [lanes], each a `law.cites` lane with the ordinal its sections' global ids take. */
    fun trie(lanes: List<Pair<Int, LaneTrie.Lane>>): Trie<String, Evidence> {
        val trie = Trie<String, Evidence>(EVIDENCE)
        for ((document, lane) in lanes) {
            val rows = lane.cursor()
            for (r in 0 until rows.size) {
                val row = rows[r]
                val section = row[0].a as Int
                val key = (row[1].a as CharSequence).toString().trimEnd('\u0000', ' ').split('/')
                trie.add(key.toSeries(), Evidence(1L, RoaringSeries.singleton((document shl 20) or section)))
            }
        }
        return trie
    }
}
