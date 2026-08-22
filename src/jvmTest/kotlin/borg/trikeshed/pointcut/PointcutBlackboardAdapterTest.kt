package borg.trikeshed.pointcut

import borg.trikeshed.context.lcnc.PointcutMark
import borg.trikeshed.cursor.TypedefProductionSystem
import borg.trikeshed.graal.ConfixBlackboard
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M1 gate — publish → adapter → blackboard entry present with provenance
 * `language` = [VmFacet] id.
 */
class PointcutBlackboardAdapterTest {

    // TypedefProductionSystem is a JVM-wide singleton: capture whatever state we
    // find on entry and put exactly that back, so tests that never touch the
    // singleton do not clobber it for other classes sharing the JVM.
    private var priorSubscriber: TypedefProductionSystem.SlabSubscriber? = null
    private var priorActive = false

    @BeforeTest
    fun capture() {
        priorSubscriber = TypedefProductionSystem.subscriber
        priorActive = TypedefProductionSystem.active
    }

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
                seq = 0,
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
        assertTrue(stored.coordinate.timestamp > 0)
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
                seq = 0,
            )
        )

        assertEquals(PointcutMark.AfterGet.raw, landing.markRaw)
        assertEquals("js", bb.getProvenance(landing.key)?.language)
    }

    @Test
    fun `explicit isWrite distinguishes a null write from a read`() {
        val bb = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(bb)

        // obj.head = None — newValue is null but this is still a write.
        val written = adapter.accept(
            PointcutEvent(VmFacet.GRAAL_PYTHON, "m.C.f", null, "head", null, 3),
            isWrite = true,
        )
        assertEquals(PointcutMark.AfterSet.raw, written.markRaw)

        val read = adapter.accept(
            PointcutEvent(VmFacet.GRAAL_PYTHON, "m.C.f", null, "head", null, 4),
            isWrite = false,
        )
        assertEquals(PointcutMark.AfterGet.raw, read.markRaw)
    }

    @Test
    fun `distinct properties on one coordinate land on distinct keys`() {
        val bb = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(bb)

        val a = adapter.accept(
            PointcutEvent(VmFacet.GRAAL_RUBY, "m.C.f", null, "alpha", 1, 10)
        )
        val b = adapter.accept(
            PointcutEvent(VmFacet.GRAAL_RUBY, "m.C.f", null, "beta", 2, 11)
        )

        assertTrue(a.key != b.key, "expected distinct keys, got ${a.key}")
        assertTrue(bb.has(a.key) && bb.has(b.key))
        assertEquals(2, adapter.size)
        assertEquals(2, adapter.keys.a)
    }

    @Test
    fun `guest site index is stable and disjoint from bytecode offsets`() {
        // Reproducible across calls (and therefore across runs) — not an
        // interning ordinal that depends on global class-init order.
        assertEquals(
            PointcutBlackboardAdapter.guestSiteIdx("head"),
            PointcutBlackboardAdapter.guestSiteIdx("head"),
        )
        // Negative by construction, so it can never alias a real bytecode offset.
        assertTrue(PointcutBlackboardAdapter.guestSiteIdx("head") < 0)
        assertTrue(PointcutBlackboardAdapter.guestSiteIdx("") < 0)

        val bb = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(bb)
        val guest = adapter.accept(
            PointcutEvent(VmFacet.GRAAL_JS, "m.C.f", null, "x", 1, 1)
        )
        assertTrue(guest.coordinate.bytecodeOffset < 0)
        // A ring landing on the same class/method with a real offset must not collide.
        assertTrue(guest.key != PointcutBlackboardAdapter.keyOf("m.C", "f", 9))
    }

    @Test
    fun `ring and guest landings share one epoch millisecond timebase`() {
        val before = System.currentTimeMillis()
        val rebased = PointcutBlackboardAdapter.nanoToEpochMillis(System.nanoTime())
        val after = System.currentTimeMillis()

        // A nanoTime stamp rebases into the same epoch window a guest event uses,
        // so the DAG's causal-parent search cannot systematically order every JVM
        // landing before every guest landing.
        assertTrue(
            rebased in (before - 1_000)..(after + 1_000),
            "rebased=$rebased outside [$before,$after]",
        )
    }

    @Test
    fun `ring slab publish flows through installed subscriber onto blackboard`() {
        val bb = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(bb, slabFacet = VmFacet.JVM)

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
        // ring stamps rebase onto epoch millis, not the raw nanoTime origin
        assertTrue(stored.coordinate.timestamp > 1_600_000_000_000L, "${stored.coordinate.timestamp}")

        assertEquals(VmFacet.JVM.id, bb.getProvenance(key)?.language)
        assertEquals("java", bb.getProvenance(key)?.language)
        assertEquals(0, adapter.rejected)
    }

    @Test
    fun `uninstall restores the displaced subscriber rather than nulling it`() {
        val sentinel = object : TypedefProductionSystem.SlabSubscriber {
            override fun onSlab(
                slab: Array<TypedefProductionSystem.TraceEvent>,
                count: Int,
                epoch: Long,
                nanoStart: Long,
                nanoEnd: Long,
            ) = Unit
        }
        TypedefProductionSystem.subscriber = sentinel

        val adapter = PointcutBlackboardAdapter(ConfixBlackboard.empty())
        val displaced = adapter.install()

        assertSame(sentinel, displaced)
        assertSame(adapter, TypedefProductionSystem.subscriber)
        assertTrue(adapter.isInstalled)

        // The bare, no-argument uninstall must put the sentinel back — not null.
        adapter.uninstall()
        assertSame(sentinel, TypedefProductionSystem.subscriber)
        assertTrue(!adapter.isInstalled)
    }

    @Test
    fun `onSlab isolates a throwing landing from the instrumented thread`() {
        // flush() runs inline on the traced application thread, so a failure
        // anywhere in the landing path must be absorbed and counted, never
        // propagated out of onSlab into the method being traced.
        val bb = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(bb)

        // typedefIdx past the fixed 65536-entry TypedefTable: resolve() indexes
        // its backing array directly, so this throws inside land().
        val poison = traceEvent().copy(typedefIdx = 70_000)

        adapter.onSlab(arrayOf(poison, traceEvent()), 2, 0L, 0L, 0L)

        // the bad event was rejected, the good one still landed
        assertEquals(1, adapter.rejected)
        assertEquals(1, adapter.size)
        assertTrue(bb.has(PointcutBlackboardAdapter.keyOf("org..types.Bag", "setLoad", 9)))
    }

    @Test
    fun `onSlab honors count over array length and is a no-op when empty`() {
        val bb = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(bb)

        adapter.onSlab(emptyArray(), 0, 0L, 0L, 0L)
        assertEquals(0, adapter.size)

        // count larger than the array must clamp, not throw
        adapter.onSlab(arrayOf(traceEvent()), 5, 1, 5_000_000L, 5_000_000L)

        val key = PointcutBlackboardAdapter.keyOf("org..types.Bag", "setLoad", 9)
        assertEquals(1, adapter.size)
        assertTrue(bb.has(key), "blackboard keys=${bb.keys()}")
        val stored = bb.get(key) as PointcutBlackboardAdapter.PointcutLanding
        // AFTER phase on a PROPERTY set* site -> AfterSet
        assertEquals(PointcutMark.AfterSet.raw, stored.markRaw)
        assertEquals(
            PointcutBlackboardAdapter.nanoToEpochMillis(5_000_000L),
            stored.coordinate.timestamp,
        )
    }

    private fun traceEvent() = TypedefProductionSystem.TraceEvent(
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
}
