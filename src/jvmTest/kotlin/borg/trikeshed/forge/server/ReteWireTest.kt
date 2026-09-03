package borg.trikeshed.forge.server

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProduction
import borg.trikeshed.dag.Activation
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.rdf.RdfTerm
import borg.trikeshed.rdf.TurtleRdf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The read surface end to end with no daemon: a [ReteNetwork] holding facts in
 * two partitions, read back through [ReteWire] as JSON, as Turtle, and as the
 * production roster.
 */
class ReteWireTest {

    private val network = ReteNetwork()
    private val wire = ReteWire(network)

    private val cable1 = PlaneFacts.fact(
        PlaneFacts.PANELS, "demo/cable/0",
        linkedMapOf(PlaneFacts.KIND to "cable", PlaneFacts.KEY to "demo", "fromNode" to "a", "toNode" to "b", "type" to "json", "tags" to listOf("x", "y")),
    )
    private val cable2 = PlaneFacts.fact(
        PlaneFacts.PANELS, "demo/cable/1",
        linkedMapOf(PlaneFacts.KIND to "cable", PlaneFacts.KEY to "demo", "fromNode" to "b", "toNode" to "c", "type" to "List<TurnFact>"),
    )
    private val node = PlaneFacts.fact(
        PlaneFacts.PANELS, "other/node/n1",
        linkedMapOf(PlaneFacts.KIND to "node", PlaneFacts.KEY to "other", "node" to "n1", "type" to "timer"),
    )
    private val landing = PlaneFacts.fact(
        PlaneFacts.GRAAL, "pointcut/T/m/3",
        linkedMapOf(PlaneFacts.KIND to "pointcut", PlaneFacts.KEY to "pointcut/T/m/3", "mark" to 3, "enabled" to true, "sourceCid" to ContentId.of("src".encodeToByteArray())),
    )
    /** A pre-plane fact (no `key` field), the shape the couch tendon asserts today. */
    private val couchStyle = PlaneFacts.fact(
        PlaneFacts.GRAAL, "panels/x",
        linkedMapOf("_id" to "panels/x", "_rev" to "1-a"),
    )

    init {
        runBlocking {
            for (f in listOf(cable1, cable2, node, landing, couchStyle)) {
                network.assert(f.factId, f.fields, f.versionCid, f.board)
            }
        }
    }

    private fun get(path: String) = runBlocking { wire.route("GET", path, "", null) }

    /** `count` as the parser hands it back (Int or Long), compared as Int. */
    private val Map<String, Any?>.count: Int get() = (this["count"] as Number).toInt()

    @Suppress("UNCHECKED_CAST")
    private fun rows(path: String): Pair<Map<String, Any?>, List<Map<String, Any?>>> {
        val r = get(path) ?: error("no route for $path")
        assertEquals(200, r.status, r.body)
        val m = JsonSupport.parse(r.body) as Map<String, Any?>
        // the parser hands an empty array back as Array<Any?> and a filled one as a List — read both
        val facts: List<Map<String, Any?>> = when (val f = m["facts"]) {
            is Array<*> -> f.map { it as Map<String, Any?> }
            is Iterable<*> -> f.map { it as Map<String, Any?> }
            else -> error("facts is not an array: $f")
        }
        return m to facts
    }

    // ── /api/rete/facts ─────────────────────────────────────────────────

    @Test
    fun partitionAloneListsEveryFactOfThatPartitionInLocalIdOrder() {
        val (m, facts) = rows("/api/rete/facts?partition=panels")
        assertEquals(3, m.count)
        assertEquals("panels", m["partition"])
        assertEquals(listOf("demo/cable/0", "demo/cable/1", "other/node/n1"), facts.map { it["id"] })
        assertTrue(facts.all { it["partition"] == "panels" })
        val first = facts[0]
        assertEquals(cable1.versionCid.value, first["versionCid"])
        @Suppress("UNCHECKED_CAST")
        val fields = first["fields"] as Map<String, Any?>
        assertEquals("cable", fields["kind"])
        assertEquals(listOf("x", "y"), fields["tags"], "list fields survive the JSON row")
    }

    @Test
    fun fieldAndValueFilterWithinThePartition() {
        val (m, facts) = rows("/api/rete/facts?partition=panels&field=kind&value=cable")
        assertEquals(2, m.count)
        assertEquals(listOf("demo/cable/0", "demo/cable/1"), facts.map { it["id"] })

        val (_, typed) = rows("/api/rete/facts?partition=panels&field=type&value=List%3CTurnFact%3E")
        assertEquals(listOf("demo/cable/1"), typed.map { it["id"] }, "the exact CCEK type is a watchable value, percent-decoded")

        val (none, _) = rows("/api/rete/facts?partition=graal&field=kind&value=cable")
        assertEquals(0, none.count, "the filter does not leak across partitions")
    }

