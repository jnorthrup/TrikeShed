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
        val vocabulary = mutableMapOf<String, Int>()
        var extra = 256
        for (byte in 0..255) {
            val symbol = if (byte in 33..126 || byte in 161..172 || byte in 174..255) byte else extra++
            vocabulary[symbol.toChar().toString()] = byte
        }
        val backing: Map<String, Int> = _m.get()
        val special = object : Map<String, Int> by backing {
            var scans = 0
            override val keys: Set<String> get() { scans++; return backing.keys }
        }
        val tokenizer = JevaTokenizer(vocabulary, emptySeriesOf<Twin<String>>(), special)
        special.scans = 0
        val count = 8193
        assertFailsWith<Stopped> {
            tokenizer.encode("a" + "🚀".repeat(count - 1), maxLength = 65_536) {
                if (special.scans in 1 until count) throw Stopped()
            }
        }
    }

    @Test fun scalarBoundsPollCancellation() {
        val text = "a" + "🚀".repeat(8192)
        var polls = 0
        assertFailsWith<Stopped> {
            JevaTokenizer.scalarBounds(text) { if (++polls == 3) throw Stopped() }
        }
    }

    @Test fun scalarMaterializationPollsCancellation() {
        val text = "a" + "🚀".repeat(8192)
        val bounds = BertWordPiece.scalarBounds(text)
        var polls = 0
        assertFailsWith<Stopped> {
            JevaTokenizer.scalars(text, bounds) { if (++polls == 3) throw Stopped() }
        }
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
