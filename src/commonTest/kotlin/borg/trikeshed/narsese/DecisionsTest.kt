package borg.trikeshed.narsese

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import modelmux.Airgap
import kotlin.test.*

class DecisionsTest {
    private val questions = linkedMapOf(
        "stated" to Jev.noul("Does page state the norm?", "The norm is stated", "The norm is absent"),
        "where" to Jev.choice(mapOf("question" to "Which passage answers?"), linkedMapOf("P0" to null, "none" to "No passage answers")),
        "refused" to Jev.noul("Does page establish the premise?"),
    )
    private fun predicate(name: String = "stated", probability: Any? = 0.8): Map<String, Any?> =
        mapOf("type" to "predicate", "name" to name, "probability" to probability)
    private fun choice(): Map<String, Any?> = mapOf("type" to "choice", "name" to "where", "choice" to "P0",
        "confidence" to 0.9, "probabilities" to listOf(
            mapOf("value" to "P0", "probability" to 0.9), mapOf("value" to "none", "probability" to 0.1)))
    private fun reply(vararg answers: Any?): Map<String, Any?> =
        mapOf("model" to Decisions.MODEL, "usage" to mapOf("input_tokens" to 12), "answers" to answers.toList())

    @Test fun requestPreservesStateQuestionNamesAndCriteria() {
        val state = mapOf("page" to "雪 closes the bank")
        val body = reifyMap(Decisions.body(state, questions))
        assertEquals(Decisions.MODEL, body["model"])
        assertEquals(state, reifyMap(body["input"] as String))
        val encoded = body["questions"] as List<*>
        val predicate = encoded[0] as Map<*, *>
        assertEquals("predicate", predicate["type"])
        assertEquals("stated", predicate["name"])
        assertTrue((predicate["instructions"] as String).contains("True: The norm is stated\nFalse: The norm is absent"))
        val choice = encoded[1] as Map<*, *>
        assertEquals("choice", choice["type"])
        assertEquals(mapOf("question" to "Which passage answers?"), reifyMap(choice["instructions"] as String))
        assertEquals(listOf(mapOf("value" to "P0"), mapOf("value" to "none", "description" to "No passage answers")), choice["choices"])
        assertEquals("plain page", reifyMap(Decisions.body("plain page", mapOf("q" to Jev.noul("stated?"))))["input"])
    }

    @Test fun requestRejectsUnboundedOrUnrepresentableQuestions() {
        assertFailsWith<IllegalArgumentException> { Decisions.body("page", emptyMap()) }
        assertFailsWith<IllegalArgumentException> {
            Decisions.body("page", (0..Decisions.MAX_QUESTIONS).associate { "q$it" to Jev.noul("stated?") })
        }
        for (question in listOf(
            Jev.noul(" "), Jev.noul("x".repeat(Decisions.MAX_TEXT_CHARS + 1)),
            mapOf("type" to "noul"), mapOf("type" to "noul", "instructions" to "stated?", "criteria" to "invalid"),
            Jev.choice("which?", mapOf("only" to null)),
            Jev.choice("which?", (0..255).associate { "c$it" to null }),
            Jev.choice("which?", mapOf("" to null, "other" to null)),
            Jev.choice("which?", mapOf("one" to 1, "two" to null)),
        )) assertFailsWith<IllegalArgumentException> { Decisions.body("page", mapOf("q" to question)) }
        assertFailsWith<IllegalArgumentException> { Decisions.body("page", mapOf(" " to Jev.noul("stated?"))) }
    }

    @Test fun answersRetainChoiceAndSeparateRefusalFromFalse() {
        val normalized = Decisions.answers(reply(predicate(), choice(), mapOf("type" to "refusal", "name" to "refused")), questions)
        assertEquals(Decisions.MODEL, normalized["model"])
        assertEquals(mapOf("input_tokens" to 12), normalized["usage"])
        val answers = normalized["answers"] as Map<*, *>
        assertEquals(0.8, Jev.noulOf(answers, "stated"))
        assertEquals(mapOf("choice" to "P0", "confidence" to 0.9, "probabilities" to mapOf("P0" to 0.9, "none" to 0.1)), answers["where"])
        assertFalse("refused" in answers)
        assertEquals(listOf("refused"), normalized["refusals"])
    }