    @Test
    fun fieldWithoutValueMatchesPresenceAndNonStringValuesMatchByTheirText() {
        val (present, facts) = rows("/api/rete/facts?partition=panels&field=tags")
        assertEquals(1, present.count)
        assertEquals("demo/cable/0", facts[0]["id"])

        val (byInt, ints) = rows("/api/rete/facts?partition=graal&field=mark&value=3")
        assertEquals(1, byInt.count); assertEquals("pointcut/T/m/3", ints[0]["id"])
        val (byBool, _) = rows("/api/rete/facts?partition=graal&field=enabled&value=true")
        assertEquals(1, byBool.count)
        val (byCid, cids) = rows("/api/rete/facts?partition=graal&field=sourceCid&value=" + ContentId.of("src".encodeToByteArray()).value)
        assertEquals(1, byCid.count)
        @Suppress("UNCHECKED_CAST")
        val fields = cids[0]["fields"] as Map<String, Any?>
        assertEquals(ContentId.of("src".encodeToByteArray()).value, fields["sourceCid"], "a ContentId field renders as its sha256: text, not the data-class toString")
    }

    @Test
    fun keyIsTheReservedFieldAndFallsBackToTheLocalId() {
        val (m, facts) = rows("/api/rete/facts?partition=panels&key=demo")
        assertEquals(2, m.count)
        assertEquals(listOf("demo/cable/0", "demo/cable/1"), facts.map { it["id"] })

        // a fact with no `key` field is still reachable by its localId (PlaneFacts.keyOf fallback)
        val (couch, rows) = rows("/api/rete/facts?partition=graal&key=panels/x")
        assertEquals(1, couch.count); assertEquals("panels/x", rows[0]["id"])

        // key composes with field/value
        val (both, _) = rows("/api/rete/facts?partition=panels&key=demo&field=type&value=json")
        assertEquals(1, both.count)
    }

    @Test
    fun noPartitionSpansEveryPartitionAndUnknownPartitionIsEmpty() {
        val (all, facts) = rows("/api/rete/facts")
        assertEquals(5, all.count)
        assertNull(all["partition"])
        assertEquals(listOf("graal", "graal", "panels", "panels", "panels"), facts.map { it["partition"] }, "snapshot order: (partition, localId)")

        val (kinds, _) = rows("/api/rete/facts?field=kind&value=cable")
        assertEquals(2, kinds.count, "field/value without partition scans every partition")

        val (unknown, none) = rows("/api/rete/facts?partition=nowhere")
        assertEquals(0, unknown.count)
        assertTrue(none.isEmpty())
    }

    @Test
    fun theReadReflectsModifyAndRetract() = runBlocking {
        val newFields = cable2.fields + ("type" to "json")
        network.modify(cable2.factId, newFields, PlaneFacts.versionOf(newFields))
        val (json, _) = rows("/api/rete/facts?partition=panels&field=type&value=json")
        assertEquals(2, json.count, "the modified cable now matches type=json")

        network.retract(FactId(PlaneFacts.PANELS, "demo/cable/0"))
        val (after, facts) = rows("/api/rete/facts?partition=panels&key=demo")
        assertEquals(1, after.count)
        assertEquals("demo/cable/1", facts[0]["id"])
    }

    // ── /api/facts/rdf ──────────────────────────────────────────────────

    @Test
    fun turtleCarriesTheFactIriAndThePlanePredicatesAndParsesBack() {
        val r = get("/api/facts/rdf?partition=panels") ?: error("no route")
        assertEquals(200, r.status)
        assertTrue(r.contentType.startsWith("text/turtle"), r.contentType)
        assertTrue("@prefix fact: <${PlaneFacts.FACT_NS}>" in r.body, r.body)
        assertTrue("@prefix plane: <${PlaneFacts.FIELD_NS}>" in r.body, r.body)
        val iri = PlaneFacts.factIri(cable1.factId).iri
        assertTrue(iri in r.body || "fact:${iri.removePrefix(PlaneFacts.FACT_NS)}" in r.body, "the fact IRI is a subject:\n${r.body}")
        assertTrue("plane:fromNode" in r.body || "<${PlaneFacts.FIELD_NS}fromNode>" in r.body, "a field is a predicate:\n${r.body}")

        val graph = TurtleRdf.parse(r.body)
        val panelsTriples = (cable1.fields.size - 1 + 2) + (cable2.fields.size) + node.fields.size // tags fans out to 2
        assertEquals(panelsTriples, graph.triples.size, "one triple per scalar field, list elements fanned out")
        val subjects = graph.triples.map { (it.s as RdfTerm.Iri).iri }.toSet()
        assertEquals(setOf(cable1, cable2, node).map { PlaneFacts.factIri(it.factId).iri }.toSet(), subjects)
        assertTrue(graph.triples.none { PlaneFacts.factIdOf(it.s as RdfTerm.Iri)?.partitionId == PlaneFacts.GRAAL }, "the partition filter holds in Turtle too")
    }

