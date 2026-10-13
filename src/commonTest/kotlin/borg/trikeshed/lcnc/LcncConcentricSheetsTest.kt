package borg.trikeshed.lcnc

import borg.trikeshed.forge.sheet.SheetColumn
import borg.trikeshed.forge.sheet.SheetRef
import borg.trikeshed.forge.sheet.SheetSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * Concentric treesheets: nested sheets where drilling into a parent sheet
 * takes you to a focused subsheet. The family of sheets forms a hierarchy,
 * and each sheet can be a root (for display) or a member (for navigation).
 */
class LcncConcentricSheetsTest {

    @Test
    fun retainedIdentityIsNotAnEmptySheet() = runTest {
        val reference = mapOf("sheet" to "document/receipt", "href" to "/api/documents?cid=receipt&view=sheet")
        for ((type, runner) in listOf(
            "sheet.count" to LcncSheetNodes.countNode(),
            "sheet.columns" to LcncSheetNodes.columnsNode(),
            "sheet.cell" to LcncSheetNodes.cellNode(),
            "sheet.filter" to LcncSheetNodes.filterNode(),
            "sheet.flatten" to LcncSheetNodes.flattenNode(),
        )) {
            val failure = assertFailsWith<IllegalArgumentException> {
                runner.run(LcncNode("test", type), mapOf("sheet" to reference))
            }
            assertTrue(failure.message!!.contains("document.sheet"))
        }
    }

    @Test
    fun sheetOperatorsPreserveWindowProvenance() = runTest {
        val original = mockSheetSeed().toLcncMap() + mapOf("parent" to "document/receipt",
            "offset" to 32, "sourceRows" to 100, "truncated" to true, "limit" to "row_window")
        val filtered = LcncSheetNodes.filterNode().run(
            LcncNode("filter", "sheet.filter", mapOf("columnName" to "col1", "columnValue" to "value2")),
            mapOf("sheet" to original))
        val sheet = filtered["sheet"] as Map<*, *>
        for (key in listOf("parent", "offset", "sourceRows", "truncated", "limit", "columns"))
            assertEquals(original[key], sheet[key])
        assertEquals(listOf(listOf("value2", 99)), sheet["rows"])
        val flat = LcncSheetNodes.flattenNode().run(LcncNode("flatten", "sheet.flatten"), filtered)
        assertEquals(filtered, flat)
        assertEquals(1, LcncSheetNodes.countNode().run(LcncNode("count", "sheet.count"), flat)["count"])
        assertEquals(2, LcncSheetNodes.countNode().run(LcncNode("count", "sheet.count"), mapOf("sheet" to original))["count"])
    }

    @Test
    fun sheetToLcncMapPreservesAllData() {
        val sheet = mockSheetSeed()
        val map = sheet.toLcncMap()
        assertEquals(sheet.id, map["id"])
        assertEquals(sheet.title, map["title"])
        @Suppress("UNCHECKED_CAST")
        val cols = map["columns"] as List<Map<String, String>>
        assertEquals("col1", cols[0]["name"])
        @Suppress("UNCHECKED_CAST")
        val rows = map["rows"] as List<List<Any?>>
        assertEquals("value1", rows[0][0])
    }

    @Test
    fun lcncMapToSheetSeedReconstructsTheSheet() {
        val original = mockSheetSeed()
        val map = original.toLcncMap()
        val reconstructed = lcncMapToSheetSeed(map)
        assertNotNull(reconstructed)
        assertEquals(original.id, reconstructed.id)
        assertEquals(original.title, reconstructed.title)
        assertEquals(original.rows.size, reconstructed.rows.size)
        assertEquals(original.columns.size, reconstructed.columns.size)
    }

    @Test
    fun sheetWithRefRoundTripsCorrectly() {
        val withRef = mockSheetSeedWithRef().copy(truncated = true, limit = "row_limit")
        val map = withRef.toLcncMap()
        val reconstructed = lcncMapToSheetSeed(map)
        assertNotNull(reconstructed)
        // The SheetRef cell should be reconstructed
        val referencedCell = reconstructed.rows[0][1]
        assertTrue(referencedCell is SheetRef, "cell should be a SheetRef after reconstruction")
        assertEquals("subsheet", (referencedCell as SheetRef).sheet)
        assertEquals("/api/documents?cid=fixture&view=sheet&cursor=tokens", referencedCell.href)
        assertEquals(withRef, reconstructed)
        assertEquals(withRef.toMap(), map)
    }

    private fun mockSheetSeed() = SheetSeed(
        id = "test",
        title = "Test Sheet",
        columns = listOf(
            SheetColumn("col1", "IoString"),
            SheetColumn("col2", "IoInt"),
        ),
        rows = listOf(
            listOf("value1", 42),
            listOf("value2", 99),
        ),
    )

    private fun mockSheetSeedWithRef() = SheetSeed(
        id = "test-ref",
        title = "Test With Refs",
        columns = listOf(
            SheetColumn("item", "IoString"),
            SheetColumn("details", "Any"),
        ),
        rows = listOf(
            listOf("item1", SheetRef("subsheet", "/api/documents?cid=fixture&view=sheet&cursor=tokens")),
            listOf("item2", "plain data"),
        ),
    )
}
