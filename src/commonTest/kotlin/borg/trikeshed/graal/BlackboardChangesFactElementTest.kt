package borg.trikeshed.graal

import borg.trikeshed.context.ElementState
import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.Activation
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteOp
import borg.trikeshed.dag.ReteProduction
import borg.trikeshed.dag.ReteStoredFact
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Daemon-free: `ConfixBlackboard.empty()` + `ReteNetwork()`, every drain called
 * by hand (no dispatcher race), the wake path proven once at the end.
 */
class BlackboardChangesFactElementTest {

    private val partition = BlackboardContext(PlaneFacts.BLACKBOARD)

    private class Rig(admit: ((String) -> Boolean)? = null) {
        val board = ConfixBlackboard.empty()
        val rete = ReteNetwork()
        val ops = ArrayList<Pair<ReteOp, ReteStoredFact>>()
        val element = if (admit == null) BlackboardChangesFactElement(board, rete)
        else BlackboardChangesFactElement(board, rete, admit = admit)

        init {
            rete.observe { op, f -> ops += op to f }
        }

        fun fact(key: String): ReteStoredFact? =
            rete.workingMemory.facts(FactId(PlaneFacts.BLACKBOARD, key)).firstOrNull()
    }

    /** A value the board holds that is neither a map nor a scalar (the PointcutLanding case). */
    private class Landing(val site: Int) {
        override fun toString(): String = "landing:$site"
    }

    // ── put → drain → fact ────────────────────────────────────────────────

    @Test
    fun mapValueFlattensToStringFieldsWithReservedNamesPrefixed() = runTest {
        val rig = Rig()
        val key = "hook-run/panel/node/port/nuid-1"
        rig.board.put(
            key,
            linkedMapOf(
                "status" to "ran",
                "count" to 3,
                "ok" to true,
                "key" to "collides-with-reserved",
                "kind" to "also-collides",
                "nested" to mapOf("z" to 1, "a" to listOf(2, 3)),
                "absent" to null,
            ),
            "lcnc",
        )
        rig.element.drainKeys()

        val fact = rig.fact(key) ?: error("no fact for $key")
        val provenance = rig.board.getProvenance(key)!!
        assertEquals("blackboard", fact.fields[PlaneFacts.KIND])
        assertEquals(key, fact.fields[PlaneFacts.KEY])
        assertEquals("lcnc", fact.fields[PlaneFacts.ACTOR])
        assertEquals(provenance.timestamp, fact.fields[PlaneFacts.AT_MS])
        assertEquals("ran", fact.fields["status"])
        assertEquals("3", fact.fields["count"], "map entries are string-valued")
        assertEquals("true", fact.fields["ok"])
        assertEquals("collides-with-reserved", fact.fields["v.key"], "colliding entry moves under v.")
        assertEquals("also-collides", fact.fields["v.kind"])
        assertEquals(key, fact.fields[PlaneFacts.KEY], "the reserved key field is the blackboard key, not the entry")
        assertEquals(PlaneFacts.canonicalJson(mapOf("a" to listOf(2, 3), "z" to 1)), fact.fields["nested"], "nested structures print as canonical JSON")
        assertTrue("absent" in fact.fields && fact.fields["absent"] == null, "a null entry is kept as a null field")
        assertEquals(PlaneFacts.versionOf(fact.fields - PlaneFacts.AT_MS), fact.versionCid, "content version excludes the stamp")
        assertEquals(1L, rig.element.factsApplied)
        assertEquals(listOf(ReteOp.ASSERT), rig.ops.map { it.first })
        assertEquals(fact, rig.ops.single().second)
    }

