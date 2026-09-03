package borg.trikeshed.graal.vitals

import borg.trikeshed.context.ElementState
import borg.trikeshed.context.lcnc.PointcutMark
import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.DagCoordinate
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteOp
import borg.trikeshed.dag.ReteStoredFact
import borg.trikeshed.pointcut.PointcutBlackboardAdapter
import borg.trikeshed.pointcut.PointcutBlackboardAdapter.PointcutLanding
import borg.trikeshed.pointcut.VmFacet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The graal partition without JFR: synthetic [JvmVitals.VitalEvent]s and a synthetic
 * snapshot drive the element, and the assertions read working memory back — bounded
 * state facts modified per event, the deopt cap, the pointcut identity, and the
 * no-op rule (an unchanged cid never reaches the network).
 */
class GraalFactElementTest {

    private val graal = BlackboardContext(PlaneFacts.GRAAL)

    private fun fact(net: ReteNetwork, localId: String): ReteStoredFact? =
        net.workingMemory.facts(FactId(PlaneFacts.GRAAL, localId)).firstOrNull()

    private fun kind(net: ReteNetwork, kind: String): List<ReteStoredFact> =
        net.workingMemory.query(graal, PlaneFacts.KIND to kind)

    private fun element(
        net: ReteNetwork,
        events: MutableSharedFlow<JvmVitals.VitalEvent> = MutableSharedFlow(extraBufferCapacity = 1024),
        snapshot: () -> Map<String, Any?> = { emptyMap() },
        landings: List<MutableSharedFlow<PointcutLanding>> = emptyList(),
        tickMs: Long = 0,
        deoptCap: Int = GraalFactElement.DEFAULT_DEOPT_CAP,
        allocTopN: Int = GraalFactElement.DEFAULT_ALLOC_TOP_N,
        clock: () -> Long = { 1_000L },
    ) = GraalFactElement(
        rete = net,
        events = events,
        snapshotSupplier = snapshot,
        pointcutFlows = landings,
        tickMs = tickMs,
        deoptCap = deoptCap,
        allocTopN = allocTopN,
        clock = clock,
    )

    private fun gcEvent(name: String, pauseMs: Long, cause: String, atMs: Long) = JvmVitals.VitalEvent(
        "gc", mapOf("name" to name, "cause" to cause, "pauseMs" to pauseMs, "atMs" to atMs, "longestPauseMs" to pauseMs), atMs,
    )

    private fun compileEvent(method: String, codeSize: Long, osr: Boolean) = JvmVitals.VitalEvent(
        "compile", mapOf("method" to method, "level" to 4L, "codeSize" to codeSize, "osr" to osr, "durationUs" to 10L, "ok" to true),
    )

    private fun deoptEvent(method: String, reason: String = "unstable_if", action: String = "reinterpret") = JvmVitals.VitalEvent(
        "deopt", mapOf("method" to method, "reason" to reason, "action" to action, "compileId" to 7L, "bci" to 3L, "line" to 12L),
    )

    private fun landing(key: String, facet: VmFacet = VmFacet.GRAAL_PYTHON, value: Any? = 42, timestamp: Long = 5_000L) = PointcutLanding(
        key = key,
        coordinate = DagCoordinate(className = "Probe", methodName = "run", bytecodeOffset = 17, timestamp = timestamp, threadId = 9L),
        mark = PointcutMark.AfterSet,
        facet = facet,
        propertyName = "x",
        value = value,
    )

    // ── gc: one state fact per collector, modified per event ──────────