    @Test fun malformedAnswersCannotBecomeStandingEvidence() {
        for (invalid in listOf(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY, "0.8", null)) {
            assertFailsWith<IllegalArgumentException> { Decisions.answers(reply(predicate(probability = invalid))) }
            assertFailsWith<IllegalArgumentException> { Decisions.answers(reply(choice() + ("confidence" to invalid))) }
            assertFailsWith<IllegalArgumentException> {
                Decisions.answers(reply(choice() + ("probabilities" to listOf(
                    mapOf("value" to "P0", "probability" to invalid), mapOf("value" to "none", "probability" to 0.1)))))
            }
        }
        assertFailsWith<IllegalArgumentException> { Decisions.answers(reply(predicate()) - "model") }
        assertFails { Decisions.answers(reply(predicate()) - "answers") }
        assertFailsWith<IllegalArgumentException> { Decisions.answers(reply(predicate(), predicate())) }
        assertFails { Decisions.answers(reply(mapOf("type" to "score", "name" to "stated"))) }
        assertFailsWith<IllegalArgumentException> { Decisions.answers(reply(predicate("unrequested")), mapOf("stated" to questions.getValue("stated"))) }
        assertFailsWith<IllegalArgumentException> { Decisions.answers(reply(predicate()), questions) }
        assertFailsWith<IllegalArgumentException> { Decisions.answers(reply(predicate("where")), mapOf("where" to questions.getValue("where"))) }
        assertFailsWith<IllegalArgumentException> { Decisions.answers(reply(choice() + ("choice" to "missing"))) }
        assertFailsWith<IllegalArgumentException> {
            Decisions.answers(reply(choice() + ("probabilities" to listOf(
                mapOf("value" to "P0", "probability" to 0.9), mapOf("value" to "P0", "probability" to 0.1)))))
        }
        assertFailsWith<IllegalArgumentException> {
            Decisions.answers(reply(choice()), mapOf("where" to Jev.choice("which?", mapOf("P0" to null, "P1" to null))))
        }
    }

    @Test fun normalizedJevAnswersRequireTheRequestedTypeAndEntireMenu() {
        val q = questions.getValue("where")
        val answer = mapOf("type" to "choice", "choice" to "P0", "confidence" to 0.9,
            "probabilities" to mapOf("P0" to 0.9, "none" to 0.1))
        assertSame(answer, Jev.answerOf(mapOf("where" to answer), "where", q))
        assertEquals(mapOf("noul" to 0.8), Jev.answerOf(mapOf("stated" to mapOf("noul" to 0.8)), "stated", questions.getValue("stated")))
        val normalized = Decisions.answers(reply(predicate(), choice(), mapOf("type" to "refusal", "name" to "refused")), questions)["answers"] as Map<*, *>
        assertEquals("P0", Jev.answerOf(normalized, "where", q)["choice"])
        assertFailsWith<IllegalArgumentException> { Jev.answerOf(normalized, "refused", questions.getValue("refused")) }
        for (invalid in listOf(
            null, "choice", mapOf("type" to "refusal"),
            answer + ("type" to "noul"), answer - "choice", answer + ("choice" to "other"),
            answer + ("choice" to true), answer + ("confidence" to "0.9"), answer + ("confidence" to Double.NaN),
            answer + ("probabilities" to emptyMap<String, Double>()),
            answer + ("probabilities" to mapOf("P0" to 1.0)),
            answer + ("probabilities" to mapOf("P0" to 0.9, "other" to 0.1)),
            answer + ("probabilities" to mapOf("P0" to 0.9, "none" to -0.1)),
            answer + ("probabilities" to mapOf("P0" to 0.9, "none" to "0.1")),
        )) assertFailsWith<IllegalArgumentException> { Jev.answerOf(mapOf("where" to invalid), "where", q) }
        for (invalid in listOf(null, "0.8", -0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY))
            assertFailsWith<IllegalArgumentException> {
                Jev.answerOf(mapOf("stated" to mapOf("noul" to invalid)), "stated", questions.getValue("stated"))
            }
        assertFailsWith<IllegalArgumentException> {
            Jev.answerOf(mapOf("stated" to mapOf("type" to "choice", "noul" to 0.8)), "stated", questions.getValue("stated"))
        }
    }

