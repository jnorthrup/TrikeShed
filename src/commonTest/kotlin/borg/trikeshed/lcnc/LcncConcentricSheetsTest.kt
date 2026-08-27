package borg.trikeshed.lcnc

import borg.trikeshed.forge.sheet.SheetColumn
import borg.trikeshed.forge.sheet.SheetRef
import borg.trikeshed.forge.sheet.SheetSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Concentric treesheets: nested sheets where drilling into a parent sheet
 * takes you to a focused subsheet. The family of sheets forms a hierarchy,
 * and each sheet can be a root (for display) or a member (for navigation).
 */
class LcncConcentricSheetsTest {

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
        val withRef = mockSheetSeedWithRef()
        val map = withRef.toLcncMap()
        val reconstructed = lcncMapToSheetSeed(map)
        assertNotNull(reconstructed)
        // The SheetRef cell should be reconstructed
        val referencedCell = reconstructed.rows[0][1]
        assertTrue(referencedCell is SheetRef, "cell should be a SheetRef after reconstruction")
        assertEquals("subsheet", (referencedCell as SheetRef).sheet)
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
            listOf("item1", SheetRef("subsheet")),
            listOf("item2", "plain data"),
        ),
    )
}
