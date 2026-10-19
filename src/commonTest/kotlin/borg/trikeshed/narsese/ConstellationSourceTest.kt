package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConstellationSourceTest {
    private fun clause(text: String) = NormClause("tenant", "must", true, "pay", "rent", null, text)

    private fun book(name: String, vararg sections: String) =
        Book.of(name, name, "", sections.toList(), sections.map { listOf(clause(it)) })

    @Test fun identicalSectionTextIsOneSourceAcrossBooksAndNamesTheGraph() {
        val shared = "The tenant must pay the rent."
        val one = Constellation("one").apply { join(book("first", shared)) }
        val twice = Constellation("twice").apply { join(book("first", shared)); join(book("second", shared)) }
        val apart = Constellation("apart").apply { join(book("first", shared)); join(book("second", "Every tenant must pay the rent.")) }

        assertEquals(one.truth(0), twice.truth(0))
        assertTrue(apart.truth(0).confidence > one.truth(0).confidence)

        val urn = "urn:" + ContentId.of(shared.encodeToByteArray()).value
        assertEquals(listOf(urn, urn), twice.support[0].toIntArray().map(twice::graph))
    }
}
