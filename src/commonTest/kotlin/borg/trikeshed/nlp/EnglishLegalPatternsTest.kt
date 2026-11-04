package borg.trikeshed.nlp

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentSource
import borg.trikeshed.narsese.DocumentAnchor
import kotlin.test.*

/** Explicit graph fixtures verify source retention and conservative reading, not parser accuracy or legal effect. */
class EnglishLegalPatternsTest {
    private fun source(text: String): DocumentSource {
        val cid = ContentId.of(text.encodeToByteArray())
        return DocumentSource(cid, cid, text, "fixture.txt", "text/plain", "fixture")
    }
    private fun parsed(text: String, number: String, unit: String = "days"): NlpDocument {
        val tokens = Regex("[A-Za-z]+(?:-[A-Za-z]+)*|[0-9]+(?:[./–-][0-9]+)*|[^\\s]").findAll(text).mapIndexed { ordinal, match ->
            val word = match.value; val lower = word.lowercase()
            NlpToken(ordinal + 1, match.range.first, match.range.last + 1, word,
                if (lower == "days") "day" else if (lower == "months") "month" else lower,
                when (lower) { "must" -> "MD"; "give", "retain" -> "VB"; "received" -> "VBN"; "not" -> "RB"; else -> "NN" }, "O")
        }.toList().toSeries()
        fun index(value: String, last: Boolean = false) = (if (last) tokens.view.last { it.word.equals(value, true) } else tokens.view.first { it.word.equals(value, true) }).index
        val action = index("give"); val unitIndex = index(unit); val numberIndex = index(number)
        val edges = mutableMapOf<Int, NlpDependency>()
        for (token in tokens.view) edges[token.index] = NlpDependency(if (token.index == action) 0 else action,
            token.index, if (token.index == action) "root" else "dep")
        fun edge(governor: Int, dependent: Int, relation: String) { edges[dependent] = NlpDependency(governor, dependent, relation) }
        edge(action, tokens[0].index, "nsubj"); edge(action, index("must"), "aux")
        edge(action, tokens[3].index, "obj"); edge(action, unitIndex, "obl")
        edge(unitIndex, numberIndex, "nummod")
        tokens.view.firstOrNull { it.word.lowercase() in _s["within", "before", "after", "by"] }?.let { edge(unitIndex, it.index, "case") }
        if (tokens.view.any { it.word.equals("unless", true) }) {
            edge(action, index("received"), "advcl"); edge(index("received"), index("unless"), "mark")
            edge(index("received"), index("notice", true), "nsubj:pass"); edge(index("received"), index("not"), "neg")
        }
        if (tokens.view.any { it.word.equals("retain", true) }) {
            edge(action, index("retain"), "conj:and"); edge(index("retain"), index("records"), "obj")
            edge(index("retain"), index("and"), "cc")
        }
        return NlpDocument(text, s_[NlpSentence(0, 0, text.length, tokens, edges.values.toList().toSeries())])
    }
    private fun inspected(text: String, number: String, unit: String = "days") = EnglishLegalPatterns.inspect(source(text), parsed(text, number, unit), 0, text.length)
    private fun sentence(result: Map<String, Any?>) = (result["sentences"] as List<*>).single() as Map<*, *>
    private fun duration(result: Map<String, Any?>) = (sentence(result)["durations"] as List<*>).single() as Map<*, *>

    @Test fun matchingDualNumberKeepsBusinessDaysAndEveryNegatedExceptionWord() {
        val text = "Tenant must give notice within thirty (30) business days unless notice is NOT received."
        val result = inspected(text, "thirty")
        val row = sentence(result)
        assertEquals(text, row["quote"]); assertEquals("unreviewed", result["reviewStatus"])
        val duration = duration(result)
        assertEquals(30, duration["integerValue"])
        assertEquals("thirty (30)", duration["quantityLiteral"])
        assertEquals("within thirty (30) business days", duration["quote"])
        assertEquals(2, (duration["numberReadings"] as List<*>).size)
        assertEquals("business", ((duration["modifiers"] as List<*>).single() as Map<*, *>)["word"])
        val markers = (row["markers"] as List<*>).map { it as Map<*, *> }
        assertTrue(markers.any { it["quote"] == "unless" }); assertTrue(markers.any { it["quote"] == "NOT" })
        assertTrue("negation-scope-not-established" in row["unresolved"] as List<*>)
        assertFalse(result.containsKey("facts")); assertFalse(result.containsKey("rules")); assertFalse(duration.containsKey("daysEquivalent"))
    }

