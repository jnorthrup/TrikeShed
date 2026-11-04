package borg.trikeshed.narsese

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.rdf.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Real KG crossing; synthetic data, declared context, no review authority or live state. */
class KgRdfFidelityTest {
    private val context = ContentId.of("Synthetic shared RDF source context".encodeToByteArray())
    private fun one(obj: String) = KgNalBridge.parseTurtle("<urn:s> <urn:p> $obj .").single()
    private fun id(row: KgTriplet) = KgNalBridge.map(row).propositionCid(context.value)

    @Test fun iriLiteralAndBlankNodeKindsDoNotCollapseInTheSameSourceContext() {
        val iri = one("<urn:o>")
        val literal = one("\"urn:o\"")
        val blank = one("_:o")
        val blankText = one("\"_:o\"")
        assertEquals("<urn:s>", iri.subject)
        assertEquals("urn:p", iri.predicate)
        assertEquals("<urn:o>", iri.obj); assertEquals("\"urn:o\"", literal.obj)
        assertEquals("_:o", blank.obj); assertEquals("\"_:o\"", blankText.obj)
        assertNotEquals(id(iri), id(literal)); assertNotEquals(id(blank), id(blankText))
        assertEquals(RdfTerm.Iri("urn:o"), TurtleRdf.parseTerm(iri.obj))
        assertEquals(RdfTerm.Literal("urn:o"), TurtleRdf.parseTerm(literal.obj))
    }

    @Test fun punctuationEscapesAndUnicodeRemainOneCompleteLiteral() {
        val examples = s_[
            "\"Smith, Jones\"" j "Smith, Jones",
            "\"Alpha; beta.\"" j "Alpha; beta.",
            "\"First. Second # kept\"" j "First. Second # kept",
            "\"He said \\\"hello\\\", then left\"" j "He said \"hello\", then left",
            "\"backslash\\\\n\"" j "backslash\\n",
            "\"line\\n\\tend\"" j "line\n\tend",
            "\"raw\ttab\"" j "raw\ttab",
            "\"\"\"long\r\n@prefix this: is literal content\nend\"\"\"" j "long\r\n@prefix this: is literal content\nend",
            "\"\\u0061\\U0001F600\"" j "a\uD83D\uDE00",
            "\"\\b\\f\\u0000\"" j "\b\u000c\u0000"]
        for ((spelling, expected) in examples.view) {
            val rows = KgNalBridge.parseTurtle("<urn:s> <urn:p> $spelling .")
            assertEquals(1, rows.size, spelling)
            assertEquals(RdfTerm.Literal(expected), TurtleRdf.parseTerm(rows.single().obj), spelling)
            assertEquals(rows.single().obj, one(rows.single().obj).obj, "Canonical term must round-trip")
        }
    }

    @Test fun languageDatatypeAndLexicalSpellingRemainPartOfTheTerm() {
        val english = one("\"hello\"@en-US"); val french = one("\"hello\"@fr")
        assertEquals(RdfTerm.Literal("hello", lang = "en-US"), TurtleRdf.parseTerm(english.obj))
        assertNotEquals(id(english), id(french))
        val typed = one("\"01\"^^xsd:integer")
        assertEquals(RdfTerm.Literal("01", RdfVocab.XSD + "integer"), TurtleRdf.parseTerm(typed.obj))
        assertNotEquals(id(typed), id(one("\"1\"^^xsd:integer")))
        val custom = KgNalBridge.parseTurtle("@prefix ex: <urn:example:> .\n<urn:s> <urn:p> \"01\"^^ex:Count .").single()
        assertEquals(RdfTerm.Literal("01", "urn:example:Count"), TurtleRdf.parseTerm(custom.obj))
    }

