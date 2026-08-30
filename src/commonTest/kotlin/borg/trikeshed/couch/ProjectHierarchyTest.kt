package borg.trikeshed.couch

import borg.trikeshed.job.CasStore
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.relaxfactory.RelaxTransport
import borg.trikeshed.relaxfactory.RequestFactoryProxy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The project heading: `projects/<id>/…` as a parsed grammar and a declared entity.
 *
 * Documents were already namespaced this way by the worktree gateway and the memory bridge, but
 * nothing declared a project, so there was no way to ask what projects exist or what belongs to
 * one — spec C7's "no `Project` entity". [Projects] is that declaration and [ProjectPath] is the
 * one place the grammar is parsed.
 */
class ProjectHierarchyTest {

    private fun node(): CouchDatabase {
        val cas = CasStore.inMemory()
        return CouchDatabase("trikeshed", CouchStoreFactory.casBacked(cas), cas)
    }

    // ── the grammar ───────────────────────────────────────────────

    @Test
    fun theGrammarNamesEveryKindOfDocumentUnderAHeading() {
        assertEquals(ProjectPath.Manifest("trikeshed"), ProjectPath.of("projects/trikeshed"))
        assertEquals(ProjectPath.Design("trikeshed", "forge"), ProjectPath.of("projects/trikeshed/_design/forge"))
        assertEquals(ProjectPath.Local("trikeshed", "ckpt"), ProjectPath.of("projects/trikeshed/_local/ckpt"))
        assertEquals(
            ProjectPath.Content("trikeshed", "src/commonMain/kotlin/X.kt"),
            ProjectPath.of("projects/trikeshed/src/commonMain/kotlin/X.kt"),
        )
        // round trip: every parse rebuilds the id it came from
        for (id in listOf(
            "projects/trikeshed",
            "projects/trikeshed/_design/forge",
            "projects/trikeshed/_local/ckpt",
            "projects/trikeshed/docs/index.html",
        )) assertEquals(id, ProjectPath.of(id)!!.id, "round trip lost $id")

        assertEquals("trikeshed", ProjectPath.projectOf("projects/trikeshed/docs/index.html"))
    }

    @Test
    fun thingsThatAreNotProjectPathsSaySo() {
        assertNull(ProjectPath.of("_design/forge"), "a database-level design doc is not in a project")
        assertNull(ProjectPath.of("vm-worlds/vm.a"), "another namespace is not a project")
        assertNull(ProjectPath.of("projects/"), "a prefix with no id names nothing")
        assertNull(ProjectPath.of("projects/trikeshed/_design"), "a reserved segment naming nothing")
        assertNull(ProjectPath.of("projects/_hidden/x"), "an id may not start with the couch underscore")
        assertTrue(!ProjectPath.isValidId(""))
        assertTrue(!ProjectPath.isValidId("a/b"))
        assertTrue(ProjectPath.isValidId("trikeshed"))
    }

    // ── the entity ────────────────────────────────────────────────

    @Test
    fun aHeadingKnowsWhatHangsUnderItAndKeepsItsKindsApart() = runTest {
        val db = node()
        val projects = Projects(db)
        assertEquals(emptyList(), projects.list())

        assertEquals(true, projects.put("trikeshed", mapOf("head" to "dcf437f3", "ipns" to "k51q"))["ok"])
        db.put("projects/trikeshed/docs/index.html", mapOf("contentType" to "text/html"), null)
        db.put("projects/trikeshed/src/X.kt", mapOf("contentType" to "text/x-kotlin"), null)
        db.put("projects/trikeshed/_design/forge", mapOf("views" to emptyMap<String, Any?>()), null)
        db.put("projects/other/README.md", mapOf("contentType" to "text/markdown"), null)
        db.put("vm-worlds/vm.a", mapOf("kind" to "vm-world"), null)

        assertEquals(listOf("trikeshed"), projects.list(), "only declared headings are listed")
        assertEquals(listOf("other"), projects.undeclared(), "a namespace in use but never declared")

        val manifest = assertNotNull(projects.get("trikeshed"))
        assertEquals("dcf437f3", manifest["head"])
        assertEquals("trikeshed", manifest["name"])

        // the heading's documents, and nothing from a neighbouring namespace
        val docs = projects.documents("trikeshed")
        assertEquals(3, docs.size, "expected the project's own docs only, got $docs")
        assertTrue(docs.none { it.startsWith("vm-worlds/") || it.startsWith("projects/other/") })
        assertEquals(listOf("projects/trikeshed/src/X.kt"), projects.documents("trikeshed", "src/"))

        val summary = projects.summary("trikeshed")
        assertEquals(true, summary["declared"])
        assertEquals(3, summary["doc_count"])
        assertEquals("projects/trikeshed/", summary["prefix"])
        assertEquals(false, projects.summary("other")["declared"])
    }

