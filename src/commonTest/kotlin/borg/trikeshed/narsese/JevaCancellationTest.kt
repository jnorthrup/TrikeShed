package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.test.*

class JevaCancellationTest {
    private class Stopped : RuntimeException()

    @Test fun asciiNormalizationPollsDuringTheScan() {
        var polls = 0
        assertFailsWith<Stopped> {
            JevaTokenizer.normalize("a".repeat(32_768)) { if (++polls == 3) throw Stopped() }
        }
    }

    @Test fun oddSupplementaryDecompositionPollsBeforeOrdering() {
        val input = "a" + "🚀".repeat(8192)
        val backing: Map<Int, Int> = _m.get()
        val classes = object : Map<Int, Int> by backing {
            var reads = 0
            override fun get(key: Int): Int? { reads++; return backing[key] }
        }
        var pollsBeforeOrdering = 0
        assertEquals(input, JevaTokenizer.normalize(input, classes) {
            if (classes.reads == 0) pollsBeforeOrdering++
        })
        assertTrue(pollsBeforeOrdering >= 6, "long scalar decomposition did not poll cancellation")
    }

    @Test fun oddSupplementarySpecialTokenScanPollsBeforeItFinishes() {
        val backing: Map<String, Int> = _m.get()
        val special = object : Map<String, Int> by backing {
            var scans = 0
            override val keys: Set<String> get() { scans++; return backing.keys }
        }
        val count = 8193
        assertFailsWith<Stopped> {
            JevaTokenizer.segments("a" + "🚀".repeat(count - 1), special,
                { if (special.scans in 1 until count) throw Stopped() }, {}, {})
        }
    }

    @Test fun addedTokenTableScanPollsWithinOneInputScalar() {
        val tokens = HashMap<String, Int>()
        repeat(8192) { tokens["token-$it"] = it }
        var polls = 0
        assertFailsWith<Stopped> {
            JevaTokenizer.segments("z", tokens, { if (++polls == 2) throw Stopped() }, {}, {})
        }
    }

    @Test fun longAddedTokenComparisonPollsBeforeTheMatchCompletes() {
        val token = "a".repeat(16_384)
        var polls = 0
        assertFailsWith<Stopped> {
            JevaTokenizer.segments(token, _m[token j 1], { if (++polls == 2) throw Stopped() }, {}, {})
        }
    }

    @Test fun addedTokenMatchingRetainsLongestSupplementaryPrefixInEitherOrder() {
        for (tokens in _l[_m["🚀" j 1, "🚀x" j 2], _m["🚀x" j 2, "🚀" j 1]]) {
            val found = ArrayList<Int>()
            val plain = StringBuilder()
            JevaTokenizer.segments("a🚀xb🚀", tokens, {}, { plain.append(it) }, { found.add(it) })
            assertEquals(_l[2, 1], found)
            assertEquals("ab", plain.toString())
        }
    }

    @Test fun splitPollsDuringALongSupplementaryPiece() {
        val text = "a" + "🚀".repeat(8192)
        var polls = 0
        assertFailsWith<Stopped> {
            JevaTokenizer.split(text) { if (++polls == 5) throw Stopped() }
        }
    }

    @Test fun bertScalarBoundsRetainTheirExistingContract() {
        assertContentEquals(_a[0, 1, 3, 5], BertWordPiece.scalarBounds("a🚀🚀"))
    }

    @Test fun normalizedOutputPollsCancellation() {
        val values = ArrayList<Int>()
        repeat(12_288) { values.add(0x1f680) }
        var polls = 0
        assertFailsWith<Stopped> {
            JevaTokenizer.normalizedText(values, values.size) { if (++polls == 3) throw Stopped() }
        }
    }
}
