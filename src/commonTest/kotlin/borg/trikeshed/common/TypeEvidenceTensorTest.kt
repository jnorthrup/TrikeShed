package borg.trikeshed.common

import borg.trikeshed.cursor.MapTypeMemento
import borg.trikeshed.cursor.SeqTypeMemento
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TypeEvidenceTensorTest {

    @Test
    fun deduceByte() {
        val e = TypeEvidence.sample("42".toSeries())
        assertEquals(IOMemento.IoByte, TypeEvidence.deduce(e))
    }

    @Test
    fun deduceShort() {
        val e = TypeEvidence.sample("12345".toSeries())
        assertEquals(IOMemento.IoShort, TypeEvidence.deduce(e))
    }

    @Test
    fun deduceInt() {
        val e = TypeEvidence.sample("1234567890".toSeries())
        assertEquals(IOMemento.IoInt, TypeEvidence.deduce(e))
    }

    @Test
    fun deduceLong() {
        val e = TypeEvidence.sample("1234567890123456789".toSeries())
        assertEquals(IOMemento.IoLong, TypeEvidence.deduce(e))
    }

    @Test
    fun deduceFloat() {
        val e = TypeEvidence.sample("3.14".toSeries())
        assertEquals(IOMemento.IoFloat, TypeEvidence.deduce(e))
    }

    @Test
    fun deduceDouble() {
        val e = TypeEvidence.sample("3.141592653589793e10".toSeries())
        assertEquals(IOMemento.IoDouble, TypeEvidence.deduce(e))
    }

    @Test
    fun deduceBoolean() {
        val e = TypeEvidence.sample("true".toSeries())
        assertEquals(IOMemento.IoBoolean, TypeEvidence.deduce(e))
    }

    @Test
    fun deduceString() {
        val e = TypeEvidence.sample("\"hello\"".toSeries())
        assertEquals(IOMemento.IoString, TypeEvidence.deduce(e))
    }

    @Test
    fun deduceMementoStructuralMap() {
        val e = TypeEvidence.sample("{\"id\":1}".toSeries())
        val memento = TypeEvidence.deduceMemento(e)
        assertEquals(MapTypeMemento, memento)
    }

    @Test
    fun deduceMementoStructuralSeq() {
        val e = TypeEvidence.sample("[1,2,3]".toSeries())
        val memento = TypeEvidence.deduceMemento(e)
        assertEquals(SeqTypeMemento, memento)
    }

    @Test
    fun deduceNegativeByte() {
        val e = TypeEvidence.sample("-7".toSeries())
        assertEquals(IOMemento.IoByte, TypeEvidence.deduce(e))
    }
}
