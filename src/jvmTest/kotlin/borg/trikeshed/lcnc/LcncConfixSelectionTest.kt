package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.kernel.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.parse.confix.Syntax
import kotlinx.coroutines.*
import kotlin.test.*

class LcncConfixSelectionTest {
    private val json = "{\"spans\":[{\"begin\":35,\"end\":42,\"quote\":\"é水🙂 \\\"x\\\"\",\"page\":12}],\"flag\":false}"
    private val cid get() = ContentId.of(json.encodeToByteArray()).value
    private fun rows() = LlvmConfixTest.oracle(Syntax.JSON, json.encodeToByteArray())

    @Test fun realNativeRowsSelectAfterGuestClosure(): Unit = runBlocking {
        val kernel = KernelElement(coroutineContext + Dispatchers.Default, KernelId("selection"))
        val scan = try {
            LlvmConfix.open(kernel, KernelId("scan"), LlvmConfixTest.profile(), LlvmScalarTest.files(LlvmConfixTest.bytes()))
                .scan(Syntax.JSON, json.encodeToByteArray())
        } finally { kernel.drain() }
        val selected = LcncConfixSelection.select(json, "spans.0", scan["rows"], scan["sourceCid"], scan["ok"], scan["format"])
        val value = selected["value"] as Map<*, *>
        assertEquals(35.0, (value["begin"] as Number).toDouble())
        assertEquals(42.0, (value["end"] as Number).toDouble())
        assertEquals(12.0, (value["page"] as Number).toDouble())
        assertEquals("é水🙂 \"x\"", value["quote"])
        val sheet = selected["sheet"] as Map<*, *>
        assertTrue(jsonOf(sheet).contains("35"))
        assertEquals("native", selected["geometry"])
        val root = LcncConfixSelection.select(json, "/", scan["rows"], cid, true, "JSON")
        val serialized = reify(jsonOf(root["value"])) as Map<*, *>
        assertEquals("é水🙂 \"x\"", ((serialized["spans"] as List<*>).single() as Map<*, *>)["quote"])
        assertEquals(false, LcncConfixSelection.select(json, "spans.-1", scan["rows"], cid, true, "JSON")["found"])
        assertEquals(false, LcncConfixSelection.select(json, "flag", scan["rows"], cid, true, "JSON")["value"])
        assertEquals(null, LcncConfixSelection.select(json, "absent", scan["rows"], cid, true, "JSON")["value"])
    }

    @Test fun scanFailureWrongFormatAndMismatchedSourceRefuseSelection() {
        for ((ok, format, identity) in s_[Triple(false, "JSON", cid), Triple(true, "YAML", cid),
            Triple(true, "JSON", ContentId.of("other".encodeToByteArray()).value)].view)
            assertFails { LcncConfixSelection.select(json, "spans.0", rows(), identity, ok, format) }
        assertFails { LcncConfixSelection.select(json, "spans.0", null, cid, true, "JSON") }
    }

    @Test fun geometryCannotCycleAliasEscapeOrOmitItsParentage() {
        val rows = rows()
        fun changed(index: Int, fields: Map<String, Any?>) = rows.mapIndexed { i, row -> if (i == index) row + fields else row }
        val invalid = s_[
            changed(0, _m["children" j _l[0]]), changed(0, _m["children" j _l[1, 1]]),
            changed(0, _m["children" j emptyList<Int>()]), changed(1, _m["depth" j 2]),
            changed(1, _m["close" j json.encodeToByteArray().size]), changed(1, _m["open" j 0]),
            changed(1, _m["tag" j "IoBytes"]), changed(1, _m["depth" j 1.5])]
        for (geometry in invalid.view) assertFails { LcncConfixSelection.select(json, "spans.0", geometry, cid, true, "JSON") }
    }

    @Test fun selectedNullAndScalarRemainVisibleWithoutRescanning() {
        val data = "{\"nil\":null,\"n\":-0.25}"
        val rows = LlvmConfixTest.oracle(Syntax.JSON, data.encodeToByteArray())
        val cid = ContentId.of(data.encodeToByteArray()).value
        val nil = LcncConfixSelection.select(data, "nil", rows, cid, true, "JSON")
        assertEquals(true, nil["found"]); assertNull(nil["value"])
        assertNotNull(nil["sheet"])
        assertEquals(-0.25, LcncConfixSelection.select(data, "n", rows, cid, true, "JSON")["value"])
    }
}