    @Test fun conflictingNumbersRangesFractionsAndOcrTyposStayUnresolved() {
        val conflicting = duration(inspected("Tenant must give notice within thirty (60) days.", "thirty"))
        assertNull(conflicting["integerValue"])
        assertTrue("conflicting-number-forms" in conflicting["unresolved"] as List<*>)
        for (literal in s_["30-60", "1.5", "thiry", "half of 30"].view) {
            val head = if (literal == "half of 30") "30" else literal
            val text = "Tenant must give notice within $literal days."
            val candidate = duration(inspected(text, head))
            assertNull(candidate["integerValue"], literal)
            assertEquals("unresolved-quantity-candidate", candidate["status"], literal)
            assertTrue((candidate["quote"] as String).contains(literal), literal)
        }
    }

    @Test fun boundedCardinalsAndUnitModifiersDoNotBecomeCalendarArithmetic() {
        assertEquals(9999, duration(inspected("Tenant must give notice within nine thousand nine hundred ninety-nine days.", "nine"))["integerValue"])
        val month = duration(inspected("Tenant must give notice before 2 calendar months.", "2", "months"))
        assertEquals(2, month["integerValue"]); assertEquals("month", month["unitLemma"])
        assertEquals("calendar", ((month["modifiers"] as List<*>).single() as Map<*, *>)["word"])
        assertFalse(month.containsKey("daysEquivalent"))
        assertNull(duration(inspected("Tenant must give notice within ten thousand days.", "ten"))["integerValue"])
    }

    @Test fun subjectObjectRolesRemainOrderedAndCoordinationDoesNotInheritModalForce() {
        val first = sentence(inspected("Tenant must give notice within 30 days.", "30"))
        val swapped = sentence(inspected("Notice must give tenant within 30 days.", "30"))
        fun role(row: Map<*, *>, key: String): String {
            val action = (row["modalActions"] as List<*>).single() as Map<*, *>
            return (((action[key] as List<*>).single() as Map<*, *>)["node"] as Map<*, *>)["word"] as String
        }
        assertEquals("Tenant", role(first, "subjects")); assertEquals("notice", role(first, "objects"))
        assertEquals("Notice", role(swapped, "subjects")); assertEquals("tenant", role(swapped, "objects"))
        val coordinated = sentence(inspected("Tenant must give notice and retain records within thirty days.", "thirty"))
        assertEquals(1, (coordinated["modalActions"] as List<*>).size)
        assertTrue("coordination-scope-not-established" in coordinated["unresolved"] as List<*>)
    }

    @Test fun enhancedCopiesKeepDistinctModalRolesWithoutInventingSourceOccurrences() {
        val text = "Tenant must give notice within 30 days."
        val plain = parsed(text, "30"); val original = plain.sentences[0]
        val extra = s_[NlpDependency(3, 3, "conj", dependentCopy = 1), NlpDependency(3, 2, "aux", governorCopy = 1, dependentCopy = 1),
            NlpDependency(3, 1, "nsubj", governorCopy = 1, dependentCopy = 1), NlpDependency(3, 4, "obj", governorCopy = 1, dependentCopy = 1)]
        val graph = original.copy(dependencies = (original.dependencies.size + extra.size) j { index: Int ->
            if (index < original.dependencies.size) original.dependencies[index] else extra[index - original.dependencies.size] })
        val result = EnglishLegalPatterns.inspect(source(text), plain.copy(sentences = s_[graph]), 0, text.length)
        val row = sentence(result); val actions = (row["modalActions"] as List<*>).map { it as Map<*, *> }
        assertEquals(_s["3:0", "3:1"], actions.map { (it["action"] as Map<*, *>)["id"] }.toSet())
        assertEquals(original.tokens.size, (row["nodes"] as List<*>).count { (it as Map<*, *>)["kind"] == "token" })
        assertEquals(1, (row["durations"] as List<*>).size)
        assertEquals("1:1", (((actions[1]["subjects"] as List<*>).single() as Map<*, *>)["node"] as Map<*, *>)["id"])
    }