    @Test fun prefixesShorthandAndPredicateIriMappingRemainExplicit() {
        val rows = KgNalBridge.parseTurtle("@prefix ex: <https://example.invalid/ns#> .\nex:s a ex:C ; ex:p ex:o, \"https://example.invalid/ns#o\" . # outside comment")
        assertEquals(3, rows.size)
        assertEquals("<https://example.invalid/ns#s>", rows[0].subject)
        assertEquals(RdfVocab.RDF + "type", rows[0].predicate)
        assertEquals(NalCopula.INHERITANCE, KgNalBridge.map(rows[0]).copula)
        assertEquals("https://example.invalid/ns#p", rows[1].predicate)
        assertNotEquals(id(rows[1]), id(rows[2]))
        val full = KgNalBridge.parseTurtle("<https://example.invalid/ns#s> <https://example.invalid/ns#p> <https://example.invalid/ns#o> .").single()
        assertEquals(id(rows[1]), id(full), "Expanded namespace aliases preserve the same RDF term")
        val default = KgNalBridge.parseTurtle("@prefix : <urn:default:> .\n:s :p :o .").single()
        assertEquals("<urn:default:s>", default.subject); assertEquals("urn:default:p", default.predicate)
        assertEquals("<urn:default:o>", default.obj)
    }

    @Test fun unsupportedAndMalformedInputNeverYieldsAValidPrefix() {
        val invalid = s_[
            "<urn:s> <urn:p> \"hello\"garbage .", "<urn:s> <urn:p> \"bad\\q\" .",
            "<urn:s> <urn:p> \"a\rb\" .", "<urn:s> <urn:p> \"a\nb\" .",
            "<urn:s> <urn:p> 'a\rb' .",
            "<urn:s> <urn:p> \"bad\\uD800\" .", "<urn:s> <urn:p> \"bad\\U00110000\" .",
            "<urn:s> <urn:p> \"bad\\u+001\" .", "<urn:s> <urn:p> \"bad\\uGGGG\" .",
            "<urn:s> <urn:p> \"bad\\u123\" .", "<urn:s> <urn:p> \"hello\"@en--US .",
            "<urn:s> <urn:p> \"hello\"^^<relative> .", "<urn:s> <urn:p> \"unterminated .",
            "<urn:s> <urn:p> <urn:o>", "<urn:s> <urn:p> <urn:o> <urn:extra> .",
            "GRAPH <urn:g> { <urn:s> <urn:p> <urn:o> . }", "{ <urn:s> <urn:p> <urn:o> . }",
            "<urn:s> <urn:p> (<urn:o>) .", "<urn:s> <urn:p> [ <urn:p> <urn:o> ] .",
            "missing:s missing:p missing:o .", "<relative> <urn:p> <urn:o> .",
            "\"subject\" <urn:p> <urn:o> .", "<urn:s> \"predicate\" <urn:o> .",
            "@base <urn:base:> .\n<relative> <urn:p> <urn:o> .", "?s <urn:p> <urn:o> .",
            "<urn:s> <urn:p> 12 .", "<urn:s> <urn:p> <urn:o> .\n@prefix ex: <urn:example:> .",
            "@prefix ex: <urn:example:> . ex:s ex:p ex:o ."]
        for (text in invalid.view) {
            assertFailsWith<IllegalArgumentException>(text) { KgNalBridge.parseTurtle(text) }
            if (!text.startsWith('@')) assertFailsWith<IllegalArgumentException>(text) {
                KgNalBridge.parseTurtle("<urn:valid> <urn:p> <urn:o> .\n$text")
            }
        }
        for (bad in s_["\uD800", "\uD801", "\uDC00"].view)
            assertFailsWith<CharacterCodingException> { KgNalBridge.parseTurtle("<urn:s> <urn:p> \"$bad\" .") }
        assertFailsWith<IllegalArgumentException> { KgNalBridge.parseTurtle(" ".repeat(65_537) + "<urn:s> <urn:p> <urn:o> .") }
        val many = (0 until 2049).toSeries() α { "<urn:o>" }
        assertFailsWith<IllegalArgumentException> { KgNalBridge.parseTurtle("<urn:s> <urn:p> " + many.view.joinToString(",") + " .") }
    }

