package borg.trikeshed.classfile.slab.facet

import borg.trikeshed.classfile.slab.SlabFacet
import borg.trikeshed.classfile.slab.SlabFacetFlag
import borg.trikeshed.context.BitMasked
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.α
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TDD RED: FacetElements CCEK Facet control contexts
 *
 * FacetElement = CCEK element carrying SlabFacet tags
 * FacetControlContext = CCEK context for FacetEngine lifecycle control
 *
 * Lifecycle: CREATED → OPEN → ACTIVE → DRAINING → CLOSED (forward-only)
 * Fanout: structured delivery to subscribers via coroutineScope
 */

// ==================== FACETELEMENT TESTS ====================

class FacetElementTest {

    @Test
    fun `FacetElement has key identity`() {
        val elem = FacetElement(key = FacetElementKey, facet = SlabFacetFlag.HOT.facet)
        assertEquals(FacetElementKey, elem.key)
    }

    @Test
    fun `FacetElement has mutable lifecycle state`() {
        val elem = FacetElement(key = FacetElementKey, facet = SlabFacetFlag.HOT.facet)
        assertEquals(LifecycleState.CREATED, elem.lifecycleState)

        elem.lifecycleState = LifecycleState.OPEN
        assertEquals(LifecycleState.OPEN, elem.lifecycleState)

        elem.lifecycleState = LifecycleState.ACTIVE
        assertEquals(LifecycleState.ACTIVE, elem.lifecycleState)
    }

    @Test
    fun `FacetElement tracks fanout subscribers`() {
        val elem = FacetElement(key = FacetElementKey, facet = SlabFacetFlag.HOT.facet)
        assertTrue(elem.fanoutSubscribers.isEmpty())

        val subscriber: FacetSubscriber = { /* noop */ }
        elem.addSubscriber(subscriber)
        assertEquals(1, elem.fanoutSubscribers.size)

        elem.removeSubscriber(subscriber)
        assertTrue(elem.fanoutSubscribers.isEmpty())
    }

    @Test
    fun `FacetElement open transitions CREATED to OPEN`() {
        val elem = FacetElement(key = FacetElementKey, facet = SlabFacetFlag.HOT.facet)
        assertEquals(LifecycleState.CREATED, elem.lifecycleState)

        elem.open()
        assertEquals(LifecycleState.OPEN, elem.lifecycleState)
    }

    @Test
    fun `FacetElement activate transitions OPEN to ACTIVE`() {
        val elem = FacetElement(key = FacetElementKey, facet = SlabFacetFlag.HOT.facet)
        elem.open()
        assertEquals(LifecycleState.OPEN, elem.lifecycleState)

        elem.activate()
        assertEquals(LifecycleState.ACTIVE, elem.lifecycleState)
    }

    @Test
    fun `FacetElement drain transitions ACTIVE to DRAINING`() {
        val elem = FacetElement(key = FacetElementKey, facet = SlabFacetFlag.HOT.facet)
        elem.open()
        elem.activate()
        assertEquals(LifecycleState.ACTIVE, elem.lifecycleState)

        elem.drain()
        assertEquals(LifecycleState.DRAINING, elem.lifecycleState)
    }

    @Test
    fun `FacetElement close transitions DRAINING to CLOSED`() {
        val elem = FacetElement(key = FacetElementKey, facet = SlabFacetFlag.HOT.facet)
        elem.open()
        elem.activate()
        elem.drain()
        assertEquals(LifecycleState.DRAINING, elem.lifecycleState)

        elem.close()
        assertEquals(LifecycleState.CLOSED, elem.lifecycleState)
    }

    @Test
    fun `FacetElement lifecycle is forward-only - cannot reopen after CLOSED`() {
        val elem = FacetElement(key = FacetElementKey, facet = SlabFacetFlag.HOT.facet)
        elem.open()
        elem.activate()
        elem.drain()
        elem.close()

        elem.lifecycleState = LifecycleState.OPEN  // attempt to reopen
        assertEquals(LifecycleState.CLOSED, elem.lifecycleState)  // must stay CLOSED
    }

    @Test
    fun `FacetElement carries SlabFacet`() {
        val elem = FacetElement(key = FacetElementKey, facet = SlabFacetFlag.COLD.facet or SlabFacetFlag.S3_TIERED.facet)
        assertTrue(elem.facet.has(SlabFacetFlag.COLD.facet))
        assertTrue(elem.facet.has(SlabFacetFlag.S3_TIERED.facet))
        assertFalse(elem.facet.has(SlabFacetFlag.HOT.facet))
    }

    @Test
    fun `FacetElement addSubscriber rejects duplicates`() {
        val elem = FacetElement(key = FacetElementKey, facet = SlabFacetFlag.HOT.facet)
        val subscriber: FacetSubscriber = { }

        elem.addSubscriber(subscriber)
        elem.addSubscriber(subscriber)  // duplicate
        assertEquals(1, elem.fanoutSubscribers.size)  // still 1
    }
}

