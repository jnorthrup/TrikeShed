package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class TextRecordsMarkerTest {
    @Test
    fun textRecordsIsExternalNonParametricMarker() {
        val marker: ExternalNonParametric = TextRecords
        assertTrue(TextRecords.gloss.isNotEmpty())
        assertTrue(marker is MemorySubstrate)
    }
}
