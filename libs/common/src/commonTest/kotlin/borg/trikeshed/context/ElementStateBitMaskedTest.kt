package borg.trikeshed.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TDD spec for ElementState as BitMasked ordinal ordering.
 * The ordinal IS the bit position — enabling `when` over ordinal and
 * bitmask operations for concurrent state gates.
 *
 * State machine: CREATED(0) → OPEN(1) → ACTIVE(2) → DRAINING(3) → CLOSED(4)
 */
class ElementStateBitMaskedTest {

    // ── Ordinal ordering ────────────────────────────────────────────────────────

    @Test
    fun `CREATED has ordinal 0`() {
        assertEquals(0, ElementState.CREATED.ordinal)
    }

    @Test
    fun `OPEN has ordinal 1`() {
        assertEquals(1, ElementState.OPEN.ordinal)
    }

    @Test
    fun `ACTIVE has ordinal 2`() {
        assertEquals(2, ElementState.ACTIVE.ordinal)
    }

    @Test
    fun `DRAINING has ordinal 3`() {
        assertEquals(3, ElementState.DRAINING.ordinal)
    }

    @Test
    fun `CLOSED has ordinal 4`() {
        assertEquals(4, ElementState.CLOSED.ordinal)
    }

    // ── mask = 1 shl ordinal ──────────────────────────────────────────────────

    @Test
    fun `CREATED mask is 1 shl 0`() {
        assertEquals(1u, ElementState.CREATED.mask)
    }

    @Test
    fun `OPEN mask is 1 shl 1`() {
        assertEquals(2u, ElementState.OPEN.mask)
    }

    @Test
    fun `ACTIVE mask is 1 shl 2`() {
        assertEquals(4u, ElementState.ACTIVE.mask)
    }

    @Test
    fun `DRAINING mask is 1 shl 3`() {
        assertEquals(8u, ElementState.DRAINING.mask)
    }

    @Test
    fun `CLOSED mask is 1 shl 4`() {
        assertEquals(16u, ElementState.CLOSED.mask)
    }

    // ── isAtLeast / isAtMost / isLessThan / isGreaterThan ──────────────────────

    @Test
    fun `CREATED isAtLeast CREATED`() { assertTrue(ElementState.CREATED.isAtLeast(ElementState.CREATED)) }
    @Test
    fun `OPEN isAtLeast CREATED`()  { assertTrue(ElementState.OPEN.isAtLeast(ElementState.CREATED)) }
    @Test
    fun `OPEN isAtLeast OPEN`()     { assertTrue(ElementState.OPEN.isAtLeast(ElementState.OPEN)) }
    @Test
    fun `OPEN isAtLeast ACTIVE`()  { assertFalse(ElementState.OPEN.isAtLeast(ElementState.ACTIVE)) }

    @Test
    fun `CLOSED isAtMost CLOSED`()    { assertTrue(ElementState.CLOSED.isAtMost(ElementState.CLOSED)) }
    @Test
    fun `ACTIVE isAtMost CLOSED`()     { assertTrue(ElementState.ACTIVE.isAtMost(ElementState.CLOSED)) }
    @Test
    fun `CREATED isAtMost ACTIVE`()    { assertTrue(ElementState.CREATED.isAtMost(ElementState.ACTIVE)) }
    @Test
    fun `OPEN isAtMost CREATED`()      { assertFalse(ElementState.OPEN.isAtMost(ElementState.CREATED)) }

    @Test
    fun `OPEN isLessThan ACTIVE`()      { assertTrue(ElementState.OPEN.isLessThan(ElementState.ACTIVE)) }
    @Test
    fun `CREATED isLessThan CLOSED`()   { assertTrue(ElementState.CREATED.isLessThan(ElementState.CLOSED)) }
    @Test
    fun `OPEN isLessThan OPEN`()        { assertFalse(ElementState.OPEN.isLessThan(ElementState.OPEN)) }
    @Test
    fun `ACTIVE isLessThan CREATED`()   { assertFalse(ElementState.ACTIVE.isLessThan(ElementState.CREATED)) }

    @Test
    fun `ACTIVE isGreaterThan OPEN`()     { assertTrue(ElementState.ACTIVE.isGreaterThan(ElementState.OPEN)) }
    @Test
    fun `CLOSED isGreaterThan CREATED`()  { assertTrue(ElementState.CLOSED.isGreaterThan(ElementState.CREATED)) }
    @Test
    fun `OPEN isGreaterThan OPEN`()       { assertFalse(ElementState.OPEN.isGreaterThan(ElementState.OPEN)) }
    @Test
    fun `CREATED isGreaterThan ACTIVE`()  { assertFalse(ElementState.CREATED.isGreaterThan(ElementState.ACTIVE)) }

    // ── lifecycle order: CREATED < OPEN < ACTIVE < DRAINING < CLOSED ─────────

    @Test
    fun `lifecycle_order_CREATED_lessThan_OPEN`() {
        assertTrue(ElementState.CREATED.isLessThan(ElementState.OPEN))
    }

    @Test
    fun `lifecycle_order_OPEN_lessThan_ACTIVE`() {
        assertTrue(ElementState.OPEN.isLessThan(ElementState.ACTIVE))
    }

    @Test
    fun `lifecycle_order_ACTIVE_lessThan_DRAINING`() {
        assertTrue(ElementState.ACTIVE.isLessThan(ElementState.DRAINING))
    }

    @Test
    fun `lifecycle_order_DRAINING_lessThan_CLOSED`() {
        assertTrue(ElementState.DRAINING.isLessThan(ElementState.CLOSED))
    }

    // ── cross-type BitMasked contract ─────────────────────────────────────────

    @Test
    fun `ElementLifecycleState alias equals ElementState`() {
        val state: ElementLifecycleState = ElementState.OPEN
        assertEquals(ElementState.OPEN, state)
    }
}
