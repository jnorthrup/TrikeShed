package borg.trikeshed.lcnc

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.rdf.*
import kotlin.test.*

class SparqlGraphTest {
    @Test fun quadStarterIsAnInspectionOnlySparqlGraph() {
        val program = LcncProgramConfix.fromJson("sparql-quad", LcncPresets.all().getValue("preset-sparql-quad"))
        assertTrue(program.controls.inspectionOnly)
        val query = SparqlGraph.lower(program)
        assertEquals(_l["subject", "predicate", "object"], query.variables)
        val store = QuadStore().add("<urn:a>", "<urn:p>", "\"one\"")
            .add("<urn:b>", "<urn:p>", "\"two\"", "<urn:graph>")
        assertEquals(2, query.plan(store.scan()).size, "the starter searches the union of graphs")
    }
    private fun graph() = LcncProgram("query", _l[
        LcncNode("q", SparqlGraph.SELECT),
        LcncNode("doc", SparqlGraph.VARIABLE, _m["name" j "?doc", "ord" j "1"]),
        LcncNode("party", SparqlGraph.VARIABLE, _m["name" j "?party", "ord" j "0"]),
        LcncNode("a", SparqlGraph.PATTERN, _m["subject" j "?doc", "predicate" j "<urn:party>", "object" j "?party", "graph" j "<urn:reviewed>"]),
        LcncNode("b", SparqlGraph.PATTERN, _m["subject" j "?doc", "predicate" j "<urn:kind>", "object" j "\"agreement\"", "graph" j "<urn:reviewed>"])
    ].toSeries(), _l[
        LcncWire("doc", "variable", "q", "variables"), LcncWire("party", "variable", "q", "variables"),
        LcncWire("a", "pattern", "q", "patterns"), LcncWire("b", "pattern", "q", "patterns")
    ].toSeries(), controls = LcncConfixControls(inspectionOnly = true))

    @Test fun visualQueryJoinsPatternsInTheSelectedGraph() {
        val p = graph()
        assertTrue(LcncTypeCheck.check(p).isEmpty())
        val query = SparqlGraph.lower(p)
        assertEquals(_l["party", "doc"], query.variables)
        val quads = QuadStore()
        quads.add("<urn:agreement-1>", "<urn:party>", "<urn:alice>", "<urn:reviewed>")
        quads.add("<urn:agreement-1>", "<urn:kind>", "\"agreement\"", "<urn:reviewed>")
        quads.add("<urn:agreement-2>", "<urn:party>", "<urn:bob>", "<urn:other>")
        quads.add("<urn:agreement-2>", "<urn:kind>", "\"agreement\"", "<urn:other>")
        val results = query.plan(quads.scan())
        assertEquals(1, results.size)
        assertEquals("<urn:alice>", results[0].values[0].toString())
        assertEquals("<urn:agreement-1>", results[0].values[1].toString())
    }

    @Test fun refusesInvalidStructureAndInjectedSyntax() {
        val p = graph()
        fun changed(id: String, key: String, value: String) = p.copy(nodes = p.nodes.view.map {
            if (it.id == id) it.copy(params = it.params + _m[key j value]) else it
        }.toList().toSeries())
        assertEquals("bad-term", assertFailsWith<SparqlGraphError> {
            SparqlGraph.lower(changed("a", "object", "?party . ?x ?y ?z"))
        }.code)
        assertEquals("bad-term", assertFailsWith<SparqlGraphError> {
            SparqlGraph.lower(changed("a", "graph", "\"not a graph\""))
        }.code)
        assertEquals("bad-term", assertFailsWith<SparqlGraphError> {
            SparqlGraph.lower(changed("a", "object", "_:unbound"))
        }.code)
        assertEquals("inspection-only", assertFailsWith<SparqlGraphError> {
            SparqlGraph.lower(p.copy(controls = LcncConfixControls()))
        }.code)
        assertEquals("query", assertFailsWith<SparqlGraphError> {
            SparqlGraph.lower(changed("doc", "name", "?missing"))
        }.code)
        val absent = p.copy(wires = p.wires.view.filter { it.toPort != "patterns" }.toList().toSeries(),
            nodes = p.nodes.view.filter { it.id != "a" && it.id != "b" }.toList().toSeries())
        assertEquals("missing-input", assertFailsWith<SparqlGraphError> { SparqlGraph.lower(absent) }.code)
    }
}
