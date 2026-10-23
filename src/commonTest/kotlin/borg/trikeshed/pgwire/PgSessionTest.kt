package borg.trikeshed.pgwire

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.CouchTables
import borg.trikeshed.cursor.query.Catalog
import borg.trikeshed.cursor.query.PostgrestSurface
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import borg.trikeshed.rdf.QuadStore
import borg.trikeshed.rdf.SparqlQueryTest
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.test.*

/** The backend session as psql and pgjdbc drive it: startup, the simple and extended protocols, the catalog psql reads. */
class PgSessionTest {
    private val cas = CasStore.inMemory()
    private val tables = CouchTables(Couch("trikeshed", CouchStoreFactory.casBacked(cas), cas))
    private val store = QuadStore().load(SparqlQueryTest.FIXTURE)
    private val surface = PostgrestSurface(Catalog(listOf({ tables.tables() }, { store.tables() })), tables)
    private val session = PgSession(surface, pid = 7, secret = 42)

    /** Backend frames as (type, payload). */
    private fun frames(bytes: ByteArray): List<Pair<Char, ByteArray>> {
        val out = ArrayList<Pair<Char, ByteArray>>()
        var i = 0
        while (i < bytes.size) {
            val type = bytes[i].toInt().toChar()
            val length = ByteBuffer(bytes).getInt(i + 1)
            out += type to bytes.copyOfRange(i + 5, i + 1 + length)
            i += 1 + length
        }
        return out
    }

    private fun types(bytes: ByteArray) = frames(bytes).map { it.first }.joinToString("")
    private fun strings(payload: ByteArray): List<String> = payload.decodeToString().split('\u0000').dropLast(1)

    /** The cells of a DataRow as text (null for SQL NULL). */
    private fun cells(payload: ByteArray): List<String?> {
        val r = PgReader(payload, 0, payload.size)
        return List(r.int16()) { r.int32().let { n -> if (n < 0) null else r.bytes(n).decodeToString() } }
    }

    /** RowDescription field names with their type oids. */
    private fun fields(payload: ByteArray): List<Pair<String, Int>> {
        val r = PgReader(payload, 0, payload.size)
        return List(r.int16()) { val name = r.cstring(); r.int32(); r.int16(); val oid = r.int32(); r.int16(); r.int32(); r.int16(); name to oid }
    }

    private fun query(sql: String) = frames(session.receive(PgFrontend.Query(sql)))
    private fun rows(sql: String): List<List<String?>> = query(sql).filter { it.first == 'D' }.map { cells(it.second) }
    private fun error(sql: String): String = query(sql).first { it.first == 'E' }.second.let { p -> strings(p).first { it.startsWith("C") }.substring(1) }

    private fun seed() {
        assertEquals("CZ", types(session.receive(PgFrontend.Query("CREATE TABLE documents (id bigint primary key, title text, score float8, embedding vector(3))"))))
        assertEquals(201, surface.handle("POST", "/rest/v1/documents", mapOf("Content-Type" to "application/json"),
            """[{"id":1,"title":"Rust ownership","score":7.5,"embedding":[1,0,0]},{"id":2,"title":"Kotlin cursors","embedding":[0,1,0]},{"id":3,"title":"it's","score":2,"embedding":[0,0,1]}]""").status)
    }

    @Test fun startupIsTrustThenTheReportedParametersAndReady() {
        assertContentEquals(PgBackend.NO, session.receive(PgFrontend.SslRequest))
        val reply = frames(session.receive(PgFrontend.Startup(mapOf("user" to "ann", "database" to "trikeshed", "client_encoding" to "UTF8", "application_name" to "psql"))))
        assertEquals("R" + "S".repeat(PgSession.REPORTED.size) + "KZ", reply.joinToString("") { it.first.toString() })
        assertContentEquals(byteArrayOf(0, 0, 0, 0), reply[0].second)
        val reported = reply.filter { it.first == 'S' }.associate { strings(it.second).let { (k, v) -> k to v } }
        assertEquals("14.0", reported["server_version"])
        assertEquals("UTF8", reported["client_encoding"])
        assertEquals("psql", reported["application_name"])
        assertEquals("ann", reported["session_authorization"])
        assertEquals("on", reported["standard_conforming_strings"])
        assertEquals("I", reply.last().second.decodeToString())
        assertTrue(session.started)
        // psql under a C locale announces SQL_ASCII: every encoding pg knows is accepted and reported back
        val ascii = frames(PgSession(surface, 1, 1).receive(PgFrontend.Startup(mapOf("user" to "x", "database" to "trikeshed", "client_encoding" to "SQL_ASCII"))))
        assertEquals(listOf("client_encoding", "SQL_ASCII"), strings(ascii.first { it.first == 'S' && it.second.decodeToString().startsWith("client_encoding") }.second))
        assertEquals("E", types(PgSession(surface, 1, 1).receive(PgFrontend.Startup(mapOf("user" to "x", "database" to "trikeshed", "client_encoding" to "KLINGON")))))
        // the one database is the one pg_database lists; another name is pg's 3D000
        val other = frames(PgSession(surface, 1, 1).receive(PgFrontend.Startup(mapOf("user" to "ann", "database" to "ann"))))
        assertEquals("E", other.joinToString("") { it.first.toString() })
        assertTrue("3D000" in other[0].second.decodeToString())
    }

