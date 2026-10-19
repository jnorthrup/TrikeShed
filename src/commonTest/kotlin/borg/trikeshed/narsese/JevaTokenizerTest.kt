package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.test.*

class JevaTokenizerTest {
    /** Outputs captured from tokenizers 0.23.3 NFC, including its pinned Unicode version. */
    @Test fun canonicalNormalization() {
        val cases = s_[
            "A\u030a\u0301" j "\u01fa",
            "a\u0315\u0300\u0327" j "\u00e0\u0327\u0315",
            "\u0344" j "\u0308\u0301",
            "\u0958" j "\u0915\u093c",
            "\u0f73" j "\u0f71\u0f72",
            "\uf900" j "\u8c48",
            "\u1100\u1161\u11a8" j "\uac01",
            "\uac00\u11a8" j "\uac01",
            "a\u0305\u0301" j "a\u0305\u0301",
            "A\u0301\u0327" j "\u00c1\u0327",
            "\ud834\udd5e" j "\ud834\udd57\ud834\udd65",
            "\ud839\udcec\u0301" j "\ud839\udcec\u0301",
            "a\ud839\udcec\u0327" j "a\ud839\udcec\u0327",
            "\u0301\u0327A" j "\u0327\u0301A",
            "a\u07fd\u0301" j "a\u07fd\u0301",
            "\u07fd\u0300\u0327" j "\u07fd\u0327\u0300",
            "\u0897\u0300\u0327" j "\u0897\u0327\u0300",
            "" j "",
        ]
        for ((input, expected) in cases.view) {
            assertEquals(expected, JevaTokenizer.normalize(input))
            assertEquals(expected, JevaTokenizer.normalize(expected), "NFC is idempotent")
        }
    }

    /** The checkpoint regex, executed independently by the Rust tokenizer before byte encoding. */
    @Test fun unicodePretokenization() {
        val cases = s_[
            "'\u017f I'd I'M" j s_["'\u017f", " I", "'d", " I", "'M"],
            "  word\t  next\r\n\n  " j s_[" ", " word", "\t ", " next", "\r\n\n", "  "],
            "\u0085\u00a0X" j s_["\u0085", "\u00a0X"],
            "a\u0301 12Ⅳ½٢²𐒠3" j s_["a\u0301", " ", "1", "2", "Ⅳ", "½", "٢", "²", "𐒠", "3"],
            "😀🧑\u200d⚖️\r\nword" j s_["😀🧑\u200d⚖", "\ufe0f", "\r\n", "word"],
        ]
        for ((input, expected) in cases.view) {
            val actual = JevaTokenizer.split(input)
            assertEquals(expected.toList(), actual.toList())
            assertEquals(input, actual.view.joinToString(""))
        }
    }

    @Test fun invalidScalarsFailBeforeByteEncoding() {
        val tokenizer = JevaTokenizer(emptyMap(), emptySeriesOf())
        assertFailsWith<IllegalArgumentException> { tokenizer.encode("\ud800") }
        assertFailsWith<IllegalArgumentException> { tokenizer.encode("\udc00") }
        assertFailsWith<IllegalArgumentException> { tokenizer.encode("", maxLength = 0) }
        assertEquals(0, tokenizer.encode("").size)
    }
}
