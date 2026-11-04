package borg.trikeshed.forge.server

import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.rdf.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SparqlVisualBoundsTest {
    private fun program(variables: Series<String>, patterns: Series<Map<String, String>>): LcncProgram {
        val nodes = mutableListOf(LcncNode("select", SparqlGraph.SELECT))
        val wires = mutableListOf<LcncWire>()
        for ((ordinal, variable) in variables.view.withIndex()) {
            nodes.add(LcncNode("v$ordinal", SparqlGraph.VARIABLE, _m["name" j variable]))
            wires.add(LcncWire("v$ordinal", "variable", "select", "variables"))
        }
        for ((ordinal, pattern) in patterns.view.withIndex()) {
            nodes.add(LcncNode("p$ordinal", SparqlGraph.PATTERN, pattern))
            wires.add(LcncWire("p$ordinal", "pattern", "select", "patterns"))
        }
        return LcncProgram("bounded-observation-query", nodes.toSeries(), wires.toSeries(),
            controls = LcncConfixControls(inspectionOnly = true))
    }
    private fun patterns(subjects: Series<String> = s_["?marker", "?marker", "?marker", "?marker"], graph: String? = null): Series<Map<String, String>> {
        val names = s_["kind", "quote", "begin", "end"]
        return names.size j { ordinal: Int -> _m["subject" j subjects[ordinal], "predicate" j "<urn:${names[ordinal]}>",
            "object" j "?${names[ordinal]}"] + (graph?.let { _m["graph" j it] } ?: emptyMap()) }
    }
    private fun markers(count: Int): QuadStore {
        val store = QuadStore()
        for (index in 0 until count) {
            val subject = "<urn:marker:$index>"
            store.add(subject, "<urn:kind>", "\"negation\"").add(subject, "<urn:quote>", "\"not\"")
                .add(subject, "<urn:begin>", "\"$index\"").add(subject, "<urn:end>", "\"${index + 3}\"")
        }
        return store
    }
    private suspend fun execute(wire: SparqlWire, program: LcncProgram, dataset: String = "quad-store") =
        requireNotNull(wire.route("POST", "/sparql/visual",
            "POST /sparql/visual HTTP/1.1\r\nContent-Type: application/json\r\n\r\n" +
                """{"dataset":"$dataset","program":${LcncProgramConfix.toJson(program)}}""", null))
    private fun bindings(body: String): List<*> {
        val root = reifyStrict(body) as Map<*, *>
        val answer = root["results"] as Map<*, *>
        return (answer["results"] as Map<*, *>)["bindings"] as List<*>
    }

    @Test fun sameMarkerFourPropertiesReturnEvidenceWithoutCartesianOrUnprojectedWidthInflation(): Unit = runBlocking {
        val store = markers(100)
        store.add("<urn:sentence>", "<urn:sourceQuote>", RdfTerm.Literal("x".repeat(4000)).toTurtle())
        val query = program(s_["?marker", "?kind", "?quote", "?begin", "?end"], patterns())
        val result = execute(SparqlWire(SparqlSurface(store)), query)
        assertEquals(200, result.status, result.body)
        val root = reifyStrict(result.body) as Map<*, *>
        val descriptor = root["source"] as Map<*, *>
        assertEquals("quad-store", descriptor["selected"])
        assertEquals("not-established", descriptor["reviewAuthority"])
        assertEquals("not-established", descriptor["bindingProvenance"])
        val rows = bindings(result.body)
        assertEquals(100, rows.size)
        for (raw in rows) {
            val row = raw as Map<*, *>
            assertEquals("not", (row["quote"] as Map<*, *>)["value"])
            val begin = (row["begin"] as Map<*, *>)["value"] as String
            assertEquals((begin.toInt() + 3).toString(), (row["end"] as Map<*, *>)["value"])
            assertEquals("urn:marker:$begin", (row["marker"] as Map<*, *>)["value"])
        }
    }

    @Test fun trueCartesianAndMultiValuedSharedSubjectStillRefuseWithoutPartialBindings(): Unit = runBlocking {
        val cartesian = execute(SparqlWire(SparqlSurface(markers(100))),
            program(s_["?quote", "?begin", "?end"], patterns(s_["?a", "?b", "?c", "?d"])))
        assertEquals(413, cartesian.status)
        assertFalse(cartesian.body.contains("bindings")); assertFalse(cartesian.body.contains("results"))
        val shared = QuadStore()
        for (index in 0 until 100) for (name in s_["kind", "quote", "begin", "end"].view)
            shared.add("<urn:one-marker>", "<urn:$name>", "\"$index\"")
        val multi = execute(SparqlWire(SparqlSurface(shared)), program(s_["?quote", "?begin", "?end"], patterns()))
        assertEquals(413, multi.status)
        assertFalse(multi.body.contains("bindings")); assertFalse(multi.body.contains("results"))
        assertEquals(400, shared.size)
    }

    @Test fun repeatedVariablesAndSharedNamedGraphsKeepTheirActualEquality(): Unit = runBlocking {
        val store = QuadStore().add("<urn:self>", "<urn:same>", "<urn:self>")
        for (index in 0 until 100) store.add("<urn:a:$index>", "<urn:same>", "<urn:b:$index>")
        val repeated = execute(SparqlWire(SparqlSurface(store)), program(s_["?same"], s_[
            _m["subject" j "?same", "predicate" j "<urn:same>", "object" j "?same"]]))
        assertEquals(200, repeated.status, repeated.body); assertEquals(1, bindings(repeated.body).size)
        val split = QuadStore().add("<urn:marker>", "<urn:kind>", "\"negation\"", "<urn:first>")
            .add("<urn:marker>", "<urn:quote>", "\"not\"", "<urn:first>")
            .add("<urn:marker>", "<urn:begin>", "\"1\"", "<urn:second>")
            .add("<urn:marker>", "<urn:end>", "\"4\"", "<urn:second>")
        val named = execute(SparqlWire(SparqlSurface(split)), program(s_["?quote", "?begin", "?end"], patterns(graph = "?graph")))
        assertEquals(200, named.status, named.body); assertEquals(0, bindings(named.body).size)
        val constant = execute(SparqlWire(SparqlSurface(split)), program(s_["?quote"], s_[
            _m["subject" j "?marker", "predicate" j "<urn:quote>", "object" j "?quote", "graph" j "<urn:second>"]]))
        assertEquals(200, constant.status, constant.body); assertEquals(0, bindings(constant.body).size)
    }

    @Test fun checkedLiveSnapshotIsExecutedWithoutSecondRawRead(): Unit = runBlocking {
        val before = QuadStore().add("<urn:before>", "<urn:p>", "<urn:o>").scan()
        val after = QuadStore().add("<urn:after>", "<urn:p>", "<urn:o>").scan()
        var reads = 0
        val changing = 1 j { row: Int -> if (++reads <= 2) before.b(row) else after.b(row) }
        val wire = SparqlWire(SparqlSurface(QuadStore()), live = { changing })
        val result = execute(wire, program(s_["?subject"], s_[
            _m["subject" j "?subject", "predicate" j "<urn:p>", "object" j "<urn:o>"]]), "rete-projection")
        assertEquals(200, result.status, result.body)
        assertTrue(result.body.contains("urn:before")); assertFalse(result.body.contains("urn:after"))
        assertEquals(2, reads, "Raw live cursor is sampled for metadata and values, then guard and executor use only the captured snapshot")
    }

    @Test fun acceptedNulContainingTermsCannotCollideInBoxedOrVisualJoinKeys(): Unit = runBlocking {
        // These identifiers are accepted by the existing RDF model/parser; this is not a strict Turtle grammar claim.
        val a = RdfTerm.BlankNode("a"); val bc = RdfTerm.BlankNode("b\u0000_:c")
        val ab = RdfTerm.BlankNode("a\u0000_:b"); val c = RdfTerm.BlankNode("c")
        assertEquals(ab, TurtleRdf.parseTerm(ab.toTurtle()))
        val store = QuadStore().add(RdfTriple(a, RdfTerm.Iri("urn:left"), bc))
            .add(RdfTriple(ab, RdfTerm.Iri("urn:right"), c))
        val query = SparqlQuery.parse("SELECT ?x ?y WHERE { ?x <urn:left> ?y . ?x <urn:right> ?y . }")
        val snapshot = store.scan()
        val boxed = snapshot.size j { row: Int -> snapshot.b(row) }
        assertEquals(0, query.plan(snapshot).size)
        assertEquals(0, query.plan(boxed).size, "Distinct shared-variable tuples must not alias on an embedded NUL")
        val visual = program(s_["?x", "?y"], s_[
            _m["subject" j "?x", "predicate" j "<urn:left>", "object" j "?y"],
            _m["subject" j "?x", "predicate" j "<urn:right>", "object" j "?y"]])
        val before = execute(SparqlWire(SparqlSurface(store)), visual)
        assertEquals(200, before.status, before.body); assertEquals(0, bindings(before.body).size)
        store.add(RdfTriple(a, RdfTerm.Iri("urn:right"), bc))
        val after = execute(SparqlWire(SparqlSurface(store)), visual)
        assertEquals(200, after.status, after.body); assertEquals(1, bindings(after.body).size)
        assertEquals(3, store.size, "No source term is rejected, normalized or removed to avoid the collision")
    }

}