    @Test
    fun scalarAndOpaqueValuesLandInValue() = runTest {
        val rig = Rig()
        rig.board.put("daemon/boot/kanban", "ok", "daemon")
        rig.board.put("daemon/linecas-index", 42L, "daemon")
        rig.board.put("pointcut/T/m/3", Landing(3), "python")
        rig.board.put("module/attached/x", listOf("a", "b"), "supervisor")
        rig.board.put("hermes/console/signal/1", null, "xterm")
        rig.element.drainKeys()

        assertEquals("ok", rig.fact("daemon/boot/kanban")!!.fields["value"])
        assertEquals("42", rig.fact("daemon/linecas-index")!!.fields["value"])
        assertEquals("landing:3", rig.fact("pointcut/T/m/3")!!.fields["value"], "an object the flattener does not know goes through toString")
        assertEquals(PlaneFacts.canonicalJson(listOf("a", "b")), rig.fact("module/attached/x")!!.fields["value"])
        val nullFact = rig.fact("hermes/console/signal/1")!!
        assertTrue("value" in nullFact.fields && nullFact.fields["value"] == null, "a null value is a fact with a null value field")
        assertEquals(5L, rig.element.factsApplied)
        assertEquals(5, rig.rete.workingMemory.query(partition, PlaneFacts.KIND to "blackboard").size)
    }

    // ── idempotency ──────────────────────────────────────────────────────

    @Test
    fun sameValueRePutIsNotANewVersion() = runTest {
        val rig = Rig()
        rig.board.put("a/b", mapOf("x" to "1"), "t")
        rig.element.drainKeys()
        val before = rig.fact("a/b")!!
        rig.ops.clear()

        // A new put of equal content: new provenance object, possibly a new stamp.
        rig.board.put("a/b", mapOf("x" to "1"), "t")
        rig.element.drainKeys()
        rig.element.drainKeys()

        assertEquals(1L, rig.element.factsApplied, "a same-value re-put applies nothing")
        assertTrue(rig.ops.isEmpty(), "the observer stays silent: ${rig.ops}")
        assertEquals(before.versionCid, rig.fact("a/b")!!.versionCid)
        assertEquals(3L, rig.element.drains)
    }

    @Test
    fun changedValueModifiesEvenWithinTheSameMillisecond() = runTest {
        val rig = Rig()
        rig.board.put("a/b", mapOf("x" to "1"), "t")
        rig.element.drainKeys()
        val v1 = rig.fact("a/b")!!.versionCid
        rig.ops.clear()

        // No delay: this put very likely carries the same millisecond stamp as the
        // first — the stamp-only diff BlackboardWire uses would miss it.
        rig.board.put("a/b", mapOf("x" to "2"), "t")
        rig.element.drainKeys()

        val fact = rig.fact("a/b")!!
        assertEquals("2", fact.fields["x"])
        assertNotEquals(v1, fact.versionCid)
        assertEquals(2L, rig.element.factsApplied)
        assertEquals(listOf(ReteOp.MODIFY), rig.ops.map { it.first })
        assertEquals(fact, rig.ops.single().second)
    }

    @Test
    fun actorChangeIsAVersionChange() = runTest {
        val rig = Rig()
        rig.board.put("a/b", mapOf("x" to "1"), "lcnc")
        rig.element.drainKeys()
        rig.board.put("a/b", mapOf("x" to "1"), "ide")
        rig.element.drainKeys()
        assertEquals("ide", rig.fact("a/b")!!.fields[PlaneFacts.ACTOR])
        assertEquals(2L, rig.element.factsApplied, "actor is content: who said it is part of the fact")
    }

    // ── retraction ───────────────────────────────────────────────────────

    @Test
    fun removeRetracts() = runTest {
        val rig = Rig()
        rig.board.put("a/b", mapOf("x" to "1"), "t")
        rig.board.put("a/c", mapOf("x" to "1"), "t")
        rig.element.drainKeys()
        rig.ops.clear()

        rig.board.remove("a/b")
        rig.element.drainKeys()

        assertNull(rig.fact("a/b"), "a vanished key leaves working memory")
        assertEquals("a/c", rig.fact("a/c")!!.fields[PlaneFacts.KEY])
        assertEquals(listOf(ReteOp.RETRACT), rig.ops.map { it.first })
        assertEquals("a/b", rig.ops.single().second.fields[PlaneFacts.KEY])
        assertEquals(3L, rig.element.factsApplied)

        rig.ops.clear()
        rig.element.drainKeys()
        assertTrue(rig.ops.isEmpty(), "a retract is applied once")

        // Re-put after remove is a fresh assert, not a modify of a ghost.
        rig.board.put("a/b", mapOf("x" to "9"), "t")
        rig.element.drainKeys()
        assertEquals(listOf(ReteOp.ASSERT), rig.ops.map { it.first })
        assertEquals("9", rig.fact("a/b")!!.fields["x"])
    }

