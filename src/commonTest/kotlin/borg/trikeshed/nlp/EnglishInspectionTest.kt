package borg.trikeshed.nlp

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentAnchor
import borg.trikeshed.narsese.DocumentSource
import kotlin.test.*

/** Explicit dependency fixtures test exact evidence and refusals, not a parser's linguistic accuracy. */
class EnglishInspectionTest {
    private fun source(text: String): DocumentSource {
        val cid = ContentId.of(text.encodeToByteArray())
        return DocumentSource(cid, cid, text, "fixture.txt", "text/plain", "fixture",
            anchors = _l[DocumentAnchor(0, text.length, page = 7)])
    }
    private fun parsed(text: String): NlpDocument {
        val tokens = Regex("\\S+").findAll(text).mapIndexed { index, match ->
            NlpToken(index + 1, match.range.first, match.range.last + 1, match.value,
                match.value.lowercase(), "NN", "O")
        }.toList().toSeries()
        val dependencies = (tokens α { token -> NlpDependency(if (token.index == 1) 0 else 1,
            token.index, if (token.index == 1) "root" else "dep") })
        return NlpDocument(text, s_[NlpSentence(0, 0, text.length, tokens, dependencies)],
            NlpMetadata("fixture-reader", "explicit-test-graph", _m["version" j "fixture"], _m["annotators" j "fixture"]))
    }
    private fun rows(inventory: Map<String, Any?>) = inventory["sentences"] as List<*>
    private fun first(inventory: Map<String, Any?>) = rows(inventory).first() as Map<*, *>
    private fun marks(inventory: Map<String, Any?>) = first(inventory)["markers"] as List<*>

    @Test fun partialRangeKeepsExactGlobalSpansAndAllExceptionNegationMarkers() {
        val prefix = "📚 Preamble.\r\n"
        val text = "Tenant may occupy unless rent is not due, except as provided in Section 2.3(a)."
        val original = source(prefix + text + "\r\nOther terms.")
        val result = EnglishInspection.inspect(original, parsed(text), prefix.length, prefix.length + text.length)
        assertEquals("unreviewed", result["reviewStatus"])
        assertEquals("lexical-and-parser-observations-only", result["authority"])
        assertEquals(original.originalCid.value, result["originalCid"])
        assertEquals(false, (result["range"] as Map<*, *>)["complete"])
        val row = first(result)
        assertEquals(text, row["quote"])
        val quotation = row["quotation"] as Map<*, *>
        assertEquals(prefix.length, quotation["begin"])
        assertEquals(text, quotation["quote"])
        assertEquals("unverified", quotation["authentication"])
        assertEquals(7, ((quotation["anchors"] as List<*>).first() as Map<*, *>)["page"])
        val markers = marks(result).map { it as Map<*, *> }
        assertEquals(_s["condition", "exception"], markers.filter { it["quote"] == "unless" }.map { it["kind"] }.toSet())
        assertTrue(markers.any { it["quote"] == "not" && it["kind"] == "negation" })
        assertTrue(markers.any { it["quote"] == "except" && it["kind"] == "exception" })
        assertTrue(markers.any { it["quote"] == "Section 2.3(a)" && it["kind"] == "cross-reference" })
        for (marker in markers) {
            assertEquals(marker["quote"], original.text.substring(marker["begin"] as Int, marker["end"] as Int))
            assertEquals("lexical-candidate", marker["status"])
        }
        val firstToken = (row["tokens"] as List<*>).first() as Map<*, *>
        assertEquals(prefix.length, firstToken["begin"])
        assertEquals("fixture-reader", (result["parser"] as Map<*, *>)["processor"])
        assertFalse(result.containsKey("facts")); assertFalse(result.containsKey("rules"))
    }

    @Test fun multipleConditionsDefinitionsAndContractionsAreCandidatesWithoutScopeInference() {
        val text = "If Term means café, Tenant shall not act unless notice arrives, subject to Section 4; notwithstanding that, Tenant can't act provided that approval is absent."
        val result = EnglishInspection.inspect(source(text), parsed(text), 0, text.length)
        val markers = marks(result).map { it as Map<*, *> }
        for (value in s_["If", "means", "shall", "not", "unless", "subject to", "Section 4", "notwithstanding", "n't", "provided that"].view)
            assertTrue(markers.any { it["quote"] == value }, value)
        assertEquals(text, first(result)["quote"])
        assertEquals(true, (result["range"] as Map<*, *>)["complete"])
        assertEquals("not-established", (result["coverage"] as Map<*, *>)["semantics"])
    }

