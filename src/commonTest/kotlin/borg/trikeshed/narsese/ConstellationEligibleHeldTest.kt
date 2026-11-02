package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConstellationEligibleHeldTest {
    private fun set(vararg ids: Int) = RoaringSeries.of(ids)

    @Test fun excludedStatementCannotContributeToSharedPredicationEvidence() {
        val positive = NormStatement("tenant", Modality.MUST, "pay", "rent", "if rent be due", 7)
        val negative = NormStatement("manager", Modality.MUST_NOT, "pay", "rent", "if rent be due", 7)
        val book = Book("one", "one", "", _l["one", "two"], _l[positive, negative],
            _l[_a[0], _a[1]])
        val c = Constellation("test").apply { join(book) }

        val pooled = c.held(7, set(7)).single()
        assertEquals(EvidenceCoord(Nal.UNIT, Nal.UNIT), pooled.belief)
        val selected = c.heldEligible(7, set(7), set(0)).single()
        assertEquals(EvidenceCoord(Nal.UNIT, 0), selected.belief)
        assertEquals(_s[0], selected.statements.toSet())
        assertTrue(c.heldEligible(7, set(7), set()).isEmpty())
    }

    @Test fun onlyFiringConditionIsPooledAndInherited() {
        val active = NormStatement("tenant", Modality.MUST, "pay", "rent", "if rent be due", 3)
        val unknown = NormStatement("landlord", Modality.MUST, "pay", "rent", "if notice be given", 7)
        val book = Book("one", "one", "", _l["one", "two"], _l[active, unknown],
            _l[_a[0], _a[1]])
        val c = Constellation("test").apply { join(book) }
        val lineage = set(3, 7)
        val fired = c.fire(lineage, _s["rent be due"])
        assertEquals(_s[0], fired.fires.toIntArray().toSet())
        assertEquals(_s[1], fired.pending.getValue("notice be given").toIntArray().toSet())

        val rows = c.heldEligible(7, lineage, fired.fires)
        assertEquals(1, rows.size)
        assertEquals(3, rows.single().via)
        assertEquals(_s[0], rows.single().statements.toSet())
        assertEquals(EvidenceCoord(Nal.UNIT, 0), rows.single().belief)
    }

    @Test fun identicalSectionTextIsNotCountedTwiceInEligibleEvidence() {
        val statement = NormStatement("tenant", Modality.MUST, "pay", "rent", null, 7)
        val section = Book.urn("same retained section")
        fun book(name: String) = Book(name, name, "", _l["section"], _l[statement],
            _l[_a[0]], sections = _l[section])
        val c = Constellation("test").apply { join(book("one")); join(book("two")) }
        val selected = c.heldEligible(7, set(7), set(0)).single()
        assertEquals(EvidenceCoord(Nal.UNIT, 0), selected.belief)
        assertEquals(1, selected.statements.size)
    }

    @Test fun sameStatementWithDifferentOccurrenceClassDoesNotExpandItsScope() {
        val first = NormStatement("tenant", Modality.MUST, "pay", "rent", null, 3)
        fun book(name: String, statement: NormStatement) = Book(name, name, "", _l["section"],
            _l[statement], _l[_a[0]])
        val c = Constellation("test").apply {
            join(book("one", first)); join(book("two", first.copy(bearerClass = 7)))
        }
        assertTrue(c.heldEligible(7, set(7), set(0)).isEmpty())
        assertEquals(EvidenceCoord(Nal.UNIT, 0), c.heldEligible(3, set(3), set(0)).single().belief)
    }
}
