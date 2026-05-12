package borg.trikeshed.polyglot

import borg.trikeshed.parse.evidence.TypeEvidence
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfidenceEdgeCaseTest {
    @Test
    fun testEmptySourceConfidence() {
        val emptyEvidence = TypeEvidence.sample("".toSeries())
        val refEvidence = TypeEvidence.sample("fun fun fun class class fun".toSeries())
        val conf = confidence(emptyEvidence, refEvidence)
        println("Actual confidence for empty source is: $conf")
        assertEquals(0.0, conf, "Empty source should have 0.0 confidence")
    }
}
