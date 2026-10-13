package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.test.*

class BertWordPieceTest {
    // IDs and UTF-16 offsets were recorded from the cached fast tokenizer at revision 86b5e093.
    // The fixture vocabulary retains those IDs without requiring model files in common tests.
    val words = mapOf(
        0 to "[PAD]", 100 to "[UNK]", 101 to "[CLS]", 102 to "[SEP]", 103 to "[MASK]",
        7592 to "hello", 7668 to "cafe", 15743 to "naive", 13746 to "resume", 1012 to ".",
        1989 to "，", 1745 to "世", 1986 to "！", 11113 to "ab", 1038 to "b", 1039 to "c", 2139 to "de",
        14477 to "una", 20961 to "##ffa", 3468 to "##ble", 2652 to "playing",
        1031 to "[", 7308 to "mask", 1033 to "]", 2924 to "bank", 8169 to "banking",
        2003 to "is", 2330 to "open", 1998 to "and", 2498 to "nothing", 2182 to "here",
        1045 to "i", 1047 to "k", 1469 to "ᄒ", 30006 to "##ᅡ", 30021 to "##ᆫ",
        29991 to "##ᄀ", 30017 to "##ᅳ", 30022 to "##ᆯ", 1037 to "a", 1155 to "α",
    )
    val tokenizer = BertWordPiece(30_522 j { i: Int -> words[i] ?: "[unused-fixture-$i]" })

    fun sentence(text: String, ids: IntArray, offsets: IntArray) {
        val encoded = tokenizer.sentence(text)
        assertContentEquals(ids, encoded.ids.toArray())
        assertEquals(ids.size * 2, offsets.size)
        for (i in 0 until encoded.offsets.size) {
            assertEquals(offsets[i * 2], encoded.offsets[i].a, "start of piece $i in $text")
            assertEquals(offsets[i * 2 + 1], encoded.offsets[i].b, "end of piece $i in $text")
        }
    }

    @Test
    fun cachedFastTokenizerNormalizationAndOffsetsMatch() {
        sentence("HéLLo café naïve résumé.", intArrayOf(101, 7592, 7668, 15743, 13746, 1012, 102),
            intArrayOf(0, 0, 0, 5, 6, 10, 11, 16, 17, 23, 23, 24, 0, 0))
        sentence("你好，世界！", intArrayOf(101, 100, 100, 1989, 1745, 100, 1986, 102),
            intArrayOf(0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 0, 0))
        sentence("A\u0000B\tC\nD\u200bE", intArrayOf(101, 11113, 1039, 2139, 102),
            intArrayOf(0, 0, 0, 3, 4, 5, 6, 9, 0, 0))
        sentence("A\u2028B\u2029C", intArrayOf(101, 1037, 1038, 1039, 102),
            intArrayOf(0, 0, 0, 1, 2, 3, 4, 5, 0, 0))
        sentence("unaffable playing [MASK] [mask]", intArrayOf(101, 14477, 20961, 3468, 2652, 103, 1031, 7308, 1033, 102),
            intArrayOf(0, 0, 0, 3, 3, 6, 6, 9, 10, 17, 18, 24, 25, 26, 26, 30, 30, 31, 0, 0))
        sentence("😀 bank", intArrayOf(101, 100, 2924, 102), intArrayOf(0, 0, 0, 2, 3, 7, 0, 0))
        sentence("a𜼐b bank", intArrayOf(101, 100, 2924, 102), intArrayOf(0, 0, 0, 4, 5, 9, 0, 0))
        sentence("a\ue000b bank", intArrayOf(101, 11113, 2924, 102), intArrayOf(0, 0, 0, 3, 4, 8, 0, 0))
        sentence("İ ſ K café́ 한글 ắ ᾈ", intArrayOf(101, 1045, 100, 1047, 7668, 1469, 30006, 30021, 29991, 30017, 30022, 1037, 1155, 102),
            intArrayOf(0, 0, 0, 1, 2, 3, 4, 5, 6, 10, 12, 13, 12, 13, 12, 13, 13, 14, 13, 14, 13, 14, 15, 16, 17, 18, 0, 0))
    }

    @Test
    fun donorTargetsRetainMatchingMethodsAndAbstentions() {
        fun target(token: BertToken, span: BertSpan, vararg pieces: Int) {
            val input = tokenizer.encode(token)
            assertEquals(span, input.span, token.toString())
            assertContentEquals(pieces, input.target.toArray())
        }
        target(BertToken("café", "HéLLo café naïve résumé."), BertSpan.Exact, 2)
        target(BertToken("世界", "你好，世界！"), BertSpan.Exact, 4, 5)
        target(BertToken("unaffable", "unaffable playing [MASK] [mask]"), BertSpan.Exact, 1, 2, 3)
        target(BertToken("bank", "😀 bank", 3, 7), BertSpan.Explicit, 2)
        target(BertToken("bank", "banking is open"), BertSpan.Stem, 1)
        target(BertToken("bank", "bank and BANK"), BertSpan.Ambiguous)
        target(BertToken("bank", "Nothing here."), BertSpan.Missing)
        target(BertToken("café́", "café́"), BertSpan.Truncated)
        target(BertToken("한글", "İ ſ K café́ 한글 ắ ᾈ"), BertSpan.Exact, 5, 6, 7, 8, 9, 10)
    }

    @Test
    fun partialWordPieceTruncationCannotBecomeATargetVector() {
        val text = "hello ".repeat(508) + "unaffable"
        val encoded = tokenizer.sentence(text)
        val input = tokenizer.encode(BertToken("unaffable", text), encoded)
        assertEquals(512, input.ids.size)
        assertEquals(101, input.ids[0])
        assertEquals(14477, input.ids[509])
        assertEquals(20961, input.ids[510])
        assertEquals(102, input.ids[511])
        assertEquals(BertSpan.Truncated, input.span)
        assertEquals(0, input.target.size)
        assertSame(encoded.ids, input.ids)
        assertSame(encoded.offsets, input.offsets)
        val last = tokenizer.encode(BertToken("bank", "hello ".repeat(509) + "bank"))
        assertEquals(BertSpan.Exact, last.span)
        assertContentEquals(intArrayOf(510), last.target.toArray())
        val beyond = tokenizer.encode(BertToken("bank", "hello ".repeat(510) + "bank"))
        assertEquals(BertSpan.Truncated, beyond.span)
        assertEquals(0, beyond.target.size)
        assertFailsWith<IllegalArgumentException> { tokenizer.encode(BertToken("bank", "bank"), encoded) }
    }

    @Test
    fun unknownWordsAreAtomicAndTheWordLimitCountsUnicodeScalars() {
        sentence("helloqx", intArrayOf(101, 100, 102), intArrayOf(0, 0, 0, 7, 0, 0))
        val scalarTokenizer = BertWordPiece(s_["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "😀", "##😀"])
        val hundred = scalarTokenizer.sentence("😀".repeat(100))
        assertEquals(102, hundred.ids.size)
        assertEquals(5, hundred.ids[1])
        assertEquals(6, hundred.ids[100])
        assertEquals(198, hundred.offsets[100].a)
        assertEquals(200, hundred.offsets[100].b)
        val over = scalarTokenizer.sentence("😀".repeat(101))
        assertContentEquals(intArrayOf(2, 1, 3), over.ids.toArray())
        assertEquals(202, over.offsets[1].b)
        assertFailsWith<IllegalArgumentException> { tokenizer.encode(BertToken("bank", "😀 bank", 1, 7)) }
        assertFailsWith<IllegalArgumentException> { tokenizer.encode(BertToken("bank", "bank", 0, null)) }
    }
}
