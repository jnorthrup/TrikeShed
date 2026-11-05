package borg.trikeshed.narsese

import borg.trikeshed.forge.server.BeliefWire
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict
import kotlinx.coroutines.*
import kotlin.test.*

class KgKifCommentTest {
    private val form = "(labels Tenant \"notice; (exact) 📚\")"
    private val refused = s_[
        "; only a comment (pays Tenant Landlord)",
        " \t; <urn:s> <urn:p> <urn:o> .\r\n; @prefix ex: <urn:> .",
        "; header\r\n(pays Tenant Landlord",
        "; header\r\n(pays Tenant \"unterminated)",
        "; header\r\n(pays Tenant Landlord) )",
        "; header\r\n(pays Tenant Landlord) unexpected",
        "; header\r\n(pays Tenant Landlord)\n<urn:s> <urn:p> <urn:o> .",
        "<urn:s> <urn:p> <urn:o> .\n; middle\r\n(pays Tenant Landlord)",
        "; not Turtle trivia\r\n<urn:s> <urn:p> <urn:o> .",
        "; header\r\n(pays Tenant Landlord) (not (pays Tenant Landlord))",
        "; header\r\n(pays Tenant Landlord) (forall (?x) (pays ?x Landlord))",
        "; header\r\n(pays Tenant Landlord) (necessarily (pays Tenant Landlord))",
        "; header\r\n(pays Tenant Landlord) (pays ?tenant Landlord)",
        "; header\r\n(pays Tenant Landlord) (pays (agentOf Tenant) Landlord)",
        "; header\r\n(pays Tenant Landlord) (transfers Tenant Landlord Dollar)",
        "# RDF comment is not KIF trivia\r\n(pays Tenant Landlord)",
    ]

    @Test fun leadingAndTrailingCommentsUseTheStrictKifCrossing() {
        for (eol in s_["\n", "\r", "\r\n"].view) {
            val input = " \t\u2003$eol; header ) \" @prefix <urn:>$eol; second line$eol$form; trailing (not a form)"
            assertEquals(KgFormat.KIF, KgNalBridge.sniff(input))
            val mapped = KgNalBridge.bridge(input).single()
            assertEquals(KgTriplet("Tenant", "labels", "\"notice; (exact) 📚\""), mapped.triplet)
            assertEquals(KgNalBridge.parseKif(input).single(), mapped.triplet)
            assertEquals(1, KgNalBridge.bridgeToRules(input).size)
        }
        assertNull(KgNalBridge.sniff(" \t; only a comment\r\n; <urn:s> <urn:p> <urn:o> ."))
    }

    @Test fun commentsDoNotTurnMalformedMixedOrUnsupportedTailsIntoFacts() {
        for (input in refused[2 until refused.size].view) {
            assertFailsWith<IllegalArgumentException>(input) { KgNalBridge.bridge(input) }
            assertFailsWith<IllegalArgumentException>(input) { KgNalBridge.bridgeToRules(input) }
        }
    }

    private suspend fun owner(body: suspend (KgRetentionFixtureCas, BeliefBagElement, BeliefWire) -> Unit) {
        val cas = KgRetentionFixtureCas()
        val bag = BeliefBagElement(cas = cas, parentJob = currentCoroutineContext()[Job]); bag.open()
        val review = TurnReviewElement(bag); review.open()
        try { body(cas, bag, BeliefWire(bag, review, null)) }
        finally { review.drain(); bag.drain(); assertTrue(bag.worker?.isCompleted == true && bag.supervisor.isCompleted) }
    }

    @Test fun wireRetainsExactCommentedSourceAndItsDefinitionWithoutNormalizing(): Unit = runBlocking {
        withTimeout(10_000) {
            val input = "\u2003; manual π\r\n; second\r\n$form\r\n; tail β\r\n"
            val bytes = input.encodeToByteArray()
            val source = ContentId.of(bytes)
            owner { cas, bag, wire ->
                val raw = "POST /api/beliefs/kg HTTP/1.1\r\nContent-Length: ${bytes.size}\r\n\r\n$input"
                val response = assertNotNull(wire.route("POST", "/api/beliefs/kg", raw, null))
                assertEquals(200, response.status)
                val report = reifyStrict(response.body) as Map<*, *>
                assertEquals("KIF", report["format"])
                assertEquals(source.value, report["sourceCid"])
                assertEquals(1, (report["processedAssertions"] as Number).toInt())
                assertContentEquals(bytes, cas.getBounded(source, KgRetention.MAX_ARTIFACT_BYTES))
                assertNotEquals(ContentId.of(form.encodeToByteArray()), source)
                val mapped = KgNalBridge.bridge(input).single()
                val definition = mapped.propositionCid(source.value)
                assertEquals(_l[definition.value], report["propositionCids"])
                assertContentEquals(mapped.propositionBytes(source.value), cas.getBounded(definition, KgRetention.MAX_ARTIFACT_BYTES))
                val signal = mapped.signal(source.value)
                assertEquals(signal, bag.signalOf(signal.angular))
                assertEquals(200, assertNotNull(wire.route("POST", "/api/beliefs/kg", raw, null)).status)
                assertEquals(signal, bag.signalOf(signal.angular), "Exact replay cannot increase evidence")
                assertEquals(1, bag.size)
            }
        }
    }

    @Test fun wireRefusesTheWholeCommentedInputBeforeRetentionAndKeepsTheOwnerHealthy(): Unit = runBlocking {
        withTimeout(10_000) {
            for (input in refused.view) owner { cas, bag, wire ->
                val response = assertNotNull(wire.route("POST", "/api/beliefs/kg", input, null))
                assertEquals(400, response.status, input)
                assertEquals("invalid_kg_input", (reifyStrict(response.body) as Map<*, *>)["error"])
                assertTrue(cas.puts.isEmpty(), "No source or accepted prefix may be retained")
                assertEquals(0, cas.syncs)
                assertEquals(0, bag.size)
                bag.awaitProcessed(); assertTrue(bag.worker?.isActive == true)
                assertEquals(200, assertNotNull(wire.route("POST", "/api/beliefs/kg", "(labels Clerk Record)", null)).status)
                assertEquals(1, bag.size)
            }
        }
    }
}
