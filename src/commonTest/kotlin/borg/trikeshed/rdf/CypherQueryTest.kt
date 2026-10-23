package borg.trikeshed.rdf

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import kotlin.test.*

/** Neo4j's face: a property graph read as quads, Cypher MATCH lowered to the one graph stage, Neo4j's HTTP result shape. */
class CypherQueryTest {
    private val ns = RdfVocab.FORGE
    private val xsdInt = "<${RdfVocab.XSD}integer>"
    private val store = QuadStore().apply {
        fun person(id: String, name: String, age: Int) {
            add("<$ns$id>", SparqlQuery.RDF_TYPE, "<${ns}Person>")
            add("<$ns$id>", "<${ns}name>", "\"$name\"")
            add("<$ns$id>", "<${ns}age>", "\"$age\"^^$xsdInt")
        }
        person("ann", "Ann", 34); person("bob", "Bob", 28); person("cat", "Cat", 41)
        add("<${ns}ann>", "<${ns}KNOWS>", "<${ns}bob>")
        add("<${ns}bob>", "<${ns}KNOWS>", "<${ns}cat>")
        add("<${ns}acme>", SparqlQuery.RDF_TYPE, "<${ns}Company>")
        add("<${ns}acme>", "<${ns}name>", "\"Acme\"")
        add("<${ns}ann>", "<${ns}WORKS_AT>", "<${ns}acme>")
    }
    private fun rows(c: Cursor): List<List<Any?>> = List(c.size) { r -> c[r].values.let { v -> List(v.size) { v[it] } } }
    private fun run(cypher: String): Pair<List<String>, List<List<Any?>>> = CypherQuery.parse(cypher).let { it.columns to rows(it.plan(store.scan())) }

    @Test fun matchLowersNodesLabelsRelationshipsAndPropertiesToPatterns() {
        assertEquals(listOf("a.name", "b.name") to listOf(listOf("\"Ann\"", "\"Bob\""), listOf("\"Bob\"", "\"Cat\"")), run("MATCH (a:Person)-[:KNOWS]->(b) RETURN a.name, b.name ORDER BY a.name"))
        assertEquals(listOf("a.name") to listOf(listOf("\"Bob\""), listOf("\"Cat\"")), run("MATCH (a:Person)<-[:KNOWS]-(b) RETURN a.name ORDER BY a.name"))
        assertEquals(listOf("employer") to listOf(listOf("\"Acme\"")), run("MATCH (a:Person {name: \"Ann\"})-[:WORKS_AT]->(c:Company) RETURN c.name AS employer"))
        assertEquals(listOf("a") to listOf(listOf("<${ns}ann>"), listOf("<${ns}bob>")), run("MATCH (a)-[:KNOWS]->(b) RETURN DISTINCT a ORDER BY a"))
        // an untyped edge is any relationship to a node, never a label or a property
        assertEquals(listOf("n") to listOf(listOf(3L)), run("MATCH (a)-->(b) RETURN count(*) AS n"))
        assertEquals(listOf("a.name") to listOf(listOf("\"Ann\"")), run("MATCH (a {age: 34}) RETURN a.name"))
    }

    @Test fun whereComparesNumbersAndStringsAndReturnAggregatesWithImplicitGrouping() {
        assertEquals(listOf("a.name") to listOf(listOf("\"Cat\""), listOf("\"Ann\"")), run("MATCH (a:Person) WHERE a.age > 30 RETURN a.name ORDER BY a.age DESC"))
        assertEquals(listOf("a.name") to listOf(listOf("\"Ann\"")), run("MATCH (a:Person) WHERE a.name STARTS WITH \"A\" RETURN a.name"))
        assertEquals(listOf("a.name") to listOf(listOf("\"Bob\""), listOf("\"Cat\"")), run("MATCH (a:Person) WHERE a.age IN [28, 41] RETURN a.name ORDER BY a.name"))
        assertEquals(listOf("a.name") to listOf(listOf("\"Bob\"")), run("MATCH (a:Person) WHERE NOT a.age > 30 AND a.name CONTAINS \"o\" RETURN a.name"))
        assertEquals(listOf("n") to listOf(listOf(3L)), run("MATCH (p:Person) RETURN count(*) AS n"))
        assertEquals(listOf("a.name", "count(*)") to listOf(listOf("\"Ann\"", 2L), listOf("\"Bob\"", 1L)), run("MATCH (a:Person)-->(b) RETURN a.name, count(*) ORDER BY a.name"))
        assertEquals(listOf("total") to listOf(listOf(103.0)), run("MATCH (a:Person) RETURN sum(a.age) AS total"))
        assertEquals(listOf("a.name") to listOf(listOf("\"Bob\"")), run("MATCH (a:Person) RETURN a.name ORDER BY a.age SKIP 0 LIMIT 1"))
    }