    @Test
    fun gcEventsFoldIntoOneStateFactPerCollector() = runBlocking {
        val net = ReteNetwork()
        val ops = ArrayList<Pair<ReteOp, String>>()
        net.observe { op, f -> ops.add(op to f.factId.localId) }
        val el = element(net)

        el.onEvent(gcEvent("G1 Young", pauseMs = 4, cause = "G1 Evacuation Pause", atMs = 100))
        val first = assertNotNull(fact(net, "gc/G1 Young"), "one gc fact keyed by collector")
        assertEquals("gc", first.fields[PlaneFacts.KIND])
        assertEquals("gc/G1 Young", first.fields[PlaneFacts.KEY])
        assertEquals(GraalFactElement.ACTOR_JVMVITALS, first.fields[PlaneFacts.ACTOR])
        assertEquals(1L, first.fields["collections"])
        assertEquals(4L, first.fields["pauseMsTotal"])
        assertEquals(4L, first.fields["lastPauseMs"])
        assertEquals("G1 Evacuation Pause", first.fields["lastCause"])
        assertEquals(100L, first.fields["lastAtMs"])
        assertEquals(PlaneFacts.versionOf(first.fields), first.versionCid, "cid is the canonical hash of the fields")

        el.onEvent(gcEvent("G1 Young", pauseMs = 6, cause = "Metadata GC Threshold", atMs = 250))
        val second = assertNotNull(fact(net, "gc/G1 Young"))
        assertEquals(2L, second.fields["collections"], "second event is a modify of the same fact, not a new one")
        assertEquals(10L, second.fields["pauseMsTotal"])
        assertEquals(6L, second.fields["lastPauseMs"])
        assertEquals("Metadata GC Threshold", second.fields["lastCause"])
        assertEquals(250L, second.fields["lastAtMs"])
        assertTrue(first.versionCid != second.versionCid, "the cid moved with the counters")

        el.onEvent(gcEvent("G1 Old", pauseMs = 40, cause = "System.gc()", atMs = 300))
        assertEquals(2, kind(net, "gc").size, "one fact per collector")
        assertEquals(listOf(ReteOp.ASSERT to "gc/G1 Young", ReteOp.MODIFY to "gc/G1 Young", ReteOp.ASSERT to "gc/G1 Old"), ops)
        assertEquals(3L, el.factsApplied)
    }

    // ── jit: one accumulator fact ───────────────────────────────────

    @Test
    fun compileEventsAccumulateIntoTheJitFact() = runBlocking {
        val net = ReteNetwork()
        val el = element(net)

        el.onEvent(compileEvent("Foo.bar", codeSize = 120, osr = false))
        val one = assertNotNull(fact(net, "jit"))
        assertEquals("jit", one.fields[PlaneFacts.KIND])
        assertEquals("jit", one.fields[PlaneFacts.KEY])
        assertEquals(1L, one.fields["compilations"])
        assertEquals(0L, one.fields["osr"])
        assertEquals(120L, one.fields["compiledBytes"])

        el.onEvent(compileEvent("Foo.loop", codeSize = 80, osr = true))
        val two = assertNotNull(fact(net, "jit"))
        assertEquals(2L, two.fields["compilations"])
        assertEquals(1L, two.fields["osr"])
        assertEquals(200L, two.fields["compiledBytes"])
        assertEquals(1, kind(net, "jit").size, "still one jit fact")

        // cpu events are not facts
        el.onEvent(JvmVitals.VitalEvent("cpu", mapOf("jvm" to 0.5, "machine" to 0.7)))
        assertEquals(2L, el.factsApplied)
    }

    // ── deopt: per method, capped ───────────────────────────────────

    @Test
    fun deoptFactsAreCappedAtTheDistinctMethodLimit() = runBlocking {
        val net = ReteNetwork()
        val el = element(net)

        for (i in 0 until 300) el.onEvent(deoptEvent("Hot.m$i"))
        assertEquals(256, kind(net, "deopt").size, "256 distinct methods projected")
        assertEquals(44L, el.deoptDropped, "the 44 past the cap were counted, not projected")
        assertNull(fact(net, "deopt/Hot.m299"), "a method past the cap has no fact")

        // A method already inside the cap keeps counting after the cap is reached.
        el.onEvent(deoptEvent("Hot.m0", reason = "class_check", action = "make_not_entrant"))
        val m0 = assertNotNull(fact(net, "deopt/Hot.m0"))
        assertEquals(2L, m0.fields["count"])
        assertEquals("class_check", m0.fields["reason"])
        assertEquals("make_not_entrant", m0.fields["action"])
        assertEquals("Hot.m0", m0.fields["method"])
        assertEquals("deopt/Hot.m0", m0.fields[PlaneFacts.KEY])
        assertEquals(256, kind(net, "deopt").size)
    }

    @Test
    fun deoptCapIsAConstructorInput() = runBlocking {
        val net = ReteNetwork()
        val el = element(net, deoptCap = 3)
        for (i in 0 until 10) el.onEvent(deoptEvent("M.m$i"))
        assertEquals(3, kind(net, "deopt").size)
        assertEquals(7L, el.deoptDropped)
    }

