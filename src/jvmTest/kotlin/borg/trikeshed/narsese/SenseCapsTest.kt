package borg.trikeshed.narsese

import borg.trikeshed.ontology.SumoCorpus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sense lane's kernel against SUMO's own classes: a row comes to focus in the most specific nested cap that holds it,
 * rolling up to the parent while siblings split its mass and back down when one of them holds; one member's plane is the
 * class's own; transport lands no more than a head was read and never places a head by itself.
 */
class SenseCapsTest {
    private val at = Locality.parse("1900s_current")!!

    private fun judged(man: Int, woman: Int): SenseMemory = SenseMemory().also { m ->
        repeat(man) { m.observe("person", at, "b", "m$it", mapOf("Man" to 1.0, "Woman" to 0.0, SenseMemory.NONE to 0.0)) }
        repeat(woman) { m.observe("person", at, "b", "w$it", mapOf("Man" to 0.0, "Woman" to 1.0, SenseMemory.NONE to 0.0)) }
    }

    @Test
    fun focusRollsUpToTheParentWhileSiblingsSplit() {
        val m = judged(60, 60)
        val row = assertNotNull(m.sense("person", at))
        assertTrue(row.eternal(), "120 judgments over 3 classes pass T ≥ 24.5·K")
        assertEquals(-1, row.production(), "neither sibling holds 7/10 alone")
        val f = assertNotNull(ConceptTree.focus(row, m))
        assertEquals("Human", f.cls)
        assertEquals("cap", f.by)
    }

    @Test
    fun focusRollsDownWhenOneSiblingHolds() {
        val m = judged(100, 20)
        val row = assertNotNull(m.sense("person", at))
        assertEquals("Man", m.className(row.classes[row.production()]))
        assertEquals("Man", assertNotNull(ConceptTree.focus(row, m)).cls)
    }

    @Test
    fun oneMembersPlaneIsTheClassPlane() {
        for (man in 0..120 step 3) for (woman in 0..60 step 7) {
            val m = judged(man, woman)
            val row = m.sense("person", at) ?: continue
            for (i in 0 until row.width) assertEquals(row.holds(i), row.holdsAll(intArrayOf(i)), "$man/$woman class $i")
        }
    }

    @Test
    fun transportLandsNoMoreThanWasRead() {
        val human = assertNotNull(SumoCorpus.classifier.classId("Human")).value
        val org = assertNotNull(SumoCorpus.classifier.classId("Organization")).value
        val t = PositTransport()
        repeat(3) { t.add("sign", human, null) }
        t.add("sign", org, null)
        repeat(2) { t.add("sue", org, null) }
        for (v in listOf("sign", "sue", "eat")) t.add(v, -1, "lessee")
        val l = t.land("lessee")
        assertEquals(3 * Nal.UNIT, l.total)
        assertEquals(2 * Nal.UNIT, l.passed, "no typed bearer eats, so that statement passes nothing")
        assertEquals(750L, l.mass[human])
        assertEquals(1250L, l.mass[org])
        assertEquals(l.passed, l.mass.values.sum())
    }

    @Test
    fun aHeadIsNeverPlacedByItself() {
        val human = assertNotNull(SumoCorpus.classifier.classId("Human")).value
        val t = PositTransport()
        t.add("testify", human, "witness")
        t.add("testify", -1, "witness")
        val l = t.land("witness")
        assertEquals(2 * Nal.UNIT, l.total)
        assertEquals(0L, l.passed)
        assertTrue(l.mass.isEmpty())
    }
}
