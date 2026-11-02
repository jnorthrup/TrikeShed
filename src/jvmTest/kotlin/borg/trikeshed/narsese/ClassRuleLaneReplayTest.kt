package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.nlp.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exact text is a conservative replay guard, not evidence of source independence. */
class ClassRuleLaneReplayTest {
    private fun reading(text: String): NlpDocument {
        if (" causes " !in text) return NlpDocument(text, emptySeriesOf())
        val subject = text.substringBefore(' ')
        val obj = text.substringAfter(" causes ").removeSuffix(".")
        val objectStart = text.indexOf(obj, subject.length + 8)
        val dot = text.length - 1
        return NlpDocument(text, s_[NlpSentence(0, 0, text.length, s_[
            NlpToken(1, 0, subject.length, subject, subject.lowercase(), "NN", "O"),
            NlpToken(2, subject.length + 1, subject.length + 7, "causes", "cause", "VBZ", "O"),
            NlpToken(3, objectStart, objectStart + obj.length, obj, obj, "NN", "O"),
            NlpToken(4, dot, dot + 1, ".", ".", ".", "O"),
        ], s_[NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"),
            NlpDependency(2, 3, "obj"), NlpDependency(2, 4, "punct")])])
    }

    private fun lane(maxSources: Int = 16, maxSourceChars: Long = 1_024, maxLineChars: Int = 128) =
        ClassRuleLane(maxSources = maxSources, maxSourceChars = maxSourceChars, maxLineChars = maxLineChars,
            readDocument = ::reading, classLookup = { if (it == "rain") 17 else null })

    @Test
    fun exactReplayAcrossCallsAndBatchSplitsDoesNotRaiseConfidence() {
        val rain = "Rain causes floods."
        val fire = "Rain causes fire."
        lane().use { lane ->
            val first = lane.observe(_l[rain]).single()
            assertEquals(null, first.miss)
            assertTrue(first.slot >= 0)
            val initial = lane.ledger.evidence(first.slot)
            val second = lane.observe(_l[fire, rain]).first { it.line == rain }
            assertEquals(first.source, second.source)
            assertEquals(initial, lane.ledger.evidence(first.slot))
            repeat(8) { lane.observe(_l[rain]) }
            assertEquals(initial, lane.ledger.evidence(first.slot))
            assertEquals(0, lane.promote(0.7f).size, "replay cannot promote a one-source candidate")
        }
    }

    @Test
    fun rejectedInputsDoNotConsumeCapacityAndFullRegistryAllowsReplay() {
        lane(maxSources = 1).use { lane ->
            assertEquals("no candidate", lane.observe(_l["Hello."]).single().miss)
            assertTrue(lane.observe(_l["Cloud causes fire."]).single().miss!!.startsWith("no class"))
            val first = lane.observe(_l["Rain causes floods."]).single()
            assertEquals(null, first.miss)
            val initial = lane.ledger.evidence(first.slot)
            assertEquals("source capacity exhausted", lane.observe(_l["Rain causes fire."]).single().miss)
            val replay = lane.observe(_l["Rain causes floods."]).single()
            assertEquals(first.source, replay.source)
            assertEquals(initial, lane.ledger.evidence(first.slot))
        }
    }

    @Test
    fun characterAndLineLimitsRefuseNewSourcesWithoutChangingPriorEvidence() {
        val firstLine = "Rain causes floods."
        lane(maxSourceChars = firstLine.length.toLong(), maxLineChars = 24).use { lane ->
            val first = lane.observe(_l[firstLine]).single()
            assertEquals(null, first.miss)
            val initial = lane.ledger.evidence(first.slot)
            assertEquals("source capacity exhausted", lane.observe(_l["Rain causes fire."]).single().miss)
            assertEquals("line exceeds source length limit", lane.observe(_l["x".repeat(25)]).single().miss)
            assertEquals(first.source, lane.observe(_l[firstLine]).single().source)
            assertEquals(initial, lane.ledger.evidence(first.slot))
        }
    }
    @Test
    fun readerCannotAttributeAParseOfDifferentTextToTheInput() {
        ClassRuleLane(maxSources = 1,
            readDocument = { if (it == "altered") reading("Rain causes floods.") else reading(it) },
            classLookup = { if (it == "rain") 17 else null }).use { lane ->
            val refused = lane.observe(_l["altered"]).single()
            assertEquals("reader returned different text", refused.miss)
            assertEquals(-1, refused.source)
            assertEquals(0, lane.ledger.size)
            assertEquals(null, lane.observe(_l["Rain causes floods."]).single().miss,
                "a refused mismatched parse must not consume source capacity")
        }
    }
}