    @Test fun theSubsetRefusesByNameAndTheReceiptIsTheStatement() {
        assertTrue(assertFailsWith<CypherError> { CypherQuery.parse("MATCH (a)-[r:KNOWS]->(b) RETURN r") }.message!!.contains("relationship variables"))
        assertTrue(assertFailsWith<CypherError> { CypherQuery.parse("MATCH (a)-[:KNOWS]-(b) RETURN a") }.message!!.contains("undirected"))
        assertTrue(assertFailsWith<CypherError> { CypherQuery.parse("MATCH (a:Person) WHERE a.nick IS NULL RETURN a") }.message!!.contains("IS NULL"))
        assertTrue(assertFailsWith<CypherError> { CypherQuery.parse("CREATE (a:Person) RETURN a") }.message!!.contains("CREATE"))
        assertTrue(assertFailsWith<CypherError> { CypherQuery.parse("MATCH (a:Person) RETURN b.name") }.message!!.contains("b is not declared"))
        // a bare node is any subject, once per triple about it; DISTINCT names the nodes; a property sorts numerically before textually
        assertEquals(listOf("a") to listOf(listOf("<${ns}acme>"), listOf("<${ns}ann>"), listOf("<${ns}bob>"), listOf("<${ns}cat>")), run("MATCH (a) RETURN DISTINCT a ORDER BY a"))
        store.add("<${ns}dan>", SparqlQuery.RDF_TYPE, "<${ns}Person>").add("<${ns}dan>", "<${ns}age>", "\"9\"^^$xsdInt").add("<${ns}dan>", "<${ns}debt>", "\"-1\"^^$xsdInt")
        assertEquals(listOf("a.age") to listOf(listOf("\"9\"^^$xsdInt"), listOf("\"28\"^^$xsdInt")), run("MATCH (a:Person) RETURN a.age ORDER BY a.age LIMIT 2"))
        assertEquals(listOf("n") to listOf(listOf(1L)), run("MATCH (a {debt: -1}) RETURN count(*) AS n"))
        assertEquals(listOf("a.age") to listOf(listOf("\"9\"^^$xsdInt")), run("MATCH (a:Person) WHERE a.debt IN [-1, -2] RETURN a.age"))
        val q = CypherQuery.parse("MATCH (a:Person) RETURN a.name").plan(store.scan())
        assertEquals("MATCH (a:Person) RETURN a.name", q.receipt.statement)
        assertEquals(listOf("Graph", "Project"), q.receipt.stages)
    }

    @Test fun theHttpApiAnswersNeo4jsShape() {
        val api = CypherSurface(store)
        val reply = api.handle("POST", "/db/neo4j/tx/commit", mapOf("Content-Type" to "application/json"), """{"statements":[{"statement":"MATCH (a:Person) RETURN a.name, a.age ORDER BY a.age"},{"statement":"MATCH (a:Person) RETURN a.name LIMIT x"}]}""")
        assertEquals(200, reply.status)
        assertEquals(
            """{"results":[{"columns":["a.name","a.age"],"data":[{"row":["Bob",28]},{"row":["Ann",34]},{"row":["Cat",41]}]}],"errors":[{"code":"Neo.ClientError.Statement.SyntaxError","message":"LIMIT needs a non-negative integer, found 'x'"}]}""",
            reply.body,
        )
        assertEquals(400, api.handle("POST", "/db/neo4j/tx/commit", emptyMap(), "not json").status)
        assertEquals(405, api.handle("GET", "/db/neo4j/tx/commit", emptyMap(), "").status)
        assertEquals("""{"results":[{"columns":["a"],"data":[{"row":["${ns}ann"]}]}],"errors":[]}""", api.handle("POST", "/db/neo4j/tx/commit", emptyMap(), """{"statements":[{"statement":"MATCH (a {name: \"Ann\"}) RETURN a"}]}""").body)
    }
}
