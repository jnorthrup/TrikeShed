package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reify
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CouncilRulingJsonTest {
    @Test
    fun quotedBracesCannotTurnAClarificationRequestIntoAPublishedRuling() = runTest {
        val questions = listOf(
            """Which exhibit contains { after "notice" at C:\case\record?""",
            """Which exhibit contains } after "notice\"sent" at C:\case\record?""",
        )
        for ((index, question) in questions.withIndex()) {
            val store = InMemoryRecordStore()
            val runners = CouncilNodes.registry(CouncilDialog { error("no model dispatch") }, RecordSeams.inMemory(store))
            val verdict = linkedMapOf(
                "disposition" to "pending",
                "needsClarification" to if (index == 0) true else "true",
                "clarificationQuestion" to question,
                "mistrial" to false,
            )
            val text = "An earlier proposal: {\"disposition\":\"granted\"}\n" +
                "The final record needs an answer.\n```json\n${jsonOf(verdict)}\n```"
            val parsed = runners.getValue("ruling.parse").run(LcncNode("parse", "ruling.parse"), mapOf("text" to text))
            val failure = assertFailsWith<IllegalArgumentException> {
                runners.getValue("council.record").run(
                    LcncNode("record", "council.record", params = mapOf("caseId" to "quoted-$index")),
                    mapOf("verdict" to parsed["verdict"], "transcript" to text,
                        "documentCid?" to ContentId.of("retained exhibit".encodeToByteArray()).value),
                )
            }
            assertTrue(failure.message.orEmpty().contains("unresolved clarification"), failure.message)
            assertEquals(true, parsed["needsClarification"])
            assertEquals(question, parsed["clarificationQuestion"])
            assertEquals(verdict, parsed["verdict"])
            assertEquals(text, parsed["text"])
            assertTrue(store.cas.isEmpty(), "no retained verdict or lifecycle bytes")
            assertTrue(store.couch.isEmpty(), "no prepared or published journal")
            assertTrue(store.blackboard.isEmpty(), "no projected case")
            assertTrue(store.kif.isEmpty(), "no published ruling")
            assertTrue(store.rulings.isEmpty(), "no ruling lifecycle completion")
            assertTrue(store.mistrials.isEmpty(), "a question is not an inferred mistrial")
        }
    }

    @Test
    fun theLastFencedObjectKeepsQuotedNestedDataAndTerminalMistrialEvidence() = runTest {
        val store = InMemoryRecordStore()
        val runners = CouncilNodes.registry(CouncilDialog { error("no model dispatch") }, RecordSeams.inMemory(store))
        val verdict = linkedMapOf(
            "disposition" to "void",
            "needsClarification" to "true",
            "clarificationQuestion" to """Who supplied } and "{notice\"sent" at C:\case?""",
            "mistrial" to "true",
            "evidence" to mapOf("citation" to "Exhibit { and quoted \"notice\""),
        )
        val text = "The prose token \"{\" is not a JSON object.\n" +
            "An earlier ruling: {\"disposition\":\"granted\",\"mistrial\":false}\n" +
            "The final ruling follows.\n```json\n${jsonOf(verdict)}\n```\nEnd of the retained ruling."
        val parsed = runners.getValue("ruling.parse").run(LcncNode("parse", "ruling.parse"), mapOf("text" to text))
        assertEquals(verdict, parsed["verdict"], "the outer final ruling wins over nested and earlier maps")
        assertEquals(true, parsed["mistrial"])
        assertEquals(false, parsed["needsClarification"], "mistrial suppresses another round")
        assertEquals(text, parsed["text"])
        val output = runners.getValue("council.record").run(
            LcncNode("record", "council.record", params = mapOf("caseId" to "quoted-mistrial")),
            mapOf("verdict" to parsed["verdict"], "transcript" to parsed["text"],
                "documentCid?" to ContentId.of("retained exhibit".encodeToByteArray()).value),
        )
        val report = output["report"] as Map<*, *>
        assertEquals("mistrial", report["status"])
        assertEquals(verdict, reify(store.cas.getValue(report["verdictCid"] as String)))
        assertEquals(text, store.cas.getValue(report["transcriptCid"] as String).decodeToString())
        assertEquals(1, store.mistrials.size)
        assertTrue(store.rulings.isEmpty())
        assertTrue(store.kif.isEmpty())
    }
}
