package borg.trikeshed.vm

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.platform.Discontinued
import borg.trikeshed.pointcut.VmFacet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract every tier must pass. Bound here to an in-memory host (a three-op calculator) so the
 * contract itself compiles and runs on every target; jvmTest binds the same contract to Graal.
 */
abstract class VmHostContract {
    abstract fun host(): VmHost
    /** Source the host evaluates to `Num(2)`. */
    open val twoSource: String = "1+1"

    @Test
    fun spawnListEvalRevokeAndRowsAgree() = runTest {
        val host = host()
        val h = host.spawn(VmSpec("a", VmFacet.GRAAL_JS))
        assertEquals(listOf("a"), host.ids())
        assertEquals(Teleported.Num(2), h.eval(twoSource))
        val rows: Cursor = host.rows()
        assertEquals(1, rows.size)
        val exemplar = rows[0]
        assertEquals(VM_COLUMNS.map { it.first }, (0 until exemplar.size).map { exemplar[it].b().name.toString() })
        assertEquals("a", exemplar[0].a)
        assertTrue(exemplar[4].a in setOf("live", "fenced"), "phase before revoke: ${exemplar[4].a}")
        host.revoke("a", "test")
        assertEquals("revoked", host.rows()[0][4].a)
        val events = host.events.take(3).toList()
        assertEquals(listOf("spawned", "evaluated", "revoked"), events.map { it.toMap()["kind"] })
        host.close()
    }
}

/** In-memory tier for the contract: evaluates `a+b` / `a*b` / literals; everything else is discontinued. */
class InMemoryVmHost : VmHost {
    override val platform: String get() = "memory"
    override val languages: Set<VmFacet> get() = setOf(VmFacet.GRAAL_JS)
    private val handles = LinkedHashMap<String, Handle>()
    private val revoked = LinkedHashSet<String>()
    private var seq = 0L
    private val _events = MutableSharedFlow<VmEvent>(replay = 64)
    override val events: Flow<VmEvent> get() = _events

    override fun spawn(spec: VmSpec): VmHandle = Handle(spec).also { handles[spec.id] = it; _events.tryEmit(VmEvent.Spawned(spec.id, ++seq, spec)) }
    override fun get(id: String): VmHandle? = handles[id]
    override fun ids(): List<String> = handles.keys.sorted()
    override fun revoke(id: String, reason: String) { if (id in handles) { revoked += id; _events.tryEmit(VmEvent.Revoked(id, ++seq, reason)) } }
    override fun rows() = ids().map { id ->
        val h = handles.getValue(id)
        VmRow(id, h.facet.id, h.spec.trust.name, "memory", if (id in revoked) "revoked" else "live", 0, 0, 0, h.evals, 0)
    }.asCursor()

    inner class Handle(val spec: VmSpec) : VmHandle {
        override val id: String get() = spec.id
        override val facet: VmFacet get() = spec.facet
        override val tier: String get() = "memory"
        var evals = 0L
        override val isAlive: Boolean get() = id !in revoked
        override fun eval(source: String, name: String): Teleported {
            evals++
            val m = Regex("""\s*(-?\d+)\s*([+*])\s*(-?\d+)\s*""").matchEntire(source)
            val out = if (m != null) {
                val (a, op, b) = m.destructured
                Teleported.Num(if (op == "+") a.toLong() + b.toLong() else a.toLong() * b.toLong())
            } else Teleported.Num(source.trim().toLong())
            _events.tryEmit(VmEvent.Evaluated(id, ++seq, out.cid.hex, 0))
            return out
        }
        override fun stats(): VmStats = VmStats(evals = evals)
    }
}

class InMemoryVmHostContractTest : VmHostContract() {
    override fun host(): VmHost = InMemoryVmHost()
}

class DeadHostTest {
    @Test
    fun bareInterfaceIsDeadAndReported() {
        val none = VmHost.NONE
        assertEquals(emptyList(), none.ids())
        assertNull(none.get("x"))
        assertEquals(0, none.rows().size)
        assertFailsWith<NotImplementedError> { none.spawn(VmSpec("x", VmFacet.GRAAL_JS)) }
        assertTrue("vm.spawn" in Discontinued.features, "chokepoint recorded: ${Discontinued.features}")
        assertTrue(none.isDead)
    }

    @Test
    fun handleDefaultsAreDead() {
        val bare = object : VmHandle { override val id = "h"; override val facet = VmFacet.GRAAL_JS }
        assertFailsWith<NotImplementedError> { bare.eval("1") }
        assertTrue("vm.eval" in Discontinued.features)
        assertEquals("none", bare.tier)
    }
}
