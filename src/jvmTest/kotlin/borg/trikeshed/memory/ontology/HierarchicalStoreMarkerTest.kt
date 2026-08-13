package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class HierarchicalStoreMarkerTest {
    @Test
    fun hierarchicalStoreIsExternalNonParametricMarker() {
        val marker: ExternalNonParametric = HierarchicalStore
        assertTrue(marker.gloss.isNotEmpty())
    }
}
