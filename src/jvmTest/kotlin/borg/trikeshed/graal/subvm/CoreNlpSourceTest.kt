package borg.trikeshed.graal.subvm

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.nlp.*
import kotlin.test.*

class CoreNlpSourceTest {
    private fun parsed(input: String): NlpDocument {
        val words = Regex("[\\p{L}\\p{N}]+|[^\\s]").findAll(input).toList()
        val tokens = words.mapIndexed { index, match ->
            val word = match.value
            NlpToken(index + 1, match.range.first, match.range.last + 1, word, word.lowercase(),
                if (word == ".") "." else "NN", "O")
        }.toSeries()
        val dependencies = s_[NlpDependency(0, 1, "root")]
        val gloss = NlpRelation(1..1, 2..2, 3..3, "parser subject", "parser relation", "parser object", 0.75)
        val metadata = NlpMetadata("fixture", "fixture", _m["profile" j "full-v1", "jarSha256" j "fixture"],
            CoreNlpRuntime.Profile.FULL.configuration)
        return NlpDocument(input, s_[NlpSentence(0, 0, input.length, tokens, dependencies, _l[gloss])], metadata)
    }

    @Test fun allCapsParserViewKeepsTheExactSourceForGrounding() {
        val source = "TENANT SHALL NOT DISCLOSE CUSTOMER DATA."
        val input = CoreNlpRuntime.sentenceCase(source)
        assertNotEquals(source, input)
        val reading = parsed(input)
        val restored = CoreNlpRuntime.sourceDocument(source, reading)
        assertEquals(source, restored.text)
        for (token in restored.sentences[0].tokens.view)
            assertEquals(source.substring(token.begin, token.end), token.word)
        assertEquals("SHALL", restored.sentences[0].tokens[1].word)
        assertEquals("shall", restored.sentences[0].tokens[1].lemma)
        assertSame(reading.sentences[0].dependencies, restored.sentences[0].dependencies)
        assertSame(reading.sentences[0].relations, restored.sentences[0].relations)
        assertEquals(reading.metadata!!.configuration, restored.metadata!!.configuration)
        val runtime = restored.metadata!!.runtime
        assertEquals("sentence-case/1", runtime["parserNormalization"])
        assertEquals(ContentId.of(source.encodeToByteArray()).value, runtime["sourceTextCid"])
        assertEquals(ContentId.of(input.encodeToByteArray()).value, runtime["parserInputCid"])
        assertEquals("parser-input", runtime["relationGlossSource"])
        assertEquals("fixture", runtime["jarSha256"])

        val sourceCid = ContentId.of(source.encodeToByteArray())
        val document = DocumentSource(sourceCid, sourceCid, source, "fixture", "text/plain", "fixture")
        val before = DocumentCurationRecord(document, reading, null, "none", emptySeriesOf(), emptySeriesOf())
        val after = before.copy(nlp = restored)
        assertNotEquals(DocumentNlpStatus.AVAILABLE, before.curationIndex().facet(DocumentCurationIndexK.NlpStatus))
        assertEquals(DocumentNlpStatus.AVAILABLE, after.curationIndex().facet(DocumentCurationIndexK.NlpStatus))
    }

    @Test fun identityAndStitchedViewsRetainOriginalOffsetsAndProfile() {
        val first = "Ordinary text 🦉.\n"
        val second = "TENANT SHALL NOT DISCLOSE CUSTOMER DATA."
        val source = first + second
        val input = CoreNlpRuntime.sentenceCase(source)
        val stitched = CoreNlpRuntime.stitch(input, _l[(0 j parsed(first)).pair,
            (first.length j parsed(CoreNlpRuntime.sentenceCase(second))).pair])
        val restored = CoreNlpRuntime.sourceDocument(source, stitched)
        assertEquals(source, restored.text)
        assertEquals(first.length, restored.sentences[1].begin)
        assertEquals("TENANT", restored.sentences[1].tokens[0].word)
        assertEquals("tenant", restored.sentences[1].tokens[0].lemma)
        assertEquals(source.substring(restored.sentences[1].begin, restored.sentences[1].end), second)

        val unchanged = CoreNlpRuntime.sourceDocument(first, parsed(first))
        assertEquals("identity/1", unchanged.metadata!!.runtime["parserNormalization"])
        assertEquals(unchanged.metadata!!.runtime["sourceTextCid"], unchanged.metadata!!.runtime["parserInputCid"])
    }

    @Test fun badParserSpansAndMalformedSourceFailBeforeCaching() {
        val source = "TENANT SHALL NOT DISCLOSE CUSTOMER DATA."
        val reading = parsed(CoreNlpRuntime.sentenceCase(source))
        assertFailsWith<IllegalArgumentException> { CoreNlpRuntime.sourceDocument(source, reading.copy(text = source)) }
        fun tokenChanged(transform: (NlpToken) -> NlpToken) = reading.copy(sentences = reading.sentences α { sentence ->
            sentence.copy(tokens = sentence.tokens α transform)
        })
        assertFailsWith<IllegalArgumentException> {
            CoreNlpRuntime.sourceDocument(source, tokenChanged { it.copy(word = "invented") })
        }
        assertFailsWith<IllegalArgumentException> {
            CoreNlpRuntime.sourceDocument(source, tokenChanged { it.copy(end = source.length + 1) })
        }
        val emoji = "🦉"
        val cut = NlpDocument(emoji, s_[NlpSentence(0, 0, emoji.length,
            s_[NlpToken(1, 0, 1, emoji.substring(0, 1), "owl", "NN", "O")], s_[NlpDependency(0, 1, "root")])])
        assertFailsWith<IllegalArgumentException> { CoreNlpRuntime.sourceDocument(emoji, cut) }
        assertFails { CoreNlpRuntime.sourceDocument("\uD800", NlpDocument("\uD800", emptySeriesOf())) }
    }

    @Test fun hardChunkBoundaryDoesNotSplitASurrogatePair() {
        val text = "A".repeat(CoreNlpRuntime.PARSE_CHARS - 1) + "🦉" + "B".repeat(50)
        val pieces = CoreNlpRuntime.pieces(text)
        assertEquals(CoreNlpRuntime.PARSE_CHARS - 2, pieces.first().last)
        assertEquals(text, pieces.joinToString("") { text.substring(it.first, it.last + 1) })
        for (piece in pieces) text.substring(piece.first, piece.last + 1).encodeToByteArray(throwOnInvalidSequence = true)
    }

    @Test fun syntaxIsAnExplicitSeparateProfileWithoutRelationAnnotators() {
        assertEquals("syntax-v1", CoreNlpRuntime.Profile.SYNTAX.id)
        assertEquals(_m["annotators" j "tokenize,ssplit,pos,lemma,depparse,ner", "threads" j "1"],
            CoreNlpRuntime.Profile.SYNTAX.configuration)
        assertNotEquals(CoreNlpRuntime.Profile.FULL.configuration, CoreNlpRuntime.Profile.SYNTAX.configuration)
        assertNotEquals(CoreNlpRuntime.Profile.NO_KBP.configuration, CoreNlpRuntime.Profile.SYNTAX.configuration)
        assertEquals(CoreNlpRuntime.Profile.entries.size, CoreNlpRuntime.Profile.entries.map { it.id }.distinct().size)
    }
}
