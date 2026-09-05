package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `/blackboard/sheet` — the harness's sheets. One fact (`?key=`) or one territory (`?prefix=`)
 * comes back as the grid-in-cell family CursorSheet/confixSheets already project for a couch
 * document on `/api/graal/sheet`: rows are (key, value), a container value is a `{sheet}` ref
 * to its own sheet, and the whole family arrives in one response.
 */
class BlackboardSheetRouteTest {

    private fun wire(bb: ConfixBlackboard) = BlackboardWire(bb, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Suppress("UNCHECKED_CAST")
    private fun family(body: String): List<Map<String, Any?>> = JsonSupport.parse(body) as List<Map<String, Any?>>

    @Test
    fun oneFactIsOneFamilyWithRefsForNestedContainers() = runTest {
        val bb = ConfixBlackboard.empty()
        bb.put("kanban/claim/card-1", mapOf("jobId" to "card-1", "status" to "Review", "answer" to mapOf("tokens" to 12, "ok" to true)), "test")
        val resp = wire(bb).route("GET", "/blackboard/sheet?key=kanban%2Fclaim%2Fcard-1", "")
        assertEquals(200, resp?.status)
        val sheets = family(resp!!.body)
        assertEquals(2, sheets.size, "root + the nested answer object")
        val root = sheets.first()
        assertEquals("kanban/claim/card-1", root["id"])
        assertEquals(null, root["parent"])
        val rows = root["rows"] as List<List<Any?>>
        assertEquals(listOf("jobId", "status", "answer"), rows.map { it[0] })
        assertEquals(mapOf("sheet" to "kanban/claim/card-1/answer"), rows[2][1], "a container value is a SheetRef cell")
        assertEquals("kanban/claim/card-1", sheets[1]["parent"])
    }

    @Test
    fun aScalarFactStillShowsARow() = runTest {
        val bb = ConfixBlackboard.empty()
        bb.put("pointcut/X/hits", 7, "test")
        val resp = wire(bb).route("GET", "/blackboard/sheet?key=pointcut%2FX%2Fhits", "")
        assertEquals(200, resp?.status)
        val rows = family(resp!!.body).single()["rows"] as List<List<Any?>>
        assertEquals("value", rows.single()[0])
        assertEquals(7, (rows.single()[1] as Number).toInt())
    }

    @Test
    fun aTerritoryIsOneSheetOfItsFacts() = runTest {
        val bb = ConfixBlackboard.empty()
        bb.put("kanban/claim/card-1", mapOf("status" to "Review"), "test")
        bb.put("kanban/claim/card-2", mapOf("status" to "Ready"), "test")
        bb.put("narsese/x", mapOf("expression" to "a --> b"), "test")
        val resp = wire(bb).route("GET", "/blackboard/sheet?prefix=kanban", "")
        assertEquals(200, resp?.status)
        val sheets = family(resp!!.body)
        val root = sheets.first()
        assertEquals("kanban", root["id"])
        val rows = root["rows"] as List<List<Any?>>
        assertEquals(listOf("claim/card-1", "claim/card-2"), rows.map { it[0] }, "keys under the prefix, prefix stripped, nothing from other territories")
        assertTrue(rows.all { (it[1] as Map<*, *>)["sheet"] != null }, "each fact is a drill-in ref")
        assertEquals(listOf("kanban/claim/card-1", "kanban/claim/card-2"), rows.map { (it[1] as Map<*, *>)["sheet"] }, "the ref is the fact's full key")
        assertEquals(3, sheets.size)
        assertTrue(sheets.drop(1).all { it["parent"] == "kanban" }, "each fact's root sheet hangs under the territory root")
    }

    @Test
    fun aLargeTerritoryProjectsInBoundedTime() = runTest {
        // One confix walk over a whole territory was quadratic (narsese: 1,038 facts took minutes).
        val bb = ConfixBlackboard.empty()
        repeat(1200) { i ->
            bb.put("narsese/curation/minted/$i", mapOf("event" to "minted", "expression" to "a$i --> b$i", "truth" to mapOf("f" to 0.9, "c" to 0.5)), "test")
        }
        val started = System.nanoTime()
        val resp = wire(bb).route("GET", "/blackboard/sheet?prefix=narsese&max=1024", "")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(200, resp?.status)
        val sheets = family(resp!!.body)
        assertTrue((sheets.first()["rows"] as List<*>).size < 1200, "a large territory is paged")
        assertTrue(sheets.size <= 256, "server caps the family: ${sheets.size}")
        assertTrue(sheets.sumOf { (it["rows"] as List<*>).size } <= 512, "row budget is request-wide")
        assertEquals(true, sheets.first()["truncated"])
        assertNotNull(sheets.first()["nextKey"])
        assertTrue(elapsedMs < 10_000, "territory projection took ${elapsedMs} ms")
    }

    @Test
    fun missingFactAndMissingSelectorAreRefused() = runTest {
        val w = wire(ConfixBlackboard.empty())
        assertEquals(404, w.route("GET", "/blackboard/sheet?key=nope", "")?.status)
        assertEquals(400, w.route("GET", "/blackboard/sheet", "")?.status)
        assertNotNull(w.route("GET", "/blackboard/sheet?prefix=empty", "")).let { assertEquals(200, it.status) }
    }
}