    @Test fun sourceIdentityAndExactQuotationGateCannotBeBypassed() {
        val text = "Tenant must give notice within 30 days."
        assertFailsWith<IllegalArgumentException> { EnglishLegalPatterns.inspect(source(text), parsed(text.lowercase(), "30"), 0, text.length) }
        val prefix = "📚 Preamble.\r\n"
        val full = source(prefix + text + "\r\nOther text.")
        val result = EnglishLegalPatterns.inspect(full, parsed(text, "30"), prefix.length, prefix.length + text.length)
        val quantity = duration(result)
        assertEquals(quantity["quote"], full.text.substring(quantity["begin"] as Int, quantity["end"] as Int))
        assertTrue((quantity["begin"] as Int) >= prefix.length)
        assertEquals(false, (result["range"] as Map<*, *>)["complete"])
    }

    @Test fun copiedQuantityBindingsRemainVisibleButAreNotMergedIntoAnAssertedDuration() {
        val text = "Tenant must give notice within 30 days."
        val plain = parsed(text, "30"); val original = plain.sentences[0]
        val extra = s_[NlpDependency(3, 7, "obl", dependentCopy = 1),
            NlpDependency(7, 6, "nummod", governorCopy = 1, dependentCopy = 1)]
        val graph = original.copy(dependencies = (original.dependencies.size + extra.size) j { index: Int ->
            if (index < original.dependencies.size) original.dependencies[index] else extra[index - original.dependencies.size] })
        val result = EnglishLegalPatterns.inspect(source(text), plain.copy(sentences = s_[graph]), 0, text.length)
        val candidate = duration(result)
        assertNull(candidate["integerValue"])
        assertEquals("unresolved-quantity-candidate", candidate["status"])
        assertEquals(2, (candidate["numberDependencies"] as List<*>).size)
        assertEquals(2, (candidate["unitNodes"] as List<*>).size)
        assertTrue("copy-node-quantity-scope-not-established" in candidate["unresolved"] as List<*>)
        assertEquals(1, (sentence(result)["durations"] as List<*>).size)
        assertEquals("within 30 days", candidate["quote"])
    }


    @Test fun repeatedModalRoleFanoutRefusesBeforeAnUnboundedInventoryIsBuilt() {
        val words = mutableListOf("give")
        repeat(40) { words.add("tenant$it") }; repeat(1024) { words.add("must") }
        val text = words.joinToString(" ")
        val tokens = Regex("\\S+").findAll(text).mapIndexed { index, match ->
            NlpToken(index + 1, match.range.first, match.range.last + 1, match.value, match.value,
                if (match.value == "must") "MD" else "NN", "O")
        }.toList().toSeries()
        val edges = tokens α { token -> NlpDependency(if (token.index == 1) 0 else 1, token.index,
            if (token.index == 1) "root" else if (token.word == "must") "aux" else "nsubj") }
        val graph = NlpDocument(text, s_[NlpSentence(0, 0, text.length, tokens, edges)])
        val failure = assertFailsWith<IllegalArgumentException> { EnglishLegalPatterns.inspect(source(text), graph, 0, text.length) }
        assertTrue(failure.message.orEmpty().contains("binding limit"))
    }


    @Test fun overlappingLayoutAnchorsCannotMultiplyWithoutBoundAcrossQuantityQuotes() {
        val text = "Tenant must give notice within 30 days before 40 weeks."
        val plain = parsed(text, "30")
        val sentence = plain.sentences[0]
        val extra = NlpDependency(10, 9, "nummod")
        val graph = plain.copy(sentences = s_[sentence.copy(dependencies = (sentence.dependencies.size + 1) j { index: Int ->
            if (index < sentence.dependencies.size) sentence.dependencies[index] else extra })])
        val original = source(text).copy(anchors = ((0 until 4096).toSeries() α { DocumentAnchor(0, text.length, page = 1) }).toList())
        val failure = assertFailsWith<IllegalArgumentException> { EnglishLegalPatterns.inspect(original, graph, 0, text.length) }
        assertTrue(failure.message.orEmpty().contains("quotation anchor limit"))
    }