    // ── pointcut: the landing's key IS the fact ─────────────────────

    @Test
    fun sameLandingTwiceIsOneFactAndOneOp() = runBlocking {
        val net = ReteNetwork()
        val ops = ArrayList<ReteOp>()
        net.observe { op, f -> if (f.factId.localId.startsWith("pointcut/")) ops.add(op) }
        val el = element(net)

        val key = PointcutBlackboardAdapter.keyOf("Probe", "run", 17)
        val l = landing(key)
        el.onLanding(l)
        el.onLanding(l)

        val f = assertNotNull(fact(net, key), "fact localId is the landing's blackboard key")
        assertEquals(listOf(ReteOp.ASSERT), ops, "the replay had the same cid: no modify reached the network")
        assertEquals(1L, el.factsApplied)
        assertEquals(1, kind(net, "pointcut").size)

        // reserved fields + exactly the shared flattening
        assertEquals("pointcut", f.fields[PlaneFacts.KIND])
        assertEquals(key, f.fields[PlaneFacts.KEY])
        assertEquals("python", f.fields[PlaneFacts.ACTOR])
        assertEquals(5_000L, f.fields[PlaneFacts.AT_MS])
        val flat = l.toFields()
        for ((name, value) in flat) assertEquals(value, f.fields[name], "field $name from PointcutLanding.toFields")
        assertEquals(setOf(PlaneFacts.KIND, PlaneFacts.KEY, PlaneFacts.ACTOR, PlaneFacts.AT_MS) + flat.keys, f.fields.keys)
        assertEquals("python", flat["facet"])
        assertEquals(PointcutMark.AfterSet.raw.toInt(), flat["mark"])
        assertEquals("x", flat["property"])
        assertEquals("42", flat["value"])
        assertEquals("Probe", flat["className"])
        assertEquals("run", flat["methodName"])
        assertEquals(17, flat["bytecodeOffset"])
        assertEquals(5_000L, flat["timestamp"])
        assertEquals(9L, flat["threadId"])

        // a new observation on the same site (the AFTER half, a different value) is a modify
        el.onLanding(landing(key, value = 43, timestamp = 5_001L))
        assertEquals(listOf(ReteOp.ASSERT, ReteOp.MODIFY), ops)
        assertEquals("43", fact(net, key)!!.fields["value"])
    }

    @Test
    fun landingWithoutValueFlattensToTheStringNull() {
        val flat = landing("pointcut/P/m/1", value = null).toFields()
        assertEquals("null", flat["value"], "the couch projection's contract, kept by the one author")
        assertEquals(PlaneFacts.versionOf(flat), PlaneFacts.versionOf(landing("pointcut/P/m/1", value = null).toFields()), "deterministic")
    }

    // ── tick: memory sample + alloc top-N ───────────────────────────