    @Test fun mismatchedIdentityAndTransformedParserTextAreRefused() {
        val text = "TENANT SHALL NOT ACT."
        val original = source(text)
        assertFailsWith<IllegalArgumentException> {
            EnglishInspection.inspect(original, parsed("Tenant shall not act."), 0, text.length)
        }
        assertFailsWith<IllegalArgumentException> {
            EnglishInspection.inspect(original.copy(extractedTextCid = ContentId.of("other".encodeToByteArray())), parsed(text), 0, text.length)
        }
        val unicode = source("📚 Tenant may act.")
        assertFailsWith<IllegalArgumentException> { EnglishInspection.inspect(unicode, parsed(unicode.text.substring(1)), 1, unicode.text.length) }
        assertFailsWith<IllegalArgumentException> {
            EnglishInspection.inspect(unicode.copy(anchors = _l[DocumentAnchor(1, unicode.text.length)]), parsed(unicode.text), 0, unicode.text.length)
        }
        val malformed = "Tenant \uD800 may act."
        assertFailsWith<IllegalArgumentException> { EnglishInspection.inspect(source(malformed), parsed(malformed), 0, malformed.length) }
    }

    @Test fun malformedSpansDuplicateIndicesAndDanglingEdgesRefuseEntireInventory() {
        val text = "Tenant may act."
        val original = source(text)
        val good = parsed(text)
        val sentence = good.sentences[0]
        val token = sentence.tokens[0]
        fun refused(changed: NlpSentence) = assertFailsWith<IllegalArgumentException> {
            EnglishInspection.inspect(original, good.copy(sentences = s_[changed]), 0, text.length)
        }
        refused(sentence.copy(tokens = s_[token, token]))
        refused(sentence.copy(tokens = s_[token.copy(word = "Other"), sentence.tokens[1], sentence.tokens[2]]))
        refused(sentence.copy(tokens = s_[token.copy(begin = 1), sentence.tokens[1], sentence.tokens[2]]))
        refused(sentence.copy(dependencies = s_[NlpDependency(0, 1, "root"), NlpDependency(1, 99, "dep")]))
        refused(sentence.copy(dependencies = s_[NlpDependency(0, 1, "root"), NlpDependency(0, 2, "root"), NlpDependency(1, 3, "dep")]))
        refused(sentence.copy(dependencies = s_[NlpDependency(0, 1, "root"), NlpDependency(1, 2, "dep"), NlpDependency(1, 2, "dep"), NlpDependency(1, 3, "dep")]))
        refused(sentence.copy(dependencies = s_[NlpDependency(0, 1, "root"), NlpDependency(2, 3, "dep"), NlpDependency(3, 2, "dep")]))
        refused(sentence.copy(end = text.length - 1))
    }

    @Test fun enhancedGraphCyclesArePreservedAsParserObservations() {
        val text = "Tenant may act."
        val good = parsed(text)
        val graph = good.sentences[0].copy(dependencies = s_[NlpDependency(0, 1, "root"), NlpDependency(1, 2, "dep"),
            NlpDependency(2, 3, "dep"), NlpDependency(3, 2, "ref")])
        val result = EnglishInspection.inspect(source(text), good.copy(sentences = s_[graph]), 0, text.length)
        assertEquals(4, (first(result)["dependencies"] as List<*>).size)
    }

    @Test fun limitsRefuseRatherThanOmitAndWhitespaceHasExplicitEmptyObservation() {
        val text = "x".repeat(EnglishInspection.MAX_CHARS + 1)
        assertFailsWith<IllegalArgumentException> { EnglishInspection.inspect(source(text), parsed(text), 0, text.length) }
        val whitespace = " \r\n "
        val result = EnglishInspection.inspect(source(whitespace), NlpDocument(whitespace, emptySeriesOf()), 0, whitespace.length)
        assertEquals(0, rows(result).size)
        assertNull(result["parser"])
        val nonblank = "Tenant may act."
        assertFailsWith<IllegalArgumentException> { EnglishInspection.inspect(source(nonblank), NlpDocument(nonblank, emptySeriesOf()), 0, nonblank.length) }
    }
}
