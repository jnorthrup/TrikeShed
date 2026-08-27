package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.cascade.Level
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step C gate — the code-keyed zoom ladder:
 *
 * 1. Ring counts at depth k equal brute-force group-by on a fixture corpus at
 *    every fibTick (the collated walk IS the prefix tree — prove it).
 * 2. Trie cached answers equal uncached [CodeKeyedZoom.ring] answers — the
 *    cached-partial option is honest.
 * 3. Satellites cluster: same-code fragments share a ring-2 group; the corpus
 *    granularity floor is the 8-bit ring.
 */
class CodeKeyedZoomTest {

    private fun bruteForce(spine: LineSpine, depth: Int): List<Pair<String, Int>> {
        // group by key prefix of [depth] nibbles, count — the obvious O(n log n) reference
        val keys = (0 until spine.size).map { CodeKeyedZoom.keyOf(spine[it]) }
        val prefixOf = { k: borg.trikeshed.lib.cascade.Key<Char> -> buildString { for (i in 0 until minOf(depth, k.size)) append(k[i]) } }
        return keys.groupBy(prefixOf).map { (p, members) -> p to members.size }.sortedBy { it.first }
    }

    private fun levelToPairs(level: Level<Char, Int>): List<Pair<String, Int>> =
        (0 until level.size).map { i ->
            val node = level[i]
            val sb = StringBuilder()
            for (j in 0 until node.a.size) sb.append(node.a[j])
            sb.toString() to node.b
        }

    private fun fixture(): LineSpine {
        val cas = CasStore.inMemory()
        val text = buildString {
            repeat(3) { r ->
                appendLine("shared header line about retrieval systems $r")
                appendLine("body line describing zoom rings and grouping $r")
                appendLine("totally distinct tail content for variety $r")
            }
        }
        return LineCas.spineInto(cas, text)
    }

    @Test
    fun ringsEqualBruteForceGroupByAtEveryFibTick() {
        val spine = fixture()
        val n = spine.size
        val ticks = CodeKeyedZoom.ticks(n).let { ticks ->
            // include the semantic depths: 2 (ring8) and 4 (full code)
            val all = (0 until ticks.size).map { ticks[it] }.toMutableList()
            if (2 !in all) all.add(2)
            if (4 !in all) all.add(4)
            all.sorted()
        }
        for (depth in ticks) {
            val got = levelToPairs(CodeKeyedZoom.ring(spine, depth)).sortedBy { it.first }
            val want = bruteForce(spine, depth).sortedBy { it.first }
            assertEquals(want, got, "ring(depth=$depth) must equal brute force")
        }
    }

    @Test
    fun trieCachedAnswersEqualUncachedRings() {
        val spine = fixture()
        val trie = CodeKeyedZoom.trie(spine)
        for (depth in listOf(2, 4, 6, 8)) {
            val cached = levelToPairs(trie.level(depth)).sortedBy { it.first }
            val uncached = levelToPairs(CodeKeyedZoom.ring(spine, depth)).sortedBy { it.first }
            assertEquals(uncached, cached, "trie.level($depth) must equal groupLevel($depth)")
        }
    }

    @Test
    fun satelliteRingGroupsFragmentsByHighCodeByte() {
        val spine = fixture()
        val ring2 = CodeKeyedZoom.ring(spine, 2)
        // every ring-2 group's members must share their code's high byte
        for (i in 0 until ring2.size) {
            val prefixHex = buildString { for (j in 0 until ring2[i].a.size) append(ring2[i].a[j]) }
            val want = prefixHex.toInt(16)
            var counted = 0
            for (k in 0 until spine.size) {
                if (spine[k].codeRing8 == want) counted++
            }
            assertEquals(counted, ring2[i].b, "ring group $prefixHex counts only its own high-byte members")
        }
        assertTrue(ring2.size <= 256, "ring 2 is the 8-bit code space")
    }

    @Test
    fun codeIndexDocRingsAnswerPerDepth() {
        val cas = CasStore.inMemory()
        val idx = CodeKeyedZoom.CodeIndex()
        val docA = LineCas.spineInto(cas, "alpha bravo charlie delta echo foxtrot")
        val docB = LineCas.spineInto(cas, "alpha bravo charlie delta echo foxtrot repeated")
        idx.ingestSpine(docA)
        idx.ingestSpine(docB)
        assertEquals(2, idx.docCount)
        val total = idx.ring(2)
        var docs = 0
        for (i in 0 until total.size) docs += total[i].b
        assertEquals(2, docs, "ring(2) over the doc index counts every doc exactly once")
        // both docs' members are near-identical → likely same ring, but only assert structure
        assertTrue(total.size in 1..256)
        val inRing = idx.docsInRing(0)
        assertTrue(inRing.isEmpty() || inRing.all { it.length == 64 }, "ring membership answers doc cid hexes")
    }
}