    @Test
    fun anInvalidHeadingIsRefusedRatherThanCreated() = runTest {
        val db = node()
        val projects = Projects(db)
        val bad = projects.put("has/slash")
        assertEquals("bad_request", bad["error"])
        assertTrue(projects.list().isEmpty())
        assertNull(db.docJson("projects/has/slash"))
    }

    @Test
    fun theManifestIsAnOrdinaryDocumentSoItRevisionsAndReplicates() = runTest {
        val db = node()
        val projects = Projects(db)
        val first = projects.put("trikeshed", mapOf("head" to "aaa"))
        val rev1 = first["rev"] as String
        assertNotNull(CouchDatabase.revToCid(rev1), "the manifest rev names no CAS blob")

        val second = projects.put("trikeshed", mapOf("head" to "bbb"))
        assertEquals(true, second["ok"])
        assertTrue(second["rev"] != rev1, "updating the manifest minted no new revision")
        assertEquals("bbb", projects.get("trikeshed")!!["head"])

        // and it is in _changes like anything else
        val changes = db.changes(since = 0L)
        assertTrue(
            CouchDatabase.asList(changes["results"])!!.any { (it as Map<*, *>)["id"] == "projects/trikeshed" },
            "the project heading never entered _changes",
        )
    }

    // ── through the envelope and the route ────────────────────────

    @Test
    fun aClientAddressesTheHierarchyThroughTheEnvelope() = runTest {
        val db = node()
        val proxy = RequestFactoryProxy(RelaxTransport.local(db))

        assertTrue(proxy.projectPut("trikeshed", mapOf("head" to "dcf437f3")).ok)
        db.put("projects/trikeshed/docs/index.html", mapOf("contentType" to "text/html"), null)
        db.put("projects/trikeshed/src/X.kt", mapOf("contentType" to "text/x-kotlin"), null)

        val one = proxy.project("trikeshed")
        assertTrue(one.ok, "project_get failed: $one")
        assertEquals(true, one.fields["declared"])
        assertEquals("dcf437f3", one.fields["head"])

        val all = proxy.projects()
        assertTrue(all.ok)
        assertEquals(listOf("trikeshed"), all.rows.map { it["id"] })

        // The heading is not one of its own documents: `projects/<id>` sits beside the namespace,
        // and `summary` already reports it. So this lists what hangs *under* the heading.
        val docs = proxy.projectDocs("trikeshed")
        assertEquals(2L, (docs.fields["total_rows"] as Number).toLong(), "the two content docs")
        assertEquals(setOf("content"), docs.rows.map { it["kind"] }.toSet())
        assertEquals(listOf("docs/index.html", "src/X.kt"), docs.rows.map { it["path"] })

        // narrowing to a sub-path is the "heading" use: what is under src/ ?
        val src = proxy.projectDocs("trikeshed", under = "src/")
        assertEquals(1L, (src.fields["total_rows"] as Number).toLong())
        assertEquals("src/X.kt", src.rows[0]["path"])

        // a project nobody has heard of refuses per-operation
        val missing = proxy.project("nope")
        assertTrue(!missing.ok && missing.error == "not_found", "expected a refusal, got $missing")
    }

    @Test
    fun theRouteServesTheSameHeadings() = runTest {
        val db = node()
        Projects(db).put("trikeshed", mapOf("head" to "aaa"))
        db.put("projects/trikeshed/docs/index.html", mapOf("contentType" to "text/html"), null)
        db.put("projects/undeclared/x.txt", mapOf("contentType" to "text/plain"), null)
        val router = CouchWireRouter(db, "projects/trikeshed/")

        @Suppress("UNCHECKED_CAST")
        suspend fun json(path: String): Map<String, Any?> =
            JsonSupport.parse(router.handle("GET", path, ByteArray(0))!!.bytes.decodeToString()) as Map<String, Any?>

        val listed = json("/trikeshed/_projects")
        val rows = CouchDatabase.asList(listed["rows"])!!.map { it as Map<*, *> }
        assertEquals(listOf("trikeshed", "undeclared"), rows.map { it["id"] })
        assertEquals(true, rows.first { it["id"] == "trikeshed" }["declared"])
        assertEquals(false, rows.first { it["id"] == "undeclared" }["declared"])

        val one = json("/trikeshed/_projects/trikeshed")
        assertEquals("aaa", one["head"])
        assertEquals(
            listOf("projects/trikeshed/docs/index.html"),
            CouchDatabase.asList(one["documents"])!!.map { it.toString() },
            "the heading lists what hangs under it, not itself",
        )
    }
}