// ==================== FACETCONTROLCONTEXT TESTS ====================

class FacetControlContextTest {

    @Test
    fun `FacetControlContext is a CoroutineContext Element`() {
        val ctx = FacetControlContext(facet = SlabFacetFlag.HOT.facet)
        assertEquals(FacetControlContextKey, ctx.key)
    }

    @Test
    fun `FacetControlContext fold extracts FacetEngine`() {
        val engine = FacetEngine(facet = SlabFacetFlag.HOT.facet)
        val ctx = FacetControlContext(engine = engine)
        val folded: FacetEngine? = ctx.fold(null) { acc, elem ->
            if (elem is FacetControlContext) elem.engine else acc
        }
        assertEquals(engine, folded)
    }

    @Test
    fun `FacetControlContext plus merges engines by facet`() {
        val hot = FacetControlContext(facet = SlabFacetFlag.HOT.facet)
        val cold = FacetControlContext(facet = SlabFacetFlag.COLD.facet)
        val merged: FacetControlContext = hot.fold(hot) { acc, elem ->
            if (elem is FacetControlContext) elem else acc
        } as FacetControlContext
        assertEquals(SlabFacetFlag.HOT.facet, merged.facet)
    }

    @Test
    fun `FacetControlContext queryKey extracts FacetEngine`() {
        val engine = FacetEngine(facet = SlabFacetFlag.HOT.facet)
        val ctx = FacetControlContext(engine = engine)
        val extracted: FacetEngine? = ctx[FacetControlContextKey]
        assertEquals(engine, extracted)
    }

    @Test
    fun `FacetControlContext minus removes engine`() {
        val engine = FacetEngine(facet = SlabFacetFlag.HOT.facet)
        val ctx = FacetControlContext(engine = engine)
        val empty: CoroutineContext = ctx.minusKey(FacetControlContextKey)
        assertTrue(empty[FacetControlContextKey] == null)
    }
}

// ==================== FACETENGINE LIFECYCLE TESTS ====================

class FacetEngineLifecycleTest {

    @Test
    fun `FacetEngine starts in CREATED state`() {
        val engine = FacetEngine(facet = SlabFacetFlag.HOT.facet)
        assertEquals(LifecycleState.CREATED, engine.lifecycleState)
    }

    @Test
    fun `FacetEngine open transitions to OPEN`() {
        val engine = FacetEngine(facet = SlabFacetFlag.HOT.facet)
        engine.open()
        assertEquals(LifecycleState.OPEN, engine.lifecycleState)
    }

    @Test
    fun `FacetEngine activate transitions OPEN to ACTIVE`() {
        val engine = FacetEngine(facet = SlabFacetFlag.HOT.facet)
        engine.open()
        engine.activate()
        assertEquals(LifecycleState.ACTIVE, engine.lifecycleState)
    }

    @Test
    fun `FacetEngine drain transitions ACTIVE to DRAINING`() {
        val engine = FacetEngine(facet = SlabFacetFlag.HOT.facet)
        engine.open()
        engine.activate()
        engine.drain()
        assertEquals(LifecycleState.DRAINING, engine.lifecycleState)
    }

    @Test
    fun `FacetEngine close transitions DRAINING to CLOSED`() {
        val engine = FacetEngine(facet = SlabFacetFlag.HOT.facet)
        engine.open()
        engine.activate()
        engine.drain()
        engine.close()
        assertEquals(LifecycleState.CLOSED, engine.lifecycleState)
    }

    @Test
    fun `FacetEngine lifecycle is forward-only - cannot skip states`() {
        val engine = FacetEngine(facet = SlabFacetFlag.HOT.facet)
        engine.activate()  // skip OPEN
        assertEquals(LifecycleState.CREATED, engine.lifecycleState)  // must stay CREATED
    }

    @Test
    fun `FacetEngine lifecycle is forward-only - cannot go backward`() {
        val engine = FacetEngine(facet = SlabFacetFlag.HOT.facet)
        engine.open()
        engine.activate()
        engine.lifecycleState = LifecycleState.OPEN  // attempt backward
        assertEquals(LifecycleState.ACTIVE, engine.lifecycleState)  // must stay ACTIVE
    }

    @Test
    fun `FacetEngine lifecycle is forward-only - cannot advance after CLOSED`() {
        val engine = FacetEngine(facet = SlabFacetFlag.HOT.facet)
        engine.open()
        engine.activate()
        engine.drain()
        engine.close()
        engine.open()  // attempt reopen
        assertEquals(LifecycleState.CLOSED, engine.lifecycleState)  // must stay CLOSED
    }

