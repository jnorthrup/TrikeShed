package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConstellationPremiseTest {
    private val premise = "rent be due"
    private val mask = RoaringSeries.of(_a[7])

    /** The two conditional statements share a premise and must remain separately visible. */
    private fun constellation(): Constellation {
        val statements = _l[
            NormStatement("tenant", Modality.MUST, "keep", "records", null, 7),
            NormStatement("tenant", Modality.MUST, "pay", "rent", "if $premise", 7),
            NormStatement("tenant", Modality.MUST, "notify", "landlord", "unless $premise", 7),
        ]
        val book = Book("fixture", "fixture", "", _l["section"], statements,
            statements.map { _a[0] })
        return Constellation("fixture").apply { join(book) }
    }

    private fun ids(value: RoaringSeries?) = value?.toIntArray()?.toSet().orEmpty()

    @Test fun unknownPremiseKeepsBothConditionsPending() {
        val result = constellation().fire(mask, emptySet())
        assertEquals(_s[0], ids(result.fires))
        assertEquals(_s[1, 2], ids(result.pending[premise]))
        assertTrue(result.blocked.isEmpty())
        assertTrue(result.conflicted.isEmpty())
    }

    @Test fun positivePremiseEnablesIfAndBlocksUnless() {
        val result = constellation().fire(mask, _s[premise])
        assertEquals(_s[0, 1], ids(result.fires))
        assertEquals(_s[2], ids(result.blocked[premise]))
        assertTrue(result.pending.isEmpty())
        assertTrue(result.conflicted.isEmpty())
    }

    @Test fun explicitNegativePremiseEnablesUnlessAndBlocksIf() {
        val result = constellation().fire(mask, emptySet(), negativeFacts = _s[premise])
        assertEquals(_s[0, 2], ids(result.fires))
        assertEquals(_s[1], ids(result.blocked[premise]))
        assertTrue(result.pending.isEmpty())
        assertTrue(result.conflicted.isEmpty())
    }

    @Test fun contradictoryPremiseDoesNotEnableEitherCondition() {
        val result = constellation().fire(mask, _s[premise], negativeFacts = _s[premise])
        assertEquals(_s[0], ids(result.fires))
        assertEquals(_s[1, 2], ids(result.conflicted[premise]))
        assertTrue(result.pending.isEmpty())
        assertTrue(result.blocked.isEmpty())
    }

    @Test fun unrelatedClassDoesNotFireOrReportTheseConditions() {
        val result = constellation().fire(RoaringSeries.of(_a[8]), emptySet())
        assertTrue(result.fires.isEmpty())
        assertTrue(result.pending.isEmpty())
        assertTrue(result.blocked.isEmpty())
        assertTrue(result.conflicted.isEmpty())
    }

    @Test fun absentPremiseDoesNotAddEvidence() {
        val c = constellation()
        val before = c.truth(2)
        repeat(3) { c.fire(mask, emptySet()) }
        assertEquals(before, c.truth(2))
    }
}