    @Test
    fun turtleWithoutPartitionIsEveryPartitionAndTheSelectionFiltersApply() {
        val all = TurtleRdf.parse(get("/api/facts/rdf")!!.body)
        val partitions = all.triples.mapNotNull { PlaneFacts.factIdOf(it.s as RdfTerm.Iri)?.partitionId }.toSet()
        assertEquals(setOf(PlaneFacts.PANELS, PlaneFacts.GRAAL), partitions)

        val one = TurtleRdf.parse(get("/api/facts/rdf?partition=graal&key=pointcut/T/m/3")!!.body)
        assertEquals(setOf(PlaneFacts.factIri(landing.factId).iri), one.triples.map { (it.s as RdfTerm.Iri).iri }.toSet())

        val empty = get("/api/facts/rdf?partition=nowhere")!!
        assertEquals(200, empty.status)
        assertTrue(TurtleRdf.parse(empty.body).triples.isEmpty())
    }

    // ── /api/rete/productions ───────────────────────────────────────────

    @Test
    fun productionsListTheRegistryInTheAlignRowShape() {
        val watcher = object : ReteProduction {
            override val ruleId = "cable-type-watch"
            override val salience = 7
            override val interests: Series<Join<String, Any?>> = 2 j { i: Int -> if (i == 0) "kind" j ("cable" as Any?) else "type" j ("json" as Any?) }
            override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) = Unit
        }
        val disposer = network.register(watcher)
        try {
            val r = get("/api/rete/productions") ?: error("no route")
            assertEquals(200, r.status)
            @Suppress("UNCHECKED_CAST")
            val m = JsonSupport.parse(r.body) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val prods = m["productions"] as List<Map<String, Any?>>
            assertEquals(m.count, prods.size)
            assertEquals(listOf("job-dependency", "cable-type-watch"), prods.map { it["ruleId"] }, "salience order: the built-in job production (100) first")
            val mine = prods.first { it["ruleId"] == "cable-type-watch" }
            assertEquals(7, (mine["salience"] as Number).toInt())
            assertEquals(listOf("kind=cable", "type=json"), mine["interests"])
        } finally {
            disposer.close()
        }
        @Suppress("UNCHECKED_CAST")
        val after = JsonSupport.parse(get("/api/rete/productions")!!.body) as Map<String, Any?>
        assertEquals(1, after.count, "a disposed production leaves the roster")
    }

    // ── routing ─────────────────────────────────────────────────────────

    @Test
    fun onlyTheThreeGetRoutesAreClaimed() {
        assertNull(get("/api/rete"))
        assertNull(get("/api/rete/facts/extra"))
        assertNull(runBlocking { wire.route("POST", "/api/rete/facts", "{}", null) }, "the read surface never writes")
        assertNull(runBlocking { wire.route("POST", "/api/facts/rdf", "", null) })
    }

    @Test
    fun selectionSemanticsAreExactOnTheFactItself() {
        val s = ReteWire.Selection.of(mapOf("partition" to "panels", "field" to "type", "value" to "json"))
        assertTrue(s.admits(cable1))
        assertTrue(!s.admits(cable2))
        assertTrue(!s.admits(PlaneFacts.fact("graal", "g", cable1.fields)), "partition is exact")
        val empty = ReteWire.Selection.of(mapOf("partition" to "", "field" to "", "key" to ""))
        assertEquals(ReteWire.Selection(null, null, null, null), empty, "blank parameters are absent parameters")
        assertTrue(ReteWire.matches(3L, "3") && ReteWire.matches(false, "false") && !ReteWire.matches("3 ", "3"))
        assertEquals(BlackboardContext(PlaneFacts.PANELS), cable1.board)
    }
}
