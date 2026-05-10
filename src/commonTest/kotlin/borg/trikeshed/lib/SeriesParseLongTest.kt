package borg.trikeshed.lib

import borg.trikeshed.isam.meta.IOMemento
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesParseLongTest {
    @Test
    fun parseLongOrNull_accepts_integer_like_strings() {
        val s = "12345".toSeries()
        val evidence = TypeEvidence.sample(s)
        val deduced = TypeEvidence.deduce(evidence)
        assertTrue(deduced == IOMemento.IoInt || deduced == IOMemento.IoLong || deduced == IOMemento.IoShort || deduced == IOMemento.IoByte)
        assertEquals(12345L, s.parseLongOrNull())
    }

    @Test
    fun parseLongOrNull_rejects_non_numeric() {
        val s = "abc".toSeries()
        val evidence = TypeEvidence.sample(s)
        val deduced = TypeEvidence.deduce(evidence)
        assertEquals(IOMemento.IoString, deduced)
        assertNull(s.parseLongOrNull())
    }

    @Test
    fun parseLongOrNull_handles_negative_and_large() {
        val s = "-9876543210".toSeries()
        val evidence = TypeEvidence.sample(s)
        val deduced = TypeEvidence.deduce(evidence)
        assertTrue(deduced == IOMemento.IoLong || deduced == IOMemento.IoInt)
        assertEquals(-9876543210L, s.parseLongOrNull())
    }

    @Test
    fun parseLongOrNull_rejects_decimal() {
        val s = "1.234".toSeries()
        val evidence = TypeEvidence.sample(s)
        val deduced = TypeEvidence.deduce(evidence)
        assertTrue(deduced == IOMemento.IoFloat || deduced == IOMemento.IoDouble)
        assertNull(s.parseLongOrNull())
    }
}
