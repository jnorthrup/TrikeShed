package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.test.*
import kotlin.time.TimeSource

class JevaTokenizerBpeTest {
    private fun tokenizer(vararg merges: Twin<String>): JevaTokenizer {
        val vocabulary = mutableMapOf<String, Int>()
        var extra = 256
        for (byte in 0..255) {
            val symbol = if (byte in 33..126 || byte in 161..172 || byte in 174..255) byte else extra++
            vocabulary[symbol.toChar().toString()] = byte
        }
        vocabulary["aa"] = 256
        vocabulary["aaa"] = 257
        vocabulary["ab"] = 258
        vocabulary["bc"] = 259
        return JevaTokenizer(vocabulary, merges.toSeries(), _m["<END>" j 260])
    }

    private fun values(ids: Series<Int>) = IntArray(ids.size) { ids[it] }

    @Test fun rankedPairsAndLeftmostEqualRankMatchReferenceBpe() {
        val rank = tokenizer("b" j "c", "a" j "b")
        assertContentEquals(_a[97, 259], values(rank.encode("abc")))
        val tie = tokenizer("a" j "a", "aa" j "a")
        assertContentEquals(_a[256, 257], values(tie.encode("aaaaa")))
    }

    @Test fun multipleRanksAndRepeatedPairsMatchPythonTokenizers() {
        val tokenizer = tokenizer("b" j "c", "a" j "b", "a" j "a", "aa" j "a")
        val cases = _l[
            "abca" j _a[97, 259, 97], "aabc" j _a[256, 259], "aaaaa" j _a[256, 257],
            "ababc" j _a[258, 97, 259], "cabaaaa" j _a[99, 258, 256, 256],
            "aabcaab" j _a[256, 259, 97, 258], "bbcaaabc" j _a[98, 259, 257, 259],
            "baaaabca" j _a[98, 256, 256, 259, 97], "cabcabaa" j _a[99, 97, 259, 258, 256],
            "aaaaaaab" j _a[256, 256, 256, 258],
        ]
        for (case in cases) assertContentEquals(case.b, values(tokenizer.encode(case.a)), case.a)
    }

    @Test fun nfcUtf8AndSpecialTokensRetainTheirBoundaries() {
        val tokenizer = tokenizer("a" j "a")
        assertContentEquals(_a[195, 169, 260, 195, 169], values(tokenizer.encode("e\u0301<END>é")))
    }

    @Test fun singleBytePiecesStillEnforceVocabularyAndTokenLimits() {
        val tokenizer = tokenizer()
        assertContentEquals(_a[49, 50, 51], values(tokenizer.encode("123", maxLength = 3)))
        assertFailsWith<IllegalArgumentException> { tokenizer.encode("123", maxLength = 2) }
        val missing = JevaTokenizer(_m["a" j 97], emptySeriesOf<Twin<String>>())
        assertFailsWith<IllegalArgumentException> { missing.encode("1") }
    }

    @Test fun longUnbrokenInputPreservesAllMergedPairs() {
        val length = 16_384
        val tokenizer = tokenizer("a" j "a")
        val elapsed = TimeSource.Monotonic.markNow()
        val ids = tokenizer.encode("a".repeat(length), maxLength = length) {
            check(elapsed.elapsedNow().inWholeSeconds < 60) { "BPE exceeded the hang guard" }
        }
        assertEquals(length / 2, ids.size)
        assertEquals(256, ids[0])
        assertEquals(256, ids[ids.size - 1])
    }
}
