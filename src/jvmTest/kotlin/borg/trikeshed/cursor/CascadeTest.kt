package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import borg.trikeshed.lib.cascade.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CascadeTest {

    private fun classify(line: String): Char {
        val t = line.trim()
        return when {
            t.isEmpty() -> '_'
            t == "6. Work packages" -> '6'
            Regex("""^\d+\.\s""").containsMatchIn(t) -> 'S'
            Regex("""^[A-Z][0-9]+\s+[—-]\s+""").containsMatchIn(t) -> 'W'
            t.startsWith("Depends on:") -> 'D'
            else -> 'P'
        }
    }

    private val plan = """
        1. Goal
        Ship it.

        6. Work packages

        G0 — Root graph
        Collapse subprojects.

        G1 — Seed bake
        Depends on: G0
        Write the seed.

        7. Acceptance
        Live.
    """.trimIndent().lines().toSeries()

    @Test
    fun forwardThenReverse() {
        val shape = plan.shape(::classify)
        val key = shape.key.toList().joinToString("")
        assertEquals("SP_6_WP_WDP_SP", key)
        // reverse: the second W run pulls back to its source line
        val w2 = shape.toList().filter { it.a == 'W' }[1]
        assertEquals("G1 — Seed bake", w2.pull(plan)[0])
    }

    @Test
    fun shapeSeamMerges() {
        val m = shapeMonoid<Char>()
        val whole = plan.shape(::classify)
        val cut = 5
        val l = plan[0 until cut].shape(::classify)
        // shift right-chunk spans to absolute
        val r = plan[cut until plan.size].shape(::classify) α { (s, sp) -> s j ((sp.a + cut) j (sp.b + cut)) }
        val merged = m.combine(l, r)
        assertEquals(whole.key.toList(), merged.key.toList())
        assertEquals(whole.toList().map { it.b.pair }, merged.toList().map { it.b.pair })
    }

    @Test
    fun fibTicksSchedule() {
        assertEquals(listOf(0, 1, 2, 4, 7, 12, 20, 33), fibTicks(40).toList())
    }

    private fun corpus(): Cursor = listOf(
        "SP_6_WP_WDP_SP" to 14, "SP_6_WP_WDP_SP" to 20, "SP_6_WP_SP" to 9,
        "SP_SP_WP" to 6, "P" to 3, "P" to 5,
    ).map { (k, n) -> cellsToRowVec(seriesOfAny(listOf(k, n)), listOf("key", "lines").toSeries()) }.toSeries()

    @Test
    fun groupLevelIsTheZoomSlider() {
        val view = corpus().view(ColK.ByIndex(0), Count) { 1 }
        assertEquals(listOf("" to 6), view(0).pairs())
        assertEquals(listOf("P" to 2, "S" to 4), view(1).pairs())
        assertEquals(listOf("P" to 2, "SP_6" to 3, "SP_S" to 1), view(4).pairs())
        assertEquals(listOf("P" to 2, "SP_6_WP_SP" to 1, "SP_6_WP_WDP_SP" to 2, "SP_SP_WP" to 1), view(99).pairs())
    }

    @Test
    fun prefixRangeByLength() {
        val plans = corpus().prefixRange(ColK.ByIndex(0), "SP_6")
        assertEquals(3, plans.size)
        val stats = plans.emits(ColK.ByIndex(0)) { Stats.of(it.intValue("lines").toDouble()) }.groupLevel(0, Stats)
        assertEquals(43.0, stats[0].b.sum)
    }

    @Test
    fun trieRereducesDingedRows() {
        val t = corpus().toTrie(ColK.ByIndex(0), Count) { 1 }
        assertEquals(3, t["SP_6".toSeries()])
        assertEquals(6, t[t.a])
        assertTrue(t.unseen("SP_7".toSeries()))
        // ding: one plan loses its work-packages section
        t.remove("SP_6_WP_SP".toSeries()); t.add("SP_SP".toSeries(), 1)
        assertEquals(2, t["SP_6".toSeries()])
        assertEquals(listOf("P" to 2, "SP_6" to 2, "SP_S" to 2), t.level(4).toList().map { it.a.toList().joinToString("") to it.b })
    }

    @Test
    fun facetKeyedEntryPoints() {
        val byName = corpus().view(ColK.ByName("key"), Count) { 1 }
        assertEquals(listOf("P" to 2, "S" to 4), byName(1).pairs())
        assertEquals(3, corpus().prefixRange(ColK.ByName("key"), "SP_6").size)
        assertEquals(6, corpus().toTrie(ColK.ByName("key"), Count) { 1 }.let { it[it.a] })
    }

    private fun Cursor.pairs() = toList().map { it[0].a as String to it[1].a }
}