    @Test
    fun tickLandsTheMemoryFactAndTheAllocTopN() = runBlocking {
        val net = ReteNetwork()
        var heapUsed = 1_000L
        var allocation: List<Map<String, Any?>> = listOf(
            mapOf("class" to "java.lang.String", "bytes" to 900L),
            mapOf("class" to "byte[]", "bytes" to 500L),
            mapOf("class" to "java.util.HashMap\$Node", "bytes" to 300L),
        )
        val snapshot: () -> Map<String, Any?> = {
            mapOf(
                "memory" to mapOf("heapUsed" to heapUsed, "heapCommitted" to 4_000L, "heapMax" to 8_000L, "metaspaceUsed" to 250L, "processors" to 8),
                "gc" to mapOf("collections" to 0L, "lane" to mapOf("allocation" to allocation)),
            )
        }
        var now = 10L
        val el = element(net, snapshot = snapshot, allocTopN = 2, clock = { now })

        el.tick()
        val mem = assertNotNull(fact(net, "vitals/memory"))
        assertEquals("memory", mem.fields[PlaneFacts.KIND])
        assertEquals("vitals/memory", mem.fields[PlaneFacts.KEY])
        assertEquals(1_000L, mem.fields["heapUsed"])
        assertEquals(4_000L, mem.fields["heapCommitted"])
        assertEquals(8_000L, mem.fields["heapMax"])
        assertEquals(250L, mem.fields["metaspaceUsed"])
        assertEquals(10L, mem.fields[PlaneFacts.AT_MS])

        val alloc = kind(net, "alloc")
        assertEquals(listOf("alloc/byte[]", "alloc/java.lang.String"), alloc.map { it.factId.localId }, "top 2 by bytes, ordered by localId")
        assertEquals(900L, fact(net, "alloc/java.lang.String")!!.fields["bytes"])
        assertEquals("java.lang.String", fact(net, "alloc/java.lang.String")!!.fields["class"])
        assertEquals(3L, el.factsApplied, "memory + 2 alloc")

        // unchanged snapshot, new clock: memory is a fresh sample (modify), alloc rows are unchanged (no op)
        now = 11L
        el.tick()
        assertEquals(11L, fact(net, "vitals/memory")!!.fields[PlaneFacts.AT_MS])
        assertEquals(4L, el.factsApplied, "only the memory sample moved")

        // a class leaves the top-N: its fact is retracted; the newcomer is asserted
        heapUsed = 1_500L
        allocation = listOf(
            mapOf("class" to "java.lang.String", "bytes" to 950L),
            mapOf("class" to "java.util.HashMap\$Node", "bytes" to 700L),
            mapOf("class" to "byte[]", "bytes" to 500L),
        )
        el.tick()
        assertNull(fact(net, "alloc/byte[]"), "fell out of the top-N: retracted")
        assertEquals(700L, fact(net, "alloc/java.util.HashMap\$Node")!!.fields["bytes"])
        assertEquals(950L, fact(net, "alloc/java.lang.String")!!.fields["bytes"])
        assertEquals(2, kind(net, "alloc").size, "bounded by N")
        assertEquals(1_500L, fact(net, "vitals/memory")!!.fields["heapUsed"])
        assertEquals(8L, el.factsApplied, "memory modify + String modify + Node assert + byte[] retract")
    }

    @Test
    fun tickWithoutSectionsLeavesWorkingMemoryAlone() = runBlocking {
        val net = ReteNetwork()
        val el = element(net, snapshot = { mapOf("jfr" to mapOf("live" to false)) })
        el.tick()
        assertEquals(0L, el.factsApplied)
        assertNull(fact(net, "vitals/memory"))
    }

    // ── lifecycle: the flows drive the same bodies ───────────────────

    @Test
    fun openCollectsTheEventAndLandingFlowsUntilClosed() = runBlocking {
        val net = ReteNetwork()
        val events = MutableSharedFlow<JvmVitals.VitalEvent>(extraBufferCapacity = 64)
        val landings = MutableSharedFlow<PointcutLanding>(extraBufferCapacity = 64)
        val el = element(net, events = events, landings = listOf(landings), snapshot = {
            mapOf("memory" to mapOf("heapUsed" to 1L, "heapCommitted" to 2L, "heapMax" to 3L, "metaspaceUsed" to 4L))
        }, tickMs = 20)

        assertEquals(ElementState.CREATED, el.state)
        el.open()
        assertEquals(ElementState.ACTIVE, el.state)

        // collectors are subscribed once open() returned? SharedFlow has no replay here, so wait for subscription.
        withTimeout(5_000) { while (events.subscriptionCount.value == 0 || landings.subscriptionCount.value == 0) delay(5) }
        assertTrue(events.tryEmit(gcEvent("G1 Young", 1, "probe", 1)))
        assertTrue(landings.tryEmit(landing("pointcut/P/m/1")))
        withTimeout(5_000) {
            while (fact(net, "gc/G1 Young") == null || fact(net, "pointcut/P/m/1") == null || fact(net, "vitals/memory") == null) delay(5)
        }
        assertEquals(1L, fact(net, "gc/G1 Young")!!.fields["collections"])

        el.close()
        assertEquals(ElementState.CLOSED, el.state)
        val applied = el.factsApplied
        events.tryEmit(gcEvent("G1 Young", 1, "after-close", 2))
        delay(60)
        assertEquals(applied, el.factsApplied, "nothing lands after close")
        assertEquals(1L, fact(net, "gc/G1 Young")!!.fields["collections"])

        el.retractAll()
        assertEquals(0, net.workingMemory.all().count { it.factId.partitionId == PlaneFacts.GRAAL }, "detach hygiene")
    }
}
