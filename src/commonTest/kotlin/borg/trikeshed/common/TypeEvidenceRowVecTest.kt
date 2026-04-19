package borg.trikeshed.common

import borg.trikeshed.cursor.name
import borg.trikeshed.cursor.type
import borg.trikeshed.cursor.MapTypeMemento
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals

class TypeEvidenceRowVecTest {
    @Test
    fun sampleTracksConfixAndProjectsRowVec() {
        val evidence = TypeEvidence.sample("""{"id64":5532807773}""".toSeries())
        val row = evidence.toRowVec()

        assertEquals(MapTypeMemento, TypeEvidence.deduceMemento(evidence))
        assertEquals("{}", row[0].a)
        assertEquals(IOMemento.IoString, row[0].b().type)
        assertEquals("confix", row[0].b().name)
        assertEquals("{}", row[0].a)
        assertEquals("digits", row[1].b().name)
        assertEquals(evidence.digits.toInt(), row[1].a)
        assertEquals("deducedType", row[row.size - 1].b().name)
        assertEquals("Map", row[row.size - 1].a)
    }
}
