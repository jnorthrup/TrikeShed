package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** One marker per run: firings fold in on (document), the count is inputs that moved. */
class LcncStaleMarkerTest {
    private fun firing(id: String, newCid: String, deleted: Boolean = false, seq: Long = 7) = mapOf(
        "runId" to "r1", "receiptKey" to "lcnc/run/r1", "receiptCid" to "sha256:receipt", "programKey" to "lcnc/program/corpus", "programCid" to "sha256:p",
        "project" to "notes", "id" to id, "oldCid" to "sha256:old-$id", "newCid" to newCid, "sequence" to "$seq", "deleted" to "$deleted",
    )

    @Test
    fun mergesOneRowPerDocumentAndCountsInputsNotFirings() {
        val first = LcncStaleMarker.merge(null, firing("a.md", "sha256:a2"), atMs = 10L)!!
        assertEquals(1, first["count"]); assertEquals("r1", first["runId"]); assertEquals("lcnc/run/r1", first["receiptKey"])
        val second = LcncStaleMarker.merge(first, firing("b.md", "sha256:b2"), atMs = 20L)!!
        assertEquals(2, second["count"]); assertEquals(20L, second["atMs"])
        assertEquals(listOf("a.md", "b.md"), (second["inputs"] as List<Map<*, *>>).map { it["id"] })
        // a.md moves again: the row is replaced, not doubled
        val third = LcncStaleMarker.merge(second, firing("a.md", "sha256:a3", deleted = true), atMs = 30L)!!
        assertEquals(2, third["count"])
        val a = (third["inputs"] as List<Map<*, *>>).first { it["id"] == "a.md" }
        assertEquals("sha256:a3", a["newCid"]); assertEquals(true, a["deleted"]); assertEquals(7L, a["sequence"])
        assertEquals("sha256:receipt", third["receiptCid"], "the receipt identity rides every merge")
    }

    @Test
    fun aFiringWithoutARunOrDocumentIsNothing() {
        assertNull(LcncStaleMarker.merge(null, mapOf("project" to "notes", "id" to "a.md"), 1L), "no runId")
        assertNull(LcncStaleMarker.merge(null, mapOf("runId" to "r1", "project" to "notes"), 1L), "no id")
        assertEquals("lcnc/stale/r1", LcncStaleMarker.key("r1"))
    }
}
