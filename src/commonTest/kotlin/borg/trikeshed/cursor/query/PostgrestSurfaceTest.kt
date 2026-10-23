package borg.trikeshed.cursor.query

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.CouchTables
import borg.trikeshed.job.CasStore
import kotlin.test.*

/** The Supabase data API as supabase-js 2.118 calls it, over Couch-backed tables. */
class PostgrestSurfaceTest {
    private val cas = CasStore.inMemory()
    private val tables = CouchTables(Couch("trikeshed", CouchStoreFactory.casBacked(cas), cas))
    private val api = PostgrestSurface(Catalog(listOf({ tables.tables() })), tables)

    private fun call(method: String, path: String, body: String = "", vararg headers: Pair<String, String>) =
        api.handle(method, path, mapOf("Accept-Profile" to "public", *headers), body)

    @Test fun spasqlAndTheQuadTableAnswerThroughTheSqlEntryPoint() {
        val store = borg.trikeshed.rdf.QuadStore().load(borg.trikeshed.rdf.SparqlQueryTest.FIXTURE)
        val surface = PostgrestSurface(Catalog(listOf({ tables.tables() }, { store.tables() })), tables)
        val spasql = surface.sql("SPARQL PREFIX ex: <urn:ex:> SELECT ?s ?o WHERE { GRAPH ex:g1 { ?s ex:p ?o } } ORDER BY ?s")
        assertEquals(200, spasql.status, spasql.body)
        assertEquals("""[{"s":"<urn:ex:a>","o":"<urn:ex:b>"},{"s":"<urn:ex:b>","o":"<urn:ex:c>"}]""", spasql.body)
        val sql = surface.sql("SELECT S AS s, O AS o FROM db.dba.rdf_quad WHERE G = '<urn:ex:g1>' AND P = '<urn:ex:p>' ORDER BY S")
        assertEquals(spasql.body, sql.body, "quads mode: SQL and SPARQL read one table")
        assertEquals("""[{"ask":true}]""", surface.sql("SPARQL ASK { <urn:ex:a> <urn:ex:p> <urn:ex:b> }").body)
        assertEquals("""[{"n":6}]""", surface.sql("SELECT COUNT(*) AS n FROM triples WHERE S = '<urn:ex:a>'").body)
        val bad = surface.sql("SPARQL SELECT ?s WHERE { ?s ex:p ?o }")
        assertEquals(400, bad.status)
        assertTrue("ex" in bad.body, bad.body)
    }

    private fun seed() {
        assertEquals("[]", api.sql("CREATE TABLE documents (id bigint primary key, title text, owner text, score float8, embedding vector(3))").body)
        val inserted = call(
            "POST", "/rest/v1/documents?select=id,title",
            """[{"id":1,"title":"Rust ownership","owner":"u1","score":7.5,"embedding":[1,0,0]},
               {"id":2,"title":"Kotlin cursors","owner":"u2","embedding":[0,1,0]},
               {"id":3,"title":"rust lifetimes","owner":"u1","score":2,"embedding":[0.9,0.1,0]},
               {"id":4,"title":"Graphs","owner":"u2","score":9,"embedding":[0,0,1]}]""",
            "Prefer" to "return=representation",
        )
        assertEquals(201, inserted.status, inserted.body)
        assertEquals("""[{"id":1,"title":"Rust ownership"},{"id":2,"title":"Kotlin cursors"},{"id":3,"title":"rust lifetimes"},{"id":4,"title":"Graphs"}]""", inserted.body)
    }

    @Test fun readsLowerToTheOnePlanner() {
        seed()
        assertEquals("""[{"id":3,"title":"rust lifetimes"},{"id":1,"title":"Rust ownership"}]""",
            call("GET", "/rest/v1/documents?select=id%2Ctitle&owner=eq.u1&order=id.desc&limit=10").body)
        for ((query, ids) in listOf(
            "score=is.null" to "[2]",
            "score=not.is.null&order=score.desc" to "[4,1,3]",
            "title=ilike.*RUST*" to "[1,3]",
            "id=in.(1,3,9)" to "[1,3]",
            "id=not.in.(1,3)" to "[2,4]",
            "or=(score.gt.8,title.ilike.*kotlin*)" to "[2,4]",
            "or=(id.eq.1,and(owner.eq.u2,score.lt.10))" to "[1,4]",
            "order=score.desc.nullslast" to "[4,1,3,2]",
            "order=score.asc.nullsfirst" to "[2,3,1,4]",
        )) {
            val reply = call("GET", "/rest/v1/documents?select=id&$query")
            assertEquals(ids, Regex("\"id\":(\\d+)").findAll(reply.body).joinToString(",", "[", "]") { it.groupValues[1] }, query)
        }
        val page = call("GET", "/rest/v1/documents?select=*&offset=1&limit=2&order=id", "", "Prefer" to "count=exact")
        assertEquals(206, page.status)
        assertEquals("1-2/4", page.headers["Content-Range"])
        assertTrue(page.body.startsWith("""[{"id":2,"title":"Kotlin cursors","owner":"u2","score":null,"embedding":"[0.0,1.0,0.0]"}"""), page.body)
        val one = call("GET", "/rest/v1/documents?select=title&id=eq.4", "", "Accept" to "application/vnd.pgrst.object+json")
        assertEquals("""{"title":"Graphs"}""", one.body)
        assertEquals(406, call("GET", "/rest/v1/documents?owner=eq.u1", "", "Accept" to "application/vnd.pgrst.object+json").status)
    }