    @Test fun theSimpleProtocolAnswersRowsTagsAndErrors() {
        seed()
        val one = query("SELECT 1 AS one")
        assertEquals("TDCZ", one.joinToString("") { it.first.toString() })
        assertEquals(listOf("one" to 20), fields(one[0].second))
        assertEquals(listOf("1"), cells(one[1].second))
        assertEquals("SELECT 1", strings(one[2].second)[0])
        assertEquals(listOf(listOf("PostgreSQL 14.0 (TrikeShed)", "public", "trikeshed", "2")), rows("SELECT version(), current_schema(), session_user, 1 + 1"))
        assertEquals("IZ", types(session.receive(PgFrontend.Query("  "))))
        assertEquals(listOf(listOf("1", "Rust ownership", "7.5", "{1.0,0.0,0.0}"), listOf("2", "Kotlin cursors", null, "{0.0,1.0,0.0}"), listOf("3", "it's", "2.0", "{0.0,0.0,1.0}")),
            rows("SELECT id, title, score, embedding FROM documents ORDER BY id"))
        assertEquals(listOf(listOf("1")), rows("SELECT id /* the key */ FROM documents -- one\n WHERE id = 1"))
        assertEquals("IZ", types(session.receive(PgFrontend.Query("-- nothing here"))))
        assertEquals(listOf(listOf("C:\\")), rows("SELECT 'C:\\'"))
        assertEquals(listOf("id" to 20, "title" to 25, "score" to 701, "embedding" to 1021), fields(query("SELECT * FROM documents WHERE id = 99")[0].second), "an empty result still describes its columns")
        assertEquals(listOf("n" to 25), fields(query("SELECT title AS n FROM documents WHERE id = 99")[0].second))
        // two statements in one query, the second failing: rows, then the error, then ready
        assertEquals("TDCEZ", types(session.receive(PgFrontend.Query("SELECT 1; SELECT nope FROM documents"))))
        assertEquals("42703", error("SELECT nope FROM documents"))
        assertEquals("42P01", error("SELECT * FROM nothing"))
        assertEquals("42883", error("SELECT nope(1)"))
        assertEquals("42702", error("SELECT id FROM documents d JOIN documents e ON d.id = e.id"), "an ambiguous column is pg's 42702, no longer an internal error")
        assertEquals("0A000", error("INSERT INTO documents VALUES (1)"))
        assertEquals("42601", error("SELEC 1"))
        assertEquals(listOf(listOf("<urn:ex:a>", "<urn:ex:b>")), rows("SPARQL SELECT ?s ?o WHERE { GRAPH <urn:ex:g1> { ?s <urn:ex:p> ?o } } ORDER BY ?s LIMIT 1"))
        assertEquals(listOf(listOf("t")), rows("SPARQL ASK { <urn:ex:a> <urn:ex:p> <urn:ex:b> }"))
        assertEquals(listOf("1"), rows("SELECT h.id FROM evoke_query('documents', 'rust', 2) h ORDER BY h.id").map { it[0] }, "Evoke's explicit-hit form on the wire: one title holds the term")
    }

