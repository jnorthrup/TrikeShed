package borg.trikeshed.narsese

import borg.trikeshed.forge.server.BeliefWire
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reify
import kotlinx.coroutines.*
import kotlin.test.*

class KgRouteErrorTest {
    private suspend fun owner(cas: CasStore?, body: suspend (BeliefBagElement, BeliefWire) -> Unit) {
        val bag = BeliefBagElement(cas = cas, parentJob = currentCoroutineContext()[Job]); bag.open()
        val review = TurnReviewElement(bag); review.open()
        try { body(bag, BeliefWire(bag, review, null)) }
        finally { review.drain(); bag.drain(); assertTrue(bag.worker?.isCompleted == true && bag.supervisor.isCompleted) }
    }

    @Test fun invalidInputReturns400WithoutMintAndTheNextValidRequestWorks(): Unit = runBlocking {
        withTimeout(10_000) {
            val invalid = s_[
                "(pays Tenant Landlord) (not (pays Tenant Landlord))",
                "<urn:s> <urn:p> \"unterminated .",
                "<urn:valid> <urn:p> <urn:o> .\n<urn:s> <urn:p> \"hello\"suffix .",
                "unrecognized prose", "{\"@context\":{},\"value\":\"private\"}",
                "(labels Tenant \"\uD800\")",
            ]
            for (input in invalid.view) owner(KgRetentionFixtureCas()) { bag, wire ->
                val response = assertNotNull(wire.route("POST", "/api/beliefs/kg", input, null))
                assertEquals(400, response.status)
                assertTrue((reify(response.body) as Map<*, *>)["error"] in _s["invalid_kg_input", "invalid_kg_unicode"])
                assertFalse(response.body.contains("Tenant") || response.body.contains("urn:s") || response.body.contains("private"))
                assertEquals(0, bag.size)
                bag.awaitProcessed(); assertTrue(bag.worker?.isActive == true)
                assertEquals(200, assertNotNull(wire.route("POST", "/api/beliefs/kg", "(labels Clerk Record)", null)).status)
                assertEquals(1, bag.size)
            }
        }
    }

    @Test fun admissionConflictReturns409AndDoesNotPretendTheProcessedPrefixRolledBack(): Unit = runBlocking {
        withTimeout(10_000) {
            val input = "(pays Tenant Landlord) (owes Tenant Landlord)"
            owner(KgRetentionFixtureCas()) { bag, wire ->
                val source = ContentId.of(input.encodeToByteArray())
                val mapped = KgNalBridge.bridge(input)
                val first = mapped[0].signal(source.value)
                val second = mapped[1].signal(source.value)
                assertEquals(first.angular, second.angular, "Actual attention collision, not a forced fixture address")
                assertNotEquals(first.propositionCid, second.propositionCid)
                val response = assertNotNull(wire.route("POST", "/api/beliefs/kg", input, null))
                assertEquals(409, response.status)
                val body = reify(response.body) as Map<*, *>
                assertEquals("PROPOSITION_MISMATCH", (body["admission"] as Map<*, *>)["issue"])
                assertEquals("earlier-statements-may-have-been-processed", body["processing"])
                assertFalse(body.containsKey("processedAssertions") || body.containsKey("minted") || body.containsKey("statements"))
                assertFalse(response.body.contains("Tenant") || response.body.contains("Landlord"))
                assertEquals(1, bag.size)
                assertEquals(first, bag.signalOf(first.angular))
                val before = SignalCodec.encode(first)
                assertEquals(409, assertNotNull(wire.route("POST", "/api/beliefs/kg", input, null)).status)
                assertContentEquals(before, SignalCodec.encode(assertNotNull(bag.signalOf(first.angular))))
                bag.awaitProcessed(); assertTrue(bag.worker?.isActive == true)
                assertEquals(200, assertNotNull(wire.route("POST", "/api/beliefs/kg", "(labels Clerk Record)", null)).status)
            }
        }
    }

    @Test fun retentionRefusalsKeepExisting503And413Responses(): Unit = runBlocking {
        withTimeout(10_000) {
            owner(null) { bag, wire ->
                val response = assertNotNull(wire.route("POST", "/api/beliefs/kg", "(pays Tenant Landlord)", null))
                assertEquals(503, response.status)
                val body = reify(response.body) as Map<*, *>
                assertEquals("kg_retention_refused", body["error"])
                assertEquals("MISSING_CAS", (body["retention"] as Map<*, *>)["issue"])
                assertEquals(0, (body["processedAssertions"] as Number).toInt()); assertEquals(0, bag.size)
                assertEquals(413, assertNotNull(wire.route("POST", "/api/beliefs/kg",
                    " ".repeat(KgRetention.MAX_SOURCE_CHARS) + "(pays Tenant Landlord)", null)).status)
                bag.awaitProcessed(); assertTrue(bag.worker?.isActive == true)
            }
        }
    }

    @Test fun cancellationIsPropagatedInsteadOfBecomingAnHttpInputError(): Unit = runBlocking {
        withTimeout(10_000) {
            val cas = object : KgRetentionFixtureCas() {
                override fun put(bytes: ByteArray): ContentId = throw CancellationException("Synthetic cancellation")
            }
            owner(cas) { bag, wire ->
                assertFailsWith<CancellationException> { wire.route("POST", "/api/beliefs/kg", "(pays Tenant Landlord)", null) }
                assertEquals(0, bag.size)
            }
        }
    }
}
