package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class StructuralStoreMarkerTest {

    @Test
    fun structuralStoreIsExternalNonParametricMarker() {
        val store: ExternalNonParametric = StructuralStore
        assertTrue(store.gloss.isNotEmpty(), "gloss should not be empty")
    }
}