    @Test fun sessionParametersAndTransactionsAreBookkeeping() {
        val set = query("SET application_name = 'DBeaver'")
        assertEquals("SCZ", set.joinToString("") { it.first.toString() }, "a reported parameter answers ParameterStatus before its tag")
        assertEquals(listOf("application_name", "DBeaver"), strings(set[0].second))
        assertEquals("CZ", types(session.receive(PgFrontend.Query("SET extra_float_digits = 3"))))
        assertEquals(listOf(listOf("3")), rows("SHOW extra_float_digits"))
        assertEquals(listOf(listOf("\"\$user\", public")), rows("SHOW search_path"))
        assertEquals(listOf("search_path" to 25), fields(query("SHOW search_path")[0].second))
        assertEquals("42704", error("SHOW nope"))
        assertEquals(listOf(listOf("read committed")), rows("SHOW TRANSACTION ISOLATION LEVEL"), "pgjdbc's getTransactionIsolation")
        assertEquals("SCZ", types(session.receive(PgFrontend.Query("SET TIME ZONE 'Europe/Paris'"))), "TimeZone is a reported parameter")
        assertEquals(listOf(listOf("Europe/Paris")), rows("SHOW TIME ZONE"))
        assertEquals(listOf(listOf("TimeZone", "Europe/Paris")), rows("SELECT name, setting FROM pg_settings WHERE lower(name) = 'timezone'"), "one row per parameter")
        assertEquals("SCZ", types(session.receive(PgFrontend.Query("RESET TIME ZONE"))))
        assertEquals(listOf(listOf("UTC")), rows("SHOW TIME ZONE"))
        assertEquals("SCZ", types(session.receive(PgFrontend.Query("SET NAMES 'LATIN1'"))))
        assertEquals(listOf(listOf("LATIN1")), rows("SHOW client_encoding"))
        assertEquals("22023", error("SET client_encoding = 'KLINGON'"))
        assertEquals(listOf(listOf("DBeaver", "session")), rows("SELECT setting, source FROM pg_catalog.pg_settings WHERE name = 'application_name'"))
        assertEquals("CZ", types(session.receive(PgFrontend.Query("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY"))))
        val begin = query("BEGIN")
        assertEquals("BEGIN", strings(begin[0].second)[0])
        assertEquals("T", begin[1].second.decodeToString())
        assertEquals("E", query("SELECT nope FROM documents").last().second.decodeToString(), "a failed statement fails the transaction")
        assertEquals("25P02", error("SELECT 1"))
        val commit = query("COMMIT")
        assertEquals("ROLLBACK", strings(commit[0].second)[0])
        assertEquals("I", commit[1].second.decodeToString())
        assertEquals("CZ", types(session.receive(PgFrontend.Query("DISCARD ALL"))))
        assertEquals("SCZ", types(session.receive(PgFrontend.Query("RESET application_name"))))
        assertEquals(listOf(listOf("")), rows("SHOW application_name"))
    }