    @Test fun observedCoreNlp46NumberHeadNpmodRetainsTheMatchingDualQuantity() {
        // Exact tokens/edges captured from the actual local 4.6.0 syntax-v1 experiment over this synthetic text.
        // This regression replays that observed parse; it does not stand in for rerunning the parser.
        val text = "Tenant must give notice within thirty (30) business days unless notice is not received."
        val tokens = s_[NlpToken(1,0,6,"Tenant","tenant","NN","O"),
            NlpToken(2,7,11,"must","must","MD","O"),
            NlpToken(3,12,16,"give","give","VB","O"),
            NlpToken(4,17,23,"notice","notice","NN","O"),
            NlpToken(5,24,30,"within","within","IN","O"),
            NlpToken(6,31,37,"thirty","thirty","CD","NUMBER"),
            NlpToken(7,38,39,"(","(","-LRB-","O"),
            NlpToken(8,39,41,"30","30","CD","NUMBER"),
            NlpToken(9,41,42,")",")","-RRB-","O"),
            NlpToken(10,43,51,"business","business","NN","DATE"),
            NlpToken(11,52,56,"days","day","NNS","DATE"),
            NlpToken(12,57,63,"unless","unless","IN","O"),
            NlpToken(13,64,70,"notice","notice","NN","O"),
            NlpToken(14,71,73,"is","be","VBZ","O"),
            NlpToken(15,74,77,"not","not","RB","O"),
            NlpToken(16,78,86,"received","receive","VBN","O"),
            NlpToken(17,86,87,".",".",".","O")]
        val edges = s_[NlpDependency(0,3,"root"),
            NlpDependency(3,1,"nsubj"),
            NlpDependency(3,2,"aux"),
            NlpDependency(3,4,"obj"),
            NlpDependency(6,5,"case"),
            NlpDependency(3,6,"obl:within"),
            NlpDependency(8,7,"punct"),
            NlpDependency(6,8,"dep"),
            NlpDependency(8,9,"punct"),
            NlpDependency(11,10,"compound"),
            NlpDependency(6,11,"obl:npmod"),
            NlpDependency(16,12,"mark"),
            NlpDependency(16,13,"nsubj:pass"),
            NlpDependency(16,14,"aux:pass"),
            NlpDependency(16,15,"advmod"),
            NlpDependency(3,16,"advcl:unless"),
            NlpDependency(3,17,"punct")]
        val parsed = NlpDocument(text, s_[NlpSentence(0, 0, text.length, tokens, edges)])
        val result = EnglishLegalPatterns.inspect(source(text), parsed, 0, text.length)
        val candidate = duration(result)
        assertEquals(30, candidate["integerValue"])
        assertEquals("within thirty (30) business days", candidate["quote"])
        assertEquals("thirty (30)", candidate["quantityLiteral"])
        assertTrue((candidate["numberDependencies"] as List<*>).any { raw ->
            val edge = raw as Map<*, *>
            edge["governorNode"] == "6:0" && edge["dependentNode"] == "11:0" && edge["relation"] == "obl:npmod"
        })
        assertTrue((candidate["markerDependencies"] as List<*>).any { raw ->
            val edge = raw as Map<*, *>
            edge["governorNode"] == "6:0" && edge["dependentNode"] == "5:0" && edge["relation"] == "case"
        })
        assertTrue("guard-scope-not-established" in candidate["unresolved"] as List<*>)
        assertTrue("negation-scope-not-established" in candidate["unresolved"] as List<*>)
        assertEquals(text, sentence(result)["quote"])
    }


    @Test fun dualNumberWhitespaceAndUnicodePrefixKeepPortableExactCaptureOffsets() {
        val prefix = "📚 Preamble.\r\n"
        val text = "Tenant must give notice within THIRTY \r\n (\t30 \t) business days."
        val original = source(prefix + text + "\r\nOther text.")
        val result = EnglishLegalPatterns.inspect(original, parsed(text, "THIRTY"), prefix.length, prefix.length + text.length)
        val candidate = duration(result)
        val readings = (candidate["numberReadings"] as List<*>).map { it as Map<*, *> }
        assertEquals(30, candidate["integerValue"])
        assertEquals("THIRTY \r\n (\t30 \t)", candidate["quantityLiteral"])
        assertEquals(_l["THIRTY", "30"], readings.map { it["quote"] })
        assertEquals(prefix.length + text.indexOf("THIRTY"), readings[0]["begin"])
        assertEquals(prefix.length + text.indexOf("30"), readings[1]["begin"])
        for (reading in readings) assertEquals(reading["quote"], original.text.substring(reading["begin"] as Int, reading["end"] as Int))
        assertEquals(false, (result["range"] as Map<*, *>)["complete"])
    }

}