    @Test
    fun `FacetEngine has facet tag`() {
        val engine = FacetEngine(facet = SlabFacetFlag.COLD.facet or SlabFacetFlag.S3_TIERED.facet)
        assertTrue(engine.facet.has(SlabFacetFlag.COLD.facet))
        assertTrue(engine.facet.has(SlabFacetFlag.S3_TIERED.facet))
    }

    @Test
    fun `FacetEngine dispatches PointcutEvent to LOGIC mode for L_GET`() {
        val engine = FacetEngine(facet = SlabFacetFlag.HOT.facet)
        val event = PointcutEvent.new(FieldSynapse.L_GET, columnId = 1L, transid = 0L)
        val result = engine.dispatch(event, EMPTY_FACETED_CURSOR, GraalContext.INVALID)
        // LOGIC mode returns pure projection
        assertEquals(EMPTY_FACETED_CURSOR, result)
    }

    @Test
    fun `FacetEngine dispatches PointcutEvent to COUPLING mode for P_GET`() {
        val engine = FacetEngine(facet = SlabFacetFlag.HOT.facet)
        val event = PointcutEvent.new(FieldSynapse.P_GET, columnId = 0L, transid = 0L)
        val result = engine.dispatch(event, EMPTY_FACETED_CURSOR, GraalContext.INVALID)
        // COUPLING mode returns btrfs ioctl result
        assertEquals(Unit, result)
    }
}

// ==================== FACETSUBSCRIBER FANOUT TESTS ====================

class FacetFanoutTest {

    @Test
    fun `FacetElement fanout delivers message to all subscribers`() {
        val elem = FacetElement(key = FacetElementKey, facet = SlabFacetFlag.HOT.facet)
        var count = 0
        val sub1: FacetSubscriber = { count++ }
        val sub2: FacetSubscriber = { count++ }
        elem.addSubscriber(sub1)
        elem.addSubscriber(sub2)

        elem.fanout { /* message */ }
        assertEquals(2, count)
    }

    @Test
    fun `FacetElement fanout clears subscribers on CLOSED`() {
        val elem = FacetElement(key = FacetElementKey, facet = SlabFacetFlag.HOT.facet)
        elem.addSubscriber { }
        elem.addSubscriber { }
        assertEquals(2, elem.fanoutSubscribers.size)

        elem.open()
        elem.activate()
        elem.drain()
        elem.close()
        assertTrue(elem.fanoutSubscribers.isEmpty())
    }

    @Test
    fun `FacetElement subscriber throws after CLOSED`() {
        val elem = FacetElement(key = FacetElementKey, facet = SlabFacetFlag.HOT.facet)
        elem.open()
        elem.activate()
        elem.drain()
        elem.close()

        var threw = false
        try { elem.addSubscriber { threw = true } } catch (_: IllegalStateException) { threw = true }
        assertTrue(threw)  // adding subscriber after CLOSED throws
    }
}

// ==================== BITMASKED SLABFACET TESTS ====================

class SlabFacetBitMaskedTest {

    @Test
    fun `SlabFacet implements BitMasked Long`() {
        val facet = SlabFacetFlag.HOT.facet or SlabFacetFlag.COLD.facet
        assertTrue(facet.has(SlabFacetFlag.HOT.facet))
        assertTrue(facet.has(SlabFacetFlag.COLD.facet))
    }

    @Test
    fun `SlabFacetFlag mask ordinal index`() {
        assertEquals(0L, SlabFacetFlag.NONE.mask)
        assertEquals(1L shl 0, SlabFacetFlag.HOT.mask)
        assertEquals(1L shl 1, SlabFacetFlag.COLD.mask)
        assertEquals(1L shl 2, SlabFacetFlag.IMMUTABLE.mask)
        assertEquals(1L shl 3, SlabFacetFlag.DEDUP_CANDIDATE.mask)
        assertEquals(1L shl 4, SlabFacetFlag.COMPRESSED_ZSTD.mask)
        assertEquals(1L shl 5, SlabFacetFlag.S3_TIERED.mask)
        assertEquals(1L shl 6, SlabFacetFlag.SNAPSHOT_ANCHOR.mask)
        assertEquals(1L shl 7, SlabFacetFlag.WAL_ACTIVE.mask)
        assertEquals(1L shl 8, SlabFacetFlag.PERSISTENT.mask)
        assertEquals(1L shl 9, SlabFacetFlag.WAL_BUFFER.mask)
        assertEquals(1L shl 10, SlabFacetFlag.COLUMNAR_EXPORT.mask)
        assertEquals(1L shl 11, SlabFacetFlag.EPHEMERAL.mask)
        assertEquals(1L shl 12, SlabFacetFlag.COMPUTED.mask)
        assertEquals(1L shl 13, SlabFacetFlag.INDEXED.mask)
    }