    @Test fun htxRequestRetriesOnlyTransientStatusesWithinItsBudget() = runTest {
        val requests = mutableListOf<HtxRequest>()
        val q = mapOf("stated" to questions.getValue("stated"))
        withHtxClientRoute({ request ->
            requests.add(request)
            when (requests.size) {
                1 -> HtxResponse(429, ByteSeries("rate limit"))
                2 -> HtxResponse(503, ByteSeries("busy"))
                else -> HtxResponse(200, ByteSeries(jsonOf(reply(predicate()))))
            }
        }) {
            assertEquals(0.8, Jev.noulOf(Decisions.ask("fixture", "page", q)["answers"] as Map<*, *>, "stated"))
        }
        assertEquals(3, requests.size)
        assertEquals(3_000L, testScheduler.currentTime)
        for (request in requests) {
            assertEquals(HtxMethod.POST, request.method)
            assertEquals("api.openai.com", request.target.host)
            assertEquals("/v1/decisions", request.target.requestPath)
            assertEquals("Bearer fixture", request.headerValue("Authorization"))
            assertEquals(Decisions.body("page", q), request.body.toArray().decodeToString())
        }
        for (status in listOf(400, 529, 503)) {
            var attempts = 0
            withHtxClientRoute({ attempts++; HtxResponse(status, ByteSeries("failure")) }) {
                assertEquals(status, assertFailsWith<HtxHttpError> { Decisions.ask("fixture", "page", q) }.status)
            }
            assertEquals(if (status == 503) 3 else 1, attempts)
        }
    }

    @Test fun airgapCancellationAndInvalidRepliesDoNotRetryOrBecomeAnswers() = runTest {
        val q = mapOf("stated" to questions.getValue("stated"))
        var requests = 0
        withHtxClientRoute({ requests++; HtxResponse(200, ByteSeries(jsonOf(reply(predicate())))) }) {
            withContext(Airgap()) { assertFailsWith<Airgap.Rejection> { Decisions.ask("fixture", "page", q) } }
            assertEquals(0, requests)
            assertFailsWith<IllegalArgumentException> { Decisions.ask(" ", "page", q) }
            assertFailsWith<IllegalArgumentException> { Decisions.ask("fixture", "page", emptyMap()) }
            assertEquals(0, requests)
        }
        val cancelled = CancellationException("decision cancellation")
        withHtxClientRoute({ requests++; throw cancelled }) {
            val caught = assertFailsWith<CancellationException> { Decisions.ask("fixture", "page", q) }
            assertEquals(cancelled.message, caught.message)
        }
        assertEquals(1, requests)
        withHtxClientRoute({ requests++; HtxResponse(200, ByteSeries(jsonOf(reply(predicate("unrequested"))))) }) {
            assertFailsWith<IllegalArgumentException> { Decisions.ask("fixture", "page", q) }
        }
        assertEquals(2, requests)
        withHtxClientRoute({ requests++; HtxResponse(200, ByteSeries(jsonOf(reply(predicate())) + " trailing")) }) {
            assertFails { Decisions.ask("fixture", "page", q) }
        }
        assertEquals(3, requests)
        assertEquals(0L, testScheduler.currentTime)
    }
}
