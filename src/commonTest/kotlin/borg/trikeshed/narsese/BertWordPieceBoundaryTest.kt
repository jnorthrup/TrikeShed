package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.test.*

class BertWordPieceBoundaryTest {
    private val tokenizer = BertWordPiece(s_["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "act", "acted", ".", "cafe", "de", "facto", "中"])

    @Test fun inferredTargetDoesNotResolveToPartOfAnotherWord() {
        for (text in s_["act2", "acté", "act🚀"].view) {
            val result = tokenizer.encode(BertToken("act", text))
            assertEquals(BertSpan.Missing, result.span, text)
            assertEquals(0, result.target.size, text)
        }
    }

    @Test fun independentExactWordWinsOverAnEmbeddedPrefix() {
        val result = tokenizer.target(BertToken("act", "act2 act."))
        assertEquals(BertSpan.Exact, result.b)
        assertEquals(5, result.a?.a)
        assertEquals(8, result.a?.b)
    }

    @Test fun validWordBoundariesAndAsciiInflectionsRemainSupported() {
        assertEquals(BertSpan.Exact, tokenizer.target(BertToken("act", "(act)")).b)
        assertEquals(BertSpan.Exact, tokenizer.target(BertToken("act", "中act中")).b)
        val stem = tokenizer.target(BertToken("act", "acted"))
        assertEquals(BertSpan.Stem, stem.b)
        assertEquals(0, stem.a?.a)
        assertEquals(5, stem.a?.b)
    }

    @Test fun normalizedMatchesKeepOffsetsThatWordPiecesActuallyCover() {
        val accent = tokenizer.encode(BertToken("act", "act\u0301"))
        assertEquals(BertSpan.Exact, accent.span)
        assertEquals(1, accent.target.size)
        assertEquals(0, accent.offsets[accent.target[0]].a)
        assertEquals(3, accent.offsets[accent.target[0]].b)

        val joined = tokenizer.encode(BertToken("act", "a\u200bct"))
        assertEquals(BertSpan.Exact, joined.span)
        assertEquals(1, joined.target.size)
        assertEquals(4, joined.offsets[joined.target[0]].b)
        assertEquals(BertSpan.Exact, tokenizer.encode(BertToken("café", "CAFE")).span)

        val surrogate = tokenizer.target(BertToken("中", "🚀中"))
        assertEquals(BertSpan.Exact, surrogate.b)
        assertEquals(2, surrogate.a?.a)
        assertEquals(3, surrogate.a?.b)
    }

    @Test fun phrasesAndExplicitSourceOffsetsRemainSupported() {
        val phrase = tokenizer.encode(BertToken("de facto", "de   facto"))
        assertEquals(BertSpan.Exact, phrase.span)
        assertEquals(2, phrase.target.size)
        val explicit = tokenizer.encode(BertToken("act", "act2", 0, 3))
        assertEquals(BertSpan.Explicit, explicit.span)
        assertEquals(1, explicit.target.size)
    }

    @Test fun targetSemanticsCannotReuseThePreviousModelIdentity() {
        assertNotEquals("bert-common-f32-1", BertIdentity.VERSION)
    }
}