    @Test
    fun admitFlipRetractsAnAlreadyAdmittedKey() = runTest {
        var allowed = true
        val rig = Rig(admit = { allowed })
        rig.board.put("a/b", mapOf("x" to "1"), "t")
        rig.element.drainKeys()
        assertEquals("1", rig.fact("a/b")!!.fields["x"])

        allowed = false
        rig.element.drainKeys()
        assertNull(rig.fact("a/b"), "a key the admit list stops accepting leaves working memory")
        assertEquals(ReteOp.RETRACT, rig.ops.last().first)
    }

    // ── admit list ───────────────────────────────────────────────────────

    @Test
    fun ruleFiringOutputsAreReceiptOnly() = runTest {
        val rig = Rig()
        rig.board.put("kanban/rule/board-rule/act-1", mapOf("salience" to "5"), "kanban-rete")
        rig.board.put("narsese/curation/belief/1f", mapOf("gloss" to "g"), "kanban-nars")
        rig.board.put("narsese/rete/firing/abc", mapOf("cid" to "abc"), "narsese")
        rig.board.put("kanban/committed/job-1/7", mapOf("column" to "doing"), "kanban")
        rig.element.drainKeys()

        assertNull(rig.fact("kanban/rule/board-rule/act-1"))
        assertNull(rig.fact("narsese/curation/belief/1f"))
        assertNull(rig.fact("narsese/rete/firing/abc"))
        assertEquals("doing", rig.fact("kanban/committed/job-1/7")!!.fields["column"], "kanban/committed is admitted; only kanban/rule is a firing output")
        assertEquals(1L, rig.element.factsApplied)
        assertEquals(1, rig.rete.workingMemory.query(partition, PlaneFacts.KIND to "blackboard").size)
        assertEquals(4, rig.board.keys().size, "the receipts stay on the board")
    }

    @Test
    fun namespacesTableResolvesLongestPrefixAndListsTheThreeExclusions() {
        assertEquals(
            setOf("narsese/curation/", "narsese/rete/firing/", "kanban/rule/"),
            BlackboardNamespaces.excludedByDefault.toSet(),
        )
        assertEquals("narsese/curation/", BlackboardNamespaces.namespaceOf("narsese/curation/x/1")!!.prefix)
        assertEquals("narsese/", BlackboardNamespaces.namespaceOf("narsese/other")!!.prefix)
        assertEquals("hermes/python/gap/", BlackboardNamespaces.namespaceOf("hermes/python/gap/root")!!.prefix)
        assertEquals("hermes/", BlackboardNamespaces.namespaceOf("hermes/python/unknown")!!.prefix)
        assertNull(BlackboardNamespaces.namespaceOf("probe/x"))
        assertTrue(BlackboardNamespaces.admitByDefault("probe/x"), "an unknown prefix is admitted: the list excludes, it does not whitelist")
        assertTrue(BlackboardNamespaces.admitByDefault("kanban/committed/j/1"))
        assertTrue(!BlackboardNamespaces.admitByDefault("kanban/rule/r/1"))
        assertEquals(BlackboardNamespaces.known.map { it.prefix }.toSet().size, BlackboardNamespaces.known.size, "no duplicate rows")
        assertTrue(BlackboardNamespaces.known.all { it.meaning.isNotBlank() && it.producer.isNotBlank() })
    }

    // ── loop guard ───────────────────────────────────────────────────────

    /**
     * A production with interest `kind=blackboard` that fires one activation per
     * blackboard fact, wired to the SAME sink shape KanbanModule installs
     * (`kanban/rule/<ruleId>/<activationId>` put back onto the board).
     */
    private class EchoProduction : ReteProduction {
        var evaluations = 0
        override val ruleId: String = "echo-blackboard"
        override val salience: Int = 10
        override val interests: Series<Join<String, Any?>> = 1 j { _: Int -> PlaneFacts.KIND j (PlaneFacts.BLACKBOARD as Any?) }
        private val version = ContentId.of("echo-v1".encodeToByteArray())