    @Test fun structuralCommentLineEndingsPreserveAllTriplesAndLongLiteralContent() {
        val expected = _l["<urn:a>", "<urn:b>"]
        for (eol in s_["\r", "\r\n", "\n"].view) {
            val input = "# header${eol}@prefix ex: <urn:> .${eol}" +
                "ex:a ex:p ex:o . # middle${eol}ex:b ex:p ex:o ."
            assertEquals(expected, KgNalBridge.parseTurtle(input).map { it.subject }, "Structural EOL must not swallow valid data")
        }
        val mixed = "# header\r@prefix ex: <urn:> .\r\nex:a ex:p ex:o . # middle\rex:b ex:p ex:o .\n"
        assertEquals(expected, KgNalBridge.parseTurtle(mixed).map { it.subject })
        val literal = "a\rb\r\nc\nd"
        val long = "<urn:a> <urn:p> \"\"\"$literal\"\"\" . # tail\r<urn:b> <urn:p> <urn:o> ."
        val rows = KgNalBridge.parseTurtle(long)
        assertEquals(2, rows.size)
        assertEquals(RdfTerm.Literal(literal), TurtleRdf.parseTerm(rows[0].obj), "Long literal CR/CRLF/LF must remain exact")
        assertEquals("<urn:b>", rows[1].subject)
        assertFailsWith<IllegalArgumentException> { KgNalBridge.parseTurtle("<urn:a> <urn:p> \"a\rb\" .") }
        // Existing permissive readers retain their previous separate behavior.
        assertEquals(1, TurtleRdf.parse("# header\r<urn:a> <urn:p> <urn:o> .").triples.size)
    }