    @Test
    fun `SlabFacetFlag isAtLeast comparison`() {
        val hot = SlabFacetFlag.HOT.facet
        val cold = SlabFacetFlag.COLD.facet
        assertTrue(hot.isAtLeast(hot))
        assertFalse(hot.isAtLeast(cold))
    }

    @Test
    fun `SlabFacetFlag isLessThan comparison`() {
        val hot = SlabFacetFlag.HOT.facet
        val cold = SlabFacetFlag.COLD.facet
        assertTrue(hot.isLessThan(cold))
        assertFalse(cold.isLessThan(hot))
    }

    @Test
    fun `SlabFacetFlag ordinalIndex lazy Series`() {
        val index: Lazy<Series<Int>> = BitMasked.ordinalIndex(SlabFacetFlag.entries)
        assertEquals(14, index.value.size)
        assertEquals(0, index.value[0])  // NONE ordinal
        assertEquals(1, index.value[1])  // HOT ordinal
    }

    @Test
    fun `SlabFacetFlag nameIndex lazy Pair`() {
        val index: Lazy<Pair<Series<String>, Series<Int>>> = BitMasked.nameIndex(SlabFacetFlag.entries)
        assertEquals(14, index.value.first.size)
        assertEquals("COLD", index.value.first[1])  // alphabetical order
    }
}

// ==================== POINTUCTEVENT WIREPROTO TESTS ====================

class PointcutEventWireProtoTest {

    @Test
    fun `PointcutEvent 24B wireproto alignment`() {
        val event = PointcutEvent.new(FieldSynapse.L_GET, columnId = 0xDEADBEEFL, transid = 0xCAFEBABEL)
        assertEquals(24, event.bytes.size)
    }

    @Test
    fun `PointcutEvent encodes opcode phase`() {
        val before = PointcutEvent.new(FieldSynapse.L_GET or 0, columnId = 1L, transid = 0L)
        val after = PointcutEvent.new(FieldSynapse.L_GET or 1, columnId = 1L, transid = 0L)
        assertTrue(before.synapse.isBefore)
        assertTrue(after.synapse.isAfter)
    }

    @Test
    fun `PointcutEvent decodes fieldOp`() {
        val lget = PointcutEvent.new(FieldSynapse.L_GET, columnId = 1L, transid = 0L)
        val lset = PointcutEvent.new(FieldSynapse.L_SET, columnId = 1L, transid = 0L)
        val pget = PointcutEvent.new(FieldSynapse.P_GET, columnId = 1L, transid = 0L)
        val pset = PointcutEvent.new(FieldSynapse.P_SET, columnId = 1L, transid = 0L)
        assertEquals(0, lget.synapse.fieldOp)
        assertEquals(1, lset.synapse.fieldOp)
        assertEquals(2, pget.synapse.fieldOp)
        assertEquals(3, pset.synapse.fieldOp)
    }

    @Test
    fun `PointcutEvent roundtrip columnId`() {
        val id = 0x123456789ABCDEF0L
        val event = PointcutEvent.new(FieldSynapse.L_GET, columnId = id, transid = 0L)
        assertEquals(id, event.columnId)
    }

    @Test
    fun `PointcutEvent roundtrip transid`() {
        val tid = 0xFEDCBA9876543210L
        val event = PointcutEvent.new(FieldSynapse.L_GET, columnId = 0L, transid = tid)
        assertEquals(tid, event.transid)
    }
}

// ==================== GRAALCONTEXT TESTS ====================

class GraalContextTest {

    @Test
    fun `GraalContext INVALID ptr is zero`() {
        assertEquals(0L, GraalContext.INVALID.ptr)
    }

    @Test
    fun `GraalContext eval returns computed value`() {
        val ctx = GraalContext.INVALID
        val result = ctx.eval("1 + 1", emptySeries())
        assertEquals(2, result)
    }

    @Test
    fun `GraalContext eval with bindings`() {
        val ctx = GraalContext.INVALID
        val bindings: Series<Join<String, Any>> = 2 j { i -> when(i) { 0 -> "x" j 10; else -> "y" j 20 } }
        val result = ctx.eval("x + y", bindings)
        assertEquals(30, result)
    }

    @Test
    fun `GraalContext registerModule exposes exports`() {
        val ctx = GraalContext.INVALID
        val exports: Series<Join<String, Any>> = 1 j { 0 -> "add" j { a: Int, b: Int -> a + b } }
        ctx.registerModule("math", exports)
        val result = ctx.eval("math.add(3, 4)", emptySeries())
        assertEquals(7, result)
    }
}

// ==================== HELPER ====================

private fun emptySeries(): Series<Join<String, Any>> = 0 j { throw IndexOutOfBoundsException() }