    @Test fun theExtendedProtocolBindsParametersAndDescribes() {
        seed()
        assertEquals("1", types(session.receive(PgFrontend.Parse("", "SELECT id, title FROM documents WHERE id = \$1 ORDER BY id", intArrayOf(0)))))
        assertEquals("2", types(session.receive(PgFrontend.Bind("", "", intArrayOf(0), arrayOf("1".encodeToByteArray()), intArrayOf()))))
        val described = frames(session.receive(PgFrontend.Describe('P', "")))
        assertEquals(listOf("id" to 20, "title" to 25), fields(described[0].second))
        val executed = frames(session.receive(PgFrontend.Execute("", 0)))
        assertEquals("DC", executed.joinToString("") { it.first.toString() })
        assertEquals(listOf("1", "Rust ownership"), cells(executed[0].second))
        assertEquals("SELECT 1", strings(executed[1].second)[0])
        assertEquals("Z", types(session.receive(PgFrontend.Sync)))
        // a named statement: Describe('S') answers the parameter types and the row shape before any Bind
        session.receive(PgFrontend.Parse("s1", "SELECT title FROM documents WHERE id = \$1", intArrayOf(20)))
        val shape = frames(session.receive(PgFrontend.Describe('S', "s1")))
        assertEquals("tT", shape.joinToString("") { it.first.toString() })
        assertContentEquals(byteArrayOf(0, 1, 0, 0, 0, 20), shape[0].second)
        assertEquals(listOf("title" to 25), fields(shape[1].second))
        // a text parameter holding a backslash is the text, under the standard_conforming_strings the session reports
        session.receive(PgFrontend.Parse("s0", "SELECT \$1 AS p", intArrayOf(1043)))
        session.receive(PgFrontend.Bind("p0", "s0", intArrayOf(0), arrayOf("C:\\it's".encodeToByteArray()), intArrayOf()))
        assertEquals(listOf("C:\\it's"), cells(frames(session.receive(PgFrontend.Execute("p0", 0)))[0].second))
        // binary parameter in, binary result out, portal suspended at maxRows
        session.receive(PgFrontend.Parse("s2", "SELECT id, title FROM documents WHERE id >= \$1 ORDER BY id", intArrayOf(20)))
        session.receive(PgFrontend.Bind("p2", "s2", intArrayOf(1), arrayOf(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 2)), intArrayOf(1, 0)))
        val binaryShape = frames(session.receive(PgFrontend.Describe('P', "p2")))[0].second
        assertEquals(1, PgReader(binaryShape, 0, binaryShape.size).let { r -> r.int16(); r.cstring(); r.int32(); r.int16(); r.int32(); r.int16(); r.int32(); r.int16() }, "id is sent binary")
        val first = frames(session.receive(PgFrontend.Execute("p2", 1)))
        assertEquals("Ds", first.joinToString("") { it.first.toString() })
        val r = PgReader(first[0].second, 0, first[0].second.size)
        assertEquals(2, r.int16()); assertEquals(8, r.int32()); assertContentEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 2), r.bytes(8))
        val rest = frames(session.receive(PgFrontend.Execute("p2", 0)))
        assertEquals("DC", rest.joinToString("") { it.first.toString() })
        assertEquals("SELECT 1", strings(rest[1].second)[0])
        assertEquals("3", types(session.receive(PgFrontend.Close('P', "p2"))))
        // pgjdbc's connection check: the empty statement
        session.receive(PgFrontend.Parse("", "", intArrayOf()))
        session.receive(PgFrontend.Bind("", "", intArrayOf(), arrayOf(), intArrayOf()))
        assertEquals("I", types(session.receive(PgFrontend.Execute("", 0))))
        assertEquals("Z", types(session.receive(PgFrontend.Sync)))
        // an error skips to Sync
        session.receive(PgFrontend.Parse("", "SELECT nope FROM documents", intArrayOf()))
        assertEquals("E", types(session.receive(PgFrontend.Bind("", "", intArrayOf(), arrayOf(), intArrayOf()))))
        assertEquals("", types(session.receive(PgFrontend.Execute("", 0))))
        assertEquals("Z", types(session.receive(PgFrontend.Sync)))
        assertEquals("E", types(session.receive(PgFrontend.Bind("", "", intArrayOf(), arrayOf("1".encodeToByteArray()), intArrayOf()))), "a parameter count mismatch")
        session.receive(PgFrontend.Sync)
        assertEquals("", types(session.receive(PgFrontend.Flush)))
        session.receive(PgFrontend.Terminate)
        assertTrue(session.closed)
    }

    @Test fun psqlsCatalogQueriesRunOverTheDerivedPgCatalog() {
        seed()
        val dt = """SELECT n.nspname as "Schema",
  c.relname as "Name",
  CASE c.relkind WHEN 'r' THEN 'table' WHEN 'v' THEN 'view' WHEN 'm' THEN 'materialized view' WHEN 'i' THEN 'index' WHEN 'S' THEN 'sequence' WHEN 't' THEN 'TOAST table' WHEN 'f' THEN 'foreign table' WHEN 'p' THEN 'partitioned table' WHEN 'I' THEN 'partitioned index' END as "Type",
  pg_catalog.pg_get_userbyid(c.relowner) as "Owner"
FROM pg_catalog.pg_class c
     LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
     LEFT JOIN pg_catalog.pg_am am ON am.oid = c.relam
WHERE c.relkind IN ('r','p','')
      AND n.nspname <> 'pg_catalog'
      AND n.nspname !~ '^pg_toast'
      AND n.nspname <> 'information_schema'
  AND pg_catalog.pg_table_is_visible(c.oid)
ORDER BY 1,2;"""
        val listed = query(dt)
        assertEquals(listOf("Schema" to 25, "Name" to 25, "Type" to 25, "Owner" to 25), fields(listed[0].second))
        assertEquals(listOf(listOf("public", "documents", "table", "trikeshed")), listed.filter { it.first == 'D' }.map { cells(it.second) })
        assertEquals(listOf(listOf("DB.DBA.RDF_QUAD", "view"), listOf("documents", "table"), listOf("triples", "view")),
            rows("SELECT c.relname, CASE c.relkind WHEN 'r' THEN 'table' WHEN 'v' THEN 'view' END FROM pg_class c WHERE c.relkind IN ('r','p','v','m','S','f','') ORDER BY 1"))
        assertEquals(listOf(listOf("id", "bigint", "t"), listOf("title", "text", "f"), listOf("score", "double precision", "f"), listOf("embedding", "real[]", "f")),
            rows("SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod), a.attnotnull FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON a.attrelid = c.oid WHERE c.relname = 'documents' AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum"))
        assertEquals(listOf(listOf(surface.catalog.oid("documents").toString())), rows("SELECT c.oid FROM pg_catalog.pg_class c WHERE c.relname = 'documents'"))
        assertEquals(listOf(listOf("0"), listOf("2")), rows("SELECT relam FROM pg_class WHERE relname IN ('triples', 'documents') ORDER BY relam"), "a view has no access method")
        assertEquals(listOf("?column?" to 25), fields(query("SELECT NULL")[0].second), "an untyped NULL is text")
        assertEquals(listOf(listOf("int8", "pg_catalog")), rows("SELECT t.typname, n.nspname FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace WHERE t.oid = 20"))
        assertEquals(listOf(listOf("trikeshed", "UTF8")), rows("SELECT d.datname, pg_catalog.pg_encoding_to_char(d.encoding) FROM pg_catalog.pg_database d WHERE d.datname = current_database()"))
        assertEquals(listOf(listOf("heap")), rows("SELECT amname FROM pg_am"))
        assertEquals(listOf(listOf("documents", "BASE TABLE")), rows("SELECT table_name, table_type FROM information_schema.tables WHERE table_type = 'BASE TABLE'"))
    }
}
