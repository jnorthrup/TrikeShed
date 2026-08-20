package borg.trikeshed.pointcut

import borg.trikeshed.context.lcnc.PointcutMark
import borg.trikeshed.cursor.TypedefProductionSystem
import borg.trikeshed.graal.ConfixBlackboard
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M1 gate — publish → adapter → blackboard entry present with provenance
 * `language` = [VmFacet] id.
 */
class PointcutBlackboardAdapterTest {

    private var priorSubscriber: TypedefProductionSystem.SlabSubscriber? = null
    private var priorActive = false

    @AfterTest
    fun restore() {
        TypedefProductionSystem.subscriber = priorSubscriber
        TypedefProductionSystem.active = priorActive
    }

    @Test
    fun `guest VM PointcutEvent lands with facet provenance`() {
        val bb = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(bb)

        val landing = adapter.accept(
            PointcutEvent(
                vmFacet = VmFacet.GRAAL_PYTHON,
                coordinate = "org..types.Chain.push",
                target = null,
                propertyName = "head",
                newValue = 42,
                timestamp = 1_700_000_000_000L,
            )
        )

        // key scheme: pointcut/<typedef>/<method>/<siteIdx>
        assertTrue(landing.key.startsWith("pointcut/org..types.Chain/push/"), landing.key)

        // blackboard entry present
        assertTrue(bb.has(landing.key), "blackboard missing ${landing.key}")
        val stored = bb.get(landing.key) as PointcutBlackboardAdapter.PointcutLanding
        assertEquals(landing, stored)

        // provenance language == VmFacet id
        val prov = bb.getProvenance(landing.key)
        assertNotNull(prov)
        assertEquals(VmFacet.GRAAL_PYTHON.id, prov.language)
        assertEquals("python", prov.language)

        // DagCoordinate mapping
        assertEquals("org..types.Chain", stored.coordinate.className)
        assertEquals("push", stored.coordinate.methodName)
        assertEquals(1_700_000_000_000L, stored.coordinate.timestamp)
        assertTrue(stored.coordinate.threadId > 0)

        // PointcutMark byte rides in the payload — a carried newValue is an AFTER_SET
        assertEquals(PointcutMark.AfterSet.raw, stored.markRaw)
    }

    @Test
    fun `guest VM read observation marks AfterGet`() {
        val bb = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(bb)

        val landing = adapter.accept(
            PointcutEvent(
                vmFacet = VmFacet.GRAAL_JS,
                coordinate = "org..types.Chain.head",
                target = null,
                propertyName = "head",
                newValue = null,
                timestamp = 7L,
            )
        )

        assertEquals(PointcutMark.AfterGet.raw, landing.markRaw)
        assertEquals("js", bb.getProvenance(landing.key)?.language)
    }

    @Test
    fun `distinct properties on one coordinate land on distinct keys`() {
        val bb = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(bb)

        val a = adapter.accept(
            PointcutEvent(VmFacet.GRAAL_RUBY, "m.C.f", null, "alpha", 1, 10L)
        )
        val b = adapter.accept(
            PointcutEvent(VmFacet.GRAAL_RUBY, "m.C.f", null, "beta", 2, 11L)
        )

        assertTrue(a.key != b.key, "expected distinct keys, got ${a.key}")
        assertTrue(bb.has(a.key) && bb.has(b.key))
        assertEquals(2, adapter.size)
        assertEquals(2, adapter.keys.a)
    }

    @Test
    fun `ring slab publish flows through installed subscriber onto blackboard`() {
        val bb = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(bb, slabFacet = VmFacet.JVM)

        priorSubscriber = TypedefProductionSystem.subscriber
        priorActive = TypedefProductionSystem.active

        adapter.install()
        TypedefProductionSystem.active = true

        TypedefProductionSystem.publish(
            opcode = TypedefProductionSystem.OP_CALL,
            typedefName = "org..types.Chain",
            methodName = "org..types.Chain.push",
            siteIdx = 0x1234,
            depth = 2,
            isAfter = false,
        )
        TypedefProductionSystem.flush("test")

        val key = PointcutBlackboardAdapter.keyOf("org..types.Chain", "push", 0x1234)
        assertTrue(bb.has(key), "blackboard keys=${bb.keys()}")

        val stored = bb.get(key) as PointcutBlackboardAdapter.PointcutLanding
        assertEquals("org..types.Chain", stored.coordinate.className)
        assertEquals("push", stored.coordinate.methodName)
        assertEquals(0x1234, stored.coordinate.bytecodeOffset)
        assertEquals("CALL", stored.propertyName)
        // BEFORE phase on a non-mutator opcode -> BeforeGet
        assertEquals(PointcutMark.BeforeGet.raw, stored.markRaw)

        assertEquals(VmFacet.JVM.id, bb.getProvenance(key)?.language)
        assertEquals("java", bb.getProvenance(key)?.language)
    }

    @Test
    fun `onSlab honors count over array length and is a no-op when empty`() {
        val bb = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(bb)

        adapter.onSlab(emptyArray(), 0, 0L, 0L, 0L)
        assertEquals(0, adapter.size)

        val evt = TypedefProductionSystem.TraceEvent(
            opcode = TypedefProductionSystem.OP_PROPERTY,
            phase = 1,
            typedefIdx = TypedefProductionSystem.TypedefTable.register("org..types.Bag"),
            methodIdx = TypedefProductionSystem.InternPool.intern("org..types.Bag.setLoad"),
            siteIdx = 9,
            seq = 0,
            nano = 5_000_000L,
            depth = 1,
            callsiteHash = 0xBEEF,
            templateIdx = 0,
        )
        // count larger than the array must clamp, not throw
        adapter.onSlab(arrayOf(evt), 5, 1L, 5_000_000L, 5_000_000L)

        val key = PointcutBlackboardAdapter.keyOf("org..types.Bag", "setLoad", 9)
        assertEquals(1, adapter.size)
        assertTrue(bb.has(key), "blackboard keys=${bb.keys()}")
        val stored = bb.get(key) as PointcutBlackboardAdapter.PointcutLanding
        // AFTER phase on a PROPERTY set* site -> AfterSet
        assertEquals(PointcutMark.AfterSet.raw, stored.markRaw)
        assertEquals(5L, stored.coordinate.timestamp)
    }
}