    @Test fun writesAreCouchDocuments() {
        seed()
        val dup = call("POST", "/rest/v1/documents", """{"id":2,"title":"again"}""")
        assertEquals(409, dup.status)
        assertTrue("\"code\":\"23505\"" in dup.body, dup.body)
        assertEquals(201, call("POST", "/rest/v1/documents?on_conflict=id", """{"id":2,"title":"merged"}""", "Prefer" to "resolution=merge-duplicates").status)
        assertEquals(204, call("PATCH", "/rest/v1/documents?owner=eq.u1", """{"owner":"u3"}""").status)
        assertEquals(204, call("DELETE", "/rest/v1/documents?id=eq.4").status)
        assertEquals(
            """[{"id":1,"owner":"u3","title":"Rust ownership"},{"id":2,"owner":"u2","title":"merged"},{"id":3,"owner":"u3","title":"rust lifetimes"}]""",
            call("GET", "/rest/v1/documents?select=id,owner,title&order=id").body,
        )
        // Rows minted without an id continue the identity sequence.
        assertEquals("""[{"id":5}]""", call("POST", "/rest/v1/documents?select=id", """{"title":"new"}""", "Prefer" to "return=representation").body)
    }

    @Test fun matchDocumentsRanksByCosineSimilarity() {
        seed()
        val reply = call("POST", "/rest/v1/rpc/match_documents?select=id,title", """{"query_embedding":[1,0,0],"match_threshold":0.5,"match_count":2}""")
        assertEquals(200, reply.status, reply.body)
        assertEquals("""[{"id":1,"title":"Rust ownership"},{"id":3,"title":"rust lifetimes"}]""", reply.body)
    }

    @Test fun evokeRpcsRankAndFuseOverTheCatalog() {
        seed()
        // documents owns a `score` column, so the marker comes back as evoke_score.
        val ranked = call("POST", "/rest/v1/rpc/evoke_query?select=id,title,evoke_score", """{"index":"documents","query":"rust","k":2}""")
        assertEquals(200, ranked.status, ranked.body)
        assertEquals(listOf(1L, 3L), Regex("\"id\":(\\d+)").findAll(ranked.body).map { it.groupValues[1].toLong() }.toList())
        assertTrue("\"evoke_score\":" in ranked.body, ranked.body)
        val fused = call(
            "POST", "/rest/v1/rpc/evoke_hybrid_fuse_candidates?select=id,score,source_names",
            """{"candidates":[{"source_name":"title","id":1,"raw_value":10,"source_rank":1,"weight":2},
                              {"source_name":"embedding","id":1,"raw_value":0.4,"source_rank":2,"direction":"lower_is_better"},
                              {"source_name":"embedding","id":2,"raw_value":0.1,"source_rank":1,"direction":"lower_is_better"}],"k":5}""",
        )
        assertEquals(200, fused.status, fused.body)
        assertTrue(fused.body.startsWith("""[{"id":1,"score":0.04891"""), fused.body)
        assertTrue("\"source_names\":[\"embedding\",\"title\"]" in fused.body.replace(" ", ""), fused.body)
        assertEquals(405, call("GET", "/rest/v1/rpc/evoke_hybrid_fuse_candidates?k=1").status)
    }

    @Test fun errorsCarryPostgrestCodes() {
        seed()
        for ((path, expected) in listOf(
            "/rest/v1/nope" to (404 to "PGRST205"),
            "/rest/v1/documents?select=nope" to (400 to "42703"),
            "/rest/v1/documents?id=eq" to (400 to "PGRST100"),
            "/rest/v1/documents?id=eq.abc" to (400 to "22P02"),
            "/rest/v1/documents?title=fts.rust" to (400 to "PGRST127"),
            "/rest/v1/documents?select=*,owner(*)" to (400 to "PGRST200"),
            "/rest/v1/rpc/nothing" to (404 to "PGRST202"),
        )) {
            val reply = call("GET", path)
            assertEquals(expected.first, reply.status, "$path ${reply.body}")
            assertTrue("\"code\":\"${expected.second}\"" in reply.body, "$path ${reply.body}")
        }
        assertEquals(406, api.handle("GET", "/rest/v1/documents", mapOf("Accept-Profile" to "private"), "").status)
    }
}