    @Test fun actualTextConsumerIsAtomicAtParsingAndOtherEntryPointsRemainSeparate(): Unit = runBlocking {
        withTimeout(10_000) {
            val bag = BeliefBagElement(cas = KgRetentionFixtureCas(), parentJob = currentCoroutineContext()[Job]); bag.open()
            try {
                assertFailsWith<IllegalArgumentException> { bag.ingestKg("<urn:valid> <urn:p> <urn:o> .\n<urn:s> <urn:p> \"hello\"garbage .") }
                assertEquals(0, bag.size)
                assertFailsWith<IllegalArgumentException> { bag.ingestKg("GRAPH <urn:g> { <urn:s> <urn:p> <urn:o> . }") }
                assertFailsWith<IllegalArgumentException> { bag.ingestKg("unrecognized prose") }
                assertEquals(0, bag.ingestKg("@prefix ex: <urn:example:> .")["statements"])
                val input = "<urn:s> <urn:label> \"Smith, Jones\" ."
                val report = bag.ingestKg(input)
                assertEquals(1, report["statements"])
                val expected = KgNalBridge.map(KgNalBridge.parseTurtle(input).single()).signal(ContentId.of(input.encodeToByteArray()).value)
                assertEquals(expected, bag.signalOf(expected.angular))
                val twoKinds = "<urn:s> <urn:p> <urn:o> .\n<urn:s> <urn:p> \"urn:o\" ."
                val source = ContentId.of(twoKinds.encodeToByteArray()).value
                val signals = KgNalBridge.parseTurtle(twoKinds).map { KgNalBridge.map(it).signal(source) }
                assertEquals(2, signals.map { it.angular }.distinct().size, "This concrete pair has distinct natural routing addresses")
                assertEquals(2, bag.ingestKg(twoKinds)["statements"])
                for (signal in signals) assertEquals(signal, bag.signalOf(signal.angular))
                bag.ingestKg(twoKinds)
                for (signal in signals) assertEquals(signal, bag.signalOf(signal.angular), "Exact source replay cannot add evidence")
                bag.awaitProcessed(); assertTrue(bag.worker?.isActive == true)
            } finally { bag.drain(); assertTrue(bag.worker?.isCompleted == true && bag.supervisor.isCompleted) }
            // These are compatibility controls, not endorsements of the permissive parser's behavior.
            assertEquals(RdfTerm.Iri("undefined:o"), TurtleRdf.parseTerm("undefined:o"))
            assertEquals(RdfTerm.Literal("badq"), TurtleRdf.parseTerm("\"bad\\q\""))
            assertEquals(1, TurtleRdf.parse("<urn:s> <urn:p> \"Smith, Jones\" .").triples.size)
            assertEquals(KgTriplet("Tenant", "pays", "Landlord"), KgNalBridge.parseKif("(pays Tenant Landlord)").single())
            assertFailsWith<IllegalArgumentException> { KgNalBridge.parseKif("(not (pays Tenant Landlord))") }
        }
    }
    @Test fun textConsumerTreatsInterchangeMarkersInsideRdfLiteralsAsData() = runBlocking {
        withTimeout(10_000) {
            val input = """# "@context" and <rdf:RDF are comment text
                |<urn:s> <urn:label> "subject" .
                |<urn:s> <urn:label> "@context" .
                |<urn:s> <urn:label> "<rdf:RDF>" .
                |<urn:s> <urn:label> "@prefix" .
                |""".trimMargin()
            assertEquals(KgFormat.TURTLE, KgNalBridge.sniff(input))
            val jsonLd = """{"@context":{},"value":"subject"}"""
            val tripletJson = """[{"subject":"s","predicate":"p","object":"@context"}]"""
            assertEquals(KgFormat.JSON_LD, KgNalBridge.sniff(jsonLd))
            assertEquals(KgFormat.TRIPLET_JSON, KgNalBridge.sniff(tripletJson))
            assertEquals(KgFormat.JSON_LD, KgNalBridge.sniff("[$jsonLd]"))
            assertNull(KgNalBridge.sniff("""{"value":"@context","nested":{"subject":"data"}}"""))
            assertEquals(KgFormat.RDF_XML, KgNalBridge.sniff("""<rdf:RDF xmlns:rdf="urn:rdf"/>"""))
            val xmlRoot = "<rdf:RDF></rdf:RDF>"
            assertEquals(KgFormat.RDF_XML, KgNalBridge.sniff(xmlRoot))
            val iriInput = "<rdf:RDF> <urn:p> <urn:o> ."
            assertEquals(KgFormat.TURTLE, KgNalBridge.sniff(iriInput))
            assertEquals(KgFormat.KIF, KgNalBridge.sniff("(pays Tenant Landlord)"))
            val bag = BeliefBagElement(parentJob = currentCoroutineContext()[Job]); bag.open()
            try {
                for (unsupported in s_[jsonLd, tripletJson, xmlRoot].view)
                    assertFailsWith<IllegalArgumentException> { bag.ingestKg(unsupported) }
                assertFails { bag.ingestKg(tripletJson.dropLast(1)) }
                assertEquals(0, bag.size, "Unsupported or malformed JSON must not mint beliefs")
                assertFailsWith<IllegalArgumentException> {
                    bag.ingestKg(input + "\n<urn:s> <urn:p> \"bad\"suffix .")
                }
                assertEquals(0, bag.size, "A malformed tail must not mint marker-looking literal prefixes")
                val report = bag.ingestKg(input)
                assertEquals(4, report["statements"])
                val source = ContentId.of(input.encodeToByteArray())
                val rows = KgNalBridge.parseTurtle(input)
                assertEquals(_l["\"subject\"", "\"@context\"", "\"<rdf:RDF>\"", "\"@prefix\""], rows.map { it.obj })
                val identities = rows.map { KgNalBridge.map(it).propositionCid(source.value) }
                assertEquals(4, identities.distinct().size)
                for (row in rows) {
                    val expected = KgNalBridge.map(row).signal(source.value)
                    assertEquals(expected, bag.signalOf(expected.angular))
                }
                assertEquals(1, bag.ingestKg(iriInput)["statements"])
                val iri = KgNalBridge.parseTurtle(iriInput).single()
                assertEquals("<rdf:RDF>", iri.subject)
                val iriSource = ContentId.of(iriInput.encodeToByteArray())
                val iriSignal = KgNalBridge.map(iri).signal(iriSource.value)
                assertEquals(iriSignal, bag.signalOf(iriSignal.angular))
                bag.awaitProcessed()
            } finally { bag.drain() }
        }
    }

}