        override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) {
            evaluations++
            for (f in net.workingMemory.query(BlackboardContext(partitionId), PlaneFacts.KIND to PlaneFacts.BLACKBOARD)) {
                fire(
                    Activation(
                        activationId = "act-${f.factId.localId}-$evaluations",
                        ruleId = ruleId,
                        ruleVersionCid = version,
                        salience = salience,
                        sequence = evaluations.toLong(),
                        supportCids = listOf(f.versionCid),
                        bindings = mapOf("key" to (f.fields[PlaneFacts.KEY] as String)),
                    ),
                )
            }
        }
    }

    private fun installKanbanShapedSink(rig: Rig) {
        rig.rete.productionSink = { a ->
            rig.board.put("kanban/rule/${a.ruleId}/${a.activationId}", a.bindings + ("salience" to "${a.salience}"), "kanban-rete")
        }
    }

    @Test
    fun defaultAdmitBoundsTheFiringLoop() = runTest {
        val rig = Rig()
        val production = EchoProduction()
        rig.rete.register(production)
        installKanbanShapedSink(rig)

        rig.board.put("hook-run/p/n/port/1", mapOf("status" to "ran"), "lcnc")
        repeat(6) { rig.element.drainKeys() }

        assertEquals(1, production.evaluations, "one admitted key → one evaluation; the receipts it writes never re-enter")
        assertTrue(rig.board.keys().any { it.startsWith("kanban/rule/echo-blackboard/") }, "the sink did write its receipt onto the board")
        val ruleFacts = rig.rete.workingMemory.query(partition, PlaneFacts.KIND to "blackboard")
            .filter { it.factId.localId.startsWith("kanban/rule/") }
        assertTrue(ruleFacts.isEmpty(), "no kanban/rule fact exists: $ruleFacts")
        assertEquals(1L, rig.element.factsApplied)
        assertEquals(1, rig.rete.workingMemory.query(partition, PlaneFacts.KIND to "blackboard").size)
    }

    @Test
    fun admitEverythingIsTheUnboundedLoopTheGuardExists() = runTest {
        // The counter-case: with the firing outputs admitted, every drain asserts the
        // previous drain's receipt, evaluates, and writes a fresh receipt. The
        // evaluation count tracks the drain count without bound.
        val rig = Rig(admit = { true })
        val production = EchoProduction()
        rig.rete.register(production)
        installKanbanShapedSink(rig)

        rig.board.put("hook-run/p/n/port/1", mapOf("status" to "ran"), "lcnc")
        repeat(6) { rig.element.drainKeys() }

        assertEquals(6, production.evaluations, "one evaluation per drain, forever")
        val ruleFacts = rig.rete.workingMemory.query(partition, PlaneFacts.KIND to "blackboard")
            .filter { it.factId.localId.startsWith("kanban/rule/") }
        assertTrue(ruleFacts.size >= 5, "receipts became facts: ${ruleFacts.size}")
    }

    // ── the wake path ────────────────────────────────────────────────────

    @Test
    fun openCollectsTheChangeStreamAndCloseStops() = runTest {
        val rig = Rig()
        rig.element.open()
        assertEquals(ElementState.ACTIVE, rig.element.state)

        withContext(Dispatchers.Default) {
            rig.board.put("probe/x", mapOf("a" to "1"), "ide")
            withTimeout(10_000) {
                while (rig.rete.workingMemory.facts(FactId(PlaneFacts.BLACKBOARD, "probe/x")).isEmpty()) delay(10)
            }
            rig.board.remove("probe/x")
            withTimeout(10_000) {
                while (rig.rete.workingMemory.facts(FactId(PlaneFacts.BLACKBOARD, "probe/x")).isNotEmpty()) delay(10)
            }
        }
        assertEquals("1", rig.ops.first { it.first == ReteOp.ASSERT }.second.fields["a"])
        assertEquals(ReteOp.RETRACT, rig.ops.last().first)

        rig.element.close()
        assertEquals(ElementState.CLOSED, rig.element.state)
        val applied = rig.element.factsApplied
        rig.board.put("probe/y", mapOf("a" to "2"), "ide")
        withContext(Dispatchers.Default) { delay(100) }
        assertEquals(applied, rig.element.factsApplied, "after close nothing drains")
    }
}
