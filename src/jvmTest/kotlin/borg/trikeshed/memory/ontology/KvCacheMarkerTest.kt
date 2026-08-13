package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class KvCacheMarkerTest {
    @Test
    fun kvCacheIsInternalParametricMarker() {
        val marker: InternalParametric = KvCache
        assertTrue(KvCache.gloss.isNotEmpty())
        assertTrue(marker is MemorySubstrate)
    }
}
